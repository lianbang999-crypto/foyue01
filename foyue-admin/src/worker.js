/**
 * 佛悦全站数据管理后台（foyue.org/admin，P3 三期一次建齐，2026-08-22）
 *
 * 设计（FEATURE-PLAN §42/§43）：
 * - 只读走多库 D1 直连（STATS/AUTH/LOOKA/SHOP），一次请求内并发查完
 * - 写操作四件套（补开 Pro / 生成码 / 绑定订单 / 封禁），每笔记 admin_audit
 * - 鉴权：FOYUE_ADMIN_KEY 强口令 + 失败限流（用户决定暂不开 CF Access，
 *   等有稳定用户量再加 —— 到时只需在 Zero Trust 面板加一条策略，本代码不用动）
 * - 🔒 红线：任何接口都不返回用户内容（笔记/日记/日程正文），只有计数与元数据
 */

const J = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
  });

const DAY_MS = 24 * 3600_000;
/** 北京时区某天的 'YYYY-MM-DD' */
const bjDay = (ms = Date.now()) => new Date(ms + 8 * 3600_000).toISOString().slice(0, 10);
/** 北京日零点的毫秒时间戳 */
const bjDayStart = day => Date.parse(day + 'T00:00:00+08:00');

// ---------- 鉴权：口令 + 失败限流（内存版，isolate 级足够挡爆破） ----------
const loginFails = new Map();   // ip -> {n, until}

function authed(request, env) {
  const key = (request.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
  return env.FOYUE_ADMIN_KEY && key === env.FOYUE_ADMIN_KEY;
}

function failGate(ip) {
  const f = loginFails.get(ip);
  return !(f && f.n >= 8 && Date.now() < f.until);
}

function markFail(ip) {
  const f = loginFails.get(ip) || { n: 0, until: 0 };
  f.n++; f.until = Date.now() + 3600_000;
  loginFails.set(ip, f);
}

async function audit(env, action, target, detail) {
  await env.STATS.prepare(
    'INSERT INTO admin_audit (actor, action, target, detail, ts) VALUES (?1, ?2, ?3, ?4, ?5)'
  ).bind('admin', action, String(target ?? ''), JSON.stringify(detail ?? {}).slice(0, 512), Date.now()).run();
}

// ---------- 聚合查询 ----------

/** 某天某事件的次数（当天走明细实时算，历史天走 daily_stats） */
async function dayCnt(env, day, kind) {
  if (day === bjDay()) {
    const a = bjDayStart(day);
    const r = await env.STATS.prepare(
      "SELECT COUNT(*) c FROM events WHERE site='looka' AND kind=?1 AND ts>=?2"
    ).bind(kind, a).first();
    return r?.c || 0;
  }
  const r = await env.STATS.prepare(
    "SELECT cnt FROM daily_stats WHERE site='looka' AND day=?1 AND kind=?2"
  ).bind(day, kind).first();
  return r?.cnt || 0;
}

async function rangeCnt(env, kind, days) {
  const r = await env.STATS.prepare(
    "SELECT COUNT(*) c FROM events WHERE site='looka' AND kind=?1 AND ts>=?2"
  ).bind(kind, Date.now() - days * DAY_MS).first();
  return r?.c || 0;
}

async function overview(env) {
  const today = bjDay(), yest = bjDay(Date.now() - DAY_MS);
  const kinds = ['register', 'app_open', 'apk_download', 'pay', 'ai_chat', 'crash', 'landing_view'];
  const out = { today: {}, yest: {}, d7: {} };
  await Promise.all(kinds.map(async k => {
    out.today[k] = await dayCnt(env, today, k);
    out.yest[k] = await dayCnt(env, yest, k);
    out.d7[k] = await rangeCnt(env, k, 7);
  }));

  // 30 日转化漏斗
  const d30 = {};
  await Promise.all(['landing_view', 'apk_download', 'register', 'pay'].map(async k => {
    d30[k] = await rangeCnt(env, k, 30);
  }));

  // 钱（本月）：pay_orders 金额 + 订阅中 + 7 日内到期
  const monthStart = bjDayStart(bjDay().slice(0, 7) + '-01');
  const [income, active, expiring, unclaimed] = await Promise.all([
    env.LOOKA.prepare('SELECT SUM(CAST(amount AS REAL)) s FROM pay_orders WHERE handled_at >= ?1 AND user_id IS NOT NULL')
      .bind(monthStart).first(),
    env.LOOKA.prepare("SELECT COUNT(*) c FROM plans WHERE plan='pro' AND expires_at > ?1").bind(Date.now()).first(),
    env.LOOKA.prepare("SELECT COUNT(*) c FROM plans WHERE plan='pro' AND expires_at BETWEEN ?1 AND ?2")
      .bind(Date.now(), Date.now() + 7 * DAY_MS).first(),
    env.LOOKA.prepare('SELECT COUNT(*) c FROM pay_orders WHERE user_id IS NULL').first()
  ]);

  // 账号总量（全站共享的 users）
  const totalUsers = await env.AUTH.prepare('SELECT COUNT(*) c FROM users').first();

  return {
    ...out, funnel30: d30,
    money: { income_month: income?.s || 0, subs_active: active?.c || 0, expiring_7d: expiring?.c || 0 },
    attention: { unclaimed_orders: unclaimed?.c || 0 },
    total_users: totalUsers?.c || 0
  };
}

async function trend(env, kind, days) {
  const rows = await env.STATS.prepare(
    "SELECT day, cnt, uniq FROM daily_stats WHERE site='looka' AND kind=?1 AND day >= ?2 ORDER BY day"
  ).bind(kind, bjDay(Date.now() - days * DAY_MS)).all();
  return rows.results || [];
}

async function listUsers(env, q, page) {
  // 🔒 只返回元数据：账号（脱敏显示由前端做）/时间/来源/活跃。永不查询内容表。
  const off = (page - 1) * 30;
  const like = q ? `%${q}%` : '%';
  const rows = await env.AUTH.prepare(
    `SELECT id, account, kind, created_at, last_seen_at, reg_site, banned_at
     FROM users WHERE account LIKE ?1 ORDER BY id DESC LIMIT 30 OFFSET ?2`
  ).bind(like, off).all();
  const users = rows.results || [];
  // 补订阅态（looka-db）
  const plans = new Map();
  if (users.length) {
    const ids = users.map(u => u.id).join(',');
    const pr = await env.LOOKA.prepare(
      `SELECT user_id, plan, expires_at FROM plans WHERE user_id IN (${ids})`
    ).all();
    for (const p of pr.results || []) plans.set(p.user_id, p);
  }
  return users.map(u => ({
    ...u,
    plan: (() => { const p = plans.get(u.id); return p && p.plan === 'pro' && p.expires_at > Date.now() ? 'pro' : 'free'; })(),
    plan_expiry: plans.get(u.id)?.expires_at || 0
  }));
}

async function listSubs(env) {
  const [orders, unclaimed, expiring] = await Promise.all([
    env.LOOKA.prepare('SELECT channel, order_no, user_id, amount, handled_at FROM pay_orders ORDER BY handled_at DESC LIMIT 50').all(),
    env.LOOKA.prepare('SELECT channel, order_no, amount, handled_at, raw FROM pay_orders WHERE user_id IS NULL ORDER BY handled_at DESC LIMIT 20').all(),
    env.LOOKA.prepare("SELECT user_id, expires_at FROM plans WHERE plan='pro' AND expires_at BETWEEN ?1 AND ?2 ORDER BY expires_at LIMIT 20")
      .bind(Date.now(), Date.now() + 7 * DAY_MS).all()
  ]);
  return { orders: orders.results || [], unclaimed: unclaimed.results || [], expiring: expiring.results || [] };
}

async function health(env) {
  const day = Date.now() - DAY_MS;
  const [pfail, aiOk, crashes, topCrash] = await Promise.all([
    env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='premium_fail' AND ts>?1").bind(day).first(),
    env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='ai_chat' AND ts>?1").bind(day).first(),
    env.LOOKA.prepare('SELECT COUNT(*) c FROM crashes WHERE created_at>?1').bind(day).first(),
    env.LOOKA.prepare('SELECT ver, COUNT(*) c FROM crashes WHERE created_at>?1 GROUP BY ver ORDER BY c DESC LIMIT 5').bind(day).all()
  ]);
  return {
    premium_fail_24h: pfail?.c || 0, ai_chat_24h: aiOk?.c || 0,
    crashes_24h: crashes?.c || 0, crash_by_ver: topCrash.results || []
  };
}

async function sites(env) {
  // 各子站对比：looka 有埋点；其余先给可直接查到的核心量（该站接入埋点后自动细化）
  const [lookaReg, users, shopOrders, shopMsgs] = await Promise.all([
    env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE site='looka' AND kind='register' AND ts>?1")
      .bind(Date.now() - 30 * DAY_MS).first(),
    env.AUTH.prepare('SELECT COUNT(*) c FROM users').first(),
    env.SHOP.prepare('SELECT COUNT(*) c FROM orders').first().catch(() => null),
    env.SHOP.prepare('SELECT COUNT(*) c FROM cs_msgs').first().catch(() => null)
  ]);
  return {
    accounts_total: users?.c || 0,
    looka: { register_30d: lookaReg?.c || 0 },
    shop: { orders_total: shopOrders?.c ?? '未接入', cs_msgs: shopMsgs?.c ?? '—' },
    note: '自知录/播经台/文钞接入埋点后自动出现在这里'
  };
}

// ---------- 写操作（每笔审计） ----------

async function grantPro(env, userId, days) {
  const cur = await env.LOOKA.prepare('SELECT * FROM plans WHERE user_id=?1').bind(userId).first();
  const base = cur && cur.plan === 'pro' && cur.expires_at > Date.now() ? cur.expires_at : Date.now();
  const exp = base + days * DAY_MS;
  await env.LOOKA.prepare(
    `INSERT INTO plans (user_id, plan, expires_at) VALUES (?1,'pro',?2)
     ON CONFLICT(user_id) DO UPDATE SET plan='pro', expires_at=?2`
  ).bind(userId, exp).run();
  return exp;
}

// ---------- 路由 ----------
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const p = url.pathname;
    const ip = request.headers.get('cf-connecting-ip') || '0';

    // 静态资源（看板页）交给 assets；API 走下面。
    // 2026-08-22 修 Error 1101：assets 此前没声明 binding，env.ASSETS 为 undefined，
    // 任何未被 assets 直出的路径（如大写 /ADMIN）都会抛 TypeError。
    if (!p.startsWith('/admin/api/')) {
      // 大小写兜底：/ADMIN、/Admin → /admin/
      if (p.toLowerCase().startsWith('/admin') && p !== p.toLowerCase()) {
        return Response.redirect(url.origin + '/admin/', 302);
      }
      if (env.ASSETS) return env.ASSETS.fetch(request);
      return new Response('admin assets unavailable', { status: 500 });
    }

    // 登录（校验口令；失败限流）
    if (p === '/admin/api/login' && request.method === 'POST') {
      if (!failGate(ip)) return J({ error: '尝试次数过多，一小时后再试' }, 429);
      const b = await request.json().catch(() => ({}));
      if (env.FOYUE_ADMIN_KEY && String(b.key || '') === env.FOYUE_ADMIN_KEY) {
        return J({ ok: true });
      }
      markFail(ip);
      return J({ error: '口令不正确' }, 401);
    }

    if (!authed(request, env)) return J({ error: '未授权' }, 401);

    try {
      if (request.method === 'GET') {
        if (p === '/admin/api/overview') return J(await overview(env));
        if (p === '/admin/api/trend') {
          return J(await trend(env, url.searchParams.get('kind') || 'register',
            Math.min(Number(url.searchParams.get('days') || 30), 90)));
        }
        if (p === '/admin/api/users') {
          return J(await listUsers(env, (url.searchParams.get('q') || '').trim(),
            Math.max(1, Number(url.searchParams.get('page') || 1))));
        }
        if (p === '/admin/api/subs') return J(await listSubs(env));
        if (p === '/admin/api/health') return J(await health(env));
        if (p === '/admin/api/sites') return J(await sites(env));
        if (p === '/admin/api/audit') {
          const rows = await env.STATS.prepare('SELECT * FROM admin_audit ORDER BY id DESC LIMIT 50').all();
          return J(rows.results || []);
        }
      }

      if (request.method === 'POST') {
        const b = await request.json().catch(() => ({}));

        // 补开 Pro（客服兜底）
        if (p === '/admin/api/grant') {
          const uid = Number(b.user_id), days = Math.min(Math.max(Number(b.days || 31), 1), 3660);
          if (!uid) return J({ error: '缺 user_id' }, 400);
          const exp = await grantPro(env, uid, days);
          await audit(env, 'grant_pro', uid, { days, exp });
          return J({ ok: true, user_id: uid, expires_at: exp });
        }

        // 手动绑定未认领订单
        if (p === '/admin/api/bind-order') {
          const no = String(b.order_no || ''), uid = Number(b.user_id);
          if (!no || !uid) return J({ error: '缺 order_no / user_id' }, 400);
          const take = await env.LOOKA.prepare(
            "UPDATE pay_orders SET user_id=?1 WHERE channel='afdian' AND order_no=?2 AND user_id IS NULL"
          ).bind(uid, no).run();
          if (!take.meta.changes) return J({ error: '订单不存在或已被认领' }, 409);
          const raw = await env.LOOKA.prepare("SELECT raw FROM pay_orders WHERE order_no=?1").bind(no).first();
          const mon = Number(JSON.parse(raw?.raw || '{}')?.month || 1);
          const exp = await grantPro(env, uid, mon >= 12 ? 366 : mon * 31);
          await audit(env, 'bind_order', no, { user_id: uid, exp });
          return J({ ok: true, user_id: uid, expires_at: exp });
        }

        // 生成兑换码（订阅天数码 / 鹿角码，直写 looka-db codes 表）
        if (p === '/admin/api/gencode') {
          const count = Math.min(Math.max(Number(b.count || 1), 1), 100);
          const out = [];
          const rand = n => [...crypto.getRandomValues(new Uint8Array(n))]
            .map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase();
          if (String(b.type || '') === 'antler') {
            const amount = Math.min(Math.max(Number(b.amount || 150), 1), 100000);
            for (let i = 0; i < count; i++) {
              const code = 'ANTLER-' + rand(2) + '-' + rand(2);
              await env.LOOKA.prepare(
                "INSERT INTO codes (code, plan, days, kind, amount, created_at) VALUES (?1,'free',0,'antler',?2,?3)"
              ).bind(code, amount, Date.now()).run();
              out.push(code);
            }
          } else {
            const days = Math.min(Math.max(Number(b.days || 31), 1), 3660);
            for (let i = 0; i < count; i++) {
              const code = 'LOOKA-' + rand(2) + '-' + rand(2);
              await env.LOOKA.prepare(
                "INSERT INTO codes (code, plan, days, kind, amount, created_at) VALUES (?1,'pro',?2,'plan',0,?3)"
              ).bind(code, days, Date.now()).run();
              out.push(code);
            }
          }
          await audit(env, 'gencode', b.type || 'plan', { count, days: b.days, amount: b.amount });
          return J({ ok: true, codes: out });
        }

        // 封禁 / 解封
        if (p === '/admin/api/ban') {
          const uid = Number(b.user_id);
          if (!uid) return J({ error: '缺 user_id' }, 400);
          const v = b.unban ? 0 : Date.now();
          await env.AUTH.prepare('UPDATE users SET banned_at=?1 WHERE id=?2').bind(v, uid).run();
          if (!b.unban) {
            // 顺带踢掉所有会话（looka 侧；自知录会话表同库不同表，暂只踢 looka）
            await env.LOOKA.prepare('DELETE FROM sessions WHERE user_id=?1').bind(uid).run().catch(() => {});
          }
          await audit(env, b.unban ? 'unban' : 'ban', uid, {});
          return J({ ok: true });
        }
      }
      return J({ error: 'not found' }, 404);
    } catch (e) {
      console.log('admin error', p, String(e));
      return J({ error: '查询失败：' + String(e).slice(0, 200) }, 500);
    }
  }
};

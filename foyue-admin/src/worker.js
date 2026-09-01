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

/* ============================================================
   四站运营合并（2026-08-30）
   各子站认同一枚口令（其 Worker Secret ADMIN_TOKEN = 本后台的 FOYUE_ADMIN_KEY），
   本后台以服务端身份取用，浏览器只跟本域说话，不再逐站配 CORS。
   注：举报处理必须看到被举报的那一条正文才能判断，属红线（不返回用户内容）的
   唯一例外，且仅限「已公开到广场且被举报」的内容，不涉及任何人的私人记录。
   ============================================================ */
const SUB = {
  wenchao: ['SVC_WENCHAO', 'https://wenchao.foyue.org'],
  game:    ['SVC_GAME',    'https://game.foyue.org'],
  zhi:     ['SVC_ZHI',     'https://zhi.foyue.org'],
};
/** 经 Service Binding 调子站；未绑定时回退公网 fetch */
async function subGet(env, site, path, body) {
  const [bindName, origin] = SUB[site] || [];
  if (!origin) return { __err: '未知站点' };
  const req = new Request(origin + path, {
    method: body ? 'POST' : 'GET',
    headers: {
      Authorization: 'Bearer ' + (env.FOYUE_ADMIN_KEY || ''),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 8000);
  try {
    const svc = env[bindName];
    const r = svc ? await svc.fetch(req) : await fetch(req, { signal: ac.signal });
    if (!r.ok) return { __err: r.status === 401 ? '该站未认这枚口令' : 'HTTP ' + r.status };
    return await r.json();
  } catch (e) {
    return { __err: e.name === 'AbortError' ? '该站响应超时' : '该站连不上：' + String(e).slice(0, 60) };
  } finally { clearTimeout(timer); }
}

/** 今日待办：跨四站聚合「需要人处理」的事，无事即无事 */
async function todoAll(env, request) {
  const [bo, wc, zhi] = await Promise.all([
    (async () => {
      try {
        const r = await env.BOJING.prepare(
          "SELECT COUNT(*) n FROM reports WHERE status = 'open'").first();
        // 问道被标「答偏了」且还没看过的。ask_feedback 是 2026-08-31 才加的表，
        // 老库里可能还没有，读不到就当零，不要因此把整个待办页拖垮。
        let askDown = 0;
        try {
          const a = await env.BOJING.prepare(
            "SELECT COUNT(*) n FROM ask_feedback WHERE vote='down' AND handled=0").first();
          askDown = a?.n || 0;
        } catch { /* 表还没建 */ }
        return { open: r?.n || 0, askDown };
      } catch (e) { return { __err: '播经台库读取失败' }; }
    })(),
    subGet(env, 'wenchao', '/api/admin/stat'),
    subGet(env, 'zhi', '/api/admin/stat'),
  ]);
  const items = [];
  if (zhi.pendingReports) items.push({ site: 'zhi', label: '自知录 · 待处理举报', note: '公开内容，宜尽快处理', n: zhi.pendingReports });
  if (bo.open) items.push({ site: 'bojing', label: '播经台 · 待处理报错', note: '同修提交的纠错', n: bo.open });
  if (bo.askDown) items.push({ site: 'bojing', label: '问道 · 答偏了的反馈', note: '调检索参数的题源', n: bo.askDown });
  if (wc.pendingCorrections) items.push({ site: 'wenchao', label: '文钞 · 待更正反馈', note: '答案被标为需更正', n: wc.pendingCorrections });
  const errs = [];
  if (bo.__err) errs.push('播经台：' + bo.__err);
  if (wc.__err) errs.push('文钞：' + wc.__err);
  if (zhi.__err) errs.push('自知录：' + zhi.__err);
  return { items, total: items.reduce((s, i) => s + i.n, 0), errs };
}

/** 各站运营明细与处理动作 */
async function opsFetch(env, request, site, kind) {
  if (site === 'bojing') {
    if (kind === 'reports') {
      const { results } = await env.BOJING.prepare(
        'SELECT id,site,kind,target,text,contact,status,ts FROM reports ORDER BY id DESC LIMIT 200').all();
      const open = (await env.BOJING.prepare("SELECT COUNT(*) n FROM reports WHERE status='open'").first())?.n || 0;
      return { items: results, openCount: open };
    }
    if (kind === 'comments') {
      const { results } = await env.BOJING.prepare(
        'SELECT c.id,c.dev,c.name,c.text,c.ts,(b.dev IS NOT NULL) banned FROM comments c '
        + 'LEFT JOIN banned b ON b.dev = c.dev ORDER BY c.id DESC LIMIT 200').all();
      return { items: results };
    }
    /* 问道回答的赞踩。默认只列「踩」—— 赞看着舒服，但没什么可做的；
       踩指出的是检索没召回、或模型答偏了的问题，是下一轮调检索参数的题源。
       连问题与回答一起取：只看一个赞踩，事后根本不知道那次答的是什么，无从改起。
       verify 是当次的引用自检结果，与用户的判断并排看，最容易找出短板。 */
    if (kind === 'askfb') {
      try {
        const want = new URL(request.url).searchParams.get('vote') || 'down';
        const sql = 'SELECT id,ts,vote,q,a,verify,handled FROM ask_feedback'
          + (want === 'all' ? '' : ' WHERE vote = ?1') + ' ORDER BY id DESC LIMIT 100';
        const st = env.BOJING.prepare(sql);
        const { results } = await (want === 'all' ? st : st.bind(want)).all();
        return { items: results };
      } catch { return { items: [], note: 'ask_feedback 表尚未建立' }; }
    }
    const total = (await env.BOJING.prepare('SELECT COUNT(*) n FROM comments').first())?.n || 0;
    const today = (await env.BOJING.prepare('SELECT COUNT(*) n FROM comments WHERE ts >= ?1')
      .bind(Date.now() - ((Date.now() + 8 * 3600_000) % DAY_MS)).first())?.n || 0;
    const banned = (await env.BOJING.prepare('SELECT COUNT(*) n FROM banned').first())?.n || 0;
    const open = (await env.BOJING.prepare("SELECT COUNT(*) n FROM reports WHERE status='open'").first())?.n || 0;
    let askUp = 0, askDown = 0, askPending = 0;
    try {
      const a = await env.BOJING.prepare(
        "SELECT SUM(vote='up') up, SUM(vote='down') down, "
        + "SUM(vote='down' AND handled=0) pending FROM ask_feedback").first();
      askUp = a?.up || 0; askDown = a?.down || 0; askPending = a?.pending || 0;
    } catch { /* 表还没建：三个数留零，页面照常出 */ }
    return { total, today, banned, openReports: open, askUp, askDown, askPending };
  }
  if (site === 'wenchao') return subGet(env, 'wenchao', kind === 'feedback' ? '/api/admin/feedback' : '/api/admin/stat');
  if (site === 'game') return subGet(env, 'game', '/api/admin/app-stat');
  if (site === 'zhi') {
    if (kind === 'reports') return subGet(env, 'zhi', '/api/admin/reports');
    if (kind === 'plaza') return subGet(env, 'zhi', '/api/admin/plaza');
    return subGet(env, 'zhi', '/api/admin/stat');
  }
  return { error: '未知站点' };
}

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
    // §80：premium_fail 分支已下线且无人再发，这里永远 0 —— 换成真实埋点 ai_fail
    env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='ai_fail' AND ts>?1").bind(day).first(),
    env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='ai_chat' AND ts>?1").bind(day).first(),
    env.LOOKA.prepare('SELECT COUNT(*) c FROM crashes WHERE created_at>?1').bind(day).first(),
    env.LOOKA.prepare('SELECT ver, COUNT(*) c FROM crashes WHERE created_at>?1 GROUP BY ver ORDER BY c DESC LIMIT 5').bind(day).all()
  ]);
  return {
    ai_fail_24h: pfail?.c || 0, ai_chat_24h: aiOk?.c || 0,
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

// ---------- v2：站点详情 / 用户画像 / 留存（2026-08-22 用户要求"看完整数据、了解用户"）----------

/** 允许上报的站点与事件（信标白名单，防垃圾灌库） */
const BEACON_SITES = ['foyue', 'wenchao', 'game', 'zhi', 'shop', 'bojing'];
const beaconHits = new Map();   // ip -> {n, win}

async function siteDetail(env, site, days) {
  const from = Date.now() - days * DAY_MS;
  const [kinds, ua, ref, hourly, trendRows] = await Promise.all([
    env.STATS.prepare('SELECT kind, COUNT(*) c FROM events WHERE site=?1 AND ts>?2 GROUP BY kind ORDER BY c DESC')
      .bind(site, from).all(),
    env.STATS.prepare("SELECT ua_class, COUNT(*) c FROM events WHERE site=?1 AND ts>?2 AND ua_class!='' GROUP BY ua_class")
      .bind(site, from).all(),
    env.STATS.prepare("SELECT ref, COUNT(*) c FROM events WHERE site=?1 AND ts>?2 AND ref!='' GROUP BY ref ORDER BY c DESC LIMIT 10")
      .bind(site, from).all(),
    // 小时分布（北京时间）：用户什么时候在用
    env.STATS.prepare(
      "SELECT CAST(strftime('%H', datetime(ts/1000,'unixepoch','+8 hours')) AS INTEGER) h, COUNT(*) c FROM events WHERE site=?1 AND ts>?2 GROUP BY h"
    ).bind(site, from).all(),
    // 14 天趋势（当天实时 + 历史汇总）
    env.STATS.prepare(
      "SELECT date(ts/1000,'unixepoch','+8 hours') d, COUNT(*) c, COUNT(DISTINCT COALESCE(CAST(user_id AS TEXT), ip_hash, 'x')) u FROM events WHERE site=?1 AND ts>?2 GROUP BY d ORDER BY d"
    ).bind(site, Date.now() - 14 * DAY_MS).all()
  ]);
  const hours = Array(24).fill(0);
  for (const r of hourly.results || []) hours[r.h] = r.c;
  return {
    kinds: kinds.results || [], ua: ua.results || [], ref: ref.results || [],
    hourly: hours, trend14: trendRows.results || []
  };
}

/** 用户画像钻取：🔒 只有元数据与事件流水（kind/时间/设备），永不含内容 */
async function userDetail(env, uid) {
  const [u, plan, ant, evs, firstEv] = await Promise.all([
    env.AUTH.prepare('SELECT id, account, kind, created_at, last_seen_at, reg_site, banned_at FROM users WHERE id=?1').bind(uid).first(),
    env.LOOKA.prepare('SELECT plan, expires_at FROM plans WHERE user_id=?1').bind(uid).first(),
    env.LOOKA.prepare('SELECT granted, paid FROM antler_balance WHERE user_id=?1').bind(uid).first(),
    env.STATS.prepare('SELECT site, kind, ua_class, meta, ts FROM events WHERE user_id=?1 ORDER BY id DESC LIMIT 60').bind(uid).all(),
    env.STATS.prepare('SELECT MIN(ts) t FROM events WHERE user_id=?1').bind(uid).first()
  ]);
  if (!u) return null;
  // 活跃天数（近 30 日有几天出现过）—— "使用习惯"最直接的一个数
  const activeDays = await env.STATS.prepare(
    "SELECT COUNT(DISTINCT date(ts/1000,'unixepoch','+8 hours')) c FROM events WHERE user_id=?1 AND ts>?2"
  ).bind(uid, Date.now() - 30 * DAY_MS).first();
  return {
    user: u,
    plan: plan && plan.plan === 'pro' && plan.expires_at > Date.now() ? { plan: 'pro', expiry: plan.expires_at } : { plan: 'free' },
    antler: ant || { granted: 0, paid: 0 },
    active_days_30: activeDays?.c || 0,
    first_seen: firstEv?.t || 0,
    events: evs.results || []
  };
}

/** 留存：注册后 1 天 / 7 天还回来的比例（按 last_seen 粗算 + events 精算近群组） */
async function retention(env) {
  // 近 14 天注册的用户，其中注册次日之后仍有事件的比例
  const cohort = await env.STATS.prepare(
    "SELECT user_id, MIN(ts) reg FROM events WHERE kind='register' AND ts>?1 GROUP BY user_id"
  ).bind(Date.now() - 14 * DAY_MS).all();
  let d1 = 0, d7 = 0, d1n = 0, d7n = 0;
  for (const r of cohort.results || []) {
    if (Date.now() - r.reg > 1 * DAY_MS) {
      d1n++;
      const back = await env.STATS.prepare(
        'SELECT 1 FROM events WHERE user_id=?1 AND ts>?2 LIMIT 1'
      ).bind(r.user_id, r.reg + 1 * DAY_MS).first();
      if (back) d1++;
    }
    if (Date.now() - r.reg > 7 * DAY_MS) {
      d7n++;
      const back = await env.STATS.prepare(
        'SELECT 1 FROM events WHERE user_id=?1 AND ts>?2 LIMIT 1'
      ).bind(r.user_id, r.reg + 7 * DAY_MS).first();
      if (back) d7++;
    }
  }
  // 存量口径：所有账号里最近 7 天活跃占比
  const [total, wau] = await Promise.all([
    env.AUTH.prepare('SELECT COUNT(*) c FROM users').first(),
    env.AUTH.prepare('SELECT COUNT(*) c FROM users WHERE last_seen_at>?1').bind(Date.now() - 7 * DAY_MS).first()
  ]);
  return {
    d1: d1n ? Math.round(d1 / d1n * 100) : null, d1_n: d1n,
    d7: d7n ? Math.round(d7 / d7n * 100) : null, d7_n: d7n,
    wau: wau?.c || 0, total: total?.c || 0
  };
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

    // 公开信标（P3-v2）：没有本地源码的站（主站/文钞/game）贴一行脚本即可接入统计。
    // 无鉴权但：站点/事件白名单 + 单 IP 限流 + 只记匿名字段。CORS 放开。
    if (p === '/admin/api/beacon') {
      const cors = {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type'
      };
      if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
      if (request.method !== 'POST') return J({ error: 'POST only' }, 405);
      const h = beaconHits.get(ip) || { n: 0, win: Date.now() };
      if (Date.now() - h.win > 3600_000) { h.n = 0; h.win = Date.now(); }
      if (++h.n > 60) { beaconHits.set(ip, h); return new Response('{"ok":true}', { headers: cors }); }
      beaconHits.set(ip, h);
      try {
        const b = await request.json();
        const site = String(b.site || '');
        const kind = ['page_view', 'play', 'download'].includes(String(b.kind)) ? String(b.kind) : 'page_view';
        if (BEACON_SITES.includes(site)) {
          const ua = request.headers.get('user-agent') || '';
          const uaClass = /Android/i.test(ua) ? 'android' : /iPhone|iPad|iPod/i.test(ua) ? 'ios' : /Mozilla/i.test(ua) ? 'desktop' : 'other';
          let ref = ''; try { ref = new URL(request.headers.get('referer') || '').host; } catch { }
          // IP 哈希：与 looka 同思路（这里简化为无盐截断日期混合，不可反查）
          const iph = ip ? btoa(ip + bjDay()).slice(0, 16) : null;
          await env.STATS.prepare(
            'INSERT INTO events (site, kind, user_id, ip_hash, ua_class, ref, meta, ts) VALUES (?1,?2,NULL,?3,?4,?5,?6,?7)'
          ).bind(site, kind, iph, uaClass, ref, '{}', Date.now()).run();
        }
      } catch (_) { }
      return new Response('{"ok":true}', { headers: { 'Content-Type': 'application/json', ...cors } });
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
        if (p === '/admin/api/site') {
          const site = url.searchParams.get('site') || 'looka';
          return J(await siteDetail(env, site, Math.min(Number(url.searchParams.get('days') || 30), 90)));
        }
        if (p === '/admin/api/user') {
          const d = await userDetail(env, Number(url.searchParams.get('id') || 0));
          return d ? J(d) : J({ error: '用户不存在' }, 404);
        }
        if (p === '/admin/api/retention') return J(await retention(env));

        // ---- 四站运营合并（2026-08-30）：待办、举报、留言、反馈 ----
        if (p === '/admin/api/todo') return J(await todoAll(env, request));
        if (p === '/admin/api/ops') {
          const site = url.searchParams.get('site') || '';
          return J(await opsFetch(env, request, site, url.searchParams.get('kind') || 'list'));
        }
        if (p === '/admin/api/audit') {
          const rows = await env.STATS.prepare('SELECT * FROM admin_audit ORDER BY id DESC LIMIT 50').all();
          return J(rows.results || []);
        }
        // C4（§54）：崩溃列表 —— C1 补传链路修通后，这里终于能看到线上崩溃了
        if (p === '/admin/api/crashes') {
          const rows = await env.LOOKA.prepare(
            'SELECT id, ver, model, substr(stack,1,2000) AS stack, created_at FROM crashes ORDER BY id DESC LIMIT 50'
          ).all();
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

        // ---- 四站运营写操作（2026-08-30）：每笔记 admin_audit ----
        if (p === '/admin/api/ops-act') {
          const site = String(b.site || '');
          const act = String(b.act || '');
          // 播经台：报错状态流转 / 删留言 / 封解封设备
          if (site === 'bojing') {
            if (act === 'mark') {
              const id = Number(b.id), st = String(b.status || '');
              if (!id || !['open', 'done', 'ignored'].includes(st)) return J({ error: '参数不合法' }, 400);
              await env.BOJING.prepare('UPDATE reports SET status=?1 WHERE id=?2').bind(st, id).run();
              await audit(env, 'bo-report-' + st, id, {});
              return J({ ok: true });
            }
            if (act === 'del') {
              const id = Number(b.id);
              if (!id) return J({ error: '缺 id' }, 400);
              await env.BOJING.prepare('DELETE FROM comments WHERE id=?1').bind(id).run();
              await audit(env, 'bo-del-comment', id, {});
              return J({ ok: true });
            }
            if (act === 'askfb-handled') {
              const id = Number(b.id);
              if (!id) return J({ error: '缺 id' }, 400);
              await env.BOJING.prepare('UPDATE ask_feedback SET handled=1 WHERE id=?1').bind(id).run();
              await audit(env, 'bo-askfb-handled', id, {});
              return J({ ok: true });
            }
            if (act === 'ban' || act === 'unban') {
              const dev = String(b.dev || '').trim();
              if (!dev) return J({ error: '缺 dev' }, 400);
              if (act === 'ban') {
                await env.BOJING.prepare('INSERT OR REPLACE INTO banned (dev,ts) VALUES (?1,?2)')
                  .bind(dev, Date.now()).run();
              } else {
                await env.BOJING.prepare('DELETE FROM banned WHERE dev=?1').bind(dev).run();
              }
              await audit(env, 'bo-' + act, dev, {});
              return J({ ok: true });
            }
          }
          // 自知录：广场内容下架 / 标记举报已办
          if (site === 'zhi' && act === 'takedown') {
            const r = await subGet(env, 'zhi', '/api/admin/takedown',
              { noteId: Number(b.noteId), takedown: b.takedown !== false });
            if (r.__err) return J({ error: r.__err }, 502);
            await audit(env, b.takedown === false ? 'zhi-report-keep' : 'zhi-takedown', b.noteId, {});
            return J({ ok: true });
          }
          // 文钞：标记反馈已处理
          if (site === 'wenchao' && act === 'handled') {
            const r = await subGet(env, 'wenchao', '/api/admin/feedback-handled',
              { key: String(b.key || ''), handled: true });
            if (r.__err) return J({ error: r.__err }, 502);
            await audit(env, 'wc-feedback-handled', String(b.key || ''), {});
            return J({ ok: true });
          }
          return J({ error: '未知操作' }, 400);
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

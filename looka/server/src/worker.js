// Looka · 可爱版九色鹿 —— Cloudflare Worker
// 账号：复用 zhi.foyue.org 的用户库（AUTH_DB.users，PBKDF2 同格式）。
// 自有数据：looka-db（sessions / items 同步 / ai_usage / plans / codes / login_fails /
//           reset_tokens / user_emails / rate_limits / invite_codes / crashes）。
// 本版（v2）：同步分页修复、注册闸门+限流、邮箱找回/绑定/验证、改密、注销、
//            AI 对话不限次（分钟/日限速）、崩溃收集、CORS、Cron 清理、R2 APK 分发。

const PBKDF2_ITER = 100000;
const KINDS = ['category', 'tasklist', 'event', 'task', 'note', 'diary', 'stamp', 'settings'];  // P5-1：设置也是同步实体
const SYNC_PAGE = 1000;            // 同步单页上限（配 has_more/next_since 游标）
const enc = new TextEncoder();

const ALLOWED_ORIGINS = new Set(['https://looka.foyue.org']);

function corsHeaders(request) {
  const origin = request.headers.get('origin') || '';
  if (!ALLOWED_ORIGINS.has(origin)) return {};
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type',
    'Access-Control-Max-Age': '86400'
  };
}

const toHex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
const fromHex = h => new Uint8Array((h.match(/../g) || []).map(x => parseInt(x, 16)));
const randHex = n => toHex(crypto.getRandomValues(new Uint8Array(n)));
const nowISO = () => new Date().toISOString();
const ym = () => nowISO().slice(0, 7);
const today = () => nowISO().slice(0, 10);

const isEmail = s => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(s);
const isPhone = s => /^1[3-9]\d{9}$/.test(s);

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// ---------- 密码（与 zhi 完全同格式：iter:saltHex:hashHex） ----------
async function derive(pw, salt, iter) {
  const key = await crypto.subtle.importKey('raw', enc.encode(pw), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, key, 256
  );
  return toHex(bits);
}
async function hashPassword(pw) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return `${PBKDF2_ITER}:${toHex(salt)}:${await derive(pw, salt, PBKDF2_ITER)}`;
}
async function verifyPassword(pw, stored) {
  const [iter, saltH, hashH] = String(stored || '').split(':');
  if (!iter || !saltH || !hashH) return false;
  return safeEqual(await derive(pw, fromHex(saltH), Number(iter)), hashH);
}

// ---------- 会话 ----------
function bearer(request) {
  const m = (request.headers.get('authorization') || '').match(/^Bearer\s+([a-f0-9]{64})$/i);
  return m && m[1];
}
async function newSession(env, userId) {
  const token = randHex(32);
  await env.DB.prepare('INSERT INTO sessions (token, user_id, expires_at) VALUES (?1, ?2, ?3)')
    .bind(token, userId, Date.now() + 90 * 24 * 3600 * 1000).run();
  return token;
}
async function getUser(request, env) {
  const token = bearer(request);
  if (!token) return null;
  const s = await env.DB.prepare('SELECT * FROM sessions WHERE token = ?1').bind(token).first();
  if (!s) return null;
  if (s.expires_at <= Date.now()) {
    await env.DB.prepare('DELETE FROM sessions WHERE token = ?1').bind(token).run();
    return null;
  }
  const u = await env.AUTH_DB.prepare('SELECT id, account, kind, nickname, banned_at FROM users WHERE id = ?1')
    .bind(s.user_id).first();
  // P3-2-5：封禁账号一律视为未登录（后台 /admin 可封禁/解封，操作记审计）
  if (u && u.banned_at > 0) return null;
  return u || null;
}

// ---------- 固定窗口限流（rate_limits：rk / cnt / win） ----------
async function rateLimit(env, key, limit, windowMs) {
  const now = Date.now();
  const r = await env.DB.prepare('SELECT cnt, win FROM rate_limits WHERE rk = ?1').bind(key).first();
  if (!r || now - r.win >= windowMs) {
    await env.DB.prepare(
      `INSERT INTO rate_limits (rk, cnt, win) VALUES (?1, 1, ?2)
       ON CONFLICT(rk) DO UPDATE SET cnt = 1, win = ?2`
    ).bind(key, now).run();
    return true;
  }
  if (r.cnt >= limit) return false;
  await env.DB.prepare('UPDATE rate_limits SET cnt = cnt + 1 WHERE rk = ?1').bind(key).run();
  return true;
}
const clientIp = request => request.headers.get('cf-connecting-ip') || '0.0.0.0';

// ---------- 邮件（Resend；发件域名 foyue.org 需在 Resend 后台完成 DNS 验证） ----------
async function sendEmail(env, to, subject, html) {
  if (!env.RESEND_API_KEY) return { ok: false, error: '未配置邮件服务' };
  try {
    const resp = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${env.RESEND_API_KEY}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        from: env.MAIL_FROM || 'Looka <noreply@foyue.org>',
        to: [to], subject, html
      })
    });
    if (resp.ok) return { ok: true };
    const d = await resp.json().catch(() => ({}));
    return { ok: false, error: d?.message || `邮件发送失败 ${resp.status}` };
  } catch (e) {
    return { ok: false, error: '邮件服务不可达' };
  }
}

const mailShell = (title, body) => `
<div style="max-width:480px;margin:0 auto;font-family:-apple-system,'PingFang SC',sans-serif;color:#1b1b1f">
  <div style="padding:28px 24px 8px"><span style="font-size:22px">🦌</span>
    <span style="font-size:18px;font-weight:700;margin-left:6px">Looka</span></div>
  <div style="padding:8px 24px 28px;font-size:14px;line-height:1.8">
    <p style="font-size:16px;font-weight:600">${title}</p>${body}
    <p style="color:#8a8f8e;font-size:12px;margin-top:24px">Looka · 可爱版九色鹿 · looka.foyue.org<br>
    此账号与自知录（zhi.foyue.org）共用，密码变更对两边同时生效。</p>
  </div>
</div>`;

// ---------- 订阅 ----------
async function planOf(env, userId) {
  const p = await env.DB.prepare('SELECT * FROM plans WHERE user_id = ?1').bind(userId).first();
  if (p && p.plan === 'pro' && p.expires_at > Date.now()) {
    return { plan: 'pro', expiresAt: p.expires_at };
  }
  return { plan: 'free', expiresAt: 0 };
}

/**
 * 从上游响应里取正文。
 * 思考型模型（Qwen3 系、DeepSeek-R1 等）会把内容放进 reasoning_content 而让 content 为空，
 * 或者用 <think></think> 包住思维链 —— 两种都要处理，否则用户收到空气泡。
 */
function pickText(data) {
  const msg = data?.choices?.[0]?.message || {};
  let t = String(msg.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  if (!t) t = String(msg.reasoning_content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  return t;
}

// ---------- 鹿角（算力券）----------
// 双桶记账：granted(赠送，有累计上限、不清零) / paid(购买，不过期)。消耗一律先扣 granted，
// 保护用户真金白银买的那部分。余额表是流水的冗余缓存，两者必须在同一个 batch 里原子更新。
// §55（2026-08-22 定案）：鹿角是唯一额度口径（取代按天次数），按动作计价、每日到账。
// 红线：写日记/建日程/贴表情/同步/导出/闹钟 永不消耗。
const ANTLER = {
  grant: { free: 10, pro: 50 },       // 每日到账（进 App 自动领，无签到感：不连签、断签无惩罚）
  cap:   { free: 60, pro: 300 },      // 赠送桶累计上限（攒得住但不无限；paid 桶不受限）
  cost:  { chat: 1, theme: 2, ocr: 3, stickers: 20 }  // 按动作：对话/生成主题/截图识别/表情包
};

const ymNow = () => new Date().toISOString().slice(0, 7);   // YYYY-MM
const dayNow = () => new Date().toISOString().slice(0, 10); // YYYY-MM-DD（鹿角日发周期）

/** 读余额；顺带做「惰性月度发放」——不用定时任务，用户来了才结算，天然幂等 */
async function antlerOf(env, userId, plan) {
  let row = await env.DB.prepare('SELECT * FROM antler_balance WHERE user_id = ?1').bind(userId).first();
  if (!row) {
    await env.DB.prepare(
      'INSERT OR IGNORE INTO antler_balance (user_id, granted, paid, grant_cycle, updated_at) VALUES (?1,0,0,NULL,?2)'
    ).bind(userId, Date.now()).run();
    row = { user_id: userId, granted: 0, paid: 0, grant_cycle: null };
  }
  // G6（§54）：惰性**日**发 —— 用户当天第一次来就到账，天然幂等；
  // 老数据 grant_cycle 是 YYYY-MM 格式，与今天不等 → 自动切到日周期，无需迁移。
  const cycle = dayNow();
  let grantedToday = 0;
  if (row.grant_cycle !== cycle) {
    const add = ANTLER.grant[plan] || ANTLER.grant.free;
    const cap = ANTLER.cap[plan] || ANTLER.cap.free;
    // 上限之上不再发，但已有余额不清零
    const next = Math.min(row.granted + add, Math.max(cap, row.granted));
    const real = next - row.granted;
    await env.DB.batch([
      env.DB.prepare(
        'UPDATE antler_balance SET granted = ?2, grant_cycle = ?3, updated_at = ?4 WHERE user_id = ?1'
      ).bind(userId, next, cycle, Date.now()),
      env.DB.prepare(
        `INSERT OR IGNORE INTO antler_ledger (id, user_id, delta, bucket, reason, ref, balance_after, created_at)
         VALUES (?1,?2,?3,'granted','daily_grant',?4,?5,?6)`
      ).bind(crypto.randomUUID(), userId, real, `${cycle}`, next + row.paid, Date.now())
    ]);
    row.granted = next; row.grant_cycle = cycle;
    grantedToday = real;
  }
  return { granted: row.granted, paid: row.paid, total: row.granted + row.paid, granted_today: grantedToday };
}

/**
 * 扣费。先扣 granted 再扣 paid。余额不足返回 null（调用方据此回落，不硬停）。
 * ref 传值时靠 ledger 的 (reason, ref) 唯一索引兜住重复扣。
 */
async function antlerSpend(env, userId, plan, amount, reason, ref = null) {
  if (amount <= 0) return { granted: 0, paid: 0, total: 0, spent: 0 };
  const bal = await antlerOf(env, userId, plan);
  if (bal.total < amount) return null;
  const fromGranted = Math.min(bal.granted, amount);
  const fromPaid = amount - fromGranted;
  const g = bal.granted - fromGranted, pd = bal.paid - fromPaid;
  await env.DB.batch([
    env.DB.prepare('UPDATE antler_balance SET granted=?2, paid=?3, updated_at=?4 WHERE user_id=?1')
      .bind(userId, g, pd, Date.now()),
    env.DB.prepare(
      `INSERT OR IGNORE INTO antler_ledger (id,user_id,delta,bucket,reason,ref,balance_after,created_at)
       VALUES (?1,?2,?3,?4,?5,?6,?7,?8)`
    ).bind(crypto.randomUUID(), userId, -amount,
           fromPaid > 0 ? 'mixed' : 'granted', reason, ref, g + pd, Date.now())
  ]);
  return { granted: g, paid: pd, total: g + pd, spent: amount };
}

/** 入账（购买/退还/活动赠送）。ref 去重，防止重复到账 */
async function antlerAdd(env, userId, plan, amount, bucket, reason, ref = null) {
  if (amount <= 0) return null;
  if (ref) {
    // 必须带 user_id：幂等是「同一用户的同一笔」不重复，不是全站唯一
    const dup = await env.DB.prepare(
      'SELECT 1 FROM antler_ledger WHERE user_id = ?1 AND reason = ?2 AND ref = ?3'
    ).bind(userId, reason, ref).first();
    if (dup) return null;                       // 已入过账，静默跳过
  }
  const bal = await antlerOf(env, userId, plan);
  const g = bal.granted + (bucket === 'granted' ? amount : 0);
  const pd = bal.paid + (bucket === 'paid' ? amount : 0);
  await env.DB.batch([
    env.DB.prepare('UPDATE antler_balance SET granted=?2, paid=?3, updated_at=?4 WHERE user_id=?1')
      .bind(userId, g, pd, Date.now()),
    env.DB.prepare(
      `INSERT OR IGNORE INTO antler_ledger (id,user_id,delta,bucket,reason,ref,balance_after,created_at)
       VALUES (?1,?2,?3,?4,?5,?6,?7,?8)`
    ).bind(crypto.randomUUID(), userId, amount, bucket, reason, ref, g + pd, Date.now())
  ]);
  return { granted: g, paid: pd, total: g + pd };
}

// ---------- 邀请奖励（§55 P5：双方各 +100，成本≈0 的获客筹码）----------
// 邀请码 = 'LK' + 用户 id 的 36 进制（无需存储，可逆）。referrals 表防重复绑定。
const inviteCodeOf = id => 'LK' + Number(id).toString(36).toUpperCase();
const inviteCodeToId = code => {
  const m = /^LK([0-9A-Z]{1,8})$/i.exec(String(code || '').trim());
  if (!m) return 0;
  const id = parseInt(m[1], 36);
  return Number.isFinite(id) && id > 0 ? id : 0;
};

// ---------- 全站埋点（P1，2026-08-22）----------
// 三条红线：不存原始 IP（每日盐哈希前 16 位）、不存完整 UA（只归类）、埋点失败绝不影响业务。
async function sha256hex(s) {
  return toHex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
}

async function dailySalt(env, day) {
  const row = await env.STATS.prepare('SELECT salt FROM ip_salts WHERE day = ?1').bind(day).first();
  if (row) return row.salt;
  const salt = randHex(16);
  await env.STATS.prepare('INSERT OR IGNORE INTO ip_salts (day, salt) VALUES (?1, ?2)')
    .bind(day, salt).run();
  return (await env.STATS.prepare('SELECT salt FROM ip_salts WHERE day = ?1').bind(day).first())?.salt || salt;
}

async function track(env, site, kind, userId, request, meta = {}) {
  try {
    if (!env.STATS) return;
    const ip = request?.headers?.get('cf-connecting-ip') || '';
    const day = today();
    const ipHash = ip ? (await sha256hex(ip + await dailySalt(env, day))).slice(0, 16) : null;
    const ua = request?.headers?.get('user-agent') || '';
    const uaClass = /Android/i.test(ua) ? 'android'
      : /iPhone|iPad|iPod/i.test(ua) ? 'ios'
      : /Mozilla/i.test(ua) ? 'desktop' : 'other';
    let ref = '';
    try { ref = new URL(request?.headers?.get('referer') || '').host; } catch { /* 无来源 */ }
    await env.STATS.prepare(
      `INSERT INTO events (site, kind, user_id, ip_hash, ua_class, ref, meta, ts)
       VALUES ('looka', ?1, ?2, ?3, ?4, ?5, ?6, ?7)`
    ).bind(kind, userId ?? null, ipHash, uaClass, ref,
      JSON.stringify(meta).slice(0, 256), Date.now()).run();
  } catch (_) { /* 埋点失败永远不能影响业务 */ }
}

// 「当日首次活跃」内存缓存：isolate 存活期内避免每个请求都写库；重启后重查一次而已
const seenToday = new Map();

async function markActive(env, userId, request) {
  const day = today();
  if (seenToday.get(userId) === day) return;
  seenToday.set(userId, day);
  try {
    const dayStartMs = Date.parse(day + 'T00:00:00+08:00');
    const r = await env.AUTH_DB.prepare(
      'UPDATE users SET last_seen_at = ?1 WHERE id = ?2 AND last_seen_at < ?3'
    ).bind(Date.now(), userId, dayStartMs).run();
    if (r.meta.changes) await track(env, 'looka', 'app_open', userId, request);
  } catch (_) { /* 活跃标记失败不影响业务 */ }
}

/** 每日汇总（03:17 Cron）：昨日明细 → daily_stats；清 90 天前明细、30 天前盐 */
async function statsRollup(env) {
  if (!env.STATS) return;
  try {
    const y = new Date(Date.now() - 24 * 3600_000);
    const day = new Date(y.getTime() + 8 * 3600_000).toISOString().slice(0, 10); // 北京昨日
    const a = Date.parse(day + 'T00:00:00+08:00'), b = a + 24 * 3600_000;
    await env.STATS.prepare(
      `INSERT OR REPLACE INTO daily_stats (site, day, kind, cnt, uniq)
       SELECT site, ?1, kind, COUNT(*), COUNT(DISTINCT COALESCE(CAST(user_id AS TEXT), ip_hash, 'x'))
       FROM events WHERE ts >= ?2 AND ts < ?3 GROUP BY site, kind`
    ).bind(day, a, b).run();
    await env.STATS.prepare('DELETE FROM events WHERE ts < ?1').bind(Date.now() - 90 * 24 * 3600_000).run();
    await env.STATS.prepare("DELETE FROM ip_salts WHERE day < date('now', '-30 days')").run();
  } catch (e) { console.log('statsRollup', String(e)); }
}

// ---------- 爱发电（国内支付通道，2026-08-21）----------
// 签名：sign = md5(token + "params" + params + "ts" + ts + "user_id" + user_id)
// Workers 的 crypto.subtle 不支持 MD5，只能内置一份纯 JS 实现（仅用于此签名，非安全用途）。
function md5hex(input) {
  const bytes = new TextEncoder().encode(input);
  const K = new Int32Array(64), S = [7,12,17,22,5,9,14,20,4,11,16,23,6,10,15,21];
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296);
  const n = bytes.length, total = ((n + 8) >> 6) + 1, M = new Int32Array(total * 16);
  for (let i = 0; i < n; i++) M[i >> 2] |= bytes[i] << ((i % 4) * 8);
  M[n >> 2] |= 0x80 << ((n % 4) * 8);
  M[total * 16 - 2] = n * 8;
  let a = 0x67452301, b = -0x10325477, c = -0x67452302, d = 0x10325476;
  const rot = (x, s) => (x << s) | (x >>> (32 - s));
  for (let i = 0; i < total * 16; i += 16) {
    const A = a, B = b, C = c, D = d;
    for (let j = 0; j < 64; j++) {
      let f, g;
      if (j < 16) { f = (b & c) | (~b & d); g = j; }
      else if (j < 32) { f = (d & b) | (~d & c); g = (5 * j + 1) % 16; }
      else if (j < 48) { f = b ^ c ^ d; g = (3 * j + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * j) % 16; }
      const t = d; d = c; c = b;
      b = b + rot((a + f + K[j] + M[i + g]) | 0, S[(j >> 4) * 4 + (j % 4)]) | 0;
      a = t;
    }
    a = (a + A) | 0; b = (b + B) | 0; c = (c + C) | 0; d = (d + D) | 0;
  }
  return [a, b, c, d].map(x =>
    [x & 0xff, (x >> 8) & 0xff, (x >> 16) & 0xff, (x >> 24) & 0xff]
      .map(v => v.toString(16).padStart(2, '0')).join('')
  ).join('');
}

// 调爱发电开放平台。⚠️ 不带 User-Agent 会被 403（2026-08-21 实测），必须显式设置。
async function afdianCall(env, path, paramsObj) {
  const params = JSON.stringify(paramsObj);
  const ts = Math.floor(Date.now() / 1000);
  const sign = md5hex(`${env.AFDIAN_TOKEN}params${params}ts${ts}user_id${env.AFDIAN_USER_ID}`);
  const r = await fetch(`https://afdian.com/api/open/${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (compatible; LookaServer/1.0; +https://looka.foyue.org)'
    },
    body: JSON.stringify({ user_id: env.AFDIAN_USER_ID, params, ts, sign })
  });
  return r.json();
}

/** 按月数折算 Pro 天数：12 个月按年卡 366 天，其余每月 31 天 */
const afdianDays = month => (month >= 12 ? 366 : month * 31);

/** 给用户开通/续期 Pro（在现有到期时间上叠加，与 /api/redeem 逻辑一致） */
async function grantPro(env, userId, days) {
  const cur = await planOf(env, userId);
  const base = cur.plan === 'pro' ? cur.expiresAt : Date.now();
  const exp = base + days * 24 * 3600 * 1000;
  await env.DB.prepare(
    `INSERT INTO plans (user_id, plan, expires_at) VALUES (?1,'pro',?2)
     ON CONFLICT(user_id) DO UPDATE SET plan='pro', expires_at=?2`
  ).bind(userId, exp).run();
  return exp;
}

/**
 * 校验并入账一笔爱发电订单（幂等）。
 * ⚠️ webhook 推送无签名，内容一律不可采信 —— 调用方必须传入 query-order 反查回来的订单。
 * 归属三级匹配：① remark 里的 LK 短码 → pay_intents；② remark 形似邮箱/手机 → 账号；③ 待认领。
 */
async function afdianSettle(env, o) {
  if (!o || Number(o.status) !== 2) return { ok: false, reason: 'status' };
  // remote_id = 我们的开发者 user_id，能对上才是发给我们的单
  if (o.remote_id && String(o.remote_id) !== String(env.AFDIAN_USER_ID)) {
    return { ok: false, reason: 'remote_id' };
  }
  const month = Number(o.month || 0);
  const amount = Number(o.total_amount || 0);
  // 金额下限校验：12元/月、98元/年≈8.2元/月 —— 防 0.01 元单开出一年 Pro
  if (month < 1 || amount < month * 8) return { ok: false, reason: 'amount' };

  // 幂等锁：主键 (channel, order_no)，插不进去就是处理过了
  const ins = await env.DB.prepare(
    `INSERT OR IGNORE INTO pay_orders (channel, order_no, user_id, amount, raw, handled_at)
     VALUES ('afdian', ?1, NULL, ?2, ?3, ?4)`
  ).bind(String(o.out_trade_no), String(o.total_amount), JSON.stringify(o), Date.now()).run();
  if (!ins.meta.changes) return { ok: true, dup: true };

  let uid = null;
  const remark = String(o.remark || '').trim();
  const lk = (remark.match(/LK-[A-Z0-9]{4,8}/i) || [])[0]?.toUpperCase();
  if (lk) {
    const it = await env.DB.prepare('SELECT * FROM pay_intents WHERE code = ?1').bind(lk).first();
    if (it) uid = it.user_id;
  }
  if (!uid && (isEmail(remark.toLowerCase()) || isPhone(remark))) {
    const u = await env.AUTH_DB.prepare('SELECT id FROM users WHERE account = ?1')
      .bind(remark.toLowerCase()).first();
    if (u) uid = u.id;
  }
  if (!uid) return { ok: true, matched: false };   // 留在 pay_orders 待自助认领

  await grantPro(env, uid, afdianDays(month));
  await track(env, 'looka', 'pay', uid, null, { amt: String(o.total_amount), mon: month });
  await env.DB.prepare(
    "UPDATE pay_orders SET user_id = ?1 WHERE channel = 'afdian' AND order_no = ?2"
  ).bind(uid, String(o.out_trade_no)).run();
  if (lk) {
    await env.DB.prepare("UPDATE pay_intents SET status='paid', order_no=?1 WHERE code=?2")
      .bind(String(o.out_trade_no), lk).run();
  }
  return { ok: true, matched: true, user_id: uid };
}

/** 每日对账：拉最近订单补漏（webhook 掉了也能捞回来；INSERT OR IGNORE 天然去重） */
async function afdianReconcile(env) {
  if (!env.AFDIAN_TOKEN || !env.AFDIAN_USER_ID) return;
  try {
    const d = await afdianCall(env, 'query-order', { page: 1 });
    for (const o of d?.data?.list || []) await afdianSettle(env, o);
  } catch (e) { console.log('afdian reconcile', String(e)); }
}

// ---------- 路由 ----------
async function route(request, env, ctx) {
  const url = new URL(request.url);
  const p = url.pathname;
  const m = request.method;
  const cors = corsHeaders(request);
  const json = (data, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store', ...cors
      }
    });
  const page = (html, status = 200) =>
    new Response(html, { status, headers: { 'Content-Type': 'text/html; charset=utf-8', ...cors } });
  const body = async () => { try { return await request.json(); } catch { return {}; } };

  if (m === 'OPTIONS') return new Response(null, { status: 204, headers: cors });

  if (p === '/api/health') return json({ ok: true, service: 'looka', time: Date.now() });

  // 客户端启动配置（无需登录）
  if (p === '/api/config' && m === 'GET') {
    return json({
      ok: true,
      register_mode: env.REGISTER_MODE || 'open',   // open | invite | closed
      // §50：FREE 每天 free_rpd 次；Pro 不限次（rpd 仅为防滥用的公平上限）
      ai: {
        chat: 'tiered',
        rpm: Number(env.AI_RPM || 10),
        rpd: Number(env.AI_RPD || 100),
        free_rpd: Number(env.FREE_AI_RPD || 10)
      }
    });
  }

  // ===== APK 分发（R2） =====
  // HEAD 也要放行：部分下载器/校验脚本先探测再下载，落到下面的鉴权段会被 401
  if (p === '/dl/looka-latest.apk' && (m === 'GET' || m === 'HEAD')) {
    if (!env.APK) return json({ error: '未配置分发存储' }, 500);
    const obj = m === 'HEAD' ? await env.APK.head('looka-latest.apk')
                             : await env.APK.get('looka-latest.apk');
    if (!obj) return json({ error: '暂无可下载的安装包' }, 404);
    // P1-5：下载埋点（此前完全空白，HEAD 探测不算）
    if (m === 'GET') ctx.waitUntil(track(env, 'looka', 'apk_download', null, request));
    return new Response(m === 'HEAD' ? null : obj.body, {
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': String(obj.size),
        'Content-Disposition': 'attachment; filename="looka.apk"',
        'Cache-Control': 'no-store'
      }
    });
  }

  // ===== 注册（闸门 + 限流 + 可选邀请码） =====
  if (p === '/api/auth/register' && m === 'POST') {
    const mode = env.REGISTER_MODE || 'open';
    if (mode === 'closed') return json({ error: '当前未开放注册' }, 403);
    if (!await rateLimit(env, `reg:ip:${clientIp(request)}`, 3, 3600_000)) {
      return json({ error: '注册过于频繁，请稍后再试' }, 429);
    }
    const b = await body();
    const account = String(b.account || '').trim().toLowerCase();
    const password = String(b.password || '');
    const invite = String(b.invite || '').trim().toUpperCase();
    const kind = isEmail(account) ? 'email' : isPhone(account) ? 'phone' : null;
    if (!kind) return json({ error: '请输入有效的邮箱或手机号' }, 400);
    if (password.length < 6) return json({ error: '密码至少 6 位' }, 400);
    if (!await rateLimit(env, `reg:acc:${account}`, 3, 24 * 3600_000)) {
      return json({ error: '该账号今日尝试次数已达上限' }, 429);
    }
    let inviteRow = null;
    if (mode === 'invite') {
      if (!invite) return json({ error: '内测期需要邀请码，请向管理员获取', need_invite: true }, 403);
      inviteRow = await env.DB.prepare('SELECT * FROM invite_codes WHERE code = ?1').bind(invite).first();
      if (!inviteRow || inviteRow.used_by) return json({ error: '邀请码无效或已被使用' }, 403);
    }
    const exists = await env.AUTH_DB.prepare('SELECT id FROM users WHERE account = ?1')
      .bind(account).first();
    if (exists) return json({ error: '该账号已注册，请直接登录' }, 409);
    const r = await env.AUTH_DB.prepare(
      'INSERT INTO users (account, kind, pass_hash, created_at) VALUES (?1, ?2, ?3, ?4)'
    ).bind(account, kind, await hashPassword(password), nowISO()).run();
    const userId = r.meta.last_row_id;
    // §55 P5：邀请奖励。ref 可选；自邀/无效码静默忽略（注册不因此失败）。
    // referrals 主键 = 新用户 id → 一个新账号只能被绑定一次；antlerAdd 的 ref 幂等再兜一层。
    const refId = inviteCodeToId(b.ref);
    if (refId > 0 && refId !== userId) {
      ctx.waitUntil((async () => {
        try {
          const refUser = await env.AUTH_DB.prepare('SELECT id FROM users WHERE id = ?1')
            .bind(refId).first();
          if (!refUser) return;
          const ins = await env.DB.prepare(
            'INSERT OR IGNORE INTO referrals (new_user_id, referrer_id, created_at) VALUES (?1,?2,?3)'
          ).bind(userId, refId, Date.now()).run();
          if (!ins.meta.changes) return;   // 已绑定过，不重复发
          await antlerAdd(env, userId, 'free', 100, 'granted', 'referral_new', `ref:${refId}`);
          await antlerAdd(env, refId, 'free', 100, 'granted', 'referral_inviter', `ref:${userId}`);
          await track(env, 'looka', 'referral', refId, request, {});
        } catch (_) { /* 奖励失败不影响注册 */ }
      })());
    }
    if (inviteRow) {
      await env.DB.prepare('UPDATE invite_codes SET used_by = ?1, used_at = ?2 WHERE code = ?3')
        .bind(userId, Date.now(), invite).run();
    }
    // 邮箱账号：注册即发验证信（不阻塞使用；验证通过才能用邮箱找回）
    if (kind === 'email') {
      const vt = randHex(24);
      await env.DB.prepare(
        `INSERT INTO user_emails (user_id, email, verified, verify_token, verify_expires)
         VALUES (?1, ?2, 0, ?3, ?4)
         ON CONFLICT(user_id) DO UPDATE SET email=?2, verified=0, verify_token=?3, verify_expires=?4`
      ).bind(userId, account, vt, Date.now() + 72 * 3600_000).run();
      ctx.waitUntil(sendEmail(env, account, '验证你的 Looka 邮箱',
        mailShell('欢迎来到 Looka 🦌', `
          <p>点击下面的链接完成邮箱验证。验证后，忘记密码时才能通过此邮箱找回。</p>
          <p><a href="https://looka.foyue.org/api/account/verify-email?token=${vt}"
                style="color:#55b04b">验证邮箱</a>（72 小时内有效）</p>
          <p>如果这不是你的操作，忽略本邮件即可。</p>`)
      ).then(() => {}));
    }
    // 新用户礼包：直接发 Pro 试用（内测期 365 天，正式期 30 天，由 TRIAL_DAYS 控制）。
    // 到期自动回落免费版；我们没有自动扣费通道，不存在"忘记取消被扣钱"。
    const trialDays = Number(env.TRIAL_DAYS || 365);
    let trialPlan = 'free';
    if (trialDays > 0) {
      const exp = Date.now() + trialDays * 24 * 3600 * 1000;
      await env.DB.prepare(
        `INSERT INTO plans (user_id, plan, expires_at) VALUES (?1,'pro',?2)
         ON CONFLICT(user_id) DO UPDATE SET plan='pro', expires_at=?2`
      ).bind(userId, exp).run();
      trialPlan = 'pro';
      await antlerAdd(env, userId, 'pro', 20, 'granted', 'welcome_bonus', String(userId));
    }
    const token = await newSession(env, userId);
    ctx.waitUntil((async () => {
      await env.AUTH_DB.prepare("UPDATE users SET reg_site = 'looka' WHERE id = ?1 AND reg_site = ''")
        .bind(userId).run().catch(() => {});
      await track(env, 'looka', 'register', userId, request, { kind });
    })());
    const trialExp = trialDays > 0 ? Date.now() + trialDays * 24 * 3600 * 1000 : 0;
    return json({ ok: true, token, account, nickname: '', plan: trialPlan, trial_days: trialDays, plan_expiry: trialExp });
  }

  // ===== 登录 =====
  if (p === '/api/auth/login' && m === 'POST') {
    const b = await body();
    const account = String(b.account || '').trim().toLowerCase();
    const lf = await env.DB.prepare('SELECT * FROM login_fails WHERE account = ?1')
      .bind(account).first();
    if (lf && lf.locked_until > Date.now()) {
      return json({
        error: `尝试次数过多，请 ${Math.ceil((lf.locked_until - Date.now()) / 60000)} 分钟后再试`
      }, 429);
    }
    const u = await env.AUTH_DB.prepare('SELECT * FROM users WHERE account = ?1')
      .bind(account).first();
    if (!u || !(await verifyPassword(String(b.password || ''), u.pass_hash))) {
      const fails = (lf && lf.locked_until <= Date.now() && lf.fails < 5 ? lf.fails : 0) + 1;
      const locked = fails >= 5 ? Date.now() + 10 * 60 * 1000 : 0;
      await env.DB.prepare(
        `INSERT INTO login_fails (account, fails, locked_until, updated_at) VALUES (?1,?2,?3,?4)
         ON CONFLICT(account) DO UPDATE SET fails=excluded.fails, locked_until=excluded.locked_until, updated_at=excluded.updated_at`
      ).bind(account, locked ? 0 : fails, locked, nowISO()).run();
      return json({ error: '账号或密码不正确' }, 401);
    }
    await env.DB.prepare('DELETE FROM login_fails WHERE account = ?1').bind(account).run();
    const token = await newSession(env, u.id);
    const pl = await planOf(env, u.id);
    ctx.waitUntil(track(env, 'looka', 'login', u.id, request));
    return json({ ok: true, token, account: u.account, nickname: u.nickname || '', plan: pl.plan, plan_expiry: pl.expiresAt });
  }

  if (p === '/api/auth/logout' && m === 'POST') {
    const token = bearer(request);
    if (token) await env.DB.prepare('DELETE FROM sessions WHERE token = ?1').bind(token).run();
    return json({ ok: true });
  }

  // ===== 忘记密码（防枚举：统一话术） =====
  if (p === '/api/auth/forgot' && m === 'POST') {
    const uniform = json({ ok: true, message: '如果该账号可通过邮箱找回，重置邮件已发出，请查收（15 分钟内有效）' });
    const b = await body();
    const account = String(b.account || '').trim().toLowerCase();
    if (!account) return json({ error: '请输入账号' }, 400);
    if (!await rateLimit(env, `fp:acc:${account}`, 1, 60_000)) return uniform;
    if (!await rateLimit(env, `fp:accd:${account}`, 5, 24 * 3600_000)) return uniform;
    if (!await rateLimit(env, `fp:ip:${clientIp(request)}`, 10, 24 * 3600_000)) return uniform;
    const u = await env.AUTH_DB.prepare('SELECT id, account, kind FROM users WHERE account = ?1')
      .bind(account).first();
    if (!u) return uniform;
    // 邮箱账号 → 直发账号邮箱；手机号账号 → 仅已验证的绑定邮箱
    let dest = null;
    if (u.kind === 'email') dest = u.account;
    else {
      const be = await env.DB.prepare('SELECT email FROM user_emails WHERE user_id = ?1 AND verified = 1')
        .bind(u.id).first();
      dest = be?.email || null;
    }
    if (!dest) return uniform;
    const token = randHex(24);
    await env.DB.prepare(
      'INSERT INTO reset_tokens (token, user_id, expires_at, used) VALUES (?1, ?2, ?3, 0)'
    ).bind(token, u.id, Date.now() + 15 * 60_000).run();
    ctx.waitUntil(sendEmail(env, dest, 'Looka 密码重置',
      mailShell('重置你的密码', `
        <p>收到账号 <b>${account}</b> 的密码重置请求。点击下面的链接设置新密码：</p>
        <p><a href="https://looka.foyue.org/reset.html?token=${token}" style="color:#55b04b">重置密码</a>
        （15 分钟内有效，仅可使用一次）</p>
        <p>如果这不是你的操作，忽略本邮件即可，密码不会被改变。</p>`)
    ).then(() => {}));
    return uniform;
  }

  if (p === '/api/auth/reset' && m === 'POST') {
    const b = await body();
    const token = String(b.token || '').trim();
    const password = String(b.password || '');
    if (password.length < 6) return json({ error: '密码至少 6 位' }, 400);
    const t = await env.DB.prepare('SELECT * FROM reset_tokens WHERE token = ?1').bind(token).first();
    if (!t || t.used || t.expires_at <= Date.now()) {
      return json({ error: '重置链接无效或已过期，请重新申请' }, 400);
    }
    await env.DB.prepare('UPDATE reset_tokens SET used = 1 WHERE token = ?1').bind(token).run();
    await env.AUTH_DB.prepare('UPDATE users SET pass_hash = ?1 WHERE id = ?2')
      .bind(await hashPassword(password), t.user_id).run();
    // 吊销全部会话：所有设备需重新登录
    await env.DB.prepare('DELETE FROM sessions WHERE user_id = ?1').bind(t.user_id).run();
    return json({ ok: true, message: '密码已重置，请用新密码登录（自知录同步生效）' });
  }

  // ===== 邮箱验证链接（GET，从邮件点入） =====
  if (p === '/api/account/verify-email' && m === 'GET') {
    const token = url.searchParams.get('token') || '';
    const r = await env.DB.prepare('SELECT * FROM user_emails WHERE verify_token = ?1').bind(token).first();
    const shell = (msg, ok) => page(`<!doctype html><meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <body style="font-family:-apple-system,'PingFang SC',sans-serif;display:flex;min-height:90vh;
        align-items:center;justify-content:center;text-align:center;color:#1b1b1f">
      <div><div style="font-size:44px">${ok ? '🦌' : '🍂'}</div>
      <p style="font-size:16px">${msg}</p>
      <a href="https://looka.foyue.org" style="color:#55b04b;font-size:14px">回到 Looka</a></div>`);
    if (!r || !r.verify_expires || r.verify_expires <= Date.now()) {
      return shell('验证链接无效或已过期，请在 App 里重新发送', false);
    }
    await env.DB.prepare(
      'UPDATE user_emails SET verified = 1, verified_at = ?1, verify_token = NULL WHERE user_id = ?2'
    ).bind(Date.now(), r.user_id).run();
    return shell('邮箱验证成功！忘记密码时可以用它找回了', true);
  }

  // ===== 崩溃上报（无需登录，限流） =====
  // P1-9：未登录首页浏览埋点（限流防刷）
  if (p === '/api/ev' && m === 'POST') {
    if (await rateLimit(env, `ev:${clientIp(request)}`, 30, 3600_000)) {
      const b = await body();
      const kind = String(b.kind || '');
      if (['landing_view'].includes(kind)) ctx.waitUntil(track(env, 'looka', kind, null, request));
    }
    return json({ ok: true });
  }

  if (p === '/api/crash' && m === 'POST') {
    if (!await rateLimit(env, `crash:${clientIp(request)}`, 5, 24 * 3600_000)) {
      return json({ ok: true });
    }
    const b = await body();
    await env.DB.prepare(
      'INSERT INTO crashes (ver, model, stack, created_at) VALUES (?1, ?2, ?3, ?4)'
    ).bind(
      String(b.ver || '').slice(0, 32), String(b.model || '').slice(0, 64),
      String(b.stack || '').slice(0, 8192), Date.now()
    ).run();
    ctx.waitUntil(track(env, 'looka', 'crash', null, request, { ver: String(b.ver || '').slice(0, 16) }));
    return json({ ok: true });
  }

  // ===== 爱发电 Webhook（无需登录；路径带随机段防猜测）=====
  // ⚠️ 推送无签名，内容不可采信：只取 out_trade_no，用 query-order 反查为准。
  // 无论处理结果如何都返回 {ec:200}，否则爱发电会反复重推。
  if (env.AFDIAN_HOOK_SECRET && p === `/api/pay/afdian/${env.AFDIAN_HOOK_SECRET}` && m === 'POST') {
    let no = '';
    try { no = String((await request.json())?.data?.order?.out_trade_no || ''); } catch { /* 忽略坏包 */ }
    // 到达埋点：后台 events 表可查爱发电有没有真的推过来（含「发送测试」）
    ctx.waitUntil(track(env, 'looka', 'afdian_hook', null, request, { no: no.slice(0, 32) }));
    if (no && /^[0-9A-Za-z_-]{6,64}$/.test(no)) {
      ctx.waitUntil((async () => {
        try {
          const d = await afdianCall(env, 'query-order', { out_trade_no: no });
          await afdianSettle(env, (d?.data?.list || [])[0]);
        } catch (e) { console.log('afdian hook', String(e)); }
      })());
    }
    return json({ ec: 200, em: '' });
  }

  // ===== Ko-fi Webhook（无需登录；路径带随机段）=====
  // Ko-fi 以 application/x-www-form-urlencoded 发送，data 字段是 JSON 字符串。
  // 鉴权：payload 里的 verification_token 必须等于我们存的 KOFI_VERIFY_TOKEN——
  // 未配置 token 时只记录不开通（防伪造）。始终 200（否则 Ko-fi 反复重试）。
  if (env.KOFI_HOOK_SECRET && p === `/api/pay/kofi/${env.KOFI_HOOK_SECRET}` && m === 'POST') {
    ctx.waitUntil((async () => {
      try {
        const form = await request.formData();
        const d = JSON.parse(form.get('data') || '{}');
        await track(env, 'looka', 'kofi_hook', null, request, { type: String(d.type || '').slice(0, 20) });
        // 验证令牌不符 = 伪造，直接丢弃
        if (!env.KOFI_VERIFY_TOKEN || d.verification_token !== env.KOFI_VERIFY_TOKEN) return;
        if (!['Subscription', 'Donation', 'Commission'].includes(d.type)) return;   // 用户建的是 Commission 商品，一并支持
        const txId = String(d.kofi_transaction_id || d.message_id || '');
        if (!txId) return;
        // 幂等
        const ins = await env.DB.prepare(
          `INSERT OR IGNORE INTO pay_orders (channel, order_no, user_id, amount, raw, handled_at)
           VALUES ('kofi', ?1, NULL, ?2, ?3, ?4)`
        ).bind(txId, String(d.amount || ''), JSON.stringify(d).slice(0, 4096), Date.now()).run();
        if (!ins.meta.changes) return;
        // 归属：Ko-fi 自带付款人邮箱 —— 直接匹配账号
        const email = String(d.email || '').trim().toLowerCase();
        let uid = null;
        if (email && isEmail(email)) {
          const u = await env.AUTH_DB.prepare('SELECT id FROM users WHERE account = ?1').bind(email).first();
          if (u) uid = u.id;
        }
        if (!uid) return;   // 留在 pay_orders 待后台手动绑定
        // 月付订阅每次 31 天；单次打赏 ≥$7 也给 31 天（让利原则）
        const amt = Number(d.amount || 0);
        if (d.type === 'Subscription' || d.type === 'Commission' || amt >= 7) {
          await grantPro(env, uid, 31);
          await track(env, 'looka', 'pay', uid, null, { amt: String(d.amount), ch: 'kofi' });
          await env.DB.prepare("UPDATE pay_orders SET user_id = ?1 WHERE channel='kofi' AND order_no = ?2")
            .bind(uid, txId).run();
        }
      } catch (e) { console.log('kofi hook', String(e)); }
    })());
    return new Response('ok', { status: 200 });
  }

  // ============ 以下都需要登录 ============
  const user = await getUser(request, env);
  if (!user) return json({ error: '未登录或会话已过期' }, 401);
  ctx.waitUntil(markActive(env, user.id, request));   // P1-7：当日首次活跃（内存缓存去重）

  if (p === '/api/me' && m === 'GET') {
    const pl = await planOf(env, user.id);
    const used = await env.DB.prepare('SELECT used FROM ai_usage WHERE user_id = ?1 AND ym = ?2')
      .bind(user.id, ym()).first();
    const be = await env.DB.prepare('SELECT email, verified FROM user_emails WHERE user_id = ?1')
      .bind(user.id).first();
    const ant = await antlerOf(env, user.id, pl.plan);
    return json({
      ok: true, account: user.account, kind: user.kind,
      // 昵称：与自知录共用 users.nickname（同账号体系，两站看到同一个名字）
      nickname: user.nickname || '',
      // §55 P5：我的邀请码（朋友注册时填，双方各 +100 鹿角）
      invite_code: inviteCodeOf(user.id),
      plan: pl.plan, plan_expiry: pl.expiresAt,
      ai_month_used: used?.used || 0, ai_rpd: Number(env.AI_RPD || 100),
      bound_email: be?.email || '', email_verified: !!be?.verified,
      antler: ant, antler_cost: ANTLER.cost
    });
  }

  // ===== 昵称：改名 =====
  // 空字符串 = 清除昵称（回到用账号显示）。不做实名校验，但挡住控制字符与超长。
  if (p === '/api/me/nickname' && m === 'POST') {
    if (!await rateLimit(env, `nick:${user.id}`, 10, 3600_000)) {
      return json({ error: '改得有点频繁，过一会儿再试' }, 429);
    }
    const b = await body();
    // 去掉控制字符与首尾空白；按「字」计长度（emoji 不该被算成 4 个字符）
    let nick = String(b.nickname ?? '').replace(/[ -]/g, '').trim();
    if ([...nick].length > 20) return json({ error: '昵称最多 20 个字' }, 400);
    await env.AUTH_DB.prepare('UPDATE users SET nickname = ?1 WHERE id = ?2')
      .bind(nick || null, user.id).run();
    // §55 P6：里程碑 —— 首次起名 +20（antlerAdd 的 ref 幂等，只发一次；清空再设不重复发）
    if (nick) {
      const pl0 = await planOf(env, user.id);
      ctx.waitUntil(antlerAdd(env, user.id, pl0.plan, 20, 'granted', 'milestone', 'nickname'));
    }
    return json({ ok: true, nickname: nick });
  }

  // ===== 鹿角：余额 + 流水 =====
  if (p === '/api/antler' && m === 'GET') {
    const pl = await planOf(env, user.id);
    const bal = await antlerOf(env, user.id, pl.plan);
    const rows = await env.DB.prepare(
      'SELECT delta, bucket, reason, balance_after, created_at FROM antler_ledger WHERE user_id = ?1 ORDER BY created_at DESC LIMIT 50'
    ).bind(user.id).all();
    return json({
      ok: true, plan: pl.plan, ...bal,
      grant_monthly: ANTLER.grant[pl.plan] || ANTLER.grant.free,
      cap: ANTLER.cap[pl.plan] || ANTLER.cap.free,
      cost: ANTLER.cost,
      ledger: rows?.results || []
    });
  }

  // ===== 绑定邮箱（手机号账号找回密码的前提） =====
  if (p === '/api/account/bind-email' && m === 'POST') {
    const b = await body();
    const email = String(b.email || '').trim().toLowerCase();
    if (!isEmail(email)) return json({ error: '请输入有效邮箱' }, 400);
    if (!await rateLimit(env, `bind:${user.id}`, 3, 3600_000)) {
      return json({ error: '发送过于频繁，请稍后再试' }, 429);
    }
    const vt = randHex(24);
    await env.DB.prepare(
      `INSERT INTO user_emails (user_id, email, verified, verify_token, verify_expires)
       VALUES (?1, ?2, 0, ?3, ?4)
       ON CONFLICT(user_id) DO UPDATE SET email=?2, verified=0, verify_token=?3, verify_expires=?4`
    ).bind(user.id, email, vt, Date.now() + 72 * 3600_000).run();
    const sent = await sendEmail(env, email, '验证你的 Looka 邮箱',
      mailShell('绑定邮箱验证', `
        <p>账号 <b>${user.account}</b> 正在绑定此邮箱。点击链接完成验证：</p>
        <p><a href="https://looka.foyue.org/api/account/verify-email?token=${vt}"
              style="color:#55b04b">验证邮箱</a>（72 小时内有效）</p>
        <p>验证通过后，忘记密码时可用此邮箱找回。如果这不是你的操作，请忽略。</p>`));
    if (!sent.ok) return json({ error: sent.error || '验证邮件发送失败' }, 502);
    return json({ ok: true, message: '验证邮件已发送，请到邮箱点击链接完成验证' });
  }

  // ===== 修改密码 =====
  if (p === '/api/account/password' && m === 'POST') {
    const b = await body();
    const oldPw = String(b.old || '');
    const newPw = String(b.password || '');
    if (newPw.length < 6) return json({ error: '新密码至少 6 位' }, 400);
    const u = await env.AUTH_DB.prepare('SELECT pass_hash FROM users WHERE id = ?1').bind(user.id).first();
    if (!u || !(await verifyPassword(oldPw, u.pass_hash))) return json({ error: '当前密码不正确' }, 401);
    await env.AUTH_DB.prepare('UPDATE users SET pass_hash = ?1 WHERE id = ?2')
      .bind(await hashPassword(newPw), user.id).run();
    // 保留当前会话，吊销其余设备
    const token = bearer(request);
    await env.DB.prepare('DELETE FROM sessions WHERE user_id = ?1 AND token != ?2')
      .bind(user.id, token).run();
    return json({ ok: true, message: '密码已修改（自知录同步生效），其他设备已退出' });
  }

  // ===== 注销账号（清除 Looka 全部数据；登录凭证与 zhi 共享，予以保留） =====
  if (p === '/api/account/delete' && m === 'POST') {
    const b = await body();
    const u = await env.AUTH_DB.prepare('SELECT pass_hash FROM users WHERE id = ?1').bind(user.id).first();
    if (!u || !(await verifyPassword(String(b.password || ''), u.pass_hash))) {
      return json({ error: '密码不正确' }, 401);
    }
    for (const sql of [
      'DELETE FROM items WHERE user_id = ?1',
      'DELETE FROM sessions WHERE user_id = ?1',
      'DELETE FROM plans WHERE user_id = ?1',
      'DELETE FROM ai_usage WHERE user_id = ?1',
      'DELETE FROM user_emails WHERE user_id = ?1',
      'DELETE FROM reset_tokens WHERE user_id = ?1'
    ]) await env.DB.prepare(sql).bind(user.id).run();
    return json({
      ok: true,
      message: 'Looka 云端数据已全部删除。登录凭证与自知录共用故保留；如需一并删除请到自知录操作'
    });
  }

  // ===== 双向同步（分页游标：has_more / next_since，修复 >2000 条丢数据） =====
  if (p === '/api/sync' && m === 'POST') {
    const b = await body();
    const since = Number(b.since || 0);
    const push = Array.isArray(b.push) ? b.push.slice(0, 500) : [];

    for (const r of push) {
      const kind = String(r.kind || '');
      const uid = String(r.uid || '').slice(0, 64);
      const updatedAt = Number(r.updated_at || 0);
      const deleted = r.deleted ? 1 : 0;
      const payload = String(r.payload || '').slice(0, 64 * 1024);
      if (!KINDS.includes(kind) || !uid || !updatedAt) continue;
      const ex = await env.DB.prepare(
        'SELECT updated_at FROM items WHERE user_id = ?1 AND kind = ?2 AND uid = ?3'
      ).bind(user.id, kind, uid).first();
      if (!ex || updatedAt >= ex.updated_at) {
        await env.DB.prepare(
          `INSERT INTO items (user_id, kind, uid, updated_at, deleted, payload) VALUES (?1,?2,?3,?4,?5,?6)
           ON CONFLICT(user_id, kind, uid) DO UPDATE SET updated_at=excluded.updated_at, deleted=excluded.deleted, payload=excluded.payload`
        ).bind(user.id, kind, uid, updatedAt, deleted, payload).run();
      }
    }

    // §55 P6：里程碑 —— 第一篇日记 +20（antlerAdd 幂等；只在这批真的带日记时才查一次）
    if (push.some(r => r.kind === 'diary' && !r.deleted)) {
      ctx.waitUntil((async () => {
        try {
          const pl0 = await planOf(env, user.id);
          await antlerAdd(env, user.id, pl0.plan, 20, 'granted', 'milestone', 'first_diary');
        } catch (_) { }
      })());
    }

    const rows = await env.DB.prepare(
      `SELECT kind, uid, updated_at, deleted, payload FROM items
       WHERE user_id = ?1 AND updated_at > ?2 ORDER BY updated_at LIMIT ${SYNC_PAGE + 1}`
    ).bind(user.id, since).all();
    let list = rows.results || [];
    const hasMore = list.length > SYNC_PAGE;
    if (hasMore) list = list.slice(0, SYNC_PAGE);
    const nextSince = list.length ? list[list.length - 1].updated_at : since;
    const pl = await planOf(env, user.id);   // P2-A6：顺路捎回订阅状态，客户端落盘
    return json({
      ok: true, apply: list,
      has_more: hasMore, next_since: nextSince,
      server_time: Date.now(),
      plan: pl.plan, plan_expiry: pl.expiresAt
    });
  }

  // ===== AI 代理（§55：鹿角是唯一额度口径；模型统一 Qwen，§53 M0 用户拍板） =====
  // premium/DeepSeek 分支已下线（§53 M6）：完整实现见 git 历史 v1.8.2（tag 可整段恢复）。
  if (p === '/api/ai/chat' && m === 'POST') {
    if (!env.SILICONFLOW_KEY) return json({ error: '服务端未配置 AI Key，请联系管理员' }, 500);
    const plan = (await planOf(env, user.id)).plan;
    // 防滥用限速保留（正常用户碰不到）
    if (!await rateLimit(env, `ai:m:${user.id}`, Number(env.AI_RPM || 10), 60_000)) {
      return json({ error: '说得太快啦，休息几秒再问小鹿 🦌' }, 429);
    }
    const b = await body();
    const messages = Array.isArray(b.messages) ? b.messages.slice(0, 40).map(x => ({
      role: ['system', 'user', 'assistant'].includes(x.role) ? x.role : 'user',
      content: String(x.content || '').slice(0, 16000)
    })) : [];
    if (!messages.length) return json({ error: '消息为空' }, 400);
    const temperature = Math.min(Math.max(Number(b.temperature ?? 0.6), 0), 1.5);

    // ── 鹿角结算（G0：取代按天次数）。读余额顺带完成当日到账（惰性日发）。
    const bal = await antlerOf(env, user.id, plan);
    const need = ANTLER.cost.chat;
    let fallbackMode = false;
    if (bal.total < need) {
      // G7：余额 0 不硬停 —— 每天保底 3 次（0 次 = 用户再也想不起这个功能）
      if (!await rateLimit(env, `ai:fb:${user.id}:${today()}`, 3, 24 * 3600_000)) {
        return json({
          error: plan === 'pro'
            ? '今天的鹿角用完啦，明天还有 50 枚 🦌'
            : '今天的鹿角用完啦，明天还有 10 枚 🦌（Pro 每天 50 枚，攒着还能生成表情包）',
          antler_empty: true
        }, 429);
      }
      fallbackMode = true;   // 保底次数：不扣费
    }

    // ── 模型：统一 Qwen（免费池），主模型重试 + 备用模型（拥挤时段兜底）
    const models = [
      env.CHAT_MODEL || 'Qwen/Qwen3.5-35B-A3B',
      env.CHAT_MODEL_FALLBACK || 'Qwen/Qwen3.5-9B'
    ];

    // ── T1（§53）：真流式。SSE 直通透传上游（OpenAI 兼容格式），客户端自行解析 delta。
    //   计量在开流前完成（流中无法再回头扣）；上游连不上则不扣、退回错误 JSON。
    if (b.stream === true) {
      let resp = null;
      for (const model of models) {
        try {
          resp = await fetch(`${env.SILICONFLOW_BASE || 'https://api.siliconflow.cn/v1'}/chat/completions`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${env.SILICONFLOW_KEY}`,
              'Content-Type': 'application/json',
              'User-Agent': 'Looka/1.9 (+https://looka.foyue.org)'
            },
            body: JSON.stringify({ model, messages, temperature, max_tokens: 2048,
              enable_thinking: false, stream: true })
          });
          if (resp.ok && resp.body) break;
          resp = null;
        } catch (_) { resp = null; }
      }
      if (!resp) return json({ error: 'AI 上游异常（稍后再试）' }, 502);
      const after = fallbackMode ? bal
        : (await antlerSpend(env, user.id, plan, need, 'chat', null)) || bal;
      ctx.waitUntil(env.DB.prepare(
        `INSERT INTO ai_usage (user_id, ym, used) VALUES (?1, ?2, 1)
         ON CONFLICT(user_id, ym) DO UPDATE SET used = used + 1`
      ).bind(user.id, ym()).run());
      ctx.waitUntil(track(env, 'looka', 'ai_chat', user.id, request, { stream: 1 }));
      return new Response(resp.body, {
        headers: {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-store',
          // 客户端要在流开始前知道账面（G3 到账提示 / G4 低额提示）
          'X-Antler-Total': String(after.total),
          'X-Antler-Granted-Today': String(bal.granted_today || 0),
          ...cors
        }
      });
    }

    // ── 非流式（兼容旧客户端）
    let data = null, ok = false, lastMsg = 'AI 上游异常';
    outer: for (const model of models) {
      for (let attempt = 0; attempt < 3; attempt++) {
        const resp = await fetch(`${env.SILICONFLOW_BASE || 'https://api.siliconflow.cn/v1'}/chat/completions`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${env.SILICONFLOW_KEY}`,
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            'User-Agent': 'Looka/1.9 (+https://looka.foyue.org)'
          },
          // enable_thinking:false —— Qwen3 系是思考型模型，默认会把正文写进 reasoning_content
          body: JSON.stringify({ model, messages, temperature, max_tokens: 2048, enable_thinking: false })
        });
        data = await resp.json().catch(() => ({}));
        // 200 但正文为空同样算失败（2026-08-21 实测的坑）
        if (resp.ok && pickText(data)) { ok = true; break outer; }
        if (resp.ok) { lastMsg = '上游返回空内容'; break; }
        lastMsg = data?.error?.message || data?.message || `AI 上游错误 ${resp.status}`;
        const busy = resp.status === 429 || /busy|rate|overload/i.test(lastMsg);
        if (!busy) break outer;
        await new Promise(r => setTimeout(r, 600 * (attempt + 1)));
      }
    }
    if (!ok) return json({ error: lastMsg + '（稍后再试）' }, 502);
    // 成功才扣（保底模式不扣）
    const after = fallbackMode ? bal
      : (await antlerSpend(env, user.id, plan, need, 'chat', null)) || bal;
    await env.DB.prepare(
      `INSERT INTO ai_usage (user_id, ym, used) VALUES (?1, ?2, 1)
       ON CONFLICT(user_id, ym) DO UPDATE SET used = used + 1`
    ).bind(user.id, ym()).run();
    ctx.waitUntil(track(env, 'looka', 'ai_chat', user.id, request, {}));
    return json({
      ok: true, content: pickText(data),
      antler: { total: after.total, granted: after.granted, paid: after.paid },
      granted_today: bal.granted_today || 0,
      spent: fallbackMode ? 0 : need
    });
  }

  // ===== 兑换订阅码 =====
  // ===== 支付意图：生成 LK 短码 + 带备注的付款链接 =====
  // 用短码而不是邮箱进 remark：不把用户隐私暴露到第三方平台，且格式固定、匹配精确。
  if (p === '/api/pay/intent' && m === 'POST') {
    if (!await rateLimit(env, `payint:${user.id}`, 10, 3600_000)) {
      return json({ error: '操作过于频繁，请稍后再试' }, 429);
    }
    const b = await body();
    const plan = String(b.plan || 'month') === 'year' ? 'year' : 'month';
    // 复用未过期的 pending 意图，避免同一用户攒一堆码
    let code = (await env.DB.prepare(
      "SELECT code FROM pay_intents WHERE user_id = ?1 AND status = 'pending' AND expires_at > ?2 LIMIT 1"
    ).bind(user.id, Date.now()).first())?.code;
    if (!code) {
      code = 'LK-' + randHex(2).toUpperCase();
      await env.DB.prepare(
        `INSERT INTO pay_intents (code, user_id, plan_days, channel, status, created_at, expires_at)
         VALUES (?1, ?2, ?3, 'afdian', 'pending', ?4, ?5)`
      ).bind(code, user.id, plan === 'year' ? 366 : 31, Date.now(), Date.now() + 24 * 3600_000).run();
    }
    const planId = plan === 'year'
      ? (env.AFDIAN_PLAN_YEAR || env.AFDIAN_PLAN_MONTH || '95141ca09d2711f1bead52540025c377')
      : (env.AFDIAN_PLAN_MONTH || '95141ca09d2711f1bead52540025c377');
    const link = `https://ifdian.net/order/create?plan_id=${planId}&product_type=0&remark=${encodeURIComponent(code)}`;
    return json({ ok: true, code, url: link });
  }

  // ===== 自助认领：用户粘贴爱发电订单号，反查确认后直接开通 =====
  // 爱发电订单里没有赞助者邮箱，「发码到邮箱」走不通 —— 认领是唯一自助兜底。
  if (p === '/api/pay/claim' && m === 'POST') {
    if (!await rateLimit(env, `payclm:${user.id}`, 6, 3600_000)) {
      return json({ error: '尝试次数过多，请一小时后再试' }, 429);
    }
    const b = await body();
    const no = String(b.order_no || '').trim();
    if (!/^[0-9A-Za-z_-]{6,64}$/.test(no)) return json({ error: '请输入完整的爱发电订单号' }, 400);
    if (!env.AFDIAN_TOKEN || !env.AFDIAN_USER_ID) return json({ error: '支付通道未配置' }, 500);

    const exist = await env.DB.prepare(
      "SELECT * FROM pay_orders WHERE channel = 'afdian' AND order_no = ?1"
    ).bind(no).first();
    if (exist && exist.user_id) {
      if (exist.user_id !== user.id) {
        return json({ error: '该订单已被其他账号认领，如有疑问请联系 looka01@qq.com' }, 409);
      }
      const cur = await planOf(env, user.id);
      return json({ ok: true, already: true, plan: cur.plan, expires_at: cur.expiresAt });
    }
    let raw = exist ? JSON.parse(exist.raw || '{}') : null;
    if (!exist) {
      const d = await afdianCall(env, 'query-order', { out_trade_no: no });
      const o = (d?.data?.list || [])[0];
      if (!o) return json({ error: '没有查到这笔订单，请核对订单号' }, 404);
      const r = await afdianSettle(env, o);          // 校验 + 幂等落库（可能已按 remark 直接开通）
      if (!r.ok) return json({ error: '订单校验未通过（未支付或金额异常）' }, 400);
      if (r.matched) {
        if (r.user_id !== user.id) {
          return json({ error: '该订单已按备注归属到其他账号，如有疑问请联系 looka01@qq.com' }, 409);
        }
        const cur = await planOf(env, user.id);
        return json({ ok: true, plan: cur.plan, expires_at: cur.expiresAt });
      }
      raw = o;
    }
    // 原子认领：只有 user_id 仍为空才认领得到，防两个账号抢同一单
    const take = await env.DB.prepare(
      "UPDATE pay_orders SET user_id = ?1 WHERE channel = 'afdian' AND order_no = ?2 AND user_id IS NULL"
    ).bind(user.id, no).run();
    if (!take.meta.changes) return json({ error: '该订单已被认领' }, 409);
    const exp = await grantPro(env, user.id, afdianDays(Number(raw?.month || 1)));
    return json({ ok: true, plan: 'pro', expires_at: exp });
  }

  if (p === '/api/redeem' && m === 'POST') {
    const b = await body();
    const code = String(b.code || '').trim().toUpperCase();
    if (!code) return json({ error: '请输入兑换码' }, 400);
    const c = await env.DB.prepare('SELECT * FROM codes WHERE code = ?1').bind(code).first();
    if (!c) return json({ error: '兑换码不存在' }, 404);
    if (c.used_by) return json({ error: '该兑换码已被使用' }, 409);
    const take = await env.DB.prepare(
      'UPDATE codes SET used_by = ?1, used_at = ?2 WHERE code = ?3 AND used_by IS NULL'
    ).bind(user.id, Date.now(), code).run();
    if (!take.meta.changes) return json({ error: '该兑换码已被使用' }, 409);

    // 鹿角码：入购买桶（等同真金白银买的，不过期）
    if (c.kind === 'antler') {
      const pl = await planOf(env, user.id);
      const bal = await antlerAdd(env, user.id, pl.plan, c.amount, 'paid', 'redeem', code)
        || await antlerOf(env, user.id, pl.plan);
      return json({ ok: true, kind: 'antler', amount: c.amount, antler: bal });
    }

    const cur = await planOf(env, user.id);
    const base = cur.plan === 'pro' ? cur.expiresAt : Date.now();
    const expires = base + c.days * 24 * 3600 * 1000;
    await env.DB.prepare(
      `INSERT INTO plans (user_id, plan, expires_at) VALUES (?1, 'pro', ?2)
       ON CONFLICT(user_id) DO UPDATE SET plan='pro', expires_at=?2`
    ).bind(user.id, expires).run();
    return json({ ok: true, plan: 'pro', expires_at: expires });
  }

  // ===== 管理：生成订阅码 / 邀请码 =====
  // 上游连通性探针（管理口令保护）。
  // 存在的理由：硅基流动国际站曾对 CF 出口 IP 风控，本机 curl 全通、Worker 里全挂。
  // 任何新上游在写进产品前，都必须先用这个探针从 Worker 内部实测一次。
  if (p === '/api/admin/probe' && m === 'POST') {
    const b = await body();
    if (!env.ADMIN_KEY || String(b.key || '') !== env.ADMIN_KEY) return json({ error: '无权限' }, 403);
    // 通用化（2026-08-21）：target=afdian 时探测爱发电（CF 出口连不连得上必须从 Worker 内实测）
    if (String(b.target || '') === 'afdian') {
      const t0 = Date.now();
      try {
        const d = await afdianCall(env, 'query-order', { page: 1 });
        return json({
          ok: d?.ec === 200, ec: d?.ec, em: d?.em, ms: Date.now() - t0,
          orders: (d?.data?.list || []).length
        });
      } catch (e) {
        return json({ ok: false, ms: Date.now() - t0, error: String(e) }, 502);
      }
    }
    // target=asr：列出硅基流动当前可用的语音模型（§51 语音选型 —— 不实测就别写进产品）
    // 用法：{"key":"...","target":"asr"}；想测具体模型再传 model 走 sf 分支。
    if (String(b.target || '') === 'asr') {
      const t0 = Date.now();
      try {
        const r = await fetch(`${env.SILICONFLOW_BASE || 'https://api.siliconflow.cn/v1'}/models?sub_type=speech`, {
          headers: { 'Authorization': `Bearer ${env.SILICONFLOW_KEY}` }
        });
        const d = await r.json().catch(() => ({}));
        const ids = (d?.data || []).map(x => x.id);
        return json({
          ok: r.ok, status: r.status, ms: Date.now() - t0,
          count: ids.length, models: ids,
          error: r.ok ? null : (d?.message || d?.error?.message || `HTTP ${r.status}`)
        });
      } catch (e) { return json({ ok: false, ms: Date.now() - t0, error: String(e) }, 502); }
    }
    // target=sf：从 Worker 内实测硅基流动 .cn 的任意模型（premium 大陆通道选型）
    if (String(b.target || '') === 'sf') {
      const t0 = Date.now();
      const mdl = String(b.model || 'deepseek-ai/DeepSeek-V3.2');
      try {
        const r = await fetch(`${env.SILICONFLOW_BASE || 'https://api.siliconflow.cn/v1'}/chat/completions`, {
          method: 'POST',
          headers: { 'Authorization': `Bearer ${env.SILICONFLOW_KEY}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: mdl, max_tokens: 40, enable_thinking: false,
            messages: [{ role: 'user', content: '用一句中文回答：你是谁？' }] })
        });
        const d = await r.json().catch(() => ({}));
        return json({ ok: r.ok, status: r.status, ms: Date.now() - t0, model: mdl,
          content: pickText(d) || null, error: r.ok ? null : (d?.message || d?.error?.message || `HTTP ${r.status}`) });
      } catch (e) { return json({ ok: false, ms: Date.now() - t0, model: mdl, error: String(e) }, 502); }
    }
    const model = String(b.model || 'openai/gpt-5.6-luna');
    const t0 = Date.now();
    try {
      const r = await fetch('https://openrouter.ai/api/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${env.OPENROUTER_KEY}`,
          'Content-Type': 'application/json',
          'HTTP-Referer': 'https://looka.foyue.org',
          'X-Title': 'Looka'
        },
        body: JSON.stringify({
          model, max_tokens: 40,
          messages: [{ role: 'user', content: '用一句中文回答：你是谁？' }]
        })
      });
      const d = await r.json().catch(() => ({}));
      return json({
        ok: r.ok, status: r.status, ms: Date.now() - t0, model,
        content: d?.choices?.[0]?.message?.content || null,
        gen_id: d?.id || null,
        error: r.ok ? null : (d?.error?.message || d?.message || `HTTP ${r.status}`)
      });
    } catch (e) {
      return json({ ok: false, ms: Date.now() - t0, model, error: String(e) }, 502);
    }
  }

  // 健康统计（E1/E2）：错误率靠 Cloudflare 面板，这里管业务量与成本敞口
  if (p === '/api/admin/health' && m === 'POST') {
    const b = await body();
    if (!env.ADMIN_KEY || String(b.key || '') !== env.ADMIN_KEY) return json({ error: '无权限' }, 403);
    // P2-E5：24 小时内高级模型失败次数（从埋点事件表查）
    let premiumFail24h = -1;
    try {
      premiumFail24h = (await env.STATS.prepare(
        "SELECT COUNT(*) n FROM events WHERE site='looka' AND kind='premium_fail' AND ts > ?1"
      ).bind(Date.now() - 24 * 3600_000).first())?.n ?? -1;
    } catch (_) { }
    const [aiMonth, crashes24h, users, antlerOut] = await Promise.all([
      env.DB.prepare('SELECT SUM(used) s FROM ai_usage WHERE ym = ?1').bind(ym()).first(),
      env.DB.prepare('SELECT COUNT(*) c FROM crashes WHERE created_at > ?1').bind(Date.now() - 86400000).first(),
      env.DB.prepare('SELECT COUNT(DISTINCT user_id) c FROM sessions WHERE expires_at > ?1').bind(Date.now()).first(),
      env.DB.prepare("SELECT SUM(-delta) s FROM antler_ledger WHERE delta < 0 AND created_at > ?1").bind(Date.now() - 86400000).first()
    ]);
    return json({
      ok: true, time: nowISO(),
      ai_calls_month: aiMonth?.s || 0,
      crashes_24h: crashes24h?.c || 0,
      active_sessions_users: users?.c || 0,
      antler_spent_24h: antlerOut?.s || 0,
      premium_fail_24h: premiumFail24h
    });
  }

  if (p === '/api/admin/gencode' && m === 'POST') {
    const b = await body();
    if (!env.ADMIN_KEY || String(b.key || '') !== env.ADMIN_KEY) return json({ error: '无权限' }, 403);
    const count = Math.min(Math.max(Number(b.count || 1), 1), 100);
    const out = [];
    if (String(b.type || '') === 'invite') {
      for (let i = 0; i < count; i++) {
        const code = 'INV-' + randHex(3).toUpperCase() + '-' + randHex(3).toUpperCase();
        await env.DB.prepare('INSERT INTO invite_codes (code, created_at) VALUES (?1, ?2)')
          .bind(code, Date.now()).run();
        out.push(code);
      }
      return json({ ok: true, type: 'invite', codes: out });
    }
    // 鹿角码：卖鹿角包用（小袋150 / 中袋500 / 大袋1500），入购买桶
    if (String(b.type || '') === 'antler') {
      const amount = Math.min(Math.max(Number(b.amount || 150), 1), 100000);
      for (let i = 0; i < count; i++) {
        const code = 'ANTLER-' + randHex(2).toUpperCase() + '-' + randHex(2).toUpperCase();
        await env.DB.prepare(
          "INSERT INTO codes (code, plan, days, kind, amount, created_at) VALUES (?1,'free',0,'antler',?2,?3)"
        ).bind(code, amount, Date.now()).run();
        out.push(code);
      }
      return json({ ok: true, type: 'antler', amount, codes: out });
    }

    const days = Math.min(Math.max(Number(b.days || 31), 1), 3660);
    for (let i = 0; i < count; i++) {
      const code = 'LOOKA-' + randHex(2).toUpperCase() + '-' + randHex(2).toUpperCase();
      await env.DB.prepare("INSERT INTO codes (code, days, kind, created_at) VALUES (?1, ?2, 'plan', ?3)")
        .bind(code, days, Date.now()).run();
      out.push(code);
    }
    return json({ ok: true, type: 'plan', days, codes: out });
  }

  return json({ error: 'Not Found' }, 404);
}

// ---------- Cron：过期会话 / 限流窗口 / 重置令牌 / 90 天墓碑 / 崩溃日志截尾 ----------
/**
 * 每日自检（E1/E2）：异常时给自己发邮件，不用天天盯面板。
 * 只在越过阈值时发信 —— 每天一封"一切正常"很快就没人看了。
 */
async function dailyAlert(env) {
  try {
    const alerts = [];
    const aiMonth = await env.DB.prepare('SELECT SUM(used) s FROM ai_usage WHERE ym = ?1').bind(ym()).first();
    const aiTotal = aiMonth?.s || 0;
    const aiLimit = Number(env.AI_MONTH_ALERT || 20000);
    if (aiTotal > aiLimit) alerts.push(`本月 AI 调用已达 ${aiTotal}（阈值 ${aiLimit}）—— 检查是否被滥用`);
    const crashes = await env.DB.prepare('SELECT COUNT(*) c FROM crashes WHERE created_at > ?1')
      .bind(Date.now() - 86400000).first();
    if ((crashes?.c || 0) > 0) alerts.push(`近 24 小时收到 ${crashes.c} 条崩溃上报`);
    const antlerOut = await env.DB.prepare(
      'SELECT SUM(-delta) s FROM antler_ledger WHERE delta < 0 AND created_at > ?1'
    ).bind(Date.now() - 86400000).first();
    const spent = antlerOut?.s || 0;
    if (spent > Number(env.ANTLER_DAY_ALERT || 5000)) alerts.push(`近 24 小时鹿角消耗 ${spent}，超出预期`);
    // P3-2-10 新增阈值（2026-08-22）
    // ① 未认领订单：有人付了钱没拿到货，最高优先级
    const unclaimed = await env.DB.prepare('SELECT COUNT(*) c FROM pay_orders WHERE user_id IS NULL').first();
    if ((unclaimed?.c || 0) > 0) alerts.push(`🔴 有 ${unclaimed.c} 笔付款订单未归属到账号，请到 foyue.org/admin → 订阅 处理`);
    if (env.STATS) {
      // ② 高级模型失败率 > 10%
      const [pf, ai] = await Promise.all([
        env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='premium_fail' AND ts>?1").bind(Date.now() - 86400000).first(),
        env.STATS.prepare("SELECT COUNT(*) c FROM events WHERE kind='ai_chat' AND ts>?1").bind(Date.now() - 86400000).first()
      ]);
      if ((ai?.c || 0) >= 10 && (pf?.c || 0) / ai.c > 0.1) {
        alerts.push(`高级模型 24 小时失败 ${pf.c}/${ai.c} 次（>10%），查 Cloudflare 日志「premium fail」`);
      }
      // ③ 注册归零（昨日 >5 而今日 0，可能注册链路挂了）
      const y = await env.STATS.prepare(
        "SELECT cnt FROM daily_stats WHERE site='looka' AND kind='register' AND day=?1"
      ).bind(new Date(Date.now() - 86400000 + 8 * 3600_000).toISOString().slice(0, 10)).first();
      const t0 = await env.STATS.prepare(
        "SELECT COUNT(*) c FROM events WHERE site='looka' AND kind='register' AND ts>?1"
      ).bind(Date.parse(today() + 'T00:00:00+08:00')).first();
      if ((y?.cnt || 0) > 5 && (t0?.c || 0) === 0) alerts.push('昨日注册>5 而今日为 0 —— 注册链路可能挂了');
    }
    if (alerts.length) {
      await sendEmail(env, env.ALERT_MAIL || 'looka01@qq.com', `⚠️ Looka 每日自检：${alerts.length} 项异常`,
        mailShell('每日自检发现异常', alerts.map(a => `<p>· ${a}</p>`).join('')));
    }
  } catch (_) { /* 自检失败不能影响清理 */ }
}

async function cleanup(env) {
  const now = Date.now();
  await env.DB.prepare('DELETE FROM sessions WHERE expires_at <= ?1').bind(now).run();
  await env.DB.prepare('DELETE FROM rate_limits WHERE win < ?1').bind(now - 2 * 24 * 3600_000).run();
  await env.DB.prepare('DELETE FROM reset_tokens WHERE expires_at < ?1').bind(now).run();
  await env.DB.prepare('DELETE FROM items WHERE deleted = 1 AND updated_at < ?1')
    .bind(now - 90 * 24 * 3600_000).run();
  await env.DB.prepare(
    'DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY id DESC LIMIT 500)'
  ).run();
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (e) {
      return new Response(JSON.stringify({ error: '服务器开小差了，稍后再试' }), {
        status: 500, headers: { 'Content-Type': 'application/json; charset=utf-8' }
      });
    }
  },
  async scheduled(event, env, ctx) {
    // */5 = 只做爱发电拉单对账（webhook 没配/掉单时，最慢 5 分钟自动开通）；
    // 03:17 北京（19:17 UTC）= 全套：对账 + 清理 + 埋点汇总 + 告警
    if (event.cron === '*/5 * * * *') {
      ctx.waitUntil(afdianReconcile(env));
      return;
    }
    ctx.waitUntil(
      afdianReconcile(env)
        .then(() => cleanup(env))
        .then(() => statsRollup(env))
        .then(() => dailyAlert(env))
    );
  }
};

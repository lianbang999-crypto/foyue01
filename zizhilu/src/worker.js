// ============================================================
// 自知录 · 极简功过格记事本 —— Cloudflare Worker
// 存储：D1（账号/记录）+ R2（图片/视频/音频）
// AI：硅基流动（省察 / 润色 / 问格 / 看图）
// ============================================================

const COOKIE = 'zzl_token';
const TWA_PACKAGE = 'org.foyue.zhi';   // Android TWA 包名
const AI_DAILY_LIMIT = 100;            // 每人每日免费 AI 次数

// 邀请码字符集：去掉易混淆的 0/O/1/I/L
const CODE_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
function newInviteCode() {
  const buf = crypto.getRandomValues(new Uint8Array(8));
  return [...buf].map(b => CODE_CHARS[b % CODE_CHARS.length]).join('');
}
// 为用户创建专属邀请码（撞码时重试）
async function ensureInvite(env, userId, quota) {
  const own = await env.DB.prepare('SELECT * FROM invites WHERE owner_id = ?1').bind(userId).first();
  if (own) return own;
  for (let i = 0; i < 5; i++) {
    const code = newInviteCode();
    try {
      await env.DB.prepare('INSERT INTO invites (code, owner_id, max_uses, used, created_at) VALUES (?1,?2,?3,0,?4)')
        .bind(code, userId, quota, nowISO()).run();
      return { code, owner_id: userId, max_uses: quota, used: 0 };
    } catch { /* 撞码，重试 */ }
  }
  throw new Error('邀请码生成失败，请稍后再试');
}
// 账号遮罩（展示「我邀请的人」时不暴露完整账号）
function maskAccount(a) {
  const s = String(a || '');
  if (s.includes('@')) {
    const [n, d] = s.split('@');
    return (n.length <= 2 ? n[0] + '*' : n.slice(0, 2) + '***') + '@' + d;
  }
  return s.length > 7 ? s.slice(0, 3) + '****' + s.slice(-4) : s;
}
const PBKDF2_ITER = 100000;
const KINDS = ['merit', 'fault', 'note'];   // 功 / 过 / 记

// ---------- 通用工具 ----------
const enc = new TextEncoder();
const json = (data, status = 200, headers = {}) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers } });

const toHex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
const fromHex = h => new Uint8Array((h.match(/../g) || []).map(x => parseInt(x, 16)));
const randHex = n => toHex(crypto.getRandomValues(new Uint8Array(n)));
const nowISO = () => new Date().toISOString();
const utcDay = () => nowISO().slice(0, 10);

const isEmail = s => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(s);
const isPhone = s => /^1[3-9]\d{9}$/.test(s);
const isDay = s => /^\d{4}-\d{2}-\d{2}$/.test(s);

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// ---------- 密码（PBKDF2-SHA256，Web Crypto 原生） ----------
async function derive(pw, salt, iter) {
  const key = await crypto.subtle.importKey('raw', enc.encode(pw), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, key, 256);
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
function getToken(request) {
  const m = (request.headers.get('cookie') || '').match(/(?:^|;\s*)zzl_token=([a-f0-9]{64})/);
  return m && m[1];
}
function sessionCookie(token, request, maxAge = 60 * 24 * 3600) {
  const secure = new URL(request.url).protocol === 'https:' ? '; Secure' : '';
  return `${COOKIE}=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure}`;
}
async function newSession(env, request, userId) {
  const token = randHex(32);
  await env.DB.prepare('INSERT INTO sessions (token, user_id, expires_at) VALUES (?1, ?2, ?3)')
    .bind(token, userId, Date.now() + 60 * 24 * 3600 * 1000).run();
  return sessionCookie(token, request);
}
async function getUser(request, env) {
  const token = getToken(request);
  if (!token) return null;
  const s = await env.DB.prepare('SELECT * FROM sessions WHERE token = ?1').bind(token).first();
  if (!s) return null;
  if (s.expires_at <= Date.now()) {
    await env.DB.prepare('DELETE FROM sessions WHERE token = ?1').bind(token).run();
    return null;
  }
  return env.DB.prepare('SELECT id, account, kind, created_at FROM users WHERE id = ?1').bind(s.user_id).first();
}

// ---------- Web Push（VAPID，无载荷推送：省去消息加密，SW 端显示固定文案） ----------
const b64urlBuf = buf => btoa(String.fromCharCode(...new Uint8Array(buf)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64decode = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));

async function vapidJWT(env, audience) {
  const key = await crypto.subtle.importKey('pkcs8', b64decode(env.VAPID_PRIVATE_KEY),
    { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const head = b64urlBuf(enc.encode(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const payload = b64urlBuf(enc.encode(JSON.stringify({
    aud: audience, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: 'mailto:lebang001@qq.com'
  })));
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(`${head}.${payload}`));
  return `${head}.${payload}.${b64urlBuf(sig)}`;
}
async function sendPush(env, sub) {
  const jwt = await vapidJWT(env, new URL(sub.endpoint).origin);
  const r = await fetch(sub.endpoint, {
    method: 'POST',
    headers: { TTL: '86400', Urgency: 'normal', Authorization: `vapid t=${jwt}, k=${env.VAPID_PUBLIC_KEY}` },
    signal: AbortSignal.timeout(15000)
  });
  return r.status;
}

// ---------- AI（硅基流动，OpenAI 兼容） ----------
async function chatAI(env, messages, { model, maxTokens = 900, temperature = 0.7 } = {}) {
  if (!env.SILICONFLOW_KEY) throw new Error('未配置 SILICONFLOW_KEY');
  const payload = { model: model || env.CHAT_MODEL, messages, max_tokens: maxTokens, temperature, enable_thinking: false };
  const call = () => fetch(env.SILICONFLOW_BASE + '/chat/completions', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + env.SILICONFLOW_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(90000)
  });
  let r = await call();
  if (!r.ok) {
    const errText = (await r.text()).slice(0, 300);
    if (r.status === 400 && errText.includes('enable_thinking')) {
      // 部分模型（如视觉模型）不接受 enable_thinking 参数，去掉后重试一次
      delete payload.enable_thinking;
      r = await call();
    } else if (r.status === 503 || r.status === 429) {
      // 上游繁忙，稍候自动重试一次
      await new Promise(ok => setTimeout(ok, 2500));
      r = await call();
    } else {
      throw new Error(`AI 服务异常（${r.status}）：${errText.slice(0, 160)}`);
    }
    if (!r.ok) throw new Error(`AI 服务繁忙，请稍后再试（${r.status}）：${(await r.text()).slice(0, 120)}`);
  }
  const j = await r.json();
  return (j.choices?.[0]?.message?.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
}

const KIND_LABEL = { merit: '功', fault: '过', note: '记' };
function noteLine(n) {
  const tag = n.kind === 'note' ? '记' : `${KIND_LABEL[n.kind]}+${n.points}`;
  let atts = [];
  try { atts = JSON.parse(n.attachments); } catch {}
  const attDesc = atts.map(a => {
    const label = { image: '图片', video: '视频', audio: '音频' }[a.type] || '附件';
    return a.caption ? `[${label}：${a.caption}]` : `[${label}]`;
  }).join(' ');
  return `[${tag}] ${n.content} ${attDesc}`.trim();
}

// ---------- 附件清洗 ----------
function cleanAttachments(list, userId) {
  if (!Array.isArray(list)) return [];
  const prefix = `/media/u${userId}/`;
  return list.filter(a => a && typeof a.url === 'string' && a.url.startsWith(prefix))
    .slice(0, 9)
    .map(a => ({
      url: a.url,
      type: ['image', 'video', 'audio'].includes(a.type) ? a.type : 'image',
      mime: String(a.mime || '').slice(0, 80),
      name: String(a.name || '').slice(0, 120),
      caption: String(a.caption || '').slice(0, 500)
    }));
}
async function deleteAttachmentFiles(env, userId, attachmentsJson) {
  try {
    const atts = JSON.parse(attachmentsJson);
    for (const a of atts) {
      const key = String(a.url || '').replace(/^\/media\//, '');
      if (key.startsWith(`u${userId}/`)) await env.MEDIA.delete(key);
    }
  } catch {}
}

// ============================================================
// 管理后台接口（统一由 foyue.org/admin 调用）
// 鉴权：Authorization: Bearer <ADMIN_TOKEN>，与主站同一枚口令
// 跨站：仅放行 foyue.org 白名单
// ============================================================
const ADMIN_ORIGINS = ['https://foyue.org', 'https://www.foyue.org'];
function adminCors(request) {
  const origin = request.headers.get('Origin') || '';
  const allow = ADMIN_ORIGINS.includes(origin) ? origin : ADMIN_ORIGINS[0];
  return {
    'Access-Control-Allow-Origin': allow,
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Max-Age': '86400',
    'Vary': 'Origin'
  };
}
async function adminRoute(request, env, p) {
  const cors = adminCors(request);
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
  if (!env.ADMIN_TOKEN) return json({ error: '后台未配置 ADMIN_TOKEN' }, 503, cors);
  if (request.headers.get('Authorization') !== `Bearer ${env.ADMIN_TOKEN}`)
    return json({ error: '口令错误' }, 401, cors);
  const body = async () => { try { return await request.json(); } catch { return {}; } };

  // 概览
  if (p === '/api/admin/stat') {
    const q = s => env.DB.prepare(s).first();
    const [users, notes, shared, pend, today] = await Promise.all([
      q('SELECT COUNT(*) c FROM users'),
      q('SELECT COUNT(*) c FROM notes'),
      q('SELECT COUNT(*) c FROM notes WHERE shared = 1'),
      q('SELECT COUNT(*) c FROM reports WHERE handled = 0'),
      q(`SELECT COUNT(DISTINCT user_id) c FROM notes WHERE created_at >= '${utcDay()}'`)
    ]);
    const ai = await q(`SELECT COALESCE(SUM(used),0) s FROM ai_usage WHERE day = '${utcDay()}'`);
    return json({
      users: users.c, notes: notes.c, shared: shared.c,
      pendingReports: pend.c, activeToday: today.c, aiToday: ai.s
    }, 200, cors);
  }

  // 举报列表（未处理优先）
  if (p === '/api/admin/reports') {
    const { results } = await env.DB.prepare(
      `SELECT r.id, r.note_id, r.reason, r.created_at, r.handled,
              n.content, n.kind, n.shared, n.attachments,
              u.account AS author_account, u.nickname AS author_nick,
              b.account AS reporter
       FROM reports r
       LEFT JOIN notes n ON n.id = r.note_id
       LEFT JOIN users u ON u.id = n.user_id
       LEFT JOIN users b ON b.id = r.by_user
       ORDER BY r.handled ASC, r.created_at DESC LIMIT 200`
    ).all();
    return json({ reports: results }, 200, cors);
  }

  // 下架（从广场撤下，不删原记录）/ 标记已处理
  if (p === '/api/admin/takedown' && request.method === 'POST') {
    const b = await body();
    const noteId = Number(b.noteId);
    if (b.takedown !== false)
      await env.DB.prepare("UPDATE notes SET shared = 0, shared_at = '' WHERE id = ?1").bind(noteId).run();
    await env.DB.prepare('UPDATE reports SET handled = 1 WHERE note_id = ?1').bind(noteId).run();
    return json({ ok: true }, 200, cors);
  }

  // 广场最近内容（便于巡查）
  if (p === '/api/admin/plaza') {
    const { results } = await env.DB.prepare(
      `SELECT n.id, n.kind, n.content, n.day, n.shared_at, n.attachments,
              u.account, u.nickname
       FROM notes n JOIN users u ON u.id = n.user_id
       WHERE n.shared = 1 ORDER BY n.shared_at DESC LIMIT 100`
    ).all();
    return json({ items: results }, 200, cors);
  }

  // 用户列表（含邀请关系与用量）
  if (p === '/api/admin/users') {
    const { results } = await env.DB.prepare(
      `SELECT u.id, u.account, u.nickname, u.created_at,
              (SELECT COUNT(*) FROM notes WHERE user_id = u.id) AS notes,
              (SELECT COALESCE(SUM(used),0) FROM ai_usage WHERE user_id = u.id) AS ai_total,
              (SELECT code FROM invites WHERE owner_id = u.id) AS code,
              (SELECT used FROM invites WHERE owner_id = u.id) AS invited,
              (SELECT max_uses FROM invites WHERE owner_id = u.id) AS quota,
              (SELECT a.account FROM invite_uses iu JOIN users a ON a.id = iu.inviter_id WHERE iu.user_id = u.id) AS inviter
       FROM users u ORDER BY u.id DESC LIMIT 500`
    ).all();
    return json({ users: results }, 200, cors);
  }

  // 调整某人邀请名额
  if (p === '/api/admin/quota' && request.method === 'POST') {
    const b = await body();
    const n = Math.max(0, Math.min(999, parseInt(b.quota, 10) || 0));
    await env.DB.prepare('UPDATE invites SET max_uses = ?1 WHERE owner_id = ?2').bind(n, Number(b.userId)).run();
    return json({ ok: true, quota: n }, 200, cors);
  }

  return json({ error: '接口不存在' }, 404, cors);
}

// ---------- 主路由 ----------

// ---------- 全站埋点（P3-2-7，2026-08-22）：与 looka 同一套 events 表 ----------
// 失败绝不影响业务；不存原始 IP/完整 UA（此处只记站点与事件，够全站后台对比用）
async function zTrack(env, kind, userId) {
  try {
    if (!env.STATS) return;
    await env.STATS.prepare(
      "INSERT INTO events (site, kind, user_id, ip_hash, ua_class, ref, meta, ts) VALUES ('zhi', ?1, ?2, NULL, '', '', '{}', ?3)"
    ).bind(kind, userId ?? null, Date.now()).run();
  } catch (_) { }
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      console.error('[error]', e.message);
      return json({ error: e.message || '服务器开小差了' }, 500);
    }
  },

  // 每日 13:00 UTC（北京 21:00）：当天（北京时区）还没记录的订阅用户收到提醒
  async scheduled(event, env) {
    const bjDay = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);
    const { results: subs } = await env.DB.prepare('SELECT * FROM push_subs').all();
    for (const s of subs) {
      try {
        const has = await env.DB.prepare('SELECT 1 x FROM notes WHERE user_id = ?1 AND day = ?2 LIMIT 1')
          .bind(s.user_id, bjDay).first();
        if (has) continue;
        const st = await sendPush(env, s);
        if (st === 404 || st === 410)
          await env.DB.prepare('DELETE FROM push_subs WHERE endpoint = ?1').bind(s.endpoint).run();
      } catch (e) { console.error('[push]', e.message); }
    }
  }
};

async function route(request, env) {
  const url = new URL(request.url);
  const p = url.pathname;
  const m = request.method;
  const body = async () => { try { return await request.json(); } catch { return {}; } };

  // ===== 账号 =====
  if (p === '/api/register' && m === 'POST') {
    const b = await body();
    const account = String(b.account || '').trim().toLowerCase();
    const password = String(b.password || '');
    const kind = isEmail(account) ? 'email' : isPhone(account) ? 'phone' : null;
    if (!kind) return json({ error: '请输入有效的邮箱或手机号' }, 400);
    if (password.length < 6) return json({ error: '密码至少 6 位' }, 400);
    // 邀请码：管理员总码（wrangler.jsonc 的 INVITE_CODE）或任一用户的专属码
    const invite = String(b.invite || '').trim().toUpperCase();
    const adminCode = String(env.INVITE_CODE || '').toUpperCase();
    const byAdmin = adminCode && invite === adminCode;
    let inviter = null;
    if (!byAdmin) {
      if (!invite) return json({ error: '请填写邀请码' }, 403);
      inviter = await env.DB.prepare('SELECT * FROM invites WHERE code = ?1').bind(invite).first();
      if (!inviter) return json({ error: '邀请码不正确' }, 403);
      if (inviter.used >= inviter.max_uses) return json({ error: '这个邀请码的名额已用完' }, 403);
    }
    const exists = await env.DB.prepare('SELECT id FROM users WHERE account = ?1').bind(account).first();
    if (exists) return json({ error: '该账号已注册，请直接登录' }, 409);
    // 原子占用名额：并发时只有一个请求能把 used 加上去
    if (inviter) {
      const occupy = await env.DB.prepare('UPDATE invites SET used = used + 1 WHERE code = ?1 AND used < max_uses')
        .bind(invite).run();
      if (!occupy.meta.changes) return json({ error: '这个邀请码的名额已用完' }, 403);
    }
    const r = await env.DB.prepare('INSERT INTO users (account, kind, pass_hash, created_at) VALUES (?1, ?2, ?3, ?4)')
      .bind(account, kind, await hashPassword(password), nowISO()).run();
    const userId = r.meta.last_row_id;
    if (inviter) {
      await env.DB.prepare('INSERT INTO invite_uses (user_id, code, inviter_id, created_at) VALUES (?1,?2,?3,?4)')
        .bind(userId, invite, inviter.owner_id, nowISO()).run();
    }
    // 新用户自己的邀请码：管理员注册的名额更多，受邀者名额较少（防止 AI Key 无限扩散）
    const quota = Math.max(0, parseInt(byAdmin ? (env.ADMIN_INVITE_QUOTA ?? 20) : (env.DEFAULT_INVITE_QUOTA ?? 2), 10) || 0);
    await ensureInvite(env, userId, quota).catch(() => {});
    const cookie = await newSession(env, request, userId);
    await zTrack(env, 'register', userId);
    return json({ ok: true, user: { id: userId, account, kind } }, 200, { 'Set-Cookie': cookie });
  }

  if (p === '/api/login' && m === 'POST') {
    const b = await body();
    const account = String(b.account || '').trim().toLowerCase();
    // 失败 5 次锁 10 分钟（防暴力破解）
    const lf = await env.DB.prepare('SELECT * FROM login_fails WHERE account = ?1').bind(account).first();
    if (lf && lf.locked_until > Date.now())
      return json({ error: `尝试次数过多，请 ${Math.ceil((lf.locked_until - Date.now()) / 60000)} 分钟后再试` }, 429);
    const u = await env.DB.prepare('SELECT * FROM users WHERE account = ?1').bind(account).first();
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
    const cookie = await newSession(env, request, u.id);
    await zTrack(env, 'login', u.id);
    return json({ ok: true, user: { id: u.id, account: u.account, kind: u.kind } }, 200, { 'Set-Cookie': cookie });
  }

  if (p === '/api/logout' && m === 'POST') {
    const token = getToken(request);
    if (token) await env.DB.prepare('DELETE FROM sessions WHERE token = ?1').bind(token).run();
    return json({ ok: true }, 200, { 'Set-Cookie': sessionCookie('deleted', request, 0) });
  }

  // Android TWA 数字资产链接（供系统校验 App 与网站同属）
  if (p === '/.well-known/assetlinks.json' && m === 'GET') {
    const fps = String(env.TWA_FINGERPRINT || '').split(',').map(s => s.trim()).filter(Boolean);
    if (!fps.length) return json({ error: '未配置 TWA_FINGERPRINT' }, 404, { 'Cache-Control': 'no-cache' });
    return json([{
      relation: ['delegate_permission/common.handle_all_urls'],
      target: { namespace: 'android_app', package_name: TWA_PACKAGE, sha256_cert_fingerprints: fps }
    }], 200, { 'Cache-Control': 'no-cache' });
  }

  // ===== 管理后台：走 Bearer 口令，不用 Cookie，故置于登录检查之前 =====
  if (p.startsWith('/api/admin/')) return adminRoute(request, env, p);

  // ===== 以下路由需登录 =====
  const user = await getUser(request, env);
  const needAuth = p.startsWith('/api/') || p.startsWith('/media/');
  if (needAuth && !user) return json({ error: '未登录' }, 401);

  if (p === '/api/me' && m === 'GET') {
    const full = await env.DB.prepare('SELECT nickname FROM users WHERE id = ?1').bind(user.id).first();
    const q = await env.DB.prepare('SELECT used FROM ai_usage WHERE user_id = ?1 AND day = ?2')
      .bind(user.id, utcDay()).first();
    return json({ user: { ...user, nickname: full?.nickname || '' }, ai: { used: q?.used || 0, limit: AI_DAILY_LIMIT } });
  }

  // 设置法名/昵称（广场署名用）
  if (p === '/api/nickname' && m === 'POST') {
    const nick = String((await body()).nickname || '').trim().replace(/\s+/g, ' ').slice(0, 24);
    if (/[<>]/.test(nick)) return json({ error: '昵称不能包含尖括号' }, 400);
    await env.DB.prepare('UPDATE users SET nickname = ?1 WHERE id = ?2').bind(nick, user.id).run();
    return json({ ok: true, nickname: nick });
  }

  // AI 配额：每次调用前领用一次（客户端直连 AI，故由此处记账）
  if (p === '/api/ai/use' && m === 'POST') {
    const day = utcDay();
    await env.DB.prepare(
      `INSERT INTO ai_usage (user_id, day, used) VALUES (?1, ?2, 1)
       ON CONFLICT(user_id, day) DO UPDATE SET used = used + 1`
    ).bind(user.id, day).run();
    const row = await env.DB.prepare('SELECT used FROM ai_usage WHERE user_id = ?1 AND day = ?2')
      .bind(user.id, day).first();
    const used = row?.used || 0;
    if (used > AI_DAILY_LIMIT)
      return json({ error: `今日 AI 已用满 ${AI_DAILY_LIMIT} 次，明日再来`, used, limit: AI_DAILY_LIMIT }, 429);
    return json({ ok: true, used, limit: AI_DAILY_LIMIT, left: AI_DAILY_LIMIT - used });
  }

  // 我的邀请码（老用户首次访问时补发）
  if (p === '/api/invite' && m === 'GET') {
    const quota = Math.max(0, parseInt(env.DEFAULT_INVITE_QUOTA ?? 2, 10) || 0);
    const inv = await ensureInvite(env, user.id, quota);
    const { results: uses } = await env.DB.prepare(
      `SELECT u.account, iu.created_at FROM invite_uses iu
       JOIN users u ON u.id = iu.user_id
       WHERE iu.inviter_id = ?1 ORDER BY iu.created_at DESC LIMIT 50`
    ).bind(user.id).all();
    return json({
      code: inv.code,
      used: inv.used,
      maxUses: inv.max_uses,
      invitees: uses.map(u => ({ account: maskAccount(u.account), at: u.created_at.slice(0, 10) }))
    });
  }

  // 修改密码（改后仅保留当前登录设备）
  if (p === '/api/change-password' && m === 'POST') {
    const b = await body();
    const full = await env.DB.prepare('SELECT * FROM users WHERE id = ?1').bind(user.id).first();
    if (!(await verifyPassword(String(b.old || ''), full.pass_hash))) return json({ error: '原密码不正确' }, 403);
    if (String(b.new || '').length < 6) return json({ error: '新密码至少 6 位' }, 400);
    await env.DB.prepare('UPDATE users SET pass_hash = ?1 WHERE id = ?2')
      .bind(await hashPassword(String(b.new)), user.id).run();
    await env.DB.prepare('DELETE FROM sessions WHERE user_id = ?1 AND token != ?2')
      .bind(user.id, getToken(request)).run();
    return json({ ok: true });
  }

  // ===== 记录 =====
  if (p === '/api/notes' && m === 'GET') {
    const q = String(url.searchParams.get('q') || '').trim();
    const before = String(url.searchParams.get('before') || '');   // 键集分页游标（created_at）
    const LIMIT = 300;
    let stmt;
    if (q) {
      stmt = env.DB.prepare('SELECT * FROM notes WHERE user_id = ?1 AND content LIKE ?2 ORDER BY created_at DESC, id DESC LIMIT 500')
        .bind(user.id, `%${q}%`);
    } else if (before) {
      stmt = env.DB.prepare('SELECT * FROM notes WHERE user_id = ?1 AND created_at < ?2 ORDER BY created_at DESC, id DESC LIMIT ' + LIMIT)
        .bind(user.id, before);
    } else {
      stmt = env.DB.prepare('SELECT * FROM notes WHERE user_id = ?1 ORDER BY created_at DESC, id DESC LIMIT ' + LIMIT)
        .bind(user.id);
    }
    const { results: notes } = await stmt.all();
    for (const n of notes) { try { n.attachments = JSON.parse(n.attachments); } catch { n.attachments = []; } }
    const hasMore = !q && notes.length === LIMIT;
    if (before) return json({ notes, hasMore });   // 追加页不重复带 days
    const { results: aiRows } = await env.DB.prepare('SELECT * FROM day_ai WHERE user_id = ?1').bind(user.id).all();
    const days = {};
    for (const d of aiRows) days[d.day] = { summary: d.summary, mood: d.mood };
    return json({ notes, days, hasMore });
  }

  // ===== 广场 =====
  // 分享 / 取消分享
  const shareMatch = p.match(/^\/api\/notes\/(\d+)\/share$/);
  if (shareMatch && m === 'POST') {
    const n = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(Number(shareMatch[1])).first();
    if (!n || n.user_id !== user.id) return json({ error: '记录不存在' }, 404);
    const on = !!(await body()).shared;
    if (on && !n.content.trim()) return json({ error: '纯附件的记录请先写一句话再分享' }, 400);
    await env.DB.prepare('UPDATE notes SET shared = ?1, shared_at = ?2 WHERE id = ?3')
      .bind(on ? 1 : 0, on ? nowISO() : '', n.id).run();
    return json({ ok: true, shared: on });
  }

  // 广场列表（分页，按分享时间倒序）
  if (p === '/api/plaza' && m === 'GET') {
    const before = String(url.searchParams.get('before') || '');
    const LIMIT = 30;
    const stmt = before
      ? env.DB.prepare(`SELECT n.id, n.kind, n.content, n.attachments, n.day, n.shared_at, n.user_id,
                               u.nickname FROM notes n JOIN users u ON u.id = n.user_id
                        WHERE n.shared = 1 AND n.shared_at < ?1 ORDER BY n.shared_at DESC LIMIT ${LIMIT}`).bind(before)
      : env.DB.prepare(`SELECT n.id, n.kind, n.content, n.attachments, n.day, n.shared_at, n.user_id,
                               u.nickname FROM notes n JOIN users u ON u.id = n.user_id
                        WHERE n.shared = 1 ORDER BY n.shared_at DESC LIMIT ${LIMIT}`);
    const { results } = await stmt.all();
    const items = results.map(r => {
      let atts = [];
      try { atts = JSON.parse(r.attachments); } catch {}
      return {
        id: r.id, kind: r.kind, content: r.content, day: r.day, shared_at: r.shared_at,
        author: r.nickname || '匿名', mine: r.user_id === user.id,
        // 广场里的媒体走公开路径
        attachments: atts.map(a => ({ ...a, url: a.url.replace('/media/', '/pub/') }))
      };
    });
    return json({ items, hasMore: items.length === LIMIT });
  }

  // 举报
  if (p === '/api/report' && m === 'POST') {
    const b = await body();
    const noteId = Number(b.noteId);
    const n = await env.DB.prepare('SELECT id FROM notes WHERE id = ?1 AND shared = 1').bind(noteId).first();
    if (!n) return json({ error: '内容不存在' }, 404);
    await env.DB.prepare('INSERT INTO reports (note_id, by_user, reason, created_at) VALUES (?1,?2,?3,?4)')
      .bind(noteId, user.id, String(b.reason || '').slice(0, 200), nowISO()).run();
    return json({ ok: true });
  }

  // 全量导出（备份用，不分页）
  if (p === '/api/export' && m === 'GET') {
    const { results: notes } = await env.DB
      .prepare('SELECT * FROM notes WHERE user_id = ?1 ORDER BY created_at DESC, id DESC').bind(user.id).all();
    for (const n of notes) { try { n.attachments = JSON.parse(n.attachments); } catch { n.attachments = []; } }
    const { results: aiRows } = await env.DB.prepare('SELECT * FROM day_ai WHERE user_id = ?1').bind(user.id).all();
    const days = {};
    for (const d of aiRows) days[d.day] = { summary: d.summary, mood: d.mood };
    return json({ notes, days });
  }

  // 导入 JSON 备份（按 created_at 去重；附件仅保留本账号 URL，媒体文件本身不在 JSON 里）
  if (p === '/api/import' && m === 'POST') {
    if (Number(request.headers.get('content-length') || 0) > 8 * 1024 * 1024)
      return json({ error: '备份文件过大（>8MB）' }, 413);
    const b = await body();
    const list = Array.isArray(b.notes) ? b.notes.slice(0, 10000) : [];
    if (!list.length) return json({ error: '备份里没有可导入的记录' }, 400);
    const { results: existRows } = await env.DB
      .prepare('SELECT created_at FROM notes WHERE user_id = ?1').bind(user.id).all();
    const exist = new Set(existRows.map(r => r.created_at));
    let imported = 0, skipped = 0;
    const stmts = [];
    const ins = env.DB.prepare('INSERT INTO notes (user_id, day, kind, points, content, attachments, created_at, updated_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)');
    for (const raw of list) {
      const content = String(raw?.content || '').trim().slice(0, 20000);
      const day = isDay(String(raw?.day || '')) ? raw.day : null;
      const created = /^\d{4}-\d{2}-\d{2}T[\d:.]+Z?$/.test(String(raw?.created_at || '')) ? raw.created_at : null;
      if (!day || !content || !created || exist.has(created)) { skipped++; continue; }
      exist.add(created);
      const kind = KINDS.includes(raw.kind) ? raw.kind : 'note';
      const points = kind === 'note' ? 0 : Math.min(1000, Math.max(1, parseInt(raw.points, 10) || 1));
      const atts = cleanAttachments(raw.attachments, user.id);
      stmts.push(ins.bind(user.id, day, kind, points, content, JSON.stringify(atts), created, nowISO()));
      imported++;
    }
    for (let i = 0; i < stmts.length; i += 50) await env.DB.batch(stmts.slice(i, i + 50));
    // 省察/月报存档一并恢复（不覆盖已有）
    if (b.days && typeof b.days === 'object') {
      const up = env.DB.prepare('INSERT OR IGNORE INTO day_ai (user_id, day, summary, mood, updated_at) VALUES (?1,?2,?3,?4,?5)');
      const dayStmts = [];
      for (const [day, v] of Object.entries(b.days).slice(0, 2000)) {
        if (!isDay(day) || !v?.summary) continue;
        dayStmts.push(up.bind(user.id, day, String(v.summary).slice(0, 500), String(v.mood || '').slice(0, 8), nowISO()));
      }
      for (let i = 0; i < dayStmts.length; i += 50) await env.DB.batch(dayStmts.slice(i, i + 50));
    }
    return json({ imported, skipped });
  }

  if (p === '/api/notes' && m === 'POST') {
    const b = await body();
    const content = String(b.content || '').trim().slice(0, 20000);
    const attachments = cleanAttachments(b.attachments, user.id);
    if (!content && !attachments.length) return json({ error: '写点什么，或添加一个附件' }, 400);
    const kind = KINDS.includes(b.kind) ? b.kind : 'note';
    let points = kind === 'note' ? 0 : Math.min(1000, Math.max(1, parseInt(b.points, 10) || 1));
    const day = isDay(b.day) ? b.day : utcDay();
    const t = nowISO();
    const r = await env.DB.prepare(
      'INSERT INTO notes (user_id, day, kind, points, content, attachments, created_at, updated_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)'
    ).bind(user.id, day, kind, points, content, JSON.stringify(attachments), t, t).run();
    const note = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(r.meta.last_row_id).first();
    note.attachments = JSON.parse(note.attachments);
    return json({ note });
  }

  // 保存附件配文（浏览器直连 AI 生成后回存）
  const capMatch = p.match(/^\/api\/notes\/(\d+)\/caption$/);
  if (capMatch && m === 'POST') {
    const n = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(Number(capMatch[1])).first();
    if (!n || n.user_id !== user.id) return json({ error: '记录不存在' }, 404);
    const b = await body();
    const atts = JSON.parse(n.attachments);
    const idx = Number(b.index);
    if (!atts[idx]) return json({ error: '附件不存在' }, 404);
    atts[idx].caption = String(b.caption || '').trim().slice(0, 500);
    await env.DB.prepare('UPDATE notes SET attachments = ?1, updated_at = ?2 WHERE id = ?3')
      .bind(JSON.stringify(atts), nowISO(), n.id).run();
    return json({ caption: atts[idx].caption });
  }

  const noteMatch = p.match(/^\/api\/notes\/(\d+)$/);
  if (noteMatch && (m === 'PUT' || m === 'DELETE')) {
    const n = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(Number(noteMatch[1])).first();
    if (!n || n.user_id !== user.id) return json({ error: '记录不存在' }, 404);

    if (m === 'DELETE') {
      await deleteAttachmentFiles(env, user.id, n.attachments);
      await env.DB.prepare('DELETE FROM notes WHERE id = ?1').bind(n.id).run();
      return json({ ok: true });
    }

    const b = await body();
    const content = String(b.content ?? n.content).trim().slice(0, 20000);
    const kind = KINDS.includes(b.kind) ? b.kind : n.kind;
    const points = kind === 'note' ? 0 : Math.min(1000, Math.max(1, parseInt(b.points, 10) || n.points || 1));
    const attachments = b.attachments !== undefined ? cleanAttachments(b.attachments, user.id) : JSON.parse(n.attachments);
    if (!content && !attachments.length) return json({ error: '内容不能为空' }, 400);
    await env.DB.prepare('UPDATE notes SET content = ?1, kind = ?2, points = ?3, attachments = ?4, updated_at = ?5 WHERE id = ?6')
      .bind(content, kind, points, JSON.stringify(attachments), nowISO(), n.id).run();
    const fresh = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(n.id).first();
    fresh.attachments = JSON.parse(fresh.attachments);
    return json({ note: fresh });
  }

  // ===== 上传：原始文件流直传 R2 =====
  if (p === '/api/upload' && m === 'POST') {
    const name = decodeURIComponent(url.searchParams.get('name') || 'file').slice(0, 200);
    const mime = String(url.searchParams.get('mime') || '');
    if (!/^(image|video|audio)\//.test(mime)) return json({ error: '仅支持图片、视频、音频文件' }, 400);
    const size = Number(request.headers.get('content-length') || 0);
    if (!size) return json({ error: '上传内容为空' }, 400);
    if (size > 50 * 1024 * 1024) return json({ error: '文件超过 50MB 上限' }, 400);
    const ext = ((name.match(/\.[A-Za-z0-9]{1,8}$/) || [''])[0]).toLowerCase();
    const key = `u${user.id}/${Date.now()}-${randHex(4)}${ext}`;
    await env.MEDIA.put(key, request.body, { httpMetadata: { contentType: mime } });
    return json({ url: '/media/' + key, type: mime.split('/')[0], mime, name: name.slice(0, 120), size });
  }

  // ===== 私有媒体：仅本人可读，支持 Range（视频/音频拖动） =====
  if ((p.startsWith('/media/') || p.startsWith('/pub/')) && m === 'GET') {
    const isPub = p.startsWith('/pub/');
    const key = decodeURIComponent(p.slice(isPub ? '/pub/'.length : '/media/'.length));
    if (isPub) {
      // 公开路径：仅当该文件属于某条已分享到广场的记录
      const file = key.split('/').pop();
      if (!/^[\w.-]+$/.test(file)) return json({ error: '路径不合法' }, 400);
      const ok = await env.DB.prepare("SELECT 1 x FROM notes WHERE shared = 1 AND attachments LIKE ?1 LIMIT 1")
        .bind('%' + file + '%').first();
      if (!ok) return json({ error: '该内容未公开' }, 403);
    } else if (!key.startsWith(`u${user.id}/`)) {
      return json({ error: '无权访问' }, 403);
    }
    let obj;
    try {
      obj = await env.MEDIA.get(key, { range: request.headers, onlyIf: request.headers });
    } catch {
      return new Response('Range Not Satisfiable', { status: 416 });
    }
    if (!obj) return new Response('Not Found', { status: 404 });
    const headers = new Headers();
    obj.writeHttpMetadata(headers);
    headers.set('etag', obj.httpEtag);
    headers.set('accept-ranges', 'bytes');
    headers.set('cache-control', 'private, max-age=31536000');
    if (obj.range) {
      const offset = obj.range.offset ?? 0;
      const length = obj.range.length ?? (obj.size - offset);
      headers.set('content-range', `bytes ${offset}-${offset + length - 1}/${obj.size}`);
    }
    if (!('body' in obj) || !obj.body) return new Response(null, { status: 304, headers });
    return new Response(obj.body, { status: request.headers.has('range') ? 206 : 200, headers });
  }

  // ===== 每日提醒（Web Push 订阅管理） =====
  if (p === '/api/push/key' && m === 'GET') {
    if (!env.VAPID_PUBLIC_KEY || !env.VAPID_PRIVATE_KEY) return json({ error: '推送未配置' }, 500);
    return json({ key: env.VAPID_PUBLIC_KEY });
  }
  if (p === '/api/push/subscribe' && m === 'POST') {
    const s = (await body()).subscription || {};
    const endpoint = String(s.endpoint || '');
    if (!endpoint.startsWith('https://') || endpoint.length > 1000) return json({ error: '订阅信息无效' }, 400);
    await env.DB.prepare(
      `INSERT INTO push_subs (endpoint, user_id, p256dh, auth, created_at) VALUES (?1,?2,?3,?4,?5)
       ON CONFLICT(endpoint) DO UPDATE SET user_id=excluded.user_id`
    ).bind(endpoint, user.id, String(s.keys?.p256dh || '').slice(0, 200), String(s.keys?.auth || '').slice(0, 100), nowISO()).run();
    return json({ ok: true });
  }
  if (p === '/api/push/unsubscribe' && m === 'POST') {
    const endpoint = String((await body()).endpoint || '');
    await env.DB.prepare('DELETE FROM push_subs WHERE endpoint = ?1 AND user_id = ?2').bind(endpoint, user.id).run();
    return json({ ok: true });
  }
  // 给自己的所有设备发一条测试提醒
  if (p === '/api/push/test' && m === 'POST') {
    const { results: subs } = await env.DB.prepare('SELECT * FROM push_subs WHERE user_id = ?1').bind(user.id).all();
    if (!subs.length) return json({ error: '还没有开启提醒的设备' }, 400);
    const out = [];
    for (const s of subs) {
      try {
        const st = await sendPush(env, s);
        if (st === 404 || st === 410) await env.DB.prepare('DELETE FROM push_subs WHERE endpoint = ?1').bind(s.endpoint).run();
        out.push(st);
      } catch { out.push(0); }
    }
    return json({ sent: out });
  }

  // ===== AI 配置：交给已登录的浏览器直连硅基流动 =====
  // （硅基流动对 Cloudflare 出口 IP 限流，浏览器直连是主通道；服务端接口作为回退）
  if (p === '/api/ai/config' && m === 'GET') {
    if (!env.SILICONFLOW_KEY) return json({ error: '未配置 SILICONFLOW_KEY' }, 500);
    // 超出当日配额则不再下发 Key
    const q = await env.DB.prepare('SELECT used FROM ai_usage WHERE user_id = ?1 AND day = ?2')
      .bind(user.id, utcDay()).first();
    if ((q?.used || 0) >= AI_DAILY_LIMIT)
      return json({ error: `今日 AI 已用满 ${AI_DAILY_LIMIT} 次，明日再来` }, 429);
    return json({ key: env.SILICONFLOW_KEY, base: env.SILICONFLOW_BASE, chatModel: env.CHAT_MODEL, visionModel: env.VISION_MODEL });
  }

  // 保存每日省察结果（浏览器直连 AI 生成后回存）
  if (p === '/api/day-ai' && m === 'POST') {
    const b = await body();
    const day = isDay(String(b.day || '')) ? b.day : null;
    const summary = String(b.summary || '').trim().slice(0, 500);
    const mood = String(b.mood || '').trim().slice(0, 8);
    if (!day || !summary) return json({ error: '参数不完整' }, 400);
    await env.DB.prepare(
      `INSERT INTO day_ai (user_id, day, summary, mood, updated_at) VALUES (?1,?2,?3,?4,?5)
       ON CONFLICT(user_id, day) DO UPDATE SET summary=excluded.summary, mood=excluded.mood, updated_at=excluded.updated_at`
    ).bind(user.id, day, summary, mood, nowISO()).run();
    return json({ day, summary, mood });
  }

  // ===== AI：每日省察 =====
  if (p === '/api/ai/day-summary' && m === 'POST') {
    const b = await body();
    const day = isDay(String(b.day || '')) ? b.day : utcDay();
    const { results: rows } = await env.DB
      .prepare('SELECT * FROM notes WHERE user_id = ?1 AND day = ?2 ORDER BY created_at').bind(user.id, day).all();
    if (!rows.length) return json({ error: '这一天还没有记录' }, 400);
    const text = rows.map(noteLine).join('\n').slice(0, 6000);
    const raw = await chatAI(env, [
      { role: 'system', content: '你是一位温和克制的修身助手，帮用户做每日功过省察。依据当天记录：先如实肯定其功，再平和点出其过或可改进处，末尾一句朴素的勉励。用第二人称，70 字以内，不说教、不堆砌辞藻、不引用经文、不以圣贤口吻自居。另选一个贴切的 emoji 概括当天。只输出 JSON：{"summary":"...","mood":"emoji"}' },
      { role: 'user', content: `日期：${day}\n当日功过记录：\n${text}` }
    ], { temperature: 0.6 });
    let summary = raw, mood = '';
    try {
      const j = JSON.parse(raw.match(/\{[\s\S]*\}/)[0]);
      summary = String(j.summary || '').trim() || raw;
      mood = String(j.mood || '').trim().slice(0, 8);
    } catch {}
    await env.DB.prepare(
      `INSERT INTO day_ai (user_id, day, summary, mood, updated_at) VALUES (?1,?2,?3,?4,?5)
       ON CONFLICT(user_id, day) DO UPDATE SET summary=excluded.summary, mood=excluded.mood, updated_at=excluded.updated_at`
    ).bind(user.id, day, summary, mood, nowISO()).run();
    return json({ day, summary, mood });
  }

  // ===== AI：润色 =====
  if (p === '/api/ai/polish' && m === 'POST') {
    const text = String((await body()).text || '').trim().slice(0, 4000);
    if (!text) return json({ error: '没有可润色的内容' }, 400);
    const out = await chatAI(env, [
      { role: 'system', content: '你是文字润色助手。把用户的随手记润色得通顺、干净、朴实，保留原意与个人口吻，纠正错别字，篇幅与原文相当。只输出润色后的正文，不要任何解释。' },
      { role: 'user', content: text }
    ], { temperature: 0.5 });
    return json({ text: out });
  }

  // ===== AI：问格（基于记录问答） =====
  if (p === '/api/ai/ask' && m === 'POST') {
    const question = String((await body()).question || '').trim().slice(0, 500);
    if (!question) return json({ error: '请输入问题' }, 400);
    const { results: rows } = await env.DB
      .prepare('SELECT * FROM notes WHERE user_id = ?1 ORDER BY created_at DESC LIMIT 150').bind(user.id).all();
    let budget = 11000;
    const lines = [];
    for (const n of rows) {
      const line = `[${n.day}] ${noteLine(n).slice(0, 240)}`;
      if (budget - line.length < 0) break;
      budget -= line.length;
      lines.push(line);
    }
    lines.reverse();
    const out = await chatAI(env, [
      { role: 'system', content: `你是用户的功过格助手。今天是 ${utcDay()}（UTC）。只依据下面的功过记录回答问题，可归纳、统计功过分值；记录里没有的信息就直说「格中没有相关记录」，不要编造。回答简洁平实。\n\n=== 功过记录 ===\n（[功+n]=善行 n 分，[过+n]=过失 n 分，[记]=普通记事）\n${lines.join('\n') || '（暂无记录）'}` },
      { role: 'user', content: question }
    ], { temperature: 0.4 });
    return json({ answer: out });
  }

  // ===== AI：看图（为图片生成一句配文） =====
  if (p === '/api/ai/caption' && m === 'POST') {
    const b = await body();
    const n = await env.DB.prepare('SELECT * FROM notes WHERE id = ?1').bind(Number(b.noteId)).first();
    if (!n || n.user_id !== user.id) return json({ error: '记录不存在' }, 404);
    const atts = JSON.parse(n.attachments);
    const att = atts[Number(b.index)];
    if (!att || att.type !== 'image') return json({ error: '该附件不是图片' }, 400);
    const key = att.url.replace(/^\/media\//, '');
    if (!key.startsWith(`u${user.id}/`)) return json({ error: '无权访问' }, 403);
    const obj = await env.MEDIA.get(key);
    if (!obj) return json({ error: '图片文件不存在' }, 404);
    if (obj.size > 10 * 1024 * 1024) return json({ error: '图片超过 10MB，暂不支持识别' }, 400);
    const buf = new Uint8Array(await obj.arrayBuffer());
    let bin = '';
    for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    const b64 = btoa(bin);
    const caption = await chatAI(env, [
      { role: 'user', content: [
        { type: 'image_url', image_url: { url: `data:${att.mime || 'image/jpeg'};base64,${b64}` } },
        { type: 'text', text: '用一句中文（30 字以内）自然地描述这张照片记录的内容，像日记配文，不要客套话。' }
      ] }
    ], { model: env.VISION_MODEL, maxTokens: 200, temperature: 0.5 });
    atts[Number(b.index)].caption = caption.slice(0, 500);
    await env.DB.prepare('UPDATE notes SET attachments = ?1, updated_at = ?2 WHERE id = ?3')
      .bind(JSON.stringify(atts), nowISO(), n.id).run();
    return json({ caption: atts[Number(b.index)].caption });
  }

  return json({ error: '接口不存在' }, 404);
}

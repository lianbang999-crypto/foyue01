// 佛乐 · 莲号与功课同步
//
// 念佛计数原先只在本机 localStorage：清一次缓存、换一台手机，几万几十万声就没了。
// 这里把它接上线。认人的办法只有一枚莲号加一道护念码 —— 不收邮箱、不收手机、
// 不发验证短信。莲友多是上了年纪的人，抄在纸上、拍张照，比什么都可靠。
//
// 三个接口：
//   POST /api/lian    开号 / 认回 / 改护念码
//   POST /api/sync    功课数据的推与拉（凭据在 Authorization 头里）
//   GET  /api/gongxiu 全站共念总数与此刻在念人数（无需凭据）
//
// 合并不在这里做。服务端只管存，谁新谁旧用 rev 认：
// 客户端推送时带上它拉到的 rev，对不上就退 409，让它重新拉、合完再推。
// 合并规则只写一份（前端 sync.js），两处各写一份迟早要走岔。

const ALPHA = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';   // 去掉 0O1IL 等抄写易混的字
const KINDS = ['nj', 'read', 'listen', 'pref'];    // 念佛计数 / 阅读 / 听经 / 法名与设置
const BLOB_MAX = 512 * 1024;      // 单类数据上限，够存十年功课
const PBKDF2_ROUNDS = 100000;
const BAD_MAX = 10;               // 连错这些次就锁
const LOCK_MS = 3600000;          // 锁一小时
const LIVE_WINDOW = 180000;       // 「此刻在念」的判定窗：三分钟内计过数即算在念
const GX_CACHE_MS = 60000;        // 全站总数的缓存时长，免得每次都全表求和

const json = (d, s = 200) =>
  new Response(JSON.stringify(d), { status: s, headers: { 'Content-Type': 'application/json; charset=utf-8' } });

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (s) => new Uint8Array((s.match(/../g) || []).map((h) => parseInt(h, 16)));

function randStr(n, alpha) {
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < n; i++) out += alpha[bytes[i] % alpha.length];
  return out;
}

/** 常数时间比较：散列比对不该因为「前几位就不同」而提前返回，那是可测的旁路。 */
function sameHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function hashPass(pass, saltHex) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pass), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: unhex(saltHex), iterations: PBKDF2_ROUNDS }, key, 256);
  return hex(bits);
}

let ready = false;
/** 建表：与 schema.sql 同形。忘了跑迁移也不该让人的功课推不上来。 */
async function ensure(env) {
  if (ready) return;
  await env.DB.batch([
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS lianyou (
      lian TEXT PRIMARY KEY, pass TEXT NOT NULL, salt TEXT NOT NULL,
      dev TEXT NOT NULL DEFAULT '', made INTEGER NOT NULL, seen INTEGER NOT NULL,
      bad INTEGER NOT NULL DEFAULT 0, lock INTEGER NOT NULL DEFAULT 0)`),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS lian_token (
      tok TEXT PRIMARY KEY, lian TEXT NOT NULL, ts INTEGER NOT NULL)`),
    env.DB.prepare('CREATE INDEX IF NOT EXISTS idx_lian_token ON lian_token(lian)'),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS lian_blob (
      lian TEXT NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL,
      rev INTEGER NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (lian, kind))`),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS nianfo_day (
      day TEXT NOT NULL, lian TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, lian))`),
    env.DB.prepare('CREATE INDEX IF NOT EXISTS idx_nianfo_day ON nianfo_day(day)'),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS nianfo_live (dev TEXT PRIMARY KEY, ts INTEGER NOT NULL)'),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)'),
  ]);
  ready = true;
}

const clientIp = (request) => request.headers.get('CF-Connecting-IP') || '0.0.0.0';

/** 限流：开号与认回要挡批量试码，同步要挡拿到凭据后猛打 D1 的客户端。
 *  缺绑定时放行（fail-open）—— 限流器自己出故障，不该反噬正在念佛的人。 */
async function limited(env, key, binding = 'SYNC_RL') {
  try {
    const rl = env[binding];
    if (!rl) return false;
    const { success } = await rl.limit({ key });
    return !success;
  } catch { return false; }
}

/* ══════════ POST /api/lian ══════════ */
export async function serveLian(request, env) {
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
  await ensure(env);

  let body;
  try { body = await request.json(); } catch { return json({ error: '格式有误' }, 400); }
  const act = String(body.act || '');

  if (act === 'open') return openLian(request, env, body);
  if (act === 'claim') return claimLian(request, env, body);
  if (act === 'repass') return repassLian(request, env, body);
  return json({ error: '未知操作' }, 400);
}

/** 开号：发一枚莲号与一道护念码。护念码明文只在此刻回这一次，库里存的是散列。 */
async function openLian(request, env, body) {
  if (await limited(env, 'open:' + clientIp(request))) return json({ error: '稍候再试' }, 429);

  const pass = randStr(6, '0123456789');       // 六位数字：好念、好抄、好记
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
  const hashed = await hashPass(pass, salt);
  const now = Date.now();
  const dev = String(body.dev || '').slice(0, 64);

  // 撞号就再摇，8 位取自 31 个字符，撞的机会本就极小
  let lian = '';
  for (let i = 0; i < 6; i++) {
    const cand = randStr(8, ALPHA);
    try {
      await env.DB.prepare(
        'INSERT INTO lianyou (lian,pass,salt,dev,made,seen) VALUES (?,?,?,?,?,?)')
        .bind(cand, hashed, salt, dev, now, now).run();
      lian = cand;
      break;
    } catch { /* 撞了，再摇 */ }
  }
  if (!lian) return json({ error: '开号未成，请重试' }, 500);

  const tok = hex(crypto.getRandomValues(new Uint8Array(24)));
  await env.DB.prepare('INSERT INTO lian_token (tok,lian,ts) VALUES (?,?,?)').bind(tok, lian, now).run();
  return json({ lian, pass, tok });
}

/** 认回：验莲号与护念码，发新凭据。连错 BAD_MAX 次锁一小时 ——
 *  六位数字的熵本就有限，真正的防线是这道限次，不是散列迭代数。 */
async function claimLian(request, env, body) {
  if (await limited(env, 'claim:' + clientIp(request))) return json({ error: '稍候再试' }, 429);

  const lian = String(body.lian || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
  const pass = String(body.pass || '').replace(/\D/g, '');
  if (lian.length !== 8 || pass.length !== 6) return json({ error: '莲号或护念码格式不对' }, 400);

  const row = await env.DB.prepare('SELECT lian,pass,salt,bad,lock FROM lianyou WHERE lian = ?').bind(lian).first();
  const now = Date.now();
  // 号不存在时也走一遍散列再回同一句话：否则响应快慢就把「有没有这个号」漏出去了
  if (!row) {
    await hashPass(pass, hex(crypto.getRandomValues(new Uint8Array(16))));
    return json({ error: '莲号或护念码不对' }, 401);
  }
  if (row.lock > now) {
    return json({ error: `连错多次，请 ${Math.ceil((row.lock - now) / 60000)} 分钟后再试` }, 429);
  }

  const hashed = await hashPass(pass, row.salt);
  if (!sameHex(hashed, row.pass)) {
    const bad = (row.bad || 0) + 1;
    const lock = bad >= BAD_MAX ? now + LOCK_MS : 0;
    await env.DB.prepare('UPDATE lianyou SET bad = ?, lock = ? WHERE lian = ?').bind(bad >= BAD_MAX ? 0 : bad, lock, lian).run();
    return json({ error: '莲号或护念码不对' }, 401);
  }

  const tok = hex(crypto.getRandomValues(new Uint8Array(24)));
  await env.DB.batch([
    env.DB.prepare('INSERT INTO lian_token (tok,lian,ts) VALUES (?,?,?)').bind(tok, lian, now),
    env.DB.prepare('UPDATE lianyou SET bad = 0, lock = 0, seen = ? WHERE lian = ?').bind(now, lian),
  ]);
  return json({ lian, tok });
}

/** 换一道护念码。要先拿旧码验明正身 —— 光有凭据不够，
 *  手机被人拿走时，凭据就在那台手机上。 */
async function repassLian(request, env, body) {
  if (await limited(env, 'claim:' + clientIp(request))) return json({ error: '稍候再试' }, 429);

  const lian = String(body.lian || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
  const old = String(body.pass || '').replace(/\D/g, '');
  const row = await env.DB.prepare('SELECT pass,salt,lock FROM lianyou WHERE lian = ?').bind(lian).first();
  if (!row || row.lock > Date.now()) return json({ error: '莲号或护念码不对' }, 401);
  if (!sameHex(await hashPass(old, row.salt), row.pass)) return json({ error: '莲号或护念码不对' }, 401);

  const pass = randStr(6, '0123456789');
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
  await env.DB.prepare('UPDATE lianyou SET pass = ?, salt = ? WHERE lian = ?')
    .bind(await hashPass(pass, salt), salt, lian).run();
  return json({ lian, pass });
}

/** 凭据换莲号。 */
async function whoIs(env, request) {
  const auth = request.headers.get('Authorization') || '';
  const tok = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!/^[0-9a-f]{48}$/.test(tok)) return null;
  const row = await env.DB.prepare('SELECT lian FROM lian_token WHERE tok = ?').bind(tok).first();
  return row ? row.lian : null;
}

/* ══════════ POST /api/sync ══════════
   body: { pull: ['nj',…], push: { nj: {data, rev} , … }, recent: {'2026-08-17': 350, …} }
   回： { blobs: { nj: {data, rev} … }, revs: {…}, conflict: ['nj'] }
   rev 对不上的那一类不写，列进 conflict，客户端重拉合并后再推。 */
export async function serveSync(request, env) {
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
  await ensure(env);

  const lian = await whoIs(env, request);
  if (!lian) return json({ error: '凭据无效，请重新认回莲号' }, 401);
  if (await limited(env, 'sync:' + lian, 'PUSH_RL')) return json({ error: '同步过频，稍候自动重试' }, 429);

  let body;
  try { body = await request.json(); } catch { return json({ error: '格式有误' }, 400); }
  const now = Date.now();

  // —— 推 ——
  const conflict = [];
  const revs = {};
  const push = body.push && typeof body.push === 'object' ? body.push : {};
  for (const kind of KINDS) {
    const item = push[kind];
    if (!item || typeof item.data !== 'string') continue;
    if (item.data.length > BLOB_MAX) { conflict.push(kind); continue; }
    const cur = await env.DB.prepare('SELECT rev FROM lian_blob WHERE lian = ? AND kind = ?').bind(lian, kind).first();
    const curRev = cur ? cur.rev : 0;
    const baseRev = Number(item.rev) || 0;
    if (baseRev !== curRev) { conflict.push(kind); revs[kind] = curRev; continue; }
    const next = curRev + 1;
    await env.DB.prepare(
      'INSERT INTO lian_blob (lian,kind,data,rev,ts) VALUES (?,?,?,?,?) ' +
      'ON CONFLICT(lian,kind) DO UPDATE SET data = excluded.data, rev = excluded.rev, ts = excluded.ts')
      .bind(lian, kind, item.data, next, now).run();
    revs[kind] = next;
  }

  // —— 每日汇总（全站共念由此累加）——
  // 客户端直接报最近几天的数目，服务端不必解整包 JSON。
  // 取大而不累加：同一天反复同步不该把数目越滚越多。功课记录宁可少算，不可注水。
  const recent = body.recent && typeof body.recent === 'object' ? body.recent : {};
  const days = Object.keys(recent).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d)).slice(0, 7);
  if (days.length) {
    await env.DB.batch(days.map((d) => env.DB.prepare(
      'INSERT INTO nianfo_day (day,lian,n) VALUES (?,?,?) ' +
      'ON CONFLICT(day,lian) DO UPDATE SET n = MAX(n, excluded.n)')
      .bind(d, lian, Math.max(0, Math.min(10000000, Number(recent[d]) || 0)))));
  }

  // 心跳：此刻在念
  const dev = String(body.dev || '').slice(0, 64);
  if (dev && body.beat) {
    await env.DB.prepare('INSERT INTO nianfo_live (dev,ts) VALUES (?,?) ON CONFLICT(dev) DO UPDATE SET ts = excluded.ts')
      .bind(dev, now).run();
  }

  // —— 拉 ——
  const blobs = {};
  const want = Array.isArray(body.pull) ? body.pull.filter((k) => KINDS.includes(k)) : [];
  for (const kind of want) {
    const row = await env.DB.prepare('SELECT data,rev FROM lian_blob WHERE lian = ? AND kind = ?').bind(lian, kind).first();
    if (row) blobs[kind] = { data: row.data, rev: row.rev };
  }

  await env.DB.prepare('UPDATE lianyou SET seen = ? WHERE lian = ?').bind(now, lian).run();
  return json({ ok: true, blobs, revs, conflict });
}

/* ══════════ GET /api/gongxiu ══════════
   全站共念总数与此刻在念人数。只报数目，不设排名 ——
   念佛贵在恳切，不在与人比多。 */
export async function serveGongxiu(request, env) {
  await ensure(env);
  const now = Date.now();

  // 心跳不挂在莲号上：没开号的莲友一样在念，不该不算数
  const url = new URL(request.url);
  const dev = String(url.searchParams.get('dev') || '').slice(0, 64);
  if (url.searchParams.get('beat') === '1' && dev) {
    await env.DB.prepare(
      'INSERT INTO nianfo_live (dev,ts) VALUES (?,?) ON CONFLICT(dev) DO UPDATE SET ts = excluded.ts')
      .bind(dev, now).run();
  }

  let total = 0;
  const cached = await env.DB.prepare("SELECT v FROM meta WHERE k = 'gx.total'").first();
  if (cached) {
    try {
      const c = JSON.parse(cached.v);
      if (now - c.t < GX_CACHE_MS) total = c.n;
    } catch { /* 缓存坏了就重算 */ }
  }
  if (!total) {
    const row = await env.DB.prepare('SELECT COALESCE(SUM(n),0) n FROM nianfo_day').first();
    total = row ? row.n : 0;
    await env.DB.prepare('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)')
      .bind('gx.total', JSON.stringify({ n: total, t: now })).run();
  }

  // 顺手清掉过期心跳，免得这张表只涨不消
  if (Math.random() < 0.1) {
    await env.DB.prepare('DELETE FROM nianfo_live WHERE ts < ?').bind(now - LIVE_WINDOW * 4).run();
  }
  const live = await env.DB.prepare('SELECT COUNT(*) n FROM nianfo_live WHERE ts > ?').bind(now - LIVE_WINDOW).first();

  return json({ total, live: live ? live.n : 0 });
}

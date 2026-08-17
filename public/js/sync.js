// 佛乐 · 功课同步（莲号）
//
// 离线优先：本地永远先写、先显示，同步在后台跑。没网就搁着，回网再补。
// 一句话也不该因为同步而卡住 —— 念佛的人不必知道有这回事。
//
// 认人只靠一枚莲号加一道护念码，不收邮箱手机。凭据存在本机，
// 换设备时输一次莲号与护念码即可把功课认回来。
//
// 合并规则全写在这一处，服务端只管存（凭 rev 做乐观锁）。
// 两处各写一份，迟早走岔。

const API_LIAN = '/api/lian';
const API_SYNC = '/api/sync';
const API_GX = '/api/gongxiu';

const KINDS = ['nj', 'read', 'listen', 'pref'];

/* 各类各管哪些键。前缀以 * 结尾表示「这一族」。
   刻意不同步的：fy.dev（本机身份）、fy.chat（问道对话，属私事且量大）、
   fy.i18n.*（翻译缓存，可重取）、fy.offline.meta（本机离线文件清单，跨机无意义）。 */
const FIELDS = {
  read: ['fy.fav.*', 'fy.bk.*', 'fy.hl.*', 'fy.rp.*', 'fy.lastRead'],
  listen: ['fy.p.*', 'fy.last'],
  pref: ['fy.fname', 'fy.theme', 'fy.lang', 'fy.zh', 'fy.muyu', 'fy.wake',
    'fy.vib', 'fy.dm', 'fy.fs', 'fy.lh', 'fy.ff', 'fy.rate'],
};

/* 累积型的键族：两处都有就并起来，不是谁新听谁的。
   收藏与划线是人一条条攒出来的，宁可某条删掉后又冒出来，也不能整批丢。 */
const UNION = ['fy.fav.', 'fy.bk.', 'fy.hl.'];

const PUSH_DELAY = 20000;    // 计数变动后攒一会儿再推，不必一声一趟
const PERIOD = 300000;       // 常规轮次

let acct = null;             // { lian, tok }
let revs = {};               // 各类的 rev
let dirty = new Set();
let timer = null;
let delayTimer = null;
let running = false;
let hooks = {};
let lastErr = '';

/* ══════════ 本机凭据 ══════════ */

function loadAcct() {
  try { acct = JSON.parse(localStorage.getItem('fy.lian')) || null; } catch { acct = null; }
  if (acct && (!acct.lian || !acct.tok)) acct = null;
  try { revs = JSON.parse(localStorage.getItem('fy.lianRev')) || {}; } catch { revs = {}; }
}
function saveAcct() {
  if (acct) localStorage.setItem('fy.lian', JSON.stringify(acct));
  else localStorage.removeItem('fy.lian');
  localStorage.setItem('fy.lianRev', JSON.stringify(revs));
}

export function syncAccount() { return acct ? { lian: acct.lian } : null; }
export function syncLastError() { return lastErr; }

/** 解除本机与莲号的关联。只断这一头 —— 云端功课原封不动，
 *  拿莲号与护念码随时能再认回来。本机数据也不删。 */
export function syncUnlink() {
  acct = null; revs = {}; dirty.clear();
  localStorage.removeItem('fy.lian');
  localStorage.removeItem('fy.lianRev');
}

/* ══════════ 开号与认回 ══════════ */

/** 开一枚新莲号。护念码明文只此一次回传，之后服务端只有散列，找不回来。 */
export async function syncOpen(dev) {
  const r = await fetch(API_LIAN, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ act: 'open', dev }),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error || '开号未成');
  acct = { lian: d.lian, tok: d.tok };
  revs = {};
  saveAcct();
  dirty = new Set(KINDS);          // 新号：把本机现有功课整套推上去
  await syncRun({ now: true });
  return { lian: d.lian, pass: d.pass };
}

/** 认回：验莲号与护念码，随后把云端功课与本机合并。 */
export async function syncClaim(lian, pass) {
  const r = await fetch(API_LIAN, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ act: 'claim', lian, pass }),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error || '认回未成');
  acct = { lian: d.lian, tok: d.tok };
  revs = {};                        // rev 归零：先整套拉下来合并，再推
  saveAcct();
  dirty = new Set(KINDS);
  await syncRun({ now: true });
  return { lian: d.lian };
}

/** 换一道护念码（须先报出旧的）。 */
export async function syncRepass(lian, pass) {
  const r = await fetch(API_LIAN, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ act: 'repass', lian, pass }),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error || '未能更换');
  return { lian: d.lian, pass: d.pass };
}

/* ══════════ 取数与落数 ══════════ */

function keysOf(patterns) {
  const out = [];
  for (const p of patterns) {
    if (p.endsWith('*')) {
      const pre = p.slice(0, -1);
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.startsWith(pre)) out.push(k);
      }
    } else if (localStorage.getItem(p) != null) out.push(p);
  }
  return out;
}

function collect(kind) {
  if (kind === 'nj') return localStorage.getItem('fy.nj') || '';
  const bag = {};
  for (const k of keysOf(FIELDS[kind] || [])) bag[k] = localStorage.getItem(k);
  return JSON.stringify({ t: Date.now(), bag });
}

/* ══════════ 合并 ══════════ */

/** 念佛计数：逐日逐功课取大，累计与月账同理，功课列表取并集。
 *
 *  为什么取大而不是相加：同一台设备反复同步、或推上去又拉回来，
 *  相加会把数目越滚越多。功课记录宁可少算，绝不可注水 ——
 *  两台设备同一天各念一场时，这里确实只认多的那一场，
 *  是明知的取舍：虚增比少计更坏。 */
function mergeNj(mineStr, theirsStr) {
  let mine, theirs;
  try { mine = JSON.parse(mineStr); } catch { mine = null; }
  try { theirs = JSON.parse(theirsStr); } catch { theirs = null; }
  if (!theirs) return mineStr;
  if (!mine) return theirsStr;

  const out = {
    v: 2,
    cur: mine.cur || theirs.cur,        // 当前功课与定课以本机为准：
    goal: mine.goal ?? theirs.goal,     // 那是此刻这个人的选择
    items: [], days: {}, months: {}, totals: {},
  };

  const seen = new Map();
  for (const it of [...(theirs.items || []), ...(mine.items || [])]) {
    if (it && it.id && !seen.has(it.id)) seen.set(it.id, it);
  }
  out.items = [...seen.values()];

  for (const src of [theirs.days || {}, mine.days || {}]) {
    for (const [day, byItem] of Object.entries(src)) {
      const dst = out.days[day] || (out.days[day] = {});
      for (const [id, n] of Object.entries(byItem || {})) {
        dst[id] = Math.max(dst[id] || 0, Number(n) || 0);
      }
    }
  }
  for (const src of [theirs.months || {}, mine.months || {}]) {
    for (const [ym, n] of Object.entries(src)) out.months[ym] = Math.max(out.months[ym] || 0, Number(n) || 0);
  }
  for (const src of [theirs.totals || {}, mine.totals || {}]) {
    for (const [id, n] of Object.entries(src)) out.totals[id] = Math.max(out.totals[id] || 0, Number(n) || 0);
  }
  // 累计不该少于逐日之和：老设备的 totals 偏小时以日账为准补齐
  const byItem = {};
  for (const byI of Object.values(out.days)) {
    for (const [id, n] of Object.entries(byI)) byItem[id] = (byItem[id] || 0) + n;
  }
  for (const [id, n] of Object.entries(byItem)) out.totals[id] = Math.max(out.totals[id] || 0, n);

  return JSON.stringify(out);
}

/** 阅读/听经/设置：整包按时间新旧取，但收藏、书签、划线这三族是并集 ——
 *  它们是一条条攒的，不能因为另一台设备晚同步一步就整批没了。 */
function mergeBag(kind, mineStr, theirsStr) {
  let mine, theirs;
  try { mine = JSON.parse(mineStr); } catch { mine = null; }
  try { theirs = JSON.parse(theirsStr); } catch { theirs = null; }
  if (!theirs || !theirs.bag) return mineStr;
  if (!mine || !mine.bag) return theirsStr;

  const newer = (mine.t || 0) >= (theirs.t || 0) ? mine : theirs;
  const older = newer === mine ? theirs : mine;
  const bag = { ...older.bag, ...newer.bag };   // 状态类：新的压旧的

  // 累积类：两边都要
  for (const [k, v] of Object.entries(older.bag)) {
    if (!UNION.some((p) => k.startsWith(p))) continue;
    if (bag[k] == null) { bag[k] = v; continue; }
    if (k.startsWith('fy.hl.')) bag[k] = mergeHl(bag[k], v);   // 划线逐条并
  }
  return JSON.stringify({ t: Math.max(mine.t || 0, theirs.t || 0), bag });
}

/** 同一篇的划线逐条并，按文本去重。 */
function mergeHl(a, b) {
  let x, y;
  try { x = JSON.parse(a); } catch { x = null; }
  try { y = JSON.parse(b); } catch { y = null; }
  if (!Array.isArray(x)) return Array.isArray(y) ? JSON.stringify(y) : a;
  if (!Array.isArray(y)) return JSON.stringify(x);
  const seen = new Set();
  const out = [];
  for (const it of [...x, ...y]) {
    const sig = JSON.stringify(it);
    if (seen.has(sig)) continue;
    seen.add(sig);
    out.push(it);
  }
  return JSON.stringify(out);
}

/** 把合并结果落回本机。 */
function apply(kind, str) {
  if (kind === 'nj') {
    if (!str) return false;
    localStorage.setItem('fy.nj', str);
    return true;
  }
  let d;
  try { d = JSON.parse(str); } catch { return false; }
  if (!d || !d.bag) return false;
  for (const [k, v] of Object.entries(d.bag)) {
    if (v == null) continue;
    try { localStorage.setItem(k, v); } catch { /* 存不进就跳过这条 */ }
  }
  return true;
}

/* ══════════ 一轮同步 ══════════ */

export function syncMark(kind) {
  if (!acct || !KINDS.includes(kind)) return;
  dirty.add(kind);
  clearTimeout(delayTimer);
  delayTimer = setTimeout(() => syncRun(), PUSH_DELAY);
}

/** 由 util.js 的 setLS/delLS 统一回调：这个键属于哪一类，就标哪一类的脏。
 *  写入点几十处，逐个去改必漏，漏的那处就成了「这台手机的收藏死活传不过去」。 */
export function syncMarkKey(key) {
  if (!acct || !key) return;
  if (key === 'fy.nj') { syncMark('nj'); return; }
  for (const [kind, pats] of Object.entries(FIELDS)) {
    for (const p of pats) {
      if (p.endsWith('*') ? key.startsWith(p.slice(0, -1)) : key === p) { syncMark(kind); return; }
    }
  }
}

/** 最近几天各念了多少（供全站共念汇总，只报数目不报功课）。 */
function recentDays() {
  const out = {};
  try {
    const nj = JSON.parse(localStorage.getItem('fy.nj') || '{}');
    const days = Object.keys(nj.days || {}).sort().slice(-3);
    for (const d of days) {
      out[d] = Object.values(nj.days[d] || {}).reduce((a, b) => a + (Number(b) || 0), 0);
    }
  } catch { /* 没有就不报 */ }
  return out;
}

export async function syncRun(opts = {}) {
  if (!acct || running || !navigator.onLine) return false;
  running = true;
  clearTimeout(delayTimer);
  try {
    for (let attempt = 0; attempt < 3; attempt++) {
      const push = {};
      for (const kind of dirty) push[kind] = { data: collect(kind), rev: revs[kind] || 0 };

      const r = await fetch(API_SYNC, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${acct.tok}` },
        body: JSON.stringify({
          push, pull: KINDS, recent: recentDays(),
          dev: localStorage.getItem('fy.dev') || '', beat: !!opts.beat,
        }),
      });

      if (r.status === 401) {          // 凭据失效：留着莲号，等人重新认回
        lastErr = '凭据已失效，请重新认回莲号';
        acct = null; saveAcct();
        hooks.onAuthLost?.();
        return false;
      }
      const d = await r.json().catch(() => ({}));
      if (!r.ok) { lastErr = d.error || '同步未成'; return false; }

      // 拉下来的与本机合并，有变动就落回本机
      let njChanged = false;
      for (const kind of KINDS) {
        const remote = d.blobs?.[kind];
        if (!remote) continue;
        const mine = collect(kind);
        const merged = kind === 'nj' ? mergeNj(mine, remote.data) : mergeBag(kind, mine, remote.data);
        if (merged && merged !== mine) {
          apply(kind, merged);
          if (kind === 'nj') njChanged = true;
          dirty.add(kind);             // 合出了新东西，得推回去
        }
        revs[kind] = remote.rev;
      }
      for (const [kind, rev] of Object.entries(d.revs || {})) {
        revs[kind] = rev;
        if (!(d.conflict || []).includes(kind)) dirty.delete(kind);
      }
      saveAcct();
      if (njChanged) hooks.onNjChanged?.();

      lastErr = '';
      if (!(d.conflict || []).length && !dirty.size) return true;
      // 有冲突：rev 已更新，带着合并后的数据再来一轮
    }
    return true;
  } catch (e) {
    lastErr = '网络不通，稍后自动重试';
    return false;
  } finally {
    running = false;
  }
}

/* ══════════ 全站共念 ══════════ */

/** beat：顺带报一次「此刻在念」。心跳不挂莲号 ——
 *  没开号的莲友一样在念，不该不算数。 */
export async function syncGongxiu(beat) {
  try {
    const q = beat ? `?beat=1&dev=${encodeURIComponent(localStorage.getItem('fy.dev') || '')}` : '';
    const r = await fetch(API_GX + q);
    if (!r.ok) return null;
    return await r.json();
  } catch { return null; }
}

/* ══════════ 启动 ══════════ */

export function syncInit(h = {}) {
  hooks = h;
  loadAcct();
  if (!acct) return;

  setTimeout(() => syncRun(), 3000);          // 让首屏先安顿
  timer = setInterval(() => syncRun(), PERIOD);

  // 切后台/熄屏时抢存一次：手机常在这一刻被系统收走
  document.addEventListener('visibilitychange', () => { if (document.hidden && dirty.size) syncRun(); });
  window.addEventListener('online', () => { if (dirty.size) syncRun(); });
}

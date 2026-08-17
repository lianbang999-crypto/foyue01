// 佛乐 · 本机要紧数据的保全
//
// 念佛计数是修行人最看重的记录，几万几十万声不该说没就没。
// 原先它单存 localStorage，而这一处并不牢靠：
//   · iOS Safari 对未加主屏的站点有「七日无访问即清站点数据」的策略；
//   · 用户清一次浏览器缓存、换一次手机，一并没了。
// 这里补两道，各有各的边界，别指望其中哪一道包打天下 ——
//   一、申请持久化配额。这道最实在：获准后浏览器不再主动清理本站数据，
//      连 iOS 那条七日策略也随之免除。
//   二、把要紧的键镜像进 IndexedDB。它挡的是 localStorage 单独出事 ——
//      配额吃紧时的分级驱逐、写满时的 QuotaExceeded、个别浏览器只清一边。
//      要说明白：用户手动清「网站数据」或 ITP 整站清理时，两处一起没，镜像救不了。
// 真正跨设备、跨清理的那道是云端同步（见 sync.js），但它要有网；这两道无网也在。
//
// 另起一个库，不并进音频缓存那个 foyue-offline：
// 那边升版本的风险不该殃及计数，这边也不必背着几百 MB 音频做事。

const DB_NAME = 'foyue-vault';
const STORE = 'mirror';

// 镜像哪些键：计数、设备身份、法名、莲号。
// 阅读/听经进度量大且变动频繁，交给云端同步，不在这里反复写盘。
const KEYS = ['fy.nj', 'fy.dev', 'fy.fname', 'fy.lian'];

let _db = null;

function open() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    let req;
    try { req = indexedDB.open(DB_NAME, 1); } catch (e) { reject(e); return; }
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'k' });
    };
    // 隐私模式下 IndexedDB 可能既不成也不败，就那么卡着：
    // 给个上限，别让启动等在这儿 —— 捞回备份要紧，但没要紧到可以拖住首屏。
    const bail = setTimeout(() => reject(new Error('indexedDB timeout')), 1500);
    req.onsuccess = () => { clearTimeout(bail); _db = req.result; resolve(_db); };
    req.onerror = () => { clearTimeout(bail); reject(req.error); };
  });
}

/** 申请持久化配额。一行 API，是这里性价比最高的一道保险 ——
 *  已加主屏或常来的站点通常直接获准，获准后浏览器不再主动清理本站数据。 */
export async function vaultPersist() {
  try {
    if (!navigator.storage?.persist) return false;
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch { return false; }   // 不支持或被拒：还有镜像那道
}

/** 写一个键的镜像。失败不声张 —— 它是备份，不该反过来打断正在计数的人。 */
export function vaultMirror(key, value) {
  if (!KEYS.includes(key)) return Promise.resolve(false);
  return open().then((db) => new Promise((resolve) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).put({ k: key, v: value, t: Date.now() });
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => resolve(false);
  })).catch(() => false);
}

/** 把 localStorage 里现有的要紧键全量镜像一遍（启动时兜底补齐）。 */
export function vaultMirrorAll() {
  const jobs = [];
  for (const k of KEYS) {
    const v = localStorage.getItem(k);
    if (v != null) jobs.push(vaultMirror(k, v));
  }
  return Promise.all(jobs).catch(() => []);
}

/** 启动时捞回：localStorage 缺哪个键就从镜像补哪个。
 *  只补缺失，不覆盖现有 —— localStorage 始终是主，镜像只是从。
 *  返回补回的键名数组。 */
export async function vaultRestore() {
  let rows;
  try {
    const db = await open();
    rows = await new Promise((resolve, reject) => {
      const r = db.transaction(STORE, 'readonly').objectStore(STORE).getAll();
      r.onsuccess = () => resolve(r.result || []);
      r.onerror = () => reject(r.error);
    });
  } catch { return []; }

  const back = [];
  for (const row of rows) {
    if (!row || !KEYS.includes(row.k) || row.v == null) continue;
    const cur = localStorage.getItem(row.k);
    if (cur != null && cur !== '') continue;   // 本机还在，不动
    try { localStorage.setItem(row.k, row.v); back.push(row.k); } catch { /* 存不进就算了 */ }
  }
  return back;
}

/** 清空镜像。用户主动清数据时必须连这里一起清，
 *  否则下次启动又被捞回来 —— 那就成了删不掉的数据。 */
export function vaultClear() {
  return open().then((db) => new Promise((resolve) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).clear();
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => resolve(false);
  })).catch(() => false);
}

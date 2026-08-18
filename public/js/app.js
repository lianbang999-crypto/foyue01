// 佛乐 · 主应用
// 底部导航：首页（今日案头）/ 听经（听经台·有声书·佛号）/ 文库（阅读站）/ 我的（数珠计数）
// 问道（文库RAG问答）在顶栏右上角
// 播放模式：live 直播（确定性排播全网同步）/ od 点播（进度记忆）/ nianfo 佛号（循环+定课）

import {
  createStation, stationNow, fmtClock, fmtDur, fmtMMSS, bjParts,
  nowMs, setClockSkew, clockSkewMs,
} from './station.js';
import { SERIES_INTROS } from './intros.js';
import { initI18n } from './i18n.js';
import {
  markDialog, setBackgroundInert, trapTab, initSkipLink, announce,
} from './a11y.js';
import { $, esc, toast, copyText, vibrate, setLS, delLS, setLSHook } from './util.js';
import { vaultPersist, vaultMirror, vaultMirrorAll, vaultRestore } from './vault.js';
import {
  syncInit, syncMarkKey, syncAccount, syncOpen, syncClaim, syncRepass,
  syncUnlink, syncRun, syncGongxiu, syncLastError,
} from './sync.js';
import { WEEK } from './const.js';
import {
  makePoster, makeLivePoster, makeQuotePoster, showPoster, revokePoster,
  trimQuote, resetPoster, posterToBlob,
} from './poster.js';
import {
  initAsk, buildWenda, loadChat, saveChat, sendQuestion, shareAnswer, pruneRt, pathToHash,
  isAsking, abortAsk, chatMsg, chatCount, clearChat, growInput, syncAskUI,
} from './ask.js';

const audio = $('#audio');
const SITE_TITLE = '佛乐 · 净土法音';
const RATES = [1, 1.25, 1.5, 1.75, 2, 0.75];
const FONT_SIZES = [17, 19, 21, 24];
const LINE_HEIGHTS = [1.75, 2.05, 2.4];   // 阅读行距：紧凑 / 适中 / 疏朗
const READER_SANS = '-apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif';
const TING_CATS = ['讲经', '讲座', '问答', '诗偈'];
const SHU_CATS = ['有声书', '传记', '故事'];
const RING_LEN = 2 * Math.PI * 54; // 数珠进度环周长

let catalog = null, library = null, qaData = null;
let station = null;
let mode = 'live';          // live | od | nianfo
let playMode = localStorage.getItem('foyue_playmode_v1') || 'list';  // list 列表循环 | one 单曲循环 | shuffle 随机
let liveItem = null;
let wantLive = false;
let liveRetry = 0;          // 直播连续失败次数，用于退避
let liveRetryT = 0;         // 在途的重试定时器
let liveWatchSec = 0;       // 播放位置已停滞几秒
let liveWatchPos = -1;      // 上一秒的播放位置
let od = null;              // 点播状态 { title, list, idx, progress, seriesId, bucket }
let schedDay = 0;
let seekPending = null;
let lastSaved = 0;
let seekDragging = false;
let nf = { tracks: [], idx: 0, timerMin: 0, deadline: null };
let sleepT = { min: 0, deadline: null };    // 睡眠定时（点播/直播共用）
const SLEEP_MINS = [0, 15, 30, 60];
let miniExpanded = localStorage.getItem('fy.miniExp') !== '0';   // 播放条两态，记住用户偏好
let nj = { total: 0, days: {} };   // 念佛计数
let reader = { chapters: null, idx: 0, path: null, backHash: '#wenku' };
let pendingReaderBack = null;      // 从问道引用跳转阅读时，返回键回问道
let pendingHlTarget = null;        // 从「我的划线」跳转时定位到的段落 {path, p}
let allChapters = null;            // 文库全部篇目（阅读页标题搜索用）

init();

async function init() {
  // 真实路径页（/read/… /qa/… /wkseries/… /series/…，见 worker/ssr.js）转成 hash 交给路由。
  // 只认路径、不依赖 window.__SSR：离线时 SW 回退到壳，同样能把人送到正确的篇目，
  // 区别仅在正文得自己取一次。URL 一并归一到 hash 形式，站内导航与分享沿用同一套。
  if (!location.hash) {
    const m = location.pathname.match(/^\/(read|wkseries|qa|series)\/(.+?)\/?$/);
    if (m) history.replaceState(null, '', `/#${m[1]}/${m[2]}`);
  }
  // 入口域名感知（同一 Worker，不拆站）：无锚点访问默认直达对应模块
  if (!location.hash) {
    if (/^bo\./i.test(location.hostname)) location.replace('#live');          // 直播台
    else if (/^(qun|liao)\./i.test(location.hostname)) location.replace('#qun'); // 莲友共修群
  }
  // 与站方对表。跟 catalog 并行发出，不额外占首屏时间；
  // 排在 createStation 之前，好让推演与快照一开始就用对的「现在」。
  const clockReady = syncClock();
  // 首屏只等 catalog（听经/直播立即可用）；library/qa 后台预取，进相关页时再等
  try {
    catalog = await fetchJson('/catalog.json');
  } catch {
    showLoadError();
    return;
  }
  await clockReady;
  station = createStation(catalog);

  // 先从镜像捞回被清掉的要紧键（只补缺失，不覆盖），再读计数 —— 顺序不可颠倒
  const back = await vaultRestore();
  loadNj();
  if (back.includes('fy.nj')) toast('已从本机备份找回念佛记录');
  vaultPersist();      // 申请持久化配额，此后浏览器不再主动清理本站数据
  vaultMirrorAll();    // 补齐镜像（首次运行、或刚导入过备份）

  // 云同步：写盘即标脏（挂在 setLS 上，不逐个改写入点），随后后台自行推拉
  setLSHook(syncMarkKey);
  syncInit({
    onNjChanged: () => { loadNj(); renderCount(); renderWode(); },
    onAuthLost: () => toast('莲号凭据已过期 · 请到「功课 → 莲号」重新认回'),
  });
  loadChat();
  pruneRt();
  buildTing();
  buildShu();
  buildFohao();
  buildHome();
  applyThemePref();
  bindEvents();
  initSkipLink();
  // 问道模块的繁简转换由此注入：它若自行 import 简繁那套，依赖链会绕回本文件
  initAsk({ trans: () => ((zhMap && zhTradOn()) ? (x) => zhConv(x, zhMap) : (x) => x) });
  route();
  tick();
  setInterval(tick, 1000);
  ensureLibrary().catch(() => { /* 预取失败静默：进入相关页时会重试 */ });
  // 语言偏好：繁体走字表转换，外文走 AI 词典（均接管后续动态内容）
  const lang = getLang();
  applyLangRow(lang);
  if (lang === 't') setZhTrad(true);
  else if (lang === 'en' || lang === 'ja') initI18n(lang);
  // Service Worker：新版本接管时自动刷一次，关掉「新页面配旧样式」的错配窗口
  //（老客户端本次打开仍由旧 SW 供样式，刷这一下才见新版）。
  // 首次安装本来就没有 controller，不刷；正在放音也不刷，免得打断听经。
  if ('serviceWorker' in navigator) {
    const hadController = !!navigator.serviceWorker.controller;
    let swReloaded = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!hadController || swReloaded || !audio.paused) return;
      swReloaded = true;
      location.reload();
    });
    navigator.serviceWorker.register('/sw.js').catch(() => { /* 忽略 */ });
  }
}

async function fetchJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url} ${r.status}`);
  return r.json();
}

// 文库与问答数据按需加载（失败可重试）；就绪后刷新依赖它们的视图
let libPromise = null;
function ensureLibrary() {
  libPromise ??= Promise.all([fetchJson('/library.json'), fetchJson('/qa.json')])
    .then(([l, q]) => {
      library = l; qaData = q;
      buildWenku();
      buildWenda(l);
      if (document.body.dataset.view === 'wode') renderWode();   // 我的页续读卡依赖 library
      if (document.body.dataset.view === 'home') buildHome();    // 首页「继续阅读」同样依赖 library
    })
    .catch((e) => { libPromise = null; throw e; });
  return libPromise;
}

function showLoadError() {
  // 目录加载失败：全屏提示 + 重试，不留白屏
  if ($('#loadErr')) return;
  const el = document.createElement('div');
  el.id = 'loadErr';
  el.innerHTML = `<div class="load-err-card">
    <p>目录加载失败，请检查网络</p>
    <button>重 试</button></div>`;
  el.querySelector('button').addEventListener('click', () => { el.remove(); init(); });
  document.body.appendChild(el);
}

function showLibError() {
  // 文库数据加载失败：浮条提示 + 重试
  if ($('#libErr')) return;
  const el = document.createElement('div');
  el.id = 'libErr';
  el.innerHTML = '<span>文库数据加载失败</span><button>重试</button>';
  el.querySelector('button').addEventListener('click', () => { el.remove(); route(); });
  document.body.appendChild(el);
}

// 外观偏好只两档。这份清单是单一数据源 —— 设置页那一行的当前值、
// 弹层里的选项与说明都从这里出，与 LANGS 同一套路。
const THEMES = [
  { id: 'day',  name: '纸墨',     desc: '宣纸为底，终日不变' },
  { id: 'auto', name: '四时流转', desc: '随播出时段在晨曦·纸墨·暮色·夜烛之间流转' },
];
const THEME_PREFS = THEMES.map((t) => t.id);

function themeName(v) {
  return (THEMES.find((t) => t.id === v) || THEMES[0]).name;
}

// 读取并校正主题偏好。旧版另有「深色 night」「敦煌 dunhuang」两个固定档，
// 其 CSS 块已随主题精简删除；老用户本机还存着旧值，若不迁回，
// body[data-theme] 会落到一个没有定义的档上，--bg / --ink / --accent
// 全部无处取值，整页配色当场塌掉。故凡读这个偏好的地方一律走这里，
// 不要各自 localStorage.getItem —— 每秒的 tick() 也在读它。
function themePref() {
  const v = localStorage.getItem('fy.theme') || 'day';
  if (THEME_PREFS.includes(v)) return v;
  setLS('fy.theme', 'day');
  return 'day';
}

// 外观偏好：首次访问默认纸墨；用户可改为四时流转。
// 设置页那一行的当前值在此更新；弹层里的选中态在 openTheme() 打开时现铺。
function applyThemePref() {
  const pref = themePref();
  const el = $('#themeVal');
  if (el) el.textContent = themeName(pref);
  let theme = pref;
  if (pref === 'auto') theme = station.liveAt(stationNow()).item.block.theme;
  document.body.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', themeMetaColor(theme));
}

// 系统状态栏底色须与页面顶边一致，否则安全区上下露出异色条。
// 取的是各主题 --sky 渐变的**首色**而非 --bg：.sky 是铺满视口的 fixed 层
// （style.css .sky { position: fixed; inset: 0 }），顶边真正露出来的是渐变起点。
// 晨曦、暮色两档尤其明显 —— --bg 是宣纸 #f6f1e6 / #efe7d4，
// 而顶边其实是更重一阶的 #e7dcc4，差着一眼能看出的一截。
// 改这里时请对着 style.css 里的 --sky 首色同步（现为文钞纸阶）。
const THEME_BG = {
  day: '#efe7d4', dawn: '#e7dcc4', dusk: '#e7dcc4', night: '#171310',
};
function themeMetaColor(theme) {
  return THEME_BG[theme] || THEME_BG.day;
}

/* ================= 简繁转换 =================
   字表惰性加载（zh-t.js，OpenCC 字级映射）；开繁体后全量转换现有
   文本节点，并以 MutationObserver 接管后续动态内容。回简体直接重载。 */

let zhMap = null;       // 简→繁
let zhBack = null;      // 繁→简（搜索词兼容用）
let zhObserver = null;

async function ensureZh() {
  if (zhMap) return;
  const m = await import('./zh-t.js');
  // 按码点展开对齐（直接按下标取的是 UTF-16 单元，遇超平面字会整表错位）
  const pair = (a, b) => {
    const from = [...a], to = [...b], map = new Map();
    for (let i = 0; i < from.length; i++) map.set(from[i], to[i]);
    return map;
  };
  zhMap = pair(m.S2T_FROM, m.S2T_TO);
  zhBack = pair(m.T2S_FROM, m.T2S_TO);
}

function zhConv(text, map) {
  let out = '';
  for (const ch of text) out += map.get(ch) || ch;
  return out;
}

function zhApply(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) =>
      n.parentNode && ['SCRIPT', 'STYLE', 'TEXTAREA'].includes(n.parentNode.nodeName)
        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const n of nodes) {
    const v = zhConv(n.nodeValue, zhMap);
    if (v !== n.nodeValue) n.nodeValue = v;
  }
  if (root.querySelectorAll) {
    for (const el of root.querySelectorAll('[placeholder], [aria-label], [title]')) {
      for (const attr of ['placeholder', 'aria-label', 'title']) {
        const v = el.getAttribute(attr);
        if (v) {
          const t = zhConv(v, zhMap);
          if (t !== v) el.setAttribute(attr, t);
        }
      }
    }
  }
}

function zhTradOn() { return getLang() === 't'; }

// 语言清单：单一数据源 —— 设置页那一行的当前值、弹层里的选项都从这里出。
// 加语种只需在此加一行，另在 i18n.js 的 SEED 里补上该语种的种子词典。
// 各语言一律以自身文字列出，不随界面语言翻译（见 i18n.js 的 SKIP_SEL）。
const LANGS = [
  { id: 's',  name: '简体中文' },
  { id: 't',  name: '繁體中文' },
  { id: 'en', name: 'English' },
  { id: 'ja', name: '日本語' },
];

// 语言偏好：s 简体 / t 繁體 / en English / ja 日本語（旧键 fy.zh 自动迁移）
function getLang() {
  return localStorage.getItem('fy.lang')
    || (localStorage.getItem('fy.zh') === 't' ? 't' : 's');
}

function langName(l) {
  return (LANGS.find((x) => x.id === l) || LANGS[0]).name;
}

// 设置页「语言」一行右侧的当前值。弹层里的选中态在 openLang() 打开时现铺。
function applyLangRow(l) {
  const el = $('#langVal');
  if (el) el.textContent = langName(l);
}

async function setZhTrad(on) {
  if (!on) { location.reload(); return; }   // 回简体：源数据即简体，重载最可靠
  await ensureZh();
  document.title = zhConv(document.title, zhMap);
  zhApply(document.body);
  if (!zhObserver) {
    zhObserver = new MutationObserver((muts) => {
      for (const mu of muts) {
        if (mu.type === 'characterData') {
          const v = zhConv(mu.target.nodeValue, zhMap);
          if (v !== mu.target.nodeValue) mu.target.nodeValue = v;   // 值稳定则不再触发，无循环
        } else {
          for (const n of mu.addedNodes) {
            if (n.nodeType === 3) {
              const v = zhConv(n.nodeValue, zhMap);
              if (v !== n.nodeValue) n.nodeValue = v;
            } else if (n.nodeType === 1) zhApply(n);
          }
        }
      }
    });
    zhObserver.observe(document.body, { childList: true, characterData: true, subtree: true });
  }
}

/* ================= 路由 ================= */

function setSeg(s) { document.body.dataset.seg = s; }

const LIB_ROUTES = /^#(wenku|wkseries\/|read\/|qa\/|wenda)/;
let routeSeq = 0;

async function route() {
  const seq = ++routeSeq;
  const h = location.hash || '#home';
  $('#zenOverlay').hidden = true;   // 静念全屏随换页收起（如安卓返回键退出计数页）
  // 莲友共修群：全屏覆盖的独立模块，底层视图保持不变（可深链/子域名直达）
  if (h.startsWith('#qun')) { openChatRoom(); return; }
  chatBackHash = h;                                   // 记住底层页，供聊天室返回
  if (chatOpen) { chatOpen = false; $('#chatRoom').hidden = true; document.title = SITE_TITLE; }
  // 文库类页面依赖 library/qa 数据：未就绪则先等加载（通常预取已完成）
  if (LIB_ROUTES.test(h) && !library) {
    try { await ensureLibrary(); } catch { if (seq === routeSeq) showLibError(); return; }
    if (seq !== routeSeq) return;   // 等待期间用户已换页，本次路由作废
  }
  let view = 'home', tab = 'home';
  if (h.startsWith('#home')) { view = 'home'; tab = 'home'; buildHome(); }
  else if (h.startsWith('#ting')) { view = 'ting'; tab = 'ting'; setSeg('ting'); buildTing(); }
  else if (h.startsWith('#shu')) { view = 'ting'; tab = 'ting'; setSeg('shu'); buildShu(); }
  else if (h.startsWith('#fohao')) { view = 'ting'; tab = 'ting'; setSeg('fohao'); buildFohao(); }
  else if (h.startsWith('#series/')) {
    view = 'series'; tab = 'ting';
    const [sid, epn] = h.slice(8).split('/');   // 可带集号深链：#series/<id>/<第n集>
    openSeries(sid, epn ? Number(epn) : null);
  }
  else if (h.startsWith('#live')) { view = 'live'; tab = 'ting'; setSeg('ting'); }
  else if (h.startsWith('#schedule')) { view = 'schedule'; tab = 'ting'; setSeg('ting'); renderSchedule(); }
  else if (h.startsWith('#wkseries/')) { view = 'wenku'; tab = 'wenku'; openWkSeries(h.slice(10)); }
  else if (h.startsWith('#wenku')) { view = 'wenku'; tab = 'wenku'; $('#wkSeries').hidden = true; $('#wkHome').hidden = false; renderWkResume(); }
  else if (h.startsWith('#read/')) { view = 'reader'; openChapter(h.slice(6)); tab = reader.backHash === '#wenda' ? 'wenda' : 'wenku'; }
  else if (h.startsWith('#qa/')) { view = 'reader'; openQa(Number(h.slice(4))); tab = 'wenda'; }
  else if (h.startsWith('#count')) { view = 'count'; tab = 'wode'; renderCount(); }
  else if (h.startsWith('#wode') || h.startsWith('#nianfo')) { view = 'wode'; tab = 'wode'; renderWode(); }
  else if (h.startsWith('#wenda')) { view = 'wenda'; tab = 'wenda'; }
  // 计数器页：刷新工具态并按需申请屏幕常亮；离开则释放
  if (view === 'count') {
    if (localStorage.getItem('fy.wake') !== '0') requestWake();
    // 进页即预热音频：iOS 首次出声须在用户手势内，而「进计数页」正由点击导航触发。
    // 不预热的话第一声木鱼常是哑的 —— 偏偏就是用户初次尝试的那一声。
    primeAudio();
    startGongxiu();
  } else {
    stopGongxiu();
    if (_wakeLock) releaseWake();
  }
  if (view !== 'reader') {
    document.body.classList.remove('rd-zen');   // 离开阅读器退出沉浸
    ttsStop();                                  // 离开阅读器停朗读
  }
  $('#quoteChip').hidden = true;
  document.body.dataset.view = view;
  if (view === 'live') {
    refreshLiveLike();   // 随喜此刻节目
    // 进入直播即自动播放：用户手势下可直接起播，被浏览器自动播放策略拦截时 loadLive 回落到「轻触莲台」
    if (mode !== 'live') backToLive();
    else if (audio.paused) { wantLive = true; loadLive(); }
  }
  syncCmtPolling();                   // 按「聊天室开 / 在直播页」决定留言轮询节奏
  document.body.dataset.tab = tab;  // 导航高亮与子栏面板显示依赖 data-tab / data-seg
  document.querySelectorAll('a[data-tab]').forEach((a) => a.classList.toggle('on', a.dataset.tab === tab));
}

/* ── 与站方对表 ──
   排播由各客户端自行推演，靠的是大家时钟一致。本机时钟一偏，
   听到的就不是大众此刻正听的那一句 —— 而人不自知，还当自己正与大众同闻。
   问一次 /api/time，以往返时延的一半作补偿。整件事失败也不要紧：
   退回本机时钟，与从前一样，不比从前更差。 */
async function syncClock() {
  try {
    const t0 = Date.now();
    const r = await fetch('/api/time', { cache: 'no-store' });
    if (!r.ok) return;
    const server = Number(await r.text());
    const t1 = Date.now();
    if (!Number.isFinite(server) || server <= 0) return;
    const rtt = t1 - t0;
    if (rtt > 8000) return;            // 往返太久，补偿不可信，宁可不校
    setClockSkew(server + rtt / 2 - t1);
    const off = Math.abs(clockSkewMs());
    // 差得离谱才出声：多数人并不知道自己手机时间不准，说一句，也让他去系统里改
    if (off > 120000) {
      toast(`本机时间差了约 ${Math.round(off / 60000)} 分钟 · 直播已按标准时间对齐`);
    }
  } catch { /* 没网就按本机时钟走 */ }
}

/* ================= 主循环 ================= */

function tick() {
  const t = stationNow();
  const { item, offset, next } = station.liveAt(t, 3);

  const p = bjParts(nowMs());
  const dateStr =
    `${p.y}年${p.mo}月${p.d}日 · 周${WEEK[p.day]} · 北京时间 ${String(p.h).padStart(2, '0')}:${String(p.mi).padStart(2, '0')}`;
  $('#nowDate').textContent = dateStr;
  $('#homeDate').textContent = dateStr;

  // 外观：默认纸墨固定档；用户选择 auto（四时流转）时才随直播时段昼夜走
  const pref = themePref();
  const theme = pref === 'auto' ? item.block.theme : pref;
  document.body.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', themeMetaColor(theme));

  if (!liveItem || liveItem.start !== item.start) {
    liveItem = item;
    renderLive(item, next);
    if (mode === 'live' && wantLive) loadLive();
    if (document.body.dataset.view === 'schedule') renderSchedule();
  }

  // 直播栏目条（听经台 + 首页）：系列名优先，集号短显，不塞已播时间避免挤掉系列名
  $('#lsSeries').textContent = item.ep.seriesTitle;
  $('#lsEp').textContent = item.ep.title;
  $('#hlSeries').textContent = item.ep.seriesTitle;
  $('#hlEp').textContent = item.ep.title;
  $('#hlBlock').textContent = item.block.name;   // 首页正在播出卡多一层时段（子夜讲堂/晨诵…）

  const pct = `${Math.min(100, (offset / item.ep.dur) * 100)}%`;
  $('#liveFill').style.width = pct;
  $('#hlFill').style.width = pct;                // 首页卡底缘细线＝本集已播进度
  $('#liveElapsed').textContent = fmtMMSS(offset);
  $('#liveTotal').textContent = fmtMMSS(item.ep.dur);

  /* 与直播位置对齐。原先是「差 40 秒之内不管，超了硬跳」——
     两头都不妥：40 秒之内两位莲友听的不是同一句；一旦硬跳，
     正听着的那半句话就被切掉了，讲经不比音乐，断在句中很难受。
     改成差得多才跳，差得少用快慢慢慢找齐：4% 的速差听不出来
     （浏览器默认保音高），差十秒也就几分钟内无声无息地对上。 */
  if (mode === 'live' && !audio.paused && seekPending === null) {
    const drift = audio.currentTime - offset;   // 正＝走快了，负＝落后了
    const ad = Math.abs(drift);
    if (ad > 40) {
      audio.currentTime = offset;               // 多半是刚从后台回来，此时切一句也无妨
      audio.playbackRate = 1;
    } else if (ad > 2) {
      audio.playbackRate = drift > 0 ? 0.96 : 1.04;
    } else if (audio.playbackRate !== 1) {
      audio.playbackRate = 1;                   // 对上了，回原速
    }
  }
  watchLiveStall();   // 每秒盘一次：位置久不动即断流，不指望 stalled 事件

  if (mode === 'nianfo' && nf.deadline && !audio.paused) {
    if (Date.now() >= nf.deadline) endNianfoSession();
    else $('#nfTimerLabel').textContent = `定课剩余 ${fmtMMSS((nf.deadline - Date.now()) / 1000)}`;
  }

  // 睡眠定时（点播/直播通用）：到点轻轻暂停
  if (sleepT.deadline) {
    if (Date.now() >= sleepT.deadline) {
      audio.pause();
      if (mode === 'live') { wantLive = false; hint('定时已到 · 轻触莲台再续'); }
      setSleep(0);
    } else {
      const leftMin = Math.ceil((sleepT.deadline - Date.now()) / 60000);
      $('#sleepVal').textContent = `${leftMin}分`;
      $('#liveSleepVal').textContent = String(leftMin);   // 工具行角标只放数字
    }
  }

  // 阅读时长：恭读页且前台可见时逐秒累计（fy.rt.<日期>，我的页显示今日分钟数）
  if (document.body.dataset.view === 'reader' && document.visibilityState === 'visible' && reader.path) {
    const rk = 'fy.rt.' + bjDateKey();
    setLS(rk, String((Number(localStorage.getItem(rk)) || 0) + 1), true);
  }

  document.body.dataset.playing = String(mode === 'live' && !audio.paused);
  document.body.dataset.odPlaying = String(mode === 'od' && !audio.paused);
  document.body.dataset.nfPlaying = String(mode === 'nianfo' && !audio.paused);

  // 锁屏进度条（媒体会话位置状态）
  if ('mediaSession' in navigator && navigator.mediaSession.setPositionState
      && Number.isFinite(audio.duration) && audio.duration > 0) {
    try {
      navigator.mediaSession.setPositionState({
        duration: audio.duration,
        playbackRate: audio.playbackRate,
        position: Math.min(audio.currentTime, audio.duration),
      });
    } catch { /* 忽略 */ }
  }
}

/* ================= 首页：今日案头 ================= */

function listenCardHtml(label) {
  // 续听卡（首页/我的共用）：读 fy.last
  let last = null;
  try { last = JSON.parse(localStorage.getItem('fy.last')); } catch { /* 忽略 */ }
  if (!last) return '';
  const s = catalog.series.find((x) => x.id === last.sid);
  const ep = s?.episodes[last.idx];
  if (!ep) return '';
  const saved = getProgress(ep.key);
  return `<button class="home-card" data-resume-listen>
    <span class="hc-label">${label}</span>
    <span class="hc-main"><strong>${esc(s.title)}</strong><em>${esc(ep.title)}${saved ? ' · 续 ' + fmtMMSS(saved) : ''}</em></span>
    <span class="hc-go">播放 ›</span></button>`;
}

function readCardHtml(label) {
  // 续读卡（首页文库/我的共用）：读 fy.lastRead
  if (!library) return '';   // 文库数据未就绪（ensureLibrary 完成后会重绘）
  const spec = localStorage.getItem('fy.lastRead');
  if (!spec) return '';
  const [sid, nStr] = spec.split('/');
  const s = library.series.find((x) => x.id === sid);
  const c = s?.chapters.find((x) => x.n === Number(nStr));
  if (!c) return '';
  return `<a class="home-card" href="#read/${spec}">
    <span class="hc-label">${label}</span>
    <span class="hc-main"><strong>${esc(c.title)}</strong><em>《${esc(s.title)}》</em></span>
    <span class="hc-go">续读 ›</span></a>`;
}

// 首页四门（发现枢纽）：图标一律 24 格线描，朱砂点睛；副题让位于内容，只留门名
const HOME_DOORS = [
  { href: '#ting', name: '听经',
    icon: '<circle cx="12" cy="12" r="8.6"/><path d="M10.2 8.9v6.2l5.3-3.1z" fill="currentColor" stroke="none"/>' },
  { href: '#shu', name: '有声书',
    icon: '<path d="M12 6c-1.8-1.4-4.2-1.8-7-1.6v13.2c2.8-.2 5.2.2 7 1.6 1.8-1.4 4.2-1.8 7-1.6V4.4c-2.8-.2-5.2.2-7 1.6z"/><path d="M12 6v13.2"/>' },
  { href: '#count', name: '念佛',
    icon: '<path d="M12 4.5c2.2 3 2.2 6.7 0 9.7-2.2-3-2.2-6.7 0-9.7z"/><path d="M6.2 8c2.8.9 4.6 3.1 4.8 6.4-3-.6-4.8-3-4.8-6.4zM17.8 8c-2.8.9-4.6 3.1-4.8 6.4 3-.6 4.8-3 4.8-6.4z"/><path d="M4.5 15c2.3 3 12.7 3 15 0-1.6 4.2-13.4 4.2-15 0z"/>' },
  { href: '#wenku', name: '阅读',
    icon: '<rect x="5" y="4" width="14" height="16" rx="2.2"/><path d="M8.6 4v16"/><path d="M11.6 9.2h4.6M11.6 13h4.6"/>' },
];

// 首页 · 别院（同源分站）：选佛谱自成一方星夜天地，与主站的宣纸案头相照。
// 独立站点故新窗打开，右上角 ↗ 明示外链；卡面一眼交代「是什么·怎么行·有多大」，
// 不叫新访者对着「十法界 · 须弥山世界」六字猜谜。
// 须弥山徽记依经作束腰形：顶为天宫，日月半山而转，山根四面香水海。
function branchHtml() {
  return `<section class="home-branch">
    <div class="fh-head"><span class="fh-title">别院</span></div>
    <a class="xf-card" href="https://game.foyue.org/" target="_blank" rel="noopener"
       aria-label="选佛谱 · 十法界须弥山世界，另开新窗">
      <span class="xf-sky" aria-hidden="true"></span>
      <span class="xf-stars" aria-hidden="true"></span>
      <span class="xf-out" aria-hidden="true">↗</span>
      <span class="xf-emblem" aria-hidden="true">
        <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="24" cy="6.4" r="1.3" fill="currentColor" stroke="none"/>
          <path d="M24 9.4V7.7"/>
          <path d="M18.6 12V9.4h10.8V12" opacity="0.9"/>
          <path d="M13.6 12h20.8"/>
          <path d="M13.6 12c1.7 5.6 4.6 9.8 6.6 13-1.8 4.6-5.6 8.8-10 12h27.6c-4.4-3.2-8.2-7.4-10-12 2-3.2 4.9-7.4 6.6-13"/>
          <circle cx="6.8" cy="24.6" r="2" fill="currentColor" stroke="none" opacity="0.92"/>
          <circle cx="41.2" cy="24.6" r="2" stroke-width="1.3" opacity="0.78"/>
          <path d="M8.2 38.8c2.6 1.7 5.3 1.7 7.9 0s5.3-1.7 7.9 0 5.3 1.7 7.9 0 5.3-1.7 7.9 0" opacity="0.6"/>
          <path d="M11.6 42.6c2.2 1.5 4.4 1.5 6.6 0s4.4-1.5 6.6 0 4.4 1.5 6.6 0" opacity="0.38"/>
        </svg>
      </span>
      <span class="xf-main">
        <span class="xf-tag">十法界 · 须弥山世界</span>
        <strong>选佛谱</strong>
        <em>掷占察轮行棋，自初发心直到成佛</em>
        <span class="xf-facts"><i>十五门</i><i>二百二十位</i><i>四人同行</i></span>
      </span>
      <span class="xf-go">入 谱 ›</span>
    </a>
  </section>`;
}

// 首页佛号：七首东林佛号全数陈列，点一首即进全屏播放器循环恭听
function fohaoHomeHtml() {
  const s = catalog.series.find((x) => x.id === 'fohao');
  if (!s || !s.episodes.length) return '';
  const cells = s.episodes.map((ep, i) =>
    `<button class="fh-chip" data-fh-idx="${i}">
      <span class="fh-play" aria-hidden="true">
        <svg class="fh-ic-play" viewBox="0 0 24 24"><path d="M8 5.5v13l11-6.5z"/></svg>
        <span class="fh-eq"><i></i><i></i><i></i></span>
      </span>
      <span class="fh-txt"><strong>${esc(ep.title)}</strong><em>${fmtDur(ep.dur)}</em></span>
    </button>`).join('');
  return `<section class="home-fohao" data-fohao-home="${s.id}">
    <div class="fh-head">
      <span class="fh-title">佛号</span>
      <a class="fh-all" href="#fohao">经咒 ›</a>
    </div>
    <div class="fh-grid">${cells}</div>
  </section>`;
}

function buildHome() {
  // 首页信息秩序（今日案头）：正在播出（在 HTML 里，常驻首屏）
  // → 个人续接（续听 / 续读）→ 四门导航 → 别院 → 佛号速取
  // 续听、续读只在有足迹时出现；新访者看到的是「直播 + 四门 + 别院 + 佛号」的干净案头
  let html = listenCardHtml('继续收听') + readCardHtml('继续阅读');

  // 四门一行（听经 / 有声书 / 念佛 / 阅读）——只占一行，把首屏让给内容
  html += '<nav class="home-doors" aria-label="板块入口">' + HOME_DOORS.map((d) =>
    `<a class="door" href="${d.href}">
      <span class="door-ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${d.icon}</svg></span>
      <span class="door-t">${d.name}</span></a>`).join('') + '</nav>';

  // 别院（选佛谱，独立站点）
  html += branchHtml();

  // 佛号速取（极简两列，随手起一炉佛号循环恭听）
  html += fohaoHomeHtml();

  $('#homeCards').innerHTML = html;
  markFohaoHome();   // 若正循环恭听某首佛号，重绘后同步高亮
}

/* ================= 播放底层 ================= */

function audioUrl(bucket, key) {
  return `/audio/${bucket}/` + key.split('/').map(encodeURIComponent).join('/');
}

/* ================= 离线音频（下载后 App 内可离线恭听） =================
   音频 blob 存 IndexedDB，轻量元信息（系列/集名/大小/时间）存 localStorage，
   二者以 ep.key 对应。播放时 startOd 优先用内存里的 blob objectURL，无网也能听。 */
const ODB_NAME = 'foyue-offline', ODB_STORE = 'audio', OFF_META = 'fy.offline.meta';
let _odb = null;
const offlineURLs = {};                 // key -> objectURL（内存态，可同步取用）
const offlineDownloading = new Set();   // 正在下载的 key
const offlineProgress = {};             // key -> 0..1

function offlineDB() {
  if (_odb) return Promise.resolve(_odb);
  return new Promise((resolve, reject) => {
    let req;
    try { req = indexedDB.open(ODB_NAME, 1); } catch (e) { reject(e); return; }
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(ODB_STORE)) db.createObjectStore(ODB_STORE, { keyPath: 'key' });
    };
    req.onsuccess = () => { _odb = req.result; resolve(_odb); };
    req.onerror = () => reject(req.error);
  });
}
function odbGet(key) {
  return offlineDB().then((db) => new Promise((resolve, reject) => {
    const r = db.transaction(ODB_STORE, 'readonly').objectStore(ODB_STORE).get(key);
    r.onsuccess = () => resolve(r.result ? r.result.blob : null);
    r.onerror = () => reject(r.error);
  }));
}
function odbPut(key, blob) {
  return offlineDB().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction(ODB_STORE, 'readwrite');
    tx.objectStore(ODB_STORE).put({ key, blob });
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  }));
}
function odbDel(key) {
  return offlineDB().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction(ODB_STORE, 'readwrite');
    tx.objectStore(ODB_STORE).delete(key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  }));
}

function offlineMeta() { try { return JSON.parse(localStorage.getItem(OFF_META) || '{}'); } catch { return {}; } }
function saveOfflineMeta(m) { setLS(OFF_META, JSON.stringify(m), true); }
function offlineHas(key) { return !!offlineMeta()[key]; }
function offlineTotal() { const m = offlineMeta(); return Object.keys(m).reduce((s, k) => s + (m[k].size || 0), 0); }

// 启动时把已下载 blob 逐个建成 objectURL，之后 startOd 可同步取用（离线亦可）；顺带自愈丢失项
async function hydrateOfflineURLs() {
  const m = offlineMeta(); let changed = false;
  for (const key of Object.keys(m)) {
    if (offlineURLs[key]) continue;
    try {
      const blob = await odbGet(key);
      if (blob) offlineURLs[key] = URL.createObjectURL(blob);
      else { delete m[key]; changed = true; }   // 元信息在但 blob 丢了：清掉
    } catch { /* 忽略 */ }
  }
  if (changed) saveOfflineMeta(m);
}

// 下载 od 的某一集到本地（带进度）
async function downloadOffline(o, idx) {
  if (!o || !o.list[idx]) return;
  const ep = o.list[idx], key = ep.key;
  if (offlineHas(key) || offlineDownloading.has(key)) return;
  offlineDownloading.add(key); offlineProgress[key] = 0;
  updateDownloadBtn(); refreshDownloadsUI();
  try {
    const resp = await fetch(audioUrl(o.bucket, key));
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const total = Number(resp.headers.get('content-length')) || 0;
    let blob;
    if (resp.body && resp.body.getReader) {
      const reader = resp.body.getReader(); const chunks = []; let recv = 0, lastPaint = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value); recv += value.length;
        offlineProgress[key] = total ? recv / total : 0;
        if (Date.now() - lastPaint > 200) { lastPaint = Date.now(); updateDownloadBtn(); }
      }
      blob = new Blob(chunks, { type: resp.headers.get('content-type') || 'audio/mpeg' });
    } else {
      blob = await resp.blob();   // 退化：不支持流式进度时整段取
    }
    await odbPut(key, blob);
    offlineURLs[key] = URL.createObjectURL(blob);
    const m = offlineMeta();
    m[key] = { sid: o.seriesId, title: o.title, sub: o.sub || '有声书', epTitle: ep.title, dur: ep.dur, size: blob.size, savedAt: Date.now() };
    saveOfflineMeta(m);
    toast('已下载 · 「我的 · 已下载」可离线恭听');
  } catch (err) {
    toast('下载失败 · ' + (err && err.message ? err.message : '请重试'));
  } finally {
    offlineDownloading.delete(key); delete offlineProgress[key];
    updateDownloadBtn(); refreshDownloadsUI();
  }
}

async function removeOffline(key) {
  try { await odbDel(key); } catch { /* 忽略 */ }
  if (offlineURLs[key]) { try { URL.revokeObjectURL(offlineURLs[key]); } catch { /* 忽略 */ } delete offlineURLs[key]; }
  const m = offlineMeta(); delete m[key]; saveOfflineMeta(m);
}
async function clearAllOffline() {
  const m = offlineMeta();
  for (const key of Object.keys(m)) {
    try { await odbDel(key); } catch { /* 忽略 */ }
    if (offlineURLs[key]) { try { URL.revokeObjectURL(offlineURLs[key]); } catch { /* 忽略 */ } delete offlineURLs[key]; }
  }
  saveOfflineMeta({});
}

// 刷新播放器「下载」键：未下载 / 下载中(%) / 已下载
function updateDownloadBtn() {
  const b = $('#btnDownload'); if (!b) return;
  const lb = b.querySelector('span');
  const key = (mode === 'od' && od && od.list[od.idx]) ? od.list[od.idx].key : null;
  b.classList.remove('on', 'loading');
  if (!key) { if (lb) lb.textContent = '下载'; return; }
  if (offlineDownloading.has(key)) {
    b.classList.add('loading');
    const p = offlineProgress[key] || 0;
    if (lb) lb.textContent = p > 0 ? Math.round(p * 100) + '%' : '下载中';
  } else if (offlineHas(key)) {
    b.classList.add('on');
    if (lb) lb.textContent = '已下载';
  } else if (lb) {
    lb.textContent = '下载';
  }
}

// iOS 且未加主屏时，离线内容可能被系统回收 —— 给一句引导（仅 iPhone 显示）
function iosOfflineHint() {
  const ua = navigator.userAgent;
  const isIOS = /iP(hone|od|ad)/.test(ua) || (/Macintosh/.test(ua) && 'ontouchend' in document);
  const standalone = window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
  return (isIOS && !standalone) ? '<span class="dl-ios">· iPhone 请把本站「添加到主屏幕」，离线内容更不易被系统回收</span>' : '';
}

// 渲染「我的 · 已下载」
// 离线下载清单 HTML（弹层内展示）
function downloadsHtml() {
  const m = offlineMeta();
  const keys = Object.keys(m).sort((a, b) => (m[b].savedAt || 0) - (m[a].savedAt || 0));
  const dling = [...offlineDownloading].filter((k) => !m[k]);
  if (!keys.length && !dling.length) return '<p class="bk-note">还没有离线下载。在播放器点「下载」即可离线恭听。</p>';
  let rows = keys.map((k) =>
    `<li data-dlplay="${esc(k)}" data-dlsid="${esc(m[k].sid)}">
        <span class="t">${esc(m[k].epTitle)}<small>《${esc(m[k].title)}》· ${((m[k].size || 0) / 1048576).toFixed(1)} MB</small></span>
        <button class="fav-del" data-dldel="${esc(k)}" aria-label="删除离线">✕</button></li>`).join('');
  rows += dling.map((k) => {
    const p = Math.round((offlineProgress[k] || 0) * 100);
    return `<li class="dl-ing"><span class="t">正在下载 …<small>${p}%</small></span></li>`;
  }).join('');
  return `<ol class="ep-list fav-list">${rows}</ol>
    <p class="dl-total">共 ${keys.length} 集 · ${(offlineTotal() / 1048576).toFixed(1)} MB ${iosOfflineHint()}</p>`;
}
// 离线数据变动后刷新：弹层开着重绘弹层，在我的页则更新行计数
function refreshDownloadsUI() {
  if (cntSheetMode === 'downloads') $('#cntSheetBody').innerHTML = downloadsHtml();
  if (document.body.dataset.view === 'wode') renderWode();
}

function switchMode(m) {
  mode = m;
  document.body.dataset.mode = m;
  audio.loop = (m === 'nianfo');
  if (m !== 'od') { $('#mini').hidden = true; od = null; markPlayingRow(); }
  if (m === 'nianfo') setSleep(0);   // 念佛堂有自己的定课计时，睡眠定时让位
  if (m !== 'live') {
    wantLive = false;
    // 直播为对齐进度可能正微调着快慢，走之前务必归位，
    // 否则这点速差会跟着带进点播与佛号里
    audio.playbackRate = 1;
    clearLiveWatch();
    clearTimeout(liveRetryT);
  }
}

function setSleep(min) {
  sleepT = { min, deadline: min > 0 ? Date.now() + min * 60000 : null };
  $('#sleepVal').textContent = min > 0 ? `${min}分` : '定时';   // 播放器内闹钟下方文字
  const badge = $('#liveSleepVal');                             // 直播工具行角标（图标钮）
  badge.textContent = min > 0 ? String(min) : '';
  badge.hidden = min <= 0;
  $('#btnSleep').classList.toggle('on', min > 0);
  $('#btnLiveSleep').classList.toggle('on', min > 0);
}

function setMiniExpanded(v) {
  miniExpanded = v;
  setLS('fy.miniExp', v ? '1' : '0');
  $('#mini').classList.toggle('collapsed', !v);
}

function closeOd() {
  // 关闭点播条：存进度、停播、取消定时、回到直播待机（不自动开播）
  saveProgress();
  audio.pause();
  setSleep(0);
  switchMode('live');
}

function stepEpisode(d) {
  if (!od || !od.list[od.idx + d]) return;
  saveProgress();
  od.idx += d;
  startOd();
}

function playStatus(text) {
  // 缓冲/网络状态提示：点播显示在播放条，直播显示在莲台提示行
  if (mode === 'od') $('#miniStatus').textContent = text;
  else if (mode === 'live') hint(text || '正与大众同闻');
}

/* ================= 直播 ================= */

function renderLive(item, next) {
  $('#blockName').textContent = `${item.block.name} · ${item.block.sub}`;
  $('#liveSeries').textContent = item.ep.seriesTitle;
  $('#liveEp').textContent = item.ep.title + (item.filler ? ' · 间奏' : '');
  $('#nextList').innerHTML = next.map((x) =>
    `<li><time>${fmtClock(x.start)}</time><span>${esc(x.ep.seriesTitle)} ${esc(x.ep.title)}${x.filler ? '<span class="tag">间奏</span>' : ''}</span></li>`
  ).join('');
  if (mode === 'live') updateMediaSession(item.ep, '直播');
  if (document.body.dataset.view === 'live') refreshLiveLike();   // 换节目即刷新随喜态
}

// retry=true 表示这是断流后的重连：此时 URL 通常与刚才失败的那次相同，
// 只赋 src 浏览器认作没变、不会重新取流，必须显式 load() 才真的再连一次。
function loadLive(retry = false) {
  const { item } = station.liveAt(stationNow());
  const url = audioUrl(item.ep.bucket, item.ep.key);
  if (!audio.src.endsWith(url)) {
    audio.src = url;
    audio.playbackRate = 1;
  } else if (retry) {
    audio.load();
  }
  seekPending = Math.max(0, stationNow() - item.start);
  // 同一集内续播时 loadedmetadata 不会再触发：元数据已就绪则立即跳到直播位置，
  // 否则 seekPending 卡住不清，既不同步又堵死 tick 的漂移校正
  if (audio.readyState >= 1) {
    try { audio.currentTime = seekPending; } catch { /* 忽略 */ }
    seekPending = null;
  }
  updateMediaSession(item.ep, '直播');
  audio.play().catch((e) => {
    // 只有「浏览器要用户先动手」才收手。原先不分缘由一律清掉 wantLive，
    // 于是网络/解码失败时，下面 error 处理器的 if (wantLive) 永不成立 ——
    // 直播断流后既不重连、提示也被这句盖成「轻触莲台」，等于自动重连从未生效。
    if (!e || e.name === 'NotAllowedError' || e.name === 'AbortError') {
      wantLive = false;
      hint('轻触莲台 · 与大众同闻');
    }
    // 其余（NotSupportedError 等取流失败）留给 error 处理器退避重试
  });
}

function hint(text) { $('#liveHint').textContent = text; }

/* ── 直播断流：退避重连 ──
   两处会走到这里 ——
     · audio 报 error：取流明确失败；
     · 看门狗：播放位置十几秒纹丝不动。整段音频走 Range 请求，中途被掐断时
       Chrome 多半报 error，而 Safari 常常只是卡在 stalled、一声不响。
       原先只认 error，于是 iPhone 上放着听经睡去，半夜网络一抖，
       屏上「缓冲中 …」挂到天亮也接不回来。 */
function liveFail(why) {
  if (mode !== 'live' || !wantLive) return;
  clearLiveWatch();
  liveRetry = Math.min(liveRetry + 1, 4);
  const wait = 4000 * 2 ** (liveRetry - 1);          // 4s → 8s → 16s → 32s 封顶
  hint(`${why}，${Math.round(wait / 1000)} 秒后重试 …`);
  clearTimeout(liveRetryT);
  liveRetryT = setTimeout(() => {
    liveRetryT = 0;      // 归零，好让停滞盘点重新上岗
    if (mode === 'live' && wantLive) loadLive(true);
  }, wait);
}

/* 卡住判定放在 tick 里按秒盘点，不挂在 stalled/waiting 事件上 ——
   那两个事件各家浏览器给不给、什么时候给都不一样，指望它们等于把成败交给运气。
   每秒看一眼位置有没有动，十五秒纹丝不动就是断了：
   寻常缓冲几秒即回，误判不了；而真断了，事件给不给都拦得住。 */
const LIVE_STALL_SEC = 15;

function clearLiveWatch() { liveWatchSec = 0; liveWatchPos = -1; }

function watchLiveStall() {
  // 重连本就在途中时不再盘点，免得把退避的节奏打乱
  if (mode !== 'live' || !wantLive || audio.paused || liveRetryT) { clearLiveWatch(); return; }
  const now = audio.currentTime;
  if (liveWatchPos < 0 || Math.abs(now - liveWatchPos) > 0.5) {
    liveWatchPos = now;      // 还在走
    liveWatchSec = 0;
    return;
  }
  if (++liveWatchSec >= LIVE_STALL_SEC) {
    clearLiveWatch();
    liveFail('直播卡住');
  }
}

function backToLive() {
  switchMode('live');
  wantLive = true;
  loadLive();
}

// 直播播放/暂停（直播页莲台钮与首页正在播出卡共用）
// data-playing 由 tick 每秒校正，这里先乐观置位，按下即变图标，不等一秒
function toggleLive() {
  if (mode !== 'live') { backToLive(); }
  else if (audio.paused) { wantLive = true; loadLive(); hint('正与大众同闻'); }
  else {
    audio.pause(); wantLive = false;
    audio.playbackRate = 1;            // 暂停即归位，免得下次接上还带着速差
    clearLiveWatch(); clearTimeout(liveRetryT);
    hint('已暂停 · 轻触回到直播');
  }
  document.body.dataset.playing = String(wantLive);
}

/* ================= 点播 ================= */

function playEpisode(series, idx) {
  switchMode('od');
  const isLoop = series.cat === '课诵';   // 佛号 / 念诵：循环恭听，不记进度、不变速
  od = {
    title: series.title, seriesId: series.id, bucket: series.bucket, cat: series.cat,
    list: series.episodes, idx, progress: !isLoop, loop: isLoop,
  };
  setMiniExpanded(true);   // 点开某集即进入全屏播放器
  startOd();
}

function startOd() {
  const ep = od.list[od.idx];
  audio.src = offlineURLs[ep.key] || audioUrl(od.bucket, ep.key);   // 已离线则用本地 blob
  audio.loop = !!od.loop;   // 佛号循环恭听
  const saved = od.progress ? getProgress(ep.key) : 0;
  seekPending = saved && saved < ep.dur - 30 ? saved : 0;
  audio.playbackRate = od.loop ? 1 : currentRate();
  audio.play().catch(() => {});
  $('#mini').hidden = false;
  $('#mini').classList.toggle('collapsed', !miniExpanded);
  $('#miniStatus').textContent = '';
  $('#miniSeries').textContent = od.title;
  $('#miniEp').textContent = ep.title;
  $('#plSeries').textContent = od.title;
  $('#plEp').textContent = ep.title;
  $('#plTag').textContent = od.loop ? '佛号' : SHU_CATS.includes(od.cat) ? '有声书' : '听经台';
  $('#miniDur').textContent = fmtMMSS(ep.dur);
  $('#rateVal').textContent = `${currentRate()}×`;
  $('#btnRate').hidden = !!od.loop;   // 佛号不变速
  $('#btnPrevEp').disabled = od.idx <= 0;
  $('#btnNextEp').disabled = od.idx >= od.list.length - 1;
  updateFav();
  updateDownloadBtn();
  refreshLike();
  updateMediaSession({ ...ep, seriesTitle: od.title }, od.loop ? '佛号' : '点播');
  markPlayingRow();
  plsMark();
  // 记住最后收听位置（首页"继续收听"用）
  if (od.progress && od.seriesId) {
    setLS('fy.last', JSON.stringify({ sid: od.seriesId, idx: od.idx }), true);
  }
}

function currentRate() { return Number(localStorage.getItem('fy.rate') || '1'); }
function getProgress(key) { const v = localStorage.getItem('fy.p.' + key); return v ? Number(v) : 0; }

function saveProgress() {
  if (mode !== 'od' || !od || !od.progress) return;
  const ep = od.list[od.idx];
  if (audio.currentTime > 10 && audio.currentTime < ep.dur - 30) {
    setLS('fy.p.' + ep.key, String(Math.floor(audio.currentTime)), true);
  } else if (audio.currentTime >= ep.dur - 30) {
    delLS('fy.p.' + ep.key);
  }
}

/* 收藏（当前点播集，仅存本机；清单见「我的」页） */
function favKey() { return (mode === 'od' && od) ? 'fy.fav.' + od.list[od.idx].key : null; }
function updateFav() { const k = favKey(); $('#btnFav').classList.toggle('on', !!(k && localStorage.getItem(k))); }
function toggleFav() {
  const k = favKey(); if (!k) return;
  if (localStorage.getItem(k)) delLS(k);
  else if (!setLS(k, '1')) toast('未能收藏 · 本机存储不可写');
  updateFav();
}

function favList() {
  // 收集 fy.fav.* 并映射回目录（桶内容变更后失效的键自然跳过）
  const keyMap = new Map();
  for (const s of catalog.series) s.episodes.forEach((ep, i) => keyMap.set(ep.key, { s, i }));
  const items = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (!k || !k.startsWith('fy.fav.')) continue;
    const hit = keyMap.get(k.slice(7));
    if (hit) items.push(hit);
  }
  items.sort((a, b) => a.s.title === b.s.title ? a.i - b.i : a.s.title.localeCompare(b.s.title, 'zh'));
  return items;
}

// 收藏总数（听经 + 阅读书签），我的页行数用
function favCount() { return favList().length + (library ? bkList().length : 0); }
// 收藏清单 HTML（听经 + 阅读，弹层内展示）
function favsHtml() {
  const items = favList();
  const bks = library ? bkList() : [];
  let html = items.length
    ? '<p class="sheet-cat">听经</p><ol class="ep-list fav-list">' + items.map(({ s, i }) =>
      `<li data-fs="${s.id}" data-fi="${i}">
        <span class="t">${esc(s.episodes[i].title)}<small>《${esc(s.title)}》</small></span>
        <span class="d">${fmtDur(s.episodes[i].dur)}</span>
        <button class="fav-del" data-unfav="${esc(s.episodes[i].key)}" aria-label="移除收藏">✕</button></li>`).join('') + '</ol>'
    : '';
  html += bks.length
    ? '<p class="sheet-cat">阅读</p><ol class="ep-list fav-list">' + bks.map(({ spec, s, c }) =>
      `<li data-bkr="${spec}">
        <span class="t">${esc(c.title)}<small>《${esc(s.title)}》</small></span>
        ${chapProgLabel(c)}
        <button class="fav-del" data-unbk="${spec}" aria-label="移除收藏">✕</button></li>`).join('') + '</ol>'
    : '';
  return html;
}

/* ================= 节目单 ================= */

function renderSchedule() {
  const t = stationNow();
  const today = Math.floor(t / 86400);
  const items = station.dayItems(today + schedDay);
  const dayStart = (today + schedDay) * 86400;

  let html = '';
  let lastBlock = null;
  for (const it of items) {
    const straddle = it.start < dayStart - 60;
    if (straddle && it.end < dayStart + 60) continue;
    if (it.block !== lastBlock) {
      if (lastBlock) html += '</div>';
      lastBlock = it.block;
      html += `<div class="sched-block"><div class="sched-block-head">
        <time>${fmtClock(it.block.start)}</time><h3>${esc(it.block.name)}</h3><small>${esc(it.block.sub)}</small></div>`;
    }
    const isNow = liveItem && it.start === liveItem.start && schedDay === 0;
    html += `<div class="sched-item${it.filler ? ' filler' : ''}${isNow ? ' now' : ''}">
      <time>${straddle ? '接昨日' : fmtClock(it.start)}</time>
      <span class="t">${esc(it.ep.seriesTitle)} ${esc(it.ep.title)}${it.filler ? '<span class="tag">间奏</span>' : ''}</span>
      ${isNow ? '<span class="live-dot"></span>' : ''}</div>`;
  }
  if (lastBlock) html += '</div>';
  $('#schedList').innerHTML = html;
}

/* ================= 听经台 / 有声书 ================= */

function seriesResume(s) {
  // 该系列的收听记忆：取最靠后一集的未听完进度；若全站最后播放的正是本系列，以其集数为准
  let best = null;
  for (let i = s.episodes.length - 1; i >= 0; i--) {
    const saved = getProgress(s.episodes[i].key);
    if (saved > 0) { best = { idx: i, saved }; break; }
  }
  try {
    const last = JSON.parse(localStorage.getItem('fy.last'));
    if (last && last.sid === s.id && s.episodes[last.idx] && (!best || last.idx >= best.idx)) {
      best = { idx: last.idx, saved: getProgress(s.episodes[last.idx].key) };
    }
  } catch { /* 忽略 */ }
  return best;
}

function seriesGroupsHtml(cats) {
  let html = '';
  for (const cat of cats) {
    const list = catalog.series.filter((s) => s.cat === cat);
    if (!list.length) continue;
    html += `<div class="lib-cat"><h3>${cat}</h3><div class="lib-grid">`;
    for (const s of list) {
      const r = seriesResume(s);
      html += `<a class="series-card" href="/series/${encodeURIComponent(s.id)}" data-series="${s.id}">
        <strong>${esc(s.title)}</strong>
        <span>${s.count} 集 · ${fmtDur(s.totalDur)}</span>
        ${r ? `<span class="sc-resume">听至 第${r.idx + 1}集${r.saved ? ' · 续 ' + fmtMMSS(r.saved) : ''}</span>` : ''}</a>`;
    }
    html += '</div></div>';
  }
  return html;
}

function buildTing() { $('#tingGroups').innerHTML = seriesGroupsHtml(TING_CATS); }
function buildShu() { $('#shuGroups').innerHTML = seriesGroupsHtml(SHU_CATS); }

/* ── 听经搜索：本地匹配系列名与集名，覆盖听经台/有声书/佛号 ── */
function runSearch(qRaw) {
  let q = qRaw.trim().toLowerCase();
  if (q && zhBack && zhTradOn()) q = zhConv(q, zhBack);   // 繁体输入转回简体匹配目录
  const res = $('#searchResults');
  if (!q) {
    document.body.removeAttribute('data-searching');
    res.hidden = true; res.innerHTML = '';
    return;
  }
  document.body.setAttribute('data-searching', '');
  const cards = [];
  const eps = [];
  for (const s of catalog.series) {
    if (s.title.toLowerCase().includes(q)) {
      cards.push(`<a class="series-card" href="/series/${encodeURIComponent(s.id)}" data-series="${s.id}">
        <strong>${esc(s.title)}</strong>
        <span>${esc(s.cat)} · ${s.count} 集 · ${fmtDur(s.totalDur)}</span></a>`);
    }
    for (let i = 0; i < s.episodes.length && eps.length < 60; i++) {
      const ep = s.episodes[i];
      if (ep.title.toLowerCase().includes(q)) {
        eps.push(`<li data-fs="${s.id}" data-fi="${i}">
          <span class="t">${esc(ep.title)}<small>《${esc(s.title)}》</small></span>
          <span class="d">${fmtDur(ep.dur)}</span></li>`);
      }
    }
  }
  res.innerHTML =
    (cards.length ? `<div class="lib-grid">${cards.join('')}</div>` : '') +
    (eps.length ? `<ol class="ep-list search-eps">${eps.join('')}</ol>` : '') +
    (cards.length || eps.length ? '' : '<p class="page-note">未找到相关内容</p>');
  res.hidden = false;
}

function openSeries(id, epn = null) {
  const s = catalog.series.find((x) => x.id === id);
  if (!s) { location.hash = '#ting'; return; }
  const seg = s.cat === '课诵' ? 'fohao' : SHU_CATS.includes(s.cat) ? 'shu' : 'ting';
  setSeg(seg);   // 记住来源子栏，返回键回对应栏目
  $('#btnSeriesBack').dataset.back = '#' + seg;
  $('#seriesName').textContent = s.title;
  $('#seriesMeta').textContent = `${s.count} 集 · 共 ${fmtDur(s.totalDur)}`;
  const intro = SERIES_INTROS[s.id] || '';
  $('#seriesIntro').textContent = intro;
  $('#seriesIntro').hidden = !intro;
  $('#epList').innerHTML = s.episodes.map((ep, i) => {
    const saved = getProgress(ep.key);
    return `<li data-idx="${i}">
      <span class="n">${i + 1}</span>
      <span class="t">${esc(ep.title)}</span>
      ${saved ? `<span class="resume">续 ${fmtMMSS(saved)}</span>` : ''}
      <span class="d">${fmtDur(ep.dur)}</span></li>`;
  }).join('');
  $('#epList').dataset.series = id;
  // 续播条：正在播放本系列时不显示（列表已高亮），否则给一键续听
  const r = seriesResume(s);
  const playingThis = mode === 'od' && od && od.seriesId === id;
  $('#seriesResume').innerHTML = (r && !playingThis)
    ? `<button class="home-card" data-resume="${r.idx}">
        <span class="hc-label">继续收听</span>
        <span class="hc-main"><strong>${esc(s.episodes[r.idx].title)}</strong><em>${r.saved ? '续 ' + fmtMMSS(r.saved) : '第 ' + (r.idx + 1) + ' 集'}</em></span>
        <span class="hc-go">播放 ›</span></button>`
    : '';
  markPlayingRow();
  // 分享深链带集号：滚动定位并闪烁提示该集（setTimeout 不依赖渲染帧，后台标签也能触发）
  if (epn && s.episodes[epn - 1]) {
    const li = $('#epList').querySelector(`li[data-idx="${epn - 1}"]`);
    if (li) {
      setTimeout(() => {
        li.scrollIntoView({ block: 'center' });
        li.classList.add('flash');
        setTimeout(() => li.classList.remove('flash'), 3400);
      }, 30);
    }
  }
}

function markPlayingRow() {
  const listSeries = $('#epList').dataset.series;
  document.querySelectorAll('#epList li').forEach((li) => {
    li.classList.toggle('playing',
      mode === 'od' && od && od.seriesId === listSeries && Number(li.dataset.idx) === od.idx);
  });
  markFohaoHome();
}

// 首页佛号横滑条：高亮正在循环恭听的那一首
function markFohaoHome() {
  const sec = document.querySelector('.home-fohao[data-fohao-home]');
  if (!sec) return;
  const on = mode === 'od' && od && od.seriesId === sec.dataset.fohaoHome;
  sec.querySelectorAll('.fh-chip').forEach((c) =>
    c.classList.toggle('playing', on && Number(c.dataset.fhIdx) === od.idx));
}

/* 播放器「目录」抽屉：不离开播放器快速切集 */
function openPlList() {
  if (!(mode === 'od' && od)) return;
  $('#plListTitle').textContent = od.title;
  $('#plListEps').innerHTML = od.list.map((ep, i) =>
    `<li data-pi="${i}"${i === od.idx ? ' class="playing"' : ''}>
      <span class="n">${i + 1}</span>
      <span class="t">${esc(ep.title)}</span>
      <span class="d">${fmtDur(ep.dur)}</span></li>`).join('');
  $('#plListSheet').hidden = false;
  // 当前集居中呈现
  $('#plListEps').querySelector('li.playing')?.scrollIntoView({ block: 'center' });
}

function plsMark() {
  // 切集后同步抽屉高亮（抽屉未开时跳过）
  if ($('#plListSheet').hidden || !od) return;
  document.querySelectorAll('#plListEps li').forEach((li) =>
    li.classList.toggle('playing', Number(li.dataset.pi) === od.idx));
}

/* ================= 文库（阅读站） ================= */

function buildWenku() {
  $('#wkSeriesCount').textContent = library.seriesCount;
  $('#wkChapterCount').textContent = library.chapterCount;
  $('#wkGrid').innerHTML = library.series.map((s) =>
    `<a class="series-card" href="/wkseries/${encodeURIComponent(s.id)}" data-wk="${s.id}">
      <strong>${esc(s.title)}</strong>
      <span>${s.count} 篇</span></a>`
  ).join('');
  renderWkResume();
}

function renderWkResume() {
  // 继续阅读条（最后读的一篇）
  $('#wkResume').innerHTML = readCardHtml('继续阅读');
}

// 篇目尾注：已读 ✓ / 读至 n% / 预计分钟数
function chapProgLabel(c) {
  const pr = readProg(c.path);
  if (pr && pr.pct >= 0.98) return '<span class="d rd-done">已读 ✓</span>';
  if (pr && pr.pct > 0.02) return `<span class="d rd-part">读至 ${Math.round(pr.pct * 100)}%</span>`;
  // 至少记 1 分钟：经文里有「起诵仪」这类两三百字的短章，四舍五入会得 0
  return `<span class="d">${Math.max(1, Math.round(c.chars / 500))} 分钟</span>`;
}

function openWkSeries(sid) {
  const s = library.series.find((x) => x.id === sid);
  if (!s) { location.hash = '#wenku'; return; }
  $('#wkHome').hidden = true;
  $('#wkSeries').hidden = false;
  $('#wkSeriesName').textContent = s.title;
  $('#wkSeriesMeta').textContent = `${s.count} 篇`;
  $('#wkChapList').innerHTML = s.chapters.map((c) =>
    `<li data-read="${s.id}/${c.n}">
      <span class="n">${c.n}</span>
      <span class="t">${esc(c.title)}</span>
      ${chapProgLabel(c)}</li>`
  ).join('');
}

async function openChapter(spec) {
  const [sid, nStr] = spec.split('/');
  const s = library.series.find((x) => x.id === sid);
  const n = Number(nStr);
  const chap = s?.chapters.find((c) => c.n === n);
  if (!chap) { location.hash = '#wenku'; return; }
  const back = pendingReaderBack || `#wkseries/${sid}`;
  pendingReaderBack = null;
  reader = {
    chapters: s.chapters, idx: s.chapters.indexOf(chap), path: chap.path, backHash: back, sid,
    title: chap.title, series: s.title, shareHash: `#read/${spec}`, bkSpec: spec,   // 篇目快切/分享/书签用
    sharePath: `/read/${spec}`,   // 对外分享用真实路径：# 之后的部分搜索引擎看不见，转不成外链
  };
  setLS('fy.lastRead', spec, true);   // 文库"继续阅读"用
  updateBookmark();
  $('#readerPos').textContent = `${reader.idx + 1} / ${s.chapters.length}`;
  await renderReader(chap.title, chap.path, s.title);
  $('#btnPrevChap').disabled = reader.idx === 0;
  // 篇末衔接卡：有下一篇给"恭读下一篇"，末篇给"本部圆满 · 返回目录"
  const next = s.chapters[reader.idx + 1];
  $('#readerNextCard').innerHTML = next
    ? `<button class="home-card" data-next-chap>
        <span class="hc-label">下一篇</span>
        <span class="hc-main"><strong>${esc(next.title)}</strong><em>《${esc(s.title)}》· 第 ${reader.idx + 2} / ${s.chapters.length} 篇</em></span>
        <span class="hc-go">恭读 ›</span></button>`
    : `<a class="home-card" href="#wkseries/${sid}">
        <span class="hc-label">本部圆满</span>
        <span class="hc-main"><strong>已是最后一篇</strong><em>《${esc(s.title)}》全 ${s.chapters.length} 篇</em></span>
        <span class="hc-go">返回目录 ›</span></a>`;
  document.querySelector('.reader-nav').hidden = false;
}

async function openQa(n) {
  const item = qaData.items[n - 1];
  if (!item || !item.text) { location.hash = '#wenda'; return; }
  reader = {
    chapters: null, idx: 0, path: item.text, backHash: '#wenda',
    title: item.title, series: '学佛问答', shareHash: `#qa/${n}`,
    sharePath: `/qa/${n}`,
  };
  pendingReaderBack = null;
  updateBookmark();
  $('#readerPos').textContent = '';
  await renderReader(item.title, item.text, '学佛问答');
  document.querySelector('.reader-nav').hidden = true;
}

async function renderReader(title, path, subtitle) {
  const body = $('#readerBody');
  ttsStop();                           // 换篇即停朗读
  $('#readLine').style.width = '0%';   // 换篇进度线归零
  applyReaderPrefs();
  // 从真实路径（/read/…、/qa/…）进来的头一篇，正文已由 Worker 写进壳里，
  // 段落结构与下面这段生成的完全一致，故直接沿用：省一次取文，也没有先空后满的闪动。
  // 用完即弃标记，站内再翻篇仍走取文。
  if (window.__SSR && window.__SSR.path === path) {
    window.__SSR = null;
    document.body.removeAttribute('data-ssr');
  } else {
    body.innerHTML = '<p class="reader-loading">恭请中 …</p>';
    let text;
    try {
      text = await (await fetch('/text/' + path)).text();
    } catch {
      body.innerHTML = '<p class="reader-loading">加载失败，请稍后再试</p>';
      return;
    }
    const paras = text.split('\n').map((x) => x.trim()).filter(Boolean);
    const normTitle = (x) => x.replace(/^\d+[\s.、]*/, '').replace(/\s/g, '');
    let start = 0;
    if (paras.length && normTitle(paras[0]) === normTitle(title)) start = 1;
    body.innerHTML = `<p class="reader-sub">${esc(subtitle)}</p><h2>${esc(title)}</h2>` +
      paras.slice(start).map((x) => `<p>${esc(x)}</p>`).join('');
  }
  applyHighlights();                   // 铺划线记号
  // 恢复上次位置：从「我的划线」进来优先定位划线段；否则段落锚点（换字号/行距/设备不漂移），旧记录退回滚动比例
  // setTimeout 不依赖渲染帧，后台标签也能触发（同深链定位的做法，rAF 会拿到过期布局）
  const prog = readProg(path);
  const hlT = pendingHlTarget && pendingHlTarget.path === path ? pendingHlTarget : null;
  pendingHlTarget = null;
  setTimeout(() => {
    const kids = body.children;
    if (hlT && kids[hlT.p]) {
      scrollToPara(hlT.p);
    } else if (prog && prog.p != null && kids[prog.p]) {
      const topLine = ($('.reader-bar').offsetHeight || 44) + 8;
      window.scrollTo(0, Math.max(0, kids[prog.p].getBoundingClientRect().top + scrollY - topLine));
    } else if (prog && prog.pct) {
      window.scrollTo(0, prog.pct * (document.body.scrollHeight - innerHeight));
    } else {
      window.scrollTo(0, 0);
    }
  }, 30);
}

// 滚到某段并闪烁提示（划线回看用）
function scrollToPara(p) {
  const el = $('#readerBody').children[p];
  if (!el) return;
  const topLine = ($('.reader-bar').offsetHeight || 44) + 8;
  window.scrollTo(0, Math.max(0, el.getBoundingClientRect().top + scrollY - topLine - 30));
  el.classList.add('hl-flash');
  setTimeout(() => el.classList.remove('hl-flash'), 2400);
}

/* ================= 划线（段落序号 + 字符偏移，换字号/设备不漂移） ================= */

function getHls(path) {
  try { return JSON.parse(localStorage.getItem('fy.hl.' + path)) || []; } catch { return []; }
}
function saveHls(path, arr) {
  if (arr.length) setLS('fy.hl.' + path, JSON.stringify(arr), true);
  else delLS('fy.hl.' + path);
}

// (node, off) 边界在段落 el 内的文本偏移：量 el 起点到边界的文本长度
function offsetIn(el, node, off) {
  const r = document.createRange();
  r.selectNodeContents(el);
  try { r.setEnd(node, off); } catch { return 0; }
  return r.toString().length;
}

// 选区落到各段落的字符区间（支持跨段选择）
function selParaRanges(sel) {
  const out = [];
  if (!sel || sel.isCollapsed || !sel.rangeCount) return out;
  const range = sel.getRangeAt(0);
  [...$('#readerBody').children].forEach((el, p) => {
    if (!range.intersectsNode(el) || !el.textContent.trim()) return;
    const whole = document.createRange();
    whole.selectNodeContents(el);
    const s = range.compareBoundaryPoints(Range.START_TO_START, whole) <= 0
      ? 0 : offsetIn(el, range.startContainer, range.startOffset);
    const e = range.compareBoundaryPoints(Range.END_TO_END, whole) >= 0
      ? el.textContent.length : offsetIn(el, range.endContainer, range.endOffset);
    if (e > s) out.push({ p, s, e });
  });
  return out;
}

// 同段重叠划线合并
function mergeHls(arr) {
  const byP = new Map();
  for (const h of arr) {
    if (!byP.has(h.p)) byP.set(h.p, []);
    byP.get(h.p).push(h);
  }
  const out = [];
  for (const [p, list] of byP) {
    list.sort((a, b) => a.s - b.s);
    let cur = null;
    for (const h of list) {
      if (cur && h.s <= cur.e) cur.e = Math.max(cur.e, h.e);
      else { cur = { p, s: h.s, e: h.e }; out.push(cur); }
    }
  }
  return out.sort((a, b) => a.p - b.p || a.s - b.s);
}

// 把当前篇的划线记号铺进正文（段落原文是纯文本，直接按区间重建）
function applyHighlights() {
  if (!reader.path) return;
  const arr = getHls(reader.path);
  [...$('#readerBody').children].forEach((el, p) => {
    const hls = arr.filter((h) => h.p === p).sort((a, b) => a.s - b.s);
    const txt = el.textContent;
    if (!hls.length) {
      if (el.querySelector('mark.hl')) el.innerHTML = esc(txt);
      return;
    }
    let html = '';
    let pos = 0;
    for (const h of hls) {
      const s = Math.max(pos, Math.min(txt.length, h.s));
      const e = Math.max(s, Math.min(txt.length, h.e));
      html += esc(txt.slice(pos, s))
        + `<mark class="hl" data-hs="${h.s}">${esc(txt.slice(s, e))}</mark>`;
      pos = e;
    }
    el.innerHTML = html + esc(txt.slice(pos));
  });
}

function addHighlight() {
  const ranges = selParaRanges(window.getSelection());
  if (!ranges.length || !reader.path) return;
  const merged = mergeHls([...getHls(reader.path), ...ranges]);
  // 记 40 字摘句，「我的划线」列表显示用
  const kids = $('#readerBody').children;
  for (const h of merged) h.t = (kids[h.p]?.textContent || '').slice(h.s, Math.min(h.e, h.s + 40));
  saveHls(reader.path, merged);
  applyHighlights();
  window.getSelection()?.removeAllRanges();
  $('#quoteChip').hidden = true;
  toast('已划线 · 「我的」页可回看');
}

function renderHlSheet() {
  const groups = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (!k || !k.startsWith('fy.hl.')) continue;
    const path = k.slice(6);
    let arr;
    try { arr = JSON.parse(localStorage.getItem(k)) || []; } catch { continue; }
    if (!arr.length) continue;
    // 回查篇目信息：讲记 sid/nn.txt，问答 qa/n.txt
    const m = path.match(/^(\w+)\/(\d+)\.txt$/);
    let title = '', series = '';
    if (m && m[1] === 'qa') {
      title = qaData?.items[Number(m[2]) - 1]?.title || '';
      series = '学佛问答';
    } else if (m && library) {
      const s = library.series.find((x) => x.id === m[1]);
      const c = s?.chapters.find((x) => x.n === Number(m[2]));
      if (c) { title = c.title; series = s.title; }
    }
    if (title) groups.push({ path, title, series, arr });
  }
  $('#cntSheetBody').innerHTML = groups.length
    ? groups.map((g) =>
      `<p class="hl-group">《${esc(g.series)}》· ${esc(g.title)}</p>` +
      g.arr.map((h) =>
        `<button class="sheet-row hl-row" data-hl-open="${esc(g.path)}" data-hl-p="${h.p}">
          <span class="hl-quote">${esc(h.t || '（划线段落）')} …</span></button>`).join('')).join('')
    : '<p class="bk-note">还没有划线。阅读时选中经文，点「划 线」即可留下记号。</p>';
}

/* ================= 收藏本篇（书签） ================= */

function updateBookmark() {
  const b = $('#btnBookmark');
  b.hidden = !reader.sid;   // 问答页无书签
  if (reader.sid) b.classList.toggle('on', !!localStorage.getItem('fy.bk.' + reader.bkSpec));
}

function bkList() {
  const items = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (!k || !k.startsWith('fy.bk.')) continue;
    const spec = k.slice(6);
    const [sid, nStr] = spec.split('/');
    const s = library.series.find((x) => x.id === sid);
    const c = s?.chapters.find((x) => x.n === Number(nStr));
    if (c) items.push({ spec, s, c });
  }
  items.sort((a, b) => a.s.title === b.s.title ? a.c.n - b.c.n : a.s.title.localeCompare(b.s.title, 'zh'));
  return items;
}

/* ================= 文转音频朗读（逐段合成，边播边预取） ================= */

const tts = { on: false, idx: 0, audio: null, nextUrl: null, nextIdx: -1 };

function ttsParas() {
  // 可读段落：标题与正文（跳过篇眉与加载占位）
  return [...$('#readerBody').children]
    .filter((el) => el.matches('h2, p:not(.reader-sub):not(.reader-loading)'));
}

async function ttsFetch(text) {
  const r = await fetch('/api/tts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!r.ok) throw new Error(await r.text());
  return URL.createObjectURL(await r.blob());
}

function ttsMark(el) {
  document.querySelectorAll('.tts-cur').forEach((x) => x.classList.remove('tts-cur'));
  if (el) {
    el.classList.add('tts-cur');
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

async function ttsPlayIdx(i) {
  const list = ttsParas();
  if (!tts.on) return;
  if (i >= list.length) { ttsStop('本篇朗读圆满 🙏'); return; }
  tts.idx = i;
  ttsMark(list[i]);
  $('#ttsInfo').textContent = `朗读中 · ${i + 1} / ${list.length} 段`;
  try {
    const url = (tts.nextIdx === i && tts.nextUrl)
      ? tts.nextUrl
      : await ttsFetch(list[i].textContent.slice(0, 580));
    tts.nextUrl = null;
    tts.nextIdx = -1;
    if (!tts.on) { URL.revokeObjectURL(url); return; }
    tts.audio.src = url;
    await tts.audio.play();
    $('#ttsBar').classList.remove('paused');
    // 预取下一段，衔接无缝
    const next = list[i + 1];
    if (next) {
      ttsFetch(next.textContent.slice(0, 580))
        .then((u) => { if (tts.on) { tts.nextUrl = u; tts.nextIdx = i + 1; } else URL.revokeObjectURL(u); })
        .catch(() => { /* 播到时再取 */ });
    }
  } catch (e) {
    if (e && e.name === 'NotAllowedError' && tts.audio && tts.audio.src) {
      // 移动端自动播放被拦（异步取音频丢了手势）：转待命，点播放键即开始
      $('#ttsBar').classList.add('paused');
      $('#ttsInfo').textContent = '轻触 ▶ 开始朗读';
      return;
    }
    ttsStop(String((e && e.message) || '朗读服务暂不可用').slice(0, 40));
  }
}

function ttsStart() {
  if (!reader.path) return;
  if (!audio.paused) audio.pause();   // 只留一路声音
  if (!tts.audio) {
    tts.audio = new Audio();
    tts.audio.addEventListener('ended', () => {
      const old = tts.audio.src;
      ttsPlayIdx(tts.idx + 1);
      if (old.startsWith('blob:')) URL.revokeObjectURL(old);
    });
  }
  tts.on = true;
  $('#ttsBar').hidden = false;
  $('#ttsBar').classList.remove('paused');
  $('#btnTtsToggle').classList.add('on');
  // 从视口顶部的段落读起
  const list = ttsParas();
  const topLine = ($('.reader-bar').offsetHeight || 44) + 8;
  let start = 0;
  for (let i = 0; i < list.length; i++) {
    if (list[i].getBoundingClientRect().bottom > topLine) { start = i; break; }
  }
  $('#ttsInfo').textContent = '合成中 …';
  ttsPlayIdx(start);
}

function ttsStop(msg) {
  if (!tts.on && !msg) return;
  tts.on = false;
  if (tts.nextUrl) URL.revokeObjectURL(tts.nextUrl);
  tts.nextUrl = null;
  tts.nextIdx = -1;
  if (tts.audio) { tts.audio.pause(); tts.audio.removeAttribute('src'); }
  $('#ttsBar').hidden = true;
  $('#btnTtsToggle').classList.remove('on');
  ttsMark(null);
  if (msg) toast(msg);
}

function applyReaderPrefs() {
  // 阅读偏好三项：字号 / 行距 / 字体（黑体为可选，默认随全站宋体）
  const el = $('#readerBody');
  el.style.fontSize = (Number(localStorage.getItem('fy.fs')) || FONT_SIZES[1]) + 'px';
  const lhIdx = Number(localStorage.getItem('fy.lh') ?? 1);
  el.style.lineHeight = String(LINE_HEIGHTS[lhIdx] ?? LINE_HEIGHTS[1]);
  el.style.fontFamily = localStorage.getItem('fy.ff') === 'hei' ? READER_SANS : '';
}

// 阅读进度：新格式 {p:段落序号, pct:比例}；兼容旧格式（纯数字滚动比例）
function readProg(path) {
  const raw = localStorage.getItem('fy.rp.' + path);
  if (!raw) return null;
  if (raw[0] === '{') { try { return JSON.parse(raw); } catch { return null; } }
  const r = Number(raw);
  return Number.isFinite(r) && r > 0 ? { pct: r } : null;
}

function renderChaptersSheet() {
  // 本部篇目快切（点阅读器顶栏「n / 总数」弹出）
  const curN = reader.chapters[reader.idx]?.n;
  $('#cntSheetBody').innerHTML = '<ol class="ep-list chap-jump">' + reader.chapters.map((c) =>
    `<li data-jump="${reader.sid}/${c.n}"${c.n === curN ? ' class="playing"' : ''}>
      <span class="n">${c.n}</span>
      <span class="t">${esc(c.title)}</span>
      ${chapProgLabel(c)}</li>`).join('') + '</ol>';
}

function renderRdSetSheet() {
  // 阅读设置：字号 / 行距 / 字体，改动即时生效（弹层不遮正文，所见即所得）
  const fs = Number(localStorage.getItem('fy.fs')) || FONT_SIZES[1];
  const lh = Number(localStorage.getItem('fy.lh') ?? 1);
  const ff = localStorage.getItem('fy.ff') || 'song';
  const chip = (k, v, label, on) => `<button data-rs="${k}:${v}"${on ? ' class="on"' : ''}>${label}</button>`;
  $('#cntSheetBody').innerHTML = `
    <div class="rd-row"><span class="rd-lbl">字号</span><div class="rd-chips">${
      FONT_SIZES.map((v, i) => chip('fs', v, ['小', '中', '大', '特大'][i], v === fs)).join('')}</div></div>
    <div class="rd-row"><span class="rd-lbl">行距</span><div class="rd-chips">${
      ['紧凑', '适中', '疏朗'].map((l, i) => chip('lh', i, l, i === lh)).join('')}</div></div>
    <div class="rd-row"><span class="rd-lbl">字体</span><div class="rd-chips">${
      chip('ff', 'song', '宋体', ff === 'song')}${chip('ff', 'hei', '黑体', ff === 'hei')}</div></div>`;
}

async function renderStorageSheet() {
  // 存储与缓存：统计 Cache Storage 明细与占用估算，可一键清空重建
  $('#cntSheetBody').innerHTML = '<p class="bk-note">正在统计 …</p>';
  let used = 0;
  try { used = (await navigator.storage.estimate()).usage || 0; } catch { /* 部分浏览器不支持 */ }
  let ver = '—', shell = 0, texts = 0, data = 0;
  try {
    const keys = await caches.keys();
    ver = keys[0] || '—';
    for (const k of keys) {
      for (const req of await (await caches.open(k)).keys()) {
        const path = new URL(req.url).pathname;
        if (path.startsWith('/text/')) texts++;
        else if (path.endsWith('.json')) data++;
        else shell++;
      }
    }
  } catch { /* 忽略 */ }
  if (cntSheetMode !== 'storage') return;   // 统计期间弹层已切换/关闭
  $('#cntSheetBody').innerHTML = `
    <div class="st-rows">
      <p class="st-row"><span>应用本体（${esc(ver)}）</span><b>${shell} 项</b></p>
      <p class="st-row"><span>已缓存讲记</span><b>${texts} 篇</b></p>
      <p class="st-row"><span>目录与数据</span><b>${data} 项</b></p>
      <p class="st-row"><span>离线音频（已下载）</span><b>${Object.keys(offlineMeta()).length} 集 · ${(offlineTotal() / 1048576).toFixed(1)} MB</b></p>
      <p class="st-row"><span>估算占用</span><b>${(used / 1048576).toFixed(1)} MB</b></p>
    </div>
    ${Object.keys(offlineMeta()).length ? '<button class="st-clear" data-offline-clear>清 空 离 线 音 频</button>' : ''}
    <button class="st-clear" data-st-clear>清 理 缓 存</button>
    <p class="bk-note">「清空离线音频」只删已下载的音频；「清理缓存」清空页面与讲记缓存并刷新重建。<br>念佛计数、阅读进度、收藏均不受影响。</p>`;
}

// 轻提示：底部浮出一句，2.6 秒自动消隐

/* ================= 听经 · 佛号（播放，不带计数） ================= */

function buildFohao() {
  // 佛号 / 念诵（cat=课诵）：按系列列出曲目，点一条即进全屏播放器循环恭听
  const series = catalog.series.filter((s) => s.cat === '课诵');
  $('#fohaoGroups').innerHTML = series.map((s) =>
    `<div class="lib-cat"><h3>${esc(s.title)}</h3><ol class="ep-list" data-fohao="${s.id}">` +
    s.episodes.map((ep, i) =>
      `<li data-idx="${i}"><span class="n">${i + 1}</span><span class="t">${esc(ep.title)}</span><span class="d">${fmtDur(ep.dur)}</span></li>`).join('') +
    '</ol></div>').join('');
}

function playNianfo() {
  switchMode('nianfo');
  const t = nf.tracks[nf.idx];
  audio.src = audioUrl(t.bucket, t.key);
  audio.loop = true;
  audio.playbackRate = 1;
  seekPending = null;
  if (nf.timerMin > 0) nf.deadline = Date.now() + nf.timerMin * 60000;
  else { nf.deadline = null; $('#nfTimerLabel').textContent = ''; }
  updateMediaSession({ title: t.title, seriesTitle: '佛号' }, '');
  audio.play().catch(() => {});
}

function endNianfoSession() {
  audio.pause();
  nf.deadline = null;
  $('#nfTimerLabel').textContent = '本座定课圆满 · 南无阿弥陀佛';
}

/* ================= 我的 · 数珠计数 ================= */

function bjDateKey() {
  const p = bjParts(nowMs());
  return `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
}

const NJ_DEFAULT_ITEMS = [
  { id: 'amtf6', name: '南无阿弥陀佛' },
  { id: 'amtf4', name: '阿弥陀佛' },
];

function loadNj() {
  try { nj = JSON.parse(localStorage.getItem('fy.nj')) || null; }
  catch { nj = null; }
  if (!nj) {
    nj = { v: 2, cur: 'amtf6', goal: 108, items: [...NJ_DEFAULT_ITEMS], days: {}, totals: {} };
  } else if (!nj.v) {
    // v1 → v2 迁移：原单计数整体归入「南无阿弥陀佛」，一声不丢
    const days = {};
    for (const [k, n] of Object.entries(nj.days || {})) { if (n > 0) days[k] = { amtf6: n }; }
    nj = { v: 2, cur: 'amtf6', goal: 108, items: [...NJ_DEFAULT_ITEMS], days, totals: { amtf6: nj.total || 0 } };
    saveNj();
  }
  importOldStore();
}

// 旧站（foyue.org 一代，Pages）计数迁移：域名切到本站后同源可读旧数据，
// 把 foyue_store.counter 并入 fy.nj（累加合并，一声不丢），只执行一次。
// 旧结构：{ practice, customPractice, practices: {名称: {total, daily, dailyDate, goal}},
//          dailyLog: {'YYYY-MM-DD': {名称: 声数}} }，日期键格式与本站一致。
function importOldStore() {
  if (localStorage.getItem('fy.njOldImport')) return;
  let old = null;
  try { old = JSON.parse(localStorage.getItem('foyue_store'))?.counter; } catch { /* 忽略 */ }
  if (!old || !old.practices) return;

  const NAME_TO_ID = { '南无阿弥陀佛': 'amtf6', '阿弥陀佛': 'amtf4' };
  // '__custom__' 是旧站早期的自定义功课占位，实际名字在 customPractice
  const nameOf = (raw) => (raw === '__custom__' ? String(old.customPractice || '').trim() : raw);
  const idOf = (name) => {
    if (NAME_TO_ID[name]) return NAME_TO_ID[name];
    let it = nj.items.find((x) => x.name === name);
    if (!it) {
      it = { id: 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5), name: String(name).slice(0, 12) };
      nj.items.push(it);
    }
    return it.id;
  };

  for (const [raw, p] of Object.entries(old.practices)) {
    const name = nameOf(raw);
    const total = Number(p?.total) || 0;
    if (!name || total <= 0) continue;
    const id = idOf(name);
    nj.totals[id] = (nj.totals[id] || 0) + total;
  }
  for (const [date, byName] of Object.entries(old.dailyLog || {})) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue;
    for (const [raw, n0] of Object.entries(byName || {})) {
      const name = nameOf(raw);
      const n = Number(n0) || 0;
      if (!name || n <= 0) continue;
      const day = nj.days[date] || (nj.days[date] = {});
      const id = idOf(name);
      day[id] = (day[id] || 0) + n;
    }
  }
  const curName = nameOf(old.practice);
  if (curName && (NAME_TO_ID[curName] || nj.items.find((x) => x.name === curName))) nj.cur = idOf(curName);
  const g = Number(old.practices[old.practice]?.goal) || 0;
  if (g > 0 && g !== 108) nj.goal = g;   // 旧站默认 108 与本站一致，非默认才覆盖

  setLS('fy.njOldImport', '1');
  saveNj();
}

function saveNj() {
  // 明细仍只留近 90 天，但退场之前先并进月总数 ——
  // 原先是直接丢弃，于是三个月前的功课在日历上一片灰，
  // 一年到头究竟念了多少，再也说不清。
  // 月总数一个月才一条，攒十年也就一百二十条，留着不占什么。
  nj.months = nj.months || {};
  const keys = Object.keys(nj.days).sort();
  for (const k of keys.slice(0, Math.max(0, keys.length - 90))) {
    const ym = k.slice(0, 7);
    nj.months[ym] = (nj.months[ym] || 0) + njDayTotal(k);   // 须在 delete 之前取
    delete nj.days[k];
  }
  const json = JSON.stringify(nj);
  setLS('fy.nj', json, true);
  vaultMirror('fy.nj', json);   // 同步镜像一份，localStorage 若被清可捞回
}

function njItem() { return nj.items.find((x) => x.id === nj.cur) || nj.items[0]; }
function njDayTotal(k) { const d = nj.days[k]; return d ? Object.values(d).reduce((a, b) => a + b, 0) : 0; }
function njGrandTotal() { return Object.values(nj.totals).reduce((a, b) => a + b, 0); }

/* 撤销栈：记下每一笔加计，撤销按「最近一次操作」整笔退回。
   原先撤销固定 −1，误触一下「+10」要连点十次才退得干净。 */
const njUndo = [];

/* delta 正为计入、负为退回。声与震都在这里发，调用处只管自己的涟漪，
   免得「十念」「撤销」这些入口各自漏掉或重复一套反馈。
   opts.silent：不出声不震（迁移、重置、云端合并等非人为计数走这条）。 */
function addNj(delta, opts = {}) {
  const k = bjDateKey();
  const day = nj.days[k] || (nj.days[k] = {});
  const cur = njItem().id;
  const t = day[cur] || 0;
  const d = Math.max(delta, -t); // 撤销不越过零
  if (d === 0) return 0;
  const dayBefore = njDayTotal(k);
  day[cur] = t + d;
  nj.totals[cur] = Math.max(0, (nj.totals[cur] || 0) + d);
  saveNj();
  renderCount();
  if (d > 0) {
    njLastCount = Date.now();   // 供「此刻在念」的心跳判定
    if (!opts.noUndo) {
      njUndo.push({ k, id: cur, n: d });
      if (njUndo.length > 60) njUndo.shift();
    }
    const tenth = Math.floor((t + d) / 10) > Math.floor(t / 10);
    const full = Math.floor((t + d) / 108) > Math.floor(t / 108);
    if (!opts.silent) playMuyu();
    if (!full) vibrate(tenth ? 22 : 12);   // 满串的震动归 beadFull，不在这里抢
    // 十念记数的支点：印光大师十念记数法从一至十循环摄心，
    // 闭目行走时不看屏也得知道念到第几位 —— 每满十声补一记轻响，就是那个支点。
    // 满串时不补，免得与满串的双响挤成一团。
    if (tenth && !full && !opts.silent) {
      setTimeout(() => playMuyu(true), 90);
      // 静念全屏最适合闭目与视障莲友，可整屏的数字对读屏是关着的（aria-hidden）。
      // 每满十声报一次数，读屏用户才有十念记数的听觉支点；明眼人听不见，无扰。
      if (!$('#zenOverlay').hidden) announce(`${t + d} 声`);
    }
    if (full) beadFull();
    // 定课圆满：当日总声数首次达标（跨功课合计）
    if (nj.goal > 0 && dayBefore < nj.goal && dayBefore + d >= nj.goal) goalDone();
  }
  return d;
}

// 撤销一笔：退回最近一次加计（那一天那一门功课），不是死减一声
// quiet：不出提示（静念的双指收起会顺手撤掉手势自己带出的那一声）
function undoNj(opts = {}) {
  const last = njUndo.pop();
  if (!last) { if (!opts.quiet) toast('没有可撤销的计数'); return; }
  const day = nj.days[last.k];
  const have = (day && day[last.id]) || 0;
  const back = Math.min(last.n, have);   // 期间被重置过就只退还剩下的
  if (back <= 0) { if (!opts.quiet) toast('这笔计数已不在'); return; }
  day[last.id] = have - back;
  nj.totals[last.id] = Math.max(0, (nj.totals[last.id] || 0) - back);
  saveNj();
  renderCount();
  if (!opts.quiet) { vibrate(10); toast(`已撤销 ${back} 声`); }
}


function beadFull() {
  vibrate([24, 60, 36]);
  setTimeout(playMuyu, 150);   // 正声已由 addNj 发出，这里补第二响，合成「哒—哒」
  // 满串靠震动与木鱼提示，读屏用户两者都收不到，补一句播报
  announce(`满一串，一百零八声。今日共 ${njDayTotal(bjDateKey())} 声`);
  lianTip();   // 攒到分量了才劝开莲号，每满一串顺路看一眼
  for (const el of [$('#btnBead'), $('#zenNum')]) {
    el.classList.remove('full');
    void el.offsetWidth;   // 重启动画
    el.classList.add('full');
  }
}

/* ── 全站共念 ──
   只报总数与此刻在念的人数，不设个人排名：
   共修是彼此增上，不是比谁念得多。 */
let gxTimer = null;
let njLastCount = 0;

async function refreshGongxiu() {
  const el = $('#cntGx');
  if (!el) return;
  // 三分钟内计过数才报心跳，免得把「开着页面发呆」也算成在念
  const d = await syncGongxiu(Date.now() - njLastCount < 180000);
  if (!d || !d.total) { el.hidden = true; return; }
  el.hidden = false;
  el.textContent = `莲友共念 ${d.total.toLocaleString()} 声`
    + (d.live > 0 ? ` · 此刻 ${d.live} 位同在` : '');
}

function startGongxiu() {
  refreshGongxiu();
  clearInterval(gxTimer);
  gxTimer = setInterval(refreshGongxiu, 60000);
}
function stopGongxiu() { clearInterval(gxTimer); gxTimer = null; }

/* 攒到一定分量再劝人开莲号：一上来就弹，是打扰；
   念到几万声还只存在一台手机里，才是真要紧的事。只说一次。 */
function lianTip() {
  if (syncAccount() || localStorage.getItem('fy.lianTip')) return;
  if (njGrandTotal() < 10000 && njStreak() < 7) return;
  setLS('fy.lianTip', '1');
  toast('功课已积起来了 · 到「功课 → 莲号」开一枚，换手机也不丢');
}

let zenGoalTimer = null;

function goalDone() {
  vibrate([40, 80, 60, 80, 90]);
  announce('今日定课圆满');
  // 静念中不弹全屏层：它压在静念层之上（z40 对 z38），闭目念的下一声
  // 会先去关它，那一声就丢了，而人还当是计上了。改浮一条不拦点击的提示。
  if (!$('#zenOverlay').hidden) {
    const el = $('#zenGoal');
    el.hidden = false;
    clearTimeout(zenGoalTimer);
    zenGoalTimer = setTimeout(() => { el.hidden = true; }, 4500);
    return;
  }
  $('#gdOverlay').hidden = false;
}

function njStreak() {
  // 连续用功天数：今日未计则从昨日起算，不因"今天还没念"清零
  let n = 0;
  let i = njDayTotal(bjDateKey()) > 0 ? 0 : 1;
  for (; i < 400; i++) {
    const p = bjParts(nowMs() - i * 86400000);
    const k = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
    if (njDayTotal(k) > 0) n++; else break;
  }
  return n;
}

let njLastDay = null;

function renderCount() {
  // 念佛计数器（极简）：当前功课 + 大念珠今日声数 + 本串/定课 + 累计/连续摘要
  const it = njItem();
  const k = bjDateKey();
  // 跨零点归零是对的，但不打一声招呼就清空，晚课念到深夜的人会以为白念了。
  // 日界按北京时间（全站共修同一个「今日」），海外莲友尤其要这一句。
  if (njLastDay && njLastDay !== k) {
    const y = njDayTotal(njLastDay);
    if (y > 0) toast(`已入次日 · 昨日 ${y.toLocaleString()} 声已归档`);
  }
  njLastDay = k;
  const mine = (nj.days[k] || {})[it.id] || 0;
  const dayTotal = njDayTotal(k);
  $('#countName').textContent = it.name;
  $('#njToday').textContent = mine;
  const frac = (mine % 108) / 108;
  $('#njRing').style.strokeDasharray = String(RING_LEN);
  $('#njRing').style.strokeDashoffset = String(RING_LEN * (1 - frac));
  $('#countSub').textContent =
    `本串 ${mine % 108} / 108　·　${nj.goal ? `定课 ${dayTotal} / ${nj.goal}` : '未设定课'}`;
  // 静念全屏同步（层未开时更新也无妨，开启瞬间即是现值）
  $('#zenName').textContent = it.name;
  $('#zenNum').textContent = mine;
  $('#zenSub').textContent =
    `今日 · 声　·　本串 ${mine % 108} / 108${nj.goal ? `　·　定课 ${dayTotal} / ${nj.goal}` : ''}`;
}

/* ── 木鱼音效（Web Audio 合成，无需音频文件） ──
   原先是一条 triangle 波从 640 滑到 170：那是电子音，不是木头。
   木鱼是掏空的木腔，敲下去分两截 ——
     一截是槌头触木的「哒」：几毫秒的宽频噪声，木字全在这里；
     一截是木腔的余响：木非丝弦，泛音不按整数倍排，故三条分音各走各的频率与衰减。
   两截叠起来才像木鱼。全程合成，不取音频文件。 */
let _audioCtx = null;
let _muyuNoise = null;

function audioCtx() {
  try {
    _audioCtx = _audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (_audioCtx.state === 'suspended') _audioCtx.resume();   // 不 await：随后排程的音会等 resume 完自行出声
    return _audioCtx;
  } catch { return null; }   // 无音频环境
}

/* 进计数页即预热：iOS 首次出声必须发生在用户手势内，
   而「进入计数页」本身由点击导航触发，正落在手势里。
   不预热的话，第一声木鱼常常是哑的 —— 恰恰是用户初次尝试的那一声。 */
function primeAudio() {
  const ctx = audioCtx();
  if (!ctx || _muyuNoise) return;
  const n = Math.ceil(ctx.sampleRate * 0.05);
  _muyuNoise = ctx.createBuffer(1, n, ctx.sampleRate);
  const ch = _muyuNoise.getChannelData(0);
  for (let i = 0; i < n; i++) ch[i] = Math.random() * 2 - 1;
  // 播一记 0 增益的空音，把音频管线整条走通（iOS 解锁）
  const s = ctx.createBufferSource();
  const g = ctx.createGain();
  g.gain.value = 0;
  s.buffer = _muyuNoise;
  s.connect(g).connect(ctx.destination);
  s.start(); s.stop(ctx.currentTime + 0.01);
}

// soft=true 用于满十声的轻点（十念记数的支点），音量收一半，不抢正声
function playMuyu(soft = false) {
  if (localStorage.getItem('fy.muyu') === '0') return;   // 默认开：念佛计数没有木鱼声，等于哑的
  const ctx = audioCtx();
  if (!ctx) return;
  try {
    if (!_muyuNoise) primeAudio();
    const t0 = ctx.currentTime + 0.001;
    const out = ctx.createGain();
    out.gain.value = soft ? 0.45 : 1;
    out.connect(ctx.destination);

    // 一、槌头触木：极短噪声过带通，「哒」的那一下
    if (_muyuNoise) {
      const n = ctx.createBufferSource();
      const nf = ctx.createBiquadFilter();
      const ng = ctx.createGain();
      n.buffer = _muyuNoise;
      nf.type = 'bandpass'; nf.frequency.value = 2000; nf.Q.value = 1.1;
      ng.gain.setValueAtTime(0.42, t0);
      ng.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.03);
      n.connect(nf).connect(ng).connect(out);
      n.start(t0); n.stop(t0 + 0.05);
    }

    // 二、木腔余响：[频率, 相对音量, 衰减秒数]，频率刻意不成整数倍
    for (const [f, amp, dur] of [[548, 1, 0.21], [934, 0.4, 0.13], [1495, 0.18, 0.07]]) {
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = 'sine';
      o.frequency.setValueAtTime(f * 1.055, t0);                    // 起振略高，20ms 内落定：
      o.frequency.exponentialRampToValueAtTime(f, t0 + 0.02);       // 敲击时木头被压住又弹回的那点「啵」
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(0.3 * amp, t0 + 0.004);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      o.connect(g).connect(out);
      o.start(t0); o.stop(t0 + dur + 0.02);
    }
  } catch { /* 无音频环境 */ }
}

/* ── 屏幕常亮（Wake Lock） ── */
let _wakeLock = null;
async function requestWake() {
  try { if ('wakeLock' in navigator) _wakeLock = await navigator.wakeLock.request('screen'); }
  catch { /* 被拒或不支持 */ }
}
async function releaseWake() { try { await _wakeLock?.release(); } catch { /* 忽略 */ } _wakeLock = null; }

/* ── 点击涟漪（大念珠与静念全屏共用） ── */
function spawnBeadRipple(host, e) {
  const r = host.getBoundingClientRect();
  const x = (e.clientX || r.left + r.width / 2) - r.left;
  const y = (e.clientY || r.top + r.height / 2) - r.top;
  const s = document.createElement('span');
  s.className = 'bead-ripple';
  s.style.left = x + 'px'; s.style.top = y + 'px';
  host.appendChild(s);
  setTimeout(() => s.remove(), 620);
}

/* ── 计数器弹层（功课 / 定课 / 历史 共用一个 sheet） ── */
const GOAL_PRESETS = [0, 108, 216, 540, 1080, 3000];
let cntSheetMode = null;
let calYM = null;

function openCntSheet(mode, title) {
  cntSheetMode = mode;
  $('#cntSheetTitle').textContent = title;
  $('#cntSheet').hidden = false;
}
function closeCntSheet() { $('#cntSheet').hidden = true; cntSheetMode = null; }

function renderPracticeSheet() {
  // 功课列表：每项显示各自的今日/累计声数（每个功课单独计数）
  const k = bjDateKey();
  $('#cntSheetBody').innerHTML = nj.items.map((x) => {
    const today = (nj.days[k] || {})[x.id] || 0;
    return `<button class="sheet-row${x.id === nj.cur ? ' on' : ''}" data-item="${x.id}">
      <span class="pr-main"><span>${esc(x.name)}</span>
        <small class="pr-stat">今日 ${today.toLocaleString()} · 累计 ${(nj.totals[x.id] || 0).toLocaleString()}</small></span>
      ${x.id.startsWith('c') ? '<span class="sheet-del" data-del="' + x.id + '">删除</span>'
        : (x.id === nj.cur ? '<span class="sheet-tick">✓</span>' : '')}</button>`;
  }).join('') + '<button class="sheet-add" data-add>＋ 添加功课</button>';
}

function renderHubSheet() {
  // 功课中心：主屏只留计数，管理/定课/历史/回向与器物开关都收在这里
  const tg = (key, def, label) => {
    const v = localStorage.getItem(key);
    const on = v === null ? def : v === '1';
    return `<button data-hubtg="${key}"${on ? ' class="on"' : ''}>${label}</button>`;
  };
  $('#cntSheetBody').innerHTML = `
    <button class="sheet-row" data-hub="practice"><span class="pr-main"><span>功课管理</span>
      <small class="pr-stat">当前：${esc(njItem().name)} · 各功课单独计数</small></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-hub="goal"><span class="pr-main"><span>每日定课</span>
      <small class="pr-stat">${nj.goal ? nj.goal.toLocaleString() + ' 声' : '未设定课'}</small></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-hub="history"><span class="pr-main"><span>念佛历史</span>
      <small class="pr-stat">累计 ${njGrandTotal().toLocaleString()} 声 · 连续 ${njStreak()} 日</small></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-hub="lian"><span class="pr-main"><span>莲号 · 功课同步</span>
      <small class="pr-stat">${lianStatLine()}</small></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-hub="huixiang"><span class="pr-main"><span>回向偈</span></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-hub="reset"><span class="pr-main"><span>重置今日</span>
      <small class="pr-stat">当前功课今日归零 · 累计同步扣除</small></span><span class="hub-go">›</span></button>
    <div class="hub-toggles">
      ${tg('fy.muyu', true, '木鱼音效')}
      ${tg('fy.wake', true, '屏幕常亮')}
      ${tg('fy.vib', true, '计数震动')}
    </div>`;
}
/* ── 莲号：跨设备认回功课 ──
   计数原先只在本机 localStorage，清一次缓存、换一台手机就没了。
   认人只要一枚莲号加一道六位护念码 —— 不收邮箱手机，抄纸上即可。
   莲友多是上了年纪的人，越少的字越好。 */

const fmtLian = (s) => String(s || '').replace(/(.{4})(.{4})/, '$1-$2');

function lianStatLine() {
  const a = syncAccount();
  if (!a) return '未开号 · 换手机或清缓存将丢失记录';
  const err = syncLastError();
  return `已接通 ${fmtLian(a.lian)}${err ? ' · ' + err : ''}`;
}

function renderLianSheet() {
  const a = syncAccount();
  if (!a) {
    $('#cntSheetBody').innerHTML = `
      <p class="lian-lead">功课眼下只存在这一台手机里。清一次浏览器缓存、换一台手机，
        几万几十万声就找不回来了。开一枚莲号，功课便同时存在云端。</p>
      <p class="lian-lead lian-quiet">不收邮箱，不收手机号。只有一枚莲号与一道六位护念码，
        抄在纸上或拍张照就行。</p>
      <button class="lian-main" data-lian="open">开 一 枚 莲 号</button>
      <button class="sheet-add" data-lian="claim">我已有莲号 · 认回功课</button>
      <div class="lian-form" id="lianForm" hidden>
        <input id="lianIn" placeholder="莲号 八位" autocomplete="off" maxlength="9" spellcheck="false">
        <input id="passIn" placeholder="护念码 六位数字" autocomplete="off" inputmode="numeric" maxlength="6">
        <button data-lian="do-claim">认 回</button>
        <p class="lian-msg" id="lianMsg"></p>
      </div>`;
    return;
  }
  $('#cntSheetBody').innerHTML = `
    <div class="lian-on">
      <p class="lc-row"><span>莲号</span><b>${fmtLian(a.lian)}</b></p>
      <p class="lian-lead lian-quiet">功课已在云端。换手机时，用这枚莲号与护念码即可认回
        念佛计数、阅读进度、收藏划线与听经足迹。</p>
      <p class="lian-msg" id="lianMsg">${esc(syncLastError() || '')}</p>
    </div>
    <button class="sheet-row" data-lian="sync"><span class="pr-main"><span>立即同步一次</span></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-lian="repass"><span class="pr-main"><span>换一道护念码</span>
      <small class="pr-stat">须先报出现用的那道</small></span><span class="hub-go">›</span></button>
    <button class="sheet-row" data-lian="unlink"><span class="pr-main"><span>在本机解除</span>
      <small class="pr-stat">云端功课与本机数据都不删，随时可再认回</small></span><span class="hub-go">›</span></button>`;
}

/** 开号/换码后展示凭据。护念码只此一次露面，站方不留明文。 */
function showLianCard(lian, pass, title) {
  $('#cntSheetTitle').textContent = title;
  $('#cntSheetBody').innerHTML = `
    <div class="lian-card">
      <p class="lc-note">请把这两行抄下来，或截一张图存好</p>
      <p class="lc-row"><span>莲 号</span><b>${fmtLian(lian)}</b></p>
      <p class="lc-row"><span>护念码</span><b>${esc(pass)}</b></p>
      <p class="lc-warn">护念码只此一次显示。站方只存散列、不留明文，丢了便找不回来。</p>
    </div>
    <button class="lian-main" data-lian="copy" data-l="${esc(lian)}" data-p="${esc(pass)}">复 制 这 两 行</button>
    <button class="sheet-add" data-lian="done">已经存好了</button>`;
}

function renderGoalSheet() {
  $('#cntSheetBody').innerHTML = '<div class="goal-grid">' + GOAL_PRESETS.map((g) =>
    `<button class="goal-cell${(nj.goal || 0) === g ? ' on' : ''}" data-goal="${g}">${g === 0 ? '不设' : g.toLocaleString()}</button>`).join('')
    + '</div><button class="sheet-add" data-custom>＋ 自定数量</button>';
}
function renderCalendar() {
  const { y, m } = calYM;
  const startDow = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const todayKey = bjDateKey();
  // 每日明细只保留近 90 天：更早日期显示为「无记录」而非 0，以免误读为没念
  const p90 = bjParts(nowMs() - 89 * 86400000);
  const cutoffKey = `${p90.y}-${String(p90.mo).padStart(2, '0')}-${String(p90.d).padStart(2, '0')}`;
  let monthTotal = 0, cells = '', hasGone = false;
  for (let i = 0; i < startDow; i++) cells += '<span class="cal-cell empty"></span>';
  for (let d = 1; d <= daysInMonth; d++) {
    const key = `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    if (key < cutoffKey) {
      hasGone = true;
      cells += `<span class="cal-cell gone"><i>${d}</i></span>`;
      continue;
    }
    const v = njDayTotal(key);
    monthTotal += v;
    const lvl = v === 0 ? 0 : v < 108 ? 1 : v < 540 ? 2 : 3;
    const vs = v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v;
    cells += `<span class="cal-cell lvl${lvl}${key === todayKey ? ' today' : ''}"><i>${d}</i>${v ? `<b>${vs}</b>` : ''}</span>`;
  }
  // 明细退场时已并进月总数，故本月合计＝还留着的日明细 + 已归档的那部分
  const ym = `${y}-${String(m).padStart(2, '0')}`;
  monthTotal += (nj.months || {})[ym] || 0;
  const dows = ['日', '一', '二', '三', '四', '五', '六'].map((d) => `<span class="cal-dow">${d}</span>`).join('');
  $('#cntSheetBody').innerHTML =
    `<div class="cal-nav"><button data-cal="-1" aria-label="上月">‹</button><strong>${y} 年 ${m} 月</strong><button data-cal="1" aria-label="下月">›</button></div>
    <div class="cal-grid">${dows}${cells}</div>
    <p class="cal-total">本月共 ${monthTotal.toLocaleString()} 声</p>` +
    `<p class="cal-note">日界以北京时间零点为准${hasGone ? '<br>灰色日期的逐日明细只留 90 天 · 当月与累计总数都照旧算数' : ''}</p>`
    + njYearsHtml();
}

/* 历年月账：逐日明细满 90 天即归档为月总数，永久留着。
   一年到头念了多少、哪几个月精进、哪几个月松了，都在这一栏里。 */
function njYearsHtml() {
  const ms = nj.months || {};
  const keys = Object.keys(ms).filter((k) => ms[k] > 0).sort().reverse();
  if (!keys.length) return '';
  const byYear = {};
  for (const k of keys) (byYear[k.slice(0, 4)] ||= []).push(k);
  const years = Object.keys(byYear).sort().reverse().map((yy) => {
    const total = byYear[yy].reduce((a, k) => a + ms[k], 0);
    const cells = byYear[yy].sort().map((k) =>
      `<span class="yr-m"><i>${Number(k.slice(5, 7))}月</i><b>${ms[k].toLocaleString()}</b></span>`).join('');
    return `<div class="yr-row"><p class="yr-head">${yy} 年 · 共 ${total.toLocaleString()} 声</p>
      <div class="yr-grid">${cells}</div></div>`;
  }).join('');
  return `<div class="yr-wrap"><p class="yr-title">历年月账</p>${years}</div>`;
}

/* ── 备份与迁移：本机全部数据（fy.*）导出/导入 ── */

function backupText() {
  const data = {};
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith('fy.')) data[k] = localStorage.getItem(k);
  }
  const json = JSON.stringify({ v: 1, t: Date.now(), data });
  const bytes = new TextEncoder().encode(json);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return 'FY1.' + btoa(bin);
}

function restoreBackup(code) {
  const bin = atob(code.slice(4).trim());
  const json = new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  const obj = JSON.parse(json);
  if (!obj || obj.v !== 1 || !obj.data) throw new Error('bad');
  const keys = Object.keys(obj.data).filter((k) => k.startsWith('fy.'));
  if (!keys.length) throw new Error('empty');
  // 逐项记成败：换机恢复时存储写不进（配额满、隐私模式）不能报「已恢复」，
  // 否则用户以为记录回来了，实则一项没落地
  let ok = 0;
  for (const k of keys) { if (setLS(k, obj.data[k])) ok++; }
  return { ok, total: keys.length };
}


function renderBackupSheet() {
  $('#cntSheetBody').innerHTML = `
    <p class="bk-note">念佛计数、收听与阅读进度、收藏与偏好都只保存在本机。换手机、换浏览器或清理数据前，请先导出备份。</p>
    <button class="sheet-add" data-bk="copy">导出 · 复制备份码</button>
    <button class="sheet-add" data-bk="file">导出 · 下载备份文件</button>
    <button class="sheet-add" data-bk="import">导入 · 粘贴备份码恢复</button>
    <p class="bk-note bk-msg" id="bkMsg"></p>`;
}

function renderWode() {
  // 我的页：修行概览（定课进度环 + 今日/累计/连续）+ 足迹 + 收藏
  const k = bjDateKey();
  const t = njDayTotal(k);
  $('#wcName').textContent = njItem().name;
  // 「声」由下行统计承载，进度行收短避免窄屏折出孤字
  $('#wcProgress').textContent = nj.goal ? `今日 ${t} / 定课 ${nj.goal}` : `今日 ${t} 声`;
  $('#whToday').textContent = t >= 10000 ? (t / 1000).toFixed(1) + 'k' : t;
  const rtMin = Math.floor((Number(localStorage.getItem('fy.rt.' + k)) || 0) / 60);
  // 段内空格用不断行空格：窄屏折行只许断在「·」上，
  // 否则会折出「连续 1 ／ 日」这样数与量词分家的孤字
  const NB = '\u00A0';   // 不断行空格（写成转义，免得被编辑器/格式化吃成普通空格）
  const stats = [
    `累计${NB}${njGrandTotal().toLocaleString()}${NB}声`,
    `连续${NB}${njStreak()}${NB}日`,
  ];
  if (rtMin > 0) stats.push(`今日恭读${NB}${rtMin}${NB}分`);
  // 分隔点用不断行空格粘在上一段尾部，折行时「·」留在行末而非行首（避头尾）
  $('#whStats').textContent = stats.join(`${NB}· `);
  // 进度环：设了定课按定课走，未设按本串（108）走
  const frac = nj.goal ? Math.min(1, t / nj.goal) : (t % 108) / 108;
  const len = 2 * Math.PI * 32;
  $('#whRing').style.strokeDasharray = String(len);
  $('#whRing').style.strokeDashoffset = String(len * (1 - frac));

  // 我的内容：只铺有内容的行（足迹 / 收藏 / 离线下载 / 划线），点击开弹层
  const hasTrail = !!(listenCardHtml('') || readCardHtml(''));
  const favN = favCount();
  const dlN = Object.keys(offlineMeta()).length;
  const dling = [...offlineDownloading].filter((x) => !offlineMeta()[x]).length;
  const hn = hlCount();
  const row = (act, icon, label, meta) => `<button class="wode-row" data-wact="${act}">
    <span class="wr-ic">${icon}</span><span class="wr-t">${label}</span>
    <span class="wr-n">${meta || ''}</span><span class="wr-go">›</span></button>`;
  let rows = '';
  if (hasTrail) rows += row('trail', WODE_IC.trail, '足迹', '');
  if (favN) rows += row('favs', WODE_IC.star, '收藏', String(favN));
  if (dlN || dling) rows += row('downloads', WODE_IC.download, '离线下载', dlN ? `${dlN} 集` : '下载中');
  if (hn) rows += row('myhl', WODE_IC.hl, '我的划线', `${hn} 处`);
  $('#wodeContentGroup').innerHTML = rows;
  $('#wodeContentGroup').hidden = !rows;
  $('#wodeContentSub').hidden = !rows;
}

// 我的内容行图标（描边线形）
const WODE_IC = {
  trail: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12a8 8 0 1 0 2.5-5.8L4 8.5"/><path d="M4 4v4.5h4.5"/><path d="M12 8v4.4l3 1.8"/></svg>',
  star: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.6l2.5 5.15 5.7.83-4.1 4 .97 5.66L12 17.9l-5.04 2.7.97-5.66-4.1-4 5.7-.83z"/></svg>',
  download: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v10"/><path d="M8 11l4 4 4-4"/><path d="M5 19h14"/></svg>',
  hl: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5.5 19.5h13"/><path d="M8.5 13.2 15.2 6.5a1.9 1.9 0 0 1 2.7 2.7l-6.7 6.7-3.6.9z"/></svg>',
};

// 我的内容弹层：足迹 / 收藏 / 离线下载（划线复用 renderHlSheet）
function renderTrailSheet() {
  $('#cntSheetBody').innerHTML = (listenCardHtml('最近在听') + readCardHtml('最近在读')) || '<p class="bk-note">还没有足迹。</p>';
}
function renderFavsSheet() {
  $('#cntSheetBody').innerHTML = favsHtml()
    || '<p class="bk-note">还没有收藏。听经时点「收藏」、阅读时点书签，即可收入此处。</p>';
}
function renderDownloadsSheet() { $('#cntSheetBody').innerHTML = downloadsHtml(); }

// 续听（首页 / 足迹弹层共用）
function resumeListen() {
  try {
    const last = JSON.parse(localStorage.getItem('fy.last'));
    const s = catalog.series.find((x) => x.id === last.sid);
    if (s && s.episodes[last.idx]) playEpisode(s, last.idx);
  } catch { /* 忽略 */ }
}

// 划线总条数（跨所有篇目，我的页入口显示）
function hlCount() {
  let n = 0;
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith('fy.hl.')) continue;
    try { n += (JSON.parse(localStorage.getItem(key)) || []).length; } catch { /* 忽略 */ }
  }
  return n;
}

/* ================= 分享（法布施） ================= */

// 海报文字要跟着当前语种走，而 canvas 不经 DOM 转换器，故把转换函数随 payload 一并交给
// 海报模块。转换逻辑留在这边：海报模块若反向 import 简繁那套，依赖链会绕回 app.js 成环。
function posterPayload(p) {
  return (zhMap && zhTradOn()) ? { ...p, T: (s) => zhConv(s, zhMap) } : p;
}

let sharePayload = null;

function playerShare() {
  // 分享当前点播集：深链 #series/<id>/<第n集>，对方打开定位到该集
  if (!(mode === 'od' && od)) return null;
  const ep = od.list[od.idx];
  return {
    title: ep.title,
    sub: `《${od.title}》· 佛乐净土法音`,
    source: `《${od.title}》`,
    text: `与您分享《${od.title}》${ep.title}`,
    quote: (SERIES_INTROS[od.seriesId] || '').slice(0, 76),
    url: `${location.origin}/series/${encodeURIComponent(od.seriesId)}/${od.idx + 1}`,
    cta: '扫二维码 听经闻法',   // 二维码下方一行，极简不落品牌
  };
}

function readerShare() {
  if (!reader.path || !reader.title) return null;
  const firstP = document.querySelector('#readerBody h2 ~ p')?.textContent || '';
  return {
    title: reader.title,
    sub: `《${reader.series}》· 佛乐净土法音`,
    source: `《${reader.series}》`,
    text: `与您分享《${reader.series}》${reader.title}`,
    quote: firstP.slice(0, 76),
    url: location.origin + (reader.sharePath || '/' + reader.shareHash),
    cta: '扫二维码 恭读原文',
  };
}

function liveShare() {
  // 分享直播：深链 #live，对方打开即入二十四时排播，与大众同闻
  const ep = liveItem ? liveItem.ep : null;
  // 系列名可能自带书名号，去掉后统一补一层，避免《《…》》
  const series = ep ? ep.seriesTitle.replace(/^《|》$/g, '') : '';
  const nowLine = ep ? `《${series}》${ep.title}` : '';
  return {
    kind: 'live',   // 海报走直播专版（当下播放内容 + 进度 + 二维码）
    title: '佛乐 · 净土法音直播',
    sub: ep ? `此刻恭听${nowLine}` : '二十四时 · 佛号讲经不断',
    source: '佛乐直播',
    text: ep ? `正与大众同闻${nowLine}，一起来听` : '佛乐净土法音 · 二十四时直播，随时同闻',
    quote: '二十四时 · 佛号讲经不断，随时可入，与大众同闻。',
    url: `${location.origin}/#live`,
    cta: '扫二维码 听经闻法',
    live: ep ? {
      series, ep: ep.title,
      block: liveItem.block ? liveItem.block.name : '',
      elapsed: Math.max(0, Math.min(ep.dur, stationNow() - liveItem.start)),
      dur: ep.dur,
      online: liveOnlineN,   // 真实在线数，0 不上海报
    } : null,
  };
}

function chatShare() {
  // 分享莲友共修群：深链 #qun，对方打开即进群同修
  const n = liveOnlineN;
  return {
    title: '莲友共修群',
    sub: n > 0 ? `${n} 位莲友正在群中 · 以法相会` : '与全球莲友以法相会 · 同称佛号',
    source: '莲友共修群',
    text: '一起来「莲友共修群」，与全球莲友以法相会、同称佛号',
    url: `${location.origin}/#qun`,
    cta: '扫二维码 进群共修',
  };
}

function openShare(p) {
  if (!p) return;
  sharePayload = p;
  $('#sharePrev').innerHTML = `<strong>${esc(p.title)}</strong><em>${esc(p.sub)}</em>`;
  $('#shareSys').hidden = !navigator.share;
  $('#shareMsg').textContent = '';
  $('#shareSheet').hidden = false;
}

/* ================= 莲友共修群（聊天室）与直播留言 ================= */

let cmtLastId = 0;
let cmtLastTs = 0;      // 上一条留言时间：超过 10 分钟插一枚时间戳（微信式）
let cmtTimer = 0;
let cmtFast = null;     // 当前轮询节奏（快=聊天室开着），避免重复重置计时器
let cmtBusy = false;
let chatOpen = false;   // 共修群全屏层是否打开（开着时轮询加密到 6 秒）
let chatBackHash = '#home';   // 聊天室关闭后返回的底层页
let chatUnseen = 0;    // 翻看历史期间到达的新消息数（浮标用）
let liveOnlineN = 0;    // 最近一次真实在线人数（直播海报用）

// 本机匿名设备标识（封禁用）与自动法名（莲友·两字清净名）
function devId() {
  let d = localStorage.getItem('fy.dev');
  if (!d) {
    d = crypto.randomUUID ? crypto.randomUUID()
      : 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    setLS('fy.dev', d);
  }
  return d;
}
function dharmaName() {
  let n = localStorage.getItem('fy.fname');
  if (!n) {
    const A = ['静', '慧', '明', '安', '和', '清', '悟', '善', '慈', '定', '莲', '净', '朗', '素', '澄', '恒'];
    n = '莲友·' + A[Math.floor(Math.random() * A.length)] + A[Math.floor(Math.random() * A.length)];
    setLS('fy.fname', n);
  }
  return n;
}

async function pollCmt() {
  // 共修群开着或身处直播页时才轮询（拉留言 + 在线心跳 + 喂弹幕）
  if (!chatOpen && document.body.dataset.view !== 'live') return;
  try {
    const qs = new URLSearchParams({ dev: devId() });   // 附带设备标识：既拉留言，又上报在线心跳、标记自己的发言
    if (cmtLastId) qs.set('after', cmtLastId);
    const r = await fetch('/api/cmt?' + qs.toString());
    if (!r.ok) return;
    const d = await r.json();
    setLiveOnline(d.online);
    const notice = (d.notice || '').trim();
    $('#liveNotice').textContent = notice;
    $('#liveNotice').hidden = !notice;
    $('#crNotice').textContent = notice;
    $('#crNotice').hidden = !notice;
    if (d.items && d.items.length) {
      const list = $('#cmtList');
      const first = !cmtLastId;
      if (first) initChatList();
      cmtLastId = d.items[d.items.length - 1].id;
      const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 100;
      appendCmts(d.items);
      if (first || nearBottom) { scrollChatBottom(); hideChatJump(); }
      else { chatUnseen += d.items.length; showChatJump(); }   // 翻看历史时新消息进浮标
    }
  } catch { /* 网络波动静默，下轮再试 */ }
}

// 追加一批留言：跨 10 分钟插时间戳；超上限裁剪最早；返回追加的 DOM 数
function appendCmts(items) {
  const list = $('#cmtList');
  const empty = list.querySelector('.cmt-empty');
  if (empty) empty.remove();
  let html = '';
  for (const c of items) {
    if (c.ts - cmtLastTs > 600000) html += cmtTimeHtml(c.ts);
    cmtLastTs = c.ts;
    html += cmtRowHtml(c);
  }
  list.insertAdjacentHTML('beforeend', html);
  while (list.children.length > 200) {
    const f = list.firstChild;
    if (f.classList && f.classList.contains('lc-sys')) break;   // 保留进群语
    list.removeChild(f);
  }
}

// 首次铺列表：清空并置顶进群语
function initChatList() {
  $('#cmtList').innerHTML = '<p class="lc-sys">莲友共修群 · 以法相会，敬请爱语，同称佛号</p>';
  cmtLastTs = 0;
}

function scrollChatBottom() { const l = $('#cmtList'); l.scrollTop = l.scrollHeight; }
function showChatJump() {
  const b = $('#crJump'); if (!b) return;
  $('#crJumpN').textContent = chatUnseen > 0 ? `${chatUnseen > 99 ? '99+' : chatUnseen} 条新消息` : '新消息';
  b.hidden = false;
}
function hideChatJump() { chatUnseen = 0; const b = $('#crJump'); if (b) b.hidden = true; }

// 时间戳分隔（北京时间）：今日只显时分，往日带月日
function cmtTimeHtml(ts) {
  const p = bjParts(ts);
  const hm = `${String(p.h).padStart(2, '0')}:${String(p.mi).padStart(2, '0')}`;
  const key = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
  return `<p class="lc-time">${key === bjDateKey() ? hm : `${p.mo}月${p.d}日 ${hm}`}</p>`;
}

// 头像取色：按法名哈希取一色，同一人恒定（微信式一人一色）
// 留言头像的八色印。与全站同批降饱和（原值是改造前的高彩度矿色，
// 排在低饱和的纸墨里格外跳），并压低明度保证白字对比都在 4.5:1 以上。
const AV_COLORS = ['#8c5649', '#936343', '#63755c', '#526671', '#7a6644', '#725a52', '#636141', '#89574d'];
function avColor(seed) {
  const s = String(seed || '莲'); let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return AV_COLORS[h % AV_COLORS.length];
}
// 一行留言气泡：法名取「·」后首字作莲印（按名取色）；自己的发言靠右朱砂气泡（服务端按设备标识判定）
function cmtRowHtml(c) {
  const dn = c.name.includes('·') ? c.name.split('·').pop() : c.name;
  const av = esc([...String(dn)][0] || '莲');
  return `<div class="lc-row${c.mine ? ' mine' : ''}">`
    + `<span class="lc-av" aria-hidden="true" style="background:${avColor(c.name)}">${av}</span>`
    + `<span class="lc-msg"><b>${esc(c.name)}</b><span>${esc(c.text)}</span></span></div>`;
}
// 同时在线人数：真实心跳统计，0 人时不显示（直播莲台 + 共修群头部同步）
function setLiveOnline(n) {
  n = Number(n) || 0;
  liveOnlineN = n;
  const box = $('#liveOnline');
  if (box) {
    if (n > 0) { $('#liveOnlineN').textContent = n; box.hidden = false; }
    else box.hidden = true;
  }
  $('#crSub').innerHTML = n > 0 ? `<b>${n}</b> 位莲友在此 · 敬请爱语` : '以法相会 · 敬请爱语';
  const hq = $('#hqSub');
  if (hq) hq.innerHTML = n > 0 ? `<b>${n}</b> 位莲友正在群中 · 以法相会` : '与全球莲友以法相会 · 同称佛号';
}

// 留言轮询节奏：共修群开着 6 秒近实时；只在直播页 30 秒（喂弹幕/在线数）；都不在则停
function syncCmtPolling() {
  // 在线心跳搭在这趟轮询上。原先只认「在直播页或聊天室开着」，
  // 于是在首页「正在播出」卡里听的人一概不计，报出来的人数偏少。
  const want = chatOpen || document.body.dataset.view === 'live'
    || (mode === 'live' && !audio.paused);
  if (!want) {
    if (cmtTimer) { clearInterval(cmtTimer); cmtTimer = 0; }
    cmtFast = null; setLiveOnline(0);
    return;
  }
  const fast = chatOpen;
  if (!cmtTimer) { $('#cmtWho').textContent = dharmaName(); pollCmt(); }
  if (fast !== cmtFast) {
    if (cmtTimer) clearInterval(cmtTimer);
    cmtTimer = setInterval(pollCmt, fast ? 6000 : 30000);
    cmtFast = fast;
  }
}

/* ── 莲友共修群全屏层（#qun 路由进入） ── */
function openChatRoom() {
  if (!chatOpen) {
    chatOpen = true;
    $('#chatRoom').hidden = false;
    document.title = '莲友共修群 · 佛乐';
    const list = $('#cmtList');
    if (!list.querySelector('.lc-row')) {   // 首开且无历史：先垫进群语 + 虚位以待
      list.innerHTML = '<p class="lc-sys">莲友共修群 · 以法相会，敬请爱语，同称佛号</p>'
        + '<p class="cmt-empty">虚位以待 · 说一句随喜，与全球莲友同修共勉</p>';
    }
  }
  $('#cmtWho').textContent = dharmaName();
  updateCmtCount();
  syncCmtPolling();
  setTimeout(scrollChatBottom, 20);   // preview 无 rAF，用 setTimeout 触底
}

async function sendCmt() {
  const input = $('#cmtText');
  const text = input.value.replace(/\s+/g, ' ').trim();
  if (!text || cmtBusy) return;
  cmtBusy = true;
  $('#btnCmtSend').disabled = true;
  $('#cmtNote').textContent = '';
  try {
    const r = await fetch('/api/cmt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        dev: devId(), name: dharmaName(), text,
        ep: (document.body.dataset.view === 'live' && liveItem)
          ? `${liveItem.ep.seriesTitle}·${liveItem.ep.title}` : '共修群',
      }),
    });
    if (r.ok) {
      input.value = '';
      updateCmtCount();
      // 自己的发言即刻上屏（用返回 id，next poll 用 after=id 不会重复）
      const d = await r.json().catch(() => ({}));
      if (d && d.id) {
        if (!cmtLastId) initChatList();
        cmtLastId = d.id;
        appendCmts([{ id: d.id, name: dharmaName(), text, ts: Date.now(), mine: 1 }]);
        scrollChatBottom(); hideChatJump();
      } else { await pollCmt(); }
    } else $('#cmtNote').textContent = (await r.text()) || '发送失败，请稍后再试';
  } catch { $('#cmtNote').textContent = '网络不畅，请稍后再试'; }
  cmtBusy = false;
  $('#btnCmtSend').disabled = false;
}

// 字数计数：接近上限时提示剩余
function updateCmtCount() {
  const el = $('#cmtCount'); if (!el) return;
  const n = ($('#cmtText').value || '').length;
  if (n > 120) { el.textContent = `${n} / 150`; el.classList.toggle('warn', n >= 150); }
  else { el.textContent = ''; el.classList.remove('warn'); }
}

/* ================= 按集：随喜 + 闻法留言 ================= */

// 集标识：稳定、简短（seriesId#idx），供随喜计数与留言归类
function epTag() { return (mode === 'od' && od) ? `${od.seriesId}#${od.idx}` : ''; }

// —— 随喜（点赞）——
const likeCache = {};
function setLikeUI(d) {
  const b = $('#btnLike'); if (!b) return;
  d = d || { count: 0, liked: false };
  b.classList.toggle('on', !!d.liked);
  const badge = $('#likeCount');
  if (badge) {
    const show = d.count > 0;
    badge.hidden = !show;
    badge.textContent = show ? (d.count > 999 ? '999+' : String(d.count)) : '';
  }
}
async function refreshLike() {
  if (!(mode === 'od' && od)) return;
  const ep = epTag();
  setLikeUI(likeCache[ep]);   // 先用缓存，避免闪烁
  try {
    const r = await fetch(`/api/like?ep=${encodeURIComponent(ep)}&dev=${encodeURIComponent(devId())}`);
    if (!r.ok) return;
    const d = await r.json();
    likeCache[ep] = d;
    if (epTag() === ep) setLikeUI(d);
  } catch { /* 网络波动静默 */ }
}
async function toggleLike() {
  if (!(mode === 'od' && od)) { toast('请先选择要随喜的音频'); return; }
  const ep = epTag();
  const cur = likeCache[ep] || { count: 0, liked: false };
  const optimistic = { count: Math.max(0, cur.count + (cur.liked ? -1 : 1)), liked: !cur.liked };
  likeCache[ep] = optimistic; setLikeUI(optimistic);   // 乐观更新
  try {
    const r = await fetch('/api/like', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ep, dev: devId() }),
    });
    if (!r.ok) throw new Error();
    const d = await r.json();
    likeCache[ep] = d;
    if (epTag() === ep) setLikeUI(d);
    if (d.liked) toast('随喜功德 · 南无阿弥陀佛');
  } catch {
    likeCache[ep] = cur; setLikeUI(cur);   // 回滚
    toast('网络不畅，请稍后再试');
  }
}

// —— 直播随喜（此刻节目）——
// 与点播用同一集标识（seriesId#idx），直播随喜与点播随喜同集合并计数
function liveEpTag() {
  if (mode !== 'live' || !liveItem) return '';
  const ep = liveItem.ep;
  const s = catalog.series.find((x) => x.id === ep.seriesId);
  const idx = s ? s.episodes.findIndex((e) => e.key === ep.key) : -1;
  return idx >= 0 ? `${ep.seriesId}#${idx}` : '';
}
function setLiveLikeUI(d) {
  const b = $('#btnLiveLike'); if (!b) return;
  d = d || { count: 0, liked: false };
  b.classList.toggle('on', !!d.liked);
  const badge = $('#liveLikeN');
  if (badge) {
    badge.hidden = !(d.count > 0);
    badge.textContent = d.count > 0 ? (d.count > 999 ? '999+' : String(d.count)) : '';
  }
}
async function refreshLiveLike() {
  const ep = liveEpTag();
  if (!ep) { setLiveLikeUI(null); return; }
  setLiveLikeUI(likeCache[ep]);
  try {
    const r = await fetch(`/api/like?ep=${encodeURIComponent(ep)}&dev=${encodeURIComponent(devId())}`);
    if (!r.ok) return;
    const d = await r.json();
    likeCache[ep] = d;
    if (liveEpTag() === ep) setLiveLikeUI(d);
  } catch { /* 网络波动静默 */ }
}
async function toggleLiveLike() {
  const ep = liveEpTag();
  if (!ep) { toast('稍候即可随喜此刻节目'); return; }
  const cur = likeCache[ep] || { count: 0, liked: false };
  const optimistic = { count: Math.max(0, cur.count + (cur.liked ? -1 : 1)), liked: !cur.liked };
  likeCache[ep] = optimistic; setLiveLikeUI(optimistic);
  try {
    const r = await fetch('/api/like', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ep, dev: devId() }),
    });
    if (!r.ok) throw new Error();
    const d = await r.json();
    likeCache[ep] = d;
    if (liveEpTag() === ep) setLiveLikeUI(d);
    if (d.liked) toast('随喜功德 · 南无阿弥陀佛');
  } catch {
    likeCache[ep] = cur; setLiveLikeUI(cur);
    toast('网络不畅，请稍后再试');
  }
}

// —— 闻法留言（按集）——
function openCmtSheet() {
  if (!(mode === 'od' && od)) { toast('请先选择要留言的音频'); return; }
  $('#cmtSheetEp').textContent = `${od.title} · ${od.list[od.idx].title}`;
  $('#cmtSheetName').textContent = dharmaName();
  $('#cmtSheetInput').value = '';
  $('#cmtSheetNote').textContent = '';
  $('#cmtSheetList').innerHTML = '<p class="cmt-empty">正在加载 …</p>';
  $('#cmtSheet').hidden = false;
  loadEpCmt();
}
async function loadEpCmt() {
  const ep = epTag();
  try {
    const r = await fetch(`/api/cmt?ep=${encodeURIComponent(ep)}&dev=${encodeURIComponent(devId())}`);
    if (!r.ok) throw new Error();
    const d = await r.json();
    if (epTag() !== ep) return;   // 加载期间已切集
    const list = $('#cmtSheetList');
    if (!d.items || !d.items.length) {
      list.innerHTML = '<p class="cmt-empty">还没有留言 · 来说一句闻法心得</p>';
      return;
    }
    list.innerHTML = d.items.map(cmtRowHtml).join('');
  } catch {
    $('#cmtSheetList').innerHTML = '<p class="cmt-empty">加载失败 · 请稍后再试</p>';
  }
}
let cmtSheetBusy = false;
async function sendEpCmt() {
  const input = $('#cmtSheetInput');
  const text = input.value.replace(/\s+/g, ' ').trim();
  if (!text || cmtSheetBusy) return;
  cmtSheetBusy = true;
  $('#cmtSheetSend').disabled = true;
  $('#cmtSheetNote').textContent = '';
  try {
    const r = await fetch('/api/cmt', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dev: devId(), name: dharmaName(), text, ep: epTag() }),
    });
    if (r.ok) { input.value = ''; loadEpCmt(); }
    else $('#cmtSheetNote').textContent = (await r.text()) || '发送失败，请稍后再试';
  } catch { $('#cmtSheetNote').textContent = '网络不畅，请稍后再试'; }
  cmtSheetBusy = false;
  $('#cmtSheetSend').disabled = false;
}
// 改法名：极简弹层（取代 window.prompt）
function renameDharma() {
  const inp = $('#nameInput');
  inp.value = dharmaName();
  $('#nameOverlay').hidden = false;
  setTimeout(() => { inp.focus(); inp.select(); }, 30);
}
function saveDharma() {
  const name = ($('#nameInput').value || '').replace(/\s+/g, ' ').trim().slice(0, 12);
  if (name.length < 2) { toast('法名至少 2 字'); return; }
  setLS('fy.fname', name);
  $('#cmtSheetName').textContent = name;
  $('#cmtWho').textContent = name;
  $('#nameOverlay').hidden = true;
  toast('已改名 · ' + name);
}

/* ================= 事件 ================= */

function bindEvents() {
  window.addEventListener('hashchange', route);

  /* 禁双击放大：CSS 的 touch-action: manipulation 在部分 iOS 上仍会「智能缩放」，补一道守卫。
     只拦非交互区域的第二次轻触——按钮/链接/输入一律放行，故念珠连点、佛号连点、发送连点均不受影响；
     长按选字与双指缩放照常保留（老莲友要放大看经，不可一刀切禁缩放）。 */
  let lastTapT = 0;
  document.addEventListener('touchend', (e) => {
    const now = Date.now();
    if (now - lastTapT <= 320
      && !e.target.closest('button, a, input, textarea, select, label, [contenteditable]')) {
      e.preventDefault();
    }
    lastTapT = now;
  }, { passive: false });

  document.querySelectorAll('.back-btn[data-back]').forEach((b) =>
    b.addEventListener('click', () => { location.hash = b.dataset.back; }));
  $('#btnSeriesBack').addEventListener('click', () => { location.hash = $('#btnSeriesBack').dataset.back || '#ting'; });

  // 续听卡（首页 / 足迹弹层共用 resumeListen 模块函数）
  $('#homeCards').addEventListener('click', (e) => { if (e.target.closest('[data-resume-listen]')) resumeListen(); });

  // 首页佛号：横滑速取，点一条即进全屏播放器循环恭听
  $('#homeCards').addEventListener('click', (e) => {
    const chip = e.target.closest('.fh-chip');
    if (!chip) return;
    const sec = chip.closest('[data-fohao-home]');
    const s = sec && catalog.series.find((x) => x.id === sec.dataset.fohaoHome);
    if (s) playEpisode(s, Number(chip.dataset.fhIdx));
  });

  // 同修在此：发送留言 + 轻触法名改名
  $('#btnCmtSend').addEventListener('click', sendCmt);
  $('#cmtWho').addEventListener('click', renameDharma);
  $('#cmtText').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); sendCmt(); }
  });

  // 直播：莲台大钮（直播页）与首页「正在播出」卡上的小钮同一行为
  $('#btnLive').addEventListener('click', toggleLive);
  $('#btnHomeLive').addEventListener('click', toggleLive);

  // 音频事件
  audio.addEventListener('loadedmetadata', () => {
    if (seekPending !== null) {
      try { audio.currentTime = seekPending; } catch { /* 忽略 */ }
      seekPending = null;
    }
  });
  audio.addEventListener('ended', () => {
    if (mode === 'live') { if (wantLive) loadLive(); }
    else if (mode === 'od' && od) {
      const ep = od.list[od.idx];
      if (od.progress) delLS('fy.p.' + ep.key);
      const n = od.list.length;
      if (playMode === 'one') {
        startOd();                                        // 单曲循环：重播本集
      } else if (playMode === 'shuffle' && n > 1) {
        let j = od.idx; while (j === od.idx) j = Math.floor(Math.random() * n);
        od.idx = j; startOd();                            // 随机播放
      } else {
        od.idx = (od.idx + 1) % n; startOd();             // 列表循环：末集回到首集
      }
    }
  });
  audio.addEventListener('error', () => {
    if (mode === 'live' && wantLive) {
      // 出声说明，并逐次拉长间隔 —— 源站真出故障时，固定 4 秒会一直空转
      liveFail('网络不稳');
    } else if (mode === 'od' && od) {
      playStatus('网络不稳，正在重试 …');
      const pos = audio.currentTime || 0;
      setTimeout(() => {
        if (mode !== 'od' || !od) return;
        seekPending = pos > 5 ? pos : (getProgress(od.list[od.idx].key) || 0);
        audio.load();
        audio.play().then(() => playStatus('')).catch(() => playStatus('网络不稳 · 轻触播放重试'));
      }, 4000);
    }
  });
  // 缓冲与恢复反馈。直播另布看门狗：卡着不动而又不报 error 的那种断流，
  // 光靠 error 处理器永远等不到（Safari 尤其如此）
  audio.addEventListener('waiting', () => playStatus('缓冲中 …'));
  audio.addEventListener('stalled', () => playStatus('缓冲中 …'));
  audio.addEventListener('playing', () => {
    liveRetry = 0;   // 接上了就把退避清零，下次断流仍从 4 秒起
    clearLiveWatch();
    playStatus('');
  });
  // 暂停即存进度（含睡眠定时暂停），不留 5 秒空窗
  audio.addEventListener('pause', saveProgress);
  audio.addEventListener('timeupdate', () => {
    if (mode !== 'od' || !od) return;
    const ep = od.list[od.idx];
    if (!seekDragging) {
      $('#miniSeek').value = String(Math.floor((audio.currentTime / ep.dur) * 1000));
      $('#miniCur').textContent = fmtMMSS(audio.currentTime);
      $('#miniLine').style.width = `${Math.min(100, (audio.currentTime / ep.dur) * 100)}%`;
    }
    if (Date.now() - lastSaved > 5000) { saveProgress(); lastSaved = Date.now(); }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { saveProgress(); return; }   // 熄屏/切后台立即存
    if (mode === 'live' && wantLive && !audio.paused) loadLive();
    // 回到前台且在计数器页时，重新申请屏幕常亮（Wake Lock 隐藏即失效）
    if (document.body.dataset.view === 'count' && localStorage.getItem('fy.wake') !== '0') requestWake();
  });

  // 节目单
  $('#btnToday').addEventListener('click', () => { schedDay = 0; toggleDay(); });
  $('#btnTomorrow').addEventListener('click', () => { schedDay = 1; toggleDay(); });
  function toggleDay() {
    $('#btnToday').classList.toggle('on', schedDay === 0);
    $('#btnTomorrow').classList.toggle('on', schedDay === 1);
    renderSchedule();
  }

  // 听经搜索
  let searchTimer = 0;
  $('#tingSearch').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => runSearch($('#tingSearch').value), 160);
  });
  $('#searchResults').addEventListener('click', (e) => {
    const card = e.target.closest('.series-card');
    if (card) { e.preventDefault(); location.hash = '#series/' + card.dataset.series; return; }
    const li = e.target.closest('li[data-fs]');
    if (!li) return;
    const s = catalog.series.find((x) => x.id === li.dataset.fs);
    if (s) playEpisode(s, Number(li.dataset.fi));
  });

  // 我的内容：足迹 / 收藏 / 离线下载 / 划线 —— 点行开对应弹层（清单操作在弹层内处理）
  $('#wodeContentGroup').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-wact]');
    if (!b) return;
    const a = b.dataset.wact;
    if (a === 'trail') { renderTrailSheet(); openCntSheet('trail', '足迹'); }
    else if (a === 'favs') { renderFavsSheet(); openCntSheet('favs', '我的收藏'); }
    else if (a === 'downloads') { renderDownloadsSheet(); openCntSheet('downloads', '离线下载'); }
    else if (a === 'myhl') {
      try { await ensureLibrary(); } catch { /* 离线时仍展示可解析的部分 */ }
      renderHlSheet(); openCntSheet('myhl', '我的划线');
    }
  });
  hydrateOfflineURLs();   // 启动即把已下载 blob 建成可用的 objectURL（离线亦可）

  // 听经台 / 有声书 / 系列
  $('#tingGroups').addEventListener('click', seriesCardClick);
  $('#shuGroups').addEventListener('click', seriesCardClick);
  function seriesCardClick(e) {
    const card = e.target.closest('.series-card');
    // 卡片是真链接（爬虫据此深入），点击仍走 hash 路由，不整页刷新
    if (card) { e.preventDefault(); location.hash = '#series/' + card.dataset.series; }
  }
  $('#epList').addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (!li) return;
    const s = catalog.series.find((x) => x.id === $('#epList').dataset.series);
    playEpisode(s, Number(li.dataset.idx));
  });
  $('#seriesResume').addEventListener('click', (e) => {
    const b = e.target.closest('[data-resume]');
    if (!b) return;
    const s = catalog.series.find((x) => x.id === $('#epList').dataset.series);
    if (s) { playEpisode(s, Number(b.dataset.resume)); $('#seriesResume').innerHTML = ''; }
  });

  // 文库
  $('#wkGrid').addEventListener('click', (e) => {
    const card = e.target.closest('.series-card');
    if (card) { e.preventDefault(); location.hash = '#wkseries/' + card.dataset.wk; }
  });
  $('#wkChapList').addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (li) location.hash = '#read/' + li.dataset.read;
  });

  // 阅读器
  $('#btnReaderBack').addEventListener('click', () => { location.hash = reader.backHash; });
  $('#btnPrevChap').addEventListener('click', () => stepChapter(-1));
  $('#readerNextCard').addEventListener('click', (e) => {
    if (e.target.closest('[data-next-chap]')) stepChapter(1);
  });
  function stepChapter(d) {
    if (!reader.chapters) return;
    const next = reader.chapters[reader.idx + d];
    if (next) location.hash = `#read/${reader.sid}/${next.n}`;
  }
  $('#btnRdSet').addEventListener('click', () => {
    renderRdSetSheet();
    openCntSheet('rdset', '阅读设置');
  });
  $('#btnChapList').addEventListener('click', () => {
    if (!reader.chapters) return;
    renderChaptersSheet();
    openCntSheet('chapters', `《${reader.series}》篇目`);
  });

  // 沉浸阅读：轻触正文收起/恢复顶栏与底栏（避开链接按钮、选中文字与划线记号）
  $('#readerBody').addEventListener('click', (e) => {
    const mk = e.target.closest('mark.hl');
    if (mk) {
      // 点划线记号：确认后取消该条
      if (!window.confirm('取消这条划线？')) return;
      const p = [...$('#readerBody').children].indexOf(mk.parentElement);
      const s = Number(mk.dataset.hs);
      saveHls(reader.path, getHls(reader.path).filter((h) => !(h.p === p && h.s === s)));
      applyHighlights();
      return;
    }
    if (e.target.closest('a, button')) return;
    const sel = window.getSelection();
    if (sel && !sel.isCollapsed) return;
    const zen = document.body.classList.toggle('rd-zen');
    if (zen && !localStorage.getItem('fy.zenTip')) {
      setLS('fy.zenTip', '1');
      toast('已进入沉浸阅读 · 轻触正文恢复');
    }
  });

  // 收藏本篇（书签）
  $('#btnBookmark').addEventListener('click', () => {
    if (!reader.sid) return;
    const k = 'fy.bk.' + reader.bkSpec;
    if (localStorage.getItem(k)) { delLS(k); toast('已取消收藏'); }
    // 存不进就别报「已收藏」——那比不出声更误导
    else toast(setLS(k, '1') ? '已收藏 · 「我的」页可回看' : '未能收藏 · 本机存储不可写');
    updateBookmark();
  });

  // 朗读（文转音频）：开/停、暂停/继续
  $('#btnTtsToggle').addEventListener('click', () => { if (tts.on) ttsStop(); else ttsStart(); });
  $('#ttsStopBtn').addEventListener('click', () => ttsStop());
  $('#ttsPlay').addEventListener('click', () => {
    if (!tts.on || !tts.audio) return;
    if (tts.audio.paused) { tts.audio.play().catch(() => { /* 忽略 */ }); $('#ttsBar').classList.remove('paused'); }
    else { tts.audio.pause(); $('#ttsBar').classList.add('paused'); }
  });
  // 听经开播时让位（只留一路声音）
  audio.addEventListener('play', () => { if (tts.on) ttsStop(); });

  // 我的划线（我的页入口）；文库数据未就绪则先等一拍
  // 文库标题搜索：即时过滤全库篇目
  // 与听经搜索同一节奏防抖：这里每次按键要全量扫 241 篇再重写 innerHTML，
  // 而中文输入法组字期间 input 会连发十几次，不防抖等于每敲一个拼音字母就重排一次列表
  let wkSearchTimer = 0;
  $('#wkSearch').addEventListener('input', () => {
    clearTimeout(wkSearchTimer);
    wkSearchTimer = setTimeout(runWkSearch, 160);
  });
  function runWkSearch() {
    let q = $('#wkSearch').value.trim();
    const res = $('#wkSearchResults');
    if (!q || !library) {
      res.hidden = true;
      $('#wkGrid').hidden = false;
      $('#wkResume').hidden = false;
      return;
    }
    if (zhBack && zhTradOn()) q = zhConv(q, zhBack);   // 繁体输入转回简体匹配
    if (!allChapters) {
      allChapters = [];
      for (const s of library.series) for (const c of s.chapters) allChapters.push({ s, c });
    }
    const hits = allChapters.filter(({ s, c }) => c.title.includes(q) || s.title.includes(q)).slice(0, 30);
    res.innerHTML = hits.length
      ? hits.map(({ s, c }) =>
        `<li data-read="${s.id}/${c.n}">
          <span class="n">${c.n}</span>
          <span class="t">${esc(c.title)}<small>《${esc(s.title)}》</small></span>
          ${chapProgLabel(c)}</li>`).join('')
      : '<li class="wk-none">未找到相关篇目</li>';
    res.hidden = false;
    $('#wkGrid').hidden = true;
    $('#wkResume').hidden = true;
  }
  $('#wkSearchResults').addEventListener('click', (e) => {
    const li = e.target.closest('li[data-read]');
    if (li) location.hash = '#read/' + li.dataset.read;
  });

  // 滑动翻篇：横扫快划切上/下一篇（避让屏幕边缘的系统手势与文字选择）
  let swipeStart = null;
  $('#readerBody').addEventListener('touchstart', (e) => {
    swipeStart = null;
    if (e.touches.length !== 1) return;
    const t = e.touches[0];
    if (t.clientX < 28 || t.clientX > innerWidth - 28) return;
    swipeStart = { x: t.clientX, y: t.clientY, t: Date.now() };
  }, { passive: true });
  $('#readerBody').addEventListener('touchend', (e) => {
    const st = swipeStart;
    swipeStart = null;
    if (!st || Date.now() - st.t > 550) return;
    const sel = window.getSelection();
    if (sel && !sel.isCollapsed) return;
    const dx = e.changedTouches[0].clientX - st.x;
    const dy = e.changedTouches[0].clientY - st.y;
    if (Math.abs(dx) < 72 || Math.abs(dx) < Math.abs(dy) * 2.2) return;
    stepChapter(dx < 0 ? 1 : -1);
  }, { passive: true });
  let scrollTimer = 0;
  const prefetched = new Set();
  function prefetchNext() {
    // 读至八成静默预取下一篇文本，翻篇零等待（经 SW 落入缓存，离线亦可读）
    const next = reader.chapters?.[reader.idx + 1];
    if (!next || prefetched.has(next.path)) return;
    prefetched.add(next.path);
    fetch('/text/' + next.path).catch(() => prefetched.delete(next.path));
  }
  function saveReadPos() {
    if (document.body.dataset.view !== 'reader' || !reader.path) return;
    const max = document.body.scrollHeight - innerHeight;
    if (max <= 200) return;
    const pct = Math.min(1, scrollY / max);
    // 段落锚点：顶栏下缘处的段落序号，恢复时不受字号/行距/设备影响
    const topLine = ($('.reader-bar').offsetHeight || 44) + 8;
    const kids = $('#readerBody').children;
    let p = 0;
    for (let i = 0; i < kids.length; i++) {
      if (kids[i].getBoundingClientRect().bottom > topLine) { p = i; break; }
    }
    setLS('fy.rp.' + reader.path,
      JSON.stringify({ p, pct: Math.round(pct * 1000) / 1000 }), true);
    if (pct > 0.8) prefetchNext();
  }
  window.addEventListener('scroll', () => {
    if (document.body.dataset.view !== 'reader' || !reader.path) return;
    const max = document.body.scrollHeight - innerHeight;
    // 阅读进度线即时走，进度记忆去抖存
    $('#readLine').style.width = `${max > 0 ? Math.min(100, (scrollY / max) * 100) : 0}%`;
    $('#btnTop').hidden = scrollY < innerHeight * 1.5;   // 读深了才出现回顶按钮
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(saveReadPos, 400);
  }, { passive: true });
  $('#btnTop').addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));

  // 阅读器：点顶栏「n / 总数」弹本部篇目快切
  $('#readerPos').addEventListener('click', () => {
    if (!reader.chapters) return;
    renderChaptersSheet();
    openCntSheet('chapters', `《${reader.series}》篇目`);
  });

  // 佛号：循环曲目列表 → 点一条进全屏播放器（循环恭听）
  $('#fohaoGroups').addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (!li) return;
    const ol = li.closest('[data-fohao]');
    const s = ol && catalog.series.find((x) => x.id === ol.dataset.fohao);
    if (s) playEpisode(s, Number(li.dataset.idx));
  });

  /* 念佛计数器：大念珠（涟漪 + 木鱼 + 计一声）· 补十声 · 撤销 · 重置

     计数走 pointerdown 而非 click —— 真念珠是按下即响，click 要等到抬指（touchend）
     才发，一秒三五声时那点滞后就是「跟不上手」的由来。
     键盘用户按不出 pointerdown，故 click 兜底：键盘激活按钮时 e.detail 为 0，
     以此与指针引发的 click 分开，两条路都通且不会重复计。 */
  const beadTap = (e) => { spawnBeadRipple($('#btnBead'), e); addNj(1); };
  $('#btnBead').addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;   // 右键/中键不计
    beadTap(e);
  });
  $('#btnBead').addEventListener('click', (e) => { if (e.detail === 0) beadTap(e); });
  $('#btnTen').addEventListener('click', () => addNj(10));
  $('#btnUndo').addEventListener('click', () => undoNj());   // 不直接挂：事件对象会被当成 opts

  // 静念全屏：整屏皆是念珠，轻触任意处计一声（闭目、行走念佛不必找珠）
  /* ========== 浮层的键盘出入口 ==========
     全站十来个浮层原先只能用指针关：没有 Esc、焦点也不归还，
     键盘与读屏用户一旦进去就出不来（#hxOverlay 内更是一个可聚焦元素都没有）。
     这里统一补两件事，且都不改各浮层自己的关闭逻辑 ——
     能点关闭钮的就去点它（复用原有副作用），没有钮的才直接置 hidden。 */
  const OVERLAYS = [
    // 顺序即栈序，靠后的压在上面；Esc 关最上面那一个
    // name 供读屏播报浮层身份（role=dialog 的可及名称）
    { el: '#cmtSheet', x: '#cmtSheetX', name: '闻法留言' },
    { el: '#plListSheet', x: '#plListX', name: '本系列目录' },
    { el: '#cntSheet', x: '#cntSheetX', name: '念佛功课' },
    { el: '#aboutOverlay', x: '#btnAboutClose', name: '关于本站' },
    // 主题与语言改弹层后一直漏在此清单外，故这两个至今没有 Esc、焦点也不归还
    { el: '#themeOverlay', x: '#btnThemeClose', name: '主题' },
    { el: '#langOverlay', x: '#btnLangClose', name: '语言' },
    { el: '#chatRoom', x: '#chatRoomX', name: '莲友共修群' },
    { el: '#nameOverlay', x: '#nameCancel', name: '改法名' },
    { el: '#shareSheet', x: '#shareX', name: '分享 · 法布施' },
    { el: '#posterOverlay', x: '#posterClose', name: '分享海报' },
    { el: '#zenOverlay', x: '#btnZenExit', name: '静念计数' },
    { el: '#gdOverlay', name: '今日定课圆满' },
    { el: '#hxOverlay', name: '回向偈' },
  ];
  let lastFocus = null;
  // 浮层显隐由各处直接改 hidden，故用属性观察统一接管焦点存还，
  // 免得去改十几个打开点、漏掉哪个又成新的不一致
  const openSet = new Set();
  for (const o of OVERLAYS) {
    const el = $(o.el);
    if (!el) continue;
    markDialog(el, o.name);   // role=dialog + aria-modal + 可及名称
    new MutationObserver(() => {
      if (!el.hidden && !openSet.has(el)) {
        openSet.add(el);
        if (!lastFocus) lastFocus = document.activeElement;
        // 背景置 inert：只靠 aria-modal 时部分读屏仍会读到背后的页面
        setBackgroundInert(openSet);
        // 让焦点落进浮层，否则读屏还停在背后的页面上
        const first = el.querySelector('button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])');
        if (first) first.focus({ preventScroll: true });
        else { el.setAttribute('tabindex', '-1'); el.focus({ preventScroll: true }); }
      } else if (el.hidden && openSet.has(el)) {
        openSet.delete(el);
        setBackgroundInert(openSet);   // 须先解 inert，否则来处还在 inert 里，聚不回去
        if (!openSet.size && lastFocus) { try { lastFocus.focus({ preventScroll: true }); } catch { /* 已移除 */ } lastFocus = null; }
      }
    }).observe(el, { attributes: true, attributeFilter: ['hidden'] });
  }
  // 最上层浮层：Esc 与 Tab 循环都只作用于它
  const topOverlay = () => {
    for (let i = OVERLAYS.length - 1; i >= 0; i--) {
      const el = $(OVERLAYS[i].el);
      if (el && !el.hidden) return { el, x: OVERLAYS[i].x && $(OVERLAYS[i].x) };
    }
    return null;
  };
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') { trapTab(e, topOverlay()?.el); return; }
    if (e.key !== 'Escape') return;
    const top = topOverlay();
    if (!top) return;
    if (top.x) top.x.click(); else top.el.hidden = true;   // 有关闭钮就复用它，副作用一并跑到
    e.preventDefault();
  });

  $('#btnZen').addEventListener('click', () => { renderCount(); $('#zenOverlay').hidden = false; });

  const zenExit = () => {
    $('#zenOverlay').hidden = true;
    $('#zenGoal').hidden = true;
    clearTimeout(zenGoalTimer);
    toast(`已收起静念 · 今日 ${njDayTotal(bjDateKey())} 声`);
  };
  $('#btnZenExit').addEventListener('click', (e) => { e.stopPropagation(); zenExit(); });
  $('#btnZenHint').addEventListener('click', (e) => { e.stopPropagation(); zenExit(); });

  /* 静念计数：pointerdown 即计（同大念珠），另挡两类误计 ——
       · 第二根手指落下＝收起手势，不是一声。手势自己带出的那一声顺手撤掉，
         否则每次退出都平白多一声。
       · 50ms 内的重复触点多半是衣料摩擦或手抖。念得再快一秒七八声，
         间隔也在 130ms 上下，这道闸挡不着正经念佛。 */
  let zenLastTap = 0;
  const zenTap = (e) => {
    const now = Date.now();
    if (now - zenLastTap < 50) return;
    zenLastTap = now;
    spawnBeadRipple($('#zenOverlay'), e);
    addNj(1);
  };
  $('#btnZenTap').addEventListener('pointerdown', (e) => {
    if (!e.isPrimary) { undoNj({ quiet: true }); zenExit(); return; }
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    zenTap(e);
  });
  // 键盘（空格/回车激活按钮）走这条：e.detail 为 0 即非指针引发，不会与上面重复计
  $('#btnZenTap').addEventListener('click', (e) => { if (e.detail === 0) zenTap(e); });

  // 功课中心（管理 / 定课 / 历史 / 回向 / 器物开关）+ 主屏快捷入口
  $('#btnHub').addEventListener('click', () => { renderHubSheet(); openCntSheet('hub', '功课'); });
  $('#btnPractice').addEventListener('click', () => { renderPracticeSheet(); openCntSheet('practice', '功课管理'); });
  $('#btnGoal').addEventListener('click', () => { renderGoalSheet(); openCntSheet('goal', '每日定课'); });

  // 弹层：关闭（× 或点遮罩）
  $('#cntSheetX').addEventListener('click', closeCntSheet);
  $('#cntSheet').addEventListener('click', (e) => { if (e.target === $('#cntSheet')) closeCntSheet(); });

  // 弹层内容：按当前 mode 分派（单一委托，避免重复开弹层堆积监听器）
  $('#cntSheetBody').addEventListener('click', (e) => {
    if (cntSheetMode === 'practice') {
      const del = e.target.closest('[data-del]');
      if (del) {
        if (window.confirm('删除此功课？已计声数仍保留在累计中。')) {
          nj.items = nj.items.filter((x) => x.id !== del.dataset.del);
          if (nj.cur === del.dataset.del) nj.cur = 'amtf6';
          saveNj(); renderPracticeSheet(); renderCount();
        }
        return;
      }
      if (e.target.closest('[data-add]')) {
        const name = (window.prompt('功课名称（如：心经、大悲咒、观音圣号）') || '').trim().slice(0, 12);
        if (!name) return;
        let it = nj.items.find((x) => x.name === name);
        if (!it) { it = { id: 'c' + Date.now().toString(36), name }; nj.items.push(it); }
        nj.cur = it.id; saveNj(); closeCntSheet(); renderCount();
        return;
      }
      const row = e.target.closest('[data-item]');
      if (!row) return;
      nj.cur = row.dataset.item; saveNj(); closeCntSheet(); renderCount();
    } else if (cntSheetMode === 'goal') {
      if (e.target.closest('[data-custom]')) {
        const v = parseInt(window.prompt('每日定课声数（如 300）') || '', 10);
        if (v > 0) { nj.goal = v; saveNj(); closeCntSheet(); renderCount(); }
        return;
      }
      const b = e.target.closest('[data-goal]');
      if (!b) return;
      nj.goal = Number(b.dataset.goal); saveNj(); closeCntSheet(); renderCount();
    } else if (cntSheetMode === 'history') {
      const nav = e.target.closest('[data-cal]');
      if (nav && calYM) {
        calYM.m += Number(nav.dataset.cal);
        if (calYM.m < 1) { calYM.m = 12; calYM.y--; }
        if (calYM.m > 12) { calYM.m = 1; calYM.y++; }
        renderCalendar();
      }
    } else if (cntSheetMode === 'backup') {
      const b = e.target.closest('[data-bk]');
      if (!b) return;
      const msg = (s) => { const el = $('#bkMsg'); if (el) el.textContent = s; };
      if (b.dataset.bk === 'copy') {
        copyText(backupText()).then((ok) =>
          msg(ok ? '已复制备份码 · 可存入备忘录，或发给自己保存' : '复制失败，请改用下载方式'));
      } else if (b.dataset.bk === 'file') {
        const p = bjParts(nowMs());
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([backupText()], { type: 'text/plain' }));
        a.download = `佛乐备份-${p.y}${String(p.mo).padStart(2, '0')}${String(p.d).padStart(2, '0')}.txt`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 4000);
        msg('已开始下载备份文件');
      } else {
        const code = (window.prompt('粘贴备份码（FY1. 开头）') || '').trim();
        if (!code) return;
        if (!code.startsWith('FY1.')) { msg('备份码无效，应以 FY1. 开头'); return; }
        if (!window.confirm('导入将覆盖本机现有的计数与进度记录，确定恢复？')) return;
        try {
          const { ok, total } = restoreBackup(code);
          if (!ok) { msg('本机存储不可写，未能恢复 · 请检查浏览器存储设置'); return; }
          msg(ok === total
            ? `已恢复 ${ok} 项数据，即将刷新 …`
            : `仅恢复 ${ok} / ${total} 项，本机存储可能已满，即将刷新 …`);
          setTimeout(() => location.reload(), ok === total ? 900 : 2200);
        } catch { msg('备份码无效或不完整，请重新复制'); }
      }
    } else if (cntSheetMode === 'chapters') {
      const li = e.target.closest('li[data-jump]');
      if (!li) return;
      closeCntSheet();
      location.hash = '#read/' + li.dataset.jump;
    } else if (cntSheetMode === 'rdset') {
      const b = e.target.closest('[data-rs]');
      if (!b) return;
      const [k, v] = b.dataset.rs.split(':');
      setLS('fy.' + k, v);
      applyReaderPrefs();
      renderRdSetSheet();
    } else if (cntSheetMode === 'storage') {
      if (e.target.closest('[data-offline-clear]')) {
        if (!window.confirm('清空全部离线音频？已下载的集将需要重新下载。')) return;
        clearAllOffline().then(() => { renderStorageSheet(); refreshDownloadsUI(); updateDownloadBtn(); toast('已清空离线音频'); });
        return;
      }
      if (!e.target.closest('[data-st-clear]')) return;
      if (!window.confirm('清空全部缓存并刷新页面？\n念佛计数、阅读进度、收藏不受影响。')) return;
      caches.keys()
        .then((ks) => Promise.all(ks.map((k) => caches.delete(k))))
        .then(() => location.reload());
    } else if (cntSheetMode === 'cite') {
      const b = e.target.closest('[data-cite-open]');
      if (!b) return;
      closeCntSheet();
      pendingReaderBack = '#wenda';
      location.hash = pathToHash(b.dataset.citeOpen);
    } else if (cntSheetMode === 'myhl') {
      const b = e.target.closest('[data-hl-open]');
      if (!b) return;
      closeCntSheet();
      const target = pathToHash(b.dataset.hlOpen);
      if (location.hash === target) scrollToPara(Number(b.dataset.hlP));   // 已在本篇：直接定位
      else {
        pendingHlTarget = { path: b.dataset.hlOpen, p: Number(b.dataset.hlP) };
        location.hash = target;
      }
    } else if (cntSheetMode === 'hub') {
      const tg = e.target.closest('[data-hubtg]');
      if (tg) {
        // 器物开关：木鱼默认关；常亮/震动默认开
        const key = tg.dataset.hubtg;
        const v = localStorage.getItem(key);
        const on = v === null ? key !== 'fy.muyu' : v === '1';
        setLS(key, on ? '0' : '1');
        if (key === 'fy.muyu' && !on) playMuyu();
        if (key === 'fy.wake') { if (on) releaseWake(); else requestWake(); }
        renderHubSheet();
        return;
      }
      const nav = e.target.closest('[data-hub]');
      if (!nav) return;
      if (nav.dataset.hub === 'practice') { renderPracticeSheet(); openCntSheet('practice', '功课管理'); }
      else if (nav.dataset.hub === 'goal') { renderGoalSheet(); openCntSheet('goal', '每日定课'); }
      else if (nav.dataset.hub === 'history') {
        const p = bjParts(nowMs());
        calYM = { y: p.y, m: p.mo };
        renderCalendar(); openCntSheet('history', '念佛历史');
      } else if (nav.dataset.hub === 'lian') {
        renderLianSheet(); openCntSheet('lian', '莲号 · 功课同步');
      } else if (nav.dataset.hub === 'huixiang') {
        closeCntSheet();
        $('#hxOverlay').hidden = false;
      } else if (nav.dataset.hub === 'reset') {
        // 重置＝当前功课今日归零（累计同步扣除今日声数），须确认；
        // 从主屏移进功课中心，主屏不留破坏性按键
        const it = njItem();
        const mine = (nj.days[bjDateKey()] || {})[it.id] || 0;
        if (!mine) { toast('「' + it.name + '」今日尚未计数'); return; }
        if (!window.confirm(`将「${it.name}」今日 ${mine} 声清零？\n累计将同步扣除这 ${mine} 声。`)) return;
        addNj(-mine);
        closeCntSheet();
        toast('今日计数已清零');
      }
    } else if (cntSheetMode === 'lian') {
      const b = e.target.closest('[data-lian]');
      if (!b) return;
      const act = b.dataset.lian;
      const msg = (s) => { const el = $('#lianMsg'); if (el) el.textContent = s; };

      if (act === 'claim') { $('#lianForm').hidden = false; $('#lianIn').focus(); return; }

      if (act === 'open') {
        b.disabled = true; b.textContent = '开 号 中 …';
        syncOpen(devId())
          .then((r) => showLianCard(r.lian, r.pass, '莲号已开'))
          .catch((err) => { b.disabled = false; b.textContent = '开 一 枚 莲 号'; msg(err.message || '开号未成'); });
        return;
      }
      if (act === 'do-claim') {
        const l = ($('#lianIn').value || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
        const p = ($('#passIn').value || '').replace(/\D/g, '');
        if (l.length !== 8 || p.length !== 6) { msg('莲号八位，护念码六位数字'); return; }
        b.disabled = true; msg('认回中 …');
        syncClaim(l, p).then(() => {
          loadNj(); renderCount(); renderWode();
          renderLianSheet();
          toast('功课已认回');
        }).catch((err) => { b.disabled = false; msg(err.message || '认回未成'); });
        return;
      }
      if (act === 'copy') {
        copyText(`佛乐 · 莲号 ${fmtLian(b.dataset.l)}　护念码 ${b.dataset.p}`)
          .then((ok) => toast(ok ? '已复制 · 请存到稳妥处' : '复制未成，请手抄'));
        return;
      }
      if (act === 'done') { $('#cntSheetTitle').textContent = '莲号 · 功课同步'; renderLianSheet(); return; }
      if (act === 'sync') {
        msg('同步中 …');
        syncRun({ now: true }).then((ok) => {
          loadNj(); renderCount(); renderWode();
          msg(ok ? '已同步' : (syncLastError() || '同步未成'));
        });
        return;
      }
      if (act === 'repass') {
        const p = (window.prompt('请先报出现用的护念码（六位数字）') || '').replace(/\D/g, '');
        if (p.length !== 6) return;
        syncRepass(syncAccount().lian, p)
          .then((r) => showLianCard(r.lian, r.pass, '护念码已换'))
          .catch((err) => msg(err.message || '未能更换'));
        return;
      }
      if (act === 'unlink') {
        if (!window.confirm('在本机解除莲号？\n云端功课与本机记录都不删，随时可以再认回。')) return;
        syncUnlink(); renderLianSheet(); toast('已在本机解除');
      }
    } else if (cntSheetMode === 'trail') {
      // 足迹：续听（按钮）/ 续读（锚点）—— 均关闭弹层
      if (e.target.closest('[data-resume-listen]')) { closeCntSheet(); resumeListen(); }
      else if (e.target.closest('a[href^="#"]')) closeCntSheet();   // 续读锚点自然跳转
    } else if (cntSheetMode === 'favs') {
      const del = e.target.closest('[data-unfav]');
      if (del) { delLS('fy.fav.' + del.dataset.unfav); updateFav(); renderFavsSheet(); renderWode(); return; }
      const unbk = e.target.closest('[data-unbk]');
      if (unbk) { delLS('fy.bk.' + unbk.dataset.unbk); renderFavsSheet(); renderWode(); return; }
      const bkr = e.target.closest('li[data-bkr]');
      if (bkr) { closeCntSheet(); location.hash = '#read/' + bkr.dataset.bkr; return; }
      const li = e.target.closest('li[data-fs]');
      if (!li) return;
      const s = catalog.series.find((x) => x.id === li.dataset.fs);
      if (s) { closeCntSheet(); playEpisode(s, Number(li.dataset.fi)); }
    } else if (cntSheetMode === 'downloads') {
      const del = e.target.closest('[data-dldel]');
      if (del) { removeOffline(del.dataset.dldel).then(() => { renderDownloadsSheet(); renderWode(); updateDownloadBtn(); toast('已删除离线文件'); }); return; }
      const li = e.target.closest('li[data-dlplay]');
      if (!li) return;
      const s = catalog.series.find((x) => x.id === li.dataset.dlsid);
      if (!s) { toast('该系列已更新，请重新下载'); return; }
      const i = s.episodes.findIndex((x) => x.key === li.dataset.dlplay);
      if (i >= 0) { closeCntSheet(); playEpisode(s, i); }
    }
  });

  // 回向偈（入口在功课中心）
  $('#hxOverlay').addEventListener('click', () => { $('#hxOverlay').hidden = true; });

  // 定课圆满层：轻触返回，或转入回向
  $('#gdOverlay').addEventListener('click', (e) => {
    if (!e.target.closest('#btnGdHx')) $('#gdOverlay').hidden = true;
  });
  $('#btnGdHx').addEventListener('click', () => {
    $('#gdOverlay').hidden = true;
    $('#hxOverlay').hidden = false;
  });

  // 备份与迁移（我的）
  $('#btnBackup').addEventListener('click', () => { renderBackupSheet(); openCntSheet('backup', '备份与迁移'); });

  // 存储与缓存（我的）
  $('#btnStorage').addEventListener('click', () => { openCntSheet('storage', '存储与缓存'); renderStorageSheet(); });

  // 播放器「目录」抽屉：不离开播放器快速切集；「前往系列页」保留旧跳转
  $('#btnPlaylist').addEventListener('click', openPlList);
  $('#plListX').addEventListener('click', () => { $('#plListSheet').hidden = true; });
  $('#plListSheet').addEventListener('click', (e) => { if (e.target === $('#plListSheet')) $('#plListSheet').hidden = true; });
  $('#plListEps').addEventListener('click', (e) => {
    const li = e.target.closest('li[data-pi]');
    if (!li || !od) return;
    const i = Number(li.dataset.pi);
    if (i === od.idx) return;
    saveProgress();
    od.idx = i;
    startOd();
  });
  $('#plListGo').addEventListener('click', () => {
    $('#plListSheet').hidden = true;
    if (od && od.seriesId) { setMiniExpanded(false); location.hash = '#series/' + od.seriesId; }
  });

  // 分享（法布施）：播放器与阅读器入口 + 分享抽屉
  $('#btnShare').addEventListener('click', () => openShare(playerShare()));
  $('#btnReaderShare').addEventListener('click', () => openShare(readerShare()));
  $('#btnLiveShare').addEventListener('click', () => openShare(liveShare()));

  // 莲友共修群（独立模块，#qun 路由）：直播「留言」入口、返回、新消息浮标
  $('#btnLiveChat').addEventListener('click', () => { location.hash = '#qun'; });
  $('#chatRoomX').addEventListener('click', () => { location.hash = chatBackHash || '#home'; });
  $('#crShare').addEventListener('click', () => openShare(chatShare()));   // 头部「…」分享共修群
  $('#crJump').addEventListener('click', () => { scrollChatBottom(); hideChatJump(); });
  $('#cmtText').addEventListener('input', updateCmtCount);

  // 改法名弹层：保存 / 取消 / 回车 / 点遮罩关
  $('#nameSave').addEventListener('click', saveDharma);
  $('#nameCancel').addEventListener('click', () => { $('#nameOverlay').hidden = true; });
  $('#nameInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); saveDharma(); } });
  $('#nameOverlay').addEventListener('click', (e) => { if (e.target === $('#nameOverlay')) $('#nameOverlay').hidden = true; });

  // 分享法布施：阅读器内选中经文（上限 800 字），浮标一点生成长图
  let quoteText = '';
  let selT = 0;
  document.addEventListener('selectionchange', () => {
    clearTimeout(selT);
    selT = setTimeout(() => {
      const chip = $('#quoteChip');
      const sel = window.getSelection();
      const inReader = document.body.dataset.view === 'reader'
        && sel && !sel.isCollapsed && sel.rangeCount
        && $('#readerBody').contains(sel.anchorNode);
      // 保留段落换行，只压平段内多余空白
      const text = inReader
        ? sel.toString().replace(/[ \t]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim()
        : '';
      if (text.length < 6) { chip.hidden = true; return; }
      quoteText = trimQuote(text, 800);
      $('#chipShare').textContent = `❝ 分享 · ${quoteText.length} 字`;
      const r = sel.getRangeAt(0).getBoundingClientRect();
      chip.hidden = false;
      const w = chip.offsetWidth || 190;
      chip.style.left = `${Math.max(12, Math.min(innerWidth - w - 12, r.left + r.width / 2 - w / 2))}px`;
      chip.style.top = `${Math.max(12, Math.min(innerHeight - 64, r.bottom + 14))}px`;
    }, 220);
  });
  $('#chipHl').addEventListener('click', addHighlight);
  $('#chipShare').addEventListener('click', () => {
    $('#quoteChip').hidden = true;
    const base = readerShare();
    if (!base || !quoteText) return;
    sharePayload = { ...base, quote: quoteText };
    showPoster(makeQuotePoster(posterPayload(sharePayload)));
    window.getSelection()?.removeAllRanges();
  });
  $('#shareX').addEventListener('click', () => { $('#shareSheet').hidden = true; });
  $('#shareSheet').addEventListener('click', (e) => { if (e.target === $('#shareSheet')) $('#shareSheet').hidden = true; });
  $('#shareSys').addEventListener('click', () => {
    const p = sharePayload;
    if (p) navigator.share({ title: p.title, text: `${p.text}\n`, url: p.url }).catch(() => { /* 用户取消 */ });
  });
  $('#shareCopy').addEventListener('click', async () => {
    const p = sharePayload;
    if (!p) return;
    const ok = await copyText(`${p.text}\n${p.url}`);
    $('#shareMsg').textContent = ok ? '已复制 · 粘贴给莲友即可' : '复制失败，请手动复制链接';
  });
  $('#sharePoster').addEventListener('click', () => {
    if (!sharePayload) return;
    // 直播分享走专版海报（带当下播放内容与进度），其余走通用版
    const pp = posterPayload(sharePayload);
    showPoster(pp.kind === 'live' ? makeLivePoster(pp) : makePoster(pp));
  });
  const closePoster = () => {
    $('#posterOverlay').hidden = true;
    $('#posterImg').removeAttribute('src');
    revokePoster();
    resetPoster();      // 作废在途回调，免得它给已关闭的预览挂上 src 又钉住一个 blob
  };
  $('#posterClose').addEventListener('click', closePoster);
  $('#posterOverlay').addEventListener('click', (e) => { if (e.target === $('#posterOverlay')) closePoster(); });
  // 分享至社交软件：走系统分享面板（微信等均在其中）；不支持的环境按钮不显示
  $('#posterShare').addEventListener('click', () => {
    posterToBlob((blob) => {
      if (!blob) { toast('海报生成失败 · 请缩短所选文字再试'); return; }
      const file = new File([blob], 'foyue-share.png', { type: 'image/png' });
      navigator.share({ files: [file] }).catch(() => { /* 用户取消 */ });
    });
  });
  $('#posterSave').addEventListener('click', () => {
    posterToBlob((blob) => {
      if (!blob) { toast('海报生成失败 · 请缩短所选文字再试'); return; }
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'foyue-share.png';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    });
  });

  // 外观设置
  // 主题：行式条目 → 弹层选择，与语言同一套路。
  const openTheme = () => {
    const cur = themePref();
    $('#themeList').innerHTML = THEMES.map((x) => `<button class="sheet-row${x.id === cur ? ' on' : ''}" data-theme="${x.id}"><span class="pr-main"><span>${x.name}</span><small class="pr-stat">${x.desc}</small></span>${
      x.id === cur ? '<span class="sheet-tick">✓</span>' : ''}</button>`).join('');
    $('#themeOverlay').hidden = false;
  };
  const closeTheme = () => { $('#themeOverlay').hidden = true; };
  $('#btnTheme').addEventListener('click', openTheme);
  $('#btnThemeClose').addEventListener('click', closeTheme);
  $('#themeOverlay').addEventListener('click', (e) => {
    if (e.target === $('#themeOverlay')) closeTheme();   // 点遮罩关闭，点内容不关
  });
  $('#themeList').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-theme]');
    if (!b) return;
    closeTheme();
    setLS('fy.theme', b.dataset.theme);
    applyThemePref();
  });

  // 语言：行式条目 → 弹层选择。选项按 LANGS 现铺，日后加语种不必动这里。
  const openLang = () => {
    const cur = getLang();
    $('#langList').innerHTML = LANGS.map((x) => `<button class="sheet-row${x.id === cur ? ' on' : ''}" data-lang="${x.id}"><span>${x.name}</span>${
      x.id === cur ? '<span class="sheet-tick">✓</span>' : ''}</button>`).join('');
    $('#langOverlay').hidden = false;
  };
  const closeLang = () => { $('#langOverlay').hidden = true; };
  $('#btnLang').addEventListener('click', openLang);
  $('#btnLangClose').addEventListener('click', closeLang);
  $('#langOverlay').addEventListener('click', (e) => {
    if (e.target === $('#langOverlay')) closeLang();   // 点遮罩关闭，点内容不关
  });
  $('#langList').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-lang]');
    if (!b) return;
    closeLang();
    const cur = getLang();
    const next = b.dataset.lang;
    if (next === cur) return;
    setLS('fy.lang', next);
    setLS('fy.zh', next === 't' ? 't' : 's');   // 兼容旧键
    applyLangRow(next);
    // 从简体出发可就地转换；其余切换（如繁→英）重载后按偏好初始化最可靠
    if (cur === 's' && next === 't') { setZhTrad(true); return; }
    if (cur === 's' && (next === 'en' || next === 'ja')) { initI18n(next); return; }
    location.reload();
  });

  // 关于本站弹窗
  $('#btnAbout').addEventListener('click', () => { $('#aboutOverlay').hidden = false; });
  $('#btnAboutClose').addEventListener('click', () => { $('#aboutOverlay').hidden = true; });
  $('#aboutOverlay').addEventListener('click', (e) => {
    if (e.target === $('#aboutOverlay')) $('#aboutOverlay').hidden = true;   // 点遮罩关闭，点内容不关
  });

  // 问道：对话（流式中发送键＝停止）
  $('#btnAsk').addEventListener('click', () => {
    if (isAsking()) { abortAsk(); return; }
    sendQuestion($('#wdInput').value);
  });
  $('#wdInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendQuestion($('#wdInput').value); }
  });
  // 随字数长高。原先 rows=1 死守一行，问句稍长就只看得见最后一行，
  // 写到一半回看不了自己写了什么 —— 打字慢的人尤其难受。
  // 上限只写在 CSS（.chat-input textarea 的 max-height），此处不复述那个数字。
  $('#wdInput').addEventListener('input', () => { growInput(); syncAskUI(); });
  $('#chatStarters').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (b) sendQuestion(b.textContent);
  });
  $('#btnChatNew').addEventListener('click', () => {
    if (isAsking()) return;
    if (chatCount() && !window.confirm('开始新的一问？当前对话将清空。')) return;
    clearChat();
  });
  $('#chatLog').addEventListener('click', (e) => {
    // 引用角标 / 出处行 → 出处预览抽屉（不打断对话）
    const c = e.target.closest('[data-path]');
    if (c) {
      $('#cntSheetBody').innerHTML = `
        <p class="cite-src">《${esc(c.dataset.s || '')}》· ${esc(c.dataset.t || '')}</p>
        <p class="cite-x">${c.dataset.x ? esc(c.dataset.x) + ' …' : '相关段落见原文'}</p>
        <button class="st-clear" data-cite-open="${esc(c.dataset.path)}">恭读全文 ›</button>`;
      openCntSheet('cite', '出处');
      return;
    }
    const rt = e.target.closest('[data-retry]');
    if (rt) {
      // 连同上方残留的提问气泡一起移除，重发不留重影
      const errMsg = rt.closest('.msg');
      if (errMsg?.previousElementSibling?.classList.contains('user')) errMsg.previousElementSibling.remove();
      errMsg?.remove();
      sendQuestion(rt.dataset.retry);
      return;
    }
    const act = e.target.closest('[data-ans-copy],[data-ans-share]');
    if (!act) return;
    const mi = Number(act.closest('.msg')?.dataset.mi);
    const m = chatMsg(mi);
    if (!m) return;
    const qText = chatMsg(mi - 1)?.content || '';
    const clean = m.content.replace(/\[\d{1,2}\]/g, '').replace(/\*\*/g, '').trim();
    if (act.hasAttribute('data-ans-copy')) {
      copyText(`问：${qText}\n\n${clean}\n\n—— 佛乐 · 问法 ${location.origin}/#wenda`)
        .then((ok) => toast(ok ? '已复制' : '复制失败'));
    } else {
      shareAnswer(qText, clean, m.sources);
    }
  });

  // 迷你播放条：两态 / 关闭 / 标题回系列
  $('#btnMiniToggle').addEventListener('click', () =>
    setMiniExpanded($('#mini').classList.contains('collapsed')));
  $('#btnPlayerDown').addEventListener('click', () => setMiniExpanded(false));
  $('#miniTitles').addEventListener('click', () => setMiniExpanded(true));
  $('#miniArt').addEventListener('click', () => setMiniExpanded(true));
  $('#btnPrevEp').addEventListener('click', () => stepEpisode(-1));
  $('#btnNextEp').addEventListener('click', () => stepEpisode(1));

  // 播放模式：单键轮换 列表循环 → 单曲循环 → 随机播放
  function applyPlayMode() {
    const b = $('#btnPlayMode');
    if (!b) return;
    b.classList.remove('m-list', 'm-one', 'm-shuffle');
    b.classList.add(playMode === 'one' ? 'm-one' : playMode === 'shuffle' ? 'm-shuffle' : 'm-list');
    const name = playMode === 'one' ? '单曲循环' : playMode === 'shuffle' ? '随机播放' : '列表循环';
    b.setAttribute('aria-label', '播放模式：' + name);
  }
  applyPlayMode();
  $('#btnPlayMode').addEventListener('click', () => {
    playMode = playMode === 'list' ? 'one' : playMode === 'one' ? 'shuffle' : 'list';
    setLS('foyue_playmode_v1', playMode);
    applyPlayMode();
    toast(playMode === 'one' ? '单曲循环' : playMode === 'shuffle' ? '随机播放' : '列表循环');
  });

  // 随喜 / 按集闻法留言（D1 后台）
  $('#btnLike').addEventListener('click', toggleLike);
  $('#btnComment').addEventListener('click', openCmtSheet);
  $('#cmtSheetSend').addEventListener('click', sendEpCmt);
  $('#cmtSheetInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); sendEpCmt(); } });
  $('#cmtSheetName').addEventListener('click', renameDharma);
  $('#cmtSheetX').addEventListener('click', () => { $('#cmtSheet').hidden = true; });
  $('#cmtSheet').addEventListener('click', (e) => { if (e.target === $('#cmtSheet')) $('#cmtSheet').hidden = true; });

  // 下载本集 → App 内离线缓存（IndexedDB）；再点可删除。「我的 · 已下载」可离线恭听
  $('#btnDownload').addEventListener('click', () => {
    if (!(mode === 'od' && od && od.list[od.idx])) { toast('请先选择要下载的音频'); return; }
    const key = od.list[od.idx].key;
    if (offlineDownloading.has(key)) { toast('正在下载 …'); return; }
    if (offlineHas(key)) {
      if (window.confirm('本集已离线下载。删除离线文件？')) {
        removeOffline(key).then(() => { updateDownloadBtn(); refreshDownloadsUI(); toast('已删除离线文件'); });
      }
      return;
    }
    downloadOffline(od, od.idx);
  });
  $('#btnFav').addEventListener('click', toggleFav);
  const togglePlay = () => {
    if (audio.paused) audio.play().catch(() => {}); else audio.pause();
  };
  $('#btnMiniPlay').addEventListener('click', togglePlay);
  $('#btnMiniPlaySm').addEventListener('click', togglePlay);
  $('#btnBack15').addEventListener('click', () => { audio.currentTime = Math.max(0, audio.currentTime - 15); });
  $('#btnFwd15').addEventListener('click', () => { audio.currentTime = audio.currentTime + 15; });
  $('#btnRate').addEventListener('click', () => {
    const next = RATES[(RATES.indexOf(currentRate()) + 1) % RATES.length];
    setLS('fy.rate', String(next));
    audio.playbackRate = next;
    $('#rateVal').textContent = `${next}×`;
  });
  const cycleSleep = () => setSleep(SLEEP_MINS[(SLEEP_MINS.indexOf(sleepT.min) + 1) % SLEEP_MINS.length]);
  $('#btnSleep').addEventListener('click', cycleSleep);
  $('#btnLiveSleep').addEventListener('click', cycleSleep);

  // 随喜此刻节目（直播工具行）
  $('#btnLiveLike').addEventListener('click', toggleLiveLike);
  $('#miniSeek').addEventListener('input', () => {
    seekDragging = true;
    if (od) $('#miniCur').textContent = fmtMMSS(($('#miniSeek').value / 1000) * od.list[od.idx].dur);
  });
  $('#miniSeek').addEventListener('change', () => {
    if (od) audio.currentTime = ($('#miniSeek').value / 1000) * od.list[od.idx].dur;
    seekDragging = false;
  });
}

/* ================= 媒体会话（锁屏控制） ================= */

function setMS(action, fn) {
  try { navigator.mediaSession.setActionHandler(action, fn); } catch { /* 旧浏览器不支持该操作 */ }
}

function updateMediaSession(ep, tag) {
  if (!('mediaSession' in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: ep.title,
    artist: '大安法师',
    album: `${ep.seriesTitle}${tag ? ' · ' + tag : ''} · 佛乐`,
    // 锁屏封面：品牌标志（宣纸底方图），多尺寸供系统挑选
    artwork: [
      { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
    ],
  });
  setMS('play', () => audio.play().catch(() => {}));
  setMS('pause', () => audio.pause());
  if (mode === 'od') {
    // 点播：锁屏可快退快进、拖进度、切上下集
    setMS('seekbackward', () => { audio.currentTime = Math.max(0, audio.currentTime - 15); });
    setMS('seekforward', () => { audio.currentTime = audio.currentTime + 15; });
    setMS('seekto', (e) => { if (e.seekTime != null) audio.currentTime = e.seekTime; });
    setMS('previoustrack', od && od.idx > 0 ? () => stepEpisode(-1) : null);
    setMS('nexttrack', od && od.idx < od.list.length - 1 ? () => stepEpisode(1) : null);
  } else {
    // 直播与大众同步、佛号循环定课：不开放拖动与切集
    setMS('seekbackward', null); setMS('seekforward', null); setMS('seekto', null);
    setMS('previoustrack', null); setMS('nexttrack', null);
  }
}


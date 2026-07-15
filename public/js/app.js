// 佛乐 · 主应用
// 底部导航：首页（今日案头）/ 听经（听经台·有声书·佛号）/ 文库（阅读站）/ 我的（数珠计数）
// 问道（文库RAG问答）在顶栏右上角
// 播放模式：live 直播（确定性排播全网同步）/ od 点播（进度记忆）/ nianfo 佛号（循环+定课）

import {
  createStation, stationNow, fmtClock, fmtDur, fmtMMSS, bjParts,
} from './station.js';
import { SERIES_INTROS } from './intros.js';
import { initI18n } from './i18n.js';

const $ = (s) => document.querySelector(s);
const audio = $('#audio');
const WEEK = ['日', '一', '二', '三', '四', '五', '六'];
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
let allChapters = null;            // 文库全部篇目（今日恭读用）
let chat = { msgs: [], streaming: false };
let askCtrl = null;                // 问法流式请求控制器（停止生成用）

init();

async function init() {
  // bo.foyue.org（直播台入口域名）：无锚点访问默认直达直播页（同一 Worker，不拆站）
  if (/^bo\./i.test(location.hostname) && !location.hash) location.replace('#live');
  // 首屏只等 catalog（听经/直播立即可用）；library/qa 后台预取，进相关页时再等
  try {
    catalog = await fetchJson('/catalog.json');
  } catch {
    showLoadError();
    return;
  }
  station = createStation(catalog);

  loadNj();
  loadChat();
  pruneRt();
  buildTing();
  buildShu();
  buildFohao();
  buildHome();
  applyThemePref();
  bindEvents();
  route();
  tick();
  setInterval(tick, 1000);
  ensureLibrary().catch(() => { /* 预取失败静默：进入相关页时会重试 */ });
  // 语言偏好：繁体走字表转换，外文走 AI 词典（均接管后续动态内容）
  const lang = getLang();
  applyLangChips(lang);
  if (lang === 't') setZhTrad(true);
  else if (lang === 'en' || lang === 'ja') initI18n(lang);
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => { /* 忽略 */ });
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
      buildWenda();
      if (document.body.dataset.view === 'home') buildHome();
      if (document.body.dataset.view === 'wode') renderWode();
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

// 外观偏好：auto 跟随时段（由 tick 依直播时段流转）/ day 固定浅色 / night 固定深色
function applyThemePref() {
  const pref = localStorage.getItem('fy.theme') || 'auto';
  document.querySelectorAll('#themeChips button').forEach((b) =>
    b.classList.toggle('on', b.dataset.theme === pref));
  let theme = pref;
  if (pref === 'auto') theme = station.liveAt(stationNow()).item.block.theme;
  document.body.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'night' ? '#17130e' : '#f3ecda');
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

// 语言偏好：s 简体 / t 繁體 / en English / ja 日本語（旧键 fy.zh 自动迁移）
function getLang() {
  return localStorage.getItem('fy.lang')
    || (localStorage.getItem('fy.zh') === 't' ? 't' : 's');
}

function applyLangChips(l) {
  document.querySelectorAll('#langChips button').forEach((b) =>
    b.classList.toggle('on', b.dataset.lang === l));
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
  if (view === 'count') { if (localStorage.getItem('fy.wake') !== '0') requestWake(); }
  else if (_wakeLock) releaseWake();
  if (view !== 'reader') {
    document.body.classList.remove('rd-zen');   // 离开阅读器退出沉浸
    ttsStop();                                  // 离开阅读器停朗读
  }
  $('#quoteChip').hidden = true;
  document.body.dataset.view = view;
  if (view === 'live') {
    startCmt();   // 直播留言：进直播页轮询
    refreshLiveLike();   // 随喜此刻节目
    // 进入直播即自动播放：用户手势下可直接起播，被浏览器自动播放策略拦截时 loadLive 回落到「轻触莲台」
    if (mode !== 'live') backToLive();
    else if (audio.paused) { wantLive = true; loadLive(); }
  } else { closeChatRoom(); stopCmt(); dmClear(); }   // 离开直播：关聊天室、停轮询、清弹幕
  document.body.dataset.tab = tab;  // 导航高亮与子栏面板显示依赖 data-tab / data-seg
  document.querySelectorAll('a[data-tab]').forEach((a) => a.classList.toggle('on', a.dataset.tab === tab));
}

/* ================= 主循环 ================= */

function tick() {
  const t = stationNow();
  const { item, offset, next } = station.liveAt(t, 3);

  const p = bjParts(Date.now());
  const dateStr =
    `${p.y}年${p.mo}月${p.d}日 · 周${WEEK[p.day]} · 北京时间 ${String(p.h).padStart(2, '0')}:${String(p.mi).padStart(2, '0')}`;
  $('#nowDate').textContent = dateStr;
  $('#homeDate').textContent = dateStr;

  // 外观：跟随时段（auto）自动昼夜流转；或用户固定浅色/深色
  const themePref = localStorage.getItem('fy.theme') || 'auto';
  const theme = themePref === 'auto' ? item.block.theme : themePref;
  document.body.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'night' ? '#17130e' : '#f3ecda');

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

  $('#liveFill').style.width = `${Math.min(100, (offset / item.ep.dur) * 100)}%`;
  $('#liveElapsed').textContent = fmtMMSS(offset);
  $('#liveTotal').textContent = fmtMMSS(item.ep.dur);

  if (mode === 'live' && !audio.paused && seekPending === null) {
    if (Math.abs(audio.currentTime - offset) > 40) audio.currentTime = offset;
  }

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
    localStorage.setItem(rk, String((Number(localStorage.getItem(rk)) || 0) + 1));
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

function dailyPick() {
  // 今日恭读：按北京日期确定性轮选一篇，全网同一篇
  if (!allChapters) {
    allChapters = [];
    for (const s of library.series) for (const c of s.chapters) allChapters.push({ s, c });
  }
  const p = bjParts(Date.now());
  const seed = p.y * 372 + p.mo * 31 + p.d;
  return allChapters[seed % allChapters.length];
}

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

// 首页四门入口（发现枢纽）：图标复用底部导航语汇，朱砂点睛
const HOME_DOORS = [
  { href: '#ting', name: '听经', sub: '二十四小时讲经',
    icon: '<circle cx="12" cy="12" r="8.6"/><path d="M10.2 8.9v6.2l5.3-3.1z" fill="currentColor" stroke="none"/>' },
  { href: '#shu', name: '有声书', sub: '故事 · 传记',
    icon: '<path d="M12 6c-1.8-1.4-4.2-1.8-7-1.6v13.2c2.8-.2 5.2.2 7 1.6 1.8-1.4 4.2-1.8 7-1.6V4.4c-2.8-.2-5.2.2-7 1.6z"/><path d="M12 6v13.2"/>' },
  { href: '#count', name: '念佛', sub: '数珠 · 定课',
    icon: '<path d="M12 4.5c2.2 3 2.2 6.7 0 9.7-2.2-3-2.2-6.7 0-9.7z"/><path d="M6.2 8c2.8.9 4.6 3.1 4.8 6.4-3-.6-4.8-3-4.8-6.4zM17.8 8c-2.8.9-4.6 3.1-4.8 6.4 3-.6 4.8-3 4.8-6.4z"/><path d="M4.5 15c2.3 3 12.7 3 15 0-1.6 4.2-13.4 4.2-15 0z"/>' },
  { href: '#wenku', name: '阅读', sub: '讲记原文',
    icon: '<rect x="5" y="4" width="14" height="16" rx="2.2"/><path d="M8.6 4v16"/><path d="M11.6 9.2h4.6M11.6 13h4.6"/>' },
];

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
      <svg class="fh-lotus" viewBox="0 0 64 64" aria-hidden="true">
        <g fill="none" stroke="currentColor" stroke-width="2.6" stroke-linejoin="round" stroke-linecap="round">
          <path d="M32 10 C38 18 38 28 32 36 C26 28 26 18 32 10 Z"/>
          <path d="M18 18 C26 21 31 28 31 36 C23 34 18 27 18 18 Z"/>
          <path d="M46 18 C38 21 33 28 33 36 C41 34 46 27 46 18 Z"/>
          <path d="M14 43 C22 50 42 50 50 43"/>
          <path d="M21 51 C27 56 37 56 43 51"/>
        </g>
      </svg>
      <span class="fh-title">佛号</span>
      <span class="fh-sub">都摄六根 · 净念相继</span>
      <a class="fh-all" href="#fohao">经咒 ›</a>
    </div>
    <div class="fh-grid">${cells}</div>
  </section>`;
}

function buildHome() {
  // 首页信息秩序（今日案头）：个人续听最优先 → 四门导航 → 佛号速取 → 今日恭读
  // 继续收听（若有未听完）——回访者最想要的一键
  let html = listenCardHtml('继续收听');

  // 四门宫格（听经 / 有声书 / 念佛 / 阅读）
  html += '<div class="home-grid">' + HOME_DOORS.map((d) =>
    `<a class="grid-card" href="${d.href}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${d.icon}</svg>
      <strong>${d.name}</strong><span>${esc(d.sub)}</span></a>`).join('') + '</div>';

  // 佛号速取（紧凑两列，随手起一炉佛号循环恭听）
  html += fohaoHomeHtml();

  // 今日恭读（每日轮选一篇讲记；文库数据就绪后由 ensureLibrary 补绘）
  if (library) {
    const pick = dailyPick();
    html += `<a class="home-card" href="#read/${pick.s.id}/${pick.c.n}">
      <span class="hc-label">今日恭读</span>
      <span class="hc-main"><strong>${esc(pick.c.title)}</strong><em>《${esc(pick.s.title)}》· 约 ${Math.max(1, Math.round(pick.c.chars / 500))} 分钟</em></span>
      <span class="hc-go">恭读 ›</span></a>`;
  }

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
function saveOfflineMeta(m) { localStorage.setItem(OFF_META, JSON.stringify(m)); }
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
  updateDownloadBtn(); renderDownloads();
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
    updateDownloadBtn(); renderDownloads();
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
function renderDownloads() {
  const el = $('#wodeDownloads'); if (!el) return;
  const m = offlineMeta();
  const keys = Object.keys(m).sort((a, b) => (m[b].savedAt || 0) - (m[a].savedAt || 0));
  const dling = [...offlineDownloading].filter((k) => !m[k]);
  if (!keys.length && !dling.length) { el.innerHTML = ''; return; }
  let rows = keys.map((k) =>
    `<li data-dlplay="${esc(k)}" data-dlsid="${esc(m[k].sid)}">
        <span class="t">${esc(m[k].epTitle)}<small>《${esc(m[k].title)}》· ${((m[k].size || 0) / 1048576).toFixed(1)} MB</small></span>
        <button class="fav-del" data-dldel="${esc(k)}" aria-label="删除离线">✕</button></li>`).join('');
  rows += dling.map((k) => {
    const p = Math.round((offlineProgress[k] || 0) * 100);
    return `<li class="dl-ing"><span class="t">正在下载 …<small>${p}%</small></span></li>`;
  }).join('');
  el.innerHTML = `<h3 class="wode-sub">已下载 · 离线可听</h3><ol class="ep-list fav-list">${rows}</ol>
    <p class="dl-total">共 ${keys.length} 集 · ${(offlineTotal() / 1048576).toFixed(1)} MB ${iosOfflineHint()}</p>`;
}

function switchMode(m) {
  mode = m;
  document.body.dataset.mode = m;
  audio.loop = (m === 'nianfo');
  if (m !== 'od') { $('#mini').hidden = true; od = null; markPlayingRow(); }
  if (m === 'nianfo') setSleep(0);   // 念佛堂有自己的定课计时，睡眠定时让位
  if (m !== 'live') wantLive = false;
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
  localStorage.setItem('fy.miniExp', v ? '1' : '0');
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

function loadLive() {
  const { item } = station.liveAt(stationNow());
  const url = audioUrl(item.ep.bucket, item.ep.key);
  if (!audio.src.endsWith(url)) {
    audio.src = url;
    audio.playbackRate = 1;
  }
  seekPending = Math.max(0, stationNow() - item.start);
  // 同一集内续播时 loadedmetadata 不会再触发：元数据已就绪则立即跳到直播位置，
  // 否则 seekPending 卡住不清，既不同步又堵死 tick 的漂移校正
  if (audio.readyState >= 1) {
    try { audio.currentTime = seekPending; } catch { /* 忽略 */ }
    seekPending = null;
  }
  updateMediaSession(item.ep, '直播');
  audio.play().catch(() => { wantLive = false; hint('轻触莲台 · 与大众同闻'); });
}

function hint(text) { $('#liveHint').textContent = text; }

function backToLive() {
  switchMode('live');
  wantLive = true;
  loadLive();
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
    localStorage.setItem('fy.last', JSON.stringify({ sid: od.seriesId, idx: od.idx }));
  }
}

function currentRate() { return Number(localStorage.getItem('fy.rate') || '1'); }
function getProgress(key) { const v = localStorage.getItem('fy.p.' + key); return v ? Number(v) : 0; }

function saveProgress() {
  if (mode !== 'od' || !od || !od.progress) return;
  const ep = od.list[od.idx];
  if (audio.currentTime > 10 && audio.currentTime < ep.dur - 30) {
    localStorage.setItem('fy.p.' + ep.key, String(Math.floor(audio.currentTime)));
  } else if (audio.currentTime >= ep.dur - 30) {
    localStorage.removeItem('fy.p.' + ep.key);
  }
}

/* 收藏（当前点播集，仅存本机；清单见「我的」页） */
function favKey() { return (mode === 'od' && od) ? 'fy.fav.' + od.list[od.idx].key : null; }
function updateFav() { const k = favKey(); $('#btnFav').classList.toggle('on', !!(k && localStorage.getItem(k))); }
function toggleFav() {
  const k = favKey(); if (!k) return;
  if (localStorage.getItem(k)) localStorage.removeItem(k); else localStorage.setItem(k, '1');
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

function renderFavs() {
  const items = favList();
  const bks = library ? bkList() : [];
  let html = items.length
    ? '<h3 class="wode-sub">收藏 · 听经</h3><ol class="ep-list fav-list">' + items.map(({ s, i }) =>
      `<li data-fs="${s.id}" data-fi="${i}">
        <span class="t">${esc(s.episodes[i].title)}<small>《${esc(s.title)}》</small></span>
        <span class="d">${fmtDur(s.episodes[i].dur)}</span>
        <button class="fav-del" data-unfav="${esc(s.episodes[i].key)}" aria-label="移除收藏">✕</button></li>`).join('') + '</ol>'
    : '';
  html += bks.length
    ? '<h3 class="wode-sub">收藏 · 阅读</h3><ol class="ep-list fav-list">' + bks.map(({ spec, s, c }) =>
      `<li data-bkr="${spec}">
        <span class="t">${esc(c.title)}<small>《${esc(s.title)}》</small></span>
        ${chapProgLabel(c)}
        <button class="fav-del" data-unbk="${spec}" aria-label="移除收藏">✕</button></li>`).join('') + '</ol>'
    : '';
  $('#wodeFavs').innerHTML = html;
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
      html += `<button class="series-card" data-series="${s.id}">
        <strong>${esc(s.title)}</strong>
        <span>${s.count} 集 · ${fmtDur(s.totalDur)}</span>
        ${r ? `<span class="sc-resume">听至 第${r.idx + 1}集${r.saved ? ' · 续 ' + fmtMMSS(r.saved) : ''}</span>` : ''}</button>`;
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
      cards.push(`<button class="series-card" data-series="${s.id}">
        <strong>${esc(s.title)}</strong>
        <span>${esc(s.cat)} · ${s.count} 集 · ${fmtDur(s.totalDur)}</span></button>`);
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
    `<button class="series-card" data-wk="${s.id}">
      <strong>${esc(s.title)}</strong>
      <span>${s.count} 篇</span></button>`
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
  return `<span class="d">${Math.round(c.chars / 500)} 分钟</span>`;
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
  };
  localStorage.setItem('fy.lastRead', spec);   // 文库"继续阅读"用
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
  body.innerHTML = '<p class="reader-loading">恭请中 …</p>';
  $('#readLine').style.width = '0%';   // 换篇进度线归零
  applyReaderPrefs();
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
  if (arr.length) localStorage.setItem('fy.hl.' + path, JSON.stringify(arr));
  else localStorage.removeItem('fy.hl.' + path);
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
let toastT = 0;
function toast(text) {
  let el = $('#toast');
  if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastT);
  toastT = setTimeout(() => el.classList.remove('show'), 2600);
}

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
  const p = bjParts(Date.now());
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

  localStorage.setItem('fy.njOldImport', '1');
  saveNj();
}

function saveNj() {
  // 只保留最近 90 天明细
  const keys = Object.keys(nj.days).sort();
  for (const k of keys.slice(0, Math.max(0, keys.length - 90))) delete nj.days[k];
  localStorage.setItem('fy.nj', JSON.stringify(nj));
}

function njItem() { return nj.items.find((x) => x.id === nj.cur) || nj.items[0]; }
function njDayTotal(k) { const d = nj.days[k]; return d ? Object.values(d).reduce((a, b) => a + b, 0) : 0; }
function njGrandTotal() { return Object.values(nj.totals).reduce((a, b) => a + b, 0); }

function addNj(delta) {
  const k = bjDateKey();
  const day = nj.days[k] || (nj.days[k] = {});
  const cur = njItem().id;
  const t = day[cur] || 0;
  const d = Math.max(delta, -t); // 撤销不越过零
  if (d === 0) return;
  const dayBefore = njDayTotal(k);
  day[cur] = t + d;
  nj.totals[cur] = Math.max(0, (nj.totals[cur] || 0) + d);
  saveNj();
  renderCount();
  if (d > 0) {
    vibrate(12);
    // 满一串（108 声）：念珠脉冲 + 木鱼双响 + 加重震动
    if (Math.floor((t + d) / 108) > Math.floor(t / 108)) beadFull();
    // 定课圆满：当日总声数首次达标（跨功课合计）
    if (nj.goal > 0 && dayBefore < nj.goal && dayBefore + d >= nj.goal) goalDone();
  }
}

// 计数震动（默认开，功课中心可关；iOS 等不支持则静默）
function vibrate(pattern) {
  if (localStorage.getItem('fy.vib') !== '0' && navigator.vibrate) navigator.vibrate(pattern);
}

function beadFull() {
  vibrate([24, 60, 36]);
  playMuyu(); setTimeout(playMuyu, 160);
  const b = $('#btnBead');
  b.classList.remove('full');
  void b.offsetWidth;   // 重启动画
  b.classList.add('full');
}

function goalDone() {
  vibrate([40, 80, 60, 80, 90]);
  $('#gdOverlay').hidden = false;
}

function njStreak() {
  // 连续用功天数：今日未计则从昨日起算，不因"今天还没念"清零
  let n = 0;
  let i = njDayTotal(bjDateKey()) > 0 ? 0 : 1;
  for (; i < 400; i++) {
    const p = bjParts(Date.now() - i * 86400000);
    const k = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
    if (njDayTotal(k) > 0) n++; else break;
  }
  return n;
}

function renderCount() {
  // 念佛计数器（极简）：当前功课 + 大念珠今日声数 + 本串/定课 + 累计/连续摘要
  const it = njItem();
  const k = bjDateKey();
  const mine = (nj.days[k] || {})[it.id] || 0;
  const dayTotal = njDayTotal(k);
  $('#countName').textContent = it.name;
  $('#njToday').textContent = mine;
  const frac = (mine % 108) / 108;
  $('#njRing').style.strokeDasharray = String(RING_LEN);
  $('#njRing').style.strokeDashoffset = String(RING_LEN * (1 - frac));
  $('#countSub').textContent =
    `本串 ${mine % 108} / 108　·　${nj.goal ? `定课 ${dayTotal} / ${nj.goal}` : '未设定课'}`;
}

/* ── 木鱼音效（Web Audio 合成，无需音频文件） ── */
let _audioCtx = null;
function playMuyu() {
  if (localStorage.getItem('fy.muyu') !== '1') return;
  try {
    _audioCtx = _audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (_audioCtx.state === 'suspended') _audioCtx.resume();
    const t0 = _audioCtx.currentTime;
    const o = _audioCtx.createOscillator();
    const g = _audioCtx.createGain();
    o.type = 'triangle';
    o.frequency.setValueAtTime(640, t0);
    o.frequency.exponentialRampToValueAtTime(170, t0 + 0.07);
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(0.32, t0 + 0.004);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.17);
    o.connect(g).connect(_audioCtx.destination);
    o.start(t0); o.stop(t0 + 0.2);
  } catch { /* 无音频环境 */ }
}

/* ── 屏幕常亮（Wake Lock） ── */
let _wakeLock = null;
async function requestWake() {
  try { if ('wakeLock' in navigator) _wakeLock = await navigator.wakeLock.request('screen'); }
  catch { /* 被拒或不支持 */ }
}
async function releaseWake() { try { await _wakeLock?.release(); } catch { /* 忽略 */ } _wakeLock = null; }

/* ── 点击涟漪 ── */
function spawnBeadRipple(e) {
  const bead = $('#btnBead');
  const r = bead.getBoundingClientRect();
  const x = (e.clientX || r.left + r.width / 2) - r.left;
  const y = (e.clientY || r.top + r.height / 2) - r.top;
  const s = document.createElement('span');
  s.className = 'bead-ripple';
  s.style.left = x + 'px'; s.style.top = y + 'px';
  bead.appendChild(s);
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
    <button class="sheet-row" data-hub="huixiang"><span class="pr-main"><span>回向偈</span></span><span class="hub-go">›</span></button>
    <div class="hub-toggles">
      ${tg('fy.muyu', false, '木鱼音效')}
      ${tg('fy.wake', true, '屏幕常亮')}
      ${tg('fy.vib', true, '计数震动')}
    </div>`;
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
  const p90 = bjParts(Date.now() - 89 * 86400000);
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
  const dows = ['日', '一', '二', '三', '四', '五', '六'].map((d) => `<span class="cal-dow">${d}</span>`).join('');
  $('#cntSheetBody').innerHTML =
    `<div class="cal-nav"><button data-cal="-1" aria-label="上月">‹</button><strong>${y} 年 ${m} 月</strong><button data-cal="1" aria-label="下月">›</button></div>
    <div class="cal-grid">${dows}${cells}</div>
    <p class="cal-total">本月共 ${monthTotal.toLocaleString()} 声</p>` +
    (hasGone ? '<p class="cal-note">灰色日期的每日明细只保留 90 天 · 累计总数不受影响</p>' : '');
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
  for (const k of keys) localStorage.setItem(k, obj.data[k]);
  return keys.length;
}

async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; }
  catch {
    // 剪贴板 API 被拒时退回旧方案
    const ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { /* 忽略 */ }
    ta.remove();
    return ok;
  }
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
  $('#whStats').textContent = `累计 ${njGrandTotal().toLocaleString()} 声 · 连续 ${njStreak()} 日`
    + (rtMin > 0 ? ` · 今日恭读 ${rtMin} 分` : '');
  // 进度环：设了定课按定课走，未设按本串（108）走
  const frac = nj.goal ? Math.min(1, t / nj.goal) : (t % 108) / 108;
  const len = 2 * Math.PI * 32;
  $('#whRing').style.strokeDasharray = String(len);
  $('#whRing').style.strokeDashoffset = String(len * (1 - frac));
  const html = listenCardHtml('最近在听') + readCardHtml('最近在读');
  $('#wodeCards').innerHTML = html;
  $('#wodeTrail').hidden = !html;
  const hn = hlCount();
  $('#hlCount').textContent = hn ? `${hn} 处` : '';
  renderFavs();
  renderDownloads();
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

/* ================= 问道（文库 RAG） ================= */

function buildWenda() {
  $('#wdCorpus').textContent = library.chapterCount + library.qaCount;
}

function pathToHash(path) {
  if (path.startsWith('qa/')) return '#qa/' + Number(path.slice(3).replace('.txt', ''));
  const m = path.match(/^(\w+)\/(\d+)\.txt$/);
  return m ? `#read/${m[1]}/${Number(m[2])}` : '#wenku';
}

async function sendQuestion(q) {
  q = q.trim();
  if (!q || chat.streaming) return;
  chat.streaming = true;
  askCtrl = new AbortController();
  $('#wdInput').value = '';
  document.querySelector('.chat-input').classList.add('asking');   // 发送键变「停止」
  $('#chatStarters').hidden = true;

  chat.msgs.push({ role: 'user', content: q });
  saveChat();
  const log = $('#chatLog');
  log.insertAdjacentHTML('beforeend', `<div class="msg user"><p>${esc(q)}</p></div>`);
  log.insertAdjacentHTML('beforeend', '<div class="msg bot streaming"><p class="thinking">检索文库中 …</p></div>');
  const botDiv = log.lastElementChild;
  botDiv.scrollIntoView({ block: 'end' });

  let sources = [];
  let answer = '';
  // 回答落定：入历史 + 渲染 + 操作行（复制/分享）
  const settle = () => {
    botDiv.classList.remove('streaming');
    chat.msgs.push({ role: 'assistant', content: answer, sources });
    botDiv.dataset.mi = chat.msgs.length - 1;
    botDiv.innerHTML = renderAnswer(answer, sources, false) + ANS_ACTS;
    saveChat();
  };
  try {
    const history = chat.msgs.slice(-7, -1).map((m) => ({ role: m.role, content: m.content }));
    const res = await fetch('/api/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q, history }),
      signal: askCtrl.signal,
    });
    if (!res.ok) throw new Error(await res.text() || res.status);

    const rd = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await rd.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const frames = buf.split('\n\n');
      buf = frames.pop();
      for (const frame of frames) {
        const ev = frame.match(/^event: (\w+)/m)?.[1];
        const dataLine = frame.match(/^data: (.*)$/m)?.[1];
        if (!ev || !dataLine) continue;
        const data = JSON.parse(dataLine);
        if (ev === 'sources') {
          sources = data;
          // 检索阶段反馈：让人知道系统正翻文库
          botDiv.innerHTML = `<p class="thinking">已找到 ${sources.length} 篇相关开示，正在作答 …</p>`;
        } else if (ev === 'delta') {
          answer += data.text;
          botDiv.innerHTML = renderAnswer(answer, sources, true);
        }
      }
    }
    if (answer) settle();
    else {
      botDiv.classList.remove('streaming');
      botDiv.innerHTML = '<p>（未能生成回答，请换个问法）</p>';
      chat.msgs.pop();
      saveChat();
    }
  } catch (e) {
    if (answer) settle();   // 中途停止：保留已生成的部分
    else {
      botDiv.classList.remove('streaming');
      chat.msgs.pop();   // 失败的问题不入历史
      saveChat();
      botDiv.innerHTML = (e && e.name === 'AbortError')
        ? '<p class="thinking">已停止</p>'
        : `<p>${esc(String(e.message || '网络异常，请稍后再试').slice(0, 120))}</p>
           <button class="chat-retry" data-retry="${esc(q)}">重 试</button>`;
    }
  }
  chat.streaming = false;
  askCtrl = null;
  document.querySelector('.chat-input').classList.remove('asking');
}

// 引用按钮统一带出处数据（s=系列 t=篇名 x=摘录），点击弹出处预览不打断对话
const citeData = (s) =>
  `data-path="${esc(s.path)}" data-s="${esc(s.series)}" data-t="${esc(s.title)}" data-x="${esc(s.x || '')}"`;

function renderAnswer(text, sources, streaming) {
  // [n] → 出处引用角标；段落按空行/换行切分
  const cited = new Set();
  const paras = text.split(/\n+/).map((x) => x.trim()).filter(Boolean);
  const html = paras.map((p) => {
    let h = esc(p)
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')  // 最简 Markdown：仅处理加粗
      .replace(/^[#]+\s*/, '');                             // 丢弃标题井号
    h = h.replace(/\[(\d{1,2})\]/g, (_, n) => {
      const s = sources[Number(n) - 1];
      if (!s) return `[${n}]`;
      cited.add(Number(n));
      return `<button class="cite" ${citeData(s)} title="${esc(s.series + ' ' + s.title)}">${n}</button>`;
    });
    return `<p>${h}</p>`;
  }).join('');
  let srcs = '';
  const shown = sources.filter((s) => cited.has(s.n));
  const list = shown.length ? shown : (streaming ? [] : sources.slice(0, 3));
  if (list.length) {
    srcs = '<div class="src-list">' + list.map((s) =>
      `<button class="src" ${citeData(s)}>
        <span class="src-n">${s.n}</span>《${esc(s.series)}》${esc(s.title)}</button>`).join('') + '</div>';
  }
  return html + srcs;
}

// 每条回答尾部的操作行（纯图标：复制 / 分享）
const ANS_ACTS = `<div class="ans-acts">
  <button data-ans-copy aria-label="复制回答" title="复制">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="8.6" y="8.6" width="11" height="11" rx="2"/><path d="M15.4 5.4a2 2 0 0 0-2-2H6.4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2"/></svg>
  </button>
  <button data-ans-share aria-label="分享为长图" title="分享">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 14.5V3.6"/><path d="M8.2 7.2 12 3.5l3.8 3.7"/><path d="M8 10.5H5.5v10h13v-10H16"/></svg>
  </button>
</div>`;

// 对话持久化：刷新/换页回来还在；「新问」清空
function saveChat() {
  localStorage.setItem('fy.chat', JSON.stringify({ msgs: chat.msgs.slice(-40) }));
}
function loadChat() {
  try { chat.msgs = JSON.parse(localStorage.getItem('fy.chat')).msgs || []; } catch { chat.msgs = []; }
  if (!chat.msgs.length) return;
  $('#chatLog').innerHTML = chat.msgs.map((m, i) => m.role === 'user'
    ? `<div class="msg user"><p>${esc(m.content)}</p></div>`
    : `<div class="msg bot" data-mi="${i}">${renderAnswer(m.content, m.sources || [], false)}${ANS_ACTS}</div>`).join('');
  $('#chatStarters').hidden = true;
}

// 阅读时长明细只留近 7 天
function pruneRt() {
  const p = bjParts(Date.now() - 6 * 86400000);
  const cut = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
  const stale = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith('fy.rt.') && k.slice(6) < cut) stale.push(k);
  }
  for (const k of stale) localStorage.removeItem(k);
}

// 分享问答：复用法布施长图（问 + 答 + 依据篇目 + 二维码）
function shareAnswer(q, a, sources) {
  const seriesList = [...new Set((sources || []).map((s) => s.series).filter(Boolean))].slice(0, 2);
  const srcLine = seriesList.length
    ? `—— 依《${seriesList.join('》《')}》讲记开示`
    : '—— 依佛乐文库讲记开示';
  showPoster(makeQuotePoster({
    quote: trimQuote(`问：${q}\n\n${a}`, 800),
    srcLine,
    url: `${location.origin}/#wenda`,
  }));
}

/* ================= 分享（法布施） ================= */

let sharePayload = null;
let posterCv = null;

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
    url: `${location.origin}/#series/${od.seriesId}/${od.idx + 1}`,
    cta: '扫码恭听',
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
    url: `${location.origin}/${reader.shareHash}`,
    cta: '扫码恭读',
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
    cta: '扫码同闻',
    live: ep ? {
      series, ep: ep.title,
      block: liveItem.block ? liveItem.block.name : '',
      elapsed: Math.max(0, Math.min(ep.dur, stationNow() - liveItem.start)),
      dur: ep.dur,
      online: liveOnlineN,   // 真实在线数，0 不上海报
    } : null,
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

// 逐字换行（中文无空格），超出行数截断加省略号
function wrapLines(ctx, text, maxW, maxLines) {
  const out = [];
  let line = '';
  for (const ch of String(text)) {
    if (ch === '\n') { if (line) out.push(line); line = ''; continue; }
    if (line && ctx.measureText(line + ch).width > maxW) { out.push(line); line = ch; }
    else line += ch;
  }
  if (line) out.push(line);
  if (out.length > maxLines) {
    out.length = maxLines;
    out[maxLines - 1] = out[maxLines - 1].slice(0, -1) + '…';
  }
  return out;
}

// 二维码：直接落在宣纸底上（四周留白即静区），依赖 /js/qrcode.js 全局 qrcode（MIT）
function drawQR(ctx, text, x, y, size) {
  if (typeof window.qrcode !== 'function') return false;
  let qr;
  try {
    qr = window.qrcode(0, 'M');   // 0 = 按内容自动选型号
    qr.addData(text);
    qr.make();
  } catch { return false; }
  const n = qr.getModuleCount();
  const cell = Math.floor(size / n);   // 格宽取整保证边缘清晰
  const off = (size - cell * n) / 2;
  ctx.fillStyle = '#2a2216';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) ctx.fillRect(x + off + c * cell, y + off + r * cell, cell, cell);
    }
  }
  return true;
}

// 分享海报（极简）：大留白宣纸 + 细界栏 + 莲音标志 + 标题出处 + 二维码，750×1000，不落网址
function makePoster(p) {
  const W = 750, H = 1000;
  const cv = document.createElement('canvas');
  cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d');
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  // 繁体模式下海报文字同步转繁（canvas 不经 DOM 转换器）
  const T = (zhMap && zhTradOn()) ? ((s) => zhConv(s, zhMap)) : ((s) => s);

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = '#f4efe2';
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = 'rgba(166, 130, 60, 0.32)';
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 莲音标志（与站内同一标志：三瓣莲 + 法音涟漪）
  ctx.save();
  ctx.translate(W / 2 - 56, 138);
  ctx.scale(1.75, 1.75);
  ctx.strokeStyle = '#bd3a26';
  ctx.lineWidth = 2.6 / 1.75;
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  for (const d of [
    'M32 10 C38 18 38 28 32 36 C26 28 26 18 32 10 Z',
    'M18 18 C26 21 31 28 31 36 C23 34 18 27 18 18 Z',
    'M46 18 C38 21 33 28 33 36 C41 34 46 27 46 18 Z',
    'M14 43 C22 50 42 50 50 43',
    'M21 51 C27 56 37 56 43 51',
  ]) ctx.stroke(new Path2D(d));
  ctx.restore();

  // 标题（最多两行）与出处，居中大留白
  ctx.textAlign = 'center';
  ctx.fillStyle = '#33291b';
  ctx.font = `600 46px ${SERIF}`;
  const titleLines = wrapLines(ctx, T(p.title), W - 200, 2);
  let y = titleLines.length > 1 ? 430 : 462;
  for (const ln of titleLines) { ctx.fillText(ln, W / 2, y); y += 70; }
  ctx.fillStyle = '#a08b6b';
  ctx.font = `26px ${SERIF}`;
  ctx.fillText(T(p.source || p.sub), W / 2, y + 14);

  // 底部：裸二维码居中 + 品牌小字（不落网址）
  const qsize = 150;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, H - 322, qsize)) {
    ctx.fillStyle = '#8f6f2e';
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T(`${p.cta || '扫码同闻'} · 佛乐净土法音`), W / 2, H - 116);
  } else {
    // 二维码库未就绪：退回品牌小字
    ctx.fillStyle = '#8f6f2e';
    ctx.font = `24px ${SERIF}`;
    ctx.fillText(T('佛 乐 · 净 土 法 音'), W / 2, H - 150);
  }
  return cv;
}

// 圆角矩形路径（canvas 兼容旧 Safari，不依赖 ctx.roundRect）
function rrPath(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// 直播分享海报：手绘一张与直播莲台同款式的「播放器卡片」——
// 直播中标记 + 当下系列/集名 + 实时进度与已播时长 + 日期 +（有人时）在线人数 + 二维码
function makeLivePoster(p) {
  const W = 750, H = 1000;
  const cv = document.createElement('canvas');
  cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d');
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = (zhMap && zhTradOn()) ? ((s) => zhConv(s, zhMap)) : ((s) => s);
  const lv = p.live;

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = '#f4efe2';
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = 'rgba(166, 130, 60, 0.32)';
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 莲音标志
  ctx.save();
  ctx.translate(W / 2 - 48, 74);
  ctx.scale(1.5, 1.5);
  ctx.strokeStyle = '#bd3a26';
  ctx.lineWidth = 2.6 / 1.5;
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  for (const d of [
    'M32 10 C38 18 38 28 32 36 C26 28 26 18 32 10 Z',
    'M18 18 C26 21 31 28 31 36 C23 34 18 27 18 18 Z',
    'M46 18 C38 21 33 28 33 36 C41 34 46 27 46 18 Z',
    'M14 43 C22 50 42 50 50 43',
    'M21 51 C27 56 37 56 43 51',
  ]) ctx.stroke(new Path2D(d));
  ctx.restore();

  // 播放器卡片（仿站内 .live-card 的圆角卡）
  const cx = 64, cy = 212, cw = W - 128, ch = 430;
  rrPath(ctx, cx, cy, cw, ch, 26);
  ctx.fillStyle = '#fbf7ec';
  ctx.fill();
  ctx.strokeStyle = '#e2d5b6';
  ctx.stroke();

  // 「直播中」胶囊（朱砂点 + 时段名）
  ctx.font = `24px ${SERIF}`;
  const chipText = T(lv && lv.block ? `直播中 · ${lv.block}` : '直播中');
  const tw = ctx.measureText(chipText).width;
  const pw = tw + 64, px = W / 2 - pw / 2, py = cy + 44;
  rrPath(ctx, px, py, pw, 44, 22);
  ctx.fillStyle = 'rgba(189, 58, 38, 0.08)';
  ctx.fill();
  ctx.strokeStyle = 'rgba(189, 58, 38, 0.35)';
  ctx.stroke();
  ctx.fillStyle = '#bd3a26';
  ctx.beginPath();
  ctx.arc(px + 24, py + 22, 5, 0, Math.PI * 2);
  ctx.fill();
  ctx.textAlign = 'left';
  ctx.fillText(chipText, px + 40, py + 31);

  // 系列名（大字，最多两行）与集名
  ctx.textAlign = 'center';
  ctx.fillStyle = '#33291b';
  ctx.font = `600 40px ${SERIF}`;
  const titleLines = lv ? wrapLines(ctx, T(`《${lv.series}》`), cw - 110, 2) : [T('二十四时 · 佛号讲经不断')];
  let y = titleLines.length > 1 ? cy + 158 : cy + 172;
  for (const ln of titleLines) { ctx.fillText(ln, W / 2, y); y += 56; }
  ctx.fillStyle = '#6b5d42';
  ctx.font = `26px ${SERIF}`;
  if (lv) { ctx.fillText(T(lv.ep), W / 2, y + 8); y += 8; }

  // 实时进度条 + 已播/总长
  if (lv && lv.dur > 0) {
    const bx = cx + 82, bw = cw - 164, by = cy + 306;
    rrPath(ctx, bx, by, bw, 6, 3);
    ctx.fillStyle = '#e5d9bd';
    ctx.fill();
    const frac = Math.min(1, lv.elapsed / lv.dur);
    if (frac > 0.01) {
      rrPath(ctx, bx, by, Math.max(8, bw * frac), 6, 3);
      ctx.fillStyle = '#bd3a26';
      ctx.fill();
    }
    ctx.fillStyle = '#a08b6b';
    ctx.font = `22px ${SERIF}`;
    ctx.textAlign = 'left';
    ctx.fillText(fmtMMSS(lv.elapsed), bx, by + 40);
    ctx.textAlign = 'right';
    ctx.fillText(fmtMMSS(lv.dur), bx + bw, by + 40);
  }

  // （有人同闻时）真实在线人数 + 日期行
  const dp = bjParts(Date.now());
  ctx.textAlign = 'center';
  if (lv && lv.online > 0) {
    ctx.fillStyle = '#bd3a26';
    ctx.font = `23px ${SERIF}`;
    ctx.fillText(T(`${lv.online} 位同修在此同闻`), W / 2, cy + 384);
  }
  ctx.fillStyle = '#a08b6b';
  ctx.font = `22px ${SERIF}`;
  ctx.fillText(
    T(`${dp.y}年${dp.mo}月${dp.d}日 · 周${WEEK[dp.day]} · 北京时间 ${String(dp.h).padStart(2, '0')}:${String(dp.mi).padStart(2, '0')}`),
    W / 2, cy + 418);

  // 底部：二维码 + 扫码同闻
  const qsize = 150;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, 700, qsize)) {
    ctx.fillStyle = '#8f6f2e';
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T('扫码同闻 · 佛乐净土法音'), W / 2, 700 + qsize + 44);
  } else {
    ctx.fillStyle = '#8f6f2e';
    ctx.font = `24px ${SERIF}`;
    ctx.fillText(T('佛 乐 · 净 土 法 音'), W / 2, 790);
  }
  return cv;
}

// 选文截到上限：超长时收在最近的句读处，避免拦腰截断
function trimQuote(text, max) {
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  let end = -1;
  for (const m of cut.matchAll(/[。！？；：」』]/g)) end = m.index;
  return end > max * 0.5 ? cut.slice(0, end + 1) : cut.slice(0, max - 1) + '…';
}

// 分享法布施长图：宽 750，高度随内容伸缩。纯内容排版（不落标识）：正文分段 + 出处 + 二维码
function makeQuotePoster(p) {
  const W = 750;
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = (zhMap && zhTradOn()) ? ((s) => zhConv(s, zhMap)) : ((s) => s);
  const bodyFont = `31px ${SERIF}`;
  const lineH = 58, paraGap = 30, bodyX = 85, bodyW = W - 170;

  // 先离屏排版量高，再按内容高度生成正式画布（canvas 改尺寸会清空，需两步）
  const mc = document.createElement('canvas').getContext('2d');
  mc.font = bodyFont;
  const paras = T(p.quote).split('\n').map((x) => x.trim()).filter(Boolean)
    .map((para) => wrapLines(mc, para, bodyW, 99));
  const bodyH = paras.reduce((h, ls) => h + ls.length * lineH, 0) + (paras.length - 1) * paraGap;
  const bodyY = 158;                  // 顶部大留白直接进正文，不设标识
  const srcY = bodyY + bodyH + 60;    // 出处行（右缩）
  const qrY = srcY + 64;              // 二维码
  const H = Math.max(860, qrY + 140 + 44 + 84);

  const cv = document.createElement('canvas');
  cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d');

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = '#f4efe2';
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = 'rgba(166, 130, 60, 0.32)';
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 正文：左起、按原文分段，行距疏朗贴近阅读器排版
  ctx.textAlign = 'left';
  ctx.fillStyle = '#33291b';
  ctx.font = bodyFont;
  let y = bodyY;
  for (const lines of paras) {
    for (const ln of lines) { ctx.fillText(ln, bodyX, y); y += lineH; }
    y += paraGap;
  }

  // 出处：右缩排，上方一道细金线呼应正文收束
  ctx.strokeStyle = 'rgba(166, 130, 60, 0.4)';
  ctx.beginPath();
  ctx.moveTo(W - bodyX - 120, srcY - 34);
  ctx.lineTo(W - bodyX, srcY - 34);
  ctx.stroke();
  ctx.textAlign = 'right';
  ctx.fillStyle = '#a08b6b';
  ctx.font = `24px ${SERIF}`;
  const src = wrapLines(ctx, T(p.srcLine || `—— ${p.source || p.sub} · ${p.title}`), bodyW, 1)[0] || '';
  ctx.fillText(src, W - bodyX, srcY);

  // 底部：二维码 + 「扫码查询原文出处」
  ctx.textAlign = 'center';
  const qsize = 140;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, qrY, qsize)) {
    ctx.fillStyle = '#8f6f2e';
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T('扫码查询原文出处'), W / 2, qrY + qsize + 44);
  }
  return cv;
}

// 海报统一出口：填预览图并按设备能力显示「分享至社交软件」
function showPoster(cv) {
  posterCv = cv;
  $('#posterImg').src = cv.toDataURL('image/png');
  let canShare = false;
  try {
    canShare = !!(navigator.canShare
      && navigator.canShare({ files: [new File([''], 'x.png', { type: 'image/png' })] }));
  } catch { /* 不支持 files 分享 */ }
  $('#posterShare').hidden = !canShare;
  $('#posterOverlay').hidden = false;
}

/* ================= 直播弹幕 =================
   与「同修在此」同一数据源：轮询到的新留言排队错峰飘过莲台卡，
   自己发送的经轮询立即上屏；开关记在 fy.dm，默认开。 */

let dmOn = localStorage.getItem('fy.dm') !== '0';
let dmQueue = [];
let dmTimer = 0;
let dmLane = 0;

function dmSet(on) {
  dmOn = on;
  localStorage.setItem('fy.dm', on ? '1' : '0');
  $('#btnDm').classList.toggle('on', on);
  if (!on) dmClear();
}

function dmClear() {
  dmQueue = [];
  if (dmTimer) { clearTimeout(dmTimer); dmTimer = 0; }
  $('#dmLayer').innerHTML = '';
}

function dmPush(texts) {
  if (!dmOn || document.body.dataset.view !== 'live' || !texts.length) return;
  dmQueue.push(...texts);
  if (dmQueue.length > 40) dmQueue = dmQueue.slice(-40);   // 积压只留最近
  if (!dmTimer) dmDrain();
}

function dmDrain() {
  if (!dmOn || !dmQueue.length || document.body.dataset.view !== 'live') { dmTimer = 0; return; }
  const text = dmQueue.shift();
  if (!document.hidden && !chatOpen) dmSpawn(text);   // 后台标签/聊天室全屏时不飘也不积压
  // 错峰：批量到达的留言摊开飘，不挤成一团
  dmTimer = setTimeout(dmDrain, 1300 + Math.random() * 1700);
}

function dmSpawn(text) {
  const layer = $('#dmLayer');
  const el = document.createElement('span');
  el.className = 'dm-item';
  el.textContent = text;
  layer.appendChild(el);
  const W = layer.clientWidth;
  const w = el.offsetWidth;
  el.style.top = `${6 + (dmLane % 4) * 24}%`;
  el.style.transform = `translateX(${W}px)`;
  dmLane += 1;
  const anim = el.animate(
    [{ transform: `translateX(${W}px)` }, { transform: `translateX(${-w - 24}px)` }],
    { duration: (W + w) * 16, easing: 'linear' });   // 约 62px/秒，匀速横过
  anim.onfinish = () => el.remove();
  anim.oncancel = () => el.remove();
}

/* ================= 直播留言（同修在此） ================= */

let cmtLastId = 0;
let cmtLastTs = 0;      // 上一条留言时间：超过 10 分钟插一枚时间戳（微信式）
let cmtTimer = 0;
let cmtBusy = false;
let chatOpen = false;   // 聊天室全屏层是否打开（开着时轮询加密到 8 秒）
let liveOnlineN = 0;    // 最近一次真实在线人数（直播海报用）

// 本机匿名设备标识（封禁用）与自动法名（莲友·两字清净名）
function devId() {
  let d = localStorage.getItem('fy.dev');
  if (!d) {
    d = crypto.randomUUID ? crypto.randomUUID()
      : 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('fy.dev', d);
  }
  return d;
}
function dharmaName() {
  let n = localStorage.getItem('fy.fname');
  if (!n) {
    const A = ['静', '慧', '明', '安', '和', '清', '悟', '善', '慈', '定', '莲', '净', '朗', '素', '澄', '恒'];
    n = '莲友·' + A[Math.floor(Math.random() * A.length)] + A[Math.floor(Math.random() * A.length)];
    localStorage.setItem('fy.fname', n);
  }
  return n;
}

async function pollCmt() {
  if (document.body.dataset.view !== 'live') return;
  try {
    const qs = new URLSearchParams({ dev: devId() });   // 附带设备标识：拉留言 + 在线心跳 + 标记自己的发言
    if (cmtLastId) qs.set('after', cmtLastId);
    const r = await fetch('/api/cmt?' + qs.toString());
    if (!r.ok) return;
    const d = await r.json();
    setLiveOnline(d.online);
    const notice = (d.notice || '').trim();
    $('#liveNotice').textContent = notice;
    $('#liveNotice').hidden = !notice;
    if (d.items && d.items.length) {
      const list = $('#cmtList');
      const first = !cmtLastId;
      if (first) { list.innerHTML = ''; cmtLastTs = 0; }
      cmtLastId = d.items[d.items.length - 1].id;
      // 微信式追加：跨 10 分钟插时间戳；已翻看历史（不在底部）时不打扰滚动位置
      const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 90;
      let html = '';
      for (const c of d.items) {
        if (c.ts - cmtLastTs > 600000) html += cmtTimeHtml(c.ts);
        cmtLastTs = c.ts;
        html += cmtRowHtml(c);
      }
      list.insertAdjacentHTML('beforeend', html);
      while (list.children.length > 160) list.firstChild.remove();   // 只留最近，防无限增长
      if (first || nearBottom) list.scrollTop = list.scrollHeight;
      // 弹幕：新留言全部上屏；首次进页只取最近两条作氛围，不回放历史
      dmPush((first ? d.items.slice(-2) : d.items).map((c) => c.text));
    }
  } catch { /* 网络波动静默，下轮再试 */ }
}
// 时间戳分隔（北京时间）：今日只显时分，往日带月日
function cmtTimeHtml(ts) {
  const p = bjParts(ts);
  const hm = `${String(p.h).padStart(2, '0')}:${String(p.mi).padStart(2, '0')}`;
  const key = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
  return `<p class="lc-time">${key === bjDateKey() ? hm : `${p.mo}月${p.d}日 ${hm}`}</p>`;
}
// 一行留言气泡：法名取「·」后首字作莲印；自己的发言靠右朱砂气泡（服务端按设备标识判定）
function cmtRowHtml(c) {
  const dn = c.name.includes('·') ? c.name.split('·').pop() : c.name;
  const av = esc([...String(dn)][0] || '莲');
  return `<div class="lc-row${c.mine ? ' mine' : ''}"><span class="lc-av" aria-hidden="true">${av}</span>`
    + `<span class="lc-msg"><b>${esc(c.name)}</b><span>${esc(c.text)}</span></span></div>`;
}
// 同时在线人数：真实心跳统计，0 人时不显示（直播莲台 + 聊天室头部同步）
function setLiveOnline(n) {
  n = Number(n) || 0;
  liveOnlineN = n;
  const box = $('#liveOnline');
  if (box) {
    if (n > 0) { $('#liveOnlineN').textContent = n; box.hidden = false; }
    else box.hidden = true;
  }
  $('#crSub').textContent = n > 0 ? `${n} 位同修在此 · 敬请爱语` : '以法相会 · 敬请爱语';
}
function startCmt() { $('#cmtWho').textContent = dharmaName(); pollCmt(); setCmtCadence(chatOpen); }
function stopCmt() { if (cmtTimer) { clearInterval(cmtTimer); cmtTimer = 0; } setLiveOnline(0); }
// 轮询节奏：聊天室开着 8 秒近实时，关着 30 秒（喂弹幕与在线数即可）
function setCmtCadence(fast) {
  if (cmtTimer) clearInterval(cmtTimer);
  cmtTimer = setInterval(pollCmt, fast ? 8000 : 30000);
}

/* ── 聊天室全屏层（直播页「留言」进入） ── */
function openChatRoom() {
  chatOpen = true;
  $('#chatRoom').hidden = false;
  $('#cmtWho').textContent = dharmaName();
  const list = $('#cmtList');
  list.scrollTop = list.scrollHeight;
  pollCmt();
  setCmtCadence(true);
}
function closeChatRoom() {
  if (!chatOpen) return;
  chatOpen = false;
  $('#chatRoom').hidden = true;
  if (cmtTimer) setCmtCadence(false);   // 仍在直播页：回落慢轮询
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
        ep: liveItem ? `${liveItem.ep.seriesTitle}·${liveItem.ep.title}` : '',
      }),
    });
    if (r.ok) { input.value = ''; await pollCmt(); }
    else $('#cmtNote').textContent = (await r.text()) || '发送失败，请稍后再试';
  } catch { $('#cmtNote').textContent = '网络不畅，请稍后再试'; }
  cmtBusy = false;
  $('#btnCmtSend').disabled = false;
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
function renameDharma() {
  const cur = dharmaName();
  const v = window.prompt('修改法名（只存本机，2–12 字）', cur);
  if (v == null) return;
  const name = v.replace(/\s+/g, ' ').trim().slice(0, 12);
  if (name.length < 2) { toast('法名至少 2 字'); return; }
  localStorage.setItem('fy.fname', name);
  $('#cmtSheetName').textContent = name;
  $('#cmtWho').textContent = name;
  toast('已改名 · ' + name);
}

/* ================= 事件 ================= */

function bindEvents() {
  window.addEventListener('hashchange', route);

  document.querySelectorAll('.back-btn[data-back]').forEach((b) =>
    b.addEventListener('click', () => { location.hash = b.dataset.back; }));
  $('#btnSeriesBack').addEventListener('click', () => { location.hash = $('#btnSeriesBack').dataset.back || '#ting'; });

  // 续听卡（首页 / 我的共用）
  const resumeListen = (e) => {
    if (!e.target.closest('[data-resume-listen]')) return;
    try {
      const last = JSON.parse(localStorage.getItem('fy.last'));
      const s = catalog.series.find((x) => x.id === last.sid);
      if (s && s.episodes[last.idx]) playEpisode(s, last.idx);
    } catch { /* 忽略 */ }
  };
  $('#homeCards').addEventListener('click', resumeListen);
  $('#wodeCards').addEventListener('click', resumeListen);

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

  // 直播
  $('#btnLive').addEventListener('click', () => {
    if (mode !== 'live') backToLive();
    else if (audio.paused) { wantLive = true; loadLive(); hint('正与大众同闻'); }
    else { audio.pause(); wantLive = false; hint('已暂停 · 轻触回到直播'); }
  });

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
      if (od.progress) localStorage.removeItem('fy.p.' + ep.key);
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
    if (mode === 'live' && wantLive) setTimeout(loadLive, 4000);
    else if (mode === 'od' && od) {
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
  // 缓冲与恢复反馈
  audio.addEventListener('waiting', () => playStatus('缓冲中 …'));
  audio.addEventListener('stalled', () => playStatus('缓冲中 …'));
  audio.addEventListener('playing', () => playStatus(''));
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
    if (card) { location.hash = '#series/' + card.dataset.series; return; }
    const li = e.target.closest('li[data-fs]');
    if (!li) return;
    const s = catalog.series.find((x) => x.id === li.dataset.fs);
    if (s) playEpisode(s, Number(li.dataset.fi));
  });

  // 我的 · 收藏清单：听经点行播放，阅读点行恭读，✕ 移除
  $('#wodeFavs').addEventListener('click', (e) => {
    const del = e.target.closest('[data-unfav]');
    if (del) {
      localStorage.removeItem('fy.fav.' + del.dataset.unfav);
      renderFavs();
      updateFav();   // 正在播放这集时同步播放器收藏态
      return;
    }
    const unbk = e.target.closest('[data-unbk]');
    if (unbk) {
      localStorage.removeItem('fy.bk.' + unbk.dataset.unbk);
      renderFavs();
      return;
    }
    const bkr = e.target.closest('li[data-bkr]');
    if (bkr) { location.hash = '#read/' + bkr.dataset.bkr; return; }
    const li = e.target.closest('li[data-fs]');
    if (!li) return;
    const s = catalog.series.find((x) => x.id === li.dataset.fs);
    if (s) playEpisode(s, Number(li.dataset.fi));
  });

  // 我的 · 已下载：点条目离线播放，点 ✕ 删除
  $('#wodeDownloads').addEventListener('click', (e) => {
    const del = e.target.closest('[data-dldel]');
    if (del) {
      removeOffline(del.dataset.dldel).then(() => { renderDownloads(); updateDownloadBtn(); toast('已删除离线文件'); });
      return;
    }
    const li = e.target.closest('li[data-dlplay]');
    if (!li) return;
    const s = catalog.series.find((x) => x.id === li.dataset.dlsid);
    if (!s) { toast('该系列已更新，请重新下载'); return; }
    const i = s.episodes.findIndex((x) => x.key === li.dataset.dlplay);
    if (i >= 0) playEpisode(s, i);
  });
  hydrateOfflineURLs();   // 启动即把已下载 blob 建成可用的 objectURL（离线亦可）

  // 听经台 / 有声书 / 系列
  $('#tingGroups').addEventListener('click', seriesCardClick);
  $('#shuGroups').addEventListener('click', seriesCardClick);
  function seriesCardClick(e) {
    const card = e.target.closest('.series-card');
    if (card) location.hash = '#series/' + card.dataset.series;
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
    if (card) location.hash = '#wkseries/' + card.dataset.wk;
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
      localStorage.setItem('fy.zenTip', '1');
      toast('已进入沉浸阅读 · 轻触正文恢复');
    }
  });

  // 收藏本篇（书签）
  $('#btnBookmark').addEventListener('click', () => {
    if (!reader.sid) return;
    const k = 'fy.bk.' + reader.bkSpec;
    if (localStorage.getItem(k)) { localStorage.removeItem(k); toast('已取消收藏'); }
    else { localStorage.setItem(k, '1'); toast('已收藏 · 「我的」页可回看'); }
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
  $('#btnMyHl').addEventListener('click', async () => {
    try { await ensureLibrary(); } catch { /* 离线时仍展示可解析的部分 */ }
    renderHlSheet();
    openCntSheet('myhl', '我的划线');
  });

  // 文库标题搜索：即时过滤全库篇目
  $('#wkSearch').addEventListener('input', () => {
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
  });
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
    localStorage.setItem('fy.rp.' + reader.path,
      JSON.stringify({ p, pct: Math.round(pct * 1000) / 1000 }));
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

  // 念佛计数器：大念珠（涟漪 + 木鱼 + 计一声）· 十念 · 撤销 · 重置
  $('#btnBead').addEventListener('click', (e) => { spawnBeadRipple(e); playMuyu(); addNj(1); });
  $('#btnTen').addEventListener('click', () => addNj(10));
  $('#btnUndo').addEventListener('click', () => addNj(-1));
  $('#btnReset').addEventListener('click', () => {
    // 重置＝当前功课今日归零（累计同步扣除今日声数），须确认
    const it = njItem();
    const mine = (nj.days[bjDateKey()] || {})[it.id] || 0;
    if (!mine) { toast('「' + it.name + '」今日尚未计数'); return; }
    if (!window.confirm(`将「${it.name}」今日 ${mine} 声清零？\n累计将同步扣除这 ${mine} 声。`)) return;
    addNj(-mine);
    toast('今日计数已清零');
  });

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
        const p = bjParts(Date.now());
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
          const n = restoreBackup(code);
          msg(`已恢复 ${n} 项数据，即将刷新 …`);
          setTimeout(() => location.reload(), 900);
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
      localStorage.setItem('fy.' + k, v);
      applyReaderPrefs();
      renderRdSetSheet();
    } else if (cntSheetMode === 'storage') {
      if (e.target.closest('[data-offline-clear]')) {
        if (!window.confirm('清空全部离线音频？已下载的集将需要重新下载。')) return;
        clearAllOffline().then(() => { renderStorageSheet(); renderDownloads(); updateDownloadBtn(); toast('已清空离线音频'); });
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
        localStorage.setItem(key, on ? '0' : '1');
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
        const p = bjParts(Date.now());
        calYM = { y: p.y, m: p.mo };
        renderCalendar(); openCntSheet('history', '念佛历史');
      } else if (nav.dataset.hub === 'huixiang') {
        closeCntSheet();
        $('#hxOverlay').hidden = false;
      }
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

  // 莲友聊天室（直播页「留言」进入的全屏层）
  $('#btnLiveChat').addEventListener('click', openChatRoom);
  $('#chatRoomX').addEventListener('click', closeChatRoom);

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
    showPoster(makeQuotePoster(sharePayload));
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
    showPoster(sharePayload.kind === 'live' ? makeLivePoster(sharePayload) : makePoster(sharePayload));
  });
  $('#posterClose').addEventListener('click', () => { $('#posterOverlay').hidden = true; });
  $('#posterOverlay').addEventListener('click', (e) => { if (e.target === $('#posterOverlay')) $('#posterOverlay').hidden = true; });
  // 分享至社交软件：走系统分享面板（微信等均在其中）；不支持的环境按钮不显示
  $('#posterShare').addEventListener('click', () => {
    if (!posterCv) return;
    posterCv.toBlob((blob) => {
      const file = new File([blob], 'foyue-share.png', { type: 'image/png' });
      navigator.share({ files: [file] }).catch(() => { /* 用户取消 */ });
    });
  });
  $('#posterSave').addEventListener('click', () => {
    if (!posterCv) return;
    posterCv.toBlob((blob) => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'foyue-share.png';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    });
  });

  // 外观设置
  $('#themeChips').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-theme]');
    if (!b) return;
    localStorage.setItem('fy.theme', b.dataset.theme);
    applyThemePref();
  });

  // 语言：简体 / 繁體 / English / 日本語
  $('#langChips').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-lang]');
    if (!b) return;
    const cur = getLang();
    const next = b.dataset.lang;
    if (next === cur) return;
    localStorage.setItem('fy.lang', next);
    localStorage.setItem('fy.zh', next === 't' ? 't' : 's');   // 兼容旧键
    applyLangChips(next);
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
    if (chat.streaming) { askCtrl?.abort(); return; }
    sendQuestion($('#wdInput').value);
  });
  $('#wdInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendQuestion($('#wdInput').value); }
  });
  $('#chatStarters').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (b) sendQuestion(b.textContent);
  });
  $('#btnChatNew').addEventListener('click', () => {
    if (chat.streaming) return;
    if (chat.msgs.length && !window.confirm('开始新的一问？当前对话将清空。')) return;
    chat.msgs = [];
    saveChat();
    $('#chatLog').innerHTML = '';
    $('#chatStarters').hidden = false;
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
    const m = chat.msgs[mi];
    if (!m) return;
    const qText = chat.msgs[mi - 1]?.content || '';
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
    localStorage.setItem('foyue_playmode_v1', playMode);
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
        removeOffline(key).then(() => { updateDownloadBtn(); renderDownloads(); toast('已删除离线文件'); });
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
    localStorage.setItem('fy.rate', String(next));
    audio.playbackRate = next;
    $('#rateVal').textContent = `${next}×`;
  });
  const cycleSleep = () => setSleep(SLEEP_MINS[(SLEEP_MINS.indexOf(sleepT.min) + 1) % SLEEP_MINS.length]);
  $('#btnSleep').addEventListener('click', cycleSleep);
  $('#btnLiveSleep').addEventListener('click', cycleSleep);

  // 弹幕开关 + 随喜此刻节目（直播工具行）
  $('#btnDm').classList.toggle('on', dmOn);
  $('#btnDm').addEventListener('click', () => dmSet(!dmOn));
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
    artwork: [{ src: '/icon-512.png', sizes: '512x512', type: 'image/png' }],
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

/* ================= 工具 ================= */

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

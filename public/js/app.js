// 佛乐 · 主应用
// 底部导航：首页（今日案头）/ 听经（听经台·有声书·佛号）/ 文库（阅读站）/ 我的（数珠计数）
// 问道（文库RAG问答）在顶栏右上角
// 播放模式：live 直播（确定性排播全网同步）/ od 点播（进度记忆）/ nianfo 佛号（循环+定课）

import {
  createStation, stationNow, fmtClock, fmtDur, fmtMMSS, bjParts,
} from './station.js';
import { SERIES_INTROS } from './intros.js';

const $ = (s) => document.querySelector(s);
const audio = $('#audio');
const WEEK = ['日', '一', '二', '三', '四', '五', '六'];
const RATES = [1, 1.25, 1.5, 1.75, 2, 0.75];
const FONT_SIZES = [17, 19, 21, 24];
const TING_CATS = ['讲经', '讲座', '问答', '诗偈'];
const SHU_CATS = ['有声书', '传记', '故事'];
const RING_LEN = 2 * Math.PI * 54; // 数珠进度环周长

let catalog = null, library = null, qaData = null;
let station = null;
let mode = 'live';          // live | od | nianfo
let liveItem = null;
let wantLive = false;
let od = null;              // 点播状态 { title, list, idx, progress, seriesId, bucket }
let schedDay = 0;
let seekPending = null;
let lastSaved = 0;
let seekDragging = false;
let nf = { tracks: [], idx: 0, timerMin: 0, deadline: null };
let odSleep = { min: 0, deadline: null };   // 点播睡眠定时
const SLEEP_MINS = [0, 15, 30, 60];
let miniExpanded = localStorage.getItem('fy.miniExp') !== '0';   // 播放条两态，记住用户偏好
let nj = { total: 0, days: {} };   // 念佛计数
let reader = { chapters: null, idx: 0, path: null, backHash: '#wenku' };
let pendingReaderBack = null;      // 从问道引用跳转阅读时，返回键回问道
let allChapters = null;            // 文库全部篇目（今日恭读用）
let chat = { msgs: [], streaming: false };

init();

async function init() {
  const [c, l, q] = await Promise.all([
    fetch('/catalog.json').then(r => r.json()),
    fetch('/library.json').then(r => r.json()),
    fetch('/qa.json').then(r => r.json()),
  ]);
  catalog = c; library = l; qaData = q;
  station = createStation(catalog);

  loadNj();
  buildTing();
  buildShu();
  buildWenku();
  buildFohao();
  buildWenda();
  buildHome();
  applyThemePref();
  bindEvents();
  route();
  tick();
  setInterval(tick, 1000);
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

/* ================= 路由 ================= */

function setSeg(s) { document.body.dataset.seg = s; }

function route() {
  const h = location.hash || '#home';
  let view = 'home', tab = 'home';
  if (h.startsWith('#home')) { view = 'home'; tab = 'home'; buildHome(); }
  else if (h.startsWith('#ting')) { view = 'ting'; tab = 'ting'; setSeg('ting'); buildTing(); }
  else if (h.startsWith('#shu')) { view = 'ting'; tab = 'ting'; setSeg('shu'); buildShu(); }
  else if (h.startsWith('#fohao')) { view = 'ting'; tab = 'ting'; setSeg('fohao'); buildFohao(); }
  else if (h.startsWith('#series/')) { view = 'series'; tab = 'ting'; openSeries(h.slice(8)); }
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
  if (view === 'count') { updateCntTools(); if (localStorage.getItem('fy.wake') === '1') requestWake(); }
  else if (_wakeLock) releaseWake();
  document.body.dataset.view = view;
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

  // 点播睡眠定时：到点轻轻暂停
  if (mode === 'od' && odSleep.deadline) {
    if (Date.now() >= odSleep.deadline) { audio.pause(); setSleep(0); }
    else $('#sleepVal').textContent = `${Math.ceil((odSleep.deadline - Date.now()) / 60000)}分`;
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

function buildHome() {
  // 继续收听（若有未听完）
  let html = listenCardHtml('继续收听');

  // 四门宫格（听经 / 有声书 / 念佛 / 阅读）
  html += '<div class="home-grid">' + HOME_DOORS.map((d) =>
    `<a class="grid-card" href="${d.href}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${d.icon}</svg>
      <strong>${d.name}</strong><span>${esc(d.sub)}</span></a>`).join('') + '</div>';

  // 今日恭读（每日轮选一篇讲记）
  const pick = dailyPick();
  html += `<a class="home-card" href="#read/${pick.s.id}/${pick.c.n}">
    <span class="hc-label">今日恭读</span>
    <span class="hc-main"><strong>${esc(pick.c.title)}</strong><em>《${esc(pick.s.title)}》· 约 ${Math.max(1, Math.round(pick.c.chars / 500))} 分钟</em></span>
    <span class="hc-go">恭读 ›</span></a>`;

  $('#homeCards').innerHTML = html;
}

/* ================= 播放底层 ================= */

function audioUrl(bucket, key) {
  return `/audio/${bucket}/` + key.split('/').map(encodeURIComponent).join('/');
}

function switchMode(m) {
  mode = m;
  document.body.dataset.mode = m;
  audio.loop = (m === 'nianfo');
  if (m !== 'od') { $('#mini').hidden = true; od = null; markPlayingRow(); setSleep(0); }
  if (m !== 'live') wantLive = false;
}

function setSleep(min) {
  odSleep = { min, deadline: min > 0 ? Date.now() + min * 60000 : null };
  $('#sleepVal').textContent = min > 0 ? `${min}分` : '定时';
  $('#btnSleep').classList.toggle('on', min > 0);
}

function setMiniExpanded(v) {
  miniExpanded = v;
  localStorage.setItem('fy.miniExp', v ? '1' : '0');
  $('#mini').classList.toggle('collapsed', !v);
}

function closeOd() {
  // 关闭点播条：存进度、停播、回到直播待机（不自动开播）
  saveProgress();
  audio.pause();
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
}

function loadLive() {
  const { item } = station.liveAt(stationNow());
  const url = audioUrl(item.ep.bucket, item.ep.key);
  if (!audio.src.endsWith(url)) {
    audio.src = url;
    audio.playbackRate = 1;
  }
  seekPending = Math.max(0, stationNow() - item.start);
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
  audio.src = audioUrl(od.bucket, ep.key);
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
  updateMediaSession({ ...ep, seriesTitle: od.title }, od.loop ? '佛号' : '点播');
  markPlayingRow();
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

/* 收藏（当前点播集，仅存本机） */
function favKey() { return (mode === 'od' && od) ? 'fy.fav.' + od.list[od.idx].key : null; }
function updateFav() { const k = favKey(); $('#btnFav').classList.toggle('on', !!(k && localStorage.getItem(k))); }
function toggleFav() {
  const k = favKey(); if (!k) return;
  if (localStorage.getItem(k)) localStorage.removeItem(k); else localStorage.setItem(k, '1');
  updateFav();
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

function openSeries(id) {
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
}

function markPlayingRow() {
  const listSeries = $('#epList').dataset.series;
  document.querySelectorAll('#epList li').forEach((li) => {
    li.classList.toggle('playing',
      mode === 'od' && od && od.seriesId === listSeries && Number(li.dataset.idx) === od.idx);
  });
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
      <span class="d">${Math.round(c.chars / 500)} 分钟</span></li>`
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
  reader = { chapters: s.chapters, idx: s.chapters.indexOf(chap), path: chap.path, backHash: back, sid };
  localStorage.setItem('fy.lastRead', spec);   // 文库"继续阅读"用
  $('#readerPos').textContent = `${reader.idx + 1} / ${s.chapters.length}`;
  await renderReader(chap.title, chap.path, s.title);
  $('#btnPrevChap').disabled = reader.idx === 0;
  $('#btnNextChap').disabled = reader.idx >= s.chapters.length - 1;
  document.querySelector('.reader-nav').hidden = false;
}

async function openQa(n) {
  const item = qaData.items[n - 1];
  if (!item || !item.text) { location.hash = '#wenda'; return; }
  reader = { chapters: null, idx: 0, path: item.text, backHash: '#wenda' };
  pendingReaderBack = null;
  $('#readerPos').textContent = '';
  await renderReader(item.title, item.text, '学佛问答');
  document.querySelector('.reader-nav').hidden = true;
}

async function renderReader(title, path, subtitle) {
  const body = $('#readerBody');
  body.innerHTML = '<p class="reader-loading">恭请中 …</p>';
  $('#readLine').style.width = '0%';   // 换篇进度线归零
  applyFontSize();
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
  const saved = Number(localStorage.getItem('fy.rp.' + path) || 0);
  requestAnimationFrame(() => { window.scrollTo(0, saved * (document.body.scrollHeight - innerHeight)); });
}

function applyFontSize() {
  const fs = Number(localStorage.getItem('fy.fs') || FONT_SIZES[1]);
  $('#readerBody').style.fontSize = fs + 'px';
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
  day[cur] = t + d;
  nj.totals[cur] = Math.max(0, (nj.totals[cur] || 0) + d);
  saveNj();
  renderCount();
  if (delta > 0 && navigator.vibrate) navigator.vibrate(12);
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
  $('#cntSummary').textContent = `累计 ${njGrandTotal().toLocaleString()} 声 · 连续 ${njStreak()} 日`;
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

function updateCntTools() {
  $('#btnMuyu').classList.toggle('on', localStorage.getItem('fy.muyu') === '1');
  $('#btnWake').classList.toggle('on', localStorage.getItem('fy.wake') === '1');
}

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
  $('#cntSheetBody').innerHTML = nj.items.map((x) =>
    `<button class="sheet-row${x.id === nj.cur ? ' on' : ''}" data-item="${x.id}">
      <span>${esc(x.name)}</span>
      ${x.id.startsWith('c') ? '<span class="sheet-del" data-del="' + x.id + '">删除</span>'
        : (x.id === nj.cur ? '<span class="sheet-tick">✓</span>' : '')}</button>`).join('')
    + '<button class="sheet-add" data-add>＋ 添加功课</button>';
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
  let monthTotal = 0, cells = '';
  for (let i = 0; i < startDow; i++) cells += '<span class="cal-cell empty"></span>';
  for (let d = 1; d <= daysInMonth; d++) {
    const key = `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
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
    <p class="cal-total">本月共 ${monthTotal.toLocaleString()} 声</p>`;
}

function renderWode() {
  // 我的页：仅计数入口卡（带今日进度预览）+ 足迹（最近在听/在读）
  const k = bjDateKey();
  const t = njDayTotal(k);
  $('#wcName').textContent = njItem().name;
  $('#wcProgress').textContent = nj.goal ? `今日 ${t} / 定课 ${nj.goal} 声` : `今日 ${t} 声`;
  const html = listenCardHtml('最近在听') + readCardHtml('最近在读');
  $('#wodeCards').innerHTML = html;
  $('#wodeTrail').hidden = !html;
}

function renderNjWeek() {
  // 近七日声数：设定课时满线为朱砂、未满为浅朱
  const wrap = $('#njWeek');
  if (!wrap) return;
  const items = [];
  for (let i = 6; i >= 0; i--) {
    const p = bjParts(Date.now() - i * 86400000);
    const k = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
    items.push({ label: i === 0 ? '今' : String(p.d), v: njDayTotal(k) });
  }
  const max = Math.max(1, ...items.map((x) => x.v));
  wrap.innerHTML = items.map((x) => `
    <div class="njw-col">
      <span class="njw-num">${x.v ? x.v.toLocaleString() : ''}</span>
      <div class="njw-bar-wrap"><div class="njw-bar${!nj.goal || x.v >= nj.goal ? ' hit' : ''}" style="height:${Math.max(3, Math.round((x.v / max) * 100))}%"></div></div>
      <span class="njw-day">${x.label}</span>
    </div>`).join('');
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
  $('#wdInput').value = '';
  $('#btnAsk').disabled = true;
  $('#chatStarters').hidden = true;

  chat.msgs.push({ role: 'user', content: q });
  const log = $('#chatLog');
  log.insertAdjacentHTML('beforeend', `<div class="msg user"><p>${esc(q)}</p></div>`);
  log.insertAdjacentHTML('beforeend', '<div class="msg bot streaming"><p class="thinking">检索文库中 …</p></div>');
  const botDiv = log.lastElementChild;
  botDiv.scrollIntoView({ block: 'end' });

  let sources = [];
  let answer = '';
  try {
    const history = chat.msgs.slice(-7, -1).map((m) => ({ role: m.role, content: m.content }));
    const res = await fetch('/api/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q, history }),
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
        if (ev === 'sources') sources = data;
        else if (ev === 'delta') {
          answer += data.text;
          botDiv.innerHTML = renderAnswer(answer, sources, true);
        }
      }
    }
    botDiv.classList.remove('streaming');
    botDiv.innerHTML = renderAnswer(answer || '（未能生成回答，请换个问法）', sources, false);
    chat.msgs.push({ role: 'assistant', content: answer });
  } catch (e) {
    botDiv.classList.remove('streaming');
    botDiv.innerHTML = `<p>${esc(String(e.message || '网络异常，请稍后再试').slice(0, 120))}</p>`;
    chat.msgs.pop(); // 失败的问题不入历史
  }
  chat.streaming = false;
  $('#btnAsk').disabled = false;
}

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
      return `<button class="cite" data-path="${esc(s.path)}" title="${esc(s.series + ' ' + s.title)}">${n}</button>`;
    });
    return `<p>${h}</p>`;
  }).join('');
  let srcs = '';
  const shown = sources.filter((s) => cited.has(s.n));
  const list = shown.length ? shown : (streaming ? [] : sources.slice(0, 3));
  if (list.length) {
    srcs = '<div class="src-list">' + list.map((s) =>
      `<button class="src" data-path="${esc(s.path)}">
        <span class="src-n">${s.n}</span>《${esc(s.series)}》${esc(s.title)}</button>`).join('') + '</div>';
  }
  return html + srcs;
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
      if (od.idx + 1 < od.list.length) { od.idx += 1; startOd(); }
      else backToLive();
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
    if (document.body.dataset.view === 'count' && localStorage.getItem('fy.wake') === '1') requestWake();
  });

  // 节目单
  $('#btnToday').addEventListener('click', () => { schedDay = 0; toggleDay(); });
  $('#btnTomorrow').addEventListener('click', () => { schedDay = 1; toggleDay(); });
  function toggleDay() {
    $('#btnToday').classList.toggle('on', schedDay === 0);
    $('#btnTomorrow').classList.toggle('on', schedDay === 1);
    renderSchedule();
  }

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
  $('#btnNextChap').addEventListener('click', () => stepChapter(1));
  function stepChapter(d) {
    if (!reader.chapters) return;
    const next = reader.chapters[reader.idx + d];
    if (next) location.hash = `#read/${reader.sid}/${next.n}`;
  }
  $('#btnFontMinus').addEventListener('click', () => stepFont(-1));
  $('#btnFontPlus').addEventListener('click', () => stepFont(1));
  function stepFont(d) {
    const cur = Number(localStorage.getItem('fy.fs') || FONT_SIZES[1]);
    const i = Math.max(0, Math.min(FONT_SIZES.length - 1, FONT_SIZES.indexOf(cur) + d));
    localStorage.setItem('fy.fs', String(FONT_SIZES[i]));
    applyFontSize();
  }
  let scrollTimer = 0;
  window.addEventListener('scroll', () => {
    if (document.body.dataset.view !== 'reader' || !reader.path) return;
    const max = document.body.scrollHeight - innerHeight;
    // 阅读进度线即时走，进度记忆去抖存
    $('#readLine').style.width = `${max > 0 ? Math.min(100, (scrollY / max) * 100) : 0}%`;
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => {
      if (max > 200) localStorage.setItem('fy.rp.' + reader.path, String(scrollY / max));
    }, 400);
  }, { passive: true });

  // 佛号：循环曲目列表 → 点一条进全屏播放器（循环恭听）
  $('#fohaoGroups').addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (!li) return;
    const ol = li.closest('[data-fohao]');
    const s = ol && catalog.series.find((x) => x.id === ol.dataset.fohao);
    if (s) playEpisode(s, Number(li.dataset.idx));
  });

  // 念佛计数器：大念珠（涟漪 + 木鱼 + 计一声）· 十念 · 撤销
  $('#btnBead').addEventListener('click', (e) => { spawnBeadRipple(e); playMuyu(); addNj(1); });
  $('#btnTen').addEventListener('click', () => addNj(10));
  $('#btnUndo').addEventListener('click', () => addNj(-1));

  // 工具：木鱼音效 / 屏幕常亮
  $('#btnMuyu').addEventListener('click', () => {
    const on = localStorage.getItem('fy.muyu') === '1';
    if (on) localStorage.removeItem('fy.muyu');
    else { localStorage.setItem('fy.muyu', '1'); playMuyu(); }
    updateCntTools();
  });
  $('#btnWake').addEventListener('click', async () => {
    const on = localStorage.getItem('fy.wake') === '1';
    if (on) { localStorage.removeItem('fy.wake'); await releaseWake(); }
    else { localStorage.setItem('fy.wake', '1'); await requestWake(); }
    updateCntTools();
  });

  // 功课 / 定课 / 历史 入口
  $('#btnPractice').addEventListener('click', () => { renderPracticeSheet(); openCntSheet('practice', '功课'); });
  $('#btnGoal').addEventListener('click', () => { renderGoalSheet(); openCntSheet('goal', '每日定课'); });
  $('#btnHistory').addEventListener('click', () => {
    const p = bjParts(Date.now());
    calYM = { y: p.y, m: p.mo };
    renderCalendar(); openCntSheet('history', '念佛历史');
  });

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
    }
  });

  // 回向偈
  $('#btnHuixiang').addEventListener('click', () => { $('#hxOverlay').hidden = false; });
  $('#hxOverlay').addEventListener('click', () => { $('#hxOverlay').hidden = true; });

  // 外观设置
  $('#themeChips').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-theme]');
    if (!b) return;
    localStorage.setItem('fy.theme', b.dataset.theme);
    applyThemePref();
  });

  // 关于本站弹窗
  $('#btnAbout').addEventListener('click', () => { $('#aboutOverlay').hidden = false; });
  $('#btnAboutClose').addEventListener('click', () => { $('#aboutOverlay').hidden = true; });
  $('#aboutOverlay').addEventListener('click', (e) => {
    if (e.target === $('#aboutOverlay')) $('#aboutOverlay').hidden = true;   // 点遮罩关闭，点内容不关
  });

  // 问道：对话
  $('#btnAsk').addEventListener('click', () => sendQuestion($('#wdInput').value));
  $('#wdInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendQuestion($('#wdInput').value); }
  });
  $('#chatStarters').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (b) sendQuestion(b.textContent);
  });
  $('#chatLog').addEventListener('click', (e) => {
    const c = e.target.closest('[data-path]');
    if (!c) return;
    pendingReaderBack = '#wenda';
    location.hash = pathToHash(c.dataset.path);
  });

  // 迷你播放条：两态 / 关闭 / 标题回系列
  $('#btnMiniToggle').addEventListener('click', () =>
    setMiniExpanded($('#mini').classList.contains('collapsed')));
  $('#btnMiniClose').addEventListener('click', closeOd);
  $('#btnPlayerDown').addEventListener('click', () => setMiniExpanded(false));
  $('#miniTitles').addEventListener('click', () => setMiniExpanded(true));
  $('#miniArt').addEventListener('click', () => setMiniExpanded(true));
  $('#btnPrevEp').addEventListener('click', () => stepEpisode(-1));
  $('#btnNextEp').addEventListener('click', () => stepEpisode(1));
  $('#btnFav').addEventListener('click', toggleFav);
  $('#btnPlaylist').addEventListener('click', () => {
    if (od && od.seriesId) { setMiniExpanded(false); location.hash = '#series/' + od.seriesId; }
  });
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
  $('#btnSleep').addEventListener('click', () => {
    setSleep(SLEEP_MINS[(SLEEP_MINS.indexOf(odSleep.min) + 1) % SLEEP_MINS.length]);
  });
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

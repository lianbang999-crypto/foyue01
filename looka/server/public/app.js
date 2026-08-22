/* Looka 网页端 —— 与安卓 App 同账号、同步协议、同视觉语言（原创实现） */
'use strict';

/* ---------------- 多语言（与安卓共用同一份字典：/i18n/*.json） ---------------- */
let LANG = localStorage.getItem('lk_lang') || 'auto';
let DICT = {};
function resolveLang() {
  if (LANG !== 'auto') return LANG;
  const l = (navigator.language || 'zh-CN');
  if (!l.startsWith('zh')) return 'en';
  return /TW|HK|MO|Hant/i.test(l) ? 'zh-TW' : 'zh-CN';
}
async function loadDict() {
  const lg = resolveLang();
  if (lg === 'zh-CN') { DICT = {}; return; }
  try { DICT = await (await fetch('/i18n/' + lg + '.json')).json(); } catch (e) { DICT = {}; }
}
function t(s, ...args) {
  let out = DICT[s] || s;
  args.forEach((a, i) => { out = out.replace('{' + i + '}', a); });
  return out;
}
const isZh = () => resolveLang() !== 'en';

/* ---------------- 农历（浏览器 Intl 中国历，零数据表） ---------------- */
const lunarCache = new Map();
function lunarText(day) {
  if (!isZh() || localStorage.getItem('lk_lunar') === '0') return '';
  if (lunarCache.has(day)) return lunarCache.get(day);
  let out = '';
  try {
    const dt = new Date((day + 0.5) * 86400000);
    const parts = new Intl.DateTimeFormat('zh-CN-u-ca-chinese', { month: 'long', day: 'numeric' })
      .formatToParts(dt);
    const mon = parts.find(x => x.type === 'month')?.value || '';
    const dnum = +(parts.find(x => x.type === 'day')?.value || 0);
    const DAYS = ['初一','初二','初三','初四','初五','初六','初七','初八','初九','初十',
      '十一','十二','十三','十四','十五','十六','十七','十八','十九','二十',
      '廿一','廿二','廿三','廿四','廿五','廿六','廿七','廿八','廿九','三十'];
    out = dnum === 1 ? mon : (DAYS[dnum - 1] || '');
  } catch (e) { }
  lunarCache.set(day, out);
  return out;
}

/* ---------------- 工具 ---------------- */
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const DAY_MS = 86400000;
const WEEK_CN = ['一', '二', '三', '四', '五', '六', '日'];
const MOODS = ['😄', '🙂', '😐', '😞', '😫'];

const todayEpoch = () => { const n = new Date(); return epochOf(n.getFullYear(), n.getMonth() + 1, n.getDate()); };
const epochOf = (y, m, d) => Math.floor(Date.UTC(y, m - 1, d) / DAY_MS);
const fromEpoch = day => { const t = new Date(day * DAY_MS); return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() }; };
const dow = day => ((day + 3) % 7 + 7) % 7 + 1;             // ISO 1=周一
const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const addMonths = (y, m, k) => { const t = y * 12 + (m - 1) + k; return { y: Math.floor(t / 12), m: (t % 12 + 12) % 12 + 1 }; };
const hm = min => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
const WEEK_EN = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
const dateCn = day => {
  const f = fromEpoch(day);
  return isZh() ? `${f.m}月${f.d}日(${WEEK_CN[dow(day) - 1]})`
                : `${f.m}/${f.d} (${WEEK_EN[dow(day) - 1]})`;
};
const isoDate = day => { const t = fromEpoch(day); return `${t.y}-${String(t.m).padStart(2, '0')}-${String(t.d).padStart(2, '0')}`; };
const parseIso = s => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || ''); return m ? epochOf(+m[1], +m[2], +m[3]) : -1; };
const uuid = () => crypto.randomUUID();
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.add('hidden'), 2200);
}

/* ---------------- 九色主题 ---------------- */
const THEMES = [
  ['森绿', '#55B04B', '#EAF6E7', '#1E4D19'], ['青碧', '#2FA69A', '#E3F4F1', '#0F4B45'],
  ['天蓝', '#4A9EDB', '#E6F1FA', '#16436B'], ['绀青', '#4A7DDC', '#E8EEFB', '#1A3670'],
  ['藕紫', '#7E6BD8', '#EEEAFA', '#35296B'], ['樱粉', '#E077A8', '#FBEAF2', '#6B2145'],
  ['珊瑚', '#E0504A', '#FBEAE9', '#6B1F1C'], ['暖橙', '#F2913D', '#FCEFE2', '#6B3C0F'],
  ['鎏金', '#C9A227', '#F7F1DC', '#5C4A0E']
];
// 自创主题（与 App 同步，2026-08-21）：主色掺 90% 白做浅底、掺 55% 黑做深字
// 2026-08-21 对齐 Lifebear：48 色贴纸盘（与 App LIST_PALETTE 同源，色相环螺旋、高饱和）
const LK_PALETTE = [
  '#000000','#FBFDFC','#7B6359','#AB958A','#EEB19F','#EE958F',
  '#E67289','#E13C5E','#D10021','#920011','#5F000B','#A44702',
  '#DE6A03','#ED8F1D','#F7C212','#F6DC31','#DFED38','#C0E30D',
  '#99B218','#2E822D','#57B652','#59DF86','#17C192','#0EA66D',
  '#046963','#0E9199','#14B2BD','#5ED3DA','#90DDF7','#53C8E9',
  '#1E86C3','#062389','#2947A1','#425BBF','#697EDB','#80A5EC',
  '#ADA1EB','#8272D5','#6A42D7','#4F18B4','#8500C0','#B852E6',
  '#DE4BE2','#CB00D8','#9F0058','#C10080','#F05FBE','#F689CD'
];
// 色块上的文字颜色：亮底黑字、深底白字（盘里有大量黄绿青亮色，写死白字会隐形）
function onColorHex(hex) {
  const n = parseInt((hex || '#888').slice(1), 16);
  const r = (n >> 16) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255;
  const lin = c => c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  return (0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)) > 0.55 ? '#1B1B1F' : '#fff';
}

function mixHex(hex, to, f) {
  const n = parseInt(hex.slice(1), 16), tn = parseInt(to.slice(1), 16);
  const ch = sh => Math.round(((n >> sh) & 255) * (1 - f) + ((tn >> sh) & 255) * f);
  return '#' + [16, 8, 0].map(sh => ch(sh).toString(16).padStart(2, '0')).join('');
}
function applyCustomTheme(hex) {
  const r = document.documentElement.style;
  r.setProperty('--primary', hex);
  r.setProperty('--container', mixHex(hex, '#ffffff', 0.90));
  r.setProperty('--on-container', mixHex(hex, '#000000', 0.55));
  // 纸：与 App 的 paperOf 同算法（掺 96% 白），页面底色随主题走
  r.setProperty('--paper', mixHex(hex, '#ffffff', 0.96));
  r.setProperty('--panel', mixHex(hex, '#ffffff', 0.92));
  localStorage.setItem('lk_theme', 'c:' + hex);
  $$('.theme-dot').forEach(el => el.classList.remove('active'));
  $$('.custom-dot').forEach(el => el.classList.toggle('active', el.dataset.c === hex));
  pushThemeSettings(-1, hex);   // B1：主题上云（自定义 = -1 + ARGB）
}
function applyTheme(i) {
  if (typeof i === 'string' && i.startsWith('c:')) return applyCustomTheme(i.slice(2));
  const t = THEMES[+i] || THEMES[0];
  const r = document.documentElement.style;
  r.setProperty('--primary', t[1]); r.setProperty('--container', t[2]); r.setProperty('--on-container', t[3]);
  r.setProperty('--paper', mixHex(t[1], '#ffffff', 0.96));
  r.setProperty('--panel', mixHex(t[1], '#ffffff', 0.92));
  localStorage.setItem('lk_theme', i);
  $$('.custom-dot').forEach(el => el.classList.remove('active'));
  $$('.theme-dot').forEach((el, j) => el.classList.toggle('active', j === +i));
  pushThemeSettings(+i, null);   // B1：主题上云
}
/* B1（§48）：主题并入 settings 同步实体（与 App 的 themeIndex/customColor 同构）。
   applyingRemote 防回环：收云端设置落地时不再往回推。 */
let applyingRemoteSettings = false;
function pushThemeSettings(idx, hex) {
  if (applyingRemoteSettings || !S.token) return;
  const p = {
    weekStartMon: ST.weekStartMon, showLunar: ST.showLunar,
    holidayMask: ST.holidayMask, showDoneTasks: ST.showDoneTasks,
    themeIndex: idx,
    customColor: hex ? parseInt('ff' + hex.slice(1), 16) : 0xFF55B04B,
    deerFacts: JSON.parse(localStorage.getItem('lk_deer_facts') || '[]')
  };
  S.dirty.push({ kind: 'settings', uid: 'settings', updated_at: Date.now(), deleted: 0, p });
  saveDirty(); scheduleSync();
}

/* ---------------- 状态与存储 ---------------- */
const KINDS = ['category', 'tasklist', 'event', 'task', 'note', 'diary', 'stamp', 'settings'];
// P5-1 设置上云：App 是设置的主人，网页跟随（两端看同一本日历）。
// 默认值与 App Prefs 一致：周一起始 / 农历跟随中文 / 显示已完成
const ST = { weekStartMon: true, showLunar: null, holidayMask: 1 << 6, showDoneTasks: true };
const stShowLunar = () => ST.showLunar == null ? isZh() : !!ST.showLunar;
const S = {
  token: localStorage.getItem('lk_token') || '',
  account: localStorage.getItem('lk_account') || '',
  plan: 'free',
  data: Object.fromEntries(KINDS.map(k => [k, new Map()])),
  dirty: [],
  selDay: todayEpoch(),
  month: (() => { const n = new Date(); return { y: n.getFullYear(), m: n.getMonth() + 1 }; })(),
  tab: 'cal',
  aiRemaining: -1,
  aiBusy: false,
  chat: []
};
const sinceKey = () => 'lk_since_' + S.account;
const cacheKey = () => 'lk_cache_' + S.account;
const dirtyKey = () => 'lk_dirty_' + S.account;

function saveCache() {
  const o = {};
  for (const k of KINDS) o[k] = [...S.data[k].values()];
  try { localStorage.setItem(cacheKey(), JSON.stringify(o)); } catch (e) { }
}
function loadCache() {
  for (const k of KINDS) S.data[k].clear();
  try {
    const o = JSON.parse(localStorage.getItem(cacheKey()) || '{}');
    for (const k of KINDS) (o[k] || []).forEach(r => S.data[k].set(r.uid, r));
  } catch (e) { }
  try { S.dirty = JSON.parse(localStorage.getItem(dirtyKey()) || '[]'); } catch (e) { S.dirty = []; }
}
const saveDirty = () => { try { localStorage.setItem(dirtyKey(), JSON.stringify(S.dirty)); } catch (e) { } };

/* ---------------- API ---------------- */
async function api(path, body, method) {
  const opt = { method: method || (body ? 'POST' : 'GET'), headers: {} };
  if (S.token) opt.headers['Authorization'] = 'Bearer ' + S.token;
  if (body) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const resp = await fetch(path, opt);
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    if (resp.status === 401) { logoutLocal(); }
    throw new Error(data.error || ('请求失败 ' + resp.status));
  }
  return data;
}

/* ---------------- 同步 ---------------- */
let syncTimer = 0;
function scheduleSync() { clearTimeout(syncTimer); syncTimer = setTimeout(() => sync().catch(() => { }), 1200); }

async function sync() {
  if (!S.token) return;
  const push = S.dirty.splice(0); saveDirty();
  try {
    // 分页游标：只随 next_since 前进（修复 >1000 条丢数据）
    let since = +(localStorage.getItem(sinceKey()) || 0);
    let body = push.map(x => ({ kind: x.kind, uid: x.uid, updated_at: x.updated_at, deleted: x.deleted, payload: x.p ? JSON.stringify(x.p) : '' }));
    for (let page = 0; page < 20; page++) {
      const r = await api('/api/sync', { since, push: body });
      body = [];
      (r.apply || []).forEach(applyRec);
      const next = +(r.next_since || since);
      if (next > since) { since = next; localStorage.setItem(sinceKey(), since); }
      if (!r.has_more) break;
    }
    ensureDefaultCategories();
    saveCache(); renderAll();
  } catch (e) {
    S.dirty = push.concat(S.dirty); saveDirty();
    throw e;
  }
}
function applyRec(rec) {
  if (rec.kind === 'settings') {           // P5-1：云端设置 → 本地生效并重画
    try {
      const p = JSON.parse(rec.payload || '{}');
      ST.weekStartMon = p.weekStartMon !== false;
      ST.showLunar = p.showLunar == null ? null : !!p.showLunar;
      ST.holidayMask = p.holidayMask ?? (1 << 6);
      ST.showDoneTasks = p.showDoneTasks !== false;
      // D1：小鹿记事本随云
      if (Array.isArray(p.deerFacts)) localStorage.setItem('lk_deer_facts', JSON.stringify(p.deerFacts));
      // B1：主题随云（App 换了主题，网页跟着变；防回环见 pushThemeSettings）
      if (p.themeIndex !== undefined) {
        applyingRemoteSettings = true;
        try {
          if (p.themeIndex === -1 && p.customColor)
            applyCustomTheme('#' + (p.customColor & 0xFFFFFF).toString(16).padStart(6, '0'));
          else applyTheme(Math.max(0, p.themeIndex));
        } finally { applyingRemoteSettings = false; }
      }
      calWin = null;                       // 周起始变了，周索引原点要重算
      if (S.tab === 'cal') renderCalendar(S.selDay);
    } catch (e) { }
    return;
  }
  const map = S.data[rec.kind]; if (!map) return;
  const ex = map.get(rec.uid);
  if (ex && ex.updated_at > rec.updated_at) return;
  if (rec.deleted) { map.delete(rec.uid); return; }
  let p = {};
  try { p = JSON.parse(rec.payload || '{}'); } catch (e) { }
  map.set(rec.uid, { uid: rec.uid, updated_at: rec.updated_at, p });
}
function put(kind, uid, p) {
  const rec = { kind, uid, updated_at: Date.now(), deleted: 0, p };
  S.data[kind].set(uid, { uid, updated_at: rec.updated_at, p });
  S.dirty.push(rec); saveDirty(); saveCache(); scheduleSync(); renderAll();
}
function del(kind, uid) {
  S.data[kind].delete(uid);
  S.dirty.push({ kind, uid, updated_at: Date.now(), deleted: 1, p: null });
  saveDirty(); saveCache(); scheduleSync(); renderAll();
}
function ensureDefaultCategories() {
  if (S.data.category.size) return;
  const defs = [
    ['cat-default-1', '未分类', '#9AA0A6', 0, false], ['cat-default-2', '工作', '#4A7DDC', 1, true],
    ['cat-default-3', '个人', '#55B04B', 2, true], ['cat-default-4', '重要', '#E0504A', 3, true],
    ['cat-default-5', '纪念日', '#E077A8', 4, true]
  ];
  defs.forEach(([uid, name, color, sort, deletable]) =>
    put('category', uid, { name, color, sort, visible: true, deletable }));
}
const cats = () => [...S.data.category.values()].map(r => ({ uid: r.uid, ...r.p }))
  .sort((a, b) => (a.sort || 0) - (b.sort || 0));
const catColor = cuid => (S.data.category.get(cuid)?.p?.color) || '#9AA0A6';

/* ---------------- 重复展开（与 App 引擎同语义） ---------------- */
function expandEvents(from, to) {
  const out = [];
  for (const rec of S.data.event.values()) {
    const p = rec.p; if (!p || p.title === undefined) continue;
    const exMap = {}; (p.exceptions || []).forEach(e => exMap[e.occ] = e);
    const dur = Math.max((p.endDay ?? p.startDay) - p.startDay, 0);
    const emit = occDay => {
      const ex = exMap[occDay];
      if (ex && ex.cancelled) return;
      const day = ex && ex.newDay >= 0 ? ex.newDay : occDay;
      const o = {
        uid: rec.uid, occDay, day, endDay: day + dur,
        title: ex?.title ?? p.title, allDay: ex?.allDay ?? !!p.allDay,
        startMin: ex?.startMin ?? (p.startMin || 0), endMin: ex?.endMin ?? (p.endMin || 0),
        categoryUid: ex?.categoryUid ?? p.categoryUid,
        location: ex?.location ?? (p.location || ''), memo: ex?.memo ?? (p.memo || ''),
        recurring: (p.freq || 0) !== 0
      };
      if (o.endDay >= from && o.day <= to) out.push(o);
    };
    const until = (p.untilDay ?? -1) >= 0 ? p.untilDay : Infinity;
    const hardTo = Math.min(to, until);
    const f = p.freq || 0, itv = Math.max(p.interval || 1, 1);
    if (f === 0) { emit(p.startDay); continue; }
    if (f === 1) {
      let d = p.startDay;
      if (from - dur > d) d += Math.floor((from - dur - d) / itv) * itv;
      for (; d <= hardTo; d += itv) emit(d);
    } else if (f === 2) {
      const mask = p.weekdays || (1 << (dow(p.startDay) - 1));
      const baseWeek = Math.floor((p.startDay + 3) / 7);
      for (let d = Math.max(p.startDay, from - dur - 7); d <= hardTo; d++) {
        const w = Math.floor((d + 3) / 7);
        if ((w - baseWeek) % itv === 0 && (mask >> (dow(d) - 1)) & 1) emit(d);
      }
    } else if (f === 3) {
      const b = fromEpoch(p.startDay);
      for (let i = 0; i <= 1200; i++) {
        const t = addMonths(b.y, b.m, i * itv);
        let day;
        if (p.monthlyByWeekday) {
          const nth = Math.floor((b.d - 1) / 7) + 1;
          const firstDow = dow(epochOf(t.y, t.m, 1));
          let dd = 1 + ((dow(p.startDay) - firstDow + 7) % 7) + (nth - 1) * 7;
          while (dd > daysInMonth(t.y, t.m)) dd -= 7;
          day = epochOf(t.y, t.m, dd);
        } else {
          day = epochOf(t.y, t.m, Math.min(b.d, daysInMonth(t.y, t.m)));
        }
        if (day > hardTo) break;
        if (day < p.startDay) continue;
        emit(day);
      }
    } else if (f === 4) {
      const b = fromEpoch(p.startDay);
      for (let i = 0; i <= 300; i++) {
        const y = b.y + i * itv;
        const day = epochOf(y, b.m, Math.min(b.d, daysInMonth(y, b.m)));
        if (day > hardTo) break;
        if (day < p.startDay) continue;
        emit(day);
      }
    }
  }
  return out;
}

/* ---------------- 弹窗 ---------------- */
function modal(html) {
  const root = $('#modalRoot');
  root.innerHTML = `<div class="mask"><div class="dialog">${html}</div></div>`;
  root.querySelector('.mask').addEventListener('click', e => { if (e.target.classList.contains('mask')) closeModal(); });
  return root.querySelector('.dialog');
}
const closeModal = () => { $('#modalRoot').innerHTML = ''; };

function confirmDlg(title, onOk, okText) {
  const d = modal(`<h3>${esc(title)}</h3><div class="btns">
    <button class="btn-ghost" id="cX">取消</button><button class="btn-danger" id="cOk">${okText || '删除'}</button></div>`);
  d.querySelector('#cX').onclick = closeModal;
  d.querySelector('#cOk').onclick = () => { closeModal(); onOk(); };
}
function scopeDlg(title, onPick) {
  const d = modal(`<h3>${esc(title)}</h3>
    <button class="menu-item" data-s="0">仅本次</button>
    <button class="menu-item" data-s="1">本次及以后</button>
    <button class="menu-item" data-s="2">全部</button>
    <div class="btns"><button class="btn-ghost" id="cX">取消</button></div>`);
  d.querySelector('#cX').onclick = closeModal;
  d.querySelectorAll('[data-s]').forEach(b => b.onclick = () => { closeModal(); onPick(+b.dataset.s); });
}

/* ---------------- 日历渲染 ---------------- */
// ==================== 连续滚动月历（2026-08-21 对齐 App / Lifebear）====================
// 以「周」为行的跨月无限滚动：一次渲染当前位置 ±26 周，滚到边缘自动扩段；
// 标题月由视口反推（取视口 1/3 处那行的周四）；月界靠水印数字 + 每月 1 号带月份。
// 周起始可变（P5-1）：原点随 ST.weekStartMon 变化
const week0 = () => { const e = epochOf(2016, 1, 1); const first = ST.weekStartMon ? 1 : 7; return e - ((dow(e) - first + 7) % 7); };
const weekIdxOf = day => Math.floor((day - week0()) / 7);
let calWin = null;           // { from, to } 已渲染的周号区间
let calRowH = 92;            // 实测行高（渲染后校准）

function calProbeMonth() {
  const grid = $('#monthGrid');
  const row = Math.floor(grid.scrollTop / calRowH) + 1;   // 视口第 2 行 ≈ 1/3 处
  const probe = week0() + (calWin.from + row) * 7 + 3;      // 那周的周四定月份
  return fromEpoch(probe);
}

function calUpdateTitle() {
  const f = calProbeMonth();
  if (f.y !== S.month.y || f.m !== S.month.m) { S.month = { y: f.y, m: f.m }; }
  $('#monthTitle').textContent = `${S.month.y}年${S.month.m}月`;
  const t = todayEpoch();
  const tf = fromEpoch(t);
  $('#btnToday').classList.toggle('hidden', S.selDay === t && S.month.y === tf.y && S.month.m === tf.m);
}

function calScrollToDay(day, smooth) {
  const grid = $('#monthGrid');
  const wi = weekIdxOf(day);
  if (!calWin || wi < calWin.from + 2 || wi > calWin.to - 2) { renderCalendar(day); return; }
  grid.scrollTo({ top: (wi - calWin.from) * calRowH, behavior: smooth ? 'smooth' : 'auto' });
}

function renderCalendar(anchorDay) {
  const grid = $('#monthGrid');
  const t = todayEpoch();
  const keepScroll = anchorDay == null && calWin != null ? grid.scrollTop : null;

  // 渲染窗口：锚点周 ±26 周（约一年）
  const anchor = anchorDay != null ? anchorDay
    : (calWin != null ? week0() + (calWin.from + Math.floor(grid.scrollTop / calRowH)) * 7
                      : epochOf(S.month.y, S.month.m, 15));
  const wi = weekIdxOf(anchor);
  calWin = { from: wi - 26, to: wi + 26 };
  const gridStart = week0() + calWin.from * 7;
  const gridEnd = week0() + (calWin.to + 1) * 7 - 1;

  // 星期头（周一开始）
  {
    const names = ST.weekStartMon ? WEEK_CN : ['日', ...WEEK_CN.slice(0, 6)];
    $('#weekHeader').innerHTML = names.map(w =>
      `<span class="${w === '日' ? 'hol' : w === '六' ? 'sat' : ''}">${w}</span>`).join('');
  }

  const occs = expandEvents(gridStart, gridEnd);
  const byDay = {};
  occs.forEach(o => {
    for (let d = Math.max(o.day, gridStart); d <= Math.min(o.endDay, gridEnd); d++) {
      (byDay[d] = byDay[d] || []).push(o);
    }
  });
  Object.values(byDay).forEach(l => l.sort((a, b) => (a.allDay ? -1 : a.startMin) - (b.allDay ? -1 : b.startMin)));

  const tasksByDay = {};
  for (const r of S.data.task.values()) {
    const p = r.p; if (!p || (p.dueDay ?? -1) < 0) continue;
    if (!ST.showDoneTasks && p.done) continue;   // P5-1：跟随「显示已完成」设置
    (tasksByDay[p.dueDay] = tasksByDay[p.dueDay] || []).push({ uid: r.uid, ...p });
  }
  const stampsByDay = {};
  for (const r of S.data.stamp.values()) {
    const p = r.p; if (!p) continue;
    (stampsByDay[p.day] = stampsByDay[p.day] || []).push({ uid: r.uid, ...p });
  }
  window.__calByDay = byDay; window.__calTasksByDay = tasksByDay; window.__calStampsByDay = stampsByDay;

  let html = '';
  for (let w = calWin.from; w <= calWin.to; w++) {
    const ws = week0() + w * 7;
    // 本周含某月 15 号 → 画跨行月份水印（Lifebear 的巨大数字）
    let wm = '';
    for (let i = 0; i < 7; i++) { const f = fromEpoch(ws + i); if (f.d === 15) { wm = `<span class="month-wm">${f.m}</span>`; break; } }
    let cells = '';
    for (let i = 0; i < 7; i++) {
      const day = ws + i;
      const f = fromEpoch(day);
      const wd = dow(day);
      const cls = ['day-cell', f.m % 2 === 0 ? 'm-even' : '', day === t ? 'today' : '',
        day === S.selDay ? 'sel' : '', f.d === 1 ? 'm1' : ''].join(' ');
      const numCls = ['day-num', wd === 7 ? 'hol' : '', wd === 6 ? 'sat' : ''].join(' ');
      const evs = byDay[day] || [];
      const tks = tasksByDay[day] || [];
      const sts = stampsByDay[day] || [];
      let lines = '';
      let shown = 0;
      const CELL_MAX = 6;
      for (const o of evs) {
        if (shown >= CELL_MAX) break;
        const c = catColor(o.categoryUid);
        lines += o.allDay
          ? `<div class="ev-line allday" style="background:${c};color:${onColorHex(c)}">${esc(o.title)}</div>`
          : `<div class="ev-line" style="color:${c}">${esc(o.title)}</div>`;
        shown++;
      }
      for (const k of tks) {
        if (shown >= CELL_MAX) break;
        lines += `<div class="ev-line task">✓${esc(k.title)}</div>`;
        shown++;
      }
      const rest = evs.length + tks.length - shown;
      if (rest > 0) lines += `<div class="ev-more">+${rest}</div>`;
      const lun = stShowLunar() ? lunarText(day) : '';
      const stampHtml = sts.slice(0, 3).map(st => st.assetId
        ? `<img class="stamp-img" src="stamps/${st.assetId}.webp" alt="">`
        : `<span>${st.emoji}</span>`).join('');
      // 连续滚动下月界要看得见：每月 1 号带月份
      const numHtml = f.d === 1 ? `${f.m}月1` : f.d;
      cells += `<div class="${cls}" data-day="${day}">
        <div class="day-num-wrap"><span class="${numCls}">${numHtml}</span>${lun ? `<span class="lunar">${lun}</span>` : ''}</div>
        ${sts.length ? `<div class="cell-stamps">${stampHtml}</div>` : ''}
        ${lines}</div>`;
    }
    html += `<div class="week-row" data-wi="${w}">${wm}${cells}</div>`;
  }
  grid.innerHTML = html;

  const firstRow = grid.querySelector('.week-row');
  if (firstRow) calRowH = firstRow.offsetHeight || calRowH;

  // 恢复/定位滚动
  if (keepScroll != null) grid.scrollTop = keepScroll;
  else grid.scrollTop = (weekIdxOf(anchor) - calWin.from) * calRowH;

  if (!grid.__calBound) {
    grid.__calBound = true;
    let tick = null;
    grid.addEventListener('scroll', () => {
      if (tick) return;
      tick = requestAnimationFrame(() => {
        tick = null;
        calUpdateTitle();
        // 滚到边缘 → 以当前位置为锚重建窗口（滚动位置按锚点恢复）
        const rows = calWin.to - calWin.from + 1;
        const row = grid.scrollTop / calRowH;
        if (row < 6 || row > rows - 12) {
          const cur = week0() + (calWin.from + Math.floor(row) + 1) * 7 + 3;
          renderCalendar(cur);
        }
      });
    });
  }

  grid.querySelectorAll('.day-cell').forEach(el => el.onclick = () => {
    S.selDay = +el.dataset.day;
    grid.querySelectorAll('.day-cell.sel').forEach(x => x.classList.remove('sel'));
    el.classList.add('sel');
    const bd = window.__calByDay || {}, td = window.__calTasksByDay || {}, sd = window.__calStampsByDay || {};
    renderDayPanel(bd[S.selDay] || [], td[S.selDay] || [], sd[S.selDay] || []);
    if (window.innerWidth <= 560) $('#dayPanel').classList.add('open');
  });

  calUpdateTitle();
  renderDayPanel(byDay[S.selDay] || [], tasksByDay[S.selDay] || [], stampsByDay[S.selDay] || []);
}

function renderDayPanel(evs, tks, sts) {
  const td = todayEpoch();
  $('#panelDate').textContent = dateCn(S.selDay) + (S.selDay === td ? ' · ' + t('今天') : '');
  const lun = lunarText(S.selDay);
  $('#panelLunar').textContent = lun ? '农历' + lun : '';
  const diary = S.data.diary.get('diary-' + S.selDay);
  let html = '';
  evs.forEach((o, i) => {
    const c = catColor(o.categoryUid);
    html += `<div class="day-item" data-ev="${i}">
      <div class="bar" style="background:${c}"></div>
      <div class="t"><div class="name">${esc(o.title)}${o.recurring ? ' <span style="color:var(--gray);font-size:11px">↻</span>' : ''}</div>
      ${o.location ? `<div class="sub">${esc(o.location)}</div>` : ''}</div>
      <div class="time">${o.allDay ? '全天' : hm(o.startMin) + '<br>' + hm(o.endMin)}</div></div>`;
  });
  tks.forEach(k => {
    html += `<div class="day-item" data-task="${k.uid}">
      <div class="t"><div class="name" style="${k.done ? 'color:var(--gray);text-decoration:line-through' : ''}">${k.done ? '☑' : '☐'} ${esc(k.title)}</div></div>
      <div class="time">任务</div></div>`;
  });
  // 日记：有没有都常驻一行（对齐 Lifebear 实机，空日子给一句邀请而不是什么都不显示）
  html += diary
    ? `<div class="day-item" data-diary="1">
      <div class="t"><div class="name">${MOODS[diary.p.mood ?? 2]} ${esc((diary.p.content || '').slice(0, 26))}</div></div>
      <div class="time">${t('日记')}</div></div>`
    : `<div class="day-item" data-diary="1">
      <div class="t"><div class="name dim-invite">${t('随便写点什么吧 ✎')}</div></div>
      <div class="time">${t('日记')}</div></div>`;
  if (sts.length) {
    html += `<div class="day-item"><div class="t">${sts.map(st => {
      const face = st.assetId
        ? `<img class="stamp-img-lg" src="stamps/${st.assetId}.webp" alt="" ${st.eventUid ? `data-stamp-ev="${st.eventUid}"` : ''}>`
        : `<span style="font-size:19px;margin-right:6px" ${st.eventUid ? `data-stamp-ev="${st.eventUid}"` : ''}>${st.emoji}</span>`;
      return face + `<button class="del" data-stamp-del="${st.uid}">×</button>`;
    }).join('')}</div><div class="time">${t('印章')}</div></div>`;
  }
  if (!evs.length && !tks.length && !diary && !sts.length) {
    html = `<div class="empty-deer"><img src="deer.svg" alt="">${t('这一天还没有安排')}
      <span class="empty-hint">${t('点右上角 ＋ 安排一条 ↗')}</span></div>` + html;
  }
  $('#dayItems').innerHTML = html;

  $$('#dayItems [data-ev]').forEach(el => el.onclick = () => openEventModal(evs[+el.dataset.ev]));
  $$('#dayItems [data-task]').forEach(el => el.onclick = () => toggleTask(el.dataset.task));
  $$('#dayItems [data-diary]').forEach(el => el.onclick = () => openDiaryModal(S.selDay));
  $$('#dayItems [data-stamp-del]').forEach(el => el.onclick = e => {
    e.stopPropagation();
    confirmDlg('删除这个印章？', () => del('stamp', el.dataset.stampDel));
  });
  $$('#dayItems [data-stamp-ev]').forEach(el => el.onclick = () => {
    const rec = S.data.event.get(el.dataset.stampEv);
    if (rec) openEventModal({ uid: rec.uid, occDay: rec.p.startDay, day: rec.p.startDay, ...rec.p, recurring: (rec.p.freq || 0) !== 0 });
  });
}

/* ---------------- 日程弹窗 ---------------- */
function openEventModal(occ) {
  const isEdit = !!occ;
  const p = isEdit ? S.data.event.get(occ.uid)?.p : null;
  const init = {
    title: occ?.title || '', allDay: occ ? !!occ.allDay : false,
    day: occ?.day ?? S.selDay, startMin: occ?.startMin ?? 9 * 60, endMin: occ?.endMin ?? 10 * 60,
    categoryUid: occ?.categoryUid || 'cat-default-1',
    location: occ?.location || '', memo: occ?.memo || '',
    freq: p?.freq || 0, interval: p?.interval || 1, weekdays: p?.weekdays || 0,
    untilDay: p?.untilDay ?? -1
  };
  const catOpts = cats().map(c =>
    `<option value="${c.uid}" ${c.uid === init.categoryUid ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
  const d = modal(`
    <h3>${isEdit ? '编辑日程' : '新建日程'}</h3>
    ${isEdit && occ.recurring ? '<p style="font-size:12px;color:var(--gray);margin-bottom:8px">重复日程在网页端按整个系列修改；改单次请在 App 内操作</p>' : ''}
    <div class="frow"><input id="evTitle" placeholder="日程名" value="${esc(init.title)}"></div>
    <div class="check-line"><input type="checkbox" id="evAllDay" ${init.allDay ? 'checked' : ''}><label for="evAllDay">全天</label></div>
    <div class="frow-inline">
      <div class="frow"><label>开始日期</label><input id="evDate" type="date" value="${isoDate(init.day)}"></div>
      <div class="frow"><label>结束日期</label><input id="evDateEnd" type="date" value="${isoDate(p?.endDay ?? init.day)}"></div>
      <div class="frow" id="evTimeWrap1"><label>开始</label><input id="evStart" type="time" value="${hm(init.startMin)}"></div>
      <div class="frow" id="evTimeWrap2"><label>结束</label><input id="evEnd" type="time" value="${hm(init.endMin)}"></div>
    </div>
    <button class="btn-ghost" id="evAdvToggle" style="padding:4px 0;color:var(--sat,#4a7ddc);font-size:13px">${t('显示详细设置')}</button>
    <div id="evAdv" class="hidden">
    <div class="frow"><label>${t('分类')}</label><select id="evCat">${catOpts}</select></div>
    <div class="frow-inline">
      <div class="frow"><label>${t('重复')}</label>
        <select id="evFreq">
          <option value="0">${t('无')}</option><option value="1">${t('每天')}</option><option value="2">${t('每周')}</option>
          <option value="3">${t('每月')}</option><option value="4">${t('每年')}</option>
        </select></div>
      <div class="frow"><label>${t('结束日（可空）')}</label><input id="evUntil" type="date" value="${init.untilDay >= 0 ? isoDate(init.untilDay) : ''}"></div>
    </div>
    <div class="wk-row hidden" id="evWkRow">${WEEK_CN.map((w, i) =>
      `<button class="wk ${(init.weekdays >> i) & 1 ? 'on' : ''}" data-i="${i}">${w}</button>`).join('')}</div>
    <div class="frow"><input id="evLoc" placeholder="${t('地点')}" value="${esc(init.location)}"></div>
    <div class="frow"><textarea id="evMemo" rows="2" placeholder="${t('备注')}">${esc(init.memo)}</textarea></div>
    </div>
    <div class="btns">
      ${isEdit ? '<button class="btn-ghost left" id="evDel" style="color:var(--red)">删除</button>' : ''}
      <button class="btn-ghost" id="evX">取消</button>
      <button class="btn-dark" id="evSave">保存</button>
    </div>`);

  const freqSel = d.querySelector('#evFreq');
  freqSel.value = String(init.freq);
  const syncVis = () => {
    const allDay = d.querySelector('#evAllDay').checked;
    d.querySelector('#evTimeWrap1').style.visibility = allDay ? 'hidden' : 'visible';
    d.querySelector('#evTimeWrap2').style.visibility = allDay ? 'hidden' : 'visible';
    d.querySelector('#evWkRow').classList.toggle('hidden', freqSel.value !== '2');
  };
  syncVis();
  d.querySelector('#evAllDay').onchange = syncVis;
  // P2-C2：渐进披露（与 App「显示详细设置」同一措辞）。编辑已填过的日程默认展开
  const advBox = d.querySelector('#evAdv'), advBtn = d.querySelector('#evAdvToggle');
  const hasAdv = isEdit && (init.location || init.memo || init.freq > 0 || (occ && occ.categoryUid && occ.categoryUid !== 'cat-default-1'));
  if (hasAdv) { advBox.classList.remove('hidden'); advBtn.classList.add('hidden'); }
  advBtn.onclick = () => { advBox.classList.remove('hidden'); advBtn.classList.add('hidden'); };
  // 节奏：弹窗一开光标就在标题上（对齐 Lifebear —— 少一次"从哪开始写"的停顿）
  setTimeout(() => { const el = d.querySelector('#evTitle'); if (el && !el.value) el.focus(); }, 60);
  freqSel.onchange = syncVis;
  d.querySelectorAll('.wk').forEach(b => b.onclick = () => b.classList.toggle('on'));
  d.querySelector('#evX').onclick = closeModal;

  d.querySelector('#evSave').onclick = () => {
    const title = d.querySelector('#evTitle').value.trim();
    if (!title) { toast('请填写日程名'); return; }
    const allDay = d.querySelector('#evAllDay').checked;
    const day = parseIso(d.querySelector('#evDate').value);
    if (day < 0) { toast('日期无效'); return; }
    const toMin = v => { const m = /^(\d{2}):(\d{2})$/.exec(v); return m ? +m[1] * 60 + +m[2] : 9 * 60; };
    let sm = toMin(d.querySelector('#evStart').value);
    let em = toMin(d.querySelector('#evEnd').value);
    if (em <= sm) em = Math.min(sm + 60, 1439);
    const freq = +freqSel.value;
    let weekdays = 0;
    d.querySelectorAll('.wk').forEach((b, i) => { if (b.classList.contains('on')) weekdays |= 1 << i; });
    if (freq === 2 && !weekdays) weekdays = 1 << (dow(day) - 1);
    const untilDay = parseIso(d.querySelector('#evUntil').value);
    const endDayIn = parseIso(d.querySelector('#evDateEnd').value);
    const payload = {
      title, categoryUid: d.querySelector('#evCat').value, allDay,
      startDay: day, endDay: Math.max(endDayIn >= 0 ? endDayIn : day, day), startMin: sm, endMin: em,
      location: d.querySelector('#evLoc').value.trim(), memo: d.querySelector('#evMemo').value.trim(),
      freq, interval: 1, weekdays, monthlyByWeekday: false,
      untilDay: untilDay >= 0 ? untilDay : -1,
      // A2：编辑时保留既有提醒（含 App 设置的 al 闹钟标记）——此前每次保存都被重置为默认，
      // App 侧精心设的"当成闹钟"会被网页一次编辑悄悄冲掉
      reminders: (isEdit && p && Array.isArray(p.reminders) && p.reminders.length)
        ? p.reminders : [{ m: 15, d: 0, t: 480, on: true }],
      exceptions: (isEdit && p && day === p.startDay) ? (p.exceptions || []) : []
    };
    put('event', isEdit ? occ.uid : uuid(), payload);
    closeModal(); toast('已保存');
  };

  if (isEdit) d.querySelector('#evDel').onclick = () => {
    closeModal();
    if (occ.recurring) {
      scopeDlg('删除哪些日程？', s => {
        const rec = S.data.event.get(occ.uid); if (!rec) return;
        const np = { ...rec.p };
        if (s === 0) {
          np.exceptions = [...(np.exceptions || []).filter(e => e.occ !== occ.occDay), { occ: occ.occDay, cancelled: true, newDay: -1 }];
          put('event', occ.uid, np);
        } else if (s === 1) {
          if (occ.occDay <= np.startDay) del('event', occ.uid);
          else { np.untilDay = occ.occDay - 1; np.exceptions = (np.exceptions || []).filter(e => e.occ < occ.occDay); put('event', occ.uid, np); }
        } else del('event', occ.uid);
        toast('已删除');
      });
    } else {
      confirmDlg(`删除「${occ.title}」？`, () => { del('event', occ.uid); toast('已删除'); });
    }
  };
}

/* ---------------- 待办（清单体系，对齐 App 二批） ---------------- */
const taskList = () => [...S.data.task.values()].map(r => ({ uid: r.uid, ...r.p }));
const taskLists = () => [...S.data.tasklist.values()].map(r => ({ uid: r.uid, ...r.p }))
  .sort((a, b) => (a.sort || 0) - (b.sort || 0));
let curList = 'all';   // all | starred | <listUid>
function toggleTask(uid) {
  const r = S.data.task.get(uid); if (!r) return;
  const done = !r.p.done;
  put('task', uid, { ...r.p, done, doneAt: done ? Date.now() : -1 });
}
function toggleStar(uid) {
  const r = S.data.task.get(uid); if (!r) return;
  put('task', uid, { ...r.p, starred: !r.p.starred });
}
function ensureDefaultList() {
  if (!S.data.tasklist.has('list-default')) {
    put('tasklist', 'list-default', { name: '我的清单', color: '#5C6670', sort: 0, archived: false, deletable: false });
  }
}
function renderTodos() {
  ensureDefaultList();
  const lists = taskLists().filter(l => !l.archived);
  const openCount = {};
  taskList().forEach(k => { if (!k.done) { const lu = k.listUid || 'list-default'; openCount[lu] = (openCount[lu] || 0) + 1; } });
  const starCount = taskList().filter(k => !k.done && k.starred).length;
  $('#listChips').innerHTML =
    `<button class="lchip ${curList === 'all' ? 'on' : ''}" data-l="all">${t('全部')}</button>` +
    `<button class="lchip ${curList === 'starred' ? 'on' : ''}" data-l="starred">⭐ ${starCount}</button>` +
    `<button class="lchip ${curList === 'next7' ? 'on' : ''}" data-l="next7">${t('未来7天')}</button>` +
    lists.map(l => `<button class="lchip ${curList === l.uid ? 'on' : ''}" data-l="${l.uid}">
      <span class="dot" style="background:${l.color || '#5C6670'}"></span>${esc(l.name)}${openCount[l.uid] ? ' ' + openCount[l.uid] : ''}</button>`).join('') +
    `<button class="lchip add" data-l="__new">＋</button>`;
  $$('#listChips .lchip').forEach(b => b.onclick = () => {
    if (b.dataset.l === '__new') {
      const name = prompt('清单名（如：购物 / 学习）'); if (!name) return;
      put('tasklist', uuid(), { name: name.trim(), color: LK_PALETTE[(6 + lists.length * 7) % 48], sort: lists.length + 1, archived: false, deletable: true });
      return;
    }
    curList = b.dataset.l; renderTodos();
  });

  let items = taskList();
  if (curList === 'next7') {   // P4-5：未来 7 天（含今天）有到期日的任务
    const t0 = todayEpoch();
    items = items.filter(k => (k.dueDay ?? -1) >= t0 && k.dueDay < t0 + 7)
                 .sort((a, b) => a.dueDay - b.dueDay);
  }
  else if (curList === 'starred') items = items.filter(k => k.starred);
  else if (curList !== 'all') items = items.filter(k => (k.listUid || 'list-default') === curList);
  const open = items.filter(k => !k.done)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || ((a.dueDay ?? -1) - (b.dueDay ?? -1)));
  const done = items.filter(k => k.done);
  const td = todayEpoch();
  const listColor = uid => (S.data.tasklist.get(uid || 'list-default')?.p?.color) || '#5C6670';
  const row = k => `<div class="todo-item ${k.done ? 'done' : ''}">
    <button class="ck" data-t="${k.uid}">${k.done ? '✓' : ''}</button>
    <span class="ldot" style="background:${listColor(k.listUid)}"></span>
    <span class="tt">${esc(k.title)}</span>
    ${(k.dueDay ?? -1) >= 0 ? `<span class="due ${!k.done && k.dueDay < td ? 'late' : ''}">${dateCn(k.dueDay)}</span>` : ''}
    <button class="star ${k.starred ? 'on' : ''}" data-s="${k.uid}">${k.starred ? '★' : '☆'}</button>
    <button class="del" data-x="${k.uid}">×</button></div>`;
  let html = open.map(row).join('');
  if (done.length) {
    html += `<div class="todo-sec">${t('已完成任务')} ${done.length} <button class="btn-mini" id="clearDone">${t('清除')}</button></div>`;
    html += done.map(row).join('');
  }
  if (!open.length && !done.length) html = `<div class="empty-deer"><img src="deer.svg" alt="">还没有任务，从上面添加一个吧</div>`;
  $('#todoList').innerHTML = html;
  $$('#todoList [data-t]').forEach(b => b.onclick = () => toggleTask(b.dataset.t));
  $$('#todoList [data-s]').forEach(b => b.onclick = () => toggleStar(b.dataset.s));
  $$('#todoList [data-x]').forEach(b => b.onclick = () => confirmDlg('删除这个任务？', () => del('task', b.dataset.x)));
  const cd = $('#clearDone');
  if (cd) cd.onclick = () => confirmDlg('清除全部已完成任务？', () => done.forEach(k => del('task', k.uid)), t('清除'));
}

/* ---------------- 笔记 ---------------- */
function renderNotes() {
  const list = [...S.data.note.values()].sort((a, b) => b.updated_at - a.updated_at);
  $('#noteList').innerHTML = list.length ? list.map(r => `
    <div class="note-item" data-n="${r.uid}">
      <div class="nt">${esc(r.p.title || '无标题')}</div>
      ${r.p.content ? `<div class="np">${esc(r.p.content.replace(/\n/g, ' '))}</div>` : ''}
      <div class="nd">${new Date(r.updated_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</div>
    </div>`).join('')
    : `<div class="empty-deer"><img src="deer.svg" alt="">还没有笔记</div>`;
  $$('#noteList [data-n]').forEach(el => el.onclick = () => openNoteModal(el.dataset.n));
}
function openNoteModal(uid) {
  const r = uid ? S.data.note.get(uid) : null;
  const d = modal(`
    <h3>${r ? '编辑笔记' : '新建笔记'}</h3>
    <div class="frow"><input id="nTitle" placeholder="标题" value="${esc(r?.p.title || '')}"></div>
    <div class="frow"><textarea id="nContent" rows="8" placeholder="开始写…">${esc(r?.p.content || '')}</textarea></div>
    <div class="btns">
      ${r ? '<button class="btn-ghost left" id="nDel" style="color:var(--red)">删除</button>' : ''}
      <button class="btn-ghost" id="nX">取消</button>
      <button class="btn-dark" id="nSave">保存</button>
    </div>`);
  d.querySelector('#nX').onclick = closeModal;
  d.querySelector('#nSave').onclick = () => {
    const title = d.querySelector('#nTitle').value.trim();
    const content = d.querySelector('#nContent').value;
    if (!title && !content.trim()) { closeModal(); return; }
    put('note', uid || uuid(), { title, content });
    closeModal(); toast('已保存');
  };
  if (r) d.querySelector('#nDel').onclick = () => { closeModal(); confirmDlg('删除这条笔记？', () => del('note', uid)); };
}

/* ---------------- 日记 ---------------- */
function renderDiary() {
  const list = [...S.data.diary.values()].sort((a, b) => (b.p.day || 0) - (a.p.day || 0));
  $('#diaryList').innerHTML = list.length ? list.map(r => `
    <div class="diary-item" data-d="${r.p.day}">
      <span class="mood">${MOODS[r.p.mood ?? 2]}</span>
      <div class="t" style="flex:1;min-width:0">
        <div class="nt" style="font-weight:600">${dateCn(r.p.day)}</div>
        <div class="np" style="color:var(--gray);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc((r.p.content || '').replace(/\n/g, ' '))}</div>
      </div>
    </div>`).join('')
    : `<div class="empty-deer"><img src="deer.svg" alt="">一天一页，从今天开始记录吧</div>`;
  $$('#diaryList [data-d]').forEach(el => el.onclick = () => openDiaryModal(+el.dataset.d));
}
function openDiaryModal(day) {
  const uid = 'diary-' + day;
  const r = S.data.diary.get(uid);
  let mood = r?.p.mood ?? 2;
  const d = modal(`
    <h3>${dateCn(day)} 的日记</h3>
    <div class="mood-row">${MOODS.map((e, i) => `<button class="mood ${i === mood ? 'on' : ''}" data-m="${i}">${e}</button>`).join('')}</div>
    <div class="frow"><textarea id="dContent" rows="8" placeholder="今天过得怎么样？">${esc(r?.p.content || '')}</textarea></div>
    <div class="btns">
      ${r ? '<button class="btn-ghost left" id="dDel" style="color:var(--red)">删除</button>' : ''}
      <button class="btn-ghost" id="dX">取消</button>
      <button class="btn-dark" id="dSave">保存</button>
    </div>`);
  d.querySelectorAll('.mood').forEach(b => b.onclick = () => {
    mood = +b.dataset.m;
    d.querySelectorAll('.mood').forEach(x => x.classList.toggle('on', +x.dataset.m === mood));
  });
  d.querySelector('#dX').onclick = closeModal;
  d.querySelector('#dSave').onclick = () => {
    const content = d.querySelector('#dContent').value;
    if (!content.trim()) { toast('写一点内容再保存吧'); return; }
    put('diary', uid, { day, mood, content });
    closeModal(); toast('已保存');
  };
  if (r) d.querySelector('#dDel').onclick = () => { closeModal(); confirmDlg('删除这天的日记？', () => del('diary', uid)); };
}

/* ---------------- 表情包（与 App 同一份 packs.json） ---------------- */
let STICKERS = null;
async function loadStickers() {
  if (STICKERS) return STICKERS;
  try { STICKERS = (await (await fetch('/stamps/packs.json')).json()).packs; }
  catch (e) { STICKERS = []; }
  return STICKERS;
}
async function openStickerModal(day) {
  const packs = await loadStickers();
  if (!packs.length) { toast('表情包加载失败'); return; }
  const nameOf = p => resolveLang() === 'en' ? p.en : (resolveLang() === 'zh-TW' ? p.tw : p.zh);
  let pi = 0, page = 0, sel = '';
  const d = modal(`<h3>${t('贴表情')} · ${dateCn(day)}</h3>
    <div id="stkGrid" class="stk-grid"></div>
    <div id="stkDots" class="stk-dots"></div>
    <div id="stkTabs" class="stk-tabs"></div>
    <div class="btns"><button class="btn-ghost" id="sX">${t('取消')}</button>
      <button class="btn-dark" id="sOk">${t('保存')}</button></div>`);
  const render = () => {
    const pack = packs[pi];
    const pages = [];
    for (let i = 0; i < pack.stamps.length; i += 10) pages.push(pack.stamps.slice(i, i + 10));
    page = Math.min(page, pages.length - 1);
    d.querySelector('#stkGrid').innerHTML = (pages[page] || []).map(st =>
      `<button class="stk ${sel === st.id ? 'on' : ''}" data-s="${st.id}">
         <img src="stamps/${st.id}.webp" alt=""><span>${esc(nameOf(st))}</span></button>`).join('');
    d.querySelector('#stkDots').innerHTML = pages.length > 1
      ? pages.map((_, i) => `<i class="${i === page ? 'on' : ''}" data-p="${i}"></i>`).join('') : '';
    d.querySelector('#stkTabs').innerHTML = packs.map((p, i) =>
      `<button class="stk-tab ${i === pi ? 'on' : ''}" data-t="${i}">
         <img src="stamps/${p.stamps[0].id}.webp" alt=""><span>${esc(nameOf(p))}</span></button>`).join('');
    d.querySelectorAll('[data-s]').forEach(b => b.onclick = () => { sel = b.dataset.s; render(); });
    d.querySelectorAll('[data-p]').forEach(b => b.onclick = () => { page = +b.dataset.p; render(); });
    d.querySelectorAll('[data-t]').forEach(b => b.onclick = () => { pi = +b.dataset.t; page = 0; render(); });
  };
  render();
  d.querySelector('#sX').onclick = closeModal;
  d.querySelector('#sOk').onclick = () => {
    if (!sel) { toast('先选一个表情'); return; }
    put('stamp', uuid(), { emoji: '🦌', day, eventUid: '', assetId: sel });
    closeModal(); toast(t('已贴上表情'));
  };
}

/* ---------------- 小鹿 AI ---------------- */
function agendaContext() {
  // A1-2（§48）：给每条标注 [e/t/n 序号]，AI 改/删只认这些序号；序号→uid 的映射存 S.aiRef。
  // 窗口扩到过去 7 天（「改昨天的」也要能定位）。
  const t = todayEpoch();
  S.aiRef = { e: {}, t: {}, n: {} };
  let ei = 0, ti = 0, ni = 0;
  const occs = expandEvents(t - 7, t + 14)
    .sort((a, b) => a.day - b.day || (a.allDay ? -1 : a.startMin) - (b.allDay ? -1 : b.startMin)).slice(0, 50);
  let s = '用户日程（近7天与未来14天，[e数字] 是它的 id）：\n';
  s += occs.length ? occs.map(o => {
    const seen = Object.keys(S.aiRef.e).find(k => S.aiRef.e[k] === o.uid);
    const id = seen || (S.aiRef.e[++ei] = o.uid, ei);
    return `- [e${id}] ${isoDate(o.day)} ${dateCn(o.day)} ${o.allDay ? '全天' : hm(o.startMin) + '-' + hm(o.endMin)} ${o.title}${o.recurring ? '（重复）' : ''}`;
  }).join('\n') + '\n' : '（暂无日程）\n';
  const open = taskList().filter(k => !k.done).slice(0, 30);
  s += '用户未完成任务（[t数字] 是它的 id）：\n';
  s += open.length ? open.map(k => {
    S.aiRef.t[++ti] = k.uid;
    return `- [t${ti}] ${k.title}${(k.dueDay ?? -1) >= 0 ? `（截止${dateCn(k.dueDay)}）` : ''}`;
  }).join('\n') + '\n' : '（无）\n';
  const ns = [...S.data.note.values()].slice(0, 10);
  if (ns.length) {
    s += '用户最近笔记（[n数字] 是它的 id）：\n';
    s += ns.map(r => { S.aiRef.n[++ni] = r.uid; return `- [n${ni}] ${r.p.title || (r.p.content || '').slice(0, 12)}`; }).join('\n') + '\n';
  }
  // A4：统计类问题也要能答真数据
  const mStart = (() => { const d = fromEpoch(t); return epochOf(d.y, d.m, 1); })();
  const dCnt = [...S.data.diary.values()].filter(r => (r.p.day ?? -1) >= mStart).length;
  s += `统计：本月日记 ${dCnt} 篇；未完成任务共 ${taskList().filter(k => !k.done).length} 项。`;
  return s;
}
function aiSystemPrompt() {
  const n = new Date();
  return `你是小鹿，Looka 手帐里的一只九色鹿，帮用户管理日程、任务、笔记与日记。
说话方式：温和、简短、不啰嗦。像一个安静的朋友，不像客服。一次说清一件事。
边界：不催促（几天没写日记也不要提）；不评判（推迟的事只陈述事实）；不确定时直说不知道；用户情绪低落时先接住情绪，别急着给建议。
你的名字来自敦煌壁画《鹿王本生图》里的九色鹿 —— 那个故事讲的是善良与守信。${S.nickname ? `\n用户的昵称是「${S.nickname}」。偶尔这样称呼他，但别每句都叫，那样很生硬。` : ''}
当前时间：${n.getFullYear()}年${n.getMonth() + 1}月${n.getDate()}日 周${WEEK_CN[dow(todayEpoch()) - 1]} ${String(n.getHours()).padStart(2, '0')}:${String(n.getMinutes()).padStart(2, '0')}

${agendaContext()}

${(() => { const f = JSON.parse(localStorage.getItem('lk_deer_facts') || '[]'); return f.length ? '你记住过的用户偏好：' + f.join('；') + '\n' : ''; })()}回复要求：简体中文、简洁友好、可少量 emoji；回答日程问题时优先引用上面的真实数据，不要编造。
当需要为用户创建、修改或删除内容时，先用一句话说明，然后在回复末尾输出一个 \`\`\`json 代码块（必须是合法 JSON）：
{"actions":[
 {"type":"create_event","title":"标题","date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","all_day":false},
 {"type":"create_task","title":"标题","due":"YYYY-MM-DD"},
 {"type":"create_note","title":"标题","content":"内容"},
 {"type":"update_event","id":3,"date":"YYYY-MM-DD","start":"HH:mm","title":"新标题"},
 {"type":"update_task","id":2,"due":"YYYY-MM-DD","done":true},
 {"type":"delete_event","id":3},
 {"type":"delete_task","id":2},
 {"type":"delete_note","id":1},
 {"type":"remember","fact":"用户告诉你的一条长期偏好"}
]}
remember 规则：只记长期有效的偏好或事实；一次性安排、情绪、秘密不要记；用户说「别记」时不要输出。
修改/删除规则（最重要）：id 只能用上面数据里方括号标注的数字（[e3] → id 是 3）；数据里找不到用户说的那条时，绝不要猜 id —— 直接问用户是哪一条，不输出 json；update 只写要改的字段；「完成了」某任务 = update_task {"done":true}。
其他规则：相对日期必须换算成具体日期；全天日程 all_day=true 并省略 start/end；用户只是提问时不要输出 json。`;
}
function chatBubble(role, text, err) {
  const list = $('#chatList');
  const hello = list.querySelector('.chat-hello');
  if (hello) hello.remove();
  const div = document.createElement('div');
  if (role === 'action') {
    div.className = 'msg action';
    div.innerHTML = `<div class="bubble">${esc(text)}</div>`;
  } else if (role === 'user') {
    div.className = 'msg user';
    div.innerHTML = `<div class="bubble">${esc(text)}</div>`;
  } else {
    div.className = 'msg ai';
    div.innerHTML = `<img class="avatar" src="deer.svg" alt=""><div class="bubble ${err ? 'err' : ''}">${esc(text)}</div>`;
  }
  list.appendChild(div);
  div.scrollIntoView({ behavior: 'smooth', block: 'end' });
  return div;
}
/* A3（§48）：撤销账本 —— 创建→del、修改→回写旧 payload、删除→put 回来 */
function aiUndoPush(op, kind, uid, prevP) { S.aiUndo.push({ op, kind, uid, prevP }); }
function aiUndo() {
  if (!S.aiUndo || !S.aiUndo.length) return;
  for (const r of S.aiUndo.reverse()) {
    if (r.op === 'created') del(r.kind, r.uid);
    else put(r.kind, r.uid, r.prevP);
  }
  chatBubble('action', `↩️ 已撤销刚才的修改`);
  S.aiUndo = [];
  const u = $('#aiUndoBar'); if (u) u.remove();
}
function showUndoBar() {
  const old = $('#aiUndoBar'); if (old) old.remove();
  const list = $('#chatList');
  const div = document.createElement('div');
  div.id = 'aiUndoBar'; div.className = 'msg action';
  div.innerHTML = `<div class="bubble" style="cursor:pointer">↩️ ${t('撤销刚才的修改')}</div>`;
  div.onclick = aiUndo;
  list.appendChild(div); div.scrollIntoView({ block: 'end' });
}
/* A3：删除必确认；一次 ≥3 条必确认（批量误建就是这么来的） */
function confirmThenExec(actions) {
  const needConfirm = actions.some(a => a.type && a.type.startsWith('delete_')) || actions.length >= 3;
  if (!needConfirm) { execActions(actions); return; }
  const label = a => {
    const del = a.type.startsWith('delete_');
    const kind = /event/.test(a.type) ? t('日程') : /task/.test(a.type) ? t('任务') : t('笔记');
    const verb = a.type.startsWith('create_') ? '' : del ? t('删除') + ' ' : t('修改') + ' ';
    const body = a.title || (a.id != null ? '#' + a.id : '') || (a.content || '').slice(0, 12);
    return `${verb}${kind} · ${esc(body)}${a.date ? ' · ' + a.date : ''}${a.start ? ' ' + a.start : ''}`;
  };
  modal(`<h3>${t('共 {0} 件事').replace('{0}', actions.length)}</h3>
    ${actions.map((a, i) => `<label style="display:block;font-size:13px;margin:4px 0${a.type.startsWith('delete_') ? ';color:#E0504A' : ''}">
      <input type="checkbox" class="aiActChk" data-i="${i}" checked> ${label(a)}</label>`).join('')}
    <div class="modal-btns"><button class="btn-mini" id="aiActCancel">${t('取消')}</button>
    <button class="btn-dark" id="aiActOk">${t('执行勾选的')}</button></div>`);
  $('#aiActCancel').onclick = () => { closeModal(); chatBubble('action', t('好，都不动 🦌')); };
  $('#aiActOk').onclick = () => {
    const picked = [...document.querySelectorAll('.aiActChk')].filter(c => c.checked).map(c => actions[+c.dataset.i]);
    closeModal();
    if (picked.length) execActions(picked); else chatBubble('action', t('好，都不动 🦌'));
  };
}
function execActions(actions) {
  const t0 = todayEpoch();
  S.aiUndo = [];
  // A1：改/删 id → uid 解析（只认 agendaContext 发出去的句柄，绝不按标题猜）
  const ref = S.aiRef || { e: {}, t: {}, n: {} };
  const uidOf = (a) => {
    const m = /event/.test(a.type) ? ref.e : /task/.test(a.type) ? ref.t : ref.n;
    return m[a.id] || null;
  };
  const toMin = v => { const m = /^(\d{1,2})[:：](\d{2})$/.exec(v || ''); return m ? +m[1] * 60 + +m[2] : -1; };
  for (const a of actions) {
    if (a.type === 'update_event' || a.type === 'delete_event') {
      const uid = uidOf(a); const rec = uid && S.data.event.get(uid);
      if (!rec) { chatBubble('action', `⚠️ ${t('没找到那条日程 —— 告诉我是哪一条？')}`); continue; }
      if (a.type === 'delete_event') {
        aiUndoPush('deleted', 'event', uid, { ...rec.p });
        del('event', uid);
        chatBubble('action', `🗑️ ${t('已删除日程')}：${dateCn(rec.p.startDay)} ${rec.p.title}${(rec.p.freq || 0) !== 0 ? t('（重复日程，整个系列已删除）') : ''}`);
      } else {
        aiUndoPush('updated', 'event', uid, { ...rec.p });
        const p = { ...rec.p };
        const nd = parseIso(a.date);
        if (nd >= 0) { const dur = (p.endDay ?? p.startDay) - p.startDay; p.startDay = nd; p.endDay = nd + Math.max(dur, 0); }
        const sm = toMin(a.start);
        if (sm >= 0) {
          const dur = Math.max((p.endMin || 0) - (p.startMin || 0), 30);
          p.startMin = sm; p.allDay = false;
          const em = toMin(a.end); p.endMin = em > sm ? em : Math.min(sm + dur, 1439);
        }
        if (a.title) p.title = a.title;
        if (a.location) p.location = a.location;
        if (a.memo) p.memo = a.memo;
        put('event', uid, p);
        chatBubble('action', `✏️ ${t('已修改')}：${dateCn(p.startDay)} ${p.allDay ? t('全天') : hm(p.startMin)} ${p.title}${(p.freq || 0) !== 0 ? t('（重复日程，整个系列一起调整）') : ''}`);
      }
      continue;
    }
    if (a.type === 'update_task' || a.type === 'delete_task') {
      const uid = uidOf(a); const rec = uid && S.data.task.get(uid);
      if (!rec) { chatBubble('action', `⚠️ ${t('没找到那条任务 —— 告诉我是哪一条？')}`); continue; }
      if (a.type === 'delete_task') {
        aiUndoPush('deleted', 'task', uid, { ...rec.p });
        del('task', uid);
        chatBubble('action', `🗑️ ${t('已删除任务')}：${rec.p.title}`);
      } else {
        aiUndoPush('updated', 'task', uid, { ...rec.p });
        const p = { ...rec.p };
        if (a.title) p.title = a.title;
        const nd = parseIso(a.due); if (nd >= 0) p.dueDay = nd;
        if (a.done === true) { p.done = true; p.doneAt = Date.now(); }
        if (a.done === false) { p.done = false; p.doneAt = -1; }
        put('task', uid, p);
        chatBubble('action', a.done === true ? `✅ ${t('已完成任务')}：${p.title}` : `✏️ ${t('已修改任务')}：${p.title}`);
      }
      continue;
    }
    if (a.type === 'update_note' || a.type === 'delete_note') {
      const uid = uidOf(a); const rec = uid && S.data.note.get(uid);
      if (!rec) { chatBubble('action', `⚠️ ${t('没找到那条笔记')}`); continue; }
      if (a.type === 'delete_note') {
        aiUndoPush('deleted', 'note', uid, { ...rec.p });
        del('note', uid);
        chatBubble('action', `🗑️ ${t('已删除笔记')}：${rec.p.title || (rec.p.content || '').slice(0, 10)}`);
      } else {
        aiUndoPush('updated', 'note', uid, { ...rec.p });
        const p = { ...rec.p };
        if (a.title) p.title = a.title;
        if (a.content) p.content = a.content;
        put('note', uid, p);
        chatBubble('action', `✏️ ${t('已修改笔记')}：${p.title || ''}`);
      }
      continue;
    }
    if (a.type === 'remember') {
      // D2（§52）：小鹿记事本（本地 + 随 settings 上云）
      const facts = JSON.parse(localStorage.getItem('lk_deer_facts') || '[]');
      if (a.fact && !facts.includes(a.fact)) {
        facts.push(a.fact);
        localStorage.setItem('lk_deer_facts', JSON.stringify(facts.slice(0, 30)));
      }
      chatBubble('action', `🦌 ${t('记住啦')}：${esc(a.fact || '')}`);
      continue;
    }
    if (a.type === 'create_event') {
      const day = parseIso(a.date) >= 0 ? parseIso(a.date) : t0;
      const sm = toMin(a.start);
      const allDay = a.all_day || sm < 0;
      const s2 = sm >= 0 ? sm : 9 * 60;
      let em = toMin(a.end); if (em <= s2) em = Math.min(s2 + 60, 1439);
      const evUid = uuid();
      aiUndoPush('created', 'event', evUid, null);
      put('event', evUid, {
        title: a.title || '未命名日程', categoryUid: 'cat-default-1', allDay,
        startDay: day, endDay: Math.max(parseIso(a.end_date), day) || day,
        startMin: s2, endMin: em, location: a.location || '', memo: a.memo || '',
        freq: 0, interval: 1, weekdays: 0, monthlyByWeekday: false, untilDay: -1,
        reminders: [{ m: 15, d: 0, t: 480, on: true }], exceptions: []
      });
      chatBubble('action', `✅ 已添加日程：${dateCn(day)} ${allDay ? '全天' : hm(s2)} ${a.title || ''}`);
    } else if (a.type === 'create_task') {
      const tkUid = uuid();
      aiUndoPush('created', 'task', tkUid, null);
      put('task', tkUid, { title: a.title || '未命名任务', done: false, dueDay: parseIso(a.due), memo: '', createdAt: Date.now() });
      chatBubble('action', `✅ 已添加任务：${a.title || ''}`);
    } else if (a.type === 'create_note') {
      const ntUid = uuid();
      aiUndoPush('created', 'note', ntUid, null);
      put('note', ntUid, { title: a.title || '', content: a.content || '' });
      chatBubble('action', `✅ 已添加笔记：${a.title || (a.content || '').slice(0, 10)}`);
    }
  }
  if (S.aiUndo.length) showUndoBar();
}
async function sendChat(text) {
  if (S.aiBusy || !text.trim()) return;
  S.aiBusy = true;
  chatBubble('user', text);
  S.chat.push({ role: 'user', content: text });
  const thinking = chatBubble('ai', '小鹿正在想…');
  try {
    const messages = [{ role: 'system', content: aiSystemPrompt() }, ...S.chat.slice(-12)];
    // T1（§53）：真流式 —— fetch + ReadableStream；T2：渲染在 ``` 处截断，绝不露动作 JSON
    const resp = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${S.token}` },
      body: JSON.stringify({ messages, temperature: 0.6, stream: true })
    });
    if (!resp.ok) {
      const eo = await resp.json().catch(() => ({}));
      throw new Error(eo.error || `HTTP ${resp.status}`);
    }
    thinking.remove();
    // G3/G4：从响应头拿账面
    S.antler = +(resp.headers.get('X-Antler-Total') ?? -1);
    const grantedToday = +(resp.headers.get('X-Antler-Granted-Today') ?? 0);
    if (grantedToday > 0) chatBubble('action', `+${grantedToday} 🦌 ${t('今天的鹿角到账啦')}`);
    updateQuota();
    const liveDiv = chatBubble('ai', '');
    const liveBubble = liveDiv.querySelector('.bubble');
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '', full = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split('\n'); buf = lines.pop();
      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (data === '[DONE]') continue;
        try {
          const delta = JSON.parse(data)?.choices?.[0]?.delta?.content || '';
          if (delta) {
            full += delta;
            // T2：截断在代码块前 —— 动作 JSON 逐字冒出也不给用户看
            liveBubble.textContent = full.split('```')[0].replace(/[`{]+$/, '');
            liveDiv.scrollIntoView({ block: 'end' });
          }
        } catch (e) { }
      }
    }
    const raw = full.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
    liveDiv.remove();   // 占位撤掉，下面按最终解析结果重新渲染
    let display = raw, actions = [];
    const fence = raw.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (fence) {
      display = raw.replace(fence[0], '').trim();
      try { actions = JSON.parse(fence[1]).actions || []; } catch (e) { }
    }
    if (display) { chatBubble('ai', display); S.chat.push({ role: 'assistant', content: display }); }
    if (actions.length) confirmThenExec(actions);
    if (!display && !actions.length) chatBubble('ai', '小鹿没想好怎么回答，换个说法试试？');
  } catch (e) {
    thinking.remove();
    chatBubble('ai', '小鹿出错了：' + e.message, true);
  } finally {
    S.aiBusy = false;
  }
}
function updateQuota() {
  const q = $('#aiQuota');
  // G4（§54）：只在余额吃紧时提示 —— 鹿角是够用的额度，不是要攒的资产
  if (S.antler >= 0 && S.antler < 10) {
    q.textContent = t('还剩 {0} 枚鹿角（明天自动补充）').replace('{0}', S.antler);
    q.classList.remove('hidden');
  } else q.classList.add('hidden');
}

/* ---------------- 渲染总控 ---------------- */
function renderAll() {
  if (S.tab === 'cal') renderCalendar();
  else if (S.tab === 'todo') renderTodos();
  else if (S.tab === 'note') renderNotes();
  else if (S.tab === 'diary') renderDiary();
}

/* ---------------- 账号 ---------------- */
function logoutLocal() {
  S.token = ''; localStorage.removeItem('lk_token');
  $('#appView').classList.add('hidden');
  $('#authView').classList.remove('hidden');
  // P1-9：落地页访问埋点（未登录才算；fetch 失败无所谓）
  try { fetch('/api/ev', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: 'landing_view' }) }); } catch (e) { }
}
S.nickname = localStorage.getItem('lk_nick') || '';
async function refreshMe() {
  try {
    const me = await api('/api/me');
    S.plan = me.plan;
    S.planExpiry = me.plan_expiry || 0;
    S.nickname = me.nickname || '';
    localStorage.setItem('lk_nick', S.nickname);
    // 有昵称就显示昵称 —— 用户看到的是自己起的名字
    const who = S.nickname || S.account;
    $('#menuPlan').textContent = `${who} · ${me.plan === 'pro' ? 'Pro · AI ' + t('不限次') : t('免费版') + ' · AI ' + t('每天 10 次')}`;
    // P2-A9（网页版）：付款等待中 → 开通即提示并清除等待
    if (me.plan === 'pro' && +localStorage.getItem('lk_pay_pending')) {
      localStorage.removeItem('lk_pay_pending');
      toast(t('✅ Pro 已开通，感谢支持小鹿 🦌'));
    }
    updateClaimVis();
  } catch (e) { }
}
const isPro = () => S.plan === 'pro' && (!S.planExpiry || Date.now() < S.planExpiry);
// A10：认领入口平时隐藏，只在付款超 2 分钟仍未到账时出现
function updateClaimVis() {
  const el = $('#mClaim'); if (!el) return;
  const pend = +localStorage.getItem('lk_pay_pending') || 0;
  el.classList.toggle('hidden', !(pend > 0 && Date.now() - pend > 2 * 60_000 && S.plan !== 'pro'));
}

/* ---------------- 启动与事件 ---------------- */
function enterApp() {
  $('#authView').classList.add('hidden');
  $('#appView').classList.remove('hidden');
  $('#btnAccount').textContent = (S.account.split('@')[0] || '我').slice(0, 8);
  loadCache();
  ensureDefaultCategories();
  renderAll();
  sync().catch(e => toast('同步失败：' + e.message));
  refreshMe();
}

async function boot() {
  await loadDict();
  applyingRemoteSettings = true;
  try { applyTheme(localStorage.getItem('lk_theme') || 0); } finally { applyingRemoteSettings = false; }
  // 静态文案按字典替换（tabs / 按钮 / 占位符）
  // 底栏按钮内含 SVG 图标，只能翻译里面的 <span>；直接写 textContent 会把图标一起抹掉
  $$('.bottombar .tab span').forEach(el => el.textContent = t(el.textContent.trim()));
  $('#todoInput').placeholder = t('搜索任务…') === '搜索任务…' ? '添加任务…' : t('添加任务…');
  document.documentElement.lang = resolveLang();

  // 语言切换
  const LANGS = [['auto','跟随系统 / Auto'],['zh-CN','简体中文'],['zh-TW','繁體中文'],['en','English']];
  $('#langPicker').innerHTML = LANGS.map(([v,l]) =>
    `<button class="btn-mini" data-lg="${v}" style="margin:4px">${l}${LANG === v ? ' ✓' : ''}</button>`).join('');
  $$('#langPicker [data-lg]').forEach(b => b.onclick = () => {
    localStorage.setItem('lk_lang', b.dataset.lg);
    location.reload();
  });
  $('#btnLang').onclick = () => { $('#langPicker').classList.toggle('hidden'); $('#themePicker').classList.add('hidden'); };
  $('#btnPanelClose').onclick = () => $('#dayPanel').classList.remove('open');

  // PWA：注册 Service Worker + 新版本提示条
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').then(reg => {
      reg.addEventListener('updatefound', () => {
        const nw = reg.installing;
        nw?.addEventListener('statechange', () => {
          if (nw.state === 'installed' && navigator.serviceWorker.controller) {
            $('#swBanner').classList.remove('hidden');
            $('#swReload').onclick = () => { nw.postMessage('skip'); location.reload(); };
          }
        });
      });
    }).catch(() => { });
  }
  // 主题选择器
  $('#themePicker').innerHTML = THEMES.map((t, i) =>
    `<button class="theme-dot" title="${t[0]}" data-i="${i}" style="background:${t[2]}"><span style="background:${t[1]}"></span></button>`).join('');
  $$('.theme-dot').forEach(b => b.onclick = () => { applyTheme(+b.dataset.i); });
  // 自创色板（与 App 同款 18 色敦煌矿物色系）
  const CUSTOM_COLORS = ['#8c4a3c','#b56a48','#c98a4b','#8f7b3e','#5f7a3d','#3e7a55',
    '#3e7a78','#3e6c8f','#4a5c9e','#6d55a8','#95538f','#ad5271',
    '#87695a','#6e7b6e','#5c6b7a','#444b52','#b08e4e','#7a5c9e'];
  const tp = $('#themePicker');
  if (tp && !tp.querySelector('.custom-dot')) {
    const wrap = document.createElement('div');
    wrap.style.cssText = 'width:100%;display:flex;flex-wrap:wrap;gap:8px;padding-top:8px;border-top:1px solid var(--hair)';
    wrap.innerHTML = CUSTOM_COLORS.map(c =>
      `<button class="custom-dot" data-c="${c}" style="width:26px;height:26px;border-radius:50%;border:2px solid transparent;background:${c}"></button>`).join('');
    tp.appendChild(wrap);
    wrap.querySelectorAll('.custom-dot').forEach(b => b.onclick = () => applyCustomTheme(b.dataset.c));
    // B6-lite（§48）：从照片取色生成主题（Pro）。取色在浏览器本地 canvas 完成，照片不上传。
    const photoBtn = document.createElement('button');
    photoBtn.className = 'custom-dot';
    photoBtn.title = t('从照片生成主题');
    photoBtn.style.cssText = 'width:26px;height:26px;border-radius:50%;border:1px dashed var(--hair);background:#fff;font-size:14px;line-height:1';
    photoBtn.textContent = '📷';
    tp.appendChild(photoBtn);
    const photoInput = document.createElement('input');
    photoInput.type = 'file'; photoInput.accept = 'image/*'; photoInput.style.display = 'none';
    tp.appendChild(photoInput);
    photoBtn.onclick = () => {
      if (S.plan !== 'pro') { const m = $('#mPro'); if (m && m.onclick) m.onclick(); return; }
      photoInput.click();
    };
    photoInput.onchange = () => {
      const f = photoInput.files && photoInput.files[0]; photoInput.value = '';
      if (!f) return;
      const img = new Image();
      img.onload = () => {
        // 64×64 降采样 → 32 级量化桶计数 → 按「数量×饱和度×中亮度」打分取前 3 个不同色相
        const cv = document.createElement('canvas'); cv.width = 64; cv.height = 64;
        const cx = cv.getContext('2d'); cx.drawImage(img, 0, 0, 64, 64);
        const d = cx.getImageData(0, 0, 64, 64).data;
        const bucket = {};
        for (let i = 0; i < d.length; i += 4) {
          const r = d[i], g = d[i + 1], b = d[i + 2];
          const k = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
          const e = bucket[k] || (bucket[k] = { n: 0, r: 0, g: 0, b: 0 });
          e.n++; e.r += r; e.g += g; e.b += b;
        }
        const cands = Object.values(bucket).map(e => {
          const r = e.r / e.n, g = e.g / e.n, b = e.b / e.n;
          const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
          const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
          const sat = mx === 0 ? 0 : (mx - mn) / mx;
          let h = 0;
          if (mx !== mn) {
            if (mx === r) h = ((g - b) / (mx - mn)) % 6; else if (mx === g) h = (b - r) / (mx - mn) + 2; else h = (r - g) / (mx - mn) + 4;
            h = (h * 60 + 360) % 360;
          }
          return { r, g, b, h, score: e.n * sat * (lum > 0.05 && lum < 0.75 ? 1 : 0) };
        }).filter(c => c.score > 0).sort((a, b) => b.score - a.score);
        const picked = [];
        for (const c of cands) {
          if (picked.some(x => Math.min(Math.abs(x.h - c.h), 360 - Math.abs(x.h - c.h)) < 24)) continue;
          picked.push(c); if (picked.length >= 3) break;
        }
        if (!picked.length) { toast(t('没取到合适的颜色，换一张色彩多一点的照片试试？')); return; }
        const hex = c => '#' + [c.r, c.g, c.b].map(v => Math.round(v).toString(16).padStart(2, '0')).join('');
        modal(`<h3>${t('照片里的颜色 🦌')}</h3>
          <p class="dim-note">${t('挑一套喜欢的，点一下就换上')}</p>
          <div style="display:flex;justify-content:space-evenly;padding:12px 0">
          ${picked.map(c => `<button class="photo-theme" data-c="${hex(c)}"
            style="width:56px;height:56px;border-radius:50%;border:1px solid #0003;background:${mixHex(hex(c), '#ffffff', 0.9)};display:flex;align-items:center;justify-content:center">
            <span style="width:30px;height:30px;border-radius:50%;background:${hex(c)}"></span></button>`).join('')}
          </div>
          <div class="modal-btns"><button class="btn-mini" id="photoThemeCancel">${t('取消')}</button></div>`);
        $('#photoThemeCancel').onclick = closeModal;
        document.querySelectorAll('.photo-theme').forEach(b => b.onclick = () => {
          applyCustomTheme(b.dataset.c); closeModal();
        });
      };
      img.src = URL.createObjectURL(f);
    };
  }
  applyingRemoteSettings = true;
  try { applyTheme(localStorage.getItem('lk_theme') || 0); } finally { applyingRemoteSettings = false; }

  // 登录/注册：单主按钮，文字链切换态；内测邀请码按服务端配置显隐
  let authMode = 'login';
  let registerMode = 'open';
  const syncAuthUi = () => {
    const reg = authMode === 'register';
    $('#btnAuthMain').textContent = reg ? t('注册') : t('登录');
    const refEl = $('#authRef'); if (refEl) refEl.classList.toggle('hidden', !reg);
    $('#authSwitchText').textContent = reg ? '已有账号？' : '还没有账号？';
    $('#authSwitchLink').textContent = reg ? t('登录') : t('注册');
    $('#authInvite').classList.toggle('hidden', !(reg && registerMode === 'invite'));
    $('#authForgot').parentElement.classList.toggle('hidden', reg);
  };
  api('/api/config').then(c => {
    registerMode = c.register_mode || 'open';
    syncAuthUi();
  }).catch(() => { });
  $('#authSwitchLink').onclick = () => { authMode = authMode === 'login' ? 'register' : 'login'; syncAuthUi(); };
  const doAuth = async () => {
    const isReg = authMode === 'register';
    const account = $('#authAccount').value.trim().toLowerCase();
    const pass = $('#authPass').value;
    const errEl = $('#authErr');
    errEl.classList.add('hidden');
    try {
      const body = { account, password: pass };
      if (isReg) {
        body.invite = $('#authInvite').value.trim();
        // §55 P5：朋友的邀请码（选填）—— 双方各 +100 鹿角
        const rc = $('#authRef').value.trim();
        if (rc) body.ref = rc;
      }
      const r = await api(isReg ? '/api/auth/register' : '/api/auth/login', body);
      S.token = r.token; S.account = r.account; S.plan = r.plan || 'free';
      localStorage.setItem('lk_token', S.token);
      localStorage.setItem('lk_account', S.account);
      localStorage.setItem(sinceKey(), '0');
      enterApp();
      toast(isReg ? '注册成功，欢迎来到 Looka 🦌' : t('登录成功'));
    } catch (e) {
      errEl.textContent = e.message; errEl.classList.remove('hidden');
    }
  };
  $('#btnAuthMain').onclick = doAuth;
  $('#authPass').addEventListener('keydown', e => { if (e.key === 'Enter') doAuth(); });
  syncAuthUi();
  $('#authForgot').onclick = async () => {
    const account = $('#authAccount').value.trim().toLowerCase() || prompt('输入你的账号（邮箱或手机号）') || '';
    if (!account) return;
    try {
      const r = await api('/api/auth/forgot', { account });
      toast(r.message || '如果可找回，重置邮件已发出');
    } catch (e) { toast(e.message); }
  };

  // 顶栏
  $('#btnPrev').onclick = () => { const p = addMonths(S.month.y, S.month.m, -1); calScrollToDay(epochOf(p.y, p.m, 15), true); };
  $('#btnNext').onclick = () => { const n = addMonths(S.month.y, S.month.m, 1); calScrollToDay(epochOf(n.y, n.m, 15), true); };
  $('#btnToday').onclick = () => {
    S.selDay = todayEpoch();
    const f = fromEpoch(S.selDay); S.month = { y: f.y, m: f.m };
    renderCalendar(S.selDay);   // 连续滚动：以今天为锚重定位
  };
  $('#btnTheme').onclick = () => $('#themePicker').classList.toggle('hidden');
  $('#btnAccount').onclick = e => { e.stopPropagation(); $('#accountMenu').classList.toggle('hidden'); refreshMe(); };
  document.addEventListener('click', e => {
    if (!e.target.closest('.account-wrap')) $('#accountMenu').classList.add('hidden');
  });
  $('#btnSyncNow').onclick = () => sync().then(() => toast('同步完成')).catch(e => toast('同步失败：' + e.message));
  $('#btnRedeem').onclick = async () => {
    const code = prompt('输入订阅兑换码（如 LOOKA-XXXX-XXXX）');
    if (!code) return;
    try {
      const r = await api('/api/redeem', { code: code.trim() });
      toast('兑换成功，已升级 ' + (r.plan || 'pro').toUpperCase() + ' 🎉');
      refreshMe();
    } catch (e) { toast(e.message); }
  };
  $('#btnLogout').onclick = async () => {
    try { await api('/api/auth/logout', {}); } catch (e) { }
    logoutLocal();
  };

  // Tab（底部菜单栏）
  function goTab(name, fromBar) {
    S.tab = name;
    // 日记/AI 从「更多」进入，底栏高亮保持在「更多」上，避免出现"没有一个是亮的"
    const barKey = ['diary', 'ai'].includes(name) ? 'more' : name;
    $$('.bottombar .tab').forEach(x => x.classList.toggle('active', x.dataset.tab === barKey));
    $$('.pane').forEach(pn => pn.classList.add('hidden'));
    const pane = $('#tab-' + name);
    if (pane) pane.classList.remove('hidden');
    $('#calNav').style.visibility = name === 'cal' ? 'visible' : 'hidden';
    renderAll();
  }
  window.__goTab = goTab;
  $$('.bottombar .tab').forEach(b => b.onclick = () => goTab(b.dataset.tab, true));
  $$('.more-row[data-go]').forEach(b => b.onclick = () => goTab(b.dataset.go));

  // 中央加号：按当前所在页新建对应的东西（与 App 一致）
  $('#bbPlus').onclick = () => {
    if (S.tab === 'todo') { const i = $('#todoInput'); i && i.focus(); }
    else if (S.tab === 'note') $('#btnAddNote').click();
    else if (S.tab === 'diary') $('#btnAddDiary').click();
    else openEventModal(null);
  };

  // 「更多」页里的动作：复用顶栏已有按钮，避免逻辑重复
  // 支持入口：中文→爱发电（预填 remark=账号，避免开通时对不上人），其余→Ko-fi
  const mPro = $('#mPro');
  const openPay = async () => {
    const zh = (localStorage.getItem('lk_lang') || navigator.language || 'zh').startsWith('zh');
    let url = 'https://ko-fi.com/summary/8389f40f-12d2-4d22-8ecb-32d91359dc4a';
    if (zh) {
      // LK 短码：备注预填、付款后服务端自动归属开通；接口不可达退回裸链接（还有订单号认领兜底）
      url = 'https://ifdian.net/order/create?plan_id=95141ca09d2711f1bead52540025c377&product_type=0';
      try { const r = await api('/api/pay/intent', { plan: 'month' }); if (r.url) url = r.url; } catch (e) { }
    }
    localStorage.setItem('lk_pay_pending', String(Date.now()));
    window.open(url, '_blank');
    toast(t('付款后切回本页，稍等片刻会自动开通'));
  };
  // F-7（§50 六）：开通前把权益与「到期后会怎样」说清楚 ——「你保留了」在前
  if (mPro) mPro.onclick = () => {
    modal(`<h3>Looka Pro</h3>
      <p class="dim-note">${t('AI 不限次 · 更聪明的小鹿 · 生成主题与表情包（规划中）· 标签与年度回顾（规划中）')}</p>
      <p style="font-size:12px;margin:8px 0 2px"><b>${t('到期后你保留')}</b>：${t('全部内容和数据 · 做过的主题 · 云同步与导出 · 提醒闹钟')}</p>
      <p style="font-size:12px;margin:2px 0 8px"><b>${t('到期后暂停')}</b>：${t('AI 不限次（改回每天 10 次）· 生成新主题 / 表情包')}</p>
      <p class="dim-note">${t('不偷偷扣钱：可随时取消，取消后立即不再扣款。')}</p>
      <div class="modal-btns"><button class="btn-mini" id="proCancel">${t('再想想')}</button>
      <button class="btn-dark" id="proGo">${t('去开通 · 12元/月')}</button></div>`);
    $('#proCancel').onclick = closeModal;
    $('#proGo').onclick = () => { closeModal(); openPay(); };
  };

  // G1/G2（§54）+ P4/P5（§55）：我的鹿角 —— 余额/明细/参考价/邀请码
  const mAntler = $('#mAntler');
  if (mAntler) mAntler.onclick = async () => {
    let bal = -1, rows = [], invite = '';
    try {
      const r = await api('/api/antler');
      bal = r.antler?.total ?? r.total ?? -1;
      rows = r.ledger || [];
    } catch (e) { }
    try { invite = (await api('/api/me')).invite_code || ''; } catch (e) { }
    const reasonName = x => ({
      daily_grant: t('每日到账'), monthly_grant: t('月度发放（旧）'), chat: t('和小鹿聊天'),
      milestone: t('里程碑奖励'), referral_new: t('邀请奖励'), referral_inviter: t('邀请奖励')
    }[x] || x);
    modal(`<h3>🦌 ${bal >= 0 ? bal : '…'}</h3>
      <p class="dim-note">${t('每天自动到账（免费 10 枚 / Pro 50 枚），不用签到、断了也不罚')}<br>
      ${t('参考价：100 枚 ≈ ¥6（暂不出售 —— 每天送的就够用）')}</p>
      ${invite ? `<p style="font-size:13px"><b>${t('我的邀请码')}：${invite}</b><br>
        <span class="dim-note">${t('朋友注册时填上它，你们各得 100 枚 🦌')}</span></p>` : ''}
      <div style="max-height:200px;overflow:auto">
      ${rows.slice(0, 30).map(r => `<p style="font-size:12px;margin:3px 0;display:flex;justify-content:space-between">
        <span>${reasonName(r.reason)}</span><span>${r.delta > 0 ? '+' : ''}${r.delta}</span></p>`).join('') ||
        `<p class="dim-note">${t('还没有记录')}</p>`}
      </div>
      <div class="modal-btns"><button class="btn-mini" id="antlerClose">${t('知道了')}</button></div>`);
    $('#antlerClose').onclick = closeModal;
  };

  // 昵称：小鹿怎么称呼你（与自知录共用同一账号的昵称）
  const mNick = $('#mNick');
  if (mNick) mNick.onclick = () => {
    modal(`<h3>${t('怎么称呼你？')}</h3>
      <input id="nickInput" type="text" maxlength="20" placeholder="${t('最多 20 个字')}" style="width:100%" value="${esc(S.nickname || '')}">
      <p class="dim-note">${t('小鹿会这样叫你。留空就用账号显示。')}</p>
      <div class="modal-btns"><button class="btn-mini" id="nickCancel">${t('取消')}</button>
      <button class="btn-dark" id="nickOk">${t('保存')}</button></div>`);
    $('#nickCancel').onclick = closeModal;
    $('#nickOk').onclick = async () => {
      const v = $('#nickInput').value.trim();
      try {
        const r = await api('/api/me/nickname', { nickname: v });
        S.nickname = r.nickname || '';
        localStorage.setItem('lk_nick', S.nickname);
        closeModal(); toast(t('好的 🦌')); refreshMe();
      } catch (e) { toast(e.message || t('保存失败')); }
    };
  };

  // A1-6：清理重复日程（同标题同日同时间同全天同重复 = 重复组；保最早，删其余；走同步双端一并生效）
  const mDedup = $('#mDedup');
  if (mDedup) mDedup.onclick = () => {
    const groups = {};
    for (const rec of S.data.event.values()) {
      const p = rec.p; if (!p || p.title === undefined) continue;
      const k = [String(p.title).trim(), p.startDay, p.startMin || 0, !!p.allDay, p.freq || 0].join('|');
      (groups[k] = groups[k] || []).push(rec);
    }
    const dups = Object.values(groups).filter(g => g.length > 1)
      .map(g => g.sort((a, b) => a.updated_at - b.updated_at));
    if (!dups.length) { toast(t('没有发现重复的日程 🦌')); return; }
    const extra = dups.reduce((n, g) => n + g.length - 1, 0);
    modal(`<h3>${t('清理重复日程')}</h3>
      <p class="dim-note">${t('发现 {0} 组重复，将保留每组最早一条，删除其余 {1} 条：').replace('{0}', dups.length).replace('{1}', extra)}</p>
      ${dups.slice(0, 8).map(g => `<p style="font-size:12px;margin:2px 0">· ${dateCn(g[0].p.startDay)} ${esc(g[0].p.title)} ×${g.length}</p>`).join('')}
      <div class="modal-btns"><button class="btn-mini" id="dedupCancel">${t('取消')}</button>
      <button class="btn-dark" id="dedupOk">${t('清理')}</button></div>`);
    $('#dedupCancel').onclick = closeModal;
    $('#dedupOk').onclick = () => {
      dups.forEach(g => g.slice(1).forEach(rec => del('event', rec.uid)));
      closeModal(); toast(t('已清理 {0} 条重复日程').replace('{0}', extra));
    };
  };

  // 认领订单：粘贴爱发电订单号 → 服务端反查开通（不依赖备注的唯一兜底）
  const mClaim = $('#mClaim');
  if (mClaim) mClaim.onclick = () => {
    modal(`<h3>${t('认领爱发电订单')}</h3>
      <p class="dim-note">${t('打开爱发电 → 我的 → 订单，复制那笔订单的「订单号」粘贴到这里。')}</p>
      <input id="claimNo" type="text" placeholder="${t('订单号')}" style="width:100%">
      <div class="modal-btns"><button class="btn-mini" id="claimCancel">${t('取消')}</button>
      <button class="btn-dark" id="claimOk">${t('认领')}</button></div>`);
    $('#claimCancel').onclick = closeModal;
    $('#claimOk').onclick = async () => {
      const no = $('#claimNo').value.trim();
      if (no.length < 6) { toast(t('请输入完整的爱发电订单号')); return; }
      try {
        await api('/api/pay/claim', { order_no: no });
        localStorage.removeItem('lk_pay_pending');
        closeModal(); toast(t('认领成功，Pro 已开通 🎉'));
        refreshMe();
      } catch (e) { toast(e.message || t('认领失败')); }
    };
  };

  // 搜索（对齐 App /search）：日程 / 任务 / 笔记 / 日记全文匹配
  const mSearch = $('#mSearch');
  if (mSearch) mSearch.onclick = () => {
    modal(`<h3>${t('搜索')}</h3>
      <input id="searchQ" type="text" placeholder="${t('搜索日程、任务、笔记…')}" style="width:100%">
      <div id="searchOut" class="search-out"></div>`);
    const q = $('#searchQ'); q.focus();
    q.oninput = () => {
      const kw = q.value.trim().toLowerCase();
      const out = $('#searchOut');
      if (kw.length < 1) { out.innerHTML = ''; return; }
      const hits = [];
      for (const r of S.data.event.values()) {
        const p = r.p; if (!p) continue;
        if ((p.title || '').toLowerCase().includes(kw) || (p.note || '').toLowerCase().includes(kw))
          hits.push({ k: t('日程'), text: p.title, day: p.day });
      }
      for (const r of S.data.task.values()) {
        const p = r.p; if (!p) continue;
        if ((p.title || '').toLowerCase().includes(kw)) hits.push({ k: t('任务'), text: p.title, day: p.dueDay });
      }
      for (const r of S.data.note.values()) {
        const p = r.p; if (!p) continue;
        if ((p.title || '').toLowerCase().includes(kw) || (p.body || '').toLowerCase().includes(kw))
          hits.push({ k: t('笔记'), text: p.title || (p.body || '').slice(0, 30) });
      }
      for (const r of S.data.diary.values()) {
        const p = r.p; if (!p) continue;
        if ((p.body || '').toLowerCase().includes(kw)) hits.push({ k: t('日记'), text: (p.body || '').slice(0, 30), day: p.day });
      }
      out.innerHTML = hits.length
        ? hits.slice(0, 50).map(h => {
            const d = h.day != null && h.day >= 0 ? fromEpoch(h.day) : null;
            return `<div class="search-hit"${h.day != null && h.day >= 0 ? ` data-day="${h.day}"` : ''}>
              <span class="tagk">${h.k}</span>${esc(h.text || '')}${d ? `<span class="hitday">${d.m}/${d.d}</span>` : ''}</div>`;
          }).join('')
        : `<p class="dim-note">${t('没有找到相关内容')}</p>`;
      out.querySelectorAll('.search-hit[data-day]').forEach(el => el.onclick = () => {
        S.selDay = +el.dataset.day;
        closeModal();
        document.querySelector('[data-tab="cal"]').click();
        renderCalendar(S.selDay);
      });
    };
  };

  // P4-7：备份恢复 —— 导入我们自己导出的 JSON（uid 相同则覆盖，全部标脏待同步）
  const mImport = $('#mImport');
  if (mImport) mImport.onclick = () => {
    const inp = document.createElement('input');
    inp.type = 'file'; inp.accept = 'application/json';
    inp.onchange = async () => {
      const f = inp.files[0]; if (!f) return;
      try {
        const j = JSON.parse(await f.text());
        const data = j.data || j;
        let n = 0;
        for (const kind of KINDS) {
          if (kind === 'settings') continue;
          for (const it of (data[kind] || [])) {
            if (!it.uid) continue;
            const { uid, ...payload } = it;
            put(kind, uid, payload); n++;
          }
        }
        toast(t('已导入 {0} 条，正在同步…').replace('{0}', n));
        renderCalendar(); renderTodos(); renderNotes(); renderDiary();
      } catch (e) { toast(t('文件格式不对：请选择 Looka 导出的 JSON')); }
    };
    inp.click();
  };

  // 数据导出（免费权益）：本地数据一键存成 JSON
  const mExport = $('#mExport');
  if (mExport) mExport.onclick = () => {
    const dump = {};
    for (const [kind, m] of Object.entries(S.data)) {
      dump[kind] = [...m.values()].map(r => ({ uid: r.uid, ...r.p }));
    }
    const blob = new Blob([JSON.stringify({ app: 'looka', exported_at: new Date().toISOString(), data: dump }, null, 2)],
      { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'looka-export-' + new Date().toISOString().slice(0, 10) + '.json';
    a.click(); URL.revokeObjectURL(a.href);
    toast(t('已导出'));
  };

  const relay = { mTheme: 'btnTheme', mLang: 'btnLang', mSync: 'btnSyncNow', mRedeem: 'btnRedeem', mLogout: 'btnLogout' };
  Object.entries(relay).forEach(([from, to]) => {
    const el = $('#' + from);
    if (el) el.onclick = () => $('#' + to)?.click();
  });

  if (matchMedia('(display-mode: standalone)').matches || window.navigator.standalone) {
    document.getElementById('afdianLeaflet')?.closest('.landing-support')?.remove();
  }

  // P2-C3：顶栏/底栏高度实测写入 CSS 变量（布局不再猜数字）
  function setLayoutVars() {
    const tb = document.querySelector('.topbar');
    const bb = document.querySelector('.bottombar');
    const r = document.documentElement.style;
    if (tb) r.setProperty('--tb', tb.offsetHeight + 'px');
    if (bb) r.setProperty('--bb', bb.offsetHeight + 'px');
  }
  setLayoutVars();
  window.addEventListener('resize', setLayoutVars);

  // P2-A7：回到前台刷订阅状态。付款等待期每次都刷；平时 5 分钟节流
  let lastMeAt = 0;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || !S.token) return;
    const pending = +localStorage.getItem('lk_pay_pending') > 0;
    if (pending || Date.now() - lastMeAt > 5 * 60_000) {
      lastMeAt = Date.now();
      refreshMe();
    }
    updateClaimVis();
  });

  // iOS「添加到主屏幕」引导：Safari 不会自动提示，且只有装到桌面才能全屏 + 收通知
  (function iosInstallTip() {
    const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
    const standalone = window.navigator.standalone || matchMedia('(display-mode: standalone)').matches;
    if (!isIOS || standalone || localStorage.getItem('lk_ios_tip') === '1') return;
    const box = document.createElement('div');
    box.className = 'ios-tip';
    box.innerHTML = '<b>装到桌面更好用</b><br>点底部 <b>分享</b> → 选「添加到主屏幕」，即可全屏使用。<button id="iosTipX">知道了</button>';
    document.body.appendChild(box);
    box.querySelector('#iosTipX').onclick = () => { localStorage.setItem('lk_ios_tip', '1'); box.remove(); };
  })();

  // 日历
  $('#btnAddEvent').onclick = () => openEventModal(null);
  $('#btnAddStamp').onclick = () => openStickerModal(S.selDay);
  $('#btnAddDiary2').onclick = () => openDiaryModal(S.selDay);   // P4-1：日面板直达写日记（对齐 App 📖）

  // 待办
  const addTodo = () => {
    const v = $('#todoInput').value.trim();
    if (!v) return;
    const listUid = (curList !== 'all' && curList !== 'starred') ? curList : 'list-default';
    put('task', uuid(), { title: v, done: false, dueDay: -1, memo: '', createdAt: Date.now(),
      listUid, starred: curList === 'starred', doneAt: -1, sortOrder: Date.now() });
    $('#todoInput').value = '';
  };
  $('#btnAddTodo').onclick = addTodo;
  $('#todoInput').addEventListener('keydown', e => { if (e.key === 'Enter') addTodo(); });

  // 笔记 / 日记
  $('#btnAddNote').onclick = () => openNoteModal(null);
  $('#btnAddDiary').onclick = () => openDiaryModal(todayEpoch());

  // AI
  // 模型档位（与 App 同构：标准不限次 / 高级 GPT）
  const tierBar = document.getElementById('tierBar');
  if (tierBar) {
    const setTier = v => {
      localStorage.setItem('lk_tier', v);
      tierBar.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.tier === v));
    };
    tierBar.querySelectorAll('button').forEach(b => b.onclick = () => setTier(b.dataset.tier));
    setTier(localStorage.getItem('lk_tier') || 'standard');
  }

  const send = () => { const v = $('#chatText').value.trim(); if (v) { $('#chatText').value = ''; sendChat(v); } };
  $('#btnSend').onclick = send;
  $('#chatText').addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
  $$('.chip').forEach(b => b.onclick = () => sendChat(b.dataset.q));

  // 周期同步
  setInterval(() => { if (S.token) sync().catch(() => { }); }, 60000);
  window.addEventListener('focus', () => { if (S.token) sync().catch(() => { }); });

  if (S.token) enterApp();
  else $('#authView').classList.remove('hidden');
}

boot();

// ============================================================
// 自知录 · 极简功过格 —— 前端逻辑
// ============================================================
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
// 图标：引用 index.html 里的内联 sprite
const ic = name => `<svg class="ic" aria-hidden="true"><use href="#i-${name}"/></svg>`;

let ME = null;
let NOTES = [];          // 已加载记录（倒序，分页追加）
let DAYS = {};           // day -> {summary, mood}
let HAS_MORE = false;    // 是否还有更早的记录
let composeAtts = [];    // 待发布附件
let composeKind = 'note';
let composeDay = null;   // 记录归属日（支持补记过去）
let polishBackup = null; // 润色前的原文
let calYM = null;        // 月历当前显示的 YYYY-MM
let JUST_SAVED_ID = 0;   // 刚记下的那条，用于落笔动画
let AI_LEFT = null;      // 今日 AI 剩余次数
let PLAZA = [];          // 广场内容
let PLAZA_MORE = false;

// 本地日期 YYYY-MM-DD（功过归属日以用户本地时区为准）
function localDay(d = new Date()) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
// 修行日的分界：凌晨 3 点前补记算前一天
function effectiveToday() {
  const d = new Date();
  if (d.getHours() < 3) d.setDate(d.getDate() - 1);
  return localDay(d);
}

// ---------- 请求封装 ----------
async function api(method, url, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const r = await fetch(url, opt);
  let j = {};
  try { j = await r.json(); } catch {}
  if (!r.ok) throw new Error(j.error || `请求失败（${r.status}）`);
  return j;
}

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add('hidden'), 2600);
}

// 是否应当省略动效（尊重系统「减少动态效果」）
const reduceMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

// ---------- 确认弹层（替代系统 confirm，保持纸墨调性） ----------
function confirmAsk(message, sub = '', okText = '删除') {
  return new Promise(resolve => {
    const box = $('#confirm-overlay');
    $('#confirm-msg').innerHTML = esc(message) + (sub ? `<span class="confirm-sub">${esc(sub)}</span>` : '');
    $('#confirm-yes').textContent = okText;
    box.classList.remove('hidden');
    const done = v => {
      box.classList.add('hidden');
      $('#confirm-yes').onclick = $('#confirm-no').onclick = box.onclick = null;
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = e => { if (e.key === 'Escape') done(false); if (e.key === 'Enter') done(true); };
    $('#confirm-yes').onclick = () => done(true);
    $('#confirm-no').onclick = () => done(false);
    box.onclick = e => { if (e.target === box) done(false); };
    document.addEventListener('keydown', onKey);
    $('#confirm-no').focus();
  });
}

// ---------- 文字逐字浮现（像慢慢写出来） ----------
function typeInto(el, text, speed = 34) {
  if (reduceMotion()) { el.textContent = text; return Promise.resolve(); }
  return new Promise(resolve => {
    el.textContent = '';
    el.classList.add('typing');
    let i = 0;
    const step = () => {
      el.textContent = text.slice(0, ++i);
      if (i < text.length) setTimeout(step, speed);
      else { el.classList.remove('typing'); resolve(); }
    };
    setTimeout(step, 120);
  });
}

// ---------- 数字滚动 ----------
function countUp(el, to, dur = 520) {
  const from = Number(el.textContent.replace(/[^\d-]/g, '')) || 0;
  const sign = el.dataset.sign === '1';
  const fmt = v => (sign && v > 0 ? '+' : '') + v;
  if (from === to || reduceMotion()) { el.textContent = fmt(to); return; }
  el.classList.add('ticking');
  setTimeout(() => el.classList.remove('ticking'), 360);
  const t0 = performance.now();
  const tick = now => {
    const p = Math.min(1, (now - t0) / dur);
    const eased = 1 - Math.pow(1 - p, 3);
    el.textContent = fmt(Math.round(from + (to - from) * eased));
    if (p < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

// ---------- 视图切换 ----------
function show(view) {
  $('#view-auth').classList.toggle('hidden', view !== 'auth');
  $('#view-main').classList.toggle('hidden', view !== 'main');
}

// 主界面里的三个页签：录 / 广场 / 我的
let TAB = 'record';
function setTab(tab) {
  TAB = tab;
  ['record', 'plaza', 'mine'].forEach(t => {
    $('#page-' + t).classList.toggle('hidden', t !== tab);
    $('#tab-' + t).classList.toggle('on', t === tab);
  });
  document.body.classList.toggle('on-record', tab === 'record');
  if (tab === 'plaza' && !PLAZA.length) loadPlaza();
  if (tab === 'mine') renderMine();
  window.scrollTo({ top: 0, behavior: reduceMotion() ? 'auto' : 'smooth' });
}

// 今日 AI 余量显示
function updateAiMeter() {
  const el = $('#ai-meter');
  if (!el || AI_LEFT === null) return;
  el.textContent = `今日 AI 还可用 ${AI_LEFT} 次`;
  el.classList.toggle('low', AI_LEFT <= 10);
}

// ---------- 登录 / 注册 ----------
let authMode = 'login';
function setAuthMode(m) {
  authMode = m;
  $('#tab-login').classList.toggle('on', m === 'login');
  $('#tab-register').classList.toggle('on', m === 'register');
  $('#auth-submit').textContent = m === 'login' ? '登 录' : '注 册';
  $('#invite-label').classList.toggle('hidden', m !== 'register');
  $('#auth-error').textContent = '';
}
$('#tab-login').onclick = () => setAuthMode('login');
$('#tab-register').onclick = () => setAuthMode('register');

$('#auth-form').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#auth-submit');
  btn.disabled = true;
  $('#auth-error').textContent = '';
  try {
    const j = await api('POST', '/api/' + authMode, {
      account: $('#auth-account').value.trim(),
      password: $('#auth-password').value,
      invite: $('#auth-invite').value.trim()
    });
    ME = j.user;
    enterMain();
  } catch (err) {
    $('#auth-error').textContent = err.message;
  } finally { btn.disabled = false; }
};

// ---------- 主界面 ----------
async function enterMain() {
  show('main');
  setTab('record');
  $('#skeleton').classList.remove('hidden');   // 首次载入先显示骨架，避免白屏
  try { await loadNotes(); }
  finally { $('#skeleton').classList.add('hidden'); }
}

async function loadNotes(q = '') {
  const j = await api('GET', '/api/notes' + (q ? '?q=' + encodeURIComponent(q) : ''));
  NOTES = j.notes;
  DAYS = j.days || DAYS;
  HAS_MORE = !q && !!j.hasMore;
  $('#btn-older').classList.toggle('hidden', !HAS_MORE);
  renderMonthStat();
  renderTimeline(!!q);
  if ($('#cal-wrap').classList.contains('open')) renderCalendar();
}

// 载入更早的记录（键集分页）
$('#btn-older').onclick = async () => {
  if (!NOTES.length) return;
  const btn = $('#btn-older');
  btn.disabled = true; btn.classList.add('busy');
  try {
    const before = NOTES[NOTES.length - 1].created_at;
    const j = await api('GET', '/api/notes?before=' + encodeURIComponent(before));
    NOTES = NOTES.concat(j.notes);
    HAS_MORE = !!j.hasMore;
    btn.classList.toggle('hidden', !HAS_MORE);
    renderTimeline(false);
    if ($('#cal-wrap').classList.contains('open')) renderCalendar();
  } catch (e) { toast(e.message); }
  finally { btn.disabled = false; btn.classList.remove('busy'); }
};

// ---------- 本月统计 + 连续天数 ----------
function monthName(ym) {
  const m = Number(ym.slice(5, 7));
  const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二'];
  return CN[m - 1] + '月';
}
function calcStreak() {
  const days = new Set(NOTES.map(n => n.day));
  const d = new Date();
  if (!days.has(localDay(d))) d.setDate(d.getDate() - 1);   // 今天还没记，从昨天起算
  let streak = 0;
  while (days.has(localDay(d))) { streak++; d.setDate(d.getDate() - 1); }
  return streak;
}
// 只计件不计分（《自知录》序：上士「书可也，不书可也」，重在自知而非积分）
function renderMonthStat() {
  const ym = localDay().slice(0, 7);
  let merit = 0, fault = 0;
  const daysSet = new Set();
  for (const n of NOTES) {
    if (!n.day.startsWith(ym)) continue;
    daysSet.add(n.day);
    if (n.kind === 'merit') merit++;
    if (n.kind === 'fault') fault++;
  }
  const streak = calcStreak();
  const box = $('#month-stat');
  const vals = { merit, fault, days: daysSet.size, streak };
  const sameShape = box.dataset.shape === String(streak > 1);
  if (!sameShape) {
    box.dataset.shape = String(streak > 1);
    box.innerHTML = `${monthName(ym)}
      <span class="sep">·</span>功 <b class="z" data-k="merit">0</b> 件
      <span class="sep">·</span>过 <b data-k="fault">0</b> 件
      <span class="sep">·</span>记 <b data-k="days">0</b> 天${streak > 1 ? `<span class="sep">·</span>连 <b data-k="streak">0</b> 天` : ''}
      <span class="caret">${ic('down')}</span>`;
  }
  for (const [k, v] of Object.entries(vals)) {
    const el = box.querySelector(`[data-k="${k}"]`);
    if (el) countUp(el, v);
  }
}

// ---------- 月历：点统计行展开 ----------
$('#month-stat').onclick = () => {
  const wrap = $('#cal-wrap');
  const open = wrap.classList.toggle('open');
  $('#month-stat').classList.toggle('open', open);
  $('#month-stat').setAttribute('aria-expanded', String(open));
  if (open) { calYM = localDay().slice(0, 7); renderCalendar(); }
};
function shiftMonth(delta) {
  const [y, m] = calYM.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  calYM = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  renderCalendar();
}
function renderCalendar() {
  const [y, m] = calYM.split('-').map(Number);
  // 每天功过汇总
  const map = {};
  for (const n of NOTES) {
    if (!n.day.startsWith(calYM)) continue;
    const d = map[n.day] || (map[n.day] = { merit: 0, fault: 0 });
    if (n.kind === 'merit') d.merit++;
    if (n.kind === 'fault') d.fault++;
  }
  const first = new Date(y, m - 1, 1);
  const daysInMonth = new Date(y, m, 0).getDate();
  const today = localDay();
  let cells = '', lit = 0;
  for (let i = 0; i < first.getDay(); i++) cells += '<div></div>';
  for (let d = 1; d <= daysInMonth; d++) {
    const key = `${calYM}-${String(d).padStart(2, '0')}`;
    const info = map[key];
    let dots = '<span class="cal-dots"></span>';
    if (info) {
      const m = info.merit ? `<span class="r" style="--i:${lit++}"></span>` : '';
      const f = info.fault ? `<span class="d" style="--i:${lit++}"></span>` : '';
      dots = `<span class="cal-dots">${m}${f}</span>`;
    }
    cells += `<div class="cal-cell${info ? ' has' : ''}${key === today ? ' today' : ''}" data-day="${key}">${d}${dots}</div>`;
  }
  $('#calendar').innerHTML = `
    <div class="cal-head">
      <button data-nav="-1" class="icon-btn plain" aria-label="上个月">${ic('left')}</button>
      <span>${y}年${monthName(calYM)}</span>
      <button data-nav="1" class="icon-btn plain" aria-label="下个月">${ic('right')}</button>
    </div>
    <div class="cal-grid">
      ${'日一二三四五六'.split('').map(w => `<div class="cal-wd">${w}</div>`).join('')}
      ${cells}
    </div>
    <div class="cal-foot">
      <button id="btn-month-report" class="preset-entry">${ic('scroll')}<span>${monthName(calYM)}省察</span></button>
    </div>`;
  $('#calendar').querySelectorAll('[data-nav]').forEach(b => b.onclick = () => shiftMonth(Number(b.dataset.nav)));
  $('#btn-month-report').onclick = () => monthReport(calYM);
  $('#calendar').querySelectorAll('.cal-cell.has').forEach(c => c.onclick = () => {
    document.querySelector(`.day-group[data-day="${c.dataset.day}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
}

// ---------- 时间线 ----------
function dayLabel(day) {
  const [y, m, d] = day.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const week = '日一二三四五六'[date.getDay()];
  const base = `${m}月${d}日 · 周${week}`;
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const diff = Math.round((today - date) / 86400000);
  if (diff === 0) return '今天 · ' + base;
  if (diff === 1) return '昨天 · ' + base;
  return (y !== today.getFullYear() ? y + '年' : '') + base;
}

function renderTimeline(isSearch) {
  const box = $('#timeline');
  box.innerHTML = '';
  const hint = $('#empty-hint');
  hint.classList.toggle('hidden', NOTES.length > 0);
  if (isSearch && !NOTES.length) {
    hint.innerHTML = '没有找到相关记录';
  } else if (!NOTES.length) {
    // 新用户引导：直接给一条入口，而不是让他对着空白发呆
    hint.innerHTML = `录上还是空的。<br>从今天第一件小事记起。<br>
      <button class="empty-cta" id="empty-cta">${ic('list')}<span>看看常用条目</span></button>`;
    hint.querySelector('#empty-cta').onclick = () => {
      setKind('merit');
      $('#btn-presets').click();
    };
  }

  const groups = [];
  for (const n of NOTES) {
    const g = groups[groups.length - 1];
    if (g && g.day === n.day) g.notes.push(n);
    else groups.push({ day: n.day, notes: [n] });
  }

  for (const g of groups) {
    const ai = DAYS[g.day];
    let merit = 0, fault = 0;
    for (const n of g.notes) {
      if (n.kind === 'merit') merit++;
      if (n.kind === 'fault') fault++;
    }
    const el = document.createElement('div');
    el.className = 'day-group';
    el.dataset.day = g.day;
    el.innerHTML = `
      <div class="day-head">
        <span class="day-title">${esc(dayLabel(g.day))}</span>
        <span class="day-tally">${merit ? `<span class="t-merit">功${merit}</span>` : ''}${merit && fault ? ' ' : ''}${fault ? `过${fault}` : ''}</span>
        ${ai?.mood ? `<span class="day-mood">${esc(ai.mood)}</span>` : ''}
        <button class="day-ai-btn icon-btn plain" data-day="${g.day}"
          aria-label="${ai ? '重新生成当日 AI 省察' : '生成当日 AI 省察'}"
          title="${ai ? '重新省察' : 'AI 省察'}">${ic(ai ? 'refresh' : 'sparkles')}</button>
      </div>
      ${ai?.summary ? `<div class="day-summary">${esc(ai.summary)}</div>` : ''}
    `;
    el.querySelector('.day-ai-btn').onclick = ev => daySummary(g.day, ev.target);
    for (const n of g.notes) el.appendChild(renderNote(n));
    box.appendChild(el);
  }
}

function renderNote(n) {
  const el = document.createElement('div');
  el.className = 'note card' + (n.id === JUST_SAVED_ID ? ' just-saved' : '');
  const t = new Date(n.created_at);
  const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;

  const media = (n.attachments || []).map((a, i) => {
    if (a.type === 'image') {
      return `<div class="m-wrap">
        <img class="m-img" src="${esc(a.url)}" alt="${esc(a.name)}" loading="lazy" data-full="${esc(a.url)}">
        ${a.caption
          ? `<span class="m-cap">${esc(a.caption)}</span>`
          : `<button class="m-cap-btn" data-note="${n.id}" data-idx="${i}" aria-label="用 AI 识别这张图并生成配文" title="AI 看图">${ic('eye')}</button>`}
      </div>`;
    }
    if (a.type === 'video') return `<video src="${esc(a.url)}" controls preload="metadata"></video>`;
    if (a.type === 'audio') return audioBar(a.url, a.name);
    return '';
  }).join('');

  const mark = n.kind === 'merit' ? '<span class="ring"></span>'
             : n.kind === 'fault' ? '<span class="dot"></span>'
             : '<span class="dash"></span>';
  const pts = n.kind === 'merit' ? '<span class="note-pts merit">功</span>'
            : n.kind === 'fault' ? '<span class="note-pts fault">过</span>' : '';

  el.innerHTML = `
    <div class="note-mark" title="${{ merit: '功', fault: '过', note: '记' }[n.kind]}">${mark}</div>
    <div class="note-body">
      <div class="note-meta"><span>${hm}</span>${pts}</div>
      ${n.content ? `<div class="note-content">${esc(n.content)}</div>` : ''}
      ${media ? `<div class="note-media">${media}</div>` : ''}
    </div>
    <div class="note-actions">
      <button class="icon-btn plain act-share${n.shared ? ' on' : ''}"
        aria-label="${n.shared ? '已分享到广场，点此撤回' : '分享到广场'}"
        title="${n.shared ? '已分享，点此撤回' : '分享到广场'}">${ic('invite')}</button>
      <button class="icon-btn plain act-edit" aria-label="编辑这条记录" title="编辑">${ic('pencil')}</button>
      <button class="icon-btn plain act-del" aria-label="删除这条记录" title="删除">${ic('trash')}</button>
    </div>
  `;

  el.querySelector('.act-share').onclick = async ev => {
    const btn = ev.currentTarget;
    const turningOn = !n.shared;
    if (turningOn && !(await confirmAsk('分享到广场？',
      '这条内容' + ((n.attachments || []).length ? '及其附件' : '') + '将对所有同修公开，附件会获得公开链接。随时可以撤回，撤回后链接立即失效。', '分享'))) return;
    try {
      await api('POST', `/api/notes/${n.id}/share`, { shared: turningOn });
      n.shared = turningOn ? 1 : 0;
      btn.classList.toggle('on', turningOn);
      PLAZA = [];   // 广场需重新拉取
      toast(turningOn ? '已分享到广场' : '已从广场撤回');
    } catch (e) { toast(e.message); }
  };

  el.querySelectorAll('.m-img').forEach(img => img.onclick = () => openLightbox(img.dataset.full));
  el.querySelectorAll('.m-cap-btn').forEach(b => b.onclick = () => captionImage(b));
  el.querySelector('.act-del').onclick = async () => {
    const hasAtt = (n.attachments || []).length;
    if (!(await confirmAsk('删除这条记录？', hasAtt ? '所含的 ' + hasAtt + ' 个附件也会一并删除，且无法恢复。' : '删除后无法恢复。'))) return;
    try {
      await api('DELETE', '/api/notes/' + n.id);
      // 先收笔淡出，再刷新
      if (!reduceMotion()) {
        el.style.height = el.offsetHeight + 'px';
        requestAnimationFrame(() => el.classList.add('removing'));
        await new Promise(r => setTimeout(r, 340));
      }
      await loadNotes($('#search').value.trim());
    } catch (e) { toast(e.message); }
  };
  el.querySelector('.act-edit').onclick = () => editNote(el, n);
  return el;
}

function editNote(el, n) {
  const body = el.querySelector('.note-body');
  if (body.querySelector('.note-edit')) return;
  const old = body.querySelector('.note-content');
  const wrap = document.createElement('div');
  wrap.className = 'note-edit';
  wrap.innerHTML = `
    <textarea aria-label="编辑记录内容">${esc(n.content)}</textarea>
    <div class="row">
      <button class="icon-btn plain e-cancel" aria-label="取消编辑" title="取消">${ic('x')}</button>
      <button class="btn-primary e-save">保存</button>
    </div>
  `;
  (old || body.querySelector('.note-meta')).after(wrap);
  if (old) old.classList.add('hidden');
  wrap.querySelector('.e-cancel').onclick = () => { wrap.remove(); old?.classList.remove('hidden'); };
  wrap.querySelector('.e-save').onclick = async () => {
    try {
      await api('PUT', '/api/notes/' + n.id, { content: wrap.querySelector('textarea').value });
      await loadNotes($('#search').value.trim());
    } catch (e) { toast(e.message); }
  };
}

// ---------- 输入区：功 / 过 / 记 ----------
const PLACEHOLDER = {
  merit: '今日行了什么善，哪怕很小……',
  fault: '今日有何过失，如实记下……',
  note: '随手记一笔……'
};
function setKind(kind) {
  composeKind = kind;
  document.querySelectorAll('#kind-tabs button[data-kind]').forEach(b => b.classList.toggle('on', b.dataset.kind === kind));
  $('#compose-text').placeholder = PLACEHOLDER[kind];
  $('#presets-row').classList.toggle('hidden', kind === 'note');
}
document.querySelectorAll('#kind-tabs button[data-kind]').forEach(btn => btn.onclick = () => setKind(btn.dataset.kind));

// ---------- 记录归属日（默认今天，可补记过去；凌晨 3 点前默认前一天） ----------
function setComposeDay(day) {
  composeDay = day;
  const today = localDay();
  const yest = localDay(new Date(Date.now() - 86400000));
  const chip = $('#compose-date');
  chip.textContent = day === today ? '今天' : day === yest ? '昨天' : `${Number(day.slice(5, 7))}月${Number(day.slice(8))}日`;
  chip.classList.toggle('back', day !== today);
}
$('#compose-date').onclick = () => {
  const inp = $('#compose-date-input');
  inp.max = localDay();
  inp.value = composeDay;
  if (inp.showPicker) inp.showPicker(); else inp.click();
};
$('#compose-date-input').addEventListener('change', e => {
  const v = e.target.value;
  if (/^\d{4}-\d{2}-\d{2}$/.test(v) && v <= localDay()) setComposeDay(v);
});
setComposeDay(effectiveToday());

const composeText = $('#compose-text');
composeText.addEventListener('input', () => {
  composeText.style.height = 'auto';
  composeText.style.height = Math.min(composeText.scrollHeight, 320) + 'px';
  if (polishBackup !== null) { polishBackup = null; $('#btn-undo-polish').classList.add('hidden'); }
});
// ⌘/Ctrl + Enter 快速记下
composeText.addEventListener('keydown', e => {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') $('#btn-save').click();
});

// ============================================================
// 常用条目 —— 依莲池大师《自知录》原书四门分类，条文以白话概括
// （原书每条附有善数，本工具不计分，故只取事目）
// 善门：忠孝 / 仁慈 / 三宝功德 / 杂善
// 过门：不忠孝 / 不仁慈 / 三宝罪业 / 杂不善
// ============================================================
const PRESETS = {
  merit: [
    ['忠孝类', ['奉养父母，致敬尽心', '劝父母行善向道', '敬养祖父母、继亲', '敬奉师长，守师良诲',
                '敬兄爱弟，家门和睦', '事上竭忠效力', '凡事真实不欺', '开陈善道，利益于人']],
    ['仁慈类', ['救助病苦，施药就医', '赈济穷困孤寡', '救免刑狱冤屈', '收养无主弃儿',
                '买物放生，救护生命', '禁绝杀生，素食一日', '劝渔猎屠户改业', '埋葬资荐亡畜',
                '让路让座，济人之急', '周给宗族患难之人']],
    ['三宝功德类', ['礼佛诵经', '念佛持咒', '受持斋戒', '布施供养三宝',
                    '造像印经，流通善书', '修建塔寺', '劝人念佛向道', '斋僧供众']],
    ['杂善类', ['称人之善，隐人之恶', '调解他人纷争', '劝人改过迁善', '还人遗失之物',
                '不欺暗室，守信不违', '忍住一次嗔怒', '主动认错道歉', '早起用功不懈',
                '拒一次不正诱惑', '惜福节用，不弃五谷']]
  ],
  fault: [
    ['不忠孝类', ['违逆顶撞父母长辈', '于亲有养无敬', '不敬师长，违其教诲',
                  '兄弟不睦，争执失和', '事上不尽职，敷衍塞责', '欺瞒失信，言而无实']],
    ['不仁慈类', ['见人危难可助而不助', '为口腹杀害生命', '打骂捶楚，恼害于人',
                  '悭吝不施，见苦不济', '虐使仆从下属', '毁坏他人财物',
                  '断人生路，夺人所爱', '纵犬猫伤害微命']],
    ['三宝罪业类', ['轻慢佛法僧三宝', '毁谤正法或出家人', '许愿不还，负三宝物',
                    '于佛前失仪，散乱嬉笑', '破斋犯戒', '以经像置于不净处']],
    ['杂不善类', ['背后说人是非', '恶口伤人，出言粗暴', '妄语欺诳', '两舌挑拨',
                  '绮语邪思', '贪求无厌，占人便宜', '嫉妒他人之好', '傲慢自矜，轻贱他人',
                  '沉迷刷屏虚度光阴', '暴饮暴食，浪费饮食', '懒惰误事', '赌博博戏']]
  ]
};
$('#btn-presets').onclick = () => {
  if (composeKind === 'note') return;
  $('#presets-title').textContent = composeKind === 'merit' ? '善门 · 常用事目' : '过门 · 常用事目';
  $('#presets-body').innerHTML =
    `<p class="preset-note">依莲池大师《自知录》四类分门，事目以白话概括，点选即填入。</p>` +
    PRESETS[composeKind].map(([cat, items]) => `
    <div class="preset-cat">${cat}</div>
    <div class="preset-items">
      ${items.map(t => `<button class="preset-item" data-t="${esc(t)}">${esc(t)}</button>`).join('')}
    </div>`).join('');
  $('#presets-body').querySelectorAll('.preset-item').forEach(b => b.onclick = () => {
    composeText.value = b.dataset.t;
    composeText.dispatchEvent(new Event('input'));
    $('#presets-overlay').classList.add('hidden');
    composeText.focus();
  });
  $('#presets-overlay').classList.remove('hidden');
};
$('#presets-close').onclick = () => $('#presets-overlay').classList.add('hidden');
$('#presets-overlay').onclick = e => { if (e.target === $('#presets-overlay')) $('#presets-overlay').classList.add('hidden'); };

// ---------- 附件上传（图片先在本地压缩） ----------
document.querySelectorAll('[data-pick]').forEach(btn => {
  btn.onclick = () => $('#pick-' + btn.dataset.pick).click();
});
['image', 'video', 'audio'].forEach(kind => {
  $('#pick-' + kind).addEventListener('change', async e => {
    for (const file of e.target.files) await uploadFile(file);
    e.target.value = '';
  });
});

// 大图压缩：>600KB 的非 GIF 图片缩到最长边 2000px、JPEG 85%
async function maybeCompress(file) {
  if (!/^image\//.test(file.type) || file.type === 'image/gif' || file.size < 600 * 1024) return file;
  try {
    const bmp = await createImageBitmap(file);
    const MAX = 2000;
    const scale = Math.min(1, MAX / Math.max(bmp.width, bmp.height));
    const cw = Math.round(bmp.width * scale), ch = Math.round(bmp.height * scale);
    const cv = document.createElement('canvas');
    cv.width = cw; cv.height = ch;
    cv.getContext('2d').drawImage(bmp, 0, 0, cw, ch);
    const blob = await new Promise(ok => cv.toBlob(ok, 'image/jpeg', 0.85));
    if (blob && blob.size < file.size)
      return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
  } catch {}
  return file;
}

async function uploadFile(rawFile) {
  const chip = document.createElement('span');
  chip.className = 'att-chip';
  chip.innerHTML = `<span class="name">上传中… ${esc(rawFile.name)}</span>`;
  $('#compose-atts').appendChild(chip);
  try {
    const file = await maybeCompress(rawFile);
    if (file.size > 50 * 1024 * 1024) throw new Error('文件超过 50MB 上限');
    const qs = `name=${encodeURIComponent(file.name)}&mime=${encodeURIComponent(file.type || 'application/octet-stream')}`;
    const r = await fetch('/api/upload?' + qs, { method: 'POST', body: file });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || '上传失败');
    composeAtts.push(j);
    chip.remove();
    renderComposeAtts();
  } catch (err) {
    chip.remove();
    toast(err.message);
  }
}

function renderComposeAtts() {
  const box = $('#compose-atts');
  box.innerHTML = '';
  composeAtts.forEach((a, i) => {
    const chip = document.createElement('span');
    chip.className = 'att-chip';
    chip.innerHTML = `
      ${a.type === 'image' ? `<img src="${esc(a.url)}" alt="">` : ic(a.type === 'video' ? 'video' : 'audio')}
      <span class="name">${esc(a.name)}</span>
      <button class="rm" aria-label="移除这个附件" title="移除">${ic('x')}</button>
    `;
    chip.querySelector('.rm').onclick = () => { composeAtts.splice(i, 1); renderComposeAtts(); };
    box.appendChild(chip);
  });
}

$('#btn-save').onclick = async () => {
  const content = composeText.value.trim();
  if (!content && !composeAtts.length) return toast('写点什么，或添加一个附件');
  const btn = $('#btn-save');
  btn.disabled = true;
  try {
    const saved = await api('POST', '/api/notes', {
      content,
      attachments: composeAtts,
      kind: composeKind,
      day: composeDay
    });
    JUST_SAVED_ID = saved.note?.id || 0;
    composeText.value = '';
    composeText.style.height = 'auto';
    composeAtts = [];
    polishBackup = null;
    $('#btn-undo-polish').classList.add('hidden');
    renderComposeAtts();
    setComposeDay(effectiveToday());
    $('#search').value = '';
    await loadNotes();
    setTimeout(() => { JUST_SAVED_ID = 0; }, 2200);
  } catch (e) { toast(e.message); }
  finally { btn.disabled = false; }
};

// ============================================================
// AI 层：浏览器直连硅基流动（主通道；Cloudflare 出口被限流），
//        直连失败时自动回退到服务端接口
// ============================================================
let AICONF = null;
async function aiConf() {
  if (!AICONF) AICONF = await api('GET', '/api/ai/config');
  return AICONF;
}
const stripThink = s => String(s || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();

async function chatDirect(messages, { model, maxTokens = 900, temperature = 0.7 } = {}) {
  // 先领用一次配额（每人每日 100 次），超额直接中止
  const u = await api('POST', '/api/ai/use');
  AI_LEFT = u.left;
  updateAiMeter();
  const c = await aiConf();
  const payload = { model: model || c.chatModel, messages, max_tokens: maxTokens, temperature, enable_thinking: false };
  const call = () => fetch(c.base + '/chat/completions', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + c.key, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  let r = await call();
  if (!r.ok) {
    const t = await r.text();
    // 部分模型不接受 enable_thinking，去掉重试一次
    if (r.status === 400 && t.includes('enable_thinking')) { delete payload.enable_thinking; r = await call(); }
    else throw new Error(`AI 服务异常（${r.status}）`);
    if (!r.ok) throw new Error(`AI 服务异常（${r.status}）`);
  }
  const j = await r.json();
  return stripThink(j.choices?.[0]?.message?.content);
}

// 与服务端一致的记录行格式（供提示词使用）
function noteLine(n) {
  const KL = { merit: '功', fault: '过' };
  const tag = n.kind === 'note' ? '记' : KL[n.kind];
  const attDesc = (n.attachments || []).map(a => {
    const label = { image: '图片', video: '视频', audio: '音频' }[a.type] || '附件';
    return a.caption ? `[${label}：${a.caption}]` : `[${label}]`;
  }).join(' ');
  return `[${tag}] ${n.content} ${attDesc}`.trim();
}

const P_POLISH = '你是文字润色助手。把用户的随手记润色得通顺、干净、朴实，保留原意与个人口吻，纠正错别字，篇幅与原文相当。只输出润色后的正文，不要任何解释。';
const P_REFLECT = '你是一位温和克制的修身助手，帮用户做每日功过省察。依据当天记录：先如实肯定其功，再平和点出其过或可改进处，末尾一句朴素的勉励。用第二人称，70 字以内，不说教、不堆砌辞藻、不引用经文、不以圣贤口吻自居。另选一个贴切的 emoji 概括当天。只输出 JSON：{"summary":"...","mood":"emoji"}';
const P_JUDGE = '你是功过格助手。根据一条记录内容，判断它应归入「功」（善行）、「过」（过失）还是普通「记」（中性记事）。不评分、不打分数。只输出 JSON：{"kind":"merit"或"fault"或"note","why":"不超过15字的理由"}';

// ---------- AI：润色 ----------
$('#btn-polish').onclick = async () => {
  const text = composeText.value.trim();
  if (!text) return toast('先写点内容，再让 AI 润色');
  const btn = $('#btn-polish');
  btn.disabled = true; btn.classList.add('busy');
  try {
    let out;
    try {
      out = await chatDirect([
        { role: 'system', content: P_POLISH },
        { role: 'user', content: text }
      ], { temperature: 0.5 });
    } catch {
      out = (await api('POST', '/api/ai/polish', { text })).text;   // 服务端回退
    }
    composeText.value = out;
    composeText.dispatchEvent(new Event('input'));
    polishBackup = text;                       // input 事件会清空备份，这里重新记录
    $('#btn-undo-polish').classList.remove('hidden');
    toast('已润色，可点还原撤回');
  } catch (e) { toast(e.message); }
  finally { btn.disabled = false; btn.classList.remove('busy'); }
};
$('#btn-undo-polish').onclick = () => {
  if (polishBackup === null) return;
  composeText.value = polishBackup;
  polishBackup = null;
  $('#btn-undo-polish').classList.add('hidden');
};

// ---------- AI：判分归类 ----------
$('#btn-judge').onclick = async () => {
  const text = composeText.value.trim();
  if (!text) return toast('先写点内容，再让 AI 判分');
  const btn = $('#btn-judge');
  btn.disabled = true; btn.classList.add('busy');
  try {
    const raw = await chatDirect([
      { role: 'system', content: P_JUDGE },
      { role: 'user', content: text }
    ], { temperature: 0.2, maxTokens: 150 });
    const j = JSON.parse(raw.match(/\{[\s\S]*\}/)[0]);
    const kind = ['merit', 'fault', 'note'].includes(j.kind) ? j.kind : 'note';
    setKind(kind);
    const label = { merit: '功', fault: '过', note: '记' }[kind];
    toast(`✦ 建议归入「${label}」${j.why ? '：' + j.why : ''}`);
  } catch (e) { toast('判别失败，请手动选择'); }
  finally { btn.disabled = false; btn.classList.remove('busy'); }
};

// ---------- AI：每日省察 ----------
async function daySummary(day, btn) {
  btn.disabled = true; btn.classList.add('busy');
  try {
    let summary, mood = '';
    try {
      // 取全量记录（避开搜索过滤），组装当天功过文本
      const all = (await api('GET', '/api/notes')).notes.filter(n => n.day === day).reverse();
      if (!all.length) throw Object.assign(new Error('这一天还没有记录'), { fatal: true });
      const text = all.map(noteLine).join('\n').slice(0, 6000);
      const raw = await chatDirect([
        { role: 'system', content: P_REFLECT },
        { role: 'user', content: `日期：${day}\n当日功过记录：\n${text}` }
      ], { temperature: 0.6 });
      summary = raw;
      try {
        const j = JSON.parse(raw.match(/\{[\s\S]*\}/)[0]);
        summary = String(j.summary || '').trim() || raw;
        mood = String(j.mood || '').trim().slice(0, 8);
      } catch {}
      await api('POST', '/api/day-ai', { day, summary, mood });
    } catch (e) {
      if (e.fatal) throw e;
      const j = await api('POST', '/api/ai/day-summary', { day });   // 服务端回退
      summary = j.summary; mood = j.mood;
    }
    DAYS[day] = { summary, mood };
    renderTimeline(false);
    // 让省察像写出来一样逐字浮现
    const box = document.querySelector(`.day-group[data-day="${day}"] .day-summary`);
    if (box) typeInto(box, summary);
  } catch (e) { toast(e.message); btn.disabled = false; btn.classList.remove('busy'); }
}

// ---------- AI：月度省察报告（生成后存档，DAYS 键 YYYY-MM-00） ----------
async function monthReport(targetYM) {
  const ym = /^\d{4}-\d{2}$/.test(targetYM || '') ? targetYM : localDay().slice(0, 7);
  const archKey = ym + '-00';
  // 已有存档：直接展示 + 可重新生成
  if (DAYS[archKey]?.summary) {
    openModal(`${monthName(ym)}省察`, `
      <div class="report-text">${esc(DAYS[archKey].summary)}</div>
      <span class="seal-mini" aria-hidden="true">知</span>
      <hr class="about-sep">
      <button id="report-again" class="btn-ghost" aria-label="重新生成月度省察">${ic('refresh')}<span>重新生成</span></button>`);
    $('#report-again').onclick = () => { delete DAYS[archKey]; monthReport(ym); };
    return;
  }
  const monthNotes = NOTES.filter(n => n.day.startsWith(ym));
  if (!monthNotes.length) return toast(`${monthName(ym)}还没有记录`);
  openModal(`${monthName(ym)}省察`, '<div class="report-text loading">✦ 正在通览本月功过…</div>');
  let merit = 0, fault = 0;
  for (const n of monthNotes) {
    if (n.kind === 'merit') merit++;
    if (n.kind === 'fault') fault++;
  }
  let budget = 8000;
  const lines = [];
  for (const n of monthNotes) {
    const line = `[${n.day.slice(8)}日] ${noteLine(n).slice(0, 160)}`;
    if (budget - line.length < 0) break;
    budget -= line.length;
    lines.push(line);
  }
  lines.reverse();
  try {
    let out;
    const question = `请为我做本月（${ym}）的月度省察：一段概览、最常见的过与其对治、可喜之处、给下月一个具体的小建议。160 字以内，平实温和，不说教，不要给分数或评级。`;
    try {
      out = await chatDirect([
        { role: 'system', content: `你是温和克制的修身助手。本月合计：功 ${merit} 件，过 ${fault} 件。以下是本月功过记录（[功]=善行、[过]=过失、[记]=记事）：\n${lines.join('\n')}` },
        { role: 'user', content: question }
      ], { temperature: 0.6 });
    } catch {
      out = (await api('POST', '/api/ai/ask', { question })).answer;   // 服务端回退
    }
    $('#modal-body').innerHTML = '<div class="report-text"></div>';
    await typeInto($('#modal-body .report-text'), out, 26);
    $('#modal-body').insertAdjacentHTML('beforeend', '<span class="seal-mini stamp-in" aria-hidden="true">知</span>');
    // 存档（失败不影响展示）
    DAYS[archKey] = { summary: out, mood: '' };
    api('POST', '/api/day-ai', { day: archKey, summary: out.slice(0, 500), mood: '' }).catch(() => {});
  } catch (e) {
    $('#modal-body').innerHTML = `<div class="report-text loading">生成失败：${esc(e.message)}</div>`;
  }
};

// ---------- AI：看图 ----------
async function captionImage(btn) {
  btn.disabled = true; btn.classList.add('busy');
  const noteId = Number(btn.dataset.note), idx = Number(btn.dataset.idx);
  try {
    try {
      const n = NOTES.find(x => x.id === noteId);
      const att = n?.attachments?.[idx];
      if (!att) throw new Error('附件不存在');
      const blob = await (await fetch(att.url)).blob();
      if (blob.size > 10 * 1024 * 1024) throw new Error('图片超过 10MB，暂不支持识别');
      const b64 = await new Promise((ok, no) => {
        const fr = new FileReader();
        fr.onload = () => ok(String(fr.result).split(',')[1]);
        fr.onerror = no;
        fr.readAsDataURL(blob);
      });
      const c = await aiConf();
      const caption = await chatDirect([
        { role: 'user', content: [
          { type: 'image_url', image_url: { url: `data:${att.mime || 'image/jpeg'};base64,${b64}` } },
          { type: 'text', text: '用一句中文（30 字以内）自然地描述这张照片记录的内容，像日记配文，不要客套话。' }
        ] }
      ], { model: c.visionModel, maxTokens: 200, temperature: 0.5 });
      await api('POST', `/api/notes/${noteId}/caption`, { index: idx, caption });
    } catch {
      await api('POST', '/api/ai/caption', { noteId, index: idx });   // 服务端回退
    }
    await loadNotes($('#search').value.trim());
  } catch (e) { toast(e.message); btn.disabled = false; btn.classList.remove('busy'); }
}

// ---------- AI：问格 ----------
$('#btn-ask').onclick = () => {
  $('#ask-panel').classList.remove('hidden');
  setTimeout(() => $('#ask-q').focus(), 60);
};
$('#ask-close').onclick = () => $('#ask-panel').classList.add('hidden');
$('#ask-panel').onclick = e => { if (e.target === $('#ask-panel')) $('#ask-panel').classList.add('hidden'); };
$('#ask-form').onsubmit = async e => {
  e.preventDefault();
  const q = $('#ask-q').value.trim();
  if (!q) return;
  $('#ask-q').value = '';
  const log = $('#ask-log');
  log.querySelector('.ask-tip')?.remove();
  log.insertAdjacentHTML('beforeend', `<div class="msg q">${esc(q)}</div>`);
  const thinking = document.createElement('div');
  thinking.className = 'msg a thinking';
  thinking.textContent = '翻看录中记事…';
  log.appendChild(thinking);
  log.scrollTop = log.scrollHeight;
  try {
    let answer;
    try {
      // 用全量记录组装上下文（搜索过滤时也保证完整）
      let list = NOTES;
      if ($('#search').value.trim()) list = (await api('GET', '/api/notes')).notes;
      let budget = 11000;
      const lines = [];
      for (const n of list) {
        const line = `[${n.day}] ${noteLine(n).slice(0, 240)}`;
        if (budget - line.length < 0) break;
        budget -= line.length;
        lines.push(line);
      }
      lines.reverse();
      answer = await chatDirect([
        { role: 'system', content: `你是用户的功过格助手。今天是 ${localDay()}。只依据下面的功过记录回答问题，可归纳、统计功过件数；记录里没有的信息就直说「录中没有相关记录」，不要编造。回答简洁平实。\n\n=== 功过记录 ===\n（[功]=善行，[过]=过失，[记]=普通记事）\n${lines.join('\n') || '（暂无记录）'}` },
        { role: 'user', content: q }
      ], { temperature: 0.4 });
    } catch {
      answer = (await api('POST', '/api/ai/ask', { question: q })).answer;   // 服务端回退
    }
    thinking.classList.remove('thinking');
    thinking.textContent = answer;
  } catch (err) {
    thinking.textContent = '出错了：' + err.message;
  }
  log.scrollTop = log.scrollHeight;
};

// ============================================================
// 广场
// ============================================================
async function loadPlaza(append = false) {
  try {
    const before = append && PLAZA.length ? '?before=' + encodeURIComponent(PLAZA[PLAZA.length - 1].shared_at) : '';
    const j = await api('GET', '/api/plaza' + before);
    PLAZA = append ? PLAZA.concat(j.items) : j.items;
    PLAZA_MORE = !!j.hasMore;
    renderPlaza();
  } catch (e) { toast(e.message); }
}

function renderPlaza() {
  const box = $('#plaza-list');
  box.innerHTML = '';
  $('#plaza-empty').classList.toggle('hidden', PLAZA.length > 0);
  $('#plaza-older').classList.toggle('hidden', !PLAZA_MORE);

  for (const it of PLAZA) {
    const el = document.createElement('div');
    el.className = 'note card plaza-item';
    const mark = it.kind === 'merit' ? '<span class="ring"></span>'
               : it.kind === 'fault' ? '<span class="dot"></span>'
               : '<span class="dash"></span>';
    const media = (it.attachments || []).map(a => {
      if (a.type === 'image') return `<img class="m-img" src="${esc(a.url)}" alt="${esc(a.caption || '分享的图片')}" loading="lazy" data-full="${esc(a.url)}">`;
      if (a.type === 'video') return `<video src="${esc(a.url)}" controls preload="metadata"></video>`;
      if (a.type === 'audio') return audioBar(a.url, a.name);
      return '';
    }).join('');
    el.innerHTML = `
      <div class="note-mark">${mark}</div>
      <div class="note-body">
        <div class="note-meta">
          <span class="plaza-author">${esc(it.author)}</span>
          <span>${esc(it.day)}</span>
          ${it.mine ? '<span class="plaza-mine">我的</span>' : ''}
        </div>
        <div class="note-content">${esc(it.content)}</div>
        ${media ? `<div class="note-media">${media}</div>` : ''}
        <div class="plaza-foot">
          ${it.mine
            ? `<button class="link p-unshare" data-id="${it.id}">取消分享</button>`
            : `<button class="link p-report" data-id="${it.id}">举报</button>`}
        </div>
      </div>`;
    el.querySelectorAll('.m-img').forEach(img => img.onclick = () => openLightbox(img.dataset.full));
    el.querySelector('.p-unshare')?.addEventListener('click', async ev => {
      if (!(await confirmAsk('从广场撤回这条？', '记录仍保留在你自己的录中。', '撤回'))) return;
      try {
        await api('POST', `/api/notes/${ev.target.dataset.id}/share`, { shared: false });
        PLAZA = PLAZA.filter(x => String(x.id) !== ev.target.dataset.id);
        renderPlaza();
        toast('已从广场撤回');
      } catch (e) { toast(e.message); }
    });
    el.querySelector('.p-report')?.addEventListener('click', async ev => {
      if (!(await confirmAsk('举报这条内容？', '我们会尽快查看处理。', '举报'))) return;
      try {
        await api('POST', '/api/report', { noteId: Number(ev.target.dataset.id) });
        toast('已收到举报，感谢');
      } catch (e) { toast(e.message); }
    });
    box.appendChild(el);
  }
}
$('#plaza-older').onclick = () => loadPlaza(true);
$('#tab-record').onclick = () => setTab('record');
$('#tab-plaza').onclick = () => setTab('plaza');
$('#tab-mine').onclick = () => setTab('mine');

// ---------- 通用弹层 ----------
function openModal(title, html) {
  $('#modal-title').textContent = title;
  $('#modal-body').innerHTML = html;
  $('#modal-overlay').classList.remove('hidden');
}
$('#modal-close').onclick = () => $('#modal-overlay').classList.add('hidden');
$('#modal-overlay').onclick = e => { if (e.target === $('#modal-overlay')) $('#modal-overlay').classList.add('hidden'); };

// ============================================================
// 我的（昵称 / AI 用量 / 提醒 / 邀请 / 备份 / 密码 / 关于）
// ============================================================
async function renderMine() {
  $('#mine-nick').textContent = ME.nickname || '未设法名';
  $('#mine-account').textContent = ME.account;
  $('#mine-body').innerHTML = `
    <div class="mine-sec">
      <b class="sec-title">${ic('pencil')}法名 / 昵称</b>
      <span class="push-tip">分享到广场时以此署名；留空则显示「匿名」</span>
      <form class="row-btns" id="nick-form">
        <input id="nick-input" type="text" maxlength="24" placeholder="如：常照" value="${esc(ME.nickname || '')}">
        <button class="btn-ghost" type="submit">保存</button>
      </form>
    </div>

    <div class="mine-sec">
      <b class="sec-title">${ic('sparkles')}AI 用量</b>
      <span class="push-tip">每人每日免费 100 次（省察、润色、判别、问格、看图各计一次），每日零时重置</span>
      <div class="ai-bar"><i id="ai-bar-fill"></i></div>
      <span id="ai-meter" class="ai-meter"></span>
    </div>

    <div class="mine-sec">
      <b class="sec-title">${ic('bell')}每日提醒</b>
      <span class="push-tip">每晚 21:00（北京时间），当天未记才提醒</span>
      <div class="row-btns">
        <button id="push-toggle" class="btn-ghost">开启提醒</button>
        <button id="push-test" class="icon-btn plain hidden" aria-label="发送一条测试提醒" title="发送测试">${ic('sparkles')}</button>
      </div>
    </div>

    <div class="mine-sec" id="invite-box">
      <b class="sec-title">${ic('invite')}邀请好友</b>
      <span class="push-tip">对方用你的邀请码注册，即可开始自己的功过格</span>
      <div id="invite-inner" class="invite-inner">读取中…</div>
    </div>

    <div class="mine-sec">
      <b class="sec-title">${ic('download')}备份与恢复</b>
      <span class="push-tip">导出为文件存好；导入时按时间去重合并（媒体文件不在 JSON 内）</span>
      <div class="row-btns">
        <button id="btn-export-md" class="btn-ghost">${ic('download')}<span>Markdown</span></button>
        <button id="btn-export-json" class="btn-ghost">${ic('download')}<span>JSON</span></button>
        <button id="btn-import" class="btn-ghost">${ic('upload')}<span>导入</span></button>
        <input id="import-file" type="file" accept=".json,application/json" hidden>
      </div>
    </div>

    <div class="mine-sec">
      <form class="pw-form" id="pw-form">
        <b class="sec-title">${ic('key')}修改密码</b>
        <label>原密码</label><input type="password" id="pw-old" required minlength="6" autocomplete="current-password">
        <label>新密码</label><input type="password" id="pw-new" required minlength="6" autocomplete="new-password">
        <button class="btn-primary" type="submit">确认修改</button>
      </form>
    </div>

    <div class="mine-sec">
      <b class="sec-title">${ic('download')}安卓 APP</b>
      <span class="push-tip">安装后全屏运行、每日提醒更可靠；网页更新自动生效，无需重装</span>
      <div class="row-btns">
        <a class="btn-ghost" href="/dl/zizhilu.apk" download>${ic('download')}<span>下载 APK（1.0.1）</span></a>
      </div>
    </div>

    <div class="mine-sec about-text">
      <b>自知录 · 极简功过格记事本</b><br>
      名承净土八祖莲池大师《自知录》，取「人贵自知」之意；此为今人自用的记录工具，非原典电子版。
      常用事目依原书善门、过门四类编排，以白话概括。<br>
      莲池大师于序中言：上士「书可也，不书可也」——「善本当行，非徼福故；恶本不当作，非畏罪故」。
      故本工具只记事、不计分。<br>
      <a class="link" href="/privacy" target="_blank">隐私政策</a>
    </div>

    <div class="mine-sec">
      <button id="btn-logout2" class="btn-ghost">${ic('logout')}<span>退出登录</span></button>
    </div>
  `;

  // --- 拉取最新资料：昵称与 AI 用量（登录接口不带这两项） ---
  try {
    const me = await api('GET', '/api/me');
    ME = me.user;
    $('#mine-nick').textContent = ME.nickname || '未设法名';
    $('#nick-input').value = ME.nickname || '';
    AI_LEFT = me.ai.limit - me.ai.used;
    const pct = Math.min(100, Math.round(me.ai.used / me.ai.limit * 100));
    $('#ai-bar-fill').style.width = pct + '%';
    updateAiMeter();
  } catch {}

  // --- 昵称 ---
  $('#nick-form').onsubmit = async e => {
    e.preventDefault();
    try {
      const j = await api('POST', '/api/nickname', { nickname: $('#nick-input').value });
      ME.nickname = j.nickname;
      $('#mine-nick').textContent = j.nickname || '未设法名';
      PLAZA = [];
      toast(j.nickname ? '已保存：' + j.nickname : '已清空，广场将显示「匿名」');
    } catch (err) { toast(err.message); }
  };

  $('#btn-logout2').onclick = async () => {
    if (!(await confirmAsk('退出登录？', '你的记录都在云端，随时可以登录回来。', '退出'))) return;
    await api('POST', '/api/logout').catch(() => {});
    location.reload();
  };

  bindExport();

  // --- 修改密码 ---
  $('#pw-form').onsubmit = async e => {
    e.preventDefault();
    try {
      await api('POST', '/api/change-password', { old: $('#pw-old').value, new: $('#pw-new').value });
      $('#modal-overlay').classList.add('hidden');
      toast('密码已修改');
    } catch (err) { toast(err.message); }
  };

  // --- 邀请好友 ---
  (async () => {
    const box = $('#invite-inner');
    try {
      const inv = await api('GET', '/api/invite');
      const left = inv.maxUses - inv.used;
      const link = `${location.origin}/?invite=${inv.code}`;
      box.innerHTML = `
        <div class="invite-code">${esc(inv.code)}</div>
        <div class="invite-meta">剩余名额 <b>${left}</b> / ${inv.maxUses}</div>
        <div class="row-btns">
          <button id="inv-copy" class="btn-ghost" ${left ? '' : 'disabled'} aria-label="复制邀请链接">${ic('copy')}<span>复制邀请链接</span></button>
        </div>
        ${inv.invitees.length ? `<div class="invite-list">已邀请：${inv.invitees.map(i => `${esc(i.account)}<i>${i.at}</i>`).join('、')}</div>` : ''}
        ${left ? '' : '<div class="invite-list">名额已用完。需要更多请联系录主。</div>'}`;
      const copyBtn = $('#inv-copy');
      if (copyBtn) copyBtn.onclick = async () => {
        const text = `我在用「自知录」记功过格，一起来：${link}\n（注册时邀请码：${inv.code}）`;
        try { await navigator.clipboard.writeText(text); toast('邀请链接已复制'); }
        catch { prompt('复制下面的邀请链接：', link); }
      };
    } catch (e) {
      box.textContent = '邀请码读取失败：' + e.message;
    }
  })();

  // --- 导入备份 ---
  $('#btn-import').onclick = () => $('#import-file').click();
  $('#import-file').addEventListener('change', async e => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const data = JSON.parse(await file.text());
      if (!Array.isArray(data.notes)) throw new Error('不是有效的自知录备份文件');
      const j = await api('POST', '/api/import', { notes: data.notes, days: data.days });
      toast(`导入 ${j.imported} 条，跳过重复 ${j.skipped} 条`);
      $('#modal-overlay').classList.add('hidden');
      await loadNotes();
    } catch (err) { toast(err.message); }
    e.target.value = '';
  });

  // --- 每日提醒 ---
  const toggle = $('#push-toggle'), testBtn = $('#push-test');
  const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  async function refreshPushUI() {
    if (!supported) { toggle.textContent = '此浏览器不支持'; toggle.disabled = true; return; }
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    toggle.textContent = sub ? '关闭提醒' : '开启提醒';
    testBtn.classList.toggle('hidden', !sub);
    return sub;
  }
  const curSub = await refreshPushUI();
  toggle.onclick = async () => {
    try {
      toggle.disabled = true;
      const reg = await navigator.serviceWorker.ready;
      let sub = await reg.pushManager.getSubscription();
      if (sub) {
        await api('POST', '/api/push/unsubscribe', { endpoint: sub.endpoint }).catch(() => {});
        await sub.unsubscribe();
        toast('已关闭每日提醒');
      } else {
        if ((await Notification.requestPermission()) !== 'granted') throw new Error('未授予通知权限');
        const { key } = await api('GET', '/api/push/key');
        const appKey = Uint8Array.from(atob(key.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
        sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: appKey });
        await api('POST', '/api/push/subscribe', { subscription: sub.toJSON() });
        toast('已开启：每晚 21:00 未记则提醒');
      }
      await refreshPushUI();
    } catch (err) { toast(err.message); }
    finally { toggle.disabled = false; }
  };
  testBtn.onclick = async () => {
    try {
      const j = await api('POST', '/api/push/test');
      toast('已发送测试提醒（状态 ' + j.sent.join(',') + '）');
    } catch (err) { toast(err.message); }
  };
  void curSub;
}

// ---------- 导出备份 ----------
function download(name, content, type) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([content], { type }));
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}
const KLABEL = { merit: '功', fault: '过', note: '记' };
function bindExport() {
  $('#btn-export-json').onclick = async () => {
  const j = await api('GET', '/api/export');   // 全量，不受分页限制
  download(`自知录备份-${localDay()}.json`,
    JSON.stringify({ app: '自知录', exportedAt: new Date().toISOString(), account: ME.account, notes: j.notes, days: j.days }, null, 2),
    'application/json');
  };
  $('#btn-export-md').onclick = async () => {
  const j = await api('GET', '/api/export');   // 全量，不受分页限制
  const groups = {};
  for (const n of j.notes) (groups[n.day] = groups[n.day] || []).unshift(n);
  const daysDesc = Object.keys(groups).sort().reverse();
  let md = `# 自知录 · 功过格备份\n\n- 账号：${ME.account}\n- 导出：${new Date().toLocaleString('zh-CN')}\n`;
  for (const day of daysDesc) {
    let merit = 0, fault = 0;
    for (const n of groups[day]) {
      if (n.kind === 'merit') merit++;
      if (n.kind === 'fault') fault++;
    }
    md += `\n## ${day}${merit || fault ? `（功 ${merit} 件、过 ${fault} 件）` : ''}\n\n`;
    if (j.days?.[day]) md += `> 省察：${j.days[day].summary} ${j.days[day].mood}\n\n`;
    for (const n of groups[day]) {
      const t = new Date(n.created_at);
      const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
      const mark = n.kind === 'merit' ? '○' : n.kind === 'fault' ? '●' : '—';
      const pts = n.kind === 'note' ? '' : ` ${KLABEL[n.kind]}`;
      md += `- ${hm} ${mark}${pts} ${n.content.replace(/\n/g, ' ')}`;
      for (const a of n.attachments || []) md += ` ［${{ image: '图', video: '影', audio: '音' }[a.type]}：${a.url}${a.caption ? '｜' + a.caption : ''}］`;
      md += '\n';
    }
  }
  download(`自知录备份-${localDay()}.md`, md, 'text/markdown');
  };
}

// ---------- 搜索 ----------
let searchTimer;
$('#search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadNotes($('#search').value.trim()), 300);
});
// 彩蛋：问题式输入（以？结尾）回车，直接交给问格
$('#search').addEventListener('keydown', e => {
  const v = $('#search').value.trim();
  if (e.key === 'Enter' && /[？?]$/.test(v)) {
    e.preventDefault();
    $('#search').value = '';
    loadNotes();
    $('#ask-panel').classList.remove('hidden');
    $('#ask-q').value = v;
    $('#ask-form').dispatchEvent(new Event('submit'));
  }
});

// ---------- 极简音频条（一根墨线；全站同一时刻只响一个） ----------
function audioBar(url, name) {
  return `<div class="ap" data-src="${esc(url)}">
    <button class="icon-btn plain ap-toggle" aria-label="播放">${ic('play')}</button>
    <div class="ap-track" role="slider" aria-label="播放进度"><i class="ap-fill"></i></div>
    <span class="ap-time">--:--</span>
    <span class="ap-name">${esc(name || '')}</span>
  </div>`;
}
const AP = { el: null, audio: null };
const fmtT = t => isFinite(t) ? `${Math.floor(t / 60)}:${String(Math.floor(t % 60)).padStart(2, '0')}` : '--:--';
function apStop() {
  if (!AP.audio) return;
  AP.audio.pause();
  AP.el?.querySelector('.ap-toggle use')?.setAttribute('href', '#i-play');
}
document.addEventListener('click', e => {
  const toggle = e.target.closest('.ap-toggle');
  const track = e.target.closest('.ap-track');
  const ap = (toggle || track)?.closest('.ap');
  if (!ap) return;
  // 换目标：停掉上一个
  if (AP.el !== ap) {
    apStop();
    AP.el = ap;
    AP.audio = new Audio(ap.dataset.src);
    AP.audio.preload = 'metadata';
    const a = AP.audio;
    a.addEventListener('loadedmetadata', () => { if (AP.audio === a) ap.querySelector('.ap-time').textContent = fmtT(a.duration); });
    a.addEventListener('timeupdate', () => {
      if (AP.audio !== a) return;
      ap.querySelector('.ap-fill').style.width = (a.currentTime / a.duration * 100 || 0) + '%';
      ap.querySelector('.ap-time').textContent = fmtT(a.duration - a.currentTime);
    });
    a.addEventListener('ended', () => {
      if (AP.audio !== a) return;
      ap.querySelector('.ap-fill').style.width = '0';
      ap.querySelector('.ap-time').textContent = fmtT(a.duration);
      ap.querySelector('.ap-toggle use').setAttribute('href', '#i-play');
    });
  }
  if (track) {
    const r = track.getBoundingClientRect();
    if (isFinite(AP.audio.duration)) AP.audio.currentTime = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * AP.audio.duration;
    return;
  }
  if (AP.audio.paused) { AP.audio.play().catch(() => toast('音频播放失败')); ap.querySelector('.ap-toggle use').setAttribute('href', '#i-pause'); }
  else apStop();
});

// ---------- 图片放大 ----------
function openLightbox(url) {
  const lb = $('#lightbox');
  lb.querySelector('img').src = url;
  lb.classList.remove('hidden');
}
$('#lightbox').onclick = () => $('#lightbox').classList.add('hidden');
// Esc：只关最上层（确认层自带处理，不抢）
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  if (!$('#confirm-overlay').classList.contains('hidden')) return;
  for (const id of ['#lightbox', '#ask-panel', '#presets-overlay', '#modal-overlay']) {
    const el = $(id);
    if (!el.classList.contains('hidden')) { el.classList.add('hidden'); return; }
  }
});

// ---------- 底部抽屉：下滑关闭（手机手势） ----------
function enableSheetDrag(overlay) {
  const sheet = overlay.querySelector('.sheet');
  if (!sheet) return;
  let y0 = null, dy = 0;
  sheet.addEventListener('touchstart', e => {
    // 内容已滚到顶部时才允许下拉关闭，否则会和滚动打架
    const body = sheet.querySelector('.sheet-body');
    if (body && body.scrollTop > 0) return;
    y0 = e.touches[0].clientY; dy = 0;
    sheet.classList.add('dragging');
  }, { passive: true });
  sheet.addEventListener('touchmove', e => {
    if (y0 === null) return;
    dy = Math.max(0, e.touches[0].clientY - y0);
    sheet.style.transform = `translateY(${dy}px)`;
    overlay.style.opacity = String(Math.max(.25, 1 - dy / 400));
  }, { passive: true });
  sheet.addEventListener('touchend', () => {
    if (y0 === null) return;
    sheet.classList.remove('dragging');
    sheet.style.transform = '';
    overlay.style.opacity = '';
    if (dy > 90) overlay.classList.add('hidden');
    y0 = null;
  });
}
['#presets-overlay', '#modal-overlay', '#ask-panel'].forEach(s => enableSheetDrag($(s)));

// ---------- 微信内置浏览器提示（无法安装到主屏、无法开启提醒） ----------
function wechatTip() {
  if (!/MicroMessenger/i.test(navigator.userAgent)) return;
  if (localStorage.getItem('zzl-wx-tip')) return;
  const bar = document.createElement('div');
  bar.className = 'wx-tip';
  bar.innerHTML = '微信内功能受限：点右上角 ··· 选「在浏览器打开」，即可安装到桌面并开启每日提醒 <button>知道了</button>';
  bar.querySelector('button').onclick = () => { bar.remove(); localStorage.setItem('zzl-wx-tip', '1'); };
  document.body.appendChild(bar);
}

// ---------- 启动 ----------
(async function init() {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  wechatTip();
  try {
    const j = await api('GET', '/api/me');
    ME = j.user;
    enterMain();
  } catch {
    show('auth');
    // 邀请链接 /?invite=XXXX：自动切到注册并填码
    const code = new URLSearchParams(location.search).get('invite');
    if (code && /^[A-Za-z0-9]{4,16}$/.test(code)) {
      setAuthMode('register');
      $('#auth-invite').value = code.toUpperCase();
      $('#auth-account').focus();
      history.replaceState(null, '', location.pathname);
    }
  }
})();

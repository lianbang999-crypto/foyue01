// 问法：文库 RAG 问答（检索 → 流式作答 → 引用跳原文）。
//
// 2026-09 重做为独占整屏的请益堂：语录式「问／答」、出处卡、往问（本机近 30 段）。
// 对话状态（往问各段与在途请求）只此一处用得着，故随模块一起走；
// 事件层（app.js）要摸它时走下面几个访问器，不直接改状态，免得两处各记一份。
// 繁简转换与海报由外部注入：在此 import 简繁那套，依赖链会绕回 app.js 成环。

import { $, esc, toast, setLS, delLS } from './util.js';
import { makeQuotePoster, showPoster, trimQuote } from './poster.js';
import { bjParts } from './station.js';
import { announce } from './a11y.js';

/* ================= 状态 ================= */
const LS_KEY = 'fy.chats';
const MAX_THREADS = 30;   // 往问最多留几段
const MAX_MSGS = 40;      // 每段最多留几条（问答各算一条）

let threads = [];         // 往问：[{ id, ts, msgs: [{ role, content, sources?, verify? }] }]
let cur = null;           // 当前这一段（也在 threads 里）
let streaming = false;
let askCtrl = null;       // 流式请求控制器（停止生成用）
let lib = null;           // 文库目录：今日一问从问答里取，由 buildWenda 注入

// 繁简转换由外部注入：海报走 canvas，不经 DOM 转换器，得自己转一道
let trans = () => (x) => x;
export function initAsk(o) {
  trans = o.trans || trans;
}

const newId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const freshThread = () => ({ id: newId(), ts: Date.now(), msgs: [] });
// 「答过了」不算护栏定句：被拒的一问之后，输入框该提示重新问，而不是「接着问」
const answered = (t) => t.msgs.some((m) => m.role === 'assistant' && !refusalKind(m));
const firstQ = (t) => t.msgs.find((m) => m.role === 'user')?.content || '';
// 往问只留答过东西的段：只挨了一句护栏定句的（离题、检索坏了）不值得留，当前这段除外
const listed = () => threads.filter((t) => t.msgs.length && (t === cur || answered(t))).sort((a, b) => b.ts - a.ts);

// 常问：栏目眉标 + 问句。问句要是文库里答得出的，别放一句好听但检索不到的
const STARTERS = [
  ['念佛', '如何对治念佛时的昏沉散乱？'],
  ['信愿', '什么是信愿行三资粮？'],
  ['临终', '临终助念应该注意什么？'],
  ['经论', '《往生论注》讲了什么？'],
  ['入门', '初学净土，应如何下手？'],
];

/* ================= 日期 ================= */
const CN = '零一二三四五六七八九';
const cnDay = (d) => d < 10 ? CN[d] : d < 20 ? '十' + (d % 10 ? CN[d % 10] : '') : CN[Math.floor(d / 10)] + '十' + (d % 10 ? CN[d % 10] : '');
const cnMonth = (m) => m < 11 ? CN[m] : '十' + CN[m - 10];
const bjKey = (ms) => { const p = bjParts(ms); return `${p.y}-${p.mo}-${p.d}`; };
const cnDate = (ms) => { const p = bjParts(ms); return `${cnMonth(p.mo)}月${cnDay(p.d)}日`; };

/** 今天 / 昨天 / 更早（group=true）或具体日期 */
function dayLabel(ms, group = false) {
  const now = Date.now();
  if (bjKey(ms) === bjKey(now)) return '今天';
  if (bjKey(ms) === bjKey(now - 86400000)) return '昨天';
  return group ? '更早' : cnDate(ms);
}

/* ================= 持久化 =================
   fy.chats = { cur, threads }：往问最多 30 段、每段 40 条，按最近提问倒序。
   旧版只存一段（fy.chat），首次读到时并进来，不丢用户手上那段对话。
   刻意不随莲号同步（见 sync.js）：属私事且量大。 */
export function saveChat() {
  const list = listed().slice(0, MAX_THREADS)
    .map((t) => ({ id: t.id, ts: t.ts, msgs: t.msgs.slice(-MAX_MSGS) }));
  setLS(LS_KEY, JSON.stringify({ cur: cur && cur.msgs.length ? cur.id : null, threads: list }), true);
}

export function loadChat() {
  let data = null;
  try { data = JSON.parse(localStorage.getItem(LS_KEY)); } catch { /* 没有或坏了 */ }
  threads = Array.isArray(data?.threads)
    ? data.threads.filter((t) => t && t.id && Array.isArray(t.msgs) && t.msgs.length)
    : [];
  cur = threads.find((t) => t.id === data?.cur) || null;
  // 旧版单段对话并入；当时正在看的就是它，接着看
  try {
    const old = JSON.parse(localStorage.getItem('fy.chat'));
    if (old?.msgs?.length) {
      const t = { id: newId(), ts: Date.now(), msgs: old.msgs };
      threads.unshift(t);
      if (!cur) cur = t;
    }
  } catch { /* 没有旧数据 */ }
  delLS('fy.chat');
  if (!cur) { cur = freshThread(); threads.unshift(cur); }
  renderLog();
  // 回到上次那一段：停在最近一答，接着问
  if (cur.msgs.length) { const el = $('#askLog'); el.scrollTop = el.scrollHeight; }
  syncAskUI();
}

/* ================= 段落（往问） ================= */
function tidy() { threads = threads.filter((t) => t.msgs.length || t === cur); }

/** 新问：旧对话自动存入往问，不再弹窗确认 */
export function newThread() {
  if (streaming) abortAsk();
  if (!cur.msgs.length) { renderLog(); return; }   // 已是空段，不必再开
  const had = answered(cur);
  cur = freshThread();
  threads.unshift(cur);
  tidy(); saveChat(); renderLog(); syncAskUI();
  if (had) toast('已存入往问');
  $('#askText')?.focus({ preventScroll: true });
}

export function openThread(id) {
  const t = threads.find((x) => x.id === id);
  if (!t || t === cur) return;
  if (streaming) abortAsk();
  cur = t;
  tidy(); saveChat(); renderLog(); syncAskUI();
}

export function clearThreads() {
  threads = [];
  cur = freshThread();
  threads.unshift(cur);
  saveChat(); renderLog(); syncAskUI();
}

/** 往问抽屉正文（复用念佛弹层 #cntSheet，mode='asklog'） */
export function histSheetHtml() {
  const list = listed();
  if (!list.length) return '<p class="hist-empty">还没有往问。问过的，会留在这里。</p>';
  let h = '', g = '';
  for (const t of list) {
    const gl = dayLabel(t.ts, true);
    if (gl !== g) { g = gl; h += `<p class="ask-eyebrow hist-d">${gl}</p>`; }
    const n = t.msgs.filter((m) => m.role === 'user').length;
    h += `<button class="hist-i${t === cur ? ' cur' : ''}" data-load-thread="${t.id}">
      <span>${esc(firstQ(t))}</span><small>${t === cur ? '当前' : n + ' 问'}</small></button>`;
  }
  return h + '<p class="hist-note">只存在本机，近 30 段；清除浏览器数据即失</p>'
    + '<button class="hist-clear" data-clear-threads>清空往问</button>';
}

/* ================= 页面态 ================= */
/** 发送键是否可按、停止态、输入框提示语，都由这一处统一刷新 ——
    分散在各调用点必漏，漏掉的那次就是一个死键。 */
export function syncAskUI() {
  const btn = $('#askSend');
  const inp = $('#askText');
  if (!btn || !inp) return;
  btn.disabled = !streaming && !inp.value.trim();
  btn.classList.toggle('stop', streaming);
  btn.setAttribute('aria-label', streaming ? '停止作答' : '提问');
  inp.placeholder = cur && answered(cur) ? '接着问 …' : '请写下您的问题 …';
}

/** 输入框随字数长高。上限只写在 CSS（.ask-composer textarea 的 max-height），
    此处不复述那个数字；超上限后 textarea 自己出滚动条。传 reset 则回到单行。 */
export function growInput(reset = false) {
  const el = $('#askText');
  if (!el) return;
  el.style.height = '';
  if (reset) return;
  el.style.height = el.scrollHeight + 'px';
}

/* —— 供事件层使用的访问器（不外露可变状态） —— */
export const isAsking = () => streaming;
export const abortAsk = () => askCtrl?.abort();
export const chatMsg = (i) => cur?.msgs[i];
export const chatCount = () => cur?.msgs.length || 0;

/* —— 作答时的滚动跟随 ——
   会话卷自己滚（不再是整页）。判断沿用聊天室那套「贴底才跟、翻看前文不打断」。 */
const STICK_SLACK = 120;   // 距底多少像素之内算「还贴着底」
const logEl = () => $('#askLog');
function nearBottom() {
  const el = logEl();
  return el.scrollHeight - el.clientHeight - el.scrollTop < STICK_SLACK;
}
let stickRaf = 0;
function stickToBottom() {
  // 逐字追加会密集触发，合并到下一帧滚一次；用瞬时而非平滑 —— 连续发起平滑滚动会彼此打架，反而抖
  cancelAnimationFrame(stickRaf);
  stickRaf = requestAnimationFrame(() => { const el = logEl(); el.scrollTop = el.scrollHeight; });
}

/* —— 软键盘 ——
   fixed 满屏的面不随软键盘缩（iOS 与新版安卓 Chrome 都只缩可视视口），
   输入框会被键盘压在底下。按可视视口把面收一收，键盘收起再放回去。 */
if (window.visualViewport) {
  const fit = () => {
    const v = $('#view-wenda');
    if (!v) return;
    const vv = window.visualViewport;
    const shrunk = document.body.dataset.view === 'wenda' && vv.height < window.innerHeight - 100;
    const stick = shrunk && nearBottom();
    v.style.height = shrunk ? `${vv.height}px` : '';
    v.style.top = shrunk ? `${vv.offsetTop}px` : '';
    if (stick) stickToBottom();
  };
  window.visualViewport.addEventListener('resize', fit);
  window.visualViewport.addEventListener('scroll', fit);
}

/* ================= 渲染 ================= */

// 文库规模由调用方给出：问法模块不持有目录数据，免得和 app.js 各存一份
export function buildWenda(library) {
  lib = library;
  $('#wdCorpus').textContent = library.chapterCount + library.qaCount;
  if (cur && !cur.msgs.length) renderLog();   // 空态里的「今日一问」要等目录到了才有
}

export function pathToHash(path) {
  if (path.startsWith('qa/')) return '#qa/' + Number(path.slice(3).replace('.txt', ''));
  const m = path.match(/^(\w+)\/(\d+)\.txt$/);
  return m ? `#read/${m[1]}/${Number(m[2])}` : '#wenku';
}

/** 今日一问：从《学佛问答》有文字稿的条目里按北京日期确定性取一条（与排播同一思路，天下同题）。
    只取问句形的标题 —— 「法要逗机」这类语录式标题拿来当问题会问得莫名其妙。 */
function todayQuestion() {
  const qa = lib?.qa;
  if (!Array.isArray(qa)) return null;
  // library.json 里的问答条目是 { n, title, path }（都有文字稿）；qa.json 那份才用 text 字段
  const pool = qa.filter((i) => (i.path || i.text) && i.title
    && (/[？?]$/.test(i.title) || /如何|怎样|怎么|为什么|为何|是否|能否|可否|什么|吗/.test(i.title)));
  if (!pool.length) return null;
  let h = 0;
  for (const ch of bjKey(Date.now())) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return pool[h % pool.length];
}

function emptyHtml() {
  const n = lib ? lib.chapterCount + lib.qaCount : 0;
  const today = todayQuestion();
  const recent = listed().filter((t) => t !== cur).slice(0, 3);
  return `<div class="ask-empty">
    <p class="ask-eyebrow">请益</p>
    <p class="ask-lead">问净土修学之事。答依大安法师${n ? ` <b>${n}</b> 篇` : ''}讲记与问答而出，每句注明出处，点编号可看原文。</p>
    ${today ? `<section class="ask-today">
      <p class="ask-eyebrow">今日一问 · ${cnDate(Date.now())}</p>
      <button class="today-q" data-q="${esc(today.title)}"><span>${esc(today.title)}</span><em>问此题 ›</em></button>
      <p class="today-n">取自《学佛问答》，每日一题</p>
    </section>` : ''}
    <section class="ask-group">
      <p class="ask-eyebrow">常问</p>
      ${STARTERS.map(([t, q]) => `<button class="ask-starter" data-q="${esc(q)}"><i>${t}</i><span>${esc(q)}</span></button>`).join('')}
    </section>
    ${recent.length ? `<section class="ask-group">
      <p class="ask-eyebrow">往问</p>
      ${recent.map((t) => `<button class="ask-starter" data-load-thread="${t.id}"><i class="dt">${esc(dayLabel(t.ts))}</i><span>${esc(firstQ(t))}</span></button>`).join('')}
      <button class="ask-more" data-hist>全部往问 ›</button>
    </section>` : ''}
  </div>`;
}

// 引用按钮统一带出处数据（s=系列 t=篇名 x=摘录 n=编号），点击弹出处预览不打断对话
const citeData = (s) =>
  `data-path="${esc(s.path)}" data-n="${s.n}" data-s="${esc(s.series)}" data-t="${esc(s.title)}" data-x="${esc(s.x || '')}"`;

function inline(h, sources, cited) {
  h = h.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');   // 最简 Markdown：加粗
  return h.replace(/\[(\d{1,2})\]/g, (_, n) => {                // [n] → 出处引用角标
    const s = sources[Number(n) - 1];
    if (!s) return `[${n}]`;
    cited.add(Number(n));
    return `<button class="cite" ${citeData(s)} title="${esc(s.series + ' ' + s.title)}">${n}</button>`;
  });
}

/** 最简 Markdown：段落、加粗、角标，外加小标题与有序/无序列表（提示词已允许模型分节列点）。
    作答中在末尾补一道朱砂笔锋。 */
function renderText(text, sources, streaming) {
  const cited = new Set();
  const lines = text.split(/\n+/).map((x) => x.trim()).filter(Boolean);
  let out = '', list = null;
  const flush = () => { if (list) { out += `</${list}>`; list = null; } };
  for (const ln of lines) {
    const li = ln.match(/^(?:[-•*]|\d{1,2}[.、)]|[（(]\d{1,2}[）)])\s*(.+)$/);
    const hd = ln.match(/^#{1,4}\s*(.+?)\s*#*$/);
    if (li) {
      const kind = /^[-•*]/.test(ln) ? 'ul' : 'ol';
      if (list !== kind) { flush(); out += `<${kind}>`; list = kind; }
      out += `<li>${inline(esc(li[1]), sources, cited)}</li>`;
    } else if (hd) { flush(); out += `<h4>${inline(esc(hd[1]), sources, cited)}</h4>`; }
    else { flush(); out += `<p>${inline(esc(ln), sources, cited)}</p>`; }
  }
  flush();
  if (streaming) out = out.replace(/(<\/(?:p|li|h4)>)(?![\s\S]*<\/(?:p|li|h4)>)/, '<span class="caret"></span>$1');
  return { html: `<div class="a-text">${out}</div>`, cited };
}

function srcCard(s) {
  return `<button class="src" ${citeData(s)}>
    <span class="src-n">${s.n}</span>
    <span class="src-t"><i>《${esc(s.series)}》</i>${esc(s.title)}</span>
    ${s.x ? `<span class="src-x">${esc(s.x)}</span>` : ''}</button>`;
}

/** 出处卡：被引用的在前，其余折叠 */
function renderSources(sources, cited) {
  if (!sources.length) return '';
  const used = sources.filter((s) => cited.has(s.n));
  const rest = sources.filter((s) => !cited.has(s.n));
  const main = used.length ? used : sources.slice(0, 3);
  const more = used.length ? rest : sources.slice(3);
  return `<div class="srcs">
    <p class="srcs-h">出处 <b>${sources.length}</b> 篇 <small>${used.length ? `引用 ${used.length} 篇 · ` : ''}点开可看原文</small></p>
    ${main.map(srcCard).join('')}
    ${more.length ? `<div class="srcs-rest" hidden>${more.map(srcCard).join('')}</div><button class="srcs-more" data-more>其余 ${more.length} 篇 ›</button>` : ''}
  </div>`;
}

/* —— 引用核验徽标 ——
   服务端答毕会做一道纯字符串自检：方括号编号有没有越出资料范围、加引号的直引
   能不能在所标那一段里逐字找到。把结果摆到用户眼前，是「可核验优先」该有的样子 ——
   机器自己都对不上的地方，先说出来，别等人读完了才发现引错。
   注意它只是信号，不改动已经答出的字；拿不准的一律请人点开出处自己核。 */
const V_OK = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
const V_WARN = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>';

export function verifyBadge(v) {
  if (!v || !v.cited) return '';       // 没标出处就没什么可核的，不摆空徽标
  if (!v.faithful) {
    return `<div class="ans-verify warn" title="回答里的方括号编号有越界，或直引原文未能与所标出处逐字对上，请点开出处核对">${V_WARN}部分引用请核对原文</div>`;
  }
  if (v.quoteChecked > 0) {
    return `<div class="ans-verify ok" title="回答里 ${v.quoteChecked} 处直引已与所标出处逐字比对一致">${V_OK}引文已核验 · 与出处逐字一致</div>`;
  }
  return `<div class="ans-verify ok" title="回答已标注 ${v.cited} 处出处编号，均在检索资料范围内，可点开逐条核对">${V_OK}已附 ${v.cited} 处出处 · 可点开核对</div>`;
}

// 每条回答尾部的操作行（纯图标：复制 / 朗读 / 有益 / 答偏了 / 分享）
const A_ICON = {
  copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="8.6" y="8.6" width="11" height="11" rx="2"/><path d="M15.4 5.4a2 2 0 0 0-2-2H6.4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2"/></svg>',
  speak: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h3l5 4z"/><path d="M16 9a3.5 3.5 0 0 1 0 6M19 6.5a7 7 0 0 1 0 11"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="5" y="5" width="14" height="14" rx="2.5"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/></svg>',
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/></svg>',
  share: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 14.5V3.6"/><path d="M8.2 7.2 12 3.5l3.8 3.7"/><path d="M8 10.5H5.5v10h13v-10H16"/></svg>',
};

export function ansActs() {
  return `<div class="ans-acts">
  <button data-ans-copy aria-label="复制回答" title="复制">${A_ICON.copy}</button>
  <button data-ans-speak aria-label="朗读回答" title="朗读">${A_ICON.speak}</button>
  <button data-ans-fb="up" aria-label="这条有益" title="有益">${A_ICON.up}</button>
  <button data-ans-fb="down" aria-label="这条答偏了" title="答偏了">${A_ICON.down}</button>
  <button data-ans-share aria-label="分享为长图" title="分享">${A_ICON.share}</button>
</div>`;
}

/* —— 护栏定句 ——
   服务端两种情形（文库里确实没有 / 检索坏了）都以一句定句作答、不带出处。
   前端认出来单独排：不列出处、不给核验与操作行，定句后给两枚去路。 */
function refusalKind(m) {
  if (m.sources && m.sources.length) return '';
  if (/^文库中未找到/.test(m.content)) return 'none';
  if (/^抱歉，文库检索暂时不可用/.test(m.content)) return 'down';
  return '';
}
function refuseHtml(kind, text, q) {
  const i = text.indexOf('。');
  const lead = i > 0 ? text.slice(0, i + 1) : text;
  const hint = i > 0 ? text.slice(i + 1).trim() : '';
  const chips = kind === 'none'
    ? '<button class="ask-chip" data-refocus>换个问法</button><button class="ask-chip" data-browse>浏览文库 ›</button>'
    : `<button class="ask-chip" data-retry="${esc(q)}">重 试</button>`;
  return `<div class="ask-refuse"><p>${esc(lead)}</p>${hint ? `<p class="hint">${esc(hint)}</p>` : ''}
    <div class="ask-chips">${chips}</div></div>`;
}

function answerHtml(m, q) {
  const kind = refusalKind(m);
  if (kind) return refuseHtml(kind, m.content, q);
  const { html, cited } = renderText(m.content, m.sources || [], false);
  return html + renderSources(m.sources || [], cited) + verifyBadge(m.verify) + ansActs();
}

// 刷新时正在作答、或答失败后的残问：留个重试，不让人对着一句没下文的问发愣
const danglingHtml = (q) =>
  `<p class="thinking">未得到回答</p><button class="chat-retry" data-retry="${esc(q)}">重 试</button>`;

function exchangeHtml(q, bodyHtml, mi, fresh) {
  return `<article class="ex${fresh ? ' ex-new' : ''}"${mi >= 0 ? ` data-mi="${mi}"` : ''}>
    <div class="ex-q"><span class="seal seal-q">问</span><p>${esc(q)}</p></div>
    <div class="ex-a"><span class="seal seal-a">答</span><div class="ex-body">${bodyHtml}</div></div>
  </article>`;
}

function renderLog() {
  const log = logEl();
  if (!log) return;
  if (!cur.msgs.length) { log.innerHTML = emptyHtml(); log.scrollTop = 0; renderRail(); return; }
  let html = '';
  for (let i = 0; i < cur.msgs.length; i++) {
    const m = cur.msgs[i];
    if (m.role !== 'user') continue;
    const a = cur.msgs[i + 1]?.role === 'assistant' ? cur.msgs[i + 1] : null;
    html += exchangeHtml(m.content, a ? answerHtml(a, m.content) : danglingHtml(m.content), a ? i + 1 : -1, false);
  }
  log.innerHTML = html;
  log.scrollTop = 0;
  renderRail();
}

/** 桌面右栏：本答出处 + 往问。手机上 display:none，渲染很便宜，不做条件判断 */
function renderRail() {
  const rail = $('#askRail');
  if (!rail) return;
  const last = [...cur.msgs].reverse().find((m) => m.role === 'assistant' && m.sources?.length);
  let h = '<p class="ask-eyebrow">本答出处</p>';
  h += last
    ? `<div class="srcs">${last.sources.map(srcCard).join('')}</div>`
    : '<p class="rail-empty">发问后，这一答的出处会列在这里；悬停正文角标即高亮对应的一篇。</p>';
  const list = listed().slice(0, 8);
  h += '<p class="ask-eyebrow rail-hist">往问</p>';
  h += list.length
    ? list.map((t) => `<button class="hist-i${t === cur ? ' cur' : ''}" data-load-thread="${t.id}"><span>${esc(firstQ(t))}</span><small>${esc(dayLabel(t.ts, true))}</small></button>`).join('')
    : '<p class="rail-empty">还没有往问。</p>';
  rail.innerHTML = h;
}

/* ================= 发问 ================= */
export async function sendQuestion(q) {
  q = q.trim();
  if (!q || streaming) return;
  const th = cur;                       // 记住是哪一段：作答中若切去别段，答案仍归原段
  streaming = true;
  askCtrl = new AbortController();
  $('#askText').value = '';
  growInput(true);          // 清空后收回单行，免得留一块空白
  syncAskUI();              // 发送键转入「停止」态

  const log = logEl();
  if (!th.msgs.length) log.innerHTML = '';   // 空态让位
  th.msgs.push({ role: 'user', content: q });
  th.ts = Date.now();                        // 段的时间 = 最近一次提问
  saveChat();
  log.insertAdjacentHTML('beforeend', exchangeHtml(q, '<p class="thinking">检索文库中 …</p>', -1, true));
  const art = log.lastElementChild;
  const body = art.querySelector('.ex-body');
  stickToBottom();

  let sources = [];
  let answer = '';
  let verify = null;                  // 服务端的引用逐字自检结果，答毕随 done 事件到
  // 回答落定：入历史 + 渲染 + 出处卡 + 核验徽标 + 操作行
  const settle = () => {
    const stick = nearBottom();
    const m = { role: 'assistant', content: answer, sources, verify };
    th.msgs.push(m);
    art.dataset.mi = th.msgs.length - 1;
    body.innerHTML = answerHtml(m, q);
    saveChat();
    if (stick) stickToBottom();       // 出处卡与操作行一并落地，别把它们顶到屏外
    if (th === cur) renderRail();
  };
  try {
    const history = th.msgs.slice(-7, -1).map((m) => ({ role: m.role, content: m.content }));
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
          const stick = nearBottom();
          // 检索阶段反馈：让人知道系统正翻文库。
          // 零命中时不能报「已找到 0 篇」—— 那是句自相矛盾的话；此时护栏已备好定句，等它来。
          body.innerHTML = sources.length
            ? `<p class="thinking">已找到 <b>${sources.length}</b> 篇相关开示，正在作答 …</p>`
            : '<p class="thinking">正在作答 …</p>';
          if (stick) stickToBottom();
        } else if (ev === 'delta') {
          answer += data.text;
          const stick = nearBottom();   // 必须在重渲染前问，渲染后高度就变了
          body.innerHTML = renderText(answer, sources, true).html;
          if (stick) stickToBottom();
        } else if (ev === 'done') {
          verify = data.verify || null;
        }
      }
    }
    if (answer) settle();
    else {
      th.msgs.pop();
      saveChat();
      body.innerHTML = `<p>（未能生成回答，请换个问法）</p><button class="chat-retry" data-retry="${esc(q)}">重 试</button>`;
    }
  } catch (e) {
    if (answer) settle();   // 中途停止：保留已生成的部分
    else {
      th.msgs.pop();        // 失败的问题不入历史
      saveChat();
      body.innerHTML = (e && e.name === 'AbortError')
        ? '<p class="thinking">已停止</p>'
        : `<p>${esc(String(e.message || '网络异常，请稍后再试').slice(0, 120))}</p>
           <button class="chat-retry" data-retry="${esc(q)}">重 试</button>`;
    }
  }
  streaming = false;
  askCtrl = null;
  syncAskUI();
  // 流式作答是逐字追加的，读屏不会主动读；答毕整段播报一次
  announce(body.querySelector('.chat-retry')
    ? '作答失败，可重试'
    : '作答完毕。' + body.textContent.trim().slice(0, 200));
}

/** 重试：把那一则连同它在历史里的痕迹一并撤掉，再问一遍 */
export function retryExchange(art, q) {
  if (streaming) return;
  const mi = Number(art?.dataset.mi);
  if (art && Number.isInteger(mi) && cur.msgs[mi]?.role === 'assistant') cur.msgs.splice(mi - 1, 2);
  else {
    // 没答成的那一问：失败时已经从历史里弹掉；刷新后残留的那种还在末尾，顺手清掉
    const last = cur.msgs[cur.msgs.length - 1];
    if (last?.role === 'user' && last.content === q) cur.msgs.pop();
  }
  saveChat();
  if (cur.msgs.length) renderLog();
  sendQuestion(q);
}

/* —— 出处角标的悬停预览与联动（只给有真鼠标的桌面）——
   手机维持原样：点一下弹出处卡片。桌面上「非点不可」是多余的一步 ——
   核对出处本该像看脚注一样，眼睛扫过去就见着，不该打断读回答的节奏。
   触屏没有真悬停，hover 在那边会变成「点一下才触发、再点别处才消失」，反而碍事，
   故用 pointer:fine 挡住。悬停角标时，同一则里与右栏里对应的出处卡一并点亮。 */
const canHover = () => window.matchMedia?.('(hover: hover) and (pointer: fine)').matches;
let citeTip = null;

function hideCiteTip() {
  if (citeTip) { citeTip.remove(); citeTip = null; }
  document.querySelectorAll('.src.hi').forEach((s) => s.classList.remove('hi'));
}

function showCiteTip(btn) {
  hideCiteTip();
  const d = btn.dataset;
  if (d.n) {
    const sel = `.src[data-n="${d.n}"]`;
    btn.closest('.ex')?.querySelectorAll(sel).forEach((s) => s.classList.add('hi'));
    $('#askRail')?.querySelectorAll(sel).forEach((s) => s.classList.add('hi'));
  }
  if (!d.x && !d.t) return;
  citeTip = document.createElement('div');
  citeTip.className = 'cite-tip';
  citeTip.innerHTML = `<b>《${esc(d.s || '')}》${esc(d.t || '')}</b>`
    + (d.x ? `<span>${esc(d.x)}…</span>` : '');
  document.body.appendChild(citeTip);
  // 贴着角标放，越出视口就往回收 —— 靠右的角标不该把卡片顶出屏幕。
  // 会话面是 fixed 满屏，视口坐标即页面坐标，不再加 scrollY
  const r = btn.getBoundingClientRect();
  const w = citeTip.offsetWidth;
  const left = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), innerWidth - w - 8);
  const above = r.top > citeTip.offsetHeight + 16;
  citeTip.style.position = 'fixed';
  citeTip.style.left = `${left}px`;
  citeTip.style.top = above ? `${r.top - citeTip.offsetHeight - 8}px` : `${r.bottom + 8}px`;
}

if (canHover()) {
  document.addEventListener('mouseover', (e) => {
    const btn = e.target.closest?.('.cite');
    if (btn) showCiteTip(btn);
  });
  document.addEventListener('mouseout', (e) => {
    if (e.target.closest?.('.cite')) hideCiteTip();
  });
  // 会话卷一滚，卡片就与角标脱节，直接收掉，不做跟随
  document.addEventListener('scroll', hideCiteTip, { passive: true, capture: true });
}

/* ================= 回答朗读 =================
   复用讲记那条 /api/tts（CosyVoice2，服务端已按文本做边缘缓存，同一段不重复计费）。
   那个接口单次上限 600 字，而回答可能到七百字，故按句切成不超过 550 字的几段依次播；
   接口不通时降级到本机 speechSynthesis —— 有声总比没有强。 */
let speakAudio = null;
let speakToken = 0;

export const isSpeaking = () => !!speakAudio || (window.speechSynthesis?.speaking ?? false);

export function stopSpeak() {
  speakToken++;                       // 作废在途回调，免得切走后又冒出声来
  if (speakAudio) { speakAudio.pause(); speakAudio = null; }
  try { window.speechSynthesis?.cancel(); } catch { /* 不支持就算了 */ }
  document.querySelectorAll('[data-ans-speak].on').forEach((b) => {
    b.classList.remove('on');
    b.innerHTML = A_ICON.speak;
  });
}

/** 去掉角标、加粗符与小标题的井号：朗读时念出「方括号三」是滑稽的 */
export function speakable(t) {
  return String(t || '').replace(/\[\d{1,2}\]/g, '').replace(/\*\*/g, '')
    .replace(/^[#\s]+/gm, '').replace(/\s+/g, ' ').trim();
}

function speakPieces(t) {
  const out = [];
  let cur2 = '';
  for (const s of t.split(/(?<=[。！？；])/)) {
    if (cur2.length + s.length > 550 && cur2) { out.push(cur2); cur2 = s; } else cur2 += s;
  }
  if (cur2.trim()) out.push(cur2);
  return out;
}

export async function speakAnswer(text, btn) {
  if (isSpeaking()) { stopSpeak(); return; }
  const clean = speakable(text);
  if (!clean) return;
  const my = ++speakToken;
  btn.classList.add('on');
  btn.innerHTML = A_ICON.stop;
  const done = () => { if (my === speakToken) stopSpeak(); };

  try {
    for (const piece of speakPieces(clean)) {
      if (my !== speakToken) return;
      const r = await fetch('/api/tts', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: piece }),
      });
      if (!r.ok) throw new Error('tts');
      const url = URL.createObjectURL(await r.blob());
      if (my !== speakToken) { URL.revokeObjectURL(url); return; }
      await new Promise((resolve, reject) => {
        speakAudio = new Audio(url);
        speakAudio.onended = resolve;
        speakAudio.onerror = reject;
        speakAudio.play().catch(reject);
      }).finally(() => URL.revokeObjectURL(url));
    }
    done();
  } catch {
    // 降级本机合成：音色差些，但断网或接口受限时仍读得出来
    if (my !== speakToken) return;
    try {
      const u = new SpeechSynthesisUtterance(clean);
      u.lang = 'zh-CN';
      u.onend = done;
      u.onerror = done;
      window.speechSynthesis.speak(u);
    } catch { toast('朗读暂不可用'); done(); }
  }
}

// 阅读时长明细只留近 7 天
export function pruneRt() {
  const p = bjParts(Date.now() - 6 * 86400000);
  const cut = `${p.y}-${String(p.mo).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
  const stale = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith('fy.rt.') && k.slice(6) < cut) stale.push(k);
  }
  for (const k of stale) delLS(k);
}

// 分享问答：复用法布施长图（问 + 答 + 依据篇目 + 二维码）
export function shareAnswer(q, a, sources) {
  const seriesList = [...new Set((sources || []).map((s) => s.series).filter(Boolean))].slice(0, 2);
  const srcLine = seriesList.length
    ? `—— 依《${seriesList.join('》《')}》讲记开示`
    : '—— 依佛乐文库讲记开示';
  showPoster(makeQuotePoster({
    T: trans(),
    quote: trimQuote(`问：${q}\n\n${a}`, 800),
    srcLine,
    url: `${location.origin}/#wenda`,
  }));
}

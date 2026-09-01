// 问道：文库 RAG 问答（检索 → 流式作答 → 引用跳原文）。
//
// 对话状态（消息表与在途请求）只此一处用得着，故随模块一起走；
// 事件层要摸它时走下面几个访问器，不直接改状态，免得两处各记一份。
// 繁简转换与海报由外部注入：在此 import 简繁那套，依赖链会绕回 app.js 成环。

import { $, esc, toast, copyText, setLS, delLS } from './util.js';
import { makeQuotePoster, showPoster, trimQuote } from './poster.js';
import { bjParts } from './station.js';
import { announce } from './a11y.js';

// 对话状态
let chat = { msgs: [], streaming: false };
let askCtrl = null;                // 问法流式请求控制器（停止生成用）

// 繁简转换由外部注入：海报走 canvas，不经 DOM 转换器，得自己转一道
let trans = () => (x) => x;
export function initAsk(o) {
  trans = o.trans || trans;
}

/* —— 作答时的滚动跟随 ——
   原先只在插入占位时滚过一次，此后再不管：回答一长，正在生成的字就跑到屏幕外，
   人得一边读一边手动往下划，流式「边生成边读」的意思就没了。
   判断沿用聊天室那套「贴底才跟、翻看前文不打断」（见 app.js 的 nearBottom），
   区别只在问法是整页滚动、聊天室是容器内滚动。 */

const STICK_SLACK = 120;   // 距底多少像素之内算「还贴着底」

function nearPageBottom() {
  const max = document.documentElement.scrollHeight - window.innerHeight;
  return max - window.scrollY < STICK_SLACK;
}

let stickRaf = 0;
function stickToBottom() {
  // 逐字追加会密集触发，合并到下一帧滚一次；用瞬时而非平滑 ——
  // 连续发起平滑滚动会彼此打架，反而抖
  cancelAnimationFrame(stickRaf);
  stickRaf = requestAnimationFrame(() => {
    window.scrollTo(0, document.documentElement.scrollHeight);
  });
}

/** 输入框随字数长高。上限只写在 CSS（.chat-input textarea 的 max-height），
    此处不复述那个数字；超上限后 textarea 自己出滚动条。
    传 reset 则回到单行（发送后清空时用）。 */
export function growInput(reset = false) {
  const el = $('#wdInput');
  if (!el) return;
  el.style.height = '';                     // 先撤掉行内高度，才能量到内容真实所需
  if (reset) return;
  el.style.height = el.scrollHeight + 'px'; // 全局 box-sizing: border-box，可直接用
}

/* 页面态：有无对话（决定「新问」与用法说明的存留）、发送键是否可按。
   都由这一处统一刷新 —— 分散在各调用点必漏，漏掉的那次就是一个死键。 */
export function syncAskUI() {
  document.body.toggleAttribute('data-chatting', chat.msgs.length > 0);
  const btn = $('#btnAsk');
  const inp = $('#wdInput');
  if (btn && inp) btn.disabled = !chat.streaming && !inp.value.trim();
}

/* —— 供事件层使用的访问器（不外露可变状态） —— */
export const isAsking = () => chat.streaming;
export const abortAsk = () => askCtrl?.abort();
export const chatMsg = (i) => chat.msgs[i];
export const chatCount = () => chat.msgs.length;
export function clearChat() {
  chat.msgs = [];
  saveChat();
  $('#chatLog').innerHTML = '';
  $('#chatStarters').hidden = false;
  syncAskUI();
}

/* ================= 问道（文库 RAG） ================= */

// 文库规模由调用方给出：问道模块不持有目录数据，免得和 app.js 各存一份
export function buildWenda(library) {
  $('#wdCorpus').textContent = library.chapterCount + library.qaCount;
}

export function pathToHash(path) {
  if (path.startsWith('qa/')) return '#qa/' + Number(path.slice(3).replace('.txt', ''));
  const m = path.match(/^(\w+)\/(\d+)\.txt$/);
  return m ? `#read/${m[1]}/${Number(m[2])}` : '#wenku';
}

export async function sendQuestion(q) {
  q = q.trim();
  if (!q || chat.streaming) return;
  chat.streaming = true;
  askCtrl = new AbortController();
  $('#wdInput').value = '';
  growInput(true);          // 清空后收回单行，免得留一块空白
  syncAskUI();              // 发送键转入「停止」态，页头也该现出「新问」
  document.querySelector('.chat-input').classList.add('asking');   // 发送键变「停止」
  $('#chatStarters').hidden = true;

  chat.msgs.push({ role: 'user', content: q });
  saveChat();
  const log = $('#chatLog');
  log.insertAdjacentHTML('beforeend', `<div class="msg user msg-new"><p>${esc(q)}</p></div>`);
  log.insertAdjacentHTML('beforeend', '<div class="msg bot streaming msg-new"><p class="thinking">检索文库中 …</p></div>');
  const botDiv = log.lastElementChild;
  botDiv.scrollIntoView({ block: 'end' });

  let sources = [];
  let answer = '';
  let verify = null;                  // 服务端的引用逐字自检结果，答毕随 done 事件到
  // 回答落定：入历史 + 渲染 + 核验徽标 + 操作行
  const settle = () => {
    const stick = nearPageBottom();
    botDiv.classList.remove('streaming');
    chat.msgs.push({ role: 'assistant', content: answer, sources, verify });
    botDiv.dataset.mi = chat.msgs.length - 1;
    botDiv.innerHTML = renderAnswer(answer, sources, false) + verifyBadge(verify) + ansActs();
    saveChat();
    if (stick) stickToBottom();       // 出处栏与操作行一并落地，别把它们顶到屏外
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
          const stick = nearPageBottom();
          // 检索阶段反馈：让人知道系统正翻文库。
          // 零命中时不能报「已找到 0 篇」—— 那是句自相矛盾的话；此时护栏已备好定句，等它来。
          botDiv.innerHTML = sources.length
            ? `<p class="thinking">已找到 ${sources.length} 篇相关开示，正在作答 …</p>`
            : '<p class="thinking">正在作答 …</p>';
          if (stick) stickToBottom();
        } else if (ev === 'delta') {
          answer += data.text;
          const stick = nearPageBottom();   // 必须在重渲染前问，渲染后高度就变了
          botDiv.innerHTML = renderAnswer(answer, sources, true);
          if (stick) stickToBottom();
        } else if (ev === 'done') {
          verify = data.verify || null;
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
  syncAskUI();
  // 流式作答是逐字追加的，读屏不会主动读；答毕整段播报一次
  announce(botDiv.querySelector('.chat-retry')
    ? '作答失败，可重试'
    : '作答完毕。' + botDiv.textContent.trim().slice(0, 200));
}

/* —— 出处角标的悬停预览（只给有真鼠标的桌面）——
   手机维持原样：点一下弹出处卡片。桌面上「非点不可」是多余的一步 ——
   核对出处本该像看脚注一样，眼睛扫过去就见着，不该打断读回答的节奏。
   触屏没有真悬停，hover 在那边会变成「点一下才触发、再点别处才消失」，反而碍事，
   故用 pointer:fine 挡住。 */
const canHover = () => window.matchMedia?.('(hover: hover) and (pointer: fine)').matches;
let citeTip = null;

function hideCiteTip() {
  if (citeTip) { citeTip.remove(); citeTip = null; }
}

function showCiteTip(btn) {
  hideCiteTip();
  const d = btn.dataset;
  if (!d.x && !d.t) return;
  citeTip = document.createElement('div');
  citeTip.className = 'cite-tip';
  citeTip.innerHTML = `<b>《${esc(d.s || '')}》${esc(d.t || '')}</b>`
    + (d.x ? `<span>${esc(d.x)}…</span>` : '');
  document.body.appendChild(citeTip);
  // 贴着角标放，越出视口就往回收 —— 靠右的角标不该把卡片顶出屏幕
  const r = btn.getBoundingClientRect();
  const w = citeTip.offsetWidth;
  const left = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), innerWidth - w - 8);
  const above = r.top > citeTip.offsetHeight + 16;
  citeTip.style.left = `${left}px`;
  citeTip.style.top = above
    ? `${r.top + scrollY - citeTip.offsetHeight - 8}px`
    : `${r.bottom + scrollY + 8}px`;
}

if (canHover()) {
  document.addEventListener('mouseover', (e) => {
    const btn = e.target.closest?.('.cite');
    if (btn) showCiteTip(btn);
  });
  document.addEventListener('mouseout', (e) => {
    if (e.target.closest?.('.cite')) hideCiteTip();
  });
  // 滚动时卡片会与角标脱节，直接收掉，不做跟随
  addEventListener('scroll', hideCiteTip, { passive: true });
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

/* —— 回答朗读 ——
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

/** 去掉角标与加粗符：朗读时念出「方括号三」是滑稽的 */
function speakable(t) {
  return String(t || '').replace(/\[\d{1,2}\]/g, '').replace(/\*\*/g, '')
    .replace(/^[#\s]+/gm, '').replace(/\s+/g, ' ').trim();
}

function speakPieces(t) {
  const out = [];
  let cur = '';
  for (const s of t.split(/(?<=[。！？；])/)) {
    if (cur.length + s.length > 550 && cur) { out.push(cur); cur = s; } else cur += s;
  }
  if (cur.trim()) out.push(cur);
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

// 对话持久化：刷新/换页回来还在；「新问」清空
export function saveChat() {
  setLS('fy.chat', JSON.stringify({ msgs: chat.msgs.slice(-40) }), true);
}
export function loadChat() {
  try { chat.msgs = JSON.parse(localStorage.getItem('fy.chat')).msgs || []; } catch { chat.msgs = []; }
  if (!chat.msgs.length) { syncAskUI(); return; }   // 空对话也要刷一次：发送键该是素的
  $('#chatLog').innerHTML = chat.msgs.map((m, i) => m.role === 'user'
    ? `<div class="msg user"><p>${esc(m.content)}</p></div>`
    : `<div class="msg bot" data-mi="${i}">${renderAnswer(m.content, m.sources || [], false)}${verifyBadge(m.verify)}${ansActs()}</div>`).join('');
  $('#chatStarters').hidden = true;
  syncAskUI();
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

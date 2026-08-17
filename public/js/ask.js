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
  // 流式作答是逐字追加的，读屏不会主动读；答毕整段播报一次
  announce(botDiv.querySelector('.chat-retry')
    ? '作答失败，可重试'
    : '作答完毕。' + botDiv.textContent.trim().slice(0, 200));
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
export function saveChat() {
  setLS('fy.chat', JSON.stringify({ msgs: chat.msgs.slice(-40) }), true);
}
export function loadChat() {
  try { chat.msgs = JSON.parse(localStorage.getItem('fy.chat')).msgs || []; } catch { chat.msgs = []; }
  if (!chat.msgs.length) return;
  $('#chatLog').innerHTML = chat.msgs.map((m, i) => m.role === 'user'
    ? `<div class="msg user"><p>${esc(m.content)}</p></div>`
    : `<div class="msg bot" data-mi="${i}">${renderAnswer(m.content, m.sources || [], false)}${ANS_ACTS}</div>`).join('');
  $('#chatStarters').hidden = true;
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

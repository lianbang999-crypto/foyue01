// 无障碍增强：浮层语义与背景遮蔽、跳转链接、动态播报。
//
// 浮层的焦点存还与 Esc 关闭已由 app.js 的 OVERLAYS 一段接管（见 bindEvents）。
// 此处只补那一段没有的三件事，由它在同一处循环里调用，不另起一套观察器 ——
// 两套观察器抢着移焦点，记下的「来处」会互相覆盖。

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), '
  + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function visibleFocusable(root) {
  return [...root.querySelectorAll(FOCUSABLE)]
    .filter((el) => !el.hidden && el.offsetParent !== null && !el.closest('[hidden]'));
}

/** 给浮层挂上模态语义。aria-modal 各读屏支持不一，故与 inert 并用。 */
export function markDialog(el, name) {
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  if (name && !el.getAttribute('aria-label')) el.setAttribute('aria-label', name);
}

/**
 * 背景遮蔽：只有最上面那层浮层可操作，其余（含被它盖住的下层浮层）一律 inert。
 * 只有 aria-modal 时，部分读屏仍会把背后的页面读出来；inert 连 Tab 一并挡住。
 *
 * openEls 按打开先后传入，最后一个即最上层。
 * 开着的那一层必须**主动摘掉** inert，不能跳过不管 —— 它很可能是在自己打开之前、
 * 被别的浮层遮蔽时被设上的：分享抽屉一开，还关着的海报浮层就吃到了 inert；
 * 随后海报叠上来，若只是跳过，那个 inert 便再没人摘，浮层看得见却整块点不动
 * （转发、保存、连关闭都失灵，人被困在预览里出不来）。
 */
export function setBackgroundInert(openEls) {
  const open = [...openEls];
  const top = open[open.length - 1] || null;
  for (const el of document.body.children) {
    if (el.tagName === 'AUDIO' || el.tagName === 'SCRIPT') continue;
    if (!top || el === top) el.removeAttribute('inert');
    else el.setAttribute('inert', '');
  }
}

/** Tab 循环困在最上层浮层内，兜住 inert 不可用的老浏览器。 */
export function trapTab(e, topEl) {
  if (e.key !== 'Tab' || !topEl) return;
  const items = visibleFocusable(topEl);
  if (!items.length) return;
  const first = items[0];
  const last = items[items.length - 1];
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}

/** 跳到主内容：全站 hash 路由，#main 会被 route() 当未知锚点弹回首页，故只移焦点不动 hash。 */
export function initSkipLink() {
  const skip = document.querySelector('.skip-link');
  if (!skip) return;
  skip.addEventListener('click', (e) => {
    e.preventDefault();
    const main = document.getElementById('main');
    if (main) { main.focus({ preventScroll: true }); main.scrollIntoView({ block: 'start' }); }
  });
}

// 播报：把只有视觉/震动能感知的变化说给读屏听（满串、定课圆满、作答完毕等）
let politeEl = null;
export function announce(msg) {
  if (!msg) return;
  if (!politeEl) {
    politeEl = document.createElement('div');
    politeEl.className = 'sr-only';
    politeEl.setAttribute('aria-live', 'polite');
    politeEl.setAttribute('aria-atomic', 'true');
    document.body.appendChild(politeEl);
  }
  // 同文本连播两次读屏会吞掉后一次，加个宽字空格错开
  politeEl.textContent = politeEl.textContent === msg ? msg + '　' : msg;
}

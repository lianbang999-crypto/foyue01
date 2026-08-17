// 通用小工具：选择器、转义、提示条、剪贴板、震动。
// 全站各模块都要用，且都不依赖应用状态，故单列一处，谁都可以 import。

export const $ = (s) => document.querySelector(s);

export function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

let toastT = 0;
export function toast(text) {
  let el = $('#toast');
  let fresh = false;
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast';
    // 全站唯一的提示通道，对读屏必须发声。属性要在插入 DOM 之前就位，
    // 否则读屏可能来不及把它认成实时区域，第一条提示就哑了。
    el.setAttribute('role', 'status');
    el.setAttribute('aria-live', 'polite');
    el.setAttribute('aria-atomic', 'true');
    document.body.appendChild(el);
    fresh = true;
  }
  // 首次创建时延一帧再写文案，给读屏留出登记实时区域的时间
  if (fresh) requestAnimationFrame(() => { el.textContent = text; });
  else el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastT);
  toastT = setTimeout(() => el.classList.remove('show'), 2600);
}

export async function copyText(text) {
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

// 计数震动（默认开，功课中心可关；iOS 等不支持则静默）
export function vibrate(pattern) {
  if (localStorage.getItem('fy.vib') !== '0' && navigator.vibrate) navigator.vibrate(pattern);
}

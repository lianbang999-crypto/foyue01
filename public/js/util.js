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

/* —— 本机存储 ——
   localStorage.setItem 会抛：配额满、iOS 隐私浏览下配额为 0、用户禁了站点数据。
   原先各处直接裸调，一抛就把调用方后面的活也打断了 —— 念佛计数最典型：
   写盘一失败，后面的 renderCount() 就不执行，念珠点了数字纹丝不动，
   还一句提示都没有，用户念了半天才发现一声没存。
   这里改为：失败不外抛（调用方照常渲染，内存里的数是真的），
   要紧的数据另给一句提示，并按分钟节流 —— 念佛时每声弹一次比不弹还糟。 */

let warnedAt = 0;
export function setLS(key, value, critical = false) {
  try {
    localStorage.setItem(key, value);
    return true;
  } catch {
    if (critical && Date.now() - warnedAt > 60000) {
      warnedAt = Date.now();
      toast('未能存入本机 · 请检查浏览器存储设置');
    }
    return false;
  }
}

export function delLS(key) {
  try { localStorage.removeItem(key); return true; } catch { return false; }
}

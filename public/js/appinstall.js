/* 安卓应用的下载引导与版本提示。
 *
 * 网页版每听一句、每读一篇都要联网，站点在 Cloudflare 上，国内网络一波动就是
 * 「打不开」。安卓应用把壳与全部讲记正文随包装进手机，装完断网也能读、能念、
 * 能听已下载的音频，还能锁屏后台恭听 —— 这个差别对网络不稳的人是决定性的，
 * 值得在底部说一句，而不是藏在「我的」页里等人自己找。
 *
 * 三种身份，三种说法：
 *   · 应用内（window.__fyNative 存在）—— 一句都不劝，改为报版本、查新版；
 *   · 安卓浏览器 —— 底部横幅引导下载，关掉后十四天不再打扰；
 *   · 微信/QQ 等内置浏览器 —— 它们会拦下 apk 下载，给按钮只会让人点了没反应，
 *     故只讲怎么绕出去，不放下载键。
 *
 * 版本与下载地址只认线上的 /app/release.json（由 scripts/build-app-assets.py
 * 依 build.gradle 的 versionName 生成）。刻意不写进本文件的常量：应用内的这份
 * 脚本是随安装包出厂的，写死了就成了拿自己的版本比自己，永远说已是最新。
 * 那个地址不在安装包内，取件台会放它走网络（见 app-android 的 AppContentHandler）。
 */
(function () {
  'use strict';

  var ua = navigator.userAgent || '';
  var isIOS = /iphone|ipad|ipod/i.test(ua) && !window.MSStream;
  var isSafari = isIOS && /safari/i.test(ua) && !/crios|fxios|edgios/i.test(ua);
  var isAndroid = /android/i.test(ua);
  // 微信/QQ/微博/UC 等内置 WebView：拦下载、无安装能力，只能引导到系统浏览器
  var inAppBrowser = /micromessenger|qq\/|qqbrowser|weibo|baiduboxapp|ucbrowser|quark/i.test(ua);
  // __fyNative 由原生在载入页面前注入，此处必定已可见
  var inApp = typeof window.__fyNative === 'object' && window.__fyNative !== null;

  var HIDE_KEY = 'fy.apk.hide';
  var HIDE_DAYS = 14;
  var rel = null;          // { version, url, size } —— 线上最新版
  var upTimer = null;

  window.__fyInstall = { inApp: inApp, isAndroid: isAndroid, isIOS: isIOS };

  /* ══════════ 取线上发布信息 ══════════ */

  function load() {
    return fetch('/app/release.json', { cache: 'no-store' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        if (j && j.version && j.url) rel = j;
        return rel;
      })
      .catch(function () { return null; });   // 没配或取不到就当没有，绝不给死链
  }

  /** a 是否比 b 旧。按点分段逐段比数字，段数不同以缺位为 0。 */
  function older(a, b) {
    var x = String(a || '').split('.');
    var y = String(b || '').split('.');
    for (var i = 0; i < Math.max(x.length, y.length); i++) {
      var p = parseInt(x[i], 10) || 0;
      var q = parseInt(y[i], 10) || 0;
      if (p !== q) return p < q;
    }
    return false;
  }

  function mb(n) { return n > 0 ? '约 ' + Math.round(n / 1048576) + 'MB' : ''; }

  /* ══════════ 「我的」页的安装区 ══════════ */

  var IC_DOWN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="2.5" width="12" height="19" rx="2.5"/><path d="M12 8v6"/><path d="M9.5 11.5 12 14l2.5-2.5"/></svg>';

  function row(inner) {
    return '<span class="wr-ic" aria-hidden="true">' + IC_DOWN + '</span>' + inner;
  }

  function renderWodeApp() {
    var sub = document.getElementById('appSub');
    var box = document.getElementById('appGroup');
    if (!sub || !box) return;

    var html = '';
    if (inApp) {
      var cur = String(window.__fyNative.version && window.__fyNative.version() || '');
      var has = rel && older(cur, rel.version);
      if (has) {
        html = '<button class="wode-row" id="appUpdate">' + row(
          '<span class="wr-t">有新版本 ' + esc(rel.version) + '<small>当前 ' + esc(cur)
          + (rel.size ? ' · ' + mb(rel.size) : '') + '</small></span>'
          + '<span class="wr-n" id="appUpState"></span><span class="wr-go">›</span>') + '</button>';
      } else {
        html = '<div class="wode-row">' + row(
          '<span class="wr-t">安卓应用<small>版本 ' + esc(cur) + ' · 已是最新</small></span>') + '</div>';
      }
    } else if (rel && isIOS) {
      // iPhone 装不了 apk。这里摆个下载链接，点了只会得到一个打不开的文件 ——
      // 与其如此，不如什么都不说，由下面那条「添加到主屏幕」接手。
      html = '';
    } else if (rel && !inAppBrowser) {
      html = '<a class="wode-row wode-ext" href="' + esc(rel.url) + '" download>' + row(
        '<span class="wr-t">下载安卓版<small>随包带走全部讲记 · 断网可读 · 锁屏可听'
        + (rel.size ? ' · ' + mb(rel.size) : '') + '</small></span>'
        + '<span class="wr-go wr-out" aria-hidden="true">↓</span>') + '</a>';
    } else if (rel && inAppBrowser) {
      html = '<div class="wode-row">' + row(
        '<span class="wr-t">下载安卓版<small>请点右上角「⋯」→ 在浏览器中打开，再下载</small></span>') + '</div>';
    }

    // iOS 没有安装包，只能引导添加到主屏；非 Safari 连这条路也没有，就不提了
    if (isSafari && !inApp && !isStandalone()) {
      html += '<div class="wode-row">' + row(
        '<span class="wr-t">添加到主屏幕<small>Safari 点 ⎙ 分享 →「添加到主屏幕」，离线更稳</small></span>') + '</div>';
    }

    box.innerHTML = html;
    box.hidden = !html;
    sub.hidden = !html;

    var btn = document.getElementById('appUpdate');
    if (btn) btn.addEventListener('click', startUpdate);
  }

  function isStandalone() {
    try {
      return window.matchMedia('(display-mode: standalone)').matches
        || window.navigator.standalone === true;
    } catch (e) { return false; }
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ══════════ 应用内更新 ══════════ */

  function startUpdate() {
    if (!rel || !window.__fyNative.update) return;
    // 相对地址要补成绝对：原生那边是拿 HttpURLConnection 直接下的，不认 /app/…
    var url = rel.url.indexOf('http') === 0 ? rel.url : 'https://foyue.org' + rel.url;
    window.__fyNative.update(url);
    if (upTimer) clearInterval(upTimer);
    upTimer = setInterval(pollUpdate, 500);
    pollUpdate();
  }

  function pollUpdate() {
    var el = document.getElementById('appUpState');
    if (!el) { clearInterval(upTimer); upTimer = null; return; }
    var s;
    try { s = JSON.parse(window.__fyNative.updateState()); } catch (e) { return; }
    if (s.stage === 'downloading') el.textContent = s.percent + '%';
    else if (s.stage === 'ready') { el.textContent = '请确认安装'; clearInterval(upTimer); upTimer = null; }
    else if (s.stage === 'error') { el.textContent = s.msg || '下载失败'; clearInterval(upTimer); upTimer = null; }
  }

  /* ══════════ 底部横幅 ══════════ */

  function bannerAllowed() {
    if (inApp || isStandalone()) return false;
    try {
      var until = parseInt(localStorage.getItem(HIDE_KEY) || '0', 10);
      if (until && Date.now() < until) return false;
    } catch (e) { /* localStorage 不可用则照常提示 */ }
    return true;
  }

  function dismiss() {
    try { localStorage.setItem(HIDE_KEY, String(Date.now() + HIDE_DAYS * 864e5)); } catch (e) { /* 忽略 */ }
    var el = document.getElementById('apkBar');
    if (el) el.remove();
  }

  function banner(inner) {
    if (document.getElementById('apkBar')) return null;
    var bar = document.createElement('div');
    bar.id = 'apkBar';
    bar.setAttribute('role', 'dialog');
    bar.setAttribute('aria-label', '下载安卓版');
    bar.style.cssText = [
      'position:fixed', 'left:50%', 'bottom:0', 'transform:translateX(-50%)',
      'z-index:2147483000', 'width:min(34rem,100%)', 'box-sizing:border-box',
      'display:flex', 'align-items:center', 'gap:.7rem',
      'padding:.7rem .85rem', 'padding-bottom:calc(.7rem + env(safe-area-inset-bottom,0))',
      'background:var(--bg,#f6f1e6)', 'color:var(--ink,#322a1e)',
      'border-top:1px solid var(--line,#d9cdb2)',
      'box-shadow:0 -6px 24px rgba(0,0,0,.12)',
      'font-family:var(--serif,serif)', 'font-size:14px', 'line-height:1.45',
      'animation:apk-up .28s ease both'
    ].join(';');
    bar.innerHTML =
      '<span aria-hidden="true" style="flex:0 0 auto;width:34px;height:34px;border-radius:8px;overflow:hidden">'
      + '<img src="/icon-192.png" alt="" width="34" height="34" style="display:block;width:100%;height:100%"></span>'
      + '<div style="flex:1 1 auto;min-width:0">' + inner + '</div>';

    var close = document.createElement('button');
    close.setAttribute('aria-label', '关闭');
    close.textContent = '✕';
    close.style.cssText = 'flex:0 0 auto;border:0;background:transparent;color:var(--ink,#322a1e);'
      + 'opacity:.5;font-size:16px;line-height:1;padding:.3rem;cursor:pointer';
    close.onclick = dismiss;
    bar.appendChild(close);

    if (!document.getElementById('apkBarStyle')) {
      var st = document.createElement('style');
      st.id = 'apkBarStyle';
      st.textContent = '@keyframes apk-up{from{transform:translate(-50%,100%)}to{transform:translate(-50%,0)}}';
      document.head.appendChild(st);
    }
    document.body.appendChild(bar);
    return bar;
  }

  function showBanner() {
    if (!rel) return;                       // 没有安装包就别提，免得给个死链
    if (inAppBrowser) {
      banner('<b>装上离线版，断网也能听也能读</b><br>'
        + '<span style="opacity:.75">请点右上角「⋯」→ 在浏览器中打开，再下载</span>');
      return;
    }
    var bar = banner('<b>装上离线版，断网也能听也能读</b><br>'
      + '<span style="opacity:.75">全部讲记随包带走' + (rel.size ? ' · ' + mb(rel.size) : '') + ' · 可锁屏后台恭听</span>');
    if (!bar) return;
    var a = document.createElement('a');
    a.href = rel.url;
    a.setAttribute('download', '');
    a.textContent = '下载';
    a.style.cssText = 'flex:0 0 auto;margin-left:.5rem;border:0;border-radius:8px;'
      + 'background:var(--accent,#b03a26);color:var(--bg,#f6f1e6);text-decoration:none;'
      + 'font-family:inherit;font-size:14px;font-weight:600;padding:.42rem .9rem;cursor:pointer';
    a.onclick = function () { setTimeout(dismiss, 400); };   // 点了就别再纠缠
    bar.insertBefore(a, bar.lastChild);     // 放在关闭按钮之前
  }

  /* ══════════ 起手 ══════════ */

  load().then(function () {
    renderWodeApp();
    if (!isAndroid || !bannerAllowed()) return;
    // 比进门晚一点：让人先看到今日案头，别一进来就被弹窗迎面挡住
    setTimeout(showBanner, 4000);
  });
})();

/*
 * §133 鹿角补充包收银页（App 外跳专用，极简无导航）。
 * 与 pay.js 同一套纪律：只显示 Paddle 的 formattedTotals，不做价格计算；
 * 国家未知不传 address；环境没配就报错停住；服务端 API key 不出现在客户端。
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var cfg = null;

  function notice(msg, isErr) {
    var el = $('notice');
    el.textContent = msg;
    el.className = 'notice' + (isErr ? ' err' : '');
  }

  // App 外跳握手：?session=<短期会话> → 存进网页端同一个 token 键，随即抹掉地址栏参数
  (function claimSession() {
    var m = /[?&]session=([a-f0-9]{64})/i.exec(location.search);
    if (!m) return;
    try { localStorage.setItem('lk_token', m[1]); } catch (e) { /* 隐私模式忽略 */ }
    history.replaceState(null, '', location.pathname);
  })();

  function token() {
    try { return localStorage.getItem('lk_token') || ''; } catch (e) { return ''; }
  }

  function previewOne(priceId) {
    if (!priceId) return Promise.resolve('');
    var req = { items: [{ priceId: priceId, quantity: 1 }] };
    if (cfg.country) req.address = { countryCode: cfg.country };
    return Paddle.PricePreview(req).then(function (res) {
      var li = res && res.data && res.data.details && res.data.details.lineItems;
      return (li && li.length) ? li[0].formattedTotals.total : '';
    }).catch(function (e) { console.log('PricePreview 失败', priceId, e); return ''; });
  }

  function openCheckout(priceId) {
    if (!priceId) { notice('这个商品还没有配置好，请稍后再试。', true); return; }
    if (!cfg.user) {
      notice('请先在 Looka 里登录后再来购买 —— 这样鹿角才能记到你的账号上。', true);
      return;
    }
    var opts = {
      items: [{ priceId: priceId, quantity: 1 }],
      customer: { email: cfg.user.email },
      customData: { user_id: String(cfg.user.id) },
      settings: {
        displayMode: 'overlay',
        variant: 'one-page',
        successUrl: location.origin + '/welcome'
      }
    };
    // 同 pay.js：不设 allowedPaymentMethods（白名单会把微信支付等方式挡掉），
    // 交给 Paddle 按买家国家与后台启用项自动决定
    Paddle.Checkout.open(opts);
  }

  var h = { 'Content-Type': 'application/json' };
  var t = token();
  if (t) h['Authorization'] = 'Bearer ' + t;

  fetch('/api/paddle/config', { headers: h }).then(function (r) {
    return r.json().then(function (d) {
      if (!r.ok || !d.ok) throw new Error(d && d.error ? d.error : '配置读取失败');
      return d;
    });
  }).then(function (d) {
    cfg = d;
    if (cfg.env !== 'production' && cfg.env !== 'sandbox') {
      throw new Error('支付环境未配置（PADDLE_ENV）');
    }
    Paddle.Environment.set(cfg.env);
    Paddle.Initialize({ token: cfg.token });
    return Promise.all([
      previewOne(cfg.prices.antler_1000).then(function (s) { $('p1000').textContent = s || '—'; }),
      previewOne(cfg.prices.antler_3000).then(function (s) { $('p3000').textContent = s || '—'; })
    ]);
  }).then(function () {
    $('b1000').onclick = function () { openCheckout(cfg.prices.antler_1000); };
    $('b3000').onclick = function () { openCheckout(cfg.prices.antler_3000); };
  }).catch(function (e) {
    notice('暂时打不开购买页：' + (e && e.message ? e.message : '未知错误') +
      '。可以稍后再试，或联系 looka01@qq.com。', true);
    $('b1000').disabled = true;
    $('b3000').disabled = true;
  });
})();

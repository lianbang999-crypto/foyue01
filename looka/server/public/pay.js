/*
 * §133 Looka 定价页 —— Paddle Checkout（overlay / one-page）
 *
 * 铁律（照 spec，逐条落到代码里）：
 *  1. 价格**只显示 Paddle 返回的 formattedTotals**。这里没有任何价格计算、没有
 *     Intl.NumberFormat、不做四舍五入、不拼货币符号 —— 汇率、税、区域格式全归 Paddle。
 *  2. 国家未知就**整个不传 address**，让 Paddle 按访客 IP 自测。我们内部若有 'OTHERS'
 *     之类哨兵，只留在自己代码里，绝不当国家码送出去。
 *  3. 环境从服务端下发，**没配就报错停住**，绝不静默默认 —— 拿错环境等于对错账号收钱。
 *  4. 服务端 API key 永远不出现在这个文件里（它只在 worker 里用）。
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var cfg = null, cycle = 'month', priceIds = { month: '', year: '' }, isCN = false;
  var shown = {};      // priceId -> Paddle 返回的已格式化总价字符串（原样，不再加工）

  function notice(msg, isErr) {
    var el = $('notice');
    el.textContent = msg;
    el.className = 'notice' + (isErr ? ' err' : '');
  }

  /* ---------- App 外跳握手：?session=<短期会话> ---------- */
  // App 里已登录但系统浏览器没有，worker 签一个 15 分钟会话带在 URL 上。
  // 存进与网页端同一个键（lk_token），随即把参数从地址栏抹掉 —— 免得留在历史/分享链接里。
  (function claimSession() {
    var m = /[?&]session=([a-f0-9]{64})/i.exec(location.search);
    if (!m) return;
    try { localStorage.setItem('lk_token', m[1]); } catch (e) { /* 隐私模式忽略 */ }
    history.replaceState(null, '', location.pathname);
  })();

  function token() {
    try { return localStorage.getItem('lk_token') || ''; } catch (e) { return ''; }
  }

  /* ---------- 取配置 ---------- */
  function loadConfig() {
    var h = { 'Content-Type': 'application/json' };
    var t = token();
    if (t) h['Authorization'] = 'Bearer ' + t;
    return fetch('/api/paddle/config', { headers: h }).then(function (r) {
      return r.json().then(function (d) {
        if (!r.ok || !d.ok) throw new Error(d && d.error ? d.error : '配置读取失败');
        return d;
      });
    });
  }

  /* ---------- 取价：一个价格一次 PricePreview ----------
   * 拆开逐个查，是为了避开「同一次结账里的周期性商品必须同周期」那条限制 ——
   * 月付、年付、一次性买断混在一个请求里本来就不是一回事。
   */
  function previewOne(priceId) {
    if (!priceId) return Promise.resolve('');
    var req = { items: [{ priceId: priceId, quantity: 1 }] };
    // 国家拿不到就不带 address（规则 2）
    if (cfg.country) req.address = { countryCode: cfg.country };
    return Paddle.PricePreview(req).then(function (res) {
      var li = res && res.data && res.data.details && res.data.details.lineItems;
      if (!li || !li.length) return '';
      // 原样使用 Paddle 的格式化字符串，不做任何再加工
      return li[0].formattedTotals.total;
    }).catch(function (e) {
      console.log('PricePreview 失败', priceId, e);
      return '';
    });
  }

  /* ---------- 渲染 ---------- */
  function paint() {
    var id = priceIds[cycle];
    $('proPrice').textContent = shown[id] || '—';
    if (isCN) {
      $('proPer').textContent = cycle === 'year' ? '一次性 · 12 个月' : '一次性 · 30 天';
      $('proSub').textContent = '一次付清，不自动续费';
      $('cycleNote').textContent = '中国区为一次性通行证，到期不会自动扣款。';
    } else {
      $('proPer').textContent = cycle === 'year' ? '每年 · 可随时取消' : '每月 · 可随时取消';
      $('proSub').textContent = '小鹿陪你更久，装扮随便挑';
      $('cycleNote').textContent = cycle === 'year' ? '按年订阅，约等于送两个月。' : '';
    }
    $('btnPro').textContent = isCN ? (cycle === 'year' ? '购买年卡' : '购买月卡') : '订阅';
  }

  function setCycle(c) {
    cycle = c;
    $('tabMonth').className = c === 'month' ? 'on' : '';
    $('tabYear').className = c === 'year' ? 'on' : '';
    paint();
  }

  /* ---------- 结账 ---------- */
  function openCheckout(priceId) {
    if (!priceId) { notice('这个商品还没有配置好，请稍后再试。', true); return; }
    // 未登录先登录：user_id 要写进 customData，webhook 回来才认得是谁买的
    if (!cfg.user) {
      notice('请先登录 Looka 账号，再回到这一页购买 —— 这样付款才能开通到你的账号上。', false);
      $('notice').innerHTML += ' <a href="/">去登录 →</a>';
      return;
    }
    var opts = {
      items: [{ priceId: priceId, quantity: 1 }],
      customer: { email: cfg.user.email },              // 已登录就预填邮箱
      customData: { user_id: String(cfg.user.id) },     // 归属唯一可信来源
      settings: {
        displayMode: 'overlay',
        variant: 'one-page',
        successUrl: location.origin + '/welcome'
      }
    };
    // 中国区显式带上支付宝（已查证 Paddle 支持 alipay；微信支付不在其支持列表内）
    if (isCN) opts.settings.allowedPaymentMethods = ['alipay', 'card', 'paypal'];
    Paddle.Checkout.open(opts);
  }

  /* ---------- 启动 ---------- */
  loadConfig().then(function (d) {
    cfg = d;
    // 规则 3：环境必须是服务端明确给的，绝不猜
    if (cfg.env !== 'production' && cfg.env !== 'sandbox') {
      throw new Error('支付环境未配置（PADDLE_ENV）');
    }
    Paddle.Environment.set(cfg.env);
    Paddle.Initialize({ token: cfg.token });

    isCN = cfg.country === 'CN';
    // 中国区走一次性通行证（支付宝对自动续费的支持没有查到凭据，不赌）
    priceIds = isCN
      ? { month: cfg.prices.pass_month, year: cfg.prices.pass_year }
      : { month: cfg.prices.pro_month, year: cfg.prices.pro_year };

    var jobs = [
      previewOne(priceIds.month).then(function (s) { shown[priceIds.month] = s; }),
      previewOne(priceIds.year).then(function (s) { shown[priceIds.year] = s; })
    ];
    // Founder 只在批次开放且配了价格时才出现（合同 ui_rules：售罄后完全退出页面）
    if (cfg.founder_open && cfg.prices.founder) {
      $('tierFounder').className = 'tier';
      jobs.push(previewOne(cfg.prices.founder).then(function (s) {
        shown[cfg.prices.founder] = s;
        $('fdPrice').textContent = s || '—';
      }));
    }
    return Promise.all(jobs);
  }).then(function () {
    setCycle('month');
    $('tabMonth').onclick = function () { setCycle('month'); };
    $('tabYear').onclick = function () { setCycle('year'); };
    $('btnPro').onclick = function () { openCheckout(priceIds[cycle]); };
    $('btnFounder').onclick = function () { openCheckout(cfg.prices.founder); };
  }).catch(function (e) {
    // Paddle 还没配好（或临时不可用）时**必须留一条能买的路** ——
    // 否则这一页就成了死胡同，比接入前还差。回落到接入前那条爱发电/Ko-fi 通道。
    console.log('Paddle 不可用，回落旧通道', e);
    fallback();
  });

  function fallback() {
    var zh = (navigator.language || 'zh').toLowerCase().indexOf('zh') === 0;
    notice('新的支付通道正在开通中，先用原来的通道购买 —— 付款后回到 Looka 会自动开通。', false);
    $('proPrice').textContent = zh ? '¥12' : '$5';
    $('proPer').textContent = zh ? '每月' : 'per month';
    $('btnPro').textContent = '去付款';
    $('tabYear').style.display = 'none';   // 旧通道只按月下单，别给一个点了没用的按钮
    $('btnPro').onclick = function () {
      var url = zh
        ? 'https://ifdian.net/order/create?plan_id=95141ca09d2711f1bead52540025c377&product_type=0'
        : 'https://ko-fi.com/summary/8389f40f-12d2-4d22-8ecb-32d91359dc4a';
      var t = token();
      if (zh && t) {
        // 带 LK 短码下单，付款后服务端能自动归属到账号（拿不到就用裸链接，还有订单号认领兜底）
        fetch('/api/pay/intent', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t },
          body: JSON.stringify({ plan: 'month' })
        }).then(function (r) { return r.json(); }).then(function (d) {
          location.href = (d && d.url) ? d.url : url;
        }).catch(function () { location.href = url; });
      } else {
        location.href = url;
      }
    };
  }
})();

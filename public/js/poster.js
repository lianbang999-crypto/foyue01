// 分享海报：把一集讲经、一段直播、一句摘录画成可转发的长图。
//
// 从 app.js 分出来的一整块 canvas 绘制，与应用状态无涉：
// 输入一个 payload，输出一张画布。繁简转换由调用方以 p.T 传入 ——
// 若在此 import 简繁模块，依赖链会绕回 app.js 形成环。

import { $, toast } from './util.js';
import { WEEK } from './const.js';
import { bjParts, fmtMMSS } from './station.js';

/* ================= 分享海报 =================
   海报是站外唯一的门面，配色必须与站内同源。下面这套值即 style.css 的
   昼间纸墨（--bg、--card、--ink 三阶、--c-zhusha、--gold 两色）的对应色 ——
   自配色与《印光法师文钞》站合流后，这里不再降饱和，直接取文钞纸墨原值。
   改站内主题色时此处需同步，否则转发出去的是「另一版佛乐」。
   海报固定用昼间素宣纸底 —— 分享落地的聊天窗背景不可控，浅底最稳。 */
const POSTER = {
  paper: '#f6f1e6',                      // 宣纸底（= 昼间 --bg，即文钞 --paper）
  paperHi: '#fcf9f2',                    // 钮内白（播放三角）＝ --card 的不透明版
  card: '#fcf9f2',                       // 播放器卡片底（同上，浮起的纸）
  cardLine: '#d9cdb2',                   // 卡片描边 （= --line，文钞界栏）
  track: '#e7dcc4',                      // 进度槽   （文钞 --paper-3）
  ink: '#322a1e',                        // 正文墨   （--ink）
  ink2: '#6d5f49',                       // 次墨     （--ink-2）
  ink3: '#72644f',                       // 三级墨   （--ink-3）
  zhusha: '#b03a26',                     // 朱砂     （--c-zhusha，文钞 --cinnabar）
  zhushaWash: 'rgba(176, 58, 38, 0.08)',
  zhushaSoft: 'rgba(176, 58, 38, 0.35)',
  halo1: 'rgba(176, 58, 38, 0.07)',     // 播放钮外层光晕
  halo2: 'rgba(176, 58, 38, 0.13)',     // 播放钮内层光晕
  gold: '#79643a',                       // 泥金字   （--gold-text）
  ruleSoft: 'rgba(149, 125, 77, 0.34)',  // 海报专用淡界栏（纸底比站内亮，故比站内界栏再淡一档）
  rule: 'rgba(149, 125, 77, 0.42)',      // 界栏     （= --gold-line）
  ringInner: 'rgba(252, 249, 242, 0.22)',// 播放钮内环（纸白描一道，不与 paperHi 拼 alpha —— 拼接一改就静默失效）
  qr: '#26211a',                         // 二维码模块：功能色不随主题走，扫码要的是尽可能深
};

/* 高清画布：canvas 按 CSS 像素排版，再乘设备像素比出图，
   否则 2x/3x 手机上预览与保存的海报都是糊的。上限 3 倍，
   再高只是徒增内存（750×1040@3x 已是 2250×3120）。 */
const CANVAS_MAX_AREA = 16000000;   // iOS Safari 的画布上限约 16.78M 像素，留一点余量
const CANVAS_MAX_SIDE = 8192;       // 单边上限，保守取值
function hiCanvas(w, h) {
  let dpr = Math.min(3, Math.max(1, Math.round(window.devicePixelRatio || 1)));
  // 超限的画布在 iOS 上不会报错，只会画出一张全白图 —— 宁可降清晰度也不能出空白。
  // 法布施长图的高度随所选文字增长，dpr=3 时很容易撞上，故按面积与单边逐级回落。
  while (dpr > 1 && (w * dpr * h * dpr > CANVAS_MAX_AREA
                     || h * dpr > CANVAS_MAX_SIDE || w * dpr > CANVAS_MAX_SIDE)) dpr--;
  const cv = document.createElement('canvas');
  cv.width = w * dpr; cv.height = h * dpr;
  const ctx = cv.getContext('2d');
  ctx.scale(dpr, dpr);
  return { cv, ctx };
}

// 逐字换行（中文无空格），超出行数截断加省略号。
// 数字与西文按整串走，否则「第01讲」会断成「第0 / 1讲」、时间与卷号同理；
// 单串本身就超过行宽时（极长英文）才退回逐字断。
function wrapLines(ctx, text, maxW, maxLines) {
  const out = [];
  let line = '';
  const tokens = String(text).match(/[0-9A-Za-z]+|[\s\S]/g) || [];
  const push = (tk) => {
    const blank = /^\s$/.test(tk);
    if (!line && blank) return;                  // 行首不留空格
    if (line && ctx.measureText(line + tk).width > maxW) {
      out.push(line);
      line = blank ? '' : tk;                    // 断在空格上时把它丢掉，别带到下一行开头
    } else line += tk;
  };
  for (const tk of tokens) {
    if (tk === '\n') { if (line) out.push(line); line = ''; continue; }
    if (tk.length > 1 && ctx.measureText(tk).width > maxW) { for (const ch of tk) push(ch); }
    else push(tk);
  }
  if (line) out.push(line);
  if (out.length > maxLines) {
    out.length = maxLines;
    out[maxLines - 1] = ellipsize(ctx, out[maxLines - 1], maxW);
  }
  return out;
}

// 把一行压进 maxW 并补省略号。按码点退（Array.from），避免把代理对切成半个字
function ellipsize(ctx, line, maxW) {
  const cs = Array.from(line);
  while (cs.length && ctx.measureText(cs.join('') + '…').width > maxW) cs.pop();
  return cs.join('') + '…';
}

// 二维码：直接落在宣纸底上（四周留白即静区），依赖 /js/qrcode.js 全局 qrcode（MIT）
function drawQR(ctx, text, x, y, size) {
  if (typeof window.qrcode !== 'function') return false;
  let qr;
  try {
    qr = window.qrcode(0, 'M');   // 0 = 按内容自动选型号
    qr.addData(text);
    qr.make();
  } catch { return false; }
  const n = qr.getModuleCount();
  const cell = Math.floor(size / n);   // 格宽取整保证边缘清晰
  const off = Math.floor((size - cell * n) / 2);   // 同样取整，否则半像素偏移把上面取整的意义抵消掉
  ctx.fillStyle = POSTER.qr;
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) ctx.fillRect(x + off + c * cell, y + off + r * cell, cell, cell);
    }
  }
  return true;
}

// 分享海报（极简）：大留白宣纸 + 细界栏 + 标题出处 + 二维码，750×1000，不落标识与网址
export function makePoster(p) {
  const W = 750, H = 1000;
  const { cv, ctx } = hiCanvas(W, H);
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = p.T || ((s) => s);

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = POSTER.paper;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = POSTER.ruleSoft;
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 标题（最多两行）与出处，居中大留白
  ctx.textAlign = 'center';
  ctx.fillStyle = POSTER.ink;
  ctx.font = `600 46px ${SERIF}`;
  const titleLines = wrapLines(ctx, T(p.title), W - 200, 2);
  let y = titleLines.length > 1 ? 388 : 420;
  for (const ln of titleLines) { ctx.fillText(ln, W / 2, y); y += 70; }
  ctx.fillStyle = POSTER.ink3;
  ctx.font = `26px ${SERIF}`;
  ctx.fillText(T(p.source || p.sub), W / 2, y + 14);

  // 底部：裸二维码居中 + 品牌小字（不落网址）
  const qsize = 150;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, H - 322, qsize)) {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T(p.cta || '扫二维码 听经闻法'), W / 2, H - 116);
  } else {
    // 二维码库未就绪：退回品牌小字
    ctx.fillStyle = POSTER.gold;
    ctx.font = `24px ${SERIF}`;
    ctx.fillText(T('佛 乐 · 净 土 法 音'), W / 2, H - 150);
  }
  return cv;
}

// 圆角矩形路径（canvas 兼容旧 Safari，不依赖 ctx.roundRect）
function rrPath(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// 直播分享海报：手绘一张与直播莲台同款式的「播放器卡片」——
// 直播中标记 + 当下系列/集名 + 实时进度与已播时长 + 日期 +（有人时）在线人数 + 二维码
export function makeLivePoster(p) {
  const W = 750, H = 1040;
  const { cv, ctx } = hiCanvas(W, H);
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = p.T || ((s) => s);
  const lv = p.live;

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = POSTER.paper;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = POSTER.ruleSoft;
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 播放器卡片（整块画进海报：胶囊 + 系列集名 + 进度 + 大播放钮 + 在线 + 日期）
  const cx = 64, cy = 112, cw = W - 128, ch = 606;
  rrPath(ctx, cx, cy, cw, ch, 26);
  ctx.fillStyle = POSTER.card;
  ctx.fill();
  ctx.strokeStyle = POSTER.cardLine;
  ctx.stroke();

  // 「直播中」胶囊（朱砂点 + 时段名）
  ctx.font = `24px ${SERIF}`;
  const chipText = T(lv && lv.block ? `直播中 · ${lv.block}` : '直播中');
  const tw = ctx.measureText(chipText).width;
  const pw = tw + 64, px = W / 2 - pw / 2, py = cy + 38;
  rrPath(ctx, px, py, pw, 44, 22);
  ctx.fillStyle = POSTER.zhushaWash;
  ctx.fill();
  ctx.strokeStyle = POSTER.zhushaSoft;
  ctx.stroke();
  ctx.fillStyle = POSTER.zhusha;
  ctx.beginPath();
  ctx.arc(px + 24, py + 22, 5, 0, Math.PI * 2);
  ctx.fill();
  ctx.textAlign = 'left';
  ctx.fillText(chipText, px + 40, py + 31);

  // 系列名（大字，最多两行）与集名
  ctx.textAlign = 'center';
  ctx.fillStyle = POSTER.ink;
  ctx.font = `600 40px ${SERIF}`;
  const titleLines = lv ? wrapLines(ctx, T(`《${lv.series}》`), cw - 110, 2) : [T('二十四时 · 佛号讲经不断')];
  let ty = titleLines.length > 1 ? cy + 146 : cy + 160;
  for (const ln of titleLines) { ctx.fillText(ln, W / 2, ty); ty += 54; }
  if (lv) {
    ctx.fillStyle = POSTER.ink2;
    ctx.font = `26px ${SERIF}`;
    ctx.fillText(T(lv.ep), W / 2, cy + 236);
  }

  // 实时进度条 + 已播/总长
  if (lv && lv.dur > 0) {
    const bx = cx + 82, bw = cw - 164, by = cy + 286;
    rrPath(ctx, bx, by, bw, 6, 3);
    ctx.fillStyle = POSTER.track;
    ctx.fill();
    const frac = Math.min(1, lv.elapsed / lv.dur);
    if (frac > 0.01) {
      rrPath(ctx, bx, by, Math.max(8, bw * frac), 6, 3);
      ctx.fillStyle = POSTER.zhusha;
      ctx.fill();
    }
    ctx.fillStyle = POSTER.ink3;
    ctx.font = `22px ${SERIF}`;
    ctx.textAlign = 'left';
    ctx.fillText(fmtMMSS(lv.elapsed), bx, by + 38);
    ctx.textAlign = 'right';
    ctx.fillText(fmtMMSS(lv.dur), bx + bw, by + 38);
  }

  // 大播放钮（朱砂圆 + 双层光晕 + 内细白环 + 圆角播放三角，邀人同闻）
  const pcx = W / 2, pcy = cy + 416, pr = 56;
  ctx.beginPath(); ctx.arc(pcx, pcy, pr + 16, 0, Math.PI * 2);
  ctx.fillStyle = POSTER.halo1; ctx.fill();
  ctx.beginPath(); ctx.arc(pcx, pcy, pr + 8, 0, Math.PI * 2);
  ctx.fillStyle = POSTER.halo2; ctx.fill();
  ctx.beginPath(); ctx.arc(pcx, pcy, pr, 0, Math.PI * 2);
  ctx.fillStyle = POSTER.zhusha; ctx.fill();
  ctx.beginPath(); ctx.arc(pcx, pcy, pr - 7, 0, Math.PI * 2);
  ctx.strokeStyle = POSTER.ringInner; ctx.lineWidth = 1.5; ctx.stroke();
  // 白色播放三角（圆角、光学右移居中）
  ctx.save();
  ctx.fillStyle = POSTER.paperHi; ctx.strokeStyle = POSTER.paperHi;
  ctx.lineJoin = 'round'; ctx.lineWidth = 8;
  const tl = pcx - 10, tr = pcx + 24, th = 20;
  ctx.beginPath();
  ctx.moveTo(tl, pcy - th);
  ctx.lineTo(tl, pcy + th);
  ctx.lineTo(tr, pcy);
  ctx.closePath();
  ctx.fill(); ctx.stroke();
  ctx.restore();

  // （有人同闻时）真实在线人数 + 日期行
  const dp = bjParts(Date.now());
  ctx.textAlign = 'center';
  if (lv && lv.online > 0) {
    ctx.fillStyle = POSTER.zhusha;
    ctx.font = `23px ${SERIF}`;
    ctx.fillText(T(`${lv.online} 位同修在此同闻`), W / 2, cy + 522);
  }
  ctx.fillStyle = POSTER.ink3;
  ctx.font = `22px ${SERIF}`;
  ctx.fillText(
    T(`${dp.y}年${dp.mo}月${dp.d}日 · 周${WEEK[dp.day]} · 北京时间 ${String(dp.h).padStart(2, '0')}:${String(dp.mi).padStart(2, '0')}`),
    W / 2, cy + (lv && lv.online > 0 ? 560 : 540));

  // 底部：二维码 + 扫码同闻
  const qsize = 150, qy = cy + ch + 40;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, qy, qsize)) {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T(p.cta || '扫二维码 听经闻法'), W / 2, qy + qsize + 40);
  } else {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `24px ${SERIF}`;
    ctx.fillText(T('佛 乐 · 净 土 法 音'), W / 2, qy + 40);
  }
  return cv;
}

/* ── 念佛计数分享海报 ──
   把计数页那圈念珠原样画出来：一百零八颗，念到哪颗朱砂到哪颗，中间是今日的声数。
   计数页上那道环是一条连续的弧，这里改画成一百零八颗珠 —— 环在屏上会走动，
   动态里读得出进度；一张静止的图没有动作可倚，得让「一百零八」这件事自己看得见。

   这张图不是用来晒数目的。站内「只报数目、不设排名」那条规矩在图上照旧算数：
   佛号最大，念珠次之，累计与连续退作一行小字。末了一句「若有见闻者，悉发菩提心」
   出自站内那首回向偈，说的正是看见这张图的人 —— 转发这一下即是回向，不是晒功课。 */
export function makeCountPoster(p) {
  const W = 750, H = 1080;
  const { cv, ctx } = hiCanvas(W, H);
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = p.T || ((s) => s);
  const num = (n) => Number(n || 0).toLocaleString();
  const today = Math.max(0, Number(p.today) || 0);

  // 素宣纸底 + 一道极细界栏（与另两版海报同源）
  ctx.fillStyle = POSTER.paper;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = POSTER.ruleSoft;
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);
  ctx.textAlign = 'center';

  // 佛号：站内这六个字是疏排的，挤作一团便失了庄重。
  // canvas 无 letter-spacing（新版虽有 ctx.letterSpacing，Safari 尚不通用），故逐字落笔。
  // 自定义功课名最长十二字，排不下就一路收字号，不许出界。
  const name = Array.from(T(p.name || '南无阿弥陀佛'));
  let ns = 44, gap = 16;
  const nameW = () => name.reduce((a, c) => a + ctx.measureText(c).width, 0) + gap * (name.length - 1);
  ctx.font = `600 ${ns}px ${SERIF}`;
  while (ns > 22 && nameW() > W - 190) {
    ns -= 2; gap = Math.max(5, gap - 1);
    ctx.font = `600 ${ns}px ${SERIF}`;
  }
  ctx.fillStyle = POSTER.ink;
  let nx = W / 2 - nameW() / 2;
  for (const c of name) {
    const cw = ctx.measureText(c).width;
    ctx.fillText(c, nx + cw / 2, 150);   // textAlign 是 center，故按字宽中点落笔
    nx += cw + gap;
  }

  // 念珠：一百零八颗围成一圈，自顶端顺时针上珠。
  // 定了课就照定课的完成度点亮（默认定课正是 108，与一串同数）；
  // 未定课则按本串算 —— 满串那一刻整圈朱砂，恰是站内「满一串」的那一响。
  const BEADS = 108, cx = W / 2, cy = 414, R = 142;
  const done = p.goal > 0 && today >= p.goal;
  const frac = p.goal > 0
    ? Math.min(1, today / p.goal)
    : (today % BEADS || (today > 0 ? BEADS : 0)) / BEADS;
  const lit = Math.round(frac * BEADS);
  for (let i = 0; i < BEADS; i++) {
    const a = -Math.PI / 2 + (i / BEADS) * Math.PI * 2;
    const on = i < lit;
    ctx.beginPath();
    ctx.arc(cx + Math.cos(a) * R, cy + Math.sin(a) * R, on ? 3.7 : 2.5, 0, Math.PI * 2);
    ctx.fillStyle = on ? POSTER.zhusha : POSTER.track;
    ctx.fill();
  }

  // 珠心：今日声数。数目长了就收字号，不许压到珠上
  const tt = num(today);
  let ts = 86;
  ctx.font = `600 ${ts}px ${SERIF}`;
  while (ts > 40 && ctx.measureText(tt).width > R * 1.5) { ts -= 4; ctx.font = `600 ${ts}px ${SERIF}`; }
  ctx.fillStyle = POSTER.ink;
  ctx.fillText(tt, cx, cy + 10);
  ctx.fillStyle = POSTER.ink3;
  ctx.font = `23px ${SERIF}`;
  ctx.fillText(T('今日 · 声'), cx, cy + 56);

  // 定课圆满：全图唯一一处朱砂小字，圆满了才有
  if (done) {
    ctx.fillStyle = POSTER.zhusha;
    ctx.font = `23px ${SERIF}`;
    ctx.fillText(T('今日定课圆满'), cx, 600);
  }

  // 一道短金线收住上半。以下日期与累计两行贴作一组（行距 34），
  // 与偈句之间空出一大档 —— 前者是这一天的实况，后者是说给看图的人听的，
  // 均分间距会把两件事读成一串流水账。
  ctx.strokeStyle = POSTER.rule;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(cx - 100, 636.5);
  ctx.lineTo(cx + 100, 636.5);
  ctx.stroke();

  const dp = bjParts(Date.now());
  ctx.fillStyle = POSTER.ink3;
  ctx.font = `22px ${SERIF}`;
  ctx.fillText(T(`${dp.y}年${dp.mo}月${dp.d}日 · 周${WEEK[dp.day]}`), cx, 678);
  const stat = [];
  if (p.total > 0) stat.push(T(`累计 ${num(p.total)} 声`));
  if (p.streak > 0) stat.push(T(`连续 ${p.streak} 日`));
  if (stat.length) ctx.fillText(stat.join(' · '), cx, 712);

  // 回向偈的第三句：说的正是看见这张图的人
  ctx.fillStyle = POSTER.ink2;
  ctx.font = `27px ${SERIF}`;
  ctx.fillText(T('若有见闻者，悉发菩提心'), cx, 776);

  // 底部：裸二维码 + 一行小字（不落标识与网址，与另两版一致）
  const qsize = 140, qy = 818;
  if (drawQR(ctx, p.url, cx - qsize / 2, qy, qsize)) {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T(p.cta || '扫二维码 同念佛号'), cx, qy + qsize + 40);
  } else {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `24px ${SERIF}`;
    ctx.fillText(T('佛 乐 · 净 土 法 音'), cx, qy + 40);
  }
  return cv;
}

// 选文截到上限：超长时收在最近的句读处，避免拦腰截断
export function trimQuote(text, max) {
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  let end = -1;
  for (const m of cut.matchAll(/[。！？；：」』]/g)) end = m.index;
  return end > max * 0.5 ? cut.slice(0, end + 1) : cut.slice(0, max - 1) + '…';
}

// 分享法布施长图：宽 750，高度随内容伸缩。纯内容排版（不落标识）：正文分段 + 出处 + 二维码
export function makeQuotePoster(p) {
  const W = 750;
  const SERIF = '"Noto Serif SC", "Songti SC", "STSong", serif';
  const T = p.T || ((s) => s);
  const bodyFont = `31px ${SERIF}`;
  const lineH = 58, paraGap = 30, bodyX = 85, bodyW = W - 170;

  // 先离屏排版量高，再按内容高度生成正式画布（canvas 改尺寸会清空，需两步）
  const mc = document.createElement('canvas').getContext('2d');
  mc.font = bodyFont;
  // 正文高度必须封顶。所选文字最多 800 字，但若是偈颂/对答那样的短句，
  // 80 个段落就能排出 7000px 的正文 —— 画布会撞上 iOS 的面积上限画成一张白图，
  // 就算没撞上，一张七千像素高的长图在聊天窗里也没法读。逐段累计，超预算即截断。
  const MAX_BODY_H = 2850;
  const paras = [];
  let bodyH = 0;
  const rawParas = T(p.quote).split('\n').map((x) => x.trim()).filter(Boolean);
  let truncated = false;
  for (let i = 0; i < rawParas.length; i++) {
    const gap = paras.length ? paraGap : 0;
    const room = MAX_BODY_H - bodyH - gap;
    if (room < lineH) { truncated = true; break; }         // 恰好卡在段落边界
    const lines = wrapLines(mc, rawParas[i], bodyW, 99);
    const fit = Math.floor(room / lineH);
    if (lines.length > fit) {                              // 段落中途截断
      lines.length = fit;
      lines[fit - 1] = ellipsize(mc, lines[fit - 1], bodyW);
      paras.push(lines); bodyH += gap + fit * lineH;
      truncated = true; break;
    }
    paras.push(lines); bodyH += gap + lines.length * lineH;
    if (i < rawParas.length - 1 && MAX_BODY_H - bodyH - paraGap < lineH) { truncated = true; break; }
  }
  // 断在段落边界时上面那支不会补省略号，读者会以为引文本来就到此为止。
  // 统一在末行补一个，让「还有下文」这件事看得见。
  if (truncated && paras.length) {
    const last = paras[paras.length - 1];
    if (!last[last.length - 1].endsWith('…')) {
      last[last.length - 1] = ellipsize(mc, last[last.length - 1], bodyW);
    }
  }
  const bodyY = 158;                  // 顶部大留白直接进正文，不设标识
  const srcY = bodyY + bodyH + 60;    // 出处行（右缩）
  const qrY = srcY + 64;              // 二维码
  const H = Math.max(860, qrY + 140 + 44 + 84);

  const { cv, ctx } = hiCanvas(W, H);

  // 素宣纸底 + 一道极细界栏
  ctx.fillStyle = POSTER.paper;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = POSTER.ruleSoft;
  ctx.lineWidth = 1;
  ctx.strokeRect(32.5, 32.5, W - 65, H - 65);

  // 正文：左起、按原文分段，行距疏朗贴近阅读器排版
  ctx.textAlign = 'left';
  ctx.fillStyle = POSTER.ink;
  ctx.font = bodyFont;
  let y = bodyY;
  for (const lines of paras) {
    for (const ln of lines) { ctx.fillText(ln, bodyX, y); y += lineH; }
    y += paraGap;
  }

  // 出处：右缩排，上方一道细金线呼应正文收束
  ctx.strokeStyle = POSTER.rule;
  ctx.beginPath();
  ctx.moveTo(W - bodyX - 120, srcY - 34);
  ctx.lineTo(W - bodyX, srcY - 34);
  ctx.stroke();
  ctx.textAlign = 'right';
  ctx.fillStyle = POSTER.ink3;
  ctx.font = `24px ${SERIF}`;
  const src = wrapLines(ctx, T(p.srcLine || `—— ${p.source || p.sub} · ${p.title}`), bodyW, 1)[0] || '';
  ctx.fillText(src, W - bodyX, srcY);

  // 底部：二维码 + 「扫码查询原文出处」
  ctx.textAlign = 'center';
  const qsize = 140;
  if (drawQR(ctx, p.url, W / 2 - qsize / 2, qrY, qsize)) {
    ctx.fillStyle = POSTER.gold;
    ctx.font = `22px ${SERIF}`;
    ctx.fillText(T('扫码查询原文出处'), W / 2, qrY + qsize + 44);
  }
  return cv;
}

// 海报统一出口：填预览图并按设备能力显示「分享至社交软件」
// 预览画布与在途生成序号：只有海报用得着，故随模块一起走
let posterCv = null;
let posterUrl = null;   // 预览用 objectURL，关闭海报即释放
let posterSeq = 0;      // 生成序号：异步编码回来时用它判断自己是否已被取代

export function showPoster(cv) {
  posterCv = cv;
  const seq = ++posterSeq;          // 本次生成的序号，用来作废在途的旧回调
  let canShare = false;
  try {
    canShare = !!(navigator.canShare
      && navigator.canShare({ files: [new File([''], 'x.png', { type: 'image/png' })] }));
  } catch { /* 不支持 files 分享 */ }
  $('#posterShare').hidden = !canShare;
  $('#posterOverlay').hidden = false;
  // 预览走 blob 而非 dataURL：高清画布下 PNG 有数 MB，转成 base64 字符串
  // 还要再涨三分之一，低端机上足以卡一下。
  // toBlob 是异步编码：连着生成两张时，第一张的回调可能晚于第二张回来，
  // 既会把旧图盖到新预览上，创建的 objectURL 也再没人释放。故以 seq 作废旧回调。
  revokePoster();
  cv.toBlob((blob) => {
    if (seq !== posterSeq) return;                     // 已被更新的一次生成取代
    if (!blob) {                                       // 编码失败：收掉空壳浮层，别让人对着白板
      $('#posterOverlay').hidden = true;
      toast('海报生成失败 · 请缩短所选文字再试');
      return;
    }
    posterUrl = URL.createObjectURL(blob);
    $('#posterImg').src = posterUrl;
  }, 'image/png');
}
export function revokePoster() {
  if (posterUrl) { URL.revokeObjectURL(posterUrl); posterUrl = null; }
}

/** 关闭预览：作废在途回调并断开画布引用。
    不置零宽高 —— 保存正在异步编码时会存出空白图。 */
export function resetPoster() {
  posterSeq++;
  posterCv = null;
}

/** 把当前预览画布编码成 blob 交给回调；没有画布则不调用。 */
export function posterToBlob(cb) {
  if (!posterCv) return;
  posterCv.toBlob(cb, 'image/png');
}

// 样式拼装：把 public/css/ 下按板块分开的源文件，在边缘拼成单个 /css/all.css。
//
// 拆成 12 个文件是为了好维护，但直接并列 12 个 <link> 要付两笔代价：
// 各自 Brotli 压缩丢掉了跨文件的重复模式（实测 25.1 KB → 34.6 KB，多传 9.4 KB），
// 而 CSS 是渲染阻塞资源，慢网下这些字节直接变成白屏时间（实测 1.6 Mbps 下 FCP +324ms）。
// 本站受众多在网络条件一般的地方，这笔账划不来。
//
// 这里在边缘拼接：源码仍是分开的，传输仍是一整份，且不引入构建步骤。
// 顺序即层叠顺序，ORDER 不可随意调换。

const ORDER = [
  'base',     // 变量、主题色、四时氛围、全局基础
  'layout',   // 顶栏、品牌标志、栅格布局、板块标题
  'home',     // 首页各卡
  'listen',   // 直播视图、节目单、系列卡
  'player',   // 统一听经播放器
  'count',    // 念佛计数与静念
  'ask',      // 问道对话
  'reader',   // 通用链接按钮、阅读器
  'chat',     // 共修群、改法名
  'chrome',   // 底部导航、页脚、搜索等零件
  'sheets',   // 分享抽屉与海报、我的页
  'motion',   // 交互动效、无障碍
];

// 同一 isolate 内复用拼装结果；部署会重建 isolate，自然失效
let cached = null;

async function build(env, origin) {
  if (cached) return cached;
  const parts = await Promise.all(ORDER.map(async (n) => {
    const r = await env.ASSETS.fetch(new Request(`${origin}/css/${n}.css`));
    if (!r.ok) throw new Error(`css/${n}.css ${r.status}`);
    return r.text();
  }));
  const body = parts.join('\n');
  // ETag 取内容哈希：样式没变时回 304，省掉整份重传
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(body));
  const etag = '"' + [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16) + '"';
  cached = { body, etag };
  return cached;
}

export async function serveCss(request, env, origin) {
  let out;
  try {
    out = await build(env, origin);
  } catch {
    return new Response('/* 样式拼装失败 */', { status: 502, headers: { 'Content-Type': 'text/css' } });
  }
  // 与壳代码同档：每次校验、部署即生效（sw.js 里 css/js 亦为网络优先，两处须一致）
  const headers = {
    'Content-Type': 'text/css; charset=utf-8',
    'Cache-Control': 'public, max-age=0, must-revalidate',
    ETag: out.etag,
  };
  if (request.headers.get('If-None-Match') === out.etag) {
    return new Response(null, { status: 304, headers });
  }
  return new Response(out.body, { headers });
}

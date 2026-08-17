// 服务端渲染：给文库正文、问答、系列目录发真实路径的可索引页面。
//
// 全站是 hash 路由的单页应用，`#read/...` 之后的部分服务器与爬虫都看不见 ——
// 241 篇讲记、969 条问答、24 个音频系列此前一律搜不到，只有首页一条 URL 进过索引。
// 这里在同一个 Worker 上给这些内容各开一条真实路径，把正文直接写进 HTML：
// 爬虫拿到全文，用户也省掉一次取文，首屏即是正文。
//
// 页面壳仍是 public/index.html，用 HTMLRewriter 流式改写 head 与正文槽位，
// 不另存一份模板，样式与脚本永远跟着主站走。

const SITE = 'https://foyue.org';
const SITE_NAME = '佛乐 · 净土法音';

/** 命中即走 SSR。这四段前缀与 public/ 下的实际目录（css/img/js/text）不重名。 */
export const SSR_PATH = /^\/(read|wkseries|qa|series)(\/|$)/;

const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

// 同一 isolate 内复用目录数据，免得每次请求都重取一遍 JSON。
// isolate 随部署重建，数据更新后自然失效。
const memo = {};
async function assetJson(env, origin, path) {
  if (memo[path]) return memo[path];
  const r = await env.ASSETS.fetch(new Request(origin + path));
  if (!r.ok) throw new Error(`${path} ${r.status}`);
  memo[path] = await r.json();
  return memo[path];
}

async function assetText(env, origin, path) {
  const r = await env.ASSETS.fetch(new Request(origin + path));
  if (!r.ok) return null;
  return r.text();
}

/** 正文首段取作摘要：搜索结果里显示的就是这段。 */
function summarize(text, n = 150) {
  const s = String(text || '').replace(/\s+/g, ' ').trim();
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** 纯文本 → 段落。与前端 renderReader 同一套规则：首行若与标题重复则略去。 */
function paragraphs(text, title) {
  const paras = text.split('\n').map((x) => x.trim()).filter(Boolean);
  const norm = (x) => x.replace(/^\d+[\s.、]*/, '').replace(/\s/g, '');
  const start = paras.length && norm(paras[0]) === norm(title) ? 1 : 0;
  return paras.slice(start);
}

/* ============ 各类页面的取数与成文 ============ */

async function pageRead(env, origin, sid, n) {
  const lib = await assetJson(env, origin, '/library.json');
  const s = lib.series.find((x) => x.id === sid);
  const chap = s?.chapters.find((c) => c.n === Number(n));
  if (!chap) return null;
  const text = await assetText(env, origin, '/text/' + chap.path);
  if (text == null) return null;

  const paras = paragraphs(text, chap.title);
  const idx = s.chapters.indexOf(chap);
  const prev = s.chapters[idx - 1];
  const next = s.chapters[idx + 1];
  return {
    title: `${chap.title} · ${s.title} · ${SITE_NAME}`,
    desc: summarize(paras.join(' ')),
    canonical: `${SITE}/read/${sid}/${chap.n}`,
    hash: `#read/${sid}/${chap.n}`,
    path: chap.path,
    // 注入阅读器正文槽。结构与 renderReader 生成的一致，SPA 接管后无缝。
    body: `<p class="reader-sub">${esc(s.title)}</p><h2>${esc(chap.title)}</h2>`
      + paras.map((x) => `<p>${esc(x)}</p>`).join(''),
    // 上一篇/下一篇给爬虫留出爬行路径，孤岛页面不易被收录
    links: [
      { rel: 'prev', href: prev ? `${SITE}/read/${sid}/${prev.n}` : null },
      { rel: 'next', href: next ? `${SITE}/read/${sid}/${next.n}` : null },
      { rel: 'up', href: `${SITE}/wkseries/${sid}` },
    ].filter((l) => l.href),
    jsonld: {
      '@context': 'https://schema.org',
      '@type': 'Article',
      headline: chap.title,
      articleSection: s.title,
      inLanguage: 'zh-Hans',
      isPartOf: { '@type': 'Book', name: s.title, url: `${SITE}/wkseries/${sid}` },
      publisher: { '@id': `${SITE}/#organization` },
      mainEntityOfPage: `${SITE}/read/${sid}/${chap.n}`,
      wordCount: chap.chars || undefined,
    },
  };
}

async function pageWkSeries(env, origin, sid) {
  const lib = await assetJson(env, origin, '/library.json');
  const s = lib.series.find((x) => x.id === sid);
  if (!s) return null;
  return {
    title: `${s.title} · 全 ${s.count} 篇 · ${SITE_NAME}`,
    desc: summarize(`《${s.title}》全 ${s.count} 篇：`
      + s.chapters.slice(0, 12).map((c) => c.title).join('、')),
    canonical: `${SITE}/wkseries/${sid}`,
    hash: `#wkseries/${sid}`,
    // 目录页正文即篇目清单，每篇一条真实链接供爬虫深入
    body: `<p class="reader-sub">净土讲记</p><h2>${esc(s.title)}</h2>`
      + `<p>全 ${s.count} 篇。</p><ol>`
      + s.chapters.map((c) =>
        `<li><a href="/read/${esc(sid)}/${c.n}">${esc(c.title)}</a></li>`).join('')
      + '</ol>',
    links: [],
    jsonld: {
      '@context': 'https://schema.org',
      '@type': 'Book',
      name: s.title,
      inLanguage: 'zh-Hans',
      numberOfPages: s.count,
      publisher: { '@id': `${SITE}/#organization` },
      hasPart: s.chapters.map((c) => ({
        '@type': 'Chapter', name: c.title, position: c.n, url: `${SITE}/read/${sid}/${c.n}`,
      })),
    },
  };
}

async function pageQa(env, origin, n) {
  const qa = await assetJson(env, origin, '/qa.json');
  const i = Number(n);
  const item = qa.items[i - 1];
  if (!item || !item.text) return null;
  const text = await assetText(env, origin, '/text/' + item.text);
  if (text == null) return null;
  const paras = paragraphs(text, item.title);

  return {
    title: `${item.title} · 学佛问答 · ${SITE_NAME}`,
    desc: summarize(paras.join(' ')),
    canonical: `${SITE}/qa/${i}`,
    hash: `#qa/${i}`,
    path: item.text,
    body: `<p class="reader-sub">学佛问答</p><h2>${esc(item.title)}</h2>`
      + paras.map((x) => `<p>${esc(x)}</p>`).join(''),
    links: [],
    // 问答天然是 QAPage：命中「某某怎么办」这类长尾搜索时可出富摘要
    jsonld: {
      '@context': 'https://schema.org',
      '@type': 'QAPage',
      inLanguage: 'zh-Hans',
      mainEntity: {
        '@type': 'Question',
        name: item.title,
        acceptedAnswer: { '@type': 'Answer', text: summarize(paras.join('\n'), 1200) },
      },
    },
  };
}

async function pageSeries(env, origin, sid, ep) {
  const cat = await assetJson(env, origin, '/catalog.json');
  const s = cat.series.find((x) => x.id === sid);
  if (!s) return null;
  // 带集号的深链（分享某一集）：落点保留集号，canonical 仍收敛到系列页 ——
  // 每集单独算一个页面的话，912 条内容雷同的 URL 会稀释系列页自身的权重
  const n = Number(ep);
  const epOk = Number.isInteger(n) && n >= 1 && n <= (s.episodes || []).length;
  const eps = s.episodes || [];
  const hours = Math.round((s.totalDur || 0) / 360) / 10;
  return {
    title: `${s.title} · ${s.count} 集 · ${SITE_NAME}`,
    desc: summarize(`《${s.title}》${s.cat}，共 ${s.count} 集`
      + (hours ? `约 ${hours} 小时` : '') + '：'
      + eps.slice(0, 10).map((e) => e.title).join('、')),
    canonical: `${SITE}/series/${sid}`,
    hash: epOk ? `#series/${sid}/${n}` : `#series/${sid}`,
    body: `<p class="reader-sub">${esc(s.cat || '讲经')}</p><h2>${esc(s.title)}</h2>`
      + `<p>共 ${s.count} 集${hours ? `，约 ${hours} 小时` : ''}。</p><ol>`
      + eps.map((e, i) =>
        `<li><a href="/series/${esc(sid)}/${i + 1}">${esc(e.title)}</a></li>`).join('')
      + '</ol>',
    links: [],
    jsonld: {
      '@context': 'https://schema.org',
      '@type': 'PodcastSeries',
      name: s.title,
      inLanguage: 'zh-Hans',
      numberOfEpisodes: s.count,
      publisher: { '@id': `${SITE}/#organization` },
    },
  };
}

/* ============ 分派与改写 ============ */

async function resolve(env, origin, pathname) {
  const seg = pathname.split('/').filter(Boolean).map(decodeURIComponent);
  const [kind, a, b] = seg;
  if (kind === 'read' && a && b) return pageRead(env, origin, a, b);
  if (kind === 'wkseries' && a) return pageWkSeries(env, origin, a);
  if (kind === 'qa' && a) return pageQa(env, origin, a);
  if (kind === 'series' && a) return pageSeries(env, origin, a, b);
  return null;
}

export async function serveSSR(request, env, url) {
  let page = null;
  try {
    page = await resolve(env, url.origin, url.pathname);
  } catch { /* 目录读取失败：退回普通壳，SPA 自己去取 */ }

  // 取页面壳。带上原请求头以沿用协商缓存，但路径固定指向首页。
  const shell = await env.ASSETS.fetch(new Request(url.origin + '/index.html'));
  if (!page) {
    // 内容不存在：给 404 状态但仍返回可用的应用壳，用户看到的是站内页而非裸错误
    return new Response(shell.body, {
      status: 404,
      headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
    });
  }

  const head = [
    ...page.links.map((l) => `<link rel="${l.rel}" href="${esc(l.href)}">`),
    `<script type="application/ld+json">${JSON.stringify(page.jsonld)}</script>`,
    // 落点与正文出处交给 SPA：它据此直接路由到位，并沿用已渲染的正文不再取一次
    `<script>window.__SSR=${JSON.stringify({ hash: page.hash, path: page.path || null })}</script>`,
  ].join('\n');

  const out = new HTMLRewriter()
    .on('title', { element(e) { e.setInnerContent(page.title); } })
    .on('meta[name="description"]', { element(e) { e.setAttribute('content', page.desc); } })
    .on('link[rel="canonical"]', { element(e) { e.setAttribute('href', page.canonical); } })
    .on('meta[property="og:title"]', { element(e) { e.setAttribute('content', page.title); } })
    .on('meta[property="og:description"]', { element(e) { e.setAttribute('content', page.desc); } })
    .on('meta[property="og:url"]', { element(e) { e.setAttribute('content', page.canonical); } })
    .on('meta[property="og:type"]', { element(e) { e.setAttribute('content', 'article'); } })
    .on('meta[name="twitter:title"]', { element(e) { e.setAttribute('content', page.title); } })
    .on('meta[name="twitter:description"]', { element(e) { e.setAttribute('content', page.desc); } })
    .on('head', { element(e) { e.append(head, { html: true }); } })
    // 正文进阅读器槽位；body 上标出落点，样式与 SPA 路由后完全一致
    .on('#readerBody', { element(e) { e.setInnerContent(page.body, { html: true }); } })
    .on('body', { element(e) { e.setAttribute('data-view', 'reader'); e.setAttribute('data-ssr', '1'); } })
    .transform(shell);

  return new Response(out.body, {
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      // 内容是静态的讲记原文，改动只在重建文库时；边缘存久些，浏览器短些
      'Cache-Control': 'public, max-age=300, s-maxage=86400',
    },
  });
}

/* ============ sitemap ============ */

export async function serveSitemap(env, origin) {
  const [lib, qa, cat] = await Promise.all([
    assetJson(env, origin, '/library.json'),
    assetJson(env, origin, '/qa.json'),
    assetJson(env, origin, '/catalog.json'),
  ]);

  const urls = [{ loc: SITE + '/', pri: '1.0', freq: 'daily' }];
  for (const s of lib.series) {
    urls.push({ loc: `${SITE}/wkseries/${encodeURIComponent(s.id)}`, pri: '0.8', freq: 'monthly' });
    for (const c of s.chapters) {
      urls.push({ loc: `${SITE}/read/${encodeURIComponent(s.id)}/${c.n}`, pri: '0.7', freq: 'monthly' });
    }
  }
  for (let i = 1; i <= qa.items.length; i++) {
    if (qa.items[i - 1]?.text) urls.push({ loc: `${SITE}/qa/${i}`, pri: '0.6', freq: 'monthly' });
  }
  for (const s of cat.series) {
    urls.push({ loc: `${SITE}/series/${encodeURIComponent(s.id)}`, pri: '0.7', freq: 'monthly' });
  }

  const lastmod = String(lib.generatedAt || '').slice(0, 10) || undefined;
  const xml = '<?xml version="1.0" encoding="UTF-8"?>\n'
    + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
    + urls.map((u) => '  <url>\n'
      + `    <loc>${esc(u.loc)}</loc>\n`
      + (lastmod ? `    <lastmod>${lastmod}</lastmod>\n` : '')
      + `    <changefreq>${u.freq}</changefreq>\n`
      + `    <priority>${u.pri}</priority>\n`
      + '  </url>').join('\n')
    + '\n</urlset>\n';

  return new Response(xml, {
    headers: {
      'Content-Type': 'application/xml; charset=utf-8',
      'Cache-Control': 'public, max-age=3600, s-maxage=86400',
    },
  });
}

// 问道智能体 —— 入口与调度（设计书四·处理次序；2026-08-04 二修：独立站定位）
//
// guard 分级 → intent 规则路由 → L1 目录查表 → L0 定本词法直出（零成本，任何来源）
//   → 快取 → 生成层（一请求一额度：重排复核档＋L2 综述·句级闸）
//   → 降级链：定本近似候选摘要 → 拒答定句
// 铁律：能查表的绝不检索，能直出的绝不生成；降级链不依赖密钥与模型可用性。
// 知识底本＝文库全库：L2 绑定与主站同一 Vectorize 索引 foyue-wenku
// （8999 块＝241 篇讲记＋820 问答），L0 定本层是其上的确定性快路，非全部。

import DINGBEN from '../../data/dingben.json';
import CATALOG from '../../data/catalog-lite.json';
import { initDingben, matchDingben, directText, sourceOf, dingbenMeta, LEX_CAND } from './dingben.js';
import { initCatalog, catalogAnswer } from './catalog.js';
import { classify, REFUSE_INJECTION, CARE_APPEND } from './intent.js';
import { genGuard, cacheKeyOf } from './guard.js';
import { retrieve, streamCompose, composeFromCache, modelOf } from './compose.js';
import { frame, fixedSse, streamSse } from './sse.js';
import { PAGE } from './ui.js';

initDingben(DINGBEN);
initCatalog(CATALOG);

/** CORS：允许主站诸域（foyue.org 及其子域）与本地开发直连；其余同源不需 CORS 头 */
function corsOf(request) {
  const origin = request.headers.get('Origin');
  if (!origin) return {};
  let host = '';
  try { host = new URL(origin).hostname; } catch { return {}; }
  const allowed = host === 'foyue.org' || host.endsWith('.foyue.org')
    || host === 'localhost' || host === '127.0.0.1';
  if (!allowed) return {};
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type, x-ask-client',
    'Vary': 'Origin',
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = corsOf(request);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (url.pathname === '/' && request.method === 'GET') {
      return new Response(PAGE, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=3600' } });
    }
    if (url.pathname === '/v1/health') {
      return Response.json({ ok: true, dingben: dingbenMeta(), model: modelOf(env) }, { headers: cors });
    }
    if (url.pathname !== '/v1/ask') return new Response('Not Found', { status: 404 });
    if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
    return handleAsk(request, env, cors);
  },
};

async function handleAsk(request, env, cors) {
  const T0 = Date.now();
  const guard = genGuard(request, env);

  let q, history;
  try {
    const body = await request.json();
    q = String(body.q || '').trim().slice(0, 300);
    history = Array.isArray(body.history) ? body.history.slice(-6) : [];
  } catch { return new Response('Bad Request', { status: 400, headers: cors }); }
  if (q.length < 2) return new Response('问题太短', { status: 400, headers: cors });

  const requestId = crypto.randomUUID();
  const intent = classify(q);

  // ── 注入：拒答定句 ──
  if (intent === 'injection') {
    return fixedSse([
      frame('mode', { mode: 'refused', requestId, basis: { label: '范围之外' } }),
      frame('sources', []),
      frame('delta', { text: REFUSE_INJECTION }),
      frame('done', { evidenceStatus: 'refused', timing: { total: Date.now() - T0 } }),
    ], cors);
  }

  // ── L1 目录：查表直答（答不了返回 null 继续） ──
  const cat = catalogAnswer(q);
  if (cat) {
    return fixedSse([
      frame('mode', { mode: 'catalog', requestId, basis: { label: '文库目录（查表）' } }),
      frame('sources', []),
      frame('delta', { text: cat }),
      frame('done', { evidenceStatus: 'grounded', timing: { total: Date.now() - T0 } }),
    ], cors);
  }

  // ── L0 定本词法档：零成本，任何来源皆可直出 ──
  const m = await matchDingben(q, { canRerank: false, env });
  if (m.hit) return dingbenSse(m, intent, { requestId, T0, cors });

  // ── 关怀路：不入生成层，近似候选＋求助提示（不冷拒） ──
  if (intent === 'care') return degraded(q, m.lex, { requestId, T0, cors, append: CARE_APPEND, label: '相近答问' });

  // ── 快取（命中零成本，置于额度之前；带追问历史不快取） ──
  const ckey = history.length ? null : await cacheKeyOf(q, modelOf(env), dingbenMeta().builtAt || '');
  const cached = await composeFromCache({ env, guard, ckey, T0 });
  if (cached) return fixedSse(cached, cors);

  // ── 生成层（一请求一额度：重排复核档＋综述） ──
  if (await guard.take()) {
    // 重排复核档：词法中分者按语义复核，命中仍走原答直出
    const m2 = await matchDingben(q, { canRerank: true, env });
    if (m2.hit) return dingbenSse(m2, intent, { requestId, T0, cors });

    const passages = await retrieve(env, q);
    if (passages && passages.length) {
      const stream = await streamCompose({ question: q, history, passages, env, guard, ckey, T0, requestId });
      if (stream) return streamSse(stream, cors);
    }
    // 检索空／生成不可用 → 落降级链
  }

  return degraded(q, m.lex, { requestId, T0, cors });
}

/** 定本直出帧 */
function dingbenSse(m, intent, { requestId, T0, cors }) {
  return fixedSse([
    frame('mode', { mode: 'dingben', requestId, basis: { label: '大安法师亲答（原文照录）', via: m.via } }),
    frame('sources', [sourceOf(m.hit)]),
    frame('delta', { text: directText(m.hit) + (intent === 'care' ? CARE_APPEND : '') }),
    frame('done', { evidenceStatus: 'grounded', timing: { total: Date.now() - T0 } }),
  ], cors);
}

/** 降级链：定本近似候选摘要（真原文，grounded）；再不济如实说无 */
function degraded(q, lex, { requestId, T0, cors, append = '', label = '未能生成综述·相近答问' }) {
  const cands = (lex || []).filter((c) => c.score >= LEX_CAND).slice(0, 3);
  if (cands.length) {
    const list = cands.map((c, i) =>
      `${i + 1}.【${c.it.title}】${String(c.it.a).replace(/\s+/g, ' ').slice(0, 80)}……`).join('\n');
    return fixedSse([
      frame('mode', { mode: 'degraded', requestId, basis: { label } }),
      frame('sources', cands.map((c, i) => sourceOf(c.it, i + 1))),
      frame('delta', { text: `文库中与您所问相近的大安法师亲答有：\n\n${list}\n\n可点出处查阅原文全文。${append}` }),
      frame('done', { evidenceStatus: 'grounded', timing: { total: Date.now() - T0 } }),
    ], cors);
  }
  return fixedSse([
    frame('mode', { mode: 'refused', requestId, basis: { label: '文库未见' } }),
    frame('sources', []),
    frame('delta', { text: `文库中未找到与您所问相近的开示。可换个问法，或到「文库」页浏览 ${CATALOG.books.length} 部讲记与 ${CATALOG.qaCount} 条答问。${append}` }),
    frame('done', { evidenceStatus: 'refused', timing: { total: Date.now() - T0 } }),
  ], cors);
}

// 定本层 —— 820 条大安法师亲答的精确对位与原文直出（设计书 L0）
//
// 【铁律】能直出的绝不生成。命中即原答全文照录——法师风格 100% 保真、
// 天然有据（出处行在文末），零生成成本、零幻觉可能。
//
// 【两级匹配】
//   词法（lex.js 字二元组）：零外呼，公网直访也可用；阈值 LEX_HIT 由
//     eval/selfhit.mjs 离线标定（原问自命中 top1 与错对分数分布之间取隙）。
//   重排复核（bge-reranker-v2-m3）：可信路且有钥时，对词法 top10 复核语义
//     改述；RR_HIT 为保守缺省，联网标定属 M2（换模型必重标）。

import { makeIndex, topK } from './lex.js';

// 阈值（经 eval/selfhit.mjs 2026-08-04 标定）：
//   Dice 档：原问自命中 100%（819/819，含近重复条），自命中 p05=0.841；
//     剔除近重复后的真错对 Dice p99=0.348、max=0.593 → LEX_HIT=0.62 压过全部观测错对。
//   覆盖率档：治短问法被长条目稀释（lex.js topK 注）。0.92≈查询整体被某条原问包含
//     （实测 cov=1.0 者即原问逐字在条目中，如 321 条）；0.72-0.8 已见同题异问之错配，
//     故此档须 ≥0.92 方可直出。
//   重排档为保守缺省，M2 联网标定。
export const LEX_HIT = 0.62;   // Dice 即直出：接近原问的问法
export const COV_HIT = 0.92;   // 覆盖率即直出：查询近乎整体含于某条原问（≥8 二元组护栏在 lex.js）
export const LEX_CAND = 0.28;  // 进重排复核／降级候选的门槛（对混合分 score）
export const RR_HIT = 0.58;    // 重排即直出

/** 词法直出判据（单点定义，matchDingben 与评测共用） */
export const lexFire = (c) => !!c && (c.dice >= LEX_HIT || c.cov >= COV_HIT);
const SF_BASE = 'https://api.siliconflow.cn/v1';

let ITEMS = null, IDX = null, META = null;

/** 注入数据（worker 从打包 JSON、评测从 fs 读后传入——模块本身不依赖运行环境） */
export function initDingben(data) {
  ITEMS = data.items;
  META = data.meta || {};
  // 匹配文本＝标题＋问句（开示体无问句者标题独任；标题多为编辑拟题，与问句互补）
  IDX = makeIndex(ITEMS, (it) => `${it.title} ${it.q}`);
}

export const dingbenMeta = () => META || {};

/** 词法 top-k */
export function lexTop(q, k = 30) {
  if (!IDX) throw new Error('dingben 未初始化');
  return topK(IDX, q, k);
}

/**
 * 重排复核：对词法候选按「标题＋问句」重排。
 * @returns [{it, rr}] 降序；无钥／上游失败返回 null（上层按词法结果继续，不因复核挂掉断服务）
 */
export async function rerankPick(q, cands, env) {
  const key = env && env.SILICONFLOW_API_KEY;
  if (!key || !cands.length) return null;
  try {
    const res = await fetch(`${SF_BASE}/rerank`, {
      method: 'POST',
      headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model: 'BAAI/bge-reranker-v2-m3',
        query: String(q).slice(0, 300),
        documents: cands.map((c) => `${c.it.title}\n${c.it.q}`.slice(0, 400)),
      }),
    });
    if (!res.ok) return null;
    const j = await res.json();
    if (!Array.isArray(j.results)) return null;
    return j.results
      .map((r) => ({ it: cands[r.index].it, rr: Number(r.relevance_score) || 0 }))
      .sort((a, b) => b.rr - a.rr);
  } catch { return null; }
}

/** 命中判定：词法过档直出；词法中分且可复核者按重排定；皆不及则不命中 */
export async function matchDingben(q, { canRerank, env }) {
  const lex = lexTop(q, 30);
  const best = lex[0];
  if (lexFire(best)) return { hit: best.it, via: 'lex', score: best.score, lex };
  if (canRerank && best && best.score >= LEX_CAND) {
    const rr = await rerankPick(q, lex.slice(0, 10), env);
    if (rr && rr[0] && rr[0].rr >= RR_HIT) return { hit: rr[0].it, via: 'rerank', score: rr[0].rr, lex };
  }
  return { hit: null, lex };
}

/** 直出文本：转述框定语＋原问题＋原答全文＋出处（归属清楚，见设计书「让原话多说话」） */
export function directText(it) {
  const head = `文库中有大安法师对此问的亲答，原文照录——\n\n【${it.title}】\n\n`;
  const tail = it.src ? `\n\n—— ${it.src}` : '\n\n—— 大安法师答问（文库辑录）';
  return head + it.a + tail;
}

/** 出处卡（与现行前端 sources 事件同形） */
export function sourceOf(it, n = 1) {
  return {
    n, title: it.title, series: '大安法师答问', path: it.path, kind: 'qa',
    x: String(it.a).replace(/\s+/g, ' ').trim().slice(0, 160),
  };
}

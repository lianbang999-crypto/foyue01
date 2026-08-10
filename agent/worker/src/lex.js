// 字二元组词法检索 —— 定本层预筛与降级链共用
//
// 【为何不用向量】820 条短问句是小语料强结构场景：字二元组 Dice 系数
// 零外呼、零冷启、公网直访也能跑（guard 分级要求降级链不依赖密钥）。
// 语义改述的召回缺口由可信路的 bge-reranker 复核补（dingben.js）。

/** 问句归一：剥行首称名礼语、剥请安套语、去标点空白 */
export function normQ(s) {
  let t = String(s || '');
  t = t.replace(/^(南无阿弥陀佛|阿弥陀佛)[!！。，,、\s]*/, '');          // 行首称名是礼节，非问题内容
  t = t.replace(/(请问法师|请师父慈悲开示|请师父开示|请法师开示|请慈悲开示|请开示|请问|顶礼法师|顶礼|感恩师父|感恩法师)/g, '');
  t = t.replace(/[^\p{Script=Han}\p{L}\p{N}]+/gu, '');                    // 只留文字与数字
  return t;
}

/** 字二元组集合（不足二字退回单字） */
export function grams(s) {
  const t = String(s || '');
  const set = new Set();
  if (t.length < 2) { if (t) set.add(t); return set; }
  for (let i = 0; i + 2 <= t.length; i++) set.add(t.slice(i, i + 2));
  return set;
}

/** Dice 系数 */
export function dice(a, b) {
  if (!a.size || !b.size) return 0;
  let hit = 0;
  const [small, big] = a.size <= b.size ? [a, b] : [b, a];
  for (const g of small) if (big.has(g)) hit++;
  return (2 * hit) / (a.size + b.size);
}

/** 建索引：items 逐条以 textOf(it) 归一取二元组 */
export function makeIndex(items, textOf) {
  return items.map((it) => ({ it, set: grams(normQ(textOf(it))) }));
}

// 覆盖率分支的最小查询字二元组数：过短的查询（如「净土宗」3 个二元组）
// 落在众多条目里覆盖率都会饱和到 1.0，无鉴别力，只许走 Dice
const COV_MIN_GRAMS = 8;

/**
 * 取 top-k：[{it, score, dice, cov}] 降序。
 * score = max(Dice, 覆盖率×0.9)——Dice 治「query≈条目全文」的对称近似，
 * 覆盖率治「短问法对长条目被稀释」（2026-08-04 实测：「临终的时候佛什么时候
 * 来接引我们」对 001 条 Dice 仅 0.2x，覆盖率却高——同题短问法本应进候选）。
 */
export function topK(index, q, k = 30) {
  const qs = grams(normQ(q));
  const scored = index.map((e) => {
    let hit = 0;
    for (const g of qs) if (e.set.has(g)) hit++;
    const d = qs.size && e.set.size ? (2 * hit) / (qs.size + e.set.size) : 0;
    const cov = qs.size >= COV_MIN_GRAMS ? hit / qs.size : 0;
    return { it: e.it, score: Math.max(d, cov * 0.9), dice: d, cov };
  });
  scored.sort((x, y) => y.score - x.score);
  return scored.slice(0, k);
}

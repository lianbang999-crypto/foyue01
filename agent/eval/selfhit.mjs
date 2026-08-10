// 定本层自命中评测（离线，零外呼）—— 词法阈值标定依据
//
// 两组：
//   A 原问自命中：以每条的完整问句为查询，top1 应为其自身（开示体无问句者跳过）
//   B 标题作问：以标题为查询（模拟用户短问法），top1 应为其自身
// 另出「错对分数分布」：非自身的 top1 分数分位——LEX_HIT 应压在
// 「原问自命中分数低分位」与「错对分数高分位」之间的空隙里。
//
// 用法：node agent/eval/selfhit.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { initDingben, lexTop, LEX_HIT, LEX_CAND, lexFire } from '../worker/src/dingben.js';
import { grams, dice, normQ } from '../worker/src/lex.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const data = JSON.parse(readFileSync(join(HERE, '..', 'data', 'dingben.json'), 'utf8'));
initDingben(data);

// 【语料实情】820 条中存在近重复条目（同一答问辑录两次，问法小异）。
// 对直出而言命中重复条仍是正确答案，故命中判定＝自身或答文近重复（答文二元组 Dice ≥ 0.55）。
const ansGrams = new Map(data.items.map((it) => [it.path, grams(normQ(it.a).slice(0, 600))]));
const isDup = (a, b) => a.path === b.path || dice(ansGrams.get(a.path), ansGrams.get(b.path)) >= 0.55;

const pct = (arr, p) => arr.length ? arr[Math.min(arr.length - 1, Math.floor(arr.length * p))] : NaN;
const fmt = (x) => Number.isFinite(x) ? x.toFixed(3) : '—';

function run(label, queryOf) {
  const selfScores = [], missTop = [];
  let hit = 0, tried = 0;
  for (const it of data.items) {
    const q = queryOf(it);
    if (!q || q.length < 4) continue;
    tried++;
    const top = lexTop(q, 3);
    if (top[0] && isDup(top[0].it, it)) { hit++; selfScores.push(top[0].score); }
    else if (top[0]) missTop.push({ q: q.slice(0, 30), got: top[0].it.title.slice(0, 20), score: top[0].score });
  }
  selfScores.sort((a, b) => a - b);
  console.log(`\n[${label}] 自命中 ${hit}/${tried} = ${(hit / tried * 100).toFixed(1)}%`);
  console.log(`  自命中分数分位  p05 ${fmt(pct(selfScores, 0.05))}  p10 ${fmt(pct(selfScores, 0.10))}  p50 ${fmt(pct(selfScores, 0.50))}`);
  if (missTop.length) {
    console.log(`  未自命中样例（前5）：`);
    missTop.slice(0, 5).forEach((m) => console.log(`    「${m.q}…」→「${m.got}…」(${m.score.toFixed(3)})`));
  }
  return { selfScores, missTop };
}

// A 原问自命中
const A = run('A 原问作查询', (it) => it.q);
// B 标题作问
const B = run('B 标题作查询', (it) => it.title);

// C 错对分布：以每条问句查其余，取「非自身且非近重复」的最高分——直出阈值须压过这条线
const wrong = [];
const wrongHigh = [];
for (const it of data.items.filter((x) => x.q && x.q.length >= 4).slice(0, 400)) {   // 抽 400 条足够定分布
  const top = lexTop(it.q, 5);
  const other = top.find((c) => !isDup(c.it, it));
  if (other) { wrong.push(other.score); if (other.score >= 0.55) wrongHigh.push({ q: it.q.slice(0, 26), got: other.it.title.slice(0, 22), score: other.score }); }
}
wrong.sort((a, b) => a - b);
console.log(`\n[C 错对（剔除近重复后的最高他项）分数分布] n=${wrong.length}`);
console.log(`  p50 ${fmt(pct(wrong, 0.5))}  p90 ${fmt(pct(wrong, 0.9))}  p99 ${fmt(pct(wrong, 0.99))}  max ${fmt(wrong[wrong.length - 1])}`);
if (wrongHigh.length) {
  console.log(`  高分错对样例（≥0.55，前6——须人工判：是真错对还是漏判的重复）：`);
  wrongHigh.sort((a, b) => b.score - a.score).slice(0, 6).forEach((m) => console.log(`    「${m.q}…」→「${m.got}…」(${m.score.toFixed(3)})`));
}

console.log(`\n[现行阈值] LEX_HIT=${LEX_HIT}(Dice直出)  COV_HIT=0.92(覆盖率直出)  LEX_CAND=${LEX_CAND}`);

// D 直出误发率：全量 819 原问逐条按 lexFire 判据发射，top1 非自身且非近重复者＝误发。
// 这是直出档的硬底线——误发即把错答当法师亲答端给用户，一条也不许。
let fired = 0, misfire = 0;
const misfires = [];
for (const it of data.items.filter((x) => x.q && x.q.length >= 4)) {
  const top = lexTop(it.q, 1)[0];
  if (!lexFire(top)) continue;
  fired++;
  if (!isDup(top.it, it)) { misfire++; misfires.push({ q: it.q.slice(0, 26), got: top.it.title.slice(0, 22) }); }
}
console.log(`\n[D 直出误发] 发射 ${fired} 次，误发 ${misfire} 次`);
misfires.slice(0, 5).forEach((m) => console.log(`    「${m.q}…」→「${m.got}…」`));

// 硬校验：原问自命中 ≥99%（保底能力）＋直出零误发
const rateA = A.selfScores.length && (A.selfScores.length / data.items.filter((x) => x.q && x.q.length >= 4).length);
if (rateA < 0.99) { console.error('✗ 原问自命中率不足 99%'); process.exit(1); }
if (misfire > 0) { console.error('✗ 直出有误发'); process.exit(1); }
console.log('✓ 原问自命中达标，直出零误发');

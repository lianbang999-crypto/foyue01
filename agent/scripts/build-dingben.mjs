// 定本层数据构建 —— 解析 public/text/qa/*.txt 820 条为 agent/data/dingben.json
//
// 【格式变体（2026-08-04 风格研究实测）】
//   · 问句前缀有「问：／居士：／信众：」三式；
//   · 11 条为「大安法师：」开示体（无「答」字、无问句）——问文置空，匹配时用标题；
//   · 出处行有「----」「——」两式，19 条无出处行；
//   · 标题行带三位编号前缀（如「001临命终时…」），剥号存 id。
//
// 产出：
//   agent/data/dingben.json      [{id,title,q,a,src,path}]
//   agent/data/catalog-lite.json 目录层查表数据（文库 38 部＋音频 22 部＋总数）
//
// 用法：node agent/scripts/build-dingben.mjs   （在仓根运行）

import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const QA_DIR = join(ROOT, 'public', 'text', 'qa');
const OUT_DIR = join(ROOT, 'agent', 'data');
mkdirSync(OUT_DIR, { recursive: true });

const files = readdirSync(QA_DIR).filter((f) => f.endsWith('.txt')).sort();
const items = [];
const warns = [];

// 无标题行条目（宏格式 B，首行直接「问：…」）的标题取自 library.json 问答清单
const libQa = JSON.parse(readFileSync(join(ROOT, 'public', 'library.json'), 'utf8')).qa;
const titleByPath = new Map(libQa.map((it) => [it.path, it.title]));

for (const fn of files) {
  const raw = readFileSync(join(QA_DIR, fn), 'utf8').trim();
  const lines = raw.split('\n');
  const first = lines[0].trim();
  // 宏格式两式：A＝首行「NNN标题」；B＝无标题行，首行即「问：…」（题取 library.json）
  const idm = first.match(/^(\d{3})\s*/);
  const id = fn.replace('.txt', '');
  let title, body;
  if (idm) {
    title = first.replace(/^\d{3}\s*/, '').trim();
    body = lines.slice(1).join('\n');
  } else {
    title = titleByPath.get(`qa/${fn}`) || '';
    body = raw;
    if (!title) warns.push(`${fn} 宏格式B且 library.json 无题`);
  }

  // 问句＝标题行与「大安法师(答)：」之间的整段（实测前缀有 问：／居士问：／信众：／请问：／
  // 请问法师，／请问… 内联等多式，按位置取比按前缀取稳），再剥常见前缀
  const cut = body.search(/大安法师(?:答)?[：:]/);
  let q = cut >= 0 ? body.slice(0, cut).trim() : '';
  q = q.replace(/^(?:问|居士问|居士|信众|弟子|请问法师|请问)[：:，,]?\s*/, '').trim();

  // 答文：「大安法师答：」或「大安法师：」之后全部
  const am = raw.match(/大安法师(?:答)?[：:]\s*([\s\S]*)$/);
  let a = am ? am[1].trim() : '';
  let src = '';
  if (a) {
    const alines = a.split('\n');
    const last = alines[alines.length - 1].trim();
    // 出处行：----／—— 起头且含《》
    if (/^[-—─–]{1,4}.*《[^》]+》/.test(last)) {
      src = last.replace(/^[-—─–]+\s*/, '');
      alines.pop();
      a = alines.join('\n').trim();
    }
  }

  if (!a) { warns.push(`${fn} 答文为空`); continue; }
  if (!q && !/大安法师[：:]/.test(raw)) warns.push(`${fn} 无问句且非开示体`);
  items.push({ id, title, q, a, src, path: `qa/${fn}` });
}

// ── 目录层数据 ──
const lib = JSON.parse(readFileSync(join(ROOT, 'public', 'library.json'), 'utf8'));
const cat = JSON.parse(readFileSync(join(ROOT, 'public', 'catalog.json'), 'utf8'));
const catalogLite = {
  builtAt: new Date().toISOString().slice(0, 10),
  seriesCount: lib.seriesCount, chapterCount: lib.chapterCount, qaCount: lib.qaCount,
  books: lib.series.map((s) => ({ id: s.id, title: s.title, count: s.count })),
  audio: cat.series.map((s) => ({ id: s.id, title: s.title, cat: s.cat, count: s.count })),
};

const meta = {
  builtAt: new Date().toISOString().slice(0, 10),
  total: items.length,
  noQuestion: items.filter((it) => !it.q).length,
  noSource: items.filter((it) => !it.src).length,
};

writeFileSync(join(OUT_DIR, 'dingben.json'), JSON.stringify({ meta, items }, null, 0));
writeFileSync(join(OUT_DIR, 'catalog-lite.json'), JSON.stringify(catalogLite, null, 0));

console.log(`定本层：${items.length} 条（无问句 ${meta.noQuestion}，无出处 ${meta.noSource}）`);
console.log(`目录层：文库 ${catalogLite.books.length} 部 ${catalogLite.chapterCount} 篇，音频 ${catalogLite.audio.length} 部，问答 ${catalogLite.qaCount} 条`);
if (warns.length) { console.log('警告：'); warns.forEach((w) => console.log(' ', w)); }
// 校验：总数须为 820（qa 目录条数）——差一条即是解析漏了
if (items.length + warns.filter((w) => w.includes('答文为空')).length !== files.length) {
  console.error(`✗ 解析计数不合：文件 ${files.length}，产出 ${items.length}`); process.exit(1);
}
if (items.length !== files.length) {
  console.error(`✗ 有 ${files.length - items.length} 条未入定本（见警告），须修解析而非跳过`); process.exit(1);
}
console.log('✓ 解析计数合');

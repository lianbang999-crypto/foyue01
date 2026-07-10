// 从 R2 清单生成前端目录 public/catalog.json（多桶）+ 问答合并索引 public/qa.json
// 清单来源：
//   scripts/r2-manifest.json        daanfashi 桶（大安法师讲经）
//   scripts/r2-manifest-others.json 其余五桶（佛号/念诵/圣贤/印光/有声书）
// 时长均为 Range 读取 MP3 头解析。重新生成：node scripts/build-catalog.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(readFileSync(join(ROOT, 'scripts/r2-manifest.json'), 'utf8'));
const manifestOthers = JSON.parse(readFileSync(join(ROOT, 'scripts/r2-manifest-others.json'), 'utf8'));
const library = JSON.parse(readFileSync(join(ROOT, 'public/library.json'), 'utf8'));

// ---- 系列定义（顺序即经藏页展示顺序）----
const SERIES = [
  { id: 'wlsjy',   dir: '《佛说无量寿经》述义',     title: '《佛说无量寿经》述义',       cat: '讲经' },
  { id: 'gjszs',   dir: '观经四帖疏',              title: '《观经四帖疏》',             cat: '讲经' },
  { id: 'ssbdy',   dir: '阿弥陀佛四十八大愿',       title: '阿弥陀佛四十八大愿',         cat: '讲经' },
  { id: 'amtj',    dir: '佛说阿弥陀经',            title: '《佛说阿弥陀经》',           cat: '讲经' },
  { id: 'pxxyp',   dir: '普贤行愿品',              title: '《普贤行愿品》',             cat: '讲经' },
  { id: 'yttz',    dir: '大势至菩萨念佛圆通章',     title: '《大势至菩萨念佛圆通章》',   cat: '讲经' },
  { id: 'xyxz',    dir: '净土资粮信愿行（正编）',   title: '净土资粮·信愿行（正编）',    cat: '讲经' },
  { id: 'xyxx',    dir: '净土资粮信愿行（续编）',   title: '净土资粮·信愿行（续编）',    cat: '讲经' },
  { id: 'xffyw',   dir: '西方发愿文',              title: '《西方发愿文》',             cat: '讲经' },
  { id: 'xgzz',    dir: '西归直指',                title: '《西归直指》',               cat: '讲经' },
  { id: 'yhbf',    dir: '一函遍复',                title: '《一函遍复》',               cat: '讲经' },
  { id: 'lzsdy',   dir: '临终三大要',              title: '《临终三大要》',             cat: '讲经' },
  { id: 'lzzn',    dir: '临终助念答疑合辑',         title: '临终助念答疑合辑',           cat: '问答' },
  { id: 'zhuanti', dir: '专题讲座',                title: '专题讲座',                   cat: '讲座' },
  { id: 'qxjts',   dir: '劝修净土诗',              title: '《劝修净土诗》',             cat: '诗偈' },
  { id: 'story',   dir: '大安法师讲故事',           title: '大安法师讲故事',             cat: '故事' },
];

// 专题讲座内部排序：子系列在前（按序），独立讲座在后（固定次序）
const ZHUANTI_ORDER = [
  '净土宗教程/净土宗教程 第1讲', '净土宗教程/净土宗教程 第2讲', '净土宗教程/净土宗教程 第3讲',
  '净土宗教程/净土宗教程 第4讲', '净土宗教程/净土宗教程 第5讲', '净土宗教程/净土宗教程 第6讲',
  '阿弥陀佛摄生三愿/阿弥陀佛摄生三愿 第1讲', '阿弥陀佛摄生三愿/阿弥陀佛摄生三愿 第2讲',
  '劝人积阴德文/劝人积阴德文 第1讲', '劝人积阴德文/劝人积阴德文 第2讲', '劝人积阴德文/劝人积阴德文 第3讲',
  '淫业决疑论/淫业决疑论 第1讲', '淫业决疑论/淫业决疑论 第2讲',
  '念佛法门的原理和方法', '如何修学净土法门', '至简易至圆顿的净土法门',
  '五浊恶世非念佛法门必不能度', '断疑生信早出轮回', '以四种心念佛必得往生',
  '阿弥陀佛摄生三愿', // 若存在同名独立文件则忽略
  '临终接引愿的内蕴', '中阴念佛救度', '地藏菩萨本愿与念佛法门',
  '华严十玄门与净土法门', '远离恶处与亲近善友',
  '2018年三月昼夜念佛开示', '2021年五月昼夜念佛开示',
  '加拿大澄水寺开示', '人性与世界和平的困境（中英双语）',
];

const mp3s = manifest.filter(o => o.key.toLowerCase().endsWith('.mp3') && o.duration);

function fileBase(key) {
  const name = key.split('/').pop();
  return name.replace(/\.mp3$/i, '');
}

// 从文件名提取集号（第N讲/第N集/第N首），无编号返回 null
function epNum(base) {
  const m = base.match(/第\s*(\d+)\s*(讲|集|首)/);
  return m ? parseInt(m[1], 10) : null;
}

const warnings = [];
const series = [];

for (const def of SERIES) {
  const prefix = def.dir + '/';
  const files = mp3s.filter(o => o.key.startsWith(prefix));
  if (!files.length) { warnings.push(`系列无文件: ${def.dir}`); continue; }

  let eps;
  if (def.id === 'zhuanti') {
    // 专题讲座：按人工定义的次序
    eps = [];
    const used = new Set();
    for (const rel of ZHUANTI_ORDER) {
      const f = files.find(o => o.key === prefix + rel + '.mp3');
      if (f) { eps.push(f); used.add(f.key); }
    }
    for (const f of files) if (!used.has(f.key)) { eps.push(f); warnings.push(`专题讲座未排序文件（追加在尾部）: ${f.key}`); }
  } else if (def.id === 'story') {
    // "001-大安法师故事 1集 重法如山.mp3" → 按前缀数字排序
    eps = [...files].sort((a, b) => parseInt(fileBase(a.key)) - parseInt(fileBase(b.key)));
  } else if (def.id === 'qxjts') {
    // 开篇排最前，其余按 第N首
    eps = [...files].sort((a, b) => {
      const na = fileBase(a.key).includes('开篇') ? 0 : (epNum(fileBase(a.key)) ?? 999);
      const nb = fileBase(b.key).includes('开篇') ? 0 : (epNum(fileBase(b.key)) ?? 999);
      return na - nb;
    });
  } else if (def.id === 'yttz') {
    // 圆通章：正讲 第1~4讲 在前，《要义》第1~2讲 在后
    const main = files.filter(o => !fileBase(o.key).includes('要义')).sort((a, b) => epNum(fileBase(a.key)) - epNum(fileBase(b.key)));
    const yaoyi = files.filter(o => fileBase(o.key).includes('要义')).sort((a, b) => epNum(fileBase(a.key)) - epNum(fileBase(b.key)));
    eps = [...main, ...yaoyi];
  } else {
    eps = [...files].sort((a, b) => (epNum(fileBase(a.key)) ?? 0) - (epNum(fileBase(b.key)) ?? 0));
    // 编号完整性检查
    const nums = eps.map(o => epNum(fileBase(o.key))).filter(n => n != null);
    if (nums.length) {
      for (let n = Math.min(...nums); n <= Math.max(...nums); n++)
        if (!nums.includes(n)) warnings.push(`${def.dir} 缺 第${n}`);
    }
  }

  const episodes = eps.map((o, i) => {
    const base = fileBase(o.key);
    let title;
    if (def.id === 'story') {
      // "001-大安法师故事 1集 重法如山" → "重法如山"（并清理 _MP3 等残留后缀）
      const m = base.match(/\d+集\s*(.+)$/);
      title = (m ? m[1].trim() : base).replace(/[_\s]*MP3$/i, '').trim();
    } else if (def.id === 'zhuanti') {
      title = base.replace(/ 第(\d+)讲$/, ' · 第$1讲');
    } else if (def.id === 'yttz') {
      title = base.includes('要义') ? base.replace(/^.*要义\s*/, '要义 ') : (base.match(/第\d+讲/) || [base])[0];
    } else {
      // 常规：取 "第N讲/集/首"；开篇等无编号的保留去前缀短名
      const m = base.match(/第\s*\d+\s*(讲|集|首)/);
      title = m ? m[0].replace(/\s+/g, '') : (base.includes('开篇') ? '开篇' : base);
    }
    return { i, title, key: o.key, dur: Math.round(o.duration), size: o.size };
  });

  series.push({
    id: def.id, title: def.title, cat: def.cat,
    count: episodes.length,
    totalDur: Math.round(episodes.reduce((s, e) => s + e.dur, 0)),
    episodes,
  });
}

// 覆盖率检查：所有 mp3 是否都被收录
const covered = new Set(series.flatMap(s => s.episodes.map(e => e.key)));
for (const o of mp3s) if (!covered.has(o.key)) warnings.push(`未收录: ${o.key}`);

// daanfashi 桶的系列统一标注桶别名
for (const s of series) s.bucket = 'daan';

// ---- 其余五桶（桶别名 → Worker 路由 /audio/<别名>/<key>）----
const O = manifestOthers.filter(o => o.duration);
const epsOf = (bucket, prefix = '') =>
  O.filter(o => o.bucket === bucket && o.key.startsWith(prefix));
const stem = k => k.split('/').pop().replace(/\.mp3$/i, '');
const leadNum = k => { const m = stem(k).match(/^(\d+)/); return m ? parseInt(m[1], 10) : 9999; };

function makeSeries({ id, bucket, title, cat, files, sort, titleFn }) {
  const sorted = [...files].sort(sort);
  const episodes = sorted.map((o, i) => ({
    i, title: titleFn(o), key: o.key, dur: Math.round(o.duration), size: o.size,
  }));
  return {
    id, bucket, title, cat, count: episodes.length,
    totalDur: Math.round(episodes.reduce((s, e) => s + e.dur, 0)),
    episodes,
  };
}

// 印光大师故事（编号 000~197，混合有无空格）
series.push(makeSeries({
  id: 'ygds', bucket: 'yinguang', title: '印光大师故事', cat: '传记',
  files: epsOf('yinguangdashi'),
  sort: (a, b) => leadNum(a.key) - leadNum(b.key),
  titleFn: o => stem(o.key).replace(/^\d+\s*/, ''),
}));

// 东林圣贤往生（20集）
series.push(makeSeries({
  id: 'dlsx', bucket: 'shengxian', title: '东林圣贤往生', cat: '传记',
  files: epsOf('jingtushengxian'),
  sort: (a, b) => a.key.localeCompare(b.key, 'zh'),
  titleFn: o => stem(o.key),
}));

// 有声书三部（安士全书系）
for (const [id, name] of [['wsxz', '万善先资'], ['yhhk', '欲海回狂'], ['wyrc', '物犹如此']]) {
  series.push(makeSeries({
    id, bucket: 'ysshu', title: `《${name}》有声书`, cat: '有声书',
    files: epsOf('youshengshu', name + '/'),
    sort: (a, b) => leadNum(a.key.replace(/^.*】/, '')) - leadNum(b.key.replace(/^.*】/, '')) ||
      (stem(a.key).match(/】(\d+)/)?.[1] ?? 0) - (stem(b.key).match(/】(\d+)/)?.[1] ?? 0),
    titleFn: o => stem(o.key).replace(/^【.+?】\d+\s*/, ''),
  }));
}

// 净土百问（151问，文件名即问题）
series.push(makeSeries({
  id: 'jtbw', bucket: 'ysshu', title: '净土百问', cat: '问答',
  files: epsOf('youshengshu', '净土百问/'),
  sort: (a, b) => a.key.localeCompare(b.key, 'zh'),
  titleFn: o => stem(o.key),
}));

// 课诵素材（供念佛堂使用，经藏页不展示）
series.push(makeSeries({
  id: 'fohao', bucket: 'fohao', title: '东林佛号', cat: '课诵',
  files: epsOf('fohao'),
  sort: (a, b) => a.key.localeCompare(b.key, 'zh'),
  titleFn: o => stem(o.key),
}));
series.push(makeSeries({
  id: 'dusong', bucket: 'dusong', title: '经典念诵', cat: '课诵',
  files: epsOf('jingdiandusong'),
  sort: (a, b) => a.key.localeCompare(b.key, 'zh'),
  titleFn: o => stem(o.key).replace(/（念诵）/, ''),
}));

// 五桶覆盖率检查
const covered2 = new Set(series.flatMap(s => s.episodes.map(e => e.key)));
for (const o of O) if (!covered2.has(o.key)) warnings.push(`未收录(五桶): ${o.bucket}/${o.key}`);

// ---- 问道合并索引 qa.json：820 条文字问答 × 151 条原声问答，按题目匹配 ----
const norm = t => t.replace(/[\s？?。．.，,！!、：:“”"'‘’（）()《》〈〉—\-…·]/g, '')
  .replace(/^大安法师答?[:：]?/, '');
const audioQA = series.find(s => s.id === 'jtbw').episodes;
const audioByNorm = new Map(audioQA.map(e => [norm(e.title), e]));
const qaMerged = [];
const usedAudio = new Set();
for (const q of library.qa) {
  const a = audioByNorm.get(norm(q.title));
  const item = { title: q.title, text: q.path };
  if (a && !usedAudio.has(a.key)) { item.audio = { key: a.key, dur: a.dur }; usedAudio.add(a.key); }
  qaMerged.push(item);
}
let audioOnly = 0;
for (const a of audioQA) {
  if (!usedAudio.has(a.key)) { qaMerged.push({ title: a.title, audio: { key: a.key, dur: a.dur } }); audioOnly++; }
}
const matched = qaMerged.filter(x => x.text && x.audio).length;
writeFileSync(join(ROOT, 'public/qa.json'), JSON.stringify({
  generatedAt: new Date().toISOString().slice(0, 10),
  total: qaMerged.length, matched, textOnly: library.qa.length - matched, audioOnly,
  items: qaMerged,
}, null, 1), 'utf8');

const catalog = {
  name: '佛乐 · 净土法音',
  generatedAt: new Date().toISOString().slice(0, 10),
  totalEpisodes: series.reduce((s, x) => s + x.count, 0),
  totalDur: series.reduce((s, x) => s + x.totalDur, 0),
  series,
};

writeFileSync(join(ROOT, 'public/catalog.json'), JSON.stringify(catalog, null, 1), 'utf8');
console.log(`✓ catalog.json：${catalog.totalEpisodes} 集 / ${(catalog.totalDur / 3600).toFixed(1)} 小时`);
for (const s of series) console.log(`  ${s.bucket.padEnd(9)} ${s.id.padEnd(8)} ${s.count.toString().padStart(3)} 集  ${(s.totalDur / 3600).toFixed(1).padStart(6)}h  [${s.cat}] ${s.title}`);
console.log(`✓ qa.json：共 ${qaMerged.length} 问（文声齐备 ${matched} / 仅文字 ${library.qa.length - matched} / 仅原声 ${audioOnly}）`);
if (warnings.length) { console.log('\n⚠ 警告:'); warnings.forEach(w => console.log('  ' + w)); }

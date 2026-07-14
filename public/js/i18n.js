// 佛乐 · 界面多语言（内置种子词典 + AI 翻译）
// 思路与繁体转换一致：遍历文本节点替换 + MutationObserver 接管动态内容。
// 词典三级：内置种子（核心导航，人工把关）→ 本机缓存 fy.i18n.<lang> → /api/i18n
//（免费模型翻译，逐条边缘缓存，全网同一字符串只翻一次）。
// 内容区不翻译（讲记正文/篇目名/留言/问答）：经文义理以原文为准，界面翻译只服务导航。

// —— 种子词典：高频核心字符串，保证首屏质量与零等待 ——
const SEED = {
  en: {
    '佛乐': '佛乐',   // 品牌名保持原字
    '佛乐 · 净土法音': '佛乐 · Pure Land Dharma',
    '净土法音': 'Pure Land Dharma', '首页': 'Home', '听经': 'Listen', '阅读': 'Read',
    '我的': 'Me', '问法': 'Ask', '今日': 'Today', '直播中': 'Live', '进入 ›': 'Enter ›',
    '听经台': 'Live Station', '有声书': 'Audiobooks', '佛号': 'Chanting',
    '继续收听': 'Continue listening', '播放 ›': 'Play ›', '续读 ›': 'Resume ›',
    '今日恭读': "Today's reading", '念佛': 'Recitation', '数珠 · 定课': 'Beads · Daily practice',
    '二十四小时讲经': '24-hour sutra lectures', '故事 · 传记': 'Stories · Biographies',
    '讲记原文': 'Lecture texts', '‹ 返回': '‹ Back', '‹ 返回听经': '‹ Back to Listen',
    '‹ 返回直播': '‹ Back to Live', '‹ 返回阅读': '‹ Back to Read',
    '接下来': 'Up next', '查看今日节目单 ›': "Today's schedule ›", '节目单': 'Schedule',
    '明日': 'Tomorrow', '同修在此': 'Fellow practitioners', '发 送': 'Send',
    '弹幕': 'Danmaku', '字幕': 'Subtitles', '定时': 'Timer', '收藏': 'Save',
    '目录': 'Contents', '分享': 'Share', '转发': 'Share', '链接': 'Link',
    '海报': 'Poster', '下载': 'Download', '保存': 'Save', '划 线': 'Highlight',
    '点播': 'On demand', '轻触莲台 · 与大众同闻': 'Tap the lotus · listen with the assembly',
    '足迹': 'Trail', '通用': 'General', '主题': 'Theme', '语言': 'Language',
    '跟随时段': 'Auto', '浅色': 'Light', '深色': 'Dark',
    '我的划线': 'My highlights', '存储与缓存': 'Storage & cache',
    '备份与迁移': 'Backup & transfer', '关于本站': 'About', '关于我们': 'About us',
    '联系方式': 'Contact', '去计数 ›': 'Count ›', '最近在听': 'Recently played',
    '最近在读': 'Recently read', '念佛计数': 'Recitation counter',
    '十念 +10': 'Ten +10', '撤销 −1': 'Undo −1', '重 置': 'Reset', '功课 ›': 'Practice ›',
    '回 向': 'Dedicate', '南无阿弥陀佛': 'Namo Amitabha', '新 问': 'New',
    '分享 · 法布施': 'Share · Dharma gift', '朗读中 …': 'Reading aloud …',
    '上一集': 'Previous', '下一集': 'Next', '倍速': 'Speed', '今日 · 声': 'today',
    '搜索系列与集名 …': 'Search series & episodes …', '搜索篇目 …': 'Search chapters …',
    '向文库提问 …': 'Ask the library …', '‹ 上一篇': '‹ Previous', '下一篇': 'Next',
  },
  ja: {
    '佛乐': '佛乐',   // 品牌名保持原字
    '佛乐 · 净土法音': '佛乐 · 浄土法音',
    '净土法音': '浄土法音', '首页': 'ホーム', '听经': '聴経', '阅读': '閲読',
    '我的': 'マイページ', '问法': '問法', '今日': '本日', '直播中': 'ライブ中', '进入 ›': '入る ›',
    '听经台': '放送台', '有声书': 'オーディオブック', '佛号': '念仏',
    '继续收听': '続きを聴く', '播放 ›': '再生 ›', '续读 ›': '続きを読む ›',
    '今日恭读': '本日の恭読', '念佛': '念仏', '数珠 · 定课': '数珠 · 日課',
    '二十四小时讲经': '二十四時間の講経', '故事 · 传记': '物語 · 伝記',
    '讲记原文': '講記原文', '‹ 返回': '‹ 戻る', '‹ 返回听经': '‹ 聴経に戻る',
    '‹ 返回直播': '‹ ライブに戻る', '‹ 返回阅读': '‹ 閲読に戻る',
    '接下来': 'この後', '查看今日节目单 ›': '本日の番組表 ›', '节目单': '番組表',
    '明日': '明日', '同修在此': '同修の広場', '发 送': '送信',
    '弹幕': '弾幕', '字幕': '字幕', '定时': 'タイマー', '收藏': 'お気に入り',
    '目录': '目次', '分享': '共有', '转发': '共有', '链接': 'リンク',
    '海报': 'ポスター', '下载': 'ダウンロード', '保存': '保存', '划 线': 'ハイライト',
    '点播': 'オンデマンド', '轻触莲台 · 与大众同闻': '蓮台に触れて · 大衆と共に聞く',
    '足迹': '足あと', '通用': '一般', '主题': 'テーマ', '语言': '言語',
    '跟随时段': '自動', '浅色': 'ライト', '深色': 'ダーク',
    '我的划线': 'マイハイライト', '存储与缓存': 'ストレージとキャッシュ',
    '备份与迁移': 'バックアップと移行', '关于本站': '当サイトについて', '关于我们': '私たちについて',
    '联系方式': '連絡先', '去计数 ›': 'カウントへ ›', '最近在听': '最近聴いた',
    '最近在读': '最近読んだ', '念佛计数': '念仏カウンター',
    '十念 +10': '十念 +10', '撤销 −1': '取り消し −1', '重 置': 'リセット', '功课 ›': '日課 ›',
    '回 向': '回向', '南无阿弥陀佛': '南無阿弥陀仏', '新 问': '新規',
    '分享 · 法布施': '共有 · 法布施', '朗读中 …': '朗読中 …',
    '上一集': '前の回', '下一集': '次の回', '倍速': '速度', '今日 · 声': '本日 · 声',
    '搜索系列与集名 …': 'シリーズ・回名を検索 …', '搜索篇目 …': '篇目を検索 …',
    '向文库提问 …': '文庫に質問 …', '‹ 上一篇': '‹ 前の篇', '下一篇': '次の篇',
  },
};

// 内容区（不翻译）：正文、篇目/系列名、留言、问答、字幕、弹幕等
const SKIP_SEL = [
  '#readerBody', '#chatLog', '#cmtList', '#dmLayer', '#liveCc', '#plCc',
  '.ep-list', '.chat-starters', '.share-prev', '.cite-x',
  '.ls-info', '.hc-main', '.wh-main', '.series-title', '.ep-title',
  '#seriesName', '#seriesIntro', '#nextList', '#schedList', '#wkSeriesName',
  '#miniTitles', '.pl-meta', '#plListEps', '#countName',
  'script', 'style', 'textarea',
].join(',');

const CJK = /[㐀-鿿豈-﫿]/;
const I18N_ATTRS = ['placeholder', 'aria-label', 'title'];

let lang = '';
const dict = new Map();      // 原文 → 译文
const outputs = new Set();   // 已产出的译文（防止日文汉字被再次送翻）
const queue = new Set();     // 待翻译原文
const waiters = new Map();   // 原文 → [回调]
let flushT = 0;
let coolUntil = 0;           // 接口失败后的冷却截止
let saveT = 0;

export function i18nLang() { return lang; }

export function initI18n(l) {
  if (!SEED[l] || lang === l) return;
  lang = l;
  document.documentElement.lang = l;
  for (const [k, v] of Object.entries(SEED[l])) { dict.set(k, v); outputs.add(v); }
  try {
    const store = JSON.parse(localStorage.getItem('fy.i18n.' + l) || '{}');
    for (const [k, v] of Object.entries(store)) {
      if (!dict.has(k)) dict.set(k, v);
      outputs.add(v);
    }
  } catch { /* 本机缓存损坏则重建 */ }

  request(document.title, (v) => { document.title = v; });
  apply(document.body);

  new MutationObserver((muts) => {
    for (const mu of muts) {
      if (mu.type === 'characterData') translateNode(mu.target);
      else {
        for (const n of mu.addedNodes) {
          if (n.nodeType === 3) translateNode(n);
          else if (n.nodeType === 1) apply(n);
        }
      }
    }
  }).observe(document.body, { childList: true, characterData: true, subtree: true });
}

function skipped(el) { return !el || !!el.closest(SKIP_SEL); }

function apply(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (skipped(n.parentElement) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const n of nodes) translateNode(n);

  if (root.querySelectorAll) {
    const els = [root, ...root.querySelectorAll('[placeholder], [aria-label], [title]')];
    for (const el of els) {
      if (!el.getAttribute || skipped(el)) continue;
      for (const attr of I18N_ATTRS) {
        const v = el.getAttribute(attr);
        if (v) request(v, (t) => el.setAttribute(attr, t));
      }
    }
  }
}

function translateNode(node) {
  if (skipped(node.parentElement)) return;
  const raw = node.nodeValue;
  const s = raw.trim();
  // 只翻短界面串：长段落即内容，交由原文呈现
  if (!s || s.length > 120 || !CJK.test(s) || outputs.has(s)) return;
  request(s, (v) => {
    if (v !== s && node.nodeValue.trim() === s) node.nodeValue = raw.replace(s, v);
  });
}

function request(s, cb) {
  if (!s || !CJK.test(s) || outputs.has(s)) return;
  const hit = dict.get(s);
  if (hit !== undefined) { cb(hit); return; }
  queue.add(s);
  if (!waiters.has(s)) waiters.set(s, []);
  waiters.get(s).push(cb);
  if (!flushT) flushT = setTimeout(flush, 400);
}

async function flush() {
  flushT = 0;
  if (!queue.size) return;
  if (Date.now() < coolUntil) { flushT = setTimeout(flush, coolUntil - Date.now() + 100); return; }
  const batch = [...queue].slice(0, 40);
  for (const t of batch) queue.delete(t);
  try {
    const r = await fetch('/api/i18n', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lang, texts: batch }),
    });
    if (!r.ok) throw new Error(String(r.status));
    const { map } = await r.json();
    for (const t of batch) {
      const v = map[t];
      if (!v) { waiters.delete(t); continue; }   // 本轮未译出：保留原文
      dict.set(t, v);
      outputs.add(v);
      for (const cb of waiters.get(t) || []) cb(v);
      waiters.delete(t);
    }
    saveSoon();
  } catch {
    // 失败冷却 30 秒再试，原文继续可读，不打扰
    for (const t of batch) queue.add(t);
    coolUntil = Date.now() + 30000;
  }
  if (queue.size && !flushT) flushT = setTimeout(flush, 500);
}

function saveSoon() {
  clearTimeout(saveT);
  saveT = setTimeout(() => {
    if (dict.size > 1500) return;   // 防止本机缓存无限膨胀（边缘缓存仍生效）
    try { localStorage.setItem('fy.i18n.' + lang, JSON.stringify(Object.fromEntries(dict))); }
    catch { /* 存储满则放弃本机缓存 */ }
  }, 1500);
}

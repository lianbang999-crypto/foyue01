// 目录层 —— 文库/音频的部数、篇数、有无某书等定位性问题，查表直答（设计书 L1）
//
// 能查表的绝不检索：这类问题走 RAG 反而答不准（块里没有全局计数）。
// answer() 返回 null 即「不是我的题」，上层继续走定本/综述——宁可漏接，不可错答。

import { normQ } from './lex.js';

let CAT = null;
export function initCatalog(data) { CAT = data; }

/** 在问句中找被点名的书（文库或音频系列），按归一后包含判断 */
function findSeries(q) {
  const qn = normQ(q);
  const hitOf = (list) => list.filter((s) => {
    const tn = normQ(s.title).replace(/^佛说/, '');
    return tn.length >= 4 && (qn.includes(tn) || tn.includes(qn));
  });
  return { books: hitOf(CAT.books), audio: hitOf(CAT.audio) };
}

/** 目录问答；答不了返回 null */
export function catalogAnswer(q) {
  if (!CAT) return null;
  const t = String(q);

  // 全局计数类
  if (/(文库|书|讲记)[^。]{0,8}(有多少|多少部|有几部|几部|哪些|哪几部)|有(多少|几)部(书|讲记)/.test(t)) {
    const sample = CAT.books.slice(0, 8).map((b) => `《${b.title}》`).join('、');
    return `文库现收大安法师讲记 ${CAT.books.length} 部、共 ${CAT.chapterCount} 篇，另有答问 ${CAT.qaCount} 条。包括 ${sample} 等，全目见「文库」页。`;
  }
  if (/(有声书|音频|专辑|听经)[^。]{0,8}(有多少|多少部|几部|哪些)/.test(t)) {
    return `听经台现收音频专辑 ${CAT.audio.length} 部。全目见「听经」页。`;
  }
  if (/(问答|答问)[^。]{0,6}(有多少|多少条|几条)/.test(t)) {
    return `文库现收大安法师答问 ${CAT.qaCount} 条，见「文库·问答」。`;
  }

  // 点名某书类：有没有／几讲／几篇
  if (/(有没有|有无|是否有|收没收|有几讲|多少讲|几篇|多少篇)/.test(t)) {
    const { books, audio } = findSeries(t);
    if (books.length || audio.length) {
      const parts = [];
      if (books.length) parts.push(books.map((b) => `文库有《${b.title}》共 ${b.count} 篇`).join('；'));
      if (audio.length) parts.push(audio.map((a) => `听经台有《${a.title}》共 ${a.count} 集`).join('；'));
      return parts.join('；') + '。';
    }
    // 点名了书但库里没有——只有在问句明确是「有没有某书」形态时才答无
    if (/《[^》]{2,20}》/.test(t) && /(有没有|有无|是否有|收没收)/.test(t)) {
      return `文库与听经台暂未收录您问的这一部。现有讲记 ${CAT.books.length} 部、音频 ${CAT.audio.length} 部，全目见「文库」与「听经」页。`;
    }
  }
  return null;
}

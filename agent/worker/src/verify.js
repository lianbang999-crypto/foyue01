// 句级闸 —— 综述层逐句核验（设计书五；承袭选佛谱 verify/gate 思路，按问文库语料改造）
//
// 【与选佛谱之别】那边有位名／门号定表可对；这边语料是散文讲记，可硬校的是四样：
//   ① 角标越界        → 剥角标仍吐（话未必错，标错了而已）
//   ② 直引不符        → 丢句（「」内 ≥4 字须在资料中逐字可寻——引号是归属声明，错即冒引）
//   ③ 亲历句式        → 丢句（第一人称亲历＝冒充法师，设计书纪律二；剥引号后再查，
//                          引号内出现是法师原话被引用，正当）
//   ④ 裸数字凭空      → 丢句（治「1368 字」类幻觉；用户问句中的数字回声属正当，问句并入数字干草堆）
// done 级（compose.js 汇总）：首句无角标记 verdict-uncited（M1 非致命）；丢句过半→ungrounded。

/** 归一：去空白与常用标点，供逐字比对 */
export const cmp = (t) => String(t || '')
  .replace(/[\s。，、；：？！""''「」『』…—·．,.!?;:()（）\[\]【】]/g, '');

/** 亲历句式（剥引号后匹配）。清单宁窄勿宽：误杀正当转述比放过一句更伤答语。 */
const PERSONA = /(我们东林寺|我告诉他|我告诉她|我告诉他们|我曾经?[遇见看听]|我当年|我原来|我前几年|来找过?我|问过我|跟我说|我接触过|我看到有个|我遇到)/;

/** 剥去引号内文本（含中西引号） */
const stripQuoted = (s) => String(s).replace(/[「『"“][^」』"”]*[」』"”]/g, '');

/**
 * 一句过闸。
 * @param {string} s        句子（可含角标 [n]）
 * @param {object} ctx      { hayN: 资料归一串, hayNum: 数字干草堆(资料原文+问句), n: 资料条数 }
 * @returns {{text:string, dropped:boolean, issues:Array}}
 */
export function gateSentence(s, ctx) {
  const issues = [];

  // ② 直引须逐字可寻
  const quotes = [...String(s).matchAll(/[「『"“]([^」』"”]{4,})[」』"”]/g)].map((m) => m[1]);
  for (const q of quotes) {
    const qn = cmp(q);
    if (qn && !String(q).split(/…+|\.{3,}/).every((seg) => !cmp(seg) || ctx.hayN.includes(cmp(seg)))) {
      return { text: '', dropped: true, issues: [{ kind: 'quote', detail: `直引「${q.slice(0, 20)}…」资料中无` }] };
    }
  }

  const bare = stripQuoted(s);

  // ③ 亲历句式
  const pm = bare.match(PERSONA);
  if (pm) return { text: '', dropped: true, issues: [{ kind: 'persona', detail: `亲历句式「${pm[0]}」` }] };

  // ④ 裸数字（≥2 位）凭空
  const nums = [...bare.matchAll(/\d{2,}/g)].map((m) => m[0]);
  const stray = nums.filter((n) => !ctx.hayNum.includes(n));
  if (stray.length) return { text: '', dropped: true, issues: [{ kind: 'number', detail: `数「${stray.join('、')}」资料与问句皆无` }] };

  // ① 角标越界：剥之仍吐
  let cleaned = String(s).replace(/\[(\d{1,2})\]/g, (whole, n) => (+n >= 1 && +n <= ctx.n ? whole : ''));
  if (cleaned !== s) issues.push({ kind: 'cite-range', detail: '角标越界已剥' });

  return { text: cleaned, dropped: false, issues };
}

/** 构造核验上下文 */
export function gateCtx(passages, question) {
  return {
    hayN: cmp(passages.map((p) => p.text).join('\n')),
    hayNum: passages.map((p) => p.text).join('\n') + '\n' + String(question || ''),
    n: passages.length,
  };
}

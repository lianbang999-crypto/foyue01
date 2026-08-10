// 综述层 —— 据文转述，句级过闸流出（设计书 L2 与纪律三条）
//
// 【流式与核验并存】材料在手且有限，故按句缓冲、过闸放行：用户仍看见字在长出来，
// 凭空之语却吐不到脸上（承袭选佛谱 compose.js 已验证的做法）。
//
// 【风格：骨可学皮不可穿】体例学法师之骨（先答后据、平实斩截），归属守转述之份
// （第三人称、原话进引号、劝诫只转引）——纪律写死在系统提示，verify.js 句级兜底。

import { gateSentence, gateCtx } from './verify.js';
import { frame } from './sse.js';

const SF_BASE = 'https://api.siliconflow.cn/v1';

export const modelOf = (env) => (env && env.COMPOSE_MODEL) || 'Qwen/Qwen3.5-35B-A3B';

const SYS = `你是净土修学网站「佛乐」问文库的检索转述者。资料全部出自净土宗大安法师的讲经文字与答问实录。

铁律（违者答语作废）：
一、只依【资料】作答。资料没有的一个字也不许添——不引他经、不凭常识补、不作发挥。
二、答语第一句直接回答所问，且这一判断必须是资料中法师下过的判断，句末缀角标 [n]。资料中无判语，就不下判断，如实说「文库中未见法师对此的直接开示」。
三、每句句末缀角标 [n]，n 为所依资料编号。无资料可依之句，不写。
四、你是转述者，不是法师。全篇第三人称（「大安法师开示」「法师在讲记中说」）；禁止第一人称叙事——「我们东林寺」「我告诉他」「我当年」这类亲历语一个字不许出现。
五、法师原话放引号「」内逐字引用；引号外是你的转述。劝诫语（「一定要」「千万不要」）只可出现在引号内，或写作「法师劝……」的转述。
六、平实白话，先答后据，条理清楚，不堆辞藻。三百字以内为宜，义理繁复者至多五百字。
七、涉重大修行抉择、医疗、家庭去留等个人裁断：可转述法师相关开示，但须明说「具体抉择请阅原文、亲近善知识」，不替问者拿主意。`;

/** 检索：嵌入→召回 20→重排 8。不可用返回 null，由上层降级。 */
export async function retrieve(env, q) {
  const key = env && env.SILICONFLOW_API_KEY;
  if (!key || !env.WENKU) return null;
  const sf = (path, body) => fetch(SF_BASE + path, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  try {
    const embRes = await sf('/embeddings', { model: 'BAAI/bge-m3', input: [q] });
    if (!embRes.ok) return null;
    const vector = (await embRes.json()).data[0].embedding;
    const found = await env.WENKU.query(vector, { topK: 20, returnValues: false, returnMetadata: 'all' });
    let matches = (found.matches || []).filter((m) => m.metadata && m.metadata.t);
    if (!matches.length) return [];
    try {
      const rrRes = await sf('/rerank', {
        model: 'BAAI/bge-reranker-v2-m3', query: q,
        documents: matches.map((m) => m.metadata.t), top_n: 8,
      });
      if (rrRes.ok) {
        const rr = await rrRes.json();
        matches = rr.results.map((r) => matches[r.index]);
      } else matches = matches.slice(0, 8);
    } catch { matches = matches.slice(0, 8); }
    return matches.map((m, i) => ({
      n: i + 1,
      title: m.metadata.title, series: m.metadata.series,
      path: m.metadata.path, kind: m.metadata.kind,
      text: String(m.metadata.t || ''),
      x: String(m.metadata.t || '').replace(/\s+/g, ' ').trim().slice(0, 160),
    }));
  } catch { return null; }
}

// 句切：句末标点后紧跟的角标并入本句
const SENT = /^[\s\S]*?[。！？；\n](?:\[\d{1,2}\])*/;
function cutSents(buf) {
  const out = [];
  let rest = buf;
  for (;;) {
    const m = rest.match(SENT);
    if (!m || !m[0]) break;
    out.push(m[0]);
    rest = rest.slice(m[0].length);
  }
  return { sents: out, rest };
}

/** 快取回放（KV 缺绑或未中返回 null）。只服务可信路。 */
export async function composeFromCache({ env, guard, ckey, T0 }) {
  if (!guard || !guard.trusted || !ckey || !env || !env.RL) return null;
  let hit = null;
  try { hit = await env.RL.get(`ans:${modelOf(env)}:${ckey}`, 'json'); } catch { /* 快取故障视同未中 */ }
  if (!hit || !hit.text) return null;
  return [
    frame('mode', { mode: 'composite', basis: { label: 'AI 依大安法师讲记综述' }, cacheStatus: 'hit' }),
    frame('sources', hit.sources || []),
    frame('delta', { text: hit.text }),
    frame('done', { evidenceStatus: hit.evidenceStatus || 'grounded', verify: hit.verify || {}, cacheStatus: 'hit', timing: { total: Date.now() - T0 } }),
  ];
}

/**
 * 据文生成，流式 SSE。返回 ReadableStream；不可用返回 null（上层降级）。
 * @param o {question, history, passages, env, guard, ckey, T0, requestId}
 */
export async function streamCompose(o) {
  const { question, history, passages, env, guard, ckey, T0 } = o;
  const key = env && env.SILICONFLOW_API_KEY;
  if (!key || !passages.length) return null;

  const context = passages.map((p) => `【${p.n}】《${p.series}》${p.title}\n${p.text}`).join('\n\n');
  const messages = [
    { role: 'system', content: SYS },
    ...(Array.isArray(history) ? history : [])
      .filter((h) => h && (h.role === 'user' || h.role === 'assistant'))
      .slice(-4)
      .map((h) => ({ role: h.role, content: String(h.content).slice(0, 400) })),
    { role: 'user', content: `【资料】\n${context}\n\n【问题】${question}` },
  ];

  let res;
  try {
    res = await fetch(`${SF_BASE}/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model: modelOf(env), messages, stream: true,
        max_tokens: 1600, temperature: 0.3,
        enable_thinking: false,   // 混合推理模型关思考直接作答（07-02 已验证的坑）
      }),
    });
  } catch { return null; }
  if (!res || !res.ok || !res.body) return null;

  const ctx = gateCtx(passages, question);
  const sources = passages.map(({ n, title, series, path, kind, x }) => ({ n, title, series, path, kind, x }));

  return new ReadableStream({
    async start(c) {
      c.enqueue(frame('mode', { mode: 'composite', basis: { label: 'AI 依大安法师讲记综述' }, requestId: o.requestId, cacheStatus: ckey ? 'miss' : undefined }));
      c.enqueue(frame('sources', sources));

      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let sse = '', buf = '', out = '', kept = 0, dropped = 0, first = true, verdictUncited = false;
      const issues = [];

      const flush = (sents) => {
        for (const s of sents) {
          if (!s.trim()) { out += s; c.enqueue(frame('delta', { text: s })); continue; }
          const g = gateSentence(s, ctx);
          if (g.dropped) { dropped++; issues.push(...g.issues); continue; }
          if (first) {   // 判语有据之弱校验（M1）：首句须缀角标，缺者记 issue 不丢句
            first = false;
            if (!/\[\d{1,2}\]/.test(g.text)) { verdictUncited = true; issues.push({ kind: 'verdict-uncited', detail: '答首判断句无角标' }); }
          }
          kept++;
          if (g.issues.length) issues.push(...g.issues);
          out += g.text;
          c.enqueue(frame('delta', { text: g.text }));
        }
      };

      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          sse += dec.decode(value, { stream: true });
          let sep;
          while ((sep = sse.indexOf('\n\n')) >= 0) {
            const block = sse.slice(0, sep); sse = sse.slice(sep + 2);
            const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
            if (!dataLine) continue;
            const data = dataLine.slice(5).trim();
            if (!data || data === '[DONE]') continue;
            let j; try { j = JSON.parse(data); } catch { continue; }
            const d = j.choices && j.choices[0] && j.choices[0].delta;
            // reasoning_content 一概弃之——不开思考是已定之事，纵上游自作主张亦不外露
            const t = d && typeof d.content === 'string' ? d.content : '';
            if (!t) continue;
            buf += t;
            const { sents, rest } = cutSents(buf);
            buf = rest;
            if (sents.length) flush(sents);
          }
        }
      } catch { /* 中途断流：已吐者留，不补编 */ }
      if (buf.trim()) flush([buf]);

      // 全篇零有效角标或丢句过半 → ungrounded；快取只存全过闸之答
      const anyCite = /\[\d{1,2}\]/.test(out);
      const grounded = kept > 0 && dropped <= kept && anyCite;
      const verify = { kept, dropped, issues: issues.slice(0, 8), verdictUncited };
      if (ckey && dropped === 0 && kept > 0 && grounded && env && env.RL) {
        try {
          await env.RL.put(`ans:${modelOf(env)}:${ckey}`, JSON.stringify({
            text: out, sources, verify, evidenceStatus: 'grounded',
          }), { expirationTtl: 604800 });
        } catch { /* 快取写失败不反噬答问 */ }
      }
      c.enqueue(frame('done', {
        evidenceStatus: grounded ? 'grounded' : 'ungrounded',
        verify,
        ...(guard && guard.remaining != null ? { remaining: guard.remaining } : {}),
        ...(ckey ? { cacheStatus: 'miss' } : {}),
        timing: { total: Date.now() - T0 },
      }));
      c.close();
    },
  });
}

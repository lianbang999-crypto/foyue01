// Pages Function: /api/ask 问道 RAG 接口
// 流程：问题向量化(bge-m3) → Vectorize 召回20 → bge-reranker 重排取8
//     → 大模型依据资料流式作答（SSE），先发出处后发正文。
// Key 存于 Pages Secret，前端不接触硅基流动。

const SF_BASE = 'https://api.siliconflow.cn/v1';

// 兜底限流：隔离实例内按 IP 每分钟计数（与平台限流绑定双保险）
const rlCounts = new Map();
function localLimitOk(ip) {
  const win = Math.floor(Date.now() / 60000);
  const key = `${ip}:${win}`;
  const n = (rlCounts.get(key) || 0) + 1;
  rlCounts.set(key, n);
  if (rlCounts.size > 5000) rlCounts.clear(); // 防内存膨胀
  return n <= 8;
}

export async function onRequestPost(context) {
  const { request, env } = context;

  // 跨站浏览器请求由 CORS 预检自然拦截（本接口不发 CORS 头）；滥用防护靠限流
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (!localLimitOk(ip)) return new Response('提问太频繁，请稍候再试', { status: 429 });
  try {
    if (env.ASK_RL) {
      const { success } = await env.ASK_RL.limit({ key: ip });
      if (!success) return new Response('提问太频繁，请稍候再试', { status: 429 });
    }
  } catch { /* 忽略限流器故障，不阻断服务 */ }

  let q, history;
  try {
    const body = await request.json();
    q = String(body.q || '').trim().slice(0, 300);
    history = Array.isArray(body.history) ? body.history.slice(-6) : [];
  } catch { return new Response('Bad Request', { status: 400 }); }
  if (q.length < 2) return new Response('问题太短', { status: 400 });

  const sf = (path, body) => fetch(SF_BASE + path, {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.SILICONFLOW_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  // 1) 问题向量化
  const embRes = await sf('/embeddings', { model: 'BAAI/bge-m3', input: [q] });
  if (!embRes.ok) return new Response('检索服务暂不可用', { status: 502 });
  const vector = (await embRes.json()).data[0].embedding;

  // 2) 向量召回
  const found = await env.WENKU.query(vector, { topK: 20, returnValues: false, returnMetadata: 'all' });
  let matches = (found.matches || []).filter((m) => m.metadata?.t);
  if (!matches.length) return new Response('资料库尚未建立', { status: 503 });

  // 3) 重排取前 8
  try {
    const rrRes = await sf('/rerank', {
      model: 'BAAI/bge-reranker-v2-m3', query: q,
      documents: matches.map((m) => m.metadata.t), top_n: 8,
    });
    if (rrRes.ok) {
      const rr = await rrRes.json();
      matches = rr.results.map((r) => matches[r.index]);
    } else { matches = matches.slice(0, 8); }
  } catch { matches = matches.slice(0, 8); }

  // 4) 组装提示词
  const sources = matches.map((m, i) => ({
    n: i + 1, title: m.metadata.title, series: m.metadata.series,
    path: m.metadata.path, kind: m.metadata.kind,
  }));
  const context = matches.map((m, i) =>
    `【${i + 1}】《${m.metadata.series}》${m.metadata.title}\n${m.metadata.t}`).join('\n\n');

  const messages = [
    { role: 'system', content:
      '你是净土修学网站「佛悦」的问道助手。你的唯一职责是根据提供的大安法师讲经文字资料，忠实地回答提问。\n' +
      '规则：\n' +
      '一、只依据【资料】作答，忠于原文义理，不得自行发挥或杜撰法义；\n' +
      '二、引用资料时在句末标注编号，如 [1][3]；\n' +
      '三、资料不足以回答时，如实说明"文库中未找到直接开示"，可建议阅读相关篇目，不得强答；\n' +
      '四、你是检索助手，不是法师：不以说法者口吻自居，涉及重大修行抉择时提醒读者阅读原文、亲近善知识；\n' +
      '五、用平实庄重的白话回答，条理清晰，适当分段，不堆砌辞藻。' },
    ...history.filter((h) => h && (h.role === 'user' || h.role === 'assistant'))
      .map((h) => ({ role: h.role, content: String(h.content).slice(0, 800) })),
    { role: 'user', content: `【资料】\n${context}\n\n【问题】${q}` },
  ];

  // 5) 流式生成，转发为 SSE：先发 sources，再逐段发 delta
  const llmRes = await sf('/chat/completions', {
    model: env.SF_CHAT_MODEL, messages, stream: true,
    max_tokens: 2200, temperature: 0.3,
    enable_thinking: false, // 混合推理模型关闭思考，直接作答
  });
  if (!llmRes.ok || !llmRes.body) return new Response('生成服务暂不可用', { status: 502 });

  const encoder = new TextEncoder();
  const decoder = new TextDecoder();
  const stream = new ReadableStream({
    async start(controller) {
      const send = (event, data) =>
        controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
      send('sources', sources);
      const reader = llmRes.body.getReader();
      let buf = '';
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });
          const lines = buf.split('\n');
          buf = lines.pop();
          for (const line of lines) {
            if (!line.startsWith('data:')) continue;
            const payload = line.slice(5).trim();
            if (payload === '[DONE]') continue;
            try {
              const text = JSON.parse(payload).choices?.[0]?.delta?.content;
              if (text) send('delta', { text });
            } catch { /* 跳过不完整帧 */ }
          }
        }
      } catch { /* 上游中断 */ }
      send('done', {});
      controller.close();
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  });
}

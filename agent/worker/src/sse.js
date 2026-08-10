// SSE 帧编码 —— 与现行前端 app.js 解析器兼容（event: X\ndata: JSON\n\n 帧式，
// 未知事件被其自动忽略，故可安全扩展 mode/done 事件）

const enc = new TextEncoder();

export const frame = (event, data) => enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);

const BASE_HEADERS = {
  'Content-Type': 'text/event-stream; charset=utf-8',
  'Cache-Control': 'no-store',
};

/** 定长帧序列直接成响应（定本直出／目录／拒答等零生成路） */
export function fixedSse(frames, extraHeaders) {
  const body = new Uint8Array(frames.reduce((n, f) => n + f.length, 0));
  let o = 0;
  for (const f of frames) { body.set(f, o); o += f.length; }
  return new Response(body, { headers: { ...BASE_HEADERS, ...(extraHeaders || {}) } });
}

/** 流式响应（综述路） */
export const streamSse = (stream, extraHeaders) =>
  new Response(stream, { headers: { ...BASE_HEADERS, ...(extraHeaders || {}) } });

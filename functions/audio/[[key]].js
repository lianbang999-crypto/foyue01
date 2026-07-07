// Pages Function: /audio/<桶别名>/<key> 从对应 R2 桶流式提供音频（支持 Range 分段）

const BUCKETS = {
  daan: 'AUDIO_DAAN',           // 大安法师讲经
  yinguang: 'AUDIO_YINGUANG',   // 印光大师故事
  shengxian: 'AUDIO_SHENGXIAN', // 东林圣贤往生
  ysshu: 'AUDIO_YSSHU',         // 有声书（安士全书系 + 净土百问）
  fohao: 'AUDIO_FOHAO',         // 东林佛号
  dusong: 'AUDIO_DUSONG',       // 经典念诵
};

export async function onRequestGet(context) {
  const { request, env } = context;
  const url = new URL(request.url);

  let rest;
  try {
    rest = decodeURIComponent(url.pathname.slice('/audio/'.length));
  } catch {
    return new Response('Bad Request', { status: 400 });
  }
  const slash = rest.indexOf('/');
  if (slash < 1) return new Response('Bad Request', { status: 400 });
  const alias = rest.slice(0, slash);
  const key = rest.slice(slash + 1);
  const binding = BUCKETS[alias];
  if (!binding || !key || key.includes('..')) return new Response('Bad Request', { status: 400 });

  // 解析 Range 头（只支持单一区间，播放器均如此）
  const rangeHeader = request.headers.get('Range');
  let range; // 传给 R2 的 { offset, length } 或 { suffix }
  if (rangeHeader) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
    if (!m || (!m[1] && !m[2])) return new Response('Bad Range', { status: 416 });
    if (m[1]) {
      const start = Number(m[1]);
      range = m[2] ? { offset: start, length: Number(m[2]) - start + 1 } : { offset: start };
    } else {
      range = { suffix: Number(m[2]) };
    }
  }

  const object = await env[binding].get(key, range ? { range } : undefined);
  if (!object) return new Response('Not Found', { status: 404 });

  const headers = new Headers();
  headers.set('Content-Type', 'audio/mpeg');
  headers.set('Accept-Ranges', 'bytes');
  headers.set('ETag', object.httpEtag);
  // 音频文件不会变更，允许浏览器与 CDN 长缓存
  headers.set('Cache-Control', 'public, max-age=31536000, immutable');

  let status = 200;
  if (range && object.range) {
    const offset = object.range.offset ?? 0;
    const length = object.range.length ?? object.size - offset;
    headers.set('Content-Range', `bytes ${offset}-${offset + length - 1}/${object.size}`);
    headers.set('Content-Length', String(length));
    status = 206;
  } else {
    headers.set('Content-Length', String(object.size));
  }

  return new Response(request.method === 'HEAD' ? null : object.body, { status, headers });
}

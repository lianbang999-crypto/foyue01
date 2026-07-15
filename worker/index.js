// 佛乐 Worker：/audio/<桶别名>/<key> 从对应 R2 桶流式提供音频（支持 Range 分段），
// 其余请求交给静态资源（public/，含文库文本 /text/*）。与旧站基础设施完全独立。

const BUCKETS = {
  daan: 'AUDIO_DAAN',           // 大安法师讲经
  yinguang: 'AUDIO_YINGUANG',   // 印光大师故事
  shengxian: 'AUDIO_SHENGXIAN', // 东林圣贤往生
  ysshu: 'AUDIO_YSSHU',         // 有声书（安士全书系 + 净土百问）
  fohao: 'AUDIO_FOHAO',         // 东林佛号
  dusong: 'AUDIO_DUSONG',       // 经典念诵
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/audio/')) {
      return serveAudio(request, env, url);
    }
    if (url.pathname === '/api/ask') {
      return serveAsk(request, env, url);
    }
    if (url.pathname === '/api/tts') {
      return serveTts(request, env, ctx);
    }
    if (url.pathname === '/api/cc') {
      // 音频转文字（实时字幕）接口已关闭
      return new Response('字幕功能已关闭', { status: 404 });
    }
    if (url.pathname === '/api/i18n') {
      return serveI18n(request, env, ctx);
    }
    if (url.pathname === '/api/cmt') {
      return serveCmt(request, env);
    }
    if (url.pathname === '/api/like') {
      return serveLike(request, env);
    }
    if (url.pathname.startsWith('/api/admin/')) {
      return serveAdmin(request, env, url);
    }
    return env.ASSETS.fetch(request);
  },
};

/* ================= 文转音频（阅读器朗读） =================
   POST /api/tts {text} → 硅基流动 CosyVoice2 合成 mp3。
   Key 存 Worker Secret SF_TTS_KEY（前端零接触）；同一段文字经边缘缓存复用，不重复计费。 */

async function serveTts(request, env, ctx) {
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
  if (!env.SF_TTS_KEY) return new Response('朗读服务未配置', { status: 503 });
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  try {
    if (env.TTS_RL) {
      const { success } = await env.TTS_RL.limit({ key: ip });
      if (!success) return new Response('朗读请求太频繁，请稍候再试', { status: 429 });
    }
  } catch { /* 限流器故障不阻断 */ }

  let text;
  try {
    text = String((await request.json()).text || '').replace(/\s+/g, ' ').trim().slice(0, 600);
  } catch { return new Response('Bad Request', { status: 400 }); }
  if (text.length < 2) return new Response('Bad Request', { status: 400 });

  // 边缘缓存：按文本哈希取回已合成的音频
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('tts-v1:' + text));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  const cacheKey = new Request(`https://tts-cache.bojingtai.internal/${hex}`);
  const cached = await caches.default.match(cacheKey);
  if (cached) return cached;

  const sf = await fetch('https://api.siliconflow.cn/v1/audio/speech', {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.SF_TTS_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'FunAudioLLM/CosyVoice2-0.5B',
      voice: 'FunAudioLLM/CosyVoice2-0.5B:benjamin',   // 沉稳男声，宜读讲记
      input: text,
      response_format: 'mp3',
      speed: 1,
    }),
  });
  if (!sf.ok) return new Response('朗读服务暂不可用', { status: 502 });
  const buf = await sf.arrayBuffer();
  const headers = { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'public, max-age=604800' };
  if (ctx) ctx.waitUntil(caches.default.put(cacheKey, new Response(buf.slice(0), { headers })));
  return new Response(buf, { headers });
}

/* ================= 界面多语言（AI 翻译） =================
   POST /api/i18n {lang, texts[]} → { map: {原文: 译文} }
   免费小模型批量翻译界面字符串；逐条边缘缓存，同一字符串全网只翻一次。 */

const I18N_LANGS = { en: 'English', ja: 'Japanese' };

async function i18nCacheKey(lang, text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`i18n-v1:${lang}${text}`));
  const hex = [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, '0')).join('');
  return new Request(`https://i18n-cache.bojingtai.internal/${lang}/${hex}`);
}

async function serveI18n(request, env, ctx) {
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
  if (!env.SF_TTS_KEY) return new Response('翻译服务未配置', { status: 503 });

  let lang, texts;
  try {
    const body = await request.json();
    lang = String(body.lang || '');
    texts = (Array.isArray(body.texts) ? body.texts : [])
      .map((t) => String(t).trim()).filter((t) => t && t.length <= 300);
  } catch { return new Response('Bad Request', { status: 400 }); }
  if (!I18N_LANGS[lang] || !texts.length) return new Response('Bad Request', { status: 400 });
  texts = [...new Set(texts)].slice(0, 60);

  // 先取边缘缓存，只把没见过的送模型
  const map = {};
  const misses = [];
  for (const t of texts) {
    const hit = await caches.default.match(await i18nCacheKey(lang, t));
    if (hit) map[t] = await hit.text();
    else misses.push(t);
  }

  if (misses.length) {
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    try {
      if (env.I18N_RL) {
        const { success } = await env.I18N_RL.limit({ key: ip });
        if (!success) return new Response('翻译请求太频繁，请稍候再试', { status: 429 });
      }
    } catch { /* 限流器故障不阻断 */ }

    const sf = await fetch(SF_BASE + '/chat/completions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${env.SF_TTS_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: env.SF_I18N_MODEL || 'Qwen/Qwen3.5-9B',
        messages: [
          { role: 'system', content:
            `You translate UI strings of a Pure Land Buddhist audio app from Chinese to ${I18N_LANGS[lang]}.\n`
            + 'Rules: use standard Buddhist terminology (e.g. 南无阿弥陀佛 → Namo Amitabha); keep strings short like UI labels; '
            + 'preserve any numbers, punctuation style and placeholders; '
            + 'reply with ONLY a JSON array of translated strings, same length and order as the input array.' },
          { role: 'user', content: JSON.stringify(misses) },
        ],
        max_tokens: 2000, temperature: 0.2, enable_thinking: false,
      }),
    });
    if (!sf.ok) return new Response('翻译服务暂不可用', { status: 502 });
    let out = [];
    try {
      const raw = (await sf.json()).choices?.[0]?.message?.content || '';
      const m = raw.match(/\[[\s\S]*\]/);
      out = JSON.parse(m ? m[0] : raw);
    } catch { out = []; }
    if (Array.isArray(out) && out.length === misses.length) {
      for (let i = 0; i < misses.length; i++) {
        const v = String(out[i] ?? '').trim();
        if (!v) continue;
        map[misses[i]] = v;
        const key = await i18nCacheKey(lang, misses[i]);
        const res = new Response(v, { headers: { 'Cache-Control': 'public, max-age=2592000' } });
        if (ctx) ctx.waitUntil(caches.default.put(key, res));
      }
    }
  }

  return json({ map });
}

/* ================= 直播留言（同修在此） =================
   GET  /api/cmt?after=<id>  → { notice, items:[{id,name,text,ts}] } 增量轮询
   POST /api/cmt {dev,name,text,ep} → 频控 + 封禁 + 屏蔽词校验后入库（不预审，后台可删可封） */

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });

async function metaGet(env, k) {
  const row = await env.DB.prepare('SELECT v FROM meta WHERE k = ?').bind(k).first();
  return row ? row.v : '';
}

/* 同时在线人数：设备心跳 upsert + 时间窗内计数（真实统计，不虚增） */
const ONLINE_WINDOW = 75000;   // 在线判定窗（毫秒），略大于前端 30 秒轮询间隔
let onlineReady = false;
async function ensureOnline(env) {
  if (onlineReady) return;
  await env.DB.prepare('CREATE TABLE IF NOT EXISTS online (dev TEXT PRIMARY KEY, ts INTEGER NOT NULL)').run();
  onlineReady = true;
}
async function liveOnline(env, dev) {
  await ensureOnline(env);
  const now = Date.now();
  if (dev && /^[a-zA-Z0-9-]{8,40}$/.test(dev)) {
    await env.DB.prepare('INSERT INTO online (dev, ts) VALUES (?, ?) ON CONFLICT(dev) DO UPDATE SET ts = excluded.ts')
      .bind(dev, now).run();
  }
  if (Math.random() < 0.05) {   // 概率性清理过期心跳，防表无限增长
    await env.DB.prepare('DELETE FROM online WHERE ts < ?').bind(now - 600000).run();
  }
  const row = await env.DB.prepare('SELECT COUNT(*) n FROM online WHERE ts > ?').bind(now - ONLINE_WINDOW).first();
  return row ? row.n : 0;
}

async function serveCmt(request, env) {
  if (request.method === 'GET') {
    const params = new URL(request.url).searchParams;
    const ep = String(params.get('ep') || '').slice(0, 60);
    const me = String(params.get('dev') || '').trim();
    // mine：是否本设备的发言（聊天气泡靠右用）；只回布尔，不外泄任何设备标识
    const pack = (rows) => rows.map((r) => ({ id: r.id, name: r.name, text: r.text, ts: r.ts, mine: r.dev === me ? 1 : 0 }));
    if (ep) {   // 按集拉留言（播放器「闻法留言」抽屉用），最新在前
      const { results } = await env.DB.prepare(
        'SELECT id,dev,name,text,ts FROM comments WHERE ep = ? ORDER BY id DESC LIMIT 60').bind(ep).all();
      return json({ items: pack(results) });
    }
    const after = Number(params.get('after')) || 0;
    const online = await liveOnline(env, me);   // 顺带上报/统计在线心跳
    const { results } = after
      ? await env.DB.prepare('SELECT id,dev,name,text,ts FROM comments WHERE id > ? ORDER BY id ASC LIMIT 50').bind(after).all()
      : await env.DB.prepare('SELECT id,dev,name,text,ts FROM comments ORDER BY id DESC LIMIT 50').all();
    const items = pack(after ? results : results.reverse());
    return json({ notice: await metaGet(env, 'notice'), items, online });
  }
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });

  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  let dev, name, text, ep;
  try {
    const body = await request.json();
    dev = String(body.dev || '').trim();
    name = String(body.name || '').replace(/\s+/g, ' ').trim().slice(0, 14);
    text = String(body.text || '').replace(/\s+/g, ' ').trim();
    ep = String(body.ep || '').trim().slice(0, 60);
  } catch { return new Response('Bad Request', { status: 400 }); }
  if (!/^[a-zA-Z0-9-]{8,40}$/.test(dev) || name.length < 2) return new Response('Bad Request', { status: 400 });
  if (!text) return new Response('留言不能为空', { status: 400 });
  if (text.length > 150) return new Response('留言最长 150 字', { status: 400 });

  // 频控（本机设备 + IP 双键）
  try {
    if (env.CMT_RL) {
      const { success } = await env.CMT_RL.limit({ key: `${dev}:${ip}` });
      if (!success) return new Response('发言太频繁，请稍候再试', { status: 429 });
    }
  } catch { /* 限流器故障不阻断 */ }

  // 封禁校验
  const ban = await env.DB.prepare('SELECT dev FROM banned WHERE dev = ?').bind(dev).first();
  if (ban) return new Response('留言功能暂不可用', { status: 403 });

  // 屏蔽词（后台可维护，JSON 数组，子串匹配）
  try {
    const words = JSON.parse(await metaGet(env, 'badwords') || '[]');
    const hit = words.find((w) => w && text.includes(w));
    if (hit) return new Response('留言包含不合适的内容，请修改后再发', { status: 422 });
  } catch { /* 词表损坏时不拦截 */ }

  const r = await env.DB.prepare('INSERT INTO comments (dev,name,text,ep,ts) VALUES (?,?,?,?,?)')
    .bind(dev, name, text, ep, Date.now()).run();
  return json({ ok: true, id: r.meta.last_row_id });
}

/* ================= 随喜（功德点赞，按集计数） =================
   GET  /api/like?ep=<集>&dev=<设备>  → { count, liked }
   POST /api/like {ep,dev}            → 切换随喜（同设备同集只算一次），返回最新 { count, liked } */

let likesReady = false;
async function ensureLikes(env) {
  if (likesReady) return;
  await env.DB.prepare(
    'CREATE TABLE IF NOT EXISTS likes (ep TEXT NOT NULL, dev TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (ep, dev))'
  ).run();
  likesReady = true;
}
async function likeCount(env, ep) {
  const c = await env.DB.prepare('SELECT COUNT(*) n FROM likes WHERE ep = ?').bind(ep).first();
  return c ? c.n : 0;
}
async function serveLike(request, env) {
  await ensureLikes(env);
  if (request.method === 'GET') {
    const params = new URL(request.url).searchParams;
    const ep = String(params.get('ep') || '').slice(0, 60);
    const dev = String(params.get('dev') || '').trim();
    if (!ep) return json({ count: 0, liked: false });
    let liked = false;
    if (/^[a-zA-Z0-9-]{8,40}$/.test(dev)) {
      liked = !!(await env.DB.prepare('SELECT 1 FROM likes WHERE ep = ? AND dev = ?').bind(ep, dev).first());
    }
    return json({ count: await likeCount(env, ep), liked });
  }
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });

  let ep, dev;
  try {
    const body = await request.json();
    ep = String(body.ep || '').trim().slice(0, 60);
    dev = String(body.dev || '').trim();
  } catch { return new Response('Bad Request', { status: 400 }); }
  if (!ep || !/^[a-zA-Z0-9-]{8,40}$/.test(dev)) return new Response('Bad Request', { status: 400 });

  const mine = await env.DB.prepare('SELECT 1 FROM likes WHERE ep = ? AND dev = ?').bind(ep, dev).first();
  if (mine) await env.DB.prepare('DELETE FROM likes WHERE ep = ? AND dev = ?').bind(ep, dev).run();
  else await env.DB.prepare('INSERT INTO likes (ep, dev, ts) VALUES (?,?,?)').bind(ep, dev, Date.now()).run();
  return json({ count: await likeCount(env, ep), liked: !mine });
}

/* ================= 管理后台接口 =================
   鉴权：Authorization: Bearer <ADMIN_TOKEN>（Worker Secret）。
   /admin.html 静态管理页调用；覆盖留言删除、设备封禁、公告、屏蔽词。 */

async function serveAdmin(request, env, url) {
  if (!env.ADMIN_TOKEN) return new Response('后台未配置', { status: 503 });
  if (request.headers.get('Authorization') !== `Bearer ${env.ADMIN_TOKEN}`) {
    return new Response('Unauthorized', { status: 401 });
  }
  const path = url.pathname.slice('/api/admin/'.length);

  if (request.method === 'GET' && path === 'overview') {
    // 「今日」按北京时间起算（Worker 运行在 UTC）
    const now = Date.now();
    const dayStart = now - ((now + 8 * 3600000) % 86400000);
    const total = (await env.DB.prepare('SELECT COUNT(*) n FROM comments').first()).n;
    const today = (await env.DB.prepare('SELECT COUNT(*) n FROM comments WHERE ts >= ?').bind(dayStart).first()).n;
    const banned = (await env.DB.prepare('SELECT dev,ts FROM banned ORDER BY ts DESC').all()).results;
    let badwords = [];
    try { badwords = JSON.parse(await metaGet(env, 'badwords') || '[]'); } catch { /* 忽略 */ }
    return json({ total, today, banned, notice: await metaGet(env, 'notice'), badwords });
  }
  if (request.method === 'GET' && path === 'comments') {
    const { results } = await env.DB.prepare(
      'SELECT c.id,c.dev,c.name,c.text,c.ep,c.ts,(b.dev IS NOT NULL) banned FROM comments c LEFT JOIN banned b ON b.dev = c.dev ORDER BY c.id DESC LIMIT 200').all();
    return json({ items: results });
  }
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });

  let body;
  try { body = await request.json(); } catch { return new Response('Bad Request', { status: 400 }); }

  if (path === 'del') {
    const ids = (Array.isArray(body.ids) ? body.ids : [body.id]).map(Number).filter(Boolean);
    if (!ids.length) return new Response('Bad Request', { status: 400 });
    await env.DB.prepare(`DELETE FROM comments WHERE id IN (${ids.map(() => '?').join(',')})`).bind(...ids).run();
    return json({ ok: true, n: ids.length });
  }
  if (path === 'ban' || path === 'unban') {
    const dev = String(body.dev || '').trim();
    if (!dev) return new Response('Bad Request', { status: 400 });
    if (path === 'ban') await env.DB.prepare('INSERT OR REPLACE INTO banned (dev,ts) VALUES (?,?)').bind(dev, Date.now()).run();
    else await env.DB.prepare('DELETE FROM banned WHERE dev = ?').bind(dev).run();
    return json({ ok: true });
  }
  if (path === 'notice') {
    await env.DB.prepare('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)')
      .bind('notice', String(body.text || '').trim().slice(0, 200)).run();
    return json({ ok: true });
  }
  if (path === 'badwords') {
    const words = (Array.isArray(body.words) ? body.words : []).map((w) => String(w).trim()).filter(Boolean).slice(0, 200);
    await env.DB.prepare('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)').bind('badwords', JSON.stringify(words)).run();
    return json({ ok: true, n: words.length });
  }
  return new Response('Not Found', { status: 404 });
}

/* ================= 问道 RAG 接口 =================
   流程：问题向量化(bge-m3) → Vectorize 召回20 → bge-reranker 重排取8
       → 大模型依据资料流式作答（SSE），先发出处后发正文。
   Key 存于 Worker Secret，前端不接触硅基流动。 */

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

async function serveAsk(request, env, url) {
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });
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
    x: String(m.metadata.t || '').replace(/\s+/g, ' ').trim().slice(0, 160),   // 段落摘录（前端出处预览用）
  }));
  const context = matches.map((m, i) =>
    `【${i + 1}】《${m.metadata.series}》${m.metadata.title}\n${m.metadata.t}`).join('\n\n');

  const messages = [
    { role: 'system', content:
      '你是净土修学网站「佛乐」的问道助手。你的唯一职责是根据提供的大安法师讲经文字资料，忠实地回答提问。\n' +
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

async function serveAudio(request, env, url) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  }

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

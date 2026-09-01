// 佛乐 Worker：/audio/<桶别名>/<key> 从对应 R2 桶流式提供音频（支持 Range 分段），
// 其余请求交给静态资源（public/，含文库文本 /text/*）。与旧站基础设施完全独立。

import { SSR_PATH, serveSSR, serveSitemap } from './ssr.js';
import { serveCss } from './css.js';
import { serveLian, serveSync, serveGongxiu } from './lian.js';

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
      return serveAsk(request, env, url, ctx);
    }
    // 检索链路自检：部署后一眼看出各路是否真的接上了（尤其关键词索引建没建好）
    if (url.pathname === '/api/ask/health') {
      return serveAskHealth(env);
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
    if (url.pathname === '/api/translate') {
      return serveTranslate(request, env, ctx);
    }
    if (url.pathname === '/api/cmt') {
      // 跨域放行：game 等子站也能读写留言（预检开放 GET/POST，业务逻辑不动 serveCmt）
      if (request.method === 'OPTIONS') {
        const h = corsHeaders(request);
        if (h['Access-Control-Allow-Origin']) h['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS';
        return new Response(null, { status: 204, headers: h });
      }
      return withCors(await serveCmt(request, env), request);
    }
    if (url.pathname === '/api/report') {
      return serveReport(request, env);
    }
    if (url.pathname === '/api/askfb') {
      return serveAskFeedback(request, env);
    }
    if (url.pathname === '/api/like') {
      return serveLike(request, env);
    }
    if (url.pathname.startsWith('/api/admin/')) {
      return serveAdmin(request, env, url);
    }
    /* 校时：直播排播全靠客户端自己算「此刻该播哪一集」，本机时钟一偏，
       听到的就不是大众正在听的那一句，而人不自知。前端启动后问一次这里。
       回纯数字，十来个字节；务必不缓存，缓存过的时间就不是时间了。 */
    if (url.pathname === '/api/time') {
      return new Response(String(Date.now()), {
        headers: {
          'Content-Type': 'text/plain; charset=utf-8',
          'Cache-Control': 'no-store, must-revalidate',
          'Access-Control-Allow-Origin': '*',
        },
      });
    }
    // 莲号与功课同步（详见 worker/lian.js）
    if (url.pathname === '/api/lian') {
      return serveLian(request, env);
    }
    if (url.pathname === '/api/sync') {
      return serveSync(request, env);
    }
    if (url.pathname === '/api/gongxiu') {
      return serveGongxiu(request, env);
    }
    // 样式：源码按板块分文件，在边缘拼成一份下发（详见 worker/css.js）
    if (url.pathname === '/css/all.css') {
      return serveCss(request, env, url.origin);
    }
    // 讲记/问答/系列的真实路径：发带正文的可索引页面（详见 worker/ssr.js）
    if (SSR_PATH.test(url.pathname)) {
      return serveSSR(request, env, url);
    }
    // sitemap 按目录实时生成，收新内容时不必手工维护
    if (url.pathname === '/sitemap.xml') {
      return serveSitemap(env, url.origin);
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

/* ================= 正文 / 经典 多语言（整站翻译） =================
   POST /api/translate {lang, texts[]} → { map:{原文:译文}, model, note }
   与 /api/i18n 分工：i18n 走免费小模型只翻界面标签；本接口走 OpenRouter 高质量模型，
   翻讲记正文 / 经典 / 长段落，供 game·wenchao·foyue 三站「AI 翻译」按钮整站调用。
   铁律：机器翻译只作参考，中文原文为准；前端并列展示、可随时切回原文，绝不销毁原文。
   逐条 SHA-256 边缘缓存（正文稳定、命中率高，缓存 90 天）；跨站 CORS 放行 *.foyue.org。 */

const TRANS_LANGS = {
  en: 'English', ja: 'Japanese', ko: 'Korean', 'zh-Hant': 'Traditional Chinese',
  fr: 'French', de: 'German', es: 'Spanish', pt: 'Portuguese', it: 'Italian',
  ru: 'Russian', th: 'Thai', vi: 'Vietnamese', id: 'Indonesian', hi: 'Hindi', ar: 'Arabic',
};
const TRANS_NOTE = 'AI机器翻译，仅供参考，以中文原文为准';

// 只放行 foyue.org 及其子域（game / wenchao 等），外加本地开发
const ALLOW_ORIGIN = /^https?:\/\/([a-z0-9-]+\.)?foyue\.org$|^http:\/\/localhost(:\d+)?$/i;
function corsHeaders(request) {
  const origin = request.headers.get('Origin') || '';
  const h = { Vary: 'Origin' };
  if (ALLOW_ORIGIN.test(origin)) {
    h['Access-Control-Allow-Origin'] = origin;
    h['Access-Control-Allow-Methods'] = 'POST, OPTIONS';
    h['Access-Control-Allow-Headers'] = 'Content-Type';
    h['Access-Control-Max-Age'] = '86400';
  }
  return h;
}
const corsJson = (request, data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...corsHeaders(request) },
  });

// 给既有响应补跨域头：Origin 命中白名单时回显 + Vary（路由层套用，不动原处理函数逻辑）
function withCors(resp, request) {
  const origin = request.headers.get('Origin') || '';
  if (!ALLOW_ORIGIN.test(origin)) return resp;
  const out = new Response(resp.body, resp);   // 复制响应后改头（原响应头可能不可变）
  out.headers.set('Access-Control-Allow-Origin', origin);
  out.headers.append('Vary', 'Origin');
  return out;
}

async function transCacheKey(model, lang, text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`trans-v1:${model}:${lang}${text}`));
  const hex = [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, '0')).join('');
  return new Request(`https://trans-cache.bojingtai.internal/${lang}/${hex}`);
}

async function serveTranslate(request, env, ctx) {
  // CORS 预检
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders(request) });
  if (request.method !== 'POST') return corsJson(request, { error: 'Method Not Allowed' }, 405);
  if (!env.OPENROUTER_KEY) return corsJson(request, { error: '翻译服务未配置' }, 503);

  const model = env.OR_TRANSLATE_MODEL || 'deepseek/deepseek-chat';

  let lang, texts;
  try {
    const body = await request.json();
    lang = String(body.lang || '');
    texts = (Array.isArray(body.texts) ? body.texts : [])
      .map((t) => String(t).replace(/\s+/g, ' ').trim()).filter((t) => t && t.length <= 2000);
  } catch { return corsJson(request, { error: 'Bad Request' }, 400); }
  if (!TRANS_LANGS[lang] || !texts.length) return corsJson(request, { error: 'Bad Request' }, 400);
  texts = [...new Set(texts)].slice(0, 40);   // 每批最多 40 段，前端自行分批

  // 先取边缘缓存，只把没见过的送模型
  const map = {};
  const misses = [];
  for (const t of texts) {
    const hit = await caches.default.match(await transCacheKey(model, lang, t));
    if (hit) map[t] = await hit.text();
    else misses.push(t);
  }

  if (misses.length) {
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    try {
      if (env.TRANS_RL) {
        const { success } = await env.TRANS_RL.limit({ key: ip });
        if (!success) return corsJson(request, { error: '翻译请求太频繁，请稍候再试' }, 429);
      }
    } catch { /* 限流器故障不阻断 */ }

    const or = await fetch('https://openrouter.ai/api/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${env.OPENROUTER_KEY}`,
        'Content-Type': 'application/json',
        'HTTP-Referer': 'https://foyue.org',
        'X-Title': 'Foyue Buddhist Translator',
      },
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content:
            'You are a professional translator of Chinese Pure Land Buddhist teachings and scriptures. '
            + `Translate each Chinese passage into ${TRANS_LANGS[lang]}.\n`
            + 'Rules:\n'
            + '- Use established Buddhist terminology (南无阿弥陀佛 → Namo Amitābha; 净土 → Pure Land; 往生 → rebirth in the Pure Land; 娑婆 → Sahā world).\n'
            + '- Be faithful and accurate. Do NOT add, omit, summarize, or reinterpret. This is a reference rendering of sacred/teaching material — fidelity over embellishment.\n'
            + '- Preserve paragraph meaning, numbers, proper names of masters and sutras (add romanization where helpful), and target-language punctuation.\n'
            + '- Reply with ONLY a JSON array of translated strings, same length and order as the input array. No commentary, no markdown.' },
          { role: 'user', content: JSON.stringify(misses) },
        ],
        temperature: 0.2, max_tokens: 4000,
      }),
    });
    if (!or.ok) return corsJson(request, { error: '翻译服务暂不可用' }, 502);
    let out = [];
    try {
      const raw = (await or.json()).choices?.[0]?.message?.content || '';
      const m = raw.match(/\[[\s\S]*\]/);
      out = JSON.parse(m ? m[0] : raw);
    } catch { out = []; }
    if (Array.isArray(out) && out.length === misses.length) {
      for (let i = 0; i < misses.length; i++) {
        const v = String(out[i] ?? '').trim();
        if (!v) continue;
        map[misses[i]] = v;
        const key = await transCacheKey(model, lang, misses[i]);
        const res = new Response(v, { headers: { 'Cache-Control': 'public, max-age=7776000' } }); // 90 天
        if (ctx) ctx.waitUntil(caches.default.put(key, res));
      }
    }
  }

  return corsJson(request, { map, model, note: TRANS_NOTE });
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
async function metaSet(env, k, v) {
  await env.DB.prepare('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)').bind(k, String(v)).run();
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

/* ================= 报错 / 纠错上报 =================
   POST /api/report {dev,site,kind,target,text,contact} → { ok, id }
   主站与游戏站共用（CORS 放行 *.foyue.org），后台 admin 统一处理。 */

async function serveReport(request, env) {
  // CORS 预检
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders(request) });
  if (request.method !== 'POST') return corsJson(request, { error: 'Method Not Allowed' }, 405);

  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  let dev, site, kind, target, text, contact;
  try {
    const body = await request.json();
    dev = String(body.dev || '').trim();
    site = String(body.site || '').trim().slice(0, 20);
    kind = String(body.kind || '').replace(/\s+/g, ' ').trim().slice(0, 20);
    target = String(body.target || '').replace(/\s+/g, ' ').trim().slice(0, 300);
    text = String(body.text || '').replace(/\s+/g, ' ').trim();
    contact = String(body.contact || '').replace(/\s+/g, ' ').trim().slice(0, 100);
  } catch { return corsJson(request, { error: 'Bad Request' }, 400); }
  if (!/^[a-zA-Z0-9-]{8,40}$/.test(dev)) return corsJson(request, { error: 'Bad Request' }, 400);
  if (site !== 'game' && site !== 'foyue') site = '';   // 来源只认两站，其余归空
  if (!text) return corsJson(request, { error: '请填写问题描述' }, 400);
  if (text.length > 300) return corsJson(request, { error: '描述最长 300 字' }, 400);
  const ua = (request.headers.get('User-Agent') || '').slice(0, 200);

  // 频控：REPORT_RL 优先，缺省退用留言限流 CMT_RL；限流器故障不阻断
  try {
    const rl = env.REPORT_RL || env.CMT_RL;
    if (rl) {
      const { success } = await rl.limit({ key: `${dev}:${ip}` });
      if (!success) return corsJson(request, { error: '提交太频繁，请稍候再试' }, 429);
    }
  } catch { /* 限流器故障不阻断 */ }

  // 封禁校验（与留言共用 banned 表）
  const ban = await env.DB.prepare('SELECT dev FROM banned WHERE dev = ?').bind(dev).first();
  if (ban) return corsJson(request, { error: '上报功能暂不可用' }, 403);

  const r = await env.DB.prepare(
    'INSERT INTO reports (dev,site,kind,target,text,contact,ua,status,ts) VALUES (?,?,?,?,?,?,?,?,?)')
    .bind(dev, site, kind, target, text, contact, ua, 'open', Date.now()).run();
  return corsJson(request, { ok: true, id: r.meta.last_row_id });
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

  /* 口令暴力破解防护：按来源 IP 记失败次数，5 次锁 10 分钟。
     注意先验口令再判锁：口令正确一律放行，免得管理员输错几次把自己关在门外；
     攻击者没有正确口令，照样被锁，防护不因此打折。 */
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const failKey = 'admfail:' + ip;
  const lockKey = 'admlock:' + ip;
  if (request.headers.get('Authorization') === `Bearer ${env.ADMIN_TOKEN}`) {
    if (await metaGet(env, failKey)) await metaSet(env, failKey, '0');
    if (await metaGet(env, lockKey)) await metaSet(env, lockKey, '0');
  } else {
    const lock = Number(await metaGet(env, lockKey) || 0);
    if (lock > Date.now()) {
      return new Response(`尝试过多，请 ${Math.ceil((lock - Date.now()) / 60000)} 分钟后再试`, { status: 429 });
    }
    const fails = Number(await metaGet(env, failKey) || 0) + 1;
    if (fails >= 5) {
      await metaSet(env, lockKey, String(Date.now() + 10 * 60 * 1000));
      await metaSet(env, failKey, '0');
    } else {
      await metaSet(env, failKey, String(fails));
    }
    return new Response('Unauthorized', { status: 401 });
  }
  const path = url.pathname.slice('/api/admin/'.length);

  // 操作日志：谁在何时改了什么（读接口不记）
  const audit = async (action, detail) => {
    try {
      await env.DB.prepare('INSERT INTO admin_log (ts, ip, action, detail) VALUES (?,?,?,?)')
        .bind(Date.now(), ip, action, String(detail || '').slice(0, 200)).run();
    } catch { /* 日志失败不影响主流程 */ }
  };

  /* 灌全文索引。scripts/push-kb.py 分批 POST 过来，服务端现算 bigram 再写 D1。
     为什么不在本地生成 SQL 再 wrangler d1 execute：实测每次调用光启动就 40 秒，
     且 390KB 的文件就开始超时 —— 5 万行要跑五个多小时，且一路在断。
     经这个接口走一条连接批量推，几分钟的事。
     bigram 在服务端算还有一层好处：与查询时用的是同一个 cjkBigrams，
     两边永远不可能切法不一致 —— 那种不一致不报错、只是「搜不到」，最难查。 */
  if (request.method === 'POST' && path === 'kbindex') {
    if (!env.KB) return json({ error: 'KB 未绑定' }, 503);
    const reset = url.searchParams.get('reset') === '1';
    try {
      if (reset) {
        await env.KB.exec('DROP TABLE IF EXISTS chunks_fts');
        await env.KB.exec('CREATE VIRTUAL TABLE chunks_fts USING fts5('
          + 'bigrams, cid UNINDEXED, text UNINDEXED, title UNINDEXED, '
          + 'series UNINDEXED, path UNINDEXED, kind UNINDEXED)');
        await audit('kbindex-reset', '');
        return json({ ok: true, reset: true });
      }
      const rows = (await request.json()).rows;
      if (!Array.isArray(rows) || !rows.length) return json({ error: 'Bad Request' }, 400);
      const stmt = env.KB.prepare(
        'INSERT INTO chunks_fts(bigrams,cid,text,title,series,path,kind) VALUES (?,?,?,?,?,?,?)');
      await env.KB.batch(rows.map((r) => stmt.bind(
        cjkBigrams(r.text || '').join(' '),
        String(r.cid || ''), String(r.text || ''), String(r.title || ''),
        String(r.series || ''), String(r.path || ''), String(r.kind || ''))));
      return json({ ok: true, n: rows.length });
    } catch (e) {
      return json({ error: String(e && e.message || e).slice(0, 200) }, 500);
    }
  }

  /* 问道反馈的查看与处理不在这里 —— 控制台（foyue-admin）与本站同 zone，
     直接绑了 bojingtai-cmt 读 ask_feedback，不必绕一道跨站接口。
     写入仍在本站：见 serveAskFeedback（页面上的赞踩）。 */

  if (request.method === 'GET' && path === 'audit') {
    try {
      const { results } = await env.DB.prepare(
        'SELECT ts, ip, action, detail FROM admin_log ORDER BY id DESC LIMIT 100').all();
      return json({ items: results });
    } catch { return json({ items: [] }); }
  }

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
  if (request.method === 'GET' && path === 'reports') {
    // 报错/纠错列表：?status=open|done|ignored 过滤，缺省全部；openCount 供概览与标题计数
    const st = String(url.searchParams.get('status') || '');
    const { results } = ['open', 'done', 'ignored'].includes(st)
      ? await env.DB.prepare('SELECT id,dev,site,kind,target,text,contact,ua,status,ts FROM reports WHERE status = ? ORDER BY id DESC LIMIT 200').bind(st).all()
      : await env.DB.prepare('SELECT id,dev,site,kind,target,text,contact,ua,status,ts FROM reports ORDER BY id DESC LIMIT 200').all();
    const openCount = (await env.DB.prepare("SELECT COUNT(*) n FROM reports WHERE status = 'open'").first()).n;
    return json({ items: results, openCount });
  }
  if (request.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });

  let body;
  try { body = await request.json(); } catch { return new Response('Bad Request', { status: 400 }); }

  if (path === 'del') {
    const ids = (Array.isArray(body.ids) ? body.ids : [body.id]).map(Number).filter(Boolean);
    if (!ids.length) return new Response('Bad Request', { status: 400 });
    await env.DB.prepare(`DELETE FROM comments WHERE id IN (${ids.map(() => '?').join(',')})`).bind(...ids).run();
    await audit('删除留言', 'id=' + ids.join(','));
    return json({ ok: true, n: ids.length });
  }
  if (path === 'ban' || path === 'unban') {
    const dev = String(body.dev || '').trim();
    if (!dev) return new Response('Bad Request', { status: 400 });
    if (path === 'ban') await env.DB.prepare('INSERT OR REPLACE INTO banned (dev,ts) VALUES (?,?)').bind(dev, Date.now()).run();
    else await env.DB.prepare('DELETE FROM banned WHERE dev = ?').bind(dev).run();
    await audit(path === 'ban' ? '封禁设备' : '解封设备', dev);
    return json({ ok: true });
  }
  if (path === 'notice') {
    await env.DB.prepare('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)')
      .bind('notice', String(body.text || '').trim().slice(0, 200)).run();
    await audit('改公告', String(body.text || '').slice(0, 60) || '（撤下）');
    return json({ ok: true });
  }
  if (path === 'reports/mark') {
    // 报错处理状态流转：open（待处理）/ done（已办）/ ignored（忽略）
    const id = Number(body.id);
    const status = String(body.status || '');
    if (!id || !['open', 'done', 'ignored'].includes(status)) return new Response('Bad Request', { status: 400 });
    await env.DB.prepare('UPDATE reports SET status = ? WHERE id = ?').bind(status, id).run();
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

   流程（每一步都 best-effort：任一步出事都退回更笨但可用的做法，不让问道整个哑掉）：

     追问改写 → 改写检索式+关键名相 → 【向量召回 ∥ 关键词召回】→ RRF 融合
     → 去重 → 重排取 8 → 取回父段落 → 依资料流式作答(SSE) → 引用逐字自检

   两处与「只用向量」的老做法相比要紧的：

   · **关键词召回**。「戒杀」「十念记数」这类短名相在长句里语义占比太小，
     纯向量常常召不回；D1 那份重叠二元索引专治这个。两路各有所长，故用 RRF 融合，
     而不是谁压倒谁。

   · **小块检索、大块喂入**。命中的是 700 字的小块，但喂给模型的是它在原文里
     所在的那一大段（PARENT_CHARS）—— 切块难免把一句话拦腰截断，只喂小块，
     模型看到的就是半句话。父段落不进索引，查询时按 path 从 ASSETS 现取。
     引用卡片仍用精确小块，便于逐字核对。

   Key 存于 Worker Secret，前端不接触硅基流动。 */

const SF_BASE = 'https://api.siliconflow.cn/v1';

const TOP_K = 8;                 // 最终喂给模型的段数
const VEC_TOPK = 24;             // 单路向量召回上限（放宽些，留给去重与重排挑）
const LEX_TOPK = 30;             // 关键词召回上限
const RERANK_POOL = 16;          // 送进重排的候选上限
const RRF_K = 60;                // RRF 融合常数：越大越平滑，弱化各路头部的绝对名次
const PARENT_CHARS = 1100;       // 父段落喂入的字数上限
const USE_CONDENSE = true;       // 多轮追问改写
const USE_QUERY_REWRITE = true;  // 改写检索式 + 抽关键名相
const USE_HYBRID = true;         // 关键词召回（缺 KB 绑定时自动退回纯向量）
const USE_RERANK = true;         // 交叉编码器重排
/* 相关度下限（重排分 0~1）：低于此数视同「文库里确实没有」，交给护栏拒答。
   非有不可 —— 向量检索对再离谱的问题也会返回最近的八条（最近邻不管多远都是邻居），
   「零命中」那道护栏因此永远够不着，问「今天天气」也会拿八段经论硬凑一篇。

   0.1 这个数是量出来的，不是拍的（2026-09-01 实测 top1 分）：
     在题   十念记数 0.998 · 信愿行 0.998 · 临终助念 0.996
     边缘   劝父母信佛 0.979 · 梦见亡亲 0.993
     离题   写冒泡排序 0.012 · 荐股 0.005 · 订机票 0.0015 · 手机选型 0.0003
   两头差两个数量级，0.1 落在正中，离题最高值的 8.5 倍、在题最低值的十分之一。
   往上调要当心：错拒一个真心求法的问题，比多答一句更糟。 */
const RERANK_MIN = 0.1;
const ANSWER_CHARS = 700;        // 回复字数上限（软引导）
const ASK_CACHE_TTL = 7 * 86400; // 答案缓存 7 天
// 检索/生成版本号，并入答案缓存键。**改了下面任何一处检索或提示词逻辑，都要把它加一** ——
// 否则旧缓存会把新逻辑遮住，改了半天线上没动静，最难查。
const RETRIEVAL_VERSION = 'r2';   // r2：加相关度下限 RERANK_MIN，离题问题改为直接拒答

/** 硅基流动 POST。timeout 到点即断——问道是流式的，某一步慢住会让人对着空屏干等。 */
function sfPost(env, path, body, timeoutMs) {
  const opts = {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.SILICONFLOW_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
  if (timeoutMs && typeof AbortSignal !== 'undefined' && AbortSignal.timeout) {
    opts.signal = AbortSignal.timeout(timeoutMs);
  }
  return fetch(SF_BASE + path, opts);
}

/** 中文切重叠二元，英数整词保留，单字兜底。
 *  **必须与 scripts/build-index.py 的 cjk_bigrams 逐字对应** —— 建库怎么切，查询就得怎么切；
 *  两边错一点点就整路召不回，而且不报错、只是「搜不到」，是最难查的那种。 */
function cjkBigrams(s) {
  const out = [];
  const tokens = String(s || '').replace(/[^\p{Script=Han}\p{L}\p{N}]+/gu, ' ').trim().split(/\s+/);
  for (const tk of tokens) {
    if (!tk) continue;
    if (!/\p{Script=Han}/u.test(tk)) { out.push(tk.toLowerCase()); continue; }
    const chars = [...tk];
    if (chars.length === 1) { out.push(chars[0]); continue; }
    for (let i = 0; i < chars.length - 1; i++) out.push(chars[i] + chars[i + 1]);
  }
  return out;
}

/* 不靠大模型的关键词兜底：去掉疑问词与虚词，留下 2 字以上的片段。
   抽词失败时仍能给关键词召回喂上名相。 */
const STOP_RE = /如何|怎[么样办]|为什么|什么|哪[些个]|是否|可以|应该|需要|这样|那样|时候|意思|请问|我们|关于|以及|还有|或者|的话|一下|呢|吗|了|啊|呀|吧|和|与|及|在|对|把|被|给|让|向|往|从/g;
function naiveTerms(q) {
  const segs = String(q || '')
    .replace(/[^\p{Script=Han}\p{L}\p{N}]+/gu, ' ')
    .replace(STOP_RE, ' ')
    .split(/\s+/)
    .map((s) => s.trim())
    .filter((s) => [...s].length >= 2);
  return [...new Set(segs)].slice(0, 6);
}

/** 一次调用同时产出「贴近讲记语体的改写检索式」与「2~5 个关键名相」。
 *  失败退回原问 + 启发式关键词，绝不阻塞问答。 */
async function buildRetrieval(env, q) {
  const result = { queries: [q], terms: naiveTerms(q) };
  if (!USE_QUERY_REWRITE || !env.SILICONFLOW_API_KEY) return result;
  try {
    const r = await sfPost(env, '/chat/completions', {
      model: env.SF_I18N_MODEL || env.SF_CHAT_MODEL,
      messages: [
        { role: 'system', content: '你是净土讲经文库的检索助手。读用户问题后只输出一行 JSON：{"q":"改写后的检索式","kw":["名相1","名相2"]}。其中 q 是把口语问题改写成更贴近讲经语体、突出关键名相的检索式（30字内）；kw 是 2~5 个最关键的名相/术语（如「信愿行」「十念记数」「带业往生」「都摄六根」）。不要解释，不要代码块，只输出该 JSON。' },
        { role: 'user', content: q },
      ],
      temperature: 0, max_tokens: 120, stream: false, enable_thinking: false,
    }, 4500);
    if (r.ok) {
      const raw = ((await r.json()).choices?.[0]?.message?.content || '').trim();
      const mt = raw.match(/\{[\s\S]*\}/);   // 容错：剥掉可能的代码块前后缀，取第一段 JSON
      if (mt) {
        const o = JSON.parse(mt[0]);
        const rw = String(o.q || '').replace(/^["“”\s]+|["“”\s]+$/g, '').trim();
        if (rw && rw !== q && rw.length <= 60) result.queries.push(rw);
        if (Array.isArray(o.kw)) {
          const kws = o.kw.map((s) => String(s || '').trim()).filter((s) => [...s].length >= 2);
          if (kws.length) result.terms = [...new Set(kws)].slice(0, 6);
        }
      }
    }
  } catch { /* 改写失败：原问 + 启发式关键词 */ }
  return result;
}

/** 多轮追问改写：把末句里的「它」「那这个」补成可独立检索的完整问题。
 *  没有历史或失败时退回启发式（短问或明显承上时并入上一问）。 */
const FOLLOWUP_RE = /它|他|她|这|那|上(面|述|文)|继续|再|还有|为什么|怎[么样]|出处|展开|具体|详细|例子|呢[？?]?$/;
async function condenseQuestion(env, history, lastU) {
  const prevU = [...history].reverse().find((h) => h.role === 'user')?.content || '';
  const heuristic = (prevU && (lastU.length < 12 || FOLLOWUP_RE.test(lastU)))
    ? `${prevU}。${lastU}` : lastU;
  if (!USE_CONDENSE || !env.SILICONFLOW_API_KEY || !prevU) return heuristic;
  try {
    const hist = history.slice(-5)
      .map((m) => (m.role === 'user' ? '用户：' : '助手：') + String(m.content).slice(0, 200)).join('\n')
      + `\n用户：${lastU}`;
    const r = await sfPost(env, '/chat/completions', {
      model: env.SF_I18N_MODEL || env.SF_CHAT_MODEL,
      messages: [
        { role: 'system', content: '你是检索助手。根据对话历史，把用户最后一句可能含指代或省略的追问，改写成一句可独立用于检索的完整问题（补全主语与话题、保留原意）；若末句本身已完整，原样输出。只输出这句问题，不解释、不加引号，40字以内。' },
        { role: 'user', content: hist },
      ],
      temperature: 0, max_tokens: 80, stream: false, enable_thinking: false,
    }, 4500);
    if (r.ok) {
      const rw = ((await r.json()).choices?.[0]?.message?.content || '')
        .replace(/^["“”\s]+|["“”\s]+$/g, '').trim();
      if (rw && rw.length >= 4 && rw.length <= 80) return rw;
    }
  } catch { /* 退回启发式 */ }
  return heuristic;
}

/** D1 关键词召回。关键词逐个切成 bigram 短语，OR 组合后 MATCH。
 *  返回与向量候选同构的 match（metadata.t 对齐向量库的字段名），供 RRF 融合。 */
async function lexicalSearch(env, terms) {
  if (!USE_HYBRID || !env.KB || !terms?.length) return [];
  const exprs = [];
  for (const t of terms) {
    const bg = cjkBigrams(t);
    if (bg.length) exprs.push('("' + bg.join(' ') + '")');   // bigram 里已无引号等特殊字符，可安全包成短语
  }
  if (!exprs.length) return [];
  try {
    const rs = await env.KB.prepare(
      'SELECT cid,text,title,series,path,kind FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY rank LIMIT ?'
    ).bind(exprs.join(' OR '), LEX_TOPK).all();
    return (rs?.results || []).map((row) => ({
      id: row.cid,
      metadata: {
        t: row.text || '', title: row.title || '', series: row.series || '',
        path: row.path || '', kind: row.kind || '',
      },
    }));
  } catch { return []; }   // FTS 语法或连接异常：退回纯向量
}

/** 多路向量召回并集，同 id 保留最高分，**并按分数排好序**。
 *  排序不是可选的：RRF 拿数组下标当名次，两路合过来若还是各自的插入顺序，
 *  算出来的名次就是错的，而且看不出错 —— 融合照跑，只是结果没道理。 */
function mergeMatchPools(pools) {
  const byId = new Map();
  for (const pool of pools) {
    for (const m of pool || []) {
      const prev = byId.get(m.id);
      if (!prev || (m.score || 0) > (prev.score || 0)) byId.set(m.id, m);
    }
  }
  return [...byId.values()].sort((a, b) => (b.score || 0) - (a.score || 0));
}

/** 倒数排名融合：两路各按自己的名次给分，谁在两路都靠前，谁就真靠前。 */
function fuseRRF(pools) {
  const acc = new Map();
  for (const pool of pools) {
    (pool || []).forEach((m, i) => {
      const prev = acc.get(m.id);
      const s = 1 / (RRF_K + i + 1);
      if (!prev) acc.set(m.id, { m, s });
      else {
        prev.s += s;
        // 关键词那一路的 metadata 更全（向量路某些字段可能缺），谁有正文用谁
        if (!prev.m.metadata?.t && m.metadata?.t) prev.m = m;
      }
    });
  }
  return [...acc.values()].map((e) => ({ ...e.m, score: e.s })).sort((a, b) => b.score - a.score);
}

/** 去重：讲记与问答常有同一段开示重出，只留一条。 */
function dedupeMatches(matches) {
  const seen = new Map();
  for (const m of matches) {
    const t = String(m.metadata?.t || '');
    if (!t) continue;
    const key = t.replace(/[\s，。、；：！？「」『』“”"'‘’（）()【】·—\-…]/g, '').slice(0, 60);
    if (!key) continue;
    const prev = seen.get(key);
    if (!prev || (m.score || 0) > (prev.score || 0)) seen.set(key, m);
  }
  return [...seen.values()].sort((a, b) => (b.score || 0) - (a.score || 0)).slice(0, RERANK_POOL);
}

/** 交叉编码器重排：只改顺序，不丢段。失败保持原序。 */
async function rerankMatches(env, query, matches) {
  if (!USE_RERANK || !env.SILICONFLOW_API_KEY || matches.length <= 1) return matches;
  const pool = matches.slice(0, RERANK_POOL);
  try {
    const r = await sfPost(env, '/rerank', {
      model: 'BAAI/bge-reranker-v2-m3', query,
      documents: pool.map((m) => m.metadata?.t || ''),
      top_n: pool.length, return_documents: false,
    }, 20000);
    const ranked = r.ok ? (await r.json()).results : null;
    if (Array.isArray(ranked) && ranked.length) {
      const ordered = [], used = new Set();
      for (const it of ranked) {
        const i = typeof it.index === 'number' ? it.index : -1;
        if (i >= 0 && i < pool.length && !used.has(i)) {
          used.add(i);
          // 留下相关度分数：向量检索对再离谱的问题也会返回最近的八条，
          // 「零命中」那道护栏因此永远够不着。真正判得出「文库里确实没有」的，
          // 只有交叉编码器给的这个分。
          if (typeof it.relevance_score === 'number') pool[i].rerankScore = it.relevance_score;
          ordered.push(pool[i]);
        }
      }
      pool.forEach((m, i) => { if (!used.has(i)) ordered.push(m); });   // 补回未覆盖的，保证不丢段
      if (ordered.length) return ordered;
    }
  } catch { /* 重排失败：保持融合序 */ }
  return pool;
}

/** 取回每一段所在的父段落。同一篇只取一次；取不到就退回小块本身。 */
async function expandParents(env, origin, matches) {
  const byPath = new Map();
  const paths = [...new Set(matches.map((m) => m.metadata?.path).filter(Boolean))];
  await Promise.all(paths.map(async (p) => {
    try {
      const enc = p.split('/').map(encodeURIComponent).join('/');
      const r = await env.ASSETS.fetch(new Request(`${origin}/text/${enc}`));
      // 与 build-index.py 切块前同一套规范化，否则下面 indexOf 找不着
      if (r.ok) byPath.set(p, (await r.text()).replace(/\n+/g, '\n').trim());
    } catch { /* 取不到：这一段退回小块 */ }
  }));
  return matches.map((m) => {
    const chunk = m.metadata?.t || '';
    const full = byPath.get(m.metadata?.path);
    if (!full || !chunk) return chunk;
    const i = full.indexOf(chunk);
    if (i < 0) return chunk;    // 文库重建过、块与正文对不上：老实用小块，不硬凑
    const pad = Math.max(0, PARENT_CHARS - chunk.length) >> 1;
    let s = Math.max(0, i - pad);
    let e = Math.min(full.length, i + chunk.length + pad);
    // 往段落边界靠：从半句话开始读，不如从整段开始读
    const ns = full.lastIndexOf('\n', s);
    if (ns >= 0 && s - ns < 200) s = ns + 1;
    const ne = full.indexOf('\n', e);
    if (ne >= 0 && ne - e < 200) e = ne;
    return full.slice(s, e);
  });
}

/** 引用逐字自检：纯字符串校验，只作信号，**不改写已经流式发出去的内容**。
 *  查两件事：[n] 有没有越出资料范围；加引号的直引能不能在所标那一段里逐字找到。
 *  比的是「模型真正看到的父段落」，不是小块 —— 否则模型引了父段落里的话会被误判为编造。 */
function normForMatch(s) {
  return String(s || '').replace(/[\s，。、；：！？「」『』“”"'‘’（）()【】\[\]·—\-…\n]/g, '');
}
function validateCitations(reply, ctxTexts) {
  const text = String(reply || '');
  const N = ctxTexts.length;
  const nums = [...text.matchAll(/\[(\d{1,2})\]/g)].map((m) => +m[1]);
  const invalid = nums.filter((n) => n < 1 || n > N).length;
  let quoteChecked = 0, quoteOk = 0;
  const qre = /[「“"]([^」”"\n]{2,40})[」”"]\s*\[(\d{1,2})\]/g;
  let mm;
  while ((mm = qre.exec(text))) {
    quoteChecked++;
    const n = +mm[2];
    if (n < 1 || n > N) continue;
    if (normForMatch(ctxTexts[n - 1]).includes(normForMatch(mm[1]))) quoteOk++;
  }
  return {
    cited: nums.length, invalid, quoteChecked, quoteOk,
    faithful: invalid === 0 && (quoteChecked === 0 || quoteOk === quoteChecked),
  };
}

/** 答案缓存键。照 serveTts 那套 caches.default + 合成 key，不另开 KV。 */
async function askCacheKey(q) {
  const digest = await crypto.subtle.digest(
    'SHA-256', new TextEncoder().encode(`ask-${RETRIEVAL_VERSION}:${q}`));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return new Request(`https://ask-cache.bojingtai.internal/${hex}`);
}

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

async function serveAsk(request, env, url, ctx) {
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

  const hist = history.filter((h) => h && (h.role === 'user' || h.role === 'assistant'))
    .map((h) => ({ role: h.role, content: String(h.content).slice(0, 800) }));

  /* ── 1) 追问改写：先把「那它和…有什么区别」补成一句能独立检索的问题 ──
     检索用改写后的，喂给模型的仍是用户原话 —— 人问的是什么，答的就该是什么。 */
  const retrievalQ = hist.length ? await condenseQuestion(env, hist, q) : q;

  /* ── 2) 缓存：只缓存首轮 ──
     追问的答案依赖上下文，同一句话在不同对话里该答得不一样，缓存了就串味。
     首轮才是热门重复问题的所在，收益也都在这儿。 */
  const cacheable = !hist.length;
  const ckey = cacheable ? await askCacheKey(retrievalQ) : null;
  if (ckey) {
    const hit = await caches.default.match(ckey);
    if (hit) return hit;
  }

  /* ── 3) 两路召回 → 融合 → 去重 → 重排 ── */
  let matches = [];
  let retrievalErrored = false;
  try {
    const { queries, terms } = await buildRetrieval(env, retrievalQ);
    const [embRes, lex] = await Promise.all([
      sfPost(env, '/embeddings', { model: 'BAAI/bge-m3', input: queries }, 15000),
      lexicalSearch(env, terms),                     // 关键词那一路与向量化并行，不叠加延迟
    ]);
    if (!embRes.ok) throw new Error('embed ' + embRes.status);
    const vecs = (await embRes.json()).data
      .sort((a, b) => a.index - b.index).map((d) => d.embedding);
    // 单路查询失败不要紧（另一路还在），但**全都失败就是检索坏了**，必须往上抛。
    // 若在此把错吞成空结果，下面的护栏会把它当成「文库里没有」——
    // 那等于替法师否认他讲过某事，是这一整套里最不能出的错。
    let vecFailed = 0;
    const pools = await Promise.all(vecs.map((v) =>
      env.WENKU.query(v, { topK: VEC_TOPK, returnValues: false, returnMetadata: 'all' })
        .then((r) => (r.matches || []).filter((m) => m.metadata?.t))
        .catch(() => { vecFailed++; return []; })));
    if (vecFailed === vecs.length) throw new Error('vectorize 全部失败');
    const vecMerged = mergeMatchPools(pools);
    matches = (USE_HYBRID && lex.length) ? fuseRRF([vecMerged, lex]) : vecMerged;
  } catch {
    retrievalErrored = true;   // 多半是嵌入服务不可用或额度受限
  }
  matches = dedupeMatches(matches);
  matches = (await rerankMatches(env, retrievalQ, matches)).slice(0, TOP_K);
  const topScore = matches.length && typeof matches[0].rerankScore === 'number'
    ? matches[0].rerankScore : null;
  // 相关度下限：低于此数视同「文库里没有」，交给护栏拒答。
  // RERANK_MIN=0 时不筛（调阈值前先量分布，别拍脑袋定数）。
  if (RERANK_MIN > 0 && topScore !== null && topScore < RERANK_MIN) matches = [];

  /* ── 4) 取回父段落 ──
     命中的是 700 字小块，切块难免把一句话拦腰截断；喂给模型的是它在原文里所在的那一大段。
     引用卡片仍用精确小块（sources.x），便于逐字核对。 */
  const ctxTexts = matches.length ? await expandParents(env, url.origin, matches) : [];

  const sources = matches.map((m, i) => ({
    n: i + 1, title: m.metadata.title, series: m.metadata.series,
    path: m.metadata.path, kind: m.metadata.kind,
    x: String(m.metadata.t || '').replace(/\s+/g, ' ').trim().slice(0, 160),   // 段落摘录（前端出处预览用）
  }));
  const context = matches.map((m, i) =>
    `【${i + 1}】《${m.metadata.series}》${m.metadata.title}\n${ctxTexts[i]}`).join('\n\n');

  /* ── 5) 护栏：宁可不答，不可妄说 ──
     这两种情形从前都落到同一句「文库中未找到」，可它们性质全然不同：
     一个是我们自己的检索坏了，一个是文库里确实没有。把前者说成后者，
     等于替大安法师否认他讲过某事 —— 这是妄语，不能因为省事就含糊过去。 */
  let earlyReply = '';
  if (retrievalErrored) earlyReply = '抱歉，文库检索暂时不可用（上游繁忙或额度受限），请稍后再试。南无阿弥陀佛。';
  else if (!matches.length) earlyReply = '文库中未找到直接相关的开示。可以换个说法，或就具体的净土法门、修持问题再问。';

  const messages = [
    { role: 'system', content:
      '你是净土修学网站「佛乐」的问道助手。下面【资料】是依用户问题从大安法师讲经文库中检索到的段落，各以【n】编号；你只是这些资料的转述与归纳者，把「可核验」放在第一位。\n' +
      '规则：\n' +
      '一、严格接地：只依据【资料】作答，忠于原文义理，绝不掺入资料之外的常识或自己的发挥；\n' +
      `二、逐点引用：每一处论断之后用方括号标出所依据的编号，如 [1] 或 [2][5]，做到句句可点开核对；优先直接引用原文并加引号，引文须与所标编号的资料严格一致、能逐字对上，不可张冠李戴。【资料】共 ${matches.length} 条，编号 1–${matches.length}，不得引用此范围外的编号；\n` +
      '三、综合而非罗列：把多段资料融会成连贯回答，不要逐段复述；资料之间说法有出入时如实并列，不强行调和；\n' +
      '四、资料不足以回答时，如实说明「文库中未找到直接开示」，可建议阅读相关篇目，不得强答；\n' +
      '五、你是检索助手，不是法师：不以说法者口吻自居，涉及重大修行抉择时提醒读者阅读原文、亲近善知识；\n' +
      `六、用平实庄重的白话回答，条理清晰，适当分段，不堆砌辞藻，不写「根据资料」「综上所述」之类套话，控制在约 ${ANSWER_CHARS} 字以内。` },
    ...hist,
    { role: 'user', content: `【资料】\n${context}\n\n【问题】${q}` },
  ];

  // 护栏命中：直接回定句，不调用生成模型 —— 既省算力，也从机制上杜绝无据发挥
  let llmRes = null;
  if (!earlyReply) {
    llmRes = await sfPost(env, '/chat/completions', {
      model: env.SF_CHAT_MODEL, messages, stream: true,
      max_tokens: 2200, temperature: 0.3,
      enable_thinking: false, // 混合推理模型关闭思考，直接作答
    });
    if (!llmRes.ok || !llmRes.body) return new Response('生成服务暂不可用', { status: 502 });
  }

  /* ── 6) 流式输出（SSE）：先发出处，再逐段发正文，末了发自检结果 ── */
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();
  let full = '';
  const stream = new ReadableStream({
    async start(controller) {
      const send = (event, data) =>
        controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
      send('sources', sources);
      if (earlyReply) {
        full = earlyReply;
        send('delta', { text: earlyReply });
      } else {
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
                if (text) { full += text; send('delta', { text }); }
              } catch { /* 跳过不完整帧 */ }
            }
          }
        } catch { /* 上游中断 */ }
      }
      // 引用自检只作信号：已经发出去的字一个都不改，前端据此显示核验徽标。
      // topScore 一并带出：调相关度下限时要看它，评测脚本也据此统计。
      send('done', { verify: full ? validateCitations(full, ctxTexts) : null, topScore });
      controller.close();
    },
  });

  const headers = { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store' };
  const resp = new Response(stream, { headers });
  // 只缓存「答出了东西」的首轮：护栏定句与空回答缓存下来，等于把一次故障钉住七天
  if (ckey && ctx && !earlyReply) {
    const [toClient, toCache] = resp.body.tee();
    ctx.waitUntil((async () => {
      // 等流走完再落盘，此时 full 才是完整回答
      await new Response(toCache).text();
      if (!full) return;
      const body = [`event: sources\ndata: ${JSON.stringify(sources)}\n\n`,
        `event: delta\ndata: ${JSON.stringify({ text: full })}\n\n`,
        `event: done\ndata: ${JSON.stringify({ verify: validateCitations(full, ctxTexts), cached: true })}\n\n`].join('');
      await caches.default.put(ckey, new Response(body, {
        headers: { ...headers, 'Cache-Control': `public, max-age=${ASK_CACHE_TTL}` },
      }));
    })());
    return new Response(toClient, { headers });
  }
  return resp;
}

/* 问道回答的赞踩。POST /api/askfb {dev,vote,q,a,verify} → {ok}
   踩比赞有用得多：它指出的是检索没召回、或模型答偏了的那些问题，
   是下一轮调检索参数的题源。故问题与回答一并存下，事后才查得出是哪一步出的岔。 */
async function serveAskFeedback(request, env) {
  if (request.method !== 'POST') return json({ error: 'Method Not Allowed' }, 405);
  let dev, vote, q, a, verify;
  try {
    const body = await request.json();
    dev = String(body.dev || '').trim().slice(0, 40);
    vote = String(body.vote || '').trim();
    q = String(body.q || '').replace(/\s+/g, ' ').trim().slice(0, 300);
    a = String(body.a || '').trim().slice(0, 4000);
    verify = body.verify ? JSON.stringify(body.verify).slice(0, 300) : '';
  } catch { return json({ error: 'Bad Request' }, 400); }
  if (vote !== 'up' && vote !== 'down') return json({ error: 'Bad Request' }, 400);
  if (!q) return json({ error: 'Bad Request' }, 400);

  try {
    const rl = env.REPORT_RL || env.CMT_RL;
    if (rl) {
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      const { success } = await rl.limit({ key: `fb:${dev}:${ip}` });
      if (!success) return json({ error: '太频繁，请稍候再试' }, 429);
    }
  } catch { /* 限流器故障不阻断 —— 反馈这类小事不值得为它挡住用户 */ }

  try {
    await env.DB.prepare(
      'INSERT INTO ask_feedback (ts,dev,vote,q,a,verify) VALUES (?,?,?,?,?,?)')
      .bind(Date.now(), dev, vote, q, a, verify).run();
  } catch { return json({ error: '暂时存不下，稍后再试' }, 503); }
  return json({ ok: true });
}

/** 检索链路自检。部署后 GET 一下，就知道各路是不是真的接上了 ——
 *  尤其 hybridReady：关键词索引没建好时，问道照常能用，只是悄悄退回了纯向量，
 *  不看这里根本发现不了。 */
async function serveAskHealth(env) {
  let lexRows = null, hybridReady = false;
  try {
    const r = await env.KB.prepare('SELECT count(*) AS n FROM chunks_fts').first();
    lexRows = r?.n ?? null;
    hybridReady = !!lexRows;
  } catch { /* 未绑定或表不存在 */ }
  return json({
    retrievalVersion: RETRIEVAL_VERSION,
    condense: USE_CONDENSE, queryRewrite: USE_QUERY_REWRITE,
    hybrid: USE_HYBRID, hybridReady, lexRows,
    rerank: USE_RERANK, topK: TOP_K, rerankPool: RERANK_POOL, parentChars: PARENT_CHARS,
    chatModel: env.SF_CHAT_MODEL || null,
    hasKey: !!env.SILICONFLOW_API_KEY,
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

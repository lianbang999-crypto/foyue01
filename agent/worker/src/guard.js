// 生成闸 —— 来源分级与限流（2026-08-04 二修：独立站定位）
//
// 【定位修订（发起人 2026-08-04 定）】本智能体部署成独立站、有自己的 API 端口，
// 公网就是正门——不能沿用选佛谱「公网直访零生成」的收口（那边有游戏 worker 挡在前面）。
// 改为与现行 foyue.org/api/ask 同水位的公网防护：
//   公网直访 → 生成开放，双限流（平台 ratelimit 绑定 每IP 8/分 ＋ 隔离实例兜底计数）；
//              PUBLIC_GEN=off 一行可关（回到零生成降级链，应急阀）。
//   内转     → service binding 构造的 ask.internal 为免限流内门（主站将来接线用），
//              按 x-ask-client 走日配额（KV，M2 兑现；缺绑放行）。
//
// 【信任判据仍是 ask.internal】此 Host 只有同账号 service binding 能出现，公网伪造不了
// （Cloudflare 按 Host 路由，改 Host 根本到不了本 worker）。
//
// 【fail-open 原则】限流器/KV 故障不反噬正常问答；收口靠双保险而非单点。

const DAY_TTL = 100000;            // 秒。当日键过期自清
export const GEN_DAILY_DEFAULT = 60;
const PUB_PER_MIN = 8;             // 公网每 IP 每分钟生成次数（与现行 /api/ask 同值）

// 隔离实例兜底计数（与主站 serveAsk 同款双保险：平台限流绑定失效时仍有此层）
const rlCounts = new Map();
function localLimitOk(ip) {
  const win = Math.floor(Date.now() / 60000);
  const key = `${ip}:${win}`;
  const n = (rlCounts.get(key) || 0) + 1;
  rlCounts.set(key, n);
  if (rlCounts.size > 5000) rlCounts.clear();   // 防内存膨胀
  return n <= PUB_PER_MIN;
}

export function genGuard(req, env) {
  let host = '';
  try { host = new URL(req.url).hostname; } catch { /* 无效 URL 按公网待之 */ }
  const trusted = host === 'ask.internal';
  const ip = req.headers.get('CF-Connecting-IP') || 'local';
  const client = String(req.headers.get('x-ask-client') || '').slice(0, 64) || `ip:${ip}`;
  const cap = Math.max(1, Number(env && env.ASK_GEN_DAILY) || GEN_DAILY_DEFAULT);
  const publicGenOn = !env || String(env.PUBLIC_GEN || 'on') !== 'off';
  return {
    trusted, cap,
    remaining: null,               // take() 之后方有数；未动生成者保持 null
    /**
     * 取一次生成额度（重排复核与综述共用此闸）。true＝放行。
     * 内转：日配额（KV 缺绑放行）；公网：每分钟双限流；PUBLIC_GEN=off 时公网永 false。
     */
    async take() {
      if (!trusted) {
        if (!publicGenOn) return false;
        if (!localLimitOk(ip)) return false;
        try {
          if (env && env.PUB_RL) {
            const { success } = await env.PUB_RL.limit({ key: ip });
            if (!success) return false;
          }
        } catch { /* 限流器故障不阻断（本地兜底已计过一次） */ }
        return true;
      }
      const kv = env && env.RL;
      if (!kv) return true;
      try {
        const day = new Date().toISOString().slice(0, 10);
        const k = `rl:${client}:${day}`;
        const n = Number(await kv.get(k)) || 0;
        if (n >= cap) { this.remaining = 0; return false; }
        this.remaining = cap - n - 1;
        await kv.put(k, String(n + 1), { expirationTtl: DAY_TTL });
        return true;
      } catch { return true; }
    },
  };
}

/** 答案快取键：问句＋模型＋数据版次。带追问历史的不快取（同问不同上下文）。 */
export async function cacheKeyOf(q, model, builtAt) {
  const bytes = new TextEncoder().encode(JSON.stringify([q.trim(), model, builtAt]));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

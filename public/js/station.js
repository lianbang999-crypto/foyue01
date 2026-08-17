// 电台核心：确定性 24 小时排播算法
//
// 原理：以固定「开播纪元」为起点，按北京时间把一天分为八个时段，
// 每个时段对应一个节目池（各系列按集连播、循环）。从纪元开始逐集推演，
// 任何客户端在任何时刻都能算出「此刻该播哪一集、播到第几秒」——
// 无需服务器状态，天下同闻，即是共修。
//
// 软边界 + 补白：一集讲经绝不中途掐断。长课允许越界最多 15 分钟；
// 放不下时，大段空当以《劝修净土诗》作诗偈间奏（21~38分），
// 小段空当以《大安法师讲故事》短篇补白（3~18分），各时段基本准点开始，
// 边界误差被限制在十几分钟内，不产生累积漂移。

// 开播纪元：2026-07-01 00:00:00（北京时间，即 UTC 2026-06-30 16:00）
export const EPOCH_UTC_MS = Date.UTC(2026, 5, 30, 16, 0, 0);

const DAY = 86400;
const H = 3600;

// 每日时段表（start 为北京时间当日秒数；theme 驱动页面昼夜氛围）
export const BLOCKS = [
  { start: 0,        name: '子夜讲堂', sub: '净土经论连播', theme: 'night', pool: 'jinglun' },
  { start: 4.5 * H,  name: '晨诵',     sub: '劝修净土诗',   theme: 'dawn',  pool: 'shi' },
  { start: 6.5 * H,  name: '上午讲堂', sub: '无量寿经述义', theme: 'day',   pool: 'wlsjy' },
  { start: 11.5 * H, name: '午间故事', sub: '大安法师讲故事', theme: 'day', pool: 'story' },
  { start: 13 * H,   name: '下午讲堂', sub: '观经四帖疏',   theme: 'day',   pool: 'gjszs' },
  { start: 17.5 * H, name: '暮诵',     sub: '劝修净土诗',   theme: 'dusk',  pool: 'shi' },
  { start: 19 * H,   name: '晚间讲座', sub: '专题开示',     theme: 'night', pool: 'zhuanti' },
  { start: 21.5 * H, name: '夜听经论', sub: '净土经论连播', theme: 'night', pool: 'jinglun' },
];

// 「净土经论」轮转池的系列次序（中短篇系列依次连播，一轮约 109 小时）
const JINGLUN_ORDER = ['amtj', 'ssbdy', 'yttz', 'pxxyp', 'xyxz', 'xyxx', 'xffyw', 'xgzz', 'yhbf', 'lzsdy', 'lzzn'];

// 返回北京时间当日秒数 tod 所处的时段
export function blockAt(tod) {
  let b = BLOCKS[BLOCKS.length - 1];
  for (const x of BLOCKS) { if (tod >= x.start) b = x; }
  return b;
}

/* ── 校时 ──
   本模块的立意是「任何客户端都能算出此刻该播哪一集，天下同闻」，
   而这一句建立在本机时钟准的前提上 —— 原先站里一处校正也没有。
   手机慢五分钟，他听到的就是别人五分钟前那一句，且全程不自知；
   慢上几个钟头，连时段都不是同一个（子夜讲堂对晨诵）。
   skew 由 app.js 启动后向 /api/time 问一次得出。 */
let clockSkew = 0;

export function setClockSkew(ms) { clockSkew = Number.isFinite(ms) ? ms : 0; }
export function clockSkewMs() { return clockSkew; }

/** 校正后的「现在」。凡涉及绝对时刻的判断都走它 ——
 *  电台推演、北京时间显示、念佛日界。
 *  睡眠定时那类本地计时不必用：同一个时钟前后做差，skew 自会抵消。 */
export function nowMs() { return Date.now() + clockSkew; }

// 距开播纪元的秒数（电台时钟）
export function stationNow() {
  return Math.max(0, (nowMs() - EPOCH_UTC_MS) / 1000);
}

// 把电台秒数换算为北京时间的当日秒数
export function todOf(t) {
  return ((t % DAY) + DAY) % DAY;
}

// 由 catalog 构建电台：节目池 + 推演缓存
export function createStation(catalog) {
  const byId = Object.fromEntries(catalog.series.map(s => [s.id, s]));
  const flat = s => s.episodes.map(e => ({ ...e, seriesId: s.id, seriesTitle: s.title, bucket: s.bucket }));

  const pools = {
    wlsjy: flat(byId.wlsjy),
    gjszs: flat(byId.gjszs),
    shi: flat(byId.qxjts),
    story: flat(byId.story),
    zhuanti: flat(byId.zhuanti),
    jinglun: JINGLUN_ORDER.flatMap(id => flat(byId[id])),
  };
  for (const [k, v] of Object.entries(pools)) {
    if (!v.length) throw new Error('节目池为空: ' + k);
  }

  // 推演缓存：items 按时间递增；ptr 记录各池已播集数
  const state = { items: [], t: 0, ptr: {} };

  /* 冷启动加速：原先每打开一次页面，都要从开播纪元逐集推演到此刻，
     推演量随开播时长线性增长 —— 三年后低端机约 309ms，而且是同步的，卡的是首屏。
     这里存一份「某个整天边界上」的推演状态，下次从它接着推，
     耗时便与开播了多久无关。排播算法一字未动，推出来的结果完全一致。

     节目池一改（新增集数等），旧快照的 ptr 就不作数了，故以各池长度作签名比对。 */
  const SNAP_KEY = 'fy.station.snap';
  const SNAP_V = 1;
  const sig = Object.keys(pools).sort().map((k) => `${k}:${pools[k].length}`).join('|');
  const marks = [];        // 推演经过整天边界时的状态，供存快照时挑一个足够老的
  let snapSaved = false;

  try {
    const s = JSON.parse(localStorage.getItem(SNAP_KEY) || 'null');
    // 快照须早于今天 0 点：否则今天的节目单会缺开头那一集
    const todayStart = Math.floor(stationNow() / DAY) * DAY;
    if (s && s.v === SNAP_V && s.sig === sig && s.t > 0 && s.t <= todayStart && s.ptr) {
      state.t = s.t;
      state.ptr = { ...s.ptr };
      marks.push({ t: s.t, ptr: { ...s.ptr } });
    }
  } catch { /* 快照坏了就从纪元推，慢一点而已，不该因此打不开 */ }

  function saveSnap() {
    if (snapSaved) return;
    const cut = Math.floor(stationNow() / DAY) * DAY;   // 今天 0 点
    let best = null;
    for (const m of marks) { if (m.t <= cut) best = m; }
    if (!best) return;
    snapSaved = true;
    try {
      localStorage.setItem(SNAP_KEY, JSON.stringify({ v: SNAP_V, sig, t: best.t, ptr: best.ptr }));
    } catch { /* 存不进，下次冷启动多推一会儿而已 */ }
  }

  /* 裁掉早已播过的排播项。原先 items 从纪元一路堆到此刻、从不清理，
     三年约三万五千条留在内存里。节目单最远查到今天 0 点，
     故留「往前一天半」绰绰有余。

     基准取「最近一次查询的时刻」而非 stationNow()：二者在正常使用中是一回事
     （tick 每秒查的就是此刻），但万一有人查了很早的时刻，
     拿此刻作基准会把那一段连同答案一起裁掉。 */
  let lastQuery = 0;
  function prune() {
    if (state.items.length < 200) return;
    const keep = lastQuery - 1.5 * DAY;
    if (keep <= 0) return;
    let i = 0;
    while (i < state.items.length && state.items[i].end < keep) i++;
    if (i > 0) state.items.splice(0, i);
  }

  const GRACE = 900;      // 允许一集越过时段边界的宽限（15 分钟）
  const EARLY = 300;      // 距边界不足 5 分钟时，提前进入下一时段
  const POEM_GAP = 2400;  // 空当 ≥ 40 分钟时用诗偈间奏，不足则用短篇故事

  // 取下一时段边界（绝对时刻）
  function nextBoundary(t) {
    const tod = todOf(t);
    for (const b of BLOCKS) if (b.start > tod) return t - tod + b.start;
    return t - tod + DAY; // 次日 00:00
  }

  function takeFrom(poolName) {
    const pool = pools[poolName];
    const idx = state.ptr[poolName] ?? 0;
    state.ptr[poolName] = idx + 1;
    return pool[idx % pool.length];
  }

  // 推演到覆盖时刻 t 为止（含 t 之后至少一集，便于取"接下来"）
  function ensure(t) {
    let guard = 0;
    while (state.t <= t) {
      const dayBefore = Math.floor(state.t / DAY);
      const nb = nextBoundary(state.t);
      let remaining = nb - state.t;
      let block;
      if (remaining <= EARLY) {
        // 距边界太近：提前进入下一时段，剩余时间按再下一个边界计算
        block = blockAt(todOf(nb));
        remaining = nextBoundary(nb + 1) - state.t;
      } else {
        block = blockAt(todOf(state.t));
      }
      const pool = pools[block.pool];
      const candidate = pool[(state.ptr[block.pool] ?? 0) % pool.length];
      let from;
      if (candidate.dur - remaining <= GRACE) {
        from = block.pool;                  // 正常排播（含允许的越界宽限）
      } else if (remaining >= POEM_GAP && block.pool !== 'shi') {
        from = 'shi';                       // 大空当：诗偈间奏
      } else {
        from = 'story';                     // 小空当：短篇故事补白
      }
      const ep = takeFrom(from);
      state.items.push({ start: state.t, end: state.t + ep.dur, ep, block, filler: from !== block.pool });
      state.t += ep.dur;
      // 跨过一个整天边界：此刻的状态可作日后冷启动的起点
      if (Math.floor(state.t / DAY) > dayBefore) {
        marks.push({ t: state.t, ptr: { ...state.ptr } });
        if (marks.length > 12) marks.shift();
      }
      if (++guard > 400000) throw new Error('排播推演超限'); // 保险丝：约 20 年
    }
  }

  // 二分查找覆盖时刻 t 的排播项下标
  function indexAt(t) {
    lastQuery = Math.max(lastQuery, t);
    ensure(t);
    /* 兜底：要查的时刻早于还留着的那一段（被裁掉了，或快照起点就在它之后）。
       正常的每秒推进永远走不到这里，但一旦走到，宁可从头推一遍也不能算错 ——
       算错的后果是给人放错了一集，比慢几百毫秒严重得多。 */
    if (!state.items.length || t < state.items[0].start) {
      state.items = [];
      state.t = 0;
      state.ptr = {};
      ensure(t);
    }
    const a = state.items;
    let lo = 0, hi = a.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (a[mid].end <= t) lo = mid + 1; else hi = mid;
    }
    return lo;
  }

  return {
    pools,
    // 此刻直播项：{ item, offset, next: [后续n项] }
    liveAt(t, nextCount = 3) {
      // 裁剪只在这里做，且必须赶在算下标之前 ——
      // 若放进 ensure()，下面 indexAt 拿到的 i 会被后一次 ensure 里的裁剪挪飞，
      // 取出来就是另一集了。tick 每秒调一次本函数，裁剪频次足够。
      prune();
      const i = indexAt(t);
      ensure(state.items[i].end + 4 * H); // 预推演，保证 next 充足
      saveSnap();                         // 推演已到此刻，顺手留一份起点给下次冷启动
      return {
        item: state.items[i],
        offset: t - state.items[i].start,
        next: state.items.slice(i + 1, i + 1 + nextCount),
      };
    },
    // 某天（开播后第 day 天，0 起）的完整节目单
    dayItems(day) {
      const from = day * DAY, to = from + DAY;
      // 用 from 而非 to 推进裁剪基准：查明天的节目单，不该把今天上半天裁掉
      lastQuery = Math.max(lastQuery, from);
      ensure(to + 4 * H);
      if (!state.items.length || from < state.items[0].start) {
        state.items = []; state.t = 0; state.ptr = {};   // 同 indexAt 的兜底
        ensure(to + 4 * H);
      }
      return state.items.filter(x => x.end > from && x.start < to);
    },
  };
}

// —— 时间显示工具（统一按北京时间） ——

export function bjParts(utcMs) {
  const d = new Date(utcMs + 8 * H * 1000);
  return {
    y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(),
    day: d.getUTCDay(), h: d.getUTCHours(), mi: d.getUTCMinutes(),
  };
}

export function fmtClock(t) { // 电台秒数 → "HH:MM"（北京时间）
  const tod = todOf(t);
  const h = Math.floor(tod / H), m = Math.floor((tod % H) / 60);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

export function fmtDur(sec) { // 秒 → "1小时23分" / "45分钟"
  const h = Math.floor(sec / H), m = Math.round((sec % H) / 60);
  if (h && m) return `${h}小时${m}分`;
  if (h) return `${h}小时`;
  return `${m}分钟`;
}

export function fmtMMSS(sec) {
  sec = Math.max(0, Math.floor(sec));
  const h = Math.floor(sec / H), m = Math.floor((sec % H) / 60), s = sec % 60;
  const mm = String(m).padStart(2, '0'), ss = String(s).padStart(2, '0');
  return h ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

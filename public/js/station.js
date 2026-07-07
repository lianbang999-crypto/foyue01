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

// 距开播纪元的秒数（电台时钟）
export function stationNow() {
  return Math.max(0, (Date.now() - EPOCH_UTC_MS) / 1000);
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
      if (++guard > 400000) throw new Error('排播推演超限'); // 保险丝：约 20 年
    }
  }

  // 二分查找覆盖时刻 t 的排播项下标
  function indexAt(t) {
    ensure(t);
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
      const i = indexAt(t);
      ensure(state.items[i].end + 4 * H); // 预推演，保证 next 充足
      return {
        item: state.items[i],
        offset: t - state.items[i].start,
        next: state.items.slice(i + 1, i + 1 + nextCount),
      };
    },
    // 某天（开播后第 day 天，0 起）的完整节目单
    dayItems(day) {
      const from = day * DAY, to = from + DAY;
      ensure(to + 4 * H);
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

-- 佛乐 · 直播留言（同修在此）表结构
-- 应用：npx wrangler d1 execute bojingtai-cmt --remote --file worker/schema.sql

CREATE TABLE IF NOT EXISTS comments (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  dev  TEXT NOT NULL,            -- 本机设备标识（匿名，封禁用）
  name TEXT NOT NULL,            -- 法名（莲友·某某）
  text TEXT NOT NULL,            -- 留言内容（≤100 字）
  ep   TEXT DEFAULT '',          -- 发送时的直播集标识（追溯语境用）
  ts   INTEGER NOT NULL          -- 毫秒时间戳
);
CREATE INDEX IF NOT EXISTS idx_comments_id ON comments(id DESC);
CREATE INDEX IF NOT EXISTS idx_comments_ep ON comments(ep, id DESC);   -- 按集拉留言

-- 随喜（功德点赞，按集计数；同设备同集只一条）
CREATE TABLE IF NOT EXISTS likes (
  ep  TEXT NOT NULL,             -- 集标识（seriesId#idx）
  dev TEXT NOT NULL,             -- 本机设备标识
  ts  INTEGER NOT NULL,          -- 毫秒时间戳
  PRIMARY KEY (ep, dev)
);

-- 封禁设备
CREATE TABLE IF NOT EXISTS banned (
  dev TEXT PRIMARY KEY,
  ts  INTEGER NOT NULL
);

-- 同时在线心跳（直播「同时在线人数」；每设备一行，轮询时刷新时间戳）
CREATE TABLE IF NOT EXISTS online (
  dev TEXT PRIMARY KEY,          -- 本机设备标识
  ts  INTEGER NOT NULL           -- 最近一次心跳毫秒时间戳
);

-- 站点配置（公告、屏蔽词等，k/v 各存一行 JSON 或纯文本）
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- 报错/纠错上报（主站与游戏共用，admin 统一处理）
CREATE TABLE IF NOT EXISTS reports (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  dev     TEXT NOT NULL,
  site    TEXT NOT NULL DEFAULT '',
  kind    TEXT DEFAULT '',
  target  TEXT DEFAULT '',
  text    TEXT NOT NULL,
  contact TEXT DEFAULT '',
  ua      TEXT DEFAULT '',
  status  TEXT NOT NULL DEFAULT 'open',
  ts      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reports_id ON reports(id DESC);
CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status, id DESC);

-- ══════════════ 莲号与功课同步 ══════════════
-- 念佛计数原先只在本机 localStorage：清一次缓存、换一台手机，几万几十万声就没了。
-- 这里把它接上线，跨设备可认回。不收邮箱、不收手机 —— 只有一枚莲号与一道护念码，
-- 抄在纸上即可。莲友多是上了年纪的人，越少的字越好。

-- 莲号本身
CREATE TABLE IF NOT EXISTS lianyou (
  lian TEXT PRIMARY KEY,             -- 莲号：8 位，已去掉 0O1IL 等易混字符
  pass TEXT NOT NULL,                -- 护念码的 PBKDF2 散列（明文不落库）
  salt TEXT NOT NULL,
  dev  TEXT NOT NULL DEFAULT '',     -- 开号时的设备标识（仅备查）
  made INTEGER NOT NULL,             -- 开号时间
  seen INTEGER NOT NULL,             -- 最近一次同步
  bad  INTEGER NOT NULL DEFAULT 0,   -- 连续认错次数
  lock INTEGER NOT NULL DEFAULT 0    -- 锁到何时（毫秒时间戳）：护念码只有六位数字，
);                                   -- 熵本就有限，真正的防线是这道失败限次

-- 认回后发的凭据，此后同步只带它，不必反复验算护念码
CREATE TABLE IF NOT EXISTS lian_token (
  tok  TEXT PRIMARY KEY,
  lian TEXT NOT NULL,
  ts   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lian_token ON lian_token(lian);

-- 功课数据本体。分四类而不是囫囵一块：
-- 阅读进度一页一动，不该每次把念佛计数整包重传。
CREATE TABLE IF NOT EXISTS lian_blob (
  lian TEXT NOT NULL,
  kind TEXT NOT NULL,                -- nj 念佛计数 / read 阅读 / listen 听经 / pref 法名与设置
  data TEXT NOT NULL,                -- JSON
  rev  INTEGER NOT NULL,             -- 每写一次 +1，客户端据此判新旧
  ts   INTEGER NOT NULL,
  PRIMARY KEY (lian, kind)
);

-- 每日念佛汇总：全站共念的总数由此累加。只记数目，不记谁念的什么。
CREATE TABLE IF NOT EXISTS nianfo_day (
  day  TEXT NOT NULL,                -- YYYY-MM-DD（北京时间，与站内日界一致）
  lian TEXT NOT NULL,
  n    INTEGER NOT NULL,
  PRIMARY KEY (day, lian)
);
CREATE INDEX IF NOT EXISTS idx_nianfo_day ON nianfo_day(day);

-- 此刻在念：短窗口心跳，与直播的 online 表同一路数
CREATE TABLE IF NOT EXISTS nianfo_live (
  dev TEXT PRIMARY KEY,
  ts  INTEGER NOT NULL
);

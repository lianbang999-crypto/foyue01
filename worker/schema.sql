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

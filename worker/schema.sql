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

-- 封禁设备
CREATE TABLE IF NOT EXISTS banned (
  dev TEXT PRIMARY KEY,
  ts  INTEGER NOT NULL
);

-- 站点配置（公告、屏蔽词等，k/v 各存一行 JSON 或纯文本）
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

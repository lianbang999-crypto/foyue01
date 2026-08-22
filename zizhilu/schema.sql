-- 日省 · 功过格 数据表
CREATE TABLE IF NOT EXISTS users (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  account    TEXT UNIQUE NOT NULL,           -- 邮箱或手机号
  kind       TEXT NOT NULL,                  -- email / phone
  pass_hash  TEXT NOT NULL,                  -- iter:salt:hash（PBKDF2-SHA256）
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS notes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL,
  day         TEXT NOT NULL,                 -- YYYY-MM-DD（用户本地时区，由前端传入）
  kind        TEXT NOT NULL DEFAULT 'note',  -- merit(功) / fault(过) / note(记)
  points      INTEGER NOT NULL DEFAULT 1,    -- 功过分值；kind=note 时为 0
  content     TEXT NOT NULL DEFAULT '',
  attachments TEXT NOT NULL DEFAULT '[]',    -- JSON 数组
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id, created_at DESC);

-- 每日 AI 省察缓存（day=YYYY-MM-00 时为月报存档）
CREATE TABLE IF NOT EXISTS day_ai (
  user_id    INTEGER NOT NULL,
  day        TEXT NOT NULL,
  summary    TEXT NOT NULL,
  mood       TEXT NOT NULL DEFAULT '',
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, day)
);

-- 登录失败限流（防暴力破解）
CREATE TABLE IF NOT EXISTS login_fails (
  account      TEXT PRIMARY KEY,
  fails        INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL
);

-- AI 每日用量（每人每天 100 次）
CREATE TABLE IF NOT EXISTS ai_usage (
  user_id INTEGER NOT NULL,
  day     TEXT NOT NULL,
  used    INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);

-- 广场举报（留作后续处理，先记录）
CREATE TABLE IF NOT EXISTS reports (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  note_id    INTEGER NOT NULL,
  by_user    INTEGER NOT NULL,
  reason     TEXT NOT NULL DEFAULT '',
  handled    INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reports_note ON reports(note_id);

-- 用户专属邀请码（每人一个，名额有限）
CREATE TABLE IF NOT EXISTS invites (
  code       TEXT PRIMARY KEY,
  owner_id   INTEGER NOT NULL,
  max_uses   INTEGER NOT NULL DEFAULT 2,
  used       INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invites_owner ON invites(owner_id);

-- 邀请关系（谁邀请了谁，便于追溯与收回）
CREATE TABLE IF NOT EXISTS invite_uses (
  user_id    INTEGER PRIMARY KEY,
  code       TEXT NOT NULL,
  inviter_id INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invite_uses_inviter ON invite_uses(inviter_id);

-- Web Push 订阅（每日提醒）
CREATE TABLE IF NOT EXISTS push_subs (
  endpoint   TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL,
  p256dh     TEXT NOT NULL DEFAULT '',
  auth       TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

-- 2026-08-18：广场分享 + 昵称（法名）+ AI 每日配额
-- ALTER 只能执行一次；已执行过会报 duplicate column，属正常。

ALTER TABLE users ADD COLUMN nickname TEXT NOT NULL DEFAULT '';
ALTER TABLE notes ADD COLUMN shared INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notes ADD COLUMN shared_at TEXT NOT NULL DEFAULT '';

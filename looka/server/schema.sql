-- Looka 云端库（looka-db）
-- 注意：用户账号表在 zhi 的 rixing-db（AUTH_DB 绑定），此处不建 users。

-- Looka 自己的登录会话（Bearer token）
CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

-- 通用同步条目：kind = category / event / task / note / diary / stamp
-- payload 为 JSON 文本；deleted=1 是墓碑
CREATE TABLE IF NOT EXISTS items (
  user_id    INTEGER NOT NULL,
  kind       TEXT NOT NULL,
  uid        TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0,
  payload    TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (user_id, kind, uid)
);
CREATE INDEX IF NOT EXISTS idx_items_user_updated ON items(user_id, updated_at);

-- AI 月度用量（ym = 2026-08）
CREATE TABLE IF NOT EXISTS ai_usage (
  user_id INTEGER NOT NULL,
  ym      TEXT NOT NULL,
  used    INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, ym)
);

-- 订阅状态（免费用户无记录）
CREATE TABLE IF NOT EXISTS plans (
  user_id    INTEGER PRIMARY KEY,
  plan       TEXT NOT NULL DEFAULT 'free',
  expires_at INTEGER NOT NULL DEFAULT 0
);

-- 订阅兑换码（先用码通路收款，后接支付）
CREATE TABLE IF NOT EXISTS codes (
  code    TEXT PRIMARY KEY,
  plan    TEXT NOT NULL DEFAULT 'pro',
  days    INTEGER NOT NULL DEFAULT 31,
  used_by INTEGER,
  used_at INTEGER
);

-- 登录防爆破
CREATE TABLE IF NOT EXISTS login_fails (
  account      TEXT PRIMARY KEY,
  fails        INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT
);

-- ===== 鹿角（算力券）2026-08-21 =====
-- 余额：读多写少，冗余存储，避免每次 SUM 全量流水
CREATE TABLE IF NOT EXISTS antler_balance (
  user_id     INTEGER PRIMARY KEY,
  granted     INTEGER NOT NULL DEFAULT 0,   -- 赠送桶：有累计上限、不清零
  paid        INTEGER NOT NULL DEFAULT 0,   -- 购买桶：不过期
  grant_cycle TEXT,                         -- 'YYYY-MM'，惰性月度发放的幂等键
  updated_at  INTEGER NOT NULL DEFAULT 0
);

-- 流水：审计与对账，永久保留，不参与热路径
CREATE TABLE IF NOT EXISTS antler_ledger (
  id            TEXT PRIMARY KEY,
  user_id       INTEGER NOT NULL,
  delta         INTEGER NOT NULL,           -- 正入负出
  bucket        TEXT NOT NULL,              -- granted | paid | mixed
  reason        TEXT NOT NULL,              -- monthly_grant|purchase|redeem|chat_premium|chat_flagship|refund|bonus
  ref           TEXT,
  balance_after INTEGER NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ledger_user ON antler_ledger(user_id, created_at DESC);
-- 幂等锁：同一 (reason, ref) 只能入账一次，挡住重复发放与重复退款
-- 幂等锁必须带 user_id：是「同一用户的同一笔」不重复，不是全站唯一
-- （曾写成 (reason,ref)，导致月度发放 ref='YYYY-MM' 时全站只有第一个用户能落流水）
CREATE UNIQUE INDEX IF NOT EXISTS idx_ledger_ref ON antler_ledger(user_id, reason, ref) WHERE ref IS NOT NULL;

-- 兑换码扩展：支持鹿角码（原表只能发订阅天数）
ALTER TABLE codes ADD COLUMN kind TEXT NOT NULL DEFAULT 'plan';   -- plan | antler
ALTER TABLE codes ADD COLUMN amount INTEGER NOT NULL DEFAULT 0;   -- kind=antler 时的鹿角数

ALTER TABLE codes ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0;

-- ===== 支付自动开通（2026-08-21）=====
-- 支付意图：把「谁要买什么」和「第三方平台上的一个短码」绑起来。
-- 用短码而不是邮箱：不把用户隐私暴露到第三方平台，且格式固定、匹配精确。
CREATE TABLE IF NOT EXISTS pay_intents (
  code       TEXT PRIMARY KEY,      -- 'LK-8F3A'
  user_id    INTEGER NOT NULL,
  plan_days  INTEGER NOT NULL,      -- 31 / 366
  channel    TEXT NOT NULL,         -- afdian | kofi
  status     TEXT NOT NULL,         -- pending | paid | expired
  order_no   TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL       -- ⚠️ 只用于「不再展示」，匹配时仍然认：
);                                  --    用户可能挂着付款页半小时才付，过期就拒 = 收钱不给货

-- 已处理订单：幂等的唯一依据。
-- ⚠️ 爱发电 webhook 无签名，推送内容一律不可采信 ——
--    只取 out_trade_no，再用 token 签名调 query-order 反查校验后才可入此表。
CREATE TABLE IF NOT EXISTS pay_orders (
  channel    TEXT    NOT NULL,      -- afdian | kofi
  order_no   TEXT    NOT NULL,      -- 爱发电 out_trade_no
  user_id    INTEGER,               -- NULL = 未认领，等自助认领或人工
  amount     TEXT,
  raw        TEXT,                  -- 原始订单 JSON，出问题时能查
  handled_at INTEGER NOT NULL,
  PRIMARY KEY (channel, order_no)   -- 主键就是防重复发放的锁
);

-- §133：Paddle 状态镜像。业务真相仍是 plans / antler_balance / founders —— 这两张表只
-- 镜像 Paddle 侧发生了什么，用于自助门户反查 customer、对账和排查。
-- 🚧 护栏：这两张表里的行是真实付费用户的状态映射，任何时候都不删、不清空。
CREATE TABLE IF NOT EXISTS paddle_customers (
  customer_id TEXT    PRIMARY KEY,  -- ctm_...
  user_id     INTEGER,              -- 归属账号；NULL = 还没匹配上（等 custom_data 或人工）
  email       TEXT    NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL      -- 取事件里的 updated_at：乱序投递时旧事件不许覆盖新状态
);

CREATE TABLE IF NOT EXISTS paddle_subscriptions (
  subscription_id         TEXT    PRIMARY KEY,  -- sub_...
  customer_id             TEXT    NOT NULL,
  status                  TEXT    NOT NULL,     -- active/trialing/past_due/paused/canceled
  price_id                TEXT    NOT NULL,
  product_id              TEXT    NOT NULL,
  scheduled_change_action TEXT,                 -- 将取消/将暂停：**不等于**已撤权
  scheduled_change_at     INTEGER,
  created_at              INTEGER NOT NULL,
  updated_at              INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_paddle_sub_customer ON paddle_subscriptions(customer_id);

-- §117 B：鹿角商店 —— 已解锁的商品（贴纸包 v1；将来主题包同表）
CREATE TABLE IF NOT EXISTS entitlements (
  user_id    INTEGER NOT NULL,
  item_id    TEXT    NOT NULL,      -- 'pack:dunhuang' / 'pack:cow' / 将来 'theme:xxx'
  price_paid INTEGER NOT NULL,      -- 成交鹿角数（Pro 免费领取 = 0，留审计线索）
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, item_id)
);

-- §128 B2：创始席位（kind: gift=创始100赠送 / buyout=¥19.9 批次买断）
CREATE TABLE IF NOT EXISTS founders (
  user_id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  seq INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

-- §128 F1：用户共建中心（报错/建议/定制；status: received/need_info/evaluating/planned/building/beta/shipped/declined/withdrawn）
CREATE TABLE IF NOT EXISTS feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  body TEXT NOT NULL,
  meta TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'received',
  reply TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback(user_id, id);

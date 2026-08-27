# 第七批 · 鹿角系统全局计划书 v1

> ⚠️ §128：本文价格与 UI 章节失效（「鹿角撤出 UI」已过时）；生成管线研究继续有效。
> 现行真值：economy.v1.json / pricing.v1.json。


> ⚠️ **2026-08-21 状态更新**：鹿角已**撤出 UI，转为纯内部计量**（用户不可见）；
> 旗舰档已下线；生图降为未来加分项。本文的账本设计（双桶/幂等/惰性发放）已在
> v1.4.0 实现并上线，其余生图管线部分**冻结待启**。UI 层面的鹿角展示章节已全部作废。

> 状态：**计划稿，未执行**。2026-08-21
> 关联：`docs/SUBSCRIPTION.md`（定价）、`docs/ROADMAP.md`（总表）、
> `LOOKA_Character_Stamp_Asset_Skill_V1.md`（资产规格）、`LOOKA_Theme_Stamp_Integration_Skill.md`
>
> 前置条件全部就绪：OpenRouter key ✅ · 供应商锁定 ✅ · 成本实测 ✅（¥0.002/张，真 alpha）

---

## 零、先修正一个认知：鹿角不再是「成本回收」，而是「闸门」

原计划书假设绘图成本高（估 ¥0.08–0.30/张），鹿角的作用是覆盖成本。
**实测把这个前提推翻了**：`gpt-5-image-mini quality:low` = ¥0.002/张，一包 24 枚 ≈ **¥0.05**。

按旧方案 Pro 每月送 100 鹿角，我们的真实成本是 **¥0.03**，毛利率 99.7%。
也就是说：**钱已经不是约束了。**

真正的约束变成两个：

| 约束 | 数量级 | 影响 |
|---|---|---|
| **生成耗时** | 60–75 秒/张 | 一包 24 枚 ≈ **25–30 分钟**。这是产品体验的主要矛盾 |
| **滥用风险** | — | 无限免费生成 = 白嫖算力、刷图床、生成违规内容 |

所以鹿角的定位要改写为：**排队配额 + 滥用闸门 + 增值感知**，而不是成本分摊。
这直接影响后面所有额度设计 —— 可以给得比原计划**慷慨得多**。

---

## 一、账本设计

### 1.1 双桶记账（核心决策）

赠送鹿角与购买鹿角**必须分开记账**：

| 桶 | 来源 | 过期 | 消耗顺序 |
|---|---|---|---|
| `granted` | 每月订阅赠送、活动赠送 | 有周期上限 | **优先扣** |
| `paid` | 用户真金白银买的 | **永不过期** | 后扣 |

先扣赠送是行业标准（Midjourney / Canva 同理）：保护用户已付费的部分，
否则"我买的鹿角被月底清零了"是必然的客诉与差评。

### 1.2 赠送额度上限而非清零

不做"月底清零"（制造焦虑、催生月末刷量），改为**累计上限**：
赠送桶余额达到上限后当月不再发放，用掉了下月补齐。既防囤积，又不制造浪费感。

### 1.3 额度（**已定案 2026-08-21**，依据「尽量让利于用户」最高原则）

| 档位 | 原计划 | **定案** | 月成本 | 理由 |
|---|---|---|---|---|
| 免费 | 20/月 | **60/月**（上限 120） | ¥0.06 | 60 鹿角 = 1 整包(48) + 6 张单图的余量，**免费用户能完整造完一包还能改**。这是核心体验，不该只给半程 |
| Pro | 100/月 | **200/月**（上限 400） | ¥0.2 | 成本可忽略，感知价值高 |

> 终身档已于 2026-08-21 取消（一次性买断 × 持续算力，结构不成立）。

外加两笔一次性赠送（成本合计不到 ¥0.1，但对新用户体感极强）：
- **首次生成成功 +20**（第一次必然成功，等于白送一次改稿机会）
- **连续 7 天写日记 +20**（轻运营，不做打卡断签惩罚）

原方案"免费 20 鹿角只够 10 张、做不出一整包"的卡点设计，是在**成本高**的前提下成立的。
成本崩塌后，这个卡点只剩下"恶心用户"的作用 —— **卡点应该换成"耗时"和"高级能力"**：
免费档能造包但排队靠后、不能用图生图（角色一致性）、不能出重主题。

### 1.4 消耗价目

| 动作 | 鹿角 | 备注 |
|---|---|---|
| 表情单张 | 2 | |
| 表情整包（24 枚） | 48 | 含 1 张样张 |
| **轻主题（纯色板 JSON）** | **0** | 零绘图成本，**免费无限出** —— 最好的留存工具 |
| 重主题（背景+图标+插画 ≈10 张） | 100 | |
| 图生图 / 角色一致性包 | **待定** | ⚠️ 成本未实测，见风险 R1 |

---

## 二、数据模型（D1）

```sql
-- 余额：读多写少，冗余存储避免每次 SUM 全表流水
CREATE TABLE antler_balance (
  user_id     TEXT PRIMARY KEY,
  granted     INTEGER NOT NULL DEFAULT 0,
  paid        INTEGER NOT NULL DEFAULT 0,
  grant_cycle TEXT,                        -- 'YYYY-MM'，防同月重复发放
  updated_at  INTEGER NOT NULL
);

-- 流水：审计与对账用，永久保留，不参与热路径
CREATE TABLE antler_ledger (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL,
  delta         INTEGER NOT NULL,          -- 正入负出
  bucket        TEXT NOT NULL,             -- granted | paid
  reason        TEXT NOT NULL,             -- monthly_grant|purchase|redeem|spend|refund|admin
  ref           TEXT,                      -- job_id / order_id / code
  balance_after INTEGER NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_ledger_user ON antler_ledger(user_id, created_at DESC);
-- 幂等锁：同一 reason+ref 只能入账一次（防重复发放/重复退款）
CREATE UNIQUE INDEX idx_ledger_ref ON antler_ledger(reason, ref) WHERE ref IS NOT NULL;

-- 生成任务
CREATE TABLE gen_jobs (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  kind       TEXT NOT NULL,   -- stamp_single|stamp_pack|theme_light|theme_heavy
  status     TEXT NOT NULL,   -- draft|sample_running|sample_done|running|done|failed|cancelled
  spec       TEXT NOT NULL,   -- JSON 设计稿（角色设定 + 动作列表 + 风格）
  held       INTEGER NOT NULL DEFAULT 0,  -- 已预扣鹿角
  total      INTEGER NOT NULL,
  done_count INTEGER NOT NULL DEFAULT 0,
  error      TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_jobs_pending ON gen_jobs(status, updated_at);

CREATE TABLE gen_items (
  id       TEXT PRIMARY KEY,
  job_id   TEXT NOT NULL,
  idx      INTEGER NOT NULL,
  prompt   TEXT NOT NULL,
  status   TEXT NOT NULL,     -- pending|running|ok|bad|failed
  attempts INTEGER NOT NULL DEFAULT 0,
  r2_key   TEXT,
  qc       TEXT,              -- JSON 质检结果
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_items_job ON gen_items(job_id, idx);
```

**新增 R2 桶** `looka-assets`，key 规范 `gen/{user_id}/{job_id}/{idx}.webp`。
（现有 `looka-apk` 只放安装包，不混用。）

**余额与流水必须原子更新** —— 用 D1 的 `batch()` 把 balance UPDATE 与 ledger INSERT 放同一批次。

---

## 三、生成管线

### 3.1 状态机

```
对话捏设定 ──► draft（设计稿，可反复改，0 鹿角）
                 │  用户点「出样张」（扣 2）
                 ▼
            sample_running ──► sample_done ──┬─► 用户不满意 → 回 draft（已扣的 2 不退，改稿免费）
                                             │
                                             └─► 确认（扣剩余 46）
                                                    ▼
                                                 running ──► done
                                                    │
                                                    └─► failed → 退还未产出部分
```

**样张确认是必须的**：24 张要跑近半小时，让用户先花 2 鹿角看一张，
比跑完半小时才发现风格不对要好太多。这也是 Skill 文档里"角色一致性"的把关点。

### 3.2 异步执行（技术选型 —— 需实测）

生成 60–75 秒/张，**绝不能同步等**。CF 上三条路：

| 方案 | 成本 | 评价 |
|---|---|---|
| Cloudflare Queues | 需 Workers Paid（$5/月） | 最正统，重试/DLQ 都现成 |
| Durable Objects | 需付费 | 适合有状态编排，杀鸡用牛刀 |
| **Cron + D1 任务表轮询** | **免费** | ⭐ 建议先用这个 |

Cron 方案：加一个 `* * * * *`（每分钟）触发器，每次拉 1–2 个 `pending` item 生成。
24 张 ≈ 15–25 分钟完成 —— 与生成本身的耗时同数量级，用户体验无损。

> ⚠️ **必须先实测**：CF Worker 的 scheduled handler 对 wall-clock 和子请求超时的实际限制。
> 若单次 Cron 撑不住一张图的 75 秒，就退到"每次只发起、下次再取回"的两段式，
> 或直接上 Workers Paid 用 Queues（$5/月，我们迟早要付）。
> **这是 7C 的第一个待验证项，不要在没测之前写完整实现。**

### 3.3 自动质检（QC）

按 `LOOKA_Character_Stamp_Asset_Skill_V1.md` 的验收清单，但**分两级**：

**机器硬检（v1 只做这两项）**
- ✅ 尺寸 = 256×256
- ✅ **真 alpha 通道**（存在透明像素，且四角为透明）

  ↑ 这一项是血泪教训：gemini-3.1-flash-image 会把棋盘格**画进像素**（mode=RGB 无 alpha），
  肉眼看着是透明的，导入后是一张带格子的图。必须机器挡。

**人眼软检（交给样张确认环节）**
- 主体占比、构图居中偏上、无边框、无文字、角色一致性

理由：像素级构图分析在 Worker 里做代价高、误判率高；而样张机制天然提供了人眼把关点。
不合格自动重试 ≤2 次，仍失败则该 item 标 `failed` 并退还对应鹿角。

### 3.4 防滥用

- 生成限速：每用户 **每小时 ≤2 个 job、每日 ≤5 个 job**
- Prompt 过滤：涉政 / 涉黄 / 知名 IP（皮卡丘、米老鼠…）关键词拦截 + 拒绝理由回显
- 鹿角本身即闸门
- R2 存储配额：每用户生成资产上限（如 500 张），超出提示清理

---

## 四、服务端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/antler` | 余额（granted/paid）+ 最近 50 条流水 |
| POST | `/api/gen/design` | 对话 → 结构化设计稿（走现有 AI 通道，0 鹿角） |
| POST | `/api/gen/sample` | 建 job + 出样张（扣 2） |
| POST | `/api/gen/confirm` | 确认设计稿 → 批量排队（扣剩余） |
| GET | `/api/gen/job/{id}` | 轮询进度 `{status, done_count, total, items:[...]}` |
| POST | `/api/gen/cancel/{id}` | 取消，退还未产出部分 |
| GET | `/api/gen/assets` | 我的已生成资产（供"我的"Tab） |
| GET | `/api/store/packs` | 商店：官方包 + 主题 |
| POST | `/api/store/buy` | 用鹿角购买官方包 |
| POST | `/api/antler/redeem` | 鹿角兑换码 |

**兑换码表要改**：现有 `codes` 只支持订阅天数，需加 `type TEXT ('plan'|'antler')` 与 `amount INTEGER`。
（这条在 SUBSCRIPTION.md 里已挂了 TODO。）

---

## 五、客户端

**安卓**
- 账号页：鹿角余额卡片（分显赠送/购买 + 到期提示）→ 点进流水页
- 新页「小鹿画室」：对话捏设定 → 设计稿卡片 → 样张预览 → 进度条（可后台）→ 完成入库
- 表情选择器：新增 **「我的」Tab**（现有 StickerPicker 的 tabs 结构直接加一项即可）
- 生成完成走通知（复用现有 NotifyScheduler）

**网页**：同构简版，进度轮询复用同一接口。

---

## 六、上线顺序（拆 4 个子阶段，每阶段可独立发布）

| 阶段 | 内容 | 风险 | 可独立上线 |
|---|---|---|---|
| **7A** | 鹿角账本：余额/流水/月度发放/兑换码/账号页展示 | 低 | ✅ 无绘图也有意义 |
| **7B** | 单张生成打通：OpenRouter → QC → R2 → 「我的」Tab | 中 | ✅ |
| **7C** | 批量管线：Cron 驱动、样张确认、失败退还、限速 | **高**（异步方案待实测） | ✅ |
| **7D** | 商店 + 主题：轻主题（0 鹿角）先上，重主题后上 | 中 | ✅ |

**强烈建议先把 7A 单独上线**：它不碰绘图，能提前把账本、幂等、对账这些最容易出错
又最难事后补救的地方跑稳；等 7B/7C 出问题时，账不会乱。

---

## 七、风险清单

| # | 风险 | 应对 |
|---|---|---|
| **R1** | **图生图成本未实测** —— 角色一致性要 vision 输入，可能贵 10 倍以上 | **7B 第一件事就是实测**，出数before定价。不要套用 ¥0.002 |
| **R2** | CF Worker 长任务限制，Cron 方案可能跑不动 75 秒 | 先做技术验证 spike；退路是 Workers Paid + Queues（$5/月） |
| **R3** | 生成 25–30 分钟，用户以为卡死 | 进度条 + 可后台 + 完成推送；样张先给即时反馈 |
| **R4** | 违规内容 / 侵权 IP | prompt 过滤 + 生成物人工抽查 + 举报入口 |
| **R5** | 计费方式一旦公布极难变更 | **首发即代币制**，不做"张数制→代币制"的中途切换 |
| **R6** | 重复发放 / 重复退款 | ledger 的 `(reason, ref)` 唯一索引 + D1 batch 原子写 |

---

## 八、待你拍板

| # | 问题 | 结论 |
|---|---|---|
| **D14** | 免费档月赠额度 | ✅ **已定 60/月**（上限 120），另加首次成功 +20、连续 7 天日记 +20 |
| **D15** | 赠送鹿角过期策略 | ✅ **已定：不清零，设累计上限**（免费 120 / Pro 400）；购买的永不过期 |
| **D16** | 免费档限制换成什么 | ✅ **已定：排队靠后 + 不能图生图 + 不能出重主题**，不卡基础额度 |
| **D17** | 是否先只做 7A 上线 | 建议是。账本跑稳再碰绘图 —— **待你确认** |
| **D18** | 要不要现在就上 Workers Paid（$5/月） | 先做 Cron spike，撑不住再上 —— **待你确认** |
| **D19** | 图生图定价 | ⏸ 阻塞于 R1 成本实测，测完再定 |

> D14–D16 依据 2026-08-21 定下的最高原则「尽量让利于用户」直接结案，
> 判据见 `docs/SUBSCRIPTION.md` 第一节第 0 条。

---

## 九、本计划书不包含

- 具体代码实现（本文件是计划，`不执行` 是明确要求）
- 第八批小程序（等企业主体）
- 支付通路（见 `docs/SUBSCRIPTION.md` 第四节，Creem 双轨方案）

---

## 十、第七批实际完成度（2026-08-21 核对）

用户问「第七批全部做完了吗」——**没有，只做完了 1/4**。

| 阶段 | 内容 | 状态 |
|---|---|---|
| **7A** | 鹿角账本：双桶记账 / 惰性月度发放 / 流水 / 兑换码 / `GET /api/antler` | ✅ **v1.4.0 已上线** |
| 7A+ | 模型分档计价（标准0 / 高级1 / 旗舰5）+ 余额不足优雅回落 | ✅ 已上线（超出原计划） |
| **7B** | 单张 AI 生图打通：OpenRouter → QC → R2 → 「我的」Tab | ❌ 未开始 |
| **7C** | 批量生成管线：Cron 驱动 / 样张确认 / 自动质检 / 失败退还 | ❌ 未开始 |
| **7D** | 商店 + 主题制作上传 | ❌ 未开始 |

且 7B–7D 已在 2026-08-21 的批次重排中**移到最后**（AI 生图降为加分项）。
当前优先级：第八批外观 → 第九批 AI 融入 → 第十批年度回顾 → …… → 最后 7B–7D。

**注意**：用户新提出的「自己制作和上传主题」属于 **7D**，是 Pro 权益里
唯一还需要生成/上传管线的部分，与外观主线直接相关，可考虑从 7D 中拆出来提前做。

---

## 十一、OpenRouter 免费模型评估（2026-08-21 实测）

用户问「OpenRouter 有免费模型吗？给免费用户打底」——**有 21 个，但不能用作打底。**

### 实测结果（从 Worker 内部发起）

| 模型 | 结果 |
|---|---|
| `z-ai/glm-5.2:free` | ✅ 200，但未返回正文 |
| `google/gemma-4-31b-it:free` | ❌ Provider returned error |
| `openai/gpt-oss-20b:free` | ✅ 200（6.7s），但未返回正文 |

### 三条否决理由

1. **🔴 每日上限是账号级的，不是每用户**（官方文档硬数字）：
   `FREE_MODEL_RATE_LIMIT_RPM = 20`、`FREE_MODEL_NO_CREDITS_RPD = 50`、
   `FREE_MODEL_HAS_CREDITS_RPD = 1000`（余额 ≥$10 时）。
   **我们整个站每天只有 1000 次** —— 100 个用户每人聊 10 次就打满。完全不可用。
2. **不稳定** —— 3 个里 1 个直接报错，2 个没吐正文。
3. **数据策略** —— OpenRouter 账号设置里 paid / free 模型的「允许训练」是**两个独立开关**；
   免费端点更可能路由到会用 prompt 训练的供应商。我们要把用户的**日程和日记**发过去，不合适。

### 结论：我们已经有免费打底了

**硅基流动的 `Qwen/Qwen3.5-35B-A3B`（当前的「标准」档）就是免费打底** ——
对用户免费不限次、对我们边际成本≈0、走自建通道无账号级日上限、数据不进第三方训练池。
不需要引入 OpenRouter 的免费模型。

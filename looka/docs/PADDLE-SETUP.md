# Paddle 接入操作手册（§133）

> 这份文档是**后台操作**与**凭据配置**的唯一清单。代码侧设计见 `FEATURE-PLAN.md` §133。
> 所有金额以最小面额字符串表示（$5.00 = `"500"`，¥12.00 = `"1200"`）。

---

## 🚧 护栏：以下实体永不删除

webhook notification destination 与其 signing secret · 所有 products 与 prices ·
Paddle 与我方库里的任何 customer / subscription / transaction 行。

这些是运行中的基础设施，不是测试残留。它们驱动 `transaction.completed` /
`subscription.*` / `customer.*` 事件，也就是整条履约链路 —— 删掉就等于把已付费用户的
开通链路打断。任何"清理""收尾"都不包含它们。

---

## 零、⚠️ 上线前必做一次：建两张镜像表

`scripts/release.sh` **不执行** schema —— 只有 `server/deploy.sh` 会跑 `d1 execute --file=schema.sql`，
而 schema.sql 里有 3 条 `ALTER TABLE codes ADD COLUMN`（**不幂等**，重跑会报 duplicate column），
所以整份文件不能反复执行。新表用定向命令建，安全且可重复：

```bash
cd server
npx wrangler d1 execute looka-db --remote -y --command "
CREATE TABLE IF NOT EXISTS paddle_customers (
  customer_id TEXT PRIMARY KEY, user_id INTEGER, email TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS paddle_subscriptions (
  subscription_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, status TEXT NOT NULL,
  price_id TEXT NOT NULL, product_id TEXT NOT NULL,
  scheduled_change_action TEXT, scheduled_change_at INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_paddle_sub_customer ON paddle_subscriptions(customer_id);"
```

不建这两张表的后果：webhook 收到第一个事件就 500，Paddle 会持续重试直到放弃。

---

## 一、SKU 全表（5 个产品 / 9 个价格）

| # | 产品 | 价格 | 类型 | 环境变量 |
|---|---|---|---|---|
| 1 | Looka Pro（海外订阅） | $5/月 · $50/年 + GB/IE/AU 覆盖 | recurring | `PADDLE_PRICE_PRO_MONTH` / `_YEAR` |
| 2 | Looka Pro 通行证 | ¥12 月卡 · ¥98 年卡 | one-time | `PADDLE_PRICE_PASS_MONTH` / `_YEAR` |
| 3 | Looka 鹿角 1000 | $2.99 + CN ¥18 | one-time | `PADDLE_PRICE_ANTLER_1000` |
| 4 | Looka 鹿角 3000 | $6.99 + CN ¥48 | one-time | `PADDLE_PRICE_ANTLER_3000` |
| 5 | Looka Founder 限量买断 | ¥19.9 | one-time | `PADDLE_PRICE_FOUNDER` |

**不建**：¥6 / $0.99 最小鹿角包 —— Paddle 标准费率下收 $0.84 要交 $0.54（约 64%），
净得 $0.30。等拿到低价报价再建；护栏规定建了不能删，所以宁可晚建。

---

## 二、建品提示词

### 提示词 A —— 产品 1（海外订阅）

> ⚠️ 若你上一轮**已经跑过**含 CN ¥12/¥98 覆盖的版本：按护栏**不要删**那两个覆盖，
> 留着无害，我们只是不把中国用户路由到订阅价。此时**跳过提示词 A**，直接跑 B。

```
I'm setting up billing for Looka, a personal planner app (calendar, tasks,
notes, diary) with a built-in AI assistant. Create ONE product:

- Name: Looka Pro
- Description: Looka Pro subscription — 50 daily AI credits, free official
  themes, priority support.
- Tax category: SaaS

Under it, create TWO recurring prices with USD as the base currency:

1) "Looka Pro — Monthly", billing interval: 1 month
   Base price: USD 5.00 → "500"
2) "Looka Pro — Yearly", billing interval: 1 year
   Base price: USD 50.00 → "5000"

Do NOT add a free trial to either price. There is no trial period.
Set both prices as tax-inclusive (tax_mode = internal).

Country-specific price overrides on BOTH prices:

Monthly:  GB (GBP) £4.49 → "449" | IE (EUR) €4.99 → "499" | AU (AUD) A$7.99 → "799"
Yearly:   GB (GBP) £44.99 → "4499" | IE (EUR) €49.99 → "4999" | AU (AUD) A$79.99 → "7999"

Do NOT create a CNY override on these subscription prices — China is served by
separate one-time products.

Amounts are in the currency's smallest denomination, as strings.
Do not create any other products or prices.
When done, list the product and every price with its Paddle ID (pro_… / pri_…),
currency, amount and billing interval, so I can map them in my backend.
```

### 提示词 B —— 产品 2-5（一次性商品）

```
Same account, same app (Looka). Now create FOUR more products, all with
ONE-TIME prices (no billing interval, non-recurring). Tax category: SaaS for
the passes, standard digital goods for the credit packs. Set all prices
tax-inclusive (tax_mode = internal). No trials anywhere.

PRODUCT 1 — "Looka Pro Pass"
Description: One-time pass granting Looka Pro benefits for a fixed period —
50 daily AI credits, free official themes, priority support. Does not auto-renew.
Two one-time prices, base currency CNY:
  a) "Looka Pro Pass — 30 days", CNY ¥12.00 → "1200"
     overrides: US (USD) $2.00 → "200"
  b) "Looka Pro Pass — 12 months", CNY ¥98.00 → "9800"
     overrides: US (USD) $16.00 → "1600"

PRODUCT 2 — "Looka Antler 1000"
Description: 1,000 Deer Antler credits for the Looka AI assistant. One-time
purchase, credits never expire.
One one-time price, base currency USD: $2.99 → "299"
  overrides: CN (CNY) ¥18.00 → "1800" | GB (GBP) £2.49 → "249"
             IE (EUR) €2.99 → "299"   | AU (AUD) A$4.99 → "499"

PRODUCT 3 — "Looka Antler 3000"
Description: 3,000 Deer Antler credits for the Looka AI assistant. One-time
purchase, credits never expire.
One one-time price, base currency USD: $6.99 → "699"
  overrides: CN (CNY) ¥48.00 → "4800" | GB (GBP) £5.99 → "599"
             IE (EUR) €6.99 → "699"   | AU (AUD) A$10.99 → "1099"

PRODUCT 4 — "Looka Founder"
Description: Limited founder buyout — permanent Looka Pro benefits for a
one-time payment. Limited number of seats.
One one-time price, base currency CNY: ¥19.90 → "1990"

Amounts are in the currency's smallest denomination, as strings.
Do not modify or delete anything that already exists in this account.
When done, list every product and price you created with its Paddle ID
(pro_… / pri_…), currency and amount, so I can map them in my backend.
```

> 通行证的 USD 覆盖（$2 / $16）是给「海外但想一次性买断周期」的用户兜底用的，
> 定得比订阅（$5/$50）低是因为它不自动续费、生命周期价值更低。如果你不想在海外卖通行证，
> 把那两行 `overrides` 删掉即可，中国区不受影响。

---

## 三、后台操作清单（我做不了，需要你点）

1. **Developer tools → Notifications** 建 notification destination
   - URL：`https://looka.foyue.org/api/pay/paddle/<PADDLE_HOOK_PATH>`
     （`<PADDLE_HOOK_PATH>` 是我们自己生成的随机段，见下节密钥表）
   - 订阅事件：`transaction.completed`、`subscription.created`、`subscription.updated`、
     `subscription.canceled`、`customer.created`、`customer.updated`
   - 建完**回读 signing secret** —— 它与 API key 是两个东西，验签用的是 signing secret
2. **Checkout → Checkout settings** → default payment link 设为
   `https://looka.foyue.org/pay.html`
   - Live 必须是**已审核通过的真实域名**，填 localhost 会让结账直接失败
   - sandbox 下 localhost 可用于本地自测
3. 商户信息表单：粘贴第五节文案

---

## 四、密钥与变量

非密（`server/wrangler.jsonc` 的 `vars`，进 git）：

```
PADDLE_ENV                 production | sandbox   ← 不设默认值，缺失时服务端直接 500
PADDLE_PRICE_PRO_MONTH     pri_...
PADDLE_PRICE_PRO_YEAR      pri_...
PADDLE_PRICE_PASS_MONTH    pri_...
PADDLE_PRICE_PASS_YEAR     pri_...
PADDLE_PRICE_ANTLER_1000   pri_...
PADDLE_PRICE_ANTLER_3000   pri_...
PADDLE_PRICE_FOUNDER       pri_...
```

密钥（`wrangler secret put`，不进 git）：

```bash
cd server
wrangler secret put PADDLE_CLIENT_TOKEN     # live_... 客户端 token（公开型，但不放仓库）
wrangler secret put PADDLE_API_KEY          # 服务端专用，任何前端代码都不得出现
wrangler secret put PADDLE_WEBHOOK_SECRET   # notification destination 的 signing secret
wrangler secret put PADDLE_HOOK_PATH        # 自己生成的随机段，如 openssl rand -hex 16
```

本地开发把同名项写进 `server/.dev.vars`（已在 `.gitignore`；样例见 `.dev.vars.example`）。

---

## 五、商户信息文案

### "你打算在 Paddle 上卖什么？"（简要）

> Digital subscriptions and one-time digital credits for Looka, our personal planner app.
> Nothing physical ships; everything is delivered instantly to the customer's signed-in account.

### "请详细介绍一下您的产品"（详细）

> **Looka** is a personal planner for Android and the web — calendar, to-do lists, notes and a
> daily diary — with a built-in AI assistant called Deer (小鹿) that turns plain-language
> requests ("move tomorrow's 3pm meeting to Friday") into real changes in the user's own data.
> Accounts sync between the Android app and looka.foyue.org.
>
> Looka is free to use, with no time limit. The free tier includes the full planner — calendar,
> tasks, notes, diary, reminders, cloud sync and data export — plus 10 daily AI credits.
>
> **What we sell on Paddle:**
>
> 1. **Looka Pro — subscription (monthly / annual).** Raises the daily AI allowance from 10 to
>    50 credits, unlocks official theme packs at no credit cost, and adds priority support and
>    beta access. Customers can cancel any time from the Paddle customer portal and keep all of
>    their content and previously unlocked themes afterwards.
> 2. **Looka Pro Pass — one-time 30-day and 12-month passes.** The same benefits as Pro, bought
>    once rather than auto-renewing. Offered in markets where local payment methods do not
>    support recurring billing.
> 3. **Deer Antler credit packs — one-time consumable credits** (1,000 and 3,000) for the AI
>    assistant. Purchased credits never expire and are separate from the daily free allowance.
>
> All items are digital and fulfilled automatically: our server verifies the Paddle webhook
> signature and grants the entitlement to the buyer's account within seconds. There is no
> physical product, no shipping, and no third-party marketplace involved — we are the sole
> publisher of the app.
>
> Company: Foyue (foyue.org) · Support: looka01@qq.com
> Terms: https://looka.foyue.org/terms.html · Privacy: https://looka.foyue.org/privacy.html

### 中文版（表单要中文时用）

> Looka（小鹿手帐）是一款安卓与网页端的个人手帐应用 —— 日历、待办、笔记、日记，
> 内置名为「小鹿」的 AI 助手，能把自然语言请求直接落成用户自己数据里的改动，
> 安卓 App 与 looka.foyue.org 同账号云同步。
>
> 免费版永久可用，含全部手帐功能（日历、待办、笔记、日记、提醒、云同步、数据导出）
> 与每天 10 枚鹿角（AI 额度）。
>
> 在 Paddle 上销售三类数字商品：
> ① **Looka Pro 订阅**（月付 / 年付）：每日鹿角提到 50 枚、官方装扮 0 鹿角领取、
> 优先支持与 Beta 邀请；可随时在 Paddle 自助门户取消，取消后内容与已领装扮全部保留。
> ② **Pro 通行证**（一次性 30 天 / 12 个月）：权益同 Pro，不自动续费，
> 面向本地支付方式不支持自动续费的市场。
> ③ **鹿角补充包**（1000 / 3000）：一次性购买的 AI 额度，永不过期，与每日免费额度分开计。
>
> 全部为数字商品：服务端校验 Paddle webhook 签名后，数秒内自动开通到买家账号，
> 无实物、无物流、无第三方分销，我们是该应用的唯一发行方。
>
> 公司：佛乐 foyue.org · 客服：looka01@qq.com
> 条款：looka.foyue.org/terms.html · 隐私：looka.foyue.org/privacy.html

---

## 六、支付方式说明（查证结论）

- **支付宝可用**：`settings.allowedPaymentMethods` 支持 `alipay`
  （出处：`developer.paddle.com/paddlejs/methods/paddle-checkout-open`，原文
  "Alipay, popular in China"）。中国区收银页会显式带上它。
- **微信支付不可用**：同一份文档的完整列表里没有 WeChat
  （`card / paypal / alipay / apple_pay / google_pay / ideal / kakao_pay / mb_way /
  naver_pay / payco / pix / samsung_pay / upi / bancontact / blik /
  south_korea_local_card / saved_payment_methods`）。
- **支付宝能否自动续费：未查证**（相关文档页 404）。这正是中国区改用**一次性通行证**
  而不是订阅的原因 —— 不赌一个没有凭据的能力。日后若确认支持，再评估是否开 CNY 订阅。

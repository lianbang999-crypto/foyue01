# 爱发电 / Ko-fi 页面文案 · 正式版

> 2026-08-21 · 国内 ¥12/月 · ¥98/年 ｜ 海外 $7/月 · $70/年
> 🚧 标记 = 开发中，上线后自动解锁、不另收费

---

# 🇨🇳 爱发电

## 方案 1 · 月度

**名称**
```
Looka Pro · 月度
```

**价格**
```
12 元 / 月
```

**方案描述**（整段粘贴）
```
Looka 是一个安静的手帐 App —— 日历、待办、笔记、日记，加一只叫小鹿的 AI 助手。
它不催你，不给红色角标，不做连续打卡。名字取自敦煌壁画《鹿王本生图》里的九色鹿。

【这些一直免费，不会变成付费项】
全部手帐功能 · 多端云同步（手机与网页）· 数据导出（JSON / 日历 / Markdown）
小鹿 AI 对话不限次 · 104 枚官方表情 · 九色主题 · 密码锁 · 小组件

【开通 Pro，你会得到】

🦌 更聪明的小鹿
   解锁 GPT 高级模型，规划、总结、拆解任务都更准
   🚧 拍张照就能建日程 —— 会议通知、海报、机票，拍下来自动提取时间地点
   🚧 写完日记，小鹿帮你挑一枚今天的表情贴到日历上

🎨 做自己的手帐
   🚧 自创主题配色，调成你喜欢的样子
   🚧 上传自己的封面纸与背景
   🚧 自定义 App 图标
   🚧 全部主题与表情包，每月还有新的

📖 更深的用法
   🚧 智能清单与自定义筛选
   🚧 模板库（日记模板 / 日程模板，也能自己建）
   🚧 年度回顾长图 —— 一年写了多少、去过哪里，一张图看完
   🚧 作品导出：排版精美、可打印的 PDF，贴进你的实体本

🌱 内测特权
   新功能抢先体验，你的建议直接决定下一个版本做什么

🚧 标记的功能正在开发中，上线后自动解锁，不会另外收费。

【关于付费，我们的两个承诺】

不偷偷扣你钱。订阅可随时取消，取消后立即不再扣款；
不想被自动续费，也可以只买一次性的月卡或年卡。
到期不收回。你做的主题、写的内容、导出的东西，到期后照样能用。

【重要】下单时请在留言里填写你的 Looka 账号邮箱，
我们会在 24 小时内把开通码发到该邮箱。
（从 App 内点「开通 Pro」跳转的话，备注会自动填好，不用手动输入。）
```

## 方案 2 · 年度

**名称**
```
Looka Pro · 年度
```

**价格**
```
98 元 / 年
```

**方案描述**：与月度相同，把开头加一句：
```
年付相当于每月 8.2 元，比月付省两个多月。
```

## 主页简介
```
Looka · 可爱版九色鹿
一个不催你的手帐 App。日历 / 待办 / 笔记 / 日记，加一只叫小鹿的 AI。
名字取自敦煌壁画《鹿王本生图》里的九色鹿。
looka.foyue.org
```

---

# 🌍 Ko-fi

## Membership tier

**Name**
```
Looka Pro
```

**Price**
```
$7 / month
```

**Description**（整段粘贴）
```
Looka is a calm journal app — calendar, tasks, notes and diary, with a little
deer called Looka helping out. No streaks. No red badges. No nagging.
Named after the nine-coloured deer from the Dunhuang cave murals.

[ALWAYS FREE — these will never move behind a paywall]
Every journal feature · Sync across phone and web · Full data export
(JSON / Calendar / Markdown) · Unlimited chat with the deer
104 official stickers · Nine themes · Passcode lock · Widgets

[WITH PRO YOU GET]

🦌 A smarter deer
   Unlocks the premium GPT model — better planning, summaries, task breakdowns
   🚧 Snap a photo to create an event — posters, invites, boarding passes
   🚧 After you journal, the deer picks a sticker for your day

🎨 Make it yours
   🚧 Custom theme colours
   🚧 Upload your own cover papers and backgrounds
   🚧 Custom app icon
   🚧 Every theme and sticker pack, plus new ones each month

📖 Go deeper
   🚧 Smart lists and custom filters
   🚧 Template library for journals and events
   🚧 Year-in-review poster — your whole year in one image
   🚧 Print-ready exports — beautiful PDFs to paste into your paper journal

🌱 Beta access
   Try new features first, and your suggestions decide what gets built next

🚧 marks features in development. They unlock automatically when they ship,
at no extra charge.

[TWO PROMISES ABOUT PAYING]

No sneaky charges. Cancel anytime and billing stops immediately —
or skip subscriptions entirely with a one-time month/year pass.
Nothing gets taken back. Themes you made and things you wrote stay yours.

[IMPORTANT] Please put the email address of your Looka account in the order
note. We'll send your activation code within 24 hours.
```

## Shop item（年付，可选）
```
Name:  Looka Pro · 1 Year
Price: $70
Desc:  12 months of Looka Pro — two months free compared to monthly.
       Please include your Looka account email in the order note.
```

## Page bio
```
Looka — a calm journal app. Calendar, tasks, notes and diary, with a little
deer to help. Named after the nine-coloured deer of the Dunhuang murals.
looka.foyue.org
```

---

# 运营须知

## 开通流程（人工，零代码）

1. 收到订单 → 看留言里的邮箱
2. 生成兑换码：
   ```bash
   curl -X POST https://looka.foyue.org/api/admin/gencode \
     -H "Authorization: Bearer <任意已登录token>" -H 'Content-Type: application/json' \
     -d '{"key":"<ADMIN_KEY>","count":1,"days":31}'      # 年度用 days=366
   ```
3. 把码发到该邮箱
4. 用户在 App「更多 → 账号与同步 → 兑换码」输入

## 爱发电接入状态

已有：Token ✅ · 方案 `plan_id=95141ca09d2711f1bead52540025c377` ✅ · 支付链接 ✅

### ❗ 还缺 user_id
开发者后台给的是**一对**凭证（`user_id` + `token`），只有 token 签不了名。
取回后填进 `server/.secrets.txt`。

### ✅ 自动续费矛盾已解决（2026-08-21）：双轨并卖
Ko-fi Membership 与爱发电「方案」本质都是自动续费订阅，与旧承诺「不自动续费」冲突。
**解法：两种都提供，让用户自己选** ——
① 订阅档（自动续费，给想省事的人）② 一次性月卡/年卡（Ko-fi Shop / 爱发电商品）。
承诺文案已改为每句都能兑现的版本（见上方「两个承诺」）。
**待办**：爱发电加建「月卡 ¥12」「年卡 ¥98」两个商品（product_type=1）；
Ko-fi 加建 Shop item「1 Month $7」「1 Year $70」。

### 💡 预填 remark（强烈建议做进 App）
支付链接的 `remark=` 参数**可以预填**：

```
https://ifdian.net/order/create?plan_id=95141ca09d2711f1bead52540025c377
  &product_type=0&remark=<用户账号邮箱>&affiliate_code=&fr=afcom
```

App 内点「开通 Pro」时把当前账号邮箱拼进去 → 用户零输入，我们零猜测。
这是整条链路最容易出错的一环，几行代码就能消除。

## Ko-fi 接入状态

### ⚠️ 两处必须先确认
- **收款要绑 PayPal**（Stripe 不支持中国大陆主体）
- **先跑通一笔小额提现**，确认美元能提回国内卡再正式开卖

## 准备清单

- [ ] 爱发电：取回 user_id
- [ ] 爱发电：确认/关闭自动续费
- [ ] 爱发电：填月度 + 年度两个方案文案
- [ ] Ko-fi：确认绑定 PayPal
- [ ] Ko-fi：填 Membership 文案（+ 可选年付 Shop item）
- [ ] 两边各自下一单最小额测试，走完整流程
- [ ] 确认两边的退款操作（不做无理由退款，但服务故障/重复扣款要能退）
- [ ] **App / 网页加「开通 Pro」入口**，按语言分流（中文→爱发电，其余→Ko-fi），爱发电链接预填 remark

## 自动发码：先别做

爱发电有 Webhook 与 `query-order`，Ko-fi 也有 Webhook。
但订单量为零时人工发码 5 分钟搞定，接回调却要处理验签、幂等、重投、退款回滚。
**等人工发码开始占用时间了再做。**

⚠️ 届时验签格式以各自开发者后台文档为准 —— 爱发电文档被 Cloudflare 挡住，
我未能核实，别用凭记忆的字段顺序。

---

# 支付通道状态（2026-08-21 更新）

## 客服邮箱：`looka01@qq.com`

对外统一用这个。**但目前代码里写的还是旧地址 `lebang001@qq.com`**，共 3 处要改：

| 文件 | 位置 |
|---|---|
| `server/public/privacy.html:52` | 隐私政策的联系方式 |
| `app/src/main/java/com/looka/app/ui/more/ExtraScreens.kt:129` | 订阅页的联系方式 |
| `i18n/dict-en.tsv:562` | 上一条对应的英文词条 |

> 🔍 顺带发现一条**废弃词条**：`i18n/dict-en.tsv:27` 还留着
> 「Pro：¥12/月 · ¥98/年 · **终身版 ¥198 限量 100 份**」——
> 源码里这句已经删了（终身档已取消），字典里是孤儿条目。一并清掉。

### ✅ Resend 域名验证：实测已通过（2026-08-21 更正）

此前多份文档把「Resend 域名未验证」列为红色阻断项 —— **实测发现是错的，早就配好了。**

实测：用 `noreply@foyue.org` 发信到 `looka01@qq.com`，Resend 接受并返回 message id
（未验证域名会被直接拒绝）。DNS 里也确有记录：

| 记录 | 现状 |
|---|---|
| `resend._domainkey.foyue.org` TXT（DKIM） | ✅ 已配 |
| `send.foyue.org` TXT（SPF `include:amazonses.com`） | ✅ 已配 |
| `send.foyue.org` MX（`feedback-smtp.ap-northeast-1.amazonses.com`） | ✅ 已配 |
| `_dmarc.foyue.org` TXT | ❌ 缺（**非验证必需**） |

**结论：自动发码通道是通的**，不再是阻断项。

#### 但建议补一条 DMARC —— 这条对国内用户很关键

QQ 邮箱、163 对 DMARC 敏感，缺了容易进垃圾箱甚至被拒。
而我们的用户和客服邮箱都在 QQ 上。到 Cloudflare DNS 加一条 TXT（**灰云 · 仅 DNS**）：

```
名称：_dmarc
类型：TXT
内容：v=DMARC1; p=none; rua=mailto:looka01@qq.com
```

`p=none` 是观察模式，不影响现有投递，先收一段时间报告再决定要不要收紧到
`p=quarantine`。**这是提升到达率，不是修故障。**

#### ⚠️ 顺带：当前 Resend key 是 send-only
调 `/domains` 接口会被拒（`This API key is restricted to only send emails`）。
要在代码里管理域名得另建全权限 key —— 但我们不需要，保持 send-only 更安全。

---

## Ko-fi：❌ 链接类型不对，需重建

用户第二次提供：`https://ko-fi.com/looka2026/commissions`
—— **URL 直接写着 `/commissions`，确认是「委托定制」而不是「会员订阅」。**

Commission 是"接单做定制"的产品形态：一次性、按件、需要你交付作品，
**不会按月续费，也不适合承载 Pro 权益**。

**要做的**：在 Ko-fi 后台找到 **Memberships**（会员）板块，
开启并新建一个 tier —— 名称 `Looka Pro`、价格 `$7/月`、
描述用本文件上方的 Ko-fi 文案。建好后的链接形如 `ko-fi.com/looka2026/tiers`。

原来的 Commission 可以删掉，或留着当"定制主题代做"之类的另一门生意（与 Pro 无关）。

（下方为原始判断依据，保留备查）

Ko-fi 的链接约定大致是：

| 路径 | 是什么 |
|---|---|
| `ko-fi.com/<用户名>` | 主页 |
| `ko-fi.com/<用户名>/tiers` | **会员（订阅）** ← 我们要的 |
| `ko-fi.com/s/<id>` | 商店商品（一次性） |
| **`ko-fi.com/c/<id>`** | **Commission（委托定制）** ← ⚠️ 可能不是我们要的 |

**请你确认这个链接在后台属于哪一类：**

- 如果是 **Membership / 会员订阅** → 正确，直接用
- 如果是 **Commission（委托）** → 那是"接单做定制"的产品类型，
  **不是订阅**，用户买了不会按月续，也不适合承载 Pro 权益 → 需要改建成 Membership
- 如果是 **Shop 商品** → 可以当"年付一次性"用，但月度订阅仍要单独建 Membership

> 判断方法：在 Ko-fi 后台看这个条目是在
> **Memberships** 标签下，还是 **Commissions / Shop** 标签下。

---

## 两个通道的当前完整状态

| | 爱发电 | Ko-fi |
|---|---|---|
| 账号 | ✅ | ✅ |
| 方案已建 | ✅ ¥12/月 + ¥98/年 | ⚠️ 类型待确认 |
| 凭证 | Token ✅ / **user_id ❌ 缺** | — |
| 支付链接 | ✅ 含可预填的 `remark` | ⚠️ 待确认 |
| 收款方式 | 微信 / 支付宝 | **需确认绑的是 PayPal**（Stripe 不支持中国大陆主体） |
| 提现验证 | 未做 | **未做** —— 先跑通一笔小额 |
| 自动续费设置 | ⚠️ 待确认能否关闭 | 待确认 |

## 开卖前必须清的（按阻断程度排序）

1. 🔴 **Ko-fi 建 Membership** —— 用户给的是 `/commissions`，**类型不对**（见下），海外通道要重建
2. 🟡 ~~Resend 域名验证~~ **✅ 实测已通过**，改为建议补 DMARC 提升到达率
3. 🟠 **两边各跑通一笔小额提现** —— 确认钱真能到手
4. 🟠 **爱发电 user_id** —— 自动化的前提
5. 🟠 **爱发电自动续费开关** —— 与「不自动续费」承诺冲突
6. 🟡 客服邮箱三处代码更新 + 清理废弃词条
7. 🟡 爱发电兑换码验证（兑换会不会产生订单记录，见 FEATURE-PLAN 二十一节）

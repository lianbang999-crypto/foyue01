# 自知录 · 极简功过格记事本

日日知非，日日改过。○ 记功 · ● 记过 · — 随手记，配一点恰到好处的 AI。
名承净土八祖莲池大师《自知录》，今人自用之工具。

**线上地址**：https://zhi.foyue.org （备用：https://zizhilu.2569331267.workers.dev）
**管理员总码**：`zizhilu2026`（`wrangler.jsonc` 的 `INVITE_CODE`，无限次，请改成自己的）

## 邀请制

注册必须凭邀请码，来源有两种：

- **管理员总码**：`INVITE_CODE`，不限次数，用它注册的人自带 20 个邀请名额（`ADMIN_INVITE_QUOTA`）。
- **用户专属码**：每个注册用户自动获得一个 8 位专属码，默认可邀请 2 人（`DEFAULT_INVITE_QUOTA`）。
  在「我的 → 邀请好友」查看、复制邀请链接（`/?invite=CODE`，对方打开自动填码）。老用户首次打开该面板会自动补发。

**名额为什么定得少**：硅基流动限流 Cloudflare 出口，AI 只能由浏览器直连，Key 会下发到每个已登录用户——
名额越大，Key 扩散越广。想放开就调大 `DEFAULT_INVITE_QUOTA` 重新 deploy；若哪天限流解除、AI 改回服务端，
这个顾虑即消失。

```bash
# 给某人更多名额
npx wrangler d1 execute rixing-db --remote -y --command "UPDATE invites SET max_uses=20 WHERE owner_id=<用户id>"
# 收回某人的邀请能力
npx wrangler d1 execute rixing-db --remote -y --command "UPDATE invites SET max_uses=used WHERE owner_id=<用户id>"
# 看邀请关系链
npx wrangler d1 execute rixing-db --remote -y --command "SELECT iu.created_at, a.account AS 邀请人, b.account AS 受邀人 FROM invite_uses iu JOIN users a ON a.id=iu.inviter_id JOIN users b ON b.id=iu.user_id"
```

## 功能

- **账号**：邮箱/手机号 + 密码（需邀请码；「我的」里可改密码、设法名；Cookie 60 天）
- **功过格**：功○ / 过● / 记— 三类，**只记事不计分**（依莲池大师序：上士「书可也，不书可也」，「善本当行，非徼福故」）；统计为「本月功 N 件 · 过 N 件 · 记 N 天 · 连 N 天」
- **常用事目**：依《自知录》原书四门分类——善门（忠孝类 / 仁慈类 / 三宝功德类 / 杂善类）、过门（不忠孝类 / 不仁慈类 / 三宝罪业类 / 杂不善类），事目以白话概括，点选即填
- **三个页签**：录 / 广场 / 我的
- **广场**：可把自己的记录（含图音视频）分享给同修观摩，以法名署名，随时可撤回；不做点赞、热度、排名；他人内容可举报
- **我的**：法名设置、AI 用量、每日提醒、邀请好友、备份恢复、修改密码、关于、退出
- **AI 配额**：每人每日免费 100 次，零时重置；用满后不再下发 Key
- **月历视图**（每日 ○● 色点，点击跳转）
- **附件**：图片/视频/音频（≤50MB，大图上传前自动压缩；R2 私有存储，仅本人可见）
- **PWA**：手机浏览器「添加到主屏幕」即为 App（含离线壳）
- **宋代美学**：方形朱文印「知」贯穿品牌（登录/顶栏/图标/月报钤印）；界面衬线为子集化 Noto Serif SC（~55KB/权重，安卓上宋体不再塌成黑体）；记录去卡片化——宋版书式落于纸面、发丝界行分隔；省察为左朱界线；自绘极简音频条；登录页竖排题跋「人苦不自知」（莲池大师序原文）
- **设计语言**：Apple HIG —— 系统字体（`-apple-system`/PingFang SC，安卓自动落到思源黑，无需下载字体）、大量留白、无边框靠背景分层、圆角卡片、底部 Tab Bar、毛玻璃、iOS 缓动 `cubic-bezier(.32,.72,0,1)`；全站仅一个强调色（朱红 `#B0492F`，取功过格朱笔记功之意）
- **界面**：工具按钮全部为线条图标（内联 SVG sprite，零请求），每个都带 `aria-label`；触控热区用透明伪元素撑到 44px；`:focus-visible` 朱色描边；深色模式为苹果式纯黑分层（正文对比度 15.6:1）；尊重 `prefers-reduced-motion`；输入框一律 ≥16px 防 iOS 聚焦缩放；顶栏/抽屉/Tab Bar 全部适配安全区
- **动效**：记下时「落笔」（滑入 + 朱色薄晕 + ○● 脉冲）、删除「收笔」（淡出收起）、统计数字滚动、AI 省察逐字浮现、月历日点依次点亮、骨架屏、自定义确认弹层（不用系统 confirm）、底部抽屉可下滑关闭
- **AI（硅基流动）**：✦ 每日省察 ✦ 月度省察报告 ✦ 润色 ✦ 判别归类（自动建议功/过/记，不打分）✦ 问格 ✦ AI 看图

## 架构

```
Cloudflare Workers（src/worker.js，零依赖）
├── 静态资源  public/（含 PWA manifest / sw.js / icons）
├── D1        rixing-db（users / sessions / notes / day_ai / invites / ai_usage / push_subs / reports）
├── R2        rixing-media（/media 私有鉴权；/pub 仅广场已分享内容可公开访问）
├── 域名      zhi.foyue.org（custom domain，自动 DNS + 证书）
└── AI        硅基流动国际站（浏览器直连为主，服务端回退）
```

**AI 双通道**：硅基流动对 Cloudflare 出口 IP 的推理请求限流（503），故浏览器
登录后从 `/api/ai/config` 取 Key 直连，结果经 `/api/day-ai`、`/api/notes/:id/caption`
回存；服务端同名接口保留作回退。**Key 会下发给已登录用户，故注册需邀请码。**

## 部署 / 更新

```bash
cd zizhilu
npx wrangler deploy        # 发布（含静态资源与自定义域名）
```

首次搭建才需要：`d1 create / d1 execute schema.sql / r2 bucket create / secret put SILICONFLOW_KEY`（均已完成）。
本地开发：`npx wrangler dev`（密钥在 `.dev.vars`）。
改动 `public/` 后建议把 `public/sw.js` 里的 `VERSION` 号 +1，确保老客户端缓存刷新。

## Android APP

TWA 方案，见 `android/README-android.md`。包名 `org.foyue.zhi`；当前 v1.0.1(versionCode 3)，印章图标；站内下载 `/dl/zizhilu.apk`；
签名指纹配置在 `wrangler.jsonc` 的 `TWA_FINGERPRINT`（对应
`/.well-known/assetlinks.json`）。**`android/android.keystore` 与
`keystore-info.txt` 不入 git，请立即异地备份**——丢失将无法更新已上架应用。

## 运维备忘

- 忘记密码（管理员重置）：
  ```bash
  # 生成新密码哈希（把 newpass 换掉）
  node -e "const c=require('crypto');const s=c.randomBytes(16).toString('hex');console.log('100000:'+s+':'+c.pbkdf2Sync('newpass',Buffer.from(s,'hex'),100000,32,'sha256').toString('hex'))"
  # 写入 D1（把 HASH 与账号换掉）
  npx wrangler d1 execute rixing-db --remote -y --command "UPDATE users SET pass_hash='HASH' WHERE account='xxx'"
  ```
- 换 AI Key：`printf '新Key' | npx wrangler secret put SILICONFLOW_KEY`
- 模型调整：`wrangler.jsonc` vars → `CHAT_MODEL` / `VISION_MODEL`
- 硅基流动国际站暂无语音转文字模型，音频以播放器保存；其上线 ASR 后可加转写

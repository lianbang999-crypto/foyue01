# Looka 🦌 可爱版九色鹿 · 极简生活手帐

Lifebear 清洁室复刻（他们是熊，我们是鹿）。安卓优先 + looka.foyue.org 网页端 + 云同步 + 小鹿 AI。

## 当前版本：v1.2.0（versionCode 3，2026-08-20）

- **正式签名**：`app/looka-release.jks`（密码在 `local.properties`；⚠️ **两者务必异地备份，丢失 = 永久无法更新**）
- **发版**：改 `app/build.gradle.kts` 的 versionCode/versionName → `scripts/release.sh "更新说明"`（打包→R2→version.json→部署一条龙；用户端次日自动弹更新）
- **构建**：`JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home gradle -p . assembleDebug`
  - Room 用 **kapt**（KSP 镜像不可达，勿改回）；R8 已开启（release）

## 服务端（server/，Cloudflare Worker）

- 域名 looka.foyue.org · D1 `looka-db` + 共享账号库 `rixing-db`（zhi 同源，PBKDF2 同格式）
- **AI 走硅基流动国内站 `.cn`**（国际站 `.com` 对 CF 出口有风控，勿改回）；对话不限次，限速 10/分 100/日
- **注册闸门当前 = open（开放注册）**，保留 IP 限流 3 次/小时防脚本
  - 收紧：`wrangler.jsonc` 改 `REGISTER_MODE` 为 `invite`（邀请码在 `server/.invite_codes.txt`）或 `closed`
- 密钥备忘：`server/.secrets.txt`（gitignored，含 OPENROUTER_KEY）；管理口令 `.admin_key`；兑换码/邀请码经 `/api/admin/gencode`
- **第七批绘图已定案**：OpenRouter `openai/gpt-5-image-mini` + `image_config:{quality:"low"}`，实测 ¥0.002/张、真透明底（对比图 docs/art-test/）
- Cron 每日 03:17（北京)清理过期会话/限流窗/重置令牌/90 天墓碑/崩溃日志截尾
- APK 分发：R2 桶 `looka-apk` → `/dl/looka-latest.apk`；版本信息 `public/version.json`

### ⚠️ 待人工操作：Resend 域名验证（邮箱找回的最后一步）

代码已全部就绪，但 **Resend 免费版在域名验证前只能发信给注册邮箱自己**。
去 [resend.com](https://resend.com)（账号 foyuejingtu@gmail.com）→ Domains → 添加 `foyue.org` →
把生成的 SPF/DKIM/DMARC 三条记录加到 Cloudflare DNS（**灰云 · 仅 DNS**）→ 等 Verified。

## 多语言

- 简体 / 繁體 / English，运行时字典（源码中文即 key，缺译回退中文）
- 唯一真源 `i18n/dict-en.tsv` → `scripts/build_i18n.py` 生成安卓 assets 与网页 `/i18n/*.json`（繁体 = 词表 + OpenCC 字表自动生成）
- 新增文案：源码写 `tr("中文")`，dict-en.tsv 加一行，跑脚本

## 印章资产

- `calendar_stamps_app_256png/` 源素材（256 PNG）→ `scripts/build_stamps.py` → APK assets + 网页 `/stamps/`
- 当前内置：基础 42 + 敦煌 38 = 80 枚 WebP；牛牛包 24 枚待重绘 256 后加入
- 规格：256×256 / WebP / 透明底 / 一包 ≤48 枚（详见 docs/STAMP-KIT-REVIEW.md）

## 文档

- `docs/ROADMAP.md` 计划总表（顶部有 2026-08-20 执行记录）
- `docs/SUBSCRIPTION.md` 订阅与鹿角设计 v2
- `docs/UI-SPEC.md` 像素冻结 · `docs/STAMP-KIT-REVIEW.md` 印章套件评审

## 测试账号

looka-smoke@foyue.org / smoke123456

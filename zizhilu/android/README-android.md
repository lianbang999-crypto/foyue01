# 自知录 · Android APP（TWA）

采用 **TWA（Trusted Web Activity）** 方案：APP 是一个信任封装的 Chrome 壳，
内容即 https://zhi.foyue.org ——网页更新 = APP 更新，无需重新发版。
包名 `org.foyue.zhi`，Google Play 可直接上架（不上国内商店）。

## 一次性构建步骤

```bash
cd zizhilu/android

# 1) 安装工具（已装可跳过）
npm i -g @bubblewrap/cli

# 2) 生成 Android 工程（读取本目录 twa-manifest.json）
bubblewrap update
# 首次运行会问 JDK / Android SDK 路径，可让它自动下载

# 3) 构建（首次会创建签名密钥 android.keystore，两个密码务必记牢并备份！）
bubblewrap build
# 产出：app-release-signed.apk（直接安装）和 app-release-bundle.aab（上架 Google Play 用）

# 4) 取签名 SHA256 指纹
keytool -list -v -keystore android.keystore -alias android | grep SHA256
```

## 让 APP 去掉地址栏（数字资产链接校验）

把上一步的 SHA256 填入 `zizhilu/wrangler.jsonc` 的 vars：

```jsonc
,"TWA_FINGERPRINT": "AA:BB:CC:……"
```

然后 `npx wrangler deploy`，确认
https://zhi.foyue.org/.well-known/assetlinks.json 返回指纹即可。
真机安装 APK 打开：顶部无地址栏 = 校验通过。

## 上架 Google Play

1. Play Console 建应用（分类：生活方式/工具），上传 `app-release-bundle.aab`。
2. 若启用了 **Play App Signing**（推荐）：Google 会用自己的密钥重签，
   需到 Play Console →「设置 → 应用完整性」复制 **应用签名密钥的 SHA256**，
   追加进 `TWA_FINGERPRINT`（英文逗号分隔多个指纹）再 deploy 一次。
3. 隐私政策页可临时用 https://zhi.foyue.org/ 的关于说明，正式上架建议在 foyue.org 放一页。

## 重要提醒

- `android.keystore` 与两个密码 = APP 的身份，**丢失即无法更新已上架应用**，请异地备份。
- 版本升级：改 `twa-manifest.json` 里的 `appVersionCode`（+1）与 `appVersionName`，重新 `bubblewrap build`。
- 日常功能更新只需改网页 `npx wrangler deploy`，无需重新出包。

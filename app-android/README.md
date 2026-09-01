# 佛乐 · 净土法音 —— 安卓离线应用

自建 WebView 应用：**壳与全部讲记正文随安装包装进手机，断网也能读、能念、能听已下载的音频**，
并且**锁屏后台可以一直恭听**。网络只用在三处 —— 音频流、问道与莲号等接口、目录更新。

包名 `org.foyue.app`（**发布后永不可改**）· minSdk 21 · 签名密钥见 `keystore/`（不在仓库里）。

## 为什么不是 TWA

TWA 本质是「委托 Chrome 打开 foyue.org」，包里一篇正文都没有，每次打开都要联网现取。
站点在 Cloudflare 上，国内网络一波动就是白屏，而 TWA 全屏无地址栏，用户连刷新都点不着。
同门的 wenchao 在 1.1.0 已为此从 TWA 换成自建 WebView，本项目直接照那条路走。

## 一处与 wenchao 不同：挂在自有域 foyue.org 上

androidx 给 `WebViewAssetLoader` 备了个默认域 `appassets.androidplatform.net`，wenchao 用的是它。
那对纯阅读的站点够用，本站不行 —— 页面里到处是 `/api/…` 与 `/audio/…` 的相对地址
（问道、莲号同步、留言、朗读，以及六个 R2 桶的音频流），挂在默认域下这些地址会落到本地、
打不到后端，就得像 wenchao 那样逐条改写成绝对地址。接口面这么大，改写迟早漏一条。

改用 `setDomain("foyue.org")`（自家域名，正当其用）后：

- APP 内的源与线上**完全一致**，相对地址天然打得到后端；
- `worker/index.js` 的跨域白名单**一个字都不用改**；
- 取件台对 `/api/` 与 `/audio/` 一律放行（`AppContentHandler` 开头那三行），
  WebView 照常发真实网络请求 —— 音频的 Range 分段由它自己的网络栈谈，
  不必在 Java 里手搓字节区间代理；
- 站点代码几乎不用为 APP 改写（只多两处：跳过 Service Worker、把播放态报给原生）。

代价：壳代码（html/js/css）在 APP 内永远来自安装包，线上改了也看不到，要发新包才更新。
这正是想要的语义 —— APP 的版本就是安装包的版本。会变的三份目录另由 `ContentUpdater` 管。

## 构建

```bash
# 1. 先把站点内容同步进 assets —— 漏了这步，装出来是个没有正文的空壳
python3 scripts/build-app-assets.py

# 2. 打包
cd app-android
./gradlew :app:assembleRelease

# 3. 签名（密码在 keystore/KEYSTORE-INFO.txt）
export PATH="$PATH:$ANDROID_HOME/build-tools/36.0.0"
PW=$(grep '^密码' keystore/KEYSTORE-INFO.txt | awk '{print $2}')
VER=$(grep -o 'versionName "[^"]*"' app/build.gradle | cut -d'"' -f2)
apksigner sign --ks keystore/foyue-upload.keystore --ks-key-alias foyue \
  --ks-pass "pass:$PW" --key-pass "pass:$PW" \
  --out "../public/app/foyue-$VER.apk" \
  app/build/outputs/apk/release/app-release-unsigned.apk
rm -f ../public/app/*.idsig      # v4 签名的附属文件，网上分发用不着

# 4. 回填安装包体积到 public/app/release.json，然后部署
cd .. && python3 scripts/build-app-assets.py && npx wrangler deploy
```

发新版：改 `app/build.gradle` 的 `versionCode`（+1）与 `versionName`，重走上面四步。
`release.json` 由脚本按 `versionName` 生成，网页版的下载入口与 APP 内的「有新版本」
都读它，不必另外改哪里。

## 代码结构

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | WebView 配置、AssetLoader 挂 foyue.org、外链外跳、返回键、blob 下载接管、内核过旧提示页 |
| `AppContentHandler.java` | 取件台：`/api` `/audio` 放行 → 覆盖层 → 出厂内容 → SPA 回退；MIME 与 UTF-8 |
| `MediaService.java` | 前台服务 + 媒体会话：锁屏与通知栏控制、耳机线控，后台不被回收 |
| `NativeBridge.java` | 页面里的 `window.__fyNative`：报播放态、递文件出去、下载并安装新版 |
| `ContentUpdater.java` | 目录更新：带 ETag 刷 catalog/library/qa 到覆盖层，下次启动生效 |
| `scripts/build-app-assets.py` | 从 `public/` 挑出 APP 要用的部分同步进 assets，并预拼 `css/all.css` |

## 几处容易踩的地方

- **`css/all.css` 磁盘上不存在**。线上是 `worker/css.js` 在边缘按 `ORDER` 拼的，
  打包脚本会读同一个 `ORDER` 现拼。漏了它，APP 装出来是一堆没有样式的裸文字。
- **声音不是 `MediaService` 放的**，是 WebView 里的 `<audio>`。音频焦点也归它 ——
  原生这边刻意不去 `requestAudioFocus`，两边都抢会互相打断，且极难查。
- **不要在 `onPause` 里调 `web.onPause()`**，那会把正在放的音频一起停掉，
  「后台恭听」就无从谈起。
- **暂停不退前台服务**。Android 12 起禁止从后台转前台，暂停就退的话，
  后台自动接下一集时再想转前台会被系统拒掉。
- **数据不与浏览器互通**。APP 的 WebView 存储自成一份，念佛计数、收藏、进度
  不会从手机浏览器带过来 —— 换机与跨端只有莲号云同步这一条路（`public/js/sync.js`）。

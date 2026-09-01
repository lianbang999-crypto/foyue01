# 佛乐 · 净土法音

基于 Cloudflare Workers + R2 的净土法音道场（foyue.org 新站）。
无服务器状态、无数据库、无构建步骤。

## 五个板块

| 板块 | 内容 |
|------|------|
| **听经台**（首页） | 讲经/讲座/问答/诗偈点播 + 「直播中」栏目条 → 二十四小时播经台（确定性排播，天下同闻，含节目单） |
| **有声书** | 安士全书三部 + 印光大师故事 + 东林圣贤往生 + 大安法师讲故事，进度自动记忆 |
| **念佛** | 数珠计数（108声/串，今日+累计，十念/撤销，无排行无打卡惩罚）+ 东林佛号 7 版与念诵循环、定课计时 |
| **文库** | 38 部讲记 241 篇文字实录，宣纸阅读器，字号可调，阅读位置记忆 |
| **问道** | NotebookLM 式文库问答：RAG 检索 8999 块原文 → 流式作答带出处编号，点编号跳读原文；另有 969 条问答直接检索 |

## 问道 RAG 架构

与同门 wenchao 的「问文钞」同一套路数（2026-09-01 对齐）。每一步都 best-effort：
任一步出事都退回更笨但可用的做法，不让问道整个哑掉。

```
追问改写(condense) → 改写检索式 + 抽关键名相
   → 【向量多查询召回 ∥ D1 全文关键词召回】 → RRF 融合 → 去重
   → bge-reranker-v2-m3 重排取 8 → 按 path 从 ASSETS 现取父段落
   → Qwen3.5-35B-A3B 流式作答(SSE) → 引用逐字自检 → 引用[n]跳原文
```

两处是这一版的要害：

- **关键词那一路**（D1 `foyue-wenku-fts`，中文重叠二元索引）。「戒杀」「十念记数」这类
  短名相在长句里语义占比太小，纯向量常常召不回；两路用 RRF 融合，不是谁压倒谁。
  两边共用同一套块 id —— **融合就是靠 id 对齐的**，故两份索引必须同一次切块产出。
- **小块检索、大块喂入**。命中的是 700 字小块，切块难免把一句话拦腰截断；喂给模型的是它
  在原文里所在的那一大段（`PARENT_CHARS`），查询时按 `path` 从 ASSETS 现取，不进索引。
  引用卡片仍用精确小块，便于逐字核对。

**两道护栏**（贴「宁可不答，不可妄说」）：检索服务自己坏了 → 据实说「检索暂时不可用」，
**绝不诬为「文库中未找到」**；相关度低于 `RERANK_MIN` → 直接拒答，不调用生成模型。
后者非有不可 —— 向量检索对再离谱的问题也会返回最近的八条，「零命中」那道护栏够不着。

- 全链路硅基流动 API，Key 存 Worker Secret（`SILICONFLOW_API_KEY`），前端零接触
- 限流：每 IP 每分钟 8 问；首轮问答的结果经 `caches.default` 缓存 7 天
  （追问不缓存 —— 同一句话在不同上下文里该答得不一样）
- 自检：`GET /api/ask/health` 看各路开关与 `hybridReady`／`lexRows`。
  关键词索引没建好时问道照常能用，只是悄悄退回纯向量，**不看这里发现不了**
- 改了检索逻辑或提示词，**务必把 `RETRIEVAL_VERSION` 加一**，否则旧缓存会遮住新逻辑
- 重建索引（两份必须一起重灌）：
  ```bash
  SF_KEY=sk-xxx python3 scripts/build-index.py
  npx wrangler vectorize upsert foyue-wenku --file=scripts/vectors.ndjson --batch-size 500
  ADMIN_TOKEN=xxx python3 scripts/push-kb.py --reset
  ```
- 评测（改检索参数后必跑，与上次对比升降）：`python3 scripts/eval-ask.py`
  统计召回率／引用率／误拒率／忠实率／直引逐字命中率，并给出在题与离题的分水岭
- 换生成模型：改 wrangler.jsonc 的 `SF_CHAT_MODEL`

## 直播排播（北京时间，每日固定）

00:00 子夜讲堂（净土经论连播）→ 04:30 晨诵（劝修净土诗）→ 06:30 上午讲堂（无量寿经述义）
→ 11:30 午间故事 → 13:00 下午讲堂（观经四帖疏）→ 17:30 暮诵 → 19:00 晚间讲座（专题）
→ 21:30 夜听经论。

排播为纯客户端确定性推演（[public/js/station.js](public/js/station.js)）：
开播纪元 2026-07-01 00:00 北京时间起，任何客户端算出同一时刻同一集同一秒。
软边界+补白：长课不掐断，空当以诗偈/短篇故事补白，时段边界误差 ≤15 分钟。

## 架构

```
worker/
  index.js            /audio/<桶别名>/<key> 六桶 R2 流式音频（Range 分段）；/api/* 各接口；其余走静态资源
  ssr.js              讲记/问答/系列的真实路径页（带正文，可索引）+ sitemap 实时生成
  css.js              把 public/css/ 下按板块分开的样式，在边缘拼成单份 /css/all.css
public/
  js/station.js       确定性排播算法（核心，EPOCH_UTC_MS 上线后不可改）
  js/app.js           五区界面 + 三种播放模式（直播/点播/念佛堂）+ 听经搜索/收藏/睡眠定时/数据备份
  js/poster.js        分享海报（canvas 绘制，与应用状态无涉）
  js/ask.js           问道 RAG 对话（对话状态随模块，事件层走访问器）
  js/a11y.js          浮层模态语义、背景遮蔽、Tab 循环、读屏播报
  js/util.js          $ / esc / toast / copyText / vibrate
  js/const.js         跨模块共用常量（放这儿是为了断开依赖环）
  css/*.css           样式源码，12 个板块文件；顺序即层叠顺序，见 worker/css.js 的 ORDER
  _headers            静态资源缓存分档（正文 1 天 / 图标 7 天 / 目录 1 小时；壳代码不放宽）
  js/appinstall.js    安卓离线应用的下载引导（横幅 + 我的页安装区）与 APP 内版本比对
  sw.js               Service Worker：壳资源缓存提速与离线兜底（/audio、/api、/app 直连；改壳清单时 VER 加一）
  app/                安卓安装包与 release.json（发布信息，由打包脚本按 build.gradle 生成）
  catalog.json        音频目录（6 桶 / 24 系列 / 912 集 / 401 小时）
  library.json        文库目录（39 系列 / 255 篇）
  qa.json             问道索引（969 问，其中 820 条有文字稿）
  text/               文库正文（UTF-8 纯文本，构建产物）
scripts/
  r2-manifest*.json   R2 全量清单 + MP3 时长（Range 读文件头解析）
  build-catalog.mjs   清单 → catalog.json + qa.json
  build-library.py    大安法师（讲法集）TXT/（docx/doc/GBK-txt）→ public/text/ + library.json
  build-index.py      public/text/ → 切块 → bge-m3 向量化 → vectors.ndjson（灌入 Vectorize）
  build-app-assets.py public/ → 安卓 APP 的 assets（并按 worker/css.js 的 ORDER 预拼 all.css）
app-android/          安卓离线应用（自建 WebView，详见 app-android/README.md）
```

### 可索引的真实路径（SEO）

全站是 hash 路由，`#read/…` 之后的部分服务器与爬虫都看不见 —— 此前只有首页一条 URL 能进索引。
现由 `worker/ssr.js` 给内容各开一条真实路径，正文直接写进 HTML：

| 路径 | 内容 | canonical |
|------|------|-----------|
| `/read/<系列>/<篇号>` | 讲记正文全文 | 自指 |
| `/qa/<编号>` | 问答正文（QAPage 结构化数据） | 自指 |
| `/wkseries/<系列>` | 篇目目录（含指向各篇的真实链接） | 自指 |
| `/series/<系列>[/<集号>]` | 音频系列目录 | 一律收敛到系列页 |

`/sitemap.xml` 按目录实时生成（约 1139 条），收新内容不必手工维护。
用户访问时 SPA 把 URL 归一成 hash 形式继续跑；服务端已渲染的正文由 `renderReader` 直接沿用，
不重复取文。分享出去的链接也用真实路径 —— hash 链接转不成外链。

音频桶：daanfashi / yinguangdashi / jingtushengxian / youshengshu / fohao / jingdiandusong。
文库源文本在本仓库 `大安法师（讲法集）TXT/`（835 个问答 docx + 37 个讲记系列）。

## 安卓离线应用

自建 WebView 应用（**不是 TWA**）：壳与 1075 篇讲记正文随安装包出厂，装完断网即可读、
可念佛计数、可听此前下载的音频，并且锁屏后台一直恭听。包体约 8MB。

内容挂在 `WebViewAssetLoader` 的**自有域 `foyue.org`** 下，与线上同源 ——
`/api/*` 与 `/audio/*` 由取件台放行走真网络（音频的 Range 分段交给 WebView 自己谈），
Worker 的跨域白名单与前端的相对地址因此一处都不用改。

站点侧只多两处改动：APP 内跳过 Service Worker（原生已管离线，两套缓存会打架），
以及把播放态报给原生（`pushNativeMedia` / `window.__fyMedia`，锁屏控制靠它）。

**数据不与手机浏览器互通** —— APP 的 WebView 存储自成一份，念佛计数与收藏不会自动带过来，
跨端只有莲号云同步这一条路（`public/js/sync.js`）。

构建与发版见 [app-android/README.md](app-android/README.md)。

## 开发与部署

```bash
npm run dev            # 本地开发（wrangler dev --remote，连真实 R2）
npm run deploy         # 部署
npm run catalog        # 桶内容变更后重建音频目录
python3 scripts/build-library.py   # 本地讲记文本变更后重建文库
python3 scripts/build-app-assets.py  # 打安卓包前必跑：同步内容进 APP（漏了装出来是空壳）
```

## 注意

- `EPOCH_UTC_MS`（开播纪元）与排播算法一经上线不可轻改，否则全网节目单错位
- 排播是从纪元逐集推演到此刻的，成本随开播时长线性增长。低端手机（6× CPU 减速）实测
  首次建台：48 天 32ms / 1 年 71ms / 2 年 191ms / 3 年 309ms；稳态每秒 tick 只 0.1ms，
  开销全在首次那一下。**两三年后会变成首屏可察觉的一顿**。届时不必动排播算法（那会让
  全网节目单错位）：把推演状态（各池指针 `state.ptr` + 时刻 `state.t`）定期存一份到
  localStorage，下次从那里续推即可，结果完全一致
- 本机存储一律走 `util.js` 的 `setLS/delLS`，不要直接 `localStorage.setItem`：
  iOS 隐私浏览下配额为 0、配额满时 setItem 会抛，裸调一抛就把调用方后面的渲染也打断了
  （念佛计数曾因此静默失效：念珠点了数字不动，且一句提示都没有）。要紧的数据传第三参
  `true`，写失败会出声提示（按分钟节流）
- 直播 `loadLive(retry)` 断流重连必须传 `retry=true`：重连时 URL 与刚才失败的那次相同，
  只赋 `audio.src` 浏览器认作没变、不会重新取流，得显式 `load()`
- 文字稿是润饰稿、音频是原声，问答两库标题多不对应，故并列呈现不强行匹配
- 旧站（Cloudflare Pages 的 foyue.org）整体归档不迁移；域名切换到本 Worker 需用户确认后操作
- 域名切到本站后，首次访问会自动把旧站念佛计数（localStorage `foyue_store.counter`）累加并入 `fy.nj`（见 app.js `importOldStore`，凭 `fy.njOldImport` 标记只执行一次）
- 尚未收入文库：《佛说无量寿经》讲记42讲完整版.docx、淨土宗教程（2018.1.16校）.doc（整本书需按讲拆分，待做）
- 样式改动请动 `public/css/` 下的板块文件，不要另建 `style.css`；新增文件须同时加进 `worker/css.js` 的 `ORDER`，否则不会下发
- CSS 别改成并列多个 `<link>`：各自压缩会丢掉跨文件的重复模式，实测多传 9.4 KB，慢网下 FCP 多等 324ms
- 浮层新增时记得加进 app.js 的 `OVERLAYS` 清单，否则没有 Esc、焦点也不归还（主题与语言弹层就漏过一轮）
- 问答 969 条里 149 条只有音频没有文字稿，故不进 sitemap（无正文可索引，硬发页面反成薄内容）

## 管理后台（foyue.org/admin）

**全站只有这一个后台**，由 `foyue-admin` Worker 提供（源码 `foyue-admin/`，路由 `foyue.org/admin*`）。
播经台原先的 `public/admin.html` 已并入，现仅留一个跳转页。

页签：**运营**（待办／播经台／文钞／自知录／须弥山）· 总览 · 用户 · 订阅 · 健康 · 子站 · 留存 · 审计 · 崩溃

**取数方式**
- 播经台：`BOJING` 绑定 `bojingtai-cmt` 库，**D1 直连**（留言／封禁／报错）
- 文钞 / 须弥山 / 自知录：**Service Binding** 内部调用（`SVC_WENCHAO` / `SVC_GAME` / `SVC_ZHI`）
  —— 不走公网、不受边缘路由影响，也就不必逐站配 CORS；各子站仍各自校验 Bearer 口令

**口令**：一枚通行四站。后台用 `FOYUE_ADMIN_KEY`，各子站用 `ADMIN_TOKEN`，值相同。
> 注意：**先部署、后设 secret**。`wrangler deploy` 之后要重设一次，否则线上取不到。

**待办优先**：打开先看跨站聚合的待处理项（举报／报错／待更正），无事显示「今日无事」。

**新增站点接入**
1. 子站 Worker 加 `/api/admin/*`，校验 `Bearer ADMIN_TOKEN`；
2. `foyue-admin/wrangler.jsonc` 的 `services` 加一条 Service Binding；
3. `src/worker.js` 的 `SUB` 加一项，`opsFetch` 加分支；
4. `public/admin/index.html` 的 `views.ops` 里 `nav` 加一个按钮。

**红线**：接口不返回用户私人内容。唯一例外是**已公开到广场且被举报**的那一条正文——不看到它就无法判断该不该下架。

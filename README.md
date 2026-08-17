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

```
问题 → bge-m3 向量化 → Vectorize(foyue-wenku, 8999块) 召回20
     → bge-reranker-v2-m3 重排取8 → Qwen3.5-35B-A3B 流式作答(SSE) → 引用[n]跳原文
```

- 全链路硅基流动 API，Key 存 Worker Secret（`SILICONFLOW_API_KEY`），前端零接触
- 限流：每 IP 每分钟 8 问；系统提示词固守「只依原文、注明出处、不足则如实说、不代法师说法」
- 重建索引：`SF_KEY=sk-xxx python3 scripts/build-index.py` → `npx wrangler vectorize insert foyue-wenku --file=scripts/vectors.ndjson --batch-size 500`
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
  sw.js               Service Worker：壳资源缓存提速与离线兜底（/audio、/api 直连；改壳清单时 VER 加一）
  catalog.json        音频目录（6 桶 / 24 系列 / 912 集 / 401 小时）
  library.json        文库目录（39 系列 / 255 篇）
  qa.json             问道索引（969 问，其中 820 条有文字稿）
  text/               文库正文（UTF-8 纯文本，构建产物）
scripts/
  r2-manifest*.json   R2 全量清单 + MP3 时长（Range 读文件头解析）
  build-catalog.mjs   清单 → catalog.json + qa.json
  build-library.py    大安法师（讲法集）TXT/（docx/doc/GBK-txt）→ public/text/ + library.json
  build-index.py      public/text/ → 切块 → bge-m3 向量化 → vectors.ndjson（灌入 Vectorize）
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

## 开发与部署

```bash
npm run dev            # 本地开发（wrangler dev --remote，连真实 R2）
npm run deploy         # 部署
npm run catalog        # 桶内容变更后重建音频目录
python3 scripts/build-library.py   # 本地讲记文本变更后重建文库
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

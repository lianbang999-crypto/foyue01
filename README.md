# 佛悦 · 净土法音

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
worker/index.js       /audio/<桶别名>/<key> 六桶 R2 流式音频（Range 分段）；/api/ask 问道 RAG；其余走静态资源
public/
  js/station.js       确定性排播算法（核心，EPOCH_UTC_MS 上线后不可改）
  js/app.js           五区界面 + 三种播放模式（直播/点播/念佛堂）
  catalog.json        音频目录（6 桶 / 24 系列 / 912 集 / 401 小时）
  library.json        文库目录（38 系列 / 241 篇）
  qa.json             问道索引（969 问）
  text/               文库正文（UTF-8 纯文本，构建产物）
scripts/
  r2-manifest*.json   R2 全量清单 + MP3 时长（Range 读文件头解析）
  build-catalog.mjs   清单 → catalog.json + qa.json
  build-library.py    大安法师（讲法集）TXT/（docx/doc/GBK-txt）→ public/text/ + library.json
  build-index.py      public/text/ → 切块 → bge-m3 向量化 → vectors.ndjson（灌入 Vectorize）
```

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
- 文字稿是润饰稿、音频是原声，问答两库标题多不对应，故并列呈现不强行匹配
- 旧站（Cloudflare Pages 的 foyue.org）整体归档不迁移；域名切换到本 Worker 需用户确认后操作
- 尚未收入文库：《佛说无量寿经》讲记42讲完整版.docx、淨土宗教程（2018.1.16校）.doc（整本书需按讲拆分，待做）

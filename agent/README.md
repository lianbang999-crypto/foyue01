# 问道智能体（agent/）

重做「问文库／问道」为智能体，未来替换主 worker 的 `serveAsk`。设计与纪律见 `设计书.md`。

## 常用命令（仓根运行）

```bash
# 重建数据层（public/text/qa 或 library/catalog 变动后）
node agent/scripts/build-dingben.mjs

# 评测（离线零外呼）
node agent/eval/selfhit.mjs   # 定本自命中＋阈值标定＋直出零误发硬校验
node agent/eval/cases.mjs     # 意图路由／目录层／定本匹配／句级闸 案例集

# 本地起服（Vectorize 本地不支持→综述自动走降级链，恰可测公网直访行为）
npx wrangler dev --config agent/worker/wrangler.toml --port 8791
curl -s http://127.0.0.1:8791/v1/health
curl -s -X POST http://127.0.0.1:8791/v1/ask -H 'content-type: application/json' -d '{"q":"自性是佛，为什么还要拜佛？"}'

# 部署（须显式 --config，裸跑会误取仓根 wrangler.jsonc 部署主站）
npx wrangler secret put SILICONFLOW_API_KEY --config agent/worker/wrangler.toml   # 首次
npx wrangler deploy --config agent/worker/wrangler.toml
```

## 现状（2026-08-04 M1·独立站定位）

- **知识底本＝文库全库**：综述层绑主站同一 Vectorize 索引（8999 块＝241 篇讲记＋820 问答）；
  定本层（820 亲答直出，零误发标定）是全库之上的确定性快路。
- 独立站门面：`GET /` 极简问答页；`POST /v1/ask` 公开 API（CORS 允许 foyue.org 系与本地）；
  公网生成双限流（PUB_RL 8/分＋兜底），`PUBLIC_GEN=off` 应急关闸；自定义域注释待发起人定子域。
- 已验证（本地）：评测 31/31＋自命中标定零误发＋wrangler dev 门面/CORS/四路由/限流单测。
- 未验证（需 SF key／线上）：综述路运行时、重排复核档、KV 日配额与答案快取（M2 兑现）。
- 主站接线（可选并存）：service binding 内转 `https://ask.internal/v1/ask`（前端零改动），
  或前端直连独立站域（CORS 已开，换 URL 一行）。

# ADR-001：Agent 引擎裁决 —— v1 自研微内核，Koog 暂不引入（2026-08-27）

状态：**已采纳**（§131）。上游依据：docs/research/AGENT-STACK-RESEARCH.md v1.1（§130 + 审计校审）。

## 背景

§130 研究把 Koog 1.1.1 列为 runtime 首选，路线 R1 要求先做 Android spike（依赖可解析 →
体积/R8/冷启动量化 → Go/No-Go）。本 ADR 记录 R1 的执行结果与裁决。

## 裁决

**v1 不引入 Koog，自研 ~150 行微内核（LookaAgentKernel），置于 R0 端口
（AgentRuntimePort / EngineSnapshotPort）之后，保持引擎可换。**

## 依据（版本事实与架构事实，非猜测）

1. **工具链硬不兼容（spike 第一道门纸面 No-Go）**：
   - Looka：Kotlin **2.0.21**（build.gradle.kts:4-5，AGP/Compose 编译器配套）。
   - Koog 1.1.1 主线：Kotlin **2.3.10** + Ktor **3.3.3** + kotlinx-serialization 1.10.0
     （koog 仓库 gradle/libs.versions.toml:17/21/25，本地克隆核对）。
   - Kotlin 编译器读不了高两个 minor 的库 metadata；消费 Koog 需把全项目升到 Kotlin 2.3
     时代（AGP、Compose 编译器、全量回归），这是独立的大型工程，不该被 Agent 功能捆绑。
   - 诚实声明：因此**设备 spike 未执行也无需执行** —— 依赖门在版本层面即失败，
     体积/R8/冷启动量化无从谈起。这是纸面裁决，但第 1 条是可复核的版本事实。
2. **wire 层错位**：Looka 的 LLM 请求走自家 Cloudflare Worker 自定义信封
   （server/src/worker.js /api/ai/chat：鹿角计量、三级模型降级、带图清洗、X-Lk-Fallback、
   自定义 SSE）。Koog 的 LLM 客户端说 OpenRouter/OpenAI 原生格式，接入必须自写
   LLMClient 适配层 —— 框架的核心卖点（官方客户端）用不上，收益缩水成「循环库」，
   而循环本身百余行。
3. **体积与 R8 面**：Ktor3 全家桶 + kotlinx-serialization 对手帐 App 是纯负担；
   Looka 现网络层为 OkHttp + org.json，零新增传输栈。

## 边界约定（R0，违反即架构回归）

- Looka 域类型（AiAction、AgentProposal、聊天表）**永不 import 引擎类型**；
  引擎只通过 `com.looka.app.agent.AgentRuntimePort` 接入。
- 业务真相层永远是 Room（AgentProposal / ChatMessage）；引擎内部状态只是可丢弃的
  执行快照（EngineSnapshotPort，v1 空实现即正确实现 —— §130 审计裁决第 2 条）。
- v1 内核**只自动执行 risk=READ 工具**；WRITE/DESTRUCTIVE 永远走提案卡人审。
  该约束由 scripts/check_contracts.py 机器核验（AgentTools 注册表逐个 risk=READ）。

## 重评触发条件

任一成立时重开本 ADR：
1. Looka 主工程升级 Kotlin ≥ 2.3（工具链障碍消失）；
2. 需要 Koog 独有能力（多智能体图、MCP 客户端、LiteRT 端上推理）且自研成本超过接入成本；
3. Koog 发布与 Looka 工具链兼容的 LTS 线。
届时按 §130 v1.1 的 R1 Go/No-Go 门重跑设备 spike（体积/R8/冷启动 + class-based 与
@Tool 双测；副作用与权限安全 100% 独立于总体 90%）。

## 许可证记录

- 自研内核：Looka 自有代码。
- 借鉴对象：Koog / adk-kotlin（Apache-2.0，可读可对照可合规拷贝，本次未拷贝）；
  gpt_mobile（GPL-3.0，clean-room 政策：只学模式，不复制、不链接，提交说明不写 port from）。

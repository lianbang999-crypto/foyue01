# Agent 技术栈研究报告 v1.0（§130）

> 2026-08-27 · 用户拍板：「这次要研究，不是自己写代码——先把成熟的 GitHub 项目研究明白，
> 学习清楚了再拿来用。研究别人已经确定性的成果，不必再走弯路去测试。」
> 方法：全部项目**浅克隆读真实源码**（不止读 README），版本/许可证/活跃度经 GitHub API 核实。
> 对应《Looka Mobile Agent 产品与技术方案 v1.1》§50 研究矩阵。

---

## 0. 结论先行：三张清单

### A. 直接可用（确定性成果，拿来即用）

| 项目 | 版本 | 许可证 | 拿什么 |
|---|---|---|---|
| **JetBrains Koog** | 1.1.1（Maven Central `ai.koog:koog-agents:1.1.1`） | Apache-2.0 | **整库作为 Agent Runtime**：官方 Android 目标 + **官方 OpenRouter 客户端** + @Tool 注解 + checkpoint 持久化 + 事件钩子 + MCP + 端上 LiteRT。与 Looka 现有 OpenRouter 主链路零适配 |
| **adk-kotlin（单文件级）** | 0.8.0（`com.google.adk:google-adk-kotlin-core`） | Apache-2.0 | 不作 runtime（见 C），但 **androidMain 的 Room 会话持久化 schema** 与 **Event 数据语义**可合规参考乃至带版权头拷贝单文件 |
| **MCP kotlin-sdk** | 官方 | MIT→Apache-2.0 过渡 | 将来接 MCP 由 Koog 的 `agents-mcp` 包着用，不直接依赖 |
| **assistant-ui** | 活跃（11.9k★） | MIT | Web 端将来若上 React 可直接用；当下**学**其 MessagePart 联合类型 / ToolInvocationTracker / external-store 适配器 |

### B. 只学模式，代码禁止复制

| 项目 | 原因 | 学什么 |
|---|---|---|
| **gpt_mobile**（1.2k★） | **GPL-3.0 —— 复制代码会传染 Looka 闭源仓库，法律红线** | AgentRun Room 表设计（外键级联+status 索引）、CANCELED≠INTERRUPTED 状态机、AgentRunCoordinator（独立于页面生命周期）、AgentRunForegroundService、Room V2 迁移的仪器测试写法 |

### C. 暂缓 / 边界参考

| 项目 | 判断 |
|---|---|
| **adk-kotlin 作为 runtime** | 0.8.0 未到 1.0；**模型层只有 Gemini/Vertex/LiteRT-LM，没有 OpenAI 兼容客户端**（core/models 目录实核）——接 OpenRouter 要自写 Model 实现，违背"不走弯路"。数据语义参考价值 S 级，runtime 价值当前 C 级 |
| adk-java 1.x | 企业向（A2A/插件），与手机端诉求错位，补充参考 |
| langgraph4j（MIT，1.9k★，已迁 langgraph4j org） | JVM graph/checkpoint——Koog 的 strategy graph + snapshot 已覆盖同类能力，暂缓 |
| Open WebUI / Chatbot UI | 功能广度的**反面参考**：明确 Looka 不做的东西（多模型面板/Persona 市场/工作流编辑器） |

**一句话总结：Runtime 选 Koog（Apache-2.0、Android 官方支持、OpenRouter 官方客户端三条全中）；
数据模型抄 adk-kotlin 的语义 + gpt_mobile 的表设计模式；UI 投影学 assistant-ui。**

---

## 1. JetBrains Koog 1.1.1（S 级 · 深研）

**是什么**：JetBrains 官方 Kotlin Agent 框架，KMP（JVM/Android/iOS/JS/WasmJS），4.5k★，日更。

**实核到的直接可用能力**（模块名=Maven 坐标一部分）：

- **模型面**：`prompt-executor-openrouter-client`（**官方 OpenRouter**，Looka 现有主链路零适配）、
  openai / anthropic / google / deepseek / dashscope / mistral / ollama / bedrock、
  **litert-client（Google LiteRT 端上模型）** —— 方案 §41 On-device 路线已内置
- **核心 API 极简**（examples/code-agent/step-01 实读）：
  ```kotlin
  AIAgent(promptExecutor, llmModel, toolRegistry = ToolRegistry { tool(...) },
      systemPrompt, strategy = singleRunStrategy(), maxIterations) {
      handleEvents { onToolCallStarting { ... } }   // = 方案的 AgentEvent 钩子
  }
  ```
- **工具**：`@Tool` 注解把普通 Kotlin 函数变工具（反射收集）；ToolRegistry = 方案的 CapabilityRegistry 雏形
- **持久化**：`agents-features-snapshot`（checkpoint/rollback，**含 androidMain 实现**）＝方案 §27 的
  pause/persist/resume；另有 persistence-jdbc / chat-history / longterm-memory / chat-memory-sql
- **策略图**：strategy DSL（node/edge/onMultipleToolCalls/nodeLLMCompressHistory 历史压缩内置）
- **MCP**：`agents-mcp` / `agents-mcp-server`（包着官方 kotlin-sdk）
- **可观测**：opentelemetry / trace / tokenizer 模块
- **官方 Compose 四端示例**：examples/demo-compose-app（Android/iOS/Desktop/Web），
  AgentProvider 模式 + `onAssistantMessage` 回调（HITL 的现成挂点）

**风险与代价**（诚实记账）：
1. 1.x 新库，API 仍会动（additions 包还是 1.1.1-beta）——用薄适配层包住（方案 §53 本来就要求）
2. 依赖体积未实测：ktor-client + kotlinx-serialization 全家桶，**APK 增量必须在 spike 里量出来再定**
3. 它的 checkpoint 是"Agent 执行状态"持久化——Looka 的「草稿卡确认」语义仍要自己接
  （工具 execute 前 gate 到 pendingAiActions，Koog 的事件钩子/自定义工具足够挂）

## 2. google/adk-kotlin 0.8.0（S 级数据语义 · C 级 runtime）

**方案 v1.1 写"ADK Kotlin 未 GA 可切换"——实核修正：仓库存在、活跃（0.8.0，2026-08-15），
已上 Maven Central，但未到 1.0。**

**值得直接拿的（Apache-2.0，可合规参考/拷贝单文件）**：
- `core/src/androidMain/.../sessions/room/`：**官方 Room 会话持久化**
  （RoomSessionService / SessionsDao / StorageEntities / AdkSessionsDatabase）——
  方案 §127.9 要自己设计的东西，Google 给了参考答案
- `Event` 数据类（commonMain/events/Event.kt 实读）：
  `id / invocationId / author / content / actions / longRunningToolIds / partial /
  turnComplete / errorCode / usageMetadata / interrupted / branch` ——
  方案的 AgentEvent 字段几乎逐一对应，**这就是 Invocation/Event 语义的权威参考**
- `litertlm` 模块 + `examples/android/litertlmchat`（含 DeviceTools）：
  端上模型聊天的完整 Android 示例——On-device 阶段的现成起点
- `mlkit` 模块：设备端 ML 能力接入参考

**为什么不选它当 runtime**：models 目录实核只有 Gemini.kt / Vertex / LiteRT——
**没有 OpenAI 兼容客户端**，接 OpenRouter 得自写 Model 实现＝走弯路；0.8.0 破坏性变更风险也高。
**跟踪策略**：进入 1.0 且出现 OpenAI 兼容层时重估（方案的 Framework Adapter 原则保证可换）。

## 3. gpt_mobile（S 级 Android 现实 · GPL 红线）

**License GPL-3.0 —— 任何代码复制进 Looka（闭源）都不合规。只学不抄。**

实核收获（包结构 data/{agent,database,context,security,backup} 已读）：
- **AgentRun 表设计**（entity/AgentRun.kt 实读）：runId 主键 + chat/user_message/assistant_message
  三外键级联删除 + **status 建索引**（恢复扫描快）+ provider_snapshot 字段（运行时配置快照——
  换模型后旧 run 仍可解释，这个细节值得抄进我们自己的设计）
- **状态机**：CANCELED（用户主动）与 INTERRUPTED(系统/进程死亡)在 Coordinator 里严格分开——
  方案 §11 的出处
- **AgentRunCoordinator** 独立于 ViewModel/页面生命周期 + **AgentRunForegroundService**
  （长任务进前台服务）——方案 §12/§37 的出处
- **迁移工程**：ChatDatabaseV2Migrations + 仪器测试（androidTest 里真跑迁移）——
  我们 Room v6→v11 一路裸奔过来，这个测试写法应当引入
- context/security 包：Keystore + 加密存 API Key（方案 §38 的实现参考）

## 4. assistant-ui（A+ 级 UI 抽象 · MIT）

- monorepo 30+ 包；核心抽象在 `packages/core` 与 `assistant-stream`
- **ToolInvocationTracker**（core/runtimes/tool-invocations）：工具调用生命周期在 UI 层的
  投影管理——我们草稿卡→结果卡的状态流转可对照它查漏
- **external-store 适配器**：UI 不拥有数据、从外部 store 投影——与方案 §43
  （AgentEvent→Projection→MessagePart→Compose）同构，验证了投影方向正确
- **adapters**（attachment/speech/suggestion）：能力即适配器的组织方式
- 对 Android 无直接可用代码（React/TS）；**Web 端若将来重构可直接采用（MIT）**，
  当下我们的 vanilla JS Web 端只借鉴 Part 类型划分

## 5. 概念对照表（方案 v1.1 ↔ 成熟实现 ↔ Looka 现状）

| 方案概念 | 权威参考实现 | Looka 现状（雏形） |
|---|---|---|
| Invocation / AgentRun | gpt_mobile AgentRun 表（模式）· adk Event.invocationId | 无（纯内存一次请求） |
| AgentEvent | adk Event 字段 · Koog handleEvents | 无 |
| MessagePart | assistant-ui 联合类型 · adk Content.parts | ChatMsg(role,text) 单体 |
| ToolInvocation | Koog ToolRegistry+事件 · assistant-ui Tracker | AiActions 文本 JSON 协议 |
| Approval / HITL | Koog onAssistantMessage + snapshot 恢复 | pendingAiActions（内存，杀进程即丢） |
| Persist / Resume | Koog agents-features-snapshot（androidMain） | 无 |
| Memory 分层 | Koog longterm-memory · 方案 Memory Policy | deerFacts（用户确认制 ✓ 方向已对） |
| MCP | Koog agents-mcp | 无 |
| On-device | Koog litert-client · adk litertlm+Android 示例 | 无 |
| Context Gateway | **无现成轮子——这正是 Looka 的独有价值层，必须自写** | agendaContext（一次性全量注入） |

## 6. 修订后的落地路线（研究结论版，替代此前口述的 K0-K4）

> 原则：每步以评测集 30 例回归护航（<90% 即停）；Koog 永远包在薄适配层后（Framework 可换，用户资产不换）。

| 步 | 内容 | 性质 |
|---|---|---|
| R1 | **Koog spike**（独立 module，不动产品代码）：AIAgent + OpenRouterLLMClient(Luna) + 3 个 Looka 只读工具，跑通评测集；量出 APK 体积增量与冷启动影响 → **数据说话再定引入** | 验证 |
| R2 | 自写 AgentRun/ToolEvent Room 表（照 gpt_mobile 模式 + adk 字段语义，自己的代码）——先只做记录与审计，不改执行链 | 数据层 |
| R3 | AiClient/AiActions 切 Koog runtime：文本 JSON 协议 → 原生 tool-calling；草稿确认 = 工具 execute 前 gate 到现有 pendingAiActions（同路事务铁律不变） | 替换 |
| R4 | snapshot 接入（草稿卡杀进程不丢）+ Context Gateway（agendaContext 拆按需 API，顺手完成 AI-3） | 增强 |
| R5 | 端上小模型试点（Koog litert-client，参考 adk litertlmchat 示例）：意图分类/隐私过滤离线化 | 远期 |

## 7. 许可证合规备忘（一票否决项）

- Koog / adk-kotlin / adk-java：Apache-2.0 —— 引库、参考、带版权头拷贝均可
- assistant-ui / langgraph4j：MIT —— 同上
- **gpt_mobile：GPL-3.0 —— 只允许阅读与模式学习，禁止复制任何代码/资源进 Looka**
- MCP kotlin-sdk：MIT→Apache-2.0 过渡期 —— 经 Koog 间接使用，不直接引

# Agent 技术栈研究报告 v1.1（§130）

> 2026-08-27 · 用户拍板：「这次要研究，不是自己写代码——先把成熟的 GitHub 项目研究明白，
> 学习清楚了再拿来用。研究别人已经确定性的成果，不必再走弯路去测试。」
> 方法：全部项目**浅克隆读真实源码**（不止读 README），版本/许可证/活跃度经 GitHub API 核实。
> 对应《Looka Mobile Agent 产品与技术方案 v1.1》§50 研究矩阵。
> **v1.1**：经《Looka_Agent_Stack_Audit_Report_v1.0》独立复核后修订（Approve with changes）。
> 审计 14 项裁决：11 项接受、2 项部分接受、**1 项经源码复核驳回**（见 §8 审计裁决）。

---

## 0. 结论先行：三张清单

### A. 直接可用（确定性成果，拿来即用）

| 项目 | 版本 | 许可证 | 拿什么 |
|---|---|---|---|
| **JetBrains Koog** | 1.1.1（Maven Central `ai.koog:koog-agents:1.1.1`） | Apache-2.0 | **整库作为 Agent Runtime**：官方 Android 目标 + **官方 OpenRouter 客户端** + @Tool 注解 + checkpoint 持久化 + 事件钩子 + MCP + 端上 LiteRT。与 Looka 现有 OpenRouter 主链路适配成本极低（Luna 兼容性以 R1 spike 实测为准） |
| **adk-kotlin（单文件级）** | 0.8.0（`com.google.adk:google-adk-kotlin-core`） | Apache-2.0 | 不作 runtime（见 C），但 **androidMain 的 Room 会话持久化 schema** 与 **Event 数据语义**作权威参考（优先按语义独立建模；确需拷贝须完整履行 LICENSE/NOTICE/修改声明义务，且避免被 Pre-GA API 绑定） |
| **MCP kotlin-sdk** | 官方 | MIT→Apache-2.0 过渡 | 将来接 MCP 由 Koog 的 `agents-mcp` 包着用，不直接依赖 |
| **assistant-ui** | 活跃（11.9k★） | MIT | Web 端将来若上 React 可直接用；当下**学**其 MessagePart 联合类型 / ToolInvocationTracker / external-store 适配器 |

### B. 只学模式，代码禁止复制

| 项目 | 原因 | 学什么 |
|---|---|---|
| **gpt_mobile**（1.2k★） | **GPL-3.0 —— 对闭源 Looka 执行 clean-room 政策：允许阅读与设计对照，禁止复制实现代码/资源、禁止链接 GPL 模块**（正式法律判断留给合规流程）| AgentRun Room 表设计（外键级联+status 索引）、CANCELED≠INTERRUPTED 状态机、AgentRunCoordinator（独立于页面生命周期）、AgentRunForegroundService、Room V2 迁移的仪器测试写法 |

### C. 暂缓 / 边界参考

| 项目 | 判断 |
|---|---|
| **adk-kotlin 作为 runtime** | 0.8.0 Pre-GA；官方内置 Model 实现偏 Google 生态（core/models 实核只有 Gemini/Vertex/LiteRT）——Model 抽象可扩展，但**缺成熟官方 OpenRouter adapter**，协议/流式/工具调用/错误映射得自己长期维护＝走弯路（审计项 9 措辞采纳）。数据语义参考价值 S 级，runtime 当前暂缓 |
| adk-java 1.x | 企业向（A2A/插件），与手机端诉求错位，补充参考 |
| langgraph4j（MIT，1.9k★，已迁 langgraph4j org） | JVM graph/checkpoint——Koog 的 strategy graph + snapshot 已覆盖同类能力，暂缓 |
| Open WebUI / Chatbot UI | 功能广度的**反面参考**：明确 Looka 不做的东西（多模型面板/Persona 市场/工作流编辑器） |

**一句话总结：Runtime 选 Koog（Apache-2.0、Android 官方支持、OpenRouter 官方客户端三条全中）；
数据模型抄 adk-kotlin 的语义 + gpt_mobile 的表设计模式；UI 投影学 assistant-ui。**

---

## 1. JetBrains Koog 1.1.1（S 级 · 深研）

**是什么**：JetBrains 官方 Kotlin Agent 框架，KMP（JVM/Android/iOS/JS/WasmJS），4.5k★，日更。

**实核到的直接可用能力**（模块名=Maven 坐标一部分）：

- **模型面**：`prompt-executor-openrouter-client`（**官方 OpenRouter**——正式支持的 provider，
  非 OpenAI 兼容绕接；Luna 的 tool-calling/reasoning/流式行为以 R1 spike 实测定版）、
  openai / anthropic / google / deepseek / dashscope / mistral / ollama / bedrock、
  **litert-client（Google LiteRT 端上模型）** —— 方案 §41 On-device 路线已内置
- **核心 API 极简**（examples/code-agent/step-01 实读）：
  ```kotlin
  AIAgent(promptExecutor, llmModel, toolRegistry = ToolRegistry { tool(...) },
      systemPrompt, strategy = singleRunStrategy(), maxIterations) {
      handleEvents { onToolCallStarting { ... } }   // = 方案的 AgentEvent 钩子
  }
  ```
- **工具**：`@Tool` 注解把普通 Kotlin 函数变工具（反射收集）；ToolRegistry = 方案的 CapabilityRegistry 雏形。
  **审计争议已源码定案**：反射收集在 `jvmCommonMain`，而构建配置明确 `androidMain dependsOn jvmCommonMain`
  （注释原文 "share the code between JVM and Android targets"）——**@Tool 编进 Android target**；
  审计报告"JVM-only 不含 Android"的说法不成立。工程上仍采纳其稳健建议：
  R1 spike **双测** class-based Tool 与 @Tool 反射（重点验 R8/ProGuard 下反射存活与体积代价）
- **持久化**：`agents-features-snapshot`（checkpoint/rollback，**含 androidMain 实现**）——
  覆盖方案 §27 的**执行引擎恢复**层；产品级 pause/approval/resume（审批对象/风险级/幂等/过期/审计）
  仍由 Looka 自己的 Room 真相层负责，checkpoint 只作可替换的 EngineSnapshot（审计项 6 采纳）；
  另有 persistence-jdbc / chat-history / longterm-memory / chat-memory-sql
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

**为什么不选它当 runtime**：不是"技术上只能 Gemini"——Model 是抽象接口可自建实现；
而是**官方没有成熟的 OpenRouter adapter**，协议、流式、tool-call、usage、错误映射都要
自己写并长期维护（Koog 已替我们做完这些），且 0.8.0 Pre-GA 破坏性变更风险高。
**跟踪策略**：进入 1.0 且出现 OpenAI 兼容层时重估（方案的 Framework Adapter 原则保证可换）。

## 3. gpt_mobile（S 级 Android 现实 · GPL 红线）

**License GPL-3.0 —— clean-room 政策：Reference only。** 架构/状态机独立重写；代码不 copy-paste；
提交说明不写 "port from gpt_mobile"，必要时以 ADR 记 "inspired by public design pattern,
independently implemented"（审计项 10 措辞采纳）。

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
| Context Gateway | **本次审计的核心项目中无满足 Looka 需求者**（memory/RAG/session 不能替代 检索+授权+隐私+溯源+领域语义 的组合）——独有价值层，自建 | agendaContext（一次性全量注入） |

## 6. 落地路线 R0-R6（v1.1 采纳审计版：R4 拆两阶、新增边界锁定与影子集成）

> **执行状态（2026-08-27，§131 落地）**：R0 ✅（agent/ 包端口 + ADR-001）；
> R1 ✅ **No-Go →自研微内核**（纸面裁决：Koog 1.1.1 要求 Kotlin 2.3.10/Ktor 3.3.3，
> Looka 在 Kotlin 2.0.21，依赖门版本层面即失败；另有 worker 信封 wire 错位与体积负担。
> 设备 spike 因此未跑也无从跑 —— 依据与重评触发条件见 docs/adr/ADR-001-agent-engine.md）；
> R2 部分 ✅（v1 只加 AgentProposal 表，七表是目标态按需增）；R3 ✅（旧单发链 = 开关关闭
> 与异常时的回退路径，同端点零服务端改动）；R4 ✅（提案卡 Room 持久化 + 状态跃迁守卫）；
> R5 v1 ✅（预注入窗口 + 四只读工具按需查询，Context Gateway 独立模块留待迭代）；R6 未开。


> 原则：canonical domain state（Conversation/Invocation/AgentRun/AgentEvent/ToolInvocation/
> ApprovalRequest/Artifact/Context/Audit）**永远归 Looka**；Koog 只出现在 adapter 与
> EngineSnapshot 层——两年后换掉 Koog，用户资产完整可用。

| 步 | 内容 | 性质 |
|---|---|---|
| R0 | **边界锁定**：RuntimePort / EngineSnapshotPort / CapabilityPolicy 接口 + clean-room 与许可证 ADR | 架构 |
| R1 | **Koog Android spike**（独立 module）：OpenRouterLLMClient+Luna+3 只读工具；class-based Tool 主路径、@Tool 反射对照测；量 APK/AAB 增量、R8、冷启动、RAM、method count；测流式 tool-call/reasoning/cancel/process-death | 验证 |
| R2 | **Canonical Room**：自写 Conversation/Invocation/AgentRun/AgentEvent/ToolInvocation/ApprovalRequest/EngineSnapshot 元数据表（照 gpt_mobile 模式+adk 语义，自己的代码）+ 迁移仪器测试 | 数据层 |
| R3 | **影子集成**：KoogRuntimeAdapter 只读能力先行；AgentEvent 投影进现有 UI；旧链路保留 fallback | 集成 |
| R4 | **Durable HITL**：pendingAiActions 内存态升格为 ApprovalRequest+ToolInvocation 状态机；杀进程/重启/拒绝/重复点击全验证；Koog checkpoint 只辅助恢复执行 | 可靠性 |
| R5 | **Context Gateway**：agendaContext 拆按需只读 API（scope/freshness/source/audit），顺手完成 AI-3 | 差异化 |
| R6 | 端上小模型试点（Koog litert-client，参考 adk litertlmchat）：意图分类/隐私过滤/轻摘要 | 远期 |

### R1 的 Go/No-Go 门（审计版采纳：安全项与总体分离）

| Gate | 标准 |
|---|---|
| 功能正确性 | 评测集 30+ 例总体 ≥90%（工具选择/参数 schema/流式/终答分别统计） |
| OpenRouter/Luna | tool-calling/reasoning/流式/usage/错误恢复全通，**不许靠文本 JSON 兜底** |
| Android 构建 | Debug/Release/R8 全过；无反射缺失崩溃 |
| 体积与性能 | AAB/APK 增量、冷启动、首 token、峰值 RAM 记录在案，与基线对比后拍板 |
| 取消语义 | cancel 后不得再执行新工具；UI/Room 状态一致 |
| **副作用安全** | 重复点击/超时/进程死亡不产生重复副作用 —— **100% 通过，不适用总体百分比** |
| **权限安全** | 未授权数据/工具 100% 拒绝 —— **100% 通过** |
| 可替换性 | Looka domain model 零依赖 Koog 类型（只出现在 adapter/EngineSnapshot 层） |

## 7. 许可证合规备忘（一票否决项）

- Koog / adk-kotlin / adk-java：Apache-2.0 —— 引库、参考、带版权头拷贝均可
- assistant-ui / langgraph4j：MIT —— 同上
- **gpt_mobile：GPL-3.0 —— clean-room：只读与模式学习；不复制实现/资源，不链接 GPL 模块**
- MCP kotlin-sdk：MIT→Apache-2.0 过渡期 —— 经 Koog 间接使用，不直接引

## 8. 审计裁决记录（v1.1 · 对《Looka_Agent_Stack_Audit_Report_v1.0》的校审）

审计报告结论「Approve with changes」**接受**；其 14 项审计的逐项裁决：

**接受（11 项）**：Koog 版本/许可/OpenRouter 官方性确认（1/2/4）、"零适配"→"低适配、Luna 以 spike 定版"（3）、
checkpoint=EngineSnapshot 而非业务真相层（6）、onAssistantMessage 是接入点非 durable HITL（7）、
ADK 现状确认（8）、GPL clean-room 措辞（10）、assistant-ui/MCP 判断确认（11/12）、
Context Gateway 表述收窄（13）、路线拆分与安全 Gate 分离（14）。

**部分接受（2 项）**：
- 项 9（ADK "只有 Gemini"）：原文本就写明"接 OpenRouter 得自写 Model 实现"（即承认可扩展），
  但审计措辞更不易误读，采纳改写；
- L17（Apache 拷贝单文件）：法律上可行的表述无误，采纳"优先独立建模、拷贝须履行义务"的收紧。

**驳回（1 项，源码定案）**：
- 项 5「@Tool annotation-based tools 为 JVM-only，不能作为 Android 实施指导」——
  **与源码不符**。实证：`agents-tools` 的反射收集（ToolSet/ToolFromCallable）位于 `jvmCommonMain`；
  `convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts` 明确
  `androidMain { dependsOn(jvmCommonMain) }`，且该 sourceSet 注释原文为
  *"Source set to share the code between JVM and Android targets"* —— @Tool 反射路径
  **编译进 Android target**。审计把文档语境中的 "JVM-only"（相对 JS/Wasm/iOS 的 JVM 家族）
  误读为"不含 Android"。其工程建议（Android 主路径偏保守用 class-based Tool、R8 下验证反射）
  仍然稳健，已并入 R1 双测项——但结论依据必须以源码为准。

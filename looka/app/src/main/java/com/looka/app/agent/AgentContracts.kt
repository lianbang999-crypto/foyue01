package com.looka.app.agent

import org.json.JSONObject

/**
 * §131 R0：Agent 边界契约。
 *
 * 这一包是「引擎可换」的隔离层（ADR-001）：Looka 域类型（AiAction/Room 表/ViewModel）
 * 永不 import 引擎实现；引擎（v1 = LookaAgentKernel，将来可能是 Koog 适配器）只通过
 * 这里的接口接入。反向亦然：本包不 import ai/、vm/、data/ 的任何类型。
 */

/** 工具风险级：v1 内核只放行 READ；WRITE/DESTRUCTIVE 永远走提案卡人审，不进内核 */
enum class ToolRisk { READ, WRITE, DESTRUCTIVE }

/** 工具登记项。合同门（check_contracts.py）逐个核验 v1 注册表 risk 全 READ */
data class AgentToolSpec(val name: String, val risk: ToolRisk)

/** 本地工具：JSON 参数进、模型可读文本出（模型消费的是文本，不是结构） */
interface AgentTool {
    val spec: AgentToolSpec
    fun execute(args: JSONObject, data: AgentDataSource): String
}

/**
 * 内核可见的数据口。实现方是 ViewModel（数据都在内存 StateFlow 里），
 * 但内核与工具只见这四个方法 —— 不见 Room、不见 ViewModel。
 * 行格式约定与预注入上下文一致：[e12]/[t3]/[n5] 标注 id，改/删定位规则因此对工具结果同样成立。
 */
interface AgentDataSource {
    fun eventLines(fromDay: Long, toDay: Long, keyword: String): List<String>
    fun taskLines(scope: String, keyword: String): List<String>
    fun noteLines(keyword: String): List<String>
    fun monthStats(month: String): String
}

/** 一轮 Agent 执行的结果 */
data class AgentResult(val raw: String, val usedTools: Boolean, val rounds: Int)

/**
 * 引擎运行时端口（R0 核心）。
 * transport = 一次完整的 LLM 往返（流式渲染由调用方闭包自理）；
 * onToolRound = 每开始一个工具轮回调一次（UI 拿去撤闪出的气泡、挂状态行）。
 */
interface AgentRuntimePort {
    suspend fun run(
        history: MutableList<Pair<String, String>>,
        transport: suspend (List<Pair<String, String>>) -> String,
        data: AgentDataSource,
        onToolRound: (String) -> Unit
    ): AgentResult
}

/**
 * 引擎快照端口（§130 审计裁决第 2 条）：业务真相层永远是 Room
 * （AgentProposal/ChatMessage），引擎内部状态只是可丢弃的执行快照。
 * v1 微内核无内部状态 —— 空实现即正确实现。将来接 Koog checkpoint 时适配到此口。
 */
interface EngineSnapshotPort {
    fun snapshot(): String? = null
    fun restore(s: String) {}
}

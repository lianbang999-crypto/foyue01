package com.looka.app.agent

import org.json.JSONObject

/**
 * §131 R1/ADR-001：自研微内核 —— Looka Agent v1 的执行引擎。
 *
 * 循环：调传输层 → 严格解析工具调用块 → risk=READ 才执行（纵深防御，见下）→
 * 结果以 [工具结果] user 消息回填 → 再调；≤3 轮封顶；模型不再要工具即为最终答案。
 *
 * 安全模型（§130 v1.1 Gate：副作用安全 100%，结构保证而非提示词保证）：
 * 1. 注册表里只有 READ 工具（合同门核验）；
 * 2. 即便注册表被误加非 READ 工具，本内核也拒绝执行（下面的 risk 检查）；
 * 3. 一切 WRITE/DESTRUCTIVE 意图仍走 actions 动作块 → 提案卡人审，内核根本不认识它们。
 */
object LookaAgentKernel : AgentRuntimePort {

    private const val MAX_ROUNDS = 3

    /** 与 AiActions.FENCE 同型：容忍结尾未闭合的代码块（截断场景） */
    private val FENCE = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)(?:```|$)")

    /**
     * 严格解析工具调用：
     * - 回复里出现 actions 动作块 → 不算工具轮（互斥规则；动作走提案卡老路）；
     * - 认 fenced 块里的 {"tool":...}，或整条回复就是一个裸对象；
     * - 多个工具块只取第一个（协议要求一次一个）。
     */
    fun parseToolCall(raw: String): JSONObject? {
        if (raw.contains("\"actions\"")) return null
        for (m in FENCE.findAll(raw)) {
            val o = runCatching { JSONObject(m.groupValues[1].trim()) }.getOrNull() ?: continue
            if (o.has("tool")) return o
        }
        val t = raw.trim()
        if (t.startsWith("{") && t.contains("\"tool\"")) {
            runCatching { JSONObject(t) }.getOrNull()?.let { if (it.has("tool")) return it }
        }
        return null
    }

    /** §131 评测实证的顽固病灶：模型说「我去查/需要先查」却不输出工具块（说而不做）。
     *  提示词三轮加压仍 ~50% 犯 —— 工程兜底：检测到即追加一次系统性补救轮，强制出块。 */
    private val NARRATE_MARKERS = listOf("我去查", "我先查", "需要先查", "我需要查", "查一下", "先查询", "帮你查")
    private const val NUDGE_MSG = "（系统提示：你刚才说要查询，但没有输出工具调用块。" +
        "请现在**只输出**一个 ```json 工具调用块，不要写任何其他文字。）"

    private fun shouldNudge(raw: String): Boolean =
        !raw.contains("```") && !raw.contains("\"actions\"") &&
            NARRATE_MARKERS.any { raw.contains(it) }

    override suspend fun run(
        history: MutableList<Pair<String, String>>,
        transport: suspend (List<Pair<String, String>>) -> String,
        data: AgentDataSource,
        onToolRound: (String) -> Unit
    ): AgentResult {
        var raw = transport(history)
        var rounds = 0
        var nudged = false
        val seen = HashSet<String>()   // 同参重查断路器
        while (rounds < MAX_ROUNDS) {
            val call = parseToolCall(raw) ?: run {
                // 补救轮（至多一次）：说查不查 → 系统性追问强制出块
                if (!nudged && shouldNudge(raw)) {
                    nudged = true
                    onToolRound(AgentTools.label("", org.json.JSONObject()))
                    history += "assistant" to raw
                    history += "user" to NUDGE_MSG
                    raw = transport(history)
                    parseToolCall(raw)
                } else null
            } ?: break
            val name = call.optString("tool")
            val tool = byNameSafe(name)
            val result = when {
                tool == null ->
                    "（没有叫「$name」的工具 —— 不要再调用工具，直接基于已有信息回答）"
                tool.spec.risk != ToolRisk.READ ->
                    "（该工具不允许自动执行 —— 请改为输出 actions 动作块，由用户确认）"
                !seen.add(name + call.toString()) ->
                    "（同样的查询刚查过了，结果就在上面 —— 请直接基于它回答）"
                else -> runCatching { tool.execute(call, data) }
                    .getOrElse { "（查询出错：${it.message ?: "未知错误"} —— 请直接基于已有信息回答）" }
            }
            onToolRound(AgentTools.label(name, call))
            history += "assistant" to raw
            history += "user" to ("[工具结果]\n" + result +
                "\n（以上是系统返回的真实数据。请基于它回答用户；不要把 [工具结果] 字样或 id 标注原样展示。）")
            rounds++
            raw = transport(history)
        }
        return AgentResult(raw, rounds > 0, rounds)
    }

    private fun byNameSafe(n: String): AgentTool? = if (n.isBlank()) null else AgentTools.byName(n)
}

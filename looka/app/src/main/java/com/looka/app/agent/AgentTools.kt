package com.looka.app.agent

import com.looka.app.util.Fmt
import com.looka.app.util.tr
import org.json.JSONObject
import java.time.LocalDate

/**
 * §131 R5：v1 只读工具注册表 —— 四个，全部 risk=READ（合同门机器核验）。
 *
 * 输出纪律：
 * - 空结果要**明说**（「无日程」），否则弱模型会换个参数反复查；
 * - 行带 [e/t/n id] 标注，与预注入上下文同格式 —— 改/删只认标注 id 的铁律对工具结果同样成立；
 * - 全部封顶（60/40/20 行），笔记只给标题+40 字摘要，绝不倒全文（隐私与 token 双约束）。
 */
object AgentTools {

    val all: List<AgentTool> = listOf(QueryEvents, QueryTasks, QueryNotes, MonthStats)

    fun byName(n: String): AgentTool? = all.find { it.spec.name == n }

    /** 工具轮的用户可见状态行（瞬态，「小鹿查了…」） */
    fun label(name: String, call: JSONObject): String = when (name) {
        "query_events" -> tr("小鹿查了 {0} 的日程…",
            call.optString("from") + " ~ " + call.optString("to"))
        "query_tasks" -> tr("小鹿翻了翻任务清单…")
        "query_notes" -> tr("小鹿找了找笔记…")
        "month_stats" -> tr("小鹿统计了 {0}…", call.optString("month"))
        else -> tr("小鹿查了查…")
    }

    /**
     * 宽松取日期（与 AiActions.parseDay 同规则的本包拷贝 —— R0 边界：agent 不 import ai/）：
     * 从脏字符串抠 YYYY-M-D，年份限当前 ±（-1..+5），1970/2226 这类离谱值直接拒。
     */
    private fun day(s: String): Long = try {
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(s.trim())
        if (m == null) -1L else {
            val y = m.groupValues[1].toInt()
            val cur = LocalDate.now().year
            if (y < cur - 1 || y > cur + 5) -1L
            else LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[3].toInt()).toEpochDay()
        }
    } catch (_: Exception) { -1L }

    private object QueryEvents : AgentTool {
        override val spec = AgentToolSpec("query_events", ToolRisk.READ)
        override fun execute(args: JSONObject, data: AgentDataSource): String {
            val f0 = day(args.optString("from"))
            val t0 = day(args.optString("to"))
            if (f0 < 0 || t0 < 0) return "（日期格式应为 YYYY-MM-DD，年份需在近几年内）"
            val f = minOf(f0, t0)
            val t = minOf(maxOf(f0, t0), f + 92)   // 范围钳 ≤92 天，防「查全年」拖垮上下文
            val lines = data.eventLines(f, t, args.optString("keyword").trim())
            if (lines.isEmpty()) return "（${Fmt.iso(f)} 至 ${Fmt.iso(t)} 无日程）"
            // §132：头部带总数 —— 「有几条/多少个」类问题不逼模型自己数行（数行是实测弱点）
            return "日程（${Fmt.iso(f)} 至 ${Fmt.iso(t)}，共 ${lines.size} 条，[e数字] 是 id）：\n" +
                lines.take(60).joinToString("\n") +
                (if (lines.size > 60) "\n（仅显示前 60 条）" else "")
        }
    }

    private object QueryTasks : AgentTool {
        override val spec = AgentToolSpec("query_tasks", ToolRisk.READ)
        override fun execute(args: JSONObject, data: AgentDataSource): String {
            val scope = args.optString("scope").ifBlank { "open" }
            val lines = data.taskLines(scope, args.optString("keyword").trim())
            if (lines.isEmpty()) return "（没有匹配的任务）"
            return "任务（范围=$scope，共 ${lines.size} 条，[t数字] 是 id）：\n" +
                lines.take(40).joinToString("\n") +
                (if (lines.size > 40) "\n（仅显示前 40 条）" else "")
        }
    }

    private object QueryNotes : AgentTool {
        override val spec = AgentToolSpec("query_notes", ToolRisk.READ)
        override fun execute(args: JSONObject, data: AgentDataSource): String {
            val kw = args.optString("keyword").trim()
            if (kw.isBlank()) return "（query_notes 需要 keyword）"
            val lines = data.noteLines(kw)
            if (lines.isEmpty()) return "（没有包含「$kw」的笔记）"
            return "笔记（含「$kw」，共 ${lines.size} 条，[n数字] 是 id，仅标题与摘要）：\n" +
                lines.take(20).joinToString("\n") +
                (if (lines.size > 20) "\n（仅显示前 20 条）" else "")
        }
    }

    private object MonthStats : AgentTool {
        override val spec = AgentToolSpec("month_stats", ToolRisk.READ)
        override fun execute(args: JSONObject, data: AgentDataSource): String {
            val m = Regex("(\\d{4})-(\\d{1,2})").find(args.optString("month").trim())
                ?: return "（月份格式应为 YYYY-MM）"
            val y = m.groupValues[1].toInt()
            if (y < LocalDate.now().year - 3 || y > LocalDate.now().year + 3) return "（月份超出可查范围）"
            return data.monthStats("${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}")
        }
    }
}

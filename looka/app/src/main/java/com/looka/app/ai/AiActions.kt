package com.looka.app.ai

import com.looka.app.util.Fmt
import com.looka.app.util.I18n
import com.looka.app.util.tr
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** AI 解析出的可执行动作 */
data class AiAction(
    val type: String,            // create_event / create_task / create_note
    val title: String = "",
    val day: Long = -1L,
    val endDay: Long = -1L,
    val startMin: Int = -1,
    val endMin: Int = -1,
    val allDay: Boolean = false,
    val location: String = "",
    val memo: String = "",
    val content: String = "",
    // P2-D1（§三十八①）：提醒语义。此前 AI 没有任何字段能表达"提醒时间"，
    // 「10:55 提醒我」只能被翻成"10:55 的日程 + 默认提前 15 分钟" —— 提醒落在 10:40，
    // 而 10:40 已过就被调度器静默丢弃，用户什么都收不到。
    val remindAtMin: Int = -1,       // 当天提醒时刻（分钟）。"X点提醒我" → 就是 X 点
    val remindMinBefore: Int = -1    // 提前 N 分钟。"提前半小时提醒" → 30
) {
    /** 预览文案 */
    fun label(): String = when (type) {
        "create_event" -> {
            val d = if (day >= 0) Fmt.dateCn(day) else tr("今天")
            val t = if (allDay || startMin < 0) tr("全天") else "${Fmt.hm(startMin)}-${Fmt.hm(endMin)}"
            val rem = when {
                remindAtMin >= 0 -> " · ⏰${Fmt.hm(remindAtMin)}"
                remindMinBefore >= 0 -> " · ⏰${tr("提前{0}分", remindMinBefore)}"
                else -> ""
            }
            "${tr("日程")} · $d $t · $title$rem"
        }
        "create_task" -> "${tr("任务")} · ${if (day >= 0) Fmt.dateCn(day) + " · " else ""}$title"
        else -> "${tr("笔记")} · ${title.ifBlank { content.take(12) }}"
    }
}

/** 小鹿 AI 的提示词与动作协议（JSON 指令解析） */
object AiActions {

    /**
     * 当前时间行。
     * 注意两点：
     * ① 只读一次时钟（原来 LocalDateTime.now() 与 LocalDate.now() 分两次读，跨零点会自相矛盾）；
     * ② 不用 String.format —— 模板里已插值完再 format 很脆，且受默认 Locale 影响（某些区域会输出本地数字）。
     */
    private fun nowLine(): String {
        val now = LocalDateTime.now()
        val d = now.toLocalDate()
        val hh = now.hour.toString().padStart(2, '0')
        val mm = now.minute.toString().padStart(2, '0')
        return "$d ${Fmt.weekFull(d.dayOfWeek.value)} $hh:$mm"
    }

    /**
     * 把相对日期直接算好喂给模型。
     * 小模型最容易错的就是日期加减（实测 7B 会把"明天"算成 2226-08-22-11），
     * 与其靠提示词要求它换算，不如把答案直接给它。
     */
    private fun dateAnchors(): String {
        val t = LocalDate.now()
        val mon = t.plusDays((8 - t.dayOfWeek.value).toLong())   // 下周一
        return "今天=$t，明天=${t.plusDays(1)}，后天=${t.plusDays(2)}，" +
            "本周末=${t.plusDays((6 - t.dayOfWeek.value).toLong())}，下周一=$mon"
    }

    /** 回复语言指令（S8：小鹿跟随界面语言） */
    private fun langLine(): String = when (I18n.lang) {
        "en" -> "Reply in English, concise and friendly, a few emoji are fine."
        "zh-TW" -> "回覆要求：繁體中文、簡潔友好、可少量 emoji。"
        else -> "回复要求：简体中文、简洁友好、可少量 emoji。"
    }

    private val PROTOCOL = """当需要为用户创建内容时，先用一句话说明，然后在回复末尾输出一个 ```json 代码块（必须是合法 JSON）：
{"actions":[
 {"type":"create_event","title":"标题","date":"YYYY-MM-DD","end_date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","all_day":false,"location":"","memo":""},
 {"type":"create_task","title":"标题","due":"YYYY-MM-DD"},
 {"type":"create_note","title":"标题","content":"内容"}
]}
提醒字段（重要）：
- 用户说「X 点提醒我 / 通知我 / 叫我」→ 这是【提醒时刻】，写 "remind_at":"HH:mm"（提醒就在那个时刻响，不是提前）。
- 用户说「提前 N 分钟提醒」→ 写 "remind_before":N（数字，分钟）。
- 用户只说事件时间没提"提醒"两个字 → 两个字段都省略（走默认提醒）。
硬性规则：
1. 最外层键名必须是 "actions"，值必须是数组。不要用 events / items / data 等其它名字。
2. 日期只能写 YYYY-MM-DD（如 2026-08-22），时间只能写 HH:mm（如 15:00）。禁止出现 2226 这类年份，禁止在日期后面接多余数字。
3. 全天日程 all_day=true 并省略 start/end；end_date 仅跨天时填写；未提到的字段直接省略。
4. **用户只是提问（如「明天有什么安排」）时，绝对不要输出 json 代码块**，正常回答即可。
5. 代码块必须以 ``` 正确闭合。"""

    /** 聊天系统提示词（带用户真实日程上下文） */
    /** 小鹿人格（2026-08-21）：与网页端 aiSystemPrompt 必须同步（第十五节①类文案） */
    private val PERSONA = """你是小鹿，Looka 手帐里的一只九色鹿，帮用户管理日程、任务、笔记与日记。

说话方式：温和、简短、不啰嗦。像一个安静的朋友，不像客服。一次说清一件事，不用「首先/其次/总之」这类结构词。

边界：
· 不催促。用户几天没写日记，不要提，也不要暗示。
· 不评判。用户推迟了很多次的事，只陈述事实，不说"要加油"。
· 不确定时直说不知道，不猜。
· 用户情绪低落时，先接住情绪，别急着给建议或排日程。

你的名字来自敦煌壁画《鹿王本生图》里的九色鹿 —— 那个故事讲的是善良与守信。"""

    fun chatSystemPrompt(agenda: String): String = """
$PERSONA
当前时间：${nowLine()}
日期参照（直接使用，不要自己加减）：${dateAnchors()}

$agenda
${langLine()}
回答日程问题时优先引用上面的真实数据，不要编造。
$PROTOCOL
""".trimIndent()

    /** 自然语言快速创建：只输出 JSON */
    fun quickParsePrompt(): String = """
你是日程解析器。把用户的自然语言解析为 Looka 可创建的对象。
当前时间：${nowLine()}
日期参照（直接使用，不要自己加减）：${dateAnchors()}
只输出一个 ```json 代码块，不要任何其他文字。格式：
{"actions":[
 {"type":"create_event","title":"...","date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","all_day":false,"remind_at":"HH:mm","remind_before":15},
 {"type":"create_task","title":"...","due":"YYYY-MM-DD"}
]}
规则：相对日期换算为具体日期；有明确时间点的事情用 create_event（end 默认 start+1 小时）；「X点提醒我/通知我」时 remind_at 就是 X 点（该时刻响铃），事件时间没另说就让 start 也等于 X 点；「提前N分钟提醒」用 remind_before；没提"提醒"就省略这两个字段；只有事项没有时间的用 create_task；一句话里有多件事就解析成多条；完全无法解析时输出 {"actions":[]}。
""".trimIndent()

    /** 任务拆解：只输出 JSON */
    fun subtasksPrompt(): String = """
你是任务拆解助手。把用户给出的任务拆解为 3-6 个可执行的子任务，每条不超过 15 个字，按先后顺序排列。子任务使用与用户输入相同的语言。
只输出一个 ```json 代码块：{"subtasks":["子任务1","子任务2"]}
""".trimIndent()

    /** 日记润色 */
    fun polishPrompt(): String = """
你是日记润色助手。在保留原意、第一人称、原文语言与全部真实事实的前提下，把用户的日记润色得更通顺、更有温度，篇幅与原文相近。只输出润色后的正文，不要任何解释或标题。
""".trimIndent()

    /** 代码块：容忍结尾未闭合（弱模型常被 max_tokens 截断） */
    private val FENCE = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)(?:```|$)")

    /**
     * 把 AI 回复拆成「展示文本 + 动作列表」。
     *
     * 设计前提：**模型一定会偶尔犯错**，所以这里的首要目标不是尽量解析成功，
     * 而是「无论如何都不能把裸 JSON 甩给用户看」——线上出过这个问题（见 docs/ROADMAP）。
     * 因此凡是"长得像动作载荷"的片段，无论能否解析，一律从展示文本里剔除。
     */
    fun split(raw: String): Pair<String, List<AiAction>> {
        var text = raw.trim()
        val actions = mutableListOf<AiAction>()

        // ① 处理代码块：只吃掉「动作载荷」，用户正经要的代码块（比如让小鹿写段正则）原样保留
        run {
            val kept = StringBuilder()
            var cursor = 0
            for (mr in FENCE.findAll(text)) {
                val inner = mr.groupValues[1]
                val parsed = parseActions(inner)
                if (parsed.isEmpty() && !looksLikePayload(inner)) continue
                actions += parsed
                kept.append(text, cursor, mr.range.first)
                cursor = mr.range.last + 1
            }
            if (cursor > 0) {
                kept.append(text, cursor, text.length)
                text = kept.toString().trim()
            }
        }

        // ② 正文里可能还有没打代码块的裸 JSON，按花括号配对逐段扫
        if (text.contains('{')) {
            val kept = StringBuilder()
            var cursor = 0
            for (span in braceSpans(text)) {
                val chunk = text.substring(span.first, span.last + 1)
                if (!looksLikePayload(chunk)) continue        // 普通文字里的花括号，保留
                kept.append(text, cursor, span.first)
                actions += parseActions(chunk)
                cursor = span.last + 1
            }
            if (cursor > 0) {
                kept.append(text, cursor, text.length)
                text = kept.toString()
            }
        }

        // ③ 收拾空白（载荷被摘走后常留下悬空的逗号与空行）
        text = text.trim().trimEnd('，', ',', '：', ':')
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val display = when {
            text.isNotBlank() -> text
            actions.isNotEmpty() -> tr("好的，帮你记上了 🦌")
            else -> tr("小鹿没太听懂，换个说法再说一次？")
        }
        return display to actions
    }

    /** 含动作特征字段才认定为载荷，避免误删用户正文里的花括号 */
    private fun looksLikePayload(s: String): Boolean =
        s.contains("\"actions\"") || s.contains("\"subtasks\"") ||
            (s.contains("\"type\"") && s.contains("create_"))

    /** 扫描出所有花括号平衡的片段；未闭合的吃到结尾（截断场景） */
    private fun braceSpans(s: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '{') { i++; continue }
            var depth = 0; var j = i; var inStr = false; var esc = false
            while (j < s.length) {
                val c = s[j]
                when {
                    esc -> esc = false
                    inStr && c == '\\' -> esc = true
                    c == '"' -> inStr = !inStr
                    !inStr && c == '{' -> depth++
                    !inStr && c == '}' -> if (--depth == 0) break
                }
                j++
            }
            out += i..minOf(j, s.length - 1)
            i = j + 1
        }
        return out
    }

    fun parseActions(json: String): List<AiAction> = try {
        val js = json.trim()
        // 顶层可能是对象，也可能模型直接甩了个数组
        val arr: JSONArray = if (js.startsWith("[")) JSONArray(js) else {
            val root = JSONObject(js)
            // 弱模型爱把 actions 写成 events/items/data；再不行就当成单个动作对象
            root.optJSONArray("actions")
                ?: root.optJSONArray("events")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("data")
                ?: if (root.has("type")) JSONArray().put(root) else JSONArray()
        }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("type")
            if (type !in setOf("create_event", "create_task", "create_note")) return@mapNotNull null
            // 没标题的动作是模型抽风的产物（实测会吐 title:"明天"这种占位），别塞给用户确认
            val name = o.optString("title").trim()
            if (name.isBlank() && o.optString("content").isBlank()) return@mapNotNull null
            val day = parseDay(o.optString("date").ifBlank { o.optString("due") })
            val endDay = parseDay(o.optString("end_date"))
            val start = parseMin(o.optString("start"))
            val end = parseMin(o.optString("end"))
            AiAction(
                type = type,
                title = name,
                day = day,
                endDay = if (endDay >= 0) endDay else day,
                startMin = start,
                endMin = if (end >= 0) end else if (start >= 0) minOf(start + 60, 24 * 60 - 1) else -1,
                allDay = o.optBoolean("all_day", start < 0),
                location = o.optString("location"),
                memo = o.optString("memo"),
                content = o.optString("content"),
                remindAtMin = parseMin(o.optString("remind_at")),
                remindMinBefore = o.optInt("remind_before", -1)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun parseSubtasks(raw: String): List<String> = try {
        val js = FENCE.find(raw)?.groupValues?.get(1)?.trim()
            ?: raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
        val root = JSONObject(js)
        val arr = root.optJSONArray("subtasks") ?: root.optJSONArray("tasks")
            ?: root.optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 宽松取日期：从脏字符串里抠出 YYYY-M-D，并做年份合理性校验。
     * 实测弱模型会输出 "2226-08-22-11" 这种，严格 LocalDate.parse 直接抛异常 →
     * 日期变成 -1 落到今天，用户看到的是"记错了"而不是"没记上"，更难排查。
     */
    private fun parseDay(s: String): Long = try {
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(s.trim())
        if (m == null) -1L else {
            val y = m.groupValues[1].toInt()
            val cur = LocalDate.now().year
            if (y < cur - 1 || y > cur + 5) -1L      // 2226 这类离谱年份直接丢弃
            else LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[3].toInt()).toEpochDay()
        }
    } catch (_: Exception) {
        -1L
    }

    /** 宽松取时间：容忍 "9:5"、"15：00"（全角冒号）、"下午3:00" 这类写法 */
    private fun parseMin(s: String): Int = try {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").find(s.trim())
        if (m == null) -1
        else (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()).coerceIn(0, 24 * 60 - 1)
    } catch (_: Exception) {
        -1
    }
}

package com.looka.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

/**
 * §132 A1：Agent 操作账本（母档 6.1 Operation Envelope 的 v1 落地面）。
 *
 * 每次 Agent 改系统事实（execActions 的一个动作 / 主题应用）落一行 —— 与 ChatMessage
 * 的人话记录不同，这里是结构化审计：谁改的（origin）、改了什么（actionType/target）、
 * 基于哪个版本改的（baseVersion，Freshness 证据）、结果如何（result）、怎么撤（undoSnapshot）。
 * 不进同步（审计是本机过程数据，与 AgentProposal 同哲学）；保留 90 天。
 *
 * result 语义：
 *  succeeded     已执行成功（undoSnapshot 非空则可撤）
 *  skipped_stale §132 A2 Freshness Guard 拦截 —— 目标在提案挂起期间被改过，未执行
 *  failed        目标不存在/已删等，未执行
 *  undone        曾成功、后被撤销
 */
@Entity
data class AgentOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 同一次确认/执行共一个批号（时间戳 ms）；撤销以批为单位 */
    val batchId: Long,
    val origin: String = "chat",
    val actionType: String,            // create_event / update_task / remember / theme …
    /** 母档 §12 自动化等级标注（§132 A4，只标注不改变行为）：L1/L2/L3 */
    val riskLevel: String,
    val targetKind: String = "",       // event/task/note/pref/theme
    val targetId: Long = -1L,
    /** 执行前目标对象的 updatedAt；创建/无目标 = -1 */
    val baseVersion: Long = -1L,
    /** 执行后目标对象的 updatedAt；撤销回放前校验它没再变（Freshness 的反向对称） */
    val resultVersion: Long = -1L,
    val payload: String = "",          // 该动作的 wire JSON（与提案 payload 同格式）
    val summary: String,               // 人话（与聊天动作卡同文）
    val result: String,
    val undoSnapshot: String = "",     // AgentOpSnapshot JSON；"" = 不可撤
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * §132 A1：撤销快照的手写 JSON 序列化。
 *
 * ⚠️ 字段漂移警告：EventSeries/Task/Note 实体加字段时必须同步这里，
 * AgentAuditTest 的 roundtrip 用例（反射比对全字段）会在漏改时红。
 * created=true 表示该对象是这次操作创建的（撤销 = 软删）；false 表示存的是旧值（撤销 = 回写）。
 */
object AgentOpSnapshot {

    fun ofEvent(s: EventSeries, created: Boolean = false): String =
        JSONObject().put("kind", "event").put("created", created).put("o", eventToJson(s)).toString()

    fun ofTask(t: Task, created: Boolean = false): String =
        JSONObject().put("kind", "task").put("created", created).put("o", taskToJson(t)).toString()

    fun ofNote(n: Note, created: Boolean = false): String =
        JSONObject().put("kind", "note").put("created", created).put("o", noteToJson(n)).toString()

    data class Decoded(val kind: String, val created: Boolean,
                       val event: EventSeries?, val task: Task?, val note: Note?)

    /** 回读失败返回 null（坏行跳过，不让一条脏数据卡死整批撤销） */
    fun decode(json: String): Decoded? = runCatching {
        val o = JSONObject(json)
        val kind = o.getString("kind")
        val created = o.optBoolean("created", false)
        val body = o.getJSONObject("o")
        when (kind) {
            "event" -> Decoded(kind, created, eventFromJson(body), null, null)
            "task" -> Decoded(kind, created, null, taskFromJson(body), null)
            "note" -> Decoded(kind, created, null, null, noteFromJson(body))
            else -> null
        }
    }.getOrNull()

    // ── 逐字段序列化（Room 实体不上 kotlinx-serialization：§131 体积裁决） ──

    private fun eventToJson(s: EventSeries) = JSONObject()
        .put("id", s.id).put("title", s.title).put("categoryId", s.categoryId)
        .put("allDay", s.allDay).put("startDay", s.startDay).put("endDay", s.endDay)
        .put("startMin", s.startMin).put("endMin", s.endMin)
        .put("location", s.location).put("memo", s.memo)
        .put("freq", s.freq).put("interval", s.interval).put("weekdays", s.weekdays)
        .put("monthlyByWeekday", s.monthlyByWeekday).put("untilDay", s.untilDay)
        .put("uid", s.uid).put("updatedAt", s.updatedAt)
        .put("dirty", s.dirty).put("deleted", s.deleted)

    private fun eventFromJson(o: JSONObject) = EventSeries(
        id = o.getLong("id"), title = o.getString("title"), categoryId = o.getLong("categoryId"),
        allDay = o.getBoolean("allDay"), startDay = o.getLong("startDay"), endDay = o.getLong("endDay"),
        startMin = o.getInt("startMin"), endMin = o.getInt("endMin"),
        location = o.getString("location"), memo = o.getString("memo"),
        freq = o.getInt("freq"), interval = o.getInt("interval"), weekdays = o.getInt("weekdays"),
        monthlyByWeekday = o.getBoolean("monthlyByWeekday"), untilDay = o.getLong("untilDay"),
        uid = o.getString("uid"), updatedAt = o.getLong("updatedAt"),
        dirty = o.getBoolean("dirty"), deleted = o.getBoolean("deleted")
    )

    private fun taskToJson(t: Task) = JSONObject()
        .put("id", t.id).put("title", t.title).put("done", t.done).put("dueDay", t.dueDay)
        .put("memo", t.memo).put("createdAt", t.createdAt).put("listUid", t.listUid)
        .put("starred", t.starred).put("doneAt", t.doneAt).put("labels", t.labels)
        .put("sortOrder", t.sortOrder).put("uid", t.uid).put("updatedAt", t.updatedAt)
        .put("dirty", t.dirty).put("deleted", t.deleted)

    private fun taskFromJson(o: JSONObject) = Task(
        id = o.getLong("id"), title = o.getString("title"), done = o.getBoolean("done"),
        dueDay = o.getLong("dueDay"), memo = o.getString("memo"), createdAt = o.getLong("createdAt"),
        listUid = o.getString("listUid"), starred = o.getBoolean("starred"),
        doneAt = o.getLong("doneAt"), labels = o.getString("labels"),
        sortOrder = o.getLong("sortOrder"), uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"), dirty = o.getBoolean("dirty"), deleted = o.getBoolean("deleted")
    )

    private fun noteToJson(n: Note) = JSONObject()
        .put("id", n.id).put("title", n.title).put("content", n.content).put("listUid", n.listUid)
        .put("createdAt", n.createdAt).put("sortOrder", n.sortOrder)
        .put("updatedAt", n.updatedAt).put("uid", n.uid)
        .put("dirty", n.dirty).put("deleted", n.deleted)

    private fun noteFromJson(o: JSONObject) = Note(
        id = o.getLong("id"), title = o.getString("title"), content = o.getString("content"),
        listUid = o.getString("listUid"), createdAt = o.getLong("createdAt"),
        sortOrder = o.getLong("sortOrder"), updatedAt = o.getLong("updatedAt"),
        uid = o.getString("uid"), dirty = o.getBoolean("dirty"), deleted = o.getBoolean("deleted")
    )
}

/** §132 A4：动作 → 母档 §12 自动化等级（v1 只标注，一切仍人审） */
fun riskLevelOf(actionType: String): String = when {
    actionType == "remember" -> "L1"
    actionType.startsWith("create_") || actionType == "theme" -> "L2"
    else -> "L3"   // update_* / delete_*
}

package com.looka.app.util

import android.content.Context
import android.net.Uri
import com.looka.app.LookaApp
import com.looka.app.data.Diary
import com.looka.app.data.Note
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Stamp
import com.looka.app.data.Task
import com.looka.app.data.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地备份 / 恢复 / 数据导出（A 批 B17 + PIPL 可携带权）：
 * - JSON 全量备份 + 按 uid 合并恢复（LWW，新者胜——与云同步同一规则）
 * - ICS：未来 365 天发生逐条导出（重复展开为独立 VEVENT，任何日历都能导入）
 * - Markdown：笔记 + 日记合集
 * 未登录用户换机迁移的唯一通道；文件经系统文件选择器写入，应用不留副本。
 */
object Backup {

    // ---------- 导出 ----------

    suspend fun exportJson(app: LookaApp, uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = app.db
        val root = JSONObject()
            .put("app", "looka").put("format", 4)
            .put("exportedAt", System.currentTimeMillis())
        var count = 0
        fun putArr(key: String, arr: JSONArray) { root.put(key, arr); count += arr.length() }

        putArr("categories", JSONArray().apply {
            db.categoryDao().list().forEach {
                put(JSONObject().put("uid", it.uid).put("name", it.name).put("color", it.colorHex)
                    .put("sort", it.sortOrder).put("visible", it.visible).put("deletable", it.deletable)
                    .put("updatedAt", it.updatedAt))
            }
        })
        putArr("taskLists", JSONArray().apply {
            db.taskListDao().listAll().forEach {
                put(JSONObject().put("uid", it.uid).put("name", it.name).put("color", it.colorHex)
                    .put("sort", it.sortOrder).put("archived", it.archived).put("deletable", it.deletable)
                    .put("updatedAt", it.updatedAt))
            }
        })
        putArr("events", JSONArray().apply {
            val catUid = db.categoryDao().list().associate { it.id to it.uid }
            db.eventDao().seriesList().forEach { s ->
                val rs = JSONArray()
                db.eventDao().remindersOf(s.id).forEach {
                    rs.put(JSONObject().put("m", it.minutesBefore).put("d", it.daysBefore)
                        .put("t", it.timeOfDayMin).put("on", it.enabled))
                }
                val exs = JSONArray()
                db.eventDao().exceptionsOf(s.id).forEach { e ->
                    exs.put(JSONObject().put("occ", e.occurrenceDay).put("cancelled", e.cancelled)
                        .put("newDay", e.newDay).putOpt("title", e.title))
                }
                put(JSONObject().put("uid", s.uid).put("title", s.title)
                    .put("categoryUid", catUid[s.categoryId] ?: "cat-default-1")
                    .put("allDay", s.allDay).put("startDay", s.startDay).put("endDay", s.endDay)
                    .put("startMin", s.startMin).put("endMin", s.endMin)
                    .put("location", s.location).put("memo", s.memo)
                    .put("freq", s.freq).put("interval", s.interval).put("weekdays", s.weekdays)
                    .put("monthlyByWeekday", s.monthlyByWeekday).put("untilDay", s.untilDay)
                    .put("updatedAt", s.updatedAt).put("reminders", rs).put("exceptions", exs))
            }
        })
        putArr("tasks", JSONArray().apply {
            db.taskDao().listAll().forEach {
                put(JSONObject().put("uid", it.uid).put("title", it.title).put("done", it.done)
                    .put("dueDay", it.dueDay).put("memo", it.memo).put("createdAt", it.createdAt)
                    .put("listUid", it.listUid).put("starred", it.starred).put("doneAt", it.doneAt)
                    .put("sortOrder", it.sortOrder).put("updatedAt", it.updatedAt))
            }
        })
        putArr("notes", JSONArray().apply {
            db.noteDao().listAll().forEach {
                put(JSONObject().put("uid", it.uid).put("title", it.title)
                    .put("content", it.content).put("updatedAt", it.updatedAt))
            }
        })
        putArr("diaries", JSONArray().apply {
            db.diaryDao().listAll().forEach {
                put(JSONObject().put("uid", it.uid).put("day", it.day).put("mood", it.mood)
                    .put("content", it.content).put("updatedAt", it.updatedAt))
            }
        })
        putArr("stamps", JSONArray().apply {
            db.stampDao().listAll().forEach {
                put(JSONObject().put("uid", it.uid).put("emoji", it.emoji).put("day", it.day)
                    .put("eventUid", it.eventUid).put("assetId", it.assetId).put("updatedAt", it.updatedAt))
            }
        })
        app.contentResolver.openOutputStream(uri)?.use {
            it.write(root.toString(2).toByteArray())
        }
        count
    }

    suspend fun exportIcs(app: LookaApp, uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = app.db
        val today = Fmt.today()
        val occs = RecurrenceEngine.expand(
            db.eventDao().seriesList(), db.eventDao().exceptionsList(), today - 30, today + 365
        )
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Looka//CN\r\n")
        fun icsDate(day: Long) = Fmt.iso(day).replace("-", "")
        occs.forEach { o ->
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:looka-${o.seriesId}-${o.occurrenceDay}@looka.foyue.org\r\n")
            sb.append("SUMMARY:").append(o.title.replace("\n", " ")).append("\r\n")
            if (o.allDay) {
                sb.append("DTSTART;VALUE=DATE:").append(icsDate(o.day)).append("\r\n")
                sb.append("DTEND;VALUE=DATE:").append(icsDate(o.endDay + 1)).append("\r\n")
            } else {
                sb.append("DTSTART:").append(icsDate(o.day))
                    .append("T%02d%02d00\r\n".format(o.startMin / 60, o.startMin % 60))
                sb.append("DTEND:").append(icsDate(o.endDay))
                    .append("T%02d%02d00\r\n".format(o.endMin / 60, o.endMin % 60))
            }
            if (o.location.isNotBlank()) sb.append("LOCATION:").append(o.location.replace("\n", " ")).append("\r\n")
            if (o.memo.isNotBlank()) sb.append("DESCRIPTION:").append(o.memo.replace("\n", "\\n")).append("\r\n")
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        app.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
        occs.size
    }

    suspend fun exportMarkdown(app: LookaApp, uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = app.db
        val notes = db.noteDao().listAll().sortedByDescending { it.updatedAt }
        val diaries = db.diaryDao().listAll().sortedByDescending { it.day }
        val sb = StringBuilder("# Looka ${tr("笔记与日记")}\n\n")
        if (notes.isNotEmpty()) {
            sb.append("## ${tr("笔记")}\n\n")
            notes.forEach { n ->
                sb.append("### ").append(n.title.ifBlank { tr("无标题") }).append("\n\n")
                    .append(n.content).append("\n\n---\n\n")
            }
        }
        if (diaries.isNotEmpty()) {
            sb.append("## ${tr("日记")}\n\n")
            diaries.forEach { d ->
                sb.append("### ").append(Fmt.iso(d.day)).append(" ")
                    .append(com.looka.app.data.MOOD_EMOJIS[d.mood.coerceIn(0, 4)]).append("\n\n")
                    .append(d.content).append("\n\n")
            }
        }
        app.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
        notes.size + diaries.size
    }

    // ---------- 恢复（按 uid 合并，新者胜；绝不清库） ----------

    suspend fun importJson(app: LookaApp, uri: Uri): Int = withContext(Dispatchers.IO) {
        val txt = app.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: return@withContext 0
        val root = JSONObject(txt)
        if (root.optString("app") != "looka") throw IllegalArgumentException(tr("不是 Looka 备份文件"))
        val db = app.db
        var merged = 0
        val now = System.currentTimeMillis()

        root.optJSONArray("taskLists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                val ex = db.taskListDao().byUid(uid)
                val up = o.optLong("updatedAt", now)
                if (ex == null) {
                    db.taskListDao().insert(TaskList(
                        name = o.optString("name"), colorHex = o.optString("color", "#5C6670"),
                        sortOrder = o.optInt("sort"), archived = o.optBoolean("archived"),
                        deletable = o.optBoolean("deletable", true),
                        uid = uid, updatedAt = up, dirty = true
                    )); merged++
                } else if (up > ex.updatedAt) {
                    db.taskListDao().update(ex.copy(
                        name = o.optString("name", ex.name), colorHex = o.optString("color", ex.colorHex),
                        archived = o.optBoolean("archived", ex.archived),
                        updatedAt = up, dirty = true, deleted = false
                    )); merged++
                }
            }
        }
        root.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                val ex = db.taskDao().byUid(uid)
                val up = o.optLong("updatedAt", now)
                if (ex == null) {
                    db.taskDao().insert(Task(
                        title = o.optString("title"), done = o.optBoolean("done"),
                        dueDay = o.optLong("dueDay", -1L), memo = o.optString("memo"),
                        createdAt = o.optLong("createdAt", up),
                        listUid = o.optString("listUid").ifBlank { "list-default" },
                        starred = o.optBoolean("starred"), doneAt = o.optLong("doneAt", -1L),
                        sortOrder = o.optLong("sortOrder"),
                        uid = uid, updatedAt = up, dirty = true
                    )); merged++
                } else if (up > ex.updatedAt) {
                    db.taskDao().update(ex.copy(
                        title = o.optString("title", ex.title), done = o.optBoolean("done", ex.done),
                        dueDay = o.optLong("dueDay", ex.dueDay), memo = o.optString("memo", ex.memo),
                        updatedAt = up, dirty = true, deleted = false
                    )); merged++
                }
            }
        }
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                val ex = db.eventDao().seriesByUid(uid)
                val up = o.optLong("updatedAt", now)
                if (ex != null && up <= ex.updatedAt) continue
                val catId = db.categoryDao().byUid(o.optString("categoryUid"))?.id
                    ?: db.categoryDao().byUid("cat-default-1")?.id ?: 1L
                val s = com.looka.app.data.EventSeries(
                    id = ex?.id ?: 0, title = o.optString("title"), categoryId = catId,
                    allDay = o.optBoolean("allDay"),
                    startDay = o.optLong("startDay"), endDay = o.optLong("endDay"),
                    startMin = o.optInt("startMin"), endMin = o.optInt("endMin"),
                    location = o.optString("location"), memo = o.optString("memo"),
                    freq = o.optInt("freq"), interval = o.optInt("interval", 1),
                    weekdays = o.optInt("weekdays"),
                    monthlyByWeekday = o.optBoolean("monthlyByWeekday"),
                    untilDay = o.optLong("untilDay", -1L),
                    uid = uid, updatedAt = up, dirty = true
                )
                val id = if (ex == null) db.eventDao().insertSeries(s)
                else { db.eventDao().updateSeries(s); ex.id }
                db.eventDao().deleteRemindersOf(id)
                o.optJSONArray("reminders")?.let { rs ->
                    for (j in 0 until rs.length()) {
                        val ro = rs.getJSONObject(j)
                        db.eventDao().insertReminder(com.looka.app.data.Reminder(
                            seriesId = id, minutesBefore = ro.optInt("m", 15),
                            daysBefore = ro.optInt("d"), timeOfDayMin = ro.optInt("t", 480),
                            enabled = ro.optBoolean("on", true)
                        ))
                    }
                }
                merged++
            }
        }
        root.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                val ex = db.noteDao().byUid(uid)
                val up = o.optLong("updatedAt", now)
                if (ex == null) {
                    db.noteDao().insert(Note(title = o.optString("title"), content = o.optString("content"),
                        uid = uid, updatedAt = up, dirty = true)); merged++
                } else if (up > ex.updatedAt) {
                    db.noteDao().update(ex.copy(title = o.optString("title", ex.title),
                        content = o.optString("content", ex.content),
                        updatedAt = up, dirty = true, deleted = false)); merged++
                }
            }
        }
        root.optJSONArray("diaries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                val ex = db.diaryDao().byUid(uid)
                val up = o.optLong("updatedAt", now)
                if (ex == null || up > ex.updatedAt) {
                    db.diaryDao().upsert(Diary(
                        id = ex?.id ?: 0, day = o.optLong("day"), mood = o.optInt("mood", 2),
                        content = o.optString("content"), uid = uid, updatedAt = up,
                        dirty = true, deleted = false
                    )); merged++
                }
            }
        }
        root.optJSONArray("stamps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optString("uid"); if (uid.isBlank()) continue
                if (db.stampDao().byUid(uid) == null) {
                    db.stampDao().insert(Stamp(
                        emoji = o.optString("emoji", "🦌"), day = o.optLong("day"),
                        eventUid = o.optString("eventUid"), assetId = o.optString("assetId"),
                        uid = uid, updatedAt = o.optLong("updatedAt", now), dirty = true
                    )); merged++
                }
            }
        }
        merged
    }
}

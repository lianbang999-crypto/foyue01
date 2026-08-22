package com.looka.app.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.looka.app.LookaApp
import com.looka.app.data.Category
import com.looka.app.data.Diary
import com.looka.app.data.EventException
import com.looka.app.data.EventSeries
import com.looka.app.data.Note
import com.looka.app.data.Prefs
import com.looka.app.data.Reminder
import com.looka.app.data.Stamp
import com.looka.app.data.Task
import com.looka.app.data.TaskList
import com.looka.app.data.ConflictLog
import com.looka.app.notify.NotifyScheduler
import com.looka.app.util.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步引擎：本机优先 + LWW（updatedAt 新者胜）。
 * 记录形态：{kind, uid, updated_at, deleted, payload}；
 * 日程 payload 内嵌提醒与例外，分类引用一律用分类 uid。
 */
object SyncEngine {

    var syncing by mutableStateOf(false)
        private set
    var lastMsg by mutableStateOf("")

    private var kickJob: Job? = null

    /** 数据变更后延迟触发一次静默同步（未登录直接跳过） */
    fun kick(app: LookaApp) {
        if (!Api.authed(app)) return
        kickJob?.cancel()
        kickJob = app.appScope.launch {
            delay(2500)
            runCatching { sync(app) }
        }
    }

    /** 登录后调用：全部标脏，把本机数据合并进账号 */
    suspend fun markAllDirty(app: LookaApp) {
        val db = app.db
        db.categoryDao().markAllDirty()
        db.taskListDao().markAllDirty()
        db.eventDao().markAllDirty()
        db.taskDao().markAllDirty()
        db.noteDao().markAllDirty()
        db.diaryDao().markAllDirty()
        db.stampDao().markAllDirty()
    }

    suspend fun sync(app: LookaApp): String {
        if (!Api.authed(app)) return tr("未登录")
        if (syncing) return tr("同步进行中")
        syncing = true
        try {
            var push = buildPush(app)
            // 分页拉取（S1 修复）：游标只随 next_since 前进，绝不跳到 server_time
            var since = Prefs.lastPullMs(app)
            var pages = 0
            while (pages < 20) {
                val resp = Api.sync(app, push, since)
                applyRecords(app, resp.optJSONArray("apply") ?: JSONArray())
                if (push.length() > 0) {
                    confirmPush(app, push)
                    push = JSONArray()   // 上行只发一次，后续纯拉取
                }
                val next = resp.optLong("next_since", since)
                // P2-A6：同步顺路捎回的订阅状态 —— 高频免费刷新通道
                if (resp.has("plan")) {
                    com.looka.app.data.PlanState.apply(
                        app, resp.optString("plan", "free"), resp.optLong("plan_expiry", 0L))
                }
                if (next > since) {
                    since = next
                    Prefs.setLastPullMs(app, since)
                }
                pages++
                if (!resp.optBoolean("has_more", false)) break
            }
            lastMsg = tr("上次同步 {0}",
                SimpleDateFormat(if (com.looka.app.util.I18n.isZh()) tr("M月d日 HH:mm") else "MMM d HH:mm",
                    Locale.getDefault()).format(Date()))
            NotifyScheduler.rescheduleFromDb(app)
            return "ok"
        } catch (e: Exception) {
            lastMsg = tr("同步失败：{0}", e.message ?: tr("网络异常"))
            throw e
        } finally {
            syncing = false
        }
    }

    // ================= 组装上行 =================

    private suspend fun buildPush(app: LookaApp): JSONArray {
        val db = app.db
        val arr = JSONArray()
        // P5-1：设置也是同步实体（uid 固定 'settings'）—— 两端必须看到同一本日历
        if (Prefs.settingsDirty(app)) {
            arr.put(rec("settings", "settings", Prefs.settingsUpdatedAt(app), false,
                JSONObject()
                    .put("weekStartMon", Prefs.weekStartMonday(app))
                    .put("showLunar", Prefs.showLunarRaw(app) ?: JSONObject.NULL)
                    .put("holidayMask", Prefs.holidayMask(app))
                    .put("showDoneTasks", Prefs.showDoneTasks(app))))
        }
        val catUidById = db.categoryDao().list().associate { it.id to it.uid }

        for (c in db.categoryDao().dirtyList()) {
            arr.put(rec("category", c.uid, c.updatedAt, c.deleted,
                if (c.deleted) null else JSONObject()
                    .put("name", c.name).put("color", c.colorHex).put("sort", c.sortOrder)
                    .put("visible", c.visible).put("deletable", c.deletable)))
        }
        for (l in db.taskListDao().dirtyList()) {
            arr.put(rec("tasklist", l.uid, l.updatedAt, l.deleted,
                if (l.deleted) null else JSONObject()
                    .put("name", l.name).put("color", l.colorHex).put("sort", l.sortOrder)
                    .put("archived", l.archived).put("deletable", l.deletable)))
        }
        for (s in db.eventDao().dirtySeries()) {
            val payload = if (s.deleted) null else eventPayload(
                s, catUidById, db.eventDao().remindersOf(s.id), db.eventDao().exceptionsOf(s.id)
            )
            arr.put(rec("event", s.uid, s.updatedAt, s.deleted, payload))
        }
        for (t in db.taskDao().dirtyList()) {
            arr.put(rec("task", t.uid, t.updatedAt, t.deleted,
                if (t.deleted) null else JSONObject()
                    .put("title", t.title).put("done", t.done).put("dueDay", t.dueDay)
                    .put("memo", t.memo).put("createdAt", t.createdAt)
                    .put("listUid", t.listUid).put("starred", t.starred)
                    .put("doneAt", t.doneAt).put("labels", t.labels)
                    .put("sortOrder", t.sortOrder)))
        }
        for (n in db.noteDao().dirtyList()) {
            arr.put(rec("note", n.uid, n.updatedAt, n.deleted,
                if (n.deleted) null else JSONObject().put("title", n.title).put("content", n.content)))
        }
        for (d in db.diaryDao().dirtyList()) {
            arr.put(rec("diary", d.uid, d.updatedAt, d.deleted,
                if (d.deleted) null else JSONObject()
                    .put("day", d.day).put("mood", d.mood).put("content", d.content)))
        }
        for (s in db.stampDao().dirtyList()) {
            arr.put(rec("stamp", s.uid, s.updatedAt, s.deleted,
                if (s.deleted) null else JSONObject()
                    .put("emoji", s.emoji).put("day", s.day).put("eventUid", s.eventUid)
                    .put("assetId", s.assetId)))
        }
        return arr
    }

    private fun rec(kind: String, uid: String, updatedAt: Long, deleted: Boolean, payload: JSONObject?) =
        JSONObject().put("kind", kind).put("uid", uid).put("updated_at", updatedAt)
            .put("deleted", if (deleted) 1 else 0).put("payload", payload?.toString() ?: "")

    private fun eventPayload(
        s: EventSeries,
        catUidById: Map<Long, String>,
        reminders: List<Reminder>,
        exceptions: List<EventException>
    ): JSONObject {
        val rs = JSONArray()
        reminders.forEach {
            rs.put(JSONObject().put("m", it.minutesBefore).put("d", it.daysBefore)
                .put("t", it.timeOfDayMin).put("on", it.enabled))
        }
        val exs = JSONArray()
        exceptions.forEach { e ->
            val o = JSONObject().put("occ", e.occurrenceDay).put("cancelled", e.cancelled).put("newDay", e.newDay)
            e.title?.let { o.put("title", it) }
            e.allDay?.let { o.put("allDay", it) }
            e.startMin?.let { o.put("startMin", it) }
            e.endMin?.let { o.put("endMin", it) }
            e.categoryId?.let { cid -> catUidById[cid]?.let { o.put("categoryUid", it) } }
            e.location?.let { o.put("location", it) }
            e.memo?.let { o.put("memo", it) }
            exs.put(o)
        }
        return JSONObject()
            .put("title", s.title)
            .put("categoryUid", catUidById[s.categoryId] ?: "cat-default-1")
            .put("allDay", s.allDay)
            .put("startDay", s.startDay).put("endDay", s.endDay)
            .put("startMin", s.startMin).put("endMin", s.endMin)
            .put("location", s.location).put("memo", s.memo)
            .put("freq", s.freq).put("interval", s.interval).put("weekdays", s.weekdays)
            .put("monthlyByWeekday", s.monthlyByWeekday).put("untilDay", s.untilDay)
            .put("reminders", rs).put("exceptions", exs)
    }

    // ================= 应用下行 =================

    /** 冲突留痕（B19）：云端要覆盖一条本机未上传的修改时，把本机版本存进冲突记录 */
    private suspend fun logConflict(app: LookaApp, kind: String, title: String, snapshot: String) {
        runCatching {
            app.db.conflictDao().insert(ConflictLog(kind = kind, title = title, payload = snapshot))
        }
    }

    private suspend fun applyRecords(app: LookaApp, arr: JSONArray) {
        val db = app.db
        // 先应用分类/清单（其余记录按 uid 引用它们）
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedBy {
                when (it.optString("kind")) {
                    "category" -> 0
                    "tasklist" -> 1
                    else -> 2
                }
            }

        for (r in list) {
            val kind = r.optString("kind")
            val uid = r.optString("uid")
            val up = r.optLong("updated_at")
            val del = r.optInt("deleted") == 1
            val payload = r.optString("payload")
            val o = if (del || payload.isBlank()) JSONObject() else try {
                JSONObject(payload)
            } catch (_: Exception) {
                JSONObject()
            }

            when (kind) {
                "settings" -> {
                    // 远端更新才应用（本地更新中的脏设置不被旧值覆盖）
                    if (!Prefs.settingsDirty(app) || up > Prefs.settingsUpdatedAt(app)) {
                        val sp0 = app.getSharedPreferences("looka_prefs", android.content.Context.MODE_PRIVATE).edit()
                        sp0.putBoolean("week_start_mon", o.optBoolean("weekStartMon", true))
                        if (o.isNull("showLunar")) sp0.putInt("show_lunar", -1)
                        else sp0.putInt("show_lunar", if (o.optBoolean("showLunar")) 1 else 0)
                        sp0.putInt("holiday_mask", o.optInt("holidayMask", 1 shl 6))
                        sp0.putBoolean("show_done_tasks", o.optBoolean("showDoneTasks", true))
                        sp0.putBoolean("st_dirty", false).putLong("st_updated", up).apply()
                    }
                }

                "category" -> {
                    val ex = db.categoryDao().byUid(uid)
                    if (del) {
                        if (ex != null && ex.deletable) db.categoryDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.categoryDao().insert(
                            Category(
                                name = o.optString("name", tr("分类")), colorHex = o.optString("color", "#9AA0A6"),
                                sortOrder = o.optInt("sort"), visible = o.optBoolean("visible", true),
                                deletable = o.optBoolean("deletable", true),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        db.categoryDao().update(
                            ex.copy(
                                name = o.optString("name", ex.name), colorHex = o.optString("color", ex.colorHex),
                                sortOrder = o.optInt("sort", ex.sortOrder),
                                visible = o.optBoolean("visible", ex.visible),
                                deletable = o.optBoolean("deletable", ex.deletable),
                                updatedAt = up, dirty = false, deleted = false
                            )
                        )
                    }
                }

                "tasklist" -> {
                    val ex = db.taskListDao().byUid(uid)
                    if (del) {
                        if (ex != null && ex.deletable) db.taskListDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.taskListDao().insert(
                            TaskList(
                                name = o.optString("name", tr("清单")),
                                colorHex = o.optString("color", "#5C6670"),
                                sortOrder = o.optInt("sort"),
                                archived = o.optBoolean("archived"),
                                deletable = o.optBoolean("deletable", true),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        db.taskListDao().update(
                            ex.copy(
                                name = o.optString("name", ex.name),
                                colorHex = o.optString("color", ex.colorHex),
                                sortOrder = o.optInt("sort", ex.sortOrder),
                                archived = o.optBoolean("archived", ex.archived),
                                deletable = o.optBoolean("deletable", ex.deletable),
                                updatedAt = up, dirty = false, deleted = false
                            )
                        )
                    }
                }

                "event" -> {
                    val ex = db.eventDao().seriesByUid(uid)
                    if (del) {
                        if (ex != null) {
                            db.eventDao().deleteRemindersOf(ex.id)
                            db.eventDao().deleteExceptionsOf(ex.id)
                            db.eventDao().hardDeleteSeries(ex.id)
                        }
                    } else if (ex == null || up > ex.updatedAt) {
                        if (ex != null && ex.dirty) logConflict(app, "event", ex.title,
                            "${com.looka.app.util.Fmt.iso(ex.startDay)} ${ex.title}\n${ex.location}\n${ex.memo}")
                        val catId = db.categoryDao().byUid(o.optString("categoryUid"))?.id ?: 1L
                        val s = EventSeries(
                            id = ex?.id ?: 0,
                            title = o.optString("title"), categoryId = catId,
                            allDay = o.optBoolean("allDay"),
                            startDay = o.optLong("startDay"), endDay = o.optLong("endDay"),
                            startMin = o.optInt("startMin"), endMin = o.optInt("endMin"),
                            location = o.optString("location"), memo = o.optString("memo"),
                            freq = o.optInt("freq"), interval = o.optInt("interval", 1),
                            weekdays = o.optInt("weekdays"),
                            monthlyByWeekday = o.optBoolean("monthlyByWeekday"),
                            untilDay = o.optLong("untilDay", -1L),
                            uid = uid, updatedAt = up, dirty = false, deleted = false
                        )
                        val id = if (ex == null) db.eventDao().insertSeries(s)
                        else { db.eventDao().updateSeries(s); ex.id }
                        db.eventDao().deleteRemindersOf(id)
                        db.eventDao().deleteExceptionsOf(id)
                        o.optJSONArray("reminders")?.let { rs ->
                            for (i in 0 until rs.length()) {
                                val ro = rs.getJSONObject(i)
                                db.eventDao().insertReminder(
                                    Reminder(
                                        seriesId = id, minutesBefore = ro.optInt("m", 15),
                                        daysBefore = ro.optInt("d"), timeOfDayMin = ro.optInt("t", 480),
                                        enabled = ro.optBoolean("on", true)
                                    )
                                )
                            }
                        }
                        o.optJSONArray("exceptions")?.let { exs ->
                            for (i in 0 until exs.length()) {
                                val eo = exs.getJSONObject(i)
                                val ecat = if (eo.has("categoryUid"))
                                    db.categoryDao().byUid(eo.optString("categoryUid"))?.id else null
                                db.eventDao().insertException(
                                    EventException(
                                        seriesId = id, occurrenceDay = eo.optLong("occ"),
                                        cancelled = eo.optBoolean("cancelled"),
                                        newDay = eo.optLong("newDay", -1L),
                                        title = if (eo.has("title")) eo.optString("title") else null,
                                        allDay = if (eo.has("allDay")) eo.optBoolean("allDay") else null,
                                        startMin = if (eo.has("startMin")) eo.optInt("startMin") else null,
                                        endMin = if (eo.has("endMin")) eo.optInt("endMin") else null,
                                        categoryId = ecat,
                                        location = if (eo.has("location")) eo.optString("location") else null,
                                        memo = if (eo.has("memo")) eo.optString("memo") else null
                                    )
                                )
                            }
                        }
                    }
                }

                "task" -> {
                    val ex = db.taskDao().byUid(uid)
                    if (del) {
                        if (ex != null) db.taskDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.taskDao().insert(
                            Task(
                                title = o.optString("title"), done = o.optBoolean("done"),
                                dueDay = o.optLong("dueDay", -1L), memo = o.optString("memo"),
                                createdAt = o.optLong("createdAt", up),
                                listUid = o.optString("listUid").ifBlank { "list-default" },
                                starred = o.optBoolean("starred"),
                                doneAt = o.optLong("doneAt", -1L),
                                labels = o.optString("labels"),
                                sortOrder = o.optLong("sortOrder"),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        if (ex.dirty) logConflict(app, "task", ex.title, "${ex.title}\n${ex.memo}")
                        db.taskDao().update(
                            ex.copy(
                                title = o.optString("title", ex.title), done = o.optBoolean("done", ex.done),
                                dueDay = o.optLong("dueDay", ex.dueDay), memo = o.optString("memo", ex.memo),
                                listUid = o.optString("listUid").ifBlank { ex.listUid },
                                starred = o.optBoolean("starred", ex.starred),
                                doneAt = o.optLong("doneAt", ex.doneAt),
                                labels = o.optString("labels", ex.labels),
                                sortOrder = o.optLong("sortOrder", ex.sortOrder),
                                updatedAt = up, dirty = false, deleted = false
                            )
                        )
                    }
                }

                "note" -> {
                    val ex = db.noteDao().byUid(uid)
                    if (del) {
                        if (ex != null) db.noteDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.noteDao().insert(
                            Note(
                                title = o.optString("title"), content = o.optString("content"),
                                updatedAt = up, uid = uid, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        if (ex.dirty) logConflict(app, "note", ex.title.ifBlank { ex.content.take(20) }, ex.content)
                        db.noteDao().update(
                            ex.copy(
                                title = o.optString("title", ex.title),
                                content = o.optString("content", ex.content),
                                updatedAt = up, dirty = false, deleted = false
                            )
                        )
                    }
                }

                "diary" -> {
                    val ex = db.diaryDao().byUid(uid)
                    if (del) {
                        if (ex != null) db.diaryDao().hardDeleteByUid(uid)
                    } else if (ex == null || up > ex.updatedAt) {
                        if (ex != null && ex.dirty && ex.content.isNotBlank())
                            logConflict(app, "diary", com.looka.app.util.Fmt.iso(ex.day), ex.content)
                        db.diaryDao().upsert(
                            Diary(
                                id = ex?.id ?: 0, day = o.optLong("day"),
                                mood = o.optInt("mood", 2), content = o.optString("content"),
                                updatedAt = up, uid = uid, dirty = false, deleted = false
                            )
                        )
                    }
                }

                "stamp" -> {
                    val ex = db.stampDao().byUid(uid)
                    if (del) {
                        if (ex != null) db.stampDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.stampDao().insert(
                            Stamp(
                                emoji = o.optString("emoji", "🦌"), day = o.optLong("day"),
                                eventUid = o.optString("eventUid"),
                                assetId = o.optString("assetId"),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        db.stampDao().update(
                            ex.copy(
                                emoji = o.optString("emoji", ex.emoji), day = o.optLong("day", ex.day),
                                eventUid = o.optString("eventUid", ex.eventUid),
                                assetId = o.optString("assetId", ex.assetId),
                                updatedAt = up, dirty = false, deleted = false
                            )
                        )
                    }
                }
            }
        }
    }

    // ================= 上行确认（清脏 / 清墓碑） =================

    private suspend fun confirmPush(app: LookaApp, push: JSONArray) {
        val db = app.db
        for (i in 0 until push.length()) {
            val r = push.getJSONObject(i)
            val kind = r.optString("kind")
            val uid = r.optString("uid")
            val up = r.optLong("updated_at")
            val del = r.optInt("deleted") == 1
            when (kind) {
                "settings" -> Prefs.setSettingsDirty(app, false)

                "category" -> {
                    if (del) db.categoryDao().hardDeleteByUid(uid)
                    else db.categoryDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.categoryDao().update(it.copy(dirty = false))
                    }
                }
                "tasklist" -> {
                    if (del) db.taskListDao().hardDeleteByUid(uid)
                    else db.taskListDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.taskListDao().update(it.copy(dirty = false))
                    }
                }
                "event" -> {
                    if (del) db.eventDao().seriesByUid(uid)?.let {
                        db.eventDao().deleteRemindersOf(it.id)
                        db.eventDao().deleteExceptionsOf(it.id)
                        db.eventDao().hardDeleteSeries(it.id)
                    } else db.eventDao().seriesByUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.eventDao().updateSeries(it.copy(dirty = false))
                    }
                }
                "task" -> {
                    if (del) db.taskDao().hardDeleteByUid(uid)
                    else db.taskDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.taskDao().update(it.copy(dirty = false))
                    }
                }
                "note" -> {
                    if (del) db.noteDao().hardDeleteByUid(uid)
                    else db.noteDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.noteDao().update(it.copy(dirty = false))
                    }
                }
                "diary" -> {
                    if (del) db.diaryDao().hardDeleteByUid(uid)
                    else db.diaryDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.diaryDao().update(it.copy(dirty = false))
                    }
                }
                "stamp" -> {
                    if (del) db.stampDao().hardDeleteByUid(uid)
                    else db.stampDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.stampDao().update(it.copy(dirty = false))
                    }
                }
            }
        }
    }
}

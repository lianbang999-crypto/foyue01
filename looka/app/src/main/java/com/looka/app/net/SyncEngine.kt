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
import com.looka.app.data.NOTE_LIST_DEFAULT
import com.looka.app.data.NoteList
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

    /** §97 TL-001：单次上行块大小，必须 ≤ 服务端 PUSH_MAX(500) */
    private const val PUSH_CHUNK = 500

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
            // §97 TL-001：**整批脏记录不再一次性发出去当成功**。
            // 原来的写法是「组全量 → 发一次 → confirmPush(全量)」，而服务端只处理前 500 条 ——
            // 第 501 条起被静默丢弃，本地却已清脏、墓碑还被物理删掉，数据无声消失。
            // 现在按 PUSH_CHUNK 切块，**每块拿到服务端 push_accepted 回执才清那一块的脏**。
            val pending = buildPush(app)
            // 分页拉取（S1 修复）：游标只随 next_since 前进，绝不跳到 server_time
            var since = Prefs.lastPullMs(app)
            // §97 TL-006：复合游标的另两段，续同毫秒批用
            var sinceKind = Prefs.lastPullKind(app)
            var sinceUid = Prefs.lastPullUid(app)
            var sent = 0                    // pending 里已确认的条数
            var pages = 0
            while (pages < 60) {
                // 本轮要发的一块（≤ PUSH_CHUNK）；发完了就纯拉取
                val chunk = JSONArray()
                var i = sent
                while (i < pending.length() && chunk.length() < PUSH_CHUNK) {
                    chunk.put(pending.get(i)); i++
                }
                val resp = Api.sync(app, chunk, since, sinceKind, sinceUid)
                applyRecords(app, resp.optJSONArray("apply") ?: JSONArray())

                if (chunk.length() > 0) {
                    // 服务端说看了前 n 条就只确认前 n 条；老服务端没这个字段时按整块算
                    val acc = resp.optInt("push_accepted", chunk.length()).coerceIn(0, chunk.length())
                    if (acc > 0) {
                        val done = JSONArray()
                        for (k in 0 until acc) done.put(chunk.get(k))
                        confirmPush(app, done)
                        sent += acc
                    }
                    // 一条都没被接收 → 再发也是同样结果，停下，脏记录留着下次同步
                    if (acc == 0) break
                }

                val next = resp.optLong("next_since", since)
                val nextKind = resp.optString("next_kind", sinceKind)
                val nextUid = resp.optString("next_uid", sinceUid)
                // P2-A6：同步顺路捎回的订阅状态 —— 高频免费刷新通道
                if (resp.has("plan")) {
                    com.looka.app.data.PlanState.apply(
                        app, resp.optString("plan", "free"), resp.optLong("plan_expiry", 0L))
                }
                // 三元组任一段前进就落盘游标（同毫秒批靠 kind/uid 往前走）
                if (next > since || nextKind != sinceKind || nextUid != sinceUid) {
                    since = next; sinceKind = nextKind; sinceUid = nextUid
                    Prefs.setLastPull(app, since, sinceKind, sinceUid)
                }
                pages++
                // 上行没发完就继续循环；发完了再看服务端还有没有下行
                if (sent >= pending.length() && !resp.optBoolean("has_more", false)) break
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
                    .put("showDoneTasks", Prefs.showDoneTasks(app))
                    .put("stampTitle", Prefs.stampTitle(app))
                    // B1（§48）：主题上云 —— 用户花心思调的主题换手机不能丢
                    .put("themeIndex", Prefs.themeIndex(app))
                    .put("customColor", Prefs.customThemeColor(app))
                    // D1（§52）：小鹿记事本随云走
                    .put("deerFacts", org.json.JSONArray().also { a ->
                        Prefs.deerFacts(app).forEach { a.put(it) } })))
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
        for (l in db.noteListDao().dirtyList()) {
            arr.put(rec("notelist", l.uid, l.updatedAt, l.deleted,
                if (l.deleted) null else JSONObject()
                    .put("name", l.name).put("color", l.colorHex).put("sort", l.sortOrder)
                    .put("deletable", l.deletable)))
        }
        for (n in db.noteDao().dirtyList()) {
            arr.put(rec("note", n.uid, n.updatedAt, n.deleted,
                if (n.deleted) null else JSONObject().put("title", n.title).put("content", n.content)
                    // §98 E3：createdAt 必须上云 —— 不带的话换设备后「按创建日排序」会全乱
                    .put("listUid", n.listUid).put("createdAt", n.createdAt)))
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
                .put("t", it.timeOfDayMin).put("on", it.enabled).put("al", it.alarm))
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
                    "notelist" -> 1
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
                        sp0.putBoolean("stamp_title", o.optBoolean("stampTitle", true))
                        // B1：主题跟随云端（老客户端的载荷没有这两个键时保持本地值）
                        if (o.has("themeIndex")) sp0.putInt("theme_index", o.optInt("themeIndex", 0))
                        if (o.has("customColor")) sp0.putLong("custom_theme", o.optLong("customColor", 0xFF55B04BL))
                        // D1：小鹿记事本跟随云端
                        o.optJSONArray("deerFacts")?.let { sp0.putString("deer_facts", it.toString()) }
                        sp0.putBoolean("st_dirty", false).putLong("st_updated", up).apply()
                        // 主题状态是 Compose State，落盘后要刷一次才立即生效
                        if (o.has("themeIndex")) com.looka.app.ui.theme.ThemeCtl.init(app)
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
                                        enabled = ro.optBoolean("on", true),
                                        alarm = ro.optBoolean("al", false)
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

                "notelist" -> {
                    val ex = db.noteListDao().byUid(uid)
                    if (del) {
                        if (ex != null && ex.deletable) db.noteListDao().hardDeleteByUid(uid)
                    } else if (ex == null) {
                        db.noteListDao().insert(
                            NoteList(
                                name = o.optString("name", tr("笔记")),
                                colorHex = o.optString("color", "#5C6670"),
                                sortOrder = o.optInt("sort"),
                                deletable = o.optBoolean("deletable", true),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        db.noteListDao().update(
                            ex.copy(
                                name = o.optString("name", ex.name),
                                colorHex = o.optString("color", ex.colorHex),
                                sortOrder = o.optInt("sort", ex.sortOrder),
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
                                listUid = o.optString("listUid", NOTE_LIST_DEFAULT),
                                // 老端推上来的记录没有 createdAt → 退回 updatedAt，保持单调不乱序
                                createdAt = o.optLong("createdAt", up),
                                updatedAt = up, uid = uid, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        if (ex.dirty) logConflict(app, "note", ex.title.ifBlank { ex.content.take(20) }, ex.content)
                        db.noteDao().update(
                            ex.copy(
                                title = o.optString("title", ex.title),
                                content = o.optString("content", ex.content),
                                listUid = o.optString("listUid", ex.listUid),
                                createdAt = o.optLong("createdAt", ex.createdAt),
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
                                posX = o.optDouble("px", -1.0).toFloat(),
                                posY = o.optDouble("py", -1.0).toFloat(),
                                uid = uid, updatedAt = up, dirty = false
                            )
                        )
                    } else if (up > ex.updatedAt) {
                        db.stampDao().update(
                            ex.copy(
                                emoji = o.optString("emoji", ex.emoji), day = o.optLong("day", ex.day),
                                eventUid = o.optString("eventUid", ex.eventUid),
                                assetId = o.optString("assetId", ex.assetId),
                                posX = o.optDouble("px", ex.posX.toDouble()).toFloat(),
                                posY = o.optDouble("py", ex.posY.toDouble()).toFloat(),
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
                    if (del) db.categoryDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.categoryDao().hardDeleteByUid(uid)
                    }
                    else db.categoryDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.categoryDao().update(it.copy(dirty = false))
                    }
                }
                "tasklist" -> {
                    if (del) db.taskListDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.taskListDao().hardDeleteByUid(uid)
                    }
                    else db.taskListDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.taskListDao().update(it.copy(dirty = false))
                    }
                }
                "event" -> {
                    if (del) db.eventDao().seriesByUid(uid)?.let {
                        // §97 TL-002：同上 —— 旧墓碑的回执不许删掉已被撤销/改动的行
                        if (it.deleted && it.updatedAt == up) {
                            db.eventDao().deleteRemindersOf(it.id)
                            db.eventDao().deleteExceptionsOf(it.id)
                            db.eventDao().hardDeleteSeries(it.id)
                        }
                    } else db.eventDao().seriesByUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.eventDao().updateSeries(it.copy(dirty = false))
                    }
                }
                "task" -> {
                    if (del) db.taskDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.taskDao().hardDeleteByUid(uid)
                    }
                    else db.taskDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.taskDao().update(it.copy(dirty = false))
                    }
                }
                "notelist" -> {
                    if (del) db.noteListDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.noteListDao().hardDeleteByUid(uid)
                    }
                    else db.noteListDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.noteListDao().update(it.copy(dirty = false))
                    }
                }
                "note" -> {
                    if (del) db.noteDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.noteDao().hardDeleteByUid(uid)
                    }
                    else db.noteDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.noteDao().update(it.copy(dirty = false))
                    }
                }
                "diary" -> {
                    if (del) db.diaryDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.diaryDao().hardDeleteByUid(uid)
                    }
                    else db.diaryDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.diaryDao().update(it.copy(dirty = false))
                    }
                }
                "stamp" -> {
                    if (del) db.stampDao().byUid(uid)?.let {
                        // §97 TL-002：只有本地行仍是**这个墓碑版本**才物理删除。
                        // 撤销会把 deleted 改回 false 并抬高 updatedAt —— 那条不能被旧墓碑的回执删掉。
                        if (it.deleted && it.updatedAt == up) db.stampDao().hardDeleteByUid(uid)
                    }
                    else db.stampDao().byUid(uid)?.let {
                        if (it.dirty && it.updatedAt == up) db.stampDao().update(it.copy(dirty = false))
                    }
                }
            }
        }
    }
}

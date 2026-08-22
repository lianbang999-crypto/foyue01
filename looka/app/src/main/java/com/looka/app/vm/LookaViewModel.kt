package com.looka.app.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.looka.app.LookaApp
import com.looka.app.ai.AiAction
import com.looka.app.ai.AiActions
import com.looka.app.ai.AiClient
import com.looka.app.data.Category
import com.looka.app.data.Diary
import com.looka.app.data.EventException
import com.looka.app.data.EventSeries
import com.looka.app.data.FREQ_NONE
import com.looka.app.data.MOOD_EMOJIS
import com.looka.app.data.Note
import com.looka.app.data.Occ
import com.looka.app.data.Prefs
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Reminder
import com.looka.app.data.Stamp
import com.looka.app.data.Task
import com.looka.app.data.TaskList
import com.looka.app.data.Template
import com.looka.app.data.newUid
import com.looka.app.net.SyncEngine
import com.looka.app.notify.NotifyScheduler
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

const val ROLE_USER = 0
const val ROLE_AI = 1
const val ROLE_ACTION = 2

data class ChatMsg(val role: Int, val text: String, val error: Boolean = false,
                   /** 这一轮实际生效的模型档：standard / premium。用于在气泡上标注 */
                   val tier: String = "standard")

/** 日程编辑草稿：编辑器与重复编辑器之间共享（规格 CAL-010/011/013） */
class EventDraft {
    var editingSeriesId by mutableLongStateOf(-1L)
    var originalSeries: EventSeries? = null
    var occurrenceDay by mutableLongStateOf(-1L)
    var uid: String = newUid()
    var title by mutableStateOf("")
    var categoryId by mutableLongStateOf(1L)
    var allDay by mutableStateOf(false)
    var startDay by mutableLongStateOf(Fmt.today())
    var endDay by mutableLongStateOf(Fmt.today())
    var startMin by mutableIntStateOf(9 * 60)
    var endMin by mutableIntStateOf(10 * 60)
    var location by mutableStateOf("")
    var memo by mutableStateOf("")
    var freq by mutableIntStateOf(FREQ_NONE)
    var interval by mutableIntStateOf(1)
    var weekdays by mutableIntStateOf(0)
    var monthlyByWeekday by mutableStateOf(false)
    var untilDay by mutableLongStateOf(-1L)
    val reminders = mutableStateListOf<Reminder>()
    var remindersTouched = false
    var detailExpanded by mutableStateOf(false)

    /** 草稿 → 系列实体（时间合法性收口，默认已标脏待同步） */
    fun toSeries(id: Long = 0, uidOverride: String = uid): EventSeries {
        val ed = maxOf(endDay, startDay)
        var em = endMin
        if (!allDay && startDay == ed && em <= startMin) em = minOf(startMin + 30, 24 * 60 - 1)
        return EventSeries(
            id = id, title = title.trim(), categoryId = categoryId, allDay = allDay,
            startDay = startDay, endDay = ed, startMin = startMin, endMin = em,
            location = location.trim(), memo = memo.trim(),
            freq = freq, interval = interval.coerceAtLeast(1), weekdays = weekdays,
            monthlyByWeekday = monthlyByWeekday, untilDay = untilDay,
            uid = uidOverride, updatedAt = System.currentTimeMillis(), dirty = true
        )
    }
}

class LookaViewModel(app: Application) : AndroidViewModel(app) {

    private val looka = app as LookaApp
    private val db = looka.db
    private val categoryDao = db.categoryDao()
    private val eventDao = db.eventDao()
    private val taskDao = db.taskDao()
    private val noteDao = db.noteDao()
    private val diaryDao = db.diaryDao()
    private val stampDao = db.stampDao()
    private val templateDao = db.templateDao()
    private val taskListDao = db.taskListDao()

    val categories = categoryDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val seriesAll = eventDao.allSeries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val exceptionsAll = eventDao.allExceptions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tasks = taskDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = noteDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val diaries = diaryDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val stamps = stampDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val templates = templateDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val taskLists = taskListDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val remindersAll = eventDao.allReminders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 日历界面状态（跨页面保留） ----
    var selectedDay by mutableLongStateOf(Fmt.today())
    var calMonth: YearMonth by mutableStateOf(YearMonth.now())
    var calView by mutableIntStateOf(0)

    /**
     * 月视图连续滚动的跳转请求（2026-08-21）：
     * 滚动会反向驱动 calMonth（视口→标题），所以「今天/跳转/通知」不能只改 calMonth——
     * 得显式请求滚动到某天，月视图消费后置回 null。
     */
    var calScrollReq by mutableStateOf<Long?>(null)
    var settingsVersion by mutableIntStateOf(0)
    fun bumpSettings() { settingsVersion++ }

    var draft: EventDraft? = null

    init {
        // 冷启动拉一次云端数据
        looka.appScope.launch { runCatching { SyncEngine.sync(looka) } }
    }

    /** 数据变更后：重排提醒 + 触发静默同步 */
    private suspend fun afterChange() {
        NotifyScheduler.rescheduleFromDb(looka)
        SyncEngine.kick(looka)
    }

    private fun now() = System.currentTimeMillis()

    // ================= 草稿准备 =================

    fun prepareCreateDraft(day: Long, startMin: Int = -1, allDay: Boolean? = null) {
        val c = getApplication<Application>()
        val d = EventDraft()
        d.startDay = day
        d.endDay = day
        d.categoryId = Prefs.defaultCategoryId(c)
        d.allDay = allDay ?: Prefs.defaultAllDay(c)
        if (startMin >= 0) {
            d.allDay = false
            d.startMin = startMin
            d.endMin = minOf(startMin + 60, 24 * 60 - 1)
        } else {
            val h = (LocalTime.now().hour + 1).coerceAtMost(23)
            d.startMin = h * 60
            d.endMin = minOf(h * 60 + 60, 24 * 60 - 1)
        }
        d.reminders.clear()
        addDefaultReminder(d)
        draft = d
    }

    fun refreshDefaultReminder(d: EventDraft) {
        if (d.remindersTouched || d.editingSeriesId >= 0) return
        d.reminders.clear()
        addDefaultReminder(d)
    }

    private fun addDefaultReminder(d: EventDraft) {
        val c = getApplication<Application>()
        if (d.allDay) {
            val days = Prefs.defAllDayReminderDays(c)
            if (days >= 0) d.reminders.add(
                Reminder(daysBefore = days, timeOfDayMin = Prefs.defAllDayReminderTime(c))
            )
        } else {
            val min = Prefs.defTimedReminderMin(c)
            if (min >= 0) d.reminders.add(Reminder(minutesBefore = min))
        }
    }

    suspend fun prepareEditDraft(seriesId: Long, occDay: Long): Boolean {
        val s = eventDao.series(seriesId) ?: return false
        val ex = eventDao.exceptionsList().find { it.seriesId == seriesId && it.occurrenceDay == occDay }
        val o = RecurrenceEngine.mergeOcc(s, occDay, ex) ?: return false
        val d = EventDraft()
        d.editingSeriesId = s.id
        d.originalSeries = s
        d.occurrenceDay = occDay
        d.uid = s.uid
        d.title = o.title
        d.categoryId = o.categoryId
        d.allDay = o.allDay
        d.startDay = o.day
        d.endDay = o.endDay
        d.startMin = o.startMin
        d.endMin = o.endMin
        d.location = o.location
        d.memo = o.memo
        d.freq = s.freq
        d.interval = s.interval
        d.weekdays = s.weekdays
        d.monthlyByWeekday = s.monthlyByWeekday
        d.untilDay = s.untilDay
        d.reminders.clear()
        d.reminders.addAll(eventDao.remindersOf(s.id))
        d.remindersTouched = true
        d.detailExpanded = true
        draft = d
        return true
    }

    // ================= 日程保存 / 删除 =================

    fun saveCreate(d: EventDraft, onDone: () -> Unit) = viewModelScope.launch {
        val id = eventDao.insertSeries(d.toSeries(uidOverride = newUid()))
        d.reminders.forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = id)) }
        afterChange()
        onDone()
    }

    /** 编辑范围 = 全部（普通日程编辑也走这里，规格 CAL-021） */
    fun saveEditAll(d: EventDraft, onDone: () -> Unit) = viewModelScope.launch {
        val orig = d.originalSeries ?: return@launch
        val delta = d.startDay - d.occurrenceDay
        val newStart = orig.startDay + delta
        val span = maxOf(d.endDay - d.startDay, 0)
        eventDao.updateSeries(d.toSeries(id = orig.id).copy(startDay = newStart, endDay = newStart + span))
        eventDao.deleteRemindersOf(orig.id)
        d.reminders.forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = orig.id)) }
        if (delta != 0L) eventDao.deleteExceptionsOf(orig.id)
        afterChange()
        onDone()
    }

    /** 编辑范围 = 仅本次：写例外 override，并把系列标脏（例外随系列打包同步） */
    fun saveEditThisOnly(d: EventDraft, onDone: () -> Unit) = viewModelScope.launch {
        val sid = d.editingSeriesId
        if (sid < 0) return@launch
        eventDao.deleteException(sid, d.occurrenceDay)
        eventDao.insertException(
            EventException(
                seriesId = sid,
                occurrenceDay = d.occurrenceDay,
                newDay = if (d.startDay != d.occurrenceDay) d.startDay else -1L,
                title = d.title.trim(),
                allDay = d.allDay,
                startMin = d.startMin,
                endMin = d.endMin,
                categoryId = d.categoryId,
                location = d.location.trim(),
                memo = d.memo.trim()
            )
        )
        eventDao.touchSeries(sid, now())
        afterChange()
        onDone()
    }

    /** 编辑范围 = 本次及以后：截断旧系列，新建系列段（规格 §11.2） */
    fun saveEditFuture(d: EventDraft, onDone: () -> Unit) = viewModelScope.launch {
        val orig = d.originalSeries ?: return@launch
        if (d.occurrenceDay <= orig.startDay) {
            eventDao.deleteExceptionsOf(orig.id)
            val span = maxOf(d.endDay - d.startDay, 0)
            eventDao.updateSeries(d.toSeries(id = orig.id).copy(startDay = d.startDay, endDay = d.startDay + span))
            eventDao.deleteRemindersOf(orig.id)
            d.reminders.forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = orig.id)) }
        } else {
            eventDao.updateSeries(orig.copy(untilDay = d.occurrenceDay - 1, dirty = true, updatedAt = now()))
            eventDao.deleteExceptionsFrom(orig.id, d.occurrenceDay)
            val id = eventDao.insertSeries(d.toSeries(uidOverride = newUid()))
            d.reminders.forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = id)) }
        }
        afterChange()
        onDone()
    }

    /** 软删除整个系列（墓碑随同步下发到各端）；同时解除印章绑定（B8） */
    fun deleteSeries(id: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        eventDao.series(id)?.let {
            eventDao.updateSeries(it.copy(deleted = true, dirty = true, updatedAt = now()))
            stampDao.unbindEvent(it.uid, now())
        }
        eventDao.deleteRemindersOf(id)
        eventDao.deleteExceptionsOf(id)
        afterChange()
        onDone()
    }

    fun deleteThisOnly(seriesId: Long, occDay: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        eventDao.deleteException(seriesId, occDay)
        eventDao.insertException(EventException(seriesId = seriesId, occurrenceDay = occDay, cancelled = true))
        eventDao.touchSeries(seriesId, now())
        afterChange()
        onDone()
    }

    fun deleteFuture(seriesId: Long, occDay: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        val s = eventDao.series(seriesId) ?: return@launch
        if (occDay <= s.startDay) {
            eventDao.updateSeries(s.copy(deleted = true, dirty = true, updatedAt = now()))
            eventDao.deleteRemindersOf(seriesId)
            eventDao.deleteExceptionsOf(seriesId)
        } else {
            eventDao.updateSeries(s.copy(untilDay = occDay - 1, dirty = true, updatedAt = now()))
            eventDao.deleteExceptionsFrom(seriesId, occDay)
        }
        afterChange()
        onDone()
    }

    fun duplicateOcc(o: Occ, onDone: () -> Unit = {}) = viewModelScope.launch {
        val id = eventDao.insertSeries(
            EventSeries(
                title = o.title, categoryId = o.categoryId, allDay = o.allDay,
                startDay = o.day, endDay = o.endDay, startMin = o.startMin, endMin = o.endMin,
                location = o.location, memo = o.memo
            )
        )
        eventDao.remindersOf(o.seriesId).forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = id)) }
        afterChange()
        onDone()
    }

    suspend fun remindersOf(id: Long) = eventDao.remindersOf(id)

    fun nextOccurrenceDay(s: EventSeries): Long {
        if (s.freq == FREQ_NONE) return s.startDay
        val today = Fmt.today()
        val occ = RecurrenceEngine
            .expand(listOf(s), exceptionsAll.value.filter { it.seriesId == s.id }, today, today + 400)
            .minByOrNull { it.day }
        return occ?.occurrenceDay ?: s.startDay
    }

    // ================= 模板（规格 CAL-010 模板入口） =================

    /** 把当前草稿存为模板（不含日期） */
    fun saveTemplate(d: EventDraft) = viewModelScope.launch {
        val catUid = categories.value.find { it.id == d.categoryId }?.uid ?: "cat-default-1"
        val rs = JSONArray()
        d.reminders.forEach {
            rs.put(JSONObject().put("m", it.minutesBefore).put("d", it.daysBefore)
                .put("t", it.timeOfDayMin).put("on", it.enabled))
        }
        val payload = JSONObject()
            .put("title", d.title.trim())
            .put("categoryUid", catUid)
            .put("allDay", d.allDay)
            .put("startMin", d.startMin).put("endMin", d.endMin)
            .put("days", maxOf(d.endDay - d.startDay, 0))
            .put("location", d.location.trim()).put("memo", d.memo.trim())
            .put("freq", d.freq).put("interval", d.interval).put("weekdays", d.weekdays)
            .put("monthlyByWeekday", d.monthlyByWeekday)
            .put("reminders", rs)
        templateDao.insert(Template(title = d.title.trim().ifBlank { tr("未命名模板") }, payload = payload.toString()))
    }

    /** 套用模板到草稿（保留当前日期） */
    fun applyTemplate(d: EventDraft, t: Template) {
        try {
            val o = JSONObject(t.payload)
            d.title = o.optString("title")
            d.categoryId = categories.value.find { it.uid == o.optString("categoryUid") }?.id ?: d.categoryId
            d.allDay = o.optBoolean("allDay")
            d.startMin = o.optInt("startMin", d.startMin)
            d.endMin = o.optInt("endMin", d.endMin)
            d.endDay = d.startDay + o.optLong("days", 0)
            d.location = o.optString("location")
            d.memo = o.optString("memo")
            d.freq = o.optInt("freq")
            d.interval = o.optInt("interval", 1)
            d.weekdays = o.optInt("weekdays")
            d.monthlyByWeekday = o.optBoolean("monthlyByWeekday")
            d.reminders.clear()
            o.optJSONArray("reminders")?.let { rs ->
                for (i in 0 until rs.length()) {
                    val ro = rs.getJSONObject(i)
                    d.reminders.add(
                        Reminder(
                            minutesBefore = ro.optInt("m", 15), daysBefore = ro.optInt("d"),
                            timeOfDayMin = ro.optInt("t", 480), enabled = ro.optBoolean("on", true)
                        )
                    )
                }
            }
            d.remindersTouched = true
            if (d.freq != FREQ_NONE || d.location.isNotBlank() || d.memo.isNotBlank()) d.detailExpanded = true
        } catch (_: Exception) { }
    }

    fun deleteTemplate(t: Template) = viewModelScope.launch { templateDao.delete(t.id) }

    // ================= 分类 =================

    fun addCategory(name: String, colorHex: String) = viewModelScope.launch {
        val order = (categories.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
        categoryDao.insert(Category(name = name.trim(), colorHex = colorHex, sortOrder = order))
        afterChange()
    }

    fun updateCategory(c: Category) = viewModelScope.launch {
        categoryDao.update(c.copy(dirty = true, updatedAt = now()))
        afterChange()
    }

    fun deleteCategory(c: Category) = viewModelScope.launch {
        if (!c.deletable) return@launch
        // B7：未分类按固定 uid 查，不再硬编码 id=1
        val fallback = categoryDao.byUid("cat-default-1")?.id
            ?: categories.value.firstOrNull { !it.deletable }?.id ?: 1L
        categoryDao.reassignEvents(c.id, fallback, now())
        categoryDao.update(c.copy(deleted = true, dirty = true, updatedAt = now()))
        afterChange()
    }

    fun moveCategory(c: Category, up: Boolean) = viewModelScope.launch {
        val list = categories.value.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val i = list.indexOfFirst { it.id == c.id }
        if (i < 0) return@launch
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return@launch
        val reordered = list.toMutableList()
        reordered[i] = list[j]
        reordered[j] = list[i]
        reordered.forEachIndexed { idx, cat ->
            if (cat.sortOrder != idx) categoryDao.update(cat.copy(sortOrder = idx, dirty = true, updatedAt = now()))
        }
        afterChange()
    }

    // ================= 任务 / 笔记 / 日记 / 印章 =================

    fun addTask(title: String, due: Long = -1L, memo: String = "", listUid: String = "list-default") =
        viewModelScope.launch {
            if (title.isNotBlank()) {
                val order = taskDao.maxSortOrder() + 1
                taskDao.insert(Task(title = title.trim(), dueDay = due, memo = memo,
                    listUid = listUid, sortOrder = order))
                afterChange()
            }
        }

    /** 拖拽重排（三批）：把清单内可见顺序整体写回 sortOrder */
    fun reorderTasks(orderedUids: List<String>) = viewModelScope.launch {
        val t = now()
        orderedUids.forEachIndexed { i, uid ->
            taskDao.setSortOrder(uid, (i + 1).toLong() * 10, t)
        }
        afterChange()
    }

    /** 推迟任务到某天（拖延，快捷 chips 用） */
    fun deferTask(task: Task, day: Long) = viewModelScope.launch {
        taskDao.update(task.copy(dueDay = day, dirty = true, updatedAt = now()))
        afterChange()
    }

    /** 逾期未完成的任务数量（转移弹窗判定） */
    suspend fun overdueCount(): Int {
        val today = Fmt.today()
        return taskDao.openDueList().count { it.dueDay in 0 until today }
    }

    /** 逾期任务整体转移到今天（避免凑数） */
    fun carryOverdueToToday(onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val today = Fmt.today()
        val n = taskDao.openDueList().count { it.dueDay in 0 until today }
        taskDao.carryOverdueTo(today, now())
        afterChange()
        onDone(n)
    }

    fun updateTask(t: Task) = viewModelScope.launch {
        taskDao.update(t.copy(dirty = true, updatedAt = now()))
        afterChange()
    }

    fun toggleTask(t: Task) = viewModelScope.launch {
        val nowDone = !t.done
        taskDao.update(
            t.copy(done = nowDone, doneAt = if (nowDone) now() else -1L, dirty = true, updatedAt = now())
        )
        afterChange()
    }

    fun setTaskStar(t: Task, on: Boolean) = viewModelScope.launch {
        taskDao.update(t.copy(starred = on, dirty = true, updatedAt = now()))
        afterChange()
    }

    /** E1 撤销（2026-08-21）：删除本来就是软删（deleted=1 同步墓碑），撤销 = 改回来，白捡的 */
    var undoTask by androidx.compose.runtime.mutableStateOf<Task?>(null)
        private set
    private var undoJob: kotlinx.coroutines.Job? = null

    fun deleteTask(t: Task) = viewModelScope.launch {
        taskDao.update(t.copy(deleted = true, dirty = true, updatedAt = now()))
        afterChange()
        undoTask = t
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            undoTask = null
        }
    }

    fun undoDeleteTask() = viewModelScope.launch {
        val t = undoTask ?: return@launch
        undoTask = null; undoJob?.cancel()
        taskDao.update(t.copy(deleted = false, dirty = true, updatedAt = now()))
        afterChange()
    }

    fun clearDoneTasks() = viewModelScope.launch {
        taskDao.clearDone(now())
        afterChange()
    }

    // ---- 任务清单（Lifebear ToDo 层级） ----

    fun addTaskList(name: String, colorHex: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val order = (taskLists.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
        taskListDao.insert(TaskList(name = name.trim(), colorHex = colorHex, sortOrder = order))
        afterChange()
    }

    fun updateTaskList(l: TaskList) = viewModelScope.launch {
        taskListDao.update(l.copy(dirty = true, updatedAt = now()))
        afterChange()
    }

    /** 删除清单：任务移入默认清单，清单打墓碑 */
    fun deleteTaskList(l: TaskList) = viewModelScope.launch {
        if (!l.deletable) return@launch
        taskDao.reassignList(l.uid, "list-default", now())
        taskListDao.update(l.copy(deleted = true, dirty = true, updatedAt = now()))
        afterChange()
    }

    suspend fun note(id: Long) = noteDao.byId(id)

    fun saveNote(id: Long, title: String, content: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        if (title.isBlank() && content.isBlank()) {
            onDone(); return@launch
        }
        if (id < 0) noteDao.insert(Note(title = title.trim(), content = content))
        else noteDao.byId(id)?.let {
            noteDao.update(it.copy(title = title.trim(), content = content, updatedAt = now(), dirty = true))
        }
        afterChange()
        onDone()
    }

    fun deleteNote(id: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        noteDao.byId(id)?.let {
            noteDao.update(it.copy(deleted = true, dirty = true, updatedAt = now()))
        }
        afterChange()
        onDone()
    }

    suspend fun diaryOf(day: Long) = diaryDao.byDay(day)

    fun saveDiary(day: Long, mood: Int, content: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val ex = diaryDao.byDay(day)
        diaryDao.upsert(
            Diary(
                id = ex?.id ?: 0, day = day, mood = mood, content = content,
                updatedAt = now(), uid = "diary-$day", dirty = true
            )
        )
        afterChange()
        onDone()
    }

    fun deleteDiary(day: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        diaryDao.byDay(day)?.let {
            diaryDao.update(it.copy(deleted = true, dirty = true, updatedAt = now()))
        }
        afterChange()
        onDone()
    }

    /** 贴印章（图片资产优先，emoji 兜底）；withEventTitle 非空时同时创建全天日程并绑定（规格 CAL-051） */
    fun addStamp(emoji: String, day: Long, withEventTitle: String = "", assetId: String = "", onDone: () -> Unit = {}) =
        viewModelScope.launch {
            var eventUid = ""
            val c = getApplication<Application>()
            if (withEventTitle.isNotBlank()) {
                val uid = newUid()
                eventDao.insertSeries(
                    EventSeries(
                        title = withEventTitle.trim(),
                        categoryId = Prefs.defaultCategoryId(c),
                        allDay = true, startDay = day, endDay = day, uid = uid
                    )
                )
                eventUid = uid
            }
            stampDao.insert(Stamp(emoji = emoji, day = day, eventUid = eventUid, assetId = assetId))
            if (assetId.isNotBlank()) Prefs.pushRecentStamp(c, assetId)
            afterChange()
            onDone()
        }

    fun deleteStamp(id: Long) = viewModelScope.launch {
        stampDao.byId(id)?.let {
            stampDao.update(it.copy(deleted = true, dirty = true, updatedAt = now()))
        }
        afterChange()
    }

    /** 印章绑定的日程系列（未绑定或已删除返回 null） */
    fun stampSeries(s: Stamp): EventSeries? =
        if (s.eventUid.isBlank()) null else seriesAll.value.find { it.uid == s.eventUid }

    // ================= 小鹿 AI =================

    val chat = mutableStateListOf<ChatMsg>()
    var aiBusy by mutableStateOf(false)

    private fun agendaContext(): String {
        // S9：用户可关闭「允许小鹿读取日程」
        if (!Prefs.aiReadAgenda(getApplication())) return tr("（用户未授权读取日程数据）") + "\n"
        val today = Fmt.today()
        val occs = RecurrenceEngine.expand(seriesAll.value, exceptionsAll.value, today, today + 14)
            .sortedWith(compareBy({ it.day }, { if (it.allDay) -1 else it.startMin }))
            .take(40)
        val sb = StringBuilder(tr("用户未来14天日程：") + "\n")
        if (occs.isEmpty()) sb.append(tr("（暂无日程）") + "\n")
        occs.forEach { o ->
            val t = if (o.allDay) tr("全天") else "${Fmt.hm(o.startMin)}-${Fmt.hm(o.endMin)}"
            sb.append("- ${Fmt.iso(o.day)} ${Fmt.dateCn(o.day)} $t ${o.title}\n")
        }
        val open = tasks.value.filter { !it.done }.take(30)
        sb.append(tr("用户未完成任务：") + "\n")
        if (open.isEmpty()) sb.append(tr("（无）") + "\n")
        open.forEach {
            sb.append("- ${it.title}${if (it.dueDay >= 0) tr("（截止{0}）", Fmt.dateCn(it.dueDay)) else ""}\n")
        }
        return sb.toString()
    }

    fun sendChat(display: String, payload: String = display) {
        if (aiBusy || display.isBlank()) return
        chat += ChatMsg(ROLE_USER, display)
        aiBusy = true
        viewModelScope.launch {
            try {
                val sys = AiActions.chatSystemPrompt(agendaContext())
                val history = ArrayList<Pair<String, String>>()
                chat.filter { !it.error && it.role != ROLE_ACTION }.takeLast(12).forEach {
                    history += (if (it.role == ROLE_USER) "user" else "assistant") to it.text
                }
                if (history.isNotEmpty() && payload != display) {
                    history[history.size - 1] = "user" to payload
                }
                val raw = AiClient.chat(getApplication(), sys, history)
                val usedTier = AiClient.lastTier          // 服务端回传的实际生效档位
                val (text, actions) = AiActions.split(raw)
                if (text.isNotBlank()) chat += ChatMsg(ROLE_AI, text, tier = usedTier)
                if (actions.isNotEmpty()) execActions(actions).forEach { chat += ChatMsg(ROLE_ACTION, it) }
                if (text.isBlank() && actions.isEmpty()) chat += ChatMsg(ROLE_AI, tr("小鹿没想好怎么回答，换个说法试试？"))
            } catch (e: Exception) {
                chat += ChatMsg(ROLE_AI, tr("小鹿出错了：{0}", e.message ?: tr("网络异常")), error = true)
            } finally {
                aiBusy = false
            }
        }
    }

    fun sendWeeklySummary() {
        val today = LocalDate.now()
        val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
        val end = start + 6
        val occs = RecurrenceEngine.expand(seriesAll.value, exceptionsAll.value, start, end)
            .sortedWith(compareBy({ it.day }, { it.startMin }))
        val doneT = tasks.value.filter { it.done }
        val openT = tasks.value.filter { !it.done }
        val ds = diaries.value.filter { it.day in start..end }
        val sb = StringBuilder(tr("请根据以下本周（{0}）真实数据，生成简洁的本周总结与下周建议：", "${Fmt.dateCn(start)} - ${Fmt.dateCn(end)}") + "\n\n" + tr("本周日程：") + "\n")
        if (occs.isEmpty()) sb.append(tr("（无）") + "\n")
        occs.forEach { o -> sb.append("- ${Fmt.dateCn(o.day)} ${if (o.allDay) tr("全天") else Fmt.hm(o.startMin)} ${o.title}\n") }
        sb.append("\n" + tr("已完成任务 {0} 项", doneT.size))
        if (doneT.isNotEmpty()) sb.append("：" + doneT.take(15).joinToString("、") { it.title })
        sb.append("\n" + tr("未完成任务 {0} 项", openT.size))
        if (openT.isNotEmpty()) sb.append("：" + openT.take(15).joinToString("、") { it.title })
        if (ds.isNotEmpty()) {
            sb.append("\n" + tr("本周日记 {0} 篇，心情：", ds.size))
            sb.append(ds.sortedBy { it.day }.joinToString(" ") { MOOD_EMOJIS[it.mood.coerceIn(0, 4)] })
        }
        sb.append("\n\n" + tr("要求：先 2-3 句总结，再给 2-3 条下周建议，轻松一点，不要输出 json。"))
        sendChat(tr("帮我总结一下本周 📋"), sb.toString())
    }

    fun clearChat() = chat.clear()

    suspend fun execActions(actions: List<AiAction>): List<String> {
        val c = getApplication<Application>()
        val out = ArrayList<String>()
        for (a in actions) {
            when (a.type) {
                "create_event" -> {
                    val day = if (a.day >= 0) a.day else Fmt.today()
                    val allDay = a.allDay || a.startMin < 0
                    val sm = if (a.startMin >= 0) a.startMin else 9 * 60
                    val em = if (a.endMin > sm) a.endMin else minOf(sm + 60, 24 * 60 - 1)
                    val id = eventDao.insertSeries(
                        EventSeries(
                            title = a.title.ifBlank { tr("未命名日程") },
                            categoryId = Prefs.defaultCategoryId(c),
                            allDay = allDay, startDay = day, endDay = maxOf(a.endDay, day),
                            startMin = sm, endMin = em, location = a.location, memo = a.memo
                        )
                    )
                    // P2-D3/D4/D5（§三十八①）：提醒必须"说出来"——
                    // 用户要求的提醒时刻优先；算出的时刻已过去要明说并自动兜底，不再静默丢弃。
                    var remNote = ""
                    if (!allDay) {
                        val now = System.currentTimeMillis()
                        val dayStart = java.time.LocalDate.ofEpochDay(day)
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        // 目标提醒时刻（分钟）：AI 指定时刻 > AI 指定提前量 > 用户默认设置
                        var remMin = when {
                            a.remindAtMin >= 0 -> a.remindAtMin
                            a.remindMinBefore >= 0 -> sm - a.remindMinBefore
                            else -> {
                                val d0 = Prefs.defTimedReminderMin(c)
                                if (d0 >= 0) sm - d0 else -1
                            }
                        }
                        if (remMin >= 0) {
                            if (dayStart + remMin * 60_000L <= now && dayStart + sm * 60_000L > now) {
                                // D5：提醒时刻已过但日程还没开始 → 退化为开始时提醒
                                remMin = sm
                                remNote = tr("（原提醒时刻已过，改为 {0} 开始时提醒）", Fmt.hm(sm))
                            }
                            if (dayStart + remMin * 60_000L > now) {
                                eventDao.insertReminder(Reminder(seriesId = id, minutesBefore = sm - remMin))
                                if (remNote.isEmpty()) remNote = tr(" · {0} 提醒你", Fmt.hm(remMin))
                            } else {
                                // D4：整个时间都过去了 → 明说，而不是让用户以为会响
                                remNote = tr("（这个时间已经过了，没有设提醒 —— 要改到明天吗？）")
                            }
                        }
                    } else {
                        val days = Prefs.defAllDayReminderDays(c)
                        if (days >= 0) eventDao.insertReminder(
                            Reminder(seriesId = id, daysBefore = days, timeOfDayMin = Prefs.defAllDayReminderTime(c))
                        )
                    }
                    out += tr("✅ 已添加日程：{0}", "${Fmt.dateCn(day)} ${if (allDay) tr("全天") else Fmt.hm(sm)} ${a.title}") + remNote
                }
                "create_task" -> {
                    taskDao.insert(Task(title = a.title.ifBlank { tr("未命名任务") }, dueDay = a.day,
                        sortOrder = taskDao.maxSortOrder() + 1))
                    out += tr("✅ 已添加任务：{0}", a.title + if (a.day >= 0) "（${Fmt.dateCn(a.day)}）" else "")
                }
                "create_note" -> {
                    noteDao.insert(Note(title = a.title, content = a.content))
                    out += tr("✅ 已添加笔记：{0}", a.title.ifBlank { a.content.take(10) })
                }
            }
        }
        if (actions.isNotEmpty()) afterChange()
        return out
    }

    suspend fun aiParseActions(input: String): List<AiAction> {
        val raw = AiClient.chat(getApplication(), AiActions.quickParsePrompt(), listOf("user" to input), temperature = 0.2)
        return AiActions.split(raw).second
    }

    suspend fun aiSubtasks(taskTitle: String): List<String> {
        val raw = AiClient.chat(getApplication(), AiActions.subtasksPrompt(), listOf("user" to taskTitle), temperature = 0.4)
        return AiActions.parseSubtasks(raw)
    }

    suspend fun aiPolish(content: String): String =
        AiClient.chat(getApplication(), AiActions.polishPrompt(), listOf("user" to content), temperature = 0.5)
}

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
import com.looka.app.data.NoteList
import com.looka.app.data.NOTE_LIST_DEFAULT
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
                   val tier: String = "standard",
                   /** L1（§62）：动作卡片可点开的目标（event/task/note + 本地 id），"" = 不可点 */
                   val targetKind: String = "", val targetId: Long = -1L)

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
    private val noteListDao = db.noteListDao()
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
    // §86 C1：笔记清单（V014 [B] —— List 是持久对象）
    val noteLists = noteListDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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

    // N1（§71）→ §75 C：贴纸 Composer 面板（三面模型中唯一留在月历上的面）
    var createPanel by mutableStateOf(false)
    // 表情连续盖章模式：非空 = 已选中贴纸，点月历日期直接贴
    var stampSel by mutableStateOf("")
    // §75 C4：Composer 记住上次的面 —— ＋ 直接打开上次用的（0日程全屏/1任务全屏/2贴纸面板）
    var composerMode by mutableIntStateOf(0)
    // 编辑器初始模式（面板/＋转入全屏时指定，消费后归零）
    var editorInitMode by mutableIntStateOf(0)
    // §77 N9：笔记页当前子 tab（0 笔记 / 1 日记）。提到 VM 是因为中央 ＋ 要按它分流 ——
    // 留在 NotesDiaryScreen 里的话 Nav 拿不到，＋ 就只能一律建日程（那正是 N9 那个 bug）。
    var notesSeg by mutableIntStateOf(0)
    // §86 C2：笔记页当前清单筛选（"" = 全部）。放 VM 是因为「新建笔记」要继承它作为默认归属
    var noteListSel by mutableStateOf("")
    // §72 §8：拖动印章时锁住日历滚动（Pointer ownership）
    var stampDragging by mutableStateOf(false)
    // §72 §11：创建面板高度 → 日历 viewport bottomInset（不压缩格子）
    var panelInset by mutableStateOf(androidx.compose.ui.unit.Dp(0f))
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
        pendingStampBind = -1L      // §5.2：新草稿默认不回绑印章，由印章入口显式设置
        pendingStampAsset = ""
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
        // §76 F2：这条日程若绑着贴纸，编辑页标题左要出缩略图 —— 无论从哪个入口进来都一致
        pendingStampBind = -1L
        pendingStampAsset = stamps.value
            .firstOrNull { it.eventUid == s.uid && it.assetId.isNotBlank() }?.assetId ?: ""
        return true
    }

    // ================= 日程保存 / 删除 =================

    /** §72 §5.2：从印章「登记日程」进来时待回绑的印章 id（保存后 Decorative → Event-bound） */
    var pendingStampBind by mutableLongStateOf(-1L)
    // §74 P0-4：登记日程时把贴纸带进表单（标题左侧缩略图）—— Context continuity，
    // 用户的心智是「给这枚贴纸登记日程」，不是「新建一个普通日程」
    var pendingStampAsset by mutableStateOf("")

    fun saveCreate(d: EventDraft, onDone: () -> Unit) = viewModelScope.launch {
        val uid = newUid()
        val id = eventDao.insertSeries(d.toSeries(uidOverride = uid))
        d.reminders.forEach { eventDao.insertReminder(it.copy(id = 0, seriesId = id)) }
        if (pendingStampBind >= 0) {
            stampDao.bindEvent(pendingStampBind, uid, now())
            pendingStampBind = -1L
            pendingStampAsset = ""
            // §74 P0-8：「贴纸→登记日程→保存」任务完成，Composer session 收束回正常日历
            createPanel = false
            stampSel = ""
        }
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
        pendingStampAsset = ""   // §76 F2：编辑态缩略图用完即清，别残留到下一次新建
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

    /** 模板字段快照（不含日期 —— 母档冻结：Template=这类事是什么，日期由 Context 给） */
    private fun templatePayload(d: EventDraft): String {
        val catUid = categories.value.find { it.id == d.categoryId }?.uid ?: "cat-default-1"
        val rs = JSONArray()
        d.reminders.forEach {
            rs.put(JSONObject().put("m", it.minutesBefore).put("d", it.daysBefore)
                .put("t", it.timeOfDayMin).put("on", it.enabled))
        }
        return JSONObject()
            .put("title", d.title.trim())
            .put("categoryUid", catUid)
            .put("allDay", d.allDay)
            .put("startMin", d.startMin).put("endMin", d.endMin)
            .put("days", maxOf(d.endDay - d.startDay, 0))
            .put("location", d.location.trim()).put("memo", d.memo.trim())
            .put("freq", d.freq).put("interval", d.interval).put("weekdays", d.weekdays)
            .put("monthlyByWeekday", d.monthlyByWeekday)
            .put("reminders", rs)
            .toString()
    }

    /** 把当前草稿存为模板（不含日期） */
    fun saveTemplate(d: EventDraft) = viewModelScope.launch {
        templateDao.insert(Template(title = d.title.trim().ifBlank { tr("未命名模板") }, payload = templatePayload(d)))
    }

    /** CAL-062（§70）：独立模板编辑页的保存 —— id>0 更新，否则新建 */
    fun upsertTemplate(id: Long, d: EventDraft, onDone: () -> Unit = {}) = viewModelScope.launch {
        val title = d.title.trim().ifBlank { tr("未命名模板") }
        if (id > 0) templateDao.update(id, title, templatePayload(d))
        else templateDao.insert(Template(title = title, payload = templatePayload(d)))
        onDone()
    }

    /** CAL-062（§70）：模板 → 编辑草稿（日期字段只是占位，编辑页不展示） */
    fun templateDraft(t: Template?): EventDraft {
        val c = getApplication<Application>()
        val d = EventDraft()
        d.startDay = Fmt.today(); d.endDay = d.startDay
        d.startMin = 9 * 60; d.endMin = 10 * 60
        d.categoryId = Prefs.defaultCategoryId(c)
        if (t != null) applyTemplate(d, t)
        d.remindersTouched = true
        return d
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

    // §99 I6：moveCategory（上移/下移）已删 —— 排序改长按拖拽，走 reorderCategories

    // ================= 任务 / 笔记 / 日记 / 印章 =================

    fun addTask(title: String, due: Long = -1L, memo: String = "", listUid: String = "list-default",
                starred: Boolean = false, done: Boolean = false) =
        viewModelScope.launch {
            if (title.isNotBlank()) {
                val order = taskDao.maxSortOrder() + 1
                // §75 T1：创建页左上 ○ / 右上 ☆（图47）—— 建时即可标完成/星标
                taskDao.insert(Task(title = title.trim(), dueDay = due, memo = memo,
                    listUid = listUid, sortOrder = order,
                    starred = starred, done = done, doneAt = if (done) now() else -1L))
                afterChange()
            }
        }

    /** 拖拽重排（三批）：把清单内可见顺序整体写回 sortOrder */
    fun reorderCategories(orderedUids: List<String>) = viewModelScope.launch {
        val t = now()
        orderedUids.forEachIndexed { i, uid ->
            categoryDao.byUid(uid)?.let { categoryDao.update(it.copy(sortOrder = i, dirty = true, updatedAt = t)) }
        }
        afterChange()
    }

    /** §99 I6：手动顺序统一写法 —— 序号 ×10 留空隙，插队时不用整表重排 */
    fun reorderNoteLists(orderedUids: List<String>) = viewModelScope.launch {
        val t = now()
        orderedUids.forEachIndexed { i, uid -> noteListDao.setSortOrder(uid, (i + 1) * 10, t) }
        afterChange()
    }

    fun reorderTaskLists(orderedUids: List<String>) = viewModelScope.launch {
        val t = now()
        orderedUids.forEachIndexed { i, uid -> taskListDao.setSortOrder(uid, (i + 1) * 10, t) }
        afterChange()
    }

    fun reorderNotes(orderedUids: List<String>) = viewModelScope.launch {
        val t = now()
        orderedUids.forEachIndexed { i, uid -> noteDao.setSortOrder(uid, (i + 1).toLong() * 10, t) }
        afterChange()
    }

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

    // ===== §99 I7：**全局撤销** =====
    // 原来只有任务有撤销、而且撤销条只挂在清单详情页一处（审计 BUG-TL-009）。
    // 左滑删除铺开之后这条必须先补：划一下东西没了却找不到撤销入口，比不给手势还糟。
    // 这里做成「删什么都能撤」的通用账本：谁删谁提供 restore 闭包。
    class UndoItem(val label: String, val restore: suspend () -> Unit)

    var undo by androidx.compose.runtime.mutableStateOf<UndoItem?>(null)
        private set
    private var undoJob: kotlinx.coroutines.Job? = null

    /** 软删 + 挂 5 秒撤销。delete/restore 都由调用方给，VM 只负责账本与计时 */
    private fun softDelete(label: String, delete: suspend () -> Unit, restore: suspend () -> Unit) {
        viewModelScope.launch {
            delete()
            afterChange()
            undo = UndoItem(label, restore)
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                kotlinx.coroutines.delay(5000)
                undo = null
            }
        }
    }

    fun doUndo() = viewModelScope.launch {
        val u = undo ?: return@launch
        undo = null; undoJob?.cancel()
        u.restore()
        afterChange()
    }

    fun deleteTask(t: Task) = softDelete(
        t.title.take(12),
        delete = { taskDao.update(t.copy(deleted = true, dirty = true, updatedAt = now())) },
        // §97 TL-002：用 upsert 不用 update —— 静默同步 2.5 秒就会把墓碑发上去，
        // 服务端确认后本地行已被物理删除；此时 @Update 影响 0 行，撤销静默失效。
        restore = { taskDao.upsert(t.copy(deleted = false, dirty = true, updatedAt = now())) }
    )

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
    fun deleteTaskList(l: TaskList) {
        if (!l.deletable) return
        var moved: List<String> = emptyList()
        softDelete(
            l.name.take(12),
            delete = {
                moved = taskDao.listAll().filter { it.listUid == l.uid }.map { it.uid }
                taskDao.reassignList(l.uid, "list-default", now())
                taskListDao.update(l.copy(deleted = true, dirty = true, updatedAt = now()))
            },
            restore = {
                taskListDao.upsert(l.copy(deleted = false, dirty = true, updatedAt = now()))
                moved.forEach { u ->
                    taskDao.byUid(u)?.let { taskDao.update(it.copy(listUid = l.uid, dirty = true, updatedAt = now())) }
                }
            }
        )
    }

    suspend fun note(id: Long) = noteDao.byId(id)

    fun saveNote(
        id: Long, title: String, content: String,
        listUid: String = NOTE_LIST_DEFAULT, onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        if (title.isBlank() && content.isBlank()) {
            onDone(); return@launch
        }
        ensureNoteListDefault()
        if (id < 0) noteDao.insert(Note(title = title.trim(), content = content, listUid = listUid))
        else noteDao.byId(id)?.let {
            noteDao.update(it.copy(
                title = title.trim(), content = content, listUid = listUid,
                updatedAt = now(), dirty = true
            ))
        }
        afterChange()
        onDone()
    }

    // ── §86 C1/C3：笔记清单 ──────────────────────────────────────
    /** 惰性建默认清单：不可删，uid 固定，跨端天然合并（同 task 的 list-default 套路） */
    suspend fun ensureNoteListDefault() {
        if (noteListDao.byUid(NOTE_LIST_DEFAULT) == null) {
            noteListDao.insert(
                NoteList(
                    name = tr("我的笔记"), colorHex = "#5C6670",
                    sortOrder = 0, deletable = false, uid = NOTE_LIST_DEFAULT
                )
            )
        }
    }

    /**
     * V014「Create List commit」：先落持久 List 再回调 —— 回调里带回 uid 供发起方自动选中；
     * 失败（重名/空名）返回 null，调用方据此**不关闭 Dialog**。
     */
    fun addNoteList(name: String, colorHex: String, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val n = name.trim()
        if (n.isBlank()) { onDone(null); return@launch }
        if (noteLists.value.any { it.name == n }) { onDone(null); return@launch }
        val order = (noteLists.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val l = NoteList(name = n, colorHex = colorHex, sortOrder = order)
        noteListDao.insert(l)
        afterChange()
        onDone(l.uid)
    }

    fun renameNoteList(l: NoteList, name: String, colorHex: String) = viewModelScope.launch {
        noteListDao.update(l.copy(name = name.trim(), colorHex = colorHex, dirty = true, updatedAt = now()))
        afterChange()
    }

    /** 删清单不删笔记：里面的笔记迁回默认清单（与 deleteTaskList 同语义） */
    fun deleteNoteList(l: NoteList) {
        if (!l.deletable) return
        // 迁移过去的笔记原来归谁，撤销时要还回去 —— 只记 uid，不整份快照
        var moved: List<String> = emptyList()
        softDelete(
            l.name.take(12),
            delete = {
                ensureNoteListDefault()
                moved = noteDao.listAll().filter { it.listUid == l.uid }.map { it.uid }
                noteDao.reassignList(l.uid, NOTE_LIST_DEFAULT, now())
                noteListDao.update(l.copy(deleted = true, dirty = true, updatedAt = now()))
            },
            restore = {
                noteListDao.upsert(l.copy(deleted = false, dirty = true, updatedAt = now()))
                moved.forEach { u ->
                    noteDao.byUid(u)?.let { noteDao.update(it.copy(listUid = l.uid, dirty = true, updatedAt = now())) }
                }
            }
        )
    }

    fun deleteNote(id: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        val n = noteDao.byId(id) ?: return@launch
        deleteNoteEntity(n)
        onDone()
    }

    /** §99 I7：左滑删除走这条 —— 与详情页删除共用同一撤销账本 */
    fun deleteNoteEntity(n: Note) = softDelete(
        n.title.ifBlank { n.content.take(12) }.take(12),
        delete = { noteDao.update(n.copy(deleted = true, dirty = true, updatedAt = now())) },
        restore = { noteDao.upsert(n.copy(deleted = false, dirty = true, updatedAt = now())) }
    )

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
        val d = diaryDao.byDay(day) ?: return@launch
        deleteDiaryEntity(d)
        onDone()
    }

    /** §99 I7：左滑删除走这条 —— 与编辑页删除共用同一撤销账本 */
    fun deleteDiaryEntity(d: Diary) = softDelete(
        Fmt.dateFull(d.day),
        delete = { diaryDao.update(d.copy(deleted = true, dirty = true, updatedAt = now())) },
        restore = { diaryDao.upsert(d.copy(deleted = false, dirty = true, updatedAt = now())) }
    )

    /** 贴印章（图片资产优先，emoji 兜底）；withEventTitle 非空时同时创建全天日程并绑定（规格 CAL-051） */
    fun addStamp(
        emoji: String, day: Long, withEventTitle: String = "", assetId: String = "",
        px: Float = 0.5f, py: Float = 0.62f, onDone: (Long) -> Unit = {}
    ) =
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
            // Sticker Canvas v1：新贴的印章直接进入摆放态（格子中偏下），之后可长按拖动
            val newId = stampDao.insert(Stamp(emoji = emoji, day = day, eventUid = eventUid,
                assetId = assetId, posX = px, posY = py))
            if (assetId.isNotBlank()) Prefs.pushRecentStamp(c, assetId)
            afterChange()
            onDone(newId)
        }

    /** Sticker Canvas v1：拖动落点写回。位置是实例状态（Place → Reposition 可反复）。 */
    fun moveStamp(id: Long, newDay: Long, px: Float, py: Float) = viewModelScope.launch {
        stampDao.byId(id)?.let { st ->
            stampDao.update(st.copy(
                day = newDay,
                posX = px.coerceIn(0f, 1f), posY = py.coerceIn(0f, 1f),
                dirty = true, updatedAt = now()
            ))
            // §74 P0-7：复合对象原子移动 —— 绑定日程随印章同 delta 平移。
            // 旧版只挪印章：日历上原日期留下事件残影（详情页显示新日期是它信了 st.day 参数的假象，
            // 后台其实没改）。BEAR 模型：Sticker 始终是 Sticker，Event 是附加语义，两者日期必须原子同步。
            val delta = newDay - st.day
            if (delta != 0L && st.eventUid.isNotBlank()) {
                eventDao.seriesByUid(st.eventUid)?.takeIf { !it.deleted }?.let { se ->
                    eventDao.updateSeries(se.copy(
                        startDay = se.startDay + delta, endDay = se.endDay + delta,
                        dirty = true, updatedAt = now()
                    ))
                    // 同 saveEditAll 规则：整体平移后旧的单次修改不再有意义
                    eventDao.deleteExceptionsOf(se.id)
                }
            }
            afterChange()
        }
    }

    fun deleteStamp(id: Long) = viewModelScope.launch {
        stampDao.byId(id)?.let {
            stampDao.update(it.copy(deleted = true, dirty = true, updatedAt = now()))
        }
        afterChange()
    }

    /**
     * §76 F3（图52「予定の削除」）：绑定后贴纸与日程是一个复合对象，删就一起删。
     * 旧版只删贴纸 —— 日程留在原地变成孤儿（用户实测「删了贴纸，嗯了又回复金色」）。
     */
    fun deleteStampComposite(id: Long) = viewModelScope.launch {
        val st = stampDao.byId(id) ?: return@launch
        if (st.eventUid.isNotBlank()) {
            eventDao.seriesByUid(st.eventUid)?.takeIf { !it.deleted }?.let { se ->
                eventDao.updateSeries(se.copy(deleted = true, dirty = true, updatedAt = now()))
                eventDao.deleteRemindersOf(se.id)
                eventDao.deleteExceptionsOf(se.id)
            }
        }
        stampDao.update(st.copy(deleted = true, dirty = true, updatedAt = now()))
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
        // A1-2：窗口扩到过去 7 天（「改昨天的」也要能定位），并给每条标注 [e/t/n + id]
        // —— AI 改/删只认这些 id，绝不按标题猜（§48 A1-2）
        val occs = RecurrenceEngine.expand(seriesAll.value, exceptionsAll.value, today - 7, today + 14)
            .sortedWith(compareBy({ it.day }, { if (it.allDay) -1 else it.startMin }))
            .take(50)
        val sb = StringBuilder(tr("用户日程（近7天与未来14天，[e数字] 是它的 id）：") + "\n")
        if (occs.isEmpty()) sb.append(tr("（暂无日程）") + "\n")
        occs.forEach { o ->
            val t = if (o.allDay) tr("全天") else "${Fmt.hm(o.startMin)}-${Fmt.hm(o.endMin)}"
            val rec = if (o.recurring) tr("（重复）") else ""
            sb.append("- [e${o.seriesId}] ${Fmt.iso(o.day)} ${Fmt.dateCn(o.day)} $t ${o.title}$rec\n")
        }
        val open = tasks.value.filter { !it.done }.take(30)
        sb.append(tr("用户未完成任务（[t数字] 是它的 id）：") + "\n")
        if (open.isEmpty()) sb.append(tr("（无）") + "\n")
        open.forEach {
            sb.append("- [t${it.id}] ${it.title}${if (it.dueDay >= 0) tr("（截止{0}）", Fmt.dateCn(it.dueDay)) else ""}\n")
        }
        val ns = notes.value.take(10)
        if (ns.isNotEmpty()) {
            sb.append(tr("用户最近笔记（[n数字] 是它的 id）：") + "\n")
            ns.forEach { sb.append("- [n${it.id}] ${it.title.ifBlank { it.content.take(12) }}\n") }
        }
        // A4：统计类问题（「这个月写了几篇日记」）也要能答真数据
        val monthStart = java.time.LocalDate.now().withDayOfMonth(1).toEpochDay()
        val diaryCnt = diaries.value.count { it.day >= monthStart }
        sb.append(tr("统计：本月日记 {0} 篇；未完成任务共 {1} 项。", diaryCnt, tasks.value.count { !it.done }) + "\n")
        // K2①（§54）：把分类名单给 AI，建日程时可以选（否则永远落进灰色的「未分类」）
        val cats = categories.value.filter { !it.deleted }.take(10)
        if (cats.isNotEmpty()) sb.append(tr("用户的日程分类：") + cats.joinToString("、") { it.name } + "\n")
        // D1（§52）：小鹿记事本 —— 用户主动说过的长期偏好
        val facts = Prefs.deerFacts(getApplication())
        if (facts.isNotEmpty()) sb.append(tr("你记住过的用户偏好：") + facts.joinToString("；") + "\n")
        return sb.toString()
    }

    fun sendChat(display: String, payload: String = display) {
        if (aiBusy || display.isBlank()) return
        chat += ChatMsg(ROLE_USER, display)
        aiBusy = true
        viewModelScope.launch {
            try {
                val sys = AiActions.chatSystemPrompt(agendaContext(), Prefs.nickname(getApplication()))
                val history = ArrayList<Pair<String, String>>()
                chat.filter { !it.error && it.role != ROLE_ACTION }.takeLast(12).forEach {
                    history += (if (it.role == ROLE_USER) "user" else "assistant") to it.text
                }
                if (history.isNotEmpty() && payload != display) {
                    history[history.size - 1] = "user" to payload
                }
                // T1（§53）真流式 + E2（§57）：**首个可见增量到达才插消息** ——
                // 提前挂空占位会渲染出一个空白小气泡（用户截图实锤）。
                // T2：渲染在 ``` 处截断，动作 JSON 逐字冒出也绝不给用户看（线上踩过的坑）
                var idx = -1
                val sb = StringBuilder()
                val raw = AiClient.chat(getApplication(), sys, history) { delta ->
                    sb.append(delta)
                    val visible = sb.toString().substringBefore("```").trimEnd('`', '{')
                        .replace(Regex("""[（(]?\[[etn]\d+\][）)]?"""), "")
                    if (visible.isNotBlank()) {
                        if (idx < 0) { idx = chat.size; chat += ChatMsg(ROLE_AI, visible) }
                        else chat[idx] = chat[idx].copy(text = visible)
                    }
                }
                val (text, actions) = AiActions.split(raw)
                when {
                    text.isNotBlank() && idx >= 0 -> chat[idx] = chat[idx].copy(text = text)
                    text.isNotBlank() -> chat += ChatMsg(ROLE_AI, text)   // 非流式路径（自带 Key）
                    idx >= 0 -> chat.removeAt(idx)   // 纯动作回复：正文占位撤掉
                }
                if (actions.isNotEmpty()) {
                    // A3/A1-4（§48）：删除一律先确认；一次 ≥3 条也先确认（批量误建就是这么来的）
                    if (actions.any { it.isDelete } || actions.size >= 3) {
                        pendingAiActions.clear(); pendingAiActions.addAll(actions)
                        pendingChecked.clear(); repeat(actions.size) { pendingChecked.add(true) }
                        chat += ChatMsg(ROLE_AI, tr("共 {0} 件事，你勾选确认后我再动手 👇", actions.size))
                    } else {
                        execActions(actions).forEachIndexed { i2, msg ->
                            val tg = lastActionTargets.getOrNull(i2)
                            chat += ChatMsg(ROLE_ACTION, msg,
                                targetKind = tg?.first ?: "", targetId = tg?.second ?: -1L)
                        }
                    }
                }
                if (text.isBlank() && actions.isEmpty()) chat += ChatMsg(ROLE_AI, tr("小鹿没想好怎么回答，换个说法试试？"))
            } catch (e: Exception) {
                // 流式中断留下的空占位撤掉，别让用户看到空气泡
                if (chat.isNotEmpty() && chat.last().role == ROLE_AI && chat.last().text.isBlank())
                    chat.removeAt(chat.size - 1)
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

    // ── A3：批量确认 + 一键撤销（§48）─────────────────────────────
    /** AI 一次给出的待确认动作（删除必确认；≥3 条必确认） */
    val pendingAiActions = mutableStateListOf<AiAction>()
    val pendingChecked = mutableStateListOf<Boolean>()

    /** 上一批的反向操作账本：创建→软删、修改→回写旧值、删除→复活 */
    private sealed interface UndoRec {
        data class Ev(val snapshot: EventSeries, val created: Boolean = false) : UndoRec
        data class Tk(val snapshot: Task, val created: Boolean = false) : UndoRec
        data class Nt(val snapshot: Note, val created: Boolean = false) : UndoRec
    }
    private val lastUndo = ArrayList<UndoRec>()
    var canUndo by mutableStateOf(false)
        private set

    fun confirmPending() {
        val picked = pendingAiActions.filterIndexed { i, _ -> pendingChecked.getOrElse(i) { true } }
        pendingAiActions.clear(); pendingChecked.clear()
        if (picked.isEmpty()) { chat += ChatMsg(ROLE_AI, tr("好，都不动 🦌")); return }
        viewModelScope.launch {
            execActions(picked).forEachIndexed { i2, msg ->
                val tg = lastActionTargets.getOrNull(i2)
                chat += ChatMsg(ROLE_ACTION, msg,
                    targetKind = tg?.first ?: "", targetId = tg?.second ?: -1L)
            }
        }
    }

    fun cancelPending() {
        pendingAiActions.clear(); pendingChecked.clear()
        chat += ChatMsg(ROLE_AI, tr("好，都不动 🦌"))
    }

    /** 撤销上一批 AI 修改（逆序回放账本） */
    fun undoLastBatch() = viewModelScope.launch {
        val n = now()
        for (r in lastUndo.reversed()) when (r) {
            is UndoRec.Ev ->
                if (r.created) {
                    eventDao.updateSeries(r.snapshot.copy(deleted = true, dirty = true, updatedAt = n))
                    eventDao.deleteRemindersOf(r.snapshot.id)
                } else eventDao.updateSeries(r.snapshot.copy(dirty = true, updatedAt = n))
            is UndoRec.Tk ->
                if (r.created) taskDao.update(r.snapshot.copy(deleted = true, dirty = true, updatedAt = n))
                else taskDao.update(r.snapshot.copy(dirty = true, updatedAt = n))
            is UndoRec.Nt ->
                if (r.created) noteDao.update(r.snapshot.copy(deleted = true, dirty = true, updatedAt = n))
                else noteDao.update(r.snapshot.copy(dirty = true, updatedAt = n))
        }
        val cnt = lastUndo.size
        lastUndo.clear(); canUndo = false
        afterChange()
        chat += ChatMsg(ROLE_ACTION, tr("↩️ 已撤销刚才的 {0} 处修改", cnt))
    }

    /** L1（§62）：每条动作反馈对应的可打开目标（kind,id），与 execActions 返回值按索引对齐 */
    val lastActionTargets = ArrayList<Pair<String, Long>>()

    suspend fun execActions(actions: List<AiAction>): List<String> {
        val c = getApplication<Application>()
        val out = ArrayList<String>()
        lastActionTargets.clear()
        fun target(kind: String, id: Long) {
            // 补齐到与 out 对齐（一个动作可能输出多行时兜底）
            while (lastActionTargets.size < out.size - 1) lastActionTargets.add("" to -1L)
            lastActionTargets.add(kind to id)
        }
        // 新一批动作开启新账本（撤销以"批"为单位）
        lastUndo.clear(); canUndo = false
        for (a in actions) {
            when (a.type) {
                "create_event" -> {
                    val day = if (a.day >= 0) a.day else Fmt.today()
                    val allDay = a.allDay || a.startMin < 0
                    val sm = if (a.startMin >= 0) a.startMin else 9 * 60
                    val em = if (a.endMin > sm) a.endMin else minOf(sm + 60, 24 * 60 - 1)
                    // K2①：AI 给了分类名就按名匹配（找不到才回落默认分类）
                    val catId = a.category.takeIf { it.isNotBlank() }
                        ?.let { name -> categories.value.find { !it.deleted && it.name == name }?.id }
                        ?: Prefs.defaultCategoryId(c)
                    val series = EventSeries(
                        title = a.title.ifBlank { tr("未命名日程") },
                        categoryId = catId,
                        allDay = allDay, startDay = day, endDay = maxOf(a.endDay, day),
                        startMin = sm, endMin = em, location = a.location, memo = a.memo
                    )
                    val id = eventDao.insertSeries(series)
                    lastUndo += UndoRec.Ev(series.copy(id = id), created = true)
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
                    target("event", id)
                }
                "create_task" -> {
                    val t = Task(title = a.title.ifBlank { tr("未命名任务") }, dueDay = a.day,
                        sortOrder = taskDao.maxSortOrder() + 1)
                    val id = taskDao.insert(t)
                    lastUndo += UndoRec.Tk(t.copy(id = id), created = true)
                    out += tr("✅ 已添加任务：{0}", a.title + if (a.day >= 0) "（${Fmt.dateCn(a.day)}）" else "")
                    target("task", id)
                }
                "create_note" -> {
                    val nte = Note(title = a.title, content = a.content)
                    val id = noteDao.insert(nte)
                    lastUndo += UndoRec.Nt(nte.copy(id = id), created = true)
                    out += tr("✅ 已添加笔记：{0}", a.title.ifBlank { a.content.take(10) })
                    target("note", id)
                }

                // ── A1（§48）：改与删。只按上下文里的 id 定位，找不到就明说，绝不猜 ──
                "update_event" -> {
                    val s = eventDao.series(a.targetId)
                    if (s == null || s.deleted) {
                        out += tr("⚠️ 没找到要改的日程（#{0}）—— 告诉我是哪一条？", a.targetId)
                    } else {
                        lastUndo += UndoRec.Ev(s)
                        // 挪日期时保住原时长（跨天日程整体平移）
                        val nd = if (a.day >= 0) a.day else s.startDay
                        val ns = s.copy(
                            title = a.title.ifBlank { s.title },
                            startDay = nd,
                            endDay = nd + (s.endDay - s.startDay),
                            startMin = if (a.startMin >= 0) a.startMin else s.startMin,
                            endMin = when {
                                a.endMin >= 0 -> a.endMin
                                a.startMin >= 0 -> minOf(a.startMin + (s.endMin - s.startMin).coerceAtLeast(30), 24 * 60 - 1)
                                else -> s.endMin
                            },
                            allDay = if (a.startMin >= 0) false else s.allDay,
                            location = a.location.ifBlank { s.location },
                            memo = a.memo.ifBlank { s.memo },
                            dirty = true, updatedAt = now()
                        )
                        eventDao.updateSeries(ns)
                        val rec = if (s.freq != FREQ_NONE) tr("（重复日程，整个系列一起调整）") else ""
                        out += tr("✏️ 已修改：{0}",
                            "${Fmt.dateCn(ns.startDay)} ${if (ns.allDay) tr("全天") else Fmt.hm(ns.startMin)} ${ns.title}") + rec
                        target("event", ns.id)
                    }
                }
                "delete_event" -> {
                    val s = eventDao.series(a.targetId)
                    if (s == null || s.deleted) {
                        out += tr("⚠️ 没找到要删的日程（#{0}），可能已经删过了", a.targetId)
                    } else {
                        lastUndo += UndoRec.Ev(s)
                        eventDao.updateSeries(s.copy(deleted = true, dirty = true, updatedAt = now()))
                        val rec = if (s.freq != FREQ_NONE) tr("（重复日程，整个系列已删除）") else ""
                        out += tr("🗑️ 已删除日程：{0}", "${Fmt.dateCn(s.startDay)} ${s.title}") + rec
                    }
                }
                "update_task" -> {
                    val t = tasks.value.find { it.id == a.targetId && !it.deleted }
                    if (t == null) {
                        out += tr("⚠️ 没找到要改的任务（#{0}）—— 告诉我是哪一条？", a.targetId)
                    } else {
                        lastUndo += UndoRec.Tk(t)
                        val nt = t.copy(
                            title = a.title.ifBlank { t.title },
                            dueDay = if (a.day >= 0) a.day else t.dueDay,
                            done = if (a.done >= 0) a.done == 1 else t.done,
                            doneAt = if (a.done == 1) now() else if (a.done == 0) -1L else t.doneAt,
                            dirty = true, updatedAt = now()
                        )
                        taskDao.update(nt)
                        out += if (a.done == 1) tr("✅ 已完成任务：{0}", nt.title)
                               else tr("✏️ 已修改任务：{0}", nt.title + if (nt.dueDay >= 0) "（${Fmt.dateCn(nt.dueDay)}）" else "")
                    target("task", nt.id)
                    }
                }
                "delete_task" -> {
                    val t = tasks.value.find { it.id == a.targetId && !it.deleted }
                    if (t == null) {
                        out += tr("⚠️ 没找到要删的任务（#{0}），可能已经删过了", a.targetId)
                    } else {
                        lastUndo += UndoRec.Tk(t)
                        taskDao.update(t.copy(deleted = true, dirty = true, updatedAt = now()))
                        out += tr("🗑️ 已删除任务：{0}", t.title)
                    }
                }
                "update_note" -> {
                    val nte = noteDao.byId(a.targetId)
                    if (nte == null || nte.deleted) {
                        out += tr("⚠️ 没找到要改的笔记（#{0}）", a.targetId)
                    } else {
                        lastUndo += UndoRec.Nt(nte)
                        noteDao.update(nte.copy(
                            title = a.title.ifBlank { nte.title },
                            content = a.content.ifBlank { nte.content },
                            dirty = true, updatedAt = now()
                        ))
                        out += tr("✏️ 已修改笔记：{0}", a.title.ifBlank { nte.title })
                    target("note", a.targetId)
                    }
                }
                "remember" -> {
                    // D2：记进小鹿记事本（可在「订阅与小鹿 AI」页查看与删除）
                    Prefs.addDeerFact(c, a.fact)
                    out += tr("🦌 记住啦：{0}", a.fact)
                }
                "delete_note" -> {
                    val nte = noteDao.byId(a.targetId)
                    if (nte == null || nte.deleted) {
                        out += tr("⚠️ 没找到要删的笔记（#{0}），可能已经删过了", a.targetId)
                    } else {
                        lastUndo += UndoRec.Nt(nte)
                        noteDao.update(nte.copy(deleted = true, dirty = true, updatedAt = now()))
                        out += tr("🗑️ 已删除笔记：{0}", nte.title.ifBlank { nte.content.take(10) })
                    }
                }
            }
        }
        if (actions.isNotEmpty()) afterChange()
        canUndo = lastUndo.isNotEmpty()
        return out
    }

    // ── A1-6（§48）：重复日程查重清理 —— 修 AI 只会「建」时代留下的重复数据 ──
    /** 同标题 + 同起始日 + 同时间 + 同全天标记 = 重复组（组内按 id 升序，保最早那条） */
    fun duplicateEventGroups(): List<List<EventSeries>> =
        seriesAll.value.filter { !it.deleted }
            .groupBy { listOf(it.title.trim(), it.startDay, it.startMin, it.allDay, it.freq) }
            .values.filter { it.size > 1 }
            .map { g -> g.sortedBy { it.id } }

    /** 每组保留最早一条，其余软删（走同步，网页端一并消失）；返回清掉的条数 */
    fun cleanDuplicateEvents(onDone: (Int) -> Unit) = viewModelScope.launch {
        var n = 0
        val ts = now()
        duplicateEventGroups().forEach { g ->
            g.drop(1).forEach {
                eventDao.updateSeries(it.copy(deleted = true, dirty = true, updatedAt = ts))
                n++
            }
        }
        if (n > 0) afterChange()
        onDone(n)
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

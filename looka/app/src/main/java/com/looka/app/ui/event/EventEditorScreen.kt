@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.looka.app.ui.event

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ai.AiAction
import com.looka.app.data.FREQ_NONE
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Reminder
import com.looka.app.data.STAMP_EMOJIS
import com.looka.app.data.Template
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaDatePicker
import com.looka.app.ui.common.LookaTimePicker
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.RadioDialog
import com.looka.app.ui.common.SaveButton
import com.looka.app.ui.common.StickerPicker
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.LinkBlue
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import com.looka.app.vm.EventDraft
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch

/**
 * 中央 + 创建页 / 日程编辑页（规格 CAL-010/011/013）。
 * 新建时底部可切换 日程 / 任务 / 印章 / AI 四种模式（CAL-CRE-005 + AI 增强）。
 */
@Composable
fun EventEditorScreen(vm: LookaViewModel, nav: NavHostController) {
    val d = vm.draft
    if (d == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val isEdit = d.editingSeriesId >= 0
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var scopeDlg by remember { mutableStateOf(false) }

    // 任务模式状态
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var taskDue by rememberSaveable { mutableLongStateOf(-1L) }
    var taskMemo by rememberSaveable { mutableStateOf("") }
    var taskDueDlg by remember { mutableStateOf(false) }

    // 印章模式状态（图片资产优先，emoji 兜底）
    var stampEmoji by rememberSaveable { mutableStateOf("") }
    var stampAsset by rememberSaveable { mutableStateOf("") }
    var stampDay by rememberSaveable { mutableLongStateOf(d.startDay) }
    var stampDateDlg by remember { mutableStateOf(false) }
    var stampBind by rememberSaveable { mutableStateOf(false) }
    var stampTitle by rememberSaveable { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }       // F2：防连点重复创建
    var discardDlg by remember { mutableStateOf(false) }   // F3：放弃编辑二次确认

    fun reallyClose() {
        vm.draft = null
        vm.pendingStampBind = -1L   // §9：EVENT_CREATE 取消 → 印章保持 Decorative，不回绑
        nav.popBackStack()
    }

    /** F3：有内容未保存时先问一句，别让误触 X 吞掉半篇输入 */
    fun close() {
        val dirty = if (!isEdit) {
            d.title.isNotBlank() || d.memo.isNotBlank() || d.location.isNotBlank()
        } else {
            val o = d.originalSeries
            o != null && (d.title != o.title || d.memo != o.memo || d.location != o.location)
        }
        if (dirty && !saving) discardDlg = true else reallyClose()
    }

    fun doSaveEvent() {
        if (saving) return                                  // F2：保存是异步的，回调前再点会建重复日程
        saving = true
        val done: () -> Unit = { toast(ctx, tr("已保存")); reallyClose() }
        if (!isEdit) {
            vm.saveCreate(d, done)
        } else {
            val orig = d.originalSeries
            if (orig != null && orig.freq != FREQ_NONE) {
                saving = false
                scopeDlg = true // 重复日程：保存时必须选择影响范围（AC-010）
            } else {
                vm.saveEditAll(d, done)
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { close() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        // 顶栏：X | 标题 | 深色保存
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { close() }) { Icon(Icons.Default.Close, tr("关闭"), tint = Ink) }
            Text(
                when {
                    isEdit -> tr("编辑日程")
                    mode == 0 -> tr("新建日程")
                    mode == 1 -> tr("新建任务")
                    mode == 2 -> tr("贴表情")
                    else -> tr("AI 快速创建")
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            when (mode) {
                0 -> SaveButton(enabled = d.title.isNotBlank()) { doSaveEvent() }
                1 -> SaveButton(enabled = taskTitle.isNotBlank()) {
                    vm.addTask(taskTitle, taskDue, taskMemo)
                    toast(ctx, tr("已添加任务")); close()
                }
                2 -> SaveButton(enabled = stampEmoji.isNotBlank() || stampAsset.isNotBlank()) {
                    val name = if (stampAsset.isNotBlank())
                        com.looka.app.util.StampAssets.def(ctx, stampAsset)?.name() ?: "" else stampEmoji
                    vm.addStamp(
                        stampEmoji.ifBlank { "🦌" }, stampDay,
                        withEventTitle = if (stampBind) stampTitle.ifBlank { tr("{0} 计划", name) } else "",
                        assetId = stampAsset
                    ) { }
                    toast(ctx, if (stampBind) tr("已贴上表情并创建日程") else tr("已贴上表情")); close()
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Hairline()

        Box(Modifier.weight(1f)) {
            when (mode) {
                0 -> EventForm(vm, nav, d)
                1 -> TaskForm(
                    taskTitle, { taskTitle = it },
                    taskDue, { taskDueDlg = true }, { taskDue = -1L },
                    taskMemo, { taskMemo = it }
                )
                2 -> StampForm(
                    stampAsset, { stampAsset = it; if (it.isNotBlank()) stampEmoji = "" },
                    stampEmoji, { stampEmoji = it; if (it.isNotBlank()) stampAsset = "" },
                    stampDay, { stampDateDlg = true },
                    stampBind, { stampBind = it },
                    stampTitle, { stampTitle = it }
                )
                else -> AiQuickForm(vm, onDone = { n ->
                    toast(ctx, tr("已添加 {0} 项", n)); close()
                })
            }
        }

        // 底部模式切换（仅新建，规格 CAL-CRE-005：不退出即可切换）
        if (!isEdit) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeIcon(Icons.Outlined.CalendarMonth, tr("日程"), mode == 0) { mode = 0 }
                ModeIcon(Icons.Outlined.TaskAlt, tr("任务"), mode == 1) { mode = 1 }
                ModeIcon(Icons.Outlined.Mood, tr("表情"), mode == 2) { mode = 2 }
                ModeIcon(Icons.Outlined.AutoAwesome, "AI", mode == 3) { mode = 3 }
            }
        }
    }

    // 重复编辑范围（规格 CAL-021）
    if (discardDlg) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discardDlg = false },
            title = { Text(tr("放弃编辑？"), fontSize = 17.sp) },
            text = { Text(tr("刚才输入的内容不会保存。"), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { discardDlg = false; reallyClose() }) {
                    Text(tr("放弃"), color = HolidayRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardDlg = false }) { Text(tr("继续编辑"), color = GrayText) }
            }
        )
    }

    if (scopeDlg) ScopeDialog(
        title = tr("应用到哪些日程？"),
        onPick = { sc ->
            scopeDlg = false
            when (sc) {
                0 -> vm.saveEditThisOnly(d) { toast(ctx, tr("已修改本次")); close() }
                1 -> vm.saveEditFuture(d) { toast(ctx, tr("已修改本次及以后")); close() }
                else -> vm.saveEditAll(d) { toast(ctx, tr("已修改全部")); close() }
            }
        },
        onDismiss = { scopeDlg = false }
    )

    if (taskDueDlg) LookaDatePicker(
        initialDay = if (taskDue >= 0) taskDue else Fmt.today(),
        onPick = { taskDue = it },
        onDismiss = { taskDueDlg = false }
    )
    if (stampDateDlg) LookaDatePicker(
        initialDay = stampDay,
        onPick = { stampDay = it },
        onDismiss = { stampDateDlg = false }
    )
}

@Composable
private fun RowScope.ModeIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).plainClick(onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // M5（§63）：选中态从黑底方块改为浅灰正圆 —— Lifebear 的轻，不压内容
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (selected) PanelBg else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (selected) Ink else GrayText, modifier = Modifier.size(22.dp))
        }
        Text(label, fontSize = 10.sp, color = if (selected) Ink else GrayText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ==================== 日程表单 ====================

@Composable
private fun EventForm(vm: LookaViewModel, nav: NavHostController, d: EventDraft) {
    val cats by vm.categories.collectAsState()
    var startDateDlg by remember { mutableStateOf(false) }
    var endDateDlg by remember { mutableStateOf(false) }
    var startTimeDlg by remember { mutableStateOf(false) }
    var endTimeDlg by remember { mutableStateOf(false) }
    var catSheet by remember { mutableStateOf(false) }
    var remSheet by remember { mutableStateOf(false) }
    var tplSheet by remember { mutableStateOf(false) }

    // 节奏（2026-08-21 对齐 Lifebear 实机录屏）：进来光标就在标题上、键盘已经起来。
    // 少这一步，用户每次新建都要多点一下、多停半秒去找"从哪开始写"。
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (d.title.isBlank()) {
            kotlinx.coroutines.delay(120)   // 等转场动画落定再要焦点，否则键盘会被切走
            runCatching { titleFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 标题（输入后即可保存 —— 最低输入成本）+ 模板入口（规格 CAL-010）
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = d.title,
                onValueChange = { d.title = it },
                placeholder = { Text(tr("日程名"), fontSize = 18.sp, color = Color(0xFFB9BBB9)) },
                textStyle = TextStyle(fontSize = 18.sp),
                colors = clearFieldColors(),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(start = 4.dp).focusRequester(titleFocus)
            )
            IconButton(onClick = { tplSheet = true }) {
                Icon(Icons.Outlined.Bookmarks, tr("日程模板"), tint = GrayText, modifier = Modifier.size(20.dp))
            }
        }
        Hairline()

        // 日期时间行：开始 > 结束 + 全天开关（关闭全天立即显示时间 AC-003）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DateTimeCol(d.startDay, d.startMin, d.allDay,
                onDate = { startDateDlg = true }, onTime = { startTimeDlg = true })
            Icon(
                Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9),
                modifier = Modifier.padding(horizontal = 10.dp).size(20.dp)
            )
            DateTimeCol(d.endDay, d.endMin, d.allDay,
                onDate = { endDateDlg = true }, onTime = { endTimeDlg = true })
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = d.allDay,
                    onCheckedChange = {
                        d.allDay = it
                        vm.refreshDefaultReminder(d)  // 全天/时间默认提醒分离（CAL-NOT-002）
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
                Text(tr("全天"), fontSize = 11.sp, color = GrayText)
            }
        }
        Hairline()

        // 分类（颜色即分类 CAL-040）
        val cat = cats.find { it.id == d.categoryId }
        Row(
            Modifier.fillMaxWidth().plainClick { catSheet = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            ColorDot(parseHex(cat?.colorHex ?: "#9AA0A6"), 10.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(cat?.name ?: tr("未分类"), fontSize = 15.sp)
                Text(tr("Looka 日历"), fontSize = 11.sp, color = GrayText)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
        }
        Hairline()

        // 渐进式披露（规格 P2）：展开带动画
        androidx.compose.animation.AnimatedVisibility(visible = !d.detailExpanded) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tr("显示详细设置"), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.plainClick { d.detailExpanded = true }
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = d.detailExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column {
            // 提醒（一个日程可多条 AC-012）
            NavRow(
                tr("提醒"), icon = Icons.Outlined.Notifications,
                value = if (d.reminders.isEmpty()) tr("无")
                else d.reminders.joinToString("、") { Fmt.reminderText(d.allDay, it) }
            ) { remSheet = true }
            Hairline()

            // 重复
            NavRow(
                tr("重复"), icon = Icons.Outlined.Repeat,
                value = RecurrenceEngine.summary(
                    d.freq, d.interval, d.weekdays, d.monthlyByWeekday, d.untilDay, d.startDay
                )
            ) { nav.navigate("recur") }
            Hairline()

            // 地点
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Place, null, tint = GrayText, modifier = Modifier.size(20.dp))
                TextField(
                    value = d.location, onValueChange = { d.location = it },
                    placeholder = { Text(tr("地点"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
                    textStyle = TextStyle(fontSize = 15.sp),
                    colors = clearFieldColors(), singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Hairline()

            // 备注
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                Icon(
                    Icons.Outlined.Notes, null, tint = GrayText,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                TextField(
                    value = d.memo, onValueChange = { d.memo = it },
                    placeholder = { Text(tr("备注"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
                    textStyle = TextStyle(fontSize = 15.sp),
                    colors = clearFieldColors(), minLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
            Hairline()
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (startDateDlg) LookaDatePicker(d.startDay, onPick = { day ->
        val delta = day - d.startDay
        d.startDay = day
        d.endDay += delta
    }, onDismiss = { startDateDlg = false })

    if (endDateDlg) LookaDatePicker(d.endDay, onPick = { day ->
        d.endDay = maxOf(day, d.startDay)
    }, onDismiss = { endDateDlg = false })

    if (startTimeDlg) LookaTimePicker(d.startMin, onPick = { m ->
        d.startMin = m
        if (d.endDay == d.startDay && d.endMin <= m) d.endMin = minOf(m + 60, 24 * 60 - 1)
    }, onDismiss = { startTimeDlg = false })

    if (endTimeDlg) LookaTimePicker(d.endMin, onPick = { m ->
        d.endMin = m
    }, onDismiss = { endTimeDlg = false })

    if (catSheet) CategoryPickerSheet(
        vm, nav, current = d.categoryId,
        onPick = { d.categoryId = it; catSheet = false },
        onDismiss = { catSheet = false }
    )

    if (remSheet) ReminderSheet(d, onDismiss = { remSheet = false })

    if (tplSheet) TemplateSheet(vm, d, nav = nav, onDismiss = { tplSheet = false })
}

/** 日程模板列表：点选套用 / 保存当前 / 长按删除（规格 CAL-010 模板入口） */
@Composable
fun TemplateSheet(vm: LookaViewModel, d: EventDraft, nav: NavHostController? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tpls by vm.templates.collectAsState()
    var delTpl by remember { mutableStateOf<Template?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                tr("日程模板"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            if (tpls.isEmpty()) {
                Text(
                    tr("还没有模板。填好常用日程后存为模板，下次一键复用。"),
                    fontSize = 12.sp, color = GrayText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            tpls.forEach { t ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                vm.applyTemplate(d, t)
                                toast(ctx, tr("已套用模板"))
                                onDismiss()
                            },
                            onLongClick = { delTpl = t }
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Bookmarks, tr("模板"), tint = GrayText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(t.title, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
            Hairline()
            TextButton(
                onClick = {
                    if (d.title.isNotBlank()) {
                        vm.saveTemplate(d)
                        toast(ctx, tr("已存为模板"))
                    } else {
                        toast(ctx, tr("先填写日程名再保存模板"))
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text(tr("＋ 把当前内容存为模板"), color = MaterialTheme.colorScheme.primary) }
            if (tpls.isNotEmpty()) {
                Text(
                    tr("长按模板可删除"), fontSize = 11.sp, color = GrayText,
                    modifier = Modifier.padding(start = 20.dp)
                )
            }
            // CAL-062（§70）：与独立管理页互通
            if (nav != null) TextButton(
                onClick = { onDismiss(); nav.navigate("templates") },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text(tr("管理模板"), color = GrayText) }
        }
    }

    delTpl?.let { t ->
        ConfirmDialog(
            title = tr("删除模板「{0}」？", t.title),
            onConfirm = { vm.deleteTemplate(t); delTpl = null },
            onDismiss = { delTpl = null }
        )
    }
}

@Composable
private fun DateTimeCol(day: Long, min: Int, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    Column {
        Text(
            Fmt.dateCn(day), fontSize = 12.sp, color = GrayText,
            modifier = Modifier.plainClick(onDate)
        )
        if (!allDay) {
            Text(
                Fmt.hm(min), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.plainClick(onTime)
            )
        } else {
            Text(
                Fmt.d(day).let { tr("{0}年", it.year) }, fontSize = 12.sp, color = Color(0xFFB9BBB9),
                modifier = Modifier.plainClick(onDate)
            )
        }
    }
}

// ==================== 提醒列表（CAL-030/031） ====================

@Composable
fun ReminderSheet(d: EventDraft, onDismiss: () -> Unit) {
    var addStep by remember { mutableStateOf(0) }         // 0 关闭 / 1 选规则 / 2 选时刻（全天）
    var pendingDays by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                tr("提醒"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            if (d.reminders.isEmpty()) {
                Text(
                    tr("暂无提醒"), fontSize = 13.sp, color = GrayText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            d.reminders.forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Fmt.reminderText(d.allDay, r), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = r.enabled,
                        onCheckedChange = {
                            d.reminders[i] = r.copy(enabled = it)
                            d.remindersTouched = true
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                    IconButton(onClick = {
                        d.reminders.removeAt(i)
                        d.remindersTouched = true
                    }) {
                        Icon(Icons.Outlined.Delete, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                    }
                }
                // A2-5：提醒 vs 闹钟 —— 提醒响一声可划走；闹钟持续响，必须手动停
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("⏰ 当成闹钟（持续响，静音也响）"), fontSize = 12.sp,
                        color = if (r.alarm) MaterialTheme.colorScheme.primary else GrayText,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = r.alarm,
                        onCheckedChange = {
                            d.reminders[i] = r.copy(alarm = it)
                            d.remindersTouched = true
                        },
                        modifier = Modifier.scale(0.8f),
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
            TextButton(
                onClick = { addStep = 1 },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text(tr("＋ 添加提醒"), color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (addStep == 1) {
        if (d.allDay) {
            RadioDialog(
                tr("提前多久提醒"),
                options = listOf(0 to tr("当天"), 1 to tr("1天前"), 2 to tr("2天前"), 3 to tr("3天前"), 7 to tr("1周前")),
                selected = -100,
                onSelect = { pendingDays = it; addStep = 2 },
                onDismiss = { if (addStep == 1) addStep = 0 }
            )
        } else {
            RadioDialog(
                tr("提前多久提醒"),
                options = listOf(
                    0 to tr("准时"), 5 to tr("5分钟前"), 10 to tr("10分钟前"), 15 to tr("15分钟前"),
                    30 to tr("30分钟前"), 60 to tr("1小时前"), 120 to tr("2小时前"), 1440 to tr("1天前")
                ),
                selected = -100,
                onSelect = { m ->
                    d.reminders.add(Reminder(minutesBefore = m))
                    d.remindersTouched = true
                    addStep = 0
                },
                onDismiss = { if (addStep == 1) addStep = 0 }
            )
        }
    }
    if (addStep == 2) {
        LookaTimePicker(
            initialMin = 480,
            onPick = { t ->
                d.reminders.add(Reminder(daysBefore = pendingDays, timeOfDayMin = t))
                d.remindersTouched = true
            },
            onDismiss = { addStep = 0 }
        )
    }
}

// ==================== 分类选择（CAL-040） ====================

@Composable
fun CategoryPickerSheet(
    vm: LookaViewModel,
    nav: NavHostController,
    current: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val cats by vm.categories.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                tr("选择分类"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            cats.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().plainClick { onPick(c.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(c.colorHex), 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        c.name + if (!c.visible) tr("（已隐藏）") else "",
                        fontSize = 15.sp, modifier = Modifier.weight(1f),
                        color = if (c.visible) Ink else GrayText
                    )
                    if (c.id == current) Icon(Icons.Default.Check, tr("已选中"), tint = Ink, modifier = Modifier.size(18.dp))
                }
            }
            Hairline()
            NavRow(tr("管理分类")) { onDismiss(); nav.navigate("categories") }
        }
    }
}

// ==================== 重复范围弹窗（CAL-021） ====================

@Composable
fun ScopeDialog(title: String, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column {
                listOf(tr("仅本次"), tr("本次及以后"), tr("全部")).forEachIndexed { i, label ->
                    Text(
                        label, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().plainClick { onPick(i) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

// ==================== 任务表单 ====================

@Composable
private fun TaskForm(
    title: String, onTitle: (String) -> Unit,
    due: Long, onPickDue: () -> Unit, onClearDue: () -> Unit,
    memo: String, onMemo: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextField(
            value = title, onValueChange = onTitle,
            placeholder = { Text(tr("任务名"), fontSize = 18.sp, color = Color(0xFFB9BBB9)) },
            textStyle = TextStyle(fontSize = 18.sp),
            colors = clearFieldColors(), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Hairline()
        Row(
            Modifier.fillMaxWidth().plainClick(onPickDue)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(tr("截止日期"), fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                if (due >= 0) Fmt.dateCn(due) else tr("无"),
                fontSize = 14.sp, color = if (due >= 0) Ink else GrayText
            )
            if (due >= 0) {
                Spacer(Modifier.width(10.dp))
                Text(tr("清除"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.plainClick(onClearDue))
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
            Icon(
                Icons.Outlined.Notes, null, tint = GrayText,
                modifier = Modifier.size(20.dp).padding(top = 2.dp)
            )
            TextField(
                value = memo, onValueChange = onMemo,
                placeholder = { Text(tr("备注"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
                textStyle = TextStyle(fontSize = 15.sp),
                colors = clearFieldColors(), minLines = 2,
                modifier = Modifier.weight(1f)
            )
        }
        Hairline()
        Text(
            tr("带截止日期的任务会出现在日历上"),
            fontSize = 12.sp, color = GrayText,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// ==================== 表情表单（对齐 Lifebear：5×2 翻页 + 包切换） ====================

@Composable
private fun StampForm(
    selectedAsset: String, onSelectAsset: (String) -> Unit,
    selectedEmoji: String, onSelectEmoji: (String) -> Unit,
    day: Long, onPickDay: () -> Unit,
    bind: Boolean, onBind: (Boolean) -> Unit,
    bindTitle: String, onBindTitle: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().plainClick(onPickDay)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(tr("贴到哪天"), fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(Fmt.dateCn(day), fontSize = 14.sp)
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
        }
        Hairline()
        // 表情绑定日程（规格 CAL-051）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("同时创建日程并绑定"), fontSize = 15.sp)
                Text(tr("绑定后点表情可直达日程详情"), fontSize = 11.sp, color = GrayText)
            }
            Switch(
                checked = bind, onCheckedChange = onBind,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
            )
        }
        if (bind) {
            TextField(
                value = bindTitle, onValueChange = onBindTitle,
                placeholder = { Text(tr("日程名，例：健身打卡"), fontSize = 14.sp, color = Color(0xFFB9BBB9)) },
                textStyle = TextStyle(fontSize = 14.sp),
                colors = clearFieldColors(), singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
            )
        }
        Hairline()
        StickerPicker(selected = selectedAsset, onSelect = onSelectAsset)
        Spacer(Modifier.height(16.dp))
    }
}

// ==================== AI 快速创建 ====================

@Composable
private fun AiQuickForm(vm: LookaViewModel, onDone: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf<List<AiAction>>(emptyList()) }
    val checked = remember { mutableStateListOf<Int>() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr("用一句话描述，小鹿帮你安排 🦌"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            tr("试试：明天下午3点和老王开会；周五前交报告"),
            fontSize = 12.sp, color = GrayText,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            placeholder = { Text(tr("例如：下周三晚上8点跑步半小时"), color = Color(0xFFB9BBB9)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true; error = null; actions = emptyList(); checked.clear()
                    try {
                        val list = vm.aiParseActions(input.trim())
                        if (list.isEmpty()) error = tr("没有识别出可创建的内容，换个说法试试")
                        else {
                            actions = list
                            checked.addAll(list.indices)
                        }
                    } catch (e: Exception) {
                        error = e.message ?: tr("网络异常")
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = input.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("小鹿识别中…"))
            } else Text(tr("识别"))
        }
        error?.let {
            Text(it, fontSize = 13.sp, color = HolidayRed, modifier = Modifier.padding(top = 8.dp))
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(tr("识别结果"), fontSize = 13.sp, color = GrayText)
            actions.forEachIndexed { i, a ->
                Row(
                    Modifier.fillMaxWidth()
                        .plainClick { if (checked.contains(i)) checked.remove(i) else checked.add(i) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked.contains(i),
                        onCheckedChange = { if (it) checked.add(i) else checked.remove(i) }
                    )
                    Text(a.label(), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val sel = actions.filterIndexed { i, _ -> checked.contains(i) }
                    scope.launch {
                        try {
                            vm.execActions(sel)
                            onDone(sel.size)
                        } catch (e: Exception) {
                            error = e.message ?: tr("添加失败")
                        }
                    }
                },
                enabled = checked.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("添加到 Looka（{0}）", checked.size)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.looka.app.ui.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.R
import com.looka.app.data.Occ
import com.looka.app.data.Prefs
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Stamp
import com.looka.app.data.Task
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaDatePicker
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.onColor
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.weekdayTint
import com.looka.app.ui.theme.DimBg
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.Hairline as HairColor
import com.looka.app.util.Fmt
import com.looka.app.util.LunarCal
import com.looka.app.util.PendingNav
import com.looka.app.util.StampAssets
import com.looka.app.util.SysCal
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 日历主页 v3（对齐 Lifebear 实机）：
 * 月视图全屏铺满、一格约 7 条；点击日期弹出可拖拽的日详情抽屉，月历自动滚动让选中周可见；
 * 农历/节假日（对位六曜）；周/日视图沿用时间轴。
 */
@Composable
fun CalendarScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val cats by vm.categories.collectAsState()
    val series by vm.seriesAll.collectAsState()
    val exceptions by vm.exceptionsAll.collectAsState()
    val tasksList by vm.tasks.collectAsState()
    val taskLists by vm.taskLists.collectAsState()
    val stampsList by vm.stamps.collectAsState()
    val diariesList by vm.diaries.collectAsState()
    val remindersList by vm.remindersAll.collectAsState()

    val weekStartMon = remember(vm.settingsVersion) { Prefs.weekStartMonday(ctx) }
    val holidayMask = remember(vm.settingsVersion) { Prefs.holidayMask(ctx) }
    val showDone = remember(vm.settingsVersion) { Prefs.showDoneTasks(ctx) }
    val showSysCal = remember(vm.settingsVersion) { Prefs.showSysCal(ctx) }
    val hiddenCals = remember(vm.settingsVersion) { Prefs.hiddenSysCals(ctx) }

    var menuOpen by remember { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }
    var sysDetail by remember { mutableStateOf<SysCal.SysEvent?>(null) }
    // §72 §5/§9：印章 Popover（锚点 = 印章在根坐标系的中心）
    var stampMenu by remember { mutableStateOf<Pair<Stamp, androidx.compose.ui.geometry.Offset>?>(null) }
    // §72 §D：屏幕坐标 → (hostDate, u, v)，由月视图注册
    val gridHit = remember { mutableStateOf<((androidx.compose.ui.geometry.Offset) -> Triple<Long, Float, Float>?)?>(null) }
    // §72 §4：从 Picker 拖出的 Ghost（assetId → 当前根坐标）
    var ghost by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Offset>?>(null) }
    val showStampTitle = remember(vm.settingsVersion) { Prefs.stampTitle(ctx) }

    val catOrder = remember(cats) { cats.mapIndexed { i, c -> c.id to i }.toMap() }
    val visibleCatIds = remember(cats) { cats.filter { it.visible }.map { it.id }.toSet() }
    val catColorMap = remember(cats) { cats.associate { it.id to parseHex(it.colorHex) } }
    val listColorMap = remember(taskLists) { taskLists.associate { it.uid to parseHex(it.colorHex) } }
    val reminderSeriesIds = remember(remindersList) {
        remindersList.filter { it.enabled }.map { it.seriesId }.toSet()
    }

    // 日详情抽屉（仅月视图）
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden, skipHiddenState = false
    )
    val scope = rememberCoroutineScope()
    val sheetOpen = sheetState.targetValue != SheetValue.Hidden
    val showLunar = remember(vm.settingsVersion, com.looka.app.util.I18n.lang) {
        Prefs.showLunarRaw(ctx) ?: com.looka.app.util.I18n.isZh()
    }

    // 通知点击 → 跳到对应日期并展开抽屉（B12）
    LaunchedEffect(Unit) {
        PendingNav.consume()?.let { d ->
            vm.selectedDay = d
            vm.calMonth = YearMonth.from(Fmt.d(d))
            vm.calScrollReq = d
            vm.calView = 0
            scope.launch { sheetState.partialExpand() }
        }
    }

    // N1（§71）：创建面板打开时，返回键先收面板；面板与日详情抽屉互斥
    BackHandler(enabled = vm.createPanel) {
        vm.createPanel = false; vm.stampSel = ""
    }
    LaunchedEffect(vm.createPanel) {
        if (vm.createPanel) sheetState.hide()
    }

    BackHandler(enabled = vm.calView == 0 && sheetOpen) {
        scope.launch { sheetState.hide() }
    }

    Box(Modifier.fillMaxSize()) {
    // §11：Picker 打开时不重算行高压缩月历，只把 viewport 底部让出来 —— 保持网格几何与空间记忆
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(bottom = if (vm.createPanel) vm.panelInset else androidx.compose.ui.unit.Dp(0f))
    ) {
        // 顶栏：大字年月（点击跳转）+ 今天 + 小鹿 AI + 视图菜单
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (vm.calView) {
                0 -> Column(Modifier.plainClick { jumpOpen = true }) {
                    Text("${vm.calMonth.year}", fontSize = 12.sp, color = GrayText)
                    Text(
                        Fmt.monthShort(vm.calMonth),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp
                    )
                }
                1 -> Text(
                    weekTitle(vm.selectedDay, weekStartMon),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.plainClick { jumpOpen = true }
                )
                else -> Column(Modifier.plainClick { jumpOpen = true }) {
                    Text("${Fmt.d(vm.selectedDay).year}", fontSize = 12.sp, color = GrayText)
                    Text(
                        Fmt.dateCn(vm.selectedDay),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(
                visible = vm.selectedDay != Fmt.today() || vm.calMonth != YearMonth.now(),
                enter = androidx.compose.animation.fadeIn(tween(180)) +
                        androidx.compose.animation.scaleIn(tween(180), initialScale = 0.85f),
                exit = androidx.compose.animation.fadeOut(tween(140))
            ) {
                Text(
                    tr("今天"), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .plainClick {
                            vm.selectedDay = Fmt.today()
                            vm.calMonth = YearMonth.now()
                            vm.calScrollReq = Fmt.today()   // 连续滚动模式下要真的滚回去
                        }
                        .padding(horizontal = 6.dp)
                )
            }
            // §71 A：AI 顶栏入口恢复（用户 2026-08-23 拍板推翻 §60 R3——全站要有方便调出的位置）
            IconButton(onClick = { nav.navigate("aiChat") }, modifier = Modifier.size(40.dp)) {
                com.looka.app.ui.common.DeerBadge(24.dp)
            }
            // 顶栏收敛（2026-08-21 对齐 Lifebear）：日历图标（数字随视图变 31/7/1）收拢 跳转/搜索/显示设置
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                CalendarGlyph(when (vm.calView) { 0 -> "31"; 1 -> "7"; else -> "1" })
            }
                    }

        val occRangeStart: Long
        val occRangeEnd: Long
        when (vm.calView) {
            0 -> {
                // 连续滚动的滑动窗口：标题月 ±1 月。视口约 6 周，±1 月足够盖住可见区，
                // 滚动带动 calMonth 变化时窗口自动跟着挪 —— 不会"滚过去日程消失"，也不会展开十年。
                // P2-B3：±2 月 —— calMonth 由滚动反推慢一拍，±1 月在快速甩动时会露白
                occRangeStart = vm.calMonth.minusMonths(2).atDay(1).toEpochDay()
                occRangeEnd = vm.calMonth.plusMonths(2).atEndOfMonth().toEpochDay()
            }
            1 -> {
                val ws = weekStart(vm.selectedDay, weekStartMon)
                occRangeStart = ws; occRangeEnd = ws + 6
            }
            else -> {
                occRangeStart = vm.selectedDay; occRangeEnd = vm.selectedDay
            }
        }
        val occs = remember(series, exceptions, occRangeStart, occRangeEnd, visibleCatIds) {
            RecurrenceEngine.expand(series, exceptions, occRangeStart, occRangeEnd)
                .filter { it.categoryId in visibleCatIds || it.categoryId !in catOrder.keys }
        }
        val sysEvents by produceState(
            initialValue = emptyList<SysCal.SysEvent>(),
            occRangeStart, occRangeEnd, showSysCal, vm.settingsVersion
        ) {
            value = if (showSysCal) SysCal.instances(ctx, occRangeStart, occRangeEnd, hiddenCals)
            else emptyList()
        }

        // 抽屉数据：独立于网格计算，跨月选中也正确
        val selDay = vm.selectedDay
        val selOccs = remember(series, exceptions, selDay, visibleCatIds) {
            RecurrenceEngine.expand(series, exceptions, selDay - 31, selDay + 1)
                .filter {
                    selDay in it.day..it.endDay &&
                            (it.categoryId in visibleCatIds || it.categoryId !in catOrder.keys)
                }
                .sortedWith(compareBy({ !it.allDay }, { it.startMin }, { catOrder[it.categoryId] ?: 99 }))
        }
        val selSys = remember(sysEvents, selDay) { sysEvents.filter { selDay in it.day..it.endDayIncl } }
        val selTasks = remember(tasksList, selDay, showDone) {
            tasksList.filter { it.dueDay == selDay && (showDone || !it.done) }
        }
        val selDiary = remember(diariesList, selDay) { diariesList.find { it.day == selDay } }
        val selStamps = remember(stampsList, selDay) { stampsList.filter { it.day == selDay } }

        when (vm.calView) {
            0 -> MonthFull(
                vm, nav,
                month = vm.calMonth,
                showLunar = showLunar,
                occs = occs, sysEvents = sysEvents,
                tasksList = tasksList, stampsList = stampsList,
                weekStartMon = weekStartMon, holidayMask = holidayMask, showDone = showDone,
                catColorMap = catColorMap, catOrder = catOrder, listColorMap = listColorMap,
                sheetState = sheetState, sheetOpen = sheetOpen,
                onOpenSys = { sysDetail = it },
                onStampTap = { st, pos -> stampMenu = st to pos },
                boundOf = { st ->
                    if (!showStampTitle) null
                    else vm.stampSeries(st)?.takeIf { it.title.isNotBlank() }?.let { it.id to it.title }
                },
                onGridReady = { gridHit.value = it },
                sheetContent = {
                    DaySheet(
                        vm, nav, selDay, selOccs, selSys, selTasks, selDiary, selStamps,
                        catColorMap, listColorMap, reminderSeriesIds,
                        onOpenSys = { sysDetail = it }
                    )
                }
            )
            1 -> {
                val ws = weekStart(vm.selectedDay, weekStartMon)
                TimelineBody(
                    vm, nav, (0..6).map { ws + it }, occs, sysEvents, holidayMask, catColorMap,
                    onOpenSys = { sysDetail = it },
                    onLongPressAllDay = { d ->
                        vm.prepareCreateDraft(d, allDay = true); nav.navigate("editor")
                    }
                )
            }
            else -> TimelineBody(
                vm, nav, listOf(vm.selectedDay), occs, sysEvents, holidayMask, catColorMap,
                onOpenSys = { sysDetail = it },
                onLongPressAllDay = { d ->
                    vm.prepareCreateDraft(d, allDay = true); nav.navigate("editor")
                }
            )
        }
    }

    // §72 §4：拖拽创建的 Ghost —— 跟手 1:1、固定虚线交互圈、不放大不旋转（§10）
    ghost?.let { (assetId, pos) ->
        val cfgG = androidx.compose.ui.platform.LocalConfiguration.current
        val wd = cfgG.screenWidthDp / 7f
        val visual = (wd * 0.47f).dp
        val ring = (wd * 1.08f).dp
        val half = with(LocalDensity.current) { ring.toPx() / 2f }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(
                    (pos.x - half).toInt(), (pos.y - half).toInt()) }
                .size(ring)
                .zIndex(70f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFF3A3A3A),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                )
            }
            StampAssets.bitmap(ctx, assetId)?.let {
                Image(it, null, modifier = Modifier.size(visual))
            }
        }
    }

    // §72 §5：印章 Popover —— 未绑定「登记日程 / 删除」；已绑定「编辑 / 删除」
    stampMenu?.let { (st, pos) ->
        val boundSeries = vm.stampSeries(st)
        StickerPopover(
            anchor = pos,
            bound = boundSeries != null,
            onPrimary = {
                stampMenu = null
                if (boundSeries != null) {
                    // §5 修正：已绑定应进这条日程，而不是再新建一条（旧实现的 bug）
                    nav.navigate("detail/${boundSeries.id}/${st.day}")
                } else {
                    // §5.2：复用标准快速创建，印章与 hostDate 作为上下文带入
                    vm.prepareCreateDraft(st.day, allDay = true)
                    vm.pendingStampBind = st.id
                    nav.navigate("editor")
                }
            },
            onDelete = { vm.deleteStamp(st.id); stampMenu = null },
            onDismiss = { stampMenu = null }
        )
    }

    // N1（§71 对齐 Lifebear b 图）：底部停靠创建面板 —— 日历可见可点，贴纸可连续盖章
    androidx.compose.animation.AnimatedVisibility(
        visible = vm.createPanel,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.slideInVertically(
            tween(com.looka.app.ui.theme.Motion.ENTER)) { it } +
            androidx.compose.animation.fadeIn(tween(com.looka.app.ui.theme.Motion.ENTER)),
        exit = androidx.compose.animation.slideOutVertically(
            tween(com.looka.app.ui.theme.Motion.EXIT)) { it } +
            androidx.compose.animation.fadeOut(tween(com.looka.app.ui.theme.Motion.EXIT))
    ) {
        CreatePanel(
            vm, nav,
            onClose = { vm.createPanel = false; vm.stampSel = "" },
            onDragCreate = { assetId, pos, phase ->
                when (phase) {
                    0, 1 -> ghost = assetId to pos
                    2 -> {
                        // §4 Drop：命中日期格 → 落库；未命中 → 取消（AC-003）
                        gridHit.value?.invoke(pos)?.let { (d, u, v) ->
                            vm.addStamp("🦌", d, assetId = assetId, px = u, py = v) { newId ->
                                // §4.1 New Drop：首次放置后自动弹语义确认（AC-004）。
                                // 锚点直接用落点 —— 印章此刻就在手指松开的位置
                                stampMenu = com.looka.app.data.Stamp(
                                    id = newId, emoji = "🦌", day = d,
                                    assetId = assetId, posX = u, posY = v
                                ) to pos
                            }
                        }
                        ghost = null
                    }
                    else -> ghost = null
                }
            }
        )
    }
    }

    if (menuOpen) ViewMenuSheet(vm, nav, onJump = { jumpOpen = true }, onDismiss = { menuOpen = false })
    if (jumpOpen) LookaDatePicker(
        initialDay = vm.selectedDay,
        onPick = { d ->
            vm.selectedDay = d
            vm.calMonth = YearMonth.from(Fmt.d(d))
            vm.calScrollReq = d
        },
        onDismiss = { jumpOpen = false }
    )

    sysDetail?.let { e ->
        AlertDialog(
            onDismissRequest = { sysDetail = null },
            title = { Text(e.title, fontSize = 17.sp) },
            text = {
                Column {
                    Text(
                        if (e.allDay) {
                            if (e.endDayIncl > e.day) tr("{0} - {1} · 全天", Fmt.dateCn(e.day), Fmt.dateCn(e.endDayIncl))
                            else tr("{0} · 全天", Fmt.dateFull(e.day))
                        } else {
                            "${Fmt.dateFull(e.day)}  ${Fmt.hm(e.startMin)} - ${Fmt.hm(e.endMin)}"
                        },
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .border(2.dp, Color(e.color), CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tr("来自系统日历「{0}」", e.calName), fontSize = 12.sp, color = GrayText)
                    }
                    Text(
                        tr("系统日历事件在 Looka 中为只读显示"),
                        fontSize = 11.sp, color = GrayText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { sysDetail = null }) {
                    Text(tr("好的"), color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = Color.White
        )
    }
}

/** 该周起始 epochDay */
fun weekStart(day: Long, weekStartMon: Boolean): Long {
    val dow = Fmt.d(day).dayOfWeek.value
    val firstDow = if (weekStartMon) 1 else 7
    return day - ((dow - firstDow) + 7) % 7
}

private fun weekTitle(day: Long, weekStartMon: Boolean): String {
    val ws = weekStart(day, weekStartMon)
    val a = Fmt.d(ws)
    val b = Fmt.d(ws + 6)
    return tr("{0}月{1}日 - {2}月{3}日", a.monthValue, a.dayOfMonth, b.monthValue, b.dayOfMonth)
}

// ==================== 连续滚动月视图 + 日详情抽屉（2026-08-21 对齐 Lifebear） ====================
// 结构：LazyColumn 以「周」为最小单元跨月无限滚动（2016~2036 约 1096 周）。
// 标题月由视口反推（取第 2 行的周四所在月）；今天/跳转/通知通过 vm.calScrollReq 显式请求滚动。
// 数据由外层按 calMonth ±1 月滑动窗口展开 —— 滚出窗口的日程不会消失（窗口跟着标题月走）。

/** 连续滚动的周索引原点与总量：2016-01-01 所在周 ~ 约 2036 年底 */
private const val TOTAL_WEEKS = 1096

private fun weekOrigin(weekStartMon: Boolean): Long =
    weekStart(java.time.LocalDate.of(2016, 1, 1).toEpochDay(), weekStartMon)

@Composable
private fun MonthFull(
    vm: LookaViewModel,
    nav: NavHostController,
    month: YearMonth,
    showLunar: Boolean,
    occs: List<Occ>,
    sysEvents: List<SysCal.SysEvent>,
    tasksList: List<Task>,
    stampsList: List<Stamp>,
    weekStartMon: Boolean,
    holidayMask: Int,
    showDone: Boolean,
    catColorMap: Map<Long, Color>,
    catOrder: Map<Long, Int>,
    listColorMap: Map<String, Color>,
    sheetState: SheetState,
    sheetOpen: Boolean,
    onOpenSys: (SysCal.SysEvent) -> Unit,
    onStampTap: (Stamp, androidx.compose.ui.geometry.Offset) -> Unit = { _, _ -> },
    boundOf: (Stamp) -> Pair<Long, String>? = { null },
    /** §72 D：把「屏幕坐标 → (日期, u, v)」的命中能力交给外层拖拽层 */
    onGridReady: ((androidx.compose.ui.geometry.Offset) -> Triple<Long, Float, Float>?) -> Unit = {},
    sheetContent: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val firstDow = if (weekStartMon) 1 else 7
    val origin = remember(weekStartMon) { weekOrigin(weekStartMon) }
    fun weekIdx(day: Long) = (((weekStart(day, weekStartMon)) - origin) / 7).toInt().coerceIn(0, TOTAL_WEEKS - 1)

    // 按天索引（跨天平铺；全天在前，按时间与分类排序 —— 规格 §6）。范围 = 数据窗口自身。
    val occByDay = remember(occs, catOrder) {
        val m = HashMap<Long, MutableList<Occ>>()
        for (o in occs) {
            var d = o.day
            while (d <= o.endDay) {
                m.getOrPut(d) { ArrayList() }.add(o); d++
            }
        }
        m.values.forEach { list ->
            list.sortWith(compareBy({ !it.allDay }, { it.startMin }, { catOrder[it.categoryId] ?: 99 }))
        }
        m
    }
    val sysByDay = remember(sysEvents) {
        val m = HashMap<Long, MutableList<SysCal.SysEvent>>()
        for (e in sysEvents) {
            var d = e.day
            val last = minOf(e.endDayIncl, e.day + 62)   // 防御：异常长的系统事件别撑爆索引
            while (d <= last) {
                m.getOrPut(d) { ArrayList() }.add(e); d++
            }
        }
        m
    }
    val tasksByDay = remember(tasksList, showDone) {
        tasksList.filter { it.dueDay >= 0 && (showDone || !it.done) }.groupBy { it.dueDay }
    }
    val stampsByDay = remember(stampsList) { stampsList.groupBy { it.day } }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = weekIdx(vm.selectedDay))

    // 视口 → 标题月：取视口第 2 行（约 1/3 处）的周四定月份。
    // 用「代表日」而不是首行，边界周天然带滞回，不会来回抖。
    LaunchedEffect(listState, weekStartMon) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            val probe = origin + (idx + 1) * 7L + 3
            val m = YearMonth.from(Fmt.d(probe))
            if (m != vm.calMonth) vm.calMonth = m
        }
    }
    // 外部跳转请求（今天 / 跳转到日期 / 通知点进来）
    LaunchedEffect(vm.calScrollReq) {
        vm.calScrollReq?.let { d ->
            listState.scrollToItem(weekIdx(d))
            vm.calScrollReq = null
        }
    }
    // 抽屉展开时，把选中周滚到可见区顶部
    LaunchedEffect(sheetOpen, vm.selectedDay) {
        if (sheetOpen) listState.animateScrollToItem(weekIdx(vm.selectedDay))
    }

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState),
        sheetPeekHeight = 392.dp,
        sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetContainerColor = Color.White,
        sheetShadowElevation = 12.dp,
        containerColor = Color.White,
        sheetContent = { sheetContent() }
    ) { _ ->
        Column(Modifier.fillMaxSize()) {
            // 星期头（常驻，滚动时不动）
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                for (i in 0 until 7) {
                    val dow = ((firstDow - 1 + i) % 7) + 1
                    Text(
                        Fmt.week(dow), fontSize = 11.sp,
                        color = weekdayTint(dow, holidayMask) ?: GrayText,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                }
            }
            Hairline()

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 固定行高：一屏约 6 周（Lifebear 密度）。不能再由"月行数"反推 —— 那是翻页模式的产物
                val rowH = maxHeight / 6
                // S2/S3（§64）：字号三档 —— 大(默认)≈5条/格、中≈6、小≈8。
                // Lifebear 的做法：不替用户决定字号，给三档自己选。
                val ctxSz = androidx.compose.ui.platform.LocalContext.current
                val sizeTier = remember(vm.settingsVersion) { Prefs.eventTextSize(ctxSz) }
                val lineH = when (sizeTier) { 0 -> 15.dp; 1 -> 13.dp; else -> 11.dp }
                val maxLines = ((rowH - 17.dp) / lineH).toInt().coerceIn(3, 8)
                val today = Fmt.today()

                // §72 §7/§8/§D：把网格几何交给外层 —— 屏幕坐标 → (hostDate, u, v)。
                // 只靠列宽与行高换算，天然支持连续滚动与不同屏宽（§7 禁止绝对屏幕坐标）。
                var gridRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                var gridSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                val rowHpx = with(LocalDensity.current) { rowH.toPx() }
                LaunchedEffect(gridRoot, gridSize, rowHpx, origin) {
                    onGridReady { p ->
                        val rx = p.x - gridRoot.x
                        val ry = p.y - gridRoot.y
                        if (gridSize.width <= 0 || rx < 0f || ry < 0f ||
                            rx > gridSize.width || ry > gridSize.height) null
                        else {
                            val colW = gridSize.width / 7f
                            val col = (rx / colW).toInt().coerceIn(0, 6)
                            val yAbs = ry + listState.firstVisibleItemScrollOffset
                            val rowOff = kotlin.math.floor(yAbs / rowHpx).toInt()
                            val wIdx = (listState.firstVisibleItemIndex + rowOff)
                                .coerceIn(0, TOTAL_WEEKS - 1)
                            Triple(
                                origin + wIdx * 7L + col,
                                ((rx - col * colW) / colW).coerceIn(0f, 1f),
                                ((yAbs - rowOff * rowHpx) / rowHpx).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    // §8：拖动印章时锁滚动，避免"想挪印章却把整月滚走"
                    userScrollEnabled = !vm.stampDragging,
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        gridRoot = it.positionInRoot(); gridSize = it.size
                    }
                ) {
                    items(TOTAL_WEEKS, key = { it }) { wi ->
                        val ws = origin + wi * 7L
                        // P2-B2：水印叠在格子「之上」（之前 drawBehind 被格子不透明底色盖住，
                        // 只有溢出到上一行的顶部 30% 漏出来）。低透明度 + 一行内装得下。
                        val wmMonth = (0..6).map { Fmt.d(ws + it) }.firstOrNull { it.dayOfMonth == 15 }?.monthValue
                        Box(Modifier.height(rowH).fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxSize()
                        ) {
                            for (c in 0 until 7) {
                                val day = ws + c
                                DayCellV2(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    day = day,
                                    showLunar = showLunar,
                                    inMonth = YearMonth.from(Fmt.d(day)) == month,
                                    isToday = day == today,
                                    isSelected = day == vm.selectedDay,
                                    holidayMask = holidayMask,
                                    maxLines = maxLines,
                                    occList = occByDay[day].orEmpty(),
                                    sysList = sysByDay[day].orEmpty(),
                                    taskList = tasksByDay[day].orEmpty(),
                                    stampList = stampsByDay[day].orEmpty(),
                                    catColorMap = catColorMap,
                                    listColorMap = listColorMap,
                                    onSelect = {
                                        when {
                                            // N1（§71）：贴纸模式 —— 点哪天贴哪天，连续盖章
                                            vm.stampSel.isNotBlank() ->
                                                vm.addStamp("🦌", day, assetId = vm.stampSel)
                                            // 面板开着：改日期不弹抽屉（快建日期跟着选中走）
                                            vm.createPanel -> vm.selectedDay = day
                                            else -> {
                                                vm.selectedDay = day
                                                if (sheetState.currentValue == SheetValue.Hidden) {
                                                    scope.launch { sheetState.partialExpand() }
                                                }
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        // CAL-CRE-002：长按日期直接创建并预填
                                        vm.prepareCreateDraft(day)
                                        nav.navigate("editor")
                                    },
                                    onStampMove = { id, nd, px, py -> vm.moveStamp(id, nd, px, py) },
                                    onStampTap = { st, pos -> onStampTap(st, pos) },
                                    onStampDrag = { vm.stampDragging = it },
                                    boundOf = boundOf
                                )
                            }
                        }
                        if (wmMonth != null) Text(
                            "$wmMonth",
                            fontSize = (rowH.value * 0.82f).sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black.copy(alpha = 0.05f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                        }
                    }
                }
            
                // CAL-001 v1.1：远离今天时右下浮现「回今天」（连续滚动特有）
                val todayIdx = remember(weekStartMon) { ((weekStart(today, weekStartMon) - origin) / 7).toInt() }
                val farFromToday by remember {
                    derivedStateOf { kotlin.math.abs(listState.firstVisibleItemIndex - todayIdx) > 2 }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = farFromToday,
                    enter = androidx.compose.animation.fadeIn(tween(com.looka.app.ui.theme.Motion.ENTER)) +
                        androidx.compose.animation.scaleIn(tween(com.looka.app.ui.theme.Motion.ENTER), initialScale = 0.85f),
                    exit = androidx.compose.animation.fadeOut(tween(com.looka.app.ui.theme.Motion.EXIT)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 16.dp)
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Ink.copy(alpha = 0.86f))
                            .plainClick {
                                vm.selectedDay = today
                                vm.calMonth = YearMonth.now()
                                vm.calScrollReq = today
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(tr("回今天"), fontSize = 12.5.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
}
        }
    }

}

@Composable
private fun DayCellV2(
    modifier: Modifier,
    day: Long,
    showLunar: Boolean,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    holidayMask: Int,
    maxLines: Int,
    occList: List<Occ>,
    sysList: List<SysCal.SysEvent>,
    taskList: List<Task>,
    stampList: List<Stamp>,
    catColorMap: Map<Long, Color>,
    listColorMap: Map<String, Color>,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onStampMove: (Long, Long, Float, Float) -> Unit = { _, _, _, _ -> },
    onStampTap: (Stamp, androidx.compose.ui.geometry.Offset) -> Unit = { _, _ -> },
    onStampDrag: (Boolean) -> Unit = {},
    /** 绑定日程 (seriesId, 标题)；未绑定或设置关闭时为 null —— §6 复合气泡 + §6.1 月格去重 */
    boundOf: (Stamp) -> Pair<Long, String>? = { null }
) {
    val dt = Fmt.d(day)
    val lunar = if (showLunar) LunarCal.of(day) else null
    // P2-B1：连续滚动下没有"非本月"，所有日期都用正常色
    val numTint = weekdayTint(dt.dayOfWeek.value, holidayMask) ?: Ink
    val lunarTint = when {
        lunar?.festival != null -> HolidayRed
        lunar?.isShuoWang == true -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFA8ADA8)
    }
    val hairColor = HairColor

    Box(
        modifier
            // 2026-08-22 P2-B1（用户实测反馈）：白/灰按「月份奇偶」交替，与滚动位置无关。
            // 上一版按 inMonth（= 是否为标题月）染色，标题一跳整屏翻转 —— 那是翻页时代的遗留概念。
            // Lifebear：一个月白、一个月灰，下划不改。今天格用日号黑方块标识即可，不再整格染灰。
            .background(if (dt.monthValue % 2 == 0) DimBg else Color.White)
            .drawBehind {
                drawLine(hairColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                drawLine(hairColor, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
            }
            .then(if (isSelected) Modifier.border(1.4.dp, Ink) else Modifier)
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = true, color = Ink),
                onClick = onSelect, onLongClick = onLongPress
            )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            // 日号（今天=黑方块白字，Lifebear 式）+ 农历/节日
            Row(
                Modifier.fillMaxWidth().height(17.dp).padding(top = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isToday) {
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Ink),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${dt.dayOfMonth}", fontSize = 10.5.sp,
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // 连续滚动下月界要看得见：每月 1 号带上月份
                    Text(
                        if (dt.dayOfMonth == 1) tr("{0}月{1}", dt.monthValue, 1) else "${dt.dayOfMonth}",
                        fontSize = if (dt.dayOfMonth == 1) 10.5.sp else 11.5.sp, color = numTint,
                        fontWeight = if (isSelected || dt.dayOfMonth == 1) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Clip
                    )
                }
                Spacer(Modifier.weight(1f))
                if (lunar != null) Text(
                    lunar.cellText, fontSize = 8.sp, color = lunarTint,
                    maxLines = 1, overflow = TextOverflow.Clip
                )
            }

            var budget = maxLines
            val inlineStamps = stampList.filter { it.posX < 0f }
            if (inlineStamps.isNotEmpty() && budget > 1) {
                val ctx2 = androidx.compose.ui.platform.LocalContext.current
                Row(
                    Modifier.height(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    inlineStamps.take(5).forEach { st ->
                        val bmp = if (st.assetId.isNotBlank()) StampAssets.bitmap(ctx2, st.assetId) else null
                        if (bmp != null) {
                            Image(bmp, null, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(1.dp))
                        } else {
                            Text(st.emoji, fontSize = 8.5.sp, maxLines = 1)
                        }
                    }
                }
                budget--
            }
            // S2/S3（§64）：字号三档（大11.5/中10/小8，行高随动）
            val ctxFs = androidx.compose.ui.platform.LocalContext.current
            val fsTier = com.looka.app.data.Prefs.eventTextSize(ctxFs)
            val evFs = when (fsTier) { 0 -> 11.5.sp; 1 -> 10.sp; else -> 8.sp }
            val evLh = when (fsTier) { 0 -> 14.sp; 1 -> 12.sp; else -> 10.sp }
            var shown = 0
            // §6.1：绑定印章的日程已由印章上的气泡表达，不再在格内重复出一条普通事件块
            val bubbleSids = remember(stampList) {
                stampList.filter { it.posX >= 0f }.mapNotNull { boundOf(it)?.first }.toSet()
            }
            for (o in occList) {
                if (shown >= budget) break
                if (o.seriesId in bubbleSids) continue
                EventLine(o, catColorMap[o.categoryId] ?: Color(0xFF9AA0A6), evFs, evLh)
                shown++
            }
            for (e in sysList) {
                if (shown >= budget) break
                Text(
                    "◦${e.title}", fontSize = evFs, color = Color(e.color),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = evLh,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp)
                )
                shown++
            }
            for (t in taskList) {
                if (shown >= budget) break
                // §73：未完成=○ 完成=✓ —— 之前恒为✓，未完成的看着像已完成，还与日程难区分
                Text(
                    (if (t.done) "✓" else "○") + t.title, fontSize = evFs,
                    color = listColorMap[t.listUid] ?: GrayText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = evLh,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp)
                )
                shown++
            }
        }

        // ── Sticker Canvas v1（§68 二 + §71 S 手感批）──────────────────
        // 视觉主体 0.50×cellW（§71 用户对比 BEAR 拍板，原 0.42 偏小）；
        // 长按进入拖动态（虚线圈 ≈1.02×cellW，视觉≠交互框）；
        // 落点 = 圈心命中哪格，day 就换到哪天（列/行偏移纯数学换算）。
        val placed = stampList.filter { it.posX >= 0f }
        if (placed.isNotEmpty()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val cellWpx = constraints.maxWidth.toFloat()
                val cellHpx = constraints.maxHeight.toFloat()
                // §72 尺寸 Token（母档 §2.2，以列宽 Wd 为基准，禁止硬编码 px）
                val visualDp = maxWidth * 0.47f    // StickerVisualSize 0.42–0.47 取上限
                val ringDp = maxWidth * 1.08f      // StickerDragRing 1.05–1.12
                val hitDp = maxWidth * 0.95f       // §3 视觉≠交互：命中远大于视觉（收在列宽内避免抢邻格）
                val hitPx = with(LocalDensity.current) { hitDp.toPx() }
                val ctxSt = androidx.compose.ui.platform.LocalContext.current
                placed.forEach { st ->
                    var dragOff by remember(st.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    var dragging by remember(st.id) { mutableStateOf(false) }
                    var rootPos by remember(st.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    val baseX = st.posX * cellWpx
                    val baseY = st.posY * cellHpx
                    val bound = boundOf(st)?.second
                    Box(
                        Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(
                                (baseX + dragOff.x - hitPx / 2f).toInt(),
                                (baseY + dragOff.y - hitPx / 2f).toInt()) }
                            .size(hitDp)
                            .zIndex(if (dragging) 30f else 3f)
                            .onGloballyPositioned {
                                rootPos = it.positionInRoot() +
                                    androidx.compose.ui.geometry.Offset(hitPx / 2f, hitPx / 2f)
                            }
                            .pointerInput(st.id) {
                                // §4.2：短点 + touchSlop 直接拖 —— 母档明确「不建议把长按设为移动的前置条件」。
                                // 旧版 detectDragGesturesAfterLongPress 正是用户反馈"拖曳不灵敏"的根因。
                                detectDragGestures(
                                    onDragStart = { dragging = true; onStampDrag(true) },
                                    onDrag = { change, amt -> change.consume(); dragOff += amt },
                                    onDragCancel = {
                                        dragging = false; onStampDrag(false)
                                        dragOff = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDragEnd = {
                                        dragging = false; onStampDrag(false)
                                        val fx = baseX + dragOff.x
                                        val fy = baseY + dragOff.y
                                        val colOff = kotlin.math.floor(fx / cellWpx).toInt()
                                        val rowOff = kotlin.math.floor(fy / cellHpx).toInt()
                                        val newDay = day + colOff + rowOff * 7L
                                        val px = (fx - colOff * cellWpx) / cellWpx
                                        val py = (fy - rowOff * cellHpx) / cellHpx
                                        dragOff = androidx.compose.ui.geometry.Offset.Zero
                                        // §4.1：Reposition 松手直接固定，不再自动弹菜单
                                        onStampMove(st.id, newDay, px, py)
                                    }
                                )
                            }
                            .plainClick { onStampTap(st, rootPos) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (dragging) Canvas(Modifier.size(ringDp)) {
                            drawCircle(
                                color = Color(0xFF3A3A3A),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                            )
                        }
                        val bmp = if (st.assetId.isNotBlank()) StampAssets.bitmap(ctxSt, st.assetId) else null
                        if (bmp != null) Image(bmp, null, modifier = Modifier.size(visualDp))
                        else Text(st.emoji, fontSize = (visualDp.value * 0.7f).sp)
                        // §6 复合对象：绑定日程后在印章上方浮一枚灰色标题气泡，随印章一起移动
                        if (bound != null) Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = hitDp / 2f - visualDp * 0.78f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF8A8F8E))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        ) {
                            Text(
                                bound, fontSize = 8.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 溢出：右下角折角 +N（Lifebear 式）—— 按实际已显示条数计算
        val total = occList.size + sysList.size + taskList.size
        val budgetForItems = maxLines - (if (stampList.isNotEmpty()) 1 else 0)
        val rest = total - budgetForItems.coerceAtLeast(0)
        if (rest > 0) {
            Box(Modifier.align(Alignment.BottomEnd).size(17.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = Path().apply {
                        moveTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(p, Color(0xCC8A8F8E))
                }
                Text(
                    "+$rest", fontSize = 6.5.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 1.dp)
                )
            }
        }
    }
}

// ==================== 日详情抽屉 ====================

@Composable
private fun DaySheet(
    vm: LookaViewModel,
    nav: NavHostController,
    day: Long,
    occList: List<Occ>,
    sysList: List<SysCal.SysEvent>,
    taskList: List<Task>,
    diary: com.looka.app.data.Diary?,
    stampList: List<Stamp>,
    catColorMap: Map<Long, Color>,
    listColorMap: Map<String, Color>,
    reminderSeriesIds: Set<Long>,
    onOpenSys: (SysCal.SysEvent) -> Unit
) {
    var delStamp by remember { mutableStateOf<Stamp?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val showLunarSheet = remember(com.looka.app.util.I18n.lang) {
        Prefs.showLunarRaw(ctx) ?: com.looka.app.util.I18n.isZh()
    }
    val lunar = if (showLunarSheet) LunarCal.of(day) else null

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
        // 头部：日期 + 农历/节日 + 写日记 + 添加日程（Lifebear 的「📖+」）
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Fmt.dateCn(day).substringBefore("("),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "（${Fmt.weekFull(Fmt.d(day).dayOfWeek.value)}）",
                        fontSize = 13.sp, color = GrayText,
                        modifier = Modifier.padding(start = 2.dp, bottom = 1.dp)
                    )
                }
                Row {
                    // 副标题只出一个：有节日显示节日，否则显示农历
                    val sub = lunar?.festival ?: lunar?.full
                    if (sub != null) Text(
                        sub, fontSize = 11.sp,
                        color = if (lunar?.festival != null) HolidayRed else GrayText
                    )
                    if (day == Fmt.today()) {
                        Text(
                            (if (sub != null) " · " else "") + tr("今天"), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        // M1（§63）：打开即内容 —— 四入口排已删（Lifebear：点日期直接看当天，新增走底部 ＋）
        // 注意：不再因为"这天什么都没有"就整段换成空状态插画 ——
        // 日记邀请那一行必须一直够得着（见下方 item），否则空日子反而无从下手。
        val nothing = occList.isEmpty() && sysList.isEmpty() && taskList.isEmpty() &&
                diary == null && stampList.isEmpty()
        run {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 84.dp)) {
                // M2（§63）：一行灰字（Lifebear 式），且指向真实的底部 ＋（原文案指右上角是错的）
                if (nothing) item {
                    Text(
                        tr("这一天还没有安排 · 点下方 ＋ 添加"),
                        fontSize = 13.sp, color = GrayText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp)
                    )
                }
                items(occList) { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClick { nav.navigate("detail/${o.seriesId}/${o.occurrenceDay}") }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.width(52.dp)) {
                            if (o.allDay) {
                                Text(tr("全天"), fontSize = 13.sp, color = GrayText)
                            } else {
                                Text(Fmt.hm(o.startMin), fontSize = 13.sp)
                                Text(Fmt.hm(o.endMin), fontSize = 11.sp, color = GrayText)
                            }
                        }
                        Box(
                            Modifier.size(11.dp).clip(CircleShape)
                                .background(catColorMap[o.categoryId] ?: Color(0xFF9AA0A6))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                o.title, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (o.location.isNotBlank()) {
                                Text(o.location, fontSize = 11.sp, color = GrayText, maxLines = 1)
                            }
                        }
                        if (o.seriesId in reminderSeriesIds) {
                            Icon(
                                Icons.Outlined.NotificationsNone, null,
                                tint = GrayText, modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Hairline(Modifier.padding(start = 18.dp))
                }
                items(sysList) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClick { onOpenSys(e) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.width(52.dp)) {
                            Text(
                                if (e.allDay) tr("全天") else Fmt.hm(e.startMin),
                                fontSize = 13.sp, color = GrayText
                            )
                        }
                        Box(
                            Modifier.size(11.dp).clip(CircleShape)
                                .border(2.dp, Color(e.color), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            e.title, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(tr("系统"), fontSize = 10.sp, color = GrayText)
                    }
                    Hairline(Modifier.padding(start = 18.dp))
                }
                items(taskList) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("任务"), fontSize = 12.sp, color = GrayText, modifier = Modifier.width(52.dp))
                        // §73：与待办页同款 —— 未完成圆圈 / 完成圆圈里打勾（旧版未完成用日历图标，像日程）
                        Icon(
                            if (t.done) Icons.Default.CheckCircle
                            else Icons.Default.RadioButtonUnchecked,
                            if (t.done) tr("取消完成") else tr("完成"),
                            tint = if (t.done) MaterialTheme.colorScheme.primary
                            else (listColorMap[t.listUid] ?: Color(0xFFC0C3C0)),
                            modifier = Modifier.size(18.dp).plainClick { vm.toggleTask(t) }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t.title, fontSize = 15.sp,
                            color = if (t.done) GrayText else Ink,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Hairline(Modifier.padding(start = 18.dp))
                }
                // 日记：无论有没有都常驻一行（对齐 Lifebear 实机 ——
                // 它的日详情面板永远挂着「メモなど自由に書いてみましょう ✏️」）。
                // 空日子给一句邀请，比什么都不显示更容易让人写下第一笔。
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClick { nav.navigate("diary/$day") }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("日记"), fontSize = 12.sp, color = GrayText, modifier = Modifier.width(52.dp))
                        if (diary != null) {
                            Text(
                                com.looka.app.data.MOOD_EMOJIS[diary.mood.coerceIn(0, 4)],
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                diary.content.replace("\n", " ").take(30).ifBlank { tr("（空白日记）") },
                                fontSize = 13.sp, color = GrayText,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                tr("随便写点什么吧 ✎"),
                                fontSize = 13.sp, color = Color(0xFFB9BBB9),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Hairline(Modifier.padding(start = 18.dp))
                }
                if (stampList.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("表情"), fontSize = 12.sp, color = GrayText, modifier = Modifier.width(52.dp))
                            stampList.forEach { s ->
                                val bound = vm.stampSeries(s)
                                val bmp = if (s.assetId.isNotBlank()) StampAssets.bitmap(ctx, s.assetId) else null
                                val mod = Modifier
                                    .padding(end = 8.dp)
                                    .combinedClickable(
                                        onClick = {
                                            bound?.let { se ->
                                                nav.navigate("detail/${se.id}/${se.startDay}")
                                            }
                                        },
                                        onLongClick = { delStamp = s }
                                    )
                                if (bmp != null) {
                                    Image(bmp, StampAssets.def(ctx, s.assetId)?.name(), modifier = mod.size(30.dp))
                                } else {
                                    Text(s.emoji, fontSize = 19.sp, modifier = mod)
                                }
                            }
                            Text(tr("长按删除"), fontSize = 10.sp, color = GrayText)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    delStamp?.let { s ->
        val name = if (s.assetId.isNotBlank()) StampAssets.def(ctx, s.assetId)?.name() ?: "" else s.emoji
        ConfirmDialog(
            title = tr("删除表情 {0}？", name),
            onConfirm = { vm.deleteStamp(s.id); delStamp = null },
            onDismiss = { delStamp = null }
        )
    }
}

/** 月格内的日程行：全天=色块、时间=彩字（Lifebear 式信息密度） */
@Composable
private fun EventLine(o: Occ, color: Color, fs: androidx.compose.ui.unit.TextUnit = 8.sp,
                      lh: androidx.compose.ui.unit.TextUnit = 10.sp) {
    if (o.allDay) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 1.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .padding(horizontal = 2.dp)
        ) {
            Text(
                // Lifebear 盘含大量亮色（黄/黄绿/浅青），白字会隐形 —— 按底色亮度自动选
                o.title, fontSize = fs, color = onColor(color), lineHeight = lh,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Text(
            o.title, fontSize = fs, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = lh,
            modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp)
        )
    }
}

// ==================== 视图 / 操作菜单（CAL-070，2026-08-21 对齐 Lifebear 抽屉） ====================

/** 日历图标（数字随视图变 31/7/1，Lifebear 式）：自绘，避免依赖 extended 图标库 */
@Composable
fun CalendarGlyph(num: String, tint: Color = Ink, size: androidx.compose.ui.unit.Dp = 23.dp) {
    Box(
        Modifier
            .size(size)
            .border(1.6.dp, tint, RoundedCornerShape(4.dp))
            .padding(top = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        // 顶部"装订条"
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(2.5.dp)
                .padding(horizontal = 3.dp)
                .background(tint)
        )
        Text(
            num, fontSize = (size.value * 0.42f).sp, color = tint,
            fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.42f).sp
        )
    }
}

@Composable
fun ViewMenuSheet(
    vm: LookaViewModel,
    nav: NavHostController,
    onJump: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            // 顶部搜索框（Lifebear 式）：点击进搜索页
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DimBg)
                    .plainClick { onDismiss(); nav.navigate("search") }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = GrayText, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("搜索日程、任务、笔记…"), fontSize = 14.sp, color = GrayText)
            }
            Spacer(Modifier.height(6.dp))
            // 月/周/日：带日历图标，当前项整行灰底（不是右侧打勾）
            listOf(tr("月") to "31", tr("周") to "7", tr("日") to "1").forEachIndexed { i, (label, num) ->
                val active = vm.calView == i
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) DimBg else Color.Transparent)
                        .plainClick {
                            vm.calView = i
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CalendarGlyph(num, size = 21.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        label, fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            Hairline()
            NavRow(tr("跳转到日期"), icon = Icons.Outlined.Event) { onDismiss(); onJump() }
            NavRow(tr("显示设置"), icon = Icons.Outlined.Tune) { onDismiss(); nav.navigate("calSettings") }
        }
    }
}


/** 四模式快捷键（§58）：图标 + 小字，一排等宽 —— 弹出面板的节奏学 Lifebear */
@Composable
private fun QuickAction(emoji: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).plainClick(onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(emoji, fontSize = 20.sp)
        Text(label, fontSize = 10.5.sp, color = GrayText)
    }
}

// ==================== N1（§71）创建面板：Lifebear 式底部停靠 ====================

/**
 * 月视图 ＋ 弹出的停靠面板：顶部三模式图标（日程/任务/表情），日历在上方保持可见可点。
 * 表情模式 = 连续盖章：点选贴纸后点日历日期直接贴，面板不收起。
 */
@Composable
private fun CreatePanel(
    vm: LookaViewModel,
    nav: NavHostController,
    onClose: () -> Unit,
    onDragCreate: ((String, androidx.compose.ui.geometry.Offset, Int) -> Unit)? = null
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val pickerPreview = (cfg.screenWidthDp / 7f * 0.60f).dp
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var evTitle by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var taskTitle by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    val densityP = LocalDensity.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color.White)
            .navigationBarsPadding()
            .imePadding()
            // §11：把面板高度回报给日历，作为 viewport bottomInset（不压缩格子）
            .onGloballyPositioned { vm.panelInset = with(densityP) { it.size.height.toDp() } }
    ) {
        // 顶部模式行（对齐 Lifebear：图标在面板顶部，选中浅灰圆）
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelIcon(Icons.Outlined.Event, tr("日程"), mode == 0) { mode = 0; vm.stampSel = "" }
            PanelIcon(Icons.Outlined.TaskAlt, tr("任务"), mode == 1) { mode = 1; vm.stampSel = "" }
            PanelIcon(Icons.Outlined.Mood, tr("表情"), mode == 2) { mode = 2 }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(Icons.Default.Close, tr("关闭"), tint = GrayText)
            }
        }
        Hairline()

        // §73：三个模式内容区等高（以表情 tab 为基准）—— 切 tab 面板不跳变
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.TopStart) {
        when (mode) {
            0 -> Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextField(
                        value = evTitle, onValueChange = { evTitle = it },
                        placeholder = { Text(tr("日程名"), fontSize = 16.sp, color = Color(0xFFB9BBB9)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                        colors = com.looka.app.ui.common.clearFieldColors(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    com.looka.app.ui.common.SaveButton(enabled = evTitle.isNotBlank()) {
                        vm.prepareCreateDraft(vm.selectedDay)
                        vm.draft?.let { d ->
                            d.title = evTitle.trim()
                            vm.saveCreate(d) { com.looka.app.ui.common.toast(ctx, tr("已保存")) }
                        }
                        vm.draft = null
                        evTitle = ""
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Fmt.dateCn(vm.selectedDay), fontSize = 12.sp, color = GrayText)
                    Text(tr("（点日历改日期）"), fontSize = 11.sp, color = Color(0xFFB9BBB9))
                    Spacer(Modifier.weight(1f))
                    Text(
                        tr("详细设置"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick {
                            vm.prepareCreateDraft(vm.selectedDay)
                            vm.draft?.title = evTitle.trim()
                            onClose()
                            nav.navigate("editor")
                        }.padding(6.dp)
                    )
                }
            }
            1 -> Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextField(
                        value = taskTitle, onValueChange = { taskTitle = it },
                        placeholder = { Text(tr("任务名"), fontSize = 16.sp, color = Color(0xFFB9BBB9)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                        colors = com.looka.app.ui.common.clearFieldColors(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    com.looka.app.ui.common.SaveButton(enabled = taskTitle.isNotBlank()) {
                        vm.addTask(taskTitle.trim(), vm.selectedDay, "")
                        com.looka.app.ui.common.toast(ctx, tr("已添加任务"))
                        taskTitle = ""
                    }
                }
                Text(
                    tr("截止 {0}", Fmt.dateCn(vm.selectedDay)),
                    fontSize = 12.sp, color = GrayText,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
            else -> Column {
                Text(
                    if (vm.stampSel.isBlank()) tr("选一枚贴纸")
                    else tr("点日历上的日期，贴上去 ↑ 可以连续贴"),
                    fontSize = 12.sp,
                    color = if (vm.stampSel.isBlank()) GrayText else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                com.looka.app.ui.common.StickerPicker(
                    selected = vm.stampSel,
                    onSelect = { vm.stampSel = if (vm.stampSel == it) "" else it },
                    onDragCreate = onDragCreate,
                    // §2.2/§11.1 双尺度：Picker 预览 0.60×Wd，比日历里的最终尺寸大，便于识别
                    previewSize = pickerPreview
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        }
    }
}

@Composable
private fun PanelIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, selected: Boolean, onClick: () -> Unit
) {
    Box(
        Modifier.padding(horizontal = 4.dp).size(38.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) com.looka.app.ui.theme.PanelBg else Color.Transparent)
            .plainClick(onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(icon, label, tint = if (selected) Ink else GrayText,
            modifier = Modifier.size(22.dp))
    }
}

// ==================== §72 §5：印章上下文 Popover ====================

/**
 * 母档 §5.1：轻交互不用重 Dialog。白底、细边框、轻圆角、两等分操作、中央分隔线、下方 caret；
 * **无全屏遮罩**（点空白处关闭，但不压暗背景）。尺寸以列宽 Wd 为基准：宽 3.2×Wd、高 0.62×Wd。
 * 两态（§5 / §9.1）：未绑定 = 登记日程 / 删除；已绑定 = 编辑 / 删除。
 */
@Composable
private fun StickerPopover(
    anchor: androidx.compose.ui.geometry.Offset,
    bound: Boolean,
    onPrimary: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wd = maxWidth / 7f
        val popW = wd * 3.2f
        val popH = wd * 0.62f
        val caretH = 5.dp
        val screenW = with(density) { maxWidth.toPx() }
        val popWpx = with(density) { popW.toPx() }
        // Anchor 到印章上方；靠边时 clamp（§5.1「靠边时需要翻转或 clamp」）
        val left = (anchor.x - popWpx / 2f).coerceIn(8f, (screenW - popWpx - 8f).coerceAtLeast(8f))
        val topPx = anchor.y - with(density) { (popH + caretH).toPx() } -
            with(density) { (wd * 0.30f).toPx() }
        val flipped = topPx < with(density) { 8.dp.toPx() }
        val finalTop = if (flipped) anchor.y + with(density) { (wd * 0.30f).toPx() } else topPx

        // 关闭层：透明、不压暗（§5.1 无全屏遮罩）
        Box(Modifier.fillMaxSize().plainClick(onDismiss))

        Column(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), finalTop.toInt()) }
                .zIndex(60f)
        ) {
            if (flipped) Caret(up = true, w = wd * 0.26f, h = caretH)
            Row(
                Modifier
                    .width(popW).height(popH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .border(0.8.dp, Color(0xFFDCDFDC), RoundedCornerShape(6.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().plainClick(onPrimary),
                    contentAlignment = Alignment.Center) {
                    Text(
                        if (bound) tr("编辑") else tr("登记日程"),
                        fontSize = 13.sp, color = Ink
                    )
                }
                Box(Modifier.width(0.8.dp).fillMaxHeight().background(Color(0xFFDCDFDC)))
                Box(Modifier.weight(1f).fillMaxHeight().plainClick(onDelete),
                    contentAlignment = Alignment.Center) {
                    Text(tr("删除"), fontSize = 13.sp, color = HolidayRed)
                }
            }
            if (!flipped) Caret(up = false, w = wd * 0.26f, h = caretH)
        }
    }
}

/** Popover 指示小三角（§5.1 caret） */
@Composable
private fun Caret(up: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.padding(start = w).size(w, h)) {
        val p = Path().apply {
            if (up) { moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height) }
            close()
        }
        drawPath(p, Color.White)
        drawPath(p, Color(0xFFDCDFDC), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f))
    }
}

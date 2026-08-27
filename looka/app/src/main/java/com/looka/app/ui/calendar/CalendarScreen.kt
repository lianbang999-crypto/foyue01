@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.looka.app.ui.calendar

import com.looka.app.ui.common.DlgTitle
import com.looka.app.ui.theme.LkIcons

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
import androidx.compose.ui.draw.alpha
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
    // §98 H4：日历也改**页内**搜索 —— 月历是网格没法原地过滤，搜索态就用结果列表盖住网格，
    // 但人还在日历 tab 里，不跳页（与待办、笔记同一套模型）
    var searching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchQ by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var jumpOpen by remember { mutableStateOf(false) }
    var sysDetail by remember { mutableStateOf<SysCal.SysEvent?>(null) }
    // §72 §5/§9：印章 Popover（锚点 = 印章在根坐标系的中心）
    var stampMenu by remember { mutableStateOf<Pair<Stamp, androidx.compose.ui.geometry.Offset>?>(null) }
    // §76 F3：待确认删除的贴纸（second = 绑定日程标题，空则为纯装饰贴纸）
    var delStampConfirm by remember { mutableStateOf<Pair<Stamp, String>?>(null) }
    // §72 §D：屏幕坐标 → (hostDate, u, v)，由月视图注册
    val gridHit = remember { mutableStateOf<((androidx.compose.ui.geometry.Offset) -> Triple<Long, Float, Float>?)?>(null) }
    // §72 §4：从 Picker 拖出的 Ghost（assetId → 当前根坐标）
    var ghost by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Offset>?>(null) }

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

    // §76 F1：本层原点（Scaffold padding 会把它推离窗口原点）—— Ghost 与 Popover 都要靠它换算
    var rootOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
    // §75 C3：面板打开时几何完全不动 —— 旧版父层 padding 会让 rowH=maxHeight/6 直接缩水
    // （实测 Lifebear 开面板前后行高 232px 不变，只被覆盖）。让出底部交给 LazyColumn contentPadding。
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏：大字年月（点击跳转）+ 今天 + 小鹿 AI + 视图菜单
        // §126 C2（T-1）：皮肤包顶栏横幅带 —— 画在顶栏底下（Lifebear 图 114 形态），
        // 安全区约定见 THEME-SYSTEM 附录 A：右 2/3 浅色保日期文字可读。没装包 = 无此层。
        androidx.compose.foundation.layout.Box {
        com.looka.app.util.SkinPacks.active?.topBanner?.let {
            androidx.compose.foundation.Image(
                it, null,
                modifier = Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
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
            // §111：「今天」**从顶栏挪到右下角浮动按钮**（用户拍板，实机图 114）。
            // 原来的文字按钮在这里，代码见本文件底部 BackToTodayFab。
            // §71 A：AI 顶栏入口恢复（用户 2026-08-23 拍板推翻 §60 R3——全站要有方便调出的位置）
            IconButton(onClick = { nav.navigate("aiChat") }, modifier = Modifier.size(40.dp)) {
                com.looka.app.ui.common.DeerBadge(24.dp)
            }
            // 顶栏收敛（2026-08-21 对齐 Lifebear）：日历图标（数字随视图变 31/7/1）收拢 跳转/搜索/显示设置
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                CalendarGlyph(when (vm.calView) { 0 -> "31"; 1 -> "7"; else -> "1" })
            }
        }
        }   // §126 C2：顶栏横幅 Box 收口

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

        // §98 H4：搜索态盖住整个视图区（月/周/日都一样），人还在日历 tab
        if (searching) {
            CalendarSearchPane(
                q = searchQ, onQ = { searchQ = it },
                onExit = { searching = false; searchQ = "" },
                series = series, cats = cats, nav = nav
            )
            return@Column
        }

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
                    // §74 P0-5/6：必须走 collectAsState 的 series（响应式），不能读 .value 快照。
                    // §75 M1 起仅用于月格去重（绑定日程不再出普通事件条），气泡已删，不再受设置开关控制
                    if (st.eventUid.isBlank()) null
                    else series.find { it.uid == st.eventUid }?.let { it.id to it.title }
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

    // §111：**回到今天 —— 需要时才出现，且落在拇指区。**
    //
    // 实机图 114 抓到的细节：滚动离开当前月后，右下角浮出一颗白色圆角方按钮
    // （量得 ≈33dp、距屏右 16dp、U 形回弯箭头），滚回当月就消失。
    // 它同时做对两件事：**不用时不占位**（克制）、**落在拇指区**（人性化）——
    // 顶栏右上角在 6.7 吋手机上单手够不到。
    //
    // 显示条件里带上 `!sheetOpen`，是照实机来的：图 112 面板开着时没有这颗按钮，
    // 图 114 面板收起后才出现。这样也顺带保证**它永远不会压在日详情面板上**。
    // 代价：面板开着时没有回今天的入口 —— 实机也一样，把面板划下去即可。
    androidx.compose.animation.AnimatedVisibility(
        visible = !sheetOpen && (vm.calMonth != YearMonth.now() || vm.selectedDay != Fmt.today()),
        enter = androidx.compose.animation.fadeIn(tween(180)) +
                androidx.compose.animation.scaleIn(tween(180), initialScale = 0.85f),
        exit = androidx.compose.animation.fadeOut(tween(140)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
            .zIndex(40f)
    ) {
        Box(
            // 视觉 36dp（实机量得 ≈33dp），但外面套到 48dp 触控区 ——
            // 33dp 低于 Android 最小可触目标 48dp，照抄尺寸会难点中。
            Modifier.size(48.dp).plainClick {
                vm.selectedDay = Fmt.today()
                vm.calMonth = YearMonth.now()
                vm.calScrollReq = Fmt.today()   // 连续滚动模式下要真的滚回去
            },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                color = Color.White,
                shadowElevation = 3.dp,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        LkIcons.ReturnToday, tr("回到今天"),
                        tint = Ink, modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // §72 §4：拖拽创建的 Ghost —— 跟手 1:1、固定虚线交互圈、不放大不旋转（§10）
    ghost?.let { (assetId, pos) ->
        val cfgG = androidx.compose.ui.platform.LocalConfiguration.current
        val wd = cfgG.screenWidthDp / 7f
        // §89 U1：0.42 是母档的**视觉主体**目标，但我们的素材是 256 画布装 218 主体（安全区，
    // 实测三套 84.8–85.9%）。把 0.42 套在画布上，视觉只剩 0.358×Wd —— 比实机 0.43 小 17%。
    // 画布 0.50 × 0.8516 = 0.426 视觉，与实机对齐，也在母档 0.36–0.46 区间中段。
    // §97 G7：素材平均填充率实测 **78.7%**（§89 写的 84.8~85.9% 只取了最满的几张，
    // 04_sad 才 61.7%）。实机视觉 41.3%×Wd → 持平需 canvas 0.525；
    // §99 I2：用户定 **0.618**（视觉 48.6%，比实机 41.3% **大约 +18%**）—— 是要求不是对齐。
    val visual = (wd * 0.618f).dp
        val ring = (wd * 1.08f).dp
        val half = with(LocalDensity.current) { ring.toPx() / 2f }
        // §76 F1：pos 是窗口坐标，本层在 Scaffold padding 之下 —— 同样要减去容器原点才跟手
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(
                    (pos.x - rootOrigin.x - half).toInt(),
                    (pos.y - rootOrigin.y - half).toInt()) }
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
                    // §76 F2（图53）：「编辑」直接进全屏编辑页（不是只读详情），标题左带贴纸缩略图
                    vm.pendingStampAsset = st.assetId
                    scope.launch {
                        if (!vm.prepareEditDraft(boundSeries.id, st.day)) vm.pendingStampAsset = ""
                        else nav.navigate("editor")
                    }
                } else {
                    // §5.2：复用标准快速创建，印章与 hostDate 作为上下文带入
                    vm.prepareCreateDraft(st.day, allDay = true)
                    vm.pendingStampBind = st.id
                    vm.pendingStampAsset = st.assetId   // P0-4：贴纸缩略图进表单
                    nav.navigate("editor")
                }
            },
            // §76 F3（图52）：删除是复合删除 —— 先确认，再把贴纸与绑定日程一起删
            onDelete = { delStampConfirm = st to (boundSeries?.title ?: ""); stampMenu = null },
            onDismiss = { stampMenu = null }
        )
    }

    // §76 F3（图52「予定の削除」）：贴纸与绑定日程是一个复合对象，删就一起删，先问一句
    delStampConfirm?.let { (st, boundTitle) ->
        ConfirmDialog(
            title = if (boundTitle.isNotBlank()) tr("删除这条日程？") else tr("删除这个贴纸？"),
            text = if (boundTitle.isNotBlank())
                tr("「{0}」和这个贴纸会一起删除。", boundTitle) else tr("贴纸会从这一天移除。"),
            confirmText = tr("删除"),
            onConfirm = { vm.deleteStampComposite(st.id); delStampConfirm = null },
            onDismiss = { delStampConfirm = null }
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
                        val hit = gridHit.value?.invoke(pos)
                        if (hit == null) {
                            ghost = null
                        } else {
                            val (d, u, v) = hit
                            vm.addStamp("🦌", d, assetId = assetId, px = u, py = v) { newId ->
                                // §74 P0-1：Ghost 持留到实例真正落库并进入渲染流后再撤 ——
                                // Drop 完成 ≠ 暂时隐藏 Sticker（旧版先撤 ghost 后落库，有一段"贴纸消失"的帧隙）
                                ghost = null
                                // §4.1 New Drop：首次放置后自动弹语义确认（AC-004）。
                                // P0-3：命中修正后，落点即实例中心，caret 天然指向贴纸
                                stampMenu = com.looka.app.data.Stamp(
                                    id = newId, emoji = "🦌", day = d,
                                    assetId = assetId, posX = u, posY = v
                                ) to pos
                            }
                        }
                    }
                    else -> ghost = null
                }
            }
        )
    }
    }

    if (menuOpen) ViewMenuSheet(vm, nav, onJump = { jumpOpen = true },
        onSearch = { searching = true }, onDismiss = { menuOpen = false })
    if (jumpOpen) LookaDatePicker(
        initialDay = vm.selectedDay,
        onPick = { d ->
            vm.selectedDay = d
            vm.calMonth = YearMonth.from(Fmt.d(d))
            vm.calScrollReq = d
        },
        onDismiss = { jumpOpen = false }
    )

    // §114 P16：系统日历详情从「只读说明」升级为可编辑/删除（用户拍板：
    // 进了 Looka 就和自己的日程一样）。重复事件首版拦下 —— Events 一行是整个系列。
    var sysDetailInfo by remember { mutableStateOf<SysCal.SysEventDetail?>(null) }
    var sysDelConfirm by remember { mutableStateOf<SysCal.SysEvent?>(null) }
    var sysPendingEdit by remember { mutableStateOf(false) }   // 授权后恢复原意图
    LaunchedEffect(sysDetail) {
        sysDetailInfo = null
        sysDetail?.let { sysDetailInfo = SysCal.eventDetail(ctx, it.id) }
    }
    fun openSysEditor(e: SysCal.SysEvent) {
        vm.editorSysEvent = e
        vm.prepareCreateDraft(e.day)
        vm.editorInitMode = 0
        sysDetail = null
        nav.navigate("editor")
    }
    val writeCalLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val e = sysDetail
        if (granted && sysPendingEdit && e != null) openSysEditor(e)
        else if (!granted) com.looka.app.ui.common.toast(ctx, tr("没有日历写入权限，改不了系统日程"))
        sysPendingEdit = false
    }
    sysDetail?.let { e ->
        AlertDialog(
            onDismissRequest = { sysDetail = null },
            title = { DlgTitle(e.title) },
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
                    if (sysDetailInfo?.recurring == true) Text(
                        tr("这是重复日程，请在系统日历中管理"),
                        fontSize = 11.sp, color = GrayText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                if (sysDetailInfo?.recurring != true) {
                    TextButton(onClick = { sysDelConfirm = e }) {
                        Text(tr("删除"), color = Ink, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = {
                        if (SysCal.hasWritePermission(ctx)) openSysEditor(e)
                        else {
                            sysPendingEdit = true
                            writeCalLauncher.launch(android.Manifest.permission.WRITE_CALENDAR)
                        }
                    }) { Text(tr("编辑"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                } else {
                    TextButton(onClick = { sysDetail = null }) {
                        Text(tr("好的"), color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            containerColor = Color.White
        )
    }

    // §114 P16：系统日程删除 —— 二次确认后写回 ContentResolver；成功 bump settingsVersion
    // 让 produceState 重拉 sysEvents。无写权限时先请求，授权即执行（恢复原意图）。
    sysDelConfirm?.let { e ->
        val delLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) scope.launch {
                if (SysCal.deleteEvent(ctx, e.id)) {
                    vm.settingsVersion++
                    com.looka.app.ui.common.toast(ctx, tr("已删除"))
                } else com.looka.app.ui.common.toast(ctx, tr("删除失败，系统日历拒绝了"))
                sysDelConfirm = null; sysDetail = null
            } else {
                com.looka.app.ui.common.toast(ctx, tr("没有日历写入权限，删不了系统日程"))
                sysDelConfirm = null
            }
        }
        ConfirmDialog(
            title = tr("删除这条系统日程？"),
            text = tr("「{0}」将从系统日历里删除，其他使用该日历的应用也会看不到它", e.title),
            onConfirm = {
                if (SysCal.hasWritePermission(ctx)) {
                    scope.launch {
                        if (SysCal.deleteEvent(ctx, e.id)) {
                            vm.settingsVersion++
                            com.looka.app.ui.common.toast(ctx, tr("已删除"))
                        } else com.looka.app.ui.common.toast(ctx, tr("删除失败，系统日历拒绝了"))
                        sysDelConfirm = null; sysDetail = null
                    }
                } else delLauncher.launch(android.Manifest.permission.WRITE_CALENDAR)
            },
            onDismiss = { sysDelConfirm = null }
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
                        // §98 H7：实测 Lifebear 字面高 35px vs 我们 31px —— 小了 12%
                        Fmt.week(dow), fontSize = 12.5.sp,
                        color = weekdayTint(dow, holidayMask) ?: GrayText,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                }
            }
            Hairline()

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 固定行高：一屏约 6 周（Lifebear 密度）。不能再由"月行数"反推 —— 那是翻页模式的产物
                // §98 H7：行高改**固定值**。原来 maxHeight/6 是强行让 6 行正好铺满屏幕 ——
                // 那是翻页时代的算法；连续滚动下没有「一屏一个月」的概念，行高却随设备高度变。
                // 实测 Lifebear 同机行高 313px = 99.4dp，网格区放得下 6.4 行（边缘露半行）。
                val rowH = 100.dp
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
                LaunchedEffect(gridRoot, gridSize, origin) {
                    onGridReady { p ->
                        val rx = p.x - gridRoot.x
                        val ry = p.y - gridRoot.y
                        if (gridSize.width <= 0 || rx < 0f || ry < 0f ||
                            rx > gridSize.width || ry > gridSize.height) null
                        else {
                            // §74 P0-2：不再用 rowHpx 浮点算式反推行号（面板 inset 重排网格时
                            // 存在陈旧窗口，实测把 12/17 算成 12/10 整整错一周）。
                            // 改问 LazyColumn 本帧的真实布局 —— 指针落在哪个 item 的范围里就是哪一周，
                            // 从构造上消灭取整/时序整类误差。
                            val hit = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { ry >= it.offset && ry < it.offset + it.size }
                            if (hit == null) null
                            else {
                                val colW = gridSize.width / 7f
                                val col = (rx / colW).toInt().coerceIn(0, 6)
                                Triple(
                                    origin + hit.index * 7L + col,
                                    ((rx - col * colW) / colW).coerceIn(0f, 1f),
                                    ((ry - hit.offset) / hit.size.toFloat()).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    // §8：拖动印章时锁滚动，避免"想挪印章却把整月滚走"
                    userScrollEnabled = !vm.stampDragging,
                    // §75 C3：面板打开只让出可滚动余量，格子几何不动（对齐实机：行高不变，被覆盖）
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = if (vm.createPanel) vm.panelInset else 0.dp
                    ),
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        gridRoot = it.positionInRoot(); gridSize = it.size
                    }
                ) {
                    items(TOTAL_WEEKS, key = { it }) { wi ->
                        val ws = origin + wi * 7L
                        // P2-B2：水印叠在格子「之上」（之前 drawBehind 被格子不透明底色盖住，
                        // 只有溢出到上一行的顶部 30% 漏出来）。低透明度 + 一行内装得下。
                        val wmMonth = (0..6).map { Fmt.d(ws + it) }.firstOrNull { it.dayOfMonth == 15 }?.monthValue
                        // §115（用户实机反馈：贴纸被日期之间的线截断）：贴纸视觉主体是
                        // 0.618×列宽、以落点为中心居中画，贴到格子边缘时**必然溢出格子**。
                        // 而日格与周行都是不透明底（background + drawBehind 画网格线），
                        // 兄弟节点按声明顺序后画覆盖先画 —— 于是溢出的那半个贴纸被
                        // 右邻格 / 下一周行的底色齐刷刷切掉，看着就是"卡在线上被截断"。
                        // 贴纸自身的 zIndex(3f) 只在**它所在的日格内部**排序，跨不出格子。
                        // 这里给"本周有贴纸"的整行提一层，下面 DayCellV2 再给"本格有贴纸"提一层。
                        val weekHasStamp = (0..6).any { c0 ->
                            stampsByDay[ws + c0]?.any { it.posX >= 0f } == true
                        }
                        Box(Modifier.height(rowH).fillMaxWidth()
                            .zIndex(if (weekHasStamp) 1f else 0f)) {
                        Row(
                            Modifier.fillMaxSize()
                        ) {
                            for (c in 0 until 7) {
                                val day = ws + c
                                DayCellV2(
                                    // §115：本格有贴纸时提一层，横向溢出不再被右邻格底色切掉
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .zIndex(if (stampsByDay[day]?.any { it.posX >= 0f } == true) 2f else 0f),
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
            
                // §115（用户实机反馈）：这里原本还有一颗**文字版「回今天」黑胶囊**
                // （CAL-001 v1.1 旧实现），与 §111 的图标版都 align(BottomEnd) ——
                // 两颗叠在屏幕右下同一个位置。用户拍板留图标版（对齐 Lifebear 图 114），
                // 文字版在此删除。
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
            // §113 E1：选中日整格加浅灰底（实机图 02/30：浅灰底 + 黑描边 + 日号黑块三层同现）。
            // 之前只有描边，选中态在满屏白格里不够醒目。
            .background(
                if (isSelected) Color(0xFFEDEEED)
                else if (dt.monthValue % 2 == 0) DimBg else Color.White
            )
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
            // §98 H7：日号左边距实测 Lifebear 17px = 5.4dp，我们只有 11px = 3.5dp，贴太左
        Column(Modifier.fillMaxSize().padding(horizontal = 5.dp)) {
            // 日号（今天=黑方块白字，Lifebear 式）+ 农历/节日
            Row(
                Modifier.fillMaxWidth().height(17.dp).padding(top = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isToday) {
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(com.looka.app.ui.theme.TodayBlock),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${dt.dayOfMonth}", fontSize = 11.sp,
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // 连续滚动下月界要看得见：每月 1 号带上月份
                    Text(
                        if (dt.dayOfMonth == 1) tr("{0}月{1}", dt.monthValue, 1) else "${dt.dayOfMonth}",
                        // §98 H7：实测 Lifebear 日号字面 28px vs 我们 27px
                        fontSize = if (dt.dayOfMonth == 1) 11.sp else 12.sp, color = numTint,
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
            // §6.1：绑定印章的日程已由印章上的气泡表达，不再在格内重复出一条普通事件块。
            // §74 P0-6：不能 remember(stampList) —— 绑定关系还依赖 series 流，
            // series 晚一拍到达时会卡住旧集合（气泡不出+事件条不隐）。每帧直算，量级个位数，零成本。
            val bubbleSids = stampList.filter { it.posX >= 0f }
                .mapNotNull { boundOf(it)?.first }.toSet()
            for (o in occList) {
                if (shown >= budget) break
                if (o.seriesId in bubbleSids) continue
                EventLine(o, catColorMap[o.categoryId] ?: com.looka.app.ui.theme.EventFallback, evFs, evLh)
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
                // §89 U1：同上 —— 0.42 曾被误施于画布；素材含 15% 安全区，改 0.50 才得视觉 0.426
    val visualDp = maxWidth * 0.618f   // §97 G7：同上
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
                    // §75 D1（拖动代理，用户描述的 Lifebear 手感）：拖动时原位贴纸留在原地（半透明），
                    // 手指上跟一枚副本；松手确认落位后原位才消失（由 moveStamp 数据更新完成）
                    Box(
                        Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(
                                (baseX - hitPx / 2f).toInt(),
                                (baseY - hitPx / 2f).toInt()) }
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
                        // §75 M1（图42/45 实锤，推翻 §72 §6 读图）：月格里只有贴纸 ——
                        // 贴纸本身就是这条日程的视觉标识，不再浮标题气泡。
                        val bmp = if (st.assetId.isNotBlank()) StampAssets.bitmap(ctxSt, st.assetId) else null
                        // 原位本体：拖动时半透明留守
                        if (bmp != null) Image(
                            bmp, null,
                            modifier = Modifier.size(visualDp)
                                .alpha(if (dragging) 0.35f else 1f)
                        )
                        else Text(
                            st.emoji, fontSize = (visualDp.value * 0.7f).sp,
                            modifier = Modifier.alpha(if (dragging) 0.35f else 1f)
                        )
                        // 拖动代理副本：跟手 + 虚线圈
                        if (dragging) Box(
                            Modifier
                                .matchParentSize()
                                .offset { androidx.compose.ui.unit.IntOffset(
                                    dragOff.x.toInt(), dragOff.y.toInt()) },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(ringDp)) {
                                drawCircle(
                                    color = Color(0xFF3A3A3A),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                                )
                            }
                            if (bmp != null) Image(bmp, null, modifier = Modifier.size(visualDp))
                            else Text(st.emoji, fontSize = (visualDp.value * 0.7f).sp)
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
                                .background(catColorMap[o.categoryId] ?: com.looka.app.ui.theme.EventFallback)
                        )
                        Spacer(Modifier.width(12.dp))
                        // §75 M2（图42）：绑定贴纸的日程，缩略图出现在标题左 —— 列表里靠它认贴纸
                        val boundStamp = stampList.find { st ->
                            st.assetId.isNotBlank() && st.eventUid.isNotBlank() &&
                                vm.stampSeries(st)?.id == o.seriesId
                        }
                        if (boundStamp != null) {
                            val sbmp = StampAssets.bitmap(ctx, boundStamp.assetId)
                            if (sbmp != null) {
                                Image(sbmp, null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                        }
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
                                LkIcons.Bell, null,
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
                        Modifier.fillMaxWidth()
                            // §116（用户实机）：日程行、系统行都能整行点开，唯独任务行只有
                            // 圆圈可点 —— 「任务在日历上没法进入编辑」就是这条。补上与
                            // 待办页同款的行点击 → 任务详情页。
                            .plainClick { nav.navigate("task/" + t.id) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("任务"), fontSize = 12.sp, color = GrayText, modifier = Modifier.width(52.dp))
                        // §73：与待办页同款 —— 未完成圆圈 / 完成圆圈里打勾（旧版未完成用日历图标，像日程）
                        Icon(
                            if (t.done) LkIcons.CheckCircle
                            else LkIcons.Circle,
                            if (t.done) tr("取消完成") else tr("完成"),
                            tint = if (t.done) MaterialTheme.colorScheme.primary
                            else (listColorMap[t.listUid] ?: Color(0xFFC0C3C0)),
                            modifier = Modifier.size(18.dp).plainClick { vm.toggleTask(t) }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t.title, fontSize = 15.sp,
                            color = if (t.done) GrayText else Ink,
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
                                fontSize = 13.sp, color = com.looka.app.ui.theme.PlaceholderText,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Hairline(Modifier.padding(start = 18.dp))
                }
                // §120 P4（E2 场景入口）：从日历问小鹿，**带着当前日期上下文**进对话 ——
                // 不是把用户扔进空白聊天页（《全站统一规划》E2 的硬要求）
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClick {
                                vm.aiPrefill = tr("{0} 有什么安排？帮我看看。", Fmt.dateCn(day))
                            // §128 A7：场景入口带上下文条（对话页顶部"正在看：X"，可关闭）
                            vm.aiContextLabel = tr("正在看：{0}", Fmt.dateCn(day))
                                nav.navigate("aiChat")
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("小鹿"), fontSize = 12.sp, color = GrayText, modifier = Modifier.width(52.dp))
                        Text("🦌", fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("问问这天的安排"), fontSize = 14.sp, color = GrayText)
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
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    // §113 B6：全宽**直角**面板（实机图 05/07；母档 4.2 特别纠正过「16-20dp 是 clean-room
    // 建议、不是实机定值」）。直角 + 60% scrim + 无拖柄 —— 视图切换是个短导航选择，
    // 不是可拖拽的内容面板，拖柄给的是错误的手势暗示。
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Color.White,
        shape = androidx.compose.ui.graphics.RectangleShape,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp)) {
            // 顶部搜索框（Lifebear 式）：点击进搜索页
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DimBg)
                    // §98 H4：不再 navigate("search")，就地开搜索态
                    .plainClick { onDismiss(); onSearch() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(LkIcons.Search, null, tint = GrayText, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                // 日历页只搜日程 —— 任务归待办页、笔记日记归笔记页，各搜各的
                Text(tr("日程名、地点、备注"), fontSize = 14.sp, color = GrayText)
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
                        label, fontSize = 16.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            Hairline()
            NavRow(tr("跳转到日期"), icon = LkIcons.Calendar) { onDismiss(); onJump() }
            NavRow(tr("显示设置"), icon = LkIcons.Settings) { onDismiss(); nav.navigate("calSettings") }
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
 * §75 C：贴纸 Composer 面板（Lifebear 三面模型的第三面）。
 * 日程/任务是全屏页（点模式图标即转入），只有贴纸留在月历上 —— 它必须让日历可见可点。
 * 高度对齐实机 ≈238dp：模式行52 + 网格120 + 页点/包切换。全局底栏在面板打开时隐藏（C2）。
 */
@Composable
private fun CreatePanel(
    vm: LookaViewModel,
    nav: NavHostController,
    onClose: () -> Unit,
    onDragCreate: ((String, androidx.compose.ui.geometry.Offset, Int) -> Unit)? = null
) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val pickerPreview = (cfg.screenWidthDp / 7f * 0.60f).dp
    val densityP = LocalDensity.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color.White)
            .navigationBarsPadding()
            // §11：把面板高度回报给日历，作为 viewport bottomInset（不压缩格子）
            .onGloballyPositioned { vm.panelInset = with(densityP) { it.size.height.toDp() } }
    ) {
        // 模式行（图48：贴纸面选中态是浅灰圆；日程/任务一击转全屏）
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelIcon(LkIcons.Calendar, tr("日程"), false) {
                vm.composerMode = 0
                onClose()
                vm.prepareCreateDraft(vm.selectedDay)
                vm.editorInitMode = 0
                nav.navigate("editor")
            }
            PanelIcon(LkIcons.Check, tr("任务"), false) {
                vm.composerMode = 1
                onClose()
                vm.prepareCreateDraft(vm.selectedDay)
                vm.editorInitMode = 1
                vm.editorTaskDue = vm.selectedDay   // §114 P7：日历上下文，预填选中日（§75 T1）
                nav.navigate("editor")
            }
            PanelIcon(LkIcons.Smile, tr("表情"), true) { }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(LkIcons.Close, tr("关闭"), tint = GrayText)
            }
        }
        Hairline()
        com.looka.app.ui.common.StickerPicker(
            selected = vm.stampSel,
            onSelect = { vm.stampSel = if (vm.stampSel == it) "" else it },
            onDragCreate = onDragCreate,
            // §2.2/§11.1 双尺度：Picker 预览 0.60×Wd，比日历里的最终尺寸大，便于识别
            previewSize = pickerPreview
        )
    }
}

@Composable
private fun PanelIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, selected: Boolean, onClick: () -> Unit
) {
    // §121：与编辑器 ModeIcon 统一 —— 44dp 热区（touch.min）、选中底 #D0D0D0（实机量值；
    // 原 PanelBg #F7F8F7 在白纸上等于隐形，选中态形同虚设）
    Box(
        Modifier.padding(horizontal = 4.dp).size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) Color(0xFFD0D0D0) else Color.Transparent)
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
    // §76 F1：容器自身在窗口中的原点（Scaffold padding 会把它下推）
    var containerOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { containerOrigin = it.positionInRoot() }
    ) {
        val density = LocalDensity.current
        val wd = maxWidth / 7f
        val popW = wd * 3.2f
        // §97 G3：**撤回 §89 U2**。那条写的「实机 78px」是错的 —— 本图在 x=340/380/420/780/810
        // 五个位置竖扫，上下描边一致落在 y=1153 / y=1250，实测 **100px = 0.616×Wd**。
        // 也就是说原来的 0.62 本来就对，我把它改矮了 22%。
        val popH = wd * 0.616f
        val caretH = 6.3.dp   // §97 G4：实测 20px = 6.3dp
        val screenW = with(density) { maxWidth.toPx() }
        val popWpx = with(density) { popW.toPx() }
        // §76 F1 根治：anchor 是**窗口坐标**（含状态栏），而本容器在 Scaffold padding 之下，
        // 原点比窗口低一个状态栏高度 —— 旧版直接拿窗口坐标当容器内偏移，菜单整体下移压住贴纸。
        // 减去容器自身原点换算到容器内坐标，与状态栏/插栏/底栏高度全部解耦。
        val ax = anchor.x - containerOrigin.x
        val ay = anchor.y - containerOrigin.y
        // Anchor 到印章上方；靠边时 clamp（§5.1「靠边时需要翻转或 clamp」）
        val left = (ax - popWpx / 2f).coerceIn(8f, (screenW - popWpx - 8f).coerceAtLeast(8f))
        val gapPx = with(density) { (wd * 0.38f).toPx() }
        val topPx = ay - with(density) { (popH + caretH).toPx() } - gapPx
        val flipped = topPx < with(density) { 8.dp.toPx() }
        val finalTop = if (flipped) ay + gapPx else topPx

        // 关闭层：透明、不压暗（§5.1 无全屏遮罩）
        Box(Modifier.fillMaxSize().plainClick(onDismiss))

        // §89 U5（真 bug 修复）：caret 此前写死 padding(start = w)，永远在 popover 左侧 12% 处 ——
        // 贴纸在中间时箭头指偏，靠边被 clamp 后更是指向一个与贴纸无关的位置。
        // 正确做法：按锚点在 popover 内的相对位置放 caret，两端各留一个圆角的余量。
        // §97 G4：实测 caret 底边 ≈20px = 0.123×Wd —— 原来的 0.26 宽了整整一倍
        val caretW = wd * 0.123f
        val caretWpx = with(density) { caretW.toPx() }
        val caretPadPx = (ax - left - caretWpx / 2f)
            .coerceIn(8f, (popWpx - caretWpx - 8f).coerceAtLeast(8f))
        val caretPad = with(density) { caretPadPx.toDp() }

        Column(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), finalTop.toInt()) }
                .zIndex(60f)
        ) {
            if (flipped) Caret(up = true, w = caretW, h = caretH, startPad = caretPad)
            Row(
                Modifier
                    .width(popW).height(popH)
                    // §97 G6：圆角实测 ≈15px = 4.8dp；描边实测 3px = 1.0dp
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White)
                    // §89 U3：实机是**深色细描边**成形（无阴影）；原 #DCDFDC 太浅几乎看不见
                    .border(1.dp, Ink, RoundedCornerShape(5.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().plainClick(onPrimary),
                    contentAlignment = Alignment.Center) {
                    Text(
                        if (bound) tr("编辑") else tr("登记日程"),
                        fontSize = 11.5.sp, color = Ink   // §97 G6：实测字高 11.1dp
                    )
                }
                // §97 G5：中缝实测是**短浅灰线**（y 1174–1235 = 62% 高，最暗值 191），
                // 不是通高黑线 —— 通高黑线会把一个轻量菜单切成两个按钮，重得多
                Box(
                    Modifier.width(1.dp).fillMaxHeight(0.62f)
                        .background(Color(0xFFBFBFBF))
                )
                Box(Modifier.weight(1f).fillMaxHeight().plainClick(onDelete),
                    contentAlignment = Alignment.Center) {
                    // §89 U4：实机「削除」是黑字，与「编集」同色 —— 危险语义由后面的确认框承担
                    Text(tr("删除"), fontSize = 11.5.sp, color = Ink)
                }
            }
            if (!flipped) Caret(up = false, w = caretW, h = caretH, startPad = caretPad)
        }
    }
}

/** Popover 指示小三角（§5.1 caret） */
@Composable
private fun Caret(
    up: Boolean,
    w: androidx.compose.ui.unit.Dp,
    h: androidx.compose.ui.unit.Dp,
    startPad: androidx.compose.ui.unit.Dp
) {
    Canvas(Modifier.padding(start = startPad).size(w, h)) {
        val p = Path().apply {
            if (up) { moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height) }
            close()
        }
        drawPath(p, Color.White)
        // §89 U3：描边与外框同色同粗
        drawPath(p, Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}


/**
 * §98 H4：日历页内搜索面板 —— 月历是网格没法原地过滤，所以搜索态用结果列表盖住它，
 * 但**不跳页**，返回键/← 就回到月历。只搜日程（任务归待办页、笔记日记归笔记页，各搜各的）。
 */
@Composable
private fun CalendarSearchPane(
    q: String,
    onQ: (String) -> Unit,
    onExit: () -> Unit,
    series: List<com.looka.app.data.EventSeries>,
    cats: List<com.looka.app.data.Category>,
    nav: NavHostController
) {
    val qq = q.trim()
    val catById = remember(cats) { cats.associateBy { it.id } }
    val hit = remember(qq, series) {
        if (qq.isBlank()) emptyList()
        else series.filter {
            !it.deleted && (it.title.contains(qq, true) ||
                it.location.contains(qq, true) || it.memo.contains(qq, true))
        }.sortedByDescending { it.startDay }
    }
    Column(Modifier.fillMaxSize()) {
        com.looka.app.ui.common.LookaSearchBar(
            query = q, onQueryChange = onQ,
            active = true, onActiveChange = { if (!it) onExit() },
            placeholder = tr("日程名、地点、备注")
        )
        Hairline()
        when {
            qq.isBlank() -> com.looka.app.ui.common.EmptyDeer(tr("输入关键词，搜日程名、地点和备注"))
            hit.isEmpty() -> com.looka.app.ui.common.EmptyDeer(
                tr("没找到「{0}」", qq), hint = tr("换个词试试"))
            else -> LazyColumn {
                items(hit, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .plainClick { nav.navigate("detail/${e.id}/${e.startDay}") }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.looka.app.ui.common.ColorDot(parseHex(catById[e.categoryId]?.colorHex ?: "#9AA0A6"), 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.title.ifBlank { tr("无标题") }, fontSize = 15.sp, color = Ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(Fmt.dateCn(e.startDay), fontSize = 12.sp, color = GrayText)
                        }
                    }
                    Hairline(Modifier.padding(start = 38.dp))
                }
                item { Spacer(Modifier.height(70.dp)) }
            }
        }
    }
}

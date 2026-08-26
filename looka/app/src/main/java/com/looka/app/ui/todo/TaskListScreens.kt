@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.looka.app.ui.todo

import com.looka.app.ui.common.dialogFieldColors
import com.looka.app.ui.common.DlgTitle
import com.looka.app.ui.theme.LkIcons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.LIST_PALETTE
import com.looka.app.data.Task
import com.looka.app.data.TaskList
import com.looka.app.ui.common.SwipeDeleteBackdrop
import com.looka.app.ui.common.rememberReorderState
import com.looka.app.ui.common.listRowGestures
import com.looka.app.ui.common.safeBack
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaDatePicker
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.onColor
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.rowClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.LinkBlue
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

private val StarAmber = Color(0xFFF2B23D)

// ==================== 清单详情页 ====================

@Composable
fun TaskListScreen(vm: LookaViewModel, nav: NavHostController, uid: String) {
    val ctx = LocalContext.current
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val list = lists.find { it.uid == uid }
    if (list == null) {
        LaunchedEffect(uid) { safeBack(nav) }
        return
    }
    // 手动顺序（sortOrder），支持长按拖拽重排
    val shouldShow = remember(tasks, uid) {
        tasks.filter { !it.done && it.listUid == uid }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }
    // §102：**快照式** —— 打勾后条目不当场消失（否则看着像被删了，正是用户反馈的那条）。
    // 真被删掉的才移除；离开页面再进来会重新快照，勾掉的自然就归到「已完成」了。
    val aliveUids = remember(tasks, uid) { tasks.filter { it.listUid == uid }.map { it.uid }.toSet() }
    val shownUids = com.looka.app.ui.common.rememberSnapshotOrder(shouldShow.map { it.uid }, aliveUids)
    val allByUid = remember(tasks) { tasks.associateBy { it.uid } }
    val open = remember(shownUids, allByUid) { shownUids.mapNotNull { allByUid[it] } }
    var editList by remember { mutableStateOf(false) }
    var delList by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(list.name, onBack = { nav.popBackStack() }) {
            ColorDot(parseHex(list.colorHex), 12.dp)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(LkIcons.More, tr("更多"), tint = Ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                    DropdownMenuItem(text = { Text(tr("编辑清单")) }, onClick = { menu = false; editList = true })
                    DropdownMenuItem(
                        text = { Text(if (list.archived) tr("取消归档") else tr("归档清单")) },
                        onClick = {
                            menu = false
                            vm.updateTaskList(list.copy(archived = !list.archived))
                            if (!list.archived) {
                                toast(ctx, tr("已归档，可在「已完成清单」中找到"))
                                nav.popBackStack()
                            }
                        }
                    )
                    if (list.deletable) {
                        DropdownMenuItem(
                            text = { Text(tr("删除清单"), color = HolidayRed) },
                            onClick = { menu = false; delList = true }
                        )
                    }
                }
            }
        }

        // §94 F4：快速添加行改形态 —— 去 22dp 胶囊底色与 40dp 圆形按钮，
        // 改「＋ 输入框 ☆」一行 + 通栏 hairline（实机图 58-63 统一形态）；
        // placeholder 保留「添加任务到「X」…」（比实机的「添加任务」说清了去向，有意不对齐）
        QuickAddTaskRow(
            placeholder = tr("添加任务到「{0}」…", list.name),
            defaultStarred = false,
            onSubmit = { title, star -> vm.addTask(title, listUid = uid, starred = star) }
        )

        // §103：**用户拍板 B** —— 排序回到页内长按拖，全站五处一套手势，不留排序按钮。
        //
        // 撤销的是 §94 F5「拆独立排序页」。那条依据是实机图 61（ToDo 的 並び替え 确实是
        // 独立整页、☆ 换 ☰），**证据本身没错**，是产品选择不同：用户要全站统一的手势语言，
        // 不要为同一件事再留一个入口。代价写明：拖拽与完成圈/星标同处一行，误触风险由
        // 「必须先长按」来兜（detectDragGesturesAfterLongPress），真机若仍误触再议。
        val reorder = rememberReorderState(open.map { it.uid })
        val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
        LazyColumn(Modifier.weight(1f)) {
            items(reorder.order.toList(), key = { it }) { tuid ->
                val t = allByUid[tuid] ?: return@items
                // 左滑露出的红底衬在行下面一层
                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                    SwipeDeleteBackdrop(Modifier.matchParentSize())
                    TaskRowV2(
                        t,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .zIndex(if (reorder.draggingUid == tuid) 1f else 0f)
                            .listRowGestures(
                                uid = tuid, state = reorder, rowHeightPx = rowHeightPx,
                                onReorder = { order -> vm.reorderTasks(order) },
                                onDelete = { vm.deleteTask(t) }
                            ),
                        listName = null, listColor = parseHex(list.colorHex),
                        onToggle = { vm.toggleTask(t) },
                        onStar = { vm.setTaskStar(t, !t.starred) },
                        onClick = { nav.navigate("task/${t.id}") }   // §85 B5：行点击进原生详情
                    )
                }
            }
            if (open.isEmpty()) {
                item { EmptyDeer(tr("清单空空的"), hint = tr("在上方输入框写下第一条 ↑")) }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (editList) ListEditDialog(
        existing = list,
        onSave = { n, c -> vm.updateTaskList(list.copy(name = n, colorHex = c)); editList = false },
        onDismiss = { editList = false }
    )
    if (delList) ConfirmDialog(
        title = tr("删除清单「{0}」？", list.name),
        text = tr("清单内的任务将移入默认清单"),
        onConfirm = {
            delList = false
            vm.deleteTaskList(list)
            safeBack(nav)
        },
        onDismiss = { delList = false }
    )
    }
}

// §103：ReorderScreen（独立排序页）已删 —— 用户拍板走页内长按拖，全站一套手势。

// ==================== 星标 ====================

@Composable
fun StarredScreen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val listMap = remember(lists) { lists.associateBy { it.uid } }
    // §102：快照式（§96.2 实机图 78/79 已证：取消星标、标完成，条目都还留在页上）
    // §114 P9：补已归档清单过滤，与首页计数同口径
    val shouldShow = remember(tasks, listMap) {
        tasks.filter { !it.done && it.starred && listMap[it.listUid]?.archived != true }.map { it.uid }
    }
    val alive = remember(tasks) { tasks.map { it.uid }.toSet() }
    val shown = com.looka.app.ui.common.rememberSnapshotOrder(shouldShow, alive)
    val byUid = remember(tasks) { tasks.associateBy { it.uid } }
    val groups = remember(shown, byUid, lists) {
        val order = lists.mapIndexed { i, l -> l.uid to i }.toMap()
        shown.mapNotNull { byUid[it] }
            .groupBy { it.listUid }
            .toList()
            .sortedBy { order[it.first] ?: 99 }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("星标"), onBack = { nav.popBackStack() })
        // §94 F3：星标页快速添加行 —— 右侧 ☆ **默认亮**（在这页建的任务自然带星，实机图 60）
        QuickAddTaskRow(
            placeholder = tr("添加任务"),
            defaultStarred = true,
            onSubmit = { title, star -> vm.addTask(title, starred = star) }
        )
        LazyColumn {
            groups.forEach { (listUid, ts) ->
                val l = listMap[listUid]
                item {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColorDot(parseHex(l?.colorHex ?: "#5C6670"), 9.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(l?.name ?: tr("清单"), fontSize = 12.sp, color = GrayText)
                    }
                }
                items(ts, key = { it.uid }) { t ->
                    // §99 I6：智能视图**只给左滑删除** —— 顺序由规则决定，不该手动排
                    Box(Modifier.animateItem()) {
                        SwipeDeleteBackdrop(Modifier.matchParentSize())
                        TaskRowV2(
                            t, listName = null, listColor = parseHex(l?.colorHex ?: "#5C6670"),
                            onToggle = { vm.toggleTask(t) },
                            onStar = { vm.setTaskStar(t, !t.starred) },
                            onClick = { nav.navigate("task/${t.id}") }   // §85 B5：行点击进原生详情
                            , modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .listRowGestures(
                                    uid = t.uid, state = null, rowHeightPx = 0f,
                                    onReorder = null, onDelete = { vm.deleteTask(t) }
                                )
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                item { EmptyDeer(tr("还没有星标任务，点任务右侧的 ☆ 收藏")) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ==================== 未来 7 天 ====================

@Composable
fun Next7Screen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val listMap = remember(lists) { lists.associateBy { it.uid } }
    val today = Fmt.today()

    // §102：快照式 —— 同星标页，打勾不让条目当场消失
    // §114 P8/P9：口径改「今天起 7 天」（today..today+6，原 +7 是 8 个日期）；
    // 补已归档清单过滤 —— 首页计数一直过滤，这里不过滤，归档后「计数 0 点进去有东西」
    val shouldShow = remember(tasks, today, listMap) {
        tasks.filter {
            !it.done && listMap[it.listUid]?.archived != true &&
                (it.dueDay in 0 until today || it.dueDay in today..(today + 6))
        }.map { it.uid }
    }
    val alive = remember(tasks) { tasks.map { it.uid }.toSet() }
    val shown = com.looka.app.ui.common.rememberSnapshotOrder(shouldShow, alive).toSet()
    val byUid = remember(tasks) { tasks.associateBy { it.uid } }
    val inView = remember(shown, byUid) { shown.mapNotNull { byUid[it] } }

    val overdue = remember(inView, today) {
        inView.filter { it.dueDay in 0 until today }.sortedBy { it.dueDay }
    }
    val byDay = remember(inView, today) {
        (0..6).map { off ->
            val d = today + off
            d to inView.filter { it.dueDay == d }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("未来 7 天"), onBack = { nav.popBackStack() })
        // §94 F3 + §96 定案：从「未来 7 天」快速添加，日期自动填今天（实机图 74/75）
        QuickAddTaskRow(
            placeholder = tr("添加任务"),
            defaultStarred = false,
            onSubmit = { title, star -> vm.addTask(title, due = today, starred = star) }
        )
        LazyColumn {
            if (overdue.isNotEmpty()) {
                item {
                    Text(
                        tr("已逾期"), fontSize = 12.sp, color = HolidayRed,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(overdue, key = { it.uid }) { t ->
                    // §99 I6：智能视图**只给左滑删除** —— 顺序由规则决定，不该手动排
                    Box(Modifier.animateItem()) {
                        SwipeDeleteBackdrop(Modifier.matchParentSize())
                        TaskRowV2(
                            t, listName = listMap[t.listUid]?.name,
                            listColor = parseHex(listMap[t.listUid]?.colorHex ?: "#5C6670"),
                            onToggle = { vm.toggleTask(t) },
                            onStar = { vm.setTaskStar(t, !t.starred) },
                            onClick = { nav.navigate("task/${t.id}") }   // §85 B5：行点击进原生详情
                            , modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .listRowGestures(
                                    uid = t.uid, state = null, rowHeightPx = 0f,
                                    onReorder = null, onDelete = { vm.deleteTask(t) }
                                )
                        )
                    }
                }
            }
            byDay.forEach { (d, ts) ->
                if (ts.isNotEmpty()) {
                    item {
                        Text(
                            when (d) {
                                today -> tr("今天 · {0}", Fmt.dateCn(d))
                                today + 1 -> tr("明天 · {0}", Fmt.dateCn(d))
                                else -> Fmt.dateCn(d)
                            },
                            fontSize = 12.sp, color = GrayText,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(ts, key = { it.uid }) { t ->
                        // §99 I6：智能视图**只给左滑删除** —— 顺序由规则决定，不该手动排
                        Box(Modifier.animateItem()) {
                            SwipeDeleteBackdrop(Modifier.matchParentSize())
                            TaskRowV2(
                                t, listName = listMap[t.listUid]?.name,
                                listColor = parseHex(listMap[t.listUid]?.colorHex ?: "#5C6670"),
                                onToggle = { vm.toggleTask(t) },
                                onStar = { vm.setTaskStar(t, !t.starred) },
                                onClick = { nav.navigate("task/${t.id}") }   // §85 B5：行点击进原生详情
                                , modifier = Modifier
                                    .background(MaterialTheme.colorScheme.background)
                                    .listRowGestures(
                                        uid = t.uid, state = null, rowHeightPx = 0f,
                                        onReorder = null, onDelete = { vm.deleteTask(t) }
                                    )
                            )
                        }
                    }
                }
            }
            if (overdue.isEmpty() && byDay.all { it.second.isEmpty() }) {
                item { EmptyDeer(tr("未来 7 天没有带日期的任务")) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ==================== 已完成任务 ====================

@Composable
fun DoneTasksScreen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val listMap = remember(lists) { lists.associateBy { it.uid } }
    var range by remember { mutableIntStateOf(1) }   // 0近1月 1近3月 2近1年 3全部

    val nowMs = System.currentTimeMillis()
    val cutoff = when (range) {
        0 -> nowMs - 31L * 86400000L
        1 -> nowMs - 93L * 86400000L
        2 -> nowMs - 366L * 86400000L
        else -> 0L
    }
    val groups = remember(tasks, range, listMap) {
        tasks.filter { it.done }
            // §94 F8：只显示未归档清单里的任务（实机「未完了リストのタスクが表示されます」）——
            // 说明文字写了就要做到，不然是撒谎
            .filter { listMap[it.listUid]?.archived != true }
            .filter { range == 3 || (if (it.doneAt > 0) it.doneAt else it.updatedAt) >= cutoff }
            .groupBy {
                val ms = if (it.doneAt > 0) it.doneAt else it.updatedAt
                java.time.Instant.ofEpochMilli(ms)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            }
            .toList()
            .sortedByDescending { it.first }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("已完成任务"), onBack = { nav.popBackStack() })
        // §113 C6：范围选择从「四个并排 chip」改成实机形态（图 14/15/16）——
        // 左侧**一个**白底灰描边胶囊「近3月 ▼」，点开期间选择弹窗（整行灰底单选、即选即关）；
        // 右侧灰字说明。四个 chip 占满一行，实机只用一个下拉，行反而透气。
        var rangeDlg by remember { mutableStateOf(false) }
        val rangeLabels = listOf(tr("近1月"), tr("近3月"), tr("近1年"), tr("全部"))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(0.8.dp, Color(0xFFC9CCC9), RoundedCornerShape(50))
                    .plainClick { rangeDlg = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(rangeLabels[range], fontSize = 13.sp, color = Ink)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowDown, null,
                    tint = Ink, modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // §94 F8：说明文字（实机「未完了リストのタスクが表示されます」放右侧）——
            // 用户不知道已归档清单的任务不在这里，是真的会困惑
            Text(tr("只显示未归档清单里的任务"), fontSize = 10.sp, color = GrayText)
        }
        if (rangeDlg) com.looka.app.ui.common.PlainChoiceDialog(
            title = tr("时间范围"),
            options = rangeLabels.mapIndexed { i, l -> i to l },
            selected = range,
            onSelect = { range = it },
            onDismiss = { rangeDlg = false }
        )
        Hairline()
        LazyColumn {
            groups.forEach { (day, ts) ->
                item {
                    Text(
                        Fmt.dateCn(day), fontSize = 12.sp, color = GrayText,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(ts, key = { it.uid }) { t ->
                    // §99 I6：智能视图**只给左滑删除** —— 顺序由规则决定，不该手动排
                    Box(Modifier.animateItem()) {
                        SwipeDeleteBackdrop(Modifier.matchParentSize())
                        TaskRowV2(
                            t, listName = listMap[t.listUid]?.name,
                            listColor = parseHex(listMap[t.listUid]?.colorHex ?: "#5C6670"),
                            onToggle = { vm.toggleTask(t) },
                            onStar = { vm.setTaskStar(t, !t.starred) },
                            onClick = { nav.navigate("task/${t.id}") }   // §99：已完成任务此前点不开详情（审计 BUG-TL-008）
                            , modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .listRowGestures(
                                    uid = t.uid, state = null, rowHeightPx = 0f,
                                    onReorder = null, onDelete = { vm.deleteTask(t) }
                                )
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                item { EmptyDeer(tr("这个时间段还没有完成的任务")) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ==================== 已完成（归档）清单 ====================

@Composable
fun DoneListsScreen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val archived = remember(lists) { lists.filter { it.archived } }
    var delList by remember { mutableStateOf<TaskList?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("已完成清单"), onBack = { nav.popBackStack() })
        LazyColumn {
            items(archived, key = { it.uid }) { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainClick { nav.navigate("list/${l.uid}") }
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(l.colorHex), 13.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(l.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.updateTaskList(l.copy(archived = false)) }) {
                        Text(tr("恢复"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (l.deletable) {
                        TextButton(onClick = { delList = l }) {
                            Text(tr("删除"), fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Hairline(Modifier.padding(start = 16.dp))
            }
            if (archived.isEmpty()) {
                item { EmptyDeer(tr("完成一个清单后，把它归档收在这里")) }
            }
        }
    }

    delList?.let { l ->
        ConfirmDialog(
            title = tr("删除清单「{0}」？", l.name),
            text = tr("清单内的任务将移入默认清单"),
            onConfirm = { vm.deleteTaskList(l); delList = null },
            onDismiss = { delList = null }
        )
    }
}

// ==================== 共用：任务行 / 快速添加行 / 编辑弹窗 / 清单弹窗 ====================

/**
 * §94 F3/F4：ToDo 子页统一的「顶部快速添加行」（实机图 58-63 每个子页都有）：
 * `＋  输入框  ☆` + 通栏 hairline。无底色、无圆角。
 *
 * - 右侧 ☆ **可先点亮再输入** —— 建任务时就带星；星标页默认亮（defaultStarred = true）
 * - 回车即提交且不收键盘 —— 连续加多条是核心场景
 * - 不跳页、不弹窗，就地变输入框
 */
@Composable
fun QuickAddTaskRow(
    placeholder: String,
    defaultStarred: Boolean,
    onSubmit: (title: String, starred: Boolean) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var starOn by remember { mutableStateOf(defaultStarred) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(LkIcons.Plus, tr("添加"), tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            TextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(placeholder, fontSize = 14.sp, color = Color(0xFFB9BBB9)) },
                colors = clearFieldColors(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (input.isNotBlank()) { onSubmit(input.trim(), starOn); input = "" }
                }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (starOn) LkIcons.StarFill else LkIcons.Star,
                tr("星标"),
                tint = if (starOn) StarAmber else Color(0xFFC0C3C0),
                modifier = Modifier.size(22.dp).plainClick { starOn = !starOn }
            )
        }
        Hairline()
    }
}

@Composable
fun TaskRowV2(
    t: Task,
    modifier: Modifier = Modifier,
    listName: String?,
    listColor: Color,
    onToggle: () -> Unit,
    onStar: () -> Unit,
    onClick: () -> Unit
    // §99 I6：原来这里有个 onDelete 参数，注释写着"长按删除"，**函数体从没用过它**
    //（审计 BUG-TL-008）。删除现在由 listRowGestures 的左滑统一提供，参数去掉。
) {
    val scale = remember { Animatable(1f) }
    var firstDraw by remember { mutableStateOf(true) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(t.done) {
        if (firstDraw) {
            firstDraw = false
        } else if (t.done) {
            // F7：完成任务给一次轻震 —— "高级感"最便宜的来源
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            scale.snapTo(1.35f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .rowClick(onClick)   // §85 B4：任务行按压反馈先于进详情页
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (t.done) LkIcons.CheckCircle else LkIcons.Circle,
            if (t.done) tr("取消完成") else tr("完成"),
            tint = if (t.done) MaterialTheme.colorScheme.primary else Color(0xFFC0C3C0),
            modifier = Modifier.size(22.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .plainClick(onToggle)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // §102：快照式之后，勾掉的行会**留在原地**，所以必须一眼看出它已完成 ——
            // 圆圈变实心勾（上面 Icon 已处理）+ 标题转灰。
            // 不加删除线：母档 B 项定过「打勾+灰字即可，全站去删除线」。
            Text(
                t.title, fontSize = 16.sp,
                color = if (t.done) GrayText else Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // §113 C3：副行对齐实机（图 14/15）—— **日期在前、色点+清单名在后**
            // （「10月1日(木) ●マイリスト」），字号 10 → 12sp（实机 Secondary 12sp，10sp 要眯眼）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.dueDay >= 0) {
                    Text(
                        Fmt.dateCn(t.dueDay), fontSize = 12.sp,
                        color = if (!t.done && t.dueDay < Fmt.today()) HolidayRed else GrayText
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (listName != null) {
                    ColorDot(listColor, 7.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(listName, fontSize = 12.sp, color = GrayText)
                }
            }
        }
        // §114 P10：热区 38→44dp（母档 touch.min 44×44），图标视觉 20dp 不变
        IconButton(onClick = onStar, modifier = Modifier.size(44.dp)) {
            Icon(
                if (t.starred) LkIcons.StarFill else LkIcons.Star,
                tr("星标"),
                tint = if (t.starred) StarAmber else Color(0xFFC9CCC9),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// §114 P14：TaskEditDialog 已退役 —— 任务编辑/复制统一走全页编辑器（EventEditorScreen 任务面）

/** 新建/编辑清单：名称 + 48 色大色板（Lifebear 式） */
@Composable
fun ListEditDialog(
    existing: TaskList?,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var color by remember(existing) { mutableStateOf(existing?.colorHex ?: LIST_PALETTE[30]) }
    // §94 F7：色盘**默认折叠**（实机「色 ● ⌄」，点开展开 48 色）——
    // 绝大多数人建清单只想打个名字，常驻色盘把对话框撑满屏、输入框被挤到角落
    var showPalette by remember(existing) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DlgTitle(if (existing == null) tr("新建清单") else tr("编辑清单")) },
        text = {
            Column {
                TextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text(tr("清单名，如：购物 / 学习")) },
                    singleLine = true,
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                if (!showPalette) {
                    // 折叠态：一行「色 ● ⌄」
                    Row(
                        Modifier.fillMaxWidth().plainClick { showPalette = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("色"), fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .background(parseHex(color))
                                .border(0.8.dp, Color(0xFFD8D8D8), CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown, tr("色"),
                            tint = GrayText, modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth().plainClick { showPalette = false }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("色"), fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            // §106：展开态**也要留当前色点**（实机图 96/128 两张都在）。
                            // 原来展开就把点收了 —— 挑色的时候恰恰最需要看见"现在是哪个"
                            Box(
                                Modifier.size(18.dp).clip(CircleShape)
                                    .background(parseHex(color))
                                    .border(0.8.dp, Color(0xFFD8D8D8), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.KeyboardArrowUp, tr("色"),
                                tint = GrayText, modifier = Modifier.size(20.dp)
                            )
                        }
                        LIST_PALETTE.chunked(6).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            row.forEach { hex ->
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .background(parseHex(hex))
                                        .border(
                                            width = if (color == hex) 2.5.dp else 0.8.dp,
                                            color = if (color == hex) Ink
                                                    else if (parseHex(hex).luminance() > 0.82f) Color(0xFFD8D8D8)
                                                    else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .plainClick { color = hex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (color == hex) {
                                        Icon(
                                            LkIcons.CheckCircle, null,
                                            // 亮色块上白勾会隐形，按亮度选色
                                            tint = onColor(parseHex(hex)), modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            repeat(6 - row.size) { Spacer(Modifier.size(30.dp)) }
                        }
                    }
                    }   // 色盘滚动 Column
                }   // else（色盘展开态）
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), color) },
                enabled = name.isNotBlank()
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}


// §99 I7：UndoBar 已搬到 ui/common/UndoHost.kt 并挂在应用级 —— 原来只挂这一页，
// 别处删除看不到撤销入口（审计 BUG-TL-009）。

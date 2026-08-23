package com.looka.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.looka.app.ui.account.AccountScreen
import com.looka.app.ui.ai.AiChatScreen
import com.looka.app.ui.calendar.CalendarScreen
import com.looka.app.ui.calendar.CalendarSettingsScreen
import com.looka.app.ui.category.CategoryManageScreen
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.event.EventDetailScreen
import com.looka.app.ui.event.EventEditorScreen
import com.looka.app.ui.event.RecurrenceEditorScreen
import com.looka.app.ui.more.MoreScreen
import com.looka.app.ui.notes.DiaryEditScreen
import com.looka.app.ui.notes.NoteEditScreen
import com.looka.app.ui.notes.NotesDiaryScreen
import com.looka.app.ui.search.SearchScreen
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.todo.DoneListsScreen
import com.looka.app.ui.todo.DoneTasksScreen
import com.looka.app.ui.todo.Next7Screen
import com.looka.app.ui.todo.StarredScreen
import com.looka.app.ui.todo.TaskListScreen
import com.looka.app.ui.todo.TodoScreen
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/** 全局导航：主壳 + 各二级页面 */
@Composable
fun LookaRoot() {
    val vm: LookaViewModel = viewModel()
    val nav = rememberNavController()

    NavHost(
        nav,
        startDestination = "home",
        // §74 P0-10（对齐 BEAR 实机逐帧）：Horizontal Push —— 新页整幅从右推入、
        // 旧页向左退 1/5 制造纵深；全程无透明度（crossfade 的重叠淡影正是"廉价感"来源）
        enterTransition = {
            slideInHorizontally(tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it }
        },
        exitTransition = {
            slideOutHorizontally(tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 5 }
        },
        popEnterTransition = {
            slideInHorizontally(tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 5 }
        },
        popExitTransition = {
            slideOutHorizontally(tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it }
        }
    ) {
        composable("home") { HomeScreen(vm, nav) }
        composable("editor") { EventEditorScreen(vm, nav) }
        composable("recur") { RecurrenceEditorScreen(vm, nav) }
        composable(
            "detail/{sid}/{occ}",
            arguments = listOf(
                navArgument("sid") { type = NavType.LongType },
                navArgument("occ") { type = NavType.LongType }
            )
        ) { e ->
            EventDetailScreen(
                vm, nav,
                e.arguments!!.getLong("sid"),
                e.arguments!!.getLong("occ")
            )
        }
        composable("categories") { CategoryManageScreen(vm, nav) }
        // CAL-062（§70）：模板独立入口与创建页
        composable("templates") { com.looka.app.ui.event.TemplateManageScreen(vm, nav) }
        composable(
            "template/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { e -> com.looka.app.ui.event.TemplateEditorScreen(vm, nav, e.arguments!!.getLong("id")) }
        composable("calSettings") { CalendarSettingsScreen(vm, nav) }
        composable("search") { SearchScreen(vm, nav) }
        composable("aiChat") { AiChatScreen(vm, nav) }
        composable("account") { AccountScreen(vm, nav) }
        composable("subscription") { com.looka.app.ui.more.SubscriptionScreen(vm, nav) }
        composable("antler") { com.looka.app.ui.more.AntlerScreen(vm, nav) }
        composable("backup") { com.looka.app.ui.more.BackupScreen(vm, nav) }
        composable("selfcheck") { com.looka.app.ui.more.SelfCheckScreen(vm, nav) }
        composable("conflicts") { com.looka.app.ui.more.ConflictScreen(vm, nav) }
        composable("language") { com.looka.app.ui.more.LanguageScreen(vm, nav) }
        composable(
            "list/{uid}",
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { e -> TaskListScreen(vm, nav, e.arguments!!.getString("uid")!!) }
        composable("starred") { StarredScreen(vm, nav) }
        composable("next7") { Next7Screen(vm, nav) }
        composable("doneTasks") { DoneTasksScreen(vm, nav) }
        composable("doneLists") { DoneListsScreen(vm, nav) }
        composable(
            "note/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { e -> NoteEditScreen(vm, nav, e.arguments!!.getLong("id")) }
        composable(
            "diary/{day}",
            arguments = listOf(navArgument("day") { type = NavType.LongType })
        ) { e -> DiaryEditScreen(vm, nav, e.arguments!!.getLong("day")) }
    }
}

/** 主壳：四个 Tab + 中央黑色 +（规格 CAL-001 底部结构） */
@Composable
fun HomeScreen(vm: LookaViewModel, nav: androidx.navigation.NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 逾期任务次日转移询问（每天最多一次，避免任务凑数堆积）
    var carryCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val today = com.looka.app.util.Fmt.today()
        if (com.looka.app.data.Prefs.carryPromptDay(ctx) != today) {
            com.looka.app.data.Prefs.setCarryPromptDay(ctx, today)
            val n = vm.overdueCount()
            if (n > 0) carryCount = n
        }
    }
    if (carryCount > 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { carryCount = 0 },
            title = { Text(com.looka.app.util.tr("有 {0} 个任务逾期未完成", carryCount), fontSize = 17.sp) },
            text = { Text(com.looka.app.util.tr("要把它们移到今天继续做吗？保持原日期则显示为已逾期。"), fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.carryOverdueToToday(); carryCount = 0
                }) { Text(com.looka.app.util.tr("移到今天"), color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { carryCount = 0 }) {
                    Text(com.looka.app.util.tr("保持原日期"), color = GrayText)
                }
            },
            containerColor = Color.White
        )
    }

    // 每日一次自动检查更新（五批）
    var update by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.looka.app.util.UpdateManager.Info?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        update = com.looka.app.util.UpdateManager.autoCheck(ctx)
    }
    update?.let { info ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!info.forced) update = null },
            title = { Text(com.looka.app.util.tr("发现新版本 {0}", info.versionName), fontSize = 17.sp) },
            text = {
                Column {
                    Text(info.changelog.ifBlank { com.looka.app.util.tr("修复与体验优化") },
                        fontSize = 14.sp, lineHeight = 21.sp)
                    if (info.forced) Text(
                        com.looka.app.util.tr("当前版本已停止支持，请更新后继续使用"),
                        fontSize = 12.sp, color = com.looka.app.ui.theme.HolidayRed,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.looka.app.util.UpdateManager.startDownload(ctx, info)
                    com.looka.app.ui.common.toast(ctx, com.looka.app.util.tr("正在后台下载，完成后会自动弹出安装"))
                    if (!info.forced) update = null
                }) { Text(com.looka.app.util.tr("立即更新"), color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            },
            dismissButton = if (info.forced) null else ({
                androidx.compose.material3.TextButton(onClick = { update = null }) {
                    Text(com.looka.app.util.tr("稍后"), color = GrayText)
                }
            }),
            containerColor = Color.White
        )
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            LookaBottomBar(
                tab = tab,
                onTab = { tab = it },
                onPlus = {
                    // N1（§71 对齐 Lifebear）：月视图的 ＋ 弹底部创建面板（日历可见可点，贴纸能连续盖）；
                    // 其余场景保持整页编辑器。AC-001/002：预填选中日期（无选中即今天）
                    if (tab == 0 && vm.calView == 0) {
                        vm.createPanel = true
                    } else {
                        vm.prepareCreateDraft(vm.selectedDay)
                        nav.navigate("editor")
                    }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> CalendarScreen(vm, nav)
                1 -> TodoScreen(vm, nav)
                2 -> NotesDiaryScreen(vm, nav)
                else -> MoreScreen(vm, nav)
            }
        }
    }
}

@Composable
private fun LookaBottomBar(tab: Int, onTab: (Int) -> Unit, onPlus: () -> Unit) {
    Column(Modifier.background(Color.White)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarItem(Icons.Outlined.CalendarMonth, tr("日历"), tab == 0) { onTab(0) }
            BarItem(Icons.Outlined.TaskAlt, tr("待办"), tab == 1) { onTab(1) }
            // 中央黑色圆形 +（规格 §12：全局最强操作锚点）
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by press.collectIsPressedAsState()
                val plusScale by animateFloatAsState(
                    if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f),
                    label = "plusScale"
                )
                Box(
                    Modifier
                        .offset(y = (-8).dp)
                        .size(48.dp)
                        .scale(plusScale)
                        .clip(CircleShape)
                        .background(Ink)
                        .clickable(
                            interactionSource = press, indication = null, onClick = onPlus
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, tr("新建"), tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            BarItem(Icons.AutoMirrored.Outlined.MenuBook, tr("笔记·日记"), tab == 2) { onTab(2) }
            BarItem(Icons.Outlined.MoreHoriz, tr("更多"), tab == 3) { onTab(3) }
        }
    }
}

@Composable
private fun RowScope.BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 选中时图标弹性放大一档，文字加重 —— 轻手感反馈，不喧宾夺主
    val scale by animateFloatAsState(
        if (selected) 1.12f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "tabScale"
    )
    val tint by animateColorAsState(
        if (selected) Ink else Color(0xFFAEB1AE), tween(180), label = "tabTint"
    )
    Column(
        Modifier
            .weight(1f)
            .plainClick {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, label, tint = tint,
            modifier = Modifier.size(24.dp).scale(scale)
        )
        Text(
            label, fontSize = 10.sp,
            color = if (selected) Ink else GrayText,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium
                         else androidx.compose.ui.text.font.FontWeight.Normal
        )
    }
}

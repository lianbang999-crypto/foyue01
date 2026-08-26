package com.looka.app.ui.todo

import com.looka.app.ui.theme.LkIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ui.common.listRowGestures
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.rowClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/**
 * ToDo 中枢页（§94 批 F1/F2 对齐 Lifebear 图 64）：
 * 搜索（页首）→ 默认清单（置顶）→ 星标 / 未来7天 → 「清单」标题 → 用户清单 → ＋新建
 * → 「已完成」标题 → 已完成任务 / 已完成清单。
 * §94 F2：行尾无 `>` 箭头、行间无分隔线（实机就没有）；星标计数 0 不显示。
 */
@Composable
fun TodoScreen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var createDlg by remember { mutableStateOf(false) }
    // §98 H3：搜索改**页内**，不再跳独立搜索页（用户拍板：一套搜索、当前页搜）
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    val active = remember(lists) { lists.filter { !it.archived } }
    // §94 F1：默认清单（不可删 / 固定 uid）置顶，与用户清单分开 —— 实机マイリスト排在星标之前
    val defaultList = remember(active) {
        active.firstOrNull { !it.deletable || it.uid == "list-default" }
    }
    val userLists = remember(active, defaultList) {
        active.filter { it.uid != defaultList?.uid }
    }
    val archived = remember(lists) { lists.filter { it.archived } }
    val activeUids = remember(active) { active.map { it.uid }.toSet() }
    val openCount = remember(tasks) {
        tasks.filter { !it.done }.groupingBy { it.listUid }.eachCount()
    }
    val starredCount = remember(tasks, activeUids) {
        tasks.count { !it.done && it.starred && it.listUid in activeUids }
    }
    val reorder = com.looka.app.ui.common.rememberReorderState(userLists.map { it.uid })
    val byUid = remember(userLists) { userLists.associateBy { it.uid } }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 52.dp.toPx() }
    val today = Fmt.today()
    val next7Count = remember(tasks, activeUids) {
        // §114 P8：today..today+7 是 8 个日期。口径统一为「今天起 7 天」= today..today+6
        tasks.count { !it.done && it.dueDay in 0..(today + 6) && it.listUid in activeUids }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // §77 N5：搜索条即页首（Lifebear 用搜索框当页首，不写标题）；
        // §98 H3：换成全站唯一的 LookaSearchBar，点开就地进搜索态
        com.looka.app.ui.common.LookaSearchBar(
            query = q, onQueryChange = { q = it },
            active = searching, onActiveChange = { searching = it },
            // §77 N7：placeholder 写清能搜到什么（实机「タスク名、メモなど」）
            placeholder = tr("任务名、备注"),
            trailing = {
                // §71 A：AI 全站入口（用户拍板）
                androidx.compose.material3.IconButton(onClick = { nav.navigate("aiChat") }) {
                    com.looka.app.ui.common.DeerBadge(24.dp)
                }
            }
        )
        Hairline()

        // §98 H3：搜索态 —— 命中的任务直接铺在当前页，不跳走
        if (searching) {
            val qq = q.trim()
            val hit = remember(qq, tasks) {
                if (qq.isBlank()) emptyList()
                else tasks.filter { !it.deleted && (it.title.contains(qq, true) || it.memo.contains(qq, true)) }
            }
            val listMap = remember(lists) { lists.associateBy { it.uid } }
            when {
                qq.isBlank() -> com.looka.app.ui.common.EmptyDeer(
                    tr("输入关键词，搜任务名和备注"))
                hit.isEmpty() -> com.looka.app.ui.common.EmptyDeer(
                    tr("没找到「{0}」", qq), hint = tr("换个词试试"))
                else -> LazyColumn {
                    items(hit, key = { it.uid }) { t ->
                        TaskRowV2(
                            t, listName = listMap[t.listUid]?.name,
                            listColor = parseHex(listMap[t.listUid]?.colorHex ?: "#5C6670"),
                            onToggle = { vm.toggleTask(t) },
                            onStar = { vm.setTaskStar(t, !t.starred) },
                            onClick = { nav.navigate("task/${t.id}") }
                        )
                    }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
            return@Column
        }

        LazyColumn {
            // §94 F1：默认清单置顶（实机マイリスト在星标之前）；不可删，不参与拖拽
            defaultList?.let { dl ->
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .rowClick { nav.navigate("list/${dl.uid}") }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColorDot(parseHex(dl.colorHex), 13.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(dl.name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        val c = openCount[dl.uid] ?: 0
                        if (c > 0) Text("$c", fontSize = 13.sp, color = GrayText)
                    }
                }
            }

            // §94 F2：星标计数 0 不显示（实机如此）
            item {
                HubRow(
                    icon = { Icon(LkIcons.StarFill, null, tint = Color(0xFFF2B23D), modifier = Modifier.size(20.dp)) },
                    title = tr("星标"),
                    trailing = if (starredCount > 0) "$starredCount" else null
                ) {
                    nav.navigate("starred")
                }
            }
            item {
                // §94 F9：未来7天图标 = 带数字 7 的日历（实机如此），不再是普通日历
                HubRow(
                    icon = { com.looka.app.ui.calendar.CalendarGlyph("7", size = 20.dp) },
                    title = tr("未来 7 天"),
                    trailing = if (next7Count > 0) "$next7Count" else null
                ) {
                    nav.navigate("next7")
                }
            }

            // §94 F1：灰色分组标题（实机 リスト / 完了済み）
            item {
                GroupTitle(tr("清单"))
            }

            // 用户清单（§99 I6：长按拖排序 / 左滑删除，与全站同一套手势）
            items(reorder.order.toList(), key = { it }) { luid ->
                val l = byUid[luid] ?: return@items
                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                com.looka.app.ui.common.SwipeDeleteBackdrop(Modifier.matchParentSize())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .listRowGestures(
                            uid = luid, state = reorder, rowHeightPx = rowPx,
                            onReorder = { order -> vm.reorderTaskLists(order) },
                            onDelete = if (l.deletable) ({ vm.deleteTaskList(l) }) else null
                        )
                        .rowClick { nav.navigate("list/${l.uid}") }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(l.colorHex), 13.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(l.name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    val c = openCount[l.uid] ?: 0
                    if (c > 0) Text("$c", fontSize = 13.sp, color = GrayText)
                }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rowClick { createDlg = true }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(LkIcons.Plus, tr("新建清单"), tint = GrayText, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(tr("新建清单"), fontSize = 16.sp, color = GrayText)
                }
            }

            // §94 F1：「已完成」分组标题
            item { GroupTitle(tr("已完成")) }
            item {
                HubRow(
                    icon = { Icon(LkIcons.CheckCircle, null, tint = GrayText, modifier = Modifier.size(20.dp)) },
                    title = tr("已完成任务"),
                    trailing = null
                ) {
                    nav.navigate("doneTasks")
                }
            }
            item {
                HubRow(
                    icon = { Icon(Icons.Outlined.Inventory2, null, tint = GrayText, modifier = Modifier.size(20.dp)) },
                    title = tr("已完成清单"),
                    trailing = if (archived.isNotEmpty()) "${archived.size}" else null
                ) { nav.navigate("doneLists") }
            }
            item { Spacer(Modifier.height(70.dp)) }
        }
    }

    if (createDlg) ListEditDialog(
        existing = null,
        onSave = { n, c -> vm.addTaskList(n, c); createDlg = false },
        onDismiss = { createDlg = false }
    )
}

/** §94 F1：灰色分组小标题（实机 リスト / ラベル / 完了済み 那种） */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text, fontSize = 12.sp, color = GrayText,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun HubRow(
    icon: @Composable () -> Unit,
    title: String,
    trailing: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowClick(onClick)   // §85 B4
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        // §94 F2：行尾无 `>` 箭头（实机就没有）
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = GrayText)
    }
}

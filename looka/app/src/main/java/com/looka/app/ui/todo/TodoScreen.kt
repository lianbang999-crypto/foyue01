package com.looka.app.ui.todo

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
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
 * ToDo 中枢页（对齐 Lifebear ToDo 结构，§77 N4/N5 调序后）：
 * 搜索（页首）→ 星标 / 未来7天 → 清单（带色/计数）→ 已完成任务 / 已完成清单
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
    val archived = remember(lists) { lists.filter { it.archived } }
    val activeUids = remember(active) { active.map { it.uid }.toSet() }
    val openCount = remember(tasks) {
        tasks.filter { !it.done }.groupingBy { it.listUid }.eachCount()
    }
    val starredCount = remember(tasks, activeUids) {
        tasks.count { !it.done && it.starred && it.listUid in activeUids }
    }
    val today = Fmt.today()
    val next7Count = remember(tasks, activeUids) {
        tasks.count { !it.done && it.dueDay in 0..(today + 7) && it.listUid in activeUids }
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
            // §77 N4：顺序对齐 Lifebear —— 星标/未来7天 在清单之前
            //（Lifebear：搜索 → マイリスト/星付き/次の7日間 → リスト → ラベル → 完了済み。
            //  ラベル 是标签体系，我们还没有，见 P4-8）
            item {
                HubRow(Icons.Filled.Star, Color(0xFFF2B23D), tr("星标"), "$starredCount") {
                    nav.navigate("starred")
                }
            }
            item {
                HubRow(Icons.Outlined.Event, Ink, tr("未来 7 天"), "$next7Count") {
                    nav.navigate("next7")
                }
            }
            item { Spacer(Modifier.height(8.dp)) }

            // 清单
            items(active, key = { it.uid }) { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rowClick { nav.navigate("list/${l.uid}") }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(l.colorHex), 13.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(l.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    val c = openCount[l.uid] ?: 0
                    if (c > 0) Text("$c", fontSize = 13.sp, color = GrayText)
                    Icon(
                        Icons.Default.ChevronRight, null,
                        tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp)
                    )
                }
                Hairline(Modifier.padding(start = 16.dp))
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rowClick { createDlg = true }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, tr("新建清单"), tint = GrayText, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(tr("新建清单"), fontSize = 15.sp, color = GrayText)
                }
                Hairline()
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                HubRow(Icons.Outlined.CheckCircle, GrayText, tr("已完成任务"), null) {
                    nav.navigate("doneTasks")
                }
            }
            item {
                HubRow(
                    Icons.Outlined.Inventory2, GrayText, tr("已完成清单"),
                    if (archived.isNotEmpty()) "${archived.size}" else null
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

@Composable
private fun HubRow(
    icon: ImageVector,
    tint: Color,
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
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = GrayText)
        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
    }
    Hairline(Modifier.padding(start = 16.dp))
}

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
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/**
 * ToDo 中枢页（对齐 Lifebear ToDo 结构）：
 * 清单（带色/计数）→ 星标 / 未来7天 → 已完成任务 / 已完成清单
 */
@Composable
fun TodoScreen(vm: LookaViewModel, nav: NavHostController) {
    val lists by vm.taskLists.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var createDlg by remember { mutableStateOf(false) }

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
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr("待办"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // §71 A：AI 全站入口（用户拍板）
            androidx.compose.material3.IconButton(onClick = { nav.navigate("aiChat") }) {
                com.looka.app.ui.common.DeerBadge(24.dp)
            }
        }
        Hairline()

        LazyColumn {
            // 搜索条（跳全局搜索）
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PanelBg)
                        .plainClick { nav.navigate("search") }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Search, tr("搜索"), tint = GrayText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("搜索任务…"), fontSize = 13.sp, color = Color(0xFFB9BBB9))
                }
            }

            // 清单
            items(active, key = { it.uid }) { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainClick { nav.navigate("list/${l.uid}") }
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
                        .plainClick { createDlg = true }
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
            .plainClick(onClick)
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

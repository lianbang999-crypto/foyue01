package com.looka.app.ui.todo

import com.looka.app.ui.theme.LkIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ui.common.safeBack
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel

/**
 * §85 B5（V013 [B]）：Task 原生详情页 —— Task 不是 Event 的弱化版。
 * 高频状态（完成/星标）在页面本体直接切换、无确认；低频动作收进 More 锚定菜单：
 * Copy = 用原任务生成**预填的新 Draft**（不是原地复制，V013「Copy ≠ Duplicate immediately」），
 * Delete = 阻断式二次确认。轻动作与危险动作分层（v1.3 §22.2）。
 */
@Composable
fun TaskDetailScreen(vm: LookaViewModel, nav: NavHostController, taskId: Long) {
    val tasks by vm.tasks.collectAsState()
    val lists by vm.taskLists.collectAsState()
    val t = tasks.find { it.id == taskId }
    if (t == null) {
        // 被删 / 同步端移除：安静退回，不弹错误
        LaunchedEffect(Unit) { safeBack(nav) }
        return
    }
    val list = lists.find { it.uid == t.listUid }
    var moreMenu by remember { mutableStateOf(false) }
    var editDlg by remember { mutableStateOf(false) }
    var copyDlg by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
    ) {
        LookaTopBar(tr("任务"), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { editDlg = true }) {
                Icon(LkIcons.Edit, tr("编辑"), tint = Ink, modifier = Modifier.size(20.dp))
            }
            // More：锚定菜单、无全屏遮罩（V013 [B]）——轻菜单只做选择，选完即关
            Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(LkIcons.More, tr("更多"), tint = Ink, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = moreMenu, onDismissRequest = { moreMenu = false },
                    containerColor = Color.White
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("复制"), fontSize = 14.sp) },
                        onClick = { moreMenu = false; copyDlg = true }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("删除"), fontSize = 14.sp, color = HolidayRed) },
                        onClick = { moreMenu = false; delDlg = true }
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 标题行：完成圆（直接切换，无确认）+ 标题 + 星标（直接切换）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (t.done) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.5.dp, if (t.done) MaterialTheme.colorScheme.primary else Color(0xFFB9BBB9), CircleShape)
                        .plainClick { vm.toggleTask(t) },
                    contentAlignment = Alignment.Center
                ) {
                    if (t.done) Icon(LkIcons.Check, tr("已完成"), tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    t.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    color = if (t.done) GrayText else Ink,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.setTaskStar(t, !t.starred) }) {
                    if (t.starred) Icon(LkIcons.StarFill, tr("取消星标"), tint = Color(0xFFF2B23D), modifier = Modifier.size(24.dp))
                    else Icon(LkIcons.Star, tr("加星标"), tint = GrayText, modifier = Modifier.size(24.dp))
                }
            }
            Hairline()

            // 字段区：只读摘要，修改统一走右上「编辑」（高频直达，低频收纳）
            DetailField(tr("清单")) {
                ColorDot(parseHex(list?.colorHex ?: "#5C6670"), 11.dp)
                Spacer(Modifier.width(8.dp))
                Text(list?.name ?: tr("默认清单"), fontSize = 14.sp, color = Ink)
            }
            Hairline()
            DetailField(tr("日期")) {
                Text(
                    if (t.dueDay >= 0) Fmt.dateFull(t.dueDay) else tr("无日期"),
                    fontSize = 14.sp, color = if (t.dueDay >= 0) Ink else GrayText
                )
            }
            Hairline()
            if (t.memo.isNotBlank()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr("备注"), fontSize = 12.sp, color = GrayText)
                    Spacer(Modifier.width(0.dp))
                    Text(t.memo, fontSize = 14.sp, color = Ink, lineHeight = 22.sp)
                }
                Hairline()
            }
            if (t.done && t.doneAt > 0) {
                DetailField(tr("完成于")) {
                    // 毫秒 → 本地日：与已完成列表同一换算（直接 /86400000 是 UTC 日，CST 早晨会错一天）
                    val day = java.time.Instant.ofEpochMilli(t.doneAt)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    Text(Fmt.dateFull(day), fontSize = 13.sp, color = GrayText)
                }
                Hairline()
            }
        }
    }

    // 编辑：复用现有对话框（字段级修改的事务边界仍是「保存」）
    if (editDlg) TaskEditDialog(
        vm, t, lists.filter { !it.archived },
        onDismiss = { editDlg = false }
    )
    // 复制：预填的新 Draft —— 新对象、原任务不动；取消零残留，保存才成为新任务
    if (copyDlg) TaskEditDialog(
        vm, t.copy(id = 0, done = false, doneAt = -1L),
        lists.filter { !it.archived },
        isNew = true,
        onDismiss = { copyDlg = false }
    )
    if (delDlg) ConfirmDialog(
        title = tr("删除这个任务？"),
        text = t.title,
        onConfirm = { delDlg = false; vm.deleteTask(t); safeBack(nav) },
        onDismiss = { delDlg = false }
    )
}

@Composable
private fun DetailField(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = GrayText, modifier = Modifier.width(64.dp))
        content()
    }
}

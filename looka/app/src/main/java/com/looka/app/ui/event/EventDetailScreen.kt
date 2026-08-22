package com.looka.app.ui.event

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import com.looka.app.data.Occ
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Reminder
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

/**
 * 日程详情（规格 CAL-012）：
 * 只读展示 + 编辑铅笔 + More（分享/复制/删除）；重复日程显示当前实例日期 + 系列摘要。
 */
@Composable
fun EventDetailScreen(vm: LookaViewModel, nav: NavHostController, sid: Long, occDay: Long) {
    val series by vm.seriesAll.collectAsState()
    val exceptions by vm.exceptionsAll.collectAsState()
    val cats by vm.categories.collectAsState()

    val s = series.find { it.id == sid }
    val ex = exceptions.find { it.seriesId == sid && it.occurrenceDay == occDay }
    val o: Occ? = s
        ?.takeIf { it.untilDay < 0 || occDay <= it.untilDay }
        ?.let { RecurrenceEngine.mergeOcc(it, occDay, ex) }

    // 被删除/取消后自动返回日历（AC-008）
    if (s == null || o == null) {
        LaunchedEffect(sid, occDay) { nav.popBackStack() }
        return
    }

    val cat = cats.find { it.id == o.categoryId }
    val reminders by produceState(initialValue = emptyList<Reminder>(), sid, series) {
        value = vm.remindersOf(sid)
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var moreOpen by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }
    var delScopeDlg by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("详情"), onBack = { nav.popBackStack() }) {
            // 高频编辑：独立铅笔（规格 §10.2）
            IconButton(onClick = {
                scope.launch {
                    if (vm.prepareEditDraft(sid, occDay)) nav.navigate("editor")
                }
            }) { Icon(Icons.Outlined.Edit, tr("编辑"), tint = Ink) }
            // 低频动作收进 More
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Default.MoreVert, tr("更多"), tint = Ink)
                }
                DropdownMenu(
                    expanded = moreOpen,
                    onDismissRequest = { moreOpen = false },
                    containerColor = Color.White
                ) {
                    DropdownMenuItem(text = { Text(tr("分享")) }, onClick = {
                        moreOpen = false
                        val text = "【${o.title}】${dateLine(o)}" +
                                (if (o.location.isNotBlank()) " · ${o.location}" else "")
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, text),
                                tr("分享日程")
                            )
                        )
                    })
                    DropdownMenuItem(text = { Text(tr("复制")) }, onClick = {
                        moreOpen = false
                        vm.duplicateOcc(o) { toast(ctx, tr("已复制一份")) }
                    })
                    DropdownMenuItem(text = { Text(tr("删除"), color = MaterialThemeErrorColor()) }, onClick = {
                        moreOpen = false
                        if (o.recurring) delScopeDlg = true else delDlg = true
                    })
                }
            }
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.padding(start = 20.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColorDot(parseHex(cat?.colorHex ?: "#9AA0A6"), 10.dp)
                Spacer(Modifier.width(8.dp))
                Text(cat?.name ?: tr("未分类"), fontSize = 12.sp, color = GrayText)
            }
            Text(
                o.title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Text(
                dateLine(o), fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            if (o.recurring && s.freq != 0) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Repeat, null, tint = GrayText, modifier = Modifier.size(14.dp))
                    Text(
                        " " + RecurrenceEngine.summary(
                            s.freq, s.interval, s.weekdays, s.monthlyByWeekday, s.untilDay, s.startDay
                        ),
                        fontSize = 12.sp, color = GrayText
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Hairline()

            if (reminders.isNotEmpty()) {
                DetailRow(
                    Icons.Outlined.Notifications,
                    reminders.joinToString("、") {
                        Fmt.reminderText(o.allDay, it) + if (!it.enabled) tr("（已关）") else ""
                    }
                )
                Hairline()
            }
            if (o.location.isNotBlank()) {
                DetailRow(Icons.Outlined.Place, o.location)
                Hairline()
            }
            if (o.memo.isNotBlank()) {
                DetailRow(Icons.Outlined.Notes, o.memo)
                Hairline()
            }
        }
    }

    // 普通删除二次确认（AC-008）
    if (delDlg) ConfirmDialog(
        title = tr("删除该日程？"),
        text = tr("「{0}」将从日历中移除", o.title),
        onConfirm = {
            delDlg = false
            vm.deleteSeries(sid)   // 数据消失后详情自动返回
        },
        onDismiss = { delDlg = false }
    )

    // 重复日程删除范围（与编辑同款三范围）
    if (delScopeDlg) ScopeDialog(
        title = tr("删除哪些日程？"),
        onPick = { sc ->
            delScopeDlg = false
            when (sc) {
                0 -> vm.deleteThisOnly(sid, occDay)
                1 -> vm.deleteFuture(sid, occDay)
                else -> vm.deleteSeries(sid)
            }
        },
        onDismiss = { delScopeDlg = false }
    )
}

@Composable
private fun MaterialThemeErrorColor(): Color = com.looka.app.ui.theme.HolidayRed

@Composable
private fun DetailRow(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Icon(icon, null, tint = GrayText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

private fun dateLine(o: Occ): String = when {
    o.allDay && o.endDay > o.day -> tr("{0} - {1} · 全天", Fmt.dateCn(o.day), Fmt.dateCn(o.endDay))
    o.allDay -> tr("{0} · 全天", Fmt.dateFull(o.day))
    o.endDay > o.day -> "${Fmt.dateCn(o.day)} ${Fmt.hm(o.startMin)} - ${Fmt.dateCn(o.endDay)} ${Fmt.hm(o.endMin)}"
    else -> "${Fmt.dateFull(o.day)}  ${Fmt.hm(o.startMin)} - ${Fmt.hm(o.endMin)}"
}

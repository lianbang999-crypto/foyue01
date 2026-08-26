package com.looka.app.ui.event

import com.looka.app.ui.theme.LkIcons

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.Reminder
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTimePicker
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.SaveButton
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.rowClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel

// §85 批 B：父/子编辑器的三个全页子编辑器。都操作 vm.draft（父 Event Editor 的草稿），
// 遵守 V011 事务边界 —— 这里只改 draft，父页 Save 才写持久层。
// 模板编辑器（TemplateScreens）用的是自己的局部 draft，仍走旧 Sheet，本批挂账。

/**
 * §85 B1（V011 7.1 [B]）：分类全页选择器。
 * 色点 + 名称 + 当前勾；点新项 = select-and-return，不设确认按钮 ——
 * 「单选一次 tap 已表达完整意图，多一个确定就是多一次打扰」（v1.3 §21.2）。
 */
@Composable
fun CategoryPickScreen(vm: LookaViewModel, nav: NavHostController) {
    val d = vm.draft
    if (d == null) {
        // 进程重建后草稿没了：与父编辑器同款守卫，安静退回
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val cats by vm.categories.collectAsState()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
    ) {
        LookaTopBar(tr("分类"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            cats.forEach { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .rowClick { d.categoryId = c.id; nav.popBackStack() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(c.colorHex), 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        c.name + if (!c.visible) tr("（已隐藏）") else "",
                        fontSize = 15.sp, modifier = Modifier.weight(1f),
                        color = if (c.visible) Ink else GrayText
                    )
                    if (c.id == d.categoryId) {
                        Icon(LkIcons.Check, tr("已选中"), tint = Ink, modifier = Modifier.size(18.dp))
                    }
                }
                Hairline()
            }
            // V011：「创建颜色」是选择器同层级的新增入口，进更深编辑页，不在选择器上堆字段
            NavRow(tr("管理分类")) { nav.navigate("categories") }
        }
    }
}

/**
 * §85 B2（V011 7.2 [B]）：提醒列表管理器 —— 两层结构的第一层。
 * 每行右侧启停；＋ 行进入创建页。Event : Reminder = 1:N（数据层本就是列表）。
 */
@Composable
fun ReminderListScreen(vm: LookaViewModel, nav: NavHostController) {
    val d = vm.draft
    if (d == null) {
        // 进程重建后草稿没了：与父编辑器同款守卫，安静退回
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
    ) {
        LookaTopBar(tr("提醒"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (d.reminders.isEmpty()) {
                Text(
                    tr("暂无提醒"), fontSize = 13.sp, color = GrayText,
                    modifier = Modifier.padding(16.dp)
                )
            }
            d.reminders.forEachIndexed { i, r ->
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Fmt.reminderText(d.allDay, r), fontSize = 15.sp,
                            color = if (r.enabled) Ink else GrayText,
                            modifier = Modifier.weight(1f)
                        )
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
                            Icon(LkIcons.Trash, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                        }
                    }
                    // A2-5：提醒响一声可划走；闹钟持续响必须手动停 —— 语义保留自旧 Sheet
                    if (r.enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("响铃到点后持续提醒（闹钟式）"), fontSize = 12.sp, color = GrayText, modifier = Modifier.weight(1f))
                            Switch(
                                checked = r.alarm,
                                onCheckedChange = {
                                    d.reminders[i] = r.copy(alarm = it)
                                    d.remindersTouched = true
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Hairline(Modifier.padding(start = 16.dp))
            }
            Row(
                Modifier.fillMaxWidth()
                    .rowClick { nav.navigate("reminderNew") }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(LkIcons.Plus, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(14.dp))
                Text(tr("添加提醒"), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            }
            Hairline()
        }
    }
}

/**
 * §85 B2（V011 7.3 [B]）：提醒创建页 —— 两层结构的第二层。
 * X 取消 / 深色 Save 显式提交；快捷规则用 radio 单选；全天日程另有提醒时刻。
 * 只有 Save 才把规则写进 draft；X 走人零残留（FORM-005 的合同）。
 */
@Composable
fun ReminderCreateScreen(vm: LookaViewModel, nav: NavHostController) {
    val d = vm.draft
    if (d == null) {
        // 进程重建后草稿没了：与父编辑器同款守卫，安静退回
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    // 默认选中首个常用项：定时=准时；全天=当天（8:00）
    var sel by remember { mutableIntStateOf(0) }
    var timeOfDay by remember { mutableIntStateOf(480) }
    var timeDlg by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
    ) {
        LookaTopBar(
            tr("添加提醒"),
            onBack = { nav.popBackStack() },
            backIcon = LkIcons.Close
        ) {
            SaveButton {
                d.reminders.add(
                    if (d.allDay) Reminder(daysBefore = sel, timeOfDayMin = timeOfDay)
                    else Reminder(minutesBefore = sel)
                )
                d.remindersTouched = true
                nav.popBackStack()
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val options =
                if (d.allDay) listOf(0 to tr("当天"), 1 to tr("1天前"), 2 to tr("2天前"), 3 to tr("3天前"), 7 to tr("1周前"))
                else listOf(
                    0 to tr("准时"), 5 to tr("5分钟前"), 10 to tr("10分钟前"), 15 to tr("15分钟前"),
                    30 to tr("30分钟前"), 60 to tr("1小时前"), 120 to tr("2小时前"), 1440 to tr("1天前")
                )
            if (d.allDay) {
                // 全天：时刻是主值（V011 的 Time 区），快捷天数是 radio
                NavRow(tr("提醒时刻"), value = Fmt.hm(timeOfDay)) { timeDlg = true }
                Hairline()
            }
            options.forEach { (v, label) ->
                Row(
                    Modifier.fillMaxWidth()
                        .rowClick { sel = v }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = sel == v, onClick = { sel = v })
                    Text(label, fontSize = 15.sp)
                }
            }
            Text(
                tr("提醒响一声可划走；需要一直响的闹钟，保存后在列表里打开「闹钟式」"),
                fontSize = 12.sp, color = GrayText,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    if (timeDlg) LookaTimePicker(
        initialMin = timeOfDay,
        onPick = { timeOfDay = it },
        onDismiss = { timeDlg = false }
    )
}

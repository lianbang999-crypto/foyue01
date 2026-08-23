package com.looka.app.ui.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color as UiColor
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.toast
import com.looka.app.util.SysCal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.navigation.NavHostController
import com.looka.app.data.Prefs
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTimePicker
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.RadioDialog
import com.looka.app.ui.common.SwitchRow
import com.looka.app.ui.theme.GrayText
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

/** 日历显示 + 新建默认值设置（规格 CAL-060/061/072 精简版） */
@Composable
fun CalendarSettingsScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cats by vm.categories.collectAsState()

    var weekStartMon by remember { mutableStateOf(Prefs.weekStartMonday(ctx)) }
    var holidayMask by remember { mutableIntStateOf(Prefs.holidayMask(ctx)) }
    var showDone by remember { mutableStateOf(Prefs.showDoneTasks(ctx)) }
    var defCat by remember { mutableStateOf(Prefs.defaultCategoryId(ctx)) }
    var defAllDay by remember { mutableStateOf(Prefs.defaultAllDay(ctx)) }
    var timedRem by remember { mutableIntStateOf(Prefs.defTimedReminderMin(ctx)) }
    var alldayRemDays by remember { mutableIntStateOf(Prefs.defAllDayReminderDays(ctx)) }
    var alldayRemTime by remember { mutableIntStateOf(Prefs.defAllDayReminderTime(ctx)) }

    var showLunar by remember { mutableStateOf(Prefs.showLunarRaw(ctx) ?: com.looka.app.util.I18n.isZh()) }
    var use12h by remember { mutableStateOf(com.looka.app.util.I18n.use12h) }
    var fontDlg by remember { mutableStateOf(false) }
    var taskRemOn by remember { mutableStateOf(Prefs.taskRemOn(ctx)) }
    var taskRemMin by remember { mutableIntStateOf(Prefs.taskRemMin(ctx)) }
    var taskRemTimeDlg by remember { mutableStateOf(false) }
    var showSys by remember { mutableStateOf(Prefs.showSysCal(ctx)) }
    var hiddenCals by remember { mutableStateOf(Prefs.hiddenSysCals(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showSys = true
            Prefs.setShowSysCal(ctx, true)
            vm.bumpSettings()
        } else {
            toast(ctx, tr("未授予日历读取权限"))
        }
    }

    var weekStartDlg by remember { mutableStateOf(false) }
    var holidayDlg by remember { mutableStateOf(false) }
    var defCatDlg by remember { mutableStateOf(false) }
    var timedRemDlg by remember { mutableStateOf(false) }
    var alldayRemDlg by remember { mutableStateOf(false) }
    var alldayTimeDlg by remember { mutableStateOf(false) }

    val weekNames = listOf(tr("周一"), tr("周二"), tr("周三"), tr("周四"), tr("周五"), tr("周六"), tr("周日"))

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("日历设置"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // E5（§57）：分类管理并入日历设置（更多页 16→8）
            NavRow(tr("分类管理")) { nav.navigate("categories") }
            Hairline()
            SectionLabel(tr("显示"))
            NavRow(
                tr("日程文字大小"),
                value = when (com.looka.app.data.Prefs.eventTextSize(ctx)) {
                    0 -> tr("大"); 1 -> tr("中"); else -> tr("小")
                }
            ) { fontDlg = true }
            Hairline()
            NavRow(tr("一周开始日"), value = if (weekStartMon) tr("周一") else tr("周日")) { weekStartDlg = true }
            Hairline()
            NavRow(
                tr("休日星期"),
                value = (0..6).filter { (holidayMask shr it) and 1 == 1 }
                    .joinToString(" ") { weekNames[it] }
                    .ifBlank { tr("无") }
            ) { holidayDlg = true }
            Hairline()
            SwitchRow(tr("在日历显示已完成任务"), showDone) {
                showDone = it; Prefs.setShowDoneTasks(ctx, it); vm.bumpSettings(); com.looka.app.net.SyncEngine.kick(ctx.applicationContext as com.looka.app.LookaApp)
            }
            Hairline()
            SwitchRow(
                tr("显示农历与节日"), showLunar,
                subtitle = tr("月/周/日视图中的农历、节假日")
            ) { showLunar = it; Prefs.setShowLunar(ctx, it); vm.bumpSettings(); com.looka.app.net.SyncEngine.kick(ctx.applicationContext as com.looka.app.LookaApp) }
            Hairline()
            SwitchRow(
                tr("12 小时制"), use12h,
                subtitle = tr("时间显示为 9:30 AM 形式")
            ) { use12h = it; com.looka.app.util.I18n.setUse12h(ctx, it); vm.bumpSettings() }
            Hairline()

            SectionLabel(tr("新建日程默认值"))
            NavRow(tr("默认分类"), value = cats.find { it.id == defCat }?.name ?: tr("未分类")) { defCatDlg = true }
            Hairline()
            SwitchRow(tr("默认全天"), defAllDay) {
                defAllDay = it; Prefs.setDefaultAllDay(ctx, it); vm.bumpSettings()
            }
            Hairline()
            NavRow(
                tr("时间日程默认提醒"),
                value = when {
                    timedRem < 0 -> tr("无")
                    timedRem == 0 -> tr("准时")
                    timedRem < 60 -> tr("{0}分钟前", timedRem)
                    else -> tr("{0}小时前", timedRem / 60)
                }
            ) { timedRemDlg = true }
            Hairline()
            NavRow(
                tr("全天日程默认提醒"),
                value = if (alldayRemDays < 0) tr("无")
                else "${if (alldayRemDays == 0) "当天" else "${alldayRemDays}天前"} ${Fmt.hm(alldayRemTime)}"
            ) { alldayRemDlg = true }
            Hairline()
            // CAL-062（§70）：模板独立入口 —— 常用日程存"是什么"，创建时 Context 给"何时"
            NavRow(tr("日程模板")) { nav.navigate("templates") }
            Hairline()

            SectionLabel(tr("任务提醒"))
            SwitchRow(
                tr("到期任务提醒"), taskRemOn,
                subtitle = tr("任务截止当天按时提醒")
            ) { on ->
                taskRemOn = on; Prefs.setTaskRemOn(ctx, on)
                vm.bumpSettings()
                (ctx.applicationContext as com.looka.app.LookaApp).let { app ->
                    app.appScope.launch { com.looka.app.notify.NotifyScheduler.rescheduleFromDb(app) }
                }
            }
            Hairline()
            if (taskRemOn) {
                NavRow(tr("提醒时刻"), value = Fmt.hm(taskRemMin)) { taskRemTimeDlg = true }
                Hairline()
            }
            NavRow(tr("提醒自检"), value = tr("提醒不响时来这里")) { nav.navigate("selfcheck") }
            Hairline()

            // 系统日历聚合（规格 CAL-080：显示与同步分离，只读展示）
            SectionLabel(tr("系统日历（只读显示）"))
            SwitchRow(
                tr("在日历中显示系统日历"),
                showSys,
                subtitle = tr("聚合手机上系统/Google 账户的日程")
            ) { on ->
                if (on) {
                    if (SysCal.hasPermission(ctx)) {
                        showSys = true; Prefs.setShowSysCal(ctx, true); vm.bumpSettings()
                    } else {
                        permLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                } else {
                    showSys = false; Prefs.setShowSysCal(ctx, false); vm.bumpSettings()
                }
            }
            Hairline()
            if (showSys) {
                val sysCals by produceState(initialValue = emptyList<SysCal.SysCalendar>(), showSys) {
                    value = SysCal.calendars(ctx)
                }
                if (sysCals.isEmpty()) {
                    Text(
                        tr("没有找到可显示的系统日历"), fontSize = 12.sp, color = GrayText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                sysCals.forEach { cal ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = cal.id.toString() !in hiddenCals,
                            onCheckedChange = { chk ->
                                hiddenCals = if (chk) hiddenCals - cal.id.toString()
                                else hiddenCals + cal.id.toString()
                                Prefs.setHiddenSysCals(ctx, hiddenCals)
                                vm.bumpSettings()
                            }
                        )
                        ColorDot(UiColor(cal.color), 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(cal.name, fontSize = 14.sp)
                    }
                }
                Hairline()
            }
            Spacer(Modifier.width(1.dp))
        }
    }

    // E2（§70 图示代替文字）：字号三档所见即所得 —— 样例直接按月视图里的真实字号渲染
    if (fontDlg) AlertDialog(
        onDismissRequest = { fontDlg = false },
        title = { Text(tr("日程文字大小"), fontSize = 17.sp) },
        text = {
            Column {
                val cur = com.looka.app.data.Prefs.eventTextSize(ctx)
                listOf(0 to tr("大"), 1 to tr("中"), 2 to tr("小")).forEach { (v, label) ->
                    val fs = when (v) { 0 -> 11.5.sp; 1 -> 10.sp; else -> 8.sp }
                    val pick = {
                        com.looka.app.data.Prefs.setEventTextSize(ctx, v); vm.bumpSettings(); fontDlg = false
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { pick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = cur == v, onClick = pick)
                        Text(label, fontSize = 15.sp)
                        Spacer(Modifier.width(14.dp))
                        ColorDot(MaterialTheme.colorScheme.primary, 6.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("晨跑 9:00"), fontSize = fs, color = GrayText)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { fontDlg = false }) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
    // E2（§70）：一周开始日 —— 每个选项直接给出整排周条预览
    if (weekStartDlg) AlertDialog(
        onDismissRequest = { weekStartDlg = false },
        title = { Text(tr("一周开始日"), fontSize = 17.sp) },
        text = {
            Column {
                listOf(true to tr("周一"), false to tr("周日")).forEach { (v, label) ->
                    val pick = {
                        weekStartMon = v; Prefs.setWeekStartMonday(ctx, v); vm.bumpSettings()
                        com.looka.app.net.SyncEngine.kick(ctx.applicationContext as com.looka.app.LookaApp)
                        weekStartDlg = false
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { pick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = weekStartMon == v, onClick = pick)
                        Text(label, fontSize = 15.sp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            (if (v) (1..7) else listOf(7, 1, 2, 3, 4, 5, 6))
                                .joinToString("  ") { Fmt.week(it) },
                            fontSize = 11.sp, color = GrayText
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { weekStartDlg = false }) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )

    if (holidayDlg) AlertDialog(
        onDismissRequest = { holidayDlg = false },
        title = { Text(tr("休日星期"), fontSize = 17.sp) },
        text = {
            Column {
                // E2（§70）：不解释"会变红"—— 勾上的星期名当场变红，所见即所得
                for (i in 0..6) {
                    val on = (holidayMask shr i) and 1 == 1
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            holidayMask = holidayMask xor (1 shl i)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { holidayMask = holidayMask xor (1 shl i) }
                        )
                        Text(
                            weekNames[i], fontSize = 15.sp,
                            color = if (on) com.looka.app.ui.theme.HolidayRed else com.looka.app.ui.theme.Ink,
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Medium
                                         else androidx.compose.ui.text.font.FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.setHolidayMask(ctx, holidayMask); vm.bumpSettings(); holidayDlg = false; com.looka.app.net.SyncEngine.kick(ctx.applicationContext as com.looka.app.LookaApp)
            }) { Text(tr("确定")) }
        },
        dismissButton = {
            TextButton(onClick = {
                holidayMask = Prefs.holidayMask(ctx); holidayDlg = false
            }) { Text(tr("取消"), color = GrayText) }
        },
        containerColor = Color.White
    )

    if (defCatDlg) RadioDialog(
        tr("默认分类"),
        options = cats.map { it.id to it.name },
        selected = defCat,
        onSelect = { defCat = it; Prefs.setDefaultCategoryId(ctx, it); vm.bumpSettings() },
        onDismiss = { defCatDlg = false }
    )

    if (timedRemDlg) RadioDialog(
        tr("时间日程默认提醒"),
        options = listOf(
            -1 to tr("无"), 0 to tr("准时"), 5 to tr("5分钟前"), 10 to tr("10分钟前"),
            15 to tr("15分钟前"), 30 to tr("30分钟前"), 60 to tr("1小时前"), 120 to tr("2小时前")
        ),
        selected = timedRem,
        onSelect = { timedRem = it; Prefs.setDefTimedReminderMin(ctx, it); vm.bumpSettings() },
        onDismiss = { timedRemDlg = false }
    )

    if (alldayRemDlg) RadioDialog(
        tr("全天日程默认提醒"),
        options = listOf(
            -1 to tr("无"), 0 to tr("当天"), 1 to tr("1天前"), 2 to tr("2天前"), 3 to tr("3天前"), 7 to tr("1周前")
        ),
        selected = alldayRemDays,
        onSelect = {
            alldayRemDays = it
            Prefs.setDefAllDayReminderDays(ctx, it)
            vm.bumpSettings()
            if (it >= 0) alldayTimeDlg = true
        },
        onDismiss = { alldayRemDlg = false }
    )

    if (taskRemTimeDlg) LookaTimePicker(
        initialMin = taskRemMin,
        onPick = { m ->
            taskRemMin = m; Prefs.setTaskRemMin(ctx, m)
            (ctx.applicationContext as com.looka.app.LookaApp).let { app ->
                app.appScope.launch { com.looka.app.notify.NotifyScheduler.rescheduleFromDb(app) }
            }
        },
        onDismiss = { taskRemTimeDlg = false }
    )

    if (alldayTimeDlg) LookaTimePicker(
        initialMin = alldayRemTime,
        onPick = {
            alldayRemTime = it; Prefs.setDefAllDayReminderTime(ctx, it); vm.bumpSettings()
        },
        onDismiss = { alldayTimeDlg = false }
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, color = GrayText,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

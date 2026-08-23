@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.looka.app.ui.event

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.Template
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTimePicker
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.SaveButton
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel
import org.json.JSONObject

/**
 * CAL-062（§70）：日程模板独立入口 —— 管理页。
 * Template = "这类事情是什么"；日期由创建时的 Context 提供，所以这里没有任何日期。
 */
@Composable
fun TemplateManageScreen(vm: LookaViewModel, nav: NavHostController) {
    val tpls by vm.templates.collectAsState()
    val cats by vm.categories.collectAsState()
    var delTpl by remember { mutableStateOf<Template?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("日程模板"), onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (tpls.isEmpty()) {
                EmptyDeer(
                    tr("常用的日程存成模板，创建时一键带入"),
                    hint = tr("点下方「＋ 新建模板」开始 ↓")
                )
            }
            tpls.forEach { t ->
                val sub = remember(t.payload, cats) {
                    try {
                        val o = JSONObject(t.payload)
                        val time = if (o.optBoolean("allDay")) tr("全天")
                        else "${Fmt.hm(o.optInt("startMin"))}–${Fmt.hm(o.optInt("endMin"))}"
                        val cat = cats.find { it.uid == o.optString("categoryUid") }?.name
                        listOfNotNull(time, cat).joinToString(" · ")
                    } catch (_: Exception) { "" }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .plainClick { nav.navigate("template/${t.id}") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Bookmarks, null, tint = GrayText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 15.sp)
                        if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = GrayText)
                    }
                    IconButton(onClick = { delTpl = t }) {
                        Icon(Icons.Outlined.Delete, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                    }
                }
                Hairline()
            }
            TextButton(
                onClick = { nav.navigate("template/-1") },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(tr("＋ 新建模板"), color = MaterialTheme.colorScheme.primary) }
        }
    }

    delTpl?.let { t ->
        ConfirmDialog(
            title = tr("删除模板「{0}」？", t.title),
            onConfirm = { vm.deleteTemplate(t); delTpl = null },
            onDismiss = { delTpl = null }
        )
    }
}

/**
 * CAL-062（§70）：独立模板编辑页。
 * 字段按母档冻结：标题 / 全天+时间 / 分类 / 提醒 / 地点 / 备注 —— 无日期、无重复。
 */
@Composable
fun TemplateEditorScreen(vm: LookaViewModel, nav: NavHostController, tplId: Long) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tpls by vm.templates.collectAsState()
    val cats by vm.categories.collectAsState()
    val isEdit = tplId > 0
    // 草稿只建一次；模板流是内存态，进入时按 id 取快照
    val d = remember(tplId) { vm.templateDraft(tpls.find { it.id == tplId }) }

    var startTimeDlg by remember { mutableStateOf(false) }
    var endTimeDlg by remember { mutableStateOf(false) }
    var catSheet by remember { mutableStateOf(false) }
    var remSheet by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.Close, tr("关闭"), tint = Ink) }
            Text(
                if (isEdit) tr("编辑模板") else tr("新建模板"),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            SaveButton(enabled = d.title.isNotBlank()) {
                vm.upsertTemplate(tplId, d) {
                    toast(ctx, tr("已保存"))
                    nav.popBackStack()
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Hairline()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TextField(
                value = d.title,
                onValueChange = { d.title = it },
                placeholder = { Text(tr("模板名，例：晨跑"), fontSize = 18.sp, color = Color(0xFFB9BBB9)) },
                textStyle = TextStyle(fontSize = 18.sp),
                colors = clearFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp)
            )
            Hairline()

            // 时间结构（无日期 —— 日期在创建时由所点的那天提供）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!d.allDay) {
                    Text(
                        Fmt.hm(d.startMin), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.plainClick { startTimeDlg = true }
                    )
                    Icon(
                        Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9),
                        modifier = Modifier.padding(horizontal = 10.dp).size(20.dp)
                    )
                    Text(
                        Fmt.hm(d.endMin), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.plainClick { endTimeDlg = true }
                    )
                } else {
                    Text(tr("全天"), fontSize = 15.sp, color = GrayText)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(
                        checked = d.allDay,
                        onCheckedChange = {
                            d.allDay = it
                            vm.refreshDefaultReminder(d)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                    Text(tr("全天"), fontSize = 11.sp, color = GrayText)
                }
            }
            Hairline()

            // 分类
            val cat = cats.find { it.id == d.categoryId }
            Row(
                Modifier.fillMaxWidth().plainClick { catSheet = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = GrayText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                ColorDot(parseHex(cat?.colorHex ?: "#9AA0A6"), 10.dp)
                Spacer(Modifier.width(8.dp))
                Text(cat?.name ?: tr("未分类"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
            }
            Hairline()

            // 提醒
            Row(
                Modifier.fillMaxWidth().plainClick { remSheet = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Notifications, null, tint = GrayText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(tr("提醒"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    if (d.reminders.isEmpty()) tr("无")
                    else d.reminders.joinToString("、") { Fmt.reminderText(d.allDay, it) },
                    fontSize = 14.sp, color = GrayText
                )
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
            }
            Hairline()

            // 地点
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Place, null, tint = GrayText, modifier = Modifier.size(20.dp))
                TextField(
                    value = d.location, onValueChange = { d.location = it },
                    placeholder = { Text(tr("地点"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
                    textStyle = TextStyle(fontSize = 15.sp),
                    colors = clearFieldColors(), singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Hairline()

            // 备注
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                Icon(
                    Icons.Outlined.Notes, null, tint = GrayText,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                TextField(
                    value = d.memo, onValueChange = { d.memo = it },
                    placeholder = { Text(tr("备注"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
                    textStyle = TextStyle(fontSize = 15.sp),
                    colors = clearFieldColors(), minLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
            Hairline()
            Spacer(Modifier.height(32.dp))
        }
    }

    if (startTimeDlg) LookaTimePicker(d.startMin, onPick = { m ->
        d.startMin = m
        if (d.endMin <= m) d.endMin = minOf(m + 60, 24 * 60 - 1)
    }, onDismiss = { startTimeDlg = false })

    if (endTimeDlg) LookaTimePicker(d.endMin, onPick = { m ->
        d.endMin = m
    }, onDismiss = { endTimeDlg = false })

    if (catSheet) CategoryPickerSheet(
        vm, nav, current = d.categoryId,
        onPick = { d.categoryId = it; catSheet = false },
        onDismiss = { catSheet = false }
    )

    if (remSheet) ReminderSheet(d, onDismiss = { remSheet = false })
}

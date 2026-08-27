package com.looka.app.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.net.Api
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.LkIcons
import com.looka.app.ui.theme.SaveDark
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * §128 F2（母档 §15-18 图 10/11）：**用户共建中心** —— 一个入口，三条清楚的路。
 * 语气红线：不写"许愿池/你被选中/马上实现"；不以鹿角奖励提交；
 * 报错严重度按影响处理，Free 与 Pro 一视同仁（文案明示）。
 */
@Composable
fun FeedbackHubScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var mineCount by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        if (Api.authed(ctx)) runCatching {
            mineCount = Api.feedbackMine(ctx).optJSONArray("items")?.length() ?: 0
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("帮助 Looka 变得更好"), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                tr("报告问题、提出建议或申请定制。报错与建议永久免费，严重问题按影响处理，不看是否付费。"),
                fontSize = 12.sp, color = GrayText, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            FeedbackEntry(tr("报告问题"), tr("哪里不顺手、报错或数据异常")) {
                nav.navigate("feedback/bug")
            }
            FeedbackEntry(tr("提出建议"), tr("说说不方便和希望的结果")) {
                nav.navigate("feedback/idea")
            }
            FeedbackEntry(tr("申请定制"), tr("评估范围、时间和费用后再开始")) {
                nav.navigate("feedback/custom")
            }
            Hairline(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().plainClick { nav.navigate("feedbackMine") }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("我的提交"), fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (mineCount > 0) Text("$mineCount", fontSize = 13.sp, color = GrayText)
                Text(" ›", fontSize = 14.sp, color = GrayText)
            }
            Hairline()
            Text(
                tr("提交内容私密，不会公开正文。日记、AI 对话原文和照片永远不会被自动附带。"),
                fontSize = 11.sp, color = GrayText, lineHeight = 17.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun FeedbackEntry(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(0.8.dp, Color(0xFFE2E5E2), RoundedCornerShape(8.dp))
            .plainClick(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 11.sp, color = GrayText, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 16.sp, color = GrayText)
    }
}

/** §128 F3（图 11）：表单 —— 用户先描述，诊断信息最后选择、逐项可关 */
@Composable
fun FeedbackFormScreen(vm: LookaViewModel, nav: NavHostController, kind: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var where by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sendVer by remember { mutableStateOf(true) }
    var sendDevice by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var doneId by remember { mutableStateOf(-1L) }

    val title = when (kind) {
        "idea" -> tr("提出建议"); "custom" -> tr("申请定制"); else -> tr("报告问题")
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(title, onBack = { nav.popBackStack() })
        if (doneId > 0) {
            // 提交成功：编号 + 状态入口（保留原表单直到确认的合同由"成功后才清"保证）
            Column(Modifier.padding(20.dp)) {
                Text(tr("已收到，我们会认真查看。"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("编号 #{0}", doneId.toString()), fontSize = 12.sp, color = GrayText,
                    modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 16.dp)) {
                    Text(
                        tr("查看提交"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick {
                            nav.popBackStack(); nav.navigate("feedbackMine")
                        }.padding(8.dp)
                    )
                }
            }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            // 主描述（Label + 短示例 placeholder，图鉴输入四层合同）
            Text(
                when (kind) {
                    "idea" -> tr("哪里不方便？希望得到什么结果？")
                    "custom" -> tr("想在什么场景用？希望得到什么结果？")
                    else -> tr("发生了什么？")
                },
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = {
                    Text(
                        when (kind) {
                            "idea" -> tr("例如：想在周视图里直接拖动任务")
                            "custom" -> tr("例如：团队 5 人想共用一套清单，下月要用")
                            else -> tr("例如：保存后日程没有出现")
                        },
                        fontSize = 14.sp, color = com.looka.app.ui.theme.PlaceholderText
                    )
                },
                colors = clearFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(8.dp)).background(com.looka.app.ui.theme.PanelBg)
            )
            if (kind == "bug") {
                ChoiceRow(tr("发生在哪？"), listOf(tr("日历"), tr("待办"), tr("笔记"), tr("小鹿 AI"), tr("其他")),
                    where) { where = it }
                ChoiceRow(tr("可以再次发生吗？"), listOf(tr("每次"), tr("偶尔"), tr("只有一次")),
                    repeat) { repeat = it }
            }
            if (kind == "idea") {
                ChoiceRow(tr("和哪块有关？"), listOf(tr("日历"), tr("待办"), tr("笔记"), tr("小鹿 AI"), tr("其他")),
                    where) { where = it }
            }
            if (kind == "custom" || kind == "idea") {
                Text(
                    if (kind == "custom") tr("联系方式（必填，评估后回复你）") else tr("联系方式（选填，方便追问）"),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                TextField(
                    value = contact, onValueChange = { contact = it },
                    placeholder = { Text(tr("邮箱或其他联系方式"), fontSize = 14.sp,
                        color = com.looka.app.ui.theme.PlaceholderText) },
                    colors = clearFieldColors(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(8.dp)).background(com.looka.app.ui.theme.PanelBg)
                )
            }
            if (kind == "custom") {
                Text(
                    tr("先评估范围、时间和费用，确认后再开始；评估免费，开发单独报价。"),
                    fontSize = 11.sp, color = GrayText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            if (kind == "bug") {
                // §128 F3：诊断信息透明 —— 默认只带版本与机型，发送前逐项可关；
                // 日记正文 / AI 对话原文 / 照片 / 联系人**永不自动附带**
                Text(
                    tr("随单附带（可关）"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                DiagRow(tr("版本号 v{0}", com.looka.app.BuildConfig.VERSION_NAME), sendVer) { sendVer = it }
                DiagRow(
                    tr("设备与系统（{0}）",
                        android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                            " · Android " + android.os.Build.VERSION.RELEASE),
                    sendDevice
                ) { sendDevice = it }
                Text(
                    tr("你的日记、AI 对话原文和照片永远不会被自动附带。"),
                    fontSize = 11.sp, color = GrayText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            // 提交
            Box(
                Modifier.fillMaxWidth().padding(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (text.isNotBlank() && !busy) SaveDark else Color(0xFFC8CCC8))
                    .plainClick {
                        if (text.isBlank() || busy) return@plainClick
                        if (!Api.authed(ctx)) { toast(ctx, tr("请先在「更多 → 账号」登录")); return@plainClick }
                        if (kind == "custom" && contact.isBlank()) {
                            toast(ctx, tr("定制申请需要联系方式，评估后好回复你")); return@plainClick
                        }
                        busy = true
                        scope.launch {
                            runCatching {
                                val meta = JSONObject()
                                    .put("where", where).put("repeat", repeat).put("contact", contact)
                                    .put("ver", if (sendVer) com.looka.app.BuildConfig.VERSION_NAME +
                                        "(" + com.looka.app.BuildConfig.VERSION_CODE + ")" else "")
                                    .put("device", if (sendDevice)
                                        android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                                            " Android " + android.os.Build.VERSION.RELEASE else "")
                                val r = Api.feedbackSubmit(ctx, kind, text.trim(), meta)
                                if (r.optBoolean("ok")) doneId = r.optLong("id", 0L)
                                else toast(ctx, r.optString("error", tr("提交失败，稍后再试")))
                            }.onFailure {
                                // 失败保留表单与内容（母档 §16：提交失败不丢内容）
                                toast(ctx, it.message ?: tr("提交失败，稍后再试"))
                            }
                            busy = false
                        }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                if (busy) com.looka.app.ui.common.DeerLoading(13.sp)
                else Text(
                    when (kind) { "idea" -> tr("提交建议"); "custom" -> tr("提交申请"); else -> tr("提交问题") },
                    fontSize = 14.sp, color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, sel: String, onSel: (String) -> Unit) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    Row(Modifier.padding(horizontal = 12.dp)) {
        options.forEach { o ->
            val on = sel == o
            Box(
                Modifier.padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.White)
                    .border(0.8.dp,
                        if (on) MaterialTheme.colorScheme.primary else Color(0xFFDDE0DD),
                        RoundedCornerShape(14.dp))
                    .plainClick { onSel(if (on) "" else o) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(o, fontSize = 12.sp, color = Ink) }
        }
    }
}

@Composable
private fun DiagRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), color = Ink)
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/** §128 F2：我的提交 —— 状态可见（母档 §18 八态克制文案）、可撤回 */
@Composable
fun FeedbackMineScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    fun reload() {
        scope.launch {
            runCatching {
                val arr = Api.feedbackMine(ctx).optJSONArray("items")
                items = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
            }
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("我的提交"), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (loaded && items.isEmpty()) Text(
                tr("还没有提交过。"), fontSize = 13.sp, color = GrayText,
                modifier = Modifier.padding(16.dp)
            )
            items.forEach { it ->
                val id = it.optLong("id")
                val status = it.optString("status")
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (it.optString("kind")) {
                                "idea" -> tr("建议"); "custom" -> tr("定制"); else -> tr("问题")
                            },
                            fontSize = 11.sp, color = GrayText,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .border(0.8.dp, Color(0xFFDDE0DD), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("#$id", fontSize = 11.sp, color = GrayText)
                        Spacer(Modifier.weight(1f))
                        Text(statusText(status), fontSize = 12.sp,
                            color = if (status == "shipped") com.looka.app.ui.theme.LookaGreen else GrayText)
                    }
                    Text(
                        it.optString("body").take(80), fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val reply = it.optString("reply")
                    if (reply.isNotBlank()) Text(
                        tr("回复：{0}", reply), fontSize = 12.sp, color = GrayText, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (status == "received" || status == "need_info") Text(
                        tr("撤回"), fontSize = 12.sp, color = GrayText,
                        modifier = Modifier.plainClick {
                            scope.launch {
                                runCatching { Api.feedbackWithdraw(ctx, id) }
                                reload()
                            }
                        }.padding(top = 6.dp)
                    )
                }
                Hairline()
            }
        }
    }
}

/** 母档 §18：克制状态文案 —— 不游戏化、不过度承诺 */
private fun statusText(s: String): String = when (s) {
    "need_info" -> tr("还需要一点信息")
    "evaluating" -> tr("评估中")
    "planned" -> tr("已进入计划")
    "building" -> tr("正在制作")
    "beta" -> tr("可以提前试用")
    "shipped" -> tr("已经可以使用")
    "declined" -> tr("当前不会安排")
    else -> tr("已收到")
}

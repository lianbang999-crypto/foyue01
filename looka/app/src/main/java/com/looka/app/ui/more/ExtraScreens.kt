package com.looka.app.ui.more

import com.looka.app.ui.common.DlgTitle
import com.looka.app.ui.theme.LkIcons

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.LookaApp
import com.looka.app.data.Prefs
import com.looka.app.net.Api
import com.looka.app.notify.NotifyScheduler
import com.looka.app.ui.calendar.SectionLabel
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.ConfirmDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import com.looka.app.ui.common.SwitchRow
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Backup
import com.looka.app.util.Fmt
import com.looka.app.util.I18n
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== 订阅与小鹿 AI（四批：取代旧 AI 设置页） ====================

@Composable
fun SubscriptionScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn = remember(vm.settingsVersion) { Api.authed(ctx) }
    val plan = com.looka.app.data.PlanState.plan   // P2-A2：唯一真值源，变了自动重组
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var claimDlg by remember { mutableStateOf(false) }
    var claimNo by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var readAgenda by remember { mutableStateOf(Prefs.aiReadAgenda(ctx)) }
    var diaryUpload by remember { mutableStateOf(Prefs.aiDiaryUpload(ctx)) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("方案与鹿角"), onBack = { nav.popBackStack() })   // §120 P1：B2 拆页后本页只管方案与钱
        Column(Modifier.verticalScroll(rememberScrollState())) {

            // 当前版本
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    if (plan == "pro") "Pro 🦌" else tr("免费版"),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (plan == "pro") MaterialTheme.colorScheme.primary else Ink
                )
                Text(
                    if (plan == "pro") tr("每天自动到账 50 枚鹿角")
                    else tr("每天自动到账 10 枚鹿角；Pro 每天 50 枚，攒着还能生成表情包"),
                    fontSize = 12.sp, color = GrayText, modifier = Modifier.padding(top = 6.dp)
                )
                if (!loggedIn) Text(
                    tr("登录后即可使用小鹿 AI 与云同步"),
                    fontSize = 12.sp, color = HolidayRed, modifier = Modifier.padding(top = 4.dp)
                )
            }
            Hairline()
            // E5：鹿角并入订阅页（更多页只留一个入口）
            Row(
                Modifier.fillMaxWidth().plainClick { nav.navigate("antler") }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("我的鹿角"), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("🦌 ›", fontSize = 14.sp, color = GrayText)
            }
            Hairline()

            // R2（§60）：权益两句话。🚧 规划中的不写 —— 做出来了自然会看见。
            SectionLabel(tr("免费就有"))
            Text(
                tr("手帐全部功能 · 云同步 · 数据导出 · 每天 10 枚鹿角"),
                fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            SectionLabel(tr("Pro"))
            // §112：「做自己的主题」权益随自创色盘一并撤下（主题只留九色）
            Text(
                tr("更多鹿角（每天 50 枚）"),
                fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Hairline()
            // §120 P1：小鹿记事本已迁「小鹿设置」（记忆属行为偏好域，不属方案页）

            Text(
                tr("内测期注册即送 Pro 试用。\n有建议或问题请联系：looka01@qq.com"),
                fontSize = 12.sp, color = GrayText, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            // 支持入口（二十节 → 三十三节升级）：按语言分流。
            // 中文走 /api/pay/intent 拿 LK 短码（备注已预填，付款后服务端自动开通）；
            // 邮箱进 remark 的旧方案废弃 —— 不把用户隐私暴露到第三方平台。
            if (loggedIn) {
                // P2-A9：支付等待三态。pendingSince>0 = 用户已跳去付款；
                // 等待期间每 15 秒强刷一次订阅状态（服务端 webhook/对账开通后立即可见）。
                var payTick by remember { mutableStateOf(0) }
                val pendingSince = remember(payTick, vm.settingsVersion) { Prefs.payPendingSince(ctx) }
                val isPro = com.looka.app.data.PlanState.isPro
                LaunchedEffect(pendingSince) {
                    while (Prefs.payPendingSince(ctx) > 0 && !com.looka.app.data.PlanState.isPro) {
                        kotlinx.coroutines.delay(15_000)
                        com.looka.app.data.PlanState.refresh(ctx, force = true)
                        payTick++
                    }
                }
                if (pendingSince > 0 && isPro) {
                    // 成功卡：看到一次就够了，清掉 pending
                    LaunchedEffect(Unit) { Prefs.setPayPendingSince(ctx, 0L) }
                    Text(
                        tr("✅ Pro 已开通，有效期至 {0}", Fmt.dateCn(com.looka.app.data.PlanState.expiry / 86_400_000L)),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else if (pendingSince > 0) {
                    val waited = System.currentTimeMillis() - pendingSince
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            tr("⏳ 正在等待付款结果…付完款回到这里就行，通常几秒到账"),
                            fontSize = 12.sp, color = GrayText, lineHeight = 18.sp
                        )
                        // A10：认领入口只在超过 2 分钟仍未到账时才出现 ——
                        // 平时常驻等于暗示"这个流程会出问题"
                        if (waited > 2 * 60_000) {
                            TextButton(onClick = { claimDlg = true }) {
                                Text(tr("还没收到？我已付款，帮我找订单"), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        TextButton(onClick = { Prefs.setPayPendingSince(ctx, 0L); payTick++ }) {
                            Text(tr("我没有付款，关闭提示"), fontSize = 11.sp, color = GrayText)
                        }
                    }
                }
                if (!isPro) OutlinedButton(
                    onClick = {
                        scope.launch {
                            val zh = com.looka.app.util.I18n.lang.startsWith("zh")
                            val url = if (zh) {
                                runCatching { Api.payIntent(ctx).optString("url") }
                                    .getOrNull()?.takeIf { it.isNotBlank() }
                                    // 服务端不可达时退回无备注的裸链接（还有订单号认领兜底）
                                    ?: "https://ifdian.net/order/create?plan_id=95141ca09d2711f1bead52540025c377&product_type=0"
                            } else "https://ko-fi.com/summary/8389f40f-12d2-4d22-8ecb-32d91359dc4a"
                            Prefs.setPayPendingSince(ctx, System.currentTimeMillis())
                            payTick++
                            runCatching {
                                ctx.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }.onFailure { toast(ctx, tr("打不开浏览器")) }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text(tr("支持小鹿 · 开通 Pro"), fontSize = 13.sp) }
            }

            // A12：兑换码收进折叠区（主流程是自动开通，码只服务"送人"场景）
            var showRedeem by remember { mutableStateOf(false) }
            if (!showRedeem) TextButton(
                onClick = { showRedeem = true },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text(tr("有兑换码？"), fontSize = 12.sp, color = GrayText) }
            if (showRedeem) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text(tr("兑换码"), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = Api.redeem(ctx, code.trim())
                                com.looka.app.data.PlanState.apply(ctx, r.optString("plan", "pro"), r.optLong("expires_at", 0L))
                                vm.bumpSettings()
                                toast(ctx, tr("兑换成功，已升级 Pro 🎉"))
                                code = ""
                            } catch (e: Exception) {
                                toast(ctx, e.message ?: tr("兑换失败"))
                            } finally { busy = false }
                        }
                    },
                    enabled = code.isNotBlank() && !busy && loggedIn
                ) { Text(tr("兑换"), fontSize = 13.sp) }
            }
            Hairline()

            // §120 P1：原「AI 与隐私 + 自有 Key」整段迁往「小鹿设置」（设置中心可达）——
            //《全站统一规划》B2：方案页管钱与权益，AI 行为偏好归设置域
            NavRow(tr("小鹿设置"), value = tr("上下文授权、日记隐私、自有 Key、小鹿的记忆")) {
                nav.navigate("deerSettings")
            }
            Hairline()
            Spacer(Modifier.height(40.dp))
        }
    }

    // 认领订单：粘贴爱发电订单号 → 服务端反查开通（不依赖备注的唯一兜底）
    if (claimDlg) AlertDialog(
        onDismissRequest = { claimDlg = false },
        title = { DlgTitle(tr("认领爱发电订单")) },
        text = {
            Column {
                Text(
                    tr("打开爱发电 → 我的 → 订单，复制那笔订单的「订单号」粘贴到这里。"),
                    fontSize = 13.sp, color = GrayText, lineHeight = 19.sp
                )
                OutlinedTextField(
                    value = claimNo, onValueChange = { claimNo = it },
                    label = { Text(tr("订单号"), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = claimNo.trim().length >= 6 && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val r = Api.payClaim(ctx, claimNo.trim())
                            com.looka.app.data.PlanState.apply(
                                ctx, r.optString("plan", "pro"), r.optLong("expires_at", 0L))
                            Prefs.setPayPendingSince(ctx, 0L)
                            vm.bumpSettings()
                            toast(ctx, tr("认领成功，Pro 已开通 🎉"))
                            claimDlg = false; claimNo = ""
                        } catch (e: Exception) {
                            toast(ctx, e.message ?: tr("认领失败"))
                        } finally { busy = false }
                    }
                }
            ) { Text(tr("认领"), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = { claimDlg = false }) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

@Composable
private fun BenefitRow(title: String, free: Boolean, pro: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(
            if (free) LkIcons.Check else LkIcons.Close, null,
            tint = if (free) MaterialTheme.colorScheme.primary else Color(0xFFCFD2CF),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(26.dp))
        Icon(
            if (pro) LkIcons.Check else LkIcons.Close, null,
            tint = if (pro) MaterialTheme.colorScheme.primary else Color(0xFFCFD2CF),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
    }
}

// ==================== 备份与恢复（A 批 B17） ====================

@Composable
fun BackupScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LookaApp
    val scope = rememberCoroutineScope()
    var dedupDlg by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { Backup.exportJson(app, it) }
                    .onSuccess { n -> toast(ctx, tr("已导出 {0} 条数据", n)) }
                    .onFailure { e -> toast(ctx, e.message ?: tr("导出失败")) }
            }
        }
    }
    val icsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { Backup.exportIcs(app, it) }
                    .onSuccess { n -> toast(ctx, tr("已导出 {0} 条日程", n)) }
                    .onFailure { e -> toast(ctx, e.message ?: tr("导出失败")) }
            }
        }
    }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { Backup.exportMarkdown(app, it) }
                    .onSuccess { n -> toast(ctx, tr("已导出 {0} 篇", n)) }
                    .onFailure { e -> toast(ctx, e.message ?: tr("导出失败")) }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { Backup.importJson(app, it) }
                    .onSuccess { n -> toast(ctx, tr("恢复完成，合并 {0} 条", n)); vm.bumpSettings() }
                    .onFailure { e -> toast(ctx, e.message ?: tr("恢复失败")) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("备份与恢复"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                tr("不登录也能守住数据：定期把备份文件存到网盘 / 微信收藏，换手机后用「恢复」找回。"),
                fontSize = 13.sp, color = GrayText, lineHeight = 21.sp,
                modifier = Modifier.padding(16.dp)
            )
            SectionLabel(tr("备份"))
            BackupRow(tr("完整备份（JSON）"), tr("全部数据，可用于恢复")) {
                jsonLauncher.launch("looka-backup-$stamp.json")
            }
            Hairline()
            SectionLabel(tr("导出"))
            BackupRow(tr("日程（ICS 日历文件）"), tr("近 1 年发生，可导入任何日历应用")) {
                icsLauncher.launch("looka-events-$stamp.ics")
            }
            Hairline()
            BackupRow(tr("笔记与日记（Markdown）"), tr("纯文本合集，永远可读")) {
                mdLauncher.launch("looka-notes-$stamp.md")
            }
            Hairline()
            SectionLabel(tr("恢复"))
            BackupRow(tr("从备份文件恢复"), tr("按条目合并，新者优先，不会清空现有数据")) {
                restoreLauncher.launch(arrayOf("application/json"))
            }
            Hairline()
            // E5（§57）：维护入口并入本页（更多页 16→8）
            SectionLabel(tr("维护"))
            BackupRow(tr("清理重复日程"), "") { dedupDlg = true }
            Hairline()
            BackupRow(tr("同步冲突记录"), "") { nav.navigate("conflicts") }
            Hairline()
            Spacer(Modifier.height(40.dp))
        }
    }

    // A1-6：重复日程清理确认框（自 MoreScreen 移入，E5）
    if (dedupDlg) {
        val groups = remember(dedupDlg) { vm.duplicateEventGroups() }
        AlertDialog(
            onDismissRequest = { dedupDlg = false },
            title = { DlgTitle(tr("清理重复日程")) },
            text = {
                if (groups.isEmpty()) Text(tr("没有发现重复的日程 🦌"), fontSize = 13.sp)
                else Column {
                    Text(
                        tr("发现 {0} 组重复（每组保留最早一条，删除其余 {1} 条）：",
                            groups.size, groups.sumOf { it.size - 1 }),
                        fontSize = 13.sp
                    )
                    groups.take(8).forEach { g ->
                        Text(
                            "· ${com.looka.app.util.Fmt.dateCn(g[0].startDay)} ${g[0].title} ×${g.size}",
                            fontSize = 12.sp, color = GrayText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (groups.size > 8) Text(tr("……等 {0} 组", groups.size), fontSize = 12.sp, color = GrayText)
                }
            },
            confirmButton = {
                if (groups.isNotEmpty()) TextButton(onClick = {
                    vm.cleanDuplicateEvents { n ->
                        android.widget.Toast.makeText(ctx, tr("已清理 {0} 条重复日程", n),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dedupDlg = false
                }) { Text(tr("清理"), color = HolidayRed) }
            },
            dismissButton = {
                TextButton(onClick = { dedupDlg = false }) {
                    Text(if (groups.isEmpty()) tr("好的") else tr("取消"), color = GrayText)
                }
            }
        )
    }
}

@Composable
private fun BackupRow(title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().plainClick(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 15.sp)
        Text(sub, fontSize = 11.sp, color = GrayText)
    }
}

// ==================== 提醒自检（三批 B6） ====================

@Composable
fun SelfCheckScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    // E1（§79 / AC LOOKA-105）：本页文案承诺「改完回到本页会自动刷新」，但 tick 此前从未被 ++ ——
    // 四项检查进页面算一次就冻结，用户去系统设置开完权限回来仍是红叉，等于文案在骗人。
    // 从系统设置返回 = 本页 ON_RESUME，此时重算全部检查项。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifOk = remember(tick) {
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    val exactOk = remember(tick) { NotifyScheduler.canExact(ctx) }
    val batteryOk = remember(tick) {
        (ctx.getSystemService(PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    // §121（用户报 bug）：闹钟渠道原在列表中段才定义，**没计入顶部结论** ——
    // 渠道被降级时上面写着「提醒状态正常」，下面一行标红，自相矛盾。提前并计入。
    val alarmChOk = remember(tick) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val ch = nm?.getNotificationChannel(NotifyScheduler.ALARM_CHANNEL)
        ch == null || ch.importance >= android.app.NotificationManager.IMPORTANCE_HIGH
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("提醒诊断"), onBack = { nav.popBackStack() })   // §120 P1：A2 术语
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // §120 P1（B3 状态分层）：先给一句状态结论，用户不用逐项读完才知道有没有事。
            // 「自启动」无法程序化判断（null），不计入结论 —— 不确定的事不写成确定的。
            val allOk = notifOk && exactOk && batteryOk && alarmChOk
            Text(
                if (allOk) tr("提醒状态正常。") else tr("有设置可能影响提醒，请处理下方标红的项。"),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (allOk) Ink else HolidayRed,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp)
            )
            Text(
                tr("改完回到本页会自动刷新。"),
                fontSize = 13.sp, color = GrayText, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp, end = 16.dp)
            )
            CheckRow(tr("通知权限"), notifOk, tr("没有它任何提醒都发不出来")) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Hairline()
            CheckRow(tr("精确闹钟"), exactOk, tr("保证提醒分钟不差；关闭时可能延迟 15 分钟")) {
                if (Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            Hairline()
            CheckRow(tr("忽略电池优化"), batteryOk, tr("防止系统休眠时杀掉提醒")) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Hairline()
            CheckRow(tr("自启动 / 后台运行"), null, tr("小米 / 华为 / OPPO / vivo 需在系统设置中允许 Looka 自启动")) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Hairline()
            // A2-6：第 5 关 —— 闹钟渠道被用户手动静音/降级时，闹钟会退化成安静通知
            //（§121：定义已提前到顶部，与结论共用同一次判定）
            CheckRow(tr("闹钟渠道"), alarmChOk, tr("被手动调低后闹钟不会全屏弹出；点击恢复为「紧急」")) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, NotifyScheduler.ALARM_CHANNEL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Hairline()

            // ── 调度实况（2026-08-21 升级：查权限只能证明"能响"，这里证明"排上了没有"）
            val st = remember(tick) { NotifyScheduler.stats(ctx) }
            SectionLabel(tr("调度实况"))
            Text(
                buildString {
                    append(tr("已排队 {0} 条提醒", st.ok.toString()))
                    if (st.failed > 0) append(" · " + tr("失败 {0} 条", st.failed.toString()))
                    if (st.nextFire > 0) {
                        val mins = ((st.nextFire - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                        append("\n" + tr("下一次响铃：约 {0} 分钟后", mins.toString()))
                    } else if (st.ok == 0) {
                        append("\n" + tr("当前没有任何已排队的提醒 —— 若你设过提醒，这就是问题所在"))
                    }
                    if (st.lastRun > 0) {
                        val ago = ((System.currentTimeMillis() - st.lastRun) / 60000)
                        append("\n" + tr("上次调度：{0} 分钟前", ago.toString()))
                    }
                },
                fontSize = 13.sp, color = if (st.failed > 0 || st.ok == 0) HolidayRed else GrayText,
                lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // ── 立即测试：一个按钮把问题劈成两半
            val scope2 = rememberCoroutineScope()
            OutlinedButton(
                onClick = {
                    val ok = NotifyScheduler.fireTestIn10s(ctx)
                    toast(ctx, if (ok) tr("已安排：10 秒后会响一条测试通知，请退到桌面等它")
                               else tr("测试闹钟安排失败 —— 说明系统拦了闹钟权限"))
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            ) { Text(tr("🔔 立即测试（10 秒后响）")) }
            // A2：闹钟链路单独测 —— 通知通不代表闹钟通（渠道、全屏意图、前台服务是另一条链）
            OutlinedButton(
                onClick = {
                    val ok = NotifyScheduler.fireAlarmTestIn10s(ctx)
                    toast(ctx, if (ok) tr("已安排：10 秒后会响真闹钟（持续响铃），请锁屏等它")
                               else tr("测试闹钟安排失败 —— 说明系统拦了闹钟权限"))
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            ) { Text(tr("⏰ 测试闹钟（10 秒后持续响）")) }
            Text(
                tr("收到 = 通知链路是通的，问题出在提醒数据或调度；收不到 = 系统在拦截，按上面五关逐项放行。"),
                fontSize = 11.5.sp, color = GrayText, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(Modifier.padding(8.dp)) {
                TextButton(onClick = { tick++; toast(ctx, tr("已刷新")) }) { Text(tr("重新检查")) }
                TextButton(onClick = {
                    scope2.launch {
                        NotifyScheduler.rescheduleFromDb(ctx.applicationContext as LookaApp)
                        tick++
                        toast(ctx, tr("已重新调度全部提醒"))
                    }
                }) { Text(tr("重新调度")) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CheckRow(title: String, ok: Boolean?, sub: String, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().plainClick(onFix)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // §121：emoji（✅⚠️🔍）→ 状态点 —— emoji 逐厂商渲染不一、且与全站细线稿语言脱节。
        // 颜色 + 右侧文字双证据（J2：任何状态不能只靠颜色）
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(
                when (ok) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> HolidayRed
                    null -> Color(0xFFC0C3C0)
                }
            )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, fontSize = 11.sp, color = GrayText)
        }
        Text(
            when (ok) { true -> tr("已开启"); false -> tr("去开启"); null -> tr("去设置") },
            fontSize = 13.sp,
            color = if (ok == false) HolidayRed else GrayText
        )
    }
}

// ==================== 同步冲突记录（A 批 B19） ====================

@Composable
fun ConflictScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LookaApp
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logs by remember { app.db.conflictDao().all() }.collectAsState(initial = emptyList())
    val fmt = remember { SimpleDateFormat("M-d HH:mm", Locale.US) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("同步冲突记录"), onBack = { nav.popBackStack() }) {
            if (logs.isNotEmpty()) TextButton(onClick = {
                scope.launch { app.db.conflictDao().clear() }
            }) { Text(tr("清空"), color = GrayText, fontSize = 13.sp) }
        }
        Text(
            tr("两台设备同时改一条数据时，较新的会胜出；被覆盖的版本留在这里，点击可复制找回。"),
            fontSize = 12.sp, color = GrayText, lineHeight = 19.sp,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn {
            items(logs, key = { it.id }) { c ->
                Column(
                    Modifier.fillMaxWidth()
                        .plainClick {
                            clipboard.setText(AnnotatedString(c.payload))
                            toast(ctx, tr("已复制被覆盖的内容"))
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (c.kind) {
                                "event" -> tr("日程"); "task" -> tr("任务")
                                "note" -> tr("笔记"); "diary" -> tr("日记"); else -> c.kind
                            },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(c.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text(fmt.format(Date(c.occurredAt)), fontSize = 11.sp, color = GrayText)
                    }
                    Text(
                        c.payload.replace("\n", " ").take(80),
                        fontSize = 12.sp, color = GrayText, maxLines = 2
                    )
                }
                Hairline()
            }
            if (logs.isEmpty()) {
                item { EmptyDeer(tr("没有同步冲突，很和谐")) }
            }
        }
    }
}

// ==================== 语言（I 批） ====================

@Composable
fun LanguageScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var pref by remember { mutableStateOf(Prefs.language(ctx)) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("语言 / Language"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            I18n.CHOICES.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().plainClick {
                        pref = c
                        I18n.set(ctx, c)
                        vm.bumpSettings()
                    }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = pref == c, onClick = {
                        pref = c; I18n.set(ctx, c); vm.bumpSettings()
                    })
                    Text(I18n.choiceLabel(c), fontSize = 15.sp)
                }
            }
            Hairline()
            Text(
                tr("切换语言只改变界面文字，不会翻译你写的内容。\n切到英文时：默认周日开始、12 小时制、隐藏农历（都可在日历设置中单独修改，已手动设置过的不受影响）。"),
                fontSize = 12.sp, color = GrayText, lineHeight = 20.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

// ==================== 鹿角页（§54 G1/G2 + §55 P4，2026-08-22） ====================

/**
 * 我的鹿角（R1 §60 减法版）：只说一句话 + 余额 + 邀请码。
 * 明细流水 / 参考价 / 里程碑说明全部移除 —— 用户不需要看账本。
 * 服务端账本（双桶/幂等/流水）原样保留，砍的只是呈现。
 */
@Composable
fun AntlerScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var bal by remember { mutableStateOf(-1) }
    var invite by remember { mutableStateOf("") }
    var grantDaily by remember { mutableStateOf(0) }
    // §120 P3（D3 钱包）：最近流水 —— 只要发生扣费，就给最基本的可核对能力
    var ledger by remember { mutableStateOf(listOf<Triple<Int, String, Long>>()) }
    LaunchedEffect(Unit) {
        runCatching {
            val r = Api.antler(ctx)
            bal = r.optJSONObject("antler")?.optInt("total", -1) ?: r.optInt("total", -1)
            grantDaily = r.optInt("grant_daily", 0)
            val arr = r.optJSONArray("ledger")
            val out = mutableListOf<Triple<Int, String, Long>>()
            for (i in 0 until minOf(arr?.length() ?: 0, 10)) {
                val o = arr!!.getJSONObject(i)
                out.add(Triple(o.optInt("delta"), o.optString("reason"), o.optLong("created_at")))
            }
            ledger = out
        }
        runCatching { invite = Api.me(ctx).optString("invite_code") }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("我的鹿角"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(if (bal >= 0) "🦌 $bal" else "🦌 …", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (grantDaily > 0) tr("当天使用后获得 {0} 枚；和小鹿聊天用 1 枚", grantDaily.toString())
                    else tr("当天使用后获得；和小鹿聊天用 1 枚"),
                    fontSize = 12.sp, color = GrayText, modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    tr("不过期、不清零、无上限"),
                    fontSize = 11.sp, color = GrayText, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Hairline()
            // §120 P3：最近明细（D3：今天获得 +10 / 小鹿对话 −1 / 装扮 −30）
            if (ledger.isNotEmpty()) {
                SectionLabel(tr("最近明细"))
                ledger.forEach { (delta, reason, ts) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (reason) {
                                "daily_grant" -> tr("每日到账")
                                "chat" -> tr("小鹿对话")
                                "shop" -> tr("装扮解锁")
                                "invite" -> tr("邀请奖励")
                                else -> reason
                            },
                            fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        Text(
                            Fmt.dateCn(java.time.Instant.ofEpochMilli(ts)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()),
                            fontSize = 11.sp, color = GrayText, modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            (if (delta > 0) "+" else "") + delta,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (delta > 0) MaterialTheme.colorScheme.primary else Ink
                        )
                    }
                }
                Hairline()
            }
            if (invite.isNotBlank()) {
                val clip = LocalClipboardManager.current
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("我的邀请码：{0}", invite), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(tr("朋友注册时填上它，你们各得 100 枚 🦌"), fontSize = 11.sp, color = GrayText)
                    }
                    OutlinedButton(onClick = {
                        clip.setText(AnnotatedString(invite)); toast(ctx, tr("已复制"))
                    }) { Text(tr("复制"), fontSize = 13.sp) }
                }
                Hairline()
            }
        }
    }
}

// ==================== 小鹿记事本（§52 D4，2026-08-22） ====================

/**
 * 「小鹿记住了什么」：可看、可逐条删。
 * 看不见的记忆是监视，看得见可删的记忆才是助理（§52 一）。
 */
@Composable
fun DeerMemorySection(vm: LookaViewModel) {
    val ctx = LocalContext.current
    var facts by remember { mutableStateOf(Prefs.deerFacts(ctx)) }
    SectionLabel(tr("小鹿记住了什么"))
    if (facts.isEmpty()) {
        Text(
            tr("还没有。聊天时告诉小鹿你的偏好（比如「我不爱早上开会」），它会记在这里。"),
            fontSize = 12.sp, color = GrayText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
    facts.forEach { f ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("· $f", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                tr("忘掉"), fontSize = 12.sp, color = GrayText,
                modifier = Modifier.plainClick {
                    val next = facts.filter { it != f }
                    Prefs.setDeerFacts(ctx, next)
                    facts = next
                }.padding(6.dp)
            )
        }
    }
}

// ==================== §118：鹿角商店（独立页） ====================

/**
 * 商店独立页 —— v42 只把解锁卡嵌在贴纸选择器锁定 tab 里，用户根本找不到（实机反馈）。
 * 形态对齐 Lifebear 着せかえショップ的骨架（图 119/120：列表卡 + 名称 + 价格红字），
 * v1 只有两个贴纸包，不做三页签 —— 商品多了再加（克制）。
 */
@Composable
fun ShopScreen(vm: com.looka.app.vm.LookaViewModel, nav: androidx.navigation.NavHostController) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val authed = remember { com.looka.app.net.Api.authed(ctx) }
    var antler by remember { mutableStateOf(-1) }
    var isPro by remember { mutableStateOf(false) }
    var owned by remember { mutableStateOf(com.looka.app.data.Prefs.ownedPacks(ctx)) }
    var prices by remember { mutableStateOf(mapOf<String, Int>()) }
    var busyId by remember { mutableStateOf("") }
    // §120 P3（D4 购买确认）：确认弹窗先看清「余额 42 → 12」再扣
    var confirmPack by remember { mutableStateOf<com.looka.app.util.StampAssets.Pack?>(null) }
    LaunchedEffect(Unit) {
        if (!authed) return@LaunchedEffect
        runCatching {
            val r = com.looka.app.net.Api.shopItems(ctx)
            antler = r.optInt("antler", -1)
            isPro = r.optString("plan") == "pro"
            val its = r.optJSONArray("items")
            val pm = mutableMapOf<String, Int>()
            for (i in 0 until (its?.length() ?: 0)) {
                val o = its!!.getJSONObject(i)
                pm[o.optString("id").removePrefix("pack:")] = o.optInt("price")
            }
            prices = pm
            val ow = r.optJSONArray("owned")
            val set = mutableSetOf<String>()
            for (i in 0 until (ow?.length() ?: 0)) set.add(ow!!.getString(i).removePrefix("pack:"))
            com.looka.app.data.Prefs.setOwnedPacks(ctx, set)
            owned = set
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("装扮商店"), onBack = { nav.popBackStack() }) {
            if (antler >= 0 && !isPro) Text(
                "🦌 $antler", fontSize = 14.sp, color = Ink,
                modifier = Modifier.padding(end = 14.dp)
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!authed) {
                Text(
                    tr("登录后可用鹿角解锁贴纸包（当天使用 Looka 即获 10 🦌）"),
                    fontSize = 13.sp, color = GrayText,
                    modifier = Modifier.padding(16.dp)
                )
            }
            val packs = remember { com.looka.app.util.StampAssets.packs(ctx) }
            packs.filter { it.id != "daily" }.forEach { pk ->
                val has = pk.id in owned
                val price = prices[pk.id] ?: if (pk.id == "dunhuang") 30 else 20
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 封面 = 包内第一枚
                    val cover = pk.stamps.firstOrNull()?.let { com.looka.app.util.StampAssets.bitmap(ctx, it.id) }
                    if (cover != null) androidx.compose.foundation.Image(
                        cover, pk.name(), modifier = Modifier.size(52.dp)
                    ) else Text("🦌", fontSize = 34.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pk.name(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("{0} 枚贴纸", pk.stamps.size.toString()),
                            fontSize = 12.sp, color = GrayText, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    when {
                        has -> Text(tr("已解锁"), fontSize = 13.sp, color = GrayText)
                        else -> androidx.compose.material3.Button(
                            onClick = {
                                if (!authed) { com.looka.app.ui.common.toast(ctx, tr("请先在「更多 → 账号」登录")); return@Button }
                                if (busyId.isNotBlank()) return@Button
                                // §120 P3：免费领取（Pro）直接走；花鹿角的先确认
                                if (!isPro) { confirmPack = pk; return@Button }
                                busyId = pk.id
                                scope.launch {
                                    runCatching {
                                        val r = com.looka.app.net.Api.shopBuy(ctx, "pack:" + pk.id)
                                        if (r.optBoolean("ok")) {
                                            owned = owned + pk.id
                                            com.looka.app.data.Prefs.setOwnedPacks(ctx, owned)
                                            antler = r.optInt("antler", antler)
                                            com.looka.app.ui.common.toast(ctx, tr("已解锁「{0}」🦌", pk.name()))
                                        } else com.looka.app.ui.common.toast(ctx, r.optString("error", tr("解锁失败，请稍后再试")))
                                    }.onFailure { com.looka.app.ui.common.toast(ctx, it.message ?: tr("解锁失败，请稍后再试")) }
                                    busyId = ""
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = com.looka.app.ui.theme.SaveDark),
                            shape = RoundedCornerShape(5.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            if (busyId == pk.id) com.looka.app.ui.common.DeerLoading(13.sp)
                            else Text(
                                if (isPro) tr("免费领取") else tr("{0} 鹿角", price.toString()),
                                fontSize = 13.sp, color = Color.White
                            )
                        }
                    }
                }
                Hairline()
            }
            Text(
                tr("「日常」包 42 枚永久免费 · Pro 会员 0 鹿角永久领取 · 当天使用 Looka 即获鹿角"),
                fontSize = 12.sp, color = GrayText,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    // §120 P3（D4/A3 文案主稿）：购买确认 —— 价格、当前余额、购后余额三个数都亮出来
    confirmPack?.let { pk ->
        val price = prices[pk.id] ?: if (pk.id == "dunhuang") 30 else 20
        val after = antler - price
        ConfirmDialog(
            title = tr("解锁「{0}」贴纸包？", pk.name()),
            text = if (antler >= 0)
                tr("需要 {0} 枚鹿角。余额 {1} → {2}。", price.toString(), antler.toString(), after.toString()) +
                    if (after < 0) "\n" + tr("还差 {0} 枚鹿角。下次使用 Looka 时还会获得鹿角。", (-after).toString()) else ""
            else tr("需要 {0} 枚鹿角。", price.toString()),
            confirmText = tr("解锁"),
            onConfirm = {
                confirmPack = null
                if (antler in 0 until price) {
                    com.looka.app.ui.common.toast(ctx, tr("鹿角不够啦，明天使用 Looka 还会获得 🦌")); return@ConfirmDialog
                }
                busyId = pk.id
                scope.launch {
                    runCatching {
                        val r = com.looka.app.net.Api.shopBuy(ctx, "pack:" + pk.id)
                        if (r.optBoolean("ok")) {
                            owned = owned + pk.id
                            com.looka.app.data.Prefs.setOwnedPacks(ctx, owned)
                            antler = r.optInt("antler", antler)
                            com.looka.app.ui.common.toast(ctx, tr("已解锁「{0}」🦌", pk.name()))
                        } else com.looka.app.ui.common.toast(ctx, r.optString("error", tr("解锁失败，请稍后再试")))
                    }.onFailure { com.looka.app.ui.common.toast(ctx, it.message ?: tr("解锁失败，请稍后再试")) }
                    busyId = ""
                }
            },
            onDismiss = { confirmPack = null }
        )
    }
}

// ==================== §119 T5：设置中心 ====================

/**
 * 真正的设置总入口 —— 此前黑区「设置」直跳日历设置（《全站统一规划》点名的假总入口）。
 * 按规划 B1 的设置域分层：日历与显示 / 提醒诊断 / 语言与地区。
 * 「小鹿设置」在 P1 批拆出「订阅与小鹿 AI」后加入。
 */
@Composable
fun SettingsHubScreen(vm: com.looka.app.vm.LookaViewModel, nav: androidx.navigation.NavHostController) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("设置"), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NavRow(tr("日历与显示"), value = tr("周起始、农历、节日、已完成任务"), icon = LkIcons.Calendar) {
                nav.navigate("calSettings")
            }
            Hairline()
            NavRow(tr("提醒诊断"), value = tr("提醒与系统权限检查"), icon = LkIcons.Bell) {
                nav.navigate("selfcheck")
            }
            Hairline()
            NavRow(tr("小鹿设置"), value = tr("上下文授权、隐私与记忆"), icon = LkIcons.Smile) {
                nav.navigate("deerSettings")
            }
            Hairline()
            NavRow(
                tr("语言 / Language"), icon = LkIcons.Help,
                value = com.looka.app.util.I18n.choiceLabel(com.looka.app.data.Prefs.language(LocalContext.current))
            ) { nav.navigate("language") }
            Hairline()
        }
    }
}


// ==================== §120 P1：小鹿设置（B2 拆页） ====================

/**
 * 小鹿的行为偏好域 —— 从「订阅与小鹿 AI」拆出（《全站统一规划》B2）。
 * 管：上下文授权、日记隐私、自有 Key、小鹿的记忆。不管：方案、鹿角、付款。
 */
@Composable
fun DeerSettingsScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var readAgenda by remember { mutableStateOf(Prefs.aiReadAgenda(ctx)) }
    var diaryUpload by remember { mutableStateOf(Prefs.aiDiaryUpload(ctx)) }
    var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("小鹿设置"), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel(tr("AI 与隐私"))
            SwitchRow(
                tr("允许小鹿读取日程与任务"), readAgenda,
                subtitle = tr("作为对话上下文，才能回答「今天有什么安排」")
            ) { readAgenda = it; Prefs.setAiReadAgenda(ctx, it) }
            Hairline()
            SwitchRow(
                tr("允许日记润色上传正文"), diaryUpload,
                subtitle = tr("日记最私密，默认关闭；开启后才能使用 AI 润色")
            ) { diaryUpload = it; Prefs.setAiDiaryUpload(ctx, it) }
            Hairline()

            // 高级：自带 Key 直连
            Text(
                if (advanced) tr("收起高级选项") else tr("高级：使用自己的 AI Key 直连"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.plainClick { advanced = !advanced }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            if (advanced) {
                var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
                var base by remember { mutableStateOf(Prefs.baseUrl(ctx)) }
                var model by remember { mutableStateOf(Prefs.model(ctx)) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = base, onValueChange = { base = it },
                        label = { Text("Base URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        label = { Text(tr("模型")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(Modifier.padding(vertical = 8.dp)) {
                        TextButton(onClick = {
                            Prefs.setApiKey(ctx, key.trim()); Prefs.setBaseUrl(ctx, base.trim())
                            Prefs.setModel(ctx, model.trim())
                            toast(ctx, tr("已保存"))
                        }) { Text(tr("保存")) }
                        TextButton(onClick = {
                            key = ""; Prefs.setApiKey(ctx, "")
                            toast(ctx, tr("已清除，恢复走 Looka 服务端"))
                        }) { Text(tr("清除 Key"), color = GrayText) }
                    }
                    Text(
                        tr("填入后 AI 请求直连服务商、走你自己的账单，不再经过 Looka 服务端。兼容 OpenAI 格式接口（如硅基流动 siliconflow.cn，在其官网申请 Key）。"),
                        fontSize = 11.sp, color = GrayText
                    )
                }
            }
            // 小鹿的记忆（§52 D4 组件平移至此 —— 记忆属于行为偏好域）
            DeerMemorySection(vm)
            Spacer(Modifier.height(40.dp))
        }
    }
}

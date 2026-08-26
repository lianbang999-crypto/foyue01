@file:OptIn(ExperimentalMaterial3Api::class)

package com.looka.app.ui.more

import com.looka.app.ui.common.DlgTitle
import com.looka.app.ui.theme.LkIcons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.R
import com.looka.app.data.Prefs
import com.looka.app.ui.calendar.SectionLabel
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.DEER_THEMES
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.ThemeCtl
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

/** 更多 Tab：账号 / 九色主题 / 各设置入口 */
@Composable
fun MoreScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var aboutDlg by remember { mutableStateOf(false) }
    var themeSheet by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.looka.app.util.UpdateManager.Info?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(checkingUpdate) {
        if (checkingUpdate) {
            val info = com.looka.app.util.UpdateManager.check(ctx)
            when {
                info == null -> updateMsg = tr("检查失败，请稍后再试")
                info.hasUpdate -> updateInfo = info
                else -> updateMsg = tr("已是最新版本 🦌")
            }
            checkingUpdate = false
        }
    }
    // 有昵称就显示昵称 —— 用户看到的是自己起的名字，不是一串邮箱/手机号
    val email = remember(vm.settingsVersion) { Prefs.displayName(ctx) }
    val plan = com.looka.app.data.PlanState.plan
    val loggedIn = remember(vm.settingsVersion) { Prefs.authToken(ctx) != null }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr("更多"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Hairline()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // §113 E4：**黑色顶区三主入口**（实机图 32：アカウント/プラン/設定，
            // 白线稿图标 + 白字三等分，是 More 页最显著的视觉锚）。
            // 与 §68 的关系：§68 撤回的是「把一切入口做成宫格黑块」；这次只把
            // 账户/订阅/设置三个身份级入口收进一条黑区，下方仍是分组列表 —— 不冲突。
            Row(Modifier.fillMaxWidth().background(Ink).padding(vertical = 16.dp)) {
                BlackTopEntry(
                    LkIcons.User,
                    if (loggedIn) email.ifBlank { tr("账号") } else tr("账号"),
                    Modifier.weight(1f)
                ) { nav.navigate("account") }
                BlackTopEntry(
                    Icons.Outlined.WorkspacePremium,
                    if (plan == "pro") "Pro" else tr("订阅"),
                    Modifier.weight(1f)
                ) { nav.navigate("subscription") }
                BlackTopEntry(LkIcons.Settings, tr("设置"), Modifier.weight(1f)) {
                    nav.navigate("calSettings")
                }
            }
            // 品牌区
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.looka.app.ui.common.DeerBadge(52.dp)   // B3：随主题变色
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Looka", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(tr("把生活，轻轻收进日历里"), fontSize = 12.sp, color = GrayText)
                }
            }
            Hairline()
            // §68 四：宫格方案撤回（大黑块太重、分组逻辑错）。
            // 按语义分组列表 —— Lifebear More 的下半部本来也是入口列表/宫格混排。
            // §113 E4：原「我」组两行（账号与同步/订阅·鹿角）已上移进黑区，组撤销。
            SectionLabel(tr("小鹿"))
            NavRow(tr("小鹿 AI"), icon = Icons.Outlined.AutoAwesome) { nav.navigate("aiChat") }
            Hairline()

            SectionLabel(tr("外观"))
            NavRow(
                tr("主题"), icon = LkIcons.Palette,
                // §112：老用户可能还停在自定义主题（入口已撤但设置仍有效），别把它标成"森绿"
                value = if (ThemeCtl.index == com.looka.app.ui.theme.CUSTOM_THEME) tr("自定义")
                        else DEER_THEMES[ThemeCtl.index.coerceIn(0, 8)].name
            ) { themeSheet = true }
            Hairline()

            SectionLabel(tr("设置"))
            NavRow(tr("日历设置"), icon = LkIcons.Settings) { nav.navigate("calSettings") }
            Hairline()
            NavRow(tr("提醒自检"), icon = LkIcons.Bell) { nav.navigate("selfcheck") }
            Hairline()
            NavRow(
                tr("语言 / Language"), icon = Icons.Outlined.Translate,
                value = com.looka.app.util.I18n.choiceLabel(Prefs.language(ctx))
            ) { nav.navigate("language") }
            Hairline()

            SectionLabel(tr("数据"))
            // §77 N2（减法）：撤掉这里的「搜索」行 —— 搜索是内容操作，不是产品设置，
            // 放在「数据」组本身就是归类错误（母档 §24.1：More 只承载产品/设置/个性化/数据管理）。
            // 删前已查全部可达路径：日历页 CalendarScreen:1362 与待办页页首 TodoScreen:89 仍在，
            // 删后剩 2 条，不会重演 §67 那次「AI 入口被合理删除两次直到删没」。
            NavRow(tr("备份与维护"), icon = Icons.Outlined.SaveAlt) { nav.navigate("backup") }
            Hairline()

            SectionLabel(tr("帮助"))
            NavRow(tr("关于 Looka"), icon = LkIcons.Help,
                value = "v" + com.looka.app.BuildConfig.VERSION_NAME) { aboutDlg = true }
            Hairline()
            // §106 B：更多页运营 Banner 位（对照 0826 参考图更多页宫格下方那块）。
            // 没接 SDK / Pro 用户 → 不画、不占高度。
            com.looka.app.ui.common.AdSlot(
                com.looka.app.ui.common.AdPlacement.MORE_BANNER,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            Spacer(Modifier.height(60.dp))
        }
    }


    // 九色主题选择
    if (themeSheet) ModalBottomSheet(
        // §62 圆角档：底部面板 16dp 顶角
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        onDismissRequest = { themeSheet = false },
        containerColor = Color.White
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(
                tr("一鹿九色，选一个今天的颜色 🦌"),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            DEER_THEMES.chunked(3).forEachIndexed { rowIdx, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEachIndexed { colIdx, t ->
                        val i = rowIdx * 3 + colIdx
                        val sel = ThemeCtl.index == i
                        Column(
                            Modifier.plainClick { ThemeCtl.set(ctx, i) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(52.dp).clip(CircleShape)
                                    .background(t.container)
                                    .border(
                                        width = if (sel) 2.5.dp else 0.8.dp,
                                        color = if (sel) Ink else Color(0xFFE2E5E2),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(t.primary))
                            }
                            Text(
                                t.name, fontSize = 12.sp,
                                color = if (sel) Ink else GrayText,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // §112（用户拍板）：**主题只留九色，自创色盘与照片取色已撤。**
            // 「一鹿九色」本来就是品牌 —— 九个之外再给 48 个，等于自己稀释自己。
            // 撤掉的是一个 Pro 卖点（§48 C4 / B6-lite），代价在 §112 记账；
            // 已设自定义主题的老用户不受影响（ThemeCtl 仍认 index=-1，直到他换成九色之一）。
        }
    }


    updateMsg?.let { m ->
        androidx.compose.runtime.LaunchedEffect(m) {
            com.looka.app.ui.common.toast(ctx, m)
            updateMsg = null
        }
    }
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            // §116：标题写清「当前 → 新」 —— 用户反复报"装完没变化"，
            // 有了这行，装没装上、现在是几，一眼可辨
            title = { DlgTitle(tr("发现新版本 ") + info.versionName + tr("（当前 {0}）", com.looka.app.BuildConfig.VERSION_NAME)) },
            text = { Text(info.changelog.ifBlank { tr("修复与体验优化") }, fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = {
                    com.looka.app.util.UpdateManager.startDownload(ctx, info)
                    com.looka.app.ui.common.toast(ctx, tr("正在后台下载，完成后会自动弹出安装"))
                    updateInfo = null
                }) { Text(tr("立即更新"), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { updateInfo = null }) { Text(tr("稍后"), color = GrayText) } },
            containerColor = Color.White
        )
    }

    if (aboutDlg) AlertDialog(
        onDismissRequest = { aboutDlg = false },
        title = { DlgTitle("Looka v" + com.looka.app.BuildConfig.VERSION_NAME) },
        text = {
            Column {
                Text(
                    tr("小鹿 Looka，可爱版九色鹿，你的极简生活手帐。\n\n") +
                            tr("· 灵感来自敦煌壁画「九色鹿」：一鹿九色，故有九套主题\n") +
                            tr("· 日历为中心：日程、任务、日记、印章围绕日期组织\n") +
                            tr("· 数据本机优先，登录后云同步\n") +
                            tr("· 小鹿 AI 助手（免费每天 10 次，Pro 不限次）\n") +
                            tr("· 独立原创品牌与设计"),
                    fontSize = 13.sp, lineHeight = 21.sp
                )
                // C2（§54）：崩溃日志在私有目录，用户自己拿不到 —— 给一个主动上报通道
                val crashF = remember { (ctx.applicationContext as com.looka.app.LookaApp).crashFile() }
                if (crashF.exists()) {
                    val cScope = androidx.compose.runtime.rememberCoroutineScope()
                    Text(
                        tr("检测到上次异常退出 · 点击发送崩溃日志"),
                        fontSize = 12.sp, color = HolidayRed,
                        modifier = Modifier.padding(top = 8.dp).plainClick {
                            cScope.launch {
                                runCatching {
                                    val lines = crashF.readText().lines()
                                    com.looka.app.net.Api.crash(ctx,
                                        lines.getOrElse(0) { "" }.removePrefix("Looka ").substringBefore(" (")
                                            .ifBlank { com.looka.app.BuildConfig.VERSION_NAME },
                                        lines.getOrElse(1) { "" }.take(64),
                                        lines.drop(3).joinToString("\n").take(8000))
                                    crashF.delete()
                                    android.widget.Toast.makeText(ctx, tr("已发送，感谢帮助小鹿变好 🦌"),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    android.widget.Toast.makeText(ctx, tr("发送失败，稍后再试"),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
                // §116：已下载待装的包给**常驻入口** —— 用户去系统设置开完
                // 「允许安装未知应用」回来，从这里一点就装，不依赖下载广播的时序
                com.looka.app.util.UpdateManager.readyApk(ctx)?.let { apk ->
                    Row(Modifier.padding(top = 10.dp)) {
                        Text(
                            tr("安装已下载的新版本"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.plainClick {
                                com.looka.app.util.UpdateManager.install(ctx, apk)
                            }
                        )
                    }
                }
                // E5：更新与语言收进关于页（更多页减行）
                Row(Modifier.padding(top = 10.dp)) {
                    Text(
                        tr("检查更新") + " · v" + com.looka.app.BuildConfig.VERSION_NAME,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick { aboutDlg = false; checkingUpdate = true }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        tr("语言 / Language"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick { aboutDlg = false; nav.navigate("language") }
                    )
                }
                Row(Modifier.padding(top = 10.dp)) {
                    Text(tr("隐私政策"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://looka.foyue.org/privacy.html")))
                        })
                    Spacer(Modifier.width(18.dp))
                    Text(tr("用户协议"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.plainClick {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://looka.foyue.org/terms.html")))
                        })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { aboutDlg = false }) {
                Text(tr("好的"), color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = Color.White
    )
}


/** §113 E4：黑色顶区单项 —— 白线稿图标 + 白字，等分三列（实机图 32） */
@Composable
private fun BlackTopEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.plainClick(onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            label, fontSize = 12.sp, color = Color.White,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

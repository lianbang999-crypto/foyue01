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
import com.looka.app.ui.theme.LinkBlue
import com.looka.app.ui.theme.ThemeCtl
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

/** 更多 Tab：账号 / 九色主题 / 各设置入口 */
@Composable
fun MoreScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var aboutDlg by remember { mutableStateOf(false) }
    // §128 M2：主题面板整体迁入设置中心「外观与语言」（唯一编辑位置），本页不再持主题状态
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
            // §128 M1（图鉴 §11-12 图 C）：黑区三入口撤除 —— §113 E4 曾按当时实机反馈补建，
            // 本轮两份文档一致拍板"More 不照搬三等分黑条，改安静身份摘要"（用户方向变更，记账）。
            // 账号入口 = 身份摘要卡；方案入口收进方案页（身份卡点进账号页内有）；设置唯一入口在下方。
            // §120 P1：品牌口号区改为**账户状态卡**（《全站统一规划》B1：
            // 口号不承载管理价值，这里显示"当前真正需要管理的状态"——
            // 未登录引导登录；已登录显示 方案 · 同步状态。点击进账号页。文案按 A3 主稿。
            Row(
                Modifier.fillMaxWidth().plainClick { nav.navigate("account") }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.looka.app.ui.common.DeerBadge(52.dp)   // B3：随主题变色
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (loggedIn) email.ifBlank { "Looka" } else tr("登录 Looka"),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (!loggedIn) tr("同步并恢复你的记录")
                        else (if (plan == "pro") "Pro" else tr("免费版")) + " · " +
                            (com.looka.app.net.SyncEngine.lastMsg.ifBlank { tr("已同步") }),
                        fontSize = 12.sp, color = GrayText, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            Hairline()
            // §128 M1（图 C）：More = 产品入口与状态摘要，**不再复制设置目录** ——
            // 主题/语言/日历与显示/提醒诊断/备份 五条重复直达全部收进设置中心
            //（"同一状态可以摘要一次，同一配置只能有一个正式编辑位置"）。
            NavRow(tr("小鹿 AI"), icon = Icons.Outlined.AutoAwesome) { nav.navigate("aiChat") }
            Hairline()
            NavRow(tr("装扮商店"), icon = LkIcons.Sticker, value = tr("贴纸与主题")) { nav.navigate("shop") }
            Hairline()
            NavRow(tr("设置"), icon = LkIcons.Settings) { nav.navigate("settingsHub") }
            Hairline()
            // §128 F2：用户共建中心（报告问题/提出建议/申请定制）
            NavRow(tr("帮助 Looka 变得更好"), icon = LkIcons.Help, value = tr("报错与建议")) {
                nav.navigate("feedbackHub")
            }
            Hairline()
            NavRow(tr("关于 Looka"), icon = LkIcons.Smile,
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
                            tr("· 小鹿 AI 助手（鹿角计次：每天使用即获鹿角）\n") +   // §119 T3：原「每天10次/Pro不限次」是过时承诺
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


// §128 M1：BlackTopEntry 已随黑区三入口一并撤除（图鉴 §11 拍板，§113 E4 反转记账）

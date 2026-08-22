@file:OptIn(ExperimentalMaterial3Api::class)

package com.looka.app.ui.more

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
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.R
import com.looka.app.data.Prefs
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.DEER_THEMES
import com.looka.app.ui.theme.GrayText
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
    // 自创主题 Pro 门禁：记录用户点的那个颜色，弹窗里就用它演示（看得见才想要）
    var customGate by remember { mutableStateOf<Long?>(null) }
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
    val email = remember(vm.settingsVersion) { Prefs.accountEmail(ctx) }
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
            // 品牌区
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_deer_badge), null, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Looka", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(tr("可爱版九色鹿 · 极简生活手帐"), fontSize = 12.sp, color = GrayText)
                }
            }
            Hairline()
            NavRow(
                tr("账号与同步"),
                icon = Icons.Outlined.PersonOutline,
                value = if (loggedIn) "$email · ${if (plan == "pro") "Pro" else "免费版"}" else tr("未登录")
            ) { nav.navigate("account") }
            Hairline()
            NavRow(
                tr("主题 · 九色"),
                icon = Icons.Outlined.Brush,
                value = DEER_THEMES[ThemeCtl.index.coerceIn(0, 8)].name
            ) { themeSheet = true }
            Hairline()
            NavRow(tr("小鹿 AI 助手"), icon = Icons.Outlined.AutoAwesome) { nav.navigate("aiChat") }
            Hairline()
            NavRow(tr("搜索"), icon = Icons.Outlined.Search) { nav.navigate("search") }
            Hairline()
            NavRow(tr("分类管理"), icon = Icons.Outlined.Palette) { nav.navigate("categories") }
            Hairline()
            NavRow(tr("日历与默认设置"), icon = Icons.Outlined.Tune) { nav.navigate("calSettings") }
            Hairline()
            NavRow(tr("订阅与小鹿 AI"), icon = Icons.Outlined.WorkspacePremium) { nav.navigate("subscription") }
            Hairline()
            // P2-A11：入口 3 层 → 1 层。免费用户点一下直达付款页（等待卡在订阅页看）
            if (!com.looka.app.data.PlanState.isPro) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                NavRow(
                    tr("升级 Pro · 12元/月"),
                    icon = Icons.Outlined.WorkspacePremium,
                    value = tr("更聪明的小鹿 + 自创主题")
                ) {
                    if (!com.looka.app.net.Api.authed(ctx)) {
                        nav.navigate("account")
                    } else {
                        scope.launch {
                            val zh = com.looka.app.util.I18n.lang.startsWith("zh")
                            val url = if (zh) {
                                kotlin.runCatching { com.looka.app.net.Api.payIntent(ctx).optString("url") }
                                    .getOrNull()?.takeIf { it.isNotBlank() }
                                    ?: "https://ifdian.net/order/create?plan_id=95141ca09d2711f1bead52540025c377&product_type=0"
                            } else "https://ko-fi.com/c/4c6210054c"
                            Prefs.setPayPendingSince(ctx, System.currentTimeMillis())
                            kotlin.runCatching {
                                ctx.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }
                            nav.navigate("subscription")   // 回来能看到等待卡
                        }
                    }
                }
                Hairline()
            }
            NavRow(
                tr("语言 / Language"), icon = Icons.Outlined.Translate,
                value = com.looka.app.util.I18n.choiceLabel(Prefs.language(ctx))
            ) { nav.navigate("language") }
            Hairline()
            NavRow(tr("备份与恢复"), icon = Icons.Outlined.SaveAlt) { nav.navigate("backup") }
            Hairline()
            NavRow(tr("提醒自检"), icon = Icons.Outlined.NotificationsActive) { nav.navigate("selfcheck") }
            Hairline()
            NavRow(tr("同步冲突记录"), icon = Icons.Outlined.SyncProblem) { nav.navigate("conflicts") }
            Hairline()
            NavRow(tr("检查更新"), icon = Icons.Outlined.CloudDownload,
                value = "v" + com.looka.app.BuildConfig.VERSION_NAME) {
                checkingUpdate = true
            }
            Hairline()
            NavRow(tr("关于 Looka"), icon = Icons.Outlined.Info) { aboutDlg = true }
            Hairline()
            Spacer(Modifier.height(60.dp))
        }
    }

    // 九色主题选择
    if (themeSheet) ModalBottomSheet(
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

            // ── 自创主题（十三节 C4 v1）：挑一个自己的颜色。手帐要的是"这是我的本子"。
            Hairline()
            Text(
                tr("或者，调一个自己的颜色"),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            val palette = remember {
                listOf(
                    0xFF8C4A3CL, 0xFFB56A48L, 0xFFC98A4BL, 0xFF8F7B3EL, 0xFF5F7A3DL, 0xFF3E7A55L,
                    0xFF3E7A78L, 0xFF3E6C8FL, 0xFF4A5C9EL, 0xFF6D55A8L, 0xFF95538FL, 0xFFAD5271L,
                    0xFF87695AL, 0xFF6E7B6EL, 0xFF5C6B7AL, 0xFF444B52L, 0xFFB08E4EL, 0xFF7A5C9EL
                )
            }
            palette.chunked(6).forEach { rowColors ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    rowColors.forEach { argb ->
                        val selC = ThemeCtl.index == com.looka.app.ui.theme.CUSTOM_THEME &&
                                   ThemeCtl.customColor == argb
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selC) 2.5.dp else 0.8.dp,
                                    color = if (selC) Ink else Color(0x33000000),
                                    shape = CircleShape
                                )
                                .plainClick {
                                    // 自创主题是 Pro 权益（2026-08-21 决定）。
                                    // 未开通不弹冷冰冰的付费墙 —— 先演示功能（ProFeatureDialog）
                                    if (com.looka.app.data.Prefs.isPro(ctx)) ThemeCtl.setCustom(ctx, argb)
                                    else customGate = argb
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selC) Text("✓", color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
            Text(
                tr("挑一个主色，整本手帐的浅底与深字会自动配好"),
                fontSize = 11.5.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
    }

    updateMsg?.let { m ->
        androidx.compose.runtime.LaunchedEffect(m) {
            com.looka.app.ui.common.toast(ctx, m)
            updateMsg = null
        }
    }
    // 自创主题上锁弹窗：用他刚点的颜色现场演示"纸"会变成什么样
    customGate?.let { argb ->
        val t = com.looka.app.ui.theme.customTheme(argb)
        com.looka.app.ui.common.ProFeatureDialog(
            title = tr("调一个自己的颜色"),
            desc = tr("Pro 可以用任意颜色做主题：整本手帐的纸色、按钮、强调色都会跟着换。上面就是你刚选的颜色铺出来的样子。"),
            onGo = { customGate = null; nav.navigate("subscription") },
            onDismiss = { customGate = null },
            demo = {
                // 迷你手帐页：纸色底 + 主色标题条 + 两行"日程"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(t.paper)
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(t.primary))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("我的手帐"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.onContainer)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(t.container).padding(horizontal = 6.dp, vertical = 3.dp)
                    ) { Text(tr("9:00 晨跑"), fontSize = 10.sp, color = t.onContainer) }
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(Color.White).padding(horizontal = 6.dp, vertical = 3.dp)
                    ) { Text(tr("14:00 和朋友喝茶"), fontSize = 10.sp, color = t.onContainer) }
                }
            }
        )
    }
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text(tr("发现新版本 ") + info.versionName, fontSize = 17.sp) },
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
        title = { Text("Looka v" + com.looka.app.BuildConfig.VERSION_NAME, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    tr("小鹿 Looka，可爱版九色鹿，你的极简生活手帐。\n\n") +
                            tr("· 灵感来自敦煌壁画「九色鹿」：一鹿九色，故有九套主题\n") +
                            tr("· 日历为中心：日程、任务、日记、印章围绕日期组织\n") +
                            tr("· 数据本机优先，登录后云同步（looka.foyue.org 网页端可用）\n") +
                            tr("· 小鹿 AI 对话不限次数\n") +
                            tr("· 独立原创品牌与设计"),
                    fontSize = 13.sp, lineHeight = 21.sp
                )
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

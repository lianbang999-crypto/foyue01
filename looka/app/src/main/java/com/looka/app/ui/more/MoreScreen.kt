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
import androidx.compose.material.icons.outlined.CleaningServices
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
    // 自创主题 Pro 门禁：记录用户点的那个颜色，弹窗里就用它演示（看得见才想要）
    var customGate by remember { mutableStateOf<Long?>(null) }
    // B6-lite（§48）：从照片取色生成主题。取色在端上完成（androidx.palette），图片不出设备。
    val themeScope = androidx.compose.runtime.rememberCoroutineScope()
    var photoThemes by remember { mutableStateOf<List<Long>>(emptyList()) }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) themeScope.launch {
            val colors = extractThemeColors(ctx, uri)
            if (colors.isEmpty()) com.looka.app.ui.common.toast(ctx, tr("没取到合适的颜色，换一张色彩多一点的照片试试？"))
            else photoThemes = colors
        }
    }
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
            // 按语义 6 组分组列表 —— Lifebear More 的真实骨架：emoji 分组标题 + 行级列表（§64 五）。
            // X1：小鹿 AI 独立入口回归（§67 事故修复）。
            SectionLabel(tr("我"))
            NavRow(
                tr("账号与同步"), icon = Icons.Outlined.PersonOutline,
                value = if (loggedIn) "$email · ${if (plan == "pro") "Pro" else tr("免费版")}" else tr("未登录")
            ) { nav.navigate("account") }
            Hairline()
            NavRow(tr("订阅 · 鹿角"), icon = Icons.Outlined.WorkspacePremium) { nav.navigate("subscription") }
            Hairline()

            SectionLabel(tr("小鹿"))
            NavRow(tr("小鹿 AI"), icon = Icons.Outlined.AutoAwesome) { nav.navigate("aiChat") }
            Hairline()

            SectionLabel(tr("外观"))
            NavRow(
                tr("主题"), icon = Icons.Outlined.Brush,
                value = DEER_THEMES[ThemeCtl.index.coerceIn(0, 8)].name
            ) { themeSheet = true }
            Hairline()

            SectionLabel(tr("设置"))
            NavRow(tr("日历设置"), icon = Icons.Outlined.Tune) { nav.navigate("calSettings") }
            Hairline()
            NavRow(tr("提醒自检"), icon = Icons.Outlined.NotificationsActive) { nav.navigate("selfcheck") }
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
            NavRow(tr("关于 Looka"), icon = Icons.Outlined.Info,
                value = "v" + com.looka.app.BuildConfig.VERSION_NAME) { aboutDlg = true }
            Hairline()
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

            // ── 自创主题（十三节 C4 v1）：挑一个自己的颜色。手帐要的是"这是我的本子"。
            Hairline()
            Text(
                tr("或者，调一个自己的颜色"),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            // T10（§65）：与 Lifebear 同规格 48 色盘（复用分类色盘，滤掉当不了主色的极亮/极暗）
            val palette = remember {
                com.looka.app.data.LIST_PALETTE.mapNotNull { hex ->
                    val argb = 0xFF000000L or hex.removePrefix("#").toLong(16)
                    val c0 = Color(argb)
                    if (c0.luminance() in 0.04f..0.72f) argb else null
                }
            }
            palette.chunked(8).forEach { rowColors ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    rowColors.forEach { argb ->
                        val selC = ThemeCtl.index == com.looka.app.ui.theme.CUSTOM_THEME &&
                                   ThemeCtl.customColor == argb
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
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
                            if (selC) Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Text(
                tr("挑一个主色，整本手帐的浅底与深字会自动配好"),
                fontSize = 11.5.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // ── B6-lite：从照片取色（Pro）。取色在手机上完成，照片不会上传 ──
            Hairline()
            Row(
                Modifier.fillMaxWidth()
                    .plainClick {
                        if (com.looka.app.data.Prefs.isPro(ctx)) photoPicker.launch("image/*")
                        else customGate = 0xFF8C4A3CL
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📷", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(tr("从照片生成主题"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("挑一张照片，小鹿取色配一套"),
                        fontSize = 11.sp, color = GrayText
                    )
                }
            }
        }
    }

    // B6-lite：照片取色候选（最多 3 套，点一套即应用）
    if (photoThemes.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { photoThemes = emptyList() },
            title = { Text(tr("照片里的颜色 🦌"), fontSize = 16.sp) },
            text = {
                Column {
                    Text(tr("挑一套喜欢的，点一下就换上"), fontSize = 12.sp, color = GrayText)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        photoThemes.take(3).forEach { argb ->
                            val t = com.looka.app.ui.theme.customTheme(argb)
                            Column(
                                Modifier.plainClick {
                                    ThemeCtl.setCustom(ctx, argb)
                                    photoThemes = emptyList()
                                },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.size(56.dp).clip(CircleShape).background(t.container)
                                        .border(0.8.dp, Color(0x33000000), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(Modifier.size(30.dp).clip(CircleShape).background(t.primary))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { photoThemes = emptyList() }) { Text(tr("取消"), color = GrayText) }
            }
        )
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

/**
 * B6-lite（§48）：端上取色 —— 照片不出设备。
 * Palette 各 swatch 按「适合做主题主色」过滤（太亮太暗都撑不起浅底深字的推导），
 * 再按色相去重，最多给 3 个候选。
 */
private suspend fun extractThemeColors(
    c: android.content.Context,
    uri: android.net.Uri
): List<Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        // 采样解码：取色不需要原图，128px 足够且省内存
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 128) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(
                it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching emptyList()

        val p = androidx.palette.graphics.Palette.from(bmp).maximumColorCount(24).generate()
        val raw = listOfNotNull(
            p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
            p.darkMutedSwatch, p.lightVibrantSwatch, p.dominantSwatch
        ).map { it.rgb }

        val out = ArrayList<Long>()
        for (rgb in raw) {
            val col = androidx.compose.ui.graphics.Color(rgb or 0xFF000000.toInt())
            val lum = col.luminance()
            if (lum < 0.05f || lum > 0.75f) continue          // 太暗/太亮做不了主色
            val argb = 0xFF000000L or (rgb.toLong() and 0xFFFFFFL)
            // 色相去重：与已选颜色太接近的跳过
            val h1 = hueOf(col)
            if (out.any { kotlin.math.abs(hueOf(androidx.compose.ui.graphics.Color(it)) - h1)
                    .let { d -> minOf(d, 360f - d) } < 24f }) continue
            out += argb
            if (out.size >= 3) break
        }
        out
    }.getOrDefault(emptyList())
}

private fun hueOf(c: androidx.compose.ui.graphics.Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    return hsv[0]
}



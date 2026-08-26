package com.looka.app.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.looka.app.data.Prefs
import com.looka.app.util.tr

// ---- 视觉常量（规格 §12 白底工具感） ----
//
// §106 A：其中四个改成**读语义令牌的 getter**（见 Tokens.kt）。
// 值没变 —— `Tokens.derived` 的出厂默认就是下面注释里那几个原值，逐位相同。
// 改成 getter 是为了让将来的主题包能接管它们：这四个常量身后有 467 处调用点，
// 走 getter 就全部自动跟随，一处调用方都不用改。
// 在 composable 里读它们会登记 Compose 快照读，装包/换主题时该重组的会自己重组。
val Ink = Color(0xFF1B1B1F)          // 主文字（契约里对应 text_primary，但那个槽还没接渲染层，先固定）
val GrayText: Color get() = Tokens.active.textSecondary   // 原 #727776（§81：4.55:1，够正文 AA）
val Hairline: Color get() = Tokens.active.divider         // 原 #D8DBD8（v1.3 加深：ECECEC 在日历网格上看不见）
val PanelBg = Color(0xFFF7F8F7)      // 浅灰面板（契约无对应 slot，保持固定）
val DimBg = Color(0xFFF4F5F4)        // 非本月日期底（契约无对应 slot）
val LinkBlue = Color(0xFF2E6BD8)     // “显示详细设置”链接蓝（契约无对应 slot）
val HolidayRed: Color get() = Tokens.active.holiday       // 原 #E0504A 休日红
val SatBlue: Color get() = Tokens.active.weekend          // 原 #4A7DDC 周六蓝
val SaveDark = Color(0xFF3F3F46)     // 深色保存按钮（契约无对应 slot）

// 品牌绿（鹿徽标底色，固定不随主题变）
val LookaGreen = Color(0xFF55B04B)
val LookaGreenSoft = Color(0xFFEAF6E7)

/** 九色鹿主题：一鹿九色（敦煌九色鹿意象，可爱版） */
data class DeerTheme(
    val name: String,
    val primary: Color,
    val container: Color,
    val onContainer: Color,
    /** 页面底色（"纸"）。之前写死白色 —— 换主题只动 10% 的点缀像素，所以毫无感觉 */
    val paper: Color = Color.White,
    /** 面板/输入框底色，比 paper 深一档 */
    val panel: Color = PanelBg,
    // ── B2（§48）：主题 6 → 11 字段。全部给默认值 —— 老主题反序列化/旧代码路径不受影响。
    // texture/decor/banner 等 B5 素材库产出后接入渲染；在那之前它们只是"有位置放"。
    /** 字色（宣纸配墨色、抹茶配深绿 —— 敦煌稿的整体感一半来自字色） */
    val ink: Color = Ink,
    /** 背景纹理资源名（宣纸 / 祥云 / 水彩晕染），null = 纯色纸 */
    val texture: String? = null,
    /** 角落装饰插画组名（树叶 / 鹿 / 莲花），null = 无装饰 */
    val decor: String? = null,
    /** 顶部横幅资源名（敦煌九色鹿长卷），null = 无横幅 */
    val banner: String? = null,
    /** FAB 样式：flat / glow / ring */
    val fabStyle: String = "flat"
)

/** 由主色调出一张"纸"：掺 96% 白 —— 有色温但不喧宾夺主，长时间看不累 */
fun paperOf(c: Color, f: Float = 0.96f) = Color(
    c.red * (1 - f) + f, c.green * (1 - f) + f, c.blue * (1 - f) + f
)

/** 两色线性混合（浅底 = 掺白 90%，深字 = 掺黑 55%，全站主题共用这一个公式） */
fun mixColor(c: Color, w: Color, f: Float) = Color(
    c.red * (1 - f) + w.red * f, c.green * (1 - f) + w.green * f, c.blue * (1 - f) + w.blue * f
)

/** 九色统一由主色推导浅底与深字 —— 九色来自 48 色盘，配套色机器推导，不再逐个手调 */
private fun themeOf(name: String, argb: Long): DeerTheme {
    val base = Color(argb)
    return DeerTheme(name, base, mixColor(base, Color.White, 0.90f), mixColor(base, Color.Black, 0.55f))
}

// §107 B（2026-08-26 用户拍板）：**纸色回到纯白，对齐 Lifebear。**
//
// 这是撤回 §48 B2 那条「纸色掺 4% 主色」。当时的理由是"换主题只动 10% 的点缀像素，
// 所以毫无感觉"—— 理由本身没错，但**解法错了**：靠给整张纸染色去制造"换了主题"的感觉，
// 代价是长时间阅读的底色不再中性，而 Lifebear 实机（图 99/100/122）从日历到编辑页
// 全是纯白。内容区必须中性，这也正是主题规格「Skin 只能在 Layer 2–5、不进内容层」那条。
//
// "换主题该有感觉"这件事不作废，只是换了承载物：**由皮肤的插画去承载**
// （顶栏画带 / 角落装饰 / 空状态 / 底部导航），不是由纸的色温去承载。
// 那套槽位就是 §107 C 这批建的。
// §112（2026-08-26 用户拍板）：**九色从 48 色盘里提取** —— 主题色与分类/清单色
// 同一个色系，日历上主题强调色和用户内容色永远不打架。
// 提取方法：对原九色在 LIST_PALETTE 里找**色相优先**的最近邻（纯 RGB 距离会被亮度
// 带偏 —— 第一轮算出"樱粉→珊瑚粉、鎏金→橄榄绿"，色相加权后才对）。两处人工把关：
//   樱粉取 #FE74C2（首选 #FD98D0 太浅撑不起主色）；
//   鎏金取 #FFBE0C（48 色里最"金"的一个；原 #C9A227 那种暗金盘里没有 ——
//   代价：鎏金主题下 primary 直接当前景色的地方会比原来浅一档，实测不行再议）。
val DEER_THEMES = listOf(
    themeOf(tr("森绿"), 0xFF50A955),
    themeOf(tr("青碧"), 0xFF0EAE96),
    themeOf(tr("天蓝"), 0xFF4EBEEC),
    themeOf(tr("绀青"), 0xFF435EC9),
    themeOf(tr("藕紫"), 0xFF877BDD),
    themeOf(tr("樱粉"), 0xFFFE74C2),
    themeOf(tr("珊瑚"), 0xFFED4E60),
    themeOf(tr("暖橙"), 0xFFF7941F),
    themeOf(tr("鎏金"), 0xFFFFBE0C)
)

/** 自创主题（十三节 C4 v1，2026-08-21）：用户挑一个主色，浅底与深字由 HSL 推导 */
const val CUSTOM_THEME = -1

// §112：自创主题的**入口已撤**（用户拍板"主题只留九色"，自创色盘与照片取色
// 一并移除 —— 那是个 Pro 卖点，撤的代价在 §112 记账）。
// 这个函数保留：已经设了自定义主题的老用户，升级后主题不能凭空变掉 ——
// ThemeCtl.init 仍认 index=-1，直到他自己挑一个九色为止。
fun customTheme(argb: Long): DeerTheme {
    val base = Color(argb)
    return DeerTheme(
        tr("自定义"), base,
        mixColor(base, Color.White, 0.90f), mixColor(base, Color.Black, 0.55f)
    )
}

/** 主题控制器：切换立即生效并持久化。index = -1 表示用户自创主题 */
object ThemeCtl {
    var index by mutableIntStateOf(0)
        private set
    var customColor by mutableLongStateOf(0xFF55B04BL)
        private set

    fun init(c: Context) {
        customColor = Prefs.customThemeColor(c)
        val i = Prefs.themeIndex(c)
        index = if (i == CUSTOM_THEME && customColor != 0L) CUSTOM_THEME
                else i.coerceIn(0, DEER_THEMES.size - 1)
        syncTokens()
    }

    fun set(c: Context, i: Int) {
        index = if (i == CUSTOM_THEME) CUSTOM_THEME else i.coerceIn(0, DEER_THEMES.size - 1)
        Prefs.setThemeIndex(c, index)
        Prefs.markSettingsDirty(c)   // B1：主题随 settings 实体上云
        syncTokens()
    }

    fun setCustom(c: Context, argb: Long) {
        customColor = argb
        Prefs.setCustomThemeColor(c, argb)
        set(c, CUSTOM_THEME)   // set() 里已经 syncTokens
    }

    /**
     * §106 A：主题一变就把语义令牌重算一次。
     * 放在这里而不是 LookaTheme 里，是因为**组合期不该写状态** ——
     * 在 composable 里写 Tokens.derived 会触发 "写后读" 警告并可能多跑一帧。
     */
    private fun syncTokens() { Tokens.derived = tokensOf(current()) }

    fun current(): DeerTheme =
        if (index == CUSTOM_THEME) customTheme(customColor)
        else DEER_THEMES[index.coerceIn(0, DEER_THEMES.size - 1)]
}

@Composable
fun LookaTheme(content: @Composable () -> Unit) {
    val t = ThemeCtl.current()
    // §90 R2（v1.3 §11：Theme apply 280–420ms crossfade）：此前换主题是**硬切** ——
    // 整屏颜色一帧跳变，看着像闪了一下。给随主题变的几个色加 300ms 过渡，
    // 换色过程变成"渐渐染上去"。reduce-motion 由系统动画缩放自动接管。
    //
    // §117 E1：colorScheme 的取值源从 DeerTheme 字段改为 **Tokens.active** ——
    // 这是主题包能"一装就全变"的关键一跳：装包时 Tokens.pack 覆盖 derived，
    // 全 App 经由 MaterialTheme.colorScheme 的读点自动跟随，一个调用方都不用改。
    // 没装包时 active == tokensOf(当前九色主题)，取值与旧写法逐位相同（零像素回归）。
    val tok = Tokens.active
    val spec = androidx.compose.animation.core.tween<Color>(300)
    val primary by androidx.compose.animation.animateColorAsState(tok.accent, spec, label = "thPrimary")
    val container by androidx.compose.animation.animateColorAsState(tok.selection, spec, label = "thContainer")
    val onContainer by androidx.compose.animation.animateColorAsState(t.onContainer, spec, label = "thOnContainer")
    val paper by androidx.compose.animation.animateColorAsState(tok.surface, spec, label = "thPaper")
    val panel by androidx.compose.animation.animateColorAsState(t.panel, spec, label = "thPanel")
    val ink by androidx.compose.animation.animateColorAsState(tok.textPrimary, spec, label = "thInk")
    MaterialTheme(
        // S4（§64）→ §113 A1：弹窗圆角对齐实机。AlertDialog 默认取 shapes.extraLarge(28dp)，
        // 一行改掉全站 31 个弹窗；底部面板要 16dp 顶角的单独在调用处指定。
        shapes = androidx.compose.material3.Shapes(
            // §113 A1：10dp → 3dp。Lifebear 实机 Dialog 顶角量得约 2dp（母档 4.1，977px 面板
            // 顶角 6 原图 px）——10dp 在它的视觉语言里已经是「卡片」。取 3dp 不取 2dp 是
            // 因为 2dp 在 3x 密度屏上渲染≈直角，3dp 是「看得出低圆角」的下限（偏离表 P-2）。
            // 锚定菜单走 extraSmall（M3 默认 4dp，在实机 4-6dp 带内，不另设）。
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
        ),
        colorScheme = lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = SaveDark,
            onSecondary = Color.White,
            background = paper,
            onBackground = ink,   // B2：字色随主题（默认仍是墨色 Ink，行为不变）
            surface = paper,
            onSurface = ink,
            surfaceVariant = panel,
            onSurfaceVariant = GrayText,
            // §113 A6：DropdownMenu 容器走 surfaceContainer，M3 默认从色调派生出一层
            // 淡紫灰 —— Lifebear 锚定菜单是纯白（图 16/23），显式钉成白
            surfaceContainer = Color.White,
            outline = Color(0xFFD8DAD8),
            outlineVariant = Hairline,
            // §117 E1：error 从 HolidayRed（holiday 槽）改读 danger 槽 —— 两槽出厂同值，
            // 但语义不同：主题包可以把节日染成品牌红、而删除警示保持标准红
            error = Tokens.active.danger,
            // §117 E1：scrim 槽接上 —— AlertDialog/ModalBottomSheet 的遮罩经由这里。
            // M3 组件对 colorScheme.scrim 会再乘一层 32% alpha，所以包里的 scrim
            // 存 RGB 主体即可；60% 总浓度已由 §113 在组件层锚定
            scrim = Tokens.active.scrim.copy(alpha = 1f)
        ),
        content = content
    )
}

/**
 * §109 B：搜索框底色。实机量得 **#EFEFEF**，高 44.5dp、圆角 ~1.8dp。
 *
 * 单列出来而不是复用 `PanelBg`：`PanelBg #F7F8F7` 在白纸上几乎看不见，
 * 搜索框会"消失"成一片白 —— **克制不等于看不见**。
 * 但 PanelBg 另有 19 处调用点（输入框底、卡片底），一起加深会牵连太多，所以分开。
 */
val SearchBg = Color(0xFFEFEFEF)

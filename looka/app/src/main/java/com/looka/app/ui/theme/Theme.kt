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
import com.looka.app.data.Prefs
import com.looka.app.util.tr

// ---- 固定视觉常量（不随主题变化，规格 §12 白底工具感） ----
val Ink = Color(0xFF1B1B1F)          // 主文字
val GrayText = Color(0xFF8A8F8E)     // 次级文字
val Hairline = Color(0xFFD8DBD8)     // 细分隔线（v1.3 加深：原 ECECEC 在日历网格上几乎看不见）
val PanelBg = Color(0xFFF7F8F7)      // 浅灰面板
val DimBg = Color(0xFFF4F5F4)        // 非本月日期底
val LinkBlue = Color(0xFF2E6BD8)     // “显示详细设置”链接蓝
val HolidayRed = Color(0xFFE0504A)   // 休日红
val SatBlue = Color(0xFF4A7DDC)      // 周六蓝
val SaveDark = Color(0xFF3F3F46)     // 深色保存按钮

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
    val panel: Color = PanelBg
)

/** 由主色调出一张"纸"：掺 96% 白 —— 有色温但不喧宾夺主，长时间看不累 */
fun paperOf(c: Color, f: Float = 0.96f) = Color(
    c.red * (1 - f) + f, c.green * (1 - f) + f, c.blue * (1 - f) + f
)

val DEER_THEMES = listOf(
    DeerTheme(tr("森绿"), LookaGreen, LookaGreenSoft, Color(0xFF1E4D19), paperOf(LookaGreen), paperOf(LookaGreen, 0.92f)),
    DeerTheme(tr("青碧"), Color(0xFF2FA69A), Color(0xFFE3F4F1), Color(0xFF0F4B45), paperOf(Color(0xFF2FA69A)), paperOf(Color(0xFF2FA69A), 0.92f)),
    DeerTheme(tr("天蓝"), Color(0xFF4A9EDB), Color(0xFFE6F1FA), Color(0xFF16436B), paperOf(Color(0xFF4A9EDB)), paperOf(Color(0xFF4A9EDB), 0.92f)),
    DeerTheme(tr("绀青"), Color(0xFF4A7DDC), Color(0xFFE8EEFB), Color(0xFF1A3670), paperOf(Color(0xFF4A7DDC)), paperOf(Color(0xFF4A7DDC), 0.92f)),
    DeerTheme(tr("藕紫"), Color(0xFF7E6BD8), Color(0xFFEEEAFA), Color(0xFF35296B), paperOf(Color(0xFF7E6BD8)), paperOf(Color(0xFF7E6BD8), 0.92f)),
    DeerTheme(tr("樱粉"), Color(0xFFE077A8), Color(0xFFFBEAF2), Color(0xFF6B2145), paperOf(Color(0xFFE077A8)), paperOf(Color(0xFFE077A8), 0.92f)),
    DeerTheme(tr("珊瑚"), Color(0xFFE0504A), Color(0xFFFBEAE9), Color(0xFF6B1F1C), paperOf(Color(0xFFE0504A)), paperOf(Color(0xFFE0504A), 0.92f)),
    DeerTheme(tr("暖橙"), Color(0xFFF2913D), Color(0xFFFCEFE2), Color(0xFF6B3C0F), paperOf(Color(0xFFF2913D)), paperOf(Color(0xFFF2913D), 0.92f)),
    DeerTheme(tr("鎏金"), Color(0xFFC9A227), Color(0xFFF7F1DC), Color(0xFF5C4A0E), paperOf(Color(0xFFC9A227)), paperOf(Color(0xFFC9A227), 0.92f))
)

/** 自创主题（十三节 C4 v1，2026-08-21）：用户挑一个主色，浅底与深字由 HSL 推导 */
const val CUSTOM_THEME = -1

fun customTheme(argb: Long): DeerTheme {
    val base = Color(argb)
    // container = 主色掺 90% 白（同九色的浅底手感）；onContainer = 掺 55% 黑保证对比度
    fun mix(c: Color, w: Color, f: Float) = Color(
        c.red * (1 - f) + w.red * f, c.green * (1 - f) + w.green * f, c.blue * (1 - f) + w.blue * f
    )
    return DeerTheme(
        tr("自定义"), base,
        mix(base, Color.White, 0.90f), mix(base, Color.Black, 0.55f),
        paperOf(base), paperOf(base, 0.92f)
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
    }

    fun set(c: Context, i: Int) {
        index = if (i == CUSTOM_THEME) CUSTOM_THEME else i.coerceIn(0, DEER_THEMES.size - 1)
        Prefs.setThemeIndex(c, index)
    }

    fun setCustom(c: Context, argb: Long) {
        customColor = argb
        Prefs.setCustomThemeColor(c, argb)
        set(c, CUSTOM_THEME)
    }

    fun current(): DeerTheme =
        if (index == CUSTOM_THEME) customTheme(customColor)
        else DEER_THEMES[index.coerceIn(0, DEER_THEMES.size - 1)]
}

@Composable
fun LookaTheme(content: @Composable () -> Unit) {
    val t = ThemeCtl.current()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = t.primary,
            onPrimary = Color.White,
            primaryContainer = t.container,
            onPrimaryContainer = t.onContainer,
            secondary = SaveDark,
            onSecondary = Color.White,
            background = t.paper,
            onBackground = Ink,
            surface = t.paper,
            onSurface = Ink,
            surfaceVariant = t.panel,
            onSurfaceVariant = GrayText,
            outline = Color(0xFFD8DAD8),
            outlineVariant = Hairline,
            error = HolidayRed
        ),
        content = content
    )
}

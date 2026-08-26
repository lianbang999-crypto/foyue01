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
val DEER_THEMES = listOf(
    DeerTheme(tr("森绿"), LookaGreen, LookaGreenSoft, Color(0xFF1E4D19)),
    DeerTheme(tr("青碧"), Color(0xFF2FA69A), Color(0xFFE3F4F1), Color(0xFF0F4B45)),
    DeerTheme(tr("天蓝"), Color(0xFF4A9EDB), Color(0xFFE6F1FA), Color(0xFF16436B)),
    DeerTheme(tr("绀青"), Color(0xFF4A7DDC), Color(0xFFE8EEFB), Color(0xFF1A3670)),
    DeerTheme(tr("藕紫"), Color(0xFF7E6BD8), Color(0xFFEEEAFA), Color(0xFF35296B)),
    DeerTheme(tr("樱粉"), Color(0xFFE077A8), Color(0xFFFBEAF2), Color(0xFF6B2145)),
    DeerTheme(tr("珊瑚"), Color(0xFFE0504A), Color(0xFFFBEAE9), Color(0xFF6B1F1C)),
    DeerTheme(tr("暖橙"), Color(0xFFF2913D), Color(0xFFFCEFE2), Color(0xFF6B3C0F)),
    DeerTheme(tr("鎏金"), Color(0xFFC9A227), Color(0xFFF7F1DC), Color(0xFF5C4A0E))
)

/** 自创主题（十三节 C4 v1，2026-08-21）：用户挑一个主色，浅底与深字由 HSL 推导 */
const val CUSTOM_THEME = -1

fun customTheme(argb: Long): DeerTheme {
    val base = Color(argb)
    // container = 主色掺 90% 白（同九色的浅底手感）；onContainer = 掺 55% 黑保证对比度
    fun mix(c: Color, w: Color, f: Float) = Color(
        c.red * (1 - f) + w.red * f, c.green * (1 - f) + w.green * f, c.blue * (1 - f) + w.blue * f
    )
    // §107 B：自创主题的纸色同样回到纯白（否则九色是白纸、自创是色纸，两套规矩）
    return DeerTheme(
        tr("自定义"), base,
        mix(base, Color.White, 0.90f), mix(base, Color.Black, 0.55f)
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
    val spec = androidx.compose.animation.core.tween<Color>(300)
    val primary by androidx.compose.animation.animateColorAsState(t.primary, spec, label = "thPrimary")
    val container by androidx.compose.animation.animateColorAsState(t.container, spec, label = "thContainer")
    val onContainer by androidx.compose.animation.animateColorAsState(t.onContainer, spec, label = "thOnContainer")
    val paper by androidx.compose.animation.animateColorAsState(t.paper, spec, label = "thPaper")
    val panel by androidx.compose.animation.animateColorAsState(t.panel, spec, label = "thPanel")
    val ink by androidx.compose.animation.animateColorAsState(t.ink, spec, label = "thInk")
    MaterialTheme(
        // S4（§64）：弹窗统一 8dp —— AlertDialog 默认取 shapes.extraLarge(28dp)，
        // 一行改掉全站 33 个弹窗；底部面板要 16dp 顶角的单独在调用处指定。
        shapes = androidx.compose.material3.Shapes(
            // §90 R1：全站圆角归到五档 —— 容器/弹窗 10dp · 搜索框与输入框 4dp（按实机修订，
            // 撤销 §64 那条「输入框 22dp 胶囊」）· Chip/Popover 6dp · 圆形元素 CircleShape。
            // AI 聊天输入框的 22dp 胶囊属聊天场景，单列保留（见 §90 四）。
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
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
            outline = Color(0xFFD8DAD8),
            outlineVariant = Hairline,
            error = HolidayRed
        ),
        content = content
    )
}

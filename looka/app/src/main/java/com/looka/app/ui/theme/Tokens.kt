package com.looka.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * §106 A：**语义色令牌（Theme Tokens v1）**。
 *
 * 为什么现在就做：将来换主题不是"换一个主色"，是装一个**主题包**
 * （见 0826 参考图里 Lifebear 的着せかえショップ —— 一个包会同时换掉
 * 顶栏插画、底部导航、锁屏、启动图）。主题包发下来是一份 JSON，
 * 渲染层必须有一组**稳定的语义名**去接它，不能靠散在各处的 `Color(0xFF...)`。
 *
 * 字段名与 looka-next 的 `design/contracts/theme-tokens.schema.json` v1
 * **一一对应**（那边是 snake_case，这边按 Kotlin 惯例转驼峰，语义不变），
 * 所以将来两个项目能用同一份主题包，不需要做名字映射表。
 *
 * ⚠️ **本批只做接口，不改任何一个像素。**
 * 出厂默认值是从当前九色主题**逐个抄过来的**，与改动前逐位相同。
 *
 * 目前**已经接上渲染层**的槽（改它就会变）：
 *   `holiday` `weekend` `divider` `textSecondary`
 *   —— 对应 `HolidayRed` / `SatBlue` / `Hairline` / `GrayText` 四个常量，
 *      共 467 处调用点，全部自动跟随，不需要改调用方。
 *
 * 目前**只占位、还没有渲染层在读**的槽（诚实记账，别当成已完成）：
 *   `surface` `surfaceVariant` `textPrimary` `textTertiary` `accent`
 *   `selection` `today` `eventAllDay` `eventTimed` `eventExternal` `scrim` `danger`
 *   这些位置现在走 MaterialTheme.colorScheme 或就地字面量。
 *   接的时候按契约名替换即可，不用再讨论叫什么。
 */
@Immutable
data class LookaTokens(
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val weekend: Color,
    val holiday: Color,
    val selection: Color,
    val today: Color,
    val eventAllDay: Color,
    val eventTimed: Color,
    val eventExternal: Color,
    val divider: Color,
    val scrim: Color,
    val danger: Color
)

// ── 出厂原始值 ──
// 必须是字面量，不能引用下面那些 getter 常量：那些 getter 反过来读 Tokens.active，
// 会绕成死循环（第一版就是这么写崩的）。
private val RAW_INK = Color(0xFF1B1B1F)
private val RAW_GRAY = Color(0xFF727776)
private val RAW_GRAY_LIGHT = Color(0xFF9AA09E)
private val RAW_HAIRLINE = Color(0xFFD8DBD8)
private val RAW_PANEL = Color(0xFFF7F8F7)
private val RAW_HOLIDAY = Color(0xFFE0504A)
private val RAW_WEEKEND = Color(0xFF4A7DDC)
private val RAW_SCRIM = Color(0x66000000)

/** 从九色主题推导一套令牌 —— 这是"没装主题包"时的取值来源 */
fun tokensOf(t: DeerTheme) = LookaTokens(
    surface = t.paper,
    surfaceVariant = RAW_PANEL,          // 注意：不是 t.panel。PanelBg 现在是固定灰，
                                         // 改成跟主题走会当场变色，超出本批"零像素改动"的范围
    textPrimary = t.ink,
    textSecondary = RAW_GRAY,
    textTertiary = RAW_GRAY_LIGHT,
    accent = t.primary,
    weekend = RAW_WEEKEND,
    holiday = RAW_HOLIDAY,
    selection = t.container,
    today = t.primary,
    eventAllDay = t.container,
    eventTimed = t.primary,
    eventExternal = RAW_GRAY,
    divider = RAW_HAIRLINE,
    scrim = RAW_SCRIM,
    danger = RAW_HOLIDAY
)

/**
 * 令牌总线。取值优先级：**主题包 > 九色主题推导**。
 *
 * `derived` 由 [ThemeCtl] 在主题切换时写入；`pack` 留给将来的主题包。
 * 两个都是 Compose state，所以在 composable 里读 [active] 会登记快照读，
 * 换主题/装包时该重组的地方会自己重组 —— 调用点一行都不用改。
 */
object Tokens {
    /** 主题包覆盖层。null = 没装包，走九色主题 */
    var pack by mutableStateOf<LookaTokens?>(null)
        private set

    /** 九色主题推导值 */
    var derived by mutableStateOf(tokensOf(DEER_THEMES[0]))
        internal set

    val active: LookaTokens get() = pack ?: derived

    /** 装 / 卸主题包。传 null 即回到九色主题 */
    fun applyPack(p: LookaTokens?) { pack = p }

    /**
     * 按 `theme-tokens.schema.json` v1 解析一份主题包令牌。
     *
     * 严格照契约来：缺 `semantic` 里任何一个必填键就返回 null（**整包拒**，
     * 不做"缺了就拿默认值凑"——凑出来的是一套没人验过的配色，
     * 比直接不装更糟）。`event_palette` / `list_palette` 本批还没有消费方，
     * 先只校验不落地。
     */
    fun parse(json: String): LookaTokens? = runCatching {
        val o = JSONObject(json)
        if (o.optString("schema_version") != "1.0") return null
        val s = o.getJSONObject("semantic")
        fun c(k: String): Color {
            val hex = s.getString(k).removePrefix("#")
            return Color(
                if (hex.length == 8) hex.toLong(16) or 0L   // AARRGGBB
                else 0xFF000000L or hex.toLong(16)          // RRGGBB
            )
        }
        LookaTokens(
            surface = c("surface"), surfaceVariant = c("surface_variant"),
            textPrimary = c("text_primary"), textSecondary = c("text_secondary"),
            textTertiary = c("text_tertiary"), accent = c("accent"),
            weekend = c("weekend"), holiday = c("holiday"),
            selection = c("selection"), today = c("today"),
            eventAllDay = c("event_all_day"), eventTimed = c("event_timed"),
            eventExternal = c("event_external"), divider = c("divider"),
            scrim = c("scrim"), danger = c("danger")
        )
    }.getOrNull()
}

package com.looka.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * §107 C：**皮肤换装接口层**（照 looka-next 那一版搬，只搬接口不搬实现）。
 *
 * 为什么照它搬、而不是自己再想一套：那边已经把两件事想清楚了，
 * 而且**这两件事想错了事后补不回来** ——
 *
 *  1. **UI 永远不直接拿资源路径。** 链路统一是
 *     `ID → Resolver → 当前 Style 配置 → 资产库 / 内置`。
 *     将来主题图是 AI 生的：要下载、要校验 hash、可能缺失、可能还在路上。
 *     调用点如果各写各的 painterResource，这些事就没有一个统一的地方去做。
 *  2. **槽位与图标 ID 是机器合同，不是随手起的名字。**
 *     `docs/contracts/{slot,icon}-registry.v1.json` 是真相源，
 *     下面两个枚举**由脚本从 JSON 生成**，`scripts/check_contracts.py` 负责对账。
 *     **不许手加枚举项** —— 加了槽位却没进 Registry，
 *     等于让生成端（AI）和渲染端各说各话，而这种错要等一整套图生出来才发现。
 *
 * 与 looka-next 共用同一份 Registry 的直接好处：**主题包两个项目之间可以互换**。
 *
 * ⚠️ 本批同样**只建接口**。[DefaultSkinResolver] 对所有槽位返回 [ResolvedAsset.Absent]，
 * 渲染层按语义回退（纹理 → 走 surface 色、装饰 → 不显示），合入后界面逐像素不变。
 *
 * **诚实记账**：25 个槽位里，v1 当前真有渲染层去问的只有
 * `GLOBAL_MASCOT_DEFAULT`（DeerBadge）一个。其余 24 个是**占位** ——
 * 名字定了、回退定了，但还没有代码去查询它们。这不叫"皮肤系统做好了"。
 */

/** 皮肤 / 吉祥物资产槽位。真相源：`docs/contracts/slot-registry.v1.json` */
enum class SkinSlot(val slotId: String) {
    GLOBAL_SURFACE_TEXTURE("global.surface.texture"),
    GLOBAL_BACKGROUND_DECORATION("global.background.decoration"),
    GLOBAL_MASCOT_DEFAULT("global.mascot.default"),
    GLOBAL_MASCOT_EMPTY("global.mascot.empty"),
    CALENDAR_HEADER_ART("calendar.header.art"),
    CALENDAR_SURFACE_TEXTURE("calendar.surface.texture"),
    CALENDAR_MONTH_WATERMARK("calendar.month.watermark"),
    CALENDAR_CORNER_TOP_LEFT("calendar.corner.top_left"),
    CALENDAR_CORNER_TOP_RIGHT("calendar.corner.top_right"),
    CALENDAR_CORNER_BOTTOM_LEFT("calendar.corner.bottom_left"),
    CALENDAR_CORNER_BOTTOM_RIGHT("calendar.corner.bottom_right"),
    CALENDAR_FOOTER_ART("calendar.footer.art"),
    CALENDAR_DECOR_OVERLAY("calendar.decor.overlay"),
    CALENDAR_MARKER_TODAY("calendar.marker.today"),
    CALENDAR_MARKER_SELECTED("calendar.marker.selected"),
    TODO_HEADER_ART("todo.header.art"),
    TODO_SURFACE_TEXTURE("todo.surface.texture"),
    TODO_EMPTY_ILLUSTRATION("todo.empty.illustration"),
    NOTES_HEADER_ART("notes.header.art"),
    NOTES_SURFACE_TEXTURE("notes.surface.texture"),
    NOTES_EMPTY_ILLUSTRATION("notes.empty.illustration"),
    DIARY_HEADER_ART("diary.header.art"),
    DIARY_PAPER_TEXTURE("diary.paper.texture"),
    DIARY_EMPTY_ILLUSTRATION("diary.empty.illustration"),
    BOTTOM_NAV_BACKGROUND_DECORATION("bottom_nav.background.decoration");

    companion object {
        private val byId = entries.associateBy { it.slotId }
        fun fromId(slotId: String): SkinSlot? = byId[slotId]
    }
}

/**
 * 功能图标 ID。真相源：`docs/contracts/icon-registry.v1.json`
 *
 * **IconId 决定功能，图形不决定功能** —— 动作绑 IconId、无障碍标签由系统固定，
 * 所以图形怎么画都不影响功能与可达性。这就是图标可以从 v1 起全开放给用户换的理由。
 */
enum class IconId(val iconId: String) {
    NAV_CALENDAR("nav.calendar"),
    NAV_TODO("nav.todo"),
    NAV_CREATE("nav.create"),
    NAV_NOTES("nav.notes"),
    NAV_MORE("nav.more"),
    ACTION_BACK("action.back"),
    ACTION_CLOSE("action.close"),
    ACTION_SAVE("action.save"),
    ACTION_SEARCH("action.search"),
    ACTION_EDIT("action.edit"),
    ACTION_DELETE("action.delete"),
    ACTION_COPY("action.copy"),
    ACTION_SHARE("action.share"),
    ACTION_CAMERA("action.camera"),
    ACTION_MORE("action.more"),
    ACTION_UNDO("action.undo"),
    ACTION_ADD("action.add"),
    CALENDAR_TODAY("calendar.today"),
    CALENDAR_REMINDER("calendar.reminder"),
    CALENDAR_REPEAT("calendar.repeat"),
    CALENDAR_LOCATION("calendar.location"),
    CALENDAR_EVENT("calendar.event"),
    CALENDAR_TASK("calendar.task"),
    CALENDAR_ALL_DAY("calendar.all_day"),
    CALENDAR_EXTERNAL("calendar.external"),
    STATE_CHECKED("state.checked"),
    STATE_UNCHECKED("state.unchecked"),
    STATE_STARRED("state.starred"),
    STATE_UNSTARRED("state.unstarred"),
    STATE_WARNING("state.warning"),
    STATE_ERROR("state.error"),
    AI_SPARKLE("ai.sparkle"),
    AI_GENERATE("ai.generate"),
    AI_MAGIC("ai.magic"),
    AI_VOICE("ai.voice"),
    AI_CAMERA("ai.camera"),
    MORE_ACCOUNT("more.account"),
    MORE_PLAN("more.plan"),
    MORE_SETTINGS("more.settings"),
    MORE_NOTICE("more.notice"),
    MORE_STAMP_SHOP("more.stamp_shop"),
    MORE_DRESSUP_SHOP("more.dressup_shop"),
    MORE_HOWTO("more.howto"),
    MORE_FAQ("more.faq"),
    MORE_REMOVE_ADS("more.remove_ads");

    companion object {
        private val byId = entries.associateBy { it.iconId }
        fun fromId(iconId: String): IconId? = byId[iconId]
    }
}

enum class IconState { DEFAULT, SELECTED, DISABLED }

/** 资产解析结果。调用方只认这三种，**永远拿不到路径** */
sealed interface ResolvedAsset {
    /** 随包内置资产（Android 资源 id） */
    data class Builtin(val resId: Int) : ResolvedAsset

    /**
     * 资产库托管资产：已落盘并校验过 hash 的本地文件。
     * AI 生成的主题图走的就是这条 —— 下载与校验都收在这里，不散到调用点。
     */
    data class Managed(val localPath: String, val sha256: String) : ResolvedAsset

    /** 该槽位没有资产：调用方按语义回退。**这不是错误**，是常态 */
    data object Absent : ResolvedAsset
}

interface SkinResolver {
    fun asset(slot: SkinSlot): ResolvedAsset
}

interface IconResolver {
    /** 回退链：用户自定义 → 当前包 → 内置。**永远解析得出** —— 图标不能成为单点故障 */
    fun icon(id: IconId, state: IconState = IconState.DEFAULT): ResolvedAsset
}

/** 没装主题包时的系统默认：全部 Absent，交给语义回退 */
object DefaultSkinResolver : SkinResolver {
    override fun asset(slot: SkinSlot) = ResolvedAsset.Absent
}

/**
 * 皮肤总线。装包时替换 [resolver]，所有查询点自动改道，调用点一处都不用改。
 *
 * 和 [Tokens] 分开是有意的：**颜色和图片的生命周期不一样**。
 * 颜色是同步的、永远有值；图片可能还在下载、可能校验失败、可能永远没有。
 * 混成一个对象就必须让颜色也去处理"还没准备好"，那是白白把简单的事情做复杂。
 */
object SkinCtl {
    var resolver by mutableStateOf<SkinResolver>(DefaultSkinResolver)
}

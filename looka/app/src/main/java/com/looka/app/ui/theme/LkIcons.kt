package com.looka.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.looka.app.R

/**
 * §109 C：**Looka Core Icon System** 的 Kotlin 门面。
 *
 * 用户：「全站的功能键全部用我们给出来的参考 ICON 文件夹。」
 *
 * 图形来自 `icon组件素材参考/workspace/output/icons/`（24×24 / 1.8px 描边 / round caps），
 * 由 `scripts/build_icons.py` 转成 `res/drawable/ic_lk_*.xml`。
 *
 * **为什么返回 `ImageVector` 而不是 `Painter`**：全站有 6 个辅助函数
 * （`BarItem` / `NavRow` / `LookaTopBar.backIcon` / `DetailRow` / `ModeIcon` / 日历那个）
 * 的参数类型就是 `ImageVector`。`ImageVector.vectorResource()` 能把 VectorDrawable
 * 直接读成 `ImageVector` —— 于是替换只是把 `Icons.Outlined.X` 换成 `LkIcons.X`，
 * **一个函数签名都不用动，74 个调用点零重构**。
 *
 * ── 换不了的 44 处，分两类，都**有意保留 Material** ──────────────────
 *
 * **一类是方向/系统图标**（ChevronRight×9、ArrowBack×2、ChevronLeft、
 * KeyboardArrowUp/Down、ArrowDropDown、Remove，共 16 处）：
 * 这些是平台约定，用户对它们的形状有肌肉记忆，换成手绘反而认不出，
 * 而且返回键还牵扯 RTL 镜像。**不是没换，是不该换。**
 *
 * **另一类是包里真没有的功能图标**（Place×3、AutoAwesome×2、Repeat×2、
 * WorkspacePremium、Translate、SaveAlt、Inventory2、History、Inbox、
 * Description、Send，共 12 种 / 15 处）：
 * 这些**是缺口**，要补得让人再画 12 个。在补齐之前它们仍是 Material。
 */
object LkIcons {
    val Calendar: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_calendar)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_check)
    val Plus: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_plus)
    val Book: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_book)
    val More: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_more)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_search)
    val Star: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_star)
    val Clock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_clock)
    val Note: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_note)
    val Tag: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_tag)
    val Filter: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_filter)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_edit)
    val Trash: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_trash)
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_settings)
    val User: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_user)
    val Bell: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_bell)
    val Smile: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_smile)
    val Palette: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_palette)
    val Sticker: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_sticker)
    val Cloud: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_cloud)
    val Sync: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_sync)
    val Lock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_lock)
    val Help: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_help)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_close)

    // ── 派生（非原始 24 个，按同一笔法补，见 build_icons.py 注释）──
    /** 空心圆：待办未完成 */
    val Circle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_circle)
    /** 实心圆 + 白勾：待办已完成 */
    val CheckCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_check_circle)
    /** 实心星：与空心星同一条轮廓，只是填上 —— 两态形状完全一致 */
    val StarFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_star_fill)
    /** U 形回弯箭头：日历「回到今天」浮动按钮（照实机图 114 的字形描） */
    val ReturnToday: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_return_today)

    // §121：AI 灵光（模式排与 AI 入口统一细线稿）
    val Sparkle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_sparkle)

    // §117 A：附件（相机入口 + 图片占位）
    val Camera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_camera)
    val Image: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lk_image)
}

package com.looka.app.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.looka.app.data.Prefs

/**
 * §106 B：**广告位接口**（只留接口，本批不接任何广告 SDK）。
 *
 * 为什么现在就留：广告位不是"以后找块空地插进去"的东西 ——
 * 它有两条硬约束，**位置定晚了会推翻已经画好的布局**：
 *
 *  1. **广告位是系统层，皮肤不得覆盖、遮挡或伪装**（主题规格 Layer Stack 第 12 层
 *     System Promo）。拿装饰去盖广告位既违反平台政策，也会被判成欺诈。
 *     所以广告槽必须画在装饰**之上**，而且它的几何要先占住，
 *     不能等主题包画完了再来抢位置。
 *  2. **Pro 用户永远不渲染**（"移除广告"是订阅权益）。这条要在最外层短路，
 *     不能指望广告 SDK 自己判断。
 *
 * 没注册 provider 时 [AdSlot] **什么都不画、也不占高度** —— 所以本批合入后
 * 界面逐像素不变。
 */
enum class AdPlacement {
    /** 底部导航条**上方**的条形位。免费层可见，Pro 隐藏 */
    BOTTOM_NAV,

    /** 更多页的运营 Banner 位（对照 0826 参考图：更多页宫格下方那块大圆角） */
    MORE_BANNER
}

/**
 * 广告承接方。接 SDK 的那天写一个实现塞给 [Ads.provider] 即可，
 * 不需要动任何一个挂载点。
 */
interface AdProvider {
    /** 这个位置现在有货吗？没货就别留白 */
    fun isReady(placement: AdPlacement): Boolean

    /** 画出来。`modifier` 已带好宽度约束，实现方别自己 fillMaxSize */
    @Composable
    fun Render(placement: AdPlacement, modifier: Modifier)
}

object Ads {
    /** null = 没接广告（当前状态）。设成非 null 后所有挂载点自动开始渲染 */
    var provider by mutableStateOf<AdProvider?>(null)

    /** 单一判据：Pro 不看广告；没 provider 或没货也不占位 */
    fun shouldShow(ctx: Context, placement: AdPlacement): Boolean {
        if (Prefs.isPro(ctx)) return false
        return provider?.isReady(placement) == true
    }
}

/**
 * 广告槽。**挂载点已经埋好，不要再另找地方插广告** ——
 * 散着插就没法统一保证"Pro 不渲染"和"皮肤不覆盖"这两条。
 */
@Composable
fun AdSlot(placement: AdPlacement, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = Ads.provider ?: return
    if (!Ads.shouldShow(ctx, placement)) return
    Box(modifier.fillMaxWidth()) { p.Render(placement, Modifier.fillMaxWidth()) }
}

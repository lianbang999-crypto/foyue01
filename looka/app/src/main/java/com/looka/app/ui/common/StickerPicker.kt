@file:OptIn(ExperimentalFoundationApi::class)

package com.looka.app.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.data.Prefs
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.StampAssets
import com.looka.app.util.tr
import kotlinx.coroutines.launch

/**
 * 表情选择器（对齐 Lifebear 实机）：
 * 5 列 × 2 行 = 10 枚/页，横向翻页 + 页点指示；底部包切换 Tab（最近 / 日常 / 敦煌 / 牛来）。
 * 每包上限 50 枚（= 5 页），资产由 scripts/build_stamps.py 保证。
 */
@Composable
fun StickerPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** §72 §4：drag-to-instantiate —— 从素材区直接拖到日历创建实例（素材本身留在库里）。
     *  回调携带根坐标系位置；null 表示本处不支持拖拽创建（如日程编辑页里的选择器）。 */
    onDragCreate: ((assetId: String, rootPos: Offset, phase: Int) -> Unit)? = null,
    /** §2.2 / §11.1：Picker Preview 0.55–0.65×Wd，比日历中的最终尺寸更大，便于识别 */
    previewSize: androidx.compose.ui.unit.Dp = 42.dp
) {
    val ctx = LocalContext.current
    val packs = remember { StampAssets.packs(ctx) }
    val recent = remember(selected) { Prefs.recentStamps(ctx) }
    val scope = rememberCoroutineScope()

    // Tab：最近（有才显示）在前，其余为官方包
    // §117 B：daily 免费；dunhuang/cow 需解锁（鹿角商店）。已放置的贴纸**永远照常渲染**，
    // 锁只挡"新选择" —— 不收回用户已经在用的东西。
    var owned by remember { mutableStateOf(Prefs.ownedPacks(ctx)) }
    // §127：用户在商店详情页隐藏掉的包不进页签（停用，不是删除 —— 所有权仍在）
    val hidden = remember { Prefs.hiddenPacks(ctx) }
    val FREE_PACKS = setOf("daily")
    data class Tab(val id: String, val label: String, val ids: List<String>, val locked: Boolean = false)
    val tabs = remember(packs, recent, owned, hidden) {
        buildList {
            if (recent.isNotEmpty()) add(Tab("recent", tr("最近"), recent))
            packs.filter { it.id !in hidden }.forEach {
                add(Tab(it.id, it.name(), it.stamps.map { s -> s.id },
                    locked = it.id !in FREE_PACKS && it.id !in owned))
            }
        }
    }
    if (tabs.isEmpty()) return
    var tabIdx by rememberSaveable(tabs.size) { mutableIntStateOf(if (recent.isNotEmpty()) 1 else 0) }
    val tab = tabs[tabIdx.coerceIn(0, tabs.lastIndex)]
    val pages = remember(tab) { tab.ids.chunked(10) }
    val pager = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(tabIdx) { pager.scrollToPage(0) }

    Column(modifier.fillMaxWidth()) {
        // §119 T7（《全站统一规划》I2-7）：锁定包不再内嵌购买逻辑 —— 全站购买只保留
        // 装扮商店一套（价格/余额/确认/幂等都在那里）。这里只做引导跳转。
        if (tab.locked) {
            LockedPackHint(tab.label, height = 120.dp)
        } else
        // ── 表情网格（10 枚/页，固定两行高度，翻页不跳动）
        HorizontalPager(
            state = pager,
            // §75 C1a：188→120dp（Lifebear 实测网格 ≈119dp）—— 面板总高对齐 ≈238dp
            modifier = Modifier.fillMaxWidth().height(120.dp)
        ) { page ->
            val items = pages.getOrElse(page) { emptyList() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                for (r in 0 until 2) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (c in 0 until 5) {
                            val i = r * 5 + c
                            val id = items.getOrNull(i)
                            if (id == null) {
                                Spacer(Modifier.width(58.dp))
                            } else {
                                StickerCell(id, id == selected, previewSize, onDragCreate) { onSelect(id) }
                            }
                        }
                    }
                }
            }
        }

        // ── 页点（Lifebear 式：当前页实心大点）
        if (pages.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { i ->
                    val on = pager.currentPage == i
                    val d by animateDpAsState(if (on) 8.dp else 5.dp, tween(180), label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(d)
                            .clip(CircleShape)
                            .background(if (on) Ink else Color(0xFFCFD2CF))
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Hairline()

        // ── 包切换 Tab（选中项下方黑色下划线，同 Lifebear）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { i, tb ->
                val on = i == tabIdx
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .plainClick { tabIdx = i; scope.launch { pager.scrollToPage(0) } }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    if (tb.id == "recent") {
                        Icon(
                            Icons.Outlined.History, tb.label,
                            tint = if (on) Ink else GrayText,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        // 用包内第一枚表情当封面（Lifebear 的做法）
                        val cover = tb.ids.firstOrNull()?.let { StampAssets.bitmap(ctx, it) }
                        if (cover != null) {
                            Image(
                                cover, tb.label,
                                modifier = Modifier.size(26.dp).scale(if (on) 1f else 0.9f)
                            )
                        } else {
                            Text("🦌", fontSize = 18.sp)
                        }
                    }
                    Text(
                        (if (tb.locked) "\uD83D\uDD12 " else "") + tb.label, fontSize = 10.sp,
                        color = if (on) Ink else GrayText,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .width(20.dp).height(2.dp)
                            .background(if (on) Ink else Color.Transparent)
                    )
                }
            }
        }
    }
}

/** 单枚表情格：58dp 触摸区 + 44dp 图，选中放大并加浅底 */
@Composable
private fun StickerCell(
    assetId: String,
    selected: Boolean,
    previewSize: androidx.compose.ui.unit.Dp = 42.dp,
    onDragCreate: ((String, Offset, Int) -> Unit)? = null,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val def = StampAssets.def(ctx, assetId)
    val bmp = StampAssets.bitmap(ctx, assetId)
    val s by animateFloatAsState(
        if (selected) 1.12f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 500f), label = "stickerScale"
    )
    var cellRoot by remember { mutableStateOf(Offset.Zero) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(58.dp)
            .onGloballyPositioned { cellRoot = it.positionInRoot() }
            .let { m ->
                if (onDragCreate == null) m else m.pointerInput(assetId) {
                    // §4：拖出即实例化；素材留在 Picker（AC-001）
                    detectDragGestures(
                        onDragStart = { off ->
                            dragPos = cellRoot + off
                            onDragCreate(assetId, dragPos, 0)
                        },
                        onDrag = { ch, amt ->
                            ch.consume(); dragPos += amt
                            onDragCreate(assetId, dragPos, 1)
                        },
                        onDragCancel = { onDragCreate(assetId, dragPos, 3) },
                        onDragEnd = { onDragCreate(assetId, dragPos, 2) }
                    )
                }
            }
            .plainClick(onClick)
    ) {
        Box(
            Modifier
                .size(previewSize + 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp, def?.name(), modifier = Modifier.size(previewSize).scale(s))
            } else {
                Text("🦌", fontSize = 24.sp)
            }
        }
        Text(
            def?.name().orEmpty(), fontSize = 9.sp,
            color = if (selected) Ink else GrayText,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}


/**
 * §119 T7：锁定包引导 —— 只说明去处，不承载价格与购买（那是装扮商店的职责）。
 * 从贴纸面板到商店的跳转由用户在「更多 → 装扮商店」完成：贴纸面板是创作场景，
 * 不在创作中途弹购买流程（《全站统一规划》1.3：不在输入/拖动中途弹商业提示）。
 */
@Composable
fun LockedPackHint(packName: String, height: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            tr("「{0}」贴纸包待解锁", packName),
            fontSize = 14.sp, color = Ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tr("到「更多 → 装扮商店」用鹿角解锁，Pro 免费领取"),
            fontSize = 12.sp, color = GrayText,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

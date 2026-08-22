@file:OptIn(ExperimentalFoundationApi::class)

package com.looka.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.Occ
import com.looka.app.ui.common.onColor
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.weekdayTint
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Hairline as HairlineColor
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.util.LunarCal
import com.looka.app.util.SysCal
import com.looka.app.vm.LookaViewModel
import java.time.LocalTime
import com.looka.app.util.tr

/*
 * 周/日视图像素冻结（v1.1，详见 docs/UI-SPEC.md）：
 * 小时高 60dp · 左侧时间栏 46dp · 时间字号 9sp 贴线 · 列间 0.6dp 分隔线
 * 今日列底色 = 主题浅底 35% · 日程块圆角 6dp / 最小高 26 分钟 / 白色 1dp 分隔描边
 * 系统日历事件 = 描边样式（区分 Looka 自有日程的实底样式）
 * 当前时间 = 红色 1.5dp 横线 + 左端 6dp 圆点 · 长按创建吸附到半点
 */
private val HOUR_DP = 60.dp
private val GUTTER = 46.dp

/** 时间轴统一块：Looka 日程或系统日历事件 */
private data class TBlock(
    val startMin: Int,
    val endMin: Int,
    val occ: Occ? = null,
    val sys: SysCal.SysEvent? = null
)

/**
 * 周/日时间轴视图（规格 CAL-002/003）：
 * 顶部全天区 + 时间轴；长按全天区→全天日程，长按时间格→预填时间（CAL-CRE-003/004）
 */
@Composable
fun TimelineBody(
    vm: LookaViewModel,
    nav: NavHostController,
    days: List<Long>,
    occs: List<Occ>,
    sysEvents: List<SysCal.SysEvent>,
    holidayMask: Int,
    catColorMap: Map<Long, Color>,
    onOpenSys: (SysCal.SysEvent) -> Unit,
    onLongPressAllDay: (Long) -> Unit = {}
) {
    val today = Fmt.today()
    val todayTint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val showLunar = remember(vm.settingsVersion, com.looka.app.util.I18n.lang) {
        com.looka.app.data.Prefs.showLunarRaw(ctx) ?: com.looka.app.util.I18n.isZh()
    }

    val allDayByDay = remember(occs, days) {
        days.associateWith { d -> occs.filter { it.allDay && d in it.day..it.endDay } }
    }
    val sysAllDayByDay = remember(sysEvents, days) {
        days.associateWith { d -> sysEvents.filter { it.allDay && d in it.day..it.endDayIncl } }
    }
    val blocksByDay = remember(occs, sysEvents, days) {
        days.associateWith { d ->
            val list = ArrayList<TBlock>()
            occs.filter { !it.allDay && it.day == d }.forEach {
                list += TBlock(it.startMin, maxOf(it.endMin, it.startMin + 1), occ = it)
            }
            sysEvents.filter { !it.allDay && it.day == d }.forEach {
                val em = if (it.endDayIncl > it.day) 24 * 60 - 1 else maxOf(it.endMin, it.startMin + 1)
                list += TBlock(it.startMin, em, sys = it)
            }
            list
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 周视图日期头
        if (days.size > 1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Spacer(Modifier.width(GUTTER))
                days.forEach { d ->
                    val dt = Fmt.d(d)
                    val sel = d == vm.selectedDay
                    Column(
                        Modifier.weight(1f).plainClick { vm.selectedDay = d },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            Fmt.week(dt.dayOfWeek.value), fontSize = 10.sp,
                            color = weekdayTint(dt.dayOfWeek.value, holidayMask) ?: GrayText
                        )
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(
                                    when {
                                        sel -> Ink
                                        d == today -> MaterialTheme.colorScheme.primary
                                        else -> Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${dt.dayOfMonth}", fontSize = 12.sp,
                                color = if (sel || d == today) Color.White else Ink,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // 农历行（对位 Lifebear 六曜）——只显示农历日；
                        // 节日单独走全天区红块，避免同一天在两处重复出现
                        if (showLunar) {
                            val lm = LunarCal.of(d)
                            Text(
                                if (lm.lunarDay == 1) lm.monthName else lm.dayName,
                                fontSize = 8.5.sp,
                                color = if (lm.isShuoWang) MaterialTheme.colorScheme.primary else GrayText,
                                maxLines = 1, overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }
            Hairline()
        }

        // 日视图：居中农历标题行
        if (days.size == 1 && showLunar) {
            val lm = LunarCal.of(days[0])
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(lm.full, fontSize = 11.sp, color = GrayText)
            }
            Hairline()
        }

        // 全天区（Looka 实底块 / 系统日历描边块）
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp, max = 84.dp)) {
            Box(Modifier.width(GUTTER).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(tr("全天"), fontSize = 9.sp, color = GrayText)
            }
            days.forEach { d ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            onClick = { vm.selectedDay = d },
                            onLongClick = {
                                vm.prepareCreateDraft(d, allDay = true)
                                nav.navigate("editor")
                            }
                        )
                        .padding(horizontal = 1.dp, vertical = 2.dp)
                        .pointerInput(d) {
                            // CAL-CRE-003（P3 Context）：长按全天区 → 直接建这天的全天日程
                            detectTapGestures(onLongPress = { onLongPressAllDay(d) })
                        }
                ) {
                    // 节假日红块（Lifebear「山の日」式）
                    (if (showLunar) LunarCal.of(d).festival else null)?.let { fest ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 1.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(HolidayRed)
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        ) {
                            Text(
                                fest, fontSize = 9.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    val list = allDayByDay[d].orEmpty()
                    val sysList = sysAllDayByDay[d].orEmpty()
                    list.take(3).forEach { o ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 1.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(catColorMap[o.categoryId] ?: Color(0xFF9AA0A6))
                                .plainClick { nav.navigate("detail/${o.seriesId}/${o.occurrenceDay}") }
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        ) {
                            Text(
                                o.title, fontSize = 9.sp,
                                color = onColor(catColorMap[o.categoryId] ?: Color(0xFF9AA0A6)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    sysList.take(2).forEach { e ->
                        val c = Color(e.color)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 1.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .border(0.8.dp, c, RoundedCornerShape(3.dp))
                                .plainClick { onOpenSys(e) }
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        ) {
                            Text(
                                e.title, fontSize = 9.sp, color = c,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // §68 三（BEAR）：溢出用右下角折角数字，与月视图同款语义
                    val rest = (list.size - 3).coerceAtLeast(0) + (sysList.size - 2).coerceAtLeast(0)
                    if (rest > 0) Text(
                        "+$rest", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 6.dp))
                            .background(Color(0xFF6B7280))
                            .padding(horizontal = 3.dp)
                    )
                }
            }
        }
        Hairline()

        // 时间轴
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val hourPx = with(density) { HOUR_DP.toPx() }
        // W1（§52/§54）：不再写死 7:30 —— 滚到「今天最早日程」或「现在」前 1 小时。
        // 晚上 9 点打开停在早上 7:30、第一个日程在 14:00 还得手动滚，都是这行的旧账。
        LaunchedEffect(days) {
            val nowMin0 = LocalTime.now().let { it.hour * 60 + it.minute }
            val earliest = blocksByDay.values.flatten().minOfOrNull { it.startMin }
            val target = when {
                earliest != null && today in days -> minOf(earliest, nowMin0) - 60
                earliest != null -> earliest - 60
                today in days -> nowMin0 - 60
                else -> 7 * 60 + 30
            }.coerceIn(0, 23 * 60)
            scroll.scrollTo((hourPx * target / 60f).toInt())
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
        ) {
            // 小时标签（数字贴在整点线旁）
            Column(Modifier.width(GUTTER).height(HOUR_DP * 24)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR_DP).fillMaxWidth()) {
                        Text(
                            "%02d:00".format(h), fontSize = 9.sp, color = GrayText,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(end = 6.dp)
                        )
                    }
                }
            }
            days.forEach { d ->
                DayTimelineColumn(
                    modifier = Modifier.weight(1f).height(HOUR_DP * 24),
                    day = d,
                    isToday = d == today,
                    todayTint = todayTint,
                    blocks = blocksByDay[d].orEmpty(),
                    catColorMap = catColorMap,
                    hourPx = hourPx,
                    onOpen = { o -> nav.navigate("detail/${o.seriesId}/${o.occurrenceDay}") },
                    onOpenSys = onOpenSys,
                    onLongPressAt = { min ->
                        vm.prepareCreateDraft(d, startMin = min)
                        nav.navigate("editor")
                    }
                )
            }
        }
    }
}

@Composable
private fun DayTimelineColumn(
    modifier: Modifier,
    day: Long,
    isToday: Boolean,
    todayTint: Color,
    blocks: List<TBlock>,
    catColorMap: Map<Long, Color>,
    hourPx: Float,
    onOpen: (Occ) -> Unit,
    onOpenSys: (SysCal.SysEvent) -> Unit,
    onLongPressAt: (Int) -> Unit
) {
    val lineColor = HairlineColor
    BoxWithConstraints(
        modifier
            .background(if (isToday) todayTint else Color.Transparent)
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                for (h in 0..23) {
                    val y = h * hourPx
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    // 半点虚线（像素冻结 v1.2）
                    val hy = y + hourPx / 2f
                    drawLine(lineColor, Offset(0f, hy), Offset(size.width, hy), strokeWidth = 1f, pathEffect = dash)
                }
                drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.6f)
            }
            .pointerInput(day) {
                detectTapGestures(
                    onLongPress = { off ->
                        // 长按时间格：吸附到半点预填（CAL-CRE-004）
                        val min = ((off.y / hourPx) * 60).toInt().coerceIn(0, 23 * 60 + 30)
                        onLongPressAt(min / 30 * 30)
                    }
                )
            }
    ) {
        val colW = maxWidth
        val lanes = remember(blocks) { layoutLanes(blocks) }
        lanes.forEach { (b, lane, count, span) ->
            val top = HOUR_DP * (b.startMin / 60f)
            val heightDp = HOUR_DP * ((b.endMin - b.startMin).coerceAtLeast(26) / 60f)
            val w = colW / count * span   // K1：孤立块向右占满，不再被长日程连坐
            if (b.occ != null) {
                val o = b.occ
                Box(
                    Modifier
                        .offset(x = colW / count * lane, y = top)
                        .width(w)
                        .height(heightDp)
                        .padding(horizontal = 1.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(blockColor(catColorMap[o.categoryId]).copy(alpha = 0.94f))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .plainClick { onOpen(o) }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                ) {
                    Column {
                        val fg = onColor(blockColor(catColorMap[o.categoryId]))
                        Text(
                            o.title, fontSize = 10.sp, color = fg, lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            // §68 三：E3 竖排撤回（用户实测"内容拉长"就是它）。
                            // K1 向右扩展后窄块已少；仍窄时宁可省略，不做竖排。
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (w >= 60.dp) Text(
                            "${Fmt.hm(o.startMin)}-${Fmt.hm(o.endMin)}",
                            fontSize = 8.sp, color = fg.copy(alpha = 0.85f), maxLines = 1
                        )
                    }
                }
            } else if (b.sys != null) {
                val e = b.sys
                val c = Color(e.color)
                Box(
                    Modifier
                        .offset(x = colW / count * lane, y = top)
                        .width(w)
                        .height(heightDp)
                        .padding(horizontal = 1.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.copy(alpha = 0.10f))
                        .border(1.dp, c, RoundedCornerShape(6.dp))
                        .plainClick { onOpenSys(e) }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                ) {
                    Column {
                        Text(
                            e.title, fontSize = 10.sp, color = c, lineHeight = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(Fmt.hm(e.startMin), fontSize = 8.sp, color = c.copy(alpha = 0.8f))
                    }
                }
            }
        }
        // 当前时间：红线 + 左端圆点
        if (isToday) {
            val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
            Box(
                Modifier
                    .offset(y = HOUR_DP * (nowMin / 60f))
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(HolidayRed)
            )
            Box(
                Modifier
                    .offset(x = (-2).dp, y = HOUR_DP * (nowMin / 60f) - 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(HolidayRed)
            )
        }
    }
}

/** 重叠块分泳道：返回（块, 泳道号, 泳道总数） */
/**
 * K1（§54 二 问题2）：泳道布局 v2。
 * 旧版让整簇平分列宽 —— 一根 9:00→14:30 的长日程会把 5 个半小时里的所有日程
 * "串"成一簇，每块被压成 1/N 宽（周视图上只剩 ~17dp，一个字都放不下）。
 * 新版（Google Calendar 同款语义）：lane 分配不变，但每块**向右扩展**，
 * 直到撞上一个与它时间真正重叠的块所在的 lane —— 孤立的块占满剩余宽度。
 * 返回 (块, lane, laneCount, span)。
 */
private data class LaneBox(val b: TBlock, val lane: Int, val count: Int, val span: Int)

private fun layoutLanes(items: List<TBlock>): List<LaneBox> {
    val sorted = items.sortedWith(compareBy({ it.startMin }, { it.endMin }))
    val out = ArrayList<LaneBox>()
    var cluster = ArrayList<Pair<TBlock, Int>>()
    var lanes = ArrayList<Int>()
    var clusterEnd = -1

    fun overlaps(a: TBlock, c: TBlock) = a.startMin < c.endMin && a.endMin > c.startMin

    fun flush() {
        val count = lanes.size.coerceAtLeast(1)
        cluster.forEach { (b, lane) ->
            var span = 1
            // 向右伸展：下一条 lane 里没有与我重叠的块，就把它也占了
            for (nl in lane + 1 until count) {
                val blocked = cluster.any { (other, ol) -> ol == nl && overlaps(b, other) }
                if (blocked) break
                span++
            }
            out += LaneBox(b, lane, count, span)
        }
        cluster = ArrayList(); lanes = ArrayList(); clusterEnd = -1
    }

    for (b in sorted) {
        if (cluster.isNotEmpty() && b.startMin >= clusterEnd) flush()
        var lane = lanes.indexOfFirst { it <= b.startMin }
        if (lane < 0) {
            lanes.add(b.endMin); lane = lanes.size - 1
        } else {
            lanes[lane] = b.endMin
        }
        cluster.add(b to lane)
        clusterEnd = maxOf(clusterEnd, b.endMin)
    }
    flush()
    return out
}


/**
 * §68 三（BEAR 对比图实锤，撤回 E4 主题色变体）：未分类 = 中性灰底白字。
 * 用户没标颜色就不该有颜色 —— 颜色只表达"分类"这一个语义。
 */
private val UNCAT_GRAY = Color(0xFF8A9095)
private fun blockColor(catColor: Color?, @Suppress("UNUSED_PARAMETER") unused: Color = UNCAT_GRAY): Color =
    if (catColor == null || catColor == Color(0xFF9AA0A6)) UNCAT_GRAY else catColor

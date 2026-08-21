package com.looka.app.data

import java.time.LocalDate
import java.time.YearMonth
import com.looka.app.util.tr

/** 展开后的一次日程发生（系列 + 例外合并后的展示对象） */
data class Occ(
    val seriesId: Long,
    val occurrenceDay: Long,   // 按规则原本的发生日（例外定位键）
    val day: Long,             // 实际显示的开始日（可能被“仅本次”移动）
    val endDay: Long,
    val title: String,
    val allDay: Boolean,
    val startMin: Int,
    val endMin: Int,
    val categoryId: Long,
    val location: String,
    val memo: String,
    val recurring: Boolean
)

/**
 * 重复展开引擎（规格 §4 / §11.2）：
 * 系列 + 规则 + 例外 → 指定日期窗口内的发生列表。
 * 支持：日/周/月/年、间隔、周多选、月末补齐、第 N 个星期几、结束日、仅本次例外。
 */
object RecurrenceEngine {

    fun expand(
        seriesList: List<EventSeries>,
        exceptions: List<EventException>,
        from: Long,
        to: Long
    ): List<Occ> {
        val exBySeries = exceptions.groupBy { it.seriesId }
        val out = ArrayList<Occ>()

        for (s in seriesList) {
            val exMap = exBySeries[s.id].orEmpty().associateBy { it.occurrenceDay }
            val dur = (s.endDay - s.startDay).coerceAtLeast(0)

            if (s.freq == FREQ_NONE) {
                addOcc(out, s, s.startDay, exMap, from, to)
                continue
            }

            val hardTo = minOf(to, if (s.untilDay >= 0) s.untilDay else Long.MAX_VALUE / 4)

            when (s.freq) {
                FREQ_DAILY -> {
                    val step = s.interval.coerceAtLeast(1).toLong()
                    var day = s.startDay
                    // 快进到窗口附近，避免远古系列全量迭代
                    if (from - dur > day) {
                        val k = (from - dur - day) / step
                        day += k * step
                    }
                    while (day <= hardTo) {
                        addOcc(out, s, day, exMap, from, to)
                        day += step
                    }
                }

                FREQ_WEEKLY -> {
                    val itv = s.interval.coerceAtLeast(1)
                    val mask = if (s.weekdays == 0)
                        1 shl (LocalDate.ofEpochDay(s.startDay).dayOfWeek.value - 1)
                    else s.weekdays
                    // 以周一为界的周序号（epochDay 0 = 1970-01-01 周四）
                    val baseWeek = Math.floorDiv(s.startDay + 3, 7)
                    var day = maxOf(s.startDay, from - dur - 7)
                    while (day <= hardTo) {
                        if (day >= s.startDay) {
                            val w = Math.floorDiv(day + 3, 7)
                            val dow = LocalDate.ofEpochDay(day).dayOfWeek.value
                            if ((w - baseWeek) % itv == 0L && (mask shr (dow - 1)) and 1 == 1) {
                                addOcc(out, s, day, exMap, from, to)
                            }
                        }
                        day += 1
                    }
                }

                FREQ_MONTHLY -> {
                    val itv = s.interval.coerceAtLeast(1)
                    val base = LocalDate.ofEpochDay(s.startDay)
                    var i = 0
                    while (i <= 1200) {
                        val ym = YearMonth.from(base).plusMonths(i.toLong() * itv)
                        val day = if (s.monthlyByWeekday) nthWeekdayInMonth(ym, base)
                        else ym.atDay(minOf(base.dayOfMonth, ym.lengthOfMonth())).toEpochDay()
                        i++
                        if (day > hardTo) break
                        if (day < s.startDay) continue
                        addOcc(out, s, day, exMap, from, to)
                    }
                }

                FREQ_YEARLY -> {
                    val itv = s.interval.coerceAtLeast(1)
                    val base = LocalDate.ofEpochDay(s.startDay)
                    var y = 0
                    while (y <= 300) {
                        val ym = YearMonth.of(base.year + y * itv, base.monthValue)
                        val day = ym.atDay(minOf(base.dayOfMonth, ym.lengthOfMonth())).toEpochDay()
                        y++
                        if (day > hardTo) break
                        if (day < s.startDay) continue
                        addOcc(out, s, day, exMap, from, to)
                    }
                }
            }
        }

        // 兜底：被“仅本次”移动进窗口、但原发生日在窗口外的例外
        for (ex in exceptions) {
            if (ex.cancelled || ex.newDay < 0) continue
            if (ex.newDay in from..to && out.none { it.seriesId == ex.seriesId && it.occurrenceDay == ex.occurrenceDay }) {
                val s = seriesList.find { it.id == ex.seriesId } ?: continue
                mergeOcc(s, ex.occurrenceDay, ex)?.let { out += it }
            }
        }
        return out
    }

    /** 该月第 N 个星期几（不足 N 个时取最后一个），返回 epochDay */
    private fun nthWeekdayInMonth(ym: YearMonth, base: LocalDate): Long {
        val nth = (base.dayOfMonth - 1) / 7 + 1
        val first = ym.atDay(1)
        val offset = (base.dayOfWeek.value - first.dayOfWeek.value + 7) % 7
        var dom = 1 + offset + (nth - 1) * 7
        while (dom > ym.lengthOfMonth()) dom -= 7
        return ym.atDay(dom).toEpochDay()
    }

    private fun addOcc(
        out: MutableList<Occ>, s: EventSeries, day: Long,
        exMap: Map<Long, EventException>, from: Long, to: Long
    ) {
        val occ = mergeOcc(s, day, exMap[day]) ?: return
        if (occ.endDay >= from && occ.day <= to) out += occ
    }

    /** 合并系列与例外，得到单次发生；被取消返回 null */
    fun mergeOcc(s: EventSeries, occDay: Long, ex: EventException?): Occ? {
        if (ex?.cancelled == true) return null
        val dur = (s.endDay - s.startDay).coerceAtLeast(0)
        val day = if (ex != null && ex.newDay >= 0) ex.newDay else occDay
        return Occ(
            seriesId = s.id,
            occurrenceDay = occDay,
            day = day,
            endDay = day + dur,
            title = ex?.title ?: s.title,
            allDay = ex?.allDay ?: s.allDay,
            startMin = ex?.startMin ?: s.startMin,
            endMin = ex?.endMin ?: s.endMin,
            categoryId = ex?.categoryId ?: s.categoryId,
            location = ex?.location ?: s.location,
            memo = ex?.memo ?: s.memo,
            recurring = s.freq != FREQ_NONE
        )
    }

    /** 重复规则中文摘要，如“每周 · 周一 周三 · 至9月30日” */
    fun summary(
        freq: Int, interval: Int, weekdays: Int,
        monthlyByWeekday: Boolean, untilDay: Long, baseDay: Long
    ): String {
        if (freq == FREQ_NONE) return tr("无")
        val base = LocalDate.ofEpochDay(baseDay)
        val itv = interval.coerceAtLeast(1)
        val names = listOf(tr("周一"), tr("周二"), tr("周三"), tr("周四"), tr("周五"), tr("周六"), tr("周日"))
        val head = when (freq) {
            FREQ_DAILY -> if (itv == 1) tr("每天") else tr("每{0}天", itv)
            FREQ_WEEKLY -> {
                val sel = (0..6).filter { (weekdays shr it) and 1 == 1 }.map { names[it] }
                val week = if (itv == 1) tr("每周") else tr("每{0}周", itv)
                if (sel.isEmpty()) week else "$week · ${sel.joinToString(" ")}"
            }
            FREQ_MONTHLY -> {
                val m = if (itv == 1) tr("每月") else tr("每{0}个月", itv)
                if (monthlyByWeekday) {
                    val nth = (base.dayOfMonth - 1) / 7 + 1
                    tr("{0} · 第{1}个{2}", m, nth, names[base.dayOfWeek.value - 1])
                } else tr("{0} · {1}日", m, base.dayOfMonth)
            }
            FREQ_YEARLY -> {
                val y = if (itv == 1) tr("每年") else tr("每{0}年", itv)
                tr("{0} · {1}月{2}日", y, base.monthValue, base.dayOfMonth)
            }
            else -> tr("无")
        }
        return if (untilDay >= 0) {
            val u = LocalDate.ofEpochDay(untilDay)
            tr("{0} · 至{1}月{2}日", head, u.monthValue, u.dayOfMonth)
        } else head
    }
}

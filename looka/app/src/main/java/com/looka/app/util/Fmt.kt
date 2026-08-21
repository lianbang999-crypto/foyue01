package com.looka.app.util

import com.looka.app.data.Reminder
import java.time.LocalDate
import java.time.YearMonth
import com.looka.app.util.tr

/** 日期/时间格式化（跟随 I18n.lang / use12h，规格 I 批区域规则） */
object Fmt {

    val WEEK_CN = arrayOf(tr("一"), tr("二"), tr("三"), tr("四"), tr("五"), tr("六"), tr("日"))   // ISO 1..7
    private val WEEK_EN = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTH_EN = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun today(): Long = LocalDate.now().toEpochDay()

    fun d(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    /** 星期短名：zh「三」 / en「Wed」 */
    fun week(dowIso: Int): String =
        if (I18n.isZh()) WEEK_CN[dowIso - 1] else WEEK_EN[dowIso - 1]

    /** 星期全称：zh「周三」 / en「Wed」 */
    fun weekFull(dowIso: Int): String =
        if (I18n.isZh()) tr("周") + WEEK_CN[dowIso - 1] else WEEK_EN[dowIso - 1]

    fun monthTitle(ym: YearMonth): String =
        if (I18n.isZh()) tr("{0}年{1}月", ym.year, ym.monthValue)
        else "${MONTH_EN[ym.monthValue - 1]} ${ym.year}"

    /** 月份短标（月视图顶栏大字）：zh「8月」 / en「Aug」 */
    fun monthShort(ym: YearMonth): String =
        if (I18n.isZh()) tr("{0}月", ym.monthValue) else MONTH_EN[ym.monthValue - 1]

    /** zh「8月20日(三)」 / en「Aug 20 (Wed)」 */
    fun dateCn(day: Long): String {
        val dt = d(day)
        return if (I18n.isZh()) tr("{0}月{1}日({2})", dt.monthValue, dt.dayOfMonth, WEEK_CN[dt.dayOfWeek.value - 1])
        else "${MONTH_EN[dt.monthValue - 1]} ${dt.dayOfMonth} (${WEEK_EN[dt.dayOfWeek.value - 1]})"
    }

    /** zh「2026年8月20日(周三)」 / en「Aug 20, 2026 (Wed)」 */
    fun dateFull(day: Long): String {
        val dt = d(day)
        return if (I18n.isZh())
            tr("{0}年{1}月{2}日(周{3})", dt.year, dt.monthValue, dt.dayOfMonth, WEEK_CN[dt.dayOfWeek.value - 1])
        else "${MONTH_EN[dt.monthValue - 1]} ${dt.dayOfMonth}, ${dt.year} (${WEEK_EN[dt.dayOfWeek.value - 1]})"
    }

    /** 时间：24h「08:05」 / 12h「8:05 AM」（跟随日历设置） */
    fun hm(min: Int): String {
        val h = min / 60
        val m = min % 60
        if (!I18n.use12h) return "%02d:%02d".format(h, m)
        val ap = if (h < 12) "AM" else "PM"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "%d:%02d %s".format(h12, m, ap)
    }

    /** yyyy-MM-dd，给 AI / 导出用（不随语言变） */
    fun iso(day: Long): String = d(day).toString()

    /** 提醒摘要 */
    fun reminderText(allDay: Boolean, r: Reminder): String = if (allDay) {
        val d = when (r.daysBefore) {
            0 -> tr("当天")
            1 -> tr("1天前")
            else -> tr("{0}天前", r.daysBefore)
        }
        "$d ${hm(r.timeOfDayMin)}"
    } else {
        when (r.minutesBefore) {
            0 -> tr("准时")
            in 1..59 -> tr("{0}分钟前", r.minutesBefore)
            in 60..1439 -> tr("{0}小时前", r.minutesBefore / 60)
            else -> tr("{0}天前", r.minutesBefore / 1440)
        }
    }
}

package com.looka.app.util

import android.icu.util.ChineseCalendar
import android.icu.util.ULocale
import java.time.LocalDate
import android.icu.util.Calendar as IcuCalendar
import android.icu.util.TimeZone as IcuTimeZone
import com.looka.app.util.tr

/**
 * 农历与节日（中国版日历的及格线，对位 Lifebear 的六曜）。
 * 历算使用 Android 内置 ICU 的 ChineseCalendar（API 24+），无需自带数据表。
 */
object LunarCal {

    data class Info(
        val monthName: String,     // 正月…腊月（闰月带“闰”）
        val dayName: String,       // 初一…三十
        val lunarMonth: Int,       // 1..12
        val lunarDay: Int,         // 1..30
        val isLeap: Boolean,
        val festival: String?,     // 农历/公历节日（红色显示）
        val isShuoWang: Boolean    // 初一 / 十五（主题色显示）
    ) {
        /** 月格右上角短文本：节日 > 初一显示月名 > 日名 */
        val cellText: String get() = festival ?: if (lunarDay == 1) monthName else dayName

        /** 完整农历，如「七月初八」 */
        val full: String get() = monthName + dayName
    }

    private val DAYS = arrayOf(
        tr("初一"), tr("初二"), tr("初三"), tr("初四"), tr("初五"), tr("初六"), tr("初七"), tr("初八"), tr("初九"), tr("初十"),
        tr("十一"), tr("十二"), tr("十三"), tr("十四"), tr("十五"), tr("十六"), tr("十七"), tr("十八"), tr("十九"), tr("二十"),
        tr("廿一"), tr("廿二"), tr("廿三"), tr("廿四"), tr("廿五"), tr("廿六"), tr("廿七"), tr("廿八"), tr("廿九"), tr("三十")
    )
    private val MONTHS = arrayOf(
        tr("正月"), tr("二月"), tr("三月"), tr("四月"), tr("五月"), tr("六月"),
        tr("七月"), tr("八月"), tr("九月"), tr("十月"), tr("冬月"), tr("腊月")
    )

    private val LUNAR_FESTIVALS = mapOf(
        (1 to 1) to tr("春节"), (1 to 15) to tr("元宵"), (2 to 2) to tr("龙抬头"),
        (5 to 5) to tr("端午"), (7 to 7) to tr("七夕"), (8 to 15) to tr("中秋"),
        (9 to 9) to tr("重阳"), (12 to 8) to tr("腊八"), (12 to 23) to tr("小年")
    )
    private val SOLAR_FESTIVALS = mapOf(
        (1 to 1) to tr("元旦"), (3 to 8) to tr("妇女节"), (3 to 12) to tr("植树节"),
        (5 to 1) to tr("劳动节"), (5 to 4) to tr("青年节"), (6 to 1) to tr("儿童节"),
        (7 to 1) to tr("建党节"), (8 to 1) to tr("建军节"), (9 to 10) to tr("教师节"),
        (10 to 1) to tr("国庆")
    )

    private val cache = HashMap<Long, Info>()

    fun of(day: Long): Info {
        cache[day]?.let { return it }
        if (cache.size > 4000) cache.clear()
        return compute(day).also { cache[day] = it }
    }

    /** 东八区正午取样，避免时区边界抖动 */
    private fun lunarOf(day: Long): Triple<Int, Int, Boolean> {
        val cc = ChineseCalendar(IcuTimeZone.getTimeZone("Asia/Shanghai"), ULocale.CHINA)
        cc.timeInMillis = day * 86400000L + 4 * 3600_000L
        return Triple(
            cc.get(IcuCalendar.MONTH) + 1,
            cc.get(IcuCalendar.DAY_OF_MONTH),
            cc.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        )
    }

    private fun compute(day: Long): Info {
        val (m, d, leap) = lunarOf(day)
        val g = LocalDate.ofEpochDay(day)

        var festival: String? = null
        if (!leap) {
            festival = LUNAR_FESTIVALS[m to d]
            // 除夕：明天是正月初一
            if (festival == null && m == 12 && d >= 29) {
                val (nm, nd, nl) = lunarOf(day + 1)
                if (nm == 1 && nd == 1 && !nl) festival = tr("除夕")
            }
        }
        if (festival == null) festival = SOLAR_FESTIVALS[g.monthValue to g.dayOfMonth]
        if (festival == null && g.monthValue == 4 && g.dayOfMonth == qingmingDay(g.year)) {
            festival = tr("清明")
        }

        return Info(
            monthName = (if (leap) tr("闰") else "") + MONTHS[(m - 1).coerceIn(0, 11)],
            dayName = DAYS[(d - 1).coerceIn(0, 29)],
            lunarMonth = m, lunarDay = d, isLeap = leap,
            festival = festival,
            isShuoWang = !leap && (d == 1 || d == 15)
        )
    }

    /** 清明日期（21 世纪通式近似，4 月 D 日） */
    fun qingmingDay(year: Int): Int {
        val y = year % 100
        return (y * 0.2422 + 4.81).toInt() - y / 4
    }
}

package com.looka.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.looka.app.util.tr

/** 系统日历只读聚合（规格 CAL-080：显示与同步分离，Looka 只读展示） */
object SysCal {

    data class SysCalendar(val id: Long, val name: String, val color: Int)

    data class SysEvent(
        val id: Long,
        val calId: Long,
        val calName: String,
        val color: Int,
        val title: String,
        val allDay: Boolean,
        val day: Long,          // epochDay
        val endDayIncl: Long,
        val startMin: Int,
        val endMin: Int
    )

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 设备上的日历账户列表 */
    suspend fun calendars(c: Context): List<SysCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext emptyList()
        val out = ArrayList<SysCalendar>()
        try {
            c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out += SysCalendar(
                        id = cur.getLong(0),
                        name = cur.getString(1) ?: tr("日历"),
                        color = if (cur.isNull(2)) 0xFF8A8F8E.toInt() else cur.getInt(2)
                    )
                }
            }
        } catch (_: Exception) { }
        out
    }

    /** 指定日期范围内的系统日历事件实例 */
    suspend fun instances(
        c: Context,
        fromDay: Long,
        toDay: Long,
        hiddenCalIds: Set<String>
    ): List<SysEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val beginMs = LocalDate.ofEpochDay(fromDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.ofEpochDay(toDay + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<SysEvent>()
        try {
            CalendarContract.Instances.query(
                c.contentResolver,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                ),
                beginMs, endMs
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val calId = cur.getLong(1)
                    if (calId.toString() in hiddenCalIds) continue
                    val begin = cur.getLong(3)
                    val end = cur.getLong(4)
                    val allDay = cur.getInt(5) == 1
                    if (allDay) {
                        // 全天事件按 UTC 零点存储
                        val d = Math.floorDiv(begin, 86400000L)
                        val e = Math.floorDiv(end, 86400000L) - 1
                        out += SysEvent(
                            id = cur.getLong(0), calId = calId,
                            calName = cur.getString(7) ?: "",
                            color = if (cur.isNull(6)) 0xFF8A8F8E.toInt() else cur.getInt(6),
                            title = cur.getString(2)?.ifBlank { tr("(无标题)") } ?: tr("(无标题)"),
                            allDay = true, day = d, endDayIncl = maxOf(e, d),
                            startMin = 0, endMin = 0
                        )
                    } else {
                        val sdt = Instant.ofEpochMilli(begin).atZone(zone)
                        val edt = Instant.ofEpochMilli(if (end > begin) end else begin).atZone(zone)
                        out += SysEvent(
                            id = cur.getLong(0), calId = calId,
                            calName = cur.getString(7) ?: "",
                            color = if (cur.isNull(6)) 0xFF8A8F8E.toInt() else cur.getInt(6),
                            title = cur.getString(2)?.ifBlank { tr("(无标题)") } ?: tr("(无标题)"),
                            allDay = false,
                            day = sdt.toLocalDate().toEpochDay(),
                            endDayIncl = edt.toLocalDate().toEpochDay(),
                            startMin = sdt.hour * 60 + sdt.minute,
                            endMin = edt.hour * 60 + edt.minute
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) { }
        out.sortedWith(compareBy({ it.day }, { if (it.allDay) -1 else it.startMin }))
    }
}

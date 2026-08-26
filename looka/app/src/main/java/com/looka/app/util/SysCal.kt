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

/** 系统日历聚合（规格 CAL-080 原为只读展示；§114 P16 用户拍板升级为**可改可删**：
 * 系统日历的日程进了 Looka，就该和 Looka 自己的日程一样能修改和删除，写回系统）。
 * 边界：**带重复规则（RRULE）的系统事件首版不支持在 Looka 内改删** ——
 * Events 表一行就是整个系列，改/删会波及全部重复实例，影响范围选择先不做，
 * 详情里明说"请在系统日历中管理"，比装作能改然后把人家整个系列删了诚实。 */
object SysCal {

    data class SysCalendar(val id: Long, val name: String, val color: Int)

    /** 编辑用明细（Instances 查询没投影这些字段，编辑前单查一次 Events 表） */
    data class SysEventDetail(
        val id: Long,
        val title: String,
        val allDay: Boolean,
        val location: String,
        val description: String,
        val recurring: Boolean   // RRULE/RDATE 非空 —— 首版不支持改删
    )

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

    fun hasWritePermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 编辑/删除前查一次明细（拿 RRULE、地点、备注 —— Instances 查询里没有这些） */
    suspend fun eventDetail(c: Context, id: Long): SysEventDetail? = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext null
        try {
            c.contentResolver.query(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.RRULE,
                    CalendarContract.Events.RDATE
                ),
                null, null, null
            )?.use { cur ->
                if (!cur.moveToFirst()) return@withContext null
                SysEventDetail(
                    id = id,
                    title = cur.getString(0) ?: "",
                    allDay = cur.getInt(1) == 1,
                    location = cur.getString(2) ?: "",
                    description = cur.getString(3) ?: "",
                    recurring = !cur.getString(4).isNullOrBlank() || !cur.getString(5).isNullOrBlank()
                )
            }
        } catch (_: Exception) { null }
    }

    /** 写回系统日历（非重复事件）。全天事件按 CalendarContract 约定写 UTC 零点毫秒。 */
    suspend fun updateEvent(
        c: Context, id: Long, title: String, allDay: Boolean,
        startDay: Long, startMin: Int, endDay: Long, endMin: Int,
        location: String, description: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission(c)) return@withContext false
        try {
            val zone = ZoneId.systemDefault()
            val v = android.content.ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                if (allDay) {
                    // 全天：UTC 零点，DTEND 为次日（区间开）
                    put(CalendarContract.Events.DTSTART, startDay * 86400000L)
                    put(CalendarContract.Events.DTEND, (endDay + 1) * 86400000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    val s = LocalDate.ofEpochDay(startDay).atStartOfDay(zone)
                        .plusMinutes(startMin.toLong()).toInstant().toEpochMilli()
                    val e = LocalDate.ofEpochDay(endDay).atStartOfDay(zone)
                        .plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
                    put(CalendarContract.Events.DTSTART, s)
                    put(CalendarContract.Events.DTEND, maxOf(e, s))
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                }
            }
            c.contentResolver.update(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                v, null, null
            ) > 0
        } catch (_: Exception) { false }
    }

    /** 删除系统日历事件（非重复；重复事件在 UI 层已拦） */
    suspend fun deleteEvent(c: Context, id: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission(c)) return@withContext false
        try {
            c.contentResolver.delete(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                null, null
            ) > 0
        } catch (_: Exception) { false }
    }

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

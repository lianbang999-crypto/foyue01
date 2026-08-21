package com.looka.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.looka.app.LookaApp
import com.looka.app.data.Occ
import com.looka.app.data.Prefs
import com.looka.app.data.RecurrenceEngine
import com.looka.app.data.Reminder
import com.looka.app.util.Fmt
import com.looka.app.util.tr
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒调度 v2（三批 B2–B6）：
 * - 日程提醒 + 到期任务提醒，展开未来 14 天 → 精确闹钟（setExactAndAllowWhileIdle）
 * - 每日 00:05 自闹钟滚动续期（去掉一次性排完的天花板）
 * - 无精确闹钟权限时自动降级 setAndAllowWhileIdle（自检页引导开启）
 * - 声音/震动 = 系统默认提示音（IMPORTANCE_HIGH 渠道自带）
 */
object NotifyScheduler {

    const val CHANNEL = "looka_events"
    private const val ALARM_SP = "looka_alarms"
    private const val CODE_DAILY = 900001

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("日程提醒"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("Looka 日程与任务提醒")
            }
        )
    }

    fun canExact(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java) ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    /** 从数据库重建全部提醒（应用启动 / 数据变更 / 开机 / 每日续期时调用） */
    suspend fun rescheduleFromDb(app: LookaApp) {
        try {
            val dao = app.db.eventDao()
            val today = Fmt.today()
            val occs = RecurrenceEngine.expand(dao.seriesList(), dao.exceptionsList(), today, today + 14)
            val reminders = dao.remindersList().groupBy { it.seriesId }
            val tasks = if (Prefs.taskRemOn(app)) {
                app.db.taskDao().openDueList().filter { it.dueDay in today..today + 14 }
            } else emptyList()
            schedule(app, occs.sortedBy { it.day }, reminders, tasks)
        } catch (_: Exception) {
            // 提醒调度失败不应影响主流程
        }
    }

    private fun schedule(
        c: Context,
        occs: List<Occ>,
        reminders: Map<Long, List<Reminder>>,
        tasks: List<com.looka.app.data.Task>
    ) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val sp = c.getSharedPreferences(ALARM_SP, Context.MODE_PRIVATE)

        // 先取消上一批
        sp.getStringSet("codes", emptySet())!!.forEach { code ->
            code.toIntOrNull()?.let { am.cancel(pending(c, it, "", "", -1L)) }
        }

        val exact = canExact(c)
        var failed = 0
        var nextFire = Long.MAX_VALUE
        // 逐条容错（2026-08-21 修复）：原来一条 SecurityException 会中断整个 for 循环，
        // 后面所有提醒一条都排不上，而外层 catch 又把异常吞掉 —— 用户看到的就是「提醒不响」。
        fun setAlarm(t: Long, pi: PendingIntent): Boolean {
            return try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                if (t < nextFire) nextFire = t
                true
            } catch (_: SecurityException) {
                // 精确闹钟权限被系统回收：降级为不精确，别让这一条毁掉整批
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                    if (t < nextFire) nextFire = t
                    true
                } catch (_: Exception) { failed++; false }
            } catch (_: Exception) { failed++; false }
        }

        val codes = HashSet<String>()
        val now = System.currentTimeMillis()
        var count = 0

        // 日程提醒
        outer@ for (o in occs) {
            for (r in reminders[o.seriesId].orEmpty()) {
                if (!r.enabled) continue
                val t = fireTime(o, r)
                if (t <= now) continue
                val code = "${o.seriesId}-${o.day}-${r.id}".hashCode()
                val text = if (o.allDay) "${tr("全天")} · ${Fmt.dateCn(o.day)}"
                else "${Fmt.dateCn(o.day)} ${Fmt.hm(o.startMin)} - ${Fmt.hm(o.endMin)}"
                setAlarm(t, pending(c, code, o.title, text, o.day))
                codes += code.toString()
                if (++count >= 200) break@outer
            }
        }

        // 到期任务提醒（当日设定时刻，默认 9:00）
        val taskMin = Prefs.taskRemMin(c)
        for (t in tasks) {
            if (count >= 200) break
            val fire = dayStartMillis(t.dueDay) + taskMin * 60_000L
            if (fire <= now) continue
            val code = "task-${t.uid}-${t.dueDay}".hashCode()
            setAlarm(fire, pending(c, code, tr("待办到期：{0}", t.title), Fmt.dateCn(t.dueDay), t.dueDay))
            codes += code.toString()
            count++
        }

        // 明日 00:05 自续期（滚动窗口，B5）
        val nextDaily = dayStartMillis(Fmt.today() + 1) + 5 * 60_000L
        val dailyPi = PendingIntent.getBroadcast(
            c, CODE_DAILY, Intent(c, DailyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDaily, dailyPi)

        sp.edit().putStringSet("codes", codes)
            .putInt("stat_ok", codes.size)
            .putInt("stat_failed", failed)
            .putLong("stat_next", if (nextFire == Long.MAX_VALUE) 0L else nextFire)
            .putLong("stat_run", now)
            .apply()
    }

    /** 调度实况（供自检页显示「到底排上了没有」，2026-08-21） */
    data class Stats(val ok: Int, val failed: Int, val nextFire: Long, val lastRun: Long)
    fun stats(c: Context): Stats {
        val sp = c.getSharedPreferences(ALARM_SP, Context.MODE_PRIVATE)
        return Stats(sp.getInt("stat_ok", 0), sp.getInt("stat_failed", 0),
            sp.getLong("stat_next", 0L), sp.getLong("stat_run", 0L))
    }

    /**
     * 立即测试：10 秒后发一条真实通知。
     * 收到 = 通知链路通（问题在调度/数据）；收不到 = 系统在挡（权限/ROM 后台限制）。
     */
    fun fireTestIn10s(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pending(c, 900002, tr("测试提醒 🦌"), tr("能看到我，说明通知链路是通的"), Fmt.today())
        return try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, pi)
            else am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, pi)
            true
        } catch (_: Exception) { false }
    }

    private fun fireTime(o: Occ, r: Reminder): Long {
        return if (o.allDay) {
            dayStartMillis(o.day - r.daysBefore) + r.timeOfDayMin * 60_000L
        } else {
            dayStartMillis(o.day) + (o.startMin - r.minutesBefore) * 60_000L
        }
    }

    private fun dayStartMillis(day: Long): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(c: Context, code: Int, title: String, text: String, day: Long): PendingIntent {
        val i = Intent(c, NotifyReceiver::class.java)
            .putExtra("title", title)
            .putExtra("text", text)
            .putExtra("day", day)
        return PendingIntent.getBroadcast(
            c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

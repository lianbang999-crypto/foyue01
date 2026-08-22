package com.looka.app.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.looka.app.R
import com.looka.app.util.tr

/**
 * A2 真闹钟（§48）：提醒是「响一声可划走」，闹钟是「持续响直到手动停」。
 * 之前只有通知 —— 用户实测「时间到只有一声咚」（§47 四确诊），根因是从来没做过闹钟。
 *
 * 链路：NotifyReceiver 收到 alarm 标记的精确闹钟广播
 *   → startForegroundService(AlarmRingService)（精确闹钟触发窗口内允许后台起 FGS）
 *   → 前台通知（looka_alarm 渠道 + CATEGORY_ALARM + 不可划走 + 全屏意图）
 *   → MediaPlayer 循环系统闹铃 + 振动，直到用户按「停止」；「再响5分钟」= 停 + 重排。
 */
class AlarmRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRing(); stopSelf(); return START_NOT_STICKY }
            ACTION_SNOOZE -> { snooze(intent); stopRing(); stopSelf(); return START_NOT_STICKY }
        }
        val title = intent?.getStringExtra("title") ?: tr("闹钟")
        val text = intent?.getStringExtra("text") ?: ""
        startForeground(NOTIF_ID, buildNotification(this, title, text))
        startRing()
        return START_NOT_STICKY
    }

    private fun startRing() {
        if (player != null) return
        // 系统闹铃音，走 ALARM 流：媒体/通知静音也响，音量跟随系统闹钟音量
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(this@AlarmRingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 500, 600, 800), 0)
            )
        }
        // 兜底：最长响 5 分钟自动停（没人管也不能响一整天）
        android.os.Handler(mainLooper).postDelayed({ stopRing(); stopSelf() }, 5 * 60_000L)
    }

    private fun stopRing() {
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vibrator?.cancel() }; vibrator = null
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    /** 再响 5 分钟：原样重排一个精确闹钟 */
    private fun snooze(intent: Intent) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(this, NotifyReceiver::class.java)
            .putExtra("title", intent.getStringExtra("title"))
            .putExtra("text", intent.getStringExtra("text"))
            .putExtra("day", intent.getLongExtra("day", -1L))
            .putExtra("alarm", true)
        val pi = PendingIntent.getBroadcast(
            this, SNOOZE_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val t = System.currentTimeMillis() + 5 * 60_000L
        runCatching {
            if (NotifyScheduler.canExact(this)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    override fun onDestroy() { stopRing(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.looka.app.ALARM_STOP"
        const val ACTION_SNOOZE = "com.looka.app.ALARM_SNOOZE"
        const val NOTIF_ID = 900100
        private const val SNOOZE_CODE = 900101

        fun buildNotification(c: Context, title: String, text: String): Notification {
            val stop = PendingIntent.getService(
                c, 1, Intent(c, AlarmRingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snooze = PendingIntent.getService(
                c, 2, Intent(c, AlarmRingService::class.java).setAction(ACTION_SNOOZE)
                    .putExtra("title", title).putExtra("text", text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 锁屏时系统按全屏意图直接弹 AlarmActivity；亮屏时是抬头通知
            val full = PendingIntent.getActivity(
                c, 3, Intent(c, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title", title).putExtra("text", text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(c, NotifyScheduler.ALARM_CHANNEL)
                .setSmallIcon(R.drawable.ic_deer)
                .setContentTitle("⏰ $title")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)                        // 不可划走 —— 划走了铃还响会更困惑
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .addAction(Notification.Action.Builder(null, tr("停止"), stop).build())
                .addAction(Notification.Action.Builder(null, tr("再响5分钟"), snooze).build())
                .build()
        }
    }
}

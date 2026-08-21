package com.looka.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.looka.app.MainActivity
import com.looka.app.R
import kotlinx.coroutines.launch

/** 收到闹钟后发出系统通知；点击跳转到对应日期（B12） */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val text = intent.getStringExtra("text") ?: ""
        val day = intent.getLongExtra("day", -1L)

        val open = PendingIntent.getActivity(
            context, (day % Int.MAX_VALUE).toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_day", day),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(context, NotifyScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_deer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}

/** 每日 00:05 滚动续期未来 14 天的提醒（B5） */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as com.looka.app.LookaApp
        app.appScope.launch {
            try {
                NotifyScheduler.rescheduleFromDb(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** APK 下载完成 → 校验并唤起安装（五批自更新） */
class ApkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        com.looka.app.util.UpdateManager.onDownloadComplete(context, id)
    }
}

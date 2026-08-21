package com.looka.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.looka.app.LookaApp
import kotlinx.coroutines.launch

/** 开机后重建提醒闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as LookaApp
        app.appScope.launch {
            try {
                NotifyScheduler.rescheduleFromDb(app)
            } finally {
                pending.finish()
            }
        }
    }
}

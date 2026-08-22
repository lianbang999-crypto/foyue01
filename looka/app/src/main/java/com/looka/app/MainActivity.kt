package com.looka.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.looka.app.ui.LookaRoot
import com.looka.app.ui.theme.LookaTheme
import com.looka.app.util.PendingNav

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // P2-A5：回到前台刷订阅状态（内部 5 分钟节流）。
        // 关键场景：用户去浏览器付完款切回来 —— webhook/对账已在服务端开通，这一刷让它"看起来是即时的"
        val app = application as LookaApp
        app.appScope.launch { com.looka.app.data.PlanState.refresh(this@MainActivity) }
    }


    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.looka.app.ui.theme.ThemeCtl.init(this)
        consumeIntent(intent)
        setContent {
            LookaTheme {
                LookaRoot()
            }
        }
        // Android 13+ 请求通知权限（日程提醒用）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    /** 通知点击带 open_day → 日历跳到该天并打开抽屉（B12） */
    private fun consumeIntent(i: Intent?) {
        val day = i?.getLongExtra("open_day", -1L) ?: -1L
        if (day >= 0) PendingNav.day = day
    }
}

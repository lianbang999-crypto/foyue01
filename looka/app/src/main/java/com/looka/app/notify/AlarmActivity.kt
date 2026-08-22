package com.looka.app.notify

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.R
import com.looka.app.ui.common.plainClick
import com.looka.app.util.tr

/** A2：全屏闹钟页 —— 锁屏直接弹出，两个大按钮：停止 / 再响5分钟 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏之上显示 + 点亮屏幕
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        val title = intent.getStringExtra("title") ?: tr("闹钟")
        val text = intent.getStringExtra("text") ?: ""
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF1C1E1C)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(painterResource(R.drawable.ic_deer_badge), null, Modifier.size(96.dp))
                Spacer(Modifier.height(24.dp))
                Text("⏰ $title", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text, fontSize = 15.sp, color = Color(0xFFB9BBB9))
                }
                Spacer(Modifier.height(56.dp))
                Row {
                    Text(
                        tr("再响5分钟"), fontSize = 16.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF3A3D3A))
                            .plainClick { act(AlarmRingService.ACTION_SNOOZE, title, text) }
                            .padding(horizontal = 28.dp, vertical = 16.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        tr("停止"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(28.dp))
                            .background(Color.White)
                            .plainClick { act(AlarmRingService.ACTION_STOP, title, text) }
                            .padding(horizontal = 40.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }

    private fun act(action: String, title: String, text: String) {
        startService(
            Intent(this, AlarmRingService::class.java).setAction(action)
                .putExtra("title", title).putExtra("text", text)
        )
        finish()
    }
}

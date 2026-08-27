package com.looka.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.ui.theme.LookaTokens
import com.looka.app.util.tr

/**
 * §126 C3：**迷你月历预览** —— 换主题前先看一屏"假月历"套上候选色。
 * 两枚色卡说不清一套主题（用户看到的是圆点，不是他的日历）；
 * 这里把 accent/selection/today/weekend/holiday/event 槽全画在真实形态上。
 * 用在：照片取色预览、聊天生成主题草稿卡（将来商店皮肤包详情同用）。
 * 纯预览：不可点、不读全局主题（一切颜色来自传入的 tokens）。
 */
@Composable
fun MiniThemePreview(tokens: LookaTokens, modifier: Modifier = Modifier) {
    val week = listOf(tr("日"), tr("一"), tr("二"), tr("三"), tr("四"), tr("五"), tr("六"))
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(0.8.dp, tokens.divider, RoundedCornerShape(6.dp))
            .background(tokens.surface)
    ) {
        // 顶栏意象：月份字 + accent 圆点（皮肤的"氛围"就是从这一条开始的）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr("8月"), fontSize = 13.sp, color = tokens.textPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(tokens.accent))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(tokens.divider))
        // 星期头（周日红 / 周六蓝 —— 可读性槽，不随皮肤变，预览也照实画）
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            week.forEachIndexed { i, w ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        w, fontSize = 9.sp,
                        color = when (i) {
                            0 -> tokens.holiday; 6 -> tokens.weekend
                            else -> tokens.textSecondary
                        }
                    )
                }
            }
        }
        // 4 行日期格：今天块（today 槽）、选中底（selection）、两条事件（eventAllDay/accent）
        var day = 1
        repeat(4) { r ->
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(tokens.divider))
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                repeat(7) { c ->
                    val n = day++
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        when {
                            // 今天：墨色小方块白字（today 槽）
                            n == 10 -> Box(
                                Modifier.padding(top = 2.dp).size(14.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(tokens.today),
                                contentAlignment = Alignment.Center
                            ) { Text("$n", fontSize = 8.sp, color = tokens.surface) }
                            // 选中日：selection 浅底
                            n == 16 -> Box(
                                Modifier.padding(top = 2.dp).size(14.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(tokens.selection),
                                contentAlignment = Alignment.Center
                            ) { Text("$n", fontSize = 8.sp, color = tokens.textPrimary) }
                            else -> Text(
                                "$n", fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp),
                                color = when (c) {
                                    0 -> tokens.holiday; 6 -> tokens.weekend
                                    else -> tokens.textPrimary
                                }
                            )
                        }
                        // 事件条：全天条（selection 系）+ accent 圆点各示意一枚
                        if (n == 4) Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                                .fillMaxWidth(0.8f).height(5.dp)
                                .clip(RoundedCornerShape(1.5.dp)).background(tokens.eventAllDay)
                        )
                        if (n == 17) Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                                .size(5.dp).clip(CircleShape).background(tokens.accent)
                        )
                    }
                }
            }
        }
    }
}

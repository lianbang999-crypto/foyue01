package com.looka.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.data.Prefs
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.util.I18n
import com.looka.app.util.LunarCal
import com.looka.app.util.tr
import androidx.compose.ui.platform.LocalContext
import java.time.YearMonth

/**
 * 自绘月历选择器（替代 Material3 DatePicker —— 那套圆角胶囊与 Looka 的白底细线语言不搭）。
 * 视觉与主月视图同源：今天=黑方块、选中=主题色圆、休日红、农历小字。
 */
@Composable
fun LookaDatePicker(initialDay: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableLongStateOf(initialDay) }
    var ym by remember { mutableStateOf(YearMonth.from(Fmt.d(initialDay))) }
    var dir by remember { mutableStateOf(1) }
    val weekStartMon = remember { Prefs.weekStartMonday(ctx) }
    val holidayMask = remember { Prefs.holidayMask(ctx) }
    val showLunar = remember { Prefs.showLunarRaw(ctx) ?: I18n.isZh() }
    val today = Fmt.today()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = null,
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 头部：‹ 2026年8月 ›
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { dir = -1; ym = ym.minusMonths(1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ChevronLeft, tr("上移"), tint = Ink, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        Fmt.monthTitle(ym),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { dir = 1; ym = ym.plusMonths(1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ChevronRight, tr("下移"), tint = Ink, modifier = Modifier.size(22.dp))
                    }
                }

                // 星期头
                val firstDow = if (weekStartMon) 1 else 7
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    for (i in 0 until 7) {
                        val dow = ((firstDow - 1 + i) % 7) + 1
                        Text(
                            Fmt.week(dow), fontSize = 10.sp,
                            color = weekdayTint(dow, holidayMask) ?: GrayText,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                        )
                    }
                }
                Hairline()

                // 月网格（换月左右滑入）
                AnimatedContent(
                    targetState = ym,
                    transitionSpec = {
                        (slideInHorizontally(tween(200)) { w -> dir * w / 4 } + fadeIn(tween(160)))
                            .togetherWith(slideOutHorizontally(tween(200)) { w -> -dir * w / 4 } + fadeOut(tween(120)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "miniMonth"
                ) { m ->
                    val first = m.atDay(1)
                    val lead = ((first.dayOfWeek.value - firstDow) + 7) % 7
                    val gridStart = first.toEpochDay() - lead
                    val rows = (lead + m.lengthOfMonth() + 6) / 7
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for (r in 0 until rows) {
                            Row(Modifier.fillMaxWidth()) {
                                for (c in 0 until 7) {
                                    val day = gridStart + r * 7 + c
                                    val dt = Fmt.d(day)
                                    val inMonth = YearMonth.from(dt) == m
                                    val isSel = day == sel
                                    val isToday = day == today
                                    val lunar = if (showLunar) LunarCal.of(day) else null
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .height(if (showLunar) 42.dp else 36.dp)
                                            .padding(1.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSel) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent
                                            )
                                            .then(
                                                if (isSel) Modifier.border(
                                                    1.2.dp, MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(8.dp)
                                                ) else Modifier
                                            )
                                            .plainClick { sel = day },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        if (isToday) {
                                            Box(
                                                Modifier.size(19.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(Ink),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "${dt.dayOfMonth}", fontSize = 11.sp,
                                                    color = Color.White, fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Text(
                                                "${dt.dayOfMonth}", fontSize = 13.sp,
                                                color = when {
                                                    !inMonth -> Color(0xFFC5C8C5)
                                                    else -> weekdayTint(dt.dayOfWeek.value, holidayMask) ?: Ink
                                                },
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                        if (lunar != null) {
                                            Text(
                                                lunar.cellText, fontSize = 7.5.sp,
                                                maxLines = 1,
                                                color = when {
                                                    lunar.festival != null -> HolidayRed
                                                    !inMonth -> Color(0xFFD5D8D5)
                                                    else -> Color(0xFFA8ADA8)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 快捷：今天 / 明天 / 下周
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf(tr("今天") to today, tr("明天") to today + 1, tr("下周一") to today + (8 - Fmt.d(today).dayOfWeek.value))
                        .forEach { (label, d0) ->
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (sel == d0) Ink else PanelBg)
                                    .plainClick { sel = d0; ym = YearMonth.from(Fmt.d(d0)) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(label, fontSize = 11.sp, color = if (sel == d0) Color.White else Ink)
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(sel); onDismiss() }) {
                Text(tr("确定"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) }
        }
    )
}

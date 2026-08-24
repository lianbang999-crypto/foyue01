package com.looka.app.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavHostController
import com.looka.app.data.FREQ_MONTHLY
import com.looka.app.data.FREQ_NONE
import com.looka.app.data.FREQ_WEEKLY
import com.looka.app.data.RecurrenceEngine
import com.looka.app.ui.common.safeBack
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.NavRow
import com.looka.app.ui.common.Stepper
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.LinkBlue
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/**
 * 重复规则编辑器（规格 CAL-020）：
 * 顶部频率分段（无/每天/每周/每月/每年），修改直接写入草稿，返回即生效。
 */
@Composable
fun RecurrenceEditorScreen(vm: LookaViewModel, nav: NavHostController) {
    val d = vm.draft
    if (d == null) {
        LaunchedEffect(Unit) { safeBack(nav) }
        return
    }
    var untilDlg by remember { mutableStateOf(false) }
    val weekNames = listOf(tr("一"), tr("二"), tr("三"), tr("四"), tr("五"), tr("六"), tr("日"))
    val base = Fmt.d(d.startDay)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("重复"), onBack = { nav.popBackStack() })

        // 规则摘要
        Text(
            RecurrenceEngine.summary(d.freq, d.interval, d.weekdays, d.monthlyByWeekday, d.untilDay, d.startDay),
            fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        )
        Hairline()

        // 频率分段（黑色下划线选中态，参照实机 UI）
        Row(Modifier.fillMaxWidth()) {
            listOf(tr("无"), tr("每天"), tr("每周"), tr("每月"), tr("每年")).forEachIndexed { i, label ->
                val sel = d.freq == i
                Column(
                    Modifier.weight(1f).plainClick {
                        d.freq = i
                        if (i == FREQ_WEEKLY && d.weekdays == 0) {
                            d.weekdays = 1 shl (base.dayOfWeek.value - 1)
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label, fontSize = 14.sp,
                        color = if (sel) Ink else Color(0xFFB9BBB9),
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Box(
                        Modifier.width(30.dp).height(2.dp)
                            .background(if (sel) Ink else Color.Transparent)
                    )
                }
            }
        }
        Hairline()

        if (d.freq != FREQ_NONE) {
            // 间隔：每 N 天/周/月/年
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("间隔"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(tr("每"), fontSize = 14.sp, color = GrayText)
                Stepper(d.interval, min = 1, max = 99) { d.interval = it }
                Text(
                    when (d.freq) {
                        1 -> tr("天"); 2 -> tr("周"); 3 -> tr("个月"); else -> tr("年")
                    },
                    fontSize = 14.sp, color = GrayText
                )
            }
            Hairline()

            // 每周：7 个圆形多选（黑色实心选中 —— 规格 §12）
            if (d.freq == FREQ_WEEKLY) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 0..6) {
                        val on = (d.weekdays shr i) and 1 == 1
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (on) Ink else PanelBg)
                                .plainClick {
                                    val next = d.weekdays xor (1 shl i)
                                    if (next != 0) d.weekdays = next  // 至少保留一天
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                weekNames[i], fontSize = 13.sp,
                                color = if (on) Color.White else Ink
                            )
                        }
                    }
                }
                Hairline()
            }

            // 每月：按日期 / 第 N 个星期几（CAL-REC-004/005）
            if (d.freq == FREQ_MONTHLY) {
                val nth = (base.dayOfMonth - 1) / 7 + 1
                Row(
                    Modifier.fillMaxWidth().plainClick { d.monthlyByWeekday = false }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !d.monthlyByWeekday, onClick = { d.monthlyByWeekday = false })
                    Text(tr("按日期 · 每月{0}日", base.dayOfMonth), fontSize = 14.sp)
                }
                Row(
                    Modifier.fillMaxWidth().plainClick { d.monthlyByWeekday = true }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = d.monthlyByWeekday, onClick = { d.monthlyByWeekday = true })
                    Text(
                        tr("按星期 · 每月第{0}个周{1}", nth, weekNames[base.dayOfWeek.value - 1]),
                        fontSize = 14.sp
                    )
                }
                Text(
                    tr("如设每月31日，小月自动落在当月最后一天"),
                    fontSize = 11.sp, color = GrayText,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                Hairline()
            }

            // 结束日（CAL-REC-006）
            // §87 D3b（V011 §8.1 [B]）：这里用**内联展开**而不是弹层 ——
            // 结束日和上面的频率 tab、间隔属于同一个编辑上下文，弹个 Dialog 盖住它们
            // 等于把用户刚设的规则藏起来，让他没法边看边调。
            NavRow(
                tr("结束日"),
                value = if (d.untilDay >= 0) Fmt.dateCn(d.untilDay) else tr("未设置")
            ) { untilDlg = !untilDlg }
            androidx.compose.animation.AnimatedVisibility(
                visible = untilDlg,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column {
                    com.looka.app.ui.common.LookaMonthPanel(
                        sel = if (d.untilDay >= 0) d.untilDay else d.startDay + 30,
                        // 内联变体：点一下即回填（同上下文可见，不需要二次确认）
                        onSelect = { d.untilDay = maxOf(it, d.startDay) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        TextButton(onClick = { d.untilDay = -1L; untilDlg = false }) {
                            Text(tr("未设置"), color = GrayText, fontSize = 13.sp)
                        }
                        TextButton(onClick = { untilDlg = false }) {
                            Text(tr("收起"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                    }
                }
            }
            Hairline()
        }
        Spacer(Modifier.weight(1f))
    }

}

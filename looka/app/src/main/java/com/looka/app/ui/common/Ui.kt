@file:OptIn(ExperimentalMaterial3Api::class)

package com.looka.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.R
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.SatBlue
import com.looka.app.ui.theme.SaveDark
import com.looka.app.util.tr

/** 细分隔线（规格 §12：浅灰分隔组织信息） */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** 顶栏：左返回 / 标题 / 右操作（规格 §12 App Bar 语言） */
@Composable
fun LookaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(backIcon, tr("返回"), tint = Ink) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        Hairline()
    }
}

/** 可点击导航行 */
@Composable
fun NavRow(
    title: String,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowClick(onClick)   // §85 B4：整行浅灰按压（V011 §6.1），替换水波纹
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value, fontSize = 13.sp, color = GrayText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFC9CCC9), modifier = Modifier.size(20.dp))
    }
}

/** 开关行 */
@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = GrayText)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            // 浅色（如近白）在白底上会隐形，补一圈极淡描边
            .then(
                if (color.luminance() > 0.82f)
                    Modifier.border(0.8.dp, Color(0xFFD8D8D8), CircleShape)
                else Modifier
            )
    )
}

/**
 * 色块上的文字颜色：亮底黑字、深底白字。
 * 换 Lifebear 高饱和盘（含大量黄绿青亮色）后，写死白字会直接看不见 —— 必须自动化。
 */
fun onColor(bg: Color): Color = if (bg.luminance() > 0.55f) Ink else Color.White

fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF9AA0A6)
}

/** 星期文字强调色：休日红 / 周六蓝 / null 默认（规格 CAL-060 休日星期） */
fun weekdayTint(dowIso: Int, holidayMask: Int): Color? = when {
    (holidayMask shr (dowIso - 1)) and 1 == 1 -> HolidayRed
    dowIso == 6 -> SatBlue
    else -> null
}

/** 深色保存按钮（规格 §12：保存为实色深色按钮） */
@Composable
fun SaveButton(text: String = tr("保存"), enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SaveDark,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFC3C5C3),
            disabledContentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp)
    ) { Text(text, fontSize = 14.sp) }
}

/**
 * T9（§70）：区块级加载 —— 小鹿轻轻点头 + 一句话，替代裸转圈。
 * 按钮内的小转圈保留不动（克制：行内反馈不需要品牌出场）。
 */
@Composable
fun DeerLoading(text: String, modifier: Modifier = Modifier) {
    val anim = androidx.compose.animation.core.rememberInfiniteTransition(label = "deerLoad")
    val dy by anim.animateFloat(
        initialValue = 0f, targetValue = -4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(650),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "deerLoadDy"
    )
    Row(
        modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.offset(y = dy.dp)) { DeerBadge(22.dp) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = GrayText)
    }
}

/** 空状态小鹿（轻微上下浮动） */
@Composable
fun EmptyDeer(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    val float = androidx.compose.animation.core.rememberInfiniteTransition(label = "deerFloat")
    val dy by float.animateFloat(
        initialValue = 0f, targetValue = -5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "deerDy"
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DeerBadge(64.dp)   // B3 补漏（§58）：空状态小鹿也随主题
        Spacer(Modifier.height(10.dp))
        Text(text, fontSize = 13.sp, color = GrayText)
        // 指向性提示（对齐 Lifebear「ここから作成できます ↓」）：
        // 空状态不能只说"没有"，要指给用户从哪开始 —— 箭头方向由调用方写进文案（↑ ↗ ↓）
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                hint, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Pro 功能上锁弹窗（对齐 Lifebear：先演示功能长什么样，再谈钱）。
 * demo 槽位放一小块功能实景预览 —— 不是付费墙文案，是"看，它是这样的"。
 */
@Composable
fun ProFeatureDialog(
    title: String,
    desc: String,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
    demo: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF4F4F2))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { demo() }
                Spacer(Modifier.height(12.dp))
                Text(desc, fontSize = 13.5.sp, color = GrayText, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onGo) {
                Text(tr("了解 Pro"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭"), color = GrayText) }
        },
        containerColor = Color.White
    )
}

/** 二次确认弹窗（规格 AC-008 普通删除需确认） */
@Composable
fun ConfirmDialog(
    title: String,
    text: String? = null,
    confirmText: String = tr("删除"),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = if (text != null) {
            { Text(text, fontSize = 14.sp) }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = HolidayRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) }
        },
        containerColor = Color.White
    )
}

/** 单选弹窗 */
@Composable
fun <T> RadioDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    // S4（§64，Lifebear 规格）：19sp 标题 / 56dp 选项行 / 点即生效即关 / 无取消按钮
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable { onSelect(v); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = v == selected, onClick = { onSelect(v); onDismiss() })
                        Text(label, fontSize = 17.sp)
                    }
                }
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.White
    )
}

/** 时间选择弹窗（当日分钟数进出） */
@Composable
fun LookaTimePicker(initialMin: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = (initialMin / 60).coerceIn(0, 23),
        initialMinute = (initialMin % 60).coerceIn(0, 59),
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute); onDismiss()
            }) { Text(tr("确定")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

/** 数字步进器（重复间隔用） */
@Composable
fun Stepper(value: Int, min: Int = 1, max: Int = 99, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Remove, null, modifier = Modifier.size(18.dp),
                tint = if (value > min) Ink else Color(0xFFCFCFCF)
            )
        }
        Text(
            "$value", fontSize = 15.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp)
        )
        IconButton(onClick = { if (value < max) onChange(value + 1) }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Add, null, modifier = Modifier.size(18.dp),
                tint = if (value < max) Ink else Color(0xFFCFCFCF)
            )
        }
    }
}

/** 无水波纹点击 */
@Composable
fun Modifier.plainClick(onClick: () -> Unit): Modifier = this.clickable(
    indication = null,
    interactionSource = remember { MutableInteractionSource() },
    onClick = onClick
)

/**
 * §87 D2：开「字段级层」（日期/时间 Picker 等）之前先把键盘收掉。
 *
 * V011 §8.3：键盘退场与 Picker 入场如果同时启动，两个 ~300ms 动画会串成 ~600ms 空窗，
 * 期间用户既看不到键盘也看不到 Picker。正确顺序是**先结束输入 composition，再开层**。
 * 此前全项目没有任何键盘协调 —— 标题输入着直接点日期行，键盘和 Dialog 互相抢空间。
 *
 * 用法：`val openPicker = keyboardAwareOpen { startDateDlg = true }`，把它接到行的 onClick。
 */
@Composable
fun keyboardAwareOpen(open: () -> Unit): () -> Unit {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    return {
        focus.clearFocus(force = true)
        kb?.hide()
        open()
    }
}

/**
 * §85 B4：行级按压反馈 —— V011 §6.1「整行浅灰 pressed 先于转场，不位移不放大」。
 * 用于会导航/打开编辑器的整行（NavRow/清单行/任务行/笔记行/表单行）；
 * 日历格、贴纸、chip、心情圆这类自带选中态的元素继续用 plainClick。
 */
@Composable
fun Modifier.rowClick(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // §90 R2（v1.3 §11：pressed 60–100ms）：原来是瞬变，按下去像"闪一下"。
    // 80ms 过渡让按压有厚度；抬手回弹稍快，避免拖沓。
    val bg by androidx.compose.animation.animateColorAsState(
        if (pressed) Color(0x0F1B1B1F) else Color.Transparent,
        androidx.compose.animation.core.tween(if (pressed) 80 else 120),
        label = "rowPress"
    )
    return this
        .background(bg)
        .clickable(indication = null, interactionSource = src, onClick = onClick)
}

/** 无边框透明输入框样式（规格 §12：表单以分隔线组织、边框极少） */
@Composable
fun clearFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary
)

/** 轻提示 */
fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.looka.app.ui.common

import com.looka.app.ui.theme.LkIcons

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
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * §113 A3：把当前 Dialog 的 scrim 提到 60% 黑。
 * Lifebear 实机 scrim 采样 RGB(102,102,102) ≈ black 60%（母档 5.2），M3 默认只有 32% ——
 * 背景压不暗，弹窗「浮」不出来。M3 没开这个口子，只能从 view 树往上摸 DialogWindowProvider。
 * 放在 Dialog 内容里任意位置调用即可（此时 LocalView 已在 dialog window 内）。
 */
@Composable
fun DialogDim(fraction: Float = 0.6f) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)
            ?.window?.setDimAmount(fraction)
    }
}

/**
 * §113 A2+A3：全站 Dialog 标题的唯一写法 —— 19sp SemiBold（实机 20sp 粗，图 09/15/18/26），
 * 顺手把 scrim 拉到 60%。替换掉此前散落各处的 `Text(xx, fontSize = 17.sp)`。
 */
@Composable
fun DlgTitle(text: String) {
    DialogDim()
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
}

/** 顶栏：左返回 / 标题 / 右操作（规格 §12 App Bar 语言） */
@Composable
fun LookaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    /** §103：标题可点（日记改期用）—— 传 null 时标题就是死的，行为不变 */
    onTitleClick: (() -> Unit)? = null,
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
                    .then(if (onTitleClick != null) Modifier.plainClick(onTitleClick) else Modifier)
            )
            actions()
        }
        Hairline()
    }
}

/** 可点击导航行。
 * §113 C1：对齐实机设置行模板（图 33/34）—— 主标签 16sp 黑 + **摘要 12sp 灰在下一行**、
 * **行尾无箭头**。此前是「15sp + 右侧 value + chevron」：值在右边一挤就截断，
 * 而实机把摘要放主标签底下，整行留白反而更透气；箭头实机根本没有。 */
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
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = GrayText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (value != null) {
                Text(
                    value, fontSize = 12.sp, color = GrayText, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
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

/**
 * §99 I1：**幂等返回** —— 白屏根治。
 *
 * 删掉当前页对应的实体后，页面会同时走两条退出路径：
 *   ① 确认按钮里直接 `nav.popBackStack()`
 *   ② 实体从数据流里消失 → `if (x == null)` 守卫里的 `LaunchedEffect` 再 `popBackStack()` 一次
 *
 * 退出动画期间旧页面仍在组合中，两条都会执行 —— 于是**多弹了一层**，
 * 连承载底部 tab 的宿主一起弹掉，NavHost 空了 → **整屏白**（实机图 85：只剩状态栏）。
 *
 * `previousBackStackEntry == null` 表示已经退到栈底，此时再弹就是弹过头。
 * 这里统一拦掉，比在每个页面加"已退出"标志位可靠 —— 它对**所有**重复来源都成立。
 */
fun safeBack(nav: androidx.navigation.NavHostController) {
    if (nav.previousBackStackEntry != null) nav.popBackStack()
}

/** 星期文字强调色：休日红 / 周六蓝 / null 默认（规格 CAL-060 休日星期） */
fun weekdayTint(dowIso: Int, holidayMask: Int): Color? = when {
    (holidayMask shr (dowIso - 1)) and 1 == 1 -> HolidayRed
    dowIso == 6 -> SatBlue
    else -> null
}

/** 深色保存按钮（规格 §12）。§113 A5：底色从 #3F3F46 改纯黑 Ink、圆角 8→6dp ——
 * 实机可用态是纯黑小矩形（图 31），#3F3F46 偏灰半档；禁用灰不变（图 06）。 */
@Composable
fun SaveButton(text: String = tr("保存"), enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Ink,
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

/** 空状态小鹿（轻微上下浮动）。
 * §113 D1：结构对齐实机（图 13/14/17）—— **黑粗标题在上、插画在下且放大**。
 * 此前是 64dp 小鹿在上 + 13sp 灰字在下：层级弱，一眼扫不到「这里为什么是空的」。
 * 实机语法：标题（タスクはありません，约 22sp 黑粗）先说清状态，插画只做情绪陪衬。 */
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
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, fontSize = 17.sp, color = Ink, fontWeight = FontWeight.SemiBold)
        // 指向性提示（对齐 Lifebear「ここから作成できます ↓」）：
        // 空状态不能只说"没有"，要指给用户从哪开始 —— 箭头方向由调用方写进文案（↑ ↗ ↓）
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                hint, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.offset(y = dy.dp)) {
            DeerBadge(96.dp)   // B3 补漏（§58）：空状态小鹿也随主题
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
        title = { DlgTitle(title) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF4F4F2))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { demo() }
                Spacer(Modifier.height(12.dp))
                Text(desc, fontSize = 13.5.sp, color = GrayText, lineHeight = 20.sp)
            }
        },
        // §113 B4：能力门是全站唯一用「实心按钮对」的 Dialog（实机图 20/21：
        // 閉じる=白底黑描边 / 詳しくみる=黑底白字，并排等宽）。普通确认弹窗仍是文字按钮 ——
        // 能力门带营销性质，按钮要有「可选择的两条路」的分量；删除确认不配拥有这种分量。
        confirmButton = {
            Button(
                onClick = onGo,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) { Text(tr("了解 Pro"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            androidx.compose.material3.OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) { Text(tr("关闭"), fontSize = 14.sp) }
        },
        containerColor = Color.White
    )
}

/** 二次确认弹窗（规格 AC-008 普通删除需确认）。
 * §113 A4：确认动作从红字改 **Ink 黑字 SemiBold** —— Lifebear 实机删除确认里
 * 「削除」也是黑字（图 09/11），危险语义靠文案与二次确认承担，红色只留给日历休日。 */
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
        title = { DlgTitle(title) },
        text = if (text != null) {
            { Text(text, fontSize = 14.sp) }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = Ink, fontWeight = FontWeight.SemiBold)
            }
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
    // §113 A2：标题收敛到 DlgTitle（19sp SemiBold + 60% scrim）
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DlgTitle(title) },
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
        // §114 P3：显式 8dp 删除 —— 盖掉了 §113 的全局 3dp，同 App 两套圆角语言
        containerColor = Color.White
    )
}

/**
 * §113 B2：短语义单选弹窗 —— **整行浅灰底标当前项、无 radio、选择即关**。
 * 实机的「期間選択」（图 15/16）就是这个形态：选项少、语义一眼懂时，
 * radio 是多余的仪式感；当前项整行灰底比圆点更快扫到。
 * 与 RadioDialog 的分工：设置枚举（含说明性选项）用 radio，快速范围切换用这个。
 */
@Composable
fun <T> PlainChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DlgTitle(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(if (v == selected) Color(0xFFE4E5E4) else Color.Transparent)
                            .clickable { onSelect(v); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 15.dp)
                    ) {
                        Text(label, fontSize = 16.sp, color = Ink)
                    }
                }
            }
        },
        confirmButton = {},
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
        text = { DialogDim(); TimePicker(state) },
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
                LkIcons.Plus, null, modifier = Modifier.size(18.dp),
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

/**
 * §113 A8：**弹窗内**输入框样式 —— 黑色下划线。
 * Lifebear 的建清单/重命名 Dialog 里输入框是一条醒目的黑色下划线（图 18/19/27/28），
 * 不是无边框也不是描边框。页面级标题输入（日程名等）继续用 clearFieldColors。
 * 线厚由 M3 内置：聚焦 2dp / 未聚焦 1dp，颜色统一 Ink。
 */
@Composable
fun dialogFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Ink,
    unfocusedIndicatorColor = Ink,
    disabledIndicatorColor = Color(0xFFC9CCC9),
    cursorColor = Ink
)

/** 轻提示 */
fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}


/**
 * §117 D3：统一加载态 —— 小鹿呼吸。
 * 替代散在各处的 Material 转圈：转圈是"系统在忙"，小鹿是"小鹿在忙"——
 * 加载也是品牌时刻。尺寸跟随字号语义，动画 900ms 呼吸缩放，reduce-motion 时由
 * 系统动画缩放自动停。
 */
@Composable
fun DeerLoading(size: androidx.compose.ui.unit.TextUnit = 22.sp, modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "deerLoad")
    val scale by t.animateFloat(
        0.85f, 1.12f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "deerScale"
    )
    Text(
        "🦌", fontSize = size,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    )
}

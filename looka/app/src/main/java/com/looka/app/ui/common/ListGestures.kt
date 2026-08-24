package com.looka.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.looka.app.util.tr
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.looka.app.ui.theme.HolidayRed
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * §99 I5：**全站统一的列表手势** —— 长按拖动排序 + 左滑删除。
 *
 * 为什么做成一个组件而不是各页面各写一遍：
 *  1. 两个手势会**互抢事件**。纵向拖拽（排序）和横向拖拽（删除）挂在同一行上，
 *     谁先拿到 pointer 谁就赢 —— 各写一遍必然出现「想删的时候在排序」。
 *     这里由 `detectDragGesturesAfterLongPress`（必须先长按）与
 *     `detectHorizontalDragGestures`（只认横向）分工，互不重叠。
 *  2. 删除入口此前散在 15 个文件里，各弹各的确认框。统一入口才谈得上统一撤销。
 *
 * 明确不套这套手势的地方（§98 说明书里已拍板）：
 *  - **日历月格**：长按已经是「弹贴纸/日程菜单」，且月格不是列表
 *  - **AI 对话**：长按已经是复制
 *  - **智能视图**（星标 / 未来7天 / 已完成）：顺序由规则决定，不该手动排 —— 只给左滑删除
 */

/**
 * 一次拖拽排序会话的共享状态；由列表持有，行读取。
 *
 * ⚠️ `draggingUid` / `offsetY` 必须是 **Compose state** —— 用普通 `var` 的话
 * 拖动时不会触发重组，行的位移根本画不出来（我第一版就写错成普通 var）。
 */
class ReorderState(val order: SnapshotStateList<String>) {
    var draggingUid by mutableStateOf<String?>(null)
    var offsetY by mutableStateOf(0f)
}

/** 列表侧持有：`open` 变化时同步顺序，但**拖拽中不覆盖**，否则手指下的行会跳回去 */
@Composable
fun rememberReorderState(uids: List<String>): ReorderState {
    val st = remember { ReorderState(mutableStateListOf()) }
    LaunchedEffect(uids) {
        if (st.draggingUid == null) { st.order.clear(); st.order.addAll(uids) }
    }
    return st
}

/**
 * 行手势：长按纵向拖拽排序 + 左滑露出删除按钮。
 *
 * §100：**改成两步**。原来是「划过阈值就直接删」—— 手一滑东西就没了。
 * 实机（Lifebear）是划开露出一条红色的「删除」，**再点一下**才真删。
 * 两步比一步安全得多，而且撤销条只是兜底、不该当主防线。
 *
 * @param uid          本行的稳定标识（拖拽用）
 * @param rowHeightPx  行高，把位移换算成"挪了几格"
 * @param onReorder    拖拽结束回调新顺序；null = 本列表不支持排序（智能视图）
 * @param onDelete     点红色「删除」后回调；null = 本行不可删
 */
@Composable
fun Modifier.listRowGestures(
    uid: String,
    state: ReorderState?,
    rowHeightPx: Float,
    onReorder: ((List<String>) -> Unit)?,
    onDelete: (() -> Unit)?
): Modifier {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // 红色删除条的宽度；行最多划开这么多，不会整条飞走
    val revealPx = with(density) { SWIPE_REVEAL.toPx() }
    val swipeX = remember(uid) { Animatable(0f) }
    val dragging = state?.draggingUid == uid

    var m = this

    if (onReorder != null && state != null) {
        m = m.pointerInput(uid, rowHeightPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.draggingUid = uid; state.offsetY = 0f
                    scope.launch { swipeX.animateTo(0f) }   // 开始拖排序就把划开的红条收回去
                },
                onDrag = { change, delta ->
                    change.consume()
                    state.offsetY += delta.y
                    val from = state.order.indexOf(uid)
                    if (from >= 0 && rowHeightPx > 0f) {
                        val shift = (state.offsetY / rowHeightPx).roundToInt()
                        val to = (from + shift).coerceIn(0, state.order.size - 1)
                        if (to != from) {
                            state.order.removeAt(from)
                            state.order.add(to, uid)
                            state.offsetY -= (to - from) * rowHeightPx
                        }
                    }
                },
                onDragEnd = {
                    state.draggingUid = null; state.offsetY = 0f
                    onReorder(state.order.toList())
                },
                onDragCancel = { state.draggingUid = null; state.offsetY = 0f }
            )
        }
    }

    if (onDelete != null) {
        m = m.pointerInput(uid) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    // 划过一半就停在"开着"的位置露出删除按钮；不够就弹回去。**不直接删**
                    scope.launch {
                        if (swipeX.value <= -revealPx / 2f) swipeX.animateTo(-revealPx)
                        else swipeX.animateTo(0f)
                    }
                },
                onDragCancel = { scope.launch { swipeX.animateTo(0f) } }
            ) { change, delta ->
                val next = (swipeX.value + delta).coerceIn(-revealPx, 0f)
                if (next != swipeX.value) change.consume()
                scope.launch { swipeX.snapTo(next) }
            }
        }
    }

    return m.graphicsLayer {
        translationX = swipeX.value
        translationY = if (dragging) state?.offsetY ?: 0f else 0f
        shadowElevation = if (dragging) 12f else 0f
    }
}

/** 左滑露出的红色删除条宽度 */
val SWIPE_REVEAL = 88.dp

/**
 * 左滑露出的**红色删除按钮**。放在行下面一层，行划开时露出来，点它才真删。
 * 抽出来是为了每个列表长得一样 —— 不然又会各画各的。
 */
@Composable
fun SwipeDeleteBackdrop(modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.width(SWIPE_REVEAL).fillMaxHeight()
                .background(HolidayRed)
                .then(if (onDelete != null) Modifier.plainClick(onDelete) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(tr("删除"), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

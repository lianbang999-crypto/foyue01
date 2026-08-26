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

/**
 * §102：**快照式列表** —— 页内操作不让条目当场消失。
 *
 * 症状：新建任务后点前面的圆圈，任务**立刻从列表里不见了**，看着就像被删了。
 * 实际是 `filter { !it.done }` 响应式重算，勾完就不满足条件。取消星标在星标页同理。
 *
 * Lifebear 不是这样：§96.2 拿图 78/79 对照过 —— 同一时刻取消星标、标完成，
 * 条目**都还留在页面上**，只是变个样子。进页面时快照一次，页内操作不改变行的存在。
 * 这条我在 §96 记过"建议对齐"，一直没做，就是这次的根因。
 *
 * 规则：
 *  - 进页面时以 `visible` 为准建快照
 *  - 页内新出现的（新建）**追加**进来
 *  - 因为改状态而不再满足筛选的（打勾/取消星标）**留着**
 *  - **真被删掉的**（从 `alive` 里消失）才移除 —— 否则左滑删完还赖着不走
 *
 * ── §108（2026-08-26）修两个 BUG，**同一个根因** ────────────────────────
 *
 * 用户报的两条症状：
 *   a) 左滑删除后条目**不消失**
 *   b) 建第一条不显示，**建第二条时才一起跳出来**
 *
 * 都出在这个函数原来的两个写法上：
 *
 * **① 原来用 `LaunchedEffect` 更新 —— 晚一帧。**
 *   而调用方普遍写成 `remember(shown, byUid) { shown.mapNotNull{...} }`。
 *   加第一条时：这一帧 shown 还是空 → 算出空列表并**被 remember 缓存**；
 *   下一帧 effect 才把它填上，但那时 remember 的 key 已经不再变化 → 缓存的空列表赖着不走。
 *
 * **② 原来返回 `SnapshotStateList` 本身 —— 身份永不改变。**
 *   `remember(keys)` 用 `equals` 比 key，而 `SnapshotStateList` **没有重写 equals**，
 *   走的是引用相等。所以它内容怎么变，对 `remember` 来说 key 都"没变"。
 *   这一条正好把 ① 的缓存钉死：只有等别的 key（`byUid`）变，整块才会重算 ——
 *   而 `byUid` 只在 tasks 变化时才换，于是表现成"要等下一次增删才刷新"。
 *   删除同理：删完 `byUid` 变了一次、重算了一次，但那一帧 shown 还没剔除，
 *   于是**删掉的行留在页面上**。
 *
 * 两处一起改：**同步算 + 返回不可变 List**。
 * 返回不可变 List 之后，调用方的 `remember(...)` 才是按内容比较，语义才对。
 *
 * @param visible 当前"按规则该显示"的 uid 列表
 * @param alive   仍然存在（未删除）的全部 uid —— 用来区分「改了状态」和「真删了」
 */
@Composable
fun rememberSnapshotOrder(visible: List<String>, alive: Set<String>): List<String> {
    // 用普通可变表即可：不再靠快照观察驱动重组，改由下面 remember 的 key 驱动
    val shown = remember { mutableListOf<String>() }
    return remember(visible, alive) {
        // 先剔除真的没了的
        shown.retainAll { it in alive }
        // 再把新出现的按 visible 的顺序补进来
        visible.forEachIndexed { i, uid ->
            if (uid !in shown) shown.add(i.coerceAtMost(shown.size), uid)
        }
        shown.toList()   // ← 不可变快照：内容变身份就变，调用方的 remember 才会失效
    }
}

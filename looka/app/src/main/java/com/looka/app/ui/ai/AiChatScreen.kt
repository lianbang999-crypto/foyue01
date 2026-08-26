@file:OptIn(ExperimentalFoundationApi::class)

package com.looka.app.ui.ai

import com.looka.app.ui.theme.LkIcons

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.looka.app.data.PlanState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.R
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.vm.ChatMsg
import com.looka.app.vm.LookaViewModel
import com.looka.app.vm.ROLE_ACTION
import com.looka.app.vm.ROLE_USER
import androidx.compose.ui.platform.LocalContext
import com.looka.app.ai.AiClient
import com.looka.app.data.Prefs
import com.looka.app.net.Api
import com.looka.app.util.tr

/** 小鹿 AI 助手：带真实日程上下文的对话 + 自然语言创建 + 周总结 */
@Composable
fun AiChatScreen(vm: LookaViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    // E3（§79 / AC LOOKA-107）：快捷指令只填意图，发送由用户明确触发（一次对话扣 1 鹿角，
    // 四个 chip 挨得近，误触即扣钱）。chipAction 记住「这条 chip 要走的专用动作」——
    // 周总结需要注入本地真实日程/任务/日记，不能退化成普通文本；用户一改文字就作废。
    var chipAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val send = {
        val v = input.trim()
        val act = chipAction
        if (!vm.aiBusy && (act != null || v.isNotEmpty())) {
            input = ""; chipAction = null
            if (act != null) act() else vm.sendChat(v)
        }
    }

    val itemCount = vm.chat.size + if (vm.aiBusy) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1 + 1) // +1 因为首个欢迎项
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(tr("小鹿 AI"), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { vm.clearChat() }) {
                Icon(LkIcons.Trash, tr("清空对话"), tint = GrayText)
            }
            IconButton(onClick = { nav.navigate("subscription") }) {
                Icon(LkIcons.Settings, tr("订阅与设置"), tint = GrayText)
            }
        }

        // §81：《AI 生成合成内容标识办法》(2025-09-01 施行) 要求的显式标识 —— 生成内容的
        // 交互界面须在显著位置提示。放在顶栏正下方，首屏必见，但保持一行小字不打扰。
        Text(
            tr("以下内容由 AI 生成，请自行核对"),
            fontSize = 11.sp, color = GrayText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // §53 M1：档位已下线（单模型 Qwen）。自带 Key / 未登录时仍要说明状态。
        when {
            Prefs.apiKey(ctx).isNotBlank() -> Text(
                tr("你填了自己的 AI Key，小鹿直连你的服务商"),
                fontSize = 11.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            !Api.authed(ctx) -> Text(
                tr("登录后即可使用小鹿 AI"),
                fontSize = 11.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // G3（§54）：当日鹿角到账轻提示 —— 一行小字，看过即清，不弹窗不做特效
        if (AiClient.grantedToday > 0) {
            Text(
                tr("+{0} 🦌 今天的鹿角到账啦", AiClient.grantedToday),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(4000)
                AiClient.grantedToday = 0
            }
        }
        // G4：只在余额吃紧时才提示（平时不打扰 —— 鹿角是够用的额度，不是要攒的资产）
        if (AiClient.lastAntler in 1..9 && Prefs.apiKey(ctx).isBlank()) {
            Text(
                tr("还剩 {0} 枚鹿角（明天自动补充）", AiClient.lastAntler),
                fontSize = 11.sp, color = GrayText,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
        ) {
            item {
                if (vm.chat.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        com.looka.app.ui.common.DeerBadge(72.dp)   // B3：随主题变色
                        Spacer(Modifier.height(12.dp))
                        Text(tr("嗨，我是小鹿 🦌"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("「明天下午3点开会」这样说，我就帮你记上\n也可以问我：今天有什么安排？"),
                            fontSize = 13.sp, color = GrayText, lineHeight = 21.sp,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            items(vm.chat) { m -> ChatBubble(m, vm, nav) }
            if (vm.aiBusy) {
                item {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.looka.app.ui.common.DeerBadge(24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("小鹿正在想…"), fontSize = 13.sp, color = GrayText)
                        Spacer(Modifier.width(6.dp))
                        ThinkingDots()   // U3：三点呼吸，替代转圈
                    }
                }
            }
        }

        // A3（§48）：批量确认卡 —— 删除必确认，一次 ≥3 条必确认；逐条可勾
        // U6：入场不硬闪
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.pendingAiActions.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(PanelBg).padding(12.dp)
            ) {
                Text(
                    tr("共 {0} 件事", vm.pendingAiActions.size),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                vm.pendingAiActions.forEachIndexed { i, a ->
                    Row(
                        Modifier.fillMaxWidth().plainClick {
                            if (i < vm.pendingChecked.size) vm.pendingChecked[i] = !vm.pendingChecked[i]
                        }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (vm.pendingChecked.getOrElse(i) { true }) "☑" else "☐",
                            fontSize = 15.sp,
                            color = if (a.isDelete) HolidayRed else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            a.label(), fontSize = 12.sp,
                            color = if (a.isDelete) HolidayRed else Ink
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("取消"), fontSize = 13.sp, color = GrayText,
                        modifier = Modifier.plainClick { vm.cancelPending() }.padding(8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        tr("执行勾选的"), fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .plainClick { vm.confirmPending() }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
        // 快捷指令
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 点 chip = 把话填进输入框（可再改），按发送才真正提交
            QuickChip(tr("今天安排")) { input = tr("今天有什么安排？"); chipAction = null }
            QuickChip(tr("明天安排")) { input = tr("明天有什么安排？"); chipAction = null }
            QuickChip(tr("本周总结")) {
                // 与 sendWeeklySummary 的 display 文案保持一致，输入框里看到什么、气泡里就是什么
                input = tr("帮我总结一下本周 📋")
                chipAction = { vm.sendWeeklySummary() }
            }
            QuickChip(tr("帮我规划明天")) {
                input = tr("根据我的日程和未完成任务，帮我把明天规划一下"); chipAction = null
            }
        }
        Hairline()

        // 输入栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                // 用户一动文字，chip 的专用动作就作废，退化成普通对话
                value = input, onValueChange = { input = it; chipAction = null },
                placeholder = { Text(tr("和小鹿说点什么…"), fontSize = 14.sp, color = Color(0xFFB9BBB9)) },
                colors = clearFieldColors(),
                maxLines = 4,
                // F1：回车即发送（对齐网页端 chatText 的 Enter 行为）
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(PanelBg)
            )
            Spacer(Modifier.width(8.dp))
            val canSend = input.isNotBlank() && !vm.aiBusy
            // U4：按压反馈 —— 与任务勾选同款 spring 手感
            val sendPressed = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isPressed by sendPressed.collectIsPressedAsState()
            val sendScale by androidx.compose.animation.core.animateFloatAsState(
                if (isPressed) 0.85f else 1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                label = "send"
            )
            IconButton(
                onClick = { if (canSend) send() },
                interactionSource = sendPressed,
                modifier = Modifier.size(44.dp)
                    .scale(sendScale)
                    .clip(CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else PanelBg)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, tr("发送"),
                    tint = if (canSend) Color.White else GrayText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(0.8.dp, Color(0xFFDDE0DD), RoundedCornerShape(10.dp))
            .plainClick(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Ink)
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, bubbleVm: LookaViewModel? = null, bubbleNav: NavHostController? = null) {
    when (m.role) {
        // U5（§52）动作卡片 + L1（§62）：点卡片直接打开该条目（查看/修改/删除一个入口）
        ROLE_ACTION -> MsgAppear {
            val canOpen = m.targetId > 0 && m.targetKind.isNotBlank() && bubbleNav != null
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .let { base ->
                        if (canOpen) base.plainClick { openActionTarget(bubbleVm, bubbleNav, m) }
                        else base
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    m.text, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (canOpen) Text("›", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            }
        }
        ROLE_USER -> MsgAppear(fromRight = true) { Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp, 10.dp, 4.dp, 10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(m.text, fontSize = 14.sp, color = Color.White, lineHeight = 21.sp)
            }
        } }
        else -> MsgAppear { Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 48.dp, top = 4.dp, bottom = 4.dp)
        ) {
            com.looka.app.ui.common.DeerBadge(26.dp)
            Spacer(Modifier.width(6.dp))
            // A1/A2（2026-08-21）：长按气泡 → 复制 / 存为笔记。
            // 复制是把内容带出产品，存笔记是把它留在手帐里 —— 后者才是沉淀。
            val ctx = LocalContext.current
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            var menu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp, 10.dp, 10.dp, 10.dp))
                        .border(0.8.dp, Color(0xFFE7EAE7), RoundedCornerShape(4.dp, 10.dp, 10.dp, 10.dp))
                        .background(Color.White)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        m.text, fontSize = 14.sp, lineHeight = 21.sp,
                        color = if (m.error) HolidayRed else Ink
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tr("复制"), fontSize = 14.sp) },
                        onClick = {
                            clip.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                            menu = false
                            com.looka.app.ui.common.toast(ctx, tr("已复制"))
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tr("存为笔记"), fontSize = 14.sp) },
                        onClick = {
                            menu = false
                            bubbleVm?.saveNote(-1L, tr("小鹿 · {0}", com.looka.app.util.Fmt.dateCn(com.looka.app.util.Fmt.today())), m.text) {
                                com.looka.app.ui.common.toast(ctx, tr("已存入笔记 🦌"))
                            }
                        }
                    )
                }
            }
        } }
    }
}

/**
 * 模型档位条：标准（不限次）/ 高级（Pro 不限量·免费档每月 60 次体验）。
 * 2026-08-21 决定：旗舰档下线、鹿角撤出 UI —— 对用户只有「次数」，没有代币概念。
 */


/** U1（§52）：消息进场 —— 全站动画语法（fadeIn 180 + 轻位移），用户从右、小鹿从左 */
@Composable
fun MsgAppear(fromRight: Boolean = false, content: @Composable () -> Unit) {
    val appeared = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = appeared,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
            androidx.compose.animation.slideInHorizontally(
                androidx.compose.animation.core.tween(180)
            ) { full -> if (fromRight) full / 6 else -full / 6 }
    ) { content() }
}

/** U3（§52）：思考态 —— 三个点依次呼吸，替代转圈（小鹿不该像个加载器） */
@Composable
fun ThinkingDots() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 200)
                ), label = "dot$i"
            )
            Box(
                Modifier.padding(horizontal = 2.dp).size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = a))
            )
        }
    }
}


/** L1（§62）：从动作卡片打开对应条目 —— 查看/修改/删除一个入口 */
private fun openActionTarget(vm: LookaViewModel?, nav: NavHostController?, m: ChatMsg) {
    if (vm == null || nav == null) return
    when (m.targetKind) {
        "event" -> vm.viewModelScope.launch {
            if (vm.prepareEditDraft(m.targetId, -1L)) nav.navigate("editor")
        }
        // 任务没有独立编辑页（清单页内编辑），跳到它所在的清单；笔记有 note/{id}
        "task" -> vm.viewModelScope.launch {
            val t = vm.tasks.value.find { it.id == m.targetId }
            nav.navigate(if (t != null && t.listUid.isNotBlank()) "list/${t.listUid}" else "home")
        }
        "note" -> nav.navigate("note/${m.targetId}")
    }
}

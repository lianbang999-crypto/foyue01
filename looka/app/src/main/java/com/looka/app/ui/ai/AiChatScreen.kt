@file:OptIn(ExperimentalFoundationApi::class)

package com.looka.app.ui.ai

import com.looka.app.ui.theme.LkIcons

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ai.AiAction
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.LinkBlue
import com.looka.app.ui.theme.LookaGreen
import com.looka.app.ui.theme.PanelBg
import com.looka.app.ui.theme.SearchBg
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.util.Fmt
import com.looka.app.vm.ChatMsg
import com.looka.app.vm.LookaViewModel
import com.looka.app.vm.ROLE_ACTION
import com.looka.app.vm.ROLE_USER
import androidx.compose.ui.platform.LocalContext
import com.looka.app.ai.AiClient
import com.looka.app.data.Prefs
import com.looka.app.net.Api
import com.looka.app.util.tr

/** 小鹿 AI 助手：带真实日程上下文的对话 + 自然语言创建 + 周总结（§126 按 AI-UX §4 全面对表） */
@Composable
fun AiChatScreen(vm: LookaViewModel, nav: NavHostController) {
    // §120 P4（E2）：场景入口带上下文进来 —— 预填一次性消费，全局入口不受影响

    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var clearDlg by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (vm.aiPrefill.isNotBlank()) { input = vm.aiPrefill; vm.aiPrefill = "" }
    }
    // E3（§79 / AC LOOKA-107）：快捷指令只填意图，发送由用户明确触发（一次对话扣 1 鹿角，
    // 四个 chip 挨得近，误触即扣钱）。chipAction 记住「这条 chip 要走的专用动作」——
    // 周总结需要注入本地真实日程/任务/日记，不能退化成普通文本；用户一改文字就作废。
    var chipAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // §123：随消息带一张图（相册选 → 压到 1024px/q80 → base64；识别计 3 🦌）
    var pendingImage by remember { mutableStateOf("") }
    val ctxImg = androidx.compose.ui.platform.LocalContext.current
    val pickChatImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val b64 = com.looka.app.util.AttachmentStore.toChatBase64(ctxImg, it)
            if (b64 == null) com.looka.app.ui.common.toast(ctxImg, tr("图片读取失败"))
            else pendingImage = b64
        }
    }
    val send = {
        val v = input.trim()
        val act = chipAction
        if (!vm.aiBusy && (act != null || v.isNotEmpty() || pendingImage.isNotEmpty())) {
            val img = pendingImage
            input = ""; chipAction = null; pendingImage = ""
            // §126 A3：第一次真正发送后，空态教学页永不再教（AI-UX 4.1）
            Prefs.setDeerTaught(ctx)
            if (act != null) act() else vm.sendChat(v, imageB64 = img)
        }
    }

    // §126 B3：只有**尾部**变化（新消息/思考态）才贴底滚动 —— 顶部翻历史不能把人拽回底部
    val lastKey = vm.chat.lastOrNull()?.let { "${it.dbId}:${it.role}:${it.text.length}" } ?: ""
    LaunchedEffect(lastKey, vm.aiBusy) {
        if (vm.chat.isNotEmpty() || vm.aiBusy) listState.animateScrollToItem(vm.chat.size + 2)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(tr("小鹿 AI"), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { clearDlg = true }) {
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

        // §53 M1：档位已下线（单模型走服务端）。自带 Key / 未登录时仍要说明状态。
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
            // §126 B3：顶部翻页（每页 50）。固定占位 item，索引稳定，贴底滚动的目标不漂
            item(key = "older") {
                if (vm.chatHasMore) Text(
                    tr("查看更早的记录"),
                    fontSize = 12.sp, color = LinkBlue,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().plainClick { vm.loadOlderChat() }
                        .padding(vertical = 8.dp)
                )
            }
            item(key = "empty") {
                // §126 A3（AI-UX 4.1）：空态 = 教学页；用过一次永不再教，之后只留极简一行
                if (vm.chat.isEmpty() && !vm.aiBusy) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        com.looka.app.ui.common.DeerBadge(48.dp)   // B3：随主题变色
                        Spacer(Modifier.height(10.dp))
                        if (!Prefs.deerTaught(ctx)) {
                            Text(tr("嗨，我是小鹿 🦌"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("说一句话，我帮你记进手帐"),
                                fontSize = 13.sp, color = GrayText,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                            )
                            listOf(
                                tr("明天下午 3 点开会，提前半小时提醒我"),
                                tr("今天有什么安排？"),
                                tr("记住：我不爱把会排在周一上午")
                            ).forEach { sample ->
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(0.8.dp, Color(0xFFE2E5E2), RoundedCornerShape(8.dp))
                                        .plainClick { input = sample; chipAction = null }
                                        .padding(horizontal = 14.dp, vertical = 11.dp)
                                ) { Text(sample, fontSize = 13.sp, color = Ink) }
                            }
                        } else {
                            Text(tr("和小鹿说点什么…"), fontSize = 13.sp, color = GrayText,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            itemsIndexed(vm.chat) { i, m ->
                // §126 B3：按天分段头（AI-UX §5：单一连续流，按天分段）
                val d = epochDayOf(m.createdAt)
                val prev = vm.chat.getOrNull(i - 1)
                if (prev == null || epochDayOf(prev.createdAt) != d) {
                    Text(
                        if (d == Fmt.today()) tr("今天") else Fmt.dateCn(d),
                        fontSize = 11.sp, color = GrayText,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                ChatBubble(m, vm, nav, isLast = i == vm.chat.size - 1)
            }
            item(key = "busy") {
                // §126 A5（AI-UX 4.4）：思考态 = 头像 + 呼吸点，无文字；>3s 才补一句
                if (vm.aiBusy) {
                    var showTxt by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3000); showTxt = true }
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.looka.app.ui.common.DeerBadge(20.dp)
                        Spacer(Modifier.width(8.dp))
                        ThinkingDots()
                        if (showTxt) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (vm.lastSendHadImage) tr("小鹿在看图…") else tr("小鹿在想…"),
                                fontSize = 12.sp, color = GrayText
                            )
                        }
                    }
                }
            }
        }

        // §126 A1（AI-UX 4.2）：草稿卡 ProposalCard —— AI 的脸面
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.pendingAiActions.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
        ) { ProposalCard(vm) }

        // §126 C4：主题草稿卡 —— 生成 → 预览 → 确认才应用（永不直接"已换好"）
        vm.pendingTheme?.let { (argb, name) -> ThemeDraftCard(vm, argb, name) }

        // §126 A2（4.2 规则 4）：执行后 5 秒撤销条
        if (vm.showUndoBar && vm.canUndo) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(PanelBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(LookaGreen))
                Spacer(Modifier.width(8.dp))
                Text(tr("已完成"), fontSize = 12.sp, color = GrayText, modifier = Modifier.weight(1f))
                Text(
                    tr("撤销"), fontSize = 13.sp, color = LinkBlue,
                    modifier = Modifier.plainClick { vm.undoLastBatch() }.padding(4.dp)
                )
            }
        }

        // §126 A5（4.4）：鹿角不足 —— 输入栏上方内联条（服务端文案直显），不弹窗、可关
        if (vm.antlerNotice.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(PanelBg)
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(vm.antlerNotice, fontSize = 12.sp, color = GrayText, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.antlerNotice = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(LkIcons.Close, tr("关闭"), tint = GrayText, modifier = Modifier.size(14.dp))
                }
            }
        }
        // 快捷指令
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 点 chip = 把话填进输入框（可再改），按发送才真正提交。
            // §120 P4（E1 三模式）：chip 按「记录 / 安排 / 回看」三个任务组织 ——
            // 一只小鹿三件事，入口先把心智立起来
            QuickChip(tr("记一件事")) { input = tr("明天下午 3 点开会，提前半小时提醒我"); chipAction = null }
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

        // §123：待发送的图预览（点 × 撤下）
        if (pendingImage.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val bmp = remember(pendingImage) {
                    runCatching {
                        val by = android.util.Base64.decode(pendingImage, android.util.Base64.NO_WRAP)
                        android.graphics.BitmapFactory.decodeByteArray(by, 0, by.size)
                    }.getOrNull()
                }
                bmp?.let {
                    Image(it.asImageBitmap(), null, modifier = Modifier.size(52.dp)
                        .clip(RoundedCornerShape(4.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                Spacer(Modifier.width(8.dp))
                Text(tr("识别这张图将使用 3 枚鹿角"), fontSize = 11.sp, color = GrayText,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { pendingImage = "" }) {
                    Icon(LkIcons.Close, tr("移除图片"), tint = GrayText, modifier = Modifier.size(16.dp))
                }
            }
        }
        // 输入栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = {
                pickChatImage.launch(androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.size(44.dp)) {
                Icon(LkIcons.Image, tr("发图片"), tint = GrayText, modifier = Modifier.size(21.dp))
            }
            TextField(
                // 用户一动文字，chip 的专用动作就作废，退化成普通对话
                value = input, onValueChange = { input = it; chipAction = null },
                placeholder = { Text(tr("和小鹿说点什么…"), fontSize = 14.sp, color = com.looka.app.ui.theme.PlaceholderText) },
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
                    // §126 A4（AI-UX 4.1）：发送键灰 → Ink 黑，不用主题色实心
                    .background(if (canSend) Ink else PanelBg)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, tr("发送"),
                    tint = if (canSend) Color.White else GrayText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // §126 B4：清空 = 物理删除（含图片）——先确认；顺带把「不上云」的定位说清楚
    if (clearDlg) com.looka.app.ui.common.ConfirmDialog(
        title = tr("清空对话？"),
        text = tr("聊天记录只在这台手机上。清空后将彻底删除（含图片），不可恢复。"),
        confirmText = tr("清空"),
        onConfirm = { clearDlg = false; vm.clearChat() },
        onDismiss = { clearDlg = false }
    )
}

private fun epochDayOf(ms: Long): Long =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * §126 A1（AI-UX 4.2）：草稿卡。硬规则：
 * ① 日期时间永远显示解析后的**绝对值**并加粗（终审防线）
 * ② 44dp 行热区；默认全勾、**删除类默认不勾**且行文字红
 * ③ 修改类 diff 行：旧值删除线灰 → 新值黑
 * ④ 执行钮 Ink 黑（4.6：accent 只在勾选选中/完成点/记忆卡三处）
 */
@Composable
private fun ProposalCard(vm: LookaViewModel) {
    val n = vm.pendingAiActions.size
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(0.8.dp, Color(0xFFE2E5E2), RoundedCornerShape(6.dp))
            .background(Color.White).padding(12.dp)
    ) {
        Text(
            tr("小鹿准备做 {0} 件事，确认后我再动手", n),
            fontSize = 13.sp, color = GrayText
        )
        Spacer(Modifier.height(6.dp))
        Hairline()
        vm.pendingAiActions.forEachIndexed { i, a ->
            val checked = vm.pendingChecked.getOrElse(i) { true }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).plainClick {
                    if (i < vm.pendingChecked.size) vm.pendingChecked[i] = !vm.pendingChecked[i]
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 勾选框（4.6：选中态是 accent 三个许可处之一）
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (checked) MaterialTheme.colorScheme.primary else Color.White)
                        .border(1.2.dp,
                            if (checked) MaterialTheme.colorScheme.primary else Color(0xFFC8CCC8),
                            RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (checked) Icon(LkIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    when {
                        a.type.endsWith("_event") -> LkIcons.Calendar
                        a.type.endsWith("_task") -> LkIcons.Check
                        a.type == "remember" -> LkIcons.Smile
                        else -> LkIcons.Book
                    },
                    null, tint = if (a.isDelete) HolidayRed else GrayText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(proposalLine(vm, a), fontSize = 13.sp, lineHeight = 19.sp,
                        color = if (a.isDelete) HolidayRed else Ink)
                    // 提醒子行 12sp 灰
                    if (a.type == "create_event") {
                        when {
                            a.remindAtMin >= 0 -> Text(tr("⏰ {0} 提醒你", Fmt.hm(a.remindAtMin)),
                                fontSize = 12.sp, color = GrayText)
                            a.remindMinBefore >= 0 -> Text(tr("⏰ 提前 {0} 分钟提醒", a.remindMinBefore),
                                fontSize = 12.sp, color = GrayText)
                        }
                        // §126 A1（AI-2）：冲突检测 —— 与当日现有日程时间重叠的警示
                        vm.conflictOf(a)?.let {
                            Text(it, fontSize = 11.sp, color = HolidayRed)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tr("取消"), fontSize = 13.sp, color = GrayText,
                modifier = Modifier.plainClick { vm.cancelPending() }.padding(8.dp)
            )
            Spacer(Modifier.weight(1f))
            val picked = vm.pendingChecked.count { it }
            Text(
                tr("执行勾选的 {0} 件", picked), fontSize = 13.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (picked > 0) Ink else Color(0xFFC8CCC8))
                    .plainClick { if (picked > 0) vm.confirmPending() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/** 草稿行富文本：绝对日期/时间 SemiBold（4.2 规则①）；修改类给 diff（规则③） */
@Composable
private fun proposalLine(vm: LookaViewModel, a: AiAction): androidx.compose.ui.text.AnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
    val strike = SpanStyle(color = GrayText, textDecoration = TextDecoration.LineThrough)
    return buildAnnotatedString {
        when (a.type) {
            "create_event" -> {
                val d = if (a.day >= 0) a.day else Fmt.today()
                withStyle(bold) {
                    append(Fmt.dateCn(d))
                    append(" ")
                    append(if (a.allDay || a.startMin < 0) tr("全天") else Fmt.hm(a.startMin))
                }
                append("  "); append(a.title)
            }
            "create_task" -> {
                if (a.day >= 0) { withStyle(bold) { append(Fmt.dateCn(a.day)) }; append("  ") }
                append(a.title)
            }
            "create_note" -> { append(tr("笔记")); append(" · "); append(a.title.ifBlank { a.content.take(12) }) }
            "update_event" -> {
                val old = vm.seriesAll.value.find { it.id == a.targetId }
                append(old?.title ?: "#${a.targetId}")
                if (a.title.isNotBlank() && old != null && a.title != old.title) {
                    append(" "); withStyle(strike) { append(old.title) }
                    append(" → "); withStyle(bold) { append(a.title) }
                }
                if (a.day >= 0 && (old == null || a.day != old.startDay)) {
                    append(" "); if (old != null) withStyle(strike) { append(Fmt.dateCn(old.startDay)) }
                    append(" → "); withStyle(bold) { append(Fmt.dateCn(a.day)) }
                }
                if (a.startMin >= 0 && (old == null || a.startMin != old.startMin)) {
                    append(" "); if (old != null && !old.allDay) withStyle(strike) { append(Fmt.hm(old.startMin)) }
                    append(" → "); withStyle(bold) { append(Fmt.hm(a.startMin)) }
                }
            }
            "update_task" -> {
                val old = vm.tasks.value.find { it.id == a.targetId }
                append(old?.title ?: "#${a.targetId}")
                if (a.done == 1) { append(" "); withStyle(bold) { append(tr("标记完成")) } }
                if (a.title.isNotBlank() && old != null && a.title != old.title) {
                    append(" "); withStyle(strike) { append(old.title) }
                    append(" → "); withStyle(bold) { append(a.title) }
                }
                if (a.day >= 0) { append(" → "); withStyle(bold) { append(Fmt.dateCn(a.day)) } }
            }
            "update_note" -> { append(tr("修改笔记")); append(" · ")
                append(vm.notes.value.find { it.id == a.targetId }?.title ?: "#${a.targetId}") }
            "delete_event" -> { append(tr("删除日程")); append(" · ")
                append(vm.seriesAll.value.find { it.id == a.targetId }?.title ?: "#${a.targetId}") }
            "delete_task" -> { append(tr("删除任务")); append(" · ")
                append(vm.tasks.value.find { it.id == a.targetId }?.title ?: "#${a.targetId}") }
            "delete_note" -> { append(tr("删除笔记")); append(" · ")
                append(vm.notes.value.find { it.id == a.targetId }?.title ?: "#${a.targetId}") }
            "remember" -> { append(tr("记住")); append(" · "); append(a.fact) }
            else -> append(a.label())
        }
    }
}

/** §126 C4：主题草稿卡 —— 迷你月历预览 + 应用/取消（复用照片主题机制） */
@Composable
private fun ThemeDraftCard(vm: LookaViewModel, argb: Long, name: String) {
    val ctx = LocalContext.current
    val tokens = remember(argb) {
        com.looka.app.util.PhotoTheme.tokensFrom(Color((0xFF000000L or argb).toInt()))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(0.8.dp, Color(0xFFE2E5E2), RoundedCornerShape(6.dp))
            .background(Color.White).padding(12.dp)
    ) {
        Text(
            if (name.isBlank()) tr("小鹿配了一套主题，看看喜欢吗？")
            else tr("小鹿配了一套主题「{0}」，看看喜欢吗？", name),
            fontSize = 13.sp, color = GrayText
        )
        Spacer(Modifier.height(8.dp))
        com.looka.app.ui.common.MiniThemePreview(tokens)
        Text(
            tr("文字与周末红蓝保持不变，阅读不受影响"),
            fontSize = 11.sp, color = GrayText, modifier = Modifier.padding(top = 6.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tr("不用了"), fontSize = 13.sp, color = GrayText,
                modifier = Modifier.plainClick { vm.cancelPendingTheme() }.padding(8.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                tr("应用"), fontSize = 13.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Ink)
                    .plainClick {
                        vm.applyPendingTheme()
                        com.looka.app.ui.common.toast(ctx, tr("已换上你的主题 🦌"))
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
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

/** §126 A2：动作反馈卡的状态点 —— emoji 前缀转语义色（✅✏️🎨🦌=绿 · 🗑=灰 · ⚠=红 · ↩=灰） */
private fun actionDot(text: String): Color = when {
    text.startsWith("⚠") -> Color(0xFFE0504A)
    text.startsWith("🗑") || text.startsWith("↩") -> Color(0xFF9AA09E)
    else -> LookaGreen
}

private val ACTION_EMOJI = Regex("""^[✅✏🗑⚠🦌↩🎨️⃣️\s]+""")

@Composable
private fun ChatBubble(m: ChatMsg, bubbleVm: LookaViewModel? = null, bubbleNav: NavHostController? = null, isLast: Boolean = false) {
    when (m.role) {
        // §126 A2（4.2 规则④）：动作结果卡 —— 白底细边 + 状态点 + 「打开」链接；不消失、不弹庆祝
        ROLE_ACTION -> MsgAppear {
            val canOpen = m.targetId > 0 && m.targetKind.isNotBlank() && bubbleNav != null
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(0.8.dp, Color(0xFFE2E5E2), RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .let { base ->
                        if (canOpen) base.plainClick { openActionTarget(bubbleVm, bubbleNav, m) }
                        else base
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(actionDot(m.text)))
                Spacer(Modifier.width(9.dp))
                Text(
                    m.text.replace(ACTION_EMOJI, "").ifBlank { m.text },
                    fontSize = 13.sp, lineHeight = 18.sp, color = Ink,
                    modifier = Modifier.weight(1f)
                )
                if (canOpen) Text(tr("打开"), fontSize = 12.sp, color = LinkBlue)
            }
        }
        ROLE_USER -> MsgAppear(fromRight = true) { Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
        ) {
            // §126 A4（4.1/4.6）：用户气泡**浅灰底**（主题色实心违反"accent 只出现在三处"）
            Column(
                Modifier.clip(RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp))
                    .background(SearchBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 带图消息回显：现场发的用 base64，历史记录从 files/chat/ 读
                val ctx = LocalContext.current
                val bmp = remember(m.imageB64, m.imageFile) {
                    runCatching {
                        if (m.imageB64.isNotBlank()) {
                            val by = android.util.Base64.decode(m.imageB64, android.util.Base64.NO_WRAP)
                            android.graphics.BitmapFactory.decodeByteArray(by, 0, by.size)
                        } else if (m.imageFile.isNotBlank())
                            com.looka.app.util.ChatStore.thumb(ctx, m.imageFile)
                        else null
                    }.getOrNull()
                }
                bmp?.let {
                    Image(
                        it.asImageBitmap(), null,
                        modifier = Modifier.padding(bottom = if (m.text.isNotBlank()) 6.dp else 0.dp)
                            .heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
                if (m.text.isNotBlank()) Text(m.text, fontSize = 15.sp, color = Ink, lineHeight = 22.sp)
            }
        } }
        else -> MsgAppear { Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 48.dp, top = 4.dp, bottom = 4.dp)
        ) {
            com.looka.app.ui.common.DeerBadge(20.dp)
            Spacer(Modifier.width(8.dp))
            // A1/A2（2026-08-21）：长按 → 复制 / 存为笔记。
            // §126 A4（4.1）：小鹿**无底色** —— 字直接写在纸上（原白卡+描边撤）
            val ctx = LocalContext.current
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            var menu by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.combinedClickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {}, onLongClick = { menu = true })
                ) {
                    Text(
                        m.text, fontSize = 15.sp, lineHeight = 23.sp,
                        color = if (m.error) HolidayRed else Ink
                    )
                    // §126 A5（4.4）：失败 → 内联「重试」（只挂在最后一条错误上）
                    if (m.error && isLast && bubbleVm != null) {
                        Text(
                            tr("重试"), fontSize = 13.sp, color = LinkBlue,
                            modifier = Modifier.plainClick { bubbleVm.retryLast() }
                                .padding(top = 4.dp, end = 8.dp)
                        )
                    }
                    // §126 A5（4.4）：线路降级尾注 —— 11sp 灰，不打扰
                    if (m.viaFallback && !m.error) {
                        Text(tr("本次走了备用线路"), fontSize = 11.sp, color = GrayText,
                            modifier = Modifier.padding(top = 2.dp))
                    }
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

/** U3（§52）→ §126 A5：思考态呼吸点。灰阶 —— 4.6：accent 不出现在加载指示上 */
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
                    .background(GrayText.copy(alpha = a))
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

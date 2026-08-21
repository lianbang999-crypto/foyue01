@file:OptIn(ExperimentalFoundationApi::class)

package com.looka.app.ui.ai

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
                Icon(Icons.Outlined.DeleteOutline, tr("清空对话"), tint = GrayText)
            }
            IconButton(onClick = { nav.navigate("subscription") }) {
                Icon(Icons.Outlined.Settings, tr("订阅与设置"), tint = GrayText)
            }
        }

        // 模型档位：标准（不限次）/ 更聪明（GPT）。
        // 不可用时要说明原因 —— 原来直接不渲染，用户会以为"功能没了"。
        when {
            Prefs.apiKey(ctx).isNotBlank() -> Text(
                tr("你填了自己的 AI Key，小鹿直连你的服务商，不提供档位切换"),
                fontSize = 11.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            !Api.authed(ctx) -> Text(
                tr("登录后可切换到更聪明的小鹿"),
                fontSize = 11.sp, color = GrayText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            else -> TierBar()
        }

        // 服务端在鹿角不足或上游故障时会回落标准模型 —— 如实告诉用户，不假装无事发生
        AiClient.lastFellBack?.let {
            Text(
                it, fontSize = 11.sp, color = HolidayRed,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
            )
        }

        // 对话不限次；只在接近当日公平使用上限时轻提示
        if (AiClient.lastRemaining in 0..19 && Prefs.apiKey(ctx).isBlank()) {
            Text(
                tr("今日剩余 {0} 次（每日限速防滥用，明天恢复）", AiClient.lastRemaining),
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
                        Image(
                            painterResource(R.drawable.ic_deer_badge), null,
                            modifier = Modifier.size(72.dp)
                        )
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
            items(vm.chat) { m -> ChatBubble(m, vm) }
            if (vm.aiBusy) {
                item {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painterResource(R.drawable.ic_deer_badge), null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tr("小鹿正在想…"), fontSize = 13.sp, color = GrayText)
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            strokeWidth = 2.dp, modifier = Modifier.size(13.dp), color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 快捷指令
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            QuickChip(tr("今天安排")) { vm.sendChat(tr("今天有什么安排？")) }
            QuickChip(tr("明天安排")) { vm.sendChat(tr("明天有什么安排？")) }
            QuickChip(tr("本周总结")) { vm.sendWeeklySummary() }
            QuickChip(tr("帮我规划明天")) { vm.sendChat(tr("根据我的日程和未完成任务，帮我把明天规划一下")) }
        }
        Hairline()

        // 输入栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(tr("和小鹿说点什么…"), fontSize = 14.sp, color = Color(0xFFB9BBB9)) },
                colors = clearFieldColors(),
                maxLines = 4,
                // F1：回车即发送（对齐网页端 chatText 的 Enter 行为）
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                    val v = input.trim()
                    if (v.isNotEmpty() && !vm.aiBusy) { input = ""; vm.sendChat(v) }
                }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(PanelBg)
            )
            Spacer(Modifier.width(8.dp))
            val canSend = input.isNotBlank() && !vm.aiBusy
            IconButton(
                onClick = {
                    if (canSend) {
                        vm.sendChat(input.trim())
                        input = ""
                    }
                },
                modifier = Modifier.size(44.dp).clip(CircleShape)
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
            .clip(RoundedCornerShape(15.dp))
            .border(0.8.dp, Color(0xFFDDE0DD), RoundedCornerShape(15.dp))
            .plainClick(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Ink)
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, bubbleVm: LookaViewModel? = null) {
    when (m.role) {
        ROLE_ACTION -> Box(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                m.text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        ROLE_USER -> Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(m.text, fontSize = 14.sp, color = Color.White, lineHeight = 21.sp)
            }
        }
        else -> Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 48.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Image(
                painterResource(R.drawable.ic_deer_badge), tr("小鹿"),
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(6.dp))
            // A1/A2（2026-08-21）：长按气泡 → 复制 / 存为笔记。
            // 复制是把内容带出产品，存笔记是把它留在手帐里 —— 后者才是沉淀。
            val ctx = LocalContext.current
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            var menu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                        .border(0.8.dp, Color(0xFFE7EAE7), RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                        .background(Color.White)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        m.text, fontSize = 14.sp, lineHeight = 21.sp,
                        color = if (m.error) HolidayRed else Ink
                    )
                }
                // 让"用没用上更聪明的小鹿"可见 —— 否则用户无从判断档位是否生效
                if (m.tier == "premium") {
                    Text(
                        tr("✨ 更聪明的小鹿"),
                        fontSize = 9.5.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = -2.dp)
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
        }
    }
}

/**
 * 模型档位条：标准（不限次）/ 高级（Pro 不限量·免费档每月 60 次体验）。
 * 2026-08-21 决定：旗舰档下线、鹿角撤出 UI —— 对用户只有「次数」，没有代币概念。
 */
@Composable
private fun TierBar() {
    val ctx = LocalContext.current
    var tier by remember { mutableStateOf(Prefs.aiTier(ctx).takeIf { it != "flagship" } ?: "premium") }
    val pro = Prefs.isPro(ctx)

    data class T(val id: String, val label: String, val hint: String)
    val tiers = listOf(
        T("standard", tr("标准"), tr("不限次")),
        T("premium", tr("更聪明"), if (pro) tr("Pro 不限量") else tr("每月 60 次体验"))
    )

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tiers.forEach { t ->
            val on = t.id == tier
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else PanelBg)
                    .plainClick { tier = t.id; Prefs.setAiTier(ctx, t.id) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    t.label, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary else Ink
                )
                Text(t.hint, fontSize = 9.sp, color = GrayText)
            }
        }
    }
}

package com.looka.app.ui.notes

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.MOOD_EMOJIS
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.SaveButton
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import kotlinx.coroutines.launch
import com.looka.app.util.tr

/** 日记编辑：每天一篇，心情 + 正文 + AI 润色 */
@Composable
fun DiaryEditScreen(vm: LookaViewModel, nav: NavHostController, day: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mood by remember { mutableIntStateOf(2) }
    var content by remember { mutableStateOf("") }
    var existed by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var polished by remember { mutableStateOf<String?>(null) }
    var privacyDlg by remember { mutableStateOf(false) }

    val draftKey = "diary_$day"
    LaunchedEffect(day) {
        vm.diaryOf(day)?.let {
            mood = it.mood.coerceIn(0, 4)
            content = it.content
            existed = true
        }
        // E2：恢复未保存的草稿（进程被杀 / 按返回没点保存）
        val d = com.looka.app.data.Prefs.draft(ctx, draftKey)
        if (d.isNotBlank() && d != content && d.length > content.length) {
            content = d
            toast(ctx, tr("已恢复未保存的草稿"))
        }
    }
    // 防抖落盘：日记的返回键不自动保存（写完要点保存才算数），草稿是安全网
    LaunchedEffect(content) {
        kotlinx.coroutines.delay(500)
        if (content.isNotBlank()) com.looka.app.data.Prefs.setDraft(ctx, draftKey, content)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(Fmt.dateFull(day), onBack = { nav.popBackStack() }) {
            if (existed) {
                IconButton(onClick = { delDlg = true }) {
                    Icon(Icons.Outlined.Delete, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                }
            }
            SaveButton(enabled = content.isNotBlank()) {
                vm.saveDiary(day, mood, content) {
                    com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
                    toast(ctx, tr("已保存"))
                    nav.popBackStack()
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        // 今日心情
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MOOD_EMOJIS.forEachIndexed { i, e ->
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(if (mood == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .border(
                            width = if (mood == i) 1.5.dp else 0.dp,
                            color = if (mood == i) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        )
                        .plainClick { mood = i },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 22.sp) }
            }
        }
        Hairline()

        TextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text(tr("今天过得怎么样？"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            colors = clearFieldColors(),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)
                .verticalScroll(rememberScrollState())
        )

        // AI 润色栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            if (aiBusy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
            }
            OutlinedButton(
                onClick = {
                    // S9：日记最私密，默认不上传；首次使用需明确授权
                    if (!com.looka.app.data.Prefs.aiDiaryUpload(ctx)) {
                        privacyDlg = true
                        return@OutlinedButton
                    }
                    scope.launch {
                        aiBusy = true
                        try {
                            polished = vm.aiPolish(content)
                        } catch (e: Exception) {
                            toast(ctx, e.message ?: tr("网络异常"))
                        } finally {
                            aiBusy = false
                        }
                    }
                },
                enabled = content.isNotBlank() && !aiBusy
            ) { Text(tr("✨ AI 润色"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        }
    }

    polished?.let { p ->
        AlertDialog(
            onDismissRequest = { polished = null },
            title = { Text(tr("润色结果"), fontSize = 17.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(p, fontSize = 14.sp, lineHeight = 22.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { content = p; polished = null }) {
                    Text(tr("替换原文"), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { polished = null }) { Text(tr("取消"), color = GrayText) }
            },
            containerColor = Color.White
        )
    }

    if (privacyDlg) AlertDialog(
        onDismissRequest = { privacyDlg = false },
        title = { Text(tr("允许上传日记正文？"), fontSize = 17.sp) },
        text = {
            Text(
                tr("AI 润色需要把这篇日记发送给大模型服务商处理。日记是最私密的内容，默认关闭。\n允许后可随时在「订阅与小鹿 AI」中关闭。"),
                fontSize = 13.sp, lineHeight = 21.sp
            )
        },
        confirmButton = {
            TextButton(onClick = {
                com.looka.app.data.Prefs.setAiDiaryUpload(ctx, true)
                privacyDlg = false
                toast(ctx, tr("已允许，再点一次「AI 润色」开始"))
            }) { Text(tr("允许并继续"), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = { privacyDlg = false }) { Text(tr("暂不"), color = GrayText) }
        },
        containerColor = Color.White
    )

    if (delDlg) ConfirmDialog(
        title = tr("删除这天的日记？"),
        onConfirm = {
            delDlg = false
            vm.deleteDiary(day) { nav.popBackStack() }
        },
        onDismiss = { delDlg = false }
    )
}

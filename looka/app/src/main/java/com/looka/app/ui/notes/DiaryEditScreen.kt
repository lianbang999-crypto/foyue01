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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.MOOD_EMOJIS
import com.looka.app.ui.common.safeBack
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
    var dateDlg by remember { mutableStateOf(false) }

    val draftKey = "diary_$day"
    LaunchedEffect(day) {
        vm.diaryOf(day)?.let {
            mood = it.mood.coerceIn(0, 4)
            content = it.content
            existed = true
        }
        // E2：恢复草稿。§104 之后正常退出已自动保存并清草稿，所以这里救的是**进程被杀**
        val d = com.looka.app.data.Prefs.draft(ctx, draftKey)
        // §90 W3（BUG-ND-003）：原条件是 d.length > content.length —— 用户把日记**改短、
        // 重写或清空**后草稿就永远恢复不了（那恰恰是最该救的几种情况）。改为「与已存内容不同」即可恢复。
        if (d.isNotBlank() && d != content) {
            content = d
            toast(ctx, tr("已恢复未保存的草稿"))
        }
    }
    // 防抖落盘：§104 起返回键已自动保存，草稿只兜**进程被杀**这一种情况
    //（正常退出走 saveAndBack，草稿会被清掉，不会在下次进来时误弹"已恢复草稿"）
    LaunchedEffect(content) {
        kotlinx.coroutines.delay(500)
        if (content.isNotBlank()) com.looka.app.data.Prefs.setDraft(ctx, draftKey, content)
    }

    // §104（用户拍板）：日记改**自动保存**，与笔记同一套心智 ——
    // 离开就存，不用惦记点保存。所以顶栏保持 `←`（返回父上下文）而不是 X（放弃）：
    // DNA 5.4 说「有明确 commit 的全页编辑器用 X」，那是给"不保存就丢"的模型定的，
    // 自动保存下 X 反而说谎。**这是有意偏离，不是漏做。**
    // 空内容守卫在 vm.saveDiary 里 —— 看一眼就退不会凭空生出空日记。
    fun saveAndBack() {
        vm.saveDiary(day, mood, content)
        com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
        nav.popBackStack()
    }
    androidx.activity.compose.BackHandler(enabled = true) { saveAndBack() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        // §103（DNA 8.2）：标题栏是**可点的日期 + ▾**，点开改期。
        // 实机图 83 就是 `← 8月22日(土) ▾`；我们此前是死标题，日子选错了只能删了重写。
        LookaTopBar(
            Fmt.dateFull(day) + " ▾",
            onBack = { saveAndBack() },
            onTitleClick = { dateDlg = true }
        ) {
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

        TextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text(tr("今天过得怎么样？"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            colors = clearFieldColors(),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)
                .verticalScroll(rememberScrollState())
        )

        // §77 N1：心情从顶部整行大圆圈挪到底部一行小图标。
        // 原来一进页面先横着五个 42dp 圆圈，等于开口就问「你今天心情如何」——
        // 与「安静等待用户先写」相悖。现在写完了顺手点一下，不点也能存。
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MOOD_EMOJIS.forEachIndexed { i, e ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (mood == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .plainClick { mood = i },
                    contentAlignment = Alignment.Center
                ) {
                    // 未选中的心情压暗，不与正文抢注意力
                    Text(e, fontSize = 17.sp, modifier = Modifier.alpha(if (mood == i) 1f else 0.4f))
                }
                Spacer(Modifier.width(2.dp))
            }
        }

        // AI 润色栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            if (aiBusy) {
                // T9（§70）：AI 干活的等待态用小鹿出场，不用裸转圈
                com.looka.app.ui.common.DeerLoading(tr("小鹿润色中…"))
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
                // §90 W5（BUG-ND-005）：按钮写着「允许并继续」，此前只设权限 + 提示「再点一次」——
                // 说了继续就要继续，别让用户为同一个意图点两次。
                com.looka.app.data.Prefs.setAiDiaryUpload(ctx, true)
                privacyDlg = false
                scope.launch {
                    aiBusy = true
                    try { polished = vm.aiPolish(content) }
                    catch (e: Exception) { toast(ctx, e.message ?: tr("网络异常")) }
                    finally { aiBusy = false }
                }
            }) { Text(tr("允许并继续"), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = { privacyDlg = false }) { Text(tr("暂不"), color = GrayText) }
        },
        containerColor = Color.White
    )

    if (dateDlg) com.looka.app.ui.common.LookaDatePicker(
        initialDay = day,
        onPick = { newDay ->
            dateDlg = false
            scope.launch {
                // 先把当前编辑的内容落到原日期，再整篇搬过去 —— 否则搬走的是上次保存的版本
                if (content.isNotBlank()) vm.saveDiary(day, mood, content)
                val err = vm.moveDiary(day, newDay)
                if (err != null) toast(ctx, err)
                else {
                    com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
                    nav.popBackStack()
                    nav.navigate("diary/$newDay")
                }
            }
        },
        onDismiss = { dateDlg = false }
    )

    if (delDlg) ConfirmDialog(
        title = tr("删除这天的日记？"),
        onConfirm = {
            delDlg = false
            vm.deleteDiary(day) { safeBack(nav) }
        },
        onDismiss = { delDlg = false }
    )
}

package com.looka.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.SaveButton
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.toast
import com.looka.app.ui.theme.GrayText
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/** 笔记编辑（id < 0 为新建），返回时自动保存 */
@Composable
fun NoteEditScreen(vm: LookaViewModel, nav: NavHostController, id: Long) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var delDlg by remember { mutableStateOf(false) }

    val draftKey = "note_$id"
    LaunchedEffect(id) {
        if (id >= 0) vm.note(id)?.let {
            title = it.title
            content = it.content
        }
        // E2：进程被杀后回来，恢复没保存成的内容（正常保存路径会清掉草稿，走不到这里）
        val d = com.looka.app.data.Prefs.draft(ctx, draftKey)
        if (d.isNotBlank() && d != content) {
            if (content.isBlank()) { content = d; toast(ctx, tr("已恢复未保存的草稿")) }
        }
    }
    // 防抖草稿：停止输入 500ms 后落盘，killed 也不丢
    LaunchedEffect(title, content) {
        kotlinx.coroutines.delay(500)
        if (content.isNotBlank() || title.isNotBlank())
            com.looka.app.data.Prefs.setDraft(ctx, draftKey, content)
    }

    fun saveAndBack() {
        vm.saveNote(id, title, content) { }
        com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
        nav.popBackStack()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(tr("笔记"), onBack = { saveAndBack() }) {
            if (id >= 0) {
                IconButton(onClick = { delDlg = true }) {
                    Icon(Icons.Outlined.Delete, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                }
            }
            SaveButton(enabled = title.isNotBlank() || content.isNotBlank()) {
                vm.saveNote(id, title, content) { toast(ctx, tr("已保存")) }
                com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
                nav.popBackStack()
            }
            Spacer(Modifier.width(8.dp))
        }
        TextField(
            value = title, onValueChange = { title = it },
            placeholder = { Text(tr("标题"), fontSize = 17.sp, color = Color(0xFFB9BBB9)) },
            textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            colors = clearFieldColors(), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Hairline()
        TextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text(tr("开始写…"), fontSize = 15.sp, color = Color(0xFFB9BBB9)) },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            colors = clearFieldColors(),
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
        )
    }

    if (delDlg) ConfirmDialog(
        title = tr("删除这条笔记？"),
        onConfirm = {
            delDlg = false
            vm.deleteNote(id) { nav.popBackStack() }
        },
        onDismiss = { delDlg = false }
    )
}

package com.looka.app.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.data.NOTE_LIST_GREY
import com.looka.app.data.NoteList
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.rowClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel

// §86 C3：笔记清单的两个轻量层（V014 [B] 的 LIST_CHANGE / CREATE_LIST 两态）。
// 工程要点不在视觉而在所有权：同一时刻只有一个 interactive modal，IME 只有一个 owner，
// 且不论走哪条路，背景里 Note 编辑器的 title/body draft 都不能被动过。

/** LIST_CHANGE：选已有清单直接回填并关闭；「新建清单」把自己让位给创建层 */
@Composable
fun NoteListChangeDialog(
    lists: List<NoteList>,
    current: String,
    onPick: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择清单"), fontSize = 17.sp) },
        text = {
            // V014 Scalability：清单多了要能滚，且「新建」行始终可发现
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                lists.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().rowClick { onPick(l.uid) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColorDot(parseHex(l.colorHex), 11.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(l.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (l.uid == current) {
                            Icon(Icons.Default.Check, tr("已选中"), tint = Ink, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().rowClick(onCreate).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, tint = GrayText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(tr("新建清单"), fontSize = 15.sp, color = GrayText)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

/**
 * 编辑页里的「新建清单」。
 *
 * §101：**色盘已拆**。这里原本有一整块 48 色选择，而「笔记页 → 新建清单」那条没有 ——
 * 同一件事两条路径长得不一样，用户正是看到这里能选色才提的。
 * 对齐 Lifebear：笔记清单**无颜色**，统一灰色文档图标。
 * 保留的是这条路径独有的两件事：**重名/空名报错** 与 **返回新建的 uid**（建完要选中它）。
 */
@Composable
fun NoteListCreateDialog(
    vm: LookaViewModel,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("新建清单"), fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; err = null },
                    label = { Text(tr("清单名")) },
                    singleLine = true,
                    isError = err != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                err?.let { Text(it, fontSize = 12.sp, color = com.looka.app.ui.theme.HolidayRed) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.addNoteList(name, NOTE_LIST_GREY) { uid ->
                        if (uid == null) err = tr("名字空着或重名了")
                        else onCreated(uid)
                    }
                },
                enabled = name.isNotBlank()
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

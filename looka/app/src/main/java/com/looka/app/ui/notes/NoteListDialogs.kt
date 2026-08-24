package com.looka.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.data.LIST_PALETTE
import com.looka.app.data.NoteList
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.onColor
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
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
 * CREATE_LIST：本层是唯一 IME owner（自动聚焦文本框）。
 * V014 Create List commit —— Save 先落持久 List 再回调；**失败（空名/重名）不关闭**，
 * 让用户就地改，而不是把输入吞掉再让他重来。
 */
@Composable
fun NoteListCreateDialog(
    vm: LookaViewModel,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(LIST_PALETTE[30]) }
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
                Spacer(Modifier.width(0.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    LIST_PALETTE.chunked(8).forEach { rowColors ->
                        Row {
                            rowColors.forEach { hex ->
                                val c = parseHex(hex)
                                androidx.compose.foundation.layout.Box(
                                    Modifier.padding(2.dp).size(26.dp).clip(CircleShape)
                                        .background(c).plainClick { color = hex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (hex == color) {
                                        Icon(
                                            Icons.Default.Check, null, tint = onColor(c),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.addNoteList(name, color) { uid ->
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

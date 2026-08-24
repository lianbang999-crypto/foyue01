package com.looka.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.Prefs
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel

/**
 * §98 E2：笔记清单二级页（实机图 79）—— `← 清单名 ⋮`，**没有搜索框**。
 *
 * 这是 Lifebear 笔记两级结构的第二级：ノート tab 给清单，点进来才是该清单的笔记。
 * 条目顺序是 **日期 / 标题 / 正文**（日期在最上），条目下通栏 hairline。
 *
 * ⚠️ 有意偏离：实机这一页仍带底部 tab 栏，新建笔记走中央 ＋。我们的二级页是全屏、
 * 底栏不在，所以把 ＋ 放到顶栏 —— 不加的话进了清单就没法建笔记，只能退出去。
 */
@Composable
fun NoteListScreen(vm: LookaViewModel, nav: NavHostController, uid: String) {
    val ctx = LocalContext.current
    val lists by vm.noteLists.collectAsState()
    val all by vm.notes.collectAsState()
    val list = lists.find { it.uid == uid }
    if (list == null) {
        LaunchedEffect(uid) { nav.popBackStack() }
        return
    }

    var menu by remember { mutableStateOf(false) }
    var renameDlg by remember { mutableStateOf(false) }
    var sortDlg by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(Prefs.noteSort(ctx)) }

    val notes = remember(all, uid, sort) {
        val inList = all.filter { it.listUid == uid }
        when (sort) {
            0 -> inList.sortedByDescending { it.updatedAt }            // 更新日
            2 -> inList.sortedBy { it.title.ifBlank { "￿" } }     // 笔记名（无标题排最后）
            else -> inList.sortedByDescending { it.createdAt }         // 创建日（默认，同实机）
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(list.name, onBack = { nav.popBackStack() }) {
            IconButton(onClick = { vm.noteListSel = uid; nav.navigate("note/-1") }) {
                Icon(Icons.Default.Add, tr("新建笔记"), tint = Ink, modifier = Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, tr("更多"), tint = Ink)
                }
                // 实机 ⋮ 只有「編集 / 並び替え」两项，锚定右上、无遮罩
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                    DropdownMenuItem(text = { Text(tr("编辑")) }, onClick = { menu = false; renameDlg = true })
                    DropdownMenuItem(text = { Text(tr("排序")) }, onClick = { menu = false; sortDlg = true })
                    // 实机这里没有删除项（13 张图里未见）。我们保留 —— 建了清单却删不掉是死路。
                    // 这是有意多出的一项，不是没对齐。
                    if (list.deletable) DropdownMenuItem(
                        text = { Text(tr("删除清单"), color = HolidayRed) },
                        onClick = { menu = false; delDlg = true }
                    )
                }
            }
        }

        if (notes.isEmpty()) {
            EmptyDeer(tr("「{0}」里还没有笔记", list.name), hint = tr("点右上角 ＋ 写第一条"))
        } else {
            LazyColumn {
                items(notes, key = { it.id }) { n ->
                    NoteRow(n) { nav.navigate("note/${n.id}") }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    if (renameDlg) NoteListNameDialog(
        title = tr("重命名清单"), initial = list.name, confirmLabel = tr("重命名"),
        onConfirm = { n -> vm.renameNoteList(list, n, list.colorHex); renameDlg = false },
        onDismiss = { renameDlg = false }
    )

    if (sortDlg) SortDialog(
        current = sort,
        onApply = { sort = it; Prefs.setNoteSort(ctx, it); sortDlg = false },
        onDismiss = { sortDlg = false }
    )

    if (delDlg) ConfirmDialog(
        title = tr("删除清单「{0}」？", list.name),
        text = tr("清单里的笔记会移入默认清单，不会丢"),
        onConfirm = { delDlg = false; vm.deleteNoteList(list); nav.popBackStack() },
        onDismiss = { delDlg = false }
    )
}

/**
 * 排序对话框（实机图 76）：radio 三档 + `取消` / `应用`。
 * 注意与待办的「並び替え」**不是一回事** —— 待办那个是整页拖拽排序，这个是选排序规则。
 * 默认选中「创建日」，与实机一致。
 */
@Composable
private fun SortDialog(current: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    var sel by remember { mutableIntStateOf(current) }
    val labels = listOf(tr("更新日"), tr("创建日"), tr("笔记名"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("排序"), fontSize = 17.sp) },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = sel == i, onClick = { sel = i })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sel == i, onClick = { sel = i })
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(sel) }) { Text(tr("应用")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

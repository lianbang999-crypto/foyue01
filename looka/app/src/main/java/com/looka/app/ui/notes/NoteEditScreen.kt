package com.looka.app.ui.notes

import com.looka.app.ui.theme.LkIcons

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.ui.common.safeBack
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.rowClick
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
    // §86 C3：清单归属。新建时继承笔记页当前筛选（"全部" → 默认清单）
    var listUid by remember { mutableStateOf(vm.noteListSel.ifEmpty { com.looka.app.data.NOTE_LIST_DEFAULT }) }
    var noteUid by remember { mutableStateOf("") }   // §117 A：已存在笔记的稳定键（附件宿主）
    var listDlg by remember { mutableStateOf(false) }
    var createDlg by remember { mutableStateOf(false) }
    val lists by vm.noteLists.collectAsState()

    val draftKey = "note_$id"
    // §114 P12：草稿从「只存正文」升级为 JSON{t,c,l} —— 原来标题、清单不入草稿
    //（只输标题被杀=全丢），且旧笔记有正文时新草稿永不恢复（条件是 content.isBlank()）。
    // 现在：草稿与已存内容**不同**即恢复；把正文删短/删空同样受保护。
    // 兼容：老版本草稿是纯文本，parse 失败按纯 content 处理。
    LaunchedEffect(id) {
        vm.ensureNoteListDefault()
        if (id >= 0) vm.note(id)?.let {
            title = it.title
            content = it.content
            listUid = it.listUid
            noteUid = it.uid
        }
        val d = com.looka.app.data.Prefs.draft(ctx, draftKey)
        if (d.isNotBlank()) {
            val o = runCatching { org.json.JSONObject(d) }.getOrNull()
            if (o != null) {
                // 新格式：全量恢复（含"标题被清空"这种编辑 —— dt 就是空）
                val dt = o.optString("t"); val dc = o.optString("c"); val dl = o.optString("l")
                if (dt != title || dc != content) {
                    title = dt; content = dc
                    if (dl.isNotBlank()) listUid = dl
                    toast(ctx, tr("已恢复未保存的草稿"))
                }
            } else if (d != content && content.isBlank()) {
                // 老格式（纯正文）：维持旧行为，只在正文为空时救
                content = d
                toast(ctx, tr("已恢复未保存的草稿"))
            }
        }
    }
    // 防抖草稿：停止输入 500ms 后落盘，killed 也不丢。
    // §114 P12：**空内容也落盘**（内容为空的 JSON ≠ 无草稿）—— 用户把正文清空后被杀，
    // 回来不该看到删除前的旧文假装无事发生。
    LaunchedEffect(title, content, listUid) {
        kotlinx.coroutines.delay(500)
        val j = org.json.JSONObject()
            .put("t", title).put("c", content).put("l", listUid).toString()
        com.looka.app.data.Prefs.setDraft(ctx, draftKey, j)
    }

    fun saveAndBack() {
        vm.saveNote(id, title, content, listUid) { }
        com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
        nav.popBackStack()
    }

    // §90 W1（BUG-ND-001）：此前只有顶栏返回走 saveAndBack，**系统返回键直接 pop** ——
    // 同一个「返回」动作两套行为，从系统返回退出就把没保存的内容吞了。
    androidx.activity.compose.BackHandler(enabled = true) { saveAndBack() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding()
    ) {
        LookaTopBar(tr("笔记"), onBack = { saveAndBack() }) {
            if (id >= 0) {
                IconButton(onClick = { delDlg = true }) {
                    Icon(LkIcons.Trash, tr("删除"), tint = GrayText, modifier = Modifier.size(20.dp))
                }
            }
            SaveButton(enabled = title.isNotBlank() || content.isNotBlank()) {
                vm.saveNote(id, title, content, listUid) { toast(ctx, tr("已保存")) }
                com.looka.app.data.Prefs.clearDraft(ctx, draftKey)
                nav.popBackStack()
            }
            Spacer(Modifier.width(8.dp))
        }
        TextField(
            value = title, onValueChange = { title = it },
            placeholder = { Text(tr("标题"), fontSize = 17.sp, color = com.looka.app.ui.theme.PlaceholderText) },
            textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            colors = clearFieldColors(), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Hairline()
        // §86 C3：清单行 —— 摘要 + 点开选择层（V014：List 是原生容器，不是编辑器里的字符串）
        Row(
            Modifier.fillMaxWidth().rowClick { listDlg = true }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr("清单"), fontSize = 13.sp, color = GrayText)
            Spacer(Modifier.width(14.dp))
            ColorDot(parseHex(lists.find { it.uid == listUid }?.colorHex ?: "#5C6670"), 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                lists.find { it.uid == listUid }?.name ?: tr("我的笔记"),
                fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ChevronRight, null,
                tint = Color(0xFFC9CCC9), modifier = Modifier.size(18.dp)
            )
        }
        Hairline()
        // §117 A：附件区。新建笔记要先保存才有稳定 uid —— 保存后再进来可加图（v1 限制，候选池有"新建即预生成 uid"）
        if (noteUid.isNotBlank()) {
            com.looka.app.ui.common.AttachmentSection(vm, "note", noteUid)
            Hairline()
        }
        TextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text(tr("记下想法…"), fontSize = 15.sp, color = com.looka.app.ui.theme.PlaceholderText) },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            colors = clearFieldColors(),
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
        )
    }

    // §86 C3（V014 Dialog layering）：ListChange 与 CreateList 是 **suspend/replace**，
    // 不叠成两层可响应的 modal —— 打开创建层时把选择层收起，取消再放回来。
    if (listDlg) NoteListChangeDialog(
        lists = lists, current = listUid,
        onPick = { listUid = it; listDlg = false },
        onCreate = { listDlg = false; createDlg = true },
        onDismiss = { listDlg = false }
    )
    if (createDlg) NoteListCreateDialog(
        vm,
        // V014 Create List commit：由笔记发起 → 建好自动选中，直接回编辑器
        onCreated = { uid -> listUid = uid; createDlg = false },
        onDismiss = { createDlg = false; listDlg = true }
    )

    if (delDlg) ConfirmDialog(
        title = tr("删除这条笔记？"),
        onConfirm = {
            delDlg = false
            vm.deleteNote(id) { safeBack(nav) }
        },
        onDismiss = { delDlg = false }
    )
}

package com.looka.app.ui.notes

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.MOOD_EMOJIS
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.clearFieldColors
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.common.rowClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.PanelBg
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.looka.app.util.tr

/**
 * 笔记·日记 Tab（Lifebear ノート&日記 的对应物）
 *
 * §90 N1：撤掉此前那排清单 chips —— 那是我照搬待办页自创的，Lifebear 的笔记列表页
 * 没有清单横排（V014 实机：切清单在**编辑页**顶部的 chip 下拉里做）。清单功能保留，形态改回。
 * §90 S1/S2/S3：搜索框按实机重做 —— 静置态是入口，点开进整页搜索态。
 */
@Composable
fun NotesDiaryScreen(vm: LookaViewModel, nav: NavHostController) {
    // §77 N9：seg 提到 VM —— 中央 ＋ 要按它决定建笔记还是建日记
    val seg = vm.notesSeg
    var q by rememberSaveable { mutableStateOf("") }
    // §90 S3：搜索是**两态**的（实机图 73/75）—— 静置态只是入口，点开才进搜索模式
    var searching by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // §98 H2：改用全站唯一的 LookaSearchBar —— 这段两态代码原来写在这里，
        // 现在搬进 ui/common，待办页和日历页复用同一份
        com.looka.app.ui.common.LookaSearchBar(
            query = q, onQueryChange = { q = it },
            active = searching, onActiveChange = { searching = it },
            // §93 E5：两个 tab 文案不同，且**两态同文案**
            placeholder = if (seg == 0) tr("笔记名、正文") else tr("正文"),
            trailing = {
                // §71 A：AI 全站入口（用户拍板）
                IconButton(onClick = { nav.navigate("aiChat") }) {
                    com.looka.app.ui.common.DeerBadge(24.dp)
                }
            }
        )
        if (searching) Hairline()
        // §90 S2：tab 两个**等宽平分**，选中 = 黑粗体 + **整宽粗下划线**（实机图 71）
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            SegTab(tr("笔记"), seg == 0, Modifier.weight(1f)) { vm.notesSeg = 0 }
            SegTab(tr("日记"), seg == 1, Modifier.weight(1f)) { vm.notesSeg = 1 }
        }
        Hairline()
        if (seg == 0) NotesList(vm, nav, q.trim()) else DiaryList(vm, nav, q.trim(), searching)
    }
}

@Composable
private fun SegTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().plainClick(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom
    ) {
        Text(
            label, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Ink else GrayText,
            modifier = Modifier.weight(1f).wrapContentHeight()
        )
        // 下划线宽 = 选中 tab 整宽（实测占屏宽 50.0%，正好是等分后的一格）；
        // §93 E4：厚度 3dp → **2dp**（实测 1.9dp）
        Box(
            Modifier.fillMaxWidth().height(2.dp)
                .background(if (selected) Ink else Color.Transparent)
        )
    }
}

/**
 * §93 E1：ノート tab 显示的是**清单列表**，不是笔记列表（实机图 82/85）。
 *
 * §90 我撤掉了自创的 chips 横排 —— 那一步对；但接着把这里做成「显示全部笔记」，
 * 落点错了：Lifebear 的笔记是**两级**的（清单 → 笔记），点清单才进二级页看笔记。
 * 这不是样式差异，是信息架构差异。
 *
 * 规格（1dp=3.125px 标定实测）：icon 24dp @ 左 16dp，文字左缘 **72dp**（Material 带图标列表标准值），
 * 行距 53dp，计数右对齐且**为 0 时不显示**，**行间无分隔线**，默认清单用收件箱图标。
 */
@Composable
private fun NotesList(vm: LookaViewModel, nav: NavHostController, q: String) {
    val all by vm.notes.collectAsState()
    val lists by vm.noteLists.collectAsState()
    var createDlg by remember { mutableStateOf(false) }
    // 回到 tab 级清单列表就把「新笔记归属」清空 —— 否则从清单 A 退出来后，
    // 底部中央 ＋ 建的笔记还会偷偷落进 A
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.noteListSel = "" }

    // 搜索态是**跨清单**的：这时候还给清单列表没有意义，直接出命中的笔记。
    // （实机的搜索是整页独立模式，我们做的是实时过滤 —— §93 已写明这条故意不对齐）
    if (q.isNotBlank()) {
        val hit = all.filter { it.title.contains(q, true) || it.content.contains(q, true) }
        if (hit.isEmpty()) { EmptyDeer(tr("没找到「{0}」", q), hint = tr("换个词试试")); return }
        LazyColumn {
            items(hit, key = { it.id }) { n -> NoteRow(n, lists.find { it.uid == n.listUid }?.name) { nav.navigate("note/${n.id}") } }
        }
        return
    }

    val countByList = remember(all) { all.groupingBy { it.listUid }.eachCount() }
    LazyColumn {
        items(lists, key = { it.uid }) { l ->
            Row(
                Modifier.fillMaxWidth().rowClick { nav.navigate("noteList/${l.uid}") }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (l.uid == com.looka.app.data.NOTE_LIST_DEFAULT)
                        Icons.Outlined.Inbox else Icons.Outlined.Description,
                    null, tint = GrayText, modifier = Modifier.size(24.dp)
                )
                // 文字左缘 72dp = 16(边距) + 24(图标) + 32(间隔)
                Spacer(Modifier.width(32.dp))
                Text(l.name, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val c = countByList[l.uid] ?: 0
                if (c > 0) Text("$c", fontSize = 14.sp, color = GrayText)
            }
            // 实机行间无 hairline（实测纯白）—— 这里也不画
        }
        item {
            Row(
                Modifier.fillMaxWidth().rowClick { createDlg = true }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = Ink, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(32.dp))
                Text(tr("新建清单"), fontSize = 16.sp, color = Ink)
            }
        }
        item { Spacer(Modifier.height(70.dp)) }
    }

    if (createDlg) NoteListNameDialog(
        title = tr("新建清单"), initial = "", confirmLabel = tr("保存"),
        onConfirm = { n -> vm.addNoteList(n, "#5C6670"); createDlg = false },
        onDismiss = { createDlg = false }
    )
}

/** 笔记条目：实机顺序是 **日期 / 标题 / 正文**（日期在最上），全部左缘 16dp，条目下通栏 hairline */
@Composable
fun NoteRow(n: com.looka.app.data.Note, listName: String? = null, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("M/d", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth().rowClick(onClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt.format(Date(n.updatedAt)), fontSize = 12.sp, color = GrayText)
            if (listName != null) {
                Spacer(Modifier.width(8.dp))
                Text(listName, fontSize = 12.sp, color = Color(0xFFB9BBB9),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            n.title.ifBlank { tr("无标题") }, fontSize = 16.sp, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (n.content.isNotBlank()) {
            Text(
                n.content.replace("\n", " "), fontSize = 14.sp, color = GrayText,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    Hairline()   // 实机是**通栏**（左右缩进均为 0）
}

/** 新建 / 重命名清单：标题 + 下划线输入框 + 取消/确定；**空名时确定置灰**（实机图 81） */
@Composable
fun NoteListNameDialog(
    title: String, initial: String, confirmLabel: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it },
                placeholder = { Text(tr("清单名")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(tr("取消"), color = GrayText)
            }
        },
        containerColor = Color.White
    )
}

@Composable
private fun DiaryList(vm: LookaViewModel, nav: NavHostController, q: String, searching: Boolean) {
    val all by vm.diaries.collectAsState()
    // §77 N6：日记只有正文可搜（心情是图标不是文字）
    val diaries = if (q.isBlank()) all else all.filter { it.content.contains(q, true) }
    val today = Fmt.today()
    // 搜索态下不插「写今天的日记」那一行 —— 它不是搜索结果
    val hasToday = searching || all.any { it.day == today }

    // §90 N3（实机图 71）：按月分组 —— 灰色月份小标题 + 每条「大号日期数字 / 星期」竖排在左，
    // 标题在右。此前是「心情 emoji + 日期 + 正文摘要」的紧凑行，那是我们自己的排法。
    val grouped = remember(diaries) {
        diaries.groupBy { Fmt.d(it.day).let { dt -> dt.year * 100 + dt.monthValue } }
            .toList().sortedByDescending { it.first }
    }

    LazyColumn {
        if (searching && q.isBlank()) {
            item {
                Text(
                    tr("输入关键词，搜索日记正文"),
                    fontSize = 13.sp, color = GrayText,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@LazyColumn
        }
        if (!hasToday) {
            item {
                Row(
                    Modifier.fillMaxWidth().rowClick { nav.navigate("diary/$today") }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Edit, tr("编辑"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("写今天的日记"), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                Hairline()
            }
        }
        grouped.forEach { (ym, items) ->
            item(key = "m$ym") {
                Text(
                    tr("{0}年{1}月", ym / 100, ym % 100),
                    fontSize = 13.sp, color = GrayText,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)
                )
            }
            itemsIndexed(items, key = { _, d -> d.id }) { idx, d ->
                val dt = Fmt.d(d.day)
                Row(
                    Modifier.fillMaxWidth().rowClick { nav.navigate("diary/${d.day}") }
                        // §93 E7：条目间距放宽。实测同月相邻 pitch ≈119dp、条目本体才 41dp ——
                        // 那多半是给照片缩略图预留的（Lifebear 日记支持插图）。我们暂无照片，
                        // 照抄 119dp 会显得空，取 80dp。**这是取舍不是复刻。**
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // 左：大号日期 + 星期（竖排）。宽 56dp 使正文左缘落在 72dp —— 与清单列表同一根竖线
                    Column(Modifier.width(56.dp)) {
                        Text("${dt.dayOfMonth}", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(Fmt.week(dt.dayOfWeek.value), fontSize = 12.sp, color = Ink)
                    }
                    Text(
                        d.content.replace("\n", " ").ifBlank { tr("（无正文）") },
                        fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    // §93 E6：撤掉右侧心情 emoji —— 实机日记列表**不显示任何心情**，那是我自创的
                }
                // §93 E7：分隔线只在**同月相邻**两条之间；左缩进 72dp、**右到边缘**。
                // 跨月不画（靠月标题上方的空白分隔）
                if (idx < items.lastIndex) Hairline(Modifier.padding(start = 72.dp))
            }
        }
        if (diaries.isEmpty()) {
            item {
                if (q.isNotBlank()) EmptyDeer(tr("没找到「{0}」", q), hint = tr("换个词试试"))
                else EmptyDeer(tr("一天一页，从今天开始记录吧"), hint = tr("点下面中间的 ＋ 写今天"))
            }
        }
    }
}

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
        if (searching) {
            // ── 激活态：顶栏变「← + 裸输入框」，无底色、无圆角（实机图 73/75）──
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
            androidx.activity.compose.BackHandler(enabled = true) { searching = false; q = "" }
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { searching = false; q = "" }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        tr("返回"), tint = Ink
                    )
                }
                TextField(
                    value = q, onValueChange = { q = it },
                    // 激活态的 placeholder 才写全能搜什么（实机：ノート名、本文 / タスク名、メモなど）
                    placeholder = {
                        Text(
                            if (seg == 0) tr("笔记名、正文") else tr("日记正文"),
                            fontSize = 16.sp, color = Color(0xFFB9BBB9)
                        )
                    },
                    textStyle = TextStyle(fontSize = 16.sp),
                    singleLine = true,
                    colors = clearFieldColors(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
            }
            Hairline()
        } else {
            // ── 静置态：灰底小圆角入口（实机 38dp 高 / 4dp 圆角），小鹿在同一行右端 ──
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).height(38.dp)
                        .clip(RoundedCornerShape(4.dp)).background(PanelBg)
                        .rowClick { searching = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Search, tr("搜索"), tint = GrayText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    // §90 S1：静置态实机只写「本文」两个字，不写长句
                    Text(tr("正文"), fontSize = 14.sp, color = Color(0xFFB9BBB9))
                }
                // §71 A：AI 全站入口（用户拍板）
                IconButton(onClick = { nav.navigate("aiChat") }) {
                    com.looka.app.ui.common.DeerBadge(24.dp)
                }
            }
        }
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
        // 整宽粗下划线（此前是 22dp 短横 —— 自创的）
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .background(if (selected) Ink else Color.Transparent)
        )
    }
}

@Composable
private fun NotesList(vm: LookaViewModel, nav: NavHostController, q: String) {
    val all by vm.notes.collectAsState()
    val lists by vm.noteLists.collectAsState()
    // §86 C2：先按当前清单收窄，再按搜索命中标题或正文（§77 N6）
    val scoped = if (vm.noteListSel.isEmpty()) all else all.filter { it.listUid == vm.noteListSel }
    val notes = if (q.isBlank()) scoped else scoped.filter {
        it.title.contains(q, true) || it.content.contains(q, true)
    }
    if (notes.isEmpty()) {
        // §86 C5：空态按当前作用域说话，别在筛了清单之后说「还没有笔记」
        val curName = lists.find { it.uid == vm.noteListSel }?.name
        when {
            q.isNotBlank() -> EmptyDeer(tr("没找到「{0}」", q), hint = tr("换个词试试"))
            curName != null -> EmptyDeer(tr("「{0}」里还没有笔记", curName), hint = tr("点下面中间的 ＋ 写第一条"))
            else -> EmptyDeer(tr("还没有笔记"), hint = tr("点下面中间的 ＋ 写第一条"))
        }
        return
    }
    val fmt = SimpleDateFormat(tr("M月d日 HH:mm"), Locale.CHINA)
    LazyColumn {
        items(notes, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().rowClick { nav.navigate("note/${n.id}") }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    n.title.ifBlank { tr("无标题") }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (n.content.isNotBlank()) {
                    Text(
                        n.content.replace("\n", " "), fontSize = 13.sp, color = GrayText,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(fmt.format(Date(n.updatedAt)), fontSize = 11.sp, color = Color(0xFFB9BBB9))
            }
            Hairline()
        }
    }
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
            items(items, key = { it.id }) { d ->
                val dt = Fmt.d(d.day)
                Row(
                    Modifier.fillMaxWidth().rowClick { nav.navigate("diary/${d.day}") }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // 左：大号日期 + 星期（竖排）
                    Column(
                        Modifier.width(56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${dt.dayOfMonth}", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(Fmt.week(dt.dayOfWeek.value), fontSize = 11.sp, color = GrayText)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.content.replace("\n", " ").ifBlank { tr("（无正文）") },
                            fontSize = 15.sp, color = Ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 心情保留为右侧识别符（列表页的心情是识别符，不是输入门槛 —— §77 N1）
                    Text(MOOD_EMOJIS[d.mood.coerceIn(0, 4)], fontSize = 18.sp)
                }
                Hairline(Modifier.padding(start = 86.dp))
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

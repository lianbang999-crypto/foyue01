package com.looka.app.ui.notes

import androidx.compose.material3.MaterialTheme

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

/** 笔记·日记 Tab（Lifebear 底部第四格 Note&Diary 的对应物） */
@Composable
fun NotesDiaryScreen(vm: LookaViewModel, nav: NavHostController) {
    // §77 N9：seg 提到 VM —— 中央 ＋ 要按它决定建笔记还是建日记
    val seg = vm.notesSeg
    var q by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // §77 N6/N8：搜索框即页首（不再有标题行），小鹿收进搜索行右端 —— 同一行，零额外高度。
        // §77 N8：顶栏那个 ＋ 撤掉，新建统一交给中央 ＋（Lifebear 也只有中央一个入口）。
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = q, onValueChange = { q = it },
                // §77 N7：placeholder 代替说明文字，直接写清能搜到什么
                placeholder = {
                    Text(
                        if (seg == 0) tr("搜索笔记名、正文") else tr("搜索日记正文"),
                        fontSize = 14.sp, color = Color(0xFFB9BBB9)
                    )
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, tr("搜索"), tint = GrayText, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                colors = clearFieldColors(),
                modifier = Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(22.dp)).background(PanelBg)
            )
            // §71 A：AI 全站入口（用户拍板）—— 从独立顶栏挪到搜索行右端
            IconButton(onClick = { nav.navigate("aiChat") }) {
                com.looka.app.ui.common.DeerBadge(24.dp)
            }
        }
        // §77 N6：tab 移到搜索框下方（对齐 Lifebear 图5 的层序）
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegTab(tr("笔记"), seg == 0) { vm.notesSeg = 0 }
            Spacer(Modifier.width(24.dp))
            SegTab(tr("日记"), seg == 1) { vm.notesSeg = 1 }
        }
        Hairline()
        if (seg == 0) NotesList(vm, nav, q.trim()) else DiaryList(vm, nav, q.trim())
    }
}

@Composable
private fun SegTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.plainClick(onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label, fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Ink else GrayText
        )
        Box(
            Modifier.padding(top = 3.dp).width(22.dp).height(2.dp)
                .background(if (selected) Ink else Color.Transparent)
        )
    }
}

@Composable
private fun NotesList(vm: LookaViewModel, nav: NavHostController, q: String) {
    val all by vm.notes.collectAsState()
    // §77 N6：搜索命中标题或正文
    val notes = if (q.isBlank()) all else all.filter {
        it.title.contains(q, true) || it.content.contains(q, true)
    }
    if (notes.isEmpty()) {
        if (q.isNotBlank()) EmptyDeer(tr("没找到「{0}」", q), hint = tr("换个词试试"))
        // §77 N8：＋ 已从顶栏撤掉，空态指向底部中央 ＋
        else EmptyDeer(tr("还没有笔记"), hint = tr("点下面中间的 ＋ 写第一条"))
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
private fun DiaryList(vm: LookaViewModel, nav: NavHostController, q: String) {
    val all by vm.diaries.collectAsState()
    // §77 N6：日记只有正文可搜（心情是图标不是文字）
    val diaries = if (q.isBlank()) all else all.filter { it.content.contains(q, true) }
    val today = Fmt.today()
    // 搜索态下不插「写今天的日记」那一行 —— 它不是搜索结果
    val hasToday = q.isNotBlank() || all.any { it.day == today }

    LazyColumn {
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
        items(diaries, key = { it.id }) { d ->
            Row(
                Modifier.fillMaxWidth().rowClick { nav.navigate("diary/${d.day}") }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(MOOD_EMOJIS[d.mood.coerceIn(0, 4)], fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Fmt.dateFull(d.day), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        d.content.replace("\n", " "), fontSize = 13.sp, color = GrayText,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Hairline()
        }
        if (diaries.isEmpty()) {
            item {
                if (q.isNotBlank()) EmptyDeer(tr("没找到「{0}」", q), hint = tr("换个词试试"))
                else EmptyDeer(tr("一天一页，从今天开始记录吧"), hint = tr("点下面中间的 ＋ 写今天"))
            }
        }
    }
}

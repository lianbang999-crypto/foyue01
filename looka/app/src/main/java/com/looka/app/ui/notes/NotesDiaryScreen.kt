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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.MOOD_EMOJIS
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.looka.app.util.tr

/** 笔记·日记 Tab（Lifebear 底部第四格 Note&Diary 的对应物） */
@Composable
fun NotesDiaryScreen(vm: LookaViewModel, nav: NavHostController) {
    var seg by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegTab(tr("笔记"), seg == 0) { seg = 0 }
            Spacer(Modifier.width(24.dp))
            SegTab(tr("日记"), seg == 1) { seg = 1 }
            Spacer(Modifier.weight(1f))
            // §71 A：AI 全站入口（用户拍板）
            IconButton(onClick = { nav.navigate("aiChat") }) {
                com.looka.app.ui.common.DeerBadge(24.dp)
            }
            IconButton(onClick = {
                if (seg == 0) nav.navigate("note/-1")
                else nav.navigate("diary/${Fmt.today()}")
            }) { Icon(Icons.Default.Add, tr("新建"), tint = Ink) }
        }
        Hairline()
        if (seg == 0) NotesList(vm, nav) else DiaryList(vm, nav)
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
private fun NotesList(vm: LookaViewModel, nav: NavHostController) {
    val notes by vm.notes.collectAsState()
    if (notes.isEmpty()) {
        EmptyDeer(tr("还没有笔记"), hint = tr("点右上角 ＋ 写第一条 ↗"))
        return
    }
    val fmt = SimpleDateFormat(tr("M月d日 HH:mm"), Locale.CHINA)
    LazyColumn {
        items(notes, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().plainClick { nav.navigate("note/${n.id}") }
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
private fun DiaryList(vm: LookaViewModel, nav: NavHostController) {
    val diaries by vm.diaries.collectAsState()
    val today = Fmt.today()
    val hasToday = diaries.any { it.day == today }

    LazyColumn {
        if (!hasToday) {
            item {
                Row(
                    Modifier.fillMaxWidth().plainClick { nav.navigate("diary/$today") }
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
                Modifier.fillMaxWidth().plainClick { nav.navigate("diary/${d.day}") }
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
            item { EmptyDeer(tr("一天一页，从今天开始记录吧"), hint = tr("点右上角 ＋ 写今天 ↗")) }
        }
    }
}

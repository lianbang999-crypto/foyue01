package com.looka.app.ui.search

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.FREQ_NONE
import com.looka.app.ui.calendar.SectionLabel
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.EmptyDeer
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.HolidayRed
import com.looka.app.ui.theme.Ink
import com.looka.app.util.Fmt
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/** 搜索（CAL-070 搜索入口）：搜日程与任务 */
@Composable
fun SearchScreen(vm: LookaViewModel, nav: NavHostController) {
    val series by vm.seriesAll.collectAsState()
    val tasksList by vm.tasks.collectAsState()
    val cats by vm.categories.collectAsState()
    val notesList by vm.notes.collectAsState()
    val diariesList by vm.diaries.collectAsState()
    var query by remember { mutableStateOf("") }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val q = query.trim()
    val hitEvents = remember(q, series) {
        if (q.isBlank()) emptyList()
        else series.filter {
            it.title.contains(q, true) || it.location.contains(q, true) || it.memo.contains(q, true)
        }.take(50)
    }
    val hitTasks = remember(q, tasksList) {
        if (q.isBlank()) emptyList()
        else tasksList.filter { it.title.contains(q, true) || it.memo.contains(q, true) }.take(50)
    }
    val hitNotes = remember(q, notesList) {
        if (q.isBlank()) emptyList()
        else notesList.filter { it.title.contains(q, true) || it.content.contains(q, true) }.take(50)
    }
    val hitDiaries = remember(q, diariesList) {
        if (q.isBlank()) emptyList()
        else diariesList.filter { it.content.contains(q, true) }.take(50)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("搜索"), onBack = { nav.popBackStack() })
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(tr("搜索日程 / 任务 / 笔记 / 日记"), color = Color(0xFFB9BBB9)) },
            leadingIcon = { Icon(Icons.Outlined.Search, tr("搜索"), tint = GrayText) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { kb?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn {
            if (hitEvents.isNotEmpty()) {
                item { SectionLabel(tr("日程")) }
                items(hitEvents, key = { "e${it.id}" }) { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .plainClick {
                                nav.navigate("detail/${s.id}/${vm.nextOccurrenceDay(s)}")
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColorDot(
                            parseHex(cats.find { it.id == s.categoryId }?.colorHex ?: "#9AA0A6"),
                            10.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Fmt.dateCn(s.startDay) +
                                        (if (s.freq != FREQ_NONE) tr(" · 重复") else "") +
                                        (if (s.location.isNotBlank()) " · ${s.location}" else ""),
                                fontSize = 12.sp, color = GrayText, maxLines = 1
                            )
                        }
                    }
                    Hairline()
                }
            }
            if (hitTasks.isNotEmpty()) {
                item { SectionLabel(tr("任务")) }
                items(hitTasks, key = { "t${it.id}" }) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (t.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = if (t.done) MaterialTheme.colorScheme.primary else GrayText,
                            modifier = Modifier.size(20.dp).plainClick { vm.toggleTask(t) }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t.title, fontSize = 15.sp,
                            color = if (t.done) GrayText else Ink,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (t.dueDay >= 0) {
                            Text(
                                Fmt.dateCn(t.dueDay), fontSize = 12.sp,
                                color = if (!t.done && t.dueDay < Fmt.today()) HolidayRed else GrayText
                            )
                        }
                    }
                    Hairline()
                }
            }
            if (hitNotes.isNotEmpty()) {
                item { SectionLabel(tr("笔记")) }
                items(hitNotes, key = { "n${it.id}" }) { n ->
                    Column(
                        Modifier.fillMaxWidth()
                            .plainClick { nav.navigate("note/${n.id}") }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(n.title.ifBlank { tr("无标题") }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            n.content.replace("\n", " "), fontSize = 12.sp, color = GrayText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Hairline()
                }
            }
            if (hitDiaries.isNotEmpty()) {
                item { SectionLabel(tr("日记")) }
                items(hitDiaries, key = { "d${it.id}" }) { d ->
                    Column(
                        Modifier.fillMaxWidth()
                            .plainClick { nav.navigate("diary/${d.day}") }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(Fmt.dateFull(d.day), fontSize = 14.sp)
                        Text(
                            d.content.replace("\n", " "), fontSize = 12.sp, color = GrayText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Hairline()
                }
            }
            if (q.isNotBlank() && hitEvents.isEmpty() && hitTasks.isEmpty() && hitNotes.isEmpty() && hitDiaries.isEmpty()) {
                item { EmptyDeer(tr("没有找到「{0}」相关内容", q)) }
            }
        }
    }
}

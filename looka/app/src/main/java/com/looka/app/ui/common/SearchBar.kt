package com.looka.app.ui.common

import com.looka.app.ui.theme.LkIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.ui.theme.SearchBg
import com.looka.app.util.tr

/**
 * §98 H1：全站**唯一**一套搜索条。
 *
 * 此前全项目有 4 套搜索：App 的独立搜索页（日程+任务+笔记+日记）、笔记/日记页内两态，
 * 网页的 `#noteSearch` 与 `#diarySearch`。笔记和日记被重复覆盖了两遍，
 * 而网页压根搜不了日程和任务 —— 四套代码，四种口径。
 *
 * 现在统一成：**每个 tab 页内搜自己的东西，不跳页**（用户拍板，Lifebear 也是各 tab 分开搜）。
 *
 * 两态形态按实机（1dp = 3.156px 标定）：
 * - 静置态：灰底 **44dp** 高、**4dp** 小圆角，只是个入口
 * - 激活态：顶栏换成 `←` + 裸输入框，自动聚焦，键盘回车键是「搜索」，返回键退出并清空
 * - **两态 placeholder 同文案**（§93 E5 已证：实机激活态不会换成更长的句子）
 *
 * @param active   是否处于激活态（调用方持有，因为退出搜索通常还要复位列表滚动等）
 * @param trailing 静置态右端的附加内容（小鹿 AI 入口就挂在这里，零额外高度）
 */
@Composable
fun LookaSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    if (active) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        BackHandler(enabled = true) { onActiveChange(false); onQueryChange("") }
        Row(
            modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onActiveChange(false); onQueryChange("") }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回"), tint = Ink)
            }
            TextField(
                value = query, onValueChange = onQueryChange,
                placeholder = { Text(placeholder, fontSize = 16.sp, color = Color(0xFFB9BBB9)) },
                textStyle = TextStyle(fontSize = 16.sp),
                singleLine = true,
                colors = clearFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
        }
    } else {
        Row(
            modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(4.dp)).background(SearchBg)
                    .rowClick { onActiveChange(true) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(LkIcons.Search, tr("搜索"), tint = GrayText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(placeholder, fontSize = 14.sp, color = Color(0xFFB9BBB9))
            }
            trailing?.invoke()
        }
    }
}

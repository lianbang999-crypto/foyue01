package com.looka.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.looka.app.ui.theme.Ink
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel

/**
 * §99 I7：**应用级撤销条**。
 *
 * 原来 `UndoBar(vm)` 只挂在待办的清单详情页一处（审计 BUG-TL-009）——
 * 从星标页、未来7天、笔记页删掉东西，撤销入口根本不出现。
 * 左滑删除铺开之后这条必须先补：划一下东西没了却找不到撤销，比不给手势还糟。
 *
 * 挂在 NavHost 之上，所以**任何页面**删除都能看见同一条。
 */
@Composable
fun UndoHost(vm: LookaViewModel) {
    val u = vm.undo ?: return
    Box(Modifier.fillMaxSize().zIndex(80f), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.navigationBarsPadding()
                .padding(bottom = 18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr("已删除「{0}」", u.label), fontSize = 13.sp, color = Color.White)
            Spacer(Modifier.width(14.dp))
            Text(
                tr("撤销"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9FD39A),
                modifier = Modifier.plainClick { vm.doUndo() }
            )
        }
    }
}

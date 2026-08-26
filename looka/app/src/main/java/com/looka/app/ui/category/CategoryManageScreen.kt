package com.looka.app.ui.category

import androidx.compose.material3.TextField
import com.looka.app.ui.common.DlgTitle
import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.looka.app.data.LIST_PALETTE
import com.looka.app.data.Category
import com.looka.app.ui.common.listRowGestures
import com.looka.app.ui.common.ColorDot
import com.looka.app.ui.common.ConfirmDialog
import com.looka.app.ui.common.Hairline
import com.looka.app.ui.common.LookaTopBar
import com.looka.app.ui.common.parseHex
import com.looka.app.ui.common.plainClick
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.Ink
import com.looka.app.vm.LookaViewModel
import com.looka.app.util.tr

/**
 * 颜色/分类管理（规格 CAL-041）：
 * 创建、改名换色、显隐、排序；未分类不可删除；删除时日程归入未分类。
 */
@Composable
fun CategoryManageScreen(vm: LookaViewModel, nav: NavHostController) {
    val cats by vm.categories.collectAsState()
    var editCat by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var delCat by remember { mutableStateOf<Category?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        LookaTopBar(tr("分类管理"), onBack = { nav.popBackStack() }) {
            TextButton(onClick = { creating = true }) {
                Text(tr("新建颜色"), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
        }
        // §99 I6：**上移/下移两个按钮删掉**，改长按拖拽（用户拍板：排序按钮全部删除）。
        // 行尾那个删除图标也删了 —— 删除统一走左滑。
        val reorder = com.looka.app.ui.common.rememberReorderState(cats.map { it.uid })
        val byUid = remember(cats) { cats.associateBy { it.uid } }
        val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 52.dp.toPx() }
        LazyColumn {
            items(reorder.order.toList(), key = { it }) { cuid ->
                val c = byUid[cuid] ?: return@items
                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                com.looka.app.ui.common.SwipeDeleteBackdrop(Modifier.matchParentSize())
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .listRowGestures(
                            uid = cuid, state = reorder, rowHeightPx = rowPx,
                            onReorder = { order -> vm.reorderCategories(order) },
                            onDelete = if (c.deletable) ({ delCat = c }) else null
                        )
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(parseHex(c.colorHex), 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        c.name, fontSize = 15.sp,
                        color = if (c.visible) Ink else GrayText,
                        modifier = Modifier.weight(1f).plainClick { editCat = c }
                            .padding(vertical = 14.dp)
                    )
                    // 显隐（AC-014：隐藏后日历不显示该分类日程）
                    Switch(
                        checked = c.visible,
                        onCheckedChange = { vm.updateCategory(c.copy(visible = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
                }
                Hairline()
            }
            item {
                Text(
                    tr("· 点击名称可改名换色\n· 长按可拖动排序 · 左滑删除\n· 隐藏后该分类日程不在日历显示\n· 删除分类时，其日程自动归入「未分类」"),
                    fontSize = 11.sp, color = GrayText, lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (creating) CategoryEditDialog(
        cat = null,
        onSave = { name, color -> vm.addCategory(name, color); creating = false },
        onDismiss = { creating = false }
    )
    editCat?.let { c ->
        CategoryEditDialog(
            cat = c,
            onSave = { name, color ->
                vm.updateCategory(c.copy(name = name, colorHex = color))
                editCat = null
            },
            onDismiss = { editCat = null }
        )
    }
    delCat?.let { c ->
        ConfirmDialog(
            title = tr("删除分类「{0}」？", c.name),
            text = tr("该分类下的日程将归入「未分类」"),
            onConfirm = { vm.deleteCategory(c); delCat = null },
            onDismiss = { delCat = null }
        )
    }
}

@Composable
private fun CategoryEditDialog(
    cat: Category?,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(cat) { mutableStateOf(cat?.name ?: "") }
    var color by remember(cat) { mutableStateOf(cat?.colorHex ?: LIST_PALETTE[31]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DlgTitle(if (cat == null) tr("新建颜色") else tr("编辑分类")) },
        text = {
            // 48 色 8 行较高，小屏上让色盘可滚
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text(tr("分类名称，如：工作")) },
                    singleLine = true,
                    colors = com.looka.app.ui.common.dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.padding(top = 12.dp))
                // 2026-08-21：分类选色与清单统一用 Lifebear 48 色盘（原 14 色小盘退役）
                LIST_PALETTE.chunked(6).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        row.forEach { hex ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .background(parseHex(hex))
                                    .border(
                                        width = if (color == hex) 2.5.dp else 0.8.dp,
                                        color = if (color == hex) Ink
                                                else if (parseHex(hex).luminance() > 0.82f) Color(0xFFD8D8D8)
                                                else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .plainClick { color = hex }
                            )
                        }
                        repeat(7 - row.size) { Spacer(Modifier.size(32.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), color) },
                enabled = name.isNotBlank()
            ) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = GrayText) } },
        containerColor = Color.White
    )
}

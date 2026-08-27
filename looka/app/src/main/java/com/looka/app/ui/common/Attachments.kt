package com.looka.app.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.looka.app.data.Attachment
import com.looka.app.ui.theme.GrayText
import com.looka.app.ui.theme.LkIcons
import com.looka.app.util.AttachmentStore
import com.looka.app.util.tr
import com.looka.app.vm.LookaViewModel
import java.io.File

/**
 * §117 A：附件区（图片 v1）—— 缩略图横滚 + 「拍照 / 相册」添加。
 *
 * Lifebear 在编辑器与详情顶栏都有相机位（图 8/10）；我们把入口放进内容区
 * 是有意偏离：附件与内容同屏可见，不用记住顶栏图标的含义。
 * 元数据先同步、字节后补是常态 —— 本地没字节的格子先给灰底占位，
 * 进入可见时自动向云端拉（ensureAttachmentLocal）。
 */
@Composable
fun AttachmentSection(vm: LookaViewModel, ownerType: String, ownerUid: String) {
    val ctx = LocalContext.current
    val list by vm.attachmentsOf(ownerType, ownerUid).collectAsState(initial = emptyList())
    var preview by remember { mutableStateOf<Attachment?>(null) }
    var refresh by remember { mutableStateOf(0) }

    // 相机：拍到 cache 临时文件，成功后走统一导入（压缩+落盘+建记录）。
    // §118 P0：getUriForFile 包 runCatching —— v42 因 cache 根未声明在这里
    // 组合期抛异常，四个页面全崩。现在根已声明；再有任何 provider 配置意外，
    // 降级为隐藏拍照入口（相册仍可用），不再拖垮整页。
    val capFile = remember { File(ctx.cacheDir, "capture_${ownerUid.hashCode()}.jpg") }
    val capUri = remember {
        runCatching {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", capFile)
        }.getOrNull()
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = capUri
        if (ok && u != null) vm.addAttachment(ownerType, ownerUid, u) { done ->
            if (!done) toast(ctx, tr("图片读取失败"))
        }
    }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { vm.addAttachment(ownerType, ownerUid, it) { done ->
            if (!done) toast(ctx, tr("图片读取失败"))
        } }
    }
    var addMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(tr("图片"), fontSize = 12.sp, color = GrayText)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            list.forEach { a ->
                key(a.uid, refresh) {
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF0F1F0))
                            .plainClick { preview = a },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = if (a.fileName.isNotBlank())
                            remember(a.fileName, refresh) { AttachmentStore.thumb(ctx, a.fileName) } else null
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), tr("图片"),
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // 云端有、本地还没拉到：占位 + 触发按需下载
                            androidx.compose.runtime.LaunchedEffect(a.uid) {
                                vm.ensureAttachmentLocal(a) { refresh++ }
                            }
                            Icon(LkIcons.Image, null, tint = GrayText, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            // 添加格：虚线观感用细边框代替（Compose 无原生虚线 border，细灰框足够克制）
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFD8DBD8), RoundedCornerShape(4.dp))
                    .plainClick { addMenu = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(LkIcons.Camera, tr("添加图片"), tint = GrayText, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (addMenu) {
        // 单选弹层：拍照 / 相册（select-and-close，对齐 §113 弹窗语法）
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addMenu = false },
            title = { DlgTitle(tr("添加图片")) },
            text = {
                Column {
                    if (capUri != null) {
                        Row(
                            Modifier.fillMaxWidth().rowClick {
                                addMenu = false
                                runCatching { takePicture.launch(capUri) }
                                    .onFailure { toast(ctx, tr("无法打开相机")) }
                            }.padding(vertical = 14.dp)
                        ) { Text(tr("拍照"), fontSize = 16.sp) }
                        Hairline()
                    }
                    Row(
                        Modifier.fillMaxWidth().rowClick {
                            addMenu = false
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.padding(vertical = 14.dp)
                    ) { Text(tr("从相册选择"), fontSize = 16.sp) }
                }
            },
            confirmButton = {},
            containerColor = Color.White
        )
    }

    preview?.let { a ->
        Dialog(
            onDismissRequest = { preview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).plainClick { preview = null }) {
                val bmp = if (a.fileName.isNotBlank())
                    remember(a.fileName) { AttachmentStore.full(ctx, a.fileName) } else null
                if (bmp != null) Image(
                    bmp.asImageBitmap(), null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().align(Alignment.Center)
                ) else Text(
                    tr("图片下载中…"), color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                Row(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp)) {
                    IconButton(onClick = {
                        vm.deleteAttachment(a); preview = null
                        toast(ctx, tr("已删除图片"))
                    }) { Icon(LkIcons.Trash, tr("删除"), tint = Color.White) }
                    IconButton(onClick = { preview = null }) {
                        Icon(LkIcons.Close, tr("关闭"), tint = Color.White)
                    }
                }
            }
        }
    }
}

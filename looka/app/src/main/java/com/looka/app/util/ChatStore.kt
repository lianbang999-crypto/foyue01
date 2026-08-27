package com.looka.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * §126 B2：聊天图片的本地文件仓（AI-UX §5：base64 不入库，字节落 files/chat/）。
 * 与 AttachmentStore 分开：附件走同步上云，聊天图**只在这台手机上**，
 * 生命周期跟聊天记录走（滚动清理/清空对话时连文件删）。
 */
object ChatStore {

    private fun dir(c: Context): File = File(c.filesDir, "chat").apply { mkdirs() }

    fun fileOf(c: Context, name: String): File = File(dir(c), name)

    /** 把已压好的 base64（识图同源 1024/q80）落盘，返回文件名；失败 "" */
    fun saveBase64(c: Context, b64: String): String = runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val name = "c${System.currentTimeMillis()}.jpg"
        fileOf(c, name).writeBytes(bytes)
        name
    }.getOrDefault("")

    /** 历史消息回显：按目标边长采样解码（气泡缩略图，内存友好） */
    fun thumb(c: Context, name: String, edgePx: Int = 512): Bitmap? = runCatching {
        val f = fileOf(c, name)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= edgePx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun delete(c: Context, name: String) {
        if (name.isNotBlank()) fileOf(c, name).delete()
    }

    /** 清空对话：整目录物理删除 */
    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }
}

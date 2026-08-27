package com.looka.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * §117 A：附件字节的本地仓库（图片 v1）。
 *
 * 所有图片进来先压一道（最长边 ≤2048、JPEG 85）再落盘 ——
 * 相机原图动辄 8-12MB，不压缩的话 R2 流量、手机存储、同步时长三头吃亏；
 * 2048px 对手帐场景（缩略图 + 全屏看）绰绰有余。
 * 文件名即 `<uid>.jpg`，与 Attachment.fileName 一致；目录在 app 私有区，卸载即清。
 */
object AttachmentStore {

    private fun dir(c: Context): File =
        File(c.filesDir, "attachments").apply { mkdirs() }

    fun fileOf(c: Context, fileName: String): File = File(dir(c), fileName)

    /** 从相册/相机 uri 读入 → 压缩 → 存为 <uid>.jpg。返回 (文件名, 字节数)；失败 null */
    fun importImage(c: Context, uri: Uri, uid: String): Pair<String, Long>? = runCatching {
        // 两趟读：先只解尺寸算采样率，再按采样解码 —— 直接整张解 12MB 原图会 OOM
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 4096) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scaled = scaleDown(bmp, 2048)
        val f = fileOf(c, "$uid.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) bmp.recycle()
        "$uid.jpg" to f.length()
    }.getOrNull()

    private fun scaleDown(b: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(b.width, b.height)
        if (edge <= maxEdge) return b
        val r = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt(), (b.height * r).toInt(), true)
    }

    /** 读缩略图（内存友好：按目标边长采样） */
    fun thumb(c: Context, fileName: String, edgePx: Int = 256): Bitmap? = runCatching {
        val f = fileOf(c, fileName)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= edgePx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun full(c: Context, fileName: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(fileOf(c, fileName).path)
    }.getOrNull()

    fun delete(c: Context, fileName: String) {
        if (fileName.isNotBlank()) fileOf(c, fileName).delete()
    }

    /** §123：对话识图用 —— 压到 1024px/q80 转 base64（识别够用，1024px 一般 <300KB） */
    fun toChatBase64(c: Context, uri: android.net.Uri): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scaled = scaleDown(bmp, 1024)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bmp) bmp.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()
}

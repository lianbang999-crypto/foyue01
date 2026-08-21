package com.looka.app.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.looka.app.BuildConfig
import com.looka.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内自更新（五批，内部分发）：
 * version.json（Worker 静态资源）→ 比对 versionCode → DownloadManager 下载 R2 上的 APK
 * → sha256 校验 → FileProvider 唤起系统安装器。minSupported 用于强制更新。
 */
object UpdateManager {

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val changelog: String,
        val minSupported: Int
    ) {
        val hasUpdate get() = versionCode > BuildConfig.VERSION_CODE
        val forced get() = BuildConfig.VERSION_CODE < minSupported
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    }

    /** 拉取版本信息（失败返回 null，静默） */
    suspend fun check(c: Context): Info? = withContext(Dispatchers.IO) {
        try {
            val url = Prefs.serverUrl(c).trimEnd('/') + "/version.json"
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val o = JSONObject(resp.body?.string().orEmpty())
                Info(
                    versionCode = o.optInt("versionCode"),
                    versionName = o.optString("versionName"),
                    url = o.optString("url"),
                    sha256 = o.optString("sha256"),
                    changelog = o.optString("changelog"),
                    minSupported = o.optInt("minSupported", 1)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 每天至多自动检查一次 */
    suspend fun autoCheck(c: Context): Info? {
        val today = Fmt.today()
        if (Prefs.updateCheckDay(c) == today) return null
        Prefs.setUpdateCheckDay(c, today)
        return check(c)?.takeIf { it.hasUpdate }
    }

    private fun apkFile(c: Context): File =
        File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "looka-update.apk")

    /** 开始下载（DownloadManager，通知栏可见进度） */
    fun startDownload(c: Context, info: Info) {
        val f = apkFile(c)
        if (f.exists()) f.delete()
        val dm = c.getSystemService(DownloadManager::class.java) ?: return
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("Looka ${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, "looka-update.apk")
            .setMimeType("application/vnd.android.package-archive")
        val id = dm.enqueue(req)
        Prefs.setApkDownloadId(c, id)
        Prefs.setApkSha256(c, info.sha256)
    }

    /** 下载完成回调：校验 sha256 后唤起安装 */
    fun onDownloadComplete(c: Context, downloadId: Long) {
        if (downloadId != Prefs.apkDownloadId(c) || downloadId < 0) return
        Prefs.setApkDownloadId(c, -1L)
        val f = apkFile(c)
        if (!f.exists()) return
        val expect = Prefs.apkSha256(c)
        if (expect.isNotBlank() && !sha256(f).equals(expect, true)) {
            f.delete()   // 校验失败：不安装损坏包
            return
        }
        install(c, f)
    }

    fun install(c: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(c, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
            c.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

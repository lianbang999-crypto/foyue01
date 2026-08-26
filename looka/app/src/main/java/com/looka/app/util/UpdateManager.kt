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
        Prefs.setApkTargetCode(c, info.versionCode)
    }

    /** 下载完成回调：校验 sha256 后唤起安装。
     * §116：每个失败分支都要**说话** —— 原来校验失败/文件丢失都静默 return，
     * 用户看到的就是「下载完了，然后什么都没发生」。 */
    fun onDownloadComplete(c: Context, downloadId: Long) {
        if (downloadId != Prefs.apkDownloadId(c) || downloadId < 0) return
        Prefs.setApkDownloadId(c, -1L)
        val f = apkFile(c)
        if (!f.exists()) {
            android.widget.Toast.makeText(c, com.looka.app.util.tr("下载的安装包不见了，请重新检查更新"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val expect = Prefs.apkSha256(c)
        if (expect.isNotBlank() && !sha256(f).equals(expect, true)) {
            f.delete()   // 校验失败：不安装损坏包
            Prefs.setApkReadyVersion(c, "")
            android.widget.Toast.makeText(c, com.looka.app.util.tr("安装包校验失败，已删除，请重新下载"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setApkReadyVersion(c, versionTag(c))
        install(c, f)
    }

    /** 已就绪待装的包（存在且校验过）；「更多」页用它显示常驻安装入口。
     * 目标 code ≤ 当前 code 说明已经装上了 —— 入口自动消失，不用手动清。 */
    fun readyApk(c: Context): File? {
        if (Prefs.apkTargetCode(c) <= BuildConfig.VERSION_CODE) return null
        val f = apkFile(c)
        return if (Prefs.apkReadyVersion(c).isNotBlank() && f.exists()) f else null
    }

    private fun versionTag(c: Context): String = Prefs.apkSha256(c).take(8)

    /**
     * §116 根因：Android 8+ 唤起 APK 安装器**必须先获得本应用的
     * 「允许安装未知应用」授权**（REQUEST_INSTALL_PACKAGES 只是声明，不等于授权）。
     * 未授权时 ACTION_VIEW 会被系统拦下 —— 部分 ROM 弹一下设置又退回，部分直接无反应。
     * 全项目此前**没有任何地方检查过这件事**，这正是用户「下载了、点了安装、
     * 装完没变化、每次都这样」的根因：每次都卡死在同一个未授权环节。
     */
    fun install(c: Context, f: File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(
                    c,
                    com.looka.app.util.tr("请先允许 Looka 安装应用，开启后回到「更多」页点「安装已下载的新版本」"),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                c.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val uri = FileProvider.getUriForFile(c, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
            c.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(c, com.looka.app.util.tr("无法打开安装器：{0}", e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
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

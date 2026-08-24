package com.looka.app

import android.app.Application
import android.os.Build
import androidx.room.Room
import com.looka.app.data.Category
import com.looka.app.data.LookaDb
import com.looka.app.notify.NotifyScheduler
import com.looka.app.util.I18n
import com.looka.app.util.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Looka 应用入口：数据库（正规迁移）、多语言、崩溃留痕、通知渠道、默认数据播种 */
class LookaApp : Application() {

    val db by lazy {
        Room.databaseBuilder(this, LookaDb::class.java, "looka.db")
            .addMigrations(LookaDb.MIGRATION_3_4, LookaDb.MIGRATION_4_5, LookaDb.MIGRATION_5_6, LookaDb.MIGRATION_6_7)
            // 仅 v1/v2 早期内部包允许破坏式升级；v3 起一律走正规迁移（B13）
            .fallbackToDestructiveMigrationFrom(1, 2)
            .build()
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 崩溃留痕文件：下次启动询问是否上报（B20） */
    fun crashFile(): File = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        installCrashHandler()
        NotifyScheduler.ensureChannel(this)
        // P2-A：订阅状态唯一真值源 —— 先从本地恢复（瞬时），网络刷新随后跟上
        com.looka.app.data.PlanState.load(this)
        appScope.launch { com.looka.app.data.PlanState.refresh(this@LookaApp, force = true) }
        // C1（§54）：补上断掉的那一环 —— 崩溃日志此前只写本机、从来没人上传，
        // crashes 表永远是空的，我们对线上崩溃全瞎。上传成功才删文件（失败下次再试）。
        appScope.launch {
            runCatching {
                val f = crashFile()
                if (f.exists()) {
                    val txt = f.readText()
                    // C3：脱敏 —— 只传版本行/机型行/堆栈，堆栈本身不含业务数据
                    val lines = txt.lines()
                    com.looka.app.net.Api.crash(
                        this@LookaApp,
                        // E1（§57）：ver 必须是**崩溃发生时**的版本（文件第一行），不是上传时的
                        ver = lines.getOrElse(0) { "" }.removePrefix("Looka ").substringBefore(" (")
                            .ifBlank { BuildConfig.VERSION_NAME },
                        model = lines.getOrElse(1) { "" }.take(64),
                        stack = lines.drop(3).joinToString("\n").take(8000)
                    )
                    f.delete()
                }
            }
        }
        // 首次启动播种默认数据；uid 固定 + updatedAt=1：
        // 已有云端数据时（首登合并），本地种子必然被云端版本覆盖，避免覆盖用户改名（B18）
        appScope.launch {
            val dao = db.categoryDao()
            if (dao.count() == 0) {
                dao.insert(Category(name = tr("未分类"), colorHex = "#9AA0A6", sortOrder = 0, deletable = false, uid = "cat-default-1", updatedAt = 1L))
                dao.insert(Category(name = tr("工作"), colorHex = "#4A7DDC", sortOrder = 1, uid = "cat-default-2", updatedAt = 1L))
                dao.insert(Category(name = tr("个人"), colorHex = "#55B04B", sortOrder = 2, uid = "cat-default-3", updatedAt = 1L))
                dao.insert(Category(name = tr("重要"), colorHex = "#E0504A", sortOrder = 3, uid = "cat-default-4", updatedAt = 1L))
                dao.insert(Category(name = tr("纪念日"), colorHex = "#E077A8", sortOrder = 4, uid = "cat-default-5", updatedAt = 1L))
            }
            val listDao = db.taskListDao()
            if (listDao.count() == 0) {
                listDao.insert(
                    com.looka.app.data.TaskList(
                        name = tr("我的清单"), colorHex = "#5C6670", sortOrder = 0,
                        deletable = false, uid = "list-default", updatedAt = 1L
                    )
                )
            }
            NotifyScheduler.rescheduleFromDb(this@LookaApp)
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile().writeText(
                    "Looka ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                        "${System.currentTimeMillis()}\n\n" +
                        android.util.Log.getStackTraceString(e).take(8000)
                )
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }
}

package com.looka.app.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.looka.app.data.Prefs
import org.json.JSONObject
import java.util.Locale

/**
 * 多语言运行时（zh-CN / zh-TW / en）：
 * 源码中文即字典 key，tr() 查表返回当前语言文案；缺译回退中文，永不崩。
 * 字典由 scripts/build_i18n.py 生成到 assets/i18n/（与网页端共用同一份）。
 * 语言切换只改 Compose 状态即时生效，无需重建 Activity（不闪屏）；
 * Android 13+ 同时写入系统「单应用语言」，与系统设置双向同步。
 */
object I18n {

    const val SYSTEM = "system"
    val CHOICES = listOf(SYSTEM, "zh-CN", "zh-TW", "en")

    /** 当前生效语言（zh-CN / zh-TW / en），Compose 状态：tr() 调用处自动重组 */
    var lang by mutableStateOf("zh-CN")
        private set

    /** 时间显示 12 小时制（en 默认开；用户可在日历设置覆盖） */
    var use12h by mutableStateOf(false)
        private set

    private var maps: Map<String, Map<String, String>> = emptyMap()

    fun init(c: Context) {
        maps = listOf("zh-TW", "en").associateWith { code ->
            try {
                val txt = c.assets.open("i18n/$code.json").bufferedReader().readText()
                val o = JSONObject(txt)
                buildMap {
                    o.keys().forEach { k -> put(k, o.getString(k)) }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        lang = resolve(Prefs.language(c))
        use12h = Prefs.time12h(c) ?: (lang == "en")
    }

    /** 偏好 → 生效语言；system 时读系统 Locale（zh-TW/HK/Hant → 繁体） */
    fun resolve(pref: String): String = when (pref) {
        "zh-CN", "zh-TW", "en" -> pref
        else -> {
            val l = Locale.getDefault()
            when {
                l.language != "zh" -> if (l.language.isBlank()) "zh-CN" else "en"
                l.script == "Hant" || l.country in setOf("TW", "HK", "MO") -> "zh-TW"
                else -> "zh-CN"
            }
        }
    }

    fun set(c: Context, pref: String) {
        Prefs.setLanguage(c, pref)
        lang = resolve(pref)
        if (Prefs.time12h(c) == null) use12h = lang == "en"
        // Android 13+：写系统单应用语言，与「系统设置 → 应用 → Looka → 语言」双向同步
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val lm = c.getSystemService(LocaleManager::class.java)
                lm.applicationLocales =
                    if (pref == SYSTEM) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(if (pref == "en") "en" else pref)
            } catch (_: Exception) { }
        }
    }

    fun setUse12h(c: Context, v: Boolean) {
        Prefs.setTime12h(c, v)
        use12h = v
    }

    fun isZh() = lang != "en"

    fun choiceLabel(pref: String): String = when (pref) {
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁體中文"
        "en" -> "English"
        else -> tr("跟随系统")
    }

    fun tr(s: String, vararg args: Any?): String {
        var out = if (lang == "zh-CN") s else maps[lang]?.get(s) ?: s
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
        return out
    }
}

/** 顶层快捷函数：Text(tr("全天")) */
fun tr(s: String, vararg args: Any?): String = I18n.tr(s, *args)

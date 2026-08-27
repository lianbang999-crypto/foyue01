package com.looka.app.util

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.looka.app.data.Prefs
import com.looka.app.ui.theme.Tokens
import org.json.JSONObject
import java.io.File

/**
 * §126 C1（T-1）：**官方皮肤包装载器** —— theme-package.v1 整包的下载/校验/装载/持久化。
 *
 * 铁律（THEME-SYSTEM 附录 A，装载端强制）：
 *  1. tokens.semantic 16 槽必须齐全（Tokens.parse 缺一即拒）
 *  2. **可读性十槽必须等于出厂值** —— 动了整包拒并报具体槽名。
 *     皮肤换氛围，不换阅读对比度；这条不靠生成端自觉，靠这里挡。
 *  3. 生成 → 校验 → 用户确认才应用；本对象只提供 install()，从不自作主张。
 *
 * 互斥（规格 §1）：装皮肤包=卸照片主题；照片/聊天主题上位=卸皮肤包；手选九色卸一切。
 * 资产落 files/theme/<id>/，离线可用、卸载 App 即清；色令牌走 Tokens 总线，
 * 图片位图走 [active]（颜色永远有值、图片可以缺 —— 缺哪张哪处原样回退）。
 */
object SkinPacks {

    /** 装载完成的皮肤资产。任何一张可为 null（渲染层按语义回退，不是错误） */
    data class Assets(
        val id: String,
        val name: String,
        val topBanner: ImageBitmap?,
        val bottomBg: ImageBitmap?,
        /** calendar / todo / plus / notes / more */
        val nav: Map<String, ImageBitmap>
    )

    var active by mutableStateOf<Assets?>(null)
        private set

    /** 附录 A.2：可读性十槽的出厂值（与 theme-tokens.schema / PhotoTheme.tokensFrom 同源） */
    private val FROZEN = mapOf(
        "text_primary" to "#1B1B1F", "text_secondary" to "#727776",
        "text_tertiary" to "#B9BBB9", "divider" to "#D8DBD8",
        "weekend" to "#4A7DDC", "holiday" to "#E0504A",
        "danger" to "#E0504A", "scrim" to "#66000000",
        "today" to "#1B1B1F", "surface" to "#FFFFFF"
    )

    fun dirOf(c: Context, id: String): File = File(c.filesDir, "theme/$id")

    /** 合同校验：通过返回 null，否则返回给用户看的具体原因（A.3：拒要拒得明白） */
    fun validate(o: JSONObject): String? {
        if (o.optString("schema_version") != "1.0") return tr("包格式版本不对（需要 1.0）")
        val id = o.optString("id")
        if (!Regex("^[a-z0-9-]{1,40}$").matches(id)) return tr("包 id 不合法")
        if (o.optString("name").isBlank()) return tr("缺少主题名")
        val sem = o.optJSONObject("tokens")?.optJSONObject("semantic")
            ?: return tr("缺少色彩令牌（tokens.semantic）")
        for ((k, v) in FROZEN) {
            val got = sem.optString(k)
            if (got.isBlank()) return tr("缺少色彩槽：{0}", k)
            if (!got.equals(v, ignoreCase = true))
                return tr("可读性槽「{0}」不允许修改（应为 {1}，包里是 {2}）", k, v, got)
        }
        if (Tokens.parse(o.getJSONObject("tokens").toString()) == null)
            return tr("色彩令牌不完整（16 槽必须齐全）")
        return null
    }

    /**
     * 从本地目录装载（目录里须有 theme.json + 资产文件）。
     * 成功返回 null 并完成：Tokens.applyPack + 资产上总线 + 持久化 + 卸照片主题。
     */
    fun install(c: Context, dir: File): String? {
        val jf = File(dir, "theme.json")
        if (!jf.exists()) return tr("包不完整：缺 theme.json")
        val o = runCatching { JSONObject(jf.readText()) }.getOrNull()
            ?: return tr("theme.json 不是合法 JSON")
        validate(o)?.let { return it }
        val tokens = Tokens.parse(o.getJSONObject("tokens").toString())
            ?: return tr("色彩令牌解析失败")
        fun bmp(name: String): ImageBitmap? = runCatching {
            val f = File(dir, name)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
        }.getOrNull()
        val nav = listOf("calendar", "todo", "plus", "notes", "more")
            .mapNotNull { k -> bmp("nav_$k.png")?.let { k to it } }.toMap()
        // 互斥：装皮肤包 = 卸下照片/聊天主题
        PhotoTheme.clear(c)
        Tokens.applyPack(tokens)
        active = Assets(
            id = o.optString("id"), name = o.optString("name"),
            topBanner = bmp("top_banner.png"), bottomBg = bmp("bottom_bg.png"), nav = nav
        )
        Prefs.setSkinPackId(c, o.optString("id"))
        return null
    }

    /** 卸下皮肤包的资产与持久化标记（色令牌由调用方决定接管者：九色/照片/聊天主题） */
    fun clear(c: Context) {
        active = null
        Prefs.setSkinPackId(c, "")
    }

    /** 启动恢复：有持久化 id 且本地包还在 → 静默装回；坏了就静默卸（不打扰启动） */
    fun init(c: Context) {
        val id = Prefs.skinPackId(c)
        if (id.isBlank()) return
        if (install(c, dirOf(c, id)) != null) clear(c)
    }

    /**
     * 从服务器拉包到本地（R2 经 looka.foyue.org 静态口）。只下载落盘，不装载 ——
     * 装载永远走 install()（生成 → 校验 → 确认的链路里，确认后才 install）。
     * 成功返回 null，失败返回原因。
     */
    suspend fun download(c: Context, id: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val base = Prefs.serverUrl(c).trimEnd('/') + "/theme/$id/"
                val client = okhttp3.OkHttpClient()
                fun fetch(path: String): ByteArray? =
                    client.newCall(okhttp3.Request.Builder().url(base + path).build())
                        .execute().use { r ->
                            if (!r.isSuccessful) null
                            else r.body?.bytes()?.takeIf { it.size <= 500 * 1024 }  // 合同：单文件 ≤500KB
                        }
                val jb = fetch("theme.json") ?: return@withContext tr("主题包不存在或超限")
                val o = JSONObject(String(jb))
                validate(o)?.let { return@withContext it }
                val dir = dirOf(c, id).apply { mkdirs() }
                File(dir, "theme.json").writeBytes(jb)
                val assets = o.optJSONObject("assets") ?: JSONObject()
                val names = mutableListOf<String>()
                if (assets.has("top_banner")) names += "top_banner.png"
                if (assets.has("bottom_bg")) names += "bottom_bg.png"
                assets.optJSONObject("nav_icons")?.let { ni ->
                    listOf("calendar", "todo", "plus", "notes", "more").forEach {
                        if (ni.has(it)) names += "nav_$it.png"
                    }
                }
                names.forEach { n -> fetch(n)?.let { File(dir, n).writeBytes(it) } }
                null
            }.getOrElse { tr("下载失败：{0}", it.message ?: "") }
        }
}

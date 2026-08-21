package com.looka.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

/**
 * 内置印章资产库（assets/stamps/，256 WebP 透明底）：
 * packs.json 描述包与三语名；位图按需解码（inSampleSize=2 → 128px 显示绰绰有余），LruCache 8MB。
 */
object StampAssets {

    data class StampDef(val id: String, val file: String, val zh: String, val en: String, val tw: String, val cat: String) {
        fun name(): String = when (I18n.lang) {
            "en" -> en
            "zh-TW" -> tw
            else -> zh
        }
    }

    data class Pack(val id: String, val zh: String, val en: String, val tw: String, val stamps: List<StampDef>) {
        fun name(): String = when (I18n.lang) {
            "en" -> en
            "zh-TW" -> tw
            else -> zh
        }
    }

    @Volatile private var packsCache: List<Pack>? = null
    private var byId: Map<String, StampDef> = emptyMap()

    private val bitmapCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun packs(c: Context): List<Pack> {
        packsCache?.let { return it }
        synchronized(this) {
            packsCache?.let { return it }
            val list = try {
                val txt = c.assets.open("stamps/packs.json").bufferedReader().readText()
                val arr = JSONObject(txt).getJSONArray("packs")
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    val ss = p.getJSONArray("stamps")
                    Pack(
                        id = p.getString("id"),
                        zh = p.getString("zh"), en = p.getString("en"), tw = p.getString("tw"),
                        stamps = (0 until ss.length()).map { j ->
                            val s = ss.getJSONObject(j)
                            StampDef(
                                id = s.getString("id"), file = s.getString("file"),
                                zh = s.getString("zh"), en = s.getString("en"), tw = s.getString("tw"),
                                cat = s.getString("cat")
                            )
                        }
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
            packsCache = list
            byId = list.flatMap { it.stamps }.associateBy { it.id }
            return list
        }
    }

    fun def(c: Context, assetId: String): StampDef? {
        if (assetId.isBlank()) return null
        if (byId.isEmpty()) packs(c)
        return byId[assetId]
    }

    /** 解码位图（半采样 128px，够 64dp@2x；失败返回 null 由调用方回退 emoji） */
    fun bitmap(c: Context, assetId: String): ImageBitmap? {
        val d = def(c, assetId) ?: return null
        bitmapCache.get(assetId)?.let { return it }
        return try {
            c.assets.open("stamps/${d.file}").use { ins ->
                val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeStream(ins, null, opt)?.asImageBitmap()
            }?.also { bitmapCache.put(assetId, it) }
        } catch (_: Exception) {
            null
        }
    }
}

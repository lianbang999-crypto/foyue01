package com.looka.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.looka.app.ui.theme.LookaTokens
import org.json.JSONObject

/**
 * §123：**照片生成主题** —— 本机取色，不是 AI（《全站统一规划》E5 明确这条路线：
 * 「照片取色：本机处理，配色预览，不是 AI」）。零网络、零鹿角、照片不出手机。
 *
 * 取色算法（零依赖，不引 androidx.palette）：
 *   1. 图片缩到 64×64（4096 像素，足够统计主色又快）
 *   2. HSV 空间分桶（色相 24 桶 × 饱和度 2 档），跳过近白/近黑/低饱和像素
 *   3. 取「频次 × 饱和度」加权最高的桶心作为 accent —— 既是照片里大面积的颜色，
 *      又避免选中灰蒙蒙的背景色
 *   4. 语义槽推导沿用九色主题同款公式（selection = accent 与白 90% 混合等），
 *      文字/分隔/周末/节日等**可读性槽不动** —— 皮肤换氛围，不换阅读对比度
 */
object PhotoTheme {

    /** 从照片提取主色；失败（全图无有效彩色像素）返回 null */
    fun extractAccent(c: Context, uri: Uri): Color? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 512) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        if (small !== bmp) bmp.recycle()

        // 桶：色相 24 段 × 饱和度高低 2 档；分数 = 计数 × 平均饱和度
        val count = IntArray(48)
        val satSum = FloatArray(48)
        val valSum = FloatArray(48)
        val hsv = FloatArray(3)
        for (y in 0 until 64) for (x in 0 until 64) {
            val px = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            val (h, s, v) = hsv
            if (s < 0.18f || v < 0.18f || v > 0.97f) continue   // 灰/黑/白不参与主色竞争
            val bucket = ((h / 15f).toInt().coerceIn(0, 23)) * 2 + (if (s >= 0.5f) 1 else 0)
            count[bucket]++; satSum[bucket] += s; valSum[bucket] += v
        }
        small.recycle()
        var best = -1; var bestScore = 0f
        for (i in 0 until 48) {
            if (count[i] < 20) continue   // 少于 20/4096 像素的颜色是噪点
            val score = count[i] * (satSum[i] / count[i])
            if (score > bestScore) { bestScore = score; best = i }
        }
        if (best < 0) return null
        val h = (best / 2) * 15f + 7.5f
        val s = (satSum[best] / count[best]).coerceIn(0.35f, 0.75f)   // 太灰提亮、太艳压住（克制）
        val v = (valSum[best] / count[best]).coerceIn(0.45f, 0.80f)
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    }.getOrNull()

    /** 主色 → 完整语义包。可读性槽保持出厂值（皮肤换氛围，不换阅读对比度） */
    fun tokensFrom(accent: Color): LookaTokens {
        fun mix(a: Color, b: Color, f: Float) = Color(
            a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f
        )
        val white = Color.White
        return LookaTokens(
            surface = white,
            surfaceVariant = Color(0xFFF7F8F7),
            textPrimary = Color(0xFF1B1B1F),
            textSecondary = Color(0xFF727776),
            textTertiary = Color(0xFFB9BBB9),
            accent = accent,
            weekend = Color(0xFF4A7DDC),
            holiday = Color(0xFFE0504A),
            selection = mix(accent, white, 0.90f),
            today = Color(0xFF1B1B1F),
            eventAllDay = mix(accent, white, 0.90f),
            eventTimed = Color(0xFF9AA0A6),
            eventExternal = Color(0xFF727776),
            divider = Color(0xFFD8DBD8),
            scrim = Color(0x66000000),
            danger = Color(0xFFE0504A)
        )
    }

    // ── 持久化（重启后皮肤还在）──

    fun save(c: Context, accent: Color) {
        val argb = (accent.value shr 32).toLong()
        c.getSharedPreferences("looka", Context.MODE_PRIVATE)
            .edit().putLong("photo_theme_accent", argb).apply()
    }

    fun clear(c: Context) {
        c.getSharedPreferences("looka", Context.MODE_PRIVATE)
            .edit().remove("photo_theme_accent").apply()
    }

    /** 启动时恢复；没存过返回 null */
    fun load(c: Context): LookaTokens? {
        val argb = c.getSharedPreferences("looka", Context.MODE_PRIVATE)
            .getLong("photo_theme_accent", -1L)
        if (argb < 0) return null
        return tokensFrom(Color(argb.toInt()))
    }
}

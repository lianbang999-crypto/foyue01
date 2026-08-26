package com.looka.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.looka.app.R
import com.looka.app.ui.theme.CUSTOM_THEME
import com.looka.app.ui.theme.DEER_THEMES
import com.looka.app.ui.theme.ResolvedAsset
import com.looka.app.ui.theme.SkinCtl
import com.looka.app.ui.theme.SkinSlot
import com.looka.app.ui.theme.ThemeCtl
import com.looka.app.util.tr

/**
 * 小鹿徽章。**§112（2026-08-26）：换成线稿鹿 + 主题色斑，重新跟着主题变色。**
 *
 * 经过了三个版本，把来龙去脉一次写清，免得再翻烧饼：
 *  1. §48 B3：Canvas 手绘圆徽章，随主题变色 —— 能变色，但画得糙；
 *  2. §106：换成水彩九色鹿位图 —— 好看了，**但固定配色不再跟主题**（当时把这条记成代价）；
 *  3. §112（现在）：用户给了 direction-C 线稿稿（`looka-direction-c-white-antlers-transparent.png`），
 *     线稿底 + 三块色斑是**分离的** —— 底图用位图，色斑由 Canvas 按主题上色。
 *     好看和变色**第一次同时成立**，§106 那笔代价就此还清。
 *
 * 三块斑的颜色 = 九色环上取 `i、i+3、i+6`（当前主题给最大那块）——
 * 九色鹿身上永远穿着自己九色里的三种，任何主题下都是一个均匀的三分色组，
 * 不会出现"主题换成珊瑚、身上还压着一块几乎同色的红斑"。自定义主题走 HSV ±120° 三分色。
 *
 * 斑画在线稿**上面**：量过底图，身体内部是不透明奶白（alpha=255），画下面根本看不见。
 * 斑的几何（质心 / 主轴角 / 椭圆半径）是在彩色原稿上逐块量出来的（两稿比例差 <1%），
 * 椭圆半径乘 0.92 收一点，保证碰不到描边。
 *
 * 仍走 `GLOBAL_MASCOT_DEFAULT` 槽位：装了 MascotSkin（含 AI 生成）就整只换掉，这里不用改。
 *
 * @param primary 保留参数只为不动 11 处调用点；实际取色走 [ThemeCtl]。
 */
@Composable
fun DeerBadge(size: Dp, primary: Color = MaterialTheme.colorScheme.primary) {
    when (val a = SkinCtl.resolver.asset(SkinSlot.GLOBAL_MASCOT_DEFAULT)) {
        is ResolvedAsset.Builtin -> Image(
            painterResource(a.resId), tr("小鹿"),
            Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop
        )
        is ResolvedAsset.Managed -> {
            val bmp = android.graphics.BitmapFactory.decodeFile(a.localPath)
            if (bmp == null) DeerBadgeBuiltin(size)
            else Image(
                painter = androidx.compose.ui.graphics.painter.BitmapPainter(bmp.asImageBitmap()),
                contentDescription = tr("小鹿"),
                // 皮肤图是满幅方图，圆裁没问题；线稿是异形，不能圆裁（会切鹿角）
                modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop
            )
        }
        ResolvedAsset.Absent -> DeerBadgeBuiltin(size)
    }
}

/**
 * 三块斑的几何：在彩色原稿上量的（正方形分数坐标：质心 cx,cy · 椭圆半径 rx,ry · 主轴角）。
 * TOP 那块没有照量出来的原值用 —— 原斑是沿脖子弯的泪滴形，椭圆近似按原尺寸画会
 * **压到脖子描边 3.1%**（栅格化后与描边掩码求交验的），收小挪内后 0 像素重叠。
 */
private class Patch(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val deg: Float)
private val PATCH_BIG = Patch(0.396f, 0.755f, 0.104f, 0.069f, -147.5f)   // 颈下最大那块
private val PATCH_LOW = Patch(0.578f, 0.909f, 0.104f, 0.064f, -154.4f)   // 底部
private val PATCH_TOP = Patch(0.393f, 0.592f, 0.124f, 0.055f, 61.0f)     // 颈侧长条（收小版）

@Composable
private fun DeerBadgeBuiltin(size: Dp) {
    // 九色环三分色；自定义主题用 HSV ±120°
    val i = ThemeCtl.index
    val (t1, t2, t3) = if (i == CUSTOM_THEME) triad(ThemeCtl.customColor)
    else Triple(
        DEER_THEMES[i.coerceIn(0, 8)].primary,
        DEER_THEMES[(i + 3) % 9].primary,
        DEER_THEMES[(i + 6) % 9].primary
    )
    // 换主题时斑色渐变过去（与 LookaTheme 的 300ms 同步，不然斑跳、底色渐，看着散架）
    val c1 by animateColorAsState(t1, tween(300), label = "deerP1")
    val c2 by animateColorAsState(t2, tween(300), label = "deerP2")
    val c3 by animateColorAsState(t3, tween(300), label = "deerP3")

    Box(Modifier.size(size)) {
        Image(
            painterResource(R.drawable.ic_deer_line), tr("小鹿"),
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit
        )
        Canvas(Modifier.fillMaxSize()) {
            fun patch(p: Patch, color: Color) = rotate(p.deg, Offset(p.cx * this.size.width, p.cy * this.size.height)) {
                drawOval(
                    color,
                    topLeft = Offset((p.cx - p.rx * 0.92f) * this.size.width, (p.cy - p.ry * 0.92f) * this.size.height),
                    size = Size(2 * p.rx * 0.92f * this.size.width, 2 * p.ry * 0.92f * this.size.height)
                )
            }
            patch(PATCH_BIG, c1)   // 最大的斑穿当前主题色 —— 换主题一眼看得见
            patch(PATCH_LOW, c2)
            patch(PATCH_TOP, c3)
        }
    }
}

/** HSV 三分色：给自定义主题的鹿配另外两块斑 */
private fun triad(argb: Long): Triple<Color, Color, Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    fun at(shift: Float): Color {
        val h = (hsv[0] + shift) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2])))
    }
    return Triple(Color(argb), at(120f), at(240f))
}

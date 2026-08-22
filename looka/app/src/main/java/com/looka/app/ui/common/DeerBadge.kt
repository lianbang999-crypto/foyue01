package com.looka.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * B3（§48）：小鹿徽章随主题变色。
 * `ic_deer_badge.xml` 是静态资源，Compose 无法只染其中几条 path —— 这里按同一几何
 * 用 Canvas 重绘：绿底与眼鼻用主题 primary，九色光环保持固定（九色就是"九色鹿"本身，
 * 是品牌不是装饰，不跟主题走）。
 */
private val HALO = listOf(
    Color(0xFFE0504A), Color(0xFFF2913D), Color(0xFFE8C33A),
    Color(0xFF7CB342), Color(0xFF3AA9A0), Color(0xFF4A9EDB),
    Color(0xFF4A7DDC), Color(0xFF7E6BD8), Color(0xFFE077A8)
)
private val HALO_POS = listOf(
    25.4f to 41.5f, 30.7f to 34.7f, 37.5f to 29.4f, 45.5f to 26.1f, 54.0f to 25.0f,
    62.5f to 26.1f, 70.5f to 29.4f, 77.3f to 34.7f, 82.6f to 41.5f
)

@Composable
fun DeerBadge(size: Dp, primary: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 108f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        // 圆底（主题色）
        drawCircle(primary, 50f * s, p(54f, 54f))
        // 鹿角（白描边，与 XML 同曲线）
        val antler = Path().apply {
            moveTo(45f * s, 47f * s); cubicTo(44.5f * s, 42f * s, 43.5f * s, 38f * s, 41.5f * s, 33.5f * s)
            moveTo(42.8f * s, 39.5f * s); cubicTo(39.5f * s, 37.5f * s, 37.5f * s, 37f * s, 35f * s, 36.5f * s)
            moveTo(63f * s, 47f * s); cubicTo(63.5f * s, 42f * s, 64.5f * s, 38f * s, 66.5f * s, 33.5f * s)
            moveTo(65.2f * s, 39.5f * s); cubicTo(68.5f * s, 37.5f * s, 70.5f * s, 37f * s, 73f * s, 36.5f * s)
        }
        drawPath(antler, Color.White, style = Stroke(width = 3.4f * s, cap = StrokeCap.Round))
        // 耳朵（白椭圆）
        drawOval(Color.White, topLeft = p(27.5f, 43f), size = Size(11f * s, 18f * s))
        drawOval(Color.White, topLeft = p(69.5f, 43f), size = Size(11f * s, 18f * s))
        // 头（白圆）
        drawCircle(Color.White, 18f * s, p(54f, 60f))
        // 眼睛与鼻子（主题色）
        drawCircle(primary, 2.4f * s, p(46.9f, 58f))
        drawCircle(primary, 2.4f * s, p(61.1f, 58f))
        drawOval(primary, topLeft = p(50.8f, 64.6f), size = Size(6.4f * s, 4.8f * s))
        // 九色光环（固定 —— 这就是九色鹿）
        HALO_POS.forEachIndexed { i, (x, y) ->
            drawCircle(HALO[i], 2.6f * s, p(x, y))
        }
    }
}

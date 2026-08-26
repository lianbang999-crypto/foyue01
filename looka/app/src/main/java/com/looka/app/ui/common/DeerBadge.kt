package com.looka.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.looka.app.R
import com.looka.app.util.tr

/**
 * 小鹿徽章。**2026-08-26 换成水彩九色鹿**（用户拍板：启动器与应用内用同一只鹿）。
 *
 * 这次换掉的是 §48 B3 那版「Canvas 重绘、跟着九色主题变色」的鹿。
 * **代价要写在这里，不然下次有人会当成 bug 去修**：
 *  - 水彩图是固定配色，**不再跟主题变色**了。切到「樱粉」主题时，
 *    更多页顶上仍是一只薄荷绿的鹿。这是换图必然的结果，不是漏改。
 *  - 主题规格里 Mascot 拆 `Identity`(LOCKED) / `Skin`(可换) 两层就是为了这个：
 *    真要让它跟主题走，得走 MascotSkin 那条路（换整套素材），
 *    而不是把一张水彩图染色 —— 水彩去色/染色只会变成一团脏。
 *
 * 24dp 下可辨识度实测过：鹿身、鹿角、叶子都还认得出，所以 22–26dp 那批调用点
 * 不用另外给简化版。
 *
 * @param primary 保留参数只为不动 11 处调用点；**现在不再使用**。
 */
@Composable
fun DeerBadge(size: Dp, primary: Color = MaterialTheme.colorScheme.primary) {
    Image(
        painter = painterResource(R.drawable.ic_deer_photo),
        contentDescription = tr("小鹿"),
        modifier = Modifier.size(size).clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

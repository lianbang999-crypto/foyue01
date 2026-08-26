package com.looka.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.looka.app.R
import com.looka.app.ui.theme.ResolvedAsset
import com.looka.app.ui.theme.SkinCtl
import com.looka.app.ui.theme.SkinSlot
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
 * §107 C：改成**走槽位查询**（`GLOBAL_MASCOT_DEFAULT`）而不是写死 R.drawable。
 * 这条正好把上面那笔代价还回来一半 —— 水彩鹿不跟主题变色是真的，
 * 但将来装一个 MascotSkin（含 AI 生的），这里**不用改一行**就换掉了。
 * 这是 25 个槽位里 v1 目前唯一真接上渲染层的一个。
 *
 * @param primary 保留参数只为不动 11 处调用点；**现在不再使用**。
 */
@Composable
fun DeerBadge(size: Dp, primary: Color = MaterialTheme.colorScheme.primary) {
    val m = Modifier.size(size).clip(CircleShape)
    when (val a = SkinCtl.resolver.asset(SkinSlot.GLOBAL_MASCOT_DEFAULT)) {
        is ResolvedAsset.Builtin -> Image(
            painterResource(a.resId), tr("小鹿"), m, contentScale = ContentScale.Crop
        )
        is ResolvedAsset.Managed -> Image(
            // 托管资产（含 AI 生成）：已校验过 hash 的本地文件
            painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                android.graphics.BitmapFactory.decodeFile(a.localPath)
                    ?.asImageBitmap() ?: return DeerBadgeBuiltin(m)
            ),
            contentDescription = tr("小鹿"), modifier = m, contentScale = ContentScale.Crop
        )
        // 语义回退：没装皮肤就用随包那只水彩鹿
        ResolvedAsset.Absent -> DeerBadgeBuiltin(m)
    }
}

@Composable
private fun DeerBadgeBuiltin(m: Modifier) = Image(
    painterResource(R.drawable.ic_deer_photo), tr("小鹿"), m, contentScale = ContentScale.Crop
)

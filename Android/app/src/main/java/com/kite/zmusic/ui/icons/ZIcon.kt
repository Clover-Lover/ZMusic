package com.kite.zmusic.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 非设置页图标尺寸。设置页继续用各自的显式尺寸，不要改。
 *
 * Compact：列表行内、次要操作。
 * Standard：导航、Dock、工具栏、圆形按钮内。
 * Prominent：大圆形关闭 / 返回 / 封面播放。
 */
object ZIconSize {
    val Compact = 18.dp
    val Standard = 22.dp
    val Prominent = 28.dp

    fun snap(raw: Dp): Dp = when {
        raw < 20.dp -> Compact
        raw < 25.dp -> Standard
        else -> Prominent
    }
}

@Composable
fun ZIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = ZIconSize.Standard,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

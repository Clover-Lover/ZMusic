package com.kite.zmusic.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kite.zmusic.playback.PlaybackMode
import com.kite.zmusic.ui.icons.ZIconSize
import com.kite.zmusic.ui.icons.ZIcons
import kotlin.math.min

private val IconTint = Color(0xFFE8EEF5)
private val LikeFilledRed = Color(0xFFFF3B5C)

@Composable
fun PlaybackModeControl(
    mode: PlaybackMode,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    circleSize: Dp = 32.dp,
    tint: Color = IconTint,
    iconAlignment: Alignment = Alignment.Center,
    /** 图标绘制尺寸相对 circleSize 的比例；1f 表示铺满触控盒。 */
    glyphFraction: Float = 0.625f,
) {
    val glyph = ZIconSize.snap(circleSize * glyphFraction.coerceIn(0.4f, 1f))
    val vector = when (mode) {
        PlaybackMode.ORDER -> ZIcons.Repeat
        PlaybackMode.REPEAT_ONE -> ZIcons.RepeatOne
        PlaybackMode.SHUFFLE -> ZIcons.Shuffle
    }
    Box(
        modifier = modifier
            .size(circleSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = iconAlignment,
    ) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyph),
        )
    }
}

@Composable
fun TransportSkipIcon(
    forward: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ZIconSize.Standard,
    tint: Color = IconTint,
) {
    Icon(
        imageVector = if (forward) ZIcons.SkipNext else ZIcons.SkipPrevious,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(ZIconSize.snap(size)),
    )
}

@Composable
fun TransportLikeIcon(
    liked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ZIconSize.Standard,
    outlineTint: Color = IconTint,
    filledTint: Color = LikeFilledRed,
) {
    Icon(
        imageVector = ZIcons.Favorite,
        contentDescription = null,
        tint = if (liked) filledTint else outlineTint,
        modifier = modifier.size(ZIconSize.snap(size)),
    )
}

@Composable
fun TransportScoreIcon(
    modifier: Modifier = Modifier,
    size: Dp = ZIconSize.Standard,
    tint: Color = IconTint,
) {
    Icon(
        imageVector = ZIcons.Playlist,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(ZIconSize.snap(size)),
    )
}

@Composable
fun TransportPlayPauseIcon(
    playing: Boolean,
    buffering: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ZIconSize.Standard,
    tint: Color = Color(0xFFF2F5F8),
) {
    val glyph = ZIconSize.snap(size)
    if (buffering) {
        Canvas(modifier.size(glyph)) {
            val w = this.size.width
            val h = this.size.height
            val r = min(w, h) * 0.12f
            val cy = h / 2f
            drawCircle(tint.copy(alpha = 0.45f), r, Offset(w * 0.28f, cy))
            drawCircle(tint.copy(alpha = 0.75f), r, Offset(w * 0.5f, cy))
            drawCircle(tint, r, Offset(w * 0.72f, cy))
        }
        return
    }
    Icon(
        imageVector = if (playing) ZIcons.Pause else ZIcons.Play,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(glyph),
    )
}

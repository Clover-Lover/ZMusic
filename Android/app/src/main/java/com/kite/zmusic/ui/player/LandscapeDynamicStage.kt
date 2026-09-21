package com.kite.zmusic.ui.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kite.zmusic.R
import com.kite.zmusic.data.LrcLine
import com.kite.zmusic.data.TrackRow
import com.kite.zmusic.i18n.t
import com.kite.zmusic.ui.common.UrlImage
import com.kite.zmusic.ui.icons.ZIcons
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DynamicInk = Color(0xFF1C1C1E)
private val DynamicPlayingInk = Color(0xFF1B2428)
private val DynamicBrowseInk = Color(0xFF5A6570)
private val DynamicMuted = Color(0xFF6E6E6E)
private val DynamicMeta = Color(0xFF8A8A8A)
private val CoverFrame = Color.White
private val CoverShape = RoundedCornerShape(8.dp)
private val CoverInnerShape = RoundedCornerShape(6.dp)

private const val LyricFanRadius = 4
/** 扇形张角：邻句开头相对当前句的水平收拢，与文字朝向无关。 */
private const val LyricFanOpenDeg = 9.0f
private const val LyricFanXPull = 0.32f
private const val LyricColWidthFrac = 0.58f
/** 文字相对水平线的旋转（约为拆分前的一半）。 */
private const val LyricTextRotXDeg = 3.2f
private const val LyricTextRotZDeg = 1.95f
/** 整块歌词相对「末句开头对齐屏幕中线」再往右的固定步长。 */
private val LyricGroupNudgeRight = 36.dp
/** 测最长句宽度用加粗主词，保证命中条盖住播放中那行。 */
private val DynamicLyricHitStyle = TextStyle(
    fontSize = 21.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 28.sp,
    letterSpacing = 0.12.sp,
)

/**
 * 横屏「动态」播放页：方封 + 封面虚化铺底 + 景深歌词。
 * 构图对齐 `dev/res/ywo.jpg`（封面必须 1:1）。
 */
@Composable
internal fun LandscapeDynamicStage(
    track: TrackRow,
    lines: List<LrcLine>,
    lyricCompanions: List<LrcLine?>,
    originalOnTop: Boolean,
    showCompanionOnOthers: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onArtistClick: (() -> Unit)?,
    transportRevealT: Float = 0f,
    transportReserve: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val coverUrl = track.coverUrl
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val cover = minOf(maxWidth * 0.202f, maxHeight * 0.362f) * 1.22f
        val leftPad = (maxWidth * 0.055f - 8.dp).coerceAtLeast(10.dp)
        val lyricsEndPad = maxWidth * 0.028f
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val lyricInset = 52.dp + navBottom
        val stageWidthPx = constraints.maxWidth
        val lyricsLeftPx = stageWidthPx * (1f - LyricColWidthFrac)
        val revealT = transportRevealT.coerceIn(0f, 1f)
        val liftPx = with(density) { (transportReserve.toPx() * revealT) / 2f }

        LandscapeDynamicStageBack(
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -liftPx },
        ) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = leftPad)
                .width(cover),
        ) {
            LandscapeDynamicSquareCover(
                coverUrl = coverUrl,
                size = cover,
                onSkipNext = onSkipNext,
                onSkipPrev = onSkipPrev,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = track.name.ifBlank { t("未知歌曲") },
                style = TextStyle(
                    color = DynamicInk,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 32.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .playerExpandAnchor(PlayerExpandSlot.FullTitle)
                    .playerExpandHideFull(),
            )
            val album = track.album?.trim().orEmpty()
            val artists = track.artists.trim()
            if (artists.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LandscapeDynamicMetaRow(
                    icon = ZIcons.MusicNote,
                    text = artists,
                    onClick = onArtistClick,
                    modifier = Modifier
                        .playerExpandAnchor(PlayerExpandSlot.FullArtist)
                        .playerExpandHideFull(),
                )
            }
            if (album.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                LandscapeDynamicMetaRow(
                    icon = ZIcons.Album,
                    text = album,
                    modifier = Modifier.playerExpandHideExtra(),
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(LyricColWidthFrac)
                .padding(
                    end = lyricsEndPad,
                    top = lyricInset,
                    bottom = lyricInset,
                )
                .graphicsLayer { clip = false },
        ) {
            LandscapeDynamicLyricsFan(
                lines = lines,
                companions = lyricCompanions,
                positionMs = positionMs,
                durationMs = durationMs,
                originalOnTop = originalOnTop,
                showCompanionOnOthers = showCompanionOnOthers,
                onSeek = onSeek,
                stageWidthPx = stageWidthPx.toFloat(),
                lyricsLeftPx = lyricsLeftPx,
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
    }
}

@Composable
private fun LandscapeDynamicStageBack(
    modifier: Modifier = Modifier,
) {
    val expand = LocalPlayerExpand.current
    val backAlpha = when {
        expand == null || !expand.mounted -> 1f
        expand.pastHandoff -> 1f
        else -> expand.visualProgress.coerceIn(0f, 1f)
    }
    Image(
        painter = painterResource(R.drawable.img_dynamic_stage_back),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = backAlpha },
    )
}

@Composable
private fun LandscapeDynamicSquareCover(
    coverUrl: String?,
    size: Dp,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
) {
    Box(
        Modifier
            .width(size)
            .aspectRatio(1f)
            .playerExpandAnchor(PlayerExpandSlot.FullCover)
            .playerExpandHideFull()
            .shadow(
                elevation = 8.dp,
                shape = CoverShape,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            )
            .background(CoverFrame, CoverShape)
            .clip(CoverShape)
            .padding(5.dp)
            .pointerInput(onSkipNext, onSkipPrev) {
                var total = 0f
                val threshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        total += amount
                    },
                    onDragEnd = {
                        when {
                            total < -threshold -> onSkipNext()
                            total > threshold -> onSkipPrev()
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CoverInnerShape),
        ) {
            UrlImage(
                url = coverUrl,
                contentDescription = t("封面"),
                contentScale = ContentScale.Crop,
                showPlaceholder = true,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.32f),
                            0.11f to Color.Transparent,
                            0.89f to Color.Transparent,
                            1.00f to Color.White.copy(alpha = 0.32f),
                        ),
                    ),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0.00f to Color.White.copy(alpha = 0.32f),
                            0.11f to Color.Transparent,
                            0.89f to Color.Transparent,
                            1.00f to Color.White.copy(alpha = 0.32f),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun LandscapeDynamicMetaRow(
    icon: ImageVector,
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(
                if (Build.VERSION.SDK_INT >= 31) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .blur(0.7.dp, BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DynamicMeta,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = TextStyle(
                color = DynamicMeta,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 16.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LandscapeDynamicLyricsFan(
    lines: List<LrcLine>,
    companions: List<LrcLine?>,
    positionMs: Long,
    durationMs: Long,
    originalOnTop: Boolean,
    showCompanionOnOthers: Boolean,
    onSeek: (Long) -> Unit,
    stageWidthPx: Float,
    lyricsLeftPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val maxLyricWidthPx = remember(lines, textMeasurer) {
        var maxW = 0
        for (line in lines) {
            val text = line.text
            if (text.isEmpty()) continue
            val w = textMeasurer.measure(
                text = text,
                style = DynamicLyricHitStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            ).size.width
            if (w > maxW) maxW = w
        }
        maxW
    }
    val animActive = lyricAnimActiveIndex(lines, positionMs, durationMs)
    val targetIndex = lyricFocusIndex(lines, animActive).let { if (it < 0) 0 else it }
    val focus = remember { Animatable(targetIndex.toFloat()) }
    var browsing by remember { mutableStateOf(false) }
    var dragSession by remember { mutableStateOf(false) }
    var idleGen by remember { mutableIntStateOf(0) }
    var dragVisual by remember { mutableFloatStateOf(Float.NaN) }
    val browsingNow = browsing || dragSession
    val onSeekRef = rememberUpdatedState(onSeek)
    val browsingRef = rememberUpdatedState(browsingNow)

    LaunchedEffect(lines.size) {
        val last = lines.lastIndex.coerceAtLeast(0).toFloat()
        focus.updateBounds(0f, last)
    }

    LaunchedEffect(lines.size) {
        browsing = false
        dragSession = false
        if (lines.isNotEmpty()) {
            focus.snapTo(targetIndex.toFloat().coerceIn(0f, lines.lastIndex.toFloat()))
        }
    }

    LaunchedEffect(targetIndex, lines.size, browsing, dragSession) {
        if (browsing || dragSession || lines.isEmpty()) return@LaunchedEffect
        val dest = targetIndex.toFloat().coerceIn(0f, lines.lastIndex.toFloat())
        val jump = abs(dest - focus.value)
        if (jump < 0.02f) return@LaunchedEffect
        val followMs = (400f + jump * 52f).toInt().coerceIn(380, 820)
        focus.animateTo(
            targetValue = dest,
            animationSpec = tween(
                durationMillis = followMs,
                easing = CubicBezierEasing(0.33f, 0.0f, 0.2f, 1f),
            ),
        )
    }

    LaunchedEffect(browsing, idleGen) {
        if (!browsing) return@LaunchedEffect
        delay(5_500)
        while (dragSession) delay(160)
        delay(320)
        if (browsing && !dragSession) {
            browsing = false
        }
    }

    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            LandscapeDynamicFanLine(
                text = t("暂无歌词"),
                companion = null,
                originalOnTop = true,
                offset = 0f,
                density = density.density,
            )
        }
        return
    }

    BoxWithConstraints(
        modifier.graphicsLayer { clip = false },
    ) {
        val halfPx = constraints.maxHeight / 2f
        val edgePx = with(density) { 12.dp.toPx() }
        val spacingPx = ((halfPx - edgePx) / LyricFanRadius.toFloat() * 1.16f).coerceIn(
            with(density) { 34.dp.toPx() },
            with(density) { 48.dp.toPx() },
        )
        val visual = if (!dragVisual.isNaN()) dragVisual else focus.value
        val start = floor(visual - LyricFanRadius).toInt().coerceAtLeast(0)
        val end = ceil(visual + LyricFanRadius).toInt().coerceAtMost(lines.lastIndex)
        val stepRad = Math.toRadians(LyricFanOpenDeg.toDouble()).toFloat()
        val radius = if (abs(sin(stepRad)) < 1e-4f) {
            spacingPx * 12f
        } else {
            spacingPx / sin(stepRad)
        }
        val lastUnplayedRelX = -(1f - cos(LyricFanRadius * stepRad)) * radius * LyricFanXPull
        val groupShiftX = stageWidthPx * 0.5f - lyricsLeftPx - lastUnplayedRelX +
            with(density) { LyricGroupNudgeRight.toPx() }
        val lastIndex = lines.lastIndex.toFloat()
        val lastIndexInt = lines.lastIndex
        val spacingKey = spacingPx.roundToInt()
        val visualRef = rememberUpdatedState(visual)
        val preselect = visual.roundToInt().coerceIn(0, lastIndexInt)
        val centerLocalX = stageWidthPx * 0.5f - lyricsLeftPx
        val boxW = constraints.maxWidth.toFloat()
        val hitLeftPx = centerLocalX.coerceIn(0f, boxW)
        val hitWpx = (centerLocalX + maxLyricWidthPx - hitLeftPx).coerceIn(0f, boxW - hitLeftPx)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(lines.size, spacingKey, hitLeftPx.roundToInt(), hitWpx.roundToInt()) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x < hitLeftPx ||
                            down.position.x > hitLeftPx + hitWpx
                        ) {
                            return@awaitEachGesture
                        }
                        val pointerId = down.id
                        val startPos = down.position
                        var lastY = startPos.y
                        var dragging = false
                        var flingLaunched = false
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)

                        fun beginDrag(fromY: Float) {
                            dragging = true
                            dragSession = true
                            lastY = fromY
                            dragVisual = focus.value
                        }

                        try {
                            while (!dragging) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.find { it.id == pointerId }
                                    ?: return@awaitEachGesture
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) {
                                    if (browsingRef.value) {
                                        val mid = size.height / 2f
                                        val rel = (startPos.y - mid) / spacingPx
                                        val tapped = (visualRef.value + rel).roundToInt()
                                            .coerceIn(0, lastIndexInt)
                                        val pre = visualRef.value.roundToInt()
                                            .coerceIn(0, lastIndexInt)
                                        if (isBrowseSeekHit(tapped, pre)) {
                                            val ms = lines.getOrNull(pre)?.timeMs
                                            if (ms != null) onSeekRef.value(ms)
                                            scope.launch { focus.snapTo(pre.toFloat()) }
                                        }
                                        browsing = false
                                        dragSession = false
                                        dragVisual = Float.NaN
                                        change.consume()
                                    }
                                    return@awaitEachGesture
                                }
                                if (change.isConsumed) return@awaitEachGesture
                                val dy = change.position.y - startPos.y
                                val dx = change.position.x - startPos.x
                                if (abs(dx) > touchSlop && abs(dx) >= abs(dy) * 0.75f) {
                                    return@awaitEachGesture
                                }
                                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 0.75f) {
                                    beginDrag(change.position.y)
                                    change.consume()
                                    break
                                }
                            }

                            while (dragging) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.find { it.id == pointerId } ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val step = change.position.y - lastY
                                lastY = change.position.y
                                val from = if (dragVisual.isNaN()) focus.value else dragVisual
                                dragVisual = (from - step / spacingPx).coerceIn(0f, lastIndex)
                                change.consume()
                                if (!change.pressed) {
                                    val velocityY = tracker.calculateVelocity().y
                                    flingLaunched = true
                                    browsing = true
                                    val releaseAt = dragVisual
                                    scope.launch {
                                        try {
                                            focus.snapTo(releaseAt)
                                            dragVisual = Float.NaN
                                            focus.animateDecay(
                                                initialVelocity = -velocityY / spacingPx,
                                                animationSpec = exponentialDecay(
                                                    frictionMultiplier = 1.35f,
                                                ),
                                            )
                                            val nearest = focus.value.roundToInt()
                                                .toFloat()
                                                .coerceIn(0f, lastIndex)
                                            if (abs(focus.value - nearest) > 0.06f) {
                                                focus.animateTo(
                                                    nearest,
                                                    spring(dampingRatio = 0.9f, stiffness = 220f),
                                                )
                                            }
                                        } finally {
                                            dragVisual = Float.NaN
                                            dragSession = false
                                            idleGen++
                                        }
                                    }
                                    break
                                }
                            }
                        } finally {
                            if (dragSession && !flingLaunched) {
                                if (dragging) {
                                    browsing = true
                                    val releaseAt = if (dragVisual.isNaN()) focus.value else dragVisual
                                    scope.launch {
                                        focus.snapTo(releaseAt)
                                        dragVisual = Float.NaN
                                    }
                                }
                                dragSession = false
                                if (dragging) idleGen++
                            }
                        }
                    }
                },
        ) {
            for (i in start..end) {
                val offset = i - visual
                if (abs(offset) > LyricFanRadius + 0.12f) continue
                val companion = companions.getOrNull(i)?.text?.trim()?.takeIf { it.isNotEmpty() }
                val absD = abs(offset)
                val showCompanion = companion != null &&
                    (absD < 0.55f || showCompanionOnOthers)
                val theta = offset * stepRad
                val yPx = sin(theta) * radius
                val xPx = -(1f - cos(theta)) * radius * LyricFanXPull + groupShiftX
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                        .zIndex(LyricFanRadius + 1f - absD)
                        .graphicsLayer { clip = false },
                ) {
                    LandscapeDynamicFanLine(
                        text = lines[i].text,
                        companion = companion.takeIf { showCompanion },
                        originalOnTop = originalOnTop,
                        offset = offset,
                        density = density.density,
                        isPlayingLine = i == targetIndex,
                        isBrowseCenter = browsingNow && i == preselect && i != targetIndex,
                    )
                }
            }
            if (hitWpx > 1f) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(hitLeftPx.roundToInt(), 0) }
                        .width(with(density) { hitWpx.toDp() })
                        .fillMaxHeight()
                        .zIndex(LyricFanRadius + 2f)
                        .consumeUnclaimedVerticalDrag(),
                )
            }
        }
    }
}

@Composable
private fun LandscapeDynamicFanLine(
    text: String,
    companion: String?,
    originalOnTop: Boolean,
    offset: Float,
    density: Float,
    isPlayingLine: Boolean = false,
    isBrowseCenter: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val absD = abs(offset)
    val blurDp = when {
        isPlayingLine && absD >= 0.42f -> dynamicFanBlur(absD) * 0.55f
        else -> dynamicFanBlur(absD)
    }
    val alpha = if (isPlayingLine) {
        dynamicFanAlpha(absD).coerceAtLeast(0.84f)
    } else {
        dynamicFanAlpha(absD)
    }
    val focused = absD < 0.42f || isBrowseCenter
    val ink = when {
        isPlayingLine -> DynamicPlayingInk
        isBrowseCenter -> DynamicBrowseInk
        else -> DynamicInk
    }
    val weight = when {
        isPlayingLine -> FontWeight.Bold
        isBrowseCenter -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }
    val textBlur = if (blurDp > 0.dp && Build.VERSION.SDK_INT >= 31) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .blur(blurDp, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }

    Column(
        modifier
            .wrapContentWidth(Alignment.Start)
            .graphicsLayer {
                clip = false
                cameraDistance = 16f * density
                transformOrigin = TransformOrigin(0f, 0.5f)
                rotationX = offset * LyricTextRotXDeg
                rotationZ = offset * LyricTextRotZDeg
                this.alpha = alpha
            }
            .padding(vertical = if (focused) 3.dp else 2.dp),
    ) {
        val main = @Composable {
            Text(
                text = text,
                style = TextStyle(
                    color = ink,
                    fontSize = 21.sp,
                    fontWeight = weight,
                    lineHeight = 28.sp,
                    letterSpacing = 0.12.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = textBlur,
            )
        }
        val sub = @Composable {
            if (!companion.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = companion,
                    style = TextStyle(
                        color = DynamicMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = textBlur,
                )
            }
        }
        if (originalOnTop) {
            main()
            sub()
        } else {
            sub()
            main()
        }
    }
}

/** 浅磨砂：邻句保持可读，越远略糊，避免糊成色块。 */
private fun dynamicFanBlur(absD: Float): Dp {
    if (absD < 0.06f) return 0.dp
    return (0.75f + absD * 1.25f).coerceIn(1.0f, 6.8f).dp
}

private fun dynamicFanAlpha(absD: Float): Float {
    if (absD < 0.06f) return 1f
    val blurOk = Build.VERSION.SDK_INT >= 31
    return if (blurOk) {
        (0.95f - absD * 0.08f).coerceIn(0.60f, 0.93f)
    } else {
        (0.88f - absD * 0.11f).coerceIn(0.44f, 0.86f)
    }
}

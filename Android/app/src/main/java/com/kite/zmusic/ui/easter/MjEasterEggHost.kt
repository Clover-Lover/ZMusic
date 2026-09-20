package com.kite.zmusic.ui.easter

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Choreographer
import android.widget.ImageView
import androidx.compose.animation.core.Animatable as ComposeAnimatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kite.zmusic.ZMusicApplication
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private val DropEasing = CubicBezierEasing(0.18f, 1.22f, 0.28f, 1f)
private val DismissEasing = CubicBezierEasing(0.4f, 0.02f, 0.16f, 1f)

private data class EasterGifSpec(
    val drawable: Drawable,
    val width: Int,
    val height: Int,
)

@Composable
fun MjEasterEggHost(modifier: Modifier = Modifier) = EasterEggHost(modifier)

@Composable
fun EasterEggHost(modifier: Modifier = Modifier) {
    val play by EasterEggs.play.collectAsStateWithLifecycle()
    val reveal = remember { ComposeAnimatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf<EasterGifSpec?>(null) }
    var panelH by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(play?.generation, play?.clip?.id) {
        val clip = play?.clip ?: return@LaunchedEffect
        val gen = play?.generation ?: return@LaunchedEffect
        mounted = true
        (spec?.drawable as? Animatable)?.stop()
        val loaded = withContext(Dispatchers.IO) {
            runCatching { loadGif(context.applicationContext, clip.gifAsset) }.getOrNull()
        }
        spec = loaded
        coroutineScope {
            launch {
                if (reveal.value < 0.99f) {
                    reveal.snapTo(0f)
                    reveal.animateTo(1f, tween(420, easing = DropEasing))
                } else {
                    reveal.snapTo(1f)
                }
            }
            if (loaded != null) {
                playLinked(
                    context.applicationContext,
                    clip.audioAsset,
                    onStart = {
                        (loaded.drawable as? Animatable)?.let { anim ->
                            anim.stop()
                            anim.start()
                        }
                    },
                )
            }
        }
        (loaded?.drawable as? Animatable)?.stop()
        if (!isActive) return@LaunchedEffect
        reveal.animateTo(0f, tween(240, easing = DismissEasing))
        if (isActive && EasterEggs.play.value?.generation == gen) {
            mounted = false
            spec = null
        }
    }
    DisposableEffect(play?.generation) {
        onDispose {
            (spec?.drawable as? Animatable)?.stop()
        }
    }

    if (!mounted && reveal.value <= 0.01f) return

    val ratio = spec?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: 1f
    val drawable = spec?.drawable
    Box(
        modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .zIndex(550f)
            .onSizeChanged { panelH = it.height }
            .graphicsLayer {
                val h = if (panelH > 0) panelH.toFloat() else size.height
                translationY = -(1f - reveal.value) * h
            },
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_XY
                    adjustViewBounds = false
                }
            },
            update = { view ->
                view.setBackgroundColor(Color.TRANSPARENT)
                if (view.drawable !== drawable) {
                    view.setImageDrawable(drawable)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        )
    }
}

private fun loadGif(context: Context, asset: String): EasterGifSpec {
    val bytes = context.assets.open(asset).use { it.readBytes() }
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    (drawable as? AnimatedImageDrawable)?.repeatCount = 0
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    return EasterGifSpec(drawable, w, h)
}

private suspend fun playLinked(
    context: Context,
    audioAsset: String,
    onStart: () -> Unit,
) = coroutineScope {
    val bridge = (context.applicationContext as? ZMusicApplication)?.playbackBridge
    val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    val mp = MediaPlayer()
    try {
        bridge?.duckMusicVolume(0.1f)
        mp.setAudioAttributes(attrs)
        context.assets.openFd(audioAsset).use { fd ->
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        }
        mp.prepare()
        mp.seekTo(0)
        suspendCancellableCoroutine { cont ->
            mp.setOnCompletionListener {
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { _, _, _ ->
                if (cont.isActive) cont.resume(Unit)
                true
            }
            Choreographer.getInstance().postFrameCallback {
                if (!cont.isActive) return@postFrameCallback
                onStart()
                mp.start()
            }
            cont.invokeOnCancellation {
                runCatching { if (mp.isPlaying) mp.stop() }
            }
        }
    } finally {
        runCatching { mp.release() }
        bridge?.duckMusicVolume(null)
    }
}

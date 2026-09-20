package com.kite.zmusic.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 百科下滑退回黑胶：只看「这次按下时是否已在顶部」。
 * 中途滑到顶再下拉不退回，避免和列表滚动抢手势。
 */
internal object WikiTopDownDismiss {
    fun pulledUpFirst(
        already: Boolean,
        dy: Float,
        dx: Float,
        slop: Float,
    ): Boolean {
        if (already) return true
        val up = -dy
        return up > slop && up >= abs(dx) * 0.65f
    }

    fun shouldDismiss(
        startedAtTop: Boolean,
        pulledUpFirst: Boolean,
        dy: Float,
        dx: Float,
        slop: Float,
        thresholdPx: Float,
    ): Boolean {
        if (!startedAtTop || pulledUpFirst) return false
        return dy > slop && dy > abs(dx) * 1.6f && dy >= thresholdPx
    }
}

private class WikiDismissGate {
    var armed = false
    var cancelled = false
    var fired = false
    var pulled = 0f

    fun reset(atTop: Boolean) {
        armed = atTop
        cancelled = false
        fired = false
        pulled = 0f
    }
}

internal fun Modifier.wikiTopDownDismiss(
    dismissThresholdPx: Float,
    atTop: () -> Boolean,
    onDismiss: () -> Unit,
): Modifier = composed {
    val gate = remember { WikiDismissGate() }
    val atTopNow = rememberUpdatedState(atTop)
    val dismissNow = rememberUpdatedState(onDismiss)
    val connection = remember(dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!gate.armed || gate.cancelled || gate.fired) return Offset.Zero
                if (available.y < 0f) {
                    if (available.y < -1f) gate.cancelled = true
                    return Offset.Zero
                }
                if (available.y == 0f) return Offset.Zero
                gate.pulled += available.y
                if (gate.pulled >= dismissThresholdPx && !gate.fired) {
                    gate.fired = true
                    dismissNow.value()
                }
                return Offset(0f, available.y)
            }
        }
    }
    Modifier
        .pointerInput(dismissThresholdPx) {
            val touchSlop = viewConfiguration.touchSlop
            val dismissSlop = touchSlop * 3.5f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                gate.reset(atTopNow.value())
                val pointerId = down.id
                val start = down.position
                var pulledUp = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.find { it.id == pointerId } ?: break
                    val dx = change.position.x - start.x
                    val dy = change.position.y - start.y
                    pulledUp = WikiTopDownDismiss.pulledUpFirst(pulledUp, dy, dx, touchSlop)
                    if (pulledUp) gate.cancelled = true
                    if (
                        !gate.fired &&
                        WikiTopDownDismiss.shouldDismiss(
                            startedAtTop = gate.armed,
                            pulledUpFirst = pulledUp,
                            dy = dy,
                            dx = dx,
                            slop = dismissSlop,
                            thresholdPx = dismissThresholdPx,
                        )
                    ) {
                        change.consume()
                        gate.fired = true
                        dismissNow.value()
                        while (true) {
                            val rest = awaitPointerEvent(PointerEventPass.Main)
                            val c = rest.changes.find { it.id == pointerId }
                                ?: return@awaitEachGesture
                            c.consume()
                            if (!c.pressed) return@awaitEachGesture
                        }
                    }
                    if (!change.pressed) break
                }
            }
        }
        .nestedScroll(connection)
}

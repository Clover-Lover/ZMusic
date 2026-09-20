package com.kite.zmusic.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiTopDownDismissTest {
    @Test
    fun dismissOnlyWhenGestureStartsAtTop() {
        assertTrue(
            WikiTopDownDismiss.shouldDismiss(
                startedAtTop = true,
                pulledUpFirst = false,
                dy = 200f,
                dx = 10f,
                slop = 40f,
                thresholdPx = 112f,
            ),
        )
        assertFalse(
            WikiTopDownDismiss.shouldDismiss(
                startedAtTop = false,
                pulledUpFirst = false,
                dy = 400f,
                dx = 0f,
                slop = 40f,
                thresholdPx = 112f,
            ),
        )
        assertFalse(
            WikiTopDownDismiss.shouldDismiss(
                startedAtTop = true,
                pulledUpFirst = true,
                dy = 400f,
                dx = 0f,
                slop = 40f,
                thresholdPx = 112f,
            ),
        )
    }

    @Test
    fun pullingUpDisarmsThisGesture() {
        assertTrue(WikiTopDownDismiss.pulledUpFirst(false, -30f, 2f, 8f))
        assertFalse(WikiTopDownDismiss.pulledUpFirst(false, 30f, 2f, 8f))
    }
}

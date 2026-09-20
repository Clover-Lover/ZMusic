package com.kite.zmusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricOverlayLogicTest {
    @Test
    fun awakeAlwaysShowsWindowBackground() {
        assertTrue(overlayShowsWindowBackground(locked = false, idleChrome = false, windowBackgroundEnabled = false))
        assertTrue(overlayShowsWindowBackground(locked = false, idleChrome = false, windowBackgroundEnabled = true))
    }

    @Test
    fun lockedFollowsWindowBackgroundSwitch() {
        assertFalse(overlayShowsWindowBackground(locked = true, idleChrome = false, windowBackgroundEnabled = false))
        assertTrue(overlayShowsWindowBackground(locked = true, idleChrome = false, windowBackgroundEnabled = true))
        assertFalse(overlayShowsWindowBackground(locked = true, idleChrome = true, windowBackgroundEnabled = false))
        assertTrue(overlayShowsWindowBackground(locked = true, idleChrome = true, windowBackgroundEnabled = true))
    }

    @Test
    fun idleUnlockedFollowsWindowBackgroundSwitch() {
        assertFalse(overlayShowsWindowBackground(locked = false, idleChrome = true, windowBackgroundEnabled = false))
        assertTrue(overlayShowsWindowBackground(locked = false, idleChrome = true, windowBackgroundEnabled = true))
    }

    @Test
    fun windowBackgroundDefaultsOff() {
        assertEquals(false, LyricOverlayPrefs().windowBackground)
    }

    @Test
    fun idleUnlockedClaimsTouchesSoDragIsNotStolen() {
        assertTrue(overlayClaimsWindowTouches(locked = false, idleChrome = true, windowDragging = false))
        assertTrue(overlayClaimsWindowTouches(locked = false, idleChrome = true, windowDragging = true))
        assertFalse(overlayClaimsWindowTouches(locked = true, idleChrome = true, windowDragging = false))
        assertFalse(overlayClaimsWindowTouches(locked = false, idleChrome = false, windowDragging = false))
        assertTrue(overlayClaimsWindowTouches(locked = false, idleChrome = false, windowDragging = true))
    }

    @Test
    fun idleTapWakesButDragStaysIdle() {
        assertTrue(
            overlayWakesFromIdle(
                idleChrome = true,
                locked = false,
                dragged = false,
                pointerOnOverlay = true,
            ),
        )
        assertFalse(
            overlayWakesFromIdle(
                idleChrome = true,
                locked = false,
                dragged = true,
                pointerOnOverlay = true,
            ),
        )
        assertFalse(
            overlayWakesFromIdle(
                idleChrome = true,
                locked = true,
                dragged = false,
                pointerOnOverlay = true,
            ),
        )
    }

    @Test
    fun othersKeepOriginalWhenCompanionHidden() {
        val rows = overlayLyricRows(
            lineText = "hello",
            companionText = "你好",
            originalOnTop = true,
            showCompanion = false,
        )
        assertEquals(listOf(OverlayLyricRow("hello", false)), rows)
    }

    @Test
    fun coexistPutsOriginalThenTranslation() {
        val rows = overlayLyricRows(
            lineText = "hello",
            companionText = "你好",
            originalOnTop = true,
            showCompanion = true,
        )
        assertEquals(
            listOf(
                OverlayLyricRow("hello", false),
                OverlayLyricRow("你好", true),
            ),
            rows,
        )
    }

    @Test
    fun coexistCanPutTranslationOnTop() {
        val rows = overlayLyricRows(
            lineText = "hello",
            companionText = "你好",
            originalOnTop = false,
            showCompanion = true,
        )
        assertEquals(
            listOf(
                OverlayLyricRow("你好", true),
                OverlayLyricRow("hello", false),
            ),
            rows,
        )
    }

    @Test
    fun blankCompanionFallsBackToSingleLine() {
        val rows = overlayLyricRows(
            lineText = "",
            companionText = "  ",
            originalOnTop = true,
            showCompanion = true,
            fallback = "♪",
        )
        assertEquals(listOf(OverlayLyricRow("♪", false)), rows)
    }

    @Test
    fun translationDefaultsOnWithCoexist() {
        val prefs = LyricOverlayPrefs()
        assertEquals(true, prefs.preferTranslation)
        assertEquals(true, prefs.translationCoexist)
        assertEquals(false, prefs.othersShowTranslation)
    }

    @Test
    fun overlayBundleCoexistKeepsOriginalWithCompanion() {
        val bundle = pickDisplayLyricBundle(
            original = listOf(LrcLine(0L, "hello")),
            translated = listOf(LrcLine(0L, "你好")),
            wordOriginal = emptyList(),
            wordTranslated = emptyList(),
            preferTranslation = true,
            coexist = true,
            wordByWord = false,
        )
        assertEquals("hello", bundle.lines.single().text)
        assertEquals("你好", bundle.companions.single()?.text)
    }

    @Test
    fun overlayBundleCoverUsesTranslationOnly() {
        val bundle = pickDisplayLyricBundle(
            original = listOf(LrcLine(0L, "hello")),
            translated = listOf(LrcLine(0L, "你好")),
            wordOriginal = emptyList(),
            wordTranslated = emptyList(),
            preferTranslation = true,
            coexist = false,
            wordByWord = false,
        )
        assertEquals("你好", bundle.lines.single().text)
        assertTrue(bundle.companions.isEmpty())
    }

    @Test
    fun orderedLyricPairFollowsOriginalOnTop() {
        val original = LrcLine(0L, "hello")
        val translated = LrcLine(0L, "你好")
        val topOriginal = orderedLyricPair(original, translated, originalOnTop = true)
        assertEquals("hello", topOriginal.first.text)
        assertEquals("你好", topOriginal.second?.text)
        val topTranslated = orderedLyricPair(original, translated, originalOnTop = false)
        assertEquals("你好", topTranslated.first.text)
        assertEquals("hello", topTranslated.second?.text)
        assertEquals("hello", orderedLyricPair(original, null, originalOnTop = false).first.text)
    }

    @Test
    fun overlayWidthAt100PercentFillsLandscape() {
        assertEquals(2400, overlayFixedWidthPx(2400, 100))
        assertEquals(1080, overlayFixedWidthPx(1080, 100))
        assertEquals(1680, overlayFixedWidthPx(2400, 70))
    }

    @Test
    fun overlayClampXAllowsRightEdgeOnLandscape() {
        val displayW = 2400
        val windowW = overlayFixedWidthPx(displayW, 70)
        assertEquals(720, overlayClampX(9999, windowW, displayW))
        assertEquals(0, overlayClampX(-20, windowW, displayW))
        assertEquals(0, overlayClampX(80, displayW, displayW))
    }

    @Test
    fun overlayDisplaySizeSwapsWhenMetricsStayPortrait() {
        assertEquals(
            2400 to 1080,
            overlayDisplaySize(1080, 2400, android.content.res.Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            1080 to 2400,
            overlayDisplaySize(1080, 2400, android.content.res.Configuration.ORIENTATION_PORTRAIT),
        )
        assertEquals(
            2400 to 1080,
            overlayDisplaySize(2400, 1080, android.content.res.Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun remapPortraitPositionToLandscapeKeepsPercentage() {
        val portraitW = 1080
        val landscapeW = 2400
        val portraitX = overlayDefaultX(portraitW)
        val landscapeX = overlayRemapCoord(
            pos = portraitX,
            ref = portraitW,
            newSize = landscapeW,
            fallback = overlayDefaultX(landscapeW),
        )
        assertEquals(overlayDefaultX(landscapeW), landscapeX)
        val rightPortraitX = overlayClampX(Int.MAX_VALUE, overlayFixedWidthPx(portraitW, 70), portraitW)
        val rightLandscapeX = overlayRemapCoord(
            pos = rightPortraitX,
            ref = portraitW,
            newSize = landscapeW,
            fallback = 0,
        )
        val expected = overlayClampX(
            Int.MAX_VALUE,
            overlayFixedWidthPx(landscapeW, 70),
            landscapeW,
        )
        assertEquals(expected, overlayClampX(rightLandscapeX, overlayFixedWidthPx(landscapeW, 70), landscapeW))
    }

    @Test
    fun remapUnsetUsesDefaultOnNewScreen() {
        assertEquals(
            overlayDefaultY(1080),
            overlayRemapCoord(
                pos = LyricOverlayPrefs.UNSET,
                ref = 2400,
                newSize = 1080,
                fallback = overlayDefaultY(1080),
            ),
        )
    }
}

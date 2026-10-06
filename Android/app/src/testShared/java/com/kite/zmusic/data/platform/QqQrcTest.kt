package com.kite.zmusic.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqQrcTest {
    @Test
    fun decryptsKnownVector() {
        val text = QqQrc.decode("c200049c87919711019d4c73b1822e993fa40f03d0db5ab9")
        assertEquals("QQ-LYRIC-OK", text)
    }

    @Test
    fun parsesWordLine() {
        val lines = QqQrc.parse("[0,2250]你(0,160)好(160,200)")
        assertEquals(1, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("你好", lines[0].text)
        assertEquals(160L, lines[0].words[1].timeMs)
        assertEquals(200L, lines[0].words[1].durationMs)
    }

    @Test
    fun stripsLyricContentWrapper() {
        val lines = QqQrc.parse("<QrcInfos LyricContent=\"[1000,500]a(1000,200)\"/>")
        assertEquals(1, lines.size)
        assertEquals("a", lines[0].text)
        assertTrue(lines[0].words.isNotEmpty())
    }
}

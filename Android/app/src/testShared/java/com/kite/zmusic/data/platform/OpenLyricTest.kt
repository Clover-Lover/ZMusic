package com.kite.zmusic.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OpenLyricTest {
    @Test
    fun kuwoDecryptsKnownVector() {
        val raw = Base64.getDecoder().decode("VFA9Y29udGVudA0KbHJjeD0xDQoNCnic881yrPQ0CMzyqywu9sp2zfVytLUFAEjDBr8=")
        assertEquals("KUWO-LYRIC-OK", KuwoLyric.decode(raw))
    }

    @Test
    fun kuwoWordTimesUseKuwoTag() {
        val lines = KuwoLyric.parse("[kuwo:040]\n[00:01.00]<506,-506>你<1171,347>好").original
        assertEquals(1, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("你好", lines[0].text)
        assertEquals(1000L, lines[0].words[0].timeMs)
        assertEquals(253L, lines[0].words[0].durationMs)
        assertEquals(1253L, lines[0].words[1].timeMs)
    }

    @Test
    fun kugouDecryptsKnownVector() {
        assertEquals("KG-LYRIC-OK", KugouLyric.decodeKrc("a3JjMTjbkgGJx/1Lo0Ln2DnUbnsDRGs="))
    }

    @Test
    fun kugouWordTimesAreLineOffsets() {
        val lines = KugouLyric.parseKrc("[1000,500]<0,200,0>你<200,200,0>好").original
        assertEquals("你好", lines.single().text)
        assertEquals(1000L, lines.single().words[0].timeMs)
        assertEquals(1200L, lines.single().words[1].timeMs)
        assertTrue(lines.single().words[1].durationMs == 200L)
    }
}

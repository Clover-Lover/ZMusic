package com.kite.zmusic.ui.easter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasterEggLogicTest {
    @Test
    fun matchIgnoresCaseAndWhitespace() {
        assertEquals("mj", EasterClip.match("MJ")?.id)
        assertEquals("fox", EasterClip.match("  Fox  ")?.id)
        assertNull(EasterClip.match("foxx"))
        assertNull(EasterClip.match(""))
    }
}

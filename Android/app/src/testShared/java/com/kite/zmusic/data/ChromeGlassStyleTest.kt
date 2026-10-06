package com.kite.zmusic.data

import com.kite.zmusic.plugin.LookGlassPartial
import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeGlassStyleTest {
    @Test
    fun eachModeKeepsItsOwnBlurAndRefraction() {
        val tuned = ChromeGlassStyle.Default
            .withBlur(0.8f)
            .withRefraction(1.6f)
            .copy(mode = ChromeGlassMode.Frosted)
            .withBlur(0.2f)
            .copy(mode = ChromeGlassMode.Solid)
            .withBlur(0.05f)

        assertEquals(0.05f, tuned.blur, 0.001f)
        assertEquals(0.8f, tuned.liquid.blur, 0.001f)
        assertEquals(1.6f, tuned.liquid.refraction, 0.001f)
        assertEquals(0.2f, tuned.frosted.blur, 0.001f)
        assertEquals(ChromeGlassStyle.REFRACTION_DEFAULT, tuned.frosted.refraction, 0.001f)

        val liquid = tuned.copy(mode = ChromeGlassMode.Liquid)
        assertEquals(0.8f, liquid.blur, 0.001f)
        assertEquals(1.6f, liquid.refraction, 0.001f)
        assertEquals(0.2f, liquid.copy(mode = ChromeGlassMode.Frosted).blur, 0.001f)
    }

    @Test
    fun pluginOverrideTouchesOnlyTheTargetMode() {
        val user = ChromeGlassStyle.Default
            .withBlur(0.8f)
            .copy(mode = ChromeGlassMode.Frosted)
            .withBlur(0.2f)
        val over = LookGlassPartial(blur = 0.5f).applyTo(user)
        assertEquals(ChromeGlassMode.Frosted, over.mode)
        assertEquals(0.5f, over.blur, 0.001f)
        assertEquals(0.8f, over.liquid.blur, 0.001f)
        assertEquals(0.2f, user.frosted.blur, 0.001f)
    }
}

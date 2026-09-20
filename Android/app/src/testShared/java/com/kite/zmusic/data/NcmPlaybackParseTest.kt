package com.kite.zmusic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NcmPlaybackParseTest {
    @Test
    fun unblockedUrlReadsDataObject() {
        val json = JSONObject("""{"code":200,"data":{"url":"https://x.example/a.mp3","id":9}}""")
        assertEquals("https://x.example/a.mp3", NcmPlaybackParse.unblockedSongUrl(json, 9))
    }

    @Test
    fun unblockedUrlReadsDataArray() {
        val json = JSONObject("""{"code":200,"data":[{"id":9,"url":"https://x.example/b.mp3"}]}""")
        assertEquals("https://x.example/b.mp3", NcmPlaybackParse.unblockedSongUrl(json, 9))
    }

    @Test
    fun unblockedUrlEmptyWhenMissing() {
        val json = JSONObject("""{"code":200,"data":{"url":""}}""")
        assertNull(NcmPlaybackParse.unblockedSongUrl(json, 9))
    }
}

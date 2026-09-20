package com.kite.zmusic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongWikiLogicTest {
    @Test
    fun albumFromSongDetailReadsAl() {
        val json = JSONObject(
            """
            {"code":200,"songs":[{
              "id":1,
              "name":"Song",
              "ar":[{"id":2,"name":"Artist"}],
              "al":{"id":88,"name":"Album","picUrl":"http://p1.music.126.net/x.jpg"}
            }]}
            """.trimIndent(),
        )
        val album = SongWikiParse.albumFromSongDetail(json)
        assertNotNull(album)
        assertEquals(88L, album!!.id)
        assertEquals("Album", album.title)
        assertEquals("Artist", album.subtitle)
        assertTrue(album.coverUrl!!.startsWith("https://"))
    }

    @Test
    fun albumFromSongDetailMissingIdIsNull() {
        val json = JSONObject("""{"songs":[{"al":{"name":"Album"}}]}""")
        assertNull(SongWikiParse.albumFromSongDetail(json))
    }

    @Test
    fun pageWithOnlyAlbumIsNotEmpty() {
        val page = SongWikiPage(
            album = SongWikiCoverItem(1L, "A", "", null),
        )
        assertFalse(page.isEmpty)
    }
}

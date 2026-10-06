package com.kite.zmusic.data.platform

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiJsonTest {
    @Test
    fun recommendAndTracksAndSeo() {
        val discover = JSONObject(
            """
            {"inner_block":[{"resources":[{"entity":{"playlist":{
              "id":"7031088062088398879","title":"夏日","url_cover":"https://img.example/a.jpg"
            }}}]}]}
            """.trimIndent(),
        )
        val cards = QishuiJson.recommendPlaylists(discover)
        assertEquals(1, cards.size)
        assertEquals(7031088062088398879L, cards[0].id)
        assertEquals("夏日", cards[0].name)

        val detail = JSONObject(
            """
            {"media_resources":[{"entity":{"track_wrapper":{"track":{
              "id":"6718002440630700034","name":"easy","duration":180000,
              "artists":[{"name":"A"}],
              "album":{"name":"B","url_cover":"https://img.example/c.jpg"}
            }}}}]}
            """.trimIndent(),
        )
        val tracks = QishuiJson.playlistTracks(detail)
        assertEquals("easy", tracks.single().name)
        assertEquals(180000L, tracks.single().durationMs)
        assertEquals("A", tracks.single().artists)

        val seo = JSONObject(
            """
            {"lyric":{"content":"[00:01.00]hi"},
             "track_player":{"video_model":"{\"video_list\":[{\"main_url\":\"https://cdn.example/a.mp3\"}]}"}}
            """.trimIndent(),
        )
        assertEquals("https://cdn.example/a.mp3", QishuiJson.seoPlayUrl(seo))
        assertTrue(QishuiJson.seoLyric(seo)!!.contains("hi"))
    }

    @Test
    fun switchingLoginPlatformsRestarts() {
        assertTrue(MusicPlatform.NETEASE.needsRestartWhenSwitching(MusicPlatform.QISHUI))
        assertTrue(!MusicPlatform.KUWO.needsRestartWhenSwitching(MusicPlatform.KUGOU))
        assertTrue(!MusicPlatform.NETEASE.needsRestartWhenSwitching(MusicPlatform.KUWO))
        assertTrue(MusicPlatform.KUWO.needsRestartWhenSwitching(MusicPlatform.NETEASE))
    }
}

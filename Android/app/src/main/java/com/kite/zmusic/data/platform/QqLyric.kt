package com.kite.zmusic.data.platform

import com.kite.zmusic.data.LrcLine
import com.kite.zmusic.data.LrcParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

internal data class OpenLyricBundle(
    val original: List<LrcLine>,
    val translated: List<LrcLine>,
)

/**
 * QQ 访客歌词。优先解密逐字 QRC，失败再退回明文 LRC。
 */
internal object QqLyric {
    fun load(http: OkHttpClient, songId: Long, songMid: String): OpenLyricBundle? {
        val id = if (songId > 0L) songId else resolveId(http, songMid)
        if (id > 0L) {
            loadQrc(http, id)?.let { return it }
        }
        if (songMid.isNotBlank()) {
            loadPlain(http, songMid)?.let { return it }
        }
        return null
    }

    private fun loadQrc(http: OkHttpClient, songId: Long): OpenLyricBundle? {
        val body = post(
            http,
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            qrcPayload(songId),
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
        ) ?: return null
        val req = body.optJSONObject("req") ?: return null
        if (body.optInt("code") != 0 || req.optInt("code") != 0) return null
        val data = req.optJSONObject("data") ?: return null
        val original = QqQrc.parse(QqQrc.decode(data.optString("lyric")))
        if (original.isEmpty()) return null
        val translated = QqQrc.parse(QqQrc.decode(data.optString("trans")))
        return OpenLyricBundle(original, translated)
    }

    private fun loadPlain(http: OkHttpClient, songMid: String): OpenLyricBundle? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songMid&g_tk=5381&loginUin=0&hostUin=0&format=json" +
            "&inCharset=utf8&outCharset=utf-8&platform=yqq"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://y.qq.com/portal/player.html")
            .get()
            .build()
        val text = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null
        val body = JSONObject(text.substring(jsonStart, jsonEnd + 1))
        if (body.optInt("code") != 0) return null
        val original = LrcParser.parse(decodeBase64(body.optString("lyric")))
        if (original.isEmpty()) return null
        val translated = LrcParser.parse(decodeBase64(body.optString("trans")))
        return OpenLyricBundle(original, translated)
    }

    private fun resolveId(http: OkHttpClient, songMid: String): Long {
        if (songMid.isBlank()) return 0L
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"),
            )
            .put(
                "req",
                JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put(
                        "param",
                        JSONObject().put("song_type", 0).put("song_mid", songMid),
                    ),
            )
        val body = post(http, "https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString(), "Mozilla/5.0")
            ?: return 0L
        return body.optJSONObject("req")
            ?.optJSONObject("data")
            ?.optJSONObject("track_info")
            ?.optLong("id") ?: 0L
    }

    private fun qrcPayload(songId: Long): String {
        val param = JSONObject()
            .put("format", "json")
            .put("crypt", 1)
            .put("ct", 19)
            .put("cv", 1873)
            .put("interval", 0)
            .put("lrc_t", 0)
            .put("qrc", 1)
            .put("qrc_t", 0)
            .put("roma", 1)
            .put("roma_t", 0)
            .put("songID", songId)
            .put("trans", 1)
            .put("trans_t", 0)
            .put("type", -1)
        return JSONObject()
            .put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
            .put(
                "req",
                JSONObject()
                    .put("method", "GetPlayLyricInfo")
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("param", param),
            )
            .toString()
    }

    private fun post(http: OkHttpClient, url: String, json: String, userAgent: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://y.qq.com")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            }
        }.getOrNull()
    }

    private fun decodeBase64(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

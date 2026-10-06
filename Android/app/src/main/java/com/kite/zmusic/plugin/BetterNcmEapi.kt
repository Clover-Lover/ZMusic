package com.kite.zmusic.plugin

import com.kite.zmusic.data.NcmUserClient
import com.kite.zmusic.data.SessionRepository
import kotlinx.coroutines.runBlocking

/**
 * `loadedPlugins.LibEAPIRequest.eapiRequest` 的宿主实现。
 * 歌词、歌曲详情、播放地址、歌单详情走现有网易云接口；其余地址返回 code 502，不抛异常。
 */
internal class BetterNcmEapi(
    private val sessions: SessionRepository,
    private val ncm: NcmUserClient,
) {
    fun call(payload: String): String {
        val obj = PluginJson.parseObject(payload) ?: return """{"code":400}"""
        val url = obj["url"] as? String ?: ""
        val query = mapOf(obj["query"])
        val data = mapOf(obj["data"])
        val session = sessions.session.value
        if (session == null || session.isGuest || session.cookie.isBlank()) return """{"code":301}"""
        val cookie = session.cookie
        val kind = BetterNcmEapiRoutes.kind(url)
        return runCatching {
            runBlocking {
                when (kind) {
                    BetterNcmEapiRoutes.Kind.LYRIC -> {
                        val id = BetterNcmEapiRoutes.firstId(query, data)
                        if (id <= 0L) """{"code":400}""" else ncm.lyric(id, cookie).toString()
                    }
                    BetterNcmEapiRoutes.Kind.DETAIL -> {
                        val ids = BetterNcmEapiRoutes.ids(query, data)
                        if (ids.isEmpty()) """{"code":400}""" else ncm.songDetail(ids, cookie).toString()
                    }
                    BetterNcmEapiRoutes.Kind.URL -> {
                        val ids = BetterNcmEapiRoutes.ids(query, data)
                        val br = BetterNcmEapiRoutes.bitrate(query, data)
                        if (ids.isEmpty()) """{"code":400}""" else ncm.songUrl(ids, cookie, br).toString()
                    }
                    BetterNcmEapiRoutes.Kind.PLAYLIST -> {
                        val id = BetterNcmEapiRoutes.firstId(query, data)
                        if (id <= 0L) """{"code":400}""" else ncm.playlistDetail(id, cookie).toString()
                    }
                    BetterNcmEapiRoutes.Kind.OTHER -> """{"code":502,"msg":"unsupported"}"""
                }
            }
        }.getOrElse { """{"code":500}""" }
    }

    private fun mapOf(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        raw.forEach { (key, item) ->
            if (key is String) out[key] = item
        }
        return out
    }
}

internal object BetterNcmEapiRoutes {
    enum class Kind { LYRIC, DETAIL, URL, PLAYLIST, OTHER }

    fun kind(url: String): Kind {
        val path = url.lowercase()
        return when {
            path.contains("/song/lyric") -> Kind.LYRIC
            path.contains("/song/detail") -> Kind.DETAIL
            path.contains("/song/enhance/download/url") ||
                path.contains("/song/enhance/player/url") ||
                path.contains("/song/url") -> Kind.URL
            path.contains("/playlist/detail") -> Kind.PLAYLIST
            else -> Kind.OTHER
        }
    }

    fun firstId(query: Map<String, Any?>, data: Map<String, Any?>): Long =
        ids(query, data).firstOrNull() ?: 0L

    fun bitrate(query: Map<String, Any?>, data: Map<String, Any?>): Int {
        val raw = data["br"] ?: query["br"]
        val n = longOf(raw)?.toInt() ?: 320_000
        return n.coerceIn(1, 9_999_999)
    }

    fun ids(query: Map<String, Any?>, data: Map<String, Any?>): List<Long> {
        val direct = longs(query["ids"] ?: data["ids"] ?: query["id"] ?: data["id"])
        if (direct.isNotEmpty()) return direct
        val packed = data["c"] ?: return emptyList()
        val array = when (packed) {
            is String -> PluginJson.parse(packed) as? List<*>
            is List<*> -> packed
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> longOf(item["id"])
                else -> longOf(item)
            }
        }
    }

    private fun longs(value: Any?): List<Long> = when (value) {
        is String -> value.split(',').mapNotNull { it.trim().toLongOrNull() }
        else -> listOfNotNull(longOf(value))
    }

    private fun longOf(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Double -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

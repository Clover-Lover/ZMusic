package com.kite.zmusic.data.platform

import com.kite.zmusic.data.PlaylistSummary
import com.kite.zmusic.data.RecommendPlaylistCard
import com.kite.zmusic.data.TrackRow
import com.kite.zmusic.data.UserProfileBrief
import org.json.JSONArray
import org.json.JSONObject

internal object QishuiJson {
    fun recommendPlaylists(body: JSONObject): List<RecommendPlaylistCard> {
        val blocks = body.optJSONArray("inner_block") ?: return emptyList()
        val out = ArrayList<RecommendPlaylistCard>()
        for (i in 0 until blocks.length()) {
            val resources = blocks.optJSONObject(i)?.optJSONArray("resources") ?: continue
            collectPlaylists(resources, out)
        }
        return out
    }

    fun playlistTracks(body: JSONObject): List<TrackRow> {
        val resources = body.optJSONArray("media_resources") ?: return emptyList()
        val out = ArrayList<TrackRow>()
        for (i in 0 until resources.length()) {
            val item = resources.optJSONObject(i) ?: continue
            val track = item.optJSONObject("entity")
                ?.optJSONObject("track_wrapper")
                ?.optJSONObject("track")
                ?: item.optJSONObject("entity")?.optJSONObject("track")
                ?: continue
            trackRow(track)?.let(out::add)
        }
        return out
    }

    fun myPlaylists(body: JSONObject): List<PlaylistSummary> {
        val list = body.optJSONArray("playlists") ?: return emptyList()
        val out = ArrayList<PlaylistSummary>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").toLongOrNull() ?: continue
            if (id <= 0L) continue
            out.add(
                PlaylistSummary(
                    id = id,
                    name = item.optString("title").ifBlank { item.optString("name") }.ifBlank { "歌单" },
                    coverUrl = cover(item),
                    trackCount = item.optInt("count_tracks", item.optInt("track_count", 0)),
                    isHeartPlaylist = false,
                    isOwned = true,
                    isSubscribed = false,
                    playCount = 0L,
                ),
            )
        }
        return out
    }

    fun profile(body: JSONObject, fallbackName: String): UserProfileBrief? {
        val info = body.optJSONObject("my_info") ?: body.optJSONObject("user") ?: body
        val id = info.optString("id").toLongOrNull() ?: return null
        val name = info.optString("nickname").ifBlank { info.optString("name") }.ifBlank { fallbackName }
        return UserProfileBrief(
            userId = id,
            nickname = name,
            avatarUrl = null,
            signature = null,
            level = null,
            listenSongs = null,
            vipKind = if (info.optBoolean("is_vip")) {
                com.kite.zmusic.data.VipKind.Vip
            } else {
                com.kite.zmusic.data.VipKind.None
            },
        )
    }

    fun seoPlayUrl(body: JSONObject): String? {
        val model = body.optJSONObject("track_player")?.optString("video_model").orEmpty()
        if (model.isBlank()) return null
        val parsed = runCatching { JSONObject(model) }.getOrNull() ?: return null
        val list = parsed.optJSONArray("video_list") ?: return null
        val first = list.optJSONObject(0) ?: return null
        return first.optString("main_url").ifBlank { first.optString("backup_url") }.takeIf { it.startsWith("http") }
    }

    fun seoLyric(body: JSONObject): String? =
        body.optJSONObject("lyric")?.optString("content")?.takeIf { it.isNotBlank() }

    private fun collectPlaylists(resources: JSONArray, out: MutableList<RecommendPlaylistCard>) {
        for (i in 0 until resources.length()) {
            val playlist = resources.optJSONObject(i)
                ?.optJSONObject("entity")
                ?.optJSONObject("playlist")
                ?: continue
            val id = playlist.optString("id").toLongOrNull() ?: continue
            if (id <= 0L) continue
            out.add(
                RecommendPlaylistCard(
                    id = id,
                    name = playlist.optString("title").ifBlank { playlist.optString("name") },
                    coverUrl = cover(playlist),
                    playCount = 0L,
                ),
            )
        }
    }

    private fun trackRow(track: JSONObject): TrackRow? {
        val id = track.optString("id").ifBlank { track.optLong("id").toString() }.toLongOrNull() ?: return null
        if (id <= 0L) return null
        val artists = track.optJSONArray("artists")
        val names = ArrayList<String>()
        if (artists != null) {
            for (i in 0 until artists.length()) {
                val name = artists.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
        }
        val album = track.optJSONObject("album")
        val duration = track.optLong("duration", track.optLong("duration_ms", 0L))
        return TrackRow(
            id = id,
            name = track.optString("name").ifBlank { "未命名" },
            artists = names.joinToString(" / ").ifBlank { "未知艺术家" },
            album = album?.optString("name")?.takeIf { it.isNotBlank() },
            durationMs = if (duration in 1..10_000) duration * 1000 else duration,
            coverUrl = album?.let { cover(it) },
        )
    }

    private fun cover(obj: JSONObject): String? {
        val raw = obj.opt("url_cover") ?: obj.opt("cover_url") ?: obj.opt("cover") ?: return null
        return when (raw) {
            is String -> raw.takeIf { it.startsWith("http") }
            is JSONObject -> raw.optString("url").ifBlank { raw.optString("uri") }.takeIf { it.startsWith("http") }
            else -> null
        }
    }
}

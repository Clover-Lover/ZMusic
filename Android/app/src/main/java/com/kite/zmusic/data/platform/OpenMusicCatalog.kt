package com.kite.zmusic.data.platform

import com.kite.zmusic.data.RecommendPlaylistCard
import com.kite.zmusic.data.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 没有登录的平台，只拉公开曲库：榜单或推荐歌单，以及歌单里的歌、搜索。
 * 不发播放地址，也不进个人账号。
 */
class OpenMusicCatalog(
    private val http: OkHttpClient,
) {
    private val browse = OpenMusicBrowse(http)
    suspend fun homePlaylists(platform: MusicPlatform): List<RecommendPlaylistCard> = withContext(Dispatchers.IO) {
        when (platform) {
            MusicPlatform.KUWO -> kuwoPlaylists()
            MusicPlatform.KUGOU -> kugouBoards()
            MusicPlatform.QQ -> qqBoards()
            else -> emptyList()
        }
    }

    suspend fun playlistTracks(platform: MusicPlatform, playlistId: Long): List<TrackRow> = withContext(Dispatchers.IO) {
        when (platform) {
            MusicPlatform.KUWO -> kuwoTracks(playlistId)
            MusicPlatform.KUGOU -> if (playlistId >= 300_000L) browse.kugouPlaylistTracks(playlistId) else kugouTracks(playlistId)
            MusicPlatform.QQ -> if (playlistId >= 100_000L) browse.qqPlaylistTracks(playlistId) else qqTracks(playlistId)
            else -> emptyList()
        }
    }

    suspend fun searchSongs(platform: MusicPlatform, keyword: String, page: Int = 1): List<TrackRow> = withContext(Dispatchers.IO) {
        when (platform) {
            MusicPlatform.KUWO -> kuwoSearch(keyword, page)
            MusicPlatform.KUGOU -> kugouSearch(keyword, page)
            MusicPlatform.QQ -> qqSearch(keyword, page)
            else -> emptyList()
        }
    }

    suspend fun hotWords(platform: MusicPlatform): List<String> = withContext(Dispatchers.IO) {
        browse.hotWords(platform)
    }

    suspend fun suggest(platform: MusicPlatform, keyword: String): List<String> = withContext(Dispatchers.IO) {
        browse.suggest(platform, keyword)
    }

    suspend fun searchPlaylists(platform: MusicPlatform, keyword: String, page: Int = 1) = withContext(Dispatchers.IO) {
        browse.searchPlaylists(platform, keyword, page)
    }

    suspend fun searchAlbums(platform: MusicPlatform, keyword: String, page: Int = 1) = withContext(Dispatchers.IO) {
        browse.searchAlbums(platform, keyword, page)
    }

    suspend fun searchArtists(platform: MusicPlatform, keyword: String, page: Int = 1) = withContext(Dispatchers.IO) {
        browse.searchArtists(platform, keyword, page)
    }

    suspend fun album(platform: MusicPlatform, albumId: Long) = withContext(Dispatchers.IO) {
        browse.album(platform, albumId)
    }

    suspend fun artistSongs(platform: MusicPlatform, artistId: Long, page: Int = 1): List<TrackRow> = withContext(Dispatchers.IO) {
        browse.artistSongs(platform, artistId, page)
    }

    suspend fun comments(platform: MusicPlatform, track: TrackRow, page: Int, pageSize: Int) = withContext(Dispatchers.IO) {
        browse.comments(platform, track, page, pageSize)
    }

    fun supportsSearch(platform: MusicPlatform): Boolean =
        platform == MusicPlatform.KUWO || platform == MusicPlatform.KUGOU || platform == MusicPlatform.QQ

    internal fun qqLyrics(songId: Long, songMid: String): OpenLyricBundle? =
        QqLyric.load(http, songId, songMid)

    internal fun kuwoLyrics(rid: String): OpenLyricBundle? = KuwoLyric.load(http, rid)

    internal fun kugouLyrics(name: String, hash: String, durationMs: Long): OpenLyricBundle? =
        KugouLyric.load(http, name, hash, durationMs)

    private fun kuwoPlaylists(): List<RecommendPlaylistCard> {
        val body = get(
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=1&rn=12&order=hot",
        )
        val list = body.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id").toLongOrNull() ?: continue
                add(
                    RecommendPlaylistCard(
                        id = id,
                        name = item.optString("name"),
                        coverUrl = httpsCover(item.optString("img")),
                        playCount = item.optLong("listencnt"),
                    ),
                )
            }
        }
    }

    private fun kuwoTracks(playlistId: Long): List<TrackRow> {
        val body = get(
            "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId&pn=0&rn=40&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1",
        )
        val list = body.optJSONArray("musiclist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id").toLongOrNull() ?: continue
                add(
                    TrackRow(
                        id = id,
                        name = item.optString("name").ifBlank { "未命名" },
                        artists = item.optString("artist").ifBlank { "未知艺术家" },
                        album = item.optString("album").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("duration") * 1000,
                        coverUrl = httpsCover(item.optString("albumpic")),
                        sourceId = id.toString(),
                    ),
                )
            }
        }
    }

    private fun kuwoSearch(keyword: String, page: Int): List<TrackRow> {
        val encoded = java.net.URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val body = get(
            "http://search.kuwo.cn/r.s?client=kt&all=$encoded&pn=${(page - 1).coerceAtLeast(0)}&rn=30&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1",
        )
        val list = body.optJSONArray("abslist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("MUSICRID").removePrefix("MUSIC_").toLongOrNull() ?: continue
                add(
                    TrackRow(
                        id = id,
                        name = item.optString("SONGNAME").ifBlank { "未命名" },
                        artists = item.optString("ARTIST").ifBlank { "未知艺术家" },
                        album = item.optString("ALBUM").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("DURATION") * 1000,
                        coverUrl = httpsCover(item.optString("web_albumpic_short").ifBlank { item.optString("hts_MVPIC") }),
                        sourceId = id.toString(),
                    ),
                )
            }
        }
    }

    private fun kugouBoards(): List<RecommendPlaylistCard> {
        val body = get(
            "http://mobilecdnbj.kugou.com/api/v5/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=1",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length().coerceAtMost(12)) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("rankid")
                if (id <= 0L) continue
                val cover = httpsCover(item.optString("img_9").ifBlank { item.optString("banner_9") })
                add(
                    RecommendPlaylistCard(
                        id = id,
                        name = item.optString("rankname"),
                        coverUrl = cover,
                        playCount = item.optLong("play_times"),
                    ),
                )
            }
        }
    }

    private fun kugouTracks(rankId: Long): List<TrackRow> {
        val body = get(
            "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0&pagesize=40&area_code=1&page=1&rankid=$rankId&with_res_tag=0",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("album_audio_id")
                if (id <= 0L) continue
                val filename = item.optString("filename")
                val parts = filename.split(" - ", limit = 2)
                add(
                    TrackRow(
                        id = id,
                        name = parts.getOrNull(1)?.ifBlank { null } ?: filename.ifBlank { "未命名" },
                        artists = parts.getOrNull(0)?.ifBlank { null } ?: "未知艺术家",
                        album = item.optString("album_name").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("duration") * 1000,
                        coverUrl = httpsCover(item.optString("album_sizable_cover")),
                        sourceId = id.toString(),
                        sourceHash = item.optString("hash").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun kugouSearch(keyword: String, page: Int): List<TrackRow> {
        val encoded = java.net.URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val body = get(
            "https://songsearch.kugou.com/song_search_v2?keyword=$encoded&page=${page.coerceAtLeast(1)}&pagesize=30&userid=0&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1",
        )
        val list = body.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("MixSongID")
                if (id <= 0L) continue
                add(
                    TrackRow(
                        id = id,
                        name = item.optString("SongName").ifBlank { "未命名" },
                        artists = item.optString("SingerName").ifBlank { "未知艺术家" },
                        album = item.optString("AlbumName").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("Duration") * 1000,
                        coverUrl = httpsCover(item.optString("Image").ifBlank { item.optString("AlbumCover") }),
                        sourceId = id.toString(),
                        sourceHash = item.optString("FileHash").ifBlank { item.optString("HQFileHash") }.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun qqBoards(): List<RecommendPlaylistCard> {
        val body = get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?g_tk=1928093487&inCharset=utf-8&outCharset=utf-8&notice=0&format=json&uin=0&needNewCode=1&platform=h5",
        )
        val list = body.optJSONObject("data")?.optJSONArray("topList") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length().coerceAtMost(12)) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id <= 0L || id == 201L) continue
                add(
                    RecommendPlaylistCard(
                        id = id,
                        name = item.optString("topTitle"),
                        coverUrl = httpsCover(item.optString("picUrl")),
                        playCount = item.optLong("listenCount"),
                    ),
                )
            }
        }
    }

    private fun qqTracks(topId: Long): List<TrackRow> {
        val body = get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$topId&page=1&song_begin=0&song_num=40&format=json",
        )
        val list = body.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val data = list.optJSONObject(i)?.optJSONObject("data") ?: continue
                val mid = data.optString("songmid")
                if (mid.isBlank()) continue
                val singers = data.optJSONArray("singer")
                val artist = buildString {
                    if (singers != null) {
                        for (s in 0 until singers.length()) {
                            val name = singers.optJSONObject(s)?.optString("name").orEmpty()
                            if (name.isBlank()) continue
                            if (isNotEmpty()) append(" / ")
                            append(name)
                        }
                    }
                }
                val albumMid = data.optString("albummid")
                add(
                    TrackRow(
                        id = stableId(mid),
                        name = data.optString("songname").ifBlank { "未命名" },
                        artists = artist.ifBlank { "未知艺术家" },
                        album = data.optString("albumname").takeIf { it.isNotBlank() },
                        durationMs = data.optLong("interval") * 1000,
                        coverUrl = albumMid.takeIf { it.isNotBlank() }?.let {
                            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg"
                        },
                        sourceId = mid,
                        albumMid = albumMid.takeIf { it.isNotBlank() },
                        sourceSongId = data.optLong("songid"),
                        sourceHash = data.optString("strMediaMid").ifBlank { data.optString("media_mid") }
                            .takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun qqSearch(keyword: String, page: Int): List<TrackRow> {
        val payload = qqSearchPayload(keyword, page)
        val text = payload.toString()
        val body = post(
            "https://u.y.qq.com/cgi-bin/musics.fcg?sign=${qqSign(text)}",
            text,
            "QQMusic 14090508(android 12)",
        )
        val data = body.optJSONObject("music.search.SearchCgiService")?.optJSONObject("data")
        val list = data?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val mid = item.optString("mid")
                if (mid.isBlank()) continue
                val singers = item.optJSONArray("singer")
                val artist = buildString {
                    if (singers != null) {
                        for (s in 0 until singers.length()) {
                            val name = singers.optJSONObject(s)?.optString("name").orEmpty()
                            if (name.isBlank()) continue
                            if (isNotEmpty()) append(" / ")
                            append(name)
                        }
                    }
                }
                val album = item.optJSONObject("album")
                val albumMid = album?.optString("mid").orEmpty()
                val mediaMid = item.optJSONObject("file")?.optString("media_mid").orEmpty()
                add(
                    TrackRow(
                        id = stableId(mid),
                        name = item.optString("title").ifBlank { "未命名" },
                        artists = artist.ifBlank { "未知艺术家" },
                        album = album?.optString("name")?.takeIf { it.isNotBlank() },
                        durationMs = item.optLong("interval") * 1000,
                        coverUrl = albumMid.takeIf { it.isNotBlank() && it != "空" }?.let {
                            "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg"
                        },
                        sourceId = mid,
                        albumMid = albumMid.takeIf { it.isNotBlank() && it != "空" },
                        sourceSongId = item.optLong("id"),
                        sourceHash = mediaMid.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun qqSearchPayload(keyword: String, page: Int): JSONObject {
        val comm = JSONObject()
            .put("_channelid", "0")
            .put("_os_version", "6.2.9200-2")
            .put("ct", "19")
            .put("cv", "2151")
            .put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
            .put("patch", "118")
            .put("psrf_access_token_expiresAt", 0)
            .put("psrf_qqaccess_token", "")
            .put("psrf_qqopenid", "")
            .put("psrf_qqunionid", "")
            .put("tmeAppID", "qqmusic")
            .put("tmeLoginType", 0)
            .put("uin", "0")
            .put("wid", "7223299733393904640")
        val param = JSONObject()
            .put("grp", 1)
            .put("num_per_page", 30)
            .put("page_num", page.coerceAtLeast(1))
            .put("query", keyword)
            .put("remoteplace", "txt.newclient.top")
            .put("search_type", 0)
            .put("searchid", qqSearchId())
        val service = JSONObject()
            .put("module", "music.search.SearchCgiService")
            .put("method", "DoSearchForQQMusicDesktop")
            .put("param", param)
        return JSONObject()
            .put("comm", comm)
            .put("music.search.SearchCgiService", service)
    }

    private fun post(url: String, json: String, userAgent: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("曲库请求失败 ${resp.code}")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("曲库请求失败 ${resp.code}")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

        private fun qqSearchId(): String {
            val alphabet = "0123456789ABCDEF"
            val guid = buildString(32) {
                repeat(32) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
            }
            return guid + kotlin.random.Random.nextInt(100000).toString().padStart(5, '0')
        }

        private fun qqSign(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString("") { "%02x".format(it) }
            fun pick(indexes: IntArray) = buildString {
                for (index in indexes) if (index < hash.length) append(hash[index])
            }
            val mixed = ByteArray(QQ_SCRAMBLE.size) { index ->
                (QQ_SCRAMBLE[index] xor hash.substring(index * 2, index * 2 + 2).toInt(16)).toByte()
            }
            val encoded = android.util.Base64.encodeToString(mixed, android.util.Base64.NO_WRAP)
                .replace(Regex("[\\\\/+=]"), "")
            return "zzc${pick(QQ_PART_1)}$encoded${pick(QQ_PART_2)}".lowercase()
        }

        private val QQ_PART_1 = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
        private val QQ_PART_2 = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        private val QQ_SCRAMBLE = intArrayOf(
            89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179,
        )

        private fun httpsCover(raw: String?): String? {
            val value = raw?.trim().orEmpty().replace("{size}", "400")
            if (value.isBlank()) return null
            return when {
                value.startsWith("https://") -> value
                value.startsWith("http://") -> "https://" + value.removePrefix("http://")
                value.startsWith("//") -> "https:$value"
                else -> null
            }
        }

        val homeUnavailable = setOf(
            HomeBlock.Banner,
            HomeBlock.DailySongs,
            HomeBlock.DailyPlaylists,
            HomeBlock.NewSongs,
            HomeBlock.Mvs,
        )

        val libraryUnavailable = listOf(
            "我喜欢的音乐",
            "创建的歌单",
            "收藏的专辑",
            "听歌时长",
            "关注与粉丝",
        )

        fun stableId(text: String): Long {
            var hash = 1125899906842597L
            for (ch in text) hash = 31L * hash + ch.code
            val mixed = hash xor (hash ushr 33)
            return mixed and Long.MAX_VALUE
        }
    }
}

package com.kite.zmusic.data.platform

import com.kite.zmusic.data.AlbumBrief
import com.kite.zmusic.data.CollectedAlbum
import com.kite.zmusic.data.SearchArtistHit
import com.kite.zmusic.data.SearchPlaylistHit
import com.kite.zmusic.data.SongComment
import com.kite.zmusic.data.SongCommentPage
import com.kite.zmusic.data.TrackRow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * 酷我、酷狗、QQ 的公开曲库：热搜、歌单、专辑、歌手和评论。
 */
internal class OpenMusicBrowse(
    private val http: OkHttpClient,
) {
    fun hotWords(platform: MusicPlatform): List<String> = when (platform) {
        MusicPlatform.KUWO -> kuwoHotWords()
        MusicPlatform.KUGOU -> kugouHotWords()
        MusicPlatform.QQ -> qqHotWords()
        else -> emptyList()
    }

    fun suggest(platform: MusicPlatform, keyword: String): List<String> {
        if (platform != MusicPlatform.KUWO || keyword.isBlank()) return emptyList()
        val encoded = encode(keyword)
        val body = getJson(
            "https://tips.kuwo.cn/t.s?corp=kuwo&newver=3&p2p=1&notrace=0&c=mbox&w=$encoded&encoding=utf8&rformat=json",
            mapOf("Referer" to "http://www.kuwo.cn/"),
        )
        val list = body.optJSONArray("WORDITEMS") ?: return emptyList()
        return strings(list, "RELWORD")
    }

    fun searchPlaylists(platform: MusicPlatform, keyword: String, page: Int): List<SearchPlaylistHit> {
        if (keyword.isBlank()) return emptyList()
        return when (platform) {
            MusicPlatform.KUWO -> kuwoPlaylists(keyword, page)
            MusicPlatform.KUGOU -> kugouPlaylists(keyword, page)
            MusicPlatform.QQ -> qqPlaylists(keyword, page)
            else -> emptyList()
        }
    }

    fun searchAlbums(platform: MusicPlatform, keyword: String, page: Int): List<CollectedAlbum> {
        if (keyword.isBlank()) return emptyList()
        return when (platform) {
            MusicPlatform.KUWO -> kuwoAlbums(keyword, page)
            MusicPlatform.KUGOU -> kugouAlbums(keyword, page)
            else -> emptyList()
        }
    }

    fun searchArtists(platform: MusicPlatform, keyword: String, page: Int): List<SearchArtistHit> {
        if (platform != MusicPlatform.KUGOU || keyword.isBlank()) return emptyList()
        val encoded = encode(keyword)
        val body = getJson(
            "http://mobilecdn.kugou.com/api/v3/search/singer?keyword=$encoded&page=${page.coerceAtLeast(1)}&pagesize=30&version=9108",
        )
        val list = body.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("singerid")
                if (id <= 0L) continue
                add(
                    SearchArtistHit(
                        id = id,
                        name = item.optString("singername").ifBlank { "未知艺术家" },
                        coverUrl = httpsCover(item.optString("imgurl")),
                    ),
                )
            }
        }
    }

    fun album(platform: MusicPlatform, albumId: Long): AlbumBrief? {
        if (albumId <= 0L) return null
        return when (platform) {
            MusicPlatform.KUWO -> kuwoAlbum(albumId)
            MusicPlatform.KUGOU -> kugouAlbum(albumId)
            else -> null
        }
    }

    fun artistSongs(platform: MusicPlatform, artistId: Long, page: Int): List<TrackRow> {
        if (platform != MusicPlatform.KUGOU || artistId <= 0L) return emptyList()
        val body = getJson(
            "http://mobiles.kugou.com/api/v5/singer/song?singerid=$artistId&page=${page.coerceAtLeast(1)}&pagesize=30",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return kugouRows(list)
    }

    fun kugouPlaylistTracks(playlistId: Long): List<TrackRow> {
        val body = getJson(
            "http://mobilecdnbj.kugou.com/api/v3/special/song?version=9108&specialid=$playlistId&plat=0&pagesize=100&page=1&area_code=1",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return kugouRows(list)
    }

    fun qqPlaylistTracks(playlistId: Long): List<TrackRow> {
        val param = JSONObject()
            .put("disstid", playlistId)
            .put("userinfo", 1)
            .put("tag", 1)
            .put("orderlist", 1)
            .put("song_begin", 0)
            .put("song_num", 100)
            .put("onlysonglist", 0)
            .put("enc_host_uin", "")
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("cv", 4747474)
                    .put("ct", 24)
                    .put("format", "json")
                    .put("inCharset", "utf-8")
                    .put("outCharset", "utf-8")
                    .put("platform", "yqq.json")
                    .put("needNewCode", 1)
                    .put("uin", 0),
            )
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.srfDissInfo.aiDissInfo")
                    .put("method", "uniform_get_Dissinfo")
                    .put("param", param),
            )
        val body = postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            payload.toString(),
            mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com"),
        )
        val list = body.optJSONObject("req_1")?.optJSONObject("data")?.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val mid = item.optString("mid")
                if (mid.isBlank()) continue
                val album = item.optJSONObject("album")
                val albumMid = album?.optString("mid").orEmpty()
                add(
                    TrackRow(
                        id = OpenMusicCatalog.stableId(mid),
                        name = item.optString("title").ifBlank { item.optString("name") }.ifBlank { "未命名" },
                        artists = singerNames(item.optJSONArray("singer")),
                        album = album?.optString("name")?.takeIf { it.isNotBlank() && it != "空" },
                        durationMs = item.optLong("interval") * 1000,
                        coverUrl = albumMid.takeIf { it.isNotBlank() && it != "空" }?.let {
                            "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg"
                        },
                        sourceId = mid,
                        albumMid = albumMid.takeIf { it.isNotBlank() && it != "空" },
                        sourceSongId = item.optLong("id"),
                        sourceHash = item.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    fun comments(platform: MusicPlatform, track: TrackRow, page: Int, pageSize: Int): SongCommentPage {
        return when (platform) {
            MusicPlatform.KUWO -> kuwoComments(track.sourceId?.ifBlank { null } ?: track.id.toString(), page, pageSize)
            MusicPlatform.KUGOU -> kugouComments(track.sourceHash.orEmpty(), page, pageSize)
            MusicPlatform.QQ -> qqComments(track.sourceSongId, page, pageSize)
            else -> SongCommentPage(emptyList(), 0L, false, null)
        }
    }

    private fun kuwoHotWords(): List<String> {
        val body = getJson(
            "http://hotword.kuwo.cn/hotword.s?prod=kwplayer_ar_9.3.0.1&corp=kuwo&newver=2&vipver=9.3.0.1&source=kwplayer_ar_9.3.0.1_40.apk&p2p=1&notrace=0&uid=0&plat=kwplayer_ar&rformat=json&encoding=utf8&tabid=1",
            mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9;)"),
        )
        return strings(body.optJSONArray("tagvalue"), "key")
    }

    private fun kugouHotWords(): List<String> {
        val body = getJson(
            "http://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0",
            mapOf(
                "dfid" to "1ssiv93oVqMp27cirf2CvoF1",
                "mid" to "156798703528610303473757548878786007104",
                "clienttime" to "1584257267",
                "x-router" to "msearch.kugou.com",
                "User-Agent" to "Android9-AndroidPhone-10020-130-0-searchrecommendprotocol-wifi",
                "kg-rc" to "1",
            ),
        )
        val groups = body.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until groups.length()) {
                val words = groups.optJSONObject(i)?.optJSONArray("keywords") ?: continue
                for (j in 0 until words.length()) {
                    val word = words.optJSONObject(j)?.optString("keyword").orEmpty()
                    if (word.isNotBlank()) add(word)
                }
            }
        }
    }

    private fun qqHotWords(): List<String> {
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", "19")
                    .put("cv", "1803")
                    .put("guid", "0")
                    .put("patch", "118")
                    .put("psrf_access_token_expiresAt", 0)
                    .put("psrf_qqaccess_token", "")
                    .put("psrf_qqopenid", "")
                    .put("psrf_qqunionid", "")
                    .put("tmeAppID", "qqmusic")
                    .put("tmeLoginType", 0)
                    .put("uin", "0")
                    .put("wid", "0"),
            )
            .put(
                "hotkey",
                JSONObject()
                    .put("method", "GetHotkeyForQQMusicPC")
                    .put("module", "tencent_musicsoso_hotkey.HotkeyService")
                    .put("param", JSONObject().put("search_id", "").put("uin", 0)),
            )
        val body = postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            payload.toString(),
            mapOf("Referer" to "https://y.qq.com/portal/player.html"),
        )
        val list = body.optJSONObject("hotkey")?.optJSONObject("data")?.optJSONArray("vec_hotkey") ?: return emptyList()
        return strings(list, "query")
    }

    private fun kuwoPlaylists(keyword: String, page: Int): List<SearchPlaylistHit> {
        val encoded = encode(keyword)
        val pn = (page - 1).coerceAtLeast(0)
        val body = loose(
            getText("http://search.kuwo.cn/r.s?all=$encoded&pn=$pn&rn=30&rformat=json&encoding=utf8&ver=mbox&vipver=MUSIC_8.7.7.0_BCS37&plat=pc&devid=28156413&ft=playlist&pay=0&needliveshow=0"),
        )
        val list = body.optJSONArray("abslist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("playlistid").toLongOrNull()
                    ?: item.optString("DC_TARGETID").toLongOrNull()
                    ?: continue
                add(
                    SearchPlaylistHit(
                        id = id,
                        name = item.optString("name").ifBlank { "未命名歌单" },
                        coverUrl = httpsCover(item.optString("hts_pic").ifBlank { item.optString("pic") }),
                        playCount = item.optString("playcnt").toLongOrNull() ?: 0L,
                        trackCount = item.optString("songnum").toIntOrNull() ?: 0,
                        creator = item.optString("nickname").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun kugouPlaylists(keyword: String, page: Int): List<SearchPlaylistHit> {
        val encoded = encode(keyword)
        val body = getJson(
            "http://msearchretry.kugou.com/api/v3/search/special?keyword=$encoded&page=${page.coerceAtLeast(1)}&pagesize=30&showtype=10&filter=0&version=7910&sver=2",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("specialid")
                if (id <= 0L) continue
                add(
                    SearchPlaylistHit(
                        id = id,
                        name = item.optString("specialname").ifBlank { "未命名歌单" },
                        coverUrl = httpsCover(item.optString("imgurl").ifBlank { item.optString("img") }),
                        playCount = item.optLong("playcount"),
                        trackCount = item.optInt("songcount"),
                        creator = item.optString("nickname").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun qqPlaylists(keyword: String, page: Int): List<SearchPlaylistHit> {
        val encoded = encode(keyword)
        val index = (page - 1).coerceAtLeast(0)
        val body = getJson(
            "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=$index&num_per_page=30&format=json&query=$encoded&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
                "Referer" to "https://y.qq.com/portal/search.html",
            ),
        )
        val list = body.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("dissid")
                if (id <= 0L) continue
                add(
                    SearchPlaylistHit(
                        id = id,
                        name = item.optString("dissname").ifBlank { "未命名歌单" },
                        coverUrl = httpsCover(item.optString("imgurl").ifBlank { item.optString("logo") }),
                        playCount = item.optLong("listennum"),
                        trackCount = item.optInt("song_count").takeIf { it > 0 } ?: item.optInt("songnum"),
                        creator = item.optJSONObject("creator")?.optString("name")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun kuwoAlbums(keyword: String, page: Int): List<CollectedAlbum> {
        val encoded = encode(keyword)
        val pn = (page - 1).coerceAtLeast(0)
        val body = loose(
            getText("http://search.kuwo.cn/r.s?all=$encoded&pn=$pn&rn=30&rformat=json&encoding=utf8&ft=album&client=kt&vipver=1&pay=0&needliveshow=0"),
        )
        val list = body.optJSONArray("albumlist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("albumid").toLongOrNull()
                    ?: item.optString("DC_TARGETID").toLongOrNull()
                    ?: continue
                add(
                    CollectedAlbum(
                        id = id,
                        name = item.optString("name").ifBlank { item.optString("album") }.ifBlank { "未命名专辑" },
                        coverUrl = httpsCover(item.optString("hts_img").ifBlank { item.optString("img") }),
                        artist = decodeBasic(item.optString("artist").ifBlank { item.optString("aartist") }),
                        artistId = item.optString("artistid").toLongOrNull() ?: 0L,
                        size = item.optString("musicnum").toIntOrNull() ?: 0,
                    ),
                )
            }
        }
    }

    private fun kugouAlbums(keyword: String, page: Int): List<CollectedAlbum> {
        val encoded = encode(keyword)
        val body = getJson(
            "http://msearchretry.kugou.com/api/v3/search/album?keyword=$encoded&page=${page.coerceAtLeast(1)}&pagesize=30&version=9108",
        )
        val list = body.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optLong("albumid")
                if (id <= 0L) continue
                add(
                    CollectedAlbum(
                        id = id,
                        name = item.optString("albumname").ifBlank { item.optString("album_name") }.ifBlank { "未命名专辑" },
                        coverUrl = httpsCover(item.optString("imgurl").ifBlank { item.optString("img") }),
                        artist = item.optString("singername").takeIf { it.isNotBlank() },
                        size = item.optInt("songcount"),
                    ),
                )
            }
        }
    }

    private fun kuwoAlbum(albumId: Long): AlbumBrief? {
        val body = loose(
            getText("http://search.kuwo.cn/r.s?pn=0&rn=200&stype=albuminfo&albumid=$albumId&show_copyright_off=0&encoding=utf&vipver=MUSIC_9.1.0"),
        )
        val list = body.optJSONArray("musiclist") ?: return null
        val songs = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id").toLongOrNull()
                    ?: item.optString("MUSICRID").removePrefix("MUSIC_").toLongOrNull()
                    ?: continue
                val rawDuration = item.optLong("duration").takeIf { it > 0L }
                    ?: item.optString("duration").toLongOrNull()
                    ?: item.optString("DURATION").toLongOrNull()
                    ?: 0L
                add(
                    TrackRow(
                        id = id,
                        name = item.optString("name").ifBlank { item.optString("SONGNAME") }.ifBlank { "未命名" },
                        artists = item.optString("artist").ifBlank { item.optString("ARTIST") }.ifBlank { "未知艺术家" },
                        album = body.optString("name").takeIf { it.isNotBlank() },
                        durationMs = rawDuration,
                        coverUrl = httpsCover(body.optString("hts_img").ifBlank { body.optString("img") }),
                        sourceId = id.toString(),
                    ),
                )
            }
        }
        if (songs.isEmpty()) return null
        return AlbumBrief(
            id = albumId,
            name = body.optString("name").ifBlank { "未命名专辑" },
            coverUrl = httpsCover(body.optString("hts_img").ifBlank { body.optString("img") }),
            artist = decodeBasic(body.optString("artist")),
            artistId = body.optString("artistid").toLongOrNull() ?: 0L,
            songs = songs.map { song ->
                val duration = song.durationMs
                song.copy(durationMs = if (duration in 1..999) duration * 1000 else duration)
            },
            company = body.optString("company").takeIf { it.isNotBlank() },
            description = body.optString("info").takeIf { it.isNotBlank() },
            size = body.optString("songnum").toIntOrNull() ?: songs.size,
        )
    }

    private fun kugouAlbum(albumId: Long): AlbumBrief? {
        val body = getJson(
            "http://mobiles.kugou.com/api/v3/album/song?version=9108&albumid=$albumId&plat=0&pagesize=200&area_code=0&page=1&with_res_tag=0",
        )
        val data = body.optJSONObject("data") ?: return null
        val songs = kugouRows(data.optJSONArray("info"))
        if (songs.isEmpty()) return null
        return AlbumBrief(
            id = albumId,
            name = songs.firstOrNull()?.album ?: "专辑",
            coverUrl = songs.firstOrNull()?.coverUrl,
            artist = songs.firstOrNull()?.artists,
            songs = songs,
            size = data.optInt("total").takeIf { it > 0 } ?: songs.size,
        )
    }

    private fun kuwoComments(rid: String, page: Int, pageSize: Int): SongCommentPage {
        if (rid.isBlank()) return SongCommentPage(emptyList(), 0L, false, null)
        val start = ((page - 1).coerceAtLeast(0)) * pageSize
        val body = getJson(
            "http://ncomment.kuwo.cn/com.s?f=web&type=get_comment&aapiver=1&prod=kwplayer_ar_10.5.2.0&digest=15&sid=$rid&start=$start&msgflag=1&count=$pageSize&newver=3&uid=0",
            mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9;)"),
        )
        if (body.optInt("code") != 200 && body.optString("code") != "200") {
            return SongCommentPage(emptyList(), 0L, false, null)
        }
        val total = body.optLong("comments_counts")
        val comments = commentRows(body.optJSONArray("comments")) { item ->
            comment(
                id = item.optString("id"),
                content = item.optString("msg"),
                time = item.optString("time").toLongOrNull()?.times(1000L) ?: 0L,
                likes = item.optInt("like_num"),
                replies = item.optJSONArray("child_comments")?.length() ?: 0,
                nickname = item.optString("u_name"),
                avatar = item.optString("u_pic"),
                userId = item.optString("u_id").toLongOrNull() ?: 0L,
            )
        }
        return SongCommentPage(comments, total, start + comments.size < total, null)
    }

    private fun kugouComments(hash: String, page: Int, pageSize: Int): SongCommentPage {
        if (hash.isBlank()) return SongCommentPage(emptyList(), 0L, false, null)
        val params = "dfid=0&mid=16249512204336365674023395779019&clienttime=${System.currentTimeMillis()}" +
            "&uuid=0&extdata=$hash&appid=1005&code=fc4be23b4e972707f36b8a828a93ba8a&schash=$hash" +
            "&clientver=11409&p=${page.coerceAtLeast(1)}&clienttoken=&pagesize=$pageSize&ver=10&kugouid=0"
        val signature = md5(KG_SIGN_KEY + params.split("&").sorted().joinToString("") + KG_SIGN_KEY)
        val body = getJson("http://m.comment.service.kugou.com/r/v1/rank/newest?$params&signature=$signature")
        if (body.optInt("err_code") != 0) return SongCommentPage(emptyList(), 0L, false, null)
        val total = body.optLong("count")
        val comments = commentRows(body.optJSONArray("list")) { item ->
            comment(
                id = item.optString("id"),
                content = item.optString("content"),
                time = item.optString("addtime").toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it } ?: 0L,
                timeLabel = item.optString("addtime").takeIf { it.contains("-") },
                likes = item.optJSONObject("like")?.optInt("likenum") ?: 0,
                replies = item.optInt("reply_num"),
                nickname = item.optString("user_name"),
                avatar = item.optString("user_pic"),
                userId = item.optLong("user_id"),
            )
        }
        val loaded = (page - 1).coerceAtLeast(0) * pageSize + comments.size
        return SongCommentPage(comments, total, loaded < total, null)
    }

    private fun qqComments(songId: Long, page: Int, pageSize: Int): SongCommentPage {
        if (songId <= 0L) return SongCommentPage(emptyList(), 0L, false, null)
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("cv", 4747474)
                    .put("ct", 24)
                    .put("format", "json")
                    .put("inCharset", "utf-8")
                    .put("outCharset", "utf-8")
                    .put("notice", 0)
                    .put("platform", "yqq.json")
                    .put("needNewCode", 1)
                    .put("uin", 0),
            )
            .put(
                "req",
                JSONObject()
                    .put("module", "music.globalComment.CommentRead")
                    .put("method", "GetHotCommentList")
                    .put(
                        "param",
                        JSONObject()
                            .put("BizType", 1)
                            .put("BizId", songId.toString())
                            .put("LastCommentSeqNo", "")
                            .put("PageSize", pageSize)
                            .put("PageNum", (page - 1).coerceAtLeast(0))
                            .put("HotType", 1)
                            .put("WithAirborne", 0)
                            .put("PicEnable", 1),
                    ),
            )
        val body = postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            payload.toString(),
            mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com"),
        )
        val comment = body.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("CommentList")
            ?: return SongCommentPage(emptyList(), 0L, false, null)
        val total = comment.optLong("Total")
        val comments = commentRows(comment.optJSONArray("Comments")) { item ->
            val published = item.optLong("PubTime")
            comment(
                id = item.optString("CmId"),
                content = item.optString("Content").replace(QQ_EMOJI, ""),
                time = if (published in 1 until 10_000_000_000L) published * 1000 else published,
                likes = item.optInt("PraiseNum"),
                replies = item.optJSONArray("SubComments")?.length() ?: 0,
                nickname = item.optString("Nick"),
                avatar = item.optString("Avatar"),
                userId = 0L,
            )
        }
        val loaded = (page - 1).coerceAtLeast(0) * pageSize + comments.size
        return SongCommentPage(comments, total, loaded < total, null)
    }

    private fun kugouRows(list: JSONArray?): List<TrackRow> {
        if (list == null) return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val hash = item.optString("hash").ifBlank { item.optString("FileHash") }
                val filename = item.optString("filename")
                val parts = filename.split(" - ", limit = 2)
                val name = item.optString("songname").ifBlank {
                    parts.getOrNull(1)?.ifBlank { null } ?: filename
                }
                if (name.isBlank()) continue
                val artist = item.optString("singername").ifBlank {
                    parts.getOrNull(0)?.ifBlank { null } ?: "未知艺术家"
                }
                val mix = item.optLong("album_audio_id").takeIf { it > 0L }
                    ?: item.optLong("MixSongID").takeIf { it > 0L }
                    ?: OpenMusicCatalog.stableId(hash.ifBlank { name })
                val duration = item.optLong("duration")
                add(
                    TrackRow(
                        id = mix,
                        name = name,
                        artists = artist,
                        album = item.optString("album_name").ifBlank { item.optString("remark") }.takeIf { it.isNotBlank() },
                        durationMs = if (duration in 1..10_000) duration * 1000 else duration,
                        coverUrl = httpsCover(item.optString("album_sizable_cover").ifBlank { item.optString("imgurl") }),
                        sourceId = mix.toString(),
                        sourceHash = hash.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun commentRows(list: JSONArray?, map: (JSONObject) -> SongComment?): List<SongComment> {
        if (list == null) return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                map(item)?.let { add(it) }
            }
        }
    }

    private fun comment(
        id: String,
        content: String,
        time: Long,
        likes: Int,
        replies: Int,
        nickname: String,
        avatar: String,
        userId: Long,
        timeLabel: String? = null,
    ): SongComment? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val commentId = id.toLongOrNull() ?: OpenMusicCatalog.stableId(id.ifBlank { text })
        return SongComment(
            commentId = commentId,
            content = text,
            timeMs = time,
            timeLabel = timeLabel?.takeIf { it.isNotBlank() } ?: clock(time),
            likedCount = likes.coerceAtLeast(0),
            replyCount = replies.coerceAtLeast(0),
            userId = userId,
            nickname = nickname.ifBlank { "用户" },
            avatarUrl = avatar.takeIf { it.isNotBlank() },
            repliedContent = null,
            repliedNickname = null,
        )
    }

    private fun singerNames(singers: JSONArray?): String {
        if (singers == null) return "未知艺术家"
        return buildString {
            for (i in 0 until singers.length()) {
                val name = singers.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isBlank()) continue
                if (isNotEmpty()) append(" / ")
                append(name)
            }
        }.ifBlank { "未知艺术家" }
    }

    private fun strings(list: JSONArray?, key: String): List<String> {
        if (list == null) return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val value = list.optJSONObject(i)?.optString(key).orEmpty()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val text = getText(url, headers)
        if (text.isBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse { loose(text) }
    }

    private fun postJson(url: String, json: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return JSONObject()
                JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            }
        }.getOrElse { JSONObject() }
    }

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
            }
        }.getOrDefault("")
    }

    private fun loose(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return JSONObject()
        val text = raw.substring(start, end + 1)
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("'", "\"")
        return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

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

    private fun decodeBasic(raw: String): String? = raw.replace("&nbsp;", " ").trim().takeIf { it.isNotBlank() }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

    private fun clock(timeMs: Long): String {
        if (timeMs <= 0L) return ""
        val zoned = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
        return "%02d-%02d %02d:%02d".format(zoned.monthValue, zoned.dayOfMonth, zoned.hour, zoned.minute)
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KG_SIGN_KEY = "OIlwieks28dk2k092lksi2UIkp"
        val QQ_EMOJI = Regex("""\[em\]e\d+\[/em\]""")
    }
}

package com.kite.zmusic.data.platform

import android.content.Context
import android.os.Build
import com.kite.zmusic.data.PlaylistSummary
import com.kite.zmusic.data.RecommendPlaylistCard
import com.kite.zmusic.data.TrackRow
import com.kite.zmusic.data.UserProfileBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class QishuiCatalog(
    private val http: OkHttpClient,
    private val session: QishuiSessionStore,
    context: Context,
) {
    private val deviceId: String
    private val installId: String
    private val computerName: String

    init {
        val prefs = context.applicationContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        var did = prefs.getString(KEY_DID, null).orEmpty()
        var iid = prefs.getString(KEY_IID, null).orEmpty()
        if (did.length < 10 || iid.length < 10) {
            did = randomInstallId()
            iid = randomInstallId()
            prefs.edit().putString(KEY_DID, did).putString(KEY_IID, iid).apply()
        }
        deviceId = did
        installId = iid
        computerName = Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
    }
    private val songCache = ConcurrentHashMap<Long, JSONObject>()

    suspend fun recommendPlaylists(count: Int = 12): List<RecommendPlaylistCard> = withContext(Dispatchers.IO) {
        val body = postLuna("/luna/discover/mix", JSONObject().put("count", count))
        QishuiJson.recommendPlaylists(body)
    }

    suspend fun playlistTracks(playlistId: Long): List<TrackRow> = withContext(Dispatchers.IO) {
        val body = postLuna(
            "/luna/playlist/detail",
            JSONObject().put("playlist_id", playlistId.toString()).put("count", 40),
        )
        QishuiJson.playlistTracks(body)
    }

    suspend fun playUrl(trackId: Long): String? = withContext(Dispatchers.IO) {
        QishuiJson.seoPlayUrl(song(trackId))
    }

    suspend fun lyric(trackId: Long): String? = withContext(Dispatchers.IO) {
        QishuiJson.seoLyric(song(trackId))
    }

    suspend fun profile(): UserProfileBrief? = withContext(Dispatchers.IO) {
        val cookie = session.cookie ?: return@withContext null
        val body = getPc("/luna/pc/me", cookie)
        QishuiJson.profile(body, session.label() ?: "汽水用户")
    }

    suspend fun myPlaylists(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val cookie = session.cookie ?: return@withContext emptyList()
        val body = getPc("/luna/pc/me/playlist", cookie)
        QishuiJson.myPlaylists(body)
    }

    suspend fun startQr(): QrTicket = withContext(Dispatchers.IO) {
        val response = http.newCall(
            Request.Builder().url(passportUrl("/passport/web/get_qrcode/", mapOf(
                "next" to "https://api.qishui.com",
                "need_logo" to "false",
                "need_short_url" to "false",
                "is_new_login" to "1",
            ))).header("User-Agent", WEB_UA).get().build(),
        ).execute()
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("二维码请求失败")
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: json
            val token = data.optString("token")
            if (token.isBlank()) error("没有拿到登录二维码")
            QrTicket(
                token = token,
                scanUrl = scanLoginUrl(token),
                frontier = data.optBoolean("is_frontier", false),
                cookieHeader = cookieHeader(resp.headers("Set-Cookie")),
            )
        }
    }

    suspend fun pollQr(ticket: QrTicket): QrPoll = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("need_logo", "false")
            .add("need_short_url", "false")
            .add("is_frontier", ticket.frontier.toString())
            .add("token", ticket.token)
            .add("is_new_login", "1")
            .add("next", "https://api.qishui.com")
            .build()
        val request = Request.Builder()
            .url(passportUrl("/passport/web/check_qrconnect/"))
            .header("User-Agent", WEB_UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { if (ticket.cookieHeader.isNotBlank()) header("Cookie", ticket.cookieHeader) }
            .post(form)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val data = json?.optJSONObject("data") ?: json
            ticket.cookieHeader = mergeCookies(ticket.cookieHeader, resp.headers("Set-Cookie"))
            val sessionId = resp.headers("Set-Cookie").firstNotNullOfOrNull { header ->
                Regex("(?:^|[;,]\\s*)sessionid=([^;,\\s]+)", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
            }.orEmpty()
            QrPoll(
                status = data?.optString("status").orEmpty(),
                sessionCookie = sessionId.takeIf { it.isNotBlank() }?.let { "sessionid=$it" },
                description = data?.optString("description").orEmpty(),
                errorCode = data?.optInt("error_code") ?: 0,
            )
        }
    }

    private fun passportUrl(path: String, extra: Map<String, String> = emptyMap()): String {
        val query = linkedMapOf(
            "passport_jssdk_version" to PASSPORT_SDK,
            "passport_jssdk_type" to "lite",
            "is_from_ttaccountsdk" to "1",
            "aid" to AID,
            "language" to "zh",
            "device_id" to deviceId,
            "install_id" to installId,
            "did" to deviceId,
            "iid" to installId,
            "device_platform" to "PC",
            "version_code" to PASSPORT_VERSION,
        )
        query.putAll(extra)
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        return "$PC$path?$encoded"
    }

    private fun scanLoginUrl(token: String): String {
        val name = URLEncoder.encode(computerName, Charsets.UTF_8.name()).replace("+", "%20")
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        return "https://bff-pc.qishui.com/light/invoke/scan_login?token=$encodedToken&os=Android&computer_name=$name"
    }

    private fun song(trackId: Long): JSONObject {
        songCache[trackId]?.let { return it }
        val body = getLuna("/luna/h5/seo_track?track_id=$trackId&device_platform=web")
        songCache[trackId] = body
        return body
    }

    private fun postLuna(path: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$LUNA$path")
            .header("User-Agent", "Luna/19.1.0 Android")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun getLuna(url: String): JSONObject {
        val request = Request.Builder().url("$LUNA$url").header("User-Agent", WEB_UA).get().build()
        return execute(request)
    }

    private fun getPc(path: String, cookie: String): JSONObject {
        val request = Request.Builder()
            .url("$PC$path?aid=$AID")
            .header("User-Agent", "LunaPC/3.0.0(290101097)")
            .header("Cookie", cookie)
            .get()
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("汽水接口失败 ${resp.code}")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    data class QrTicket(
        val token: String,
        val scanUrl: String,
        val frontier: Boolean,
        var cookieHeader: String,
    )
    data class QrPoll(
        val status: String,
        val sessionCookie: String?,
        val description: String = "",
        val errorCode: Int = 0,
    )

    companion object {
        private const val LUNA = "https://beta-luna.douyin.com"
        private const val PC = "https://api.qishui.com"
        private const val AID = "386088"
        private const val PASSPORT_SDK = "4.2.3"
        private const val PASSPORT_VERSION = "3.5.1"
        private const val DEVICE_PREFS = "zmusic_qishui_device"
        private const val KEY_DID = "did"
        private const val KEY_IID = "iid"

        private fun randomInstallId(): String =
            Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L).toString()

        private fun cookieHeader(headers: List<String>): String =
            headers.map { it.substringBefore(';').trim() }.filter { it.contains('=') }.joinToString("; ")

        private fun mergeCookies(existing: String, headers: List<String>): String {
            val map = linkedMapOf<String, String>()
            fun add(pair: String) {
                val item = pair.substringBefore(';').trim()
                val name = item.substringBefore('=').trim()
                if (name.isNotEmpty() && item.contains('=')) map[name] = item
            }
            existing.split(';').forEach { add(it) }
            headers.forEach { add(it) }
            return map.values.joinToString("; ")
        }
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val homeUnavailable = setOf(
            HomeBlock.Banner,
            HomeBlock.DailySongs,
            HomeBlock.DailyPlaylists,
            HomeBlock.NewSongs,
            HomeBlock.Mvs,
        )

        val libraryUnavailable = listOf("我喜欢的音乐", "收藏的专辑", "听歌时长", "关注与粉丝")
    }
}

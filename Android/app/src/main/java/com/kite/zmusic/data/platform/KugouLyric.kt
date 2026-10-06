package com.kite.zmusic.data.platform

import com.kite.zmusic.data.LrcLine
import com.kite.zmusic.data.LrcParser
import com.kite.zmusic.data.LyricWord
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Inflater

/**
 * 酷狗歌词：先按歌名和文件 hash 搜候选，再下载 KRC 并异或解压。
 */
internal object KugouLyric {
    fun load(http: OkHttpClient, name: String, hash: String, durationMs: Long): OpenLyricBundle? {
        val keyword = name.trim()
        if (keyword.isBlank() && hash.isBlank()) return null
        val found = search(http, keyword, hash, durationMs) ?: return null
        val payload = download(http, found) ?: return null
        val bundle = when (payload.fmt) {
            "krc" -> parseKrc(decodeKrc(payload.content))
            "lrc" -> {
                val text = runCatching {
                    String(Base64.getMimeDecoder().decode(payload.content), Charsets.UTF_8)
                }.getOrDefault("")
                OpenLyricBundle(LrcParser.parse(text), emptyList())
            }
            else -> return null
        }
        return bundle.takeIf { it.original.isNotEmpty() }
    }

    fun decodeKrc(base64: String): String {
        if (base64.isBlank()) return ""
        val raw = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return ""
        if (raw.size <= 4) return ""
        val data = raw.copyOfRange(4, raw.size)
        for (i in data.indices) {
            data[i] = ((data[i].toInt() and 0xFF) xor (KEY[i % KEY.size].toInt() and 0xFF)).toByte()
        }
        return inflate(data)
    }

    fun parseKrc(raw: String): OpenLyricBundle {
        if (raw.isBlank()) return OpenLyricBundle(emptyList(), emptyList())
        var text = raw.replace("\r", "")
        text = text.replace(Regex("""^.*\[id:\$\w+]\n"""), "")
        val language = languageRegex.find(text)
        val translations = language?.let { decodeLanguage(it.groupValues[1]) }.orEmpty()
        if (language != null) {
            text = text.replace(languageRegex, "")
        }
        val original = ArrayList<LrcLine>()
        val translated = ArrayList<LrcLine>()
        var index = 0
        for (line in text.lineSequence()) {
            val parsed = parseLine(line.trim()) ?: continue
            original.add(parsed)
            val trans = translations.getOrNull(index)
            val cleaned = trans?.let(LrcParser::sanitizeLyricText)
            if (cleaned != null) translated.add(LrcLine(timeMs = parsed.timeMs, text = cleaned))
            index++
        }
        return OpenLyricBundle(original, translated)
    }

    private fun parseLine(line: String): LrcLine? {
        val header = lineRegex.find(line) ?: return null
        val start = header.groupValues[1].toLongOrNull() ?: return null
        val body = header.groupValues[3]
        val words = ArrayList<LyricWord>()
        for (match in wordRegex.findAll(body)) {
            val offset = match.groupValues[1].toLongOrNull() ?: continue
            val duration = match.groupValues[2].toLongOrNull()?.coerceAtLeast(0L) ?: continue
            val token = match.groupValues[3]
            if (token.isEmpty()) continue
            words.add(LyricWord(timeMs = start + offset, durationMs = duration, text = token))
        }
        val plain = if (words.isNotEmpty()) words.joinToString("") { it.text } else body
        val text = LrcParser.sanitizeLyricText(plain) ?: return null
        return LrcLine(timeMs = start, text = text, words = words)
    }

    private fun decodeLanguage(base64: String): List<String> {
        val json = runCatching {
            JSONObject(String(Base64.getMimeDecoder().decode(base64), Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val content = json.optJSONArray("content") ?: return emptyList()
        var lines: org.json.JSONArray? = null
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optInt("type") == 1) {
                lines = item.optJSONArray("lyricContent")
                break
            }
        }
        if (lines == null) return emptyList()
        return buildList {
            for (i in 0 until lines.length()) {
                val row = lines.optJSONArray(i)
                if (row == null) {
                    add("")
                    continue
                }
                add(buildString {
                    for (j in 0 until row.length()) append(row.optString(j))
                })
            }
        }
    }

    private fun search(http: OkHttpClient, name: String, hash: String, durationMs: Long): Candidate? {
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
        val url = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encoded" +
            "&hash=$hash&timelength=$durationMs&lrctxt=1"
        val body = getJson(http, url) ?: return null
        val list = body.optJSONArray("candidates") ?: return null
        if (list.length() == 0) return null
        val info = list.optJSONObject(0) ?: return null
        val id = info.optString("id")
        val accessKey = info.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return null
        val krc = info.optInt("krctype") == 1 && info.optInt("contenttype") != 1
        return Candidate(id, accessKey, if (krc) "krc" else "lrc")
    }

    private fun download(http: OkHttpClient, candidate: Candidate): Payload? {
        val url = "http://lyrics.kugou.com/download?ver=1&client=pc&id=${candidate.id}" +
            "&accesskey=${candidate.accessKey}&fmt=${candidate.fmt}&charset=utf8"
        val body = getJson(http, url) ?: return null
        val content = body.optString("content")
        if (content.isBlank()) return null
        val fmt = body.optString("fmt").ifBlank { candidate.fmt }
        return Payload(fmt, content)
    }

    private fun getJson(http: OkHttpClient, url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("KG-RC", "1")
            .header("KG-THash", "expand_search_manager.cpp:852736169:451")
            .header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            }
        }.getOrNull()
    }

    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        } finally {
            inflater.end()
        }
    }

    private data class Candidate(val id: String, val accessKey: String, val fmt: String)
    private data class Payload(val fmt: String, val content: String)

    private val lineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordRegex = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>([^<]*)""")
    private val languageRegex = Regex("""\[language:([A-Za-z0-9+/=\\]+)]\n?""")
    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d,
        0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )
}

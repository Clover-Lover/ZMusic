package com.kite.zmusic.plugin

import java.net.URI

/** 只允许 http/https，且必须有主机名。用来拦住 file、javascript 这类地址。 */
internal object BetterNcmLinks {
    fun allow(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2000) return null
        if (text.any { it.isISOControl() || it.isWhitespace() }) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return text
    }

    /**
     * Win32 筛选串里的 `*.ext`。没有扩展名时交给系统选择任意文件。
     */
    fun mimeTypes(filter: String): List<String> {
        val exts = Regex("\\*\\.([A-Za-z0-9]+)")
            .findAll(filter)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
        if (exts.isEmpty()) return listOf("*/*")
        return exts.map { ext ->
            when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "txt", "md", "lrc" -> "text/plain"
                "json" -> "application/json"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
        }
    }
}

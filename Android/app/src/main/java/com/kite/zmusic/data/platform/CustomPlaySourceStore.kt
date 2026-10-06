package com.kite.zmusic.data.platform

import android.content.Context

/** 酷我、酷狗、QQ 共用一条自定义音源地址。 */
class CustomPlaySourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(): String {
        val shared = prefs.getString(KEY, null)?.trim().orEmpty()
        if (shared.isNotEmpty()) return shared
        val legacy = MusicPlatform.entries.firstNotNullOfOrNull { platform ->
            prefs.getString("url_${platform.id}", null)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val value = legacy ?: DEFAULT_URL
        prefs.edit().putString(KEY, value).apply()
        return value
    }

    fun set(url: String) {
        val value = url.trim().ifBlank { DEFAULT_URL }
        prefs.edit().putString(KEY, value).apply()
    }

    companion object {
        const val DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/pdone/lx-music-source@main/huibq/latest.js"
        private const val PREFS = "zmusic_custom_play_source"
        private const val KEY = "url"
    }
}

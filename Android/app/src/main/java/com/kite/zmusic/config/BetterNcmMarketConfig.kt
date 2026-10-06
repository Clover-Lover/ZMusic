package com.kite.zmusic.config

import com.kite.zmusic.BuildConfig
import java.net.URI

/**
 * BetterNCM 插件市场根地址。
 * 编译期默认来自 `Android/local.properties`；运行期由设置页覆盖。
 */
object BetterNcmMarketConfig {
    val defaultBaseUrl: String = normalize(BuildConfig.BETTERNCM_MARKET_BASE_URL)

    @Volatile
    private var runtimeBaseUrl: String? = null

    @Volatile
    var revision: Int = 0
        private set

    val baseUrl: String
        get() = runtimeBaseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl

    fun setRuntime(baseUrl: String) {
        runtimeBaseUrl = normalize(baseUrl).ifEmpty { null }
        revision++
    }

    fun normalize(raw: String): String {
        val text = raw.trim().filter { !it.isWhitespace() }
        if (text.isEmpty()) return ""
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            return ""
        }
        val host = runCatching { URI(text).host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return ""
        return text.trimEnd('/') + "/"
    }
}

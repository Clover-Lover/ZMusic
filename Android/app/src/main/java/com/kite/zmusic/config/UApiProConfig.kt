package com.kite.zmusic.config

import com.kite.zmusic.BuildConfig

/**
 * UApiPro 供应商运行时配置（多服务共用同一 baseUrl + API Key）。
 * 编译期默认来自 `Android/local.properties`；运行期可由设置页覆盖并持久化。
 */
object UApiProConfig {
    val defaultBaseUrl: String = BuildConfig.UAPIPRO_BASE_URL.trimEnd('/')
    val defaultApiKey: String = BuildConfig.UAPIPRO_API_KEY.trim()

    @Volatile
    private var runtimeBaseUrl: String? = null

    @Volatile
    private var runtimeApiKey: String? = null

    val baseUrl: String
        get() = runtimeBaseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl

    val apiKey: String
        get() = runtimeApiKey?.takeIf { it.isNotBlank() } ?: defaultApiKey

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    fun setRuntime(baseUrl: String, apiKey: String) {
        runtimeBaseUrl = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
        runtimeApiKey = apiKey.trim().takeIf { it.isNotEmpty() }
    }

    fun clearRuntime() {
        runtimeBaseUrl = null
        runtimeApiKey = null
    }
}

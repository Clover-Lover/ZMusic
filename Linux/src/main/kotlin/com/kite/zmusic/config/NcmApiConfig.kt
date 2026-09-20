package com.kite.zmusic.config

/**
 * 网易云兼容 API 基地址。
 * 环境变量 `ZMUSIC_NCM_API_BASE_URL` 或运行期设置可覆盖；
 * 未配置时为空（开源仓库不内置公网地址）。
 */
object NcmApiConfig {
    const val PRODUCT_VERSION = "0.1.0"

    val defaultBaseUrl: String =
        System.getenv("ZMUSIC_NCM_API_BASE_URL")?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()

    @Volatile
    private var runtimeBaseUrl: String? = null

    val baseUrl: String
        get() = runtimeBaseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl

    fun setRuntimeBaseUrl(url: String) {
        runtimeBaseUrl = url.trim().trimEnd('/').takeIf { it.isNotEmpty() }
    }

    fun clearRuntimeBaseUrl() {
        runtimeBaseUrl = null
    }
}

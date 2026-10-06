package com.kite.zmusic.plugin

internal data class BetterNcmLook(
    val imageUrl: String? = null,
    val accent: String? = null,
    val frosted: Boolean = false,
)

/**
 * 从 BetterNCM 主题样式里抽出能落到 ZMusic 界面上的部分。
 * 网易云节点选择器（`.g-mn`、`#main-player`）没有对应界面，不在这里翻译。
 */
internal object BetterNcmThemeSignals {
    private val imageUrl = Regex(
        """url\(\s*['"]?(https?://[^'")\s]+)['"]?\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val accentNames = listOf(
        "md-accent-color",
        "theme-primary",
        "colorprimary1",
    )

    fun imageUrl(css: String, vars: Map<String, String>): String? {
        vars.values.firstNotNullOfOrNull { imageUrl.find(it)?.groupValues?.getOrNull(1) }
            ?.let { return it }
        return imageUrl.find(css)?.groupValues?.getOrNull(1)
    }

    fun accent(vars: Map<String, String>): String? {
        for ((key, value) in vars) {
            val name = key.trim().removePrefix("--").lowercase()
            if (name !in accentNames) continue
            val hex = value.trim()
            if (hex.startsWith("#") && hex.length in 4..9) return hex
        }
        return null
    }

    fun frosted(css: String): Boolean {
        val text = css.lowercase()
        return text.contains("backdrop-filter") || text.contains("background: transparent") ||
            text.contains("background:transparent")
    }
}

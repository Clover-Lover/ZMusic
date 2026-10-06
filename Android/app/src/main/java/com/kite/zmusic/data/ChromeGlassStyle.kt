package com.kite.zmusic.data

import com.kite.zmusic.i18n.t

enum class ChromeGlassMode {
    Liquid,
    Frosted,
    Solid,
    ;

    val title: String
        get() = when (this) {
            Liquid -> t("液态")
            Frosted -> t("磨砂")
            Solid -> t("纯色")
        }

    val caption: String
        get() = when (this) {
            Liquid -> t("折射背后的画面")
            Frosted -> t("只做模糊，不折射")
            Solid -> t("不透明底，不再透出背景")
        }

    companion object {
        fun fromStored(raw: String?): ChromeGlassMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Liquid
    }
}

/**
 * 某一玻璃模式自己的折射率与模糊。三种模式各持一份，互不覆盖。
 * 折射率是相对产品默认的倍率，模糊是 0–1 强度。
 */
data class ChromeGlassLook(
    val refraction: Float = ChromeGlassStyle.REFRACTION_DEFAULT,
    val blur: Float = ChromeGlassStyle.BLUR_DEFAULT,
) {
    fun sanitized(): ChromeGlassLook = copy(
        refraction = refraction.coerceIn(
            ChromeGlassStyle.REFRACTION_MIN,
            ChromeGlassStyle.REFRACTION_MAX,
        ),
        blur = blur.coerceIn(0f, 1f),
    )
}

/**
 * 主界面玻璃主题。当前模式决定画面用哪一份 [ChromeGlassLook]。
 */
data class ChromeGlassStyle(
    val mode: ChromeGlassMode = ChromeGlassMode.Liquid,
    val liquid: ChromeGlassLook = ChromeGlassLook(),
    val frosted: ChromeGlassLook = ChromeGlassLook(),
    val solid: ChromeGlassLook = ChromeGlassLook(),
) {
    val look: ChromeGlassLook get() = lookOf(mode)

    /** 当前模式的折射率。 */
    val refraction: Float get() = look.refraction

    /** 当前模式的模糊强度。 */
    val blur: Float get() = look.blur

    val settingsSubtitle: String
        get() = when (mode) {
            ChromeGlassMode.Liquid ->
                t("液态 · 折射率 %s · 模糊 %s", formatRefraction(refraction), formatBlurPercent(blur))
            ChromeGlassMode.Frosted ->
                t("磨砂 · 模糊 %s", formatBlurPercent(blur))
            ChromeGlassMode.Solid -> t("纯色，不透明")
        }

    fun lookOf(mode: ChromeGlassMode): ChromeGlassLook = when (mode) {
        ChromeGlassMode.Liquid -> liquid
        ChromeGlassMode.Frosted -> frosted
        ChromeGlassMode.Solid -> solid
    }

    fun withLook(mode: ChromeGlassMode, look: ChromeGlassLook): ChromeGlassStyle {
        val next = look.sanitized()
        return when (mode) {
            ChromeGlassMode.Liquid -> copy(liquid = next)
            ChromeGlassMode.Frosted -> copy(frosted = next)
            ChromeGlassMode.Solid -> copy(solid = next)
        }
    }

    fun withRefraction(value: Float): ChromeGlassStyle =
        withLook(this.mode, look.copy(refraction = value))

    fun withBlur(value: Float): ChromeGlassStyle =
        withLook(this.mode, look.copy(blur = value))

    fun sanitized(): ChromeGlassStyle = copy(
        liquid = liquid.sanitized(),
        frosted = frosted.sanitized(),
        solid = solid.sanitized(),
    )

    companion object {
        const val REFRACTION_MIN = 0f
        const val REFRACTION_MAX = 2f
        const val REFRACTION_DEFAULT = 1f
        const val BLUR_DEFAULT = 0.4f

        val Default = ChromeGlassStyle()

        fun formatRefraction(value: Float): String =
            String.format(java.util.Locale.US, "%.2f", value.coerceIn(REFRACTION_MIN, REFRACTION_MAX))

        fun formatBlurPercent(value: Float): String =
            "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"
    }
}

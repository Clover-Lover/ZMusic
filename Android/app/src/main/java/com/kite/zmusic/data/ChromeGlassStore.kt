package com.kite.zmusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局玻璃主题（设置预览与 Dock / 迷你条 / 弹窗 / 灵动岛共用）。
 */
class ChromeGlassStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _style = MutableStateFlow(load())
    val style: StateFlow<ChromeGlassStyle> = _style.asStateFlow()

    fun current(): ChromeGlassStyle = _style.value

    fun setMode(mode: ChromeGlassMode) {
        val next = _style.value.copy(mode = mode)
        if (next == _style.value) return
        persist(next)
    }

    fun setRefraction(value: Float) {
        val next = _style.value.withRefraction(value)
        if (next == _style.value) return
        persist(next)
    }

    fun setBlur(value: Float) {
        val next = _style.value.withBlur(value)
        if (next == _style.value) return
        persist(next)
    }

    fun apply(style: ChromeGlassStyle) {
        persist(style.sanitized())
    }

    fun reset() {
        persist(ChromeGlassStyle.Default)
    }

    private fun persist(next: ChromeGlassStyle) {
        val style = next.sanitized()
        prefs.edit()
            .putString(KEY_MODE, style.mode.name)
            .putFloat(KEY_REFRACTION, style.refraction)
            .putFloat(KEY_BLUR, style.blur)
            .putLook(KEY_LIQUID, style.liquid)
            .putLook(KEY_FROSTED, style.frosted)
            .putLook(KEY_SOLID, style.solid)
            .apply()
        _style.value = style
    }

    private fun load(): ChromeGlassStyle {
        val legacy = ChromeGlassLook(
            refraction = prefs.safeFloat(KEY_REFRACTION, ChromeGlassStyle.REFRACTION_DEFAULT),
            blur = prefs.safeFloat(KEY_BLUR, ChromeGlassStyle.BLUR_DEFAULT),
        ).sanitized()
        return ChromeGlassStyle(
            mode = ChromeGlassMode.fromStored(prefs.getString(KEY_MODE, null)),
            liquid = loadLook(KEY_LIQUID, legacy),
            frosted = loadLook(KEY_FROSTED, legacy),
            solid = loadLook(KEY_SOLID, legacy),
        )
    }

    private fun loadLook(prefix: String, legacy: ChromeGlassLook): ChromeGlassLook {
        val refKey = "${prefix}_refraction"
        val blurKey = "${prefix}_blur"
        return ChromeGlassLook(
            refraction = if (prefs.contains(refKey)) {
                prefs.safeFloat(refKey, legacy.refraction)
            } else {
                legacy.refraction
            },
            blur = if (prefs.contains(blurKey)) {
                prefs.safeFloat(blurKey, legacy.blur)
            } else {
                legacy.blur
            },
        ).sanitized()
    }

    companion object {
        private const val PREFS = "zmusic_chrome_glass"
        private const val KEY_MODE = "mode"
        private const val KEY_REFRACTION = "refraction"
        private const val KEY_BLUR = "blur"
        private const val KEY_LIQUID = "liquid"
        private const val KEY_FROSTED = "frosted"
        private const val KEY_SOLID = "solid"
    }
}

private fun android.content.SharedPreferences.Editor.putLook(
    prefix: String,
    look: ChromeGlassLook,
): android.content.SharedPreferences.Editor = putFloat("${prefix}_refraction", look.refraction)
    .putFloat("${prefix}_blur", look.blur)

private fun android.content.SharedPreferences.safeFloat(key: String, default: Float): Float =
    runCatching { getFloat(key, default) }.getOrDefault(default)

package com.kite.zmusic.plugin

/**
 * `channel.call("audioplayer.*")` 的参数形状。
 * 进度回调给的是秒，seek 的第三参也是秒。音量是 0 到 1。
 */
internal object BetterNcmTransport {
    fun seekMs(args: List<*>): Long? {
        val seconds = number(args.getOrNull(2)) ?: return null
        if (!seconds.isFinite() || seconds < 0.0) return null
        return (seconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    fun unit(args: List<*>, index: Int): Float? {
        val value = number(args.getOrNull(index)) ?: return null
        if (!value.isFinite()) return null
        return value.toFloat().coerceIn(0f, 1f)
    }

    fun rate(raw: String): Float? {
        val value = raw.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.coerceIn(MIN_RATE, MAX_RATE)
    }

    private fun number(value: Any?): Double? = when (value) {
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Double -> value
        is Float -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    const val MIN_RATE = 0.1f
    const val MAX_RATE = 3f
}

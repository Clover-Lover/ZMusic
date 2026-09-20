package com.kite.zmusic.ui.easter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 聊天 / 搜索 / 评论发送时扫描彩蛋口令。
 * 不拦截原逻辑，只额外弹出图层。新彩蛋登记在 [EasterClip.Clips]。
 */
object EasterEggs {
    private val _play = MutableStateFlow<EasterPlay?>(null)
    val play: StateFlow<EasterPlay?> = _play.asStateFlow()
    private var generation = 0

    fun consider(text: String) {
        val clip = EasterClip.match(text) ?: return
        generation += 1
        _play.value = EasterPlay(generation, clip)
    }
}

/** 兼容旧调用点：内部转发到 [EasterEggs]。 */
object MjEasterEgg {
    fun consider(text: String) = EasterEggs.consider(text)

    fun matches(text: String): Boolean = EasterClip.match(text)?.id == "mj"
}

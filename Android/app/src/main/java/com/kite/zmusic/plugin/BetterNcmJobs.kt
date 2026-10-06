package com.kite.zmusic.plugin

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把同一次脚本回合里的多次宿主调用收成一次后续排空。
 * `.then` 是在宿主调用返回之后才挂上的，所以排空不能插在调用返回之前。
 */
internal class BetterNcmJobSignal {
    private val queued = AtomicBoolean(false)

    fun request(post: (() -> Unit) -> Unit, turn: () -> Unit) {
        if (!queued.compareAndSet(false, true)) return
        post {
            queued.set(false)
            turn()
        }
    }
}

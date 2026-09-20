package com.kite.zmusic.ui.easter

/**
 * 一条可登记的全屏 GIF + 音效彩蛋。
 * 之后加同类需求：在 [Clips] 里追加一条即可，触发走 [EasterEggs.consider]。
 */
data class EasterClip(
    val id: String,
    val triggers: Set<String>,
    val gifAsset: String,
    val audioAsset: String,
) {
    companion object {
        val Clips: List<EasterClip> = listOf(
            EasterClip(
                id = "mj",
                triggers = setOf("mj"),
                gifAsset = "easter/mj.gif",
                audioAsset = "easter/mj.mp3",
            ),
            EasterClip(
                id = "fox",
                triggers = setOf("fox"),
                gifAsset = "easter/fox.gif",
                audioAsset = "easter/fox.mp3",
            ),
        )

        fun match(text: String): EasterClip? {
            val key = text.trim().lowercase()
            if (key.isEmpty()) return null
            return Clips.firstOrNull { clip ->
                clip.triggers.any { it.equals(key, ignoreCase = true) }
            }
        }
    }
}

data class EasterPlay(
    val generation: Int,
    val clip: EasterClip,
)

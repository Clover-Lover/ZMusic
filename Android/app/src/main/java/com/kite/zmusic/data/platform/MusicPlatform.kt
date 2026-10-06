package com.kite.zmusic.data.platform

/**
 * 客户端当前曲库平台。
 * [reloginOnSwitch] 为真时，切到或切离该平台都要重启，让用户重新登录这一侧。
 */
enum class MusicPlatform(
    val id: String,
    /** 有自己的登录。切到或切离这种平台时要重启。 */
    val reloginOnSwitch: Boolean,
) {
    NETEASE("netease", reloginOnSwitch = true),
    QISHUI("qishui", reloginOnSwitch = true),
    KUWO("kuwo", reloginOnSwitch = false),
    KUGOU("kugou", reloginOnSwitch = false),
    QQ("qq", reloginOnSwitch = false),
    ;

    /** 切到有登录的平台才重启。没有登录的平台当场切换。 */
    fun needsRestartWhenSwitching(other: MusicPlatform): Boolean =
        this != other && other.reloginOnSwitch

    companion object {
        fun fromId(raw: String?): MusicPlatform =
            entries.firstOrNull { it.id == raw } ?: NETEASE
    }
}

enum class HomeBlock {
    Banner,
    DailySongs,
    DailyPlaylists,
    NewSongs,
    Mvs,
}

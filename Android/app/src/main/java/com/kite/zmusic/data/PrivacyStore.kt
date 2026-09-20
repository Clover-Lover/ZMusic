package com.kite.zmusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 隐私相关开关。默认全部关闭。
 * 「歌曲解灰」须在免责页明确接受后才会被写成 true。
 */
class PrivacyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _songUnlockGray = MutableStateFlow(prefs.getBoolean(KEY_SONG_UNLOCK_GRAY, false))
    val songUnlockGray: StateFlow<Boolean> = _songUnlockGray.asStateFlow()

    fun songUnlockGrayEnabled(): Boolean = _songUnlockGray.value

    fun setSongUnlockGray(enabled: Boolean) {
        if (enabled == _songUnlockGray.value) return
        prefs.edit().putBoolean(KEY_SONG_UNLOCK_GRAY, enabled).apply()
        _songUnlockGray.value = enabled
    }

    companion object {
        private const val PREFS = "zmusic_privacy"
        private const val KEY_SONG_UNLOCK_GRAY = "song_unlock_gray"
    }
}

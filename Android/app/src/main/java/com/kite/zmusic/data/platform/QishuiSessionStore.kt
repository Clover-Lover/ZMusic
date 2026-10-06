package com.kite.zmusic.data.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QishuiSessionStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _cookie = MutableStateFlow(prefs.getString(KEY, null)?.trim().orEmpty().ifBlank { null })
    val cookieFlow: StateFlow<String?> = _cookie.asStateFlow()

    val cookie: String? get() = _cookie.value

    fun persist(cookie: String, label: String?) {
        val value = cookie.trim()
        prefs.edit().putString(KEY, value).putString(KEY_LABEL, label?.trim()).apply()
        _cookie.value = value.ifBlank { null }
    }

    fun label(): String? = prefs.getString(KEY_LABEL, null)

    fun clear() {
        prefs.edit().clear().apply()
        _cookie.value = null
    }

    companion object {
        private const val PREFS = "zmusic_qishui_session"
        private const val KEY = "cookie"
        private const val KEY_LABEL = "label"
    }
}

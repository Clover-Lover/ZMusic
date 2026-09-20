package com.kite.zmusic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kite.zmusic.config.UApiProConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UApiPro 供应商凭据持久化（加密）。默认回退到编译期 [UApiProConfig] 值。
 */
class UApiProStore(context: Context) {

    data class Credentials(
        val baseUrl: String,
        val apiKey: String,
    )

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private val _credentials = MutableStateFlow(readEffective())
    val credentials: StateFlow<Credentials> = _credentials.asStateFlow()

    init {
        applyToRuntime()
    }

    fun current(): Credentials = _credentials.value

    fun persist(baseUrl: String, apiKey: String) {
        val url = baseUrl.trim().trimEnd('/')
        val key = apiKey.trim()
        require(url.isNotEmpty()) { "baseUrl empty" }
        require(key.isNotEmpty()) { "apiKey empty" }
        prefs.edit()
            .putString(KEY_BASE_URL, url)
            .putString(KEY_API_KEY, key)
            .apply()
        UApiProConfig.setRuntime(url, key)
        _credentials.value = Credentials(url, key)
    }

    fun applyToRuntime() {
        val cur = current()
        if (cur.baseUrl.isNotEmpty() || cur.apiKey.isNotEmpty()) {
            UApiProConfig.setRuntime(cur.baseUrl, cur.apiKey)
        }
    }

    private fun readEffective(): Credentials {
        val storedUrl = prefs.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/').orEmpty()
        val storedKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        return Credentials(
            baseUrl = storedUrl.ifEmpty { UApiProConfig.defaultBaseUrl },
            apiKey = storedKey.ifEmpty { UApiProConfig.defaultApiKey },
        )
    }

    companion object {
        private const val PREFS_NAME = "zmusic_uapipro"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"

        /** 前 10 + 后 3（`uapi-` 前缀占 5 位）；过短则整段掩码。 */
        fun maskApiKey(key: String): String {
            val k = key.trim()
            if (k.isEmpty()) return ""
            if (k.length <= 13) return "***"
            return "${k.take(10)}…${k.takeLast(3)}"
        }

        fun looksMasked(value: String): Boolean {
            val v = value.trim()
            return v.contains('…') || v.contains("***") || v.contains('*')
        }

        private fun createPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

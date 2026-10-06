package com.kite.zmusic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kite.zmusic.config.BetterNcmMarketConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BetterNCM 插件市场地址。未单独保存时用编译期默认。
 */
class BetterNcmMarketStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val _baseUrl = MutableStateFlow(readEffective())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    init {
        applyToRuntime()
    }

    fun current(): String = _baseUrl.value

    fun persist(baseUrl: String) {
        val url = BetterNcmMarketConfig.normalize(baseUrl)
        require(url.isNotEmpty()) { "baseUrl empty" }
        prefs.edit().putString(KEY_BASE_URL, url).apply()
        BetterNcmMarketConfig.setRuntime(url)
        _baseUrl.value = url
    }

    fun applyToRuntime() {
        val url = current()
        if (url.isNotEmpty()) BetterNcmMarketConfig.setRuntime(url)
    }

    private fun readEffective(): String {
        val stored = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val normalized = BetterNcmMarketConfig.normalize(stored)
        return normalized.ifEmpty { BetterNcmMarketConfig.defaultBaseUrl }
    }

    companion object {
        private const val PREFS_NAME = "zmusic_betterncm_market"
        private const val KEY_BASE_URL = "base_url"

        private fun createPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

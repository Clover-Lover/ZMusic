package com.kite.zmusic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kite.zmusic.data.UApiProStore
import com.kite.zmusic.i18n.t
import com.kite.zmusic.ui.common.GlassPromptField
import com.kite.zmusic.ui.main.MainPalette
import com.kite.zmusic.ui.main.wallpaperItemChrome

@Composable
fun UApiProSettingsPage(
    store: UApiProStore,
    contentBottomInset: Dp,
    saveToken: Int,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember(store) { store.current() }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var committedKey by remember { mutableStateOf(initial.apiKey) }
    var keyIsMask by remember { mutableStateOf(initial.apiKey.isNotEmpty()) }
    var apiKeyField by remember {
        mutableStateOf(
            if (initial.apiKey.isNotEmpty()) UApiProStore.maskApiKey(initial.apiKey) else "",
        )
    }
    var bannerError by remember { mutableStateOf<String?>(null) }

    fun onApiKeyChange(value: String) {
        bannerError = null
        val cleaned = value.filter { !it.isWhitespace() }
        if (keyIsMask) {
            val mask = UApiProStore.maskApiKey(committedKey)
            if (cleaned == mask) {
                apiKeyField = mask
                return
            }
            keyIsMask = false
            apiKeyField = when {
                cleaned.isEmpty() -> ""
                UApiProStore.looksMasked(cleaned) -> ""
                mask.startsWith(cleaned) -> ""
                else -> cleaned
            }
            return
        }
        apiKeyField = cleaned
    }

    LaunchedEffect(saveToken) {
        if (saveToken <= 0) return@LaunchedEffect
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) {
            bannerError = t("请填写 Base URL")
            onError(bannerError!!)
            return@LaunchedEffect
        }
        val typed = apiKeyField.trim()
        val key = when {
            keyIsMask -> committedKey
            typed.isEmpty() -> committedKey
            UApiProStore.looksMasked(typed) -> {
                bannerError = t("请输入完整 API Key")
                onError(bannerError!!)
                return@LaunchedEffect
            }
            else -> typed
        }
        if (key.isEmpty()) {
            bannerError = t("请填写 API Key")
            onError(bannerError!!)
            return@LaunchedEffect
        }
        runCatching { store.persist(url, key) }
            .onSuccess {
                committedKey = key
                keyIsMask = true
                apiKeyField = UApiProStore.maskApiKey(key)
                baseUrl = url
                bannerError = null
                onSaved()
            }
            .onFailure {
                bannerError = t("保存失败")
                onError(bannerError!!)
            }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = contentBottomInset + 24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = t("UApiPro 是第三方能力供应商，音乐与社区之外的增强服务共用此处的 Base URL 与 API Key。当前客户端会按需调用其能力；密钥仅保存在本机。"),
            style = TextStyle(
                color = MainPalette.Secondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .wallpaperItemChrome(RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = t("Base URL"),
                style = TextStyle(
                    color = MainPalette.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = t("API 根地址，无末尾斜杠"),
                style = TextStyle(
                    color = MainPalette.Secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            GlassPromptField(
                value = baseUrl,
                onValueChange = {
                    bannerError = null
                    baseUrl = it.filter { ch -> !ch.isWhitespace() }
                },
                placeholder = "https://uapis.cn/api/v1",
                maxLength = 256,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .wallpaperItemChrome(RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = t("API Key"),
                style = TextStyle(
                    color = MainPalette.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = t("显示时仅保留前 10 位与后 3 位"),
                style = TextStyle(
                    color = MainPalette.Secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            GlassPromptField(
                value = apiKeyField,
                onValueChange = ::onApiKeyChange,
                placeholder = t("粘贴 API Key"),
                maxLength = 256,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
        }
        bannerError?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = err,
                style = TextStyle(
                    color = MainPalette.Accent,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

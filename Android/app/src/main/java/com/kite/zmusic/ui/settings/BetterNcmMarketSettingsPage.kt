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
import com.kite.zmusic.config.BetterNcmMarketConfig
import com.kite.zmusic.data.BetterNcmMarketStore
import com.kite.zmusic.i18n.t
import com.kite.zmusic.ui.common.GlassPromptField
import com.kite.zmusic.ui.main.MainPalette
import com.kite.zmusic.ui.main.wallpaperItemChrome

@Composable
fun BetterNcmMarketSettingsPage(
    store: BetterNcmMarketStore,
    contentBottomInset: Dp,
    saveToken: Int,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember(store) { store.current() }
    var baseUrl by remember { mutableStateOf(initial.trimEnd('/')) }
    var bannerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saveToken) {
        if (saveToken <= 0) return@LaunchedEffect
        val url = BetterNcmMarketConfig.normalize(baseUrl)
        if (url.isEmpty()) {
            bannerError = t("请填写 http 或 https 地址")
            onError(bannerError!!)
            return@LaunchedEffect
        }
        runCatching { store.persist(url) }
            .onSuccess {
                baseUrl = url.trimEnd('/')
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
            text = t("BetterNCM 插件列表从这里读取 plugins.json。预览图和插件包也相对这个地址。只保存在本机。"),
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
                text = t("市场地址"),
                style = TextStyle(
                    color = MainPalette.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = t("根地址，建议保留末尾斜杠"),
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
                placeholder = "https://example.com/betterncm/",
                maxLength = 300,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            if (!bannerError.isNullOrBlank()) {
                Text(
                    text = bannerError!!,
                    style = TextStyle(color = MainPalette.Accent, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

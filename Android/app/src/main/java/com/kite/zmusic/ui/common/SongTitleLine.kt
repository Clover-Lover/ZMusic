package com.kite.zmusic.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kite.zmusic.i18n.t

private val VipGold = Color(0xFFE6B325)
private val VipInk = Color(0xFF3A2A10)

/** 歌名一行。VIP 歌曲在标题后保留徽标，标题过长时先省略歌名。 */
@Composable
fun SongTitleLine(
    name: String,
    vip: Boolean,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
            ),
        )
        if (vip) {
            Spacer(Modifier.width(6.dp))
            VipSongMark()
        }
    }
}

@Composable
private fun VipSongMark() {
    val label = t("会员歌曲")
    Box(
        modifier = Modifier
            .size(width = 20.dp, height = 11.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(VipGold)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "VIP",
            color = VipInk,
            fontSize = 8.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

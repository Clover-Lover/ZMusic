package com.kite.zmusic.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kite.zmusic.i18n.t
import com.kite.zmusic.ui.theme.MainPalette

@Composable
fun UnderConstructionCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val stripe = MainPalette.Hint.copy(alpha = 0.28f)
    val border = MainPalette.Hint.copy(alpha = 0.7f)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val step = 14.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = stripe,
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = 8.dp.toPx(),
                )
                x += step
            }
            drawRoundRect(
                color = border,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = t("施工中"),
                style = TextStyle(
                    color = MainPalette.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
            )
            Text(
                text = title,
                style = TextStyle(
                    color = MainPalette.Secondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

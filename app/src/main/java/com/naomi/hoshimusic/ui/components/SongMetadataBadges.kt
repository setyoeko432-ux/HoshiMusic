package com.naomi.hoshimusic.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.util.QualityResolver

@Composable
fun SongMetadataBadges(
    yearAdded: String,
    quality: QualityResolver.AudioQuality,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = yearAdded,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.50f)
        )

        if (quality != QualityResolver.AudioQuality.NONE) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Text(
                    text = when (quality) {
                        QualityResolver.AudioQuality.HI_RES -> "HI\nRES"
                        QualityResolver.AudioQuality.LOSSLESS -> "LOSS\nLESS"
                        QualityResolver.AudioQuality.HQ -> "HQ"
                        else -> ""
                    },
                    fontSize = if (quality == QualityResolver.AudioQuality.HQ) 10.sp else 7.5.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 8.5.sp,
                    color = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

package com.naomi.hoshimusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.theme.rememberHeroPalette
import com.naomi.hoshimusic.util.QualityResolver

@Composable
fun RhythmHeroCard(
    song: Song,
    onPlayClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberHeroPalette(artworkUri = song.artworkUri)
    val yearAdded = QualityResolver.formatYearAdded(song.dateAddedMs / 1000L)
    val quality = QualityResolver.resolve(song)

    val heroShape = RoundedCornerShape(22.dp)
    val backgroundColor = MaterialTheme.colorScheme.background
    
    val bottomGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.Transparent,
            0.45f to Color.Transparent,
            0.78f to backgroundColor.copy(alpha = 0.55f),
            1.00f to backgroundColor
        )
    )

    Card(
        shape = heroShape,
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(heroShape)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Artwork(
                model = song.artworkUri,
                size = 290.dp,
                cornerRadius = 22.dp,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(bottomGradient)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = palette.accentColor,
                        modifier = Modifier
                            .height(40.dp)
                            .widthIn(min = 100.dp)
                            .clickable(onClick = onPlayClick)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Text(
                                text = "Mainkan",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    SongMetadataBadges(
                        yearAdded = yearAdded,
                        quality = quality
                    )
                }
            }
        }
    }
}

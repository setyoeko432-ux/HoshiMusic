package com.naomi.hoshimusic.ui.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.MotionIconButton

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun SongRow(
    song: Song,
    isFavorite: Boolean,
    isPlaying: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSwipeQueue: () -> Unit,
    onAction: () -> Unit,
    actionIcon: ImageVector = Icons.Rounded.MoreVert,
    actionDescription: String = "Tindakan lagu",
) {
    var dragX by remember(song.stableId) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = remember(density) { with(density) { 84.dp.toPx() } }
    val maximum = remember(density) { with(density) { 132.dp.toPx() } }
    val haptic = LocalHapticFeedback.current

    val displayedOffset by animateFloatAsState(
        targetValue = dragX,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1000f),
        label = "songSwipe",
    )

    val rowColor = if (isPlaying) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background
    val techSummary = remember(song) { song.getTechnicalSummary() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (dragX > 1f) MaterialTheme.colorScheme.surfaceContainerHigh
                else if (dragX < -1f) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
    ) {
        if (kotlin.math.abs(dragX) > 0.5f) {
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (dragX > 0) Arrangement.Start else Arrangement.End,
            ) {
                Icon(
                    if (dragX > 0) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (dragX > 0) "Favorit" else "Ke antrean",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = displayedOffset }
                .background(rowColor)
                .pointerInput(song.stableId) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX = (dragX + amount).coerceIn(-maximum, maximum)
                        },
                        onDragCancel = { dragX = 0f },
                        onDragEnd = {
                            when {
                                dragX >= threshold -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleFavorite()
                                }
                                dragX <= -threshold -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeQueue()
                                }
                            }
                            dragX = 0f
                        },
                    )
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artworkModifier = Modifier.clip(RoundedCornerShape(12.dp))
            Artwork(song.artworkUri, 52.dp, 12.dp, modifier = artworkModifier)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!song.romanizedTitle.isNullOrBlank()) {
                    Text(
                        text = song.romanizedTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val artistText = if (song.romanizedArtist.isNullOrBlank()) song.artist else "${song.artist} (${song.romanizedArtist})"
                    Text(
                        text = "$artistText • $techSummary",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            MotionIconButton(
                onClick = onToggleFavorite,
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Hapus favorit" else "Tambah favorit",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(46.dp),
                iconSize = 22.dp,
            )
            MotionIconButton(
                onClick = onAction,
                imageVector = actionIcon,
                contentDescription = actionDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(46.dp),
                iconSize = 22.dp,
            )
        }
    }
}

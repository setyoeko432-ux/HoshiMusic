package com.naomi.hoshimusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.domain.MusicGroup
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.EmptyState
import com.naomi.hoshimusic.ui.MotionIconButton
import com.naomi.hoshimusic.ui.bounceClick

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupList(
    groups: List<MusicGroup>,
    icon: ImageVector,
    emptyTitle: String,
    onPlayGroup: (MusicGroup) -> Unit,
    onLongPressGroup: ((MusicGroup) -> Unit)? = null,
) {
    if (groups.isEmpty()) {
        EmptyState(icon, emptyTitle, "Pindai ulang koleksi musik perangkat.")
        return
    }
    val haptic = LocalHapticFeedback.current
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)) {
        items(groups, key = { it.key }, contentType = { "group" }) { group ->
            Row(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onPlayGroup(group) },
                    onLongClick = onLongPressGroup?.let { action ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            action(group)
                        }
                    },
                ).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(group.songs.firstOrNull()?.artworkUri, 60.dp, 14.dp, icon)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton({ onPlayGroup(group) }, Icons.Rounded.PlayArrow, "Putar ${group.title}", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun GroupGrid(
    groups: List<MusicGroup>,
    icon: ImageVector,
    onPlayGroup: (MusicGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState(icon, "Tidak ada data", "Coba kata kunci pencarian berbeda.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 84.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        items(groups, key = { it.key }) { group ->
            Column(
                Modifier.fillMaxWidth().bounceClick { onPlayGroup(group) }
            ) {
                Artwork(group.songs.firstOrNull()?.artworkUri, 160.dp, 16.dp, icon, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

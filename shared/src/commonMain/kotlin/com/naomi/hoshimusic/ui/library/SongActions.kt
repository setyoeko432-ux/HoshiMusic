package com.naomi.hoshimusic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    isFavorite: Boolean,
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onRequestNewPlaylist: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.artworkUri, 52.dp, 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item { ActionListItem(Icons.Rounded.SkipNext, "Putar berikutnya") { onPlayNext(); onDismiss() } }
            item { ActionListItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Tambahkan ke antrean") { onAddQueue(); onDismiss() } }
            item {
                ActionListItem(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isFavorite) "Hapus dari favorit" else "Tambahkan ke favorit",
                ) { onToggleFavorite(); onDismiss() }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { Text("Tambahkan ke playlist", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) }
            item { ActionListItem(Icons.Rounded.Add, "Playlist baru") { onRequestNewPlaylist(); onDismiss() } }
            items(playlists, key = { it.id }) { playlist ->
                ActionListItem(Icons.AutoMirrored.Rounded.QueueMusic, playlist.name) { onAddToPlaylist(playlist.id); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionListItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

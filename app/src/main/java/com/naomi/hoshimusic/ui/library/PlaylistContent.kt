package com.naomi.hoshimusic.ui.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.SavedQueue
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.EmptyState
import com.naomi.hoshimusic.ui.MotionIconButton

@Composable
internal fun PlaylistHub(
    playlists: List<LocalPlaylist>,
    savedQueues: List<SavedQueue>,
    songs: List<Song>,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onExportPlaylist: (LocalPlaylist) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onDeleteSavedQueue: (String) -> Unit,
) {
    val songsById = remember(songs) { songs.associateBy(Song::stableId) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCreatePlaylist, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Buat")
                }
                OutlinedButton(onClick = onImportPlaylist, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("Impor M3U", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        if (playlists.isEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Belum ada playlist. Buat satu atau impor M3U/M3U8.", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        items(playlists, key = LocalPlaylist::id, contentType = { "playlist" }) { playlist ->
            val resolved = playlist.songIds.mapNotNull(songsById::get)
            CollectionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = playlist.name,
                subtitle = "${resolved.size} lagu",
                onClick = { onOpenPlaylist(playlist) },
                onPlay = { if (resolved.isNotEmpty()) onPlaySongs(resolved, 0) },
                onExport = { onExportPlaylist(playlist) },
                onDelete = { onDeletePlaylist(playlist.id) },
            )
        }
        if (savedQueues.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                Text("Antrean tersimpan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            items(savedQueues, key = SavedQueue::id, contentType = { "queue" }) { queue ->
                val resolved = queue.songIds.mapNotNull(songsById::get)
                CollectionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    title = queue.name,
                    subtitle = "${resolved.size} lagu • ketuk untuk memuat",
                    onClick = { if (resolved.isNotEmpty()) onPlaySongs(resolved, 0) },
                    onPlay = { if (resolved.isNotEmpty()) onPlaySongs(resolved, 0) },
                    onDelete = { onDeleteSavedQueue(queue.id) },
                )
            }
        }
    }
}

@Composable
private fun CollectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onExport: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            MotionIconButton({ onPlay() }, Icons.Rounded.PlayArrow, "Putar", tint = MaterialTheme.colorScheme.onSurface)
            if (onExport != null) MotionIconButton({ onExport() }, Icons.Rounded.UploadFile, "Ekspor M3U8", tint = MaterialTheme.colorScheme.onSurface)
            MotionIconButton({ onDelete() }, Icons.Rounded.Delete, "Hapus", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlaylistDetail(
    playlist: LocalPlaylist,
    songs: List<Song>,
    favoriteIds: Set<String>,
    currentMediaId: String?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onRemoveSong: (String) -> Unit,
) {
    val byId = remember(songs) { songs.associateBy(Song::stableId) }
    val resolved = playlist.songIds.mapNotNull(byId::get)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton({ onBack() }, Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Text("${resolved.size} lagu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            MotionIconButton(
                { if (resolved.isNotEmpty()) onPlaySongs(resolved, 0) },
                Icons.Rounded.PlayArrow,
                "Putar playlist",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (resolved.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Playlist kosong", "Tambahkan lagu melalui menu tindakan lagu.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)) {
            items(resolved, key = { "pl_${it.id}_${it.stableId}" }, contentType = { "song" }) { song ->
                SongRow(
                    song = song,
                    isFavorite = song.stableId in favoriteIds,
                    isPlaying = song.stableId == currentMediaId,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onClick = { onPlaySongs(resolved, resolved.indexOf(song)) },
                    onLongPress = { onRemoveSong(song.stableId) },
                    onToggleFavorite = { onToggleFavorite(song.stableId) },
                    onSwipeQueue = { onAddToQueue(song) },
                    onAction = { onRemoveSong(song.stableId) },
                    actionIcon = Icons.Rounded.Delete,
                    actionDescription = "Hapus dari playlist",
                )
            }
        }
    }
}

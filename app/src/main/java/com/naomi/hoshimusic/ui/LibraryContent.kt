@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.naomi.hoshimusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.LibraryScanResult
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.domain.MusicGrouping
import com.naomi.hoshimusic.ui.library.*

@Composable
internal fun LibraryContent(
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelectMusicFolder: () -> Unit,
    libraryState: LibraryUiState,
    onRefresh: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    songListState: androidx.compose.foundation.lazy.LazyListState,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenActions: (Song) -> Unit,
    onToggleHiddenFolder: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onExportPlaylist: (LocalPlaylist) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onDeleteSavedQueue: (String) -> Unit,
    onRemoveSongFromPlaylist: (String, String) -> Unit,
    playbackMediaId: String?,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.Songs) }
    var detailPlaylistId by remember { mutableStateOf<String?>(null) }
    val detailPlaylist = remember(detailPlaylistId, libraryState.playlists) {
        libraryState.playlists.find { it.id == detailPlaylistId }
    }

    val refreshState = rememberPullToRefreshState()

    BackHandler(enabled = detailPlaylistId != null) { detailPlaylistId = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = {
                            selectedFilter = filter
                            detailPlaylistId = null
                        },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = libraryState.isLoading,
                onRefresh = onRefresh,
                state = refreshState,
                modifier = Modifier.weight(1f)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = detailPlaylist != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.92f),
                    exit = scaleOut(targetScale = 0.92f)
                ) {
                    detailPlaylist?.let { playlist ->
                        PlaylistDetail(
                            playlist = playlist,
                            songs = libraryState.songs,
                            favoriteIds = libraryState.favoriteIds,
                            currentMediaId = playbackMediaId,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onBack = { detailPlaylistId = null },
                            onPlaySongs = onPlaySongs,
                            onToggleFavorite = onToggleFavorite,
                            onAddToQueue = onAddToQueue,
                            onRemoveSong = { songId -> onRemoveSongFromPlaylist(playlist.id, songId) }
                        )
                    }
                }

                if (detailPlaylist == null) {
                    val scanResult = libraryState.scanResult
                    
                    when {
                        scanResult is LibraryScanResult.PermissionRequired -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Rounded.FolderOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.height(16.dp))
                                    Text("Izin Akses Musik Diperlukan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Izin diperlukan untuk memindai seluruh perangkat.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(24.dp))
                                    Button(onClick = onRequestPermission) { Text("Berikan Izin") }
                                }
                            }
                        }
                        libraryState.songs.isEmpty() && !libraryState.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Rounded.FolderOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.height(16.dp))
                                    Text("Koleksi Kosong", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (libraryState.selectedMusicFolder == null) "Pilih folder musik di pengaturan atau berikan izin akses musik untuk mulai."
                                        else "Tidak ditemukan file audio di folder ini.",
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    if (libraryState.selectedMusicFolder == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = onSelectMusicFolder) { Text("Pilih Folder") }
                                            Button(onClick = onRequestPermission) { Text("Scan Seluruh HP") }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            Column(Modifier.fillMaxSize()) {
                                if (libraryState.hasSongsHiddenByFilter && selectedFilter == LibraryFilter.Songs) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Text("File audio ditemukan, tetapi disembunyikan oleh Filter Durasi.", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                
                                when (selectedFilter) {
                                    LibraryFilter.Songs -> {
                                        SongList(
                                            songs = libraryState.filteredSongs,
                                            favoriteIds = libraryState.favoriteIds,
                                            currentMediaId = playbackMediaId,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            listState = songListState,
                                            onPlaySongs = onPlaySongs,
                                            onToggleFavorite = onToggleFavorite,
                                            onAddToQueue = onAddToQueue,
                                            onOpenActions = onOpenActions,
                                            emptyTitle = "Tidak Ada Lagu",
                                            emptyBody = "Tidak ditemukan file musik yang cocok dengan filter atau pencarian."
                                        )
                                    }
                                    LibraryFilter.Albums -> {
                                        val albums = remember(libraryState.songs) { MusicGrouping.albumGroups(libraryState.songs) }
                                        GroupGrid(
                                            groups = albums,
                                            icon = Icons.Rounded.Album,
                                            onPlayGroup = { onPlaySongs(it.songs, 0) }
                                        )
                                    }
                                    LibraryFilter.Artists -> {
                                        val artists = remember(libraryState.songs) { MusicGrouping.artistGroups(libraryState.songs) }
                                        GroupList(
                                            groups = artists,
                                            icon = Icons.Rounded.Person,
                                            emptyTitle = "Tidak ada artis",
                                            onPlayGroup = { onPlaySongs(it.songs, 0) }
                                        )
                                    }
                                    LibraryFilter.Playlists -> {
                                        PlaylistHub(
                                            playlists = libraryState.playlists,
                                            savedQueues = libraryState.savedQueues,
                                            songs = libraryState.songs,
                                            onCreatePlaylist = onCreatePlaylist,
                                            onImportPlaylist = onImportPlaylist,
                                            onOpenPlaylist = { onOpenPlaylist(it); detailPlaylistId = it.id },
                                            onPlaySongs = onPlaySongs,
                                            onExportPlaylist = onExportPlaylist,
                                            onDeletePlaylist = onDeletePlaylist,
                                            onDeleteSavedQueue = onDeleteSavedQueue
                                        )
                                    }
                                    LibraryFilter.Genres -> {
                                        val genres = remember(libraryState.songs) { MusicGrouping.genreGroups(libraryState.songs) }
                                        GroupGrid(
                                            groups = genres,
                                            icon = Icons.Rounded.GraphicEq,
                                            onPlayGroup = { onPlaySongs(it.songs, 0) }
                                        )
                                    }
                                    LibraryFilter.Folders -> {
                                        val folders = remember(libraryState.songs, libraryState.hiddenFolders) {
                                            MusicGrouping.folderGroups(libraryState.songs.filter { it.folder !in libraryState.hiddenFolders })
                                        }
                                        FolderScreen(
                                            groups = folders,
                                            hiddenFolders = libraryState.hiddenFolders,
                                            onPlayGroup = { onPlaySongs(it.songs, 0) },
                                            onToggleHiddenFolder = onToggleHiddenFolder
                                        )
                                    }
                                    LibraryFilter.Favorites -> {
                                        SongList(
                                            songs = libraryState.songs.filter { it.stableId in libraryState.favoriteIds },
                                            favoriteIds = libraryState.favoriteIds,
                                            currentMediaId = playbackMediaId,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            onPlaySongs = onPlaySongs,
                                            onToggleFavorite = onToggleFavorite,
                                            onAddToQueue = onAddToQueue,
                                            onOpenActions = onOpenActions,
                                            emptyTitle = "Belum ada favorit",
                                            emptyBody = "Geser lagu ke kanan untuk menambahkan."
                                        )
                                    }
                                    LibraryFilter.Activity -> {
                                        StatsScreen(
                                            songs = libraryState.songs,
                                            stats = libraryState.playStats,
                                            onPlaySongs = onPlaySongs
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

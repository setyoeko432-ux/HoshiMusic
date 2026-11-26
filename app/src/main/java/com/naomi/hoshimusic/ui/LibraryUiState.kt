package com.naomi.hoshimusic.ui

import androidx.compose.runtime.Immutable
import com.naomi.hoshimusic.data.LibraryScanResult
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.PlayStat
import com.naomi.hoshimusic.data.SavedQueue
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.domain.MusicGroup

@Immutable
internal data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val filteredSongs: List<Song> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val hiddenFolders: Set<String> = emptySet(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val savedQueues: List<SavedQueue> = emptyList(),
    val playStats: Map<String, PlayStat> = emptyMap(),
    val songsById: Map<String, Song> = emptyMap(),
    val recentlyPlayed: List<Song> = emptyList(),
    val frequentlyPlayed: List<Song> = emptyList(),
    val carouselSongs: List<Song> = emptyList(),
    val recentlyAdded: List<Song> = emptyList(),
    val albumGroups: List<MusicGroup> = emptyList(),
    val artistGroups: List<MusicGroup> = emptyList(),
    val genreGroups: List<MusicGroup> = emptyList(),
    val folderGroups: List<MusicGroup> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val totalListeningTimeMs: Long = 0L,
    val filterShortTracks: Boolean = false,
    val selectedMusicFolder: String? = null,
    val query: String = "",
    val isLoading: Boolean = false,
    val scanResult: LibraryScanResult? = null,
    val hasSongsHiddenByFilter: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
)

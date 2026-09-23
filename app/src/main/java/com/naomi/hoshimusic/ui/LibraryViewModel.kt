package com.naomi.hoshimusic.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.naomi.hoshimusic.data.LibraryScanResult
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.MusicRepository
import com.naomi.hoshimusic.data.PlayStat
import com.naomi.hoshimusic.data.PlaylistFileRepository
import com.naomi.hoshimusic.data.SavedQueue
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
private data class LibraryCore(
    val songs: List<Song>,
    val query: String,
    val isLoading: Boolean,
    val scanResult: LibraryScanResult?,
    val errorMessage: String?,
)

private data class PersistedLibrary(
    val favoriteIds: Set<String>,
    val hiddenFolders: Set<String>,
    val playlists: List<LocalPlaylist>,
    val savedQueues: List<SavedQueue>,
    val filterShortTracks: Boolean,
    val musicFolderUri: String?,
    val totalListeningTimeMs: Long,
)

internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application.applicationContext)
    private val preferences = UserPreferences(application.applicationContext)
    private val playlistFiles = PlaylistFileRepository(application.applicationContext)
    private val songs = MutableStateFlow<List<Song>>(emptyList())
    private val query = MutableStateFlow("")
    private val isLoading = MutableStateFlow(false)
    private val scanResult = MutableStateFlow<LibraryScanResult?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.allSongs.collect { songs.value = it }
        }
    }

    private val core = combine(songs, query, isLoading, scanResult, errorMessage) {
            allSongs, activeQuery, loading, scan, error ->
        LibraryCore(allSongs, activeQuery, loading, scan, error)
    }

    private val persisted = combine(
        preferences.favoriteSongIds,
        preferences.hiddenFolders,
        preferences.playlists,
        preferences.savedQueues,
        preferences.filterShortTracks,
        preferences.musicFolderUri,
        preferences.totalListeningTimeMs
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        PersistedLibrary(
            favoriteIds = args[0] as Set<String>,
            hiddenFolders = args[1] as Set<String>,
            playlists = args[2] as List<LocalPlaylist>,
            savedQueues = args[3] as List<SavedQueue>,
            filterShortTracks = args[4] as Boolean,
            musicFolderUri = args[5] as String?,
            totalListeningTimeMs = args[6] as Long
        )
    }

    // PERF: statistik hanya bergantung pada daftar lagu. Dipisah dari combine utama
    // agar peta O(n) ini TIDAK dihitung ulang pada setiap ketikan query pencarian.
    private val playStats = songs.map { list ->
        list.associate { it.stableId to PlayStat(it.playCount, it.lastPlayedAtMs) }
    }.flowOn(Dispatchers.Default)

    val uiState = combine(core, persisted, playStats, notice) { active, saved, statsMap, activeNotice ->
        var visibleSongs = active.songs.filterNot { it.folder in saved.hiddenFolders }

        val totalBeforeFilter = visibleSongs.size
        if (saved.filterShortTracks) {
            visibleSongs = visibleSongs.filter { it.durationMs >= 30_000L }
        }
        val hasHiddenByFilter = totalBeforeFilter > visibleSongs.size

        val normalizedQuery = active.query.trim()
        val filtered = if (normalizedQuery.isBlank()) visibleSongs else visibleSongs.filter { song ->
            song.title.contains(normalizedQuery, ignoreCase = true) ||
                song.artist.contains(normalizedQuery, ignoreCase = true) ||
                song.albumArtist.contains(normalizedQuery, ignoreCase = true) ||
                song.album.contains(normalizedQuery, ignoreCase = true) ||
                song.genre.contains(normalizedQuery, ignoreCase = true) ||
                song.folder.contains(normalizedQuery, ignoreCase = true)
        }

        LibraryUiState(
            songs = active.songs,
            filteredSongs = filtered,
            favoriteIds = saved.favoriteIds,
            hiddenFolders = saved.hiddenFolders,
            playlists = saved.playlists,
            savedQueues = saved.savedQueues,
            playStats = statsMap,
            totalListeningTimeMs = saved.totalListeningTimeMs,
            filterShortTracks = saved.filterShortTracks,
            selectedMusicFolder = saved.musicFolderUri,
            query = active.query,
            isLoading = active.isLoading,
            scanResult = active.scanResult,
            hasSongsHiddenByFilter = hasHiddenByFilter,
            errorMessage = active.errorMessage,
            notice = activeNotice,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryUiState(),
    )

    fun refresh() {
        if (isLoading.value) return
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            val result = runCatching { repository.loadSongs() }
                .getOrElse { error ->
                    LibraryScanResult.Error(error.message ?: "Koleksi musik tidak dapat dibaca.")
                }
            
            scanResult.value = result
            
            if (result is LibraryScanResult.Success) {
                repository.scheduleLoudnessScan()
            } else if (result is LibraryScanResult.Error) {
                errorMessage.value = result.message
            }
            
            isLoading.value = false
        }
    }

    fun updateQuery(value: String) { query.value = value }
    fun toggleFavorite(songId: String) { viewModelScope.launch { preferences.toggleFavorite(songId) } }

    fun setFilterShortTracks(enabled: Boolean) {
        viewModelScope.launch { preferences.setFilterShortTracks(enabled) }
    }

    fun onSelectMusicFolder(uri: String?) {
        viewModelScope.launch { 
            preferences.setMusicFolderUri(uri)
            refresh()
        }
    }

    fun toggleHiddenFolder(folder: String) {
        viewModelScope.launch {
            preferences.toggleHiddenFolder(folder)
            notice.value = if (folder in uiState.value.hiddenFolders) {
                "Folder ditampilkan kembali."
            } else {
                "Folder disembunyikan dari koleksi."
            }
        }
    }

    fun createPlaylist(name: String, songIds: List<String> = emptyList()) {
        viewModelScope.launch {
            preferences.createPlaylist(name, songIds)
            notice.value = "Playlist dibuat."
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            preferences.deletePlaylist(playlistId)
            notice.value = "Playlist dihapus."
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            preferences.addSongToPlaylist(playlistId, songId)
            notice.value = "Lagu ditambahkan ke playlist."
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            preferences.removeSongFromPlaylist(playlistId, songId)
            notice.value = "Lagu dikeluarkan dari playlist."
        }
    }

    fun saveQueue(name: String, songIds: List<String>) {
        viewModelScope.launch {
            preferences.saveNamedQueue(name, songIds)
            notice.value = "Antrean disimpan."
        }
    }

    fun deleteSavedQueue(queueId: String) {
        viewModelScope.launch {
            preferences.deleteSavedQueue(queueId)
            notice.value = "Antrean tersimpan dihapus."
        }
    }

    fun importM3u(uri: Uri) {
        viewModelScope.launch {
            runCatching { playlistFiles.importPlaylist(uri, songs.value) }
                .onSuccess { imported ->
                    if (imported.songIds.isEmpty()) {
                        notice.value = "Tidak ada lagu lokal yang cocok dengan playlist."
                    } else {
                        preferences.createPlaylist(imported.name, imported.songIds)
                        notice.value = buildString {
                            append("${imported.songIds.size} lagu diimpor")
                            if (imported.unmatchedEntries > 0) append(" • ${imported.unmatchedEntries} tidak ditemukan")
                        }
                    }
                }
                .onFailure { error -> notice.value = error.message ?: "Playlist tidak dapat diimpor." }
        }
    }

    fun exportM3u(uri: Uri, playlist: LocalPlaylist) {
        viewModelScope.launch {
            runCatching { playlistFiles.exportPlaylist(uri, playlist, songs.value) }
                .onSuccess { count -> notice.value = "$count lagu diekspor ke M3U8." }
                .onFailure { error -> notice.value = error.message ?: "Playlist tidak dapat diekspor." }
        }
    }

    fun clearNotice() { notice.value = null }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(application) as T
            }
            throw IllegalArgumentException("ViewModel tidak dikenal: ${modelClass.name}")
        }
    }
}

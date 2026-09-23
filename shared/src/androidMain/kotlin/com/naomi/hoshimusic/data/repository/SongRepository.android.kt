package com.naomi.hoshimusic.data.repository

import com.naomi.hoshimusic.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class SongRepository actual constructor() {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    actual val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    actual suspend fun scanFolder(folderPath: String): List<Song> {
        return _songs.value
    }

    actual suspend fun search(query: String): List<Song> {
        val q = query.lowercase()
        return _songs.value.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }

    actual suspend fun getSongByStableId(stableId: String): Song? {
        return _songs.value.find { it.stableId == stableId }
    }
}

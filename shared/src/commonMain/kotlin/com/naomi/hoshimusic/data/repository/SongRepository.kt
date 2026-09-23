package com.naomi.hoshimusic.data.repository

import com.naomi.hoshimusic.data.Song
import kotlinx.coroutines.flow.StateFlow

expect class SongRepository() {
    val songs: StateFlow<List<Song>>
    suspend fun scanFolder(folderPath: String): List<Song>
    suspend fun search(query: String): List<Song>
    suspend fun getSongByStableId(stableId: String): Song?
}

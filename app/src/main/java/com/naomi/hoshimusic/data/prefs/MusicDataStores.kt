package com.naomi.hoshimusic.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.SavedQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*

internal class MusicDataStores(private val dataStore: DataStore<Preferences>) {
    val playlists: Flow<List<LocalPlaylist>> = dataStore.data.map { JsonSerializers.decodePlaylists(it[PreferenceKeys.Playlists]) }
    val savedQueues: Flow<List<SavedQueue>> = dataStore.data.map { JsonSerializers.decodeQueues(it[PreferenceKeys.SavedQueues]) }
    val totalListeningTimeMs: Flow<Long> = dataStore.data.map { it[PreferenceKeys.TotalListeningTimeMs] ?: 0L }
    val loudnessScanLastRunMs: Flow<Long> = dataStore.data.map { it[PreferenceKeys.LoudnessScanLastRun] ?: 0L }
    val loudnessScanSuccess: Flow<Int> = dataStore.data.map { it[PreferenceKeys.LoudnessScanSuccess] ?: 0 }
    val loudnessScanTransient: Flow<Int> = dataStore.data.map { it[PreferenceKeys.LoudnessScanTransient] ?: 0 }
    val loudnessScanPermanent: Flow<Int> = dataStore.data.map { it[PreferenceKeys.LoudnessScanPermanent] ?: 0 }

    suspend fun incrementTotalListeningTime(deltaMs: Long) = dataStore.edit {
        val current = it[PreferenceKeys.TotalListeningTimeMs] ?: 0L
        it[PreferenceKeys.TotalListeningTimeMs] = current + deltaMs
    }

    suspend fun setLoudnessScanProgress(lastRunMs: Long, success: Int, transient: Int, permanent: Int) {
        dataStore.edit { p ->
            p[PreferenceKeys.LoudnessScanLastRun] = lastRunMs
            p[PreferenceKeys.LoudnessScanSuccess] = success
            p[PreferenceKeys.LoudnessScanTransient] = transient
            p[PreferenceKeys.LoudnessScanPermanent] = permanent
        }
    }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        dataStore.edit { p ->
            val updated = JsonSerializers.decodePlaylists(p[PreferenceKeys.Playlists]) + LocalPlaylist(id, name.trim().ifBlank { "Playlist baru" }, songIds.distinct(), System.currentTimeMillis())
            p[PreferenceKeys.Playlists] = JsonSerializers.encodePlaylists(updated)
        }
        return id
    }

    suspend fun deletePlaylist(playlistId: String) = dataStore.edit { p ->
        p[PreferenceKeys.Playlists] = JsonSerializers.encodePlaylists(JsonSerializers.decodePlaylists(p[PreferenceKeys.Playlists]).filterNot { it.id == playlistId })
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String) = dataStore.edit { p ->
        val updated = JsonSerializers.decodePlaylists(p[PreferenceKeys.Playlists]).map { if (it.id == playlistId && songId !in it.songIds) it.copy(songIds = it.songIds + songId) else it }
        p[PreferenceKeys.Playlists] = JsonSerializers.encodePlaylists(updated)
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = dataStore.edit { p ->
        val updated = JsonSerializers.decodePlaylists(p[PreferenceKeys.Playlists]).map { if (it.id == playlistId) it.copy(songIds = it.songIds.filterNot { it == songId }) else it }
        p[PreferenceKeys.Playlists] = JsonSerializers.encodePlaylists(updated)
    }

    suspend fun saveNamedQueue(name: String, songIds: List<String>) {
        if (songIds.isEmpty()) return
        dataStore.edit { p ->
            val nName = name.trim().ifBlank { "Antrean tersimpan" }
            val current = JsonSerializers.decodeQueues(p[PreferenceKeys.SavedQueues])
            val existing = current.firstOrNull { it.name.equals(nName, ignoreCase = true) }
            val replacement = SavedQueue(existing?.id ?: UUID.randomUUID().toString(), nName, songIds.distinct(), System.currentTimeMillis())
            p[PreferenceKeys.SavedQueues] = JsonSerializers.encodeQueues((current.filterNot { it.id == replacement.id } + replacement).sortedByDescending { it.updatedAtMs })
        }
    }

    suspend fun deleteSavedQueue(queueId: String) = dataStore.edit { p ->
        p[PreferenceKeys.SavedQueues] = JsonSerializers.encodeQueues(JsonSerializers.decodeQueues(p[PreferenceKeys.SavedQueues]).filterNot { it.id == queueId })
    }

    suspend fun remapPlaylistAndQueueIds(mapping: Map<String, String>) = dataStore.edit { p ->
        val playlists = JsonSerializers.decodePlaylists(p[PreferenceKeys.Playlists])
        val updatedPlaylists = playlists.map { it.copy(songIds = it.songIds.map { id -> mapping[id] ?: id }) }
        p[PreferenceKeys.Playlists] = JsonSerializers.encodePlaylists(updatedPlaylists)

        val queues = JsonSerializers.decodeQueues(p[PreferenceKeys.SavedQueues])
        val updatedQueues = queues.map { it.copy(songIds = it.songIds.map { id -> mapping[id] ?: id }) }
        p[PreferenceKeys.SavedQueues] = JsonSerializers.encodeQueues(updatedQueues)
    }
}

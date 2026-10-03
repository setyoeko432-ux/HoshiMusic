package com.naomi.hoshimusic.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class LibraryPreferences(private val dataStore: DataStore<Preferences>) {
    val favoriteSongIds: Flow<Set<String>> = dataStore.data.map { it[PreferenceKeys.FavoriteSongIds].orEmpty() }
    val hiddenFolders: Flow<Set<String>> = dataStore.data.map { it[PreferenceKeys.HiddenFolders].orEmpty() }
    val filterShortTracks: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.FilterShortTracks] ?: true }
    val aiTaggingEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.AiTaggingEnabled] ?: true }
    val musicFolderUri: Flow<String?> = dataStore.data.map { it[PreferenceKeys.MusicFolderUri] }
    val mediaIdMigrationDone: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.MediaIdMigrationDone] ?: false }
    val mediaIdMigrationVersion: Flow<Int> = dataStore.data.map { p ->
        p[PreferenceKeys.MediaIdMigrationVersion]
            ?: if (p[PreferenceKeys.MediaIdMigrationDone] == true) 1 else 0
    }

    suspend fun toggleFavorite(songId: String) = dataStore.edit { p ->
        val current = p[PreferenceKeys.FavoriteSongIds].orEmpty()
        p[PreferenceKeys.FavoriteSongIds] = if (songId in current) current - songId else current + songId
    }

    suspend fun toggleHiddenFolder(folder: String) = dataStore.edit { p ->
        val current = p[PreferenceKeys.HiddenFolders].orEmpty()
        p[PreferenceKeys.HiddenFolders] = if (folder in current) current - folder else current + folder
    }

    suspend fun setFilterShortTracks(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.FilterShortTracks] = enabled }
    suspend fun setAiTaggingEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.AiTaggingEnabled] = enabled }
    suspend fun setMusicFolderUri(uri: String?) = dataStore.edit { if (uri == null) it.remove(PreferenceKeys.MusicFolderUri) else it[PreferenceKeys.MusicFolderUri] = uri }
    suspend fun setMediaIdMigrationDone(done: Boolean) = dataStore.edit { it[PreferenceKeys.MediaIdMigrationDone] = done }
    suspend fun setMediaIdMigrationVersion(version: Int) {
        dataStore.edit { it[PreferenceKeys.MediaIdMigrationVersion] = version }
    }

    suspend fun remapFavoriteIds(mapping: Map<String, String>) = dataStore.edit { p ->
        val current = p[PreferenceKeys.FavoriteSongIds].orEmpty()
        val updated = current.map { mapping[it] ?: it }.toSet()
        if (updated != current) {
            p[PreferenceKeys.FavoriteSongIds] = updated
        }
    }
}

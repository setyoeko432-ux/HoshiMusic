package com.naomi.hoshimusic.data

import android.content.Context
import com.naomi.hoshimusic.data.prefs.*
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import kotlinx.coroutines.flow.Flow

enum class LyricSourcePriority {
    EXTERNAL_FIRST,
    METADATA_FIRST
}

data class PeqBand(val type: Int, val fc: Float, val gain: Float, val q: Float)
data class PeqProfile(val id: String, val name: String, val bands: List<PeqBand>, val preamp: Float = 0f)

class UserPreferences(context: Context) {
    private val dataStore = context.userPreferencesDataStore
    
    private val playback = PlaybackPreferences(dataStore)
    private val appearance = AppearancePreferences(dataStore)
    private val library = LibraryPreferences(dataStore)
    private val dsp = DspPreferences(dataStore)
    private val musicData = MusicDataStores(dataStore)

    val favoriteSongIds: Flow<Set<String>> = library.favoriteSongIds
    val hiddenFolders: Flow<Set<String>> = library.hiddenFolders
    val playlists: Flow<List<LocalPlaylist>> = musicData.playlists
    val savedQueues: Flow<List<SavedQueue>> = musicData.savedQueues
    
    val lyricSourcePriority: Flow<LyricSourcePriority> = playback.lyricSourcePriority
    val gaplessEnabled: Flow<Boolean> = playback.gaplessEnabled
    val normalizationEnabled: Flow<Boolean> = playback.normalizationEnabled
    val btGuardMode: Flow<Int> = playback.btGuardMode
    val ambienceEnabled: Flow<Boolean> = playback.ambienceEnabled
    val preampDb: Flow<Float> = playback.preampDb
    val spatialAudioEnabled: Flow<Boolean> = playback.spatialAudioEnabled
    val spatialAudioPreset: Flow<Int> = playback.spatialAudioPreset
    val boutiqueBypassEnabled: Flow<Boolean> = playback.boutiqueBypassEnabled
    val wiredBitPerfectPreferred: Flow<Boolean> = playback.wiredBitPerfectPreferred

    val dynamicColorEnabled: Flow<Boolean> = appearance.dynamicColorEnabled
    val appTheme: Flow<Int> = appearance.appTheme
    val romanizationEnabled: Flow<Boolean> = appearance.romanizationEnabled
    val translationMode: Flow<Boolean> = appearance.translationMode
    val autoSkipIntroOutro: Flow<Boolean> = playback.autoSkipIntroOutro
    val landscapeDualPaneEnabled: Flow<Boolean> = appearance.landscapeDualPaneEnabled
    
    val mediaIdMigrationDone: Flow<Boolean> = library.mediaIdMigrationDone
    val mediaIdMigrationVersion: Flow<Int> = library.mediaIdMigrationVersion
    val filterShortTracks: Flow<Boolean> = library.filterShortTracks
    val musicFolderUri: Flow<String?> = library.musicFolderUri
    
    val totalListeningTimeMs: Flow<Long> = musicData.totalListeningTimeMs
    val loudnessScanLastRunMs: Flow<Long> = musicData.loudnessScanLastRunMs
    val loudnessScanSuccess: Flow<Int> = musicData.loudnessScanSuccess
    val loudnessScanTransient: Flow<Int> = musicData.loudnessScanTransient
    val loudnessScanPermanent: Flow<Int> = musicData.loudnessScanPermanent

    val dspEnabled: Flow<Boolean> = dsp.dspEnabled
    val dspPresetId: Flow<String> = dsp.dspPresetId
    val customDspPresets: Flow<List<CustomDspPreset>> = dsp.customDspPresets
    val peqEnabled: Flow<Boolean> = dsp.peqEnabled
    val peqProfiles: Flow<List<PeqProfile>> = dsp.peqProfiles
    val activePeqProfileId: Flow<String> = dsp.activePeqProfileId
    val routeProfiles: Flow<Map<String, String>> = dsp.routeProfiles

    suspend fun setLoudnessScanProgress(lastRunMs: Long, success: Int, transient: Int, permanent: Int) =
        musicData.setLoudnessScanProgress(lastRunMs, success, transient, permanent)

    suspend fun incrementTotalListeningTime(deltaMs: Long) = musicData.incrementTotalListeningTime(deltaMs)

    suspend fun setGaplessEnabled(enabled: Boolean) = playback.setGaplessEnabled(enabled)
    suspend fun setNormalizationEnabled(enabled: Boolean) = playback.setNormalizationEnabled(enabled)
    suspend fun setBtGuardMode(mode: Int) = playback.setBtGuardMode(mode)
    suspend fun setAmbienceEnabled(enabled: Boolean) = playback.setAmbienceEnabled(enabled)
    suspend fun setPreampDb(db: Float) = playback.setPreampDb(db)
    suspend fun setSpatialAudioEnabled(enabled: Boolean) = playback.setSpatialAudioEnabled(enabled)
    suspend fun setSpatialAudioPreset(preset: Int) = playback.setSpatialAudioPreset(preset)
    suspend fun setBoutiqueBypassEnabled(enabled: Boolean) = playback.setBoutiqueBypassEnabled(enabled)
    suspend fun setWiredBitPerfectPreferred(enabled: Boolean) = playback.setWiredBitPerfectPreferred(enabled)
    suspend fun setLyricSourcePriority(priority: LyricSourcePriority) = playback.setLyricSourcePriority(priority)

    suspend fun setDynamicColorEnabled(enabled: Boolean) = appearance.setDynamicColorEnabled(enabled)
    suspend fun setAppTheme(theme: Int) = appearance.setAppTheme(theme)
    suspend fun setRomanizationEnabled(enabled: Boolean) = appearance.setRomanizationEnabled(enabled)
    suspend fun setTranslationMode(enabled: Boolean) = appearance.setTranslationMode(enabled)
    suspend fun setAutoSkipIntroOutro(enabled: Boolean) = playback.setAutoSkipIntroOutro(enabled)
    suspend fun setLandscapeDualPaneEnabled(enabled: Boolean) = appearance.setLandscapeDualPaneEnabled(enabled)

    suspend fun setFilterShortTracks(enabled: Boolean) = library.setFilterShortTracks(enabled)
    suspend fun setMusicFolderUri(uri: String?) = library.setMusicFolderUri(uri)
    suspend fun setMediaIdMigrationDone(done: Boolean) = library.setMediaIdMigrationDone(done)
    suspend fun setMediaIdMigrationVersion(version: Int) =
        library.setMediaIdMigrationVersion(version)
    suspend fun toggleFavorite(songId: String) = library.toggleFavorite(songId)
    suspend fun toggleHiddenFolder(folder: String) = library.toggleHiddenFolder(folder)

    suspend fun setDspEnabled(enabled: Boolean) = dsp.setDspEnabled(enabled)
    suspend fun setDspPresetId(id: String) = dsp.setDspPresetId(id)
    suspend fun setPeqEnabled(enabled: Boolean) = dsp.setPeqEnabled(enabled)
    suspend fun setActivePeqProfile(id: String) = dsp.setActivePeqProfile(id)
    suspend fun savePeqProfile(profile: PeqProfile) = dsp.savePeqProfile(profile)
    suspend fun deletePeqProfile(id: String) = dsp.deletePeqProfile(id)
    suspend fun setRouteProfileBinding(key: String, presetId: String) = dsp.setRouteProfileBinding(key, presetId)
    suspend fun clearRouteProfiles() = dsp.clearRouteProfiles()
    suspend fun saveCustomDspPreset(preset: CustomDspPreset) = dsp.saveCustomDspPreset(preset)
    suspend fun deleteCustomDspPreset(id: String) = dsp.deleteCustomDspPreset(id)

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()) = musicData.createPlaylist(name, songIds)
    suspend fun deletePlaylist(playlistId: String) = musicData.deletePlaylist(playlistId)
    suspend fun addSongToPlaylist(playlistId: String, songId: String) = musicData.addSongToPlaylist(playlistId, songId)
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = musicData.removeSongFromPlaylist(playlistId, songId)
    suspend fun saveNamedQueue(name: String, songIds: List<String>) = musicData.saveNamedQueue(name, songIds)
    suspend fun deleteSavedQueue(queueId: String) = musicData.deleteSavedQueue(queueId)

    suspend fun remapMediaIds(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        library.remapFavoriteIds(mapping)
        musicData.remapPlaylistAndQueueIds(mapping)
    }
}

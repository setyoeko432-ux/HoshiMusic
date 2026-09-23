package com.naomi.hoshimusic.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore

internal val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

internal object PreferenceKeys {
    val FavoriteSongIds = stringSetPreferencesKey("favorite_song_ids")
    val HiddenFolders = stringSetPreferencesKey("hidden_folders")
    val Playlists = stringPreferencesKey("playlists_json")
    val SavedQueues = stringPreferencesKey("saved_queues_json")
    val LyricPriority = intPreferencesKey("lyric_priority")
    val GaplessEnabled = booleanPreferencesKey("gapless_enabled")
    val NormalizationEnabled = booleanPreferencesKey("normalization_enabled")
    val DynamicColorEnabled = booleanPreferencesKey("dynamic_color_enabled")
    val FilterShortTracks = booleanPreferencesKey("filter_short_tracks")
    val AppTheme = intPreferencesKey("app_theme")
    val RomanizationEnabled = booleanPreferencesKey("romanization_enabled")
    val LandscapeDualPaneEnabled = booleanPreferencesKey("landscape_dual_pane_enabled")
    val MusicFolderUri = stringPreferencesKey("music_folder_uri")
    val TotalListeningTimeMs = longPreferencesKey("total_listening_time_ms")
    val DspEnabled = booleanPreferencesKey("dsp_enabled")
    val DspPresetId = stringPreferencesKey("dsp_preset_id")
    val CustomDspPresets = stringPreferencesKey("custom_dsp_presets_json")
    val PeqEnabled = booleanPreferencesKey("peq_enabled")
    val BtGuardMode = intPreferencesKey("bt_guard_mode")
    val AmbienceEnabled = booleanPreferencesKey("ambience_enabled")
    val PreampDb = floatPreferencesKey("preamp_db")
    val SpatialAudioEnabled = booleanPreferencesKey("spatial_audio_enabled")
    val SpatialAudioPreset = intPreferencesKey("spatial_audio_preset")
    val PeqProfiles = stringPreferencesKey("peq_profiles_json")
    val ActivePeqProfileId = stringPreferencesKey("active_peq_profile_id")
    val RouteProfiles = stringPreferencesKey("route_profiles_json")
    val LoudnessScanLastRun = longPreferencesKey("loudness_scan_last_run")
    val LoudnessScanSuccess = intPreferencesKey("loudness_scan_success")
    val LoudnessScanTransient = intPreferencesKey("loudness_scan_transient")
    val LoudnessScanPermanent = intPreferencesKey("loudness_scan_permanent")
    val MediaIdMigrationDone = booleanPreferencesKey("media_id_migration_done")
    val MediaIdMigrationVersion = intPreferencesKey("media_id_migration_version")
    val TranslationMode = booleanPreferencesKey("secondary_lyric_translation")
    val AutoSkipIntroOutro = booleanPreferencesKey("auto_skip_intro_outro")
    val BoutiqueBypassEnabled = booleanPreferencesKey("boutique_bypass_enabled")
    val WiredBitPerfectPreferred = booleanPreferencesKey("wired_bit_perfect_preferred")
}

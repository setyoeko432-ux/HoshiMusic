package com.naomi.hoshimusic.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.naomi.hoshimusic.data.LyricSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class PlaybackPreferences(private val dataStore: DataStore<Preferences>) {
    val lyricSourcePriority: Flow<LyricSourcePriority> = dataStore.data.map {
        val index = it[PreferenceKeys.LyricPriority] ?: LyricSourcePriority.EXTERNAL_FIRST.ordinal
        LyricSourcePriority.entries.getOrElse(index) { LyricSourcePriority.EXTERNAL_FIRST }
    }

    val gaplessEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.GaplessEnabled] ?: true }
    val normalizationEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.NormalizationEnabled] ?: false }
    val btGuardMode: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BtGuardMode] ?: 0 }
    val ambienceEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.AmbienceEnabled] ?: false }
    val preampDb: Flow<Float> = dataStore.data.map { it[PreferenceKeys.PreampDb] ?: 0f }
    val spatialAudioEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.SpatialAudioEnabled] ?: false }
    val spatialAudioPreset: Flow<Int> = dataStore.data.map { it[PreferenceKeys.SpatialAudioPreset] ?: 0 }
    val autoSkipIntroOutro: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.AutoSkipIntroOutro] ?: false }
    val boutiqueBypassEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.BoutiqueBypassEnabled] ?: false }
    val wiredBitPerfectPreferred: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.WiredBitPerfectPreferred] ?: false }
    val lowLatencyAudio: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.LowLatencyAudio] ?: false }

    suspend fun setGaplessEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.GaplessEnabled] = enabled }
    suspend fun setNormalizationEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.NormalizationEnabled] = enabled }
    suspend fun setBtGuardMode(mode: Int) = dataStore.edit { it[PreferenceKeys.BtGuardMode] = mode.coerceIn(0, 2) }
    suspend fun setAmbienceEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.AmbienceEnabled] = enabled }
    suspend fun setPreampDb(db: Float) = dataStore.edit { it[PreferenceKeys.PreampDb] = db.coerceIn(-6f, 6f) }
    suspend fun setSpatialAudioEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.SpatialAudioEnabled] = enabled }
    suspend fun setSpatialAudioPreset(preset: Int) = dataStore.edit { it[PreferenceKeys.SpatialAudioPreset] = preset.coerceIn(0, 2) }
    suspend fun setAutoSkipIntroOutro(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.AutoSkipIntroOutro] = enabled }
    suspend fun setBoutiqueBypassEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.BoutiqueBypassEnabled] = enabled }
    suspend fun setWiredBitPerfectPreferred(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.WiredBitPerfectPreferred] = enabled }
    suspend fun setLowLatencyAudio(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.LowLatencyAudio] = enabled }
    suspend fun setLyricSourcePriority(priority: LyricSourcePriority) = dataStore.edit { it[PreferenceKeys.LyricPriority] = priority.ordinal }
}

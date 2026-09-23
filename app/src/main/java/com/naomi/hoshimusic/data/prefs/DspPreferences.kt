package com.naomi.hoshimusic.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.naomi.hoshimusic.data.PeqProfile
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DspPreferences(private val dataStore: DataStore<Preferences>) {
    val dspEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.DspEnabled] ?: false }
    val dspPresetId: Flow<String> = dataStore.data.map { it[PreferenceKeys.DspPresetId] ?: "reference" }
    val customDspPresets: Flow<List<CustomDspPreset>> = dataStore.data.map { JsonSerializers.decodeCustomDspPresets(it[PreferenceKeys.CustomDspPresets]) }
    val peqEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.PeqEnabled] ?: false }
    val peqProfiles: Flow<List<PeqProfile>> = dataStore.data.map { JsonSerializers.decodePeqProfiles(it[PreferenceKeys.PeqProfiles]) }
    val activePeqProfileId: Flow<String> = dataStore.data.map { it[PreferenceKeys.ActivePeqProfileId] ?: "" }
    val routeProfiles: Flow<Map<String, String>> = dataStore.data.map { JsonSerializers.decodeRouteProfiles(it[PreferenceKeys.RouteProfiles]) }

    suspend fun setDspEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.DspEnabled] = enabled }
    suspend fun setDspPresetId(id: String) = dataStore.edit { it[PreferenceKeys.DspPresetId] = id }
    suspend fun setPeqEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.PeqEnabled] = enabled }
    suspend fun setActivePeqProfile(id: String) = dataStore.edit { it[PreferenceKeys.ActivePeqProfileId] = id }

    suspend fun savePeqProfile(profile: PeqProfile) = dataStore.edit { p ->
        val updated = JsonSerializers.decodePeqProfiles(p[PreferenceKeys.PeqProfiles]).filterNot { it.id == profile.id } + profile
        p[PreferenceKeys.PeqProfiles] = JsonSerializers.encodePeqProfiles(updated)
    }

    suspend fun deletePeqProfile(id: String) = dataStore.edit { p ->
        p[PreferenceKeys.PeqProfiles] = JsonSerializers.encodePeqProfiles(JsonSerializers.decodePeqProfiles(p[PreferenceKeys.PeqProfiles]).filterNot { it.id == id })
    }

    suspend fun setRouteProfileBinding(key: String, presetId: String) = dataStore.edit { p ->
        val updated = JsonSerializers.decodeRouteProfiles(p[PreferenceKeys.RouteProfiles]) + (key to presetId)
        p[PreferenceKeys.RouteProfiles] = JsonSerializers.encodeRouteProfiles(updated)
    }

    suspend fun clearRouteProfiles() = dataStore.edit { it.remove(PreferenceKeys.RouteProfiles) }

    suspend fun saveCustomDspPreset(preset: CustomDspPreset) = dataStore.edit { p ->
        val updated = JsonSerializers.decodeCustomDspPresets(p[PreferenceKeys.CustomDspPresets]).filterNot { it.id == preset.id } + preset
        p[PreferenceKeys.CustomDspPresets] = JsonSerializers.encodeCustomDspPresets(updated)
    }

    suspend fun deleteCustomDspPreset(id: String) = dataStore.edit { p ->
        p[PreferenceKeys.CustomDspPresets] = JsonSerializers.encodeCustomDspPresets(JsonSerializers.decodeCustomDspPresets(p[PreferenceKeys.CustomDspPresets]).filterNot { it.id == id })
    }
}

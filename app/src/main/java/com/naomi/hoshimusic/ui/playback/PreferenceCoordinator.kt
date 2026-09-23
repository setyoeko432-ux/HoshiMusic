package com.naomi.hoshimusic.ui.playback

import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.playback.route.BtClarityBridge
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.ui.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class PreferenceCoordinator(
    private val scope: CoroutineScope,
    private val preferences: UserPreferences,
    private val mutableUiState: MutableStateFlow<PlaybackUiState>,
    private val onReloadEnhancements: (String) -> Unit,
    private val onApplyOutputVolume: () -> Unit,
    private val onSyncState: () -> Unit,
    private val onRecallPresetForRoute: (Map<String, String>) -> Unit
) {
    fun setupObservers() {
        scope.launch {
            combine(
                preferences.favoriteSongIds,
                preferences.lyricSourcePriority,
                preferences.gaplessEnabled,
                preferences.normalizationEnabled,
                preferences.dynamicColorEnabled,
                preferences.appTheme,
                preferences.romanizationEnabled,
                preferences.landscapeDualPaneEnabled,
                preferences.dspEnabled,
                preferences.peqEnabled,
                preferences.dspPresetId,
                preferences.customDspPresets,
                BtClarityBridge.active,
                preferences.btGuardMode,
                preferences.ambienceEnabled,
                preferences.preampDb,
                preferences.spatialAudioEnabled,
                preferences.spatialAudioPreset,
                preferences.peqProfiles,
                preferences.activePeqProfileId,
                preferences.routeProfiles,
                preferences.translationMode,
                preferences.autoSkipIntroOutro,
                preferences.boutiqueBypassEnabled
            ) { values ->
                values.copyOf() // Ensure we have a snapshot
            }
            .distinctUntilChanged { old, new -> old.contentDeepEquals(new) }
            .collect { v ->
                val favs = v[0] as Set<String>
                val lPrio = v[1] as LyricSourcePriority
                val gapless = v[2] as Boolean
                val norm = v[3] as Boolean
                val dynColor = v[4] as Boolean
                val theme = v[5] as Int
                val rom = v[6] as Boolean
                val dual = v[7] as Boolean
                val dspE = v[8] as Boolean
                val peqE = v[9] as Boolean
                val dspP = v[10] as String
                @Suppress("UNCHECKED_CAST")
                val dspC = v[11] as List<CustomDspPreset>
                val btA = v[12] as Boolean
                val btM = v[13] as Int
                val amb = v[14] as Boolean
                val pre = v[15] as Float
                val spatialEnabled = v[16] as Boolean
                val spatialPreset = v[17] as Int
                @Suppress("UNCHECKED_CAST")
                val peqs = v[18] as List<PeqProfile>
                val peqA = v[19] as String
                @Suppress("UNCHECKED_CAST")
                val routes = v[20] as Map<String, String>
                val trans = v[21] as Boolean
                val skip = v[22] as Boolean
                val boutique = v[23] as Boolean

                val oldState = mutableUiState.value
                val normalizationChanged = oldState.normalizationEnabled != norm
                val lyricsInputChanged = oldState.romanizationEnabled != rom ||
                        oldState.lyricPriority != lPrio ||
                        oldState.translationMode != trans

                val resolvedMode = when {
                    trans -> LyricDisplayMode.TRANSLATED
                    rom -> LyricDisplayMode.ROMANIZED
                    else -> LyricDisplayMode.ORIGINAL
                }

                mutableUiState.update {
                    it.copy(
                        lyricPriority = lPrio,
                        gaplessEnabled = gapless,
                        normalizationEnabled = norm,
                        dynamicColorEnabled = dynColor,
                        appTheme = theme,
                        romanizationEnabled = rom,
                        landscapeDualPaneEnabled = dual,
                        dspEnabled = dspE,
                        peqEnabled = peqE,
                        dspPresetId = dspP,
                        customDspPresets = dspC,
                        btClarityActive = btA,
                        btGuardMode = btM,
                        ambienceEnabled = amb,
                        preampDb = pre,
                        spatialAudioEnabled = spatialEnabled,
                        spatialAudioPreset = spatialPreset,
                        peqProfiles = peqs,
                        activePeqProfileId = peqA,
                        routeProfiles = routes,
                        translationMode = trans,
                        displayMode = resolvedMode,
                        autoSkipIntroOutro = skip,
                        boutiqueBypassEnabled = boutique
                    )
                }
                
                if (lyricsInputChanged) {
                    // LyricsRepository.load() menerima `romanize` dan `priority`, dan keduanya
                    // ikut jadi cache key, jadi hasil lama tidak valid lagi. Reload ini murah
                    // kalau versi tersebut pernah dibuat.
                    oldState.mediaId?.let { onReloadEnhancements(it) }
                }

                if (normalizationChanged) {
                    onApplyOutputVolume()
                }
                onRecallPresetForRoute(routes)
                onSyncState()
            }
        }
    }

    fun setLyricPriority(p: LyricSourcePriority) = scope.launch { preferences.setLyricSourcePriority(p) }
    fun setGaplessEnabled(e: Boolean) = scope.launch { preferences.setGaplessEnabled(e) }
    fun setNormalizationEnabled(e: Boolean) = scope.launch { preferences.setNormalizationEnabled(e); onApplyOutputVolume() }
    fun setDynamicColorEnabled(e: Boolean) = scope.launch { preferences.setDynamicColorEnabled(e) }
    fun setAppTheme(t: Int) = scope.launch { preferences.setAppTheme(t) }
    fun setRomanizationEnabled(e: Boolean) = scope.launch { 
        preferences.setRomanizationEnabled(e)
        if (e) preferences.setTranslationMode(false)
    }
    fun setTranslationMode(e: Boolean) = scope.launch { 
        preferences.setTranslationMode(e)
        if (e) preferences.setRomanizationEnabled(false)
    }
    fun setAutoSkipIntroOutro(e: Boolean) = scope.launch { preferences.setAutoSkipIntroOutro(e) }
    fun setBoutiqueBypassEnabled(e: Boolean) = scope.launch { preferences.setBoutiqueBypassEnabled(e) }
    fun setLandscapeDualPaneEnabled(e: Boolean) = scope.launch { preferences.setLandscapeDualPaneEnabled(e) }
    fun setDspEnabled(e: Boolean) = scope.launch { preferences.setDspEnabled(e) }
    fun setPeqEnabled(e: Boolean) = scope.launch { preferences.setPeqEnabled(e) }
    
    fun setDspPreset(id: String, routeKey: () -> String?) = scope.launch {
        preferences.setDspPresetId(id)
        routeKey()?.let { preferences.setRouteProfileBinding(it, id) }
        
        // Zero-latency: resolve params and push immediately via bridge
        val customs = preferences.customDspPresets.first()
        val params = DspPresets.resolveParams(id, customs).toFloatArray()
        com.naomi.hoshimusic.playback.dsp.DspPreviewBridge.setPreviewParams(params)
    }
    
    fun setBtGuardMode(m: Int) = scope.launch { preferences.setBtGuardMode(m) }
    fun setAmbienceEnabled(e: Boolean) = scope.launch { preferences.setAmbienceEnabled(e) }
    fun setPreampDb(d: Float) = scope.launch { preferences.setPreampDb(d) }
    fun setSpatialAudioEnabled(e: Boolean) = scope.launch { preferences.setSpatialAudioEnabled(e) }
    fun setSpatialAudioPreset(p: Int) = scope.launch { preferences.setSpatialAudioPreset(p) }
    fun setActivePeqProfile(id: String) = scope.launch { preferences.setActivePeqProfile(id) }
    
    fun savePeqProfile(id: String?, bands: List<PeqBand>, preamp: Float, name: String) = scope.launch {
        val targetId = id ?: "peq_${System.currentTimeMillis()}"
        preferences.savePeqProfile(PeqProfile(targetId, name, bands, preamp))
        preferences.setActivePeqProfile(targetId) // Automatically activate the new/edited profile
    }
    
    fun deletePeqProfile(id: String) = scope.launch { preferences.deletePeqProfile(id) }
    fun clearRouteProfiles() = scope.launch { preferences.clearRouteProfiles() }
    
    fun saveCustomDspPreset(title: String, params: List<Float>) = scope.launch {
        val id = "custom_${System.currentTimeMillis()}"
        preferences.saveCustomDspPreset(CustomDspPreset(id, title, params))
    }
    
    fun deleteCustomDspPreset(id: String) = scope.launch { preferences.deleteCustomDspPreset(id) }
}

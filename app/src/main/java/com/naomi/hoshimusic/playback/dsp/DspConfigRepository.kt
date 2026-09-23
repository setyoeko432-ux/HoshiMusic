package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.data.PeqProfile
import com.naomi.hoshimusic.data.UserPreferences
import com.naomi.hoshimusic.playback.route.BtClarityBridge
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.playback.HoshiDspProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.pow

/**
 * Repository yang menggabungkan berbagai sumber data (DataStore, Bridge) 
 * menjadi satu aliran snapshot DspRuntimeConfig.
 */
class DspConfigRepository(
    private val preferences: UserPreferences
) {
    val configFlow: Flow<DspRuntimeConfig> = combine(
        preferences.dspEnabled,
        preferences.peqEnabled,
        preferences.dspPresetId,
        preferences.customDspPresets,
        preferences.btGuardMode,
        BtClarityBridge.active,
        BtClarityBridge.a2dpPresent,
        preferences.ambienceEnabled,
        preferences.preampDb,
        preferences.spatialAudioEnabled,
        preferences.spatialAudioPreset,
        preferences.activePeqProfileId,
        preferences.peqProfiles,
        DspPreviewBridge.previewPeq,
        DspPreviewBridge.previewParams
    ) { values ->
        val enabled = values[0] as Boolean
        val peqEnabled = values[1] as Boolean
        val presetId = values[2] as String
        @Suppress("UNCHECKED_CAST")
        val customs = values[3] as List<CustomDspPreset>
        val guardMode = values[4] as Int
        val btActive = values[5] as Boolean
        val a2dpPresent = values[6] as Boolean
        val ambience = values[7] as Boolean
        val manualPreampDb = values[8] as Float
        val spatialEnabled = values[9] as Boolean
        val spatialPreset = values[10] as Int
        val activePeqId = values[11] as String
        @Suppress("UNCHECKED_CAST")
        val peqProfiles = values[12] as List<PeqProfile>
        val preview = values[13] as PeqProfile?
        val previewParams = values[14] as FloatArray?

        val params = previewParams ?: DspPresets.resolveParams(presetId, customs).toFloatArray()
        
        // [Issue 8]: Ambience Mode (room-reflection modifier)
        if (ambience) {
            params[DspPresets.IDX_WIDTH] = (params[DspPresets.IDX_WIDTH] * 1.25f).coerceAtMost(2.0f)
            params[DspPresets.IDX_SATURATE] = (params[DspPresets.IDX_SATURATE] * 1.15f).coerceAtMost(2.0f)
            params[DspPresets.IDX_DEESSER] = (params[DspPresets.IDX_DEESSER] * 0.85f).coerceAtLeast(0f)
        }

        val effectiveBtClarity = when (guardMode) {
            1 -> a2dpPresent
            2 -> false
            else -> btActive
        }

        val peqProfile = preview ?: peqProfiles.firstOrNull { it.id == activePeqId }
        val bands = peqProfile?.bands ?: emptyList()
        val totalPreampDb = manualPreampDb + (peqProfile?.preamp ?: 0f)
        val linearPreamp = 10.0.pow(totalPreampDb.toDouble() / 20.0).toFloat()

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        DspRuntimeConfig(
            enabled = enabled,
            adaptiveDspEnabled = enabled,
            peqEnabled = if (preview != null) true else peqEnabled,
            params = params,
            btClarityEnabled = effectiveBtClarity,
            ambienceEnabled = ambience,
            preampLinear = linearPreamp,
            spatialAudioEnabled = spatialEnabled,
            spatialAudioPreset = spatialPreset,
            peqBands = bands.take(HoshiDspProcessor.DSP_PEQ_MAX),
            peqCount = bands.size.coerceAtMost(HoshiDspProcessor.DSP_PEQ_MAX)
        )
    }.distinctUntilChanged()
}

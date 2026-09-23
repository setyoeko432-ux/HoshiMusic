package com.naomi.hoshimusic.playback.dsp

import androidx.compose.runtime.Immutable
import com.naomi.hoshimusic.data.PeqBand

/**
 * Snapshot konfigurasi DSP yang immutable.
 * Digunakan untuk memastikan seluruh parameter dikirim ke native sebagai satu kesatuan.
 */
@Immutable
data class DspRuntimeConfig(
    val enabled: Boolean = false, // Legacy
    val adaptiveDspEnabled: Boolean = false,
    val peqEnabled: Boolean = false,
    val params: FloatArray = FloatArray(15) { 1.0f },
    val btClarityEnabled: Boolean = false,
    val ambienceEnabled: Boolean = false,
    val preampLinear: Float = 1.0f,
    val spatialAudioEnabled: Boolean = false,
    val spatialAudioPreset: Int = 0,
    val peqBands: List<PeqBand> = emptyList(),
    val peqCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DspRuntimeConfig) return false
        if (enabled != other.enabled) return false
        if (adaptiveDspEnabled != other.adaptiveDspEnabled) return false
        if (peqEnabled != other.peqEnabled) return false
        if (!params.contentEquals(other.params)) return false
        if (btClarityEnabled != other.btClarityEnabled) return false
        if (ambienceEnabled != other.ambienceEnabled) return false
        if (preampLinear != other.preampLinear) return false
        if (spatialAudioEnabled != other.spatialAudioEnabled) return false
        if (spatialAudioPreset != other.spatialAudioPreset) return false
        if (peqBands != other.peqBands) return false
        if (peqCount != other.peqCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + adaptiveDspEnabled.hashCode()
        result = 31 * result + peqEnabled.hashCode()
        result = 31 * result + params.contentHashCode()
        result = 31 * result + btClarityEnabled.hashCode()
        result = 31 * result + ambienceEnabled.hashCode()
        result = 31 * result + preampLinear.hashCode()
        result = 31 * result + spatialAudioEnabled.hashCode()
        result = 31 * result + spatialAudioPreset.hashCode()
        result = 31 * result + peqBands.hashCode()
        result = 31 * result + peqCount.hashCode()
        return result
    }
}

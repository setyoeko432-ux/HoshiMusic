package com.naomi.hoshimusic.playback.dsp

import androidx.compose.runtime.Immutable

/**
 * [V33]: State validitas telemetry vokal dari NDK.
 */
@Immutable
data class VocalTelemetryState(
    val vocalEnergy: Float,
    val isValid: Boolean,
    val isProcessingAudio: Boolean,
    val frameProgress: Long,
    val updatedAtMs: Long
)

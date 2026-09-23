package com.naomi.hoshimusic.playback

import androidx.compose.runtime.Immutable

@Immutable
data class SourceAudioFormat(
    val mime: String = "unknown",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0
)

@Immutable
data class OutputSinkCapability(
    val deviceName: String = "Internal Speaker",
    val codec: String = "PCM",
    val maxSampleRate: Int = 48000,
    val maxBitDepth: Int = 16,
    val isDownsamplingRequired: Boolean = false
)

/**
 * Status jalur audio yang dilaporkan ke UI.
 */
enum class AudioPathStatus {
    DSP_ACTIVE,       // Native DSP engine terpasang dan benar-benar memproses audio
    DSP_BYPASS,       // DSP dimatikan oleh user atau dilewati (jalur Media3 standar)
    DSP_ATTACHED,     // Processor terpasang tapi belum terdeteksi memproses frame (sedang inisialisasi)
    DSP_ERROR,        // Gagal memuat library atau error native
    UNKNOWN_FORMAT    // Jalur audio belum terdeteksi atau format tidak didukung
}

@Immutable
data class AudioSignalPathState(
    val source: SourceAudioFormat = SourceAudioFormat(),
    val sink: OutputSinkCapability = OutputSinkCapability(),
    val status: AudioPathStatus = AudioPathStatus.UNKNOWN_FORMAT,
    val actualSampleRate: Int? = null,
    val actualBitDepth: Int? = null,
    val isBitPerfect: Boolean = false,
    val btClarityActive: Boolean = false
)

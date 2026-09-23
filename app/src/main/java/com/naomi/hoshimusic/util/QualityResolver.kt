package com.naomi.hoshimusic.util

import androidx.compose.ui.graphics.Color
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.data.metadata.ScanStatus
import java.time.Instant
import java.time.ZoneId

/**
 * Unified Quality Resolver & Metadata Formatter (v1.4.6 cleanup).
 * 
 * Provides a single source of truth for audio quality badges and 
 * metadata display across the app.
 */
object QualityResolver {

    enum class AudioQuality(val label: String, val color: Color) {
        HI_RES("HI-RES", Color(0xFFFFD700)),
        LOSSLESS("LOSSLESS", Color(0xFFFF4081)),
        HQ("HQ", Color(0xFF2196F3)),
        NONE("", Color.Transparent)
    }

    fun resolve(song: Song): AudioQuality {
        // Fast-path: MediaStore provides sampleRate (API 31+), bitrate (API 30+), mimeType (always).
        // Use these for immediate classification without waiting for heavy deep scan.
        val sampleRate = song.sampleRate ?: 0
        val bitrate = song.bitrate ?: 0
        val mimeType = song.mimeType?.uppercase() ?: ""
        
        // Lossless detection via MIME or verified codec/container
        val losslessCodecs = listOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "DSF", "DFF", "DSD")
        val isLosslessMime = losslessCodecs.any { mimeType.contains(it) }
        val isVerified = song.isFormatVerified
        val container = song.container?.uppercase()
        val codec = song.codec?.uppercase()
        val isLossless = isLosslessMime || (isVerified && losslessCodecs.any { it == codec || it == container })

        // bitDepth is null from MediaStore; if lossless, we can infer 24-bit for badges
        val bitDepth = song.bitDepth ?: if (isLossless) 24 else 0

        return when {
            // Policy [C3]: Lossless verified AND high resolution (>= 24-bit AND >= 48kHz)
            isLossless && (bitDepth >= 24 && sampleRate >= 48000) -> AudioQuality.HI_RES
            isLossless -> AudioQuality.LOSSLESS
            // Policy [C3]: HQ for high bitrate lossy (>= 320 kbps)
            bitrate >= 320_000 -> AudioQuality.HQ
            else -> AudioQuality.NONE
        }
    }

    fun formatYearAdded(dateAddedSeconds: Long?): String {
        if (dateAddedSeconds == null || dateAddedSeconds <= 0L) return ""
        return try {
            Instant.ofEpochSecond(dateAddedSeconds)
                .atZone(ZoneId.systemDefault())
                .year
                .toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun getTechnicalSummary(song: Song): String {
        return buildString {
            val level = resolve(song)
            if (level != AudioQuality.NONE) append("${level.label} • ")
            
            song.sampleRate?.let { 
                val khz = it.toFloat() / 1000f
                append(if (khz % 1.0f == 0.0f) "%.0f".format(khz) else "%.1f".format(khz))
                append(" kHz • ")
            }

            song.bitDepth?.let {
                if (it > 0) append("$it bit • ")
            }
            
            song.bitrate?.let {
                val kbps = it / 1000
                append("$kbps kbps • ")
            }
            
            append(song.getCleanFormat())
        }
    }
}

package com.naomi.hoshimusic.util

import androidx.compose.ui.graphics.Color
import com.naomi.hoshimusic.data.Song

object QualityResolver {

    enum class AudioQuality(val label: String, val color: Color) {
        HI_RES("HI-RES", Color(0xFFFFD700)),
        LOSSLESS("LOSSLESS", Color(0xFFFF4081)),
        HQ("HQ", Color(0xFF2196F3)),
        NONE("", Color.Transparent)
    }

    fun resolve(song: Song): AudioQuality {
        val sampleRate = song.sampleRate ?: 0
        val bitrate = song.bitrate ?: 0
        val mimeType = song.mimeType.uppercase()
        
        val losslessCodecs = listOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "DSF", "DFF", "DSD")
        val isLosslessMime = losslessCodecs.any { mimeType.contains(it) }
        val isVerified = song.isFormatVerified
        val container = song.container?.uppercase()
        val codec = song.codec?.uppercase()
        val isLossless = isLosslessMime || (isVerified && losslessCodecs.any { it == codec || it == container })

        val bitDepth = song.bitDepth ?: if (isLossless) 24 else 0

        return when {
            isLossless && (bitDepth >= 24 && sampleRate >= 48000) -> AudioQuality.HI_RES
            isLossless -> AudioQuality.LOSSLESS
            bitrate >= 320_000 -> AudioQuality.HQ
            else -> AudioQuality.NONE
        }
    }

    fun formatYearAdded(dateAddedSeconds: Long?): String {
        if (dateAddedSeconds == null || dateAddedSeconds <= 0L) return ""
        return try {
            java.time.Instant.ofEpochSecond(dateAddedSeconds)
                .atZone(java.time.ZoneId.systemDefault())
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
                append(if (khz % 1.0f == 0.0f) "${khz.toInt()}" else "$khz")
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

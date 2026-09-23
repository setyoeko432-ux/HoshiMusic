package com.naomi.hoshimusic.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.time.Instant
import java.time.ZoneId

enum class AudioQuality {
    HI_RES,
    LOSSLESS,
    HQ,
    NONE
}

/**
 * [A2] Hallucination policy: UNKNOWN ≠ 44100 Hz ≠ 16-bit ≠ 0 kbps.
 *
 * Previously this object fabricated plausible defaults (44100/16/"2026")
 * whenever a value was unreadable, which surfaced fake specs to users and
 * inflated lossless files to fake HI-RES badges. Every unknown is now null
 * / empty and quality claims require EVIDENCE:
 *   - HI-RES needs an actually-read 24-bit+ depth OR >48 kHz rate.
 *   - HQ needs an actually-read ≥256 kbps bitrate.
 */
object AudioMetadataHelper {

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

    fun resolveQuality(
        mimeType: String?,
        formatExtension: String?,
        sampleRate: Int?,
        bitDepth: Int?,
        bitrateKbps: Int?
    ): AudioQuality {
        val ext = (formatExtension ?: mimeType?.substringAfter("audio/"))?.lowercase().orEmpty()

        val isLosslessMime = ext.contains("flac") || ext.contains("wav") ||
                ext.contains("alac") || ext.contains("aiff") || ext.contains("aif") ||
                ext.contains("dsd") || ext.contains("dff") || ext.contains("dsf") ||
                ext.contains("ape")

        // HI-RES: Evidence-based only (> 48kHz OR > 16-bit)
        // LOSSLESS: Only if verified lossless mime/extension
        // HQ: Bitrate >= 320 kbps (Lossy)
        
        val rate = sampleRate ?: 0
        val depth = bitDepth ?: 0

        return when {
            (rate > 48000 || depth > 16) -> AudioQuality.HI_RES
            isLosslessMime -> AudioQuality.LOSSLESS
            (bitrateKbps ?: 0) >= 320 -> AudioQuality.HQ
            else -> AudioQuality.NONE
        }
    }
}

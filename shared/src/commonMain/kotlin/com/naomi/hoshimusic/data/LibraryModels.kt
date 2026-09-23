package com.naomi.hoshimusic.data

import androidx.compose.runtime.Immutable
import com.naomi.hoshimusic.data.lrc.InstrumentalAnnotation
import com.naomi.hoshimusic.data.lrc.LyricAnnotationType

@Immutable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songIds: List<String>,
    val createdAtMs: Long,
)

@Immutable
data class SavedQueue(
    val id: String,
    val name: String,
    val songIds: List<String>,
    val updatedAtMs: Long,
)

@Immutable
data class PlayStat(
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
)

@Immutable
data class LyricToken(
    val timestampMs: Long,
    val text: String,
    val endMs: Long = 0L,
    val romanized: String? = null,
    val isMorphemeInterior: Boolean = false,
)

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val tokens: List<LyricToken> = emptyList(),
    val agent: String? = null,
    val vocalEndMs: Long = 0L,
    val translation: String? = null,
    val romanizedText: String? = null,
    @Deprecated("Use instrumentalAnnotation instead")
    val instrumentalGap: InstrumentalGap? = null,
    val annotationType: LyricAnnotationType = LyricAnnotationType.VOCAL,
    val instrumentalAnnotation: InstrumentalAnnotation? = null,
)

@Immutable
data class InstrumentalGap(
    val earliestStartMs: Long,
    val nextLyricTimestampMs: Long,
    val confidence: Float
)

enum class VocalTimingConfidence {
    HIGH,
    MEDIUM,
    LOW
}

@Immutable
data class VocalEndEstimate(
    val timestampMs: Long,
    val confidence: VocalTimingConfidence
)

@Immutable
data class LyricsResult(
    val lines: List<LyricLine> = emptyList(),
    val plainText: String = "",
    val sourceName: String? = null,
)

@Immutable
data class ReplayGainInfo(
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val peak: Float?,
) {
    fun effectiveGainDb(preferAlbum: Boolean = false): Float? {
        val requested = if (preferAlbum) albumGainDb ?: trackGainDb else trackGainDb ?: albumGainDb
        if (requested == null) return null
        val safeMaximum = peak
            ?.takeIf { it > 0f }
            ?.let { value -> (-20.0 * kotlin.math.log10(value.toDouble())).toFloat() }
        return if (safeMaximum == null) requested else minOf(requested, safeMaximum)
    }
}

sealed interface LibraryScanResult {
    data class Success(
        val songs: List<Song>,
        val skippedFiles: Int
    ) : LibraryScanResult

    data object PermissionRequired : LibraryScanResult

    data class Error(val message: String) : LibraryScanResult
}

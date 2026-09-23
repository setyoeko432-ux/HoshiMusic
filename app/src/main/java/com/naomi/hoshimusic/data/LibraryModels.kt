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
    /** Perkiraan akhir kata: start token berikutnya, atau akhir baris. 0 = belum dihitung. */
    val endMs: Long = 0L,
    /** [V12]: Romanisasi per token, agar karaoke pada teks romaji akurat. */
    val romanized: String? = null,
    /** [V13]: Penanda token berada di tengah morfem Kanji (untuk cegah blank lines di Romaji mode). */
    val isMorphemeInterior: Boolean = false,
)

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val tokens: List<LyricToken> = emptyList(),
    /** TIER-2: penanda vokalis Walaoke "M"(pria)/"F"(wanita)/"D"(duet) — untuk UI karaoke kiri/kanan. */
    val agent: String? = null,
    /** [V9]: Waktu vokal benar-benar selesai (berdasarkan syllable engine). */
    val vocalEndMs: Long = 0L,
    /** [V11]: Terjemahan lirik (bilingual). */
    val translation: String? = null,
    /** [V11]: Hasil romanisasi/transliterasi lirik. */
    val romanizedText: String? = null,
    /** [V32]: Data gap instrumental terstruktur (Legacy). */
    @Deprecated("Use instrumentalAnnotation instead")
    val instrumentalGap: InstrumentalGap? = null,
    /** [V33]: Tipe klasifikasi baris lirik. */
    val annotationType: LyricAnnotationType = LyricAnnotationType.VOCAL,
    /** [V33]: Metadata instrumental terstruktur. */
    val instrumentalAnnotation: InstrumentalAnnotation? = null,
)

/** [V32]: Representasi jeda instrumental antara baris ini dan baris berikutnya. */
@Immutable
data class InstrumentalGap(
    val earliestStartMs: Long,
    val nextLyricTimestampMs: Long,
    val confidence: Float
)

/** [V32]: Tingkat kepercayaan timing vokal. */
enum class VocalTimingConfidence {
    /** Sangat tinggi: Berasal dari token-synced LRC (karaoke). */
    HIGH,
    /** Menengah: Berasal dari baris-synced dengan estimasi syllable yang stabil. */
    MEDIUM,
    /** Rendah: Fallback durasi tetap atau teks terlalu pendek untuk dianalisis. */
    LOW
}

/** [V32]: Hasil estimasi akhir vokal. */
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

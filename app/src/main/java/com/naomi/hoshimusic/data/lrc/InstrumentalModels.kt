package com.naomi.hoshimusic.data.lrc

import androidx.compose.runtime.Immutable

/**
 * [V33]: Tipe anotasi lirik untuk klasifikasi struktural.
 */
enum class LyricAnnotationType {
    /** Baris berisi vokal/lirik yang dinyanyikan. */
    VOCAL,
    /** Label struktur lagu seperti [Chorus], [Verse], [Bridge]. */
    SECTION_LABEL,
    /** Penanda instrumental eksplisit dari file LRC (♪, Instrumental, Solo). */
    EXPLICIT_INSTRUMENTAL,
    /** Kandidat jeda instrumental hasil inferensi gap (untuk internal/future use). */
    INFERRED_INSTRUMENTAL,
    /** Pemisah visual seperti "---" atau "...". */
    SEPARATOR
}

/**
 * [V33]: Sumber data instrumental.
 */
enum class InstrumentalSource {
    /** Dari marker eksplisit di file LRC. */
    EXPLICIT_LRC,
    /** Hasil inferensi dari gap timestamp. */
    INFERRED_GAP
}

/**
 * [V33]: Representasi metadata instrumental terstruktur.
 */
@Immutable
data class InstrumentalAnnotation(
    val source: InstrumentalSource,
    val startTimestampMs: Long,
    val endTimestampMs: Long? = null,
    val confidence: Float = 1.0f
)

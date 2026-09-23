package com.naomi.hoshimusic.data.lrc

import com.naomi.hoshimusic.data.LyricLine

internal object LrcConstants {
    const val INSTRUMENTAL_MARKER = "♪"
    
    // Ambang batas hening MURNI (setelah vokal selesai bernyanyi)
    const val PURE_INSTRUMENTAL_THRESHOLD_MS = 3_000L

    // Margin aman minimum sebelum penanda instrumental ditampilkan setelah estimasi vokal selesai
    const val MIN_SAFETY_MARGIN_MS = 1_200L

    // Porsi dari jeda hening MURNI (setelah estimasi vokal selesai) yang wajib dilewati dulu
    const val SAFETY_MARGIN_GAP_RATIO = 0.40

    // Jaring pengaman KEDUA yang independen dari estimasi panjang kata
    const val MIN_LINE_GAP_PROPORTION = 0.5

    // Batas maksimum durasi vokal yang ditambah untuk kata yang teridentifikasi "rawan ditahan lama"
    const val SUSTAIN_PRONE_MAX_MS = 8_000L

    // --- [V32]: Constants for Instrumental Detection ---
    const val VOCAL_ACTIVE_THRESHOLD = 0.020f
    const val VOCAL_INACTIVE_THRESHOLD = 0.010f
    const val SILENCE_CONFIRMATION_MS = 800L
    const val INSTRUMENTAL_MIN_REMAINING_MS = 1_200L
    const val INSTRUMENTAL_EXIT_BEFORE_MS = 150L
}

/** [V32]: State presentasi lirik untuk sinkronisasi UI. */
sealed interface LyricPresentationState {
    data class Vocal(val lineIndex: Int) : LyricPresentationState
    data class SectionLabel(val lineIndex: Int, val text: String) : LyricPresentationState
    data class Instrumental(
        val previousLineIndex: Int,
        val startMs: Long,
        val nextLineTimestampMs: Long
    ) : LyricPresentationState
    data object None : LyricPresentationState
}

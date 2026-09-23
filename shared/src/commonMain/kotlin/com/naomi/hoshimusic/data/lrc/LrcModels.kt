package com.naomi.hoshimusic.data.lrc

object LrcConstants {
    const val INSTRUMENTAL_MARKER = "♪"
    const val PURE_INSTRUMENTAL_THRESHOLD_MS = 3_000L
    const val MIN_SAFETY_MARGIN_MS = 1_200L
    const val SAFETY_MARGIN_GAP_RATIO = 0.40
    const val MIN_LINE_GAP_PROPORTION = 0.5
    const val SUSTAIN_PRONE_MAX_MS = 8_000L
    const val VOCAL_ACTIVE_THRESHOLD = 0.020f
    const val VOCAL_INACTIVE_THRESHOLD = 0.010f
    const val SILENCE_CONFIRMATION_MS = 800L
    const val INSTRUMENTAL_MIN_REMAINING_MS = 1_200L
    const val INSTRUMENTAL_EXIT_BEFORE_MS = 150L
}

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

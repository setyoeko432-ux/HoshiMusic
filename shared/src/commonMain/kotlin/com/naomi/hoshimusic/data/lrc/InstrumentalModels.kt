package com.naomi.hoshimusic.data.lrc

import androidx.compose.runtime.Immutable

enum class LyricAnnotationType {
    VOCAL,
    SECTION_LABEL,
    EXPLICIT_INSTRUMENTAL,
    INFERRED_INSTRUMENTAL,
    SEPARATOR
}

enum class InstrumentalSource {
    EXPLICIT_LRC,
    INFERRED_GAP
}

@Immutable
data class InstrumentalAnnotation(
    val source: InstrumentalSource,
    val startTimestampMs: Long,
    val endTimestampMs: Long? = null,
    val confidence: Float = 1.0f
)

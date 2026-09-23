package com.naomi.hoshimusic.data.metadata

import com.naomi.hoshimusic.data.InstrumentalGap
import com.naomi.hoshimusic.data.LyricLine
import com.naomi.hoshimusic.data.VocalTimingConfidence
import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.util.RomanizationLanguage

/**
 * Instrumental Gap and Transition Engine (V9.5).
 * 
 * Bertugas menganalisis jeda antar baris lirik dan memberikan penanda unik
 * pada baris vokal terakhir sebelum jeda instrumental dimulai.
 * Menggunakan data dari LrcCalibrationEngine untuk timing yang natural.
 */
internal object LrcInstrumentalEngine {

    /** Jeda minimum untuk memicu mode instrumental (Safety Fallback) */
    private const val FIXED_THRESHOLD_MS = 3000L

    /** 
     * Menganalisis list lirik menggunakan Hybrid Engine:
     * 1. Mencoba menghitung Median Gap (Statistical Core)
     * 2. Jika lagu tidak stabil (terlalu sedikit gap), pakai Fixed Threshold (Safety Core)
     */
    fun analyzeAndMark(lines: List<LyricLine>, lang: RomanizationLanguage): List<LyricLine> {
        if (lines.isEmpty()) return lines
        
        // --- 1. PROFILING GAPS ---
        val gaps = mutableListOf<Long>()
        for (i in 0 until lines.size - 1) {
            val current = lines[i]
            val next = lines[i + 1]
            
            if (current.annotationType == LyricAnnotationType.SECTION_LABEL || 
                current.annotationType == LyricAnnotationType.SEPARATOR ||
                next.annotationType == LyricAnnotationType.SECTION_LABEL ||
                next.annotationType == LyricAnnotationType.SEPARATOR
            ) continue

            val vEnd = LrcVocalTiming.calculateVocalEndTime(current, lang, next.timestampMs).timestampMs
            val gap = next.timestampMs - vEnd
            if (gap in 200..5000) gaps.add(gap)
        }
        
        // --- 2. SWITCHING LOGIC ---
        val useStatistical = gaps.size >= 5
        val medianBreathMs = if (useStatistical) {
            gaps.sorted()[gaps.size / 2]
        } else 800L

        val triggerThreshold = if (useStatistical) {
            (medianBreathMs * 3).coerceIn(2500L, 6000L)
        } else FIXED_THRESHOLD_MS
        
        // [V33]: Inferred gaps are now strictly >= 8s as per safety requirement.
        val inferredGapThreshold = 8000L

        // --- 3. PROCESSING ---
        val output = lines.toMutableList()
        for (i in output.indices) {
            val current = output[i]
            val next = output.getOrNull(i + 1)
            val nextTs = next?.timestampMs
            
            if (current.annotationType == LyricAnnotationType.SECTION_LABEL || 
                current.annotationType == LyricAnnotationType.SEPARATOR) {
                output[i] = current.copy(vocalEndMs = current.timestampMs)
                continue
            }

            val estimate = LrcVocalTiming.calculateVocalEndTime(current, lang, nextTs)
            val voiceEnd = estimate.timestampMs
            
            // Baris terakhir: jangan buat gap instrumental.
            if (nextTs == null) {
                output[i] = current.copy(vocalEndMs = voiceEnd)
                continue
            }

            val silenceGap = nextTs - voiceEnd
            var updated = current.copy(vocalEndMs = voiceEnd + 1000L)

            if (current.annotationType == LyricAnnotationType.EXPLICIT_INSTRUMENTAL) {
                updated = updated.copy(
                    instrumentalAnnotation = InstrumentalAnnotation(
                        source = InstrumentalSource.EXPLICIT_LRC,
                        startTimestampMs = current.timestampMs,
                        endTimestampMs = nextTs,
                        confidence = 1.0f
                    )
                )
            } else if (silenceGap >= inferredGapThreshold && next.annotationType != LyricAnnotationType.SECTION_LABEL) {
                updated = updated.copy(
                    instrumentalAnnotation = InstrumentalAnnotation(
                        source = InstrumentalSource.INFERRED_GAP,
                        startTimestampMs = voiceEnd,
                        endTimestampMs = nextTs,
                        confidence = if (estimate.confidence == VocalTimingConfidence.HIGH) 0.8f else 0.5f
                    )
                )
            }
            output[i] = updated
        }
        
        return output
    }

}

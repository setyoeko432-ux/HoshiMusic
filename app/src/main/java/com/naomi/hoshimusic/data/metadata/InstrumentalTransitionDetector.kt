package com.naomi.hoshimusic.data.metadata

import com.naomi.hoshimusic.data.VocalTimingConfidence
import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.playback.dsp.VocalTelemetryState

/**
 * [V32]: State Machine untuk transisi jeda instrumental lirik.
 * Menggunakan Hysteresis, Silence Confirmation, dan Adaptive Guard.
 */
internal class InstrumentalTransitionDetector {

    private enum class InternalState {
        VOCAL,
        WAITING_FOR_SILENCE,
        INSTRUMENTAL
    }

    private var state = InternalState.VOCAL
    private var silenceStartMs = 0L
    private var smoothedEnergy = 0f
    
    // Hysteresis thresholds
    private val activeThreshold = LrcConstants.VOCAL_ACTIVE_THRESHOLD
    private val inactiveThreshold = LrcConstants.VOCAL_INACTIVE_THRESHOLD
    private val silenceRequiredMs = LrcConstants.SILENCE_CONFIRMATION_MS

    fun update(
        playbackPos: Long,
        currentLineIndex: Int,
        annotation: InstrumentalAnnotation?,
        confidence: VocalTimingConfidence,
        telemetry: VocalTelemetryState?,
        isDiscontinuity: Boolean
    ): LyricPresentationState {
        if (isDiscontinuity) {
            reset()
        }

        // [V33]: EXPLICIT_ONLY logic is now the hardcoded policy for reliability.
        // We only show ♪ if the annotation source is EXPLICIT_LRC.
        
        if (annotation == null) {
            state = InternalState.VOCAL
            return LyricPresentationState.Vocal(currentLineIndex)
        }

        if (annotation.source == InstrumentalSource.INFERRED_GAP) {
            // Inferred gaps are ignored in EXPLICIT_ONLY policy to avoid false positives.
            state = InternalState.VOCAL
            return LyricPresentationState.Vocal(currentLineIndex)
        }

        val earliestStart = annotation.startTimestampMs
        val nextLyricTs = annotation.endTimestampMs ?: (earliestStart + 5000L)
        val remainingToNext = nextLyricTs - playbackPos

        if (remainingToNext < LrcConstants.INSTRUMENTAL_EXIT_BEFORE_MS) {
            state = InternalState.VOCAL
            return LyricPresentationState.Vocal(currentLineIndex + 1)
        }

        // For EXPLICIT markers, we can optionally use telemetry to "delay" if vocal is still detected,
        // but we don't "create" them. However, since it's EXPLICIT, we trust the LRC.
        
        if (playbackPos >= earliestStart) {
            state = InternalState.INSTRUMENTAL
        }

        return if (state == InternalState.INSTRUMENTAL) {
            LyricPresentationState.Instrumental(currentLineIndex, earliestStart, nextLyricTs)
        } else {
            LyricPresentationState.Vocal(currentLineIndex)
        }
    }

    fun reset() {
        state = InternalState.VOCAL
        silenceStartMs = 0L
        smoothedEnergy = 0f
    }
}

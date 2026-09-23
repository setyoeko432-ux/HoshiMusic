package com.naomi.hoshimusic.data.lrc

import com.naomi.hoshimusic.data.LyricLine
import com.naomi.hoshimusic.data.VocalEndEstimate
import com.naomi.hoshimusic.data.VocalTimingConfidence
import com.naomi.hoshimusic.data.lrc.LrcConstants.SAFETY_MARGIN_GAP_RATIO
import com.naomi.hoshimusic.data.lrc.LrcConstants.SUSTAIN_PRONE_MAX_MS
import com.naomi.hoshimusic.data.metadata.LrcCalibrationEngine
import com.naomi.hoshimusic.util.RomanizationLanguage

internal object LrcVocalTiming {

    private val SUSTAIN_PRONE_REGEX = Regex(
        "^(oh+|ooh+|ah+|aah+|whoa+|na+|la+|yeah+|mmm+|hmm+|uh+|woo+|hoo+|you|me|no|so)[.,!?~-]*$",
        RegexOption.IGNORE_CASE
    )

    fun calculateVocalEndTime(
        line: LyricLine,
        lang: RomanizationLanguage,
        nextLyricTimestampMs: Long? = null
    ): VocalEndEstimate {
        val cleanText = line.text.substringBefore("|").trim()
        if (cleanText.isEmpty() || line.text == LrcConstants.INSTRUMENTAL_MARKER) {
            return VocalEndEstimate(line.timestampMs, VocalTimingConfidence.LOW)
        }

        val syllableCount = countSyllables(cleanText)
        val baseMsPerSyllable = LrcCalibrationEngine.getSyllableDuration(lang)
        val paceFactor = LrcCalibrationEngine.getPaceFactor(syllableCount, lang)

        val sustainMultiplier = when {
            cleanText.endsWith("~") || cleanText.endsWith("...") || cleanText.endsWith("—") -> 1.7f
            cleanText.endsWith("-") -> 1.3f
            // [V32]: Remove 0.7x multiplier for punctuation to avoid premature ends
            else -> 1.0f
        }

        return if (line.tokens.isNotEmpty()) {
            val lastToken = line.tokens.last()
            val cleanWord = lastToken.text.trim()
            val syllables = countSyllables(cleanWord).coerceAtLeast(1)
            val baseDuration = (syllables * baseMsPerSyllable).coerceIn(1_500L, 4_000L)
            
            val wordDuration = if (isSustainProne(cleanWord)) {
                (baseDuration * 1.8 * sustainMultiplier).toLong().coerceAtMost(SUSTAIN_PRONE_MAX_MS)
            } else {
                (baseDuration * sustainMultiplier).toLong()
            }
            
            // Priority 1: Token Synced timing is HIGH confidence
            val ts = lastToken.timestampMs + wordDuration
            VocalEndEstimate(
                timestampMs = applySafetyBounds(ts, line.timestampMs, nextLyricTimestampMs),
                confidence = VocalTimingConfidence.HIGH
            )
        } else {
            val baseDuration = (syllableCount * baseMsPerSyllable * paceFactor).toLong()
                .coerceIn(2_000L, 7_500L)

            val estimatedDuration = (baseDuration * sustainMultiplier).toLong()
            val ts = line.timestampMs + estimatedDuration
            
            // Priority 2: Line Synced estimation is MEDIUM confidence
            VocalEndEstimate(
                timestampMs = applySafetyBounds(ts, line.timestampMs, nextLyricTimestampMs),
                confidence = VocalTimingConfidence.MEDIUM
            )
        }
    }

    private fun applySafetyBounds(
        estimatedTs: Long,
        lineStartTs: Long,
        nextLyricTs: Long?
    ): Long {
        if (nextLyricTs == null) return estimatedTs
        // Jangan melampaui baris berikutnya minus sedikit margin
        val maxSafe = (nextLyricTs - 50L).coerceAtLeast(lineStartTs)
        return estimatedTs.coerceIn(lineStartTs, maxSafe)
    }

    fun countSyllables(text: String): Int {
        var count = 0
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (word in words) {
            // [V21]: Senior Refactor - Improved Mora counting for JP
            if (word.any { LrcNormalizer.isCjk(it) }) {
                // Japanese Mora estimation: CJK characters often represent multiple beats
                count += (word.length * 1.5).toInt().coerceAtLeast(1)
            } else {
                val vowels = Regex("(?i)[aeiouy]+").findAll(word).count()
                count += vowels.coerceAtLeast(1)
            }
        }
        return count.coerceAtLeast(1)
    }

    fun dynamicSafetyRatio(pureSilenceGap: Long): Double {
        val extraPerMinute = 0.15
        val extra = (pureSilenceGap / 60_000.0) * extraPerMinute
        return (SAFETY_MARGIN_GAP_RATIO + extra).coerceAtMost(0.55)
    }

    fun isSustainProne(word: String): Boolean {
        val clean = word.trim().trimEnd('.', ',', '!', '?', '~', '-', '(', ')')
        if (clean.isEmpty()) return false

        if (SUSTAIN_PRONE_REGEX.matches(clean)) return true

        if (clean.length >= 3) {
            val lastChar = clean.last().lowercaseChar()
            val repeatCount = clean.reversed().takeWhile { it.lowercaseChar() == lastChar }.count()
            if (repeatCount >= 3) return true
        }
        return false
    }
}

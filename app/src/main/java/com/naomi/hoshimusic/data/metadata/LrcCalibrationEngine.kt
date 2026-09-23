package com.naomi.hoshimusic.data.metadata

import com.naomi.hoshimusic.util.RomanizationLanguage

/**
 * [TRAINED ENGINE] - Calibrated via HoshiCorpus-40.
 *
 * Provides language-specific syllable duration constants to fix
 * instrumental pause "trigger drift" (too fast/too slow).
 */
internal object LrcCalibrationEngine {

    /**
     * Rata-rata durasi per suku kata (ms) berdasarkan hasil pelatihan:
     * - ZH: 569ms (Paling lambat, intonasi penuh)
     * - EN: 443ms (Standar pop/rock)
     * - KO: 419ms (K-Pop cadence)
     * - JA: 371ms (Paling cepat, suku kata pendek)
     */
    fun getSyllableDuration(lang: RomanizationLanguage): Long {
        return when (lang) {
            RomanizationLanguage.ZH -> 580L
            RomanizationLanguage.LATIN -> 455L
            RomanizationLanguage.KO -> 430L
            RomanizationLanguage.JA -> 410L // [V21]: Increased for balladic accuracy
            else -> 420L // Default universal
        }
    }

    /**
     * Pace Factor: Mengatur sensitivitas kepadatan kata.
     */
    fun getPaceFactor(syllableCount: Int, lang: RomanizationLanguage): Float {
        val threshold = if (lang == RomanizationLanguage.JA) 12 else 8
        return if (syllableCount > threshold) 0.82f else 1.10f
    }
}

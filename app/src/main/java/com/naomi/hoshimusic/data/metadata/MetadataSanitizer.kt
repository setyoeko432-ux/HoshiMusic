package com.naomi.hoshimusic.data.metadata

/**
 * [B9] Range-validates numeric metadata before it reaches the database.
 *
 * Prevents hallucinated/garbage values from tag corruption ever surfacing
 * in the UI: years like 30001, BPM 99999, negative sample rates, etc.
 * Values outside physical plausibility are treated as ABSENT (null),
 * never silently coerced — coercion would itself be a fabrication.
 */
internal object MetadataSanitizer {

    const val YEAR_MIN = 1877                 // first commercial audio recordings
    const val BPM_MIN = 20
    const val BPM_MAX = 400                   // extreme grindcore/country tops out
    const val SAMPLE_RATE_MIN = 5_512         // old telephony/codecs
    const val SAMPLE_RATE_MAX = 6_144_000     // DSD512
    const val BIT_DEPTH_MIN = 1               // DSD reports 1-bit
    const val BIT_DEPTH_MAX = 64
    const val CHANNELS_MIN = 1
    const val CHANNELS_MAX = 64               // ambisonics/extensible layouts
    const val BITRATE_BPS_MIN = 8_000
    const val BITRATE_BPS_MAX = 60_000_000    // DSD64 stereo ≈ 45 Mbps
    const val DURATION_MS_MIN = 100
    const val DURATION_MS_MAX = 86_400_000L   // 24h (dj mixes/audiobooks)

    fun sanitizeYear(raw: String?): Int? {
        val value = parseLeadingYear(raw) ?: return null
        return value.takeIf { it in YEAR_MIN..currentYearPlusSlack() }
    }

    fun sanitizeBpm(value: Int?): Int? =
        value?.takeIf { it in BPM_MIN..BPM_MAX }

    fun sanitizeSampleRate(value: Int?): Int? =
        value?.takeIf { it in SAMPLE_RATE_MIN..SAMPLE_RATE_MAX }

    fun sanitizeBitDepth(value: Int?): Int? =
        value?.takeIf { it in BIT_DEPTH_MIN..BIT_DEPTH_MAX }

    fun sanitizeChannels(value: Int?): Int? =
        value?.takeIf { it in CHANNELS_MIN..CHANNELS_MAX }

    fun sanitizeBitrateBps(value: Int?): Int? =
        value?.takeIf { it in BITRATE_BPS_MIN..BITRATE_BPS_MAX }

    fun sanitizeDurationMs(value: Long?): Long? =
        value?.takeIf { it in DURATION_MS_MIN..DURATION_MS_MAX }

    /**
     * Year parsing tolerant of full dates ("2021-03-05"), parenthesized
     * forms and other junk around a plausible 4-digit year.
     */
    fun parseLeadingYear(raw: String?): Int? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        s.toIntOrNull()?.let { return it }
        // First standalone 4-digit number that looks like a year.
        return Regex("(?:^|\\D)(\\d{4})(?:\\D|$)").find(s)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun currentYearPlusSlack(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 2
}

package com.naomi.hoshimusic.data.lrc

internal object LrcTimestampParser {

    val LINE_TIME_REGEX = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.,:](\\d{1,3}))?]")
    val TOKEN_TIME_REGEX = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.,:](\\d{1,3}))?>")
    val OFFSET_REGEX = Regex("\\[offset:\\s*([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)

    fun parseTimestamp(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L
        val fRaw = frac ?: ""
        val fMs = when (fRaw.length) {
            0 -> 0L
            1 -> fRaw.toLong() * 100
            2 -> fRaw.toLong() * 10
            else -> fRaw.take(3).toLongOrNull() ?: 0L
        }
        return m * 60_000 + s * 1_000 + fMs
    }
}

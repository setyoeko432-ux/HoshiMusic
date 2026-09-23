package com.naomi.hoshimusic.util

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        val h = hours.toString()
        val m = if (minutes < 10) "0$minutes" else "$minutes"
        val s = if (seconds < 10) "0$seconds" else "$seconds"
        "$h:$m:$s"
    } else {
        val m = minutes.toString()
        val s = if (seconds < 10) "0$seconds" else "$seconds"
        "$m:$s"
    }
}

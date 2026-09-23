package com.naomi.hoshimusic.data.metadata

/**
 * [A5][A6] Outcome classification for a deep-scan attempt. Drives whether
 * the worker stamps lastScannedMs (permanent/partial/success) or leaves
 * the song queued for WorkManager retry (transient).
 */
enum class ScanStatus {
    /** Belum diproses. */
    PENDING,

    /** Everything requested was read. */
    SUCCESS,

    /** Some sources unavailable (e.g. SAF-only stream, tags skipped) but no hard failure. */
    PARTIAL,

    /** File corrupt/unsupported/malformed — retrying will not help. */
    PERMANENT_FAILURE,

    /** IO hiccup, remote volume asleep, etc. — worth retrying later. */
    TRANSIENT_FAILURE
}

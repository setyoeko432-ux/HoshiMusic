package com.naomi.hoshimusic.data.metadata

import android.util.Log
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * [A5] Bridges java.util.logging (JAudiotagger's logging stack) into
 * Logcat. Without this, every skipped-frame / padding-fix diagnostic the
 * library emits vanishes — making user bug reports undiagnosable.
 *
 * Idempotent; install once lazily before first tag read.
 */
internal object JulLogBridge {

    private const val LOG_TAG = "JAudioTagger"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                LogManager.getLogManager().reset()
                val root = Logger.getLogger("")
                root.level = Level.INFO
                root.addHandler(object : Handler() {
                    override fun publish(record: LogRecord) {
                        if (!root.isLoggable(record.level)) return
                        val msg = try { formatter.formatMessage(record) } catch (_: Exception) { record.message }
                        val text = msg ?: return
                        when {
                            record.level.intValue() >= Level.WARNING.intValue() ->
                                Log.w(LOG_TAG, text, record.thrown)
                            record.level.intValue() >= Level.INFO.intValue() ->
                                Log.i(LOG_TAG, text, record.thrown)
                            else -> Log.d(LOG_TAG, text, record.thrown)
                        }
                    }

                    override fun flush() = Unit
                    override fun close() = Unit
                })
                installed = true
            } catch (_: Throwable) {
                // Bridging must never break metadata reading.
            }
        }
    }
}

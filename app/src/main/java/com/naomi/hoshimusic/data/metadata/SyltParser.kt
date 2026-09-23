package com.naomi.hoshimusic.data.metadata

import com.naomi.hoshimusic.data.LyricLine
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * [V17]: Binary Decoder for ID3v2 SYLT (Synchronized Lyrics) frames.
 *
 * SYLT is a sequence of [Null-Terminated String][4-byte Big-Endian Timestamp].
 * Support both Milliseconds (Type 2) and MPEG Frames (Type 1).
 */
internal object SyltParser {

    /**
     * Parses the binary SYLT payload.
     * @param bytes The raw SYLT lyrics data.
     * @param format 1 = MPEG frames, 2 = Milliseconds.
     * @param sampleRate Used only for Type 1 conversion.
     */
    fun parse(bytes: ByteArray, format: Int, sampleRate: Int? = null): List<LyricLine> {
        if (bytes.isEmpty()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        val buffer = ByteBuffer.wrap(bytes)

        // Conversion factor for MPEG frames (Type 1)
        val frameMsFactor = if (format == 1 && sampleRate != null && sampleRate > 0) {
            1000.0 / sampleRate
        } else 1.0

        while (buffer.hasRemaining()) {
            // 1. Read text until null terminator
            val textBytes = mutableListOf<Byte>()
            while (buffer.hasRemaining()) {
                val b = buffer.get()
                if (b == 0.toByte()) break
                textBytes.add(b)
            }

            if (textBytes.isEmpty() && !buffer.hasRemaining()) break

            val text = String(textBytes.toByteArray(), StandardCharsets.UTF_8).trim()

            // 2. Read 4-byte timestamp
            if (buffer.remaining() >= 4) {
                val rawTimestamp = buffer.getInt().toLong()
                val timestampMs = if (format == 1) {
                    (rawTimestamp * frameMsFactor).toLong()
                } else {
                    rawTimestamp
                }

                if (text.isNotEmpty()) {
                    lines.add(
                        LyricLine(
                            timestampMs = timestampMs.coerceAtLeast(0L),
                            text = text
                        )
                    )
                }
            }
        }

        return lines.sortedBy { it.timestampMs }
    }
}

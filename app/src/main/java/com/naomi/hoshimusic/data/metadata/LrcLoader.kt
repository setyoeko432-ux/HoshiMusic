package com.naomi.hoshimusic.data.metadata

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * [C11][C12] Smart .lrc loading + structured parsing.
 *
 * C11: Real-world .lrc files arrive as UTF-8 (with/without BOM), UTF-16,
 * or legacy ANSI codecs (GB18030/EUC-KR/Shift_JIS/Big5) from Chinese-market
 * players. Blind UTF-8 reading produces garbage. We cascade:
 * BOM sniff → strict UTF-8 validation → strict legacy-codec candidates.
 *
 * C12: The stored form is a NORMALIZED LRC (timestamps canonicalized,
 * lines time-sorted, enhanced word-tags stripped, offset applied) instead
 * of the raw file dump, so consumers get predictable text.
 */
internal object LrcLoader {

    /** Refuse to slurp absurd sidecar files (corrupt/misnamed). */
    private const val MAX_BYTES = 5L * 1024 * 1024

    private val TIMESTAMP = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val ENHANCED_WORD_TAG = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val OFFSET_TAG = Regex("\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)

    data class LrcLine(val timeMs: Long, val text: String)

    /** Reads + decodes + normalizes an .lrc file. Null when unreadable. */
    fun loadSmart(file: File): String? = try {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_BYTES) null
        else file.readBytes().let(::decodeSmart)?.let(::normalize)
    } catch (_: Exception) {
        null
    }

    /**
     * Decodes raw .lrc bytes with the cascade described above.
     * Returns null only when every strategy fails.
     */
    fun decodeSmart(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null

        // 1. BOM sniffing is authoritative when present.
        when {
            bytes.hasPrefix(0xEF, 0xBB, 0xBF) ->
                return strictDecode(bytes, 3, Charsets.UTF_8) ?: utf8Lenient(bytes, 3)
            bytes.hasPrefix(0xFF, 0xFE) ->
                return strictDecode(bytes, 2, Charsets.UTF_16LE)
                    ?: strictDecode(bytes, 2, Charsets.UTF_16BE)
            bytes.hasPrefix(0xFE, 0xFF) ->
                return strictDecode(bytes, 2, Charsets.UTF_16BE)
                    ?: strictDecode(bytes, 2, Charsets.UTF_16LE)
        }

        // 2. Strict UTF-8 — rejects legacy ANSI bytes outright.
        strictDecode(bytes, 0, Charsets.UTF_8)?.let { return it.replace("\uFEFF", "") }

        // 3. Legacy candidates, most common first; first clean decode wins.
        for (cs in legacyCandidates()) {
            strictDecode(bytes, 0, cs)?.let { return it }
        }

        // 4. Never-fail last resort so a weird sidecar never blocks lyrics.
        return utf8Lenient(bytes, 0)
    }

    /**
     * [C12] Parses raw LRC content into timed lines:
     * applies [offset:], strips enhanced word-tags, supports multiple
     * timestamps per line, sorts by time, drops empty/meta lines.
     */
    fun parse(content: String): List<LrcLine> {
        val offsetMs = OFFSET_TAG.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lines = mutableListOf<LrcLine>()
        for (rawLine in content.lineSequence()) {
            val stamps = TIMESTAMP.findAll(rawLine).toList()
            if (stamps.isEmpty()) continue

            val text = rawLine.substring(stamps.last().range.last + 1)
                .replace(ENHANCED_WORD_TAG, "")
                .trim()
            if (text.isEmpty()) continue

            for (m in stamps) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                val fracRaw = m.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val timeMs = minutes * 60_000 + seconds * 1_000 + fracMs - offsetMs
                lines += LrcLine(timeMs.coerceAtLeast(0L), text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Canonical persisted form: "[mm:ss.SSS] text" per line, sorted,
     * metadata-free. Keeps DB payload stable across rescans.
     */
    fun normalize(content: String): String {
        val parsed = parse(content)
        if (parsed.isEmpty()) return content.trim()
        return parsed.joinToString("\n") { line ->
            val min = line.timeMs / 60_000
            val sec = (line.timeMs % 60_000) / 1_000
            val ms = line.timeMs % 1_000
            String.format(Locale.US, "[%02d:%02d.%03d] %s", min, sec, ms, line.text)
        }
    }

    // ---- internals -------------------------------------------------------

    private fun legacyCandidates(): List<Charset> = listOf(
        "GB18030", "EUC-KR", "Shift_JIS", "Big5", "windows-1252"
    ).mapNotNull { name -> runCatching { charset(name) }.getOrNull() }

    private fun strictDecode(bytes: ByteArray, offset: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun utf8Lenient(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, bytes.size - offset, Charsets.UTF_8)

    private fun ByteArray.hasPrefix(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        prefix.forEachIndexed { i, p -> if ((this[i].toInt() and 0xFF) != p) return false }
        return true
    }
}

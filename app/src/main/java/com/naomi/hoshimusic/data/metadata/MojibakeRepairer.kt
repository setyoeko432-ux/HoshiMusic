package com.naomi.hoshimusic.data.metadata

import android.util.LruCache
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * [C10] Repairs mojibake produced by legacy 90s/2000s taggers.
 *
 * Failure mode being fixed: huge amounts of JP/KR/CN rips store Shift-JIS /
 * EUC-KR / Big5 / GBK bytes while the ID3 encoding byte claims ISO-8859-1.
 * Every compliant decoder then yields Latin-1 garbage like "ÇÑ±Û" (한글) or
 * "\"ú{" (日本語), which poisons downstream language detection and
 * romanization (HoshiRomanizer).
 *
 * Detection heuristic (from fix-music-tags / community practice):
 *  1. A significant share of characters sit in U+0080..U+00FF (Latin-1
 *     high range) — real CJK text has ~0% there.
 *  2. Re-decoding the original Latin-1 bytes through a candidate legacy
 *     codec succeeds strictly (no malformed input, no U+FFFD).
 *  3. The candidate result contains a dominant share of CJK/Hangul/Kana
 *     script characters — proving the re-decode is meaningful, not noise.
 *
 * Legitimate accented Latin names ("Beyoncé", "Motörhead") fall below the
 * threshold or fail strict decoding, so they are never touched.
 */
internal object MojibakeRepairer {

    private val repairCache = LruCache<String, String>(256)

    /** Below this length, statistics are meaningless. */
    private const val MIN_LENGTH = 3

    /** Required fraction of Latin-1 high-range chars to suspect mojibake. */
    private const val HIGH_RANGE_THRESHOLD = 0.15

    /** Required fraction of CJK-script chars in a successful repair. */
    private const val SCRIPT_RATIO_THRESHOLD = 0.40

    /** Candidate source codecs, most-common-first (Android ICU provides all). */
    private val CANDIDATE_CHARSETS: List<Charset> = listOf(
        "Shift_JIS",  // Japanese (CP932 superset via ICU)
        "EUC-KR",     // Korean (CP949)
        "Big5",       // Traditional Chinese
        "GB18030",    // Simplified Chinese (GBK superset)
        "windows-1252"
    ).mapNotNull { name -> runCatching { charset(name) }.getOrNull() }

    /**
     * Returns the repaired string, or [text] unchanged when it does not
     * exhibit the mojibake signature or no candidate decodes convincingly.
     */
    fun repair(text: String?): String? {
        if (text.isNullOrEmpty() || text.length < MIN_LENGTH) return text
        
        // Phase 5: Cache hit check
        val cached = repairCache.get(text)
        if (cached != null) return cached

        // Phase 5: ASCII short-circuit
        var hasHighRange = false
        for (i in 0 until text.length) {
            if (text[i].code >= 0x80) {
                hasHighRange = true
                break
            }
        }
        if (!hasHighRange) {
            repairCache.put(text, text)
            return text
        }

        var highCount = 0
        for (c in text) if (c.code in 0x80..0xFF) highCount++
        if (highCount.toDouble() / text.length < HIGH_RANGE_THRESHOLD) {
            repairCache.put(text, text)
            return text
        }

        // Recover the original bytes the mislabeled Latin-1 decoder consumed.
        val rawBytes = text.toByteArray(Charsets.ISO_8859_1)

        var best: String? = null
        var bestScore = 0.0
        for (cs in CANDIDATE_CHARSETS) {
            val decoded = strictDecode(rawBytes, cs) ?: continue
            if (decoded == text || decoded.any { it == '\uFFFD' }) continue

            val score = scriptRatio(decoded)
            if (score >= SCRIPT_RATIO_THRESHOLD && score > bestScore) {
                best = decoded
                bestScore = score
            }
        }
        val result = best ?: text
        repairCache.put(text, result)
        return result
    }

    /** Strict charset decode; rejects on ANY malformed or unmappable byte. */
    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Exception) {
        null
    }

    /**
     * Fraction of characters belonging to CJK ideographs, kana, hangul,
     * CJK punctuation or fullwidth forms — i.e. scripts reachable only via
     * the legacy codecs we target.
     */
    private fun scriptRatio(s: String): Double {
        if (s.isEmpty()) return 0.0
        var hits = 0
        for (c in s) {
            when (c.code) {
                in 0x3040..0x30FF,   // hiragana + katakana
                in 0x3400..0x4DBF,   // CJK ext A
                in 0x4E00..0x9FFF,   // CJK unified
                in 0xAC00..0xD7A3,   // hangul syllables
                in 0x3000..0x303F,   // CJK punctuation
                in 0xFF00..0xFFEF    // fullwidth forms
                -> hits++
            }
        }
        return hits.toDouble() / s.length
    }
}

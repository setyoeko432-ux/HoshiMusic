package com.naomi.hoshimusic.data.lrc

import com.naomi.hoshimusic.data.lrc.LrcConstants.INSTRUMENTAL_MARKER
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.TOKEN_TIME_REGEX

internal object LrcNormalizer {

    private val SEGMENT_ORPHAN_REGEX = Regex("<\\d{1,3}:\\d{1,2}(?:[.,:]\\d{1,3})?[^\\s>]*")
    val METADATA_REGEX = Regex("^\\[(ti|ar|al|by|re|ve|length|offset|plain):.*]", RegexOption.IGNORE_CASE)
    val GENDER_MARKERS_REGEX = Regex("^[MFD]:\\s*", RegexOption.IGNORE_CASE)

    private val EXPLICIT_INSTRUMENTAL_REGEX = Regex(
        "^[\\[({]?(?:instrumental|interlude|solo|(?:guitar|piano|drum|sax|synth|violin|bass|fiddle)\\s*solo|間奏|イントロ|アウトロ|간주|♪|♫|♬)[\\])}]?$",
        RegexOption.IGNORE_CASE
    )

    private val SECTION_LABEL_REGEX = Regex(
        "^[\\[({]?(?:verse(?:\\s*\\d+)?|chorus|pre-chorus|post-chorus|hook|bridge|intro|outro|refrain|drop|breakdown|music|melody|melodi|jeda|intermezzo)[\\])}]?$",
        RegexOption.IGNORE_CASE
    )

    private val SEPARATOR_REGEX = Regex("^(?:—+|…+|\\.{2,}|[*\\-]{3,})$")

    fun isInstrumental(text: String): Boolean {
        val clean = text.trim().replace("\uE000", "")
        return clean == INSTRUMENTAL_MARKER ||
                clean == "♪ • • •" ||
                clean == "•••" ||
                EXPLICIT_INSTRUMENTAL_REGEX.matches(clean)
    }

    fun isSectionLabel(text: String): Boolean {
        val clean = text.trim().replace("\uE000", "")
        return SECTION_LABEL_REGEX.matches(clean)
    }

    fun isSeparator(text: String): Boolean {
        val clean = text.trim().replace("\uE000", "")
        return SEPARATOR_REGEX.matches(clean)
    }

    fun stripOrphanTags(input: String): Pair<String, Int> {
        if ('<' !in input) return input to 0
        val sb = StringBuilder(input.length)
        var last = 0
        var count = 0
        TOKEN_TIME_REGEX.findAll(input).forEach { m ->
            val segment = input.substring(last, m.range.first)
            count += SEGMENT_ORPHAN_REGEX.findAll(segment).count()
            sb.append(SEGMENT_ORPHAN_REGEX.replace(segment, " "))
            sb.append(m.value)
            last = m.range.last + 1
        }
        val tail = input.substring(last)
        count += SEGMENT_ORPHAN_REGEX.findAll(tail).count()
        sb.append(SEGMENT_ORPHAN_REGEX.replace(tail, " "))
        return sb.toString() to count
    }

    fun isCjk(c: Char): Boolean {
        val code = c.code
        // [8e] CJK Range Parity: Samakan dengan HoshiRomanizer (termasuk Extension A-D)
        return (code in 0x3040..0x309F) || // Hiragana
                (code in 0x30A0..0x30FF) || // Katakana
                (code in 0x3000..0x303F) || // Tanda baca CJK (、。「」『』)
                (code in 0x4E00..0x9FFF) || // Kanji
                (code in 0x3400..0x4DBF) || // Kanji Ext A
                (code in 0xAC00..0xD7AF) || // Hangul Syllables
                (code in 0x1100..0x11FF) || // Hangul Jamo
                (code in 0xFF00..0xFFEF)    // Fullwidth CJK & Latin
    }
}

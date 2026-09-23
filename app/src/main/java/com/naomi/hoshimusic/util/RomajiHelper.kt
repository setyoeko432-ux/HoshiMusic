package com.naomi.hoshimusic.util

import android.icu.text.Transliterator
import android.os.Build

object RomajiHelper {

    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
        } else null
    }

    fun toRomaji(input: String): String {
        if (input.isBlank()) return ""

        val result = StringBuilder()
        val japaneseBuffer = StringBuilder()

        // [7c] Iterasi per code-point untuk keamanan surrogate pairs (Audit RH2)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            
            if (isJapanese(cp)) {
                japaneseBuffer.append(input.substring(i, i + charCount))
            } else {
                if (japaneseBuffer.isNotEmpty()) {
                    val romaji = transliterateJapanese(japaneseBuffer.toString())
                    result.append(romaji)
                    japaneseBuffer.clear()
                    // Cek spasi pengaman
                    if (Character.isLetterOrDigit(cp) && !result.endsWith(" ")) {
                        result.append(" ")
                    }
                }
                result.append(input.substring(i, i + charCount))
            }
            i += charCount
        }

        if (japaneseBuffer.isNotEmpty()) {
            result.append(transliterateJapanese(japaneseBuffer.toString()))
        }

        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun transliterateJapanese(text: String): String {
        // Use HoshiRomanizer to get accurate readings for Kanji (requires kuromoji)
        val res = HoshiRomanizer.romanize(text, RomanizationLanguage.JA)
        return res.romanized ?: transliterateFallback(text)
    }

    private fun transliterateFallback(text: String): String {
        val t = transliterator ?: return text
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                t.transliterate(text).trim()
            } catch (_: Exception) {
                text
            }
        } else text
    }

    private fun isJapanese(code: Int): Boolean {
        // [7c] Sinkronisasi dengan blok CJK HoshiRomanizer (Audit RH4)
        return (code in 0x3040..0x309F) || // Hiragana
               (code in 0x30A0..0x30FF) || // Katakana
               (code in 0x4E00..0x9FFF) || // Kanji
               (code in 0x3400..0x4DBF) || // Kanji Ext A
               (code in 0x20000..0x2A6DF) || // Kanji Ext B
               (code in 0x2A700..0x2B73F) || // Kanji Ext C
               (code in 0x2B740..0x2B81F) || // Kanji Ext D
               code == 0x3005 || // 々 Kanji iteration mark
               code == 0x303B || // 〻 Vertical kanji iteration mark
               code == 0x3007    // 〇 Kanji-style zero
    }
}

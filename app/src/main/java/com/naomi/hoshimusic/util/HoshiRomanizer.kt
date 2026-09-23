package com.naomi.hoshimusic.util

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Hoshi Romanizer Engine (V5.5 - Heuristic Language Disambiguation & Fallback)
 */
object HoshiRomanizer {

    private const val TAG = "HoshiRomanizer"

    // [Fase 6] Versi engine untuk invalidasi cache
    const val VERSION = 2

    private val WHITESPACE = Regex("\\s+")

    // Tag LRC dan metadata
    private val LRC_TIMESTAMP = Regex("[\\[<]\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?[\\]>]")
    private val LRC_METADATA = Regex("^\\[(ti|ar|al|by|re|ve|length|offset):.*]", RegexOption.IGNORE_CASE)

    // Deteksi karakter penanda khusus Mandarin (Simplified & Traditional)
    // PERBAIKAN: Karakter duplikat '为' dihapus (Audit R2)
    private val CHINESE_MARKERS = Regex("[们这说国现没时间发长关东门车见這們說國學發關點讓靜邊聽麼裡为与业丝两严个]")

    private val ENGLISH_MARKERS = Regex("\\b(the|and|you|that|was|for|are|with|his|they|this|from|have|been|which|she|had|were|can|will|but|what|all|when|there|her|make|out|about|down|day|one|did|get|come|now|like)\\b", RegexOption.IGNORE_CASE)

    private val genericTransliterator by lazy { safeGetTransliterator("Any-Latin") }

    // Pinyin bertanda nada untuk Mandarin — jauh lebih akurat daripada Any-Latin generik
    private val hanLatinTransliterator by lazy { safeGetTransliterator("Han-Latin") }

    // [1c] Lazy Hangul-Latin transliterator
    private val hangulLatinTransliterator by lazy { safeGetTransliterator("Hangul-Latin") }

    // Kamus kuromoji tokenize itu mahal.
    private val kuromojiTokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { Tokenizer() }
            .onFailure { Log.w(TAG, "Kamus kuromoji gagal dimuat — romanisasi JA dinonaktifkan", it) }
            .getOrNull()
    }

    // [4a] Lock terpusat untuk tokenizer
    private val tokenizerLock = Any()

    /**
     * [V17]: Warm up the heavy Japanese dictionary (Kuromoji) on a background thread.
     * Prevents the initial lag when playing the first Japanese song.
     */
    fun prepare() {
        // Trigger the lazy initializer for the tokenizer
        val init = kuromojiTokenizer
    }

    private fun safeGetTransliterator(id: String): Transliterator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance(id) }.getOrNull()
        } else null
    }

    /**
     * TIER-1: Normalisasi full-width (ＡＢＣ１２３ → ABC123, \u3000 → spasi).
     * Murni JVM — aman untuk unit test & memperbaiki deteksi lirik lama gaya JP.
     */
    fun normalizeFullWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch.code) {
                in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // TIER-1: Kamus nama diri 多音字/artist
    private val PROPER_NOUN_OVERRIDES: Map<String, String> = mapOf(
        "周杰伦" to "Jay Chou",
        "邓紫棋" to "G.E.M.",
        "薛之谦" to "Joker Xue",
        "王力宏" to "Leehom Wang",
        "林俊杰" to "JJ Lin",
        "陈奕迅" to "Eason Chan",
        "张学友" to "Jacky Cheung",
    )

    // TIER-1: Cache LRU hasil romanisasi (kuromoji tokenize itu mahal).
    // Sinkronisasi untuk keamanan concurrent access.
    private val rawCache = object : LinkedHashMap<String, RomanizationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RomanizationResult>): Boolean = size > 256
    }
    private val resultCache = java.util.Collections.synchronizedMap(rawCache)

    /**
     * TIER-1: Resolver Alias Artis / Nama Internasional.
     * Dipisahkan dari romanisasi transliterasi murni sesuai kontrak.
     */
    fun resolveArtistAlias(name: String): String? {
        val clean = normalizeFullWidth(name).trim()
        // [7e] Case-insensitive lookup (Audit MM3)
        return PROPER_NOUN_OVERRIDES[clean] ?: PROPER_NOUN_OVERRIDES.entries.find { 
            it.key.equals(clean, ignoreCase = true) 
        }?.value
    }

    // [2b] Voicing map untuk iteration mark bersuara (ゞ)
    private val VOICING_MAP = mapOf(
        'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
        'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
        'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
        'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
        'カ' to 'ガ', 'キ' to 'ギ', 'ク' to 'グ', 'ケ' to 'ゲ', 'コ' to 'ゴ',
        'サ' to 'ザ', 'シ' to 'ジ', 'ス' to 'ズ', 'セ' to 'ゼ', 'ソ' to 'ゾ',
        'タ' to 'ダ', 'チ' to 'ヂ', 'ツ' to 'ヅ', 'テ' to 'デ', 'ト' to 'ド',
        'ハ' to 'バ', 'ヒ' to 'ビ', 'フ' to 'ブ', 'ヘ' to 'ベ', 'ホ' to 'ボ'
    )

    /**
     * [2b] Handle iteration marks (々, ゝ, ゞ) post-tokenize.
     */
    private fun handleIterationMarks(surface: String, lastReading: String?): String? {
        if (surface.isEmpty() || lastReading == null || lastReading == "*") return null
        return when (surface[0]) {
            '々' -> lastReading // Duplikasi unvoiced sebagai fallback aman
            'ゝ' -> lastReading.lastOrNull()?.toString()
            'ゞ' -> {
                val lastChar = lastReading.lastOrNull() ?: return null
                VOICING_MAP[lastChar]?.toString() ?: lastChar.toString()
            }
            else -> null
        }
    }

    /**
     * Deteksi bahasa berbasis karakter satuan / kalimat pendek.
     */
    fun detectLanguage(text: String, lyrics: String? = null): RomanizationLanguage {
        val normalizedInput = normalizeFullWidth(text)
        val lyricsText = lyrics?.let { normalizeFullWidth(it.take(1000)) } ?: "" // Sampel awal lirik
        val combinedText = "$normalizedInput $lyricsText"
        if (combinedText.isBlank()) return RomanizationLanguage.UNKNOWN

        // 1. Heuristik Instrumental: Prioritas tinggi
        val textLower = combinedText.lowercase()
        val isInstrumentalKeyword = textLower.contains("instrumental") || 
            textLower.contains("karaoke") || 
            textLower.contains("off vocal") || 
            textLower.contains("backing track") ||
            textLower.contains("no vocal") || 
            textLower.contains("minus one") ||
            textLower.contains("piano version") ||
            textLower.contains("instrumental version")

        if (isInstrumentalKeyword) return RomanizationLanguage.INSTRUMENTAL

        var hasKana = false
        var hasHangul = false
        var hasHan = false
        var hasLatin = false
        
        var kanaCount = 0
        var hangulCount = 0
        var hanCount = 0
        var latinCount = 0

        // Iterasi per CODE POINT (bukan per Char): Hanzi astral (Extension B+, mis. 𠮷)
        // tersusun dari surrogate pair dan akan terlewat bila diiterasi sebagai Char.
        var index = 0
        while (index < combinedText.length) {
            val codePoint = combinedText.codePointAt(index)
            index += Character.charCount(codePoint)
            val block = Character.UnicodeBlock.of(codePoint)

            // Halfwidth Katakana (ｱｲｳ...) tinggal di blok HALFWIDTH_AND_FULLWIDTH_FORMS
            if (block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS &&
                codePoint in 0xFF66..0xFF9F
            ) {
                hasKana = true
                kanaCount++
                continue
            }

            when (block) {
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> {
                    hasKana = true
                    kanaCount++
                }
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> {
                    hasHangul = true
                    hangulCount++
                }
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> {
                    hasHan = true
                    hanCount++
                }
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> {
                    if (Character.isLetter(codePoint)) {
                        hasLatin = true
                        latinCount++
                    }
                }
                Character.UnicodeBlock.CYRILLIC,
                Character.UnicodeBlock.GREEK,
                Character.UnicodeBlock.THAI,
                Character.UnicodeBlock.ARABIC,
                Character.UnicodeBlock.DEVANAGARI -> {
                    // [7a] Tandai sebagai script internasional lain untuk UNKNOWN (non-ambiguous Han)
                    if (Character.isLetter(codePoint)) {
                        latinCount++ // Ikut dihitung sebagai non-Han
                    }
                }
                else -> Unit
            }
        }

        // 2. Klasifikasi Berdasarkan Bobot (Lyrics-First Heuristic)
        return when {
            // Kana adalah ciri mutlak bahasa Jepang
            hasKana -> RomanizationLanguage.JA
            
            // Hangul adalah ciri mutlak bahasa Korea
            hasHangul -> RomanizationLanguage.KO
            
            // Jika ada Hanzi dan cocok dengan penanda Mandarin
            hasHan && CHINESE_MARKERS.containsMatchIn(combinedText) -> RomanizationLanguage.ZH
            
            // Hanzi/Kanji murni tanpa Kana/Hangul: Cek proporsi lirik jika ada
            hasHan -> {
                if (lyrics != null && kanaCount == 0 && hangulCount == 0) {
                    // Dalam lirik panjang, jika ada Hanzi tapi 0 Kana/Hangul, dipastikan Mandarin
                    RomanizationLanguage.ZH
                } else {
                    RomanizationLanguage.HAN_AMBIGUOUS
                }
            }
            
            // Jika hanya Latin, cek apakah ini kemungkinan instrumental (durasi panjang lirik kosong)
            // dihandle oleh caller atau detect instrumental di atas.
            hasLatin -> {
                if (ENGLISH_MARKERS.containsMatchIn(combinedText)) RomanizationLanguage.EN
                else RomanizationLanguage.LATIN
            }
            
            else -> RomanizationLanguage.UNKNOWN
        }
    }

    /**
     * Deteksi bahasa dari sampel seluruh lirik lagu (hingga 50 baris pertama).
     */
    fun detectLyricsLanguage(lrc: String): RomanizationLanguage {
        if (lrc.isBlank()) return RomanizationLanguage.UNKNOWN

        val cleanSample = lrc.lineSequence()
            .filter { it.isNotBlank() && !LRC_METADATA.matches(it) }
            .map { it.replace(LRC_TIMESTAMP, "").trim() }
            .filter { it.isNotBlank() }
            .take(50)
            .joinToString(" ")

        if (cleanSample.isBlank()) return RomanizationLanguage.UNKNOWN

        // Gunakan deteksi karakter yang sudah diperluas
        return detectLanguage("", cleanSample)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    // [4b] Hapus synchronized global agar cache-hit tidak memblokir thread lain (Audit R10)
    fun romanize(text: String, songLanguageHint: RomanizationLanguage? = null): RomanizationResult {
        if (text.isBlank()) return RomanizationResult(text, null, RomanizationLanguage.UNKNOWN)

        val workingText = normalizeFullWidth(text)
        var effective = detectLanguage(workingText)

        // Selesaikan ambiguitas Hanzi murni berdasarkan hint konteks lagu
        if (effective == RomanizationLanguage.HAN_AMBIGUOUS) {
            effective = resolveAmbiguousHan(songLanguageHint)
        }

        val cacheKey = "$effective|$workingText"
        resultCache[cacheKey]?.let { return it }

        if (effective == RomanizationLanguage.LATIN ||
            effective == RomanizationLanguage.UNKNOWN ||
            effective == RomanizationLanguage.MIXED
        ) {
            return RomanizationResult(text, null, effective).also { resultCache[cacheKey] = it }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return RomanizationResult(text, null, effective).also { resultCache[cacheKey] = it }
        }

        val romanized = when (effective) {
            RomanizationLanguage.JA -> romanizeJapanese(workingText)
            RomanizationLanguage.KO -> hangulLatinTransliterator?.transliterate(workingText) ?: genericTransliterator?.transliterate(workingText)
            // Han-Latin = pinyin bertanda nada; fallback ke Any-Latin bila tak tersedia
            RomanizationLanguage.ZH -> hanLatinTransliterator?.transliterate(workingText)
                ?: genericTransliterator?.transliterate(workingText)
            else -> null
        }

        val cleaned = romanized?.replace("*", " ")?.replace(WHITESPACE, " ")?.trim()
        val final = if (!cleaned.isNullOrEmpty() && cleaned != text) cleaned else null
        return RomanizationResult(text, final, effective).also { resultCache[cacheKey] = it }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun romanizeJapanese(text: String): String? = runCatching {
        val tokenizer = kuromojiTokenizer ?: return@runCatching null

        val katakana = buildString {
            var lastReading: String? = null
            // [4a] Lock hanya pada bagian tokenize yang kritis
            val tokens = synchronized(tokenizerLock) { tokenizer.tokenize(text) }
            tokens.forEach { token ->
                var reading = token.pronunciation ?: token.reading ?: token.surface
                
                // [2a/2b] Handle iteration marks post-tokenize
                if (reading == "*" || reading == token.surface) {
                    val expanded = handleIterationMarks(token.surface, lastReading)
                    if (expanded != null) reading = expanded
                }

                if (reading != "*") {
                    append(reading).append(' ')
                    lastReading = reading
                } else {
                    // Include the surface text if no reading is found (e.g. Latin parts)
                    append(token.surface).append(' ')
                }
            }
        }.trim()

        if (katakana.isBlank()) return@runCatching null

        // FIX 2: Reconnect sokuon (small tsu) to subsequent consonants
        val fixedKatakana = katakana
            .replace("ッ ", "ッ")
            .replace("っ ", "っ")

        // ICU Any-Latin will transliterate Katakana to Alphabet Romaji
        genericTransliterator?.transliterate(fixedKatakana)
    }.getOrNull()

    /**
     * Hanzi ambigu diselesaikan berdasarkan bahasa lagu yang terdeteksi dari LRC / metadata.
     * Jika tidak ada hint (null/unknown), default teraman untuk Hanzi tanpa Kana adalah Mandarin (ZH).
     */
    private fun resolveAmbiguousHan(hint: RomanizationLanguage?): RomanizationLanguage {
        return when (hint) {
            RomanizationLanguage.JA -> RomanizationLanguage.JA
            RomanizationLanguage.KO -> RomanizationLanguage.KO
            RomanizationLanguage.ZH -> RomanizationLanguage.ZH
            else -> RomanizationLanguage.ZH // Default mutlak: Hanzi tanpa Kana adalah Mandarin
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // =========================================================================
    // TIER-2: FURIGANA PER-TOKEN — murni JVM+perangkat (tanpa ICU),
    // membuka UI ruby annotation (kanji + romaji kecil di atasnya).
    // =========================================================================

    /** Satu token hasil kuromoji: bentuk asli, bacaan katakana, dan romajinya. */
    data class ReadingToken(
        val surface: String,
        val readingKatakana: String?,
        val romaji: String?,
    )

    fun furiganaTokens(text: String): List<ReadingToken> {
        if (text.isBlank()) return emptyList()
        val tokenizer = kuromojiTokenizer
            ?: return listOf(ReadingToken(normalizeFullWidth(text), null, null))
        return runCatching {
            val normalized = normalizeFullWidth(text)
            var lastReading: String? = null
            // [4a] Lock untuk keamanan concurrent access (Audit A2)
            val tokens = synchronized(tokenizerLock) { tokenizer.tokenize(normalized) }
            tokens.map { token ->
                var reading = token.reading?.takeIf { it.isNotBlank() && it != "*" }
                
                // [2a/2b] Handle iteration marks post-tokenize
                if (reading == null) {
                    reading = handleIterationMarks(token.surface, lastReading)
                }

                if (reading == null && isAllKana(token.surface)) {
                    reading = katakanaOf(token.surface)
                }
                
                if (reading != null) lastReading = reading
                
                ReadingToken(token.surface, reading, reading?.let { katakanaToRomaji(it) })
            }
        }.getOrDefault(emptyList())
    }

    private fun isAllKana(text: String): Boolean = text.isNotEmpty() && text.all {
        it.code in 0x3041..0x309F || // Hiragana
        it.code in 0x30A1..0x30FA || // Katakana
        it == 'ー' || it == '々' || it == 'ゝ' || it == 'ゞ'
    }

    /** Hiragana → katakana (geser +0x60); karakter lain diteruskan. */
    private fun katakanaOf(source: String): String = source.map { c ->
        if (c.code in 0x3041..0x3096) ((c.code + 0x60).toChar()) else c
    }.joinToString("")

    private val KANA_TO_ROMAJI = mapOf(
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "o",
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        "ヴ" to "vu",
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
        "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
        "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
        "シェ" to "she", "ジェ" to "je", "チェ" to "che",
    )

    private fun kanaRomajiAt(text: String, index: Int): Pair<String, Int>? {
        if (index >= text.length) return null
        if (index + 1 < text.length) {
            KANA_TO_ROMAJI[katakanaOf(text.substring(index, index + 2))]?.let { return it to 2 }
        }
        KANA_TO_ROMAJI[katakanaOf(text[index].toString())]?.let { return it to 1 }
        return null
    }

    /** Hepburn sederhana: yōon digraf, sokuon (ganda konsonan), vokal panjang 'ー', ん=n. */
    fun katakanaToRomaji(katakana: String): String {
        if (katakana.isBlank()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < katakana.length) {
            val c = katakana[i]
            when {
                c == 'ー' -> sb.lastOrNull { it in "aiueo" }?.let { sb.append(it) }
                c == 'ッ' || c == 'っ' -> {
                    val next = kanaRomajiAt(katakana, i + 1)?.first.orEmpty()
                    when (val cons = next.firstOrNull { it !in "aiueo" }) {
                        'c' -> sb.append('t')
                        null -> Unit
                        else -> sb.append(cons)
                    }
                }
                c == 'ン' || c == 'ん' -> {
                    // FIX AUDIT: ん sebelum vokal/y butuh apostrof (ンア → "n'a",
                    // bukan "na" yang salah baca); sebelum konsonan cukup "n".
                    val nxt = kanaRomajiAt(katakana, i + 1)?.first.orEmpty()
                    val needsApostrophe = nxt.isNotEmpty() && (nxt[0] in "aiueo" || nxt[0] == 'y')
                    sb.append(if (needsApostrophe) "n'" else "n")
                }
                else -> {
                    val hit = kanaRomajiAt(katakana, i)
                    if (hit != null) {
                        sb.append(hit.first); i += hit.second; continue
                    }
                    sb.append(c) // non-kana (latin/angka) diteruskan apa adanya
                }
            }
            i++
        }
        return sb.toString()
    }
}
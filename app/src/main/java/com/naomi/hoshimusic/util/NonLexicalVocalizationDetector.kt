package com.naomi.hoshimusic.util

/**
 * Mendeteksi baris lirik yang isinya cuma vokalisasi non-leksikal / ad-lib —
 * contoh: "aaah", "oooh", "la la la", "na na na", "yeah", "woo", "mmm", "sha la la".
 *
 * Baris seperti ini bukan kata sungguhan di bahasa manapun. Kalau dikirim ke
 * ML Kit Translate (yang murni model neural machine translation, tidak bisa
 * diberi instruksi/prompt), hasilnya sering ngasal — dia tetap "memaksa"
 * cari padanan kata untuk bunyi mulut, dan itu buang-buang panggilan
 * terjemahan untuk sesuatu yang memang tidak seharusnya diterjemahkan.
 *
 * Dipakai oleh [com.naomi.hoshimusic.data.LyricsRepository] untuk skip baris
 * seperti ini dari proses translate (baris tetap ditampilkan apa adanya,
 * tanpa baris terjemahan).
 *
 * Cakupan saat ini: ad-lib berskrip Latin (paling umum dipakai lintas bahasa
 * lagu JP/KO/ZH/EN sekalipun, karena ad-lib biasanya sudah ditulis
 * ter-romanisasi di file .lrc). Ad-lib berskrip asli non-Latin (mis. かー,
 * 음, 啊) belum tercakup — lihat catatan di bawah `isNonLexical`.
 */
object NonLexicalVocalizationDetector {

    /**
     * Suku kata/kata ad-lib yang umum dipakai sebagai vokalisasi, dicocokkan
     * secara utuh (exact match) per token.
     */
    private val KNOWN_SYLLABLES = setOf(
        "la", "na", "da", "ba", "ra", "sha", "cha", "doo", "dum", "bum", "ba",
        "yeah", "yea", "yah", "woo", "whoa", "woah", "hey", "oy", "uh", "huh",
        "mm", "hm", "hmm", "ooh", "oooh", "aah", "aaah", "ah", "oh", "eh",
        "wow", "wo", "tra", "la-la", "ha", "haha", "lalala", "nanana"
    )

    /**
     * Unit suku kata pendek yang boleh dianggap ad-lib kalau diulang tanpa
     * spasi, mis. "lalala", "nanana", "shalala". Dibatasi sengaja ke suku
     * kata yang memang lazim dipakai buat scat/ad-lib, supaya kata beneran
     * yang kebetulan berpola berulang (mis. "mama", "papa", "coco") tidak
     * ikut ke-skip.
     */
    private val AD_LIB_UNITS = setOf("la", "na", "da", "ba", "ra", "ya", "wa", "sha", "cha", "doo", "fa")

    /** Elongasi vokal/nasal, mis. "aaaah", "ooooh", "mmmmm", "hmmmm". */
    private val ELONGATED_VOWEL = Regex("^(a+h?|o+h?|e+h?|u+h?|m+|n+|h+m*|w+o+h?)$")

    private val CLEAN_REGEX = Regex("[~\\-_.,!?()\\[\\]\"'’]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * true kalau SELURUH baris terdiri dari token ad-lib (bukan sebagian) —
     * sengaja konservatif, supaya baris campuran (ada kata beneran + ad-lib)
     * tetap diterjemahkan seperti biasa.
     *
     * Catatan keterbatasan: hanya menangani ad-lib berskrip Latin. Ad-lib
     * dalam kana/hangul/hanzi asli (bukan romanisasi) akan lolos dan tetap
     * dikirim ke ML Kit — bisa ditambah nanti kalau perlu, mis. dengan daftar
     * karakter seperti "あ/ー/ん" atau "아/음/오".
     */
    fun isNonLexical(rawText: String): Boolean {
        val cleaned = rawText.lowercase().replace(CLEAN_REGEX, " ").trim()
        if (cleaned.isEmpty()) return false

        val tokens = cleaned.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        return tokens.all { isNonLexicalToken(it) }
    }

    private fun isNonLexicalToken(token: String): Boolean {
        if (token in KNOWN_SYLLABLES) return true
        if (ELONGATED_VOWEL.matches(token)) return true
        if (isRepeatedAdLibUnit(token)) return true
        return false
    }

    /** Mendeteksi token seperti "lalala"/"nanana" — unit ad-lib yang diulang tanpa spasi. */
    private fun isRepeatedAdLibUnit(token: String): Boolean {
        if (token.length < 4 || token.length > 15) return false
        for (unitLen in 2..3) {
            if (token.length % unitLen != 0) continue
            val unit = token.substring(0, unitLen)
            if (unit !in AD_LIB_UNITS) continue
            if (token.chunked(unitLen).all { it == unit }) return true
        }
        return false
    }
}

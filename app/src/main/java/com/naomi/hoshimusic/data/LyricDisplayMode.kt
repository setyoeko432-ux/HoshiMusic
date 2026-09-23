package com.naomi.hoshimusic.data

/**
 * [V14]: Menentukan mode tampilan lirik utama.
 * Memisahkan state Romanisasi dan Terjemahan yang sebelumnya overload.
 */
enum class LyricDisplayMode {
    /** Lirik asli tanpa tambahan (JP/KR/EN). */
    ORIGINAL,
    /** Tampilkan romaji/latin di baris utama (atau di atas baris utama). */
    ROMANIZED,
    /** Tampilkan terjemahan bahasa Indonesia di baris utama. */
    TRANSLATED
}

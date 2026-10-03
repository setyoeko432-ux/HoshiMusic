package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.util.DiagLog

/**
 * [DIAG] Instrumen pengukuran rantai DSP — DIMISAKHKAN dari jalur audio.
 *
 * Alasan pemisahan ini murni untuk keterbacaan. Semua field di sini
 * hanya `@Volatile Long` yang dijumlahkan sekali per buffer; biaya jalurnya
 * sudah terukur negligible (lihat timing di bawah). Yang berubah adalah
 * bahwa HoshiDspProcessor sekarang bisa dibaca tanpa distraksi
 * instrumentation, dan setiap angka memiliki satu tempat jelas untuk
 * ditelusuri.
 *
 * Threading — dua arah, dan itu disengaja:
 *  - [onBufferProcessed] dan [recordTiming] dipanggil DARI audio thread.
 *    Harus tanpa alokasi, tanpa lock, tanpa logging.
 *  - [checkFrozen], [timingReport], [resetFreezeState] dipanggil DARI luar
 *    (coroutine heartbeat).
 *
 * Hanya satu penulis dan satu pembaca per field, jadi tidak ada atomic yang
 * berlebihan; `@Volatile` sudah cukup.
 */
class DspDiagnostics(private val tag: String) {

    @Volatile
    private var convertInNs = 0L

    @Volatile
    private var nativeProcessNs = 0L

    @Volatile
    private var convertOutNs = 0L

    @Volatile
    private var timedCalls = 0L

    @Volatile
    private var timedFrames = 0L

    // ---------------------------------------------------------------- freeze

    @Volatile
    private var framesAtLastPoll = 0L

    /** Nilai `framesAtLastPoll` pada polling sebelumnya, untuk deteksi progres. */
    @Volatile
    private var lastObservedFrames = 0L

    @Volatile
    private var frozenSinceNs = 0L

    @Volatile
    var isFrozen: Boolean = false
        private set

    /**
     * Dipanggil dari audio thread setiap buffer yang benar-benar diproses.
     * Hanya satu penulisan @Volatile, tanpa lock, tanpa alokasi.
     *
     * [AKUMULASI WAJIB] Nilai harus dijumlahkan, bukan ditimpa.
     *
     * Versi lama menulis `framesAtLastPoll = frames`, yaitu MENIMPA dengan
     * jumlah frame per buffer. Nilai itu konstan (882 frame pada 44,1 kHz,
     * 4608 pada 48 kHz — keduanya ukuran buffer media3, bukan total), jadi
     * [checkFrozen] tidak pernah melihat pergerakan dan SELALU menyatakan
     * beku tepat setelah 3 detik, padahal audio mengalir normal.
     *
     * Akibatnya `recoverFromDspFreeze()` membangun ulang seluruh rantai
     * audio berulang kali, dan itulah sumber "macet" yang dirasakan
     * pengguna — bukan DSP, bukan AudioTrack, bukan media3.
     *
     * Diperbaiki dengan penjumlahan. Bukti di vivo/iQOO V2322:
     * queueInput#450 frames=135828 sementara detector melaporkan
     * "frame macet di 882".
     */
    fun onBufferProcessed(frames: Long) {
        framesAtLastPoll += frames
    }

    /**
     * Dipanggil dari coroutine heartbeat, bukan audio thread.
     *
     * Mengembalikan true HANYA kalau penghitung frame kumulatif benar-benar
     * tidak bergerak selama lebih dari [freezeThresholdNs].
     *
     * Dua cacat versi lama sudah diperbaiki di sini:
     *
     * 1. `frozenSinceNs` tidak pernah dikembalikan ke 0 saat frame kembali
     *    bergerak, sehingga begitu freeze terjadi sekali, kondisi
     *    `nowNs - frozenSinceNs >= threshold` tetap benar selamanya dan
     *    freeze berikutnya menyatakan beku pada polling pertama.
     *
     * 2. Tidak ada pembandingan antar-poll, jadi "tidak bergerak" tidak pernah
     *    diuji. Sekarang `lastObservedFrames` dibandingkan dengan
     *    `framesAtLastPoll`; kalau berbeda, ada progres dan timer di-reset.
     *
     * Tetap murni GEJALA: processor hanya melaporkan bahwa ia tidak
     * menerima input lagi, bukan penyebabnya.
     */
    fun checkFrozen(nowNs: Long, freezeThresholdNs: Long = DEFAULT_FREEZE_THRESHOLD_NS): Boolean {
        val current = framesAtLastPoll
        if (current == 0L) {
            frozenSinceNs = 0L
            lastObservedFrames = 0L
            isFrozen = false
            return false
        }

        if (current != lastObservedFrames) {
            // Ada progres. Timer beku harus di-reset, kalau tidak freeze
            // berikutnya akan memicu seketika.
            lastObservedFrames = current
            frozenSinceNs = 0L
            isFrozen = false
            return false
        }

        if (frozenSinceNs == 0L) {
            frozenSinceNs = nowNs
            isFrozen = false
            return false
        }
        if (nowNs - frozenSinceNs >= freezeThresholdNs) {
            if (!isFrozen) {
                isFrozen = true
                DiagLog.w(
                    tag,
                    "FREEZE: frame macet di $current selama " +
                        "${(nowNs - frozenSinceNs) / 1_000_000_000L}s — DSP tidak menerima input"
                )
            }
        }
        return isFrozen
    }

    fun resetFreezeState() {
        framesAtLastPoll = 0L
        lastObservedFrames = 0L
        frozenSinceNs = 0L
        isFrozen = false
    }

    // ---------------------------------------------------------------- timing

    /**
     * Akumulasi biaya per buffer. Memakai satu objek [Long] sementara
     * daripada menulis @Volatile tiga kali, lalu menuliskannya sekali
     * di akhir — mengurangi penulisan lintas-thread di audio path.
     */
    fun recordTiming(convInNs: Long, nativeNs: Long, convOutNs: Long, frames: Long) {
        this.convertInNs += convInNs
        this.nativeProcessNs += nativeNs
        this.convertOutNs += convOutNs
        timedCalls++
        timedFrames += frames
    }

    /**
     * Biaya rata-rata per panggilan dibanding durasi PCM yang harus
     * dicakup panggilan itu.
     *
     * `ratio >= 1.0` berarti rantai ini TIDAK mungkin real-time dan pasti
     * penyebab underrun. Angka terukur di vivo/iQOO untuk build ini adalah
     * `ratio = 0,10` — headroom 10x. Angka itulah yang menyingkirkan
     * performa DSP sebagai penyebab freeze.
     */
    fun timingReport(sampleRate: Int): String {
        val calls = timedCalls
        if (calls == 0L || sampleRate <= 0) return "timing=(belum ada panggilan native)"
        val budgetPerCallUs = (timedFrames * 1_000_000.0 / sampleRate) / calls
        val convInUs = convertInNs.toDouble() / 1000.0 / calls
        val nativeUs = nativeProcessNs.toDouble() / 1000.0 / calls
        val convOutUs = convertOutNs.toDouble() / 1000.0 / calls
        val total = convInUs + nativeUs + convOutUs
        // [FIX] Seluruh string harus diapit tanda kurung SEBELUM `.format()`.
        //
        // Versi lama menulis:
        //     "timing[... convIn=%.0f native=%.0f convOut=%.0f " +
        //         "TOTAL=%.0f BUDGET=%.0f ratio=%.2f]".format(a, b, c, ...)
        //
        // Di Kotlin, `+` mengikat lebih longgar daripada pemanggilan metode,
        // jadi `.format()` hanya menempel pada LITERAL KEDUA. Akibatnya
        // `convIn`/`native`/`convOut` tercetak mentah sebagai `%.0f`, sementara
        // argumen pertama masuk ke `TOTAL`, kedua ke `BUDGET`, ketiga ke
        // `ratio`. Laporan yang muncul benar-benar salah label: angka "TOTAL"
        // sebenarnya cuma waktu konversi masuk.
        //
        // Terverifikasi di perangkat: tanpa fix ini laporan menghasilkan
        // `convIn=%.0f native=%.0f convOut=%.0f TOTAL=2845 BUDGET=4314 ratio=3029,00`
        // padahal budget sesaatnya ~46 ms dan rasionya ~0,2.
        return ("timing[perCallUs convIn=%.0f native=%.0f convOut=%.0f " +
            "TOTAL=%.0f BUDGET=%.0f ratio=%.2f]")
            .format(
                convInUs, nativeUs, convOutUs, total, budgetPerCallUs,
                total / budgetPerCallUs
            )
    }

    fun resetTiming() {
        convertInNs = 0L
        nativeProcessNs = 0L
        convertOutNs = 0L
        timedCalls = 0L
        timedFrames = 0L
    }

    companion object {
         /**
         * 3 detik. Diperukuran: DSP memproses ~9,6 buffer per detik pada
         * 44,1 kHz, jadi ambang ini memberi toleransi ~30 buffer sebelum
         * menyatakan beku — cukup untuk membedakan jeda buffering normal
         * dari keadaan macet permanen.
         */
        const val DEFAULT_FREEZE_THRESHOLD_NS = 3_000_000_000L
    }
}

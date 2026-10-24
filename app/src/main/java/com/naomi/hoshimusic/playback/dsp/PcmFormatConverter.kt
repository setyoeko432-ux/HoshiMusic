package com.naomi.hoshimusic.playback.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Konversi PCM 16-bit <-> float stereo, dengan byte carry-over.
 *
 * Alat native hanya menerima Float32 Stereo: satu frame = 2 sample =
 * 8 byte. AudioProcessor dari media3 hanya boleh mengubah format PCM
 * yang sudah disepakati, dan `AudioProcessorChain` di media3 hanya
 * menerima processor 16-bit (google/ExoPlayer#10516). Jadi konversi ini
 * adalah jalur wajib, bukan pilihan.
 *
 * Batas buffer dari media3 tidak selalu jatuh di batas frame stereo.
 * Satu byte sisa harus dibawa ke panggilan berikutnya, kalau tidak satu
 * sample hilang di setiap sambungan buffer — terdengar sebagai klik.
 * [carry] menyimpan maximal satu byte itu.
 *
 * Threading: dipakai dari audio thread saja. Tidak ada alokasi setelah
 * [ensureDirectBuffer] dipanggil.
 */
class PcmFormatConverter {

    /** Byte sisa dari panggilan sebelumnya, 0 atau 1 byte. */
    private val carry = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())

    /** Buffer kerja float. Hanya tumbuh, tidak pernah menyusut. */
    var directBuffer: ByteBuffer = ByteBuffer.allocate(0).order(ByteOrder.nativeOrder())
        private set

    /** Sisa byte yang belum jadi sample utuh; 0 = tidak ada. */
    val carryBytes: Int get() = carry.position()

    fun reset() {
        carry.clear()
        directBuffer = ByteBuffer.allocate(0).order(ByteOrder.nativeOrder())
    }

    /**
     * Pastikan [directBuffer] cukup besar untuk [bytesNeeded] byte float.
     *
     * Ukuran 64 KB dipilih karena `allocateDirect` di Android page-aligned
     * (4 KB), yang memenuhi syarat alignment 16 byte untuk ARM NEON — syarat
     * yang dibutuhkan `processEssBandBatchNEON`.
     */
    fun ensureDirectBuffer(bytesNeeded: Int) {
        if (directBuffer.capacity() < bytesNeeded) {
            val size = maxOf(bytesNeeded, DEFAULT_DIRECT_CAPACITY)
            directBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            directBuffer.clear()
        }
    }

    /**
     * Hitung jumlah frame stereo yang bisa diproses dari [available] byte.
     *
     * Penting: jumlah SAMPLE dihitung dulu, baru diturunkan ke frame.
     * Versi lama membagi byte langsung per frame, sehingga saat total
     * tersedia bukan kelipatan frame stereo, satu sample tertulis melewati
     * batas dan dibuang oleh native — satu sample hilang per buffer.
     */
    fun framesAvailable(available: Int, is16Bit: Boolean): Int {
        val bytesPerSample = if (is16Bit) 2 else 4
        val samples = (available + carryBytes) / bytesPerSample
        return samples / 2
    }

    /**
     * Isi [directBuffer] dengan float stereo dari [input].
     *
     * Setelah dipanggil, [directBuffer] berada di posisi write (belum flip).
     */
    fun toFloat(input: ByteBuffer, frames: Int, is16Bit: Boolean) {
        if (is16Bit) decode16Bit(input, frames) else bulkCopy(input, frames)
    }

    /**
     * Salin float stereo dari [directBuffer] ke [output] dengan konversi
     * kembali ke PCM 16-bit memakai rounding (bukan truncation). [output] dibiarkan dalam posisi write.
     */
    fun encode16Bit(direct: ByteBuffer, output: ByteBuffer, frames: Int) {
        direct.flip()
        val samples = frames * 2
        var i = 0
        while (i < samples && direct.hasRemaining()) {
            val f = direct.float
            val scaled = f.coerceIn(-1.0f, 1.0f) * 32767.0f
            val rounded = if (scaled >= 0f) (scaled + 0.5f).toInt() else (scaled - 0.5f).toInt()
            output.putShort(rounded.toShort())
            i++
        }
    }

    /** Salin float stereo mentah, tanpa konversi. */
    fun encodeFloat(direct: ByteBuffer, output: ByteBuffer, bytes: Int) {
        direct.flip()
        val end = minOf(bytes, direct.remaining())
        var moved = 0
        while (moved < end) {
            output.put(direct)
            moved += 4
        }
    }

    // ------------------------------------------------------------- internal

    /**
     * Decode 16-bit ke float. Dua tahap:
     *  1. habiskan [carry] lebih dulu, gabungkan dengan byte pertama dari
     *     [input] kalau carry berisi satu byte ganjil;
     *  2. lanjutkan pasangan straight dari [input].
     *
     * Kalau [input] habis di tengah sample, byte sisanya disimpan lagi
     * ke [carry] — tidak pernah dibuang.
     */
    private fun decode16Bit(input: ByteBuffer, frames: Int) {
        val targetSamples = frames * 2
        var written = 0

        if (carry.position() > 0) {
            carry.flip()
            while (written < targetSamples && carry.hasRemaining()) {
                val lo = carry.get().toInt() and 0xFF
                val hi = when {
                    carry.hasRemaining() -> carry.get().toInt() and 0xFF
                    input.remaining() >= 1 -> input.get().toInt() and 0xFF
                    else -> {
                        carry.clear()
                        carry.put(lo.toByte())
                        return
                    }
                }
                directBuffer.putFloat((((hi shl 8) or lo).toShort()) / 32768f)
                written++
            }
            carry.clear()
        }

        while (written < targetSamples && input.remaining() >= 2) {
            directBuffer.putFloat(input.short / 32768f)
            written++
        }

        // Simpan sisa ganjil untuk panggilan berikutnya.
        //
        // [DEFECT 4] `carry.position()` adalah penanda jumlah byte tertunda
        // (dibaca `carryBytes`, dan jadi penanda di `framesAvailable`).
        // Versi lama menulis `put` -> `flip` -> `get` -> `clear`: `put`
        // sebenarnya sudah mengisi position dengan jumlah byte sisa, tapi
        // `flip()` mengembalikannya ke 0 lalu `clear()` membuang sisa itu.
        // Akibatnya `carryBytes` permanen 0, cabang
        // `if (carry.position() > 0)` di `decode16Bit` jadi kode mati, dan
        // setiap batas buffer yang tidak jatuh di batas frame stereo
        // MEMBUANG 1-3 byte dari stream.
        //
        // Dua gejala: (1) byte-stream desync — tiap sample berikutnya
        // bergeser, menghasilkan dengung frekuensi tinggi yang menutupi
        // perbedaan DSP apa pun yang sedang dicoba didengarkan; (2) karena
        // `directBuffer` jadi kurang dari `frames * 2` float, `encode16Bit`
        // tetap menulis buffer keluaran sepanjang `frames * 4` byte dan
        // `replaceOutputBuffer` mengisinya nol -> klik/dropout berkala.
        //
        // `clear()` lalu `put()` (bukan `put()` lalu `flip()`) karena yang
        // dibutuhkan di sini murni menulis ulang position sebagai penanda,
        // tanpa menyentuh limit.
        if (input.hasRemaining()) {
            carry.clear()
            carry.put(input)
        }
    }

    /**
     * Jalur float: bulk copy, bukan per-sample. Tidak ada carry karena
     * 4 byte/sample sudah selaras dengan batas mana pun.
     */
    private fun bulkCopy(input: ByteBuffer, frames: Int) {
        val needed = frames * 8
        if (carry.position() > 0) {
            carry.flip()
            val n = minOf(carry.remaining(), needed)
            val end = carry.position() + n
            while (carry.position() < end) directBuffer.put(carry)
            carry.clear()
        }
        val fromInput = needed - directBuffer.position()
        if (fromInput > 0) {
            val originalLimit = input.limit()
            input.limit(input.position() + minOf(fromInput, input.remaining()))
            directBuffer.put(input)
            input.limit(originalLimit)
        }
    }

    companion object {
        const val DEFAULT_DIRECT_CAPACITY = 64 * 1024
    }
}

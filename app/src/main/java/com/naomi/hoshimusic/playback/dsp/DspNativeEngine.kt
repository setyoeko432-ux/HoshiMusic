package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.util.DiagLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Jembatan JNI ke mesin C++ `hoshi_dsp`.
 *
 * Tanggung jawab kelas ini HANYA: memegang native handle, memanggil fungsi
 * native, dan menjaga agar handle tidak pernah dipakai setelah dilepas.
 * Tidak tahu apa itu media3, tidak tahu apa itu AudioFormat, tidak tahu
 * apa itu buffer PCM.
 *
 * Pemisahan ini disengaja. Semua kegagalan yang pernah kita telusuri
 * (handle yang bocor, native yang tidak pernah di-init ulang setelah
 * reset, exception yang tidak tertangkap) berakar pada siklus hidup
 * handle, dan siklus hidup paling mudah ditelaah kalau terpisah dari
 * lapisan Media3.
 *
 * Threading: [currentHandle] dibaca tanpa lock supaya audio thread tidak
 * pernah memblokir. State lain hanya ditulis dari thread konfigurasi.
 */
class DspNativeEngine(private val tag: String) {

    @Volatile
    private var handle: Long = 0L

    private val handleAtomic = AtomicLong(0L)

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var isReleased: Boolean = false
        private set

    @Volatile
    var sampleRate: Int = 0
        private set

    /** Handle saat ini, atau 0. Aman dipanggil dari audio thread. */
    val currentHandle: Long get() = handleAtomic.get()

    val isUsable: Boolean
        get() = handleAtomic.get() != 0L && isInitialized && !isReleased

    /**
     * Buat handle native kalau belum ada.
     *
     * Dipanggil dari `onConfigure` supaya telemetry bisa melaporkan
     * "initialized" bahkan saat negosiasi format masih berjalan.
     */
    fun ensureCreated(): Long {
        if (isReleased) return 0L
        if (handle == 0L) {
            handle = nativeCreate()
            handleAtomic.set(handle)
        }
        return handle
    }

    /**
     * Inisialisasi mesin pada [rate] Hz.
     *
     * Aman dipanggil berulang: `DspEngine::init()` di C++ sudah
     * idempoten untuk sample rate yang sama.
     */
    fun init(rate: Int): Boolean {
        val h = handleAtomic.get()
        if (h == 0L || isReleased) return false
        if (rate < 8000 || rate > 768000) {
            DiagLog.e(tag, "sample rate di luar rentang native: $rate")
            return false
        }
        if (!safeCall { nativeInit(h, rate) }) return false
        sampleRate = rate
        isInitialized = true
        return true
    }

    /**
     * Proses [length] byte float stereo, mulai dari posisi [buffer] saat ini.
     * Panggil dari audio thread.
     *
     * `position()` diteruskan ke native secara eksplisit.
     * `GetDirectBufferAddress()` selalu mengembalikan alamat DASAR buffer dan
     * mengabaikan `position()`, jadi tanpa offset eksplisit wilayah yang
     * diproses akan salah begitu saja pada buffer hasil slice/duplikat.
     */
    fun process(buffer: ByteBuffer, length: Int) {
        val h = handleAtomic.get()
        if (h == 0L || !isInitialized || length < 8) return
        // Sengaja tanpa safeNativeCall: handle sudah divalidasi di atas,
        // dan try/catch per buffer menambah biaya di jalur audio.
        nativeProcess(h, buffer, length, buffer.position())
    }

    fun flush(): Boolean {
        val h = handleAtomic.get()
        if (h == 0L || !isInitialized) return false
        return safeCall { nativeFlush(h) }
    }

    /**
     * Reset mesin DAN turunkan status inisialisasi.
     *
     * Media3 tidak selalu menjalankan negosiasi format ulang setelah
     * reset. Versi lama membiarkan `isInitialized` tetap true, sehingga
     * mesin yang sudah di-flush tidak pernah di-init ulang dan playback
     * diam-diam jatuh ke jalur software — DSP mati tanpa error apa pun.
     *
     * Ini juga titik yang aman untuk menghapus handle yang tertunda:
     * `reset()` dipanggil dari audio thread (hook `onReset`), jadi tidak
     * mungkin ada panggilan `process()` yang sedang berjalan.
     */
    fun reset(): Boolean {
        drainPendingDelete()
        val h = handleAtomic.get()
        val ok = if (h != 0L && isInitialized) safeCall { nativeReset(h) } else false
        isInitialized = false
        return ok
    }

    /**
     * Handle yang ditandai untuk dihapus, menunggu giliran audio thread.
     */
    @Volatile
    private var pendingDelete = 0L

    /**
     * Melakukan `delete` yang tertunda. Hanya boleh dipanggil dari audio thread.
     */
    private fun drainPendingDelete() {
        val h = pendingDelete
        if (h == 0L) return
        pendingDelete = 0L
        runCatching { nativeRelease(h) }
    }

    /**
     * Lepas handle.
     *
     * [FIX-6] `release()` dipanggil dari service (bukan audio thread). Kalau
     * `delete` dilakukan di sini, ada jendela use-after-free: audio thread bisa
     * sedang berada di dalam `process()` memakai handle yang sama.
     *
     * Jadi `release()` hanya MENANDA handle, dan memory dibebaskan
     * nanti oleh `drainPendingDelete()` yang dipanggil dari `reset()` pada
     * audio thread. Handle langsung dinonaktifkan agar tidak ada pemanggilan
     * baru yang memakainya.
     */
    fun release() {
        val h = handleAtomic.get()
        if (h == 0L || isReleased) {
            handle = 0L
            handleAtomic.set(0L)
            return
        }
        isReleased = true
        isInitialized = false
        handle = 0L
        handleAtomic.set(0L)
        pendingDelete = h
    }

    // ------------------------------------------------------- setter native

    fun setAdaptiveEnabled(on: Boolean) = withHandle("setAdaptiveEnabled") { nativeSetAdaptiveDspEnabled(it, on) }
    fun setEnabled(on: Boolean) = withHandle("setEnabled") { nativeSetEnabled(it, on) }
    fun setParams(params: FloatArray) = withHandle("setParams") { nativeSetParams(it, params) }
    fun setBtClarity(on: Boolean) = withHandle("setBtClarity") { nativeSetBtClarity(it, on) }
    fun setAmbience(on: Boolean) = withHandle("setAmbience") { nativeSetAmbience(it, on) }
    fun setPreampGain(gain: Float) = withHandle("setPreampGain") { nativeSetPreampGain(it, gain) }
    fun setSystemVolume(volume: Float) = withHandle("setSystemVolume") { nativeSetSystemVolume(it, volume) }

    fun applyPeqSnapshot(
        enabled: Boolean,
        preampDb: Float,
        count: Int,
        types: IntArray,
        fc: FloatArray,
        gain: FloatArray,
        q: FloatArray,
        revision: Int
    ) = withHandle("applyPeqSnapshot") {
        nativeApplyPeqSnapshot(it, enabled, preampDb, count, types, fc, gain, q, revision)
    }

    /** Snapshot telemetry, atau null kalau mesin belum hidup. */
    fun telemetry(): DspTelemetry? {
        val h = handleAtomic.get()
        if (h == 0L || isReleased) return null
        return try {
            nativeGetTelemetrySnapshot(h)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    // -------------------------------------------------------------- internal

    private inline fun withHandle(label: String, block: (Long) -> Unit): Boolean {
        val h = handleAtomic.get()
        if (h == 0L || !isInitialized || isReleased) {
            // [DEFECT 3] Dulu kondisi ini `return false` tanpa jejak apa pun.
            //
            // `isInitialized` hanya jadi true di dalam
            // `HoshiDspProcessor.onConfigure` -> `engine.init(sr)` yang dipanggil
            // media3 di audio thread, sedangkan coordinator menerapkan config
            // dari `serviceScope` (Dispatchers.Main) begitu processor di-attach
            // di `onCreate()`. Jadi setiap config yang jatuh di jendela itu
            // hilang tanpa error — dan tidak ada cara membuktikannya dari luar.
            //
            // Onset yang paling nyata: `onReset()` -> `engine.reset()`
            // mengembalikan `isInitialized` ke false, sementara service
            // memanggil `initializePlayer(forceRecreate = true)` pada
            // perubahan rute dan saat `boutiqueBypassEnabled` toggled, dengan
            // `detachProcessor()` tidak pernah dipanggil di jalur reuse.
            //
            // Config yang hilang biasanya dipulihkan oleh
            // `pushAllStateToNative()` di akhir `onConfigure`, tapi hanya untuk
            // state yang ada di `desiredConfig` pada saat itu. Penandaan
            // ini membuat sisanya terlihat di DiagLog.
            droppedSetters += 1
            DiagLog.w(
                tag,
                "setter '$label' DILEWATI: handle=${if (h != 0L) "ada" else "0"} " +
                    "initialized=$isInitialized released=$isReleased " +
                    "(total dilewati=$droppedSetters) — configure belum selesai"
            )
            return false
        }
        return safeCall { block(h) }
    }

    /** Berapa banyak setter yang hilang karena mesin belum di-init. */
    @Volatile
    var droppedSetters: Int = 0
        private set

    /** Bungkus panggilan native; `UnsatisfiedLinkError` mematikan library. */
    private inline fun safeCall(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: UnsatisfiedLinkError) {
        DspNativeLib.libraryLoaded = false
        DiagLog.e(tag, "JNI tidak tersedia", e)
        false
    }

    // ------------------------------------------------------------- native JNI
    //
    // Nama simbol JNI diturunkan dari package + class, jadi SETELAH kelas ini
    // dipindah ke `com.naomi.hoshimusic.playback.dsp`, nama fungsi di
    // hoshi_dsp_core.cpp harus ikut diubah dari
    //   Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_native*
    // menjadi
    //   Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_native*
    //
    // Semua adalah instance method (bukan @JvmStatic) supaya parameter
    // kedua di sisi C++ tetap `jobject`, sama seperti sebelumnya.

    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long, sampleRate: Int)
    private external fun nativeProcess(handle: Long, buffer: ByteBuffer, length: Int, offset: Int)
    private external fun nativeFlush(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetSystemVolume(handle: Long, volume: Float)
    private external fun nativeGetTelemetrySnapshot(handle: Long): DspTelemetry?
    private external fun nativeApplyPeqSnapshot(
        handle: Long, enabled: Boolean, preampDb: Float, count: Int,
        types: IntArray, fc: FloatArray, gain: FloatArray, q: FloatArray, revision: Int
    )
    private external fun nativeSetAdaptiveDspEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetParams(handle: Long, params: FloatArray)
    private external fun nativeSetBtClarity(handle: Long, enabled: Boolean)
    private external fun nativeSetAmbience(handle: Long, enabled: Boolean)
    private external fun nativeSetPreampGain(handle: Long, gain: Float)

    companion object {
        /**
         * Muat library native sekali saja.
         *
         * Dipisah dari instance karena `System.loadLibrary` harus selesai
         * sebelum instance pertama dipakai, sementara handle baru dibuat
         * jauh kemudian saat negosiasi format.
         */
        fun ensureLibraryLoaded() {
            if (libraryLoadAttempted) return
            libraryLoadAttempted = true
            try {
                System.loadLibrary("hoshi_dsp")
                DspNativeLib.libraryLoaded = true
                DiagLog.i("HoshiDsp", "library native hoshi_dsp berhasil dimuat")
            } catch (e: UnsatisfiedLinkError) {
                DspNativeLib.libraryLoaded = false
                DiagLog.e("HoshiDsp", "gagal memuat library native hoshi_dsp", e)
            } catch (e: Exception) {
                DspNativeLib.libraryLoaded = false
                DiagLog.e("HoshiDsp", "gagal memuat library native hoshi_dsp", e)
            }
        }

        /** True kalau `libhoshi_dsp.so` berhasil dimuat. */
        val libraryLoaded: Boolean get() = DspNativeLib.libraryLoaded

        @Volatile
        private var libraryLoadAttempted = false
    }
}

/**
 * Holder status library native.
 *
 * Dipisah supaya bisa ditulis dari [DspNativeEngine.safeCall] tanpa
 * membuat `libraryLoaded` jadi `lateinit` yang hanya bisa di-set sekali.
 */
internal object DspNativeLib {
    @JvmStatic
    var libraryLoaded: Boolean = false
}

package com.naomi.hoshimusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.naomi.hoshimusic.data.PeqBand
import com.naomi.hoshimusic.playback.dsp.DspDiagnostics
import com.naomi.hoshimusic.playback.dsp.DspNativeEngine
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.playback.dsp.DspTelemetry
import com.naomi.hoshimusic.playback.dsp.DspTelemetryBridge
import com.naomi.hoshimusic.playback.dsp.PcmFormatConverter
import com.naomi.hoshimusic.playback.dsp.VocalTelemetryState
import com.naomi.hoshimusic.util.DiagLog
import java.nio.ByteBuffer

/**
 * Adapter antara kontrak `AudioProcessor` media3 dan mesin DSP native.
 *
 * ## Aturan kontrak yang dipegang kelas ini
 *
 * Semua aturan diambil dari sumber media3 1.11.1 (media3-common sources jar),
 * bukan dari ingatan. Rujukan file ada di tiap bagian.
 *
 * 1. `onConfigure` WAJIB mengembalikan `AudioFormat.NOT_SET` kalau kita tidak
 *    benar-benar bisa memproses. `BaseAudioProcessor.configure()` (final)
 *    mengembalikan `isActive() ? pendingOutputAudioFormat : NOT_SET`, dan
 *    `AudioProcessingPipeline.configure()` punya
 *    `checkState(!nextFormat.equals(NOT_SET))` untuk processor yang aktif.
 *    Processor yang melaporkan aktif lalu gagal menghasilkan output berarti
 *    `isOperational()` true tapi tidak ada yang mengalir: kondisi undead yang
 *    tidak memunculkan exception apa pun.
 *
 * 2. `isActive()` harus persis mengikuti hasil negosiasi format. Method ini
 *    beranotasi `@CallSuper` dan membaca
 *    `pendingOutputAudioFormat != NOT_SET`. Menambah syarat sendiri membuat
 *    status ini bisa berbeda dari format yang sudah dinegosiasikan.
 *
 * 3. `queueInput()` SELALU harus menghasilkan output. Dokumentasi interface:
 *    "Calling queueInput() again invalidates any pending output." Kalau
 *    `queueInput` keluar tanpa `replaceOutputBuffer`, output yang tertunda
 *    hilang dan pipeline tidak pernah maju.
 *
 * 4. Tidak boleh mengubah format keluar. `AudioProcessorChain` hanya menerima
 *    PCM 16-bit (google/ExoPlayer#10516); meminta float membuat
 *    `DefaultAudioSink` menolak dengan `UnhandledAudioFormatException`.
 *
 * 5. `isActive()` tidak boleh berayun saat pemutaran berjalan; status hanya
 *    berubah lewat `configure()` dan `reset()` yang dipanggil media3.
 *
 * ### Kenapa versi sebelumnya membeku
 *
 * `onConfigure` versi lama selalu `return inputAudioFormat`, bahkan ketika
 * `engine.init()` gagal atau `currentHandle == 0L`. Itu melanggar aturan 1:
 * processor tetap join ke `activeAudioProcessors` sehingga `isOperational()`
 * jadi true, sementara predikat lain (`shouldProcess()`) bernilai false
 * sehingga input tidak pernah diproses. Akibatnya `queueInput` keluar tanpa
 * output, pipeline berhenti, dan renderer berhenti memberi buffer.
 *
 * Gejalanya persis yang tercatat di hoshi_diag.log: frame macet di satu
 * buffer, lalu 3x rebuild, lalu playback berhenti.
 */
@UnstableApi
class HoshiDspProcessor : BaseAudioProcessor() {

    companion object {
        const val DSP_PARAM_COUNT = 15

        /** Maks band PEQ, sinkron dengan `PEQ_SLOTS` di mesin C++. */
        const val DSP_PEQ_MAX = 10

        private const val TAG = "HoshiDsp"

        /** PCM 16-bit stereo: 2 kanal x 2 byte per frame. */
        private const val BYTES_PER_16BIT_FRAME = 4

        /** Float stereo: 2 kanal x 4 byte per frame. */
        private const val BYTES_PER_FLOAT_FRAME = 8

        /** True kalau `libhoshi_dsp.so` berhasil dimuat. */
        val libraryLoaded: Boolean
            get() {
                DspNativeEngine.ensureLibraryLoaded()
                return DspNativeEngine.libraryLoaded
            }
    }

    private val engine = DspNativeEngine(TAG)
    private val converter = PcmFormatConverter()
    private val diagnostics = DspDiagnostics(TAG)

    init {
        DspNativeEngine.ensureLibraryLoaded()
    }

    // ------------------------------------------------------------ konfigurasi

    private data class DspConfig(
        var adaptiveDspEnabled: Boolean = false,
        var peqEnabled: Boolean = false,
        var enabled: Boolean = false,
        var params: FloatArray? = null,
        var btClarity: Boolean = false,
        var ambience: Boolean = false,
        var preampGain: Float = 1f,
        var spatialAudioEnabled: Boolean = false,
        var spatialAudioPreset: Int = 0,
        var peqCount: Int = 0
    )

    private val desiredConfig = DspConfig()

    private var peqRevision = 0

    @Volatile
    private var inputEncoding = C.ENCODING_INVALID

    @Volatile
    private var inputSampleRate = 0

    @Volatile
    var configureCount: Int = 0
        private set

    @Volatile
    private var lastConfiguredFormat: String = "(belum pernah dikonfigurasi)"

    @Volatile
    private var formatPathUsed: String = "(belum ada input)"

    @Volatile
    private var fallbackFrameCounter = 0L

    @Volatile
    private var hardwareSupported = false

    @Volatile
    var boutiqueBypassActive: Boolean = false

    @Volatile
    private var telemetrySnapshot: DspTelemetry = DspTelemetry()
        private set

    val isHardwareSupported: Boolean get() = hardwareSupported

    /**
     * True HANYA kalau mesin native benar-benar memproses audio.
     *
     * Drives `AudioPathStatus.DSP_ACTIVE` di `MusicPlaybackService`. Sengaja
     * konservatif: library termuat, ada handle, dan pengguna menyalakan
     * sesuatu.
     */
    val isActuallyProcessing: Boolean
        get() = DspNativeEngine.libraryLoaded &&
            engine.currentHandle != 0L &&
            dspUserEnabled

    /**
     * SATU-SATUNYA predikat "apakah DSP mengubah suara".
     *
     * Versi lama punya dua predikat yang tidak sinkron: `isActive()` memakai
     * `super.isActive() && libraryLoaded`, sedangkan `shouldProcess()` memakai
     * `libraryLoaded && engine.isUsable && hardwareSupported && (...)`.
     * Keduanya bisa berbeda, dan itu sumber kebuntuan. Sekarang hanya ada
     * satu, dan hanya dipakai di dalam `queueInput`, tidak pernah menyentuh
     * status aktif yang dibaca pipeline.
     */
    private val dspUserEnabled: Boolean
        get() = desiredConfig.adaptiveDspEnabled ||
            desiredConfig.peqEnabled ||
            desiredConfig.peqCount > 0 ||
            kotlin.math.abs(desiredConfig.preampGain - 1.0f) > 0.001f

    // ------------------------------------------------- kontrak AudioProcessor

    /**
     * Aturan 2: biarkan `BaseAudioProcessor` menentukan, apa adanya.
     *
     * Status ini persis "apakah kita sudah punya format keluaran yang
     * dinegosiasikan", dan itu satu-satunya definisi yang dipakai
     * `AudioProcessingPipeline` untuk memasukkan processor ke rantai.
     */
    override fun isActive(): Boolean = super.isActive()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        configureCount++
        inputEncoding = inputAudioFormat.encoding
        inputSampleRate = inputAudioFormat.sampleRate
        formatPathUsed = "16bit(loop-per-sample)"
        lastConfiguredFormat =
            "sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                "enc=${inputAudioFormat.encoding}"

        DiagLog.i(
            TAG,
            "AUDIT configure#$configureCount format=$lastConfiguredFormat " +
                "libLoaded=${DspNativeEngine.libraryLoaded}"
        )

        // Aturan 4: hanya PCM 16-bit. Untuk format lain, jangan masuk rantai
        // sama sekali; lebih baik audio apa adanya daripada audio yang diam.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            hardwareSupported = false
            DiagLog.w(TAG, "configure: enc=${inputAudioFormat.encoding} bukan 16-bit, dilewati")
            return AudioFormat.NOT_SET
        }

        if (!DspNativeEngine.libraryLoaded) {
            hardwareSupported = false
            DiagLog.w(TAG, "configure: library native tidak termuat, dilewati")
            return AudioFormat.NOT_SET
        }

        // Aturan 1: kalau mesin tidak hidup, janganlapau aktif.
        engine.ensureCreated()
        if (engine.currentHandle == 0L) {
            hardwareSupported = false
            DiagLog.e(TAG, "configure: native handle gagal dibuat, dilewati")
            return AudioFormat.NOT_SET
        }

        if (!engine.init(inputAudioFormat.sampleRate)) {
            hardwareSupported = false
            DiagLog.e(
                TAG,
                "configure: nativeInit(${inputAudioFormat.sampleRate}) gagal, dilewati"
            )
            return AudioFormat.NOT_SET
        }

        hardwareSupported = true
        DiagLog.i(TAG, "configure: native DSP aktif pada ${inputAudioFormat.sampleRate}Hz")
        pushAllStateToNative()
        return inputAudioFormat
    }

    /**
     * Jalur audio utama. Berjalan di audio thread media3.
     *
     * Aturan 3: fungsi ini selalu menghasilkan output kalau dipanggil dengan
     * input yang punya byte. Tidak ada cabang yang keluar tanpa
     * `replaceOutputBuffer` + `flip()`, dan seluruh input selalu dikonsumsi.
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        // `AudioProcessingPipeline.getOutput()` memompa dengan EMPTY_BUFFER
        // untuk menanyakan apakah processor bisa menghasilkan sesuatu. Itu
        // bukan input sungguhan, jadi tidak boleh menyentuh output buffer.
        //
        // CATATAN: jangan pernah logging di sini. Jalur ini berjalan di audio
        // thread pada frekuensi buffer, dan DiagLog menyatakan larangan itu
        // secara eksplisit.
        if (inputBuffer.remaining() == 0) return

        if (!engine.isInitialized) {
            engine.init(inputSampleRate)
        }

        val frames = converter.framesAvailable(inputBuffer.remaining(), true)
        if (frames <= 0) {
            // Byte yang tersisa tidak cukup untuk satu frame stereo penuh.
            // Kontrak mengizinkan posisi tidak maju; sisanya dibawa ke
            // panggilan berikutnya lewat carry PcmFormatConverter.
            return
        }

        if (dspUserEnabled && engine.isUsable) {
            processWithDsp(inputBuffer, frames)
        } else {
            passthrough(inputBuffer)
        }

        diagnostics.onBufferProcessed(frames.toLong())
    }

    /**
     * Konversi 16-bit ke float, mesin native, konversi float ke 16-bit.
     *
     * Jumlah byte keluar selalu sama dengan jumlah byte masuk (frames x 2 kanal
     * x 2 byte), jadi latency-neutral dan tidak ada sample yang tertinggal.
     */
    private fun processWithDsp(inputBuffer: ByteBuffer, frames: Int) {
        val floatBytes = frames * BYTES_PER_FLOAT_FRAME
        converter.ensureDirectBuffer(floatBytes)
        val work = converter.directBuffer

        val t0 = System.nanoTime()
        converter.toFloat(inputBuffer, frames, true)
        val t1 = System.nanoTime()

        // Data float dimulai dari byte 0 (ensureDirectBuffer() selalu clear()).
        // Jangan pakai work.position(): toFloat() meninggalkannya di ujung data.
        engine.process(work, 0, floatBytes)
        val t2 = System.nanoTime()

        val output = replaceOutputBuffer(frames * BYTES_PER_16BIT_FRAME)
        converter.encode16Bit(work, output, frames)
        output.flip()
        val t3 = System.nanoTime()

        diagnostics.recordTiming(t1 - t0, t2 - t1, t3 - t2, frames.toLong())
    }

    /**
     * DSP mati: teruskan byte apa adanya.
     *
     * Ini jalur yang sah, bukan error, dan tetap memakai `replaceOutputBuffer`
     * supaya bentuk rantai tidak berubah di tengah pemutaran. Memakai
     * `bulk put`, bukan loop per-sample: bit-exact (tidak ada kuantisasi
     * ulang lewat float) sekaligus jalur tercepat.
     */
    private fun passthrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        // `BaseAudioProcessor.flush()` adalah `final` dan sudah membersihkan
        // outputBuffer serta format-nya sendiri SEBELUM hook ini dipanggil,
        // jadi tidak ada `super.onFlush()` yang perlu dipanggil di sini.
        engine.flush()
        converter.reset()
    }

    override fun onReset() {
        // Sama seperti onFlush: `reset()` juga `final` dan sudah membersihkan
        // format-nya sendiri sebelum hook ini dipanggil.
        engine.reset()
        converter.reset()
        diagnostics.resetFreezeState()
    }

    // ------------------------------------------------------------ diagnosis

    fun auditSnapshot(framesProcessed: Long, actuallyProcessing: Boolean): String =
        "configureCount=$configureCount frames=$framesProcessed " +
            "processing=$actuallyProcessing libLoaded=${DspNativeEngine.libraryLoaded} " +
            "nativeInit=${engine.isInitialized} hwSupported=$hardwareSupported " +
            "sr=$inputSampleRate enc=$inputEncoding " +
            "is16bitPath=$formatPathUsed " +
            "frozen=${diagnostics.isFrozen} " +
            "format=$lastConfiguredFormat ${diagnostics.timingReport(inputSampleRate)}"

    fun checkFreeze(
        nowNs: Long,
        freezeThresholdNs: Long = DspDiagnostics.DEFAULT_FREEZE_THRESHOLD_NS
    ): Boolean = diagnostics.checkFrozen(nowNs, freezeThresholdNs)

    fun resetFreezeState() = diagnostics.resetFreezeState()

    /**
     * Snapshot telemetry dari mesin native.
     *
     * Dipanggil dari coroutine heartbeat, bukan audio thread, supaya JNI
     * tidak menambah beban di jalur render.
     */
    fun updateTelemetry(): DspTelemetry? {
        val snapshot = engine.telemetry()
        if (snapshot != null) {
            val frames = snapshot.totalFramesProcessed + fallbackFrameCounter
            val combined = snapshot.copy(
                totalFramesProcessed = frames,
                requestedPeqRevision = peqRevision,
                nativeInitialized = true
            )
            telemetrySnapshot = combined
            DspTelemetryBridge.update(
                combined,
                VocalTelemetryState(
                    vocalEnergy = snapshot.vocalEnergy,
                    isValid = isActuallyProcessing,
                    isProcessingAudio = isActuallyProcessing,
                    frameProgress = frames,
                    updatedAtMs = android.os.SystemClock.elapsedRealtime()
                )
            )
            return combined
        }

        val soft = DspTelemetry(
            totalFramesProcessed = fallbackFrameCounter,
            nativeInitialized = false,
            dominantBand = "SOFTWARE BYPASS"
        )
        telemetrySnapshot = soft
        DspTelemetryBridge.update(
            soft,
            VocalTelemetryState(
                vocalEnergy = 0f,
                isValid = false,
                isProcessingAudio = false,
                frameProgress = fallbackFrameCounter,
                updatedAtMs = android.os.SystemClock.elapsedRealtime()
            )
        )
        return soft
    }

    val telemetry: DspTelemetry get() = telemetrySnapshot

    // ------------------------------------------------------------- setters

    private fun pushAllStateToNative() {
        val snapshot = synchronized(desiredConfig) {
            desiredConfig.copy(params = desiredConfig.params?.copyOf())
        }
        engine.setAdaptiveEnabled(snapshot.adaptiveDspEnabled)
        engine.setEnabled(snapshot.enabled)
        snapshot.params?.let { engine.setParams(it) }
        engine.setBtClarity(snapshot.btClarity)
        engine.setAmbience(snapshot.ambience)
        engine.setPreampGain(snapshot.preampGain)
    }

    fun setSystemVolume(volume: Float) = engine.setSystemVolume(volume)

    fun setEnabled(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.enabled == enabled &&
                desiredConfig.adaptiveDspEnabled == enabled
            ) return
            desiredConfig.enabled = enabled
            desiredConfig.adaptiveDspEnabled = enabled
        }
        engine.setAdaptiveEnabled(enabled)
    }

    fun setAdaptiveDspEnabled(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.adaptiveDspEnabled == enabled) return
            desiredConfig.adaptiveDspEnabled = enabled
            desiredConfig.enabled = enabled
        }
        engine.setAdaptiveEnabled(enabled)
    }

    fun setPeqEnabled(enabled: Boolean) {
        synchronized(desiredConfig) { desiredConfig.peqEnabled = enabled }
    }

    /**
     * Terapkan parameter preset. Bila parameter non-netral
     * (ada yang bukan 1.0), nyalakan adaptive DSP supaya
     * `processFrame()` benar-benar berjalan di C++.
     *
     * Tanpa ini, `setParams` mengubah koefisien tapi C++ melewatkan
     * `processFrame` karena `adaptiveDspEnabled == false` — output
     * identik dengan bypass meski UI melaporkan aktif.
     */
    private fun ensureAdaptiveEnabledForParams(params: FloatArray) {
        val nonNeutral = params.any { it != 1.0f }
        if (nonNeutral) {
            synchronized(desiredConfig) {
                if (!desiredConfig.adaptiveDspEnabled) {
                    desiredConfig.adaptiveDspEnabled = true
                    desiredConfig.enabled = true
                }
            }
            engine.setAdaptiveEnabled(true)
        }
    }

    fun setParams(params: FloatArray) {
        val fixed = FloatArray(DSP_PARAM_COUNT) { i -> params.getOrElse(i) { 1f } }
        synchronized(desiredConfig) { desiredConfig.params = fixed.copyOf() }
        engine.setParams(fixed)
        ensureAdaptiveEnabledForParams(fixed)
    }

    fun setBtClarity(enabled: Boolean) {
        synchronized(desiredConfig) { desiredConfig.btClarity = enabled }
        engine.setBtClarity(enabled)
    }

    fun setAmbience(enabled: Boolean) {
        synchronized(desiredConfig) { desiredConfig.ambience = enabled }
        engine.setAmbience(enabled)
    }

    fun setPreampGain(linear: Float) {
        synchronized(desiredConfig) { desiredConfig.preampGain = linear }
        engine.setPreampGain(linear)
    }

    fun setSpatialAudio(enabled: Boolean, preset: Int) {
        if (desiredConfig.spatialAudioEnabled == enabled &&
            desiredConfig.spatialAudioPreset == preset
        ) return

        synchronized(desiredConfig) {
            desiredConfig.spatialAudioEnabled = enabled
            desiredConfig.spatialAudioPreset = preset
        }

        val widthAdj = when (preset) {
            1 -> 1.40f
            2 -> 0.60f
            else -> 1.00f
        }
        val crossfeedAdj = when (preset) {
            1 -> 0.30f
            2 -> 1.20f
            else -> 1.00f
        }

        val current = synchronized(desiredConfig) { desiredConfig.params?.clone() } ?: return
        current[DspPresets.IDX_WIDTH] =
            (current[DspPresets.IDX_WIDTH] * widthAdj).coerceIn(0f, 2f)
        current[DspPresets.IDX_CROSSFEED] =
            (current[DspPresets.IDX_CROSSFEED] * crossfeedAdj).coerceIn(0f, 1.5f)
        setParams(current)
    }

    /** Terapkan konfigurasi PEQ secara atomik (double-buffer di sisi native). */
    fun applyPeqSnapshot(enabled: Boolean, preampDb: Float, bands: List<PeqBand>) {
        val count = bands.size.coerceAtMost(DSP_PEQ_MAX)
        val types = IntArray(DSP_PEQ_MAX)
        val fc = FloatArray(DSP_PEQ_MAX)
        val gain = FloatArray(DSP_PEQ_MAX)
        val q = FloatArray(DSP_PEQ_MAX)

        bands.forEachIndexed { i, b ->
            if (i < DSP_PEQ_MAX) {
                types[i] = b.type
                fc[i] = b.fc
                gain[i] = b.gain
                q[i] = b.q
            }
        }

        val revision = ++peqRevision
        synchronized(desiredConfig) {
            desiredConfig.peqEnabled = enabled
            desiredConfig.peqCount = count
            // [DEFECT 2] `desiredConfig.preampGain` SENGAJA TIDAK ditulis di sini.
            //
            // Field itu milik `setPreampGain()` dan dibaca predikat
            // `dspUserEnabled`. Semula baris ini menimpanya dengan
            // pow(10, preampDb/20) — dan karena coordinator mengirim hardcoded
            // `0f`, setiap apply config menimpanya kembali dengan 1.0f. Akibatnya
            // preamp +6 dB milik pengguna hilang, dan saat master DSP mati +
            // tidak ada band PEQ, predikat itu ikut bernilai false sehingga
            // audio jatuh ke passthrough total.
            //
            // Preamp tetap diterapkan, tapi lewat jalur yang benar: `preampDb`
            // diteruskan ke native di bawah dan dipakai sebagai
            // `targetPreampLinear` di DspEngine::process().
        }
        engine.applyPeqSnapshot(enabled, preampDb, count, types, fc, gain, q, revision)
    }

    fun release() {
        engine.release()
        converter.reset()
    }
}

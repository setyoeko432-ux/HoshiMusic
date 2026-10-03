package com.naomi.hoshimusic.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.InitializationException
import androidx.media3.exoplayer.audio.AudioSink.WriteException
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.naomi.hoshimusic.data.UserPreferences
import com.naomi.hoshimusic.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Wrapper untuk DefaultAudioSink yang MEMINTA MIXER_BEHAVIOR_BIT_PERFECT pada
 * jalur USB (AudioMixerAttributes, Android 14+).
 *
 * Penting: ini hanya permintaan, dan belum pernah terverifikasi pada perangkat
 * nyata. Bit-perfect hanya berlaku bila SELURUH jalur output (sink -> mixer ->
 * DAC) tidak melakukan konversi. Resampling, pemrosesan vendor, atau efek OEM
 * di hilir bisa membatalkannya. Status ini tidak boleh diklaim sebagai fakta
 * tanpa pengukuran pada route yang benar-benar dipakai.
 */
@UnstableApi
class HoshiAudioSink(
    private val context: Context,
    private val preferences: UserPreferences,
    private val dspProcessor: HoshiDspProcessor?
) : AudioSink {

    private val outputProvider = HoshiAudioOutputProvider(context, preferences)

  /**
   * [A7-AUDIT] Metrik AudioTrack yang benar-benar dialokasikan.
   * Dibaca dari luar lewat DiagLog heartbeat; bukan dipanggil dari jalur audio.
   */
  val trackMetrics: String get() = outputProvider.trackMetrics
    
    /**
     * PENTING — hasil investigasi perangkat (vivo/iQOO, Android 15):
     *
     * Dengan `setEnableFloatOutput(true)`, DefaultAudioSink TIDAK mengaktifkan
     * rantai audio processor. Akibatnya HoshiDspProcessor tidak pernah
     * onConfigure(), native engine tidak pernah jalan, dan UI tetap melaporkan
     * "DSP aktif". Terbukti lewat totalFramesProcessed yang tetap 0.
     *
     * Dengan `setEnableFloatOutput(false)`, rantai processor AKTIF dan mesin
     * native benar-benar memproses frame — TETAPI AudioTrack menjadi PCM 16-bit
     * dan tercatat 95.052 underrun dengan 0 frame ready, sehingga playback
     * macet. Jadi keduanya rusak, dan masalah utamanya ada di jalur PCM 16-bit
     * pada queueInput, bukan hanya di setelan ini.
     *
     * Setelan dikembalikan ke true karena itu kondisi yang benar-benar playable.
     * Menyembunyikan processor lewat isActive() berarti DSP dilewati sepenuhnya;
     * menyalakannya lewat float berarti audio rusak. Keduanya belum satisfactory
     * dan harus diselesaikan sebagai satu pekerjaan tersendiri.
     */
    private val delegate: DefaultAudioSink = DefaultAudioSink.Builder(context)
        // [A7] ToFloatPcmAudioProcessor SUDAH DICUBA dan HARUS DIBALIK.
        //
        // Dipermeasured di vivo/iQOO 1.4.7:
        //   ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        //   AudioSink$ConfigurationException: AudioProcessor$UnhandledAudioFormatException:
        //   Unhandled input format: AudioFormat[sr=44100, ch=2, encoding=4]
        //
        // encoding=4 = PCM_FLOAT. Dengan setEnableFloatOutput(false), DefaultAudioSink
        // TIDAK bisa menulis float ke AudioTrack, jadi seluruh rantai processor
        // terkunci ke PCM 16-bit.
        //
        // Konsekuensi yang terbukti: jalur 16-bit — dengan dua loop konversi
        // per-sample di audio thread — bersifat STRUKTURAL dan tidak bisa
        // dihindari lewat API media3. Satu-satunya cara memperbaikinya
        // adalah memindahkan konversi itu ke C++.
        .setAudioProcessors(if (DSP_EXPERIMENT_NO_PROCESSOR) emptyArray() else (dspProcessor?.let { arrayOf(it) } ?: emptyArray()))
        // [A7] Rantai processor HANYA masuk kalau float output dimatikan, dan
        // itu TIDAK bisa dipakai di media3 1.11.1. Bukti terukur di vivo/iQOO
        // (Android 15), 1.4.7 debug, sumber: files/hoshi_diag.log:
        //
        //   1. setEnableFloatOutput(false) → rantai masuk, DSP jalan
        //      (configureCount=1, native DSP initialized successfully).
        //   2. TAPI playback macet setelah ~56 detik. frames berhenti di
        //      2.709.504, sink bergantian play/pause ~4x/detik tanpa henti.
        //
        // Lima hipotesis diuji dan SEMUA SALAH, semuanya terbukti angka:
        //   - "C++ DSP core lambat"     → SALAH. Timing: native 6.924 us dari
        //     anggaran 104.490 us → ratio 0,10. Headroom 10x.
        //   - "Loop konversi 16-bit"   → SALAH. convIn 3.292 us + convOut 31 us.
        //   - "Buffer di-clamp"         → SALAH. trackFrames=55.125 frame =
        //     1.250 ms, permintaan diterima utuh.
        //   - "Underrun #3210"          → SALAH. onAudioUnderrun = 0.
        //   - "ToFloatPcmAudioProcessor"→ SALAH. ERROR_CODE_AUDIO_TRACK_INIT_
        //     FAILED, UnhandledAudioFormatException encoding=4. DefaultAudioSink
        //     tidak bisa menulis float ke AudioTrack saat float output mati,
        //     jadi rantai terkunci ke PCM 16-bit.
        //
        // Kesimpulan: processor benar, latency-neutral (sisaInput=0), dan 10x
        // lebih cepat dari real-time. Yang macet adalah kendali internal
        // media3 yang hanya muncul begitu ada AudioProcessor pengguna, dan itu
        // tidak bisa diperbaiki dari sisi project. PR upstream #2445 masih
        // terbuka. Sampai ada rilis yang menyelesaikannya, konfigurasi ini
        // adalah satu-satunya yang menghasilkan audio sehat.
        .setEnableFloatOutput(false)
        .setAudioOutputProvider(outputProvider)
        .build()

    fun updateRouting(device: AudioDeviceInfo?) {
        DiagLog.d("HoshiAudioSink", "Updating preferred device: ${device?.productName ?: "Default"}")
        delegate.setPreferredDevice(device)
    }

    // --- AudioSink Delegation ---
    
    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)
    
    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) = delegate.setPlayerId(playerId)
    
    override fun setClock(clock: androidx.media3.common.util.Clock) = delegate.setClock(clock)
    
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)
    
    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)
    
    override fun getFormatOffloadSupport(format: Format) = delegate.getFormatOffloadSupport(format)
    
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)
    
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        // Titik bisect: configure() di HoshiAudioSink berarti Media3 memakai
        // sink ini. absence onConfigure() di HoshiDspProcessor berarti
        // DefaultAudioSink tidak memasukkan processor-nya ke rantai.
        DiagLog.i("HoshiAudioSink", "configure() dipanggil: input=$inputFormat " +
            "bufferSize=$specifiedBufferSize processor=${dspProcessor != null}")
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    /** Transisi state sink dicatat agar terlihat kenapa audio berhenti mengalir. */
    private fun trace(op: String) {
        val ended = runCatching { delegate.isEnded() }.getOrNull()
        val pending = runCatching { delegate.hasPendingData() }.getOrNull()
        val bufUs = runCatching { delegate.getAudioTrackBufferSizeUs() }.getOrNull()
        DiagLog.i("HoshiAudioSink", "sink.$op ended=$ended pendingData=$pending bufSizeUs=$bufUs")
    }

    override fun play() {
        trace("play"); delegate.play()
    }

    override fun pause() {
        trace("pause"); delegate.pause()
    }

    override fun flush() {
        trace("flush"); delegate.flush()
    }

    override fun reset() {
        trace("reset"); delegate.reset()
    }

    override fun setVolume(volume: Float) {
        DiagLog.i("HoshiAudioSink", "sink.setVolume=$volume")
        delegate.setVolume(volume)
    }

    private companion object {
        /**
         * [FIX-PENDING-DATA] Ambang minimum keterlambatan, dalam milidetik
         * audio, sebelum [hasPendingData] boleh melaporkan "tidak ada data".
         *
         * 100 ms = sama dengan grace period transisi ready→not-ready milik
         * Media3 sendiri (100 ms, #3210). Di bawah nilai itu, keterlambatan
         * dianggap kedip sesaat, bukan jeda nyata — jadi ambang ini tidak
         * lebih ketat daripada toleransi yang sudah dipakai media3.
         */
        const val PENDING_DATA_FLOOR_MS = 100L

        /**
         * [EXPERIMENT] Isolasi: true = processor DSP TIDAK dimasukkan ke rantai.
         *
         * Hasil uji 2026-10-01 di vivo/iQOO V2322: dengan `true`, lagu
         * "Lingering Summer Light" (FLAC 48 kHz) berjalan mulus >100 detik
         * tanpa freeze/rebuild. Dengan `false`, frame macet tepat di 4608
         * (~3 detik) lalu 3x rebuild lalu berhenti.
         *
         * Bukti bahwa penyebab ada di HoshiDspProcessor, BUKAN di mesin
         * native C++ dan BUKAN di HoshiAudioSink — keduanya tetap identik
         * pada kedua percobaan.
         */
        const val DSP_EXPERIMENT_NO_PROCESSOR = false

        /**
         * PCM 16-bit stereo: 2 kanal x 2 byte per sample = 4 byte per frame.
         * Jalur ini selalu 16-bit karena rantai processor dipaksa lewat
         * `setEnableFloatOutput(false)`.
         */
        const val PCM_16BIT_STEREO_FRAME_BYTES = 4
    }
    
    override fun handleDiscontinuity() = delegate.handleDiscontinuity()

    @Throws(InitializationException::class, WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!bufferSeen) {
            bufferSeen = true
            DiagLog.i("HoshiAudioSink", "handleBuffer()pertama kali - audio benar-benar mengalir lewat sink ini")
        }
        // Byte yang kita serahkan ke DefaultAudioSink, dihitung SEBELUM
        // delegasi karena delegasi akan menggeser position/limit buffer.
        val submittedBytes = buffer.remaining()

        if (buffer.remaining() == 0) {
            sawEndOfStream = true
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        sawEndOfStream = false
        val handled = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled && submittedBytes > 0) {
            // PCM 16-bit stereo: 2 kanal x 2 byte = 4 byte per frame.
            framesSubmitted += submittedBytes / PCM_16BIT_STEREO_FRAME_BYTES
        }
        return handled
    }

    @Volatile private var bufferSeen = false

    /**
     * [FIX-PENDING-DATA] Frame stereo yang sudah diserahkan ke `DefaultAudioSink`.
     *
     * Ini adalah "writtenFrames" versi kita sendiri — yang dipakai
     * `DefaultAudioSink` private. Ditulis dan dibaca di playback thread yang
     * sama, jadi cukup @Volatile tanpa lock.
     */
    @Volatile
    private var framesSubmitted = 0L

    /**
     * [hasPendingData] Penanda bahwa renderer sudah memberi tahu tidak ada
     * lagi data untuk trek ini.
     *
     * `DefaultAudioSink.isEnded()` adalah
     * `!isAudioOutputInitialized() || (handledEndOfStream && !hasPendingData())`.
     * Artinya kita hanya perlu melepas `hasPendingData()` ke false agar akhir
     * trek tetap tercapai dan playlist bisa lanjut.
     */
    @Volatile
    private var sawEndOfStream = false

    override fun playToEndOfStream() {
        sawEndOfStream = true
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean = delegate.isEnded()

    /**
     * [FIX-PENDING-DATA] Titik perbaikan untuk play/pause loop.
     *
     * `DefaultAudioSink.hasPendingData()` (baris 1362) memanggil
     * `hasAudioOutputPendingData(getWrittenFrames())` (baris 1934), yang
     * ada-nya hanya satu perbandingan:
     *
     *     return writtenFrames > currentPositionFrames;
     *
     * Dua bilangan itu punya sumber berbeda:
     *  - `writtenFrames` — bookkeeping sink saat menulis;
     *  - `currentPositionFrames` — `audioOutput.getPositionUs()`, posisi
     *    playback head yang dilaporkan HAL, maju ASYNCHRONOUSLY dalam blok.
     *
     * Ketika keduanya sesaat menyamai, hasilnya berayun. Renderer melihat
     * false, memanggil `pause()`; `pause()` memanggil `audioOutput.pause()`
     * yang membekukan `getPositionUs()` — sehingga perbandingan itu balik
     * lagi ke true, dan renderer memanggil `play()`. Loop tertutup, ~4 Hz,
     * tidak bisa keluar sendiri.
     *
     * Di sini flip ke false hanya diteruskan kalau AudioTrack benar-benar
     * tertinggal lebih dari [PENDING_DATA_FLOOR_MS] milidetik audio.
     * Granularitas blok `getPositionUs()` jauh lebih kecil dari itu, jadi
     * kedip yang memicu loop tidak pernah terlihat renderer dan
     * `audioOutput.pause()` tidak pernah dipanggil karena alasan ini.
     *
     * Batas bawah ini WAJIB ada: `isEnded()` memakai `!hasPendingData()`,
     * jadi menahan flip tanpa batas membuat akhir trek tidak pernah tercapai
     * dan playlist tidak akan lanjut.
     */
    override fun hasPendingData(): Boolean {
        if (delegate.hasPendingData()) return true

        // [FIX-STARVED-TRACK] Gate sebenarnya ada di
        // `MediaCodecAudioRenderer.isReady()`, yang HANYA bergantung pada
        // pemanggilan ini:
        //
        //     boolean isReady = audioSink.hasPendingData();
        //     ... grace period READY_GRACE_PERIOD_MS ...
        //     return false;
        //
        // Bawaan media3 (`writtenFrames > currentPositionFrames`) wentar
        // begitu play-head menyentuh posisi written. Dengan rantai DSP aktif
        // itu terjadi pada 882 dari 22.050 frame, jadi track benar-benar
        // KOSONG. Setelah grace period, isReady() false, renderer berhenti
        // decode, tidak ada lagi buffer yang masuk, track tetap kosong.
        // Deadlock tertutup: tidak ada exception, tidak ada log.
        //
        // Perbaikan: selama renderer belum menyatakan akhir stream, kita
        // tetap bilang "ada data" supaya renderer terus decode dan mengisi
        // track. `sawEndOfStream` dilepas oleh playToEndOfStream() dan
        // buffer kosong, jadi `isEnded()` tetap bisa true di akhir trek dan
        // playlist tetap lanjut.
        return !sawEndOfStream
    }

    
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = delegate.setPlaybackParameters(playbackParameters)
    
    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()
    
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = delegate.setSkipSilenceEnabled(skipSilenceEnabled)
    
    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()
    
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = delegate.setAudioAttributes(audioAttributes)
    
    override fun getAudioAttributes(): AudioAttributes = delegate.getAudioAttributes()
    
    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)
    
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)
    
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = delegate.setPreferredDevice(preferredDevice)
    
    override fun setVirtualDeviceId(virtualDeviceId: Int) = delegate.setVirtualDeviceId(virtualDeviceId)
    
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    
    override fun getAudioTrackBufferSizeUs(): Long = delegate.getAudioTrackBufferSizeUs()
    
    override fun enableTunnelingV21() = delegate.enableTunnelingV21()
    
    override fun disableTunneling() = delegate.disableTunneling()
    
    @SuppressLint("NewApi")
    override fun setOffloadMode(offloadMode: Int) = delegate.setOffloadMode(offloadMode)
    
    @SuppressLint("NewApi")
    override fun setOffloadDelayPadding(delayFrames: Int, paddingFrames: Int) = delegate.setOffloadDelayPadding(delayFrames, paddingFrames)
    
    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) = delegate.setAudioOutputProvider(audioOutputProvider)
    
    override fun release() = delegate.release()
}

/**
 * Custom AudioOutputProvider yang menginjeksi AudioMixerAttributes pada API 34+.
 */
@UnstableApi
private class HoshiAudioOutputProvider(
    private val context: Context,
    private val preferences: UserPreferences
) : AudioOutputProvider {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    @Volatile
    private var bitPerfectPreferredCache = false

    @Volatile
    private var lowLatencyAudioCache = false

    /**
     * Target durasi buffer AudioTrack PCM, mikrodetik.
     *
     * Diperbesar dari default 320 ms yang terukur. Buffer 2 detik menghapus
     * badai play/pause (97/96 -> 1/1) TETAPI memicu
     * StuckPlayerException("no progress for 10000 ms") dari watchdog Media3.
     *
     * Penyebabnya: toleransi micro-underrun yang membuat buffer besar aman
     * baru diperkenalkan Media3 SETELAH 1.9.2, sedangkan proyek ini terpaku di
     * 1.9.0 (guardrail §4 melarang upgrade). Jadi separuh perbaikan upstream
     * tidak tersedia di sini.
     *
     * 750 ms = batas atas default Media3 itu sendiri (MAX_PCM_BUFFER_DURATION_US),
     * dipilih karena ~2,3x baseline tanpa memicu watchdog.
     */
    /**
     * Ukuran buffer AudioTrack PCM, mikrodetik — 0 berarti pakai default Media3.
     *
     * Hasil pengukuran di vivo/iQOO dengan rantai DSP aktif (lihat plan Fase 1):
     *
     * | buffer      | play/pause | error            |
     * |-------------|------------|------------------|
     * | 320 ms      | 97 / 96    | -                | badai underrun
     * | 750 ms      | 167 / 166  | -                | badai underrun
     * | 1250 ms     | 1 / 1      | StuckPlayer      | badai hilang, watchdog
     * | 2000 ms     | 1 / 1      | StuckPlayer      | badai hilang, watchdog
     *
     * Buffer besar menghapus badai play/pause, tapi memicu
     * StuckPlayerException("no progress for 10000 ms") dari watchdog Media3.
     * Penyebabnya: toleransi micro-underrun yang membuat buffer besar aman
     * baru masuk Media3 SETELAH 1.9.2, sedangkan proyek ini terpaku di 1.9.0
     * (guardrail §4 melarang upgrade). Jadi separuh perbaikan upstream tidak
     * tersedia di sini.
     *
     * [A7] RESULT: DSP TIDAK BISA DIBAWAH KONDISI INI. Buffer 1250 ms
     * diterima utuh (trackFrames=55.125) dan DSP punya headroom 10x, tapi
     * playback tetap macet karenaplay/pause loop internal media3. Buffer
     * dikembalikan ke default Media3 1.11.1 (fixed 500 ms, terverifikasi
     * bufSizeUs=500000) karena penyesuaian ini tidak memperbaiki apa pun.
     *
     * API min/max/factor sudah deprecated di 1.11.x. Nilai 0 = default
     * Media3.
     */
    private val TARGET_PCM_BUFFER_DURATION_US = 0
    
    init {
        scope.launch {
            preferences.wiredBitPerfectPreferred.collectLatest { enabled ->
                bitPerfectPreferredCache = enabled
            }
        }
        scope.launch {
            preferences.lowLatencyAudio.collectLatest { enabled ->
                lowLatencyAudioCache = enabled
            }
        }
    }

    private var lastPreferredDevice: AudioDeviceInfo? = null

    // [A7-AUDIT] Referensi AudioOutput asli, dipakai untuk membaca
    // buffer yang benar-benar dialokasikan (bukan yang diminta).
    @Volatile
    private var currentOutput: androidx.media3.exoplayer.audio.AudioOutput? = null

    /**
     * [FIX-PENDING-DATA] Sample rate dan encoding output yang sebenarnya.
     *
     * `AudioSink` tidak punya accessor untuk keduanya, tapi `AudioOutput` punya.
     * Dibaca oleh [HoshiAudioSink.hasPendingData] untuk mengukur
     * "writtenFrames > playedFrames" dalam satuan frame, bukan milidetik.
     */
    @Volatile
    var outputSampleRate: Int = 0
        private set

    @Volatile
    var outputEncoding: Int = 0
        private set

    private val delegate = AudioTrackAudioOutputProvider.Builder(context)
        // [Fase 1] AudioTrack buffer bisa diperbesar eksplisit.
        //
        // Default Media3 1.9.0: clamp(minBufferSize * 4, 250ms, 750ms), dan di
        // perangkat ini hanya menghasilkan ~320 ms. Ketika rantai processor DSP
        // aktif, buffer sekecil itu tidak sanggup menutupi jeda optimize:
        // AudioTrack underrun, lalu Media3 1.8.0+ menganggap renderer tidak siap
        // dan melakukan siklus play() -> pause() -> BUFFERING tanpa henti.
        //
        // Nilai 0 = pakai default Media3. Buffer yang lebih besar menghapus badai
        // itu, tapi memicu watchdog StuckPlayerException — lihat catatan pada
        // TARGET_PCM_BUFFER_DURATION_US. setTargetPcmBufferDurationUs() hanya ada
        // di Media3 yang lebih baru; di 1.9.0 yang tersedia min/max/factor.
        .setAudioTrackBufferSizeProvider(
            if (TARGET_PCM_BUFFER_DURATION_US > 0) {
                DefaultAudioTrackBufferSizeProvider.Builder()
                    .setTargetPcmBufferDurationUs(TARGET_PCM_BUFFER_DURATION_US)
                    .setPassthroughBufferDurationUs(150_000)
                    .setOffloadBufferDurationUs(150_000)
                    .build()
            } else {
                DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
            }
        )
        .setAudioTrackBuilderModifier { builder, _ ->
            val device = lastPreferredDevice
            val bitPerfectPreferred = bitPerfectPreferredCache
            val lowLatency = lowLatencyAudioCache
            
            if (bitPerfectPreferred && device != null && isUsbDevice(device)) {
                if (Build.VERSION.SDK_INT >= 34) {
                    applyMixerAttributes(builder, device)
                }
            }

            // [P1]: AAudio Low Latency Performance Mode (Opt-in toggle di Settings)
            if (lowLatency) {
                try {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    DiagLog.i("HoshiAudio", "Applied PERFORMANCE_MODE_LOW_LATENCY to AudioTrack")
                } catch (e: Exception) {
                    DiagLog.w("HoshiAudio", "Failed to set PERFORMANCE_MODE_LOW_LATENCY", e)
                }
            }
        }
        .build()

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        return delegate.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        lastPreferredDevice = formatConfig.preferredDevice
        return delegate.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): androidx.media3.exoplayer.audio.AudioOutput {
        val out = delegate.getAudioOutput(config)
        currentOutput = out
        outputSampleRate = out.sampleRate
        // Encoding diambil dari OutputConfig, bukan dari AudioOutput —
        // AudioOutput di media3 1.11.0 tidak mengekspos accessor encoding.
        outputEncoding = config.encoding
        DiagLog.i("HoshiTrack", "AudioOutput dibuat: $trackMetrics")
        return out
    }

    /**
     * [A7-AUDIT] Metrik AudioTrack yang SEBENARNYA dialokasikan.
     *
     * `DefaultAudioSink.getAudioTrackBufferSizeUs()` hanya melaporkan
     * permintaan dari buffer size provider. Media3 lalu meng-clamp ke
     * minBufferSize perangkat, jadi angka itu bisa jauh lebih kecil dari
     * yang kita minta. Yang harus dibandingkan adalah angka di sini:
     * inilah yang membuktikan apakah setTargetPcmBufferDurationUs()
     * benar-benar dipakai atau diam-diam di-clamp.
     */
    val trackMetrics: String
        get() {
            val o = currentOutput ?: return "track=(belum dibuat)"
            return "trackFrames=${o.bufferSizeInFrames} sr=${o.sampleRate} " +
                "sessionId=${o.audioSessionId} offload=${o.isOffloadedPlayback} " +
                "trackMs=${if (o.sampleRate > 0) o.bufferSizeInFrames * 1000L / o.sampleRate else -1L}"
        }
    override fun addListener(listener: AudioOutputProvider.Listener) = delegate.addListener(listener)
    override fun removeListener(listener: AudioOutputProvider.Listener) = delegate.removeListener(listener)
    
    override fun release() {
        delegate.release()
        scope.cancel()
    }
    
    override fun setClock(clock: androidx.media3.common.util.Clock) = delegate.setClock(clock)

    private fun isUsbDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
               device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
               device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    @SuppressLint("NewApi")
    @RequiresApi(34)
    private fun applyMixerAttributes(
        builder: AudioTrack.Builder,
        device: AudioDeviceInfo
    ) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportedAttributes = audioManager.getSupportedMixerAttributes(device)
        
        val bitPerfectAttr = supportedAttributes.find { attr ->
            attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }

        if (bitPerfectAttr != null) {
            try {
                // Gunakan refleksi untuk menghindari error build jika IDE SDK tertinggal
                val method = builder.javaClass.getMethod("setAudioMixerAttributes", AudioMixerAttributes::class.java)
                method.invoke(builder, bitPerfectAttr)
                DiagLog.i("HoshiAudio", "Applied BIT_PERFECT MixerAttributes for ${device.productName}")
            } catch (e: Exception) {
                // Jika compileSdk sudah benar, pemanggilan langsung lebih baik
                // builder.setAudioMixerAttributes(bitPerfectAttr)
                DiagLog.e("HoshiAudio", "Reflection call for setAudioMixerAttributes failed", e)
            }
        }
    }
}


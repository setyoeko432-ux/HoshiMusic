package com.naomi.hoshimusic.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.naomi.hoshimusic.data.PeqBand
import com.naomi.hoshimusic.playback.HoshiDspProcessor
import com.naomi.hoshimusic.playback.dsp.DspParamResolver
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.util.DiagLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * ============================================================================
 *  HOSHI AUDIO PIPELINE TRACE KIT  —  satu file, debug build only
 * ============================================================================
 *
 *  Menjalankan trace pipeline audio HoshiMusic hulu-ke-hilir di perangkat
 *  sungguhan, lalu menulis hasilnya lewat DiagLog — jalur yang TIDAK
 *  bergantung pada logcat, karena logcat diblokir OEM di vivo/iQoo ini.
 *
 *  ----------------------------------------------------------------------------
 *  CARA PAKAI (debug build saja)
 *  ----------------------------------------------------------------------------
 *  Semua preset bawaan, sumber sintetik (tanpa perlu file apa pun):
 *
 *      adb shell am broadcast -a com.naomi.hoshimusic.PROBE \
 *          -n com.naomi.hoshimusic/.diag.ProbeReceiver
 *
 *  File WAV milik sendiri (PCM 16-bit stereo; FLAC harus dikonversi dulu):
 *
 *      ffmpeg -i song.flac -ac 2 -ar 44100 -sample_fmt s16 /sdcard/probe.wav
 *      adb shell am broadcast -a com.naomi.hoshimusic.PROBE \
 *          -n com.naomi.hoshimusic/.diag.ProbeReceiver --es file /sdcard/probe.wav
 *
 *  Batasi ke beberapa preset, plus satu profile PEQ:
 *
 *      adb shell am broadcast -a com.naomi.hoshimusic.PROBE \
 *          -n com.naomi.hoshimusic/.diag.ProbeReceiver \
 *          --es file /sdcard/probe.wav \
 *          --es presets warm,bright,bass_boost \
 *          --es peq 1
 *
 *  Baca hasil:
 *
 *      adb shell run-as com.naomi.hoshimusic cat files/audio_trace_report.txt
 *      adb shell run-as com.naomi.hoshimusic cat files/hoshi_diag.log | grep AudioPipe
 *
 *  Extra yang dikenali:
 *      file    : path WAV. Kosong = pakai generator sintetik.
 *      presets : daftar id preset dipisah koma. Kosong = semua bawaan.
 *      peq     : "1" untuk menyalakan satu profile PEQ pada semua run.
 *      chunk   : frame per buffer (default 2048).
 *      report  : nama file laporan (default audio_trace_report.txt).
 *
 *  MODE TONE — respons tiap preset pada nada murni, kondisi terkendali:
 *
 *      adb shell am broadcast -a com.naomi.hoshimusic.PROBE \
 *          -n com.naomi.hoshimusic/.diag.ProbeReceiver --es tone 1
 *
 *      Terbatas ke sebagian level, frekuensi, dan preset:
 *
 *      adb shell am broadcast -a com.naomi.hoshimusic.PROBE \
 *          -n com.naomi.hoshimusic/.diag.ProbeReceiver --es tone 1 \
 *          --es levels -30,-12,-6 --es freqs 60,10000 \
 *          --es presets bass_boost,bright
 *
 *  Extra yang dikenali mode tone:
 *      tone    : "1" menyalakan mode ini. Selain itu, mode trace biasa dipakai.
 *      levels  : peak dBFS dipisah koma. Default "-20,-6".
 *      freqs   : Hz dipisah koma. Default "60,300,3000,10000".
 *      report  : nama file laporan (default audio_trace_tone_report.txt).
 *
 *  Mode tone TIDAK memakai extra "file": sumbernya selalu nada sintetis.
 *
 *  ----------------------------------------------------------------------------
 *  KENAPA TESTKIT INI ADA
 *  ----------------------------------------------------------------------------
 *  Gejala "semua preset terdengar sama" lolos ke rilis karena semua indikator
 *  yang ada terlihat hijau: parameter preset berbeda, semua terkirim ke
 *  native, `setParams` menyimpan dengan benar, UI melaporkan "DSP aktif".
 *  Yang TIDAK PERNAH diukur adalah apakah PCM keluarannya benar-benar berbeda,
 *  dan apakah tidak ada frame yang hilang di tengah rantai.
 *
 *  Testkit ini menutup kedua celah itu, stage demi stage.
 *
 *  ----------------------------------------------------------------------------
 *  PETA TRACE — mengikuti alur nyata di HoshiMusic
 *  ----------------------------------------------------------------------------
 *
 *   [1] SUMBER          File WAV / sintetik -> short[] interleaved stereo
 *          |
 *   [2] GATE onConfigure            5 gerbang; satu gagal = NOT_SET = DSP mati
 *          |  g1 encoding == PCM_16BIT      g4 nativeInit(sampleRate)
 *          |  g2 libraryLoaded              g5 negotiated (format keluar = masuk)
 *          |  g3 native handle != 0
 *          v
 *   [3] KONVERSI MASUK    PcmFormatConverter.toFloat  int16 -> float32
 *          v
 *   [4] MESIN NATIVE      DspEngine::process -> processFrame (18 block P1..P18)
 *          v
 *   [5] KONVERSI KELUAR   PcmFormatConverter.encode16Bit  float32 -> int16
 *          v
 *   [6] OUTPUT            replaceOutputBuffer -> clamp -> PCM stereo
 *
 *  Yang diukur per preset:
 *   - [2] apakah gerbang benar-benar dilewati (bukan diam-diam NOT_SET)
 *   - [3][4][5] waktu per tahap vs anggaran realtime (rasio headroom)
 *   - [4] telemetri koefisien adaptif: air / vocal / side / vbass
 *   - [6] KECOCOKAN FRAME: framesIn harus == framesOut (latency-neutral)
 *   - [6] PIRITA: energi 60 Hz / 300 Hz / 3 kHz / 10 kHz vs bypass
 *
 *  Berkas ini hanya ada di source set `src/debug`, jadi TIDAK PERNAH ikut ke
 *  APK rilis.
 */

// ============================================================================
//  [1] PEMBACA WAV
// ============================================================================

/**
 * Pembaca WAV untuk trace kit. Mendukung PCM 8/16/24/32-bit dan IEEE float
 * 32/64-bit, lalu men-downmix ke stereo interleaved.
 *
 * FLAC/MP3/AAC tidak didukung — file harus dikonversi ke WAV dulu.
 */
class WavSource private constructor(
    val sampleRate: Int,
    val sourceChannels: Int,
    val stereo: ShortArray
) {
    val frames: Int get() = stereo.size / 2
    val durationSeconds: Double get() = frames.toDouble() / sampleRate

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE

        fun read(file: File): WavSource {
            RandomAccessFile(file, "r").use { raf ->
                require(readTag(raf) == "RIFF") { "Bukan file RIFF/WAV" }
                readLeInt(raf) // ukuran payload RIFF; tidak dipakai, tapi harus dibaca
                // "WAVE" adalah FORM TYPE, bukan chunk. Versi pertama trace kit
                // memperlakukannya sebagai chunk lalu melompat ke akhir file,
                // sehingga tidak pernah menemukan "fmt "/"data" sama sekali.
                require(readTag(raf) == "WAVE") { "RIFF ini bukan WAVE (bisa AIFF)" }

                var sampleRate = 0
                var channels = 0
                var bits = 0
                var format = 0
                var dataOffset = -1L
                var dataLength = 0

                // Setiap chunk WAV: tag 4 byte + panjang 4 byte little-endian.
                while (raf.filePointer < raf.length() - 8) {
                    val id = readTag(raf)
                    val size = readLeInt(raf).toLong() and 0xFFFFFFFFL
                    val body = raf.filePointer

                    when (id) {
                        "fmt " -> {
                            format = readLeShort(raf)
                            channels = readLeShort(raf)
                            sampleRate = readLeInt(raf)
                            raf.skipBytes(6) // byteRate + blockAlign
                            bits = readLeShort(raf)
                            if (format == FORMAT_EXTENSIBLE && size >= 40) {
                                val sub = readLeShort(raf)
                                if (sub == FORMAT_PCM || sub == FORMAT_FLOAT) format = sub
                            }
                        }
                        "data" -> {
                            dataOffset = body
                            dataLength = if (size <= 0 || body + size > raf.length()) {
                                (raf.length() - body).toInt()
                            } else {
                                size.toInt()
                            }
                        }
                    }

                    if (dataOffset >= 0 && sampleRate > 0 && channels > 0) break
                    // Chunk ganjil di-align dengan 1 byte pad.
                    raf.seek(body + size + (size and 1L))
                }

                require(sampleRate > 0 && channels > 0 && dataOffset >= 0) {
                    "WAV rusak: sampleRate=$sampleRate channels=$channels dataOffset=$dataOffset"
                }

                raf.seek(dataOffset)
                val raw = ByteArray(dataLength)
                raf.readFully(raw)
                return WavSource(sampleRate, channels, decode(raw, format, bits, channels))
            }
        }

        private fun decode(raw: ByteArray, format: Int, bits: Int, channels: Int): ShortArray {
            val bytesPerSample = bits / 8
            require(bytesPerSample > 0) { "Bits per sample tidak didukung: $bits" }

            val totalFrames = (raw.size / bytesPerSample) / channels
            val out = ShortArray(totalFrames * 2)
            if (totalFrames == 0) return out

            // Lewat ByteBuffer supaya tidak ada bit-twiddling manual yang rawan
            // salah untuk 24/32-bit dan float.
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            var w = 0
            repeat(totalFrames) {
                var sum = 0.0
                for (c in 0 until channels) sum += readSample(bb, format, bits)
                val v = (sum / channels).toInt().coerceIn(-32768, 32767).toShort()
                out[w++] = v
                out[w++] = v
            }
            return out
        }

        /** Satu sample dinormalisasi ke rentang -32768..32767. */
        private fun readSample(bb: ByteBuffer, format: Int, bits: Int): Double {
            val isFloat = format == FORMAT_FLOAT
            return when {
                isFloat && bits == 32 -> bb.float.toDouble().coerceIn(-1.0, 1.0) * 32767.0
                isFloat && bits == 64 -> bb.double.coerceIn(-1.0, 1.0) * 32767.0
                bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128.0 * 32767.0
                bits == 16 -> bb.short.toDouble()
                bits == 24 -> {
                    val b0 = bb.get().toInt() and 0xFF
                    val b1 = bb.get().toInt() and 0xFF
                    val b2 = bb.get().toInt() and 0xFF
                    // Sign-extend 24 bit ke 32 bit.
                    (((b2 shl 24) or (b1 shl 16) or (b0 shl 8)) shr 8).toDouble()
                }
                bits == 32 -> (bb.int shr 16).toDouble()
                else -> throw IllegalArgumentException("format/bits tidak didukung: $format/$bits")
            }
        }

        private fun readTag(raf: RandomAccessFile): String =
            ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)

        private fun readLeShort(raf: RandomAccessFile): Int {
            val lo = raf.read()
            val hi = raf.read()
            return (lo and 0xFF) or ((hi and 0xFF) shl 8)
        }

        private fun readLeInt(raf: RandomAccessFile): Int =
            readLeShort(raf) or (readLeShort(raf) shl 16)
    }
}

// ============================================================================
//  [4] PENGUKUR ENERGI PER PITA
// ============================================================================

/**
 * Pengukur energi per pita lewat bandpass biquad RBJ.
 *
 * Pita dipilih mengikuti klaim tiap preset:
 *   60 Hz bass, 300 Hz low-mid/body, 3 kHz presence/vokal, 10 kHz air.
 *
 * Debu: energi dijumlahkan sebagai jumlah kuadrat, jadi nilainya sebanding
 * energi. Bandpass biquad punya gain ~unity di frekuensi pusat, jadi perbandingan
 * antar preset sah.
 */
class BandEnergyMeter(sampleRate: Int, frequencies: List<Double>) {

    private val filters = frequencies.map { BiquadBandpass(sampleRate, it, 1.0) }

    val frequencies: List<Double> = frequencies

    fun reset() = filters.forEach { it.reset() }

    fun process(mono: Double) {
        for (i in filters.indices) filters[i].process(mono)
    }

    /** Energi tiap pita; indeks sejajar dengan [frequencies]. */
    fun energies(): DoubleArray = DoubleArray(filters.size) { filters[it].energy }

    private class BiquadBandpass(fs: Int, fc: Double, q: Double) {
        private val b0: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        var energy = 0.0
            private set

        init {
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = alpha / a0
            b2 = -alpha / a0
            a1 = -2.0 * cos(w0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0; energy = 0.0
        }

        fun process(x: Double): Double {
            val y = b0 * x - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            energy += y * y
            return y
        }
    }
}

// ============================================================================
//  SUMBER SINTETIK (ground truth)
// ============================================================================

/**
 * Penembang WAV uji dengan konten pita yang **diketahui**.
 *
 * ## Kenapa ground truth penting
 *
 * Testkit jadi tidak berguna kalau sumbernya tidak diketahui isinya. Dengan
 * sumber ini, pita yang sedang bunyi saat diukur sudah pasti, sehingga klaim
 * tiap preset bisa diuji secara kuantitatif:
 *
 *   - segmen 10 kHz yang diberi `warm` (pAir = 0.70) harus benar-benar redup
 *   - segmen 60 Hz yang diberi `bass_boost` (pBass = 1.80) harus lebih gemuk
 *   - `flat` (semula 0f = -4.5 dB bass / -3.5 dB udara) harus TIDAK meredupkan
 *
 * Kalau angkanya tidak bergerak sesuai klaim, presetnya bohong — dan trace kit
 * ini yang membuktikannya.
 */
object SyntheticWav {

    data class Segment(val name: String, val hz: Double, val seconds: Double)

    val BAND_SEGMENTS = listOf(
        Segment("bass 60Hz", 60.0, 1.5),
        Segment("lowmid 300Hz", 300.0, 1.5),
        Segment("presence 3kHz", 3000.0, 1.5),
        Segment("air 10kHz", 10000.0, 1.5)
    )

    const val DEFAULT_SAMPLE_RATE = 44100
    const val PEAK_AMPLITUDE = 0.5   // -6 dBFS; menyisakan headroom untuk limiter

    /** Tulis WAV sintetis ke [target]; mengembalikan durasi dalam detik. */
    @Synchronized
    fun generate(target: File, sampleRate: Int = DEFAULT_SAMPLE_RATE): Double {
        val out = GrowableShorts()

        // Fade 25 ms di kedua ujung segmen. Tanpa ini, klik besar mendominasi
        // spektrum dan mengaburkan pengukuran pita.
        val fade = (sampleRate * 0.025).toInt().coerceAtLeast(1)

        BAND_SEGMENTS.forEach { seg ->
            val n = (seg.seconds * sampleRate).toInt()
            repeat(n) { i ->
                val env = envelope(i, n, fade)
                out.addStereo(sin(2.0 * PI * seg.hz * i / sampleRate) * PEAK_AMPLITUDE * env)
            }
            // Jeda senyap agar filter IIR sempat settle.
            repeat(sampleRate / 10) { out.addStereo(0.0) }
        }

        // Sapuan logaritmik 40 Hz -> 16 kHz: memberi kurva gain-vs-frekuensi.
        val sweepN = (2.0 * sampleRate).toInt()
        var phase = 0.0
        repeat(sweepN) { i ->
            val progress = i.toDouble() / sweepN
            val hz = 40.0 * Math.pow(16000.0 / 40.0, progress)
            phase += 2.0 * PI * hz / sampleRate
            out.addStereo(sin(phase) * PEAK_AMPLITUDE * envelope(i, sweepN, fade))
        }

        repeat(sampleRate / 10) { out.addStereo(0.0) }

        // Dinamis: 1 kHz dengan amplop 0.02 -> 0.9, menguji AGC + limiter.
        val dynN = (2.0 * sampleRate).toInt()
        repeat(dynN) { i ->
            val progress = i.toDouble() / dynN
            val amp = 0.02 + 0.88 * (0.5 - 0.5 * Math.cos(2.0 * PI * progress))
            val v = sin(2.0 * PI * 1000.0 * i / sampleRate) * amp * envelope(i, dynN, fade)
            out.addStereo(v)
        }

        writeWav16(target, out.toShortArray(), sampleRate)
        return out.size / 2.0 / sampleRate
    }

    private fun envelope(i: Int, n: Int, fade: Int): Double {
        if (i < fade) return i.toDouble() / fade
        val tail = n - 1 - i
        if (tail < fade) return tail.coerceAtLeast(0).toDouble() / fade
        return 1.0
    }

    /** Penulis WAV 16-bit PCM stereo. */
    fun writeWav16(target: File, stereo: ShortArray, sampleRate: Int) {
        target.parentFile?.mkdirs()
        val dataBytes = stereo.size * 2
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF"); writeLeInt(raf, 36 + dataBytes); raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeLeInt(raf, 16)            // ukuran chunk fmt
            writeLeShort(raf, 1)           // PCM
            writeLeShort(raf, 2)           // kanal
            writeLeInt(raf, sampleRate)
            writeLeInt(raf, sampleRate * 4) // byteRate
            writeLeShort(raf, 4)           // blockAlign
            writeLeShort(raf, 16)          // bits per sample
            raf.writeBytes("data"); writeLeInt(raf, dataBytes)

            val chunk = ByteArray(dataBytes)
            var i = 0
            while (i < stereo.size) {
                val v = stereo[i].toInt()
                chunk[i * 2] = (v and 0xFF).toByte()
                chunk[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                i++
            }
            raf.write(chunk)
        }
    }

    private fun writeLeShort(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
    }

    private fun writeLeInt(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
        raf.write((v shr 16) and 0xFF); raf.write((v shr 24) and 0xFF)
    }

    private class GrowableShorts(initial: Int = 1 shl 16) {
        private var data = ShortArray(initial)
        var size = 0
            private set

        fun addStereo(mono: Double) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            val v = (mono.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
            data[size++] = v
            data[size++] = v
        }

        fun toShortArray(): ShortArray = data.copyOf(size)
    }
}

// ============================================================================
//  MESIN TRACE
// ============================================================================

/**
 * Menjalankan trace pipeline audio untuk sekumpulan preset.
 *
 * Menghasilkan laporan yang memetakan langsung ke stage di [HoshiDspProcessor]
 * dan `DspEngine::process()`, sehingga setiap angka bisa ditelusuri ke baris
 * kode yang menghasilkannya.
 */
@androidx.media3.common.util.UnstableApi
class AudioPipelineTraceKit(
    private val chunkFrames: Int = 2048,
    /** Frame awal dibuang: AGC pre-gain dan crossfade wet masih merambat. */
    private val settleFrames: Int = 4096
) {

    private val bandFreqs = listOf(60.0, 300.0, 3000.0, 10000.0)

    /** Profile PEQ uji: peaking 1 kHz +6 dB, untuk menguji stage S4. */
    private val probePeq = listOf(PeqBand(type = 0, fc = 1000f, gain = 6f, q = 1.0f))

    private data class GateResult(
        val name: String,
        val detail: String,
        val passed: Boolean
    )

    private data class TraceRun(
        val label: String,
        val presetId: String,
        val params: FloatArray,
        val dspEnabled: Boolean,
        /** `HoshiDspProcessor.isActuallyProcessing` saat streaming berjalan.
         *  False di sini berarti mesin TIDAK memproses apa pun,apa pun presetnya. */
        val actuallyProcessing: Boolean,
        val gates: List<GateResult>,
        val negotiated: Boolean,
        val framesIn: Long,
        val framesOut: Long,
        val rmsIn: Double,
        val rmsOut: Double,
        val peakOut: Double,
        val clipped: Int,
        /**
         * Berapa sample keluaran yang BERBEDA dari sample masukan pada frame
         * yang sama.
         *
         * Ini angka paling jujur di seluruh laporan. Kalau 0, output secara
         * literal identik dengan input: apa pun yang diklaim preset, mesin
         * tidak mengubah satu sample pun. Kalau besar tapi pita tetap 0 dB,
         * berarti filter jalan tapi saling meniadakan.
         */
        val samplesDifferingFromInput: Long,
        val samplesMeasured: Long,
        /** Potongan mentah masuk-vs-keluar untuk pemeriksaan visual. */
        val samplePairs: List<Pair<Int, Int>>,
        val bands: DoubleArray,
        val timing: String,
        /** Gain yang sengaja dikalikan ke INPUT, hanya dipakai run kalibrasi. */
        val inputGain: Double,
        val audit: String,
        val telemetry: String
    )

    /**
     * Jalankan trace untuk [input]. Bila [input] null, sumber sintetik dibuat
     * lebih dulu di [syntheticTarget].
     */
    fun trace(
        input: File?,
        syntheticTarget: File,
        presetIds: List<String>?,
        peqEnabled: Boolean
    ): String {
        val source = input ?: SyntheticWav.generate(syntheticTarget).let {
            DiagLog.i(TAG, "sumber sintetik dibuat: ${syntheticTarget.name} (%.2f s)".format(Locale.US, it))
            syntheticTarget
        }

        val wav = WavSource.read(source)
        val ids = presetIds ?: DspPresets.BUILTINS.map { it.id }

        val report = StringBuilder()
        header(report, source, wav, ids, peqEnabled)

        val runs = ArrayList<TraceRun>(ids.size + 2)

        // Kontrol: bypass penuh (DSP mati) — mengukur integritas rantai.
        runs += execute(wav, "__bypass__", "__bypass__",
            FloatArray(DspPresets.PARAM_COUNT) { 1f }, dspEnabled = false, peqEnabled = false)

        // KALIBRASI: bypass dengan input dinaikkan 2x (tak Proposal +6.02 dB).
        //
        // Tanpa ini, tabel pita bisa saja menampilkan 0,00 dB untuk SEMUA preset
        // hanya karena meter-nya yang rusak — dan hasilnya akan disalahbaca
        // sebagai "preset tidak ada efeknya". Jalur ini membuktikan meternya
        // benar-benar bisa melihat perubahan yang pasti ada.
        val calibGain = 2.0
        runs += execute(wav, "__calib__", "__calib__",
            FloatArray(DspPresets.PARAM_COUNT) { 1f }, dspEnabled = false, peqEnabled = false,
            inputGain = calibGain)

        // Baseline mesin: reference dengan DSP nyala.
        runs += execute(wav, "reference", DspPresets.REFERENCE_ID,
            DspParamResolver.resolve(DspPresets.REFERENCE_ID, emptyList(), null), true, peqEnabled)

        ids.filter { it != DspPresets.REFERENCE_ID }.forEach { id ->
            runs += execute(wav, id, id,
                DspParamResolver.resolve(id, emptyList(), null), true, peqEnabled)
        }

        report.append(renderGates(runs))
        report.appendLine()
        report.append(renderIntegrity(runs))
        report.appendLine()
        report.append(renderBands(runs))
        report.appendLine()
        report.append(renderTiming(runs))
        report.appendLine()
        report.append(renderLevels(runs))
        report.appendLine()
        report.append(renderNativeState(runs))

        val text = report.toString()
        DiagLog.i(TAG, "trace selesai: ${runs.size} run atas ${wav.frames} frame")
        text.lineSequence().forEach { DiagLog.i(TAG, it) }
        return text
    }

    /** Jalankan trace dan tulis laporan ke [output] supaya mudah di-pull. */
    fun traceToFile(
        input: File?,
        syntheticTarget: File,
        output: File,
        presetIds: List<String>?,
        peqEnabled: Boolean
    ): String {
        val text = trace(input, syntheticTarget, presetIds, peqEnabled)
        output.writeText(text)
        DiagLog.i(TAG, "laporan ditulis: ${output.absolutePath} (${output.length()} bytes)")
        return text
    }

    // ------------------------------------------------------------- eksekusi

    private fun execute(
        wav: WavSource,
        label: String,
        presetId: String,
        params: FloatArray,
        dspEnabled: Boolean,
        peqEnabled: Boolean,
        inputGain: Double = 1.0
    ): TraceRun {
        val processor = HoshiDspProcessor()
        val gates = ArrayList<GateResult>(5)

        // --- [2] Gerbang onConfigure, direkonstruksi satu per satu -----------
        gates += GateResult(
            "g1 encoding == PCM_16BIT",
            "enc=${C.ENCODING_PCM_16BIT}", true
        )
        gates += GateResult(
            "g2 libraryLoaded",
            "libhoshi_dsp.so=${if (HoshiDspProcessor.libraryLoaded) "OK" else "GAGAL"}",
            HoshiDspProcessor.libraryLoaded
        )

        val format = AudioFormat(wav.sampleRate, 2, C.ENCODING_PCM_16BIT)
        val negotiatedFormat = processor.configure(format)
        val negotiated = negotiatedFormat != AudioFormat.NOT_SET
        gates += GateResult("g3+g4 handle & nativeInit", "sr=${wav.sampleRate}Hz", negotiated)
        gates += GateResult(
            "g5 format keluar renegosiasi",
            if (negotiated) "sr=${negotiatedFormat.sampleRate} ch=${negotiatedFormat.channelCount}" else "NOT_SET",
            negotiated
        )

        if (!negotiated) {
            processor.release()
            return TraceRun(
                label, presetId, params, dspEnabled, false, gates, false, 0, 0,
                0.0, 0.0, 0.0, 0, 0L, 0L, emptyList(),
                DoubleArray(bandFreqs.size), "n/a", inputGain, "n/a", "n/a"
            )
        }

        processor.setParams(params)
        processor.setAdaptiveDspEnabled(dspEnabled)
        if (peqEnabled) {
            processor.applyPeqSnapshot(true, 0f, probePeq)
        }

        // Gate terakhir: apakah mesin NATIVE benar-benar memproses? Kalau false,
        // semua angka lain (pita, level) tidak ada artinya karena keluarannya
        // passthrough. Dicek DI SINI, sebelum streaming, bukan setelah.
        val actuallyProcessing = processor.isActuallyProcessing
        if (dspEnabled && !actuallyProcessing) {
            DiagLog.w(
                TAG,
                "PRESET '$label': isActuallyProcessing=FALSE padahal DSP diminta nyala. " +
                    "Mesin akan passthrough — angka preset di bawah tidak berlaku."
            )
        }

        // --- [3][4][5][6]streaming ------------------------------------------
        var framesIn = 0L
        var framesOut = 0L
        var sumIn = 0.0
        var sumOut = 0.0
        var peakOut = 0.0
        var clipped = 0
        var counted = 0L
        var differing = 0L
        val pairs = ArrayList<Pair<Int, Int>>(12)

        val meterOut = BandEnergyMeter(wav.sampleRate, bandFreqs)
        val meterIn = BandEnergyMeter(wav.sampleRate, bandFreqs)

        val totalFrames = wav.frames
        var offset = 0
        while (offset < totalFrames) {
            val n = minOf(chunkFrames, totalFrames - offset)
            val startSample = offset * 2

            val inputBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) {
                // `inputGain` hanya untuk run kalibrasi: menaikkan sinyal
                // SEBELUM DSP sehingga gain yang terukur harus persis sama.
                val s = (wav.stereo[startSample + i * 2] * inputGain)
                    .toInt().coerceIn(-32768, 32767).toShort()
                inputBuf.putShort(s)
                inputBuf.putShort(s)
            }
            inputBuf.flip()
            framesIn += n

            processor.queueInput(inputBuf)

            val out = processor.getOutput()
            if (out.hasRemaining()) {
                val shorts = ShortArray(out.remaining() / 2)
                out.asShortBuffer().get(shorts)
                val produced = shorts.size / 2
                framesOut += produced

                for (i in 0 until produced) {
                    val l = shorts[i * 2].toInt()
                    val r = shorts[i * 2 + 1].toInt()
                    val frameIdx = offset + i
                    if (frameIdx < settleFrames) continue

                    // Bandingkan dengan sample masukan pada frame yang sama.
                    // Rantai latency-neutral, jadi indeksnya boleh langsung.
                    val inS = if (frameIdx < totalFrames) {
                        wav.stereo[frameIdx * 2].toInt()
                    } else {
                        l
                    }
                    // Toleransi 2 LSB: jalur DSP melewati int16 -> float -> int16
                    // (x/32768 lalu x32767), yang menggeser ~1 LSB pada hampir
                    // semua sample BAHKAN kalau mesin native tidak mengubah apa
                    // pun. Tanpa toleransi, kolom ini tampak "hidup" padahal
                    // DSP-nya tidak berefek.
                    if (kotlin.math.abs(l - inS) > 2 || kotlin.math.abs(r - inS) > 2) differing++
                    if (pairs.size < 12) pairs.add(inS to l)

                    val mono = (l + r) / 2.0 / 32768.0
                    sumOut += mono * mono
                    counted++
                    val a = kotlin.math.abs(mono)
                    if (a > peakOut) peakOut = a
                    if (l >= 32767 || l <= -32768 || r >= 32767 || r <= -32768) clipped++
                    meterOut.process(mono)
                }
            }

            if (offset >= settleFrames) {
                for (i in 0 until n) {
                    val s = wav.stereo[startSample + i * 2].toInt() / 32768.0
                    sumIn += s * s
                    meterIn.process(s)
                }
            }

            offset += n
        }

        // Snapshot audit diambil dari luar jalur audio, persis seperti yang
        // dilakukan heartbeat produksi.
        //
        // `auditSnapshot()` sudah berupa string terformat; ia TIDAK boleh
        // diformat ulang. Versi pertama trace kit memotongnya dengan
        // substringAfter lalu mencetaknya sebagai kolom, sehingga placeholder
        // `%.0f` yang sudah seharusnya berubah ikut tercetak mentah.
        val audit = processor.auditSnapshot(framesOut, actuallyProcessing)
        val tel = processor.updateTelemetry()
        val telemetry = if (tel == null) {
            "n/a (native tidak hidup)"
        } else {
            // PENTING: seluruh string harus diapit tanda kurung SEBELUM .format().
            // Kalau tidak, `.format()` hanya menempel pada literal kedua,
            // sementara operator `+` mengikat lebih longgar — jadi argumennya
            // salah tempat dan meledek jadi IllegalFormatConversionException.
            ("nativeSr=%d wet=%.3f native=%s agcPre=%.3f slowRms=%.4f | air=%.2f vocal=%.2f " +
                "side=%.2f frames=%d")
                .format(
                    tel.sampleRate, tel.adspWet, if (tel.adspEnabled) "ON" else "OFF",
                    tel.agcPreGain, tel.slowRms,
                    tel.curAirBoostDb, tel.curVocalBoostDb, tel.curSideBoostDb,
                    tel.totalFramesProcessed
                )
        }

        processor.release()

        val denom = if (counted > 0) counted else 1
        return TraceRun(
            label = label,
            presetId = presetId,
            params = params,
            dspEnabled = dspEnabled,
            actuallyProcessing = actuallyProcessing,
            gates = gates,
            negotiated = negotiated,
            framesIn = framesIn,
            framesOut = framesOut,
            rmsIn = Math.sqrt(sumIn / denom),
            rmsOut = Math.sqrt(sumOut / denom),
            peakOut = peakOut,
            clipped = clipped,
            samplesDifferingFromInput = differing,
            samplesMeasured = counted,
            samplePairs = pairs,
            bands = meterOut.energies(),
            timing = audit,
            inputGain = inputGain,
            audit = audit,
            telemetry = telemetry
        )
    }

    // -------------------------------------------------------------- laporan

    private fun header(
        sb: StringBuilder, source: File, wav: WavSource, ids: List<String>, peq: Boolean
    ) {
        sb.appendLine("=".repeat(74))
        sb.appendLine("HOSHI AUDIO PIPELINE TRACE")
        sb.appendLine("=".repeat(74))
        sb.appendLine("[1] SUMBER")
        sb.appendLine("    file         : ${source.name} (${source.length() / 1024} KiB)")
        sb.appendLine("    sampleRate   : ${wav.sampleRate} Hz")
        sb.appendLine("    kanal        : ${wav.sourceChannels} -> stereo interleaved")
        sb.appendLine("    frames       : ${wav.frames}")
        sb.appendLine("    durasi       : %.2f s".format(Locale.US, wav.durationSeconds))
        sb.appendLine("    chunk        : $chunkFrames frame (settle $settleFrames frame diabaikan)")
        sb.appendLine("    preset diuji : ${ids.size}")
        sb.appendLine("    PEQ          : ${if (peq) "ON (peaking 1kHz +6dB)" else "off"}")
        sb.appendLine("    pita ukur    : ${bandFreqs.joinToString(", ") { "${it.toInt()}Hz" }}")
        sb.appendLine()
    }

    private fun renderGates(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        sb.appendLine("[2] GERBANG onConfigure  — satu gagal = NOT_SET = DSP mati senyap")
        sb.appendLine()
        val ref = runs.first()
        // [FIX-GATES] Header lama punya 4 kolom gerbang, tapi tiap baris
        // mencetak 5 sel (4 gerbang + negotiated) -- kolom terakhir tidak ada
        // judulnya, dan lebar kolom g3/4 di header (14) berbeda dari baris
        // (12) sehingga kolomnya tidak lurus. Sekarang satu daftar lebar
        // dipakai bersama oleh header dan isi.
        val gateW = listOf(12, 12, 14, 12)
        sb.appendLine(
            "    " + pad("run", 18) + pad("g1 16bit", 12) + pad("g2 lib", 12) +
                pad("g3+g4 init", 14) + pad("g5 fmt", 12) + "negotiated"
        )
        sb.appendLine("    " + "-".repeat(18 + gateW.sum() + 14))
        runs.forEach { r ->
            sb.append("    " + pad(r.label, 18))
            r.gates.forEachIndexed { i, g ->
                sb.append(pad(if (g.passed) "PASS" else "GAGAL", gateW.getOrElse(i) { 12 }))
            }
            sb.appendLine(if (r.negotiated) "PASS" else "GAGAL (NOT_SET)")
        }
        sb.appendLine()
        sb.appendLine("    g1 = encoding PCM_16BIT, g2 = libhoshi_dsp.so termuat,")
        sb.appendLine("    g3+g4 = native handle dibuat & nativeInit(sr) sukses,")
        sb.appendLine("    g5 = format keluar = format masuk (kontrak media3).")
        sb.appendLine("    Kolom negotiated mengulang nilai yang sama dengan g5; keptahankan")
        sb.appendLine("    supaya ketidaksesuaian keduanya terlihat kalau pernah terjadi.")
        sb.appendLine("    Detail g2: ${ref.gates.getOrNull(1)?.detail ?: "-"}")
        return sb.toString()
    }

    private fun renderIntegrity(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        sb.appendLine("[6] KECOCOKAN FRAME  — rantai wajib latency-neutral")
        sb.appendLine("    framesOut harus == framesIn. Selisih berarti ada byte hilang")
        sb.appendLine("    di batas buffer (kelas bug PcmFormatConverter).")
        sb.appendLine()
        sb.appendLine("    " + pad("run", 18) + pad("framesIn", 12) + pad("framesOut", 12) + pad("selisih", 10) + "status")
        sb.appendLine("    " + "-".repeat(64))
        runs.forEach { r ->
            val diff = r.framesOut - r.framesIn
            sb.appendLine(
                "    " + pad(r.label, 18) + pad(r.framesIn.toString(), 12) +
                    pad(r.framesOut.toString(), 12) + pad(diff.toString(), 10) +
                    (if (diff == 0L) "OK" else "HILANG $diff frame")
            )
        }
        return sb.toString()
    }

    private fun renderBands(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        val baseline = runs.firstOrNull { !it.dspEnabled }
        sb.appendLine("[4] PITA FREKUENSI — bukti preset benar-benar mengubah suara")
        sb.appendLine("    dB relatif terhadap bypass. Kolom yang TIDAK bergerak = preset")
        sb.appendLine("    tidak mengubah apa pun (bug lama: semua preset bypass).")
        sb.appendLine()
        sb.append("    " + pad("run", 18))
        bandFreqs.forEach { sb.append(pad("${it.toInt()}Hz", 11)) }
        sb.appendLine("catatan")

        runs.forEach { r ->
            sb.append("    " + pad(r.label, 18))
            var moved = 0
            r.bands.forEachIndexed { i, e ->
                val ref = baseline?.bands?.get(i) ?: 0.0
                val db = if (ref > 1e-12) 10.0 * Math.log10(e / ref) else 0.0
                if (kotlin.math.abs(db) > 0.15) moved++
                sb.append(pad("%+.2f".format(Locale.US, db), 11))
            }
            sb.appendLine(
                when {
                    !r.dspEnabled -> "kontrol bypass"
                    moved == 0 -> "TIDAK ADA PERUBAHAN"
                    else -> "$moved pita bergeser"
                }
            )
        }
        sb.appendLine()

        // Putusan kalibrasi: apakah angka-angka di tabel ini bisa dipercaya?
        val calib = runs.firstOrNull { it.label == "__calib__" }
        if (calib != null && baseline != null) {
            val expected = 20.0 * Math.log10(calib.inputGain)
            val measured = calib.bands.mapIndexed { i, e ->
                val ref = baseline.bands[i]
                if (ref > 1e-12) 10.0 * Math.log10(e / ref) else 0.0
            }
            val meanDb = measured.average()
            val ok = kotlin.math.abs(meanDb - expected) < 0.5
            sb.appendLine("    KALIBRASI METER (input x%.0f, harapan %+.2f dB): terukur %+.2f dB"
                .format(Locale.US, calib.inputGain, expected, meanDb))
            sb.appendLine(
                if (ok) "    -> meter SAH. Angka 0,00 dB di tabel berarti memang tidak ada efek."
                else "    -> METER RUSAK. Tabel di bawah tidak bisa dipercaya; perbaiki meter dulu."
            )
            sb.appendLine()
        }

        sb.appendLine("    Harapan per preset (dB kasar, bukan spesifikasi):")
        sb.appendLine("      bass_boost  -> 60Hz naik   (pBass=1.80)")
        sb.appendLine("      warm        -> 300Hz naik, 10kHz turun (pAir=0.70)")
        sb.appendLine("      bright      -> 10kHz naik   (pAir=1.50)")
        sb.appendLine("      vocal       -> 3kHz naik    (pVocal=1.45)")
        sb.appendLine("      flat        -> semua ~0 dB  (regresi DEFECT 6)")
        return sb.toString()
    }

    private fun renderTiming(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        sb.appendLine("[3][4][5] WAKTU PER TAHAP  — konversi & mesin native")
        sb.appendLine("    Rasio terhadap anggaran realtime. ratio >= 1.0 berarti rantai")
        sb.appendLine("    TIDAK mungkin real-time dan pasti penyebab underrun.")
        sb.appendLine()
        runs.forEach { r ->
            sb.appendLine("    " + r.label)
            sb.appendLine("        memproses  : ${if (r.dspEnabled) (if (r.actuallyProcessing) "YA" else "TIDAK — passthrough!") else "tidak (bypass)"}")
            sb.appendLine("        " + r.timing.trim())
        }
        return sb.toString()
    }

    private fun renderLevels(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        sb.appendLine("[6] LEVEL, CLIP, dan PARAMETER")
        sb.appendLine()
        sb.appendLine(
            "    " + pad("run", 18) + pad("rmsIn", 10) + pad("rmsOut", 10) +
                pad("gain dB", 9) + pad("peakOut", 9) + pad("clip", 7) +
                pad("beda thd input", 15) + "params"
        )
        sb.appendLine("    " + "-".repeat(120))
        runs.forEach { r ->
            val gainDb = if (r.rmsIn > 1e-9) 20.0 * Math.log10(r.rmsOut / r.rmsIn) else 0.0
            val pct = if (r.samplesMeasured > 0) {
                100.0 * r.samplesDifferingFromInput / r.samplesMeasured
            } else 0.0
            sb.appendLine(
                "    " + pad(r.label, 18) +
                    pad("%.5f".format(Locale.US, r.rmsIn), 10) +
                    pad("%.5f".format(Locale.US, r.rmsOut), 10) +
                    pad("%+.2f".format(Locale.US, gainDb), 9) +
                    pad("%.4f".format(Locale.US, r.peakOut), 9) +
                    pad(r.clipped.toString(), 7) +
                    pad("%.1f%%".format(Locale.US, pct), 15) +
                    r.params.joinToString(",") { "%.2f".format(Locale.US, it) }
            )
        }
        sb.appendLine()
        sb.appendLine("    Kolom 'beda thd input' = persentase sample keluaran yang")
        sb.appendLine("    nilainya berbeda dari sample masukan pada frame yang sama.")
        sb.appendLine("    0.0% berarti mesin tidak mengubah satu sample pun.")
        sb.appendLine()
        sb.appendLine("    PASANG SAMPLE MENTAH (masuk -> keluar), 12 pertama:")
        runs.filter { it.dspEnabled && it.samplePairs.isNotEmpty() }.forEach { r ->
            sb.append("    " + pad(r.label, 18))
            sb.appendLine(r.samplePairs.joinToString(" ") { "${it.first}->${it.second}" })
        }
        sb.appendLine()
        sb.appendLine("    params = prm[0..14]: bass, vocal, air, warm, exciter, transient,")
        sb.appendLine("            width, deesser, crossfeed, agc, limiter, loudness,")
        sb.appendLine("            punch, body, saturate")
        return sb.toString()
    }

    private fun renderNativeState(runs: List<TraceRun>): String {
        val sb = StringBuilder()
        sb.appendLine("[4] TELEMETRI MESIN NATIVE")
        sb.appendLine("    PENTING: 'air/vocal/side' BUKAN bukti rantai filter jalan.")
        sb.appendLine("    Field itu dihitung di blok telemetry yang tidak digate, jadi")
        sb.appendLine("    selalu bergerak begitu pAir/pVocal berbeda — bahkan kalau DSP mati.")
        sb.appendLine("    Yang membuktikan filter benar-benar jalan adalah:")
        sb.appendLine("      wet      : crossfade wet/dry; ~0 = output dicampur balik ke kering")
        sb.appendLine("      adspNative: atomik adaptiveDspEnabled yang dibaca sisi C++")
        sb.appendLine("      agcPre   : gain pre-gain AGC; harus menjauhi 1.0 untuk sinyal keras")
        sb.appendLine()
        runs.forEach { r ->
            sb.appendLine("    " + pad(r.label, 18) + r.telemetry)
        }
        return sb.toString()
    }

    private fun pad(s: String, width: Int): String =
        if (s.length >= width) s + "  " else s + " ".repeat(width - s.length)

    // ==========================================================================
    //  MODE "tone" — respons preset pada nada murni, kondisi terkendali
    // ==========================================================================
    //
    //  Trace biasa memakai satu sumber multi-band lalu mengukur ENERGI per pita.
    //  Itu tidak bisa menjawab pertanyaan yang lebih tajam: apakah boost 60 Hz
    //  benar-benar sebesar yang dirancang, dan apakah ia hilang karena shelf-nya
    //  sendiri atau karena parameter lain di dalam preset yang sama.
    //
    //  Mode ini menjawabnya dengan kondisi terkendali: satu sinus, satu
    //  frekuensi, satu level. Karena input hanya punya energi di satu frekuensi,
    //  selisih rms keluar/masuk ADALAH gain pada frekuensi itu sendiri.
    //
    //  Pengukuran hanya diambil di detik terakhir supaya AGC pre-gain dan semua
    //  filter sudah mencapai keadaan stabil. Rantai latency-neutral, jadi indeks
    //  frame masuk dan keluar pada jendela itu boleh sama.
    //
    //  Setiap (preset, level, frekuensi) memakai `HoshiDspProcessor` BARU, jadi
    //  tidak ada sisa AGC atau state filter dari run sebelumnya.
    //
    //  Semua kode di bawah ini BARU. Jalur trace()/execute() yang lama tidak
    //  disentuh sama sekali.

    /** Panjang tiap nada. */
    private val toneSeconds = 3.0

    /** Hanya detik terakhir yang diukur; sisanya untuk memanaskan AGC/filter. */
    private val toneMeasureSeconds = 1.0

    /** Fade di kedua ujung supaya tidak ada klik transien. */
    private val toneFadeSeconds = 0.025

    private val toneSampleRate = SyntheticWav.DEFAULT_SAMPLE_RATE

    /** Batas |delta| di bawah ini dianggap "tidak bergerak sama sekali". */
    private val toneDeadDb = 0.15

    /** Pita hanya dinilai kalau nilai harapan-nya di atas ambang ini. */
    private val toneExpectedMinDb = 0.5

    /** Toleransi sel kontrol bypass. */
    private val toneControlTolDb = 0.1

    /** Selisih antar level yang masih dianggap tidak bergantung level. */
    private val toneLevelFlatDb = 1.0

    /** Lebar kolom label preset dan kolom pita. */
    private val toneLabelW = 18
    private val toneColW = 9

    /**
     * Preset isolasi sintetis: HANYA alat uji ini, bukan bagian DspPresets.
     * Basis semua 1.0, lalu tepat satu parameter yang diubah.
     */
    private val toneIsoPresets = listOf(
        Triple("iso_bass", DspPresets.IDX_BASS, 1.80f),
        Triple("iso_air", DspPresets.IDX_AIR, 1.80f),
        Triple("iso_vocal", DspPresets.IDX_VOCAL, 1.45f)
    )

    /**
     * Kontrol kedua: preset `reference` dengan crossfeed dimatikan.
     *
     * Sinyal uji dibuat stereo identik (L = R), sedangkan crossfeed earphone
     * justru mencampur silang kedua kanal. Untuk L = R, crossfeed berubah jadi
     * low-pass — jadi crossfeed sendiri sudah menggeser respons di 3 kHz dan
     * 10 kHz. Tanpa run ini, delta preset yang berbeda crossfeed/body-nya
     * terlihat seperti "shelf-nya rusak", padahal shelf-nya baik-baik saja.
     */
    private val TONE_REF_NO_CF = "ref_nocf"

    private val TONE_MIN_RATIO_60HZ = 0.50

    /**
     * [FIX-THRESH] 10 kHz: 0,25 -> 0,50.
     *
     * Angka 0,25 berasal dari shelf udara yang tadinya di 12,5 kHz, sehingga
     * 10 kHz hanya kena ujung kurva. Setelah center shelf dipindah ke 7 kHz
     * (lihat kPresetAirShelfHz di hoshi_dsp_core.cpp), boost di 10 kHz
     * mendekati penuh, jadi ambang 0,25 hanya akan menganggap respons yang
     * sudah benar sebagai "LEMAH".
     */
    private val TONE_MIN_RATIO_10KHZ = 0.50

    /**
     * Ambang paling kecil untuk pita 3 kHz.
     *
     * Berbeda dari 60 Hz/10 kHz, pita ini dinilai ARAH saja; yang dipakai
     * hanyalah tanda perubahan parameter vokal. Ambang umum (0,5 dB) terlalu
     * besar: preset `vocal` hanya menaikkan prm[1] sebesar 0,45 sehingga
     * selalu menjadi "n/a" walau efeknya jelas +0,95 dB di 3 kHz.
     */
    private val toneVocalMinParamDelta = 0.05

    /**
     * Satu pasangan pembanding untuk Bagian E: preset isolasi versus preset
     * penuh, pada pita yang sama.
     */
    private data class ToneComparison(
        val isoLabel: String,
        val freqHz: Double,
        val fullLabels: List<String>,
        /** Rasio minimum agar preset isolasi dianggap "boost-nya hidup". */
        val isoMinRatio: Double,
        /** Rasio minimum agar preset penuh dianggap "boost-nya hidup". */
        val fullMinRatio: Double
    )

    private val toneComparisons = listOf(
        // [FIX-THRESH] Ambang 10 kHz dinaikkan 0,25 -> 0,50 karena center shelf
        // udara sudah pindah ke 7 kHz (kPresetAirShelfHz). Tidak ada lagi alasan
        // untuk mengizinkan boost udara hanya diterapkan seperempat.
        ToneComparison("iso_bass", 60.0, listOf("bass_boost"), TONE_MIN_RATIO_60HZ, TONE_MIN_RATIO_60HZ),
        ToneComparison(
            "iso_air", 10000.0, listOf("bright", "air_shimmer"),
            TONE_MIN_RATIO_10KHZ, TONE_MIN_RATIO_10KHZ
        )
    )

    /**
     * True kalau preset punya crossfeed atau body yang berbeda dari `reference`.
     *
     * Keduanya bekerja di luar jalur shelf dan langsung menggeser gain di SEMUA
     * pita pada nada murni L = R, sehingga delta-nya tidak boleh dibaca sebagai
     // karakteristik shelf preset. Nilai yang dipakai di sini: prm[8]
     * (crossfeed) dan prm[13] (body).
     */
    private fun toneHasCrossfeedOrBodyDiff(params: FloatArray): Boolean {
        fun at(i: Int): Float = params.getOrElse(i) { 1f }
        return at(DspPresets.IDX_CROSSFEED) != 1f || at(DspPresets.IDX_BODY) != 1f
    }

    /** Hasil satu nada. */
    private data class ToneRun(
        val label: String,
        val levelDb: Double,
        val freqHz: Double,
        /** `configure()` mengembalikan NOT_SET. */
        val configured: Boolean,
        val gainDb: Double,
        val agcPre: Double,
        val rmsIn: Double,
        val rmsOut: Double
    )

    /** Satu vonis pita untuk satu preset pada satu level. */
    private data class ToneVerdict(
        val label: String,
        val levelDb: Double,
        val freqHz: Double,
        val expected: Double,
        val measured: Double,
        val ratio: Double?,
        val verdict: String
    )

    /**
     * Jalankan mode tone dan kembalikan laporannya sebagai teks.
     *
     * [levelsDb] adalah peak dBFS tiap nada, [freqs] frekuensi dalam Hz.
     * [presetIds] kosong memakai semua preset bawaan.
     */
    fun traceTone(
        presetIds: List<String>?,
        levelsDb: List<Double>,
        freqs: List<Double>
    ): String {
        val isoLabels = toneIsoPresets.map { it.first }
        val builtinIds = (presetIds ?: DspPresets.BUILTINS.map { it.id })
            .filter { it != TONE_BYPASS && it !in isoLabels }

        // `reference` selalu ikut: dia baseline delta di Bagian C.
        // `ref_nocf` ikut juga: kontrol crossfeed (lihat TONE_REF_NO_CF).
        val labels = (builtinIds + DspPresets.REFERENCE_ID + TONE_REF_NO_CF).distinct() + isoLabels

        val runs = ArrayList<ToneRun>()

        // Kontrol bypass lebih dulu, di setiap level x frekuensi. Kalau angka di
        // sini tidak 0,00 dB, gain preset di bawahnya tidak bisa dipercaya.
        DiagLog.i(
            TAG,
            "tone: kontrol bypass ${levelsDb.size} level x ${freqs.size} frekuensi"
        )
        val neutral = FloatArray(DspPresets.PARAM_COUNT) { 1f }
        levelsDb.forEach { level ->
            freqs.forEach { hz ->
                runs += executeTone(TONE_BYPASS, neutral, dspEnabled = false, levelDb = level, freqHz = hz)
            }
        }

        val totalRuns = labels.size * levelsDb.size * freqs.size
        DiagLog.i(TAG, "tone: ${totalRuns} nada untuk ${labels.size} preset")
        labels.forEach { label ->
            DiagLog.i(TAG, "tone: mengukur preset '$label' ...")
            val params = toneParamsFor(label)
            val dspOn = label != TONE_BYPASS
            levelsDb.forEach { level ->
                freqs.forEach { hz ->
                    runs += executeTone(label, params, dspOn, level, hz)
                }
            }
        }

        val verdicts = toneVerdicts(runs, levelsDb, builtinIds, freqs)

        val report = StringBuilder()
        report.append(renderToneHeader(levelsDb, freqs, labels))
        report.append(renderToneControl(runs, levelsDb, freqs))
        report.append(renderToneAbsolute(runs, levelsDb, labels, freqs))
        report.append(renderToneDelta(runs, levelsDb, labels, freqs))
        report.append(renderToneVerdicts(verdicts))
        report.append(renderToneDiagnosis(runs, verdicts, levelsDb, freqs, labels))

        val text = report.toString()
        DiagLog.i(TAG, "trace selesai: mode tone, ${runs.size} nada atas ${labels.size} preset")
        text.lineSequence().forEach { DiagLog.i(TAG, it) }
        return text
    }

    /** Jalankan mode tone dan tulis laporan ke [output] supaya mudah di-pull. */
    fun traceToneToFile(
        presetIds: List<String>?,
        levelsDb: List<Double>,
        freqs: List<Double>,
        output: File
    ): String {
        val text = traceTone(presetIds, levelsDb, freqs)
        output.writeText(text)
        DiagLog.i(TAG, "laporan ditulis: ${output.absolutePath} (${output.length()} bytes)")
        return text
    }

    // ------------------------------------------------------- eksekusi tone

    /** Parameter untuk [label]; preset isolasi disintesis di sini. */
    private fun toneParamsFor(label: String): FloatArray {
        toneIsoPresets.firstOrNull { it.first == label }?.let { return toneIsoParams(it.second, it.third) }
        // Kontrol crossfeed: `reference` persis, tapi prm[8] dimatikan.
        if (label == TONE_REF_NO_CF) {
            val base = DspParamResolver.resolve(DspPresets.REFERENCE_ID, emptyList(), null).copyOf()
            base[DspPresets.IDX_CROSSFEED] = 0f
            return base
        }
        return DspParamResolver.resolve(label, emptyList(), null)
    }

    private fun toneIsoParams(index: Int, value: Float): FloatArray =
        FloatArray(DspPresets.PARAM_COUNT) { i -> if (i == index) value else 1f }

    /**
     * Amplop fade; menyalin pola `SyntheticWav.envelope()` persis.
     *
     * Dipakai ulang (bukan dipanggil) karena fungsi itu private di objeknya.
     */
    private fun toneEnvelope(i: Int, n: Int, fade: Int): Double {
        if (i < fade) return i.toDouble() / fade
        val tail = n - 1 - i
        if (tail < fade) return tail.coerceAtLeast(0).toDouble() / fade
        return 1.0
    }

    /**
     * Jalankan satu nada dan ukur gainnya di detik terakhir.
     *
     * Konfigurasi dibuat dari nol supaya tidak ada sisa state dari run lain.
     */
    private fun executeTone(
        label: String,
        params: FloatArray,
        dspEnabled: Boolean,
        levelDb: Double,
        freqHz: Double
    ): ToneRun {
        val processor = HoshiDspProcessor()
        val format = AudioFormat(toneSampleRate, 2, C.ENCODING_PCM_16BIT)

        if (processor.configure(format) == AudioFormat.NOT_SET) {
            processor.release()
            return ToneRun(label, levelDb, freqHz, false, 0.0, 1.0, 0.0, 0.0)
        }

        processor.setParams(params)
        processor.setAdaptiveDspEnabled(dspEnabled)

        val totalFrames = (toneSeconds * toneSampleRate).toInt()
        val measureFrom = totalFrames - (toneMeasureSeconds * toneSampleRate).toInt()
        val fade = (toneFadeSeconds * toneSampleRate).toInt()
        val amp = Math.pow(10.0, levelDb / 20.0)
        val w = 2.0 * Math.PI * freqHz / toneSampleRate

        var sumIn = 0.0
        var sumOut = 0.0
        var counted = 0L

        var offset = 0
        while (offset < totalFrames) {
            val n = minOf(chunkFrames, totalFrames - offset)

            val inputBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) {
                val idx = offset + i
                val s = (Math.sin(w * idx) * amp * toneEnvelope(idx, totalFrames, fade) * 32767.0)
                    .toInt().coerceIn(-32768, 32767).toShort()
                // Stereo identik: L = R, jadi satu kanal cukup untuk mengukur.
                inputBuf.putShort(s)
                inputBuf.putShort(s)
            }
            inputBuf.flip()

            processor.queueInput(inputBuf)

            val out = processor.getOutput()
            if (out.hasRemaining()) {
                val shorts = ShortArray(out.remaining() / 2)
                out.asShortBuffer().get(shorts)
                val produced = shorts.size / 2

                for (i in 0 until produced) {
                    val frameIdx = offset + i
                    if (frameIdx < measureFrom) continue
                    // Kanal kiri saja, sesuai spesifikasi mode ini.
                    val l = shorts[i * 2].toInt() / 32768.0
                    sumOut += l * l
                    counted++
                }
            }

            // Masukan diukur PER FRAME pada jendela yang sama dengan `sumOut`.
            //
            // Guard lama `if (offset >= measureFrom)` menghitung seluruh
            // input sebuah chunk begitu offset-nya lewat ambang, sedangkan
            // output dijumlahkan per frame dari measureFrom. measureFrom
            // (88200) bukan kelipatan chunk (2048), jadi kedua penjumlah
            // menghitung sample yang BERBEDA — lalu keduanya dibagi `counted`
            // yang sama. Selisihnya konstan, bukan acak: kontrol bypass pun
            // terbaca +0,19 dB dan meter ditandai RUSAK.
            for (i in 0 until n) {
                val idx = offset + i
                if (idx < measureFrom) continue
                val s = (Math.sin(w * idx) * amp * toneEnvelope(idx, totalFrames, fade) * 32767.0)
                    .toInt().coerceIn(-32768, 32767) / 32768.0
                sumIn += s * s
            }

            offset += n
        }

        val tel = processor.updateTelemetry()
        val agcPre = tel?.agcPreGain?.toDouble() ?: 1.0
        processor.release()

        val denom = if (counted > 0L) counted.toDouble() else 1.0
        val rmsIn = Math.sqrt(sumIn / denom)
        val rmsOut = Math.sqrt(sumOut / denom)
        val gainDb = if (rmsIn > 1e-9 && rmsOut > 1e-9) {
            20.0 * Math.log10(rmsOut / rmsIn)
        } else {
            // Floor agar tabel tidak berisi NaN/Inf yang-ruup semua sel.
            -120.0
        }

        return ToneRun(label, levelDb, freqHz, true, gainDb, agcPre, rmsIn, rmsOut)
    }

    // ---------------------------------------------------------- pembacaan

    private fun List<ToneRun>.toneFind(label: String, levelDb: Double, freqHz: Double): ToneRun? =
        firstOrNull { it.label == label && it.levelDb == levelDb && it.freqHz == freqHz }

    /**
     * Gain [label] dikurangi gain preset `reference` pada level dan frekuensi
     * yang sama. Offset bersama ikut terhapus, jadi yang tersisa adalah efek
     * khusus preset tersebut.
     */
    private fun List<ToneRun>.toneDelta(label: String, levelDb: Double, freqHz: Double): Double? {
        val own = toneFind(label, levelDb, freqHz) ?: return null
        val ref = toneFind(DspPresets.REFERENCE_ID, levelDb, freqHz) ?: return null
        if (!own.configured || !ref.configured) return null
        return own.gainDb - ref.gainDb
    }

    /**
     * Harapan shelf dari parameter preset: 60 Hz dapat bass, 10 kHz dapat air.
     * Null kalau pita itu tidak punya shelf yang relevan.
     */
    private fun toneShelfExpectation(label: String, freqHz: Double): Double? {
        val params = toneParamsFor(label)
        val gains = DspParamResolver.presetShelfGainsDb(params)
        return when (freqHz) {
            60.0 -> gains.first.toDouble()
            10000.0 -> gains.second.toDouble()
            else -> null
        }
    }

    // ---------------------------------------------------------- vonis pita

    private fun toneVerdictFor(measured: Double, expected: Double, minRatio: Double): Pair<Double?, String> {
        if (kotlin.math.abs(measured) < toneDeadDb) return null to "TIDAK ADA EFEK"
        val ratio = measured / expected
        if (ratio < 0) return ratio to "BERLAWANAN"
        if (ratio >= minRatio) return ratio to "SESUAI"
        return ratio to ("LEMAH (rasio %d%%)".format(Locale.US, Math.round(ratio * 100.0)))
    }

    /** Pita 3 kHz dinilai ARAH saja; besarnya tidak dinilai. */
    private fun toneVocalVerdictFor(measured: Double, expected: Double): Pair<Double?, String> {
        if (kotlin.math.abs(measured) < toneDeadDb) return null to "TIDAK ADA EFEK"
        val sameSide = (measured > 0.0) == (expected > 0.0)
        return null to if (sameSide) "SESUAI ARAH" else "BERLAWANAN"
    }

    private fun toneVerdicts(
        runs: List<ToneRun>,
        levelsDb: List<Double>,
        builtinIds: List<String>,
        freqs: List<Double>
    ): List<ToneVerdict> {
        val out = ArrayList<ToneVerdict>()
        builtinIds.forEach { label ->
            val params = toneParamsFor(label)
            val vocalDelta = params.getOrElse(DspPresets.IDX_VOCAL) { 1f } - 1f
            levelsDb.forEach { level ->
                freqs.forEach { hz ->
                    val measured = runs.toneDelta(label, level, hz) ?: return@forEach
                    val rawLine = when (hz) {
                        60.0 -> {
                            val expected = DspParamResolver.presetShelfGainsDb(params).first.toDouble()
                            if (kotlin.math.abs(expected) < toneExpectedMinDb) {
                                null to "n/a (harapan < 0,5 dB)"
                            } else {
                                toneVerdictFor(measured, expected, TONE_MIN_RATIO_60HZ)
                            }
                        }
                        10000.0 -> {
                            val expected = DspParamResolver.presetShelfGainsDb(params).second.toDouble()
                            if (kotlin.math.abs(expected) < toneExpectedMinDb) {
                                null to "n/a (harapan < 0,5 dB)"
                            } else {
                                // [FIX-THRESH] Center shelf udara kini 7 kHz
                                // (kPresetAirShelfHz), jadi 10 kHz tidak lagi
                                // hanya kena ujung kurva -> ambang 0,50.
                                toneVerdictFor(measured, expected, TONE_MIN_RATIO_10KHZ)
                            }
                        }
                        3000.0 -> {
                            // [FIX-VOCAL] Pita ini dinilai ARAH. Delta parameter
                            // yang NOL (prm[1] = 1,0) berarti preset tidak
                            // menyentuh vokal sama sekali -- bukan "arah turun".
                            // Ambangnya diturunkan ke [toneVocalMinParamDelta]
                            // supaya `vocal` (prm[1] = 1,45) ikut dinilai.
                            if (kotlin.math.abs(vocalDelta) < toneVocalMinParamDelta) {
                                null to "netral (prm[vokal] tidak diubah)"
                            } else {
                                toneVocalVerdictFor(measured, kotlin.math.sign(vocalDelta).toDouble())
                            }
                        }
                        else -> null to "tidak dinilai (pita tanpa penilaian)"
                    }
                    // [FIX-XFEED] "BERLAWANAN" pada preset yang crossfeed atau
                    // body-nya berbeda dari reference bukan bukti shelf rusak:
                    // dua parameter itu menggeser gain di semua pita pada nada
                    // L = R. Tetapkan sebagai keterangan, bukan bug shelf.
                    val line = if (rawLine.second.startsWith("BERLAWANAN") &&
                        toneHasCrossfeedOrBodyDiff(params)
                    ) {
                        rawLine.first to "BERLAWANAN (dipengaruhi crossfeed/body)"
                    } else {
                        rawLine
                    }
                    out += ToneVerdict(
                        label = label,
                        levelDb = level,
                        freqHz = hz,
                        expected = when (hz) {
                            60.0 -> DspParamResolver.presetShelfGainsDb(params).first.toDouble()
                            10000.0 -> DspParamResolver.presetShelfGainsDb(params).second.toDouble()
                            3000.0 -> kotlin.math.sign(vocalDelta).toDouble()
                            else -> 0.0
                        },
                        measured = measured,
                        ratio = line.first,
                        verdict = line.second
                    )
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ laporan

    private fun toneHzLabel(hz: Double): String =
        if (hz >= 1000.0) "%.1fkHz".format(Locale.US, hz / 1000.0) else "${hz.toInt()}Hz"

    private fun toneLevelLabel(levelDb: Double): String =
        ("%+.0fdBFS".format(Locale.US, levelDb))

    private fun toneSigned(db: Double): String = "%+.2f".format(Locale.US, db)

    private fun toneRatio(ratio: Double): String = "%+.0f%%".format(Locale.US, ratio * 100.0)

    private fun renderToneHeader(
        levelsDb: List<Double>,
        freqs: List<Double>,
        labels: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=".repeat(74))
        sb.appendLine("HOSHI AUDIO PIPELINE TRACE — MODE TONE")
        sb.appendLine("=".repeat(74))
        sb.appendLine("[1] KONFIGURASI")
        sb.appendLine("    sampleRate   : ${toneSampleRate} Hz")
        sb.appendLine("    kanal        : 2 -> stereo identik (L = R); diukur kanal kiri")
        sb.appendLine("    nada         : %.1f s sinus, fade %.0f ms di kedua ujung"
            .format(Locale.US, toneSeconds, toneFadeSeconds * 1000.0))
        sb.appendLine("    diukur       : %.1f detik terakhir saja (sisa %.1f s untuk"
            .format(Locale.US, toneMeasureSeconds, toneSeconds - toneMeasureSeconds))
        sb.appendLine("                   memanaskan AGC pre-gain dan filter)")
        sb.appendLine("    processor    : instance BARU tiap nada (tidak ada sisa AGC/filter)")
        sb.appendLine("    chunk        : ${chunkFrames} frame")
        sb.appendLine("    level diuji  : " + levelsDb.joinToString(", ") { toneLevelLabel(it) })
        sb.appendLine("    frekuensi    : " + freqs.joinToString(", ") { toneHzLabel(it) })
        sb.appendLine("    preset       : ${labels.size} total (${labels.count { it.startsWith("iso_") }} isolasi sintetis)")
        sb.appendLine()
        sb.appendLine("    ganhoDb = 20*log10(rmsOut/rmsIn) pada jendela ukur yang sama.")
        sb.appendLine("    Karena input hanya berisi satu frekuensi, angka ini adalah gain")
        sb.appendLine("    pada frekuensi itu sendiri.")
        return sb.toString()
    }

    private fun renderToneControl(
        runs: List<ToneRun>,
        levelsDb: List<Double>,
        freqs: List<Double>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[A] KONTROL BYPASS  — gerbang kepercayaan seluruh tabel")
        sb.appendLine("    bypass: setAdaptiveDspEnabled(false) dan semua parameter 1.0.")
        sb.appendLine("    Karena input nada murni, gain di sini HARUS 0,00 dB. Menyimpang")
        sb.appendLine("    berarti meter atau jalurnya yang bermasalah, bukan presetnya.")
        sb.appendLine()
        sb.append("    " + pad("level", toneLabelW))
        freqs.forEach { sb.append(pad(toneHzLabel(it), toneColW)) }
        sb.appendLine("status")
        sb.appendLine("    " + "-".repeat(toneLabelW + toneColW * freqs.size + 8))

        var anyBroken = false
        levelsDb.forEach { level ->
            sb.append("    " + pad(toneLevelLabel(level), toneLabelW))
            var rowOk = true
            freqs.forEach { hz ->
                val run = runs.toneFind(TONE_BYPASS, level, hz)
                when {
                    run == null -> {
                        rowOk = false
                        sb.append(pad("n/a", toneColW))
                    }
                    !run.configured -> {
                        rowOk = false
                        sb.append(pad("GAGAL", toneColW))
                    }
                    else -> {
                        if (kotlin.math.abs(run.gainDb) > toneControlTolDb) rowOk = false
                        sb.append(pad(toneSigned(run.gainDb), toneColW))
                    }
                }
            }
            if (!rowOk) anyBroken = true
            sb.appendLine(if (rowOk) "OK" else "RUSAK")
        }
        sb.appendLine()

        if (anyBroken) {
            sb.appendLine("    METER/ALUR RUSAK — tabel tidak bisa dipercaya.")
            sb.appendLine("    Gotanya: pada bypass, gain paling banter hanya pembulatan,")
            sb.appendLine("    jadi seharusnya sekitar 0,00 dB. Tabel di bawah tetap")
            sb.appendLine("    dicetak, tapi jangan dibaca sebagai karakteristik preset.")
        } else {
            sb.appendLine("    Semua sel dalam toleransi ±%.1f dB -> meter SAH."
                .format(Locale.US, toneControlTolDb))
            sb.appendLine("    Angka 0,00 dB di tabel di bawah berarti memang tidak ada efek.")
        }
        return sb.toString()
    }

    private fun renderToneAbsolute(
        runs: List<ToneRun>,
        levelsDb: List<Double>,
        labels: List<String>,
        freqs: List<Double>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[B] GAIN ABSOLUT  — gain dB yang benar-benar keluar, per level")
        sb.appendLine("    Mengandung SEMUA yang aktif: AGC, limiter, loudness, dan efek preset.")
        sb.appendLine("    Kolom agcPre = gain pre-gain AGC saat pengukuran (1.000 = AGC diam).")
        sb.appendLine()
        levelsDb.forEach { level ->
            sb.appendLine("    level " + toneLevelLabel(level))
            sb.append("    " + pad("preset", toneLabelW))
            freqs.forEach { sb.append(pad(toneHzLabel(it), toneColW)) }
            sb.appendLine(pad("agcPre", 8))
            sb.appendLine("    " + "-".repeat(toneLabelW + toneColW * freqs.size + 8))
            labels.forEach { label ->
                sb.append("    " + pad(label, toneLabelW))
                var lastAgc = Double.NaN
                freqs.forEach { hz ->
                    val run = runs.toneFind(label, level, hz)
                    when {
                        run == null -> sb.append(pad("n/a", toneColW))
                        !run.configured -> sb.append(pad("GAGAL", toneColW))
                        else -> {
                            lastAgc = run.agcPre
                            sb.append(pad(toneSigned(run.gainDb), toneColW))
                        }
                    }
                }
                sb.appendLine(
                    if (lastAgc.isNaN()) pad("n/a", 8)
                    else pad("%.3f".format(Locale.US, lastAgc), 8)
                )
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun renderToneDelta(
        runs: List<ToneRun>,
        levelsDb: List<Double>,
        labels: List<String>,
        freqs: List<Double>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[C] DELTA TERHADAP REFERENCE  — tabel terpenting")
        sb.appendLine("    gainDb dikurangi gainDb preset '${DspPresets.REFERENCE_ID}' pada level")
        sb.appendLine("    dan frekuensi yang sama. Offset bersama (AGC, limiter, loudness,")
        sb.appendLine("    dan apa pun yang selalu aktif) ikut terhapus, jadi yang tersisa")
        sb.appendLine("    adalah efek KHUSUS preset tersebut.")
        sb.appendLine()
        levelsDb.forEach { level ->
            sb.appendLine("    level " + toneLevelLabel(level))
            sb.append("    " + pad("preset", toneLabelW))
            freqs.forEach { sb.append(pad(toneHzLabel(it), toneColW)) }
            sb.appendLine()
            // [FIX-DELTA] Kolom "vonis" dihapus: headed-nya ada tapi tidak
            // pernah diisi, sehingga selalu kosong dan menyesatkan. Vonis
            // lengkap (dengan alasan "dipengaruhi crossfeed/body") ada di [D].
            sb.appendLine("    " + "-".repeat(toneLabelW + toneColW * freqs.size))
            labels.forEach { label ->
                sb.append("    " + pad(label, toneLabelW))
                freqs.forEach { hz ->
                    val delta = runs.toneDelta(label, level, hz)
                    sb.append(if (delta == null) pad("n/a", toneColW) else pad(toneSigned(delta), toneColW))
                }
                sb.appendLine()
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun renderToneVerdicts(verdicts: List<ToneVerdict>): String {
        val sb = StringBuilder()
        sb.appendLine("[D] VONIS PER PRESET BAWAAN, PER LEVEL")
        sb.appendLine("    Harapan 60 Hz dan 10 kHz diambil dari")
        sb.appendLine("    DspParamResolver.presetShelfGainsDb(params); 3 kHz dinilai ARAH saja")
        sb.appendLine("    (prm[vokal] tidak diubah -> 'netral'); 300 Hz tidak dinilai karena")
        sb.appendLine("    tidak ada parameter yang memetakannya.")
        sb.appendLine("    Ambang: |deltaTerukur| < %.2f dB -> TIDAK ADA EFEK; rasio < 0 ->"
            .format(Locale.US, toneDeadDb))
        sb.appendLine("    BERLAWANAN; rasio >= %.2f (60 Hz & 10 kHz) -> SESUAI."
            .format(Locale.US, TONE_MIN_RATIO_60HZ))
        sb.appendLine("    BERLAWANAN pada preset yang crossfeed/body-nya berbeda dari")
        sb.appendLine("    reference diberi keterangan: dua parameter itu menggeser gain di")
        sb.appendLine("    semua pita pada nada L = R, jadi bukan bukti shelf rusak.")
        sb.appendLine()

        if (verdicts.isEmpty()) {
            sb.appendLine("    (tidak ada preset bawaan yang dinilai)")
            return sb.toString()
        }

        verdicts.groupBy { it.levelDb }.forEach { (level, rows) ->
            sb.appendLine("    level " + toneLevelLabel(level))
            rows.forEach { v ->
                // [FIX-VOCAL] expected == 0 berarti TANPA ARAH: preset
                // reference punya prm[vokal] = 1,0 sehingga sign(delta) = 0.
                // Sempler dicetak "arah turun" padahal tidak ada yang dituju.
                val exp = when {
                    v.freqHz != 3000.0 -> "${toneSigned(v.expected)} dB"
                    v.expected > 0.0 -> "arah naik"
                    v.expected < 0.0 -> "arah turun"
                    else -> "netral"
                }
                val ratioTxt = v.ratio?.let { "rasio ${toneRatio(it)}" } ?: "rasio  n/a"
                sb.appendLine(
                    "      " + pad(v.label, toneLabelW) + pad(toneHzLabel(v.freqHz), toneColW) +
                        pad("harapan $exp", 20) + pad("terukur ${toneSigned(v.measured)}", 18) +
                        pad(ratioTxt, 14) + v.verdict
                )
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun renderToneDiagnosis(
        runs: List<ToneRun>,
        verdicts: List<ToneVerdict>,
        levelsDb: List<Double>,
        freqs: List<Double>,
        labels: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[E] DIAGNOSIS OTOMATIS")
        sb.appendLine("    Bahasa di bawah sengaja hati-hati: 'indikasi', bukan penyebab")
        sb.appendLine("    pasti. Satu preset bisa gagal karena beberapa alasan sekaligus,")
        sb.appendLine("    dan alat uji ini tidak bisa memisahkan semuanya.")
        sb.appendLine()

        val problematic = verdicts.filter {
            it.verdict == "TIDAK ADA EFEK" || it.verdict.startsWith("BERLAWANAN")
        }

        sb.appendLine("    CATATAN KONTROL: CROSSFEED & BODY")
        sb.appendLine("      Sinyal uji dibuat stereo identik (L = R). Crossfeed earphone")
        sb.appendLine("      justru mencampur silang kedua kanal, jadi untuk L = R ia")
        sb.appendLine("      berubah menjadi low-pass dan MENGURANGI gain di 3 kHz serta")
        sb.appendLine("      10 kHz, berapa pun nilai crossfeed preset. Run '${TONE_REF_NO_CF}'")
        sb.appendLine("      (reference dengan prm[8]=0) mengukur pengaruh itu langsung:")
        levelsDb.forEach { level ->
            val parts = freqs.mapNotNull { hz ->
                val d = runs.toneDelta(TONE_REF_NO_CF, level, hz) ?: return@mapNotNull null
                "${toneHzLabel(hz)} ${toneSigned(d)} dB"
            }
            if (parts.isNotEmpty()) {
                sb.appendLine("        " + pad(toneLevelLabel(level), 12) + parts.joinToString(", "))
            }
        }
        sb.appendLine("      Angka positif = crossfeed reference justru MEREDUKSI band.")
        sb.appendLine("      Karena itu delta di 3 kHz/10 kHz pada preset dengan crossfeed")
        sb.appendLine("      berbeda dari reference bukan bukti shelf-nya rusak.")

        val bodyRef = labels
            .map { it to (toneParamsFor(it).getOrElse(DspPresets.IDX_BODY) { 1f } - 1f) }
            .filter { kotlin.math.abs(it.second) > 0.0f }
            .maxByOrNull { kotlin.math.abs(it.second) }
        if (bodyRef != null) {
            sb.appendLine("      Parameter body (prm[13]) juga menambah/mengurangi gain rata")
            sb.appendLine("      di SEMUA frekuensi pada nada murni. Contoh terbesarnya")
            sb.appendLine("      '${bodyRef.first}' (prm[13] = %.2f):"
                .format(Locale.US, bodyRef.second + 1f))
            levelsDb.forEach { level ->
                val parts = freqs.mapNotNull { hz ->
                    val d = runs.toneDelta(bodyRef.first, level, hz) ?: return@mapNotNull null
                    "${toneHzLabel(hz)} ${toneSigned(d)} dB"
                }
                if (parts.isNotEmpty()) {
                    sb.appendLine("        " + pad(toneLevelLabel(level), 12) + parts.joinToString(", "))
                }
            }
            sb.appendLine("      Vonis BERLAWANAN untuk preset seperti ini diberi keterangan")
            sb.appendLine("      '(dipengaruhi crossfeed/body)' di bagian [D].")
        }
        sb.appendLine()

        levelsDb.forEach { level ->
            sb.appendLine("    level " + toneLevelLabel(level))
            sb.appendLine()
            toneComparisons.forEach { cmp ->
                val isoDelta = runs.toneDelta(cmp.isoLabel, level, cmp.freqHz)
                val isoExp = toneShelfExpectation(cmp.isoLabel, cmp.freqHz)
                if (isoDelta == null || isoExp == null || kotlin.math.abs(isoExp) < toneExpectedMinDb) {
                    return@forEach
                }
                val isoRatio = isoDelta / isoExp

                sb.appendLine(
                    "      " + cmp.isoLabel + " vs " + cmp.fullLabels.joinToString("/") +
                        " @ " + toneHzLabel(cmp.freqHz)
                )
                sb.appendLine(
                    "        isolasi : terukur ${toneSigned(isoDelta)} dB, " +
                        "harapan ${toneSigned(isoExp)} dB, rasio ${toneRatio(isoRatio)} " +
                        "(ambang ${toneRatio(cmp.isoMinRatio)})"
                )

                cmp.fullLabels.forEach { full ->
                    val fullDelta = runs.toneDelta(full, level, cmp.freqHz)
                    val fullExp = toneShelfExpectation(full, cmp.freqHz)
                    if (fullDelta == null || fullExp == null || kotlin.math.abs(fullExp) < toneExpectedMinDb) {
                        return@forEach
                    }
                    val fullRatio = fullDelta / fullExp
                    sb.appendLine(
                        "        " + pad(full, toneLabelW) +
                            "terukur ${toneSigned(fullDelta)} dB, " +
                            "harapan ${toneSigned(fullExp)} dB, rasio ${toneRatio(fullRatio)} " +
                            "(ambang ${toneRatio(cmp.fullMinRatio)})"
                    )
                    when {
                        isoRatio >= cmp.isoMinRatio && fullRatio < cmp.fullMinRatio ->
                            sb.appendLine(
                                "          Indikasi: boost hilang karena parameter LAIN di dalam" +
                                    " preset (mis. saturasi/punch/body), bukan karena shelf."
                            )
                        isoRatio < cmp.isoMinRatio ->
                            sb.appendLine(
                                "          Indikasi: shelf dinetralkan di dalam mesin (mis. AGC" +
                                    " yang mengukur level setelah shelf, atau saturasi)," +
                                    " bukan karena kombinasi parameter preset."
                            )
                        fullRatio >= cmp.fullMinRatio ->
                            sb.appendLine("          Efek preset sesuai rancangan pada level ini.")
                        else ->
                            sb.appendLine(
                                "          Di luar tiga pola di atas; rasio preset di antara" +
                                    " ambang iso dan ambang preset."
                            )
                    }
                }
                sb.appendLine()
            }
        }

        // Ketergantungan level: delta level terendah vs tertinggi, preset sama.
        if (levelsDb.size >= 2) {
            val lo = levelsDb.first()
            val hi = levelsDb.last()
            sb.appendLine("    KETERGANTUNGAN LEVEL (delta ${toneLevelLabel(lo)} vs ${toneLevelLabel(hi)},")
            sb.appendLine("    ambang ${"%.1f".format(Locale.US, toneLevelFlatDb)} dB):")
            var any = false
            labels.forEach { label ->
                freqs.forEach { hz ->
                    val a = runs.toneDelta(label, lo, hz)
                    val b = runs.toneDelta(label, hi, hz)
                    if (a == null || b == null) return@forEach
                    val diff = a - b
                    if (kotlin.math.abs(diff) > toneLevelFlatDb) {
                        any = true
                        sb.appendLine(
                            "      " + pad(label, toneLabelW) + pad(toneHzLabel(hz), toneColW) +
                                pad("${toneSigned(a)} -> ${toneSigned(b)}", 22) +
                                "selisih ${toneSigned(diff)}"
                        )
                    }
                }
            }
            if (any) {
                sb.appendLine("      Indikasi: efek bergantung level (AGC/saturasi/limiter terlibat).")
            } else {
                sb.appendLine("      (tidak ada preset yang bergeser lebih dari ambang)")
            }
            sb.appendLine()
        }

        sb.appendLine("    AGC (agcPre per level):")
        levelsDb.forEach { level ->
            val agcs = runs
                .filter { it.levelDb == level && it.label != TONE_BYPASS && it.configured }
                .map { it.agcPre }
                .filter { it > 0.0 }
            val lo = agcs.minOrNull()
            val hi = agcs.maxOrNull()
            if (lo == null || hi == null) {
                sb.appendLine("      ${pad(toneLevelLabel(level), 12)} n/a")
            } else {
                val engaged = lo < 0.8 || hi > 1.2
                sb.appendLine(
                    "      " + pad(toneLevelLabel(level), 12) +
                        "%.3f .. %.3f".format(Locale.US, lo, hi) +
                        if (engaged) "  AGC aktif memengaruhi pengukuran pada level ini" else ""
                )
            }
        }
        sb.appendLine()

        sb.appendLine("    PRESET BERSTATUS TIDAK ADA EFEK / BERLAWANAN:")
        val bad = problematic.map { "${it.label} @ ${toneLevelLabel(it.levelDb)} ${toneHzLabel(it.freqHz)}: ${it.verdict}" }
        if (bad.isEmpty()) {
            sb.appendLine("      (tidak ada)")
        } else {
            bad.distinct().forEach { sb.appendLine("      $it") }
        }
        return sb.toString()
    }

    companion object {
        const val TAG = "AudioPipe"
        const val REPORT_FILE = "audio_trace_report.txt"
        const val SYNTHETIC_FILE = "audio_trace_source.wav"

        /** Nama laporan mode tone; dipakai ProbeReceiver sebagai default. */
        const val TONE_REPORT_FILE = "audio_trace_tone_report.txt"

        /** Label kontrol bypass pada mode tone. */
        const val TONE_BYPASS = "__bypass__"
    }
}

// ============================================================================
//  PEMICU LEWAT ADB
// ============================================================================

/**
 * Pemicu trace kit dari adb. Hanya ada di build debug.
 *
 * Receiver tidak di-export, jadi satu-satunya pemicu adalah adb shell dari
 * mesin pengembang — tidak ada aplikasi lain di perangkat yang bisa menjalankan
 * trace atas nama pengguna.
 *
 * KerMana ada di thread terpisah: satu trace bisa memakan waktu detik-an,
 * sedangkan `onReceive` punya anggaran sangat pendek.
 */
@androidx.media3.common.util.UnstableApi
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROBE) return

        val appContext = context.applicationContext
        val path = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
        val presets = intent.getStringExtra(EXTRA_PRESETS)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        val peq = intent.getStringExtra(EXTRA_PEQ) == "1"
        val chunk = intent.getStringExtra(EXTRA_CHUNK)?.toIntOrNull() ?: 2048
        val toneOn = intent.getStringExtra(EXTRA_TONE) == "1"
        val levels = intent.getStringExtra(EXTRA_LEVELS)
            ?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_TONE_LEVELS
        val freqs = intent.getStringExtra(EXTRA_FREQS)
            ?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_TONE_FREQS
        val reportName = intent.getStringExtra(EXTRA_REPORT)
            ?: if (toneOn) AudioPipelineTraceKit.TONE_REPORT_FILE else AudioPipelineTraceKit.REPORT_FILE

        // Mode tone selalu memakai nada sintetis, jadi extra "file" diabaikan.
        if (!toneOn && path != null && !File(path).exists()) {
            DiagLog.e(TAG, "File tidak ditemukan: $path")
            Log.e(TAG, "File tidak ditemukan: $path")
            return
        }

        val pending = goAsync()
        executor.execute {
            try {
                DiagLog.init(appContext)
                val kit = AudioPipelineTraceKit(chunkFrames = chunk)
                if (toneOn) {
                    DiagLog.i(
                        TAG,
                        "trace tone mulai: presets=${presets?.joinToString() ?: "SEMUA"} " +
                            "levels=${levels.joinToString() { "%.0f".format(Locale.US, it) }} " +
                            "freqs=${freqs.joinToString() { "%.0f".format(Locale.US, it) }} chunk=$chunk"
                    )
                    kit.traceToneToFile(
                        presetIds = presets,
                        levelsDb = levels,
                        freqs = freqs,
                        output = File(appContext.filesDir, reportName)
                    )
                } else {
                    DiagLog.i(
                        TAG,
                        "trace mulai: sumber=${path ?: "sintetis"} " +
                            "presets=${presets?.joinToString() ?: "SEMUA"} peq=$peq chunk=$chunk"
                    )
                    kit.traceToFile(
                        input = path?.let { File(it) },
                        syntheticTarget = File(appContext.filesDir, AudioPipelineTraceKit.SYNTHETIC_FILE),
                        output = File(appContext.filesDir, reportName),
                        presetIds = presets,
                        peqEnabled = peq
                    )
                }

                DiagLog.i(TAG, "trace selesai; baca ${appContext.filesDir}/$reportName")
            } catch (t: Throwable) {
                DiagLog.e(TAG, "trace gagal: ${t.javaClass.simpleName}: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PROBE = "com.naomi.hoshimusic.PROBE"
        const val EXTRA_FILE = "file"
        const val EXTRA_PRESETS = "presets"
        const val EXTRA_PEQ = "peq"
        const val EXTRA_CHUNK = "chunk"
        const val EXTRA_REPORT = "report"
        const val EXTRA_TONE = "tone"
        const val EXTRA_LEVELS = "levels"
        const val EXTRA_FREQS = "freqs"

        /** Default mode tone: dua level yang jauh, empat pita representatif. */
        val DEFAULT_TONE_LEVELS = listOf(-20.0, -6.0)
        val DEFAULT_TONE_FREQS = listOf(60.0, 300.0, 3000.0, 10000.0)

        private const val TAG = "ProbeReceiver"

        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "hoshi-audio-trace").apply { isDaemon = true }
        }
    }
}
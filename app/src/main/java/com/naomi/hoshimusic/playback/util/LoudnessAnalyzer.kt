package com.naomi.hoshimusic.playback.util

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.pow

/**
 * Analyzer loudness offline (BS.1770-inspired) untuk lagu TANPA tag ReplayGain.
 *
 * Dekode ke PCM via MediaExtractor+MediaCodec, sampel dialirkan melalui K-weighting
 * (2 biquad RBJ berantai: high-shelf +4 dB @1500 Hz Q0.707 lalu HPF 38 Hz Q0.5),
 * lalu digating ala BS.1770 dengan blok 400 ms overlap 75%:
 * gate absolut -70 LUFS, lalu gate relatif (integrated - 10 LU).
 *
 * Keluaran Pair(gainDb kompatibel ReplayGain, samplePeak dBFS):
 * gain = -18 LUFS - integrated, dibatasi headroom dari sample peak.
 * Semua dibungkus runCatching; gagal dekode/timeout mengembalikan null.
 */
object LoudnessAnalyzer {

    /** @return Pair(gainDb kompatibel ReplayGain, samplePeak dBFS) atau null jika gagal. */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        mimeTypeHint: String? = null,
        sampleDurationMs: Long? = null
    ): Pair<Float, Float>? =
        withContext(Dispatchers.IO) { runCatching { decodeAndMeasure(context, uri, mimeTypeHint, sampleDurationMs) }.getOrNull() }

    // ------------------------------------------------------------------
    // Konstanta BS.1770 / ReplayGain 2.0
    // ------------------------------------------------------------------
    const val DEFAULT_BUDGET_MS = 90_000L           // batas aman per lagu (~90 detik)
    const val REFERENCE_LUFS = -18.0f               // ReplayGain 2.0 reference level
    const val ABS_GATE_LUFS = -70.0f                // gate absolut
    const val RELATIVE_GATE_LU = 10.0f              // gate relatif: integrated - 10 LU

    private fun decodeAndMeasure(
        context: Context,
        uri: Uri,
        mimeTypeHint: String?,
        sampleDurationMs: Long?
    ): Pair<Float, Float>? {
        val deadline = System.nanoTime() + DEFAULT_BUDGET_MS * 1_000_000L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val trackMime = f.getString(MediaFormat.KEY_MIME)
                if (trackMime?.startsWith("audio/") == true) {
                    if (trackIndex == -1 || trackMime == mimeTypeHint) {
                        trackIndex = i; inputFormat = f
                    }
                }
            }
            val fmt = inputFormat ?: return null
            extractor.selectTrack(trackIndex)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()

                var pcmEncoding = fmt.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                var channelCount = fmt.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                val stats = LoudnessStats(fmt.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100))

                val info = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false

                while (!outputEos) {
                    if (System.nanoTime() > deadline) return null // timeout-safe: biarkan di-retry nanti

                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outF = codec.outputFormat
                            pcmEncoding = outF.intOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                            channelCount = outF.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        }
                        else -> if (outIdx >= 0) {
                            // Phase 3: Early exit for sampled analysis
                            if (sampleDurationMs != null && info.presentationTimeUs > sampleDurationMs * 1000) {
                                outputEos = true
                            }
                            
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floats = FloatArray(info.size / 4)
                                    outBuf.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)
                                    stats.consume(floats, channelCount)
                                } else {
                                    val shorts = ShortArray(info.size / 2)
                                    outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                    stats.consume(shorts, channelCount)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                        }
                    }
                }

                return stats.resultOrNull()
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** getInteger(key, default) butuh API 29 — pakai pola ini agar aman utk minSdk 26. */
    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}

/**
 * Akumulator energi K-weighted + gating blok 400 ms (hop 100 ms => overlap 75%).
 * Satu instans per lagu; dipanggil dari loop dekode pada thread IO.
 */
private class LoudnessStats(sampleRate: Int) {
    private val chainL = KWeightingChain(sampleRate)
    private val chainR = KWeightingChain(sampleRate)

    // Blok BS.1770: jendela 400 ms, hop 100 ms (4 sub-blok per jendela)
    private val SUB_BLOCK_SAMPLES = (sampleRate * 0.1f).toInt().coerceAtLeast(1) // 100 ms
    private val WINDOW_SUB_BLOCKS = 4                                            // 400 ms total

    private val windowSums = ArrayDeque<Double>() // energi sub-blok dalam jendela aktif
    private val blockEnergies = ArrayList<Double>(2048)
    private var subBlockCount = 0
    private var subBlockSum = 0.0
    private var peak = 0.0f

    fun consume(pcm: ShortArray, channels: Int) {
        val ch = if (channels >= 2) 2 else 1
        var i = 0
        while (i + ch <= pcm.size) {
            // int16 -> float normalisasi penuh-skala
            push(if (ch == 2) pcm[i] / 32768f else pcm[i] / 32768f, if (ch == 2) pcm[i + 1] / 32768f else pcm[i] / 32768f)
            i += ch
        }
    }

    fun consume(pcm: FloatArray, channels: Int) {
        val ch = if (channels >= 2) 2 else 1
        var i = 0
        while (i + ch <= pcm.size) {
            push(pcm[i], if (ch == 2) pcm[i + 1] else pcm[i])
            i += ch
        }
    }

    private fun push(l: Float, r: Float) {
        // Sample peak dari sinyal MENTAH (sebelum K-weighting), dBFS nanti dihitung dr linear
        val absL = kotlin.math.abs(l); val absR = kotlin.math.abs(r)
        val framePeak = maxOf(absL, absR)
        if (framePeak > peak) peak = framePeak
        val kL = chainL.process(l)
        val kR = chainR.process(r)
        subBlockSum += (kL.toDouble() * kL + kR.toDouble() * kR)
        if (++subBlockCount >= SUB_BLOCK_SAMPLES) {
            subBlockCount = 0
            windowSums.addLast(subBlockSum)
            subBlockSum = 0.0
            if (windowSums.size > WINDOW_SUB_BLOCKS) windowSums.removeFirst()
            if (windowSums.size == WINDOW_SUB_BLOCKS) blockEnergies.add(windowSums.sum())
        }
    }

    /** Integrated LUFS + peak setelah gating; null jika data tidak cukup. */
    fun resultOrNull(): Pair<Float, Float>? {
        if (blockEnergies.isEmpty()) return null
        // Gate absolut -70 LUFS: energi < ambang dibuang
        val absGateEnergy = 10.0.pow((LoudnessAnalyzer.ABS_GATE_LUFS + 0.691) / 10.0)
        val gated = blockEnergies.filter { it > absGateEnergy }
        if (gated.isEmpty()) return null
        val meanGated = gated.sum() / gated.size
        // Gate relatif: integrated - 10 LU => ambang energi dibagi 10^(10/10)
        val relThresholdEnergy = meanGated / 10.0.pow(LoudnessAnalyzer.RELATIVE_GATE_LU / 10.0)
        val surviving = gated.filter { it > relThresholdEnergy }
        val integratedEnergy = if (surviving.isNotEmpty()) surviving.sum() / surviving.size else meanGated
        val integratedLufs = -0.691f + 10.0f * log10(integratedEnergy.toFloat())

        // Gain kompatibel ReplayGain 2.0: target -18 LUFS, dibatasi headroom sample peak
        var gainDb = LoudnessAnalyzer.REFERENCE_LUFS - integratedLufs
        if (peak > 0f) {
            val headroomDb = -20.0f * log10(peak)
            if (headroomDb < gainDb) gainDb = headroomDb
        }
        val peakDbfs = if (peak > 0f) 20.0f * log10(peak) else -60.0f
        return Pair(gainDb, peakDbfs)
    }
}

/**
 * K-weighting ala BS.1770: dua biquad Direct Form I berantai.
 * Stage 1: high-shelf +4 dB @1500 Hz Q0.707 (RBJ cookbook).
 * Stage 2: high-pass 38 Hz Q0.5 (RBJ cookbook).
 */
private class KWeightingChain(sampleRate: Int) {
    private companion object {
        const val SHELF_GAIN_DB = 4.0
        const val SHELF_Q = 0.707
        const val HPF_Q = 0.5
    }

    // ---- Stage 1: high shelf ----
    private val shelf = run {
        val f0 = 1500.0
        val a = 10.0.pow(SHELF_GAIN_DB / 40.0)
        val w0 = 2.0 * Math.PI * f0 / sampleRate
        val cw = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) / (2.0 * SHELF_Q)
        val sqA = kotlin.math.sqrt(a)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sqA * alpha
        Biquad(
            b0 = (a * ((a + 1) + (a - 1) * cw + 2 * sqA * alpha) / a0).toFloat(),
            b1 = (-2.0 * a * ((a - 1) + (a + 1) * cw) / a0).toFloat(),
            b2 = (a * ((a + 1) + (a - 1) * cw - 2 * sqA * alpha) / a0).toFloat(),
            a1 = (2.0 * ((a - 1) - (a + 1) * cw) / a0).toFloat(),
            a2 = (((a + 1) - (a - 1) * cw - 2 * sqA * alpha) / a0).toFloat()
        )
    }

    // ---- Stage 2: high pass 38 Hz Q0.5 ----
    private val hpf = run {
        val f0 = 38.0
        val w0 = 2.0 * Math.PI * f0 / sampleRate
        val cw = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) / (2.0 * HPF_Q)
        val a0 = 1 + alpha
        Biquad(
            b0 = ((1 + cw) / 2 / a0).toFloat(),
            b1 = (-(1 + cw) / a0).toFloat(),
            b2 = ((1 + cw) / 2 / a0).toFloat(),
            a1 = (-2 * cw / a0).toFloat(),
            a2 = ((1 - alpha) / a0).toFloat()
        )
    }

    fun process(x: Float): Float = hpf.process(shelf.process(x))

    private class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
}

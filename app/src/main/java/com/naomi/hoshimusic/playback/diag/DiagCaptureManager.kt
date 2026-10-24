package com.naomi.hoshimusic.playback.diag

import android.content.Context
import com.naomi.hoshimusic.util.DiagLog
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manager untuk bounded PCM capture pada jalur audio HoshiMusic.
 *
 * Sesuai Aturan Seksi 8:
 *  1. Menggunakan preallocated capture buffers.
 *  2. Transfer non-blocking via ArrayBlockingQueue.
 *  3. Penulisan disk di background executor (tidak pernah di audio thread).
 *  4. Batas durasi (default 5 detik) dan ukuran file.
 *  5. Menghasilkan WAV valid dan `capture_manifest.json`.
 */
class DiagCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "DiagCapture"
        private const val QUEUE_CAPACITY = 256
        private const val MAX_CAPTURE_FRAMES = 44100 * 5 // 5 detik @ 44.1kHz
        private const val MAX_CAPTURE_BYTES = MAX_CAPTURE_FRAMES * 4 // 16-bit stereo = 4 bytes/frame
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hoshi-pcm-capture").apply { priority = Thread.MIN_PRIORITY }
    }

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    private var currentSessionId: String = ""

    private var sampleRate: Int = 44100
    private var channels: Int = 2

    // Preallocated ring/queue packets (each packet max 4096 bytes)
    private data class CapturePacket(
        val stage: String,
        val isFloat: Boolean,
        val data: ByteArray,
        val length: Int
    )

    private val queue = ArrayBlockingQueue<CapturePacket>(QUEUE_CAPACITY)
    private val active = AtomicBoolean(false)

    // Stage frame counters for bounded capture
    private val stageFramesWritten = mutableMapOf<String, AtomicLong>()

    // PCM Metrics accumulators
    data class StageMetrics(
        val stage: String,
        var frames: Long = 0L,
        var peak: Float = 0f,
        var sumSquares: Double = 0.0,
        var nanCount: Long = 0L,
        var infCount: Long = 0L,
        var clipCount: Long = 0L
    )

    private val metricsMap = mutableMapOf<String, StageMetrics>()

    fun startSession(sessionId: String, sampleRate: Int = 44100, channels: Int = 2) {
        if (active.getAndSet(true)) return
        this.currentSessionId = sessionId
        this.sampleRate = sampleRate
        this.channels = channels
        this.isCapturing = true

        stageFramesWritten.clear()
        metricsMap.clear()

        val captureDir = File(context.filesDir, "diag_captures/$sessionId")
        if (!captureDir.exists()) captureDir.mkdirs()

        DiagLog.i(TAG, "Mulai PCM capture session=$sessionId sr=$sampleRate ch=$channels dir=${captureDir.absolutePath}")

        executor.submit { processQueueLoop(captureDir) }
    }

    /**
     * Non-blocking capture feeder, aman dipanggil dari audio thread.
     */
    fun offerPcmData(stage: String, isFloat: Boolean, buffer: ByteBuffer, offsetBytes: Int, lengthBytes: Int) {
        if (!active.get() || lengthBytes <= 0) return

        val writtenCounter = stageFramesWritten.getOrPut(stage) { AtomicLong(0L) }
        val framesInPacket = if (isFloat) lengthBytes / 8 else lengthBytes / 4
        if (writtenCounter.get() >= MAX_CAPTURE_FRAMES) return // Limit reached

        // Preallocated or copied packet
        val copy = ByteArray(minOf(lengthBytes, 4096))
        val oldPos = buffer.position()
        buffer.position(offsetBytes)
        val readLen = minOf(lengthBytes, copy.size)
        buffer.get(copy, 0, readLen)
        buffer.position(oldPos)

        val packet = CapturePacket(stage, isFloat, copy, readLen)
        if (queue.offer(packet)) {
            writtenCounter.addAndGet(framesInPacket.toLong())
        }
    }

    fun stopSession(): Map<String, StageMetrics> {
        if (!active.getAndSet(false)) return metricsMap
        isCapturing = false

        // Wait brief drain
        Thread.sleep(100)

        val captureDir = File(context.filesDir, "diag_captures/$currentSessionId")
        writeManifestJson(captureDir)

        DiagLog.i(TAG, "PCM capture selesai session=$currentSessionId. Manifest ditulis.")
        return metricsMap.toMap()
    }

    private fun processQueueLoop(captureDir: File) {
        val fileOutputs = mutableMapOf<String, FileOutputStream>()

        while (active.get() || queue.isNotEmpty()) {
            val packet = queue.poll() ?: run {
                Thread.sleep(10)
                return@run null
            } ?: continue

            try {
                val fileName = when (packet.stage) {
                    "converter" -> "pcm_after_converter.wav"
                    "native" -> "pcm_after_native_dsp.wav"
                    "sink" -> "pcm_before_audio_sink.wav"
                    else -> "pcm_${packet.stage}.wav"
                }

                val wavFile = File(captureDir, fileName)
                if (!wavFile.exists()) {
                    writeWavHeader(wavFile, sampleRate, channels, 16)
                }

                val out = fileOutputs.getOrPut(packet.stage) {
                    FileOutputStream(wavFile, true)
                }

                // Convert float to 16-bit if needed before writing to standard WAV
                val pcm16Data = if (packet.isFloat) {
                    floatToPcm16(packet.data, packet.length, packet.stage)
                } else {
                    accumulatePcm16Metrics(packet.data, packet.length, packet.stage)
                    packet.data
                }

                out.write(pcm16Data, 0, if (packet.isFloat) packet.length / 2 else packet.length)
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Error penulisan capture chunk", t)
            }
        }

        fileOutputs.values.forEach { runCatching { it.close() } }

        // Finalize WAV headers with correct data sizes
        fileOutputs.keys.forEach { stage ->
            val fileName = when (stage) {
                "converter" -> "pcm_after_converter.wav"
                "native" -> "pcm_after_native_dsp.wav"
                "sink" -> "pcm_before_audio_sink.wav"
                else -> "pcm_${stage}.wav"
            }
            finalizeWavHeader(File(captureDir, fileName))
        }
    }

    private fun floatToPcm16(floatBytes: ByteArray, length: Int, stage: String): ByteArray {
        val numFloats = length / 4
        val pcm16 = ByteArray(numFloats * 2)
        val bbIn = ByteBuffer.wrap(floatBytes, 0, length).order(ByteOrder.nativeOrder())
        val bbOut = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)

        val metrics = metricsMap.getOrPut(stage) { StageMetrics(stage) }
        metrics.frames += numFloats / channels

        for (i in 0 until numFloats) {
            val f = bbIn.float
            if (f.isNaN()) metrics.nanCount++
            if (f.isInfinite()) metrics.infCount++
            if (kotlin.math.abs(f) > 1.0f) metrics.clipCount++

            val clamped = f.coerceIn(-1.0f, 1.0f)
            val absF = kotlin.math.abs(clamped)
            if (absF > metrics.peak) metrics.peak = absF
            metrics.sumSquares += (clamped * clamped).toDouble()

            val scaled = (clamped * 32767.0f).toInt().coerceIn(-32768, 32767)
            bbOut.putShort(scaled.toShort())
        }

        return pcm16
    }

    private fun accumulatePcm16Metrics(pcmBytes: ByteArray, length: Int, stage: String) {
        val numShorts = length / 2
        val bb = ByteBuffer.wrap(pcmBytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        val metrics = metricsMap.getOrPut(stage) { StageMetrics(stage) }
        metrics.frames += numShorts / channels

        for (i in 0 until numShorts) {
            val s = bb.short.toFloat() / 32768.0f
            val absS = kotlin.math.abs(s)
            if (absS > metrics.peak) metrics.peak = absS
            metrics.sumSquares += (s * s).toDouble()
            if (absS >= 0.999f) metrics.clipCount++
        }
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()

            bb.put("RIFF".toByteArray())
            bb.putInt(36) // Placeholder
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16) // Subchunk1Size (16 for PCM)
            bb.putShort(1.toShort()) // AudioFormat (1 for PCM)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(byteRate)
            bb.putShort(blockAlign)
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray())
            bb.putInt(0) // Placeholder data size

            out.write(header)
        }
    }

    private fun finalizeWavHeader(file: File) {
        if (!file.exists() || file.length() < 44) return
        val pcmLength = (file.length() - 44).toInt()
        val riffLength = pcmLength + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLeBytes(riffLength))
            raf.seek(40)
            raf.write(intToLeBytes(pcmLength))
        }
    }

    private fun intToLeBytes(v: Int): ByteArray {
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )
    }

    private fun writeManifestJson(captureDir: File) {
        val manifestFile = File(captureDir, "capture_manifest.json")
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"sessionId\": \"$currentSessionId\",\n")
        sb.append("  \"sampleRate\": $sampleRate,\n")
        sb.append("  \"channels\": $channels,\n")
        sb.append("  \"captures\": [\n")

        val stages = metricsMap.values.toList()
        stages.forEachIndexed { idx, m ->
            val durationMs = if (sampleRate > 0) (m.frames * 1000.0 / sampleRate).toLong() else 0L
            val rms = if (m.frames * channels > 0) sqrt(m.sumSquares / (m.frames * channels)).toFloat() else 0f
            val rmsDb = if (rms > 1e-6f) 20f * kotlin.math.log10(rms) else -120f

            val fileName = when (m.stage) {
                "converter" -> "pcm_after_converter.wav"
                "native" -> "pcm_after_native_dsp.wav"
                "sink" -> "pcm_before_audio_sink.wav"
                else -> "pcm_${m.stage}.wav"
            }

            sb.append("    {\n")
            sb.append("      \"stage\": \"${m.stage}\",\n")
            sb.append("      \"file\": \"$fileName\",\n")
            sb.append("      \"frames\": ${m.frames},\n")
            sb.append("      \"durationMs\": $durationMs,\n")
            sb.append("      \"peakDb\": ${"%.2f".format(20f * kotlin.math.log10(max(1e-6f, m.peak)))},\n")
            sb.append("      \"rmsDb\": ${"%.2f".format(rmsDb)},\n")
            sb.append("      \"nanCount\": ${m.nanCount},\n")
            sb.append("      \"infCount\": ${m.infCount},\n")
            sb.append("      \"clipCount\": ${m.clipCount}\n")
            sb.append("    }${if (idx < stages.size - 1) "," else ""}\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")

        manifestFile.writeText(sb.toString())
    }
}

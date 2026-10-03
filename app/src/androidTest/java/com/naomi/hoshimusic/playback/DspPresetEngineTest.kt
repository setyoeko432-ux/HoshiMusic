package com.naomi.hoshimusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naomi.hoshimusic.playback.dsp.DspParamResolver
import com.naomi.hoshimusic.playback.dsp.DspPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Uji auditif DSP preset terhadap mesin native asli.
 *
 * ## Kenapa ini ada
 *
 * `DspLifecycleTest` membuktikan mesin hidup (configure, flush, reset).
 * `DspPresetTest` (JVM) membuktikan parameter yang dihitung benar.
 *
 * Tidak ada yang membuktikan keduanya sampai-sampai audio. Dan itulah yang
 * justru gagal: parameter setiap preset berbeda, semua terkirim ke native,
 * `DspEngine::setParams` juga menyimpan dengan benar -- tapi preset yang
 * berbeda menghasilkan PCM yang identik. Semua indikator terlihat hijau
 * sementara tidak ada satu pun suara yang berbeda.
 *
 * Test ini menutup celah itu: ia benar-benar mendorong sinyal PCM yang sama
 * melewati `HoshiDspProcessor` dengan parameter preset yang berbeda, lalu
 * membandingkan PCM keluaran.
 *
 * Sinyal masukan sengaja deterministik (gabungan sinus murni dengan amplop
 * lambat) supaya kegagalan bisa direproduksi persis.
 */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class DspPresetEngineTest {

    private val sampleRate = 44100
    private val channels = 2

    /** Frame per segmen; cukup panjang untuk melewati semua crossfade. */
    private val framesPerChunk = 4096

    /** Segmen yang dibuang: AGC pre-gain dan crossfade wet masih berayun. */
    private val settleChunks = 3

    // ------------------------------------------------------------------ helper

    private fun assertLibraryLoaded() {
        assertTrue(
            "Native DSP library gagal dimuat; periksa JNI class path dan ABI",
            HoshiDspProcessor.libraryLoaded
        )
    }

    /**
     * Sinus 220 Hz + 2,2 kHz + 7,5 kHz dengan amplop yang berubah lambat,
     * supaya AGC, limiter, dan de-esser punya sesuatu untuk bekerja.
     * Deterministik, tanpa random.
     */
    private fun signalChunk(chunkIndex: Int, frames: Int): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(frames * channels * 2)
            .order(ByteOrder.nativeOrder())
        val t0 = chunkIndex.toDouble() * frames
        for (i in 0 until frames) {
            val t = (t0 + i) / sampleRate.toDouble()
            val envelope = 0.18 + 0.30 * sin(2.0 * PI * 0.7 * t)
            val s = (envelope * sin(2.0 * PI * 220.0 * t) +
                0.35 * envelope * sin(2.0 * PI * 2200.0 * t) +
                0.15 * envelope * sin(2.0 * PI * 7500.0 * t)).coerceIn(-0.95, 0.95)
            val v = (s * 32767.0).toInt().toShort()
            buffer.putShort(v)
            buffer.putShort(v)
        }
        buffer.flip()
        return buffer
    }

    private fun collect(processor: HoshiDspProcessor, presetLabel: String, chunks: Int): ShortArray {
        val collected = ArrayList<ShortArray>(chunks - settleChunks)
        repeat(chunks) { chunk ->
            processor.queueInput(signalChunk(chunk, framesPerChunk))
            val out = processor.getOutput()
            if (chunk >= settleChunks && out.hasRemaining()) {
                val dst = ShortArray(out.remaining() / 2)
                out.asShortBuffer().get(dst)
                collected.add(dst)
            }
        }
        processor.release()
        assertTrue("Tidak ada PCM yang terkumpul untuk $presetLabel", collected.isNotEmpty())
        return collected.fold(ShortArray(0)) { acc, next -> acc + next }
    }

    /**
     * Jalankan parameter mentah melewati mesin dan kembalikan PCM keluaran
     * setelah fase transisi selesai.
     */
    private fun renderParams(params: FloatArray, label: String, chunks: Int = 8): ShortArray {
        val processor = HoshiDspProcessor()
        val format = AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        assertEquals("Format keluaran harus sama dengan masuk", format, processor.configure(format))

        // Urutan penting: `isActuallyProcessing` cuma true kalau master switch
        // sudah menyala. MemeriksaNYA SEBELUM setAdaptiveDspEnabled(true)
        // selalu gagal -- itu kesalahannya bukan mesinnya.
        processor.setParams(params)
        processor.setAdaptiveDspEnabled(true)

        assertTrue(
            "Mesin native harus aktif setelah configure + enable ($label)",
            processor.isActuallyProcessing
        )
        return collect(processor, label, chunks)
    }

    /** Jalankan preset melalui jalur resolusi yang sama dengan produksi. */
    private fun renderPreset(presetId: String, chunks: Int = 8): ShortArray =
        renderParams(
            DspParamResolver.resolve(presetId, emptyList(), null),
            "preset '$presetId'",
            chunks
        )

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble() / 32768.0
            sum += v * v
        }
        return Math.sqrt(sum / samples.size)
    }

    /** Berapa banyak sample yang berbeda antara dua keluaran. */
    private fun diffCount(a: ShortArray, b: ShortArray): Int {
        val n = minOf(a.size, b.size)
        var diff = 0
        for (i in 0 until n) if (a[i] != b[i]) diff++
        return diff
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun libraryLoadsAndEngineProcesses() {
        assertLibraryLoaded()
        val out = renderPreset(DspPresets.REFERENCE_ID, chunks = 6)
        assertTrue("Mesin harus menghasilkan audio", out.isNotEmpty())
        assertTrue("Keluaran DSP tidak boleh diam", rms(out) > 0.001)
    }

    @Test
    fun presetBerbedaMenghasilkanAudioBerbeda() {
        assertLibraryLoaded()

        val reference = renderPreset(DspPresets.REFERENCE_ID)
        val warm = renderPreset("warm")
        val bright = renderPreset("bright")
        val bassBoost = renderPreset("bass_boost")
        val flat = renderPreset("flat")

        // Inilah regresi utamanya: dulu semua preset menghasilkan PCM yang
        // sama persis, sehingga test ini gagal serempak.
        val pairs = listOf(
            Triple("reference vs warm", reference, warm),
            Triple("warm vs bright", warm, bright),
            Triple("reference vs bass_boost", reference, bassBoost),
            Triple("reference vs flat", reference, flat),
            Triple("flat vs bass_boost", flat, bassBoost)
        )

        pairs.forEach { (label, a, b) ->
            assertEquals("Panjang keluaran '$label' harus sama", a.size, b.size)
            val diff = diffCount(a, b)
            val ratio = diff.toDouble() / a.size
            assertTrue(
                "Preset $label menghasilkan PCM yang nyaris identik " +
                    "($diff dari ${a.size} sample, ${(ratio * 100).toInt()}%). " +
                    "Inilah gejala 'semua preset terdengar sama'.",
                ratio > 0.01
            )
        }
    }

    @Test
    fun idTransientSesungguhnyaMembubahOutput() {
        assertLibraryLoaded()

        // DEFECT 5: prm[5] dulu tidak pernah dimuat di DspEngine::process(),
        // jadi dua preset yang hanya berbeda di indeks itu menghasilkan
        // output bit-identik. Bangun dua array yang identik kecuali indeks 5.
        val base = DspPresets.resolveParams(DspPresets.REFERENCE_ID, emptyList()).toFloatArray()
        val tanpaTransient = base.copyOf()
        val denganTransient = base.copyOf().apply {
            this[DspPresets.IDX_TRANSIENT] = 1.8f
        }

        val a = renderParams(tanpaTransient, "tanpa IDX_TRANSIENT")
        val b = renderParams(denganTransient, "dengan IDX_TRANSIENT")

        assertEquals("Panjang keluaran harus sama", a.size, b.size)
        assertTrue(
            "IDX_TRANSIENT masih tidak terbaca native; dua array yang hanya " +
                "berbeda di indeks 5 menghasilkan PCM identik",
            diffCount(a, b) > a.size / 100
        )
    }

    @Test
    fun presetFlatTidakMemotongBassDanUdara() {
        assertLibraryLoaded()

        // DEFECT 6: preset `flat` lama memakai 0f untuk bass dan air, yang di
        // native menjadi -4.5 dB dan -3.5 dB shelf -- jadi "flat" justru
        // memotong bass dan udara, bukan netral.
        val reference = renderPreset(DspPresets.REFERENCE_ID)
        val flat = renderPreset("flat")

        val shelfGains = DspParamResolver.presetShelfGainsDb(
            DspParamResolver.resolve("flat", emptyList(), null)
        )
        assertEquals("Shelf bass 'flat' harus 0 dB", 0f, shelfGains.first, 1e-4f)
        assertEquals("Shelf udara 'flat' harus 0 dB", 0f, shelfGains.second, 1e-4f)

        // Dan secara energi: flat tidak boleh jauh lebih pelan dari reference.
        assertTrue(
            "Keluaran 'flat' jauh lebih pelan dari reference " +
                "(rms ref=${rms(reference)}, rms flat=${rms(flat)})",
            rms(flat) >= rms(reference) * 0.80
        )
        assertTrue("Reference harus punya audio", rms(reference) > 0.001)
    }

    @Test
    fun ambienceMemperluasOutput() {
        assertLibraryLoaded()

        val paramsDry =
            DspParamResolver.resolve("stage", emptyList(), null, ambienceEnabled = false)
        val paramsWet =
            DspParamResolver.resolve("stage", emptyList(), null, ambienceEnabled = true)

        val dry = renderParams(paramsDry, "stage tanpa ambience")
        val wet = renderParams(paramsWet, "stage dengan ambience")

        assertTrue(
            "Ambience harus mengubah output (refleksi ruang ditambahkan)",
            diffCount(dry, wet) > dry.size / 100
        )
    }

    @Test
    fun outputTidakDiamBeruntun() {
        assertLibraryLoaded()

        val out = renderPreset("bass_boost", chunks = 10)
        assertTrue("Harus ada audio", rms(out) > 0.001)

        // `replaceOutputBuffer` mengisi nol, jadi ekor buffer yang tidak
        // tergisi muncul sebagai digital silence mendadak (klik/dropout).
        var trailingZeros = 0
        for (i in out.indices.reversed()) {
            if (out[i] != 0.toShort()) break
            trailingZeros++
        }
        assertTrue(
            "Ekor keluaran berisi $trailingZeros sample nol berturut-turut -- " +
                "menandakan output buffer tidak terisi penuh",
            trailingZeros < out.size / 10
        )
    }
}

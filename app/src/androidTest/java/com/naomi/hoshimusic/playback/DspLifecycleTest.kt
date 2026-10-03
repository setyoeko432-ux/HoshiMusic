package com.naomi.hoshimusic.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naomi.hoshimusic.playback.HoshiDspProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class DspLifecycleTest {

    @Test
    fun testProcessorToggleLifecycle() {
        assertTrue(
            "Native DSP library gagal dimuat; periksa JNI class path dan ABI",
            HoshiDspProcessor.libraryLoaded
        )
        
        val processor = HoshiDspProcessor()
        
        // 1. Initial State
        assertFalse("Processor harus OFF di awal", processor.isActuallyProcessing)
        
        // 2. Enable tanpa configure.
        //
        // [FIX] Dulu test ini `assertTrue(processor.isActive)` di sini, yang
        // mustahil: `isActive()` membaca `pendingOutputAudioFormat != NOT_SET`
        // dan format baru dinegosiasikan oleh `configure()`. Jadi test ini
        // gagal sejak awal, bukan karena mesinnya rusak -- artinya test
        // siklus-hidup DSP ini tidak pernah benar-benar hijau.
        processor.setEnabled(true)
        assertFalse(
            "isActive harus false sebelum configure: belum ada format keluaran yang dinegosiasikan",
            processor.isActive
        )
        assertFalse("isActuallyProcessing harus false sebelum configure", processor.isActuallyProcessing)

        // 3. Configure
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        val outputFormat = processor.configure(format)

        assertEquals(format.sampleRate, outputFormat.sampleRate)
        assertTrue("isActive harus true setelah format dinegosiasikan", processor.isActive)
        assertTrue("isActuallyProcessing harus true setelah configure", processor.isActuallyProcessing)

        // 4. Disable mid-stream
        //
        // [FIX] Dulu `assertFalse(processor.isActive)` — juga salah. Format
        // sudah dinegosiasikan, jadi processor TETAP anggota rantai; hanya
        // gate `dspUserEnabled` yang mati.(assertFalse hanya benar
        // kebetulan karena langkah 2 lebih dulu sudah membuatnya false.)
        processor.setEnabled(false)
        assertTrue(
            "Processor tetap aktif di rantai: format masih dinegosiasikan",
            processor.isActive
        )
        assertFalse(
            "isActuallyProcessing harus false: master switch mati",
            processor.isActuallyProcessing
        )
        
        // 5. Re-enable
        processor.setEnabled(true)
        assertTrue(processor.isActuallyProcessing)
        
        processor.release()
    }

    @Test
    fun testFlushAndReset() {
        assertTrue(
            "Native DSP library gagal dimuat; periksa JNI class path dan ABI",
            HoshiDspProcessor.libraryLoaded
        )
        val processor = HoshiDspProcessor()
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(48000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        
        val input = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder())
        processor.queueInput(input)
        
        processor.reset()
        assertFalse("Reset harus mendisfungsikan nativeInitialized", processor.isActuallyProcessing)
        
        processor.release()
    }
    
    private fun assertEquals(expected: Any, actual: Any) {
        if (expected != actual) throw AssertionError("Expected $expected but was $actual")
    }
}

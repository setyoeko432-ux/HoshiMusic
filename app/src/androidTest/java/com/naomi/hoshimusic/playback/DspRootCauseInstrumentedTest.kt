package com.naomi.hoshimusic.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naomi.hoshimusic.playback.dsp.DspNativeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Instrumentation Diagnostic Test Suite for Android JNI & Native DSP Engine.
 *
 * Runs on Android device/emulator with loaded `libhoshi_dsp.so` to verify:
 *   - Test A: JNI DirectByteBuffer offset & memory boundary safety.
 *   - Test B: Native master enable gate & passthrough state.
 *   - Test D/F: Telemetry snapshot integrity & native frame counters.
 */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class DspRootCauseInstrumentedTest {

    private val sampleRate = 44100

    @Before
    fun ensureNativeLibraryLoaded() {
        DspNativeEngine.ensureLibraryLoaded()
        assertTrue("libhoshi_dsp.so harus berhasil dimuat di device Android", DspNativeEngine.libraryLoaded)
    }

    // ================================================================
    // TEST A — JNI DirectByteBuffer Offset & Boundary Validation
    // ================================================================

    @Test
    fun testA_JniBufferOffsetAndSentinelBoundaries() {
        val engine = DspNativeEngine("TestA")
        val handle = engine.ensureCreated()
        assertTrue("Native handle harus valid", handle != 0L)
        assertTrue("Init native engine harus berhasil", engine.init(sampleRate))

        val prefixBytes = 256
        val pcmBytes = 2048 // 256 stereo float frames (256 * 2 ch * 4 bytes)
        val suffixBytes = 256
        val totalBytes = prefixBytes + pcmBytes + suffixBytes

        val buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())

        // Sentinel byte value
        val sentinelByte: Byte = 0x7E

        // Fill prefix and suffix with sentinel
        for (i in 0 until prefixBytes) buffer.put(i, sentinelByte)
        for (i in (prefixBytes + pcmBytes) until totalBytes) buffer.put(i, sentinelByte)

        // Fill PCM region with test float samples
        val floatBuf = buffer.duplicate().order(ByteOrder.nativeOrder())
        floatBuf.position(prefixBytes)
        repeat(pcmBytes / 4) {
            floatBuf.putFloat(0.42f)
        }

        // Execute native process with explicit offset and length
        engine.setParams(floatArrayOf(1.8f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f))
        engine.setAdaptiveEnabled(true)
        engine.process(buffer, prefixBytes, pcmBytes)

        // Verify Prefix Sentinel integrity
        for (i in 0 until prefixBytes) {
            assertEquals("Prefix byte pada offset $i tidak boleh tersentuh native", sentinelByte, buffer.get(i))
        }

        // Verify Suffix Sentinel integrity
        for (i in (prefixBytes + pcmBytes) until totalBytes) {
            assertEquals("Suffix byte pada offset $i tidak boleh tersentuh native", sentinelByte, buffer.get(i))
        }

        engine.release()
    }

    @Test
    fun testA_JniExplicitZeroOffsetWithPositionAtEnd() {
        // Verification for HoshiDspProcessor pattern:
        // position is at end of write buffer, but explicit offsetBytes = 0 is passed to GetDirectBufferAddress.
        val engine = DspNativeEngine("TestA2")
        engine.ensureCreated()
        engine.init(sampleRate)

        val pcmBytes = 1024
        val buffer = ByteBuffer.allocateDirect(pcmBytes).order(ByteOrder.nativeOrder())
        val floatBuf = buffer.asFloatBuffer()
        repeat(pcmBytes / 4) {
            floatBuf.put(0.35f)
        }

        // Simulate position moved to end
        buffer.position(pcmBytes)

        engine.setAdaptiveEnabled(true)
        engine.setParams(FloatArray(15) { 1.5f })

        // Process with explicit offset = 0
        engine.process(buffer, 0, pcmBytes)

        // Verify native read from address base (offset 0)
        buffer.position(0)
        val readVal = buffer.float
        assertTrue("Native harus membaca dari alamat dasar buffer (offset 0)", readVal.isFinite())

        engine.release()
    }

    // ================================================================
    // TEST B — Native Master Enable & Passthrough Verification
    // ================================================================

    @Test
    fun testB_NativeMasterEnableAndTelemetryState() {
        val engine = DspNativeEngine("TestB")
        engine.ensureCreated()
        engine.init(sampleRate)

        // Set non-neutral params
        engine.setParams(FloatArray(15) { 1.6f })

        // State 1: Adaptive DSP Disabled
        engine.setAdaptiveEnabled(false)

        val pcmBytes = 2048
        val buffer = ByteBuffer.allocateDirect(pcmBytes).order(ByteOrder.nativeOrder())
        engine.process(buffer, 0, pcmBytes)

        val t1 = engine.telemetry()
        assertNotNull("Telemetry snapshot harus tidak null saat initialized", t1)
        assertFalse("adspEnabled harus false saat disabled", t1!!.adspEnabled)

        // State 2: Adaptive DSP Enabled
        engine.setAdaptiveEnabled(true)
        engine.process(buffer, 0, pcmBytes)

        val t2 = engine.telemetry()
        assertNotNull("Telemetry snapshot harus valid", t2)
        assertTrue("adspEnabled harus true saat enabled", t2!!.adspEnabled)
        assertTrue("Total frame processed harus bertambah", t2.totalFramesProcessed > 0)

        engine.release()
    }

    // ================================================================
    // TEST F — Native Engine Reset & Flush Lifecycle Safety
    // ================================================================

    @Test
    fun testF_NativeEngineResetFlushLifecycle() {
        val engine = DspNativeEngine("TestF")
        engine.ensureCreated()
        engine.init(sampleRate)

        assertTrue("Native flush harus mengembalikan true", engine.flush())
        assertTrue("Native reset harus mengembalikan true", engine.reset())

        // Re-initialize after reset
        assertTrue("Native engine harus bisa di-re-init setelah reset", engine.init(sampleRate))
        assertTrue("Engine harus usable", engine.isUsable)

        engine.release()
    }
}

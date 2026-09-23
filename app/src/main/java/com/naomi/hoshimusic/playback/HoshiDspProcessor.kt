package com.naomi.hoshimusic.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.playback.dsp.DspTelemetry
import com.naomi.hoshimusic.playback.dsp.DspTelemetryBridge
import com.naomi.hoshimusic.playback.dsp.VocalTelemetryState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Hoshi Master Audiophile DSP Engine
 * JNI Bridge to high-performance C++ core.
 */
@UnstableApi
class HoshiDspProcessor : BaseAudioProcessor() {

    companion object {
        const val DSP_PARAM_COUNT = 15
        /** Maks band PEQ koreksi headphone — sinkron dengan PEQ_SLOTS mesin C++. */
        const val DSP_PEQ_MAX = 10
        private const val TAG = "HoshiDsp"

        var libraryLoaded = false
            private set

        init {
            try {
                System.loadLibrary("hoshi_dsp")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // android.util.Log.e(TAG, "Failed to load native library hoshi_dsp", e)
                libraryLoaded = false
            } catch (e: Exception) {
                libraryLoaded = false
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long, sampleRate: Int)
    private external fun nativeProcess(handle: Long, buffer: ByteBuffer, length: Int)
    private external fun nativeFlush(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetSystemVolume(handle: Long, volume: Float)
    private external fun nativeGetTelemetrySnapshot(handle: Long): DspTelemetry?
    
    private external fun nativeApplyPeqSnapshot(
        handle: Long,
        enabled: Boolean,
        preampDb: Float,
        count: Int,
        types: IntArray,
        fc: FloatArray,
        gain: FloatArray,
        q: FloatArray,
        revision: Int
    )
    
    private external fun nativeSetAdaptiveDspEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetParams(handle: Long, params: FloatArray)
    private external fun nativeSetBtClarity(handle: Long, enabled: Boolean)
    private external fun nativeSetAmbience(handle: Long, enabled: Boolean)
    private external fun nativeSetPreampGain(handle: Long, gain: Float)
    private var nativeHandle: Long = 0L
    private val nativeHandleAtomic = AtomicLong(0L)
    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var fallbackFrameCounter = 0L // [V21]: Counter for software path stats

    @Volatile
    private var nativeInitialized = false

    @Volatile
    private var released = false

    @Volatile
    var isHardwareSupported: Boolean = libraryLoaded
        private set

    private var currentInputEncoding = C.ENCODING_INVALID
    private var currentInputSampleRate = 0
    private var lastNativeSampleRate = 0
    private var configurationAttemptCount = 0
    private var warmupFrames = 0 // [V32]: Vivo hardware priming counter
    @Volatile
    private var isWarmupActive = false // [Issue 2]: Optimized warmup check

    private data class DspConfig(
        var adaptiveDspEnabled: Boolean = false,
        var peqEnabled: Boolean = false,
        var enabled: Boolean = false, // Legacy
        var params: FloatArray? = null,
        var btClarity: Boolean = false,
        var ambience: Boolean = false,
        var preampGain: Float = 1f,
        var spatialAudioEnabled: Boolean = false,
        var spatialAudioPreset: Int = 0,
        val peqBands: MutableMap<Int, PeqBandConfig> = mutableMapOf(),
        var peqCount: Int = 0
    )

    private data class PeqBandConfig(
        val type: Int,
        val fc: Float,
        val gain: Float,
        val q: Float
    )

    private val desiredConfig = DspConfig()

    @Volatile
    var boutiqueBypassActive: Boolean = false

    private fun pushAllStateToNative() {
        if (!libraryLoaded || nativeHandle == 0L) return
        // [V20]: Use a snapshot to avoid long locks on the Audio Thread
        val config = synchronized(desiredConfig) { desiredConfig.copy(params = desiredConfig.params?.copyOf()) }
        
        rwLock.read {
            if (nativeHandle != 0L) {
                nativeSetAdaptiveDspEnabled(nativeHandle, config.adaptiveDspEnabled)
                nativeSetEnabled(nativeHandle, config.enabled)
                config.params?.let { nativeSetParams(nativeHandle, it) }
                nativeSetBtClarity(nativeHandle, config.btClarity)
                nativeSetAmbience(nativeHandle, config.ambience)
                nativeSetPreampGain(nativeHandle, config.preampGain)
                
                // [V35]: Use snapshot for bands initial push
                val bands = config.peqBands.values.map { 
                    com.naomi.hoshimusic.data.PeqBand(it.type, it.fc, it.gain, it.q)
                }
                applyPeqSnapshot(config.peqEnabled, 0f, bands) // Preamp applied separately in legacy mode or combined here
            }
        }
    }

    @Volatile
    var telemetry: DspTelemetry = DspTelemetry()
        private set

    /** Wrapper aman untuk JNI call. */
    private inline fun safeNativeCall(block: () -> Unit): Boolean {
        if (!libraryLoaded) return false
        return try {
            block()
            true
        } catch (e: UnsatisfiedLinkError) {
            libraryLoaded = false
            false
        }
    }

    // NOTE: directBuffer only grows (allocateDirect) and never shrinks by design
    // to avoid frequent GC pressure. Audio buffer sizes are typically small enough 
    // that this memory retention is negligible compared to the overhead of re-allocation.
    private var directBuffer: ByteBuffer = EMPTY_BUFFER

    // Carry-over buffer to ensure Float32 stereo frame alignment (8 bytes)
    private val pendingBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())

    /**
     * Honest status: DSP is truly active only if native library is loaded, 
     * processor is initialized, and it's enabled by user (either PEQ or Adaptive DSP).
     */
    val isActuallyProcessing: Boolean get() {
        val handle = nativeHandleAtomic.get()
        return libraryLoaded && (desiredConfig.adaptiveDspEnabled || desiredConfig.peqEnabled || desiredConfig.preampGain != 1f) && handle != 0L
    }

    override fun isActive(): Boolean {
        return libraryLoaded
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        android.util.Log.d(TAG, "onConfigure: input=$inputAudioFormat (Boutique=$boutiqueBypassActive)")
        
        val formatChanged = currentInputSampleRate != inputAudioFormat.sampleRate || 
                            currentInputEncoding != inputAudioFormat.encoding

        currentInputEncoding = inputAudioFormat.encoding
        currentInputSampleRate = inputAudioFormat.sampleRate

        if (formatChanged) {
            warmupFrames = 4800 // Approx 100ms at 48kHz
            isWarmupActive = true
            android.util.Log.i(TAG, "onConfigure: format change, scheduled 4800 frames of digital warm-up")
        }

        // [Issue 12]: Dynamic rate matching for Boutique Bypass.
        // Force at least 48kHz to ensure zero-silence on exclusive paths,
        // but allow Hi-Res (96k/192k) to pass through if present.
        val outputSampleRate = if (boutiqueBypassActive) {
            maxOf(48000, inputAudioFormat.sampleRate)
        } else {
            inputAudioFormat.sampleRate
        }

        if (boutiqueBypassActive) {
            android.util.Log.i(TAG, "Boutique Bypass active: forcing output rate to ${outputSampleRate}Hz")
        }

        // [V26]: Create native handle as soon as possible to allow telemetry to report 
        // "initialized" even during Media3 format negotiation.
        if (nativeHandle == 0L && libraryLoaded) {
            android.util.Log.d(TAG, "onConfigure: creating native handle early")
            nativeHandle = nativeCreate()
            nativeHandleAtomic.set(nativeHandle)
        }

        // Hoshi DSP core only supports PCM Float Stereo.
        // [V30]: Universal Format Support - Accept 16-bit if Media3 refuses to convert after 2 attempts
        val needsFloat = inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        val needsResampling = inputAudioFormat.sampleRate != outputSampleRate
        val needsStereo = inputAudioFormat.channelCount != 2

        if ((needsFloat || needsResampling || needsStereo) && configurationAttemptCount < 2) {
            configurationAttemptCount++
            android.util.Log.d(TAG, "onConfigure: format mismatch (attempt $configurationAttemptCount), requesting PCM_FLOAT Stereo @ ${outputSampleRate}Hz")
            nativeInitialized = false 
            return AudioFormat(outputSampleRate, 2, C.ENCODING_PCM_FLOAT)
        }
        
        // Accepted (either correct format or fallback to manual conversion in queueInput)
        configurationAttemptCount = 0
        
        if (nativeHandle == 0L) {
            android.util.Log.e(TAG, "onConfigure: failed to create native handle")
            isHardwareSupported = false
            return inputAudioFormat
        }

        android.util.Log.d(TAG, "onConfigure: initializing native DSP at ${inputAudioFormat.sampleRate}Hz")
        nativeInitialized = true // Set early to allow telemetry to flow as soon as handle exists
        if (safeNativeCall { nativeInit(nativeHandle, inputAudioFormat.sampleRate) }) {
            lastNativeSampleRate = inputAudioFormat.sampleRate
            isHardwareSupported = true
            android.util.Log.i(TAG, "onConfigure: native DSP initialized successfully")
            // [V19]: Preallocate large direct buffer here (64 KB) to avoid any
            // allocations in the high-priority Audio Thread (Priority 3.D).
            if (directBuffer.capacity() < 65536) {
                // [V27]: allocateDirect is typically page-aligned (4KB) on Android, 
                // which ensures the 16-byte alignment required for optimal ARM NEON SIMD performance.
                directBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
            }
            pushAllStateToNative()
        } else {
            android.util.Log.e(TAG, "onConfigure: safeNativeCall to nativeInit failed")
            isHardwareSupported = false
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val handle = nativeHandleAtomic.get()
        // Fast path for inactive/uninitialized state
        if (handle == 0L || !nativeInitialized || !libraryLoaded || 
            !(desiredConfig.adaptiveDspEnabled || desiredConfig.peqEnabled || desiredConfig.preampGain != 1f) || 
            !isHardwareSupported) {
            processSoftwareFallback(inputBuffer)
            return
        }

        // [V30]: Universal Support - Call nativeInit kembali jika sample rate berubah di tengah stream
        if (currentInputSampleRate != lastNativeSampleRate) {
            if (safeNativeCall { nativeInit(handle, currentInputSampleRate) }) {
                lastNativeSampleRate = currentInputSampleRate
            }
        }

        val is16Bit = currentInputEncoding == C.ENCODING_PCM_16BIT
        val inputFrameSize = if (is16Bit) 4 else 8
        val inputRemaining = inputBuffer.remaining()
        if (inputRemaining == 0 && pendingBuffer.position() == 0 && !isWarmupActive) return

        val totalAvailable = inputRemaining + pendingBuffer.position()
        val framesToProcess = totalAvailable / inputFrameSize
        
        // [V32]: Tangani digital warm-up priming
        val framesToWarmup = if (isWarmupActive) warmupFrames else 0

        if (framesToProcess > 0 || framesToWarmup > 0) {
            val totalFramesOut = framesToProcess + framesToWarmup
            val bytesToProcessNative = totalFramesOut * 8 // Native selalu pakai Float (8 bytes/frame)
            
            // [V19]: SAFETY GUARD - Lewati pemrosesan jika buffer overflow (jarang)
            if (directBuffer.capacity() < bytesToProcessNative) {
                val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                return
            }
            directBuffer.clear()

            // 1. Priming dengan silence
            if (isWarmupActive && framesToWarmup > 0) {
                repeat(framesToWarmup * 2) { directBuffer.putFloat(0f) }
                warmupFrames = 0
                isWarmupActive = false
            }

            // 2. Konversi data asli
            if (framesToProcess > 0) {
                if (is16Bit) {
                    pendingBuffer.flip()
                    while (pendingBuffer.remaining() >= 2) {
                        directBuffer.putFloat(pendingBuffer.short / 32768f)
                    }
                    
                    if (pendingBuffer.hasRemaining()) {
                        val b1 = pendingBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt() and 0xFF
                        val s = ((b2 shl 8) or b1).toShort()
                        directBuffer.putFloat(s / 32768f)
                    }
                    pendingBuffer.clear()

                    while (directBuffer.hasRemaining() && inputBuffer.remaining() >= 2) {
                        directBuffer.putFloat(inputBuffer.short / 32768f)
                    }
                } else {
                    if (pendingBuffer.position() > 0) {
                        pendingBuffer.flip()
                        directBuffer.put(pendingBuffer)
                        pendingBuffer.clear()
                    }

                    val neededFromInput = bytesToProcessNative - directBuffer.position()
                    val originalLimit = inputBuffer.limit()
                    inputBuffer.limit(inputBuffer.position() + neededFromInput)
                    directBuffer.put(inputBuffer)
                    inputBuffer.limit(originalLimit)
                }
            }

            // 3. Pemrosesan Native
            directBuffer.flip()
            // Panggil langsung tanpa safeNativeCall untuk performa (handle sudah divalidasi)
            nativeProcess(handle, directBuffer, bytesToProcessNative)

            // 4. Ekspos ke pipeline Media3
            if (is16Bit) {
                val outputBuffer = replaceOutputBuffer(totalFramesOut * 4)
                directBuffer.flip()
                while (directBuffer.hasRemaining()) {
                    val f = directBuffer.float
                    outputBuffer.putShort((f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort())
                }
                outputBuffer.flip()
            } else {
                val outputBuffer = replaceOutputBuffer(bytesToProcessNative)
                outputBuffer.put(directBuffer)
                outputBuffer.flip()
            }
        }

        if (inputBuffer.hasRemaining()) {
            pendingBuffer.put(inputBuffer)
        }
    }

    /** [V21]: Zero-allocation Software Fallback (Simple Preamp Gain) */
    private fun processSoftwareFallback(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        
        val is16Bit = currentInputEncoding == C.ENCODING_PCM_16BIT
        val outputBuffer = replaceOutputBuffer(remaining)
        val gain = desiredConfig.preampGain
        
        if (java.lang.Math.abs(gain - 1.0f) < 0.01f) {
            outputBuffer.put(inputBuffer)
        } else {
            if (is16Bit) {
                while (inputBuffer.remaining() >= 2 && outputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short / 32768f
                    val scaled = (sample * gain).coerceIn(-1.0f, 1.0f)
                    outputBuffer.putShort((scaled * 32767.0f).toInt().toShort())
                }
            } else {
                while (inputBuffer.remaining() >= 4 && outputBuffer.hasRemaining()) {
                    val sample = inputBuffer.float
                    val scaled = sample * gain
                    outputBuffer.putFloat(scaled.coerceIn(-1.0f, 1.0f))
                }
            }
        }
        val frameSize = if (is16Bit) 4 else 8
        fallbackFrameCounter += remaining / frameSize
        outputBuffer.flip()
    }

    /** 
     * Memperbarui snapshot telemetry dari native. 
     * Dipanggil dari coroutine luar agar tidak membebani audio thread (Priority 1).
     */
    fun updateTelemetry(): DspTelemetry? {
        val handle = nativeHandleAtomic.get()
        val now = android.os.SystemClock.elapsedRealtime()
        
        if (libraryLoaded && handle != 0L) {
            val snapshot = try {
                nativeGetTelemetrySnapshot(handle)
            } catch (e: UnsatisfiedLinkError) { null }
            
            val base = snapshot ?: DspTelemetry(nativeInitialized = true)
            val frames = base.totalFramesProcessed + fallbackFrameCounter
            
            val combined = base.copy(
                totalFramesProcessed = frames,
                requestedPeqRevision = peqRevision,
                nativeInitialized = true
            )
            telemetry = combined
            
            // [V33]: Create robust vocal telemetry state
            val isProcessing = isActuallyProcessing && !boutiqueBypassActive
            val isValid = isProcessing && (frames > lastConfirmedFrames)
            if (frames > lastConfirmedFrames) {
                lastConfirmedFrames = frames
                lastTelemetryUpdateTime = now
            }

            val freshness = now - lastTelemetryUpdateTime
            val telemetryState = VocalTelemetryState(
                vocalEnergy = base.vocalEnergy,
                isValid = isValid && freshness < 500L,
                isProcessingAudio = isProcessing,
                frameProgress = frames,
                updatedAtMs = now
            )
            
            DspTelemetryBridge.update(combined, telemetryState)
            return combined
        } else {
            val softTelemetry = DspTelemetry(
                totalFramesProcessed = fallbackFrameCounter,
                nativeInitialized = false,
                dominantBand = "SOFTWARE BYPASS"
            )
            telemetry = softTelemetry
            val telemetryState = VocalTelemetryState(
                vocalEnergy = 0f,
                isValid = false,
                isProcessingAudio = false,
                frameProgress = fallbackFrameCounter,
                updatedAtMs = now
            )
            DspTelemetryBridge.update(softTelemetry, telemetryState)
            return softTelemetry
        }
    }

    private var lastConfirmedFrames = 0L
    private var lastTelemetryUpdateTime = 0L

    override fun onFlush() {
        val handle = nativeHandleAtomic.get()
        if (nativeInitialized && handle != 0L) {
            safeNativeCall { nativeFlush(handle) }
        }
        directBuffer = EMPTY_BUFFER
        pendingBuffer.clear()
    }

    override fun onReset() {
        val handle = nativeHandleAtomic.get()
        if (nativeInitialized && handle != 0L) {
            safeNativeCall { nativeReset(handle) }
        }
        nativeInitialized = false
        directBuffer = EMPTY_BUFFER
        pendingBuffer.clear()
    }

    /** 
     * Melepaskan resource native secara permanen. 
     * Dipanggil saat service dihancurkan.
     */
    fun release() {
        rwLock.write {
            if (libraryLoaded && !released && nativeHandle != 0L) {
                try {
                    nativeRelease(nativeHandle)
                    nativeHandle = 0L
                    nativeHandleAtomic.set(0L)
                    released = true
                } catch (e: UnsatisfiedLinkError) {
                    android.util.Log.e(TAG, "Failed to release native DSP handle", e)
                }
            }
            nativeHandle = 0L // Ensure nulled even if library failed
            nativeHandleAtomic.set(0L)
            nativeInitialized = false
            isHardwareSupported = false
        }
    }

    /**
     * Updates the system volume context for the DSP engine.
     * TODO: This MUST be called from a Volume Change Listener (e.g., via AudioManager hook)
     * for Fletcher-Munson Equal Loudness compensation to function correctly.
     */
    fun setSystemVolume(volume: Float) {
        val handle = nativeHandleAtomic.get()
        if (nativeInitialized && handle != 0L) {
            safeNativeCall { nativeSetSystemVolume(handle, volume) }
        }
    }

    /** Master bypass DSP Engine (false = passthrough Media3 murni). */
    fun setEnabled(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.enabled == enabled && desiredConfig.adaptiveDspEnabled == enabled) return
            desiredConfig.enabled = enabled
            desiredConfig.adaptiveDspEnabled = enabled
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetAdaptiveDspEnabled(handle, enabled) }
            }
        }
    }

    /** Aktifkan/matikan Adaptive Hoshi DSP Engine secara independen. */
    fun setAdaptiveDspEnabled(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.adaptiveDspEnabled == enabled) return
            desiredConfig.adaptiveDspEnabled = enabled
            desiredConfig.enabled = enabled // keep in sync for legacy
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetAdaptiveDspEnabled(handle, enabled) }
            }
        }
    }

    /** Aktifkan/matikan Manual Equalizer secara independen. */
    fun setPeqEnabled(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.peqEnabled == enabled) return
            desiredConfig.peqEnabled = enabled
        }
    }

    /**
     * Terapkan parameter preset (15 float). Guard: dipadatkan/dilengkapi ke tepat 15 nilai.
     * Nilai hilang dianggap 1f (perilaku default yang sudah di-tuning).
     */
    fun setParams(params: FloatArray) {
        val fixed = FloatArray(DSP_PARAM_COUNT) { i -> params.getOrElse(i) { 1f } }
        synchronized(desiredConfig) {
            desiredConfig.params = fixed.copyOf()
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetParams(handle, fixed) }
            }
        }
    }

    /** BT Clarity Guard — aktif saat output speaker bluetooth generik (kualitas SBC). */
    fun setBtClarity(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.btClarity == enabled) return
            desiredConfig.btClarity = enabled
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetBtClarity(handle, enabled) }
            }
        }
    }

    /** Ambience Mode (Eksperimen): FDN early-reflection halus. Default OFF. */
    fun setAmbience(enabled: Boolean) {
        synchronized(desiredConfig) {
            if (desiredConfig.ambience == enabled) return
            desiredConfig.ambience = enabled
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetAmbience(handle, enabled) }
            }
        }
    }

    /** Preamp linear (1.0 = netral). Diterapkan paling depan di mesin C++. */
    fun setPreampGain(linear: Float) {
        synchronized(desiredConfig) {
            if (desiredConfig.preampGain == linear) return
            desiredConfig.preampGain = linear
            val handle = nativeHandleAtomic.get()
            if (nativeInitialized && handle != 0L) {
                safeNativeCall { nativeSetPreampGain(handle, linear) }
            }
        }
    }

    /** Spatial Audio: Adjust DSP parameters (width and crossfeed) based on preset. */
    fun setSpatialAudio(enabled: Boolean, preset: Int) {
        if (desiredConfig.spatialAudioEnabled == enabled && desiredConfig.spatialAudioPreset == preset) return

        val updated = desiredConfig.copy(
            spatialAudioEnabled = enabled,
            spatialAudioPreset = preset
        )

        val widthAdj = when (preset) {
            1 -> 1.40f  // Stage: wide stereo
            2 -> 0.60f  // Close: intimate
            else -> 1.00f // Standard: neutral
        }
        val crossfeedAdj = when (preset) {
            1 -> 0.30f  // Stage: minimal crossfeed
            2 -> 1.20f  // Close: more crossfeed
            else -> 1.00f // Standard: default
        }

        val currentParams = updated.params?.clone() ?: return
        currentParams[DspPresets.IDX_WIDTH] = (currentParams[DspPresets.IDX_WIDTH] * widthAdj).coerceIn(0f, 2.0f)
        currentParams[DspPresets.IDX_CROSSFEED] = (currentParams[DspPresets.IDX_CROSSFEED] * crossfeedAdj).coerceIn(0f, 1.5f)
        setParams(currentParams)
    }

    private var peqRevision = 0

    /** Menerapkan seluruh konfigurasi PEQ secara atomik menggunakan double-buffering native. */
    fun applyPeqSnapshot(enabled: Boolean, preampDb: Float, bands: List<com.naomi.hoshimusic.data.PeqBand>) {
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
            desiredConfig.preampGain = Math.pow(10.0, preampDb.toDouble() / 20.0).toFloat()
        }

        val handle = nativeHandleAtomic.get()
        if (nativeInitialized && handle != 0L) {
            safeNativeCall {
                nativeApplyPeqSnapshot(handle, enabled, preampDb, count, types, fc, gain, q, revision)
            }
        }
    }
}

package com.naomi.hoshimusic.playback.dsp

import androidx.media3.common.util.UnstableApi
import com.naomi.hoshimusic.playback.HoshiDspProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Koordinator runtime DSP yang bertanggung jawab untuk menerapkan konfigurasi
 * ke HoshiDspProcessor secara atomik.
 *
 * Pipeline Order (kritis):
 * 1. config.params → Preset DSP (denoise, normalize, clip prevention — pemrosesan audio utama)
 * 2. setEnabled/setAdaptiveDspEnabled → Aktifkan DSP engine
 * 3. BT Clarity → Pembersihan sinyal Bluetooth
 * 4. Ambience → Reverb modifier (diterapkan setelah preset agar memodifikasi output preset)
 * 5. Preamp → Gain linear (diterapkan di native C++ layer)
 */
@UnstableApi
class DspRuntimeCoordinator(
    private val scope: CoroutineScope,
    private val configRepository: DspConfigRepository
) {
    private var processor: HoshiDspProcessor? = null
    private var configJob: Job? = null

    private val _currentConfig = MutableStateFlow(DspRuntimeConfig())
    val currentConfig = _currentConfig.asStateFlow()

    fun attachProcessor(dspProcessor: HoshiDspProcessor) {
        processor = dspProcessor
        startObserving()
    }

    fun detachProcessor() {
        configJob?.cancel()
        configJob = null
        processor = null
    }

    private fun startObserving() {
        configJob?.cancel()
        configJob = scope.launch(Dispatchers.Main) {
            configRepository.configFlow.collect { config ->
                _currentConfig.value = config
                applyConfig(config)
            }
        }
    }

    private fun applyConfig(config: DspRuntimeConfig) {
        val dsp = processor ?: return
        
        // [Issue 1&5]: Audio Pipeline Order: Raw -> DSP Clean -> EQ Shaping -> Output
        // Pushing configuration atomik to native engine.
        
        // 1. EQ Shaping & Filters (Cleaning)
        val spatialParams = config.params.clone()
        if (config.spatialAudioEnabled) {
            val widthAdj = when (config.spatialAudioPreset) {
                1 -> 1.40f
                2 -> 0.60f
                else -> 1.00f
            }
            val crossfeedAdj = when (config.spatialAudioPreset) {
                1 -> 0.30f
                2 -> 1.20f
                else -> 1.00f
            }
            spatialParams[DspPresets.IDX_WIDTH] = (spatialParams[DspPresets.IDX_WIDTH] * widthAdj).coerceIn(0f, 2.0f)
            spatialParams[DspPresets.IDX_CROSSFEED] = (spatialParams[DspPresets.IDX_CROSSFEED] * crossfeedAdj).coerceIn(0f, 1.5f)
        }
        dsp.setParams(spatialParams)
        
        // 2. Modifiers
        dsp.setBtClarity(config.btClarityEnabled)
        dsp.setAmbience(config.ambienceEnabled)
        dsp.setPreampGain(config.preampLinear)
        
        // 3. Parametric EQ (Connected)
        dsp.applyPeqSnapshot(config.peqEnabled, 0f, config.peqBands)
        
        // 4. Master Enable
        dsp.setAdaptiveDspEnabled(config.adaptiveDspEnabled)
        dsp.setEnabled(config.enabled)
    }
}

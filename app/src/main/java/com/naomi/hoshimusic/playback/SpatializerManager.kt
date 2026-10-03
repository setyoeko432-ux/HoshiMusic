package com.naomi.hoshimusic.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [P2]: Manager untuk Android 13+ (API 33+) android.media.Spatializer.
 *
 * Catatan Teknis:
 * - Spatializer membutuhkan output device yang mendukung (biasanya headphone atau speaker built-in tertentu).
 * - Tidak semua format audio sumber kompatibel.
 * - Jika device/API tidak mendukung, SpatializerManager akan melaporkan fallback
 *   sehingga sistem beralih secara otomatis ke custom Crossfeed DSP.
 */
class SpatializerManager(private val context: Context) {

    data class SpatializerState(
        val isSupportedByDevice: Boolean = false,
        val canBeSpatialized: Boolean = false,
        val isEnabled: Boolean = false,
        val isFallbackToCrossfeed: Boolean = true,
        val activePreset: Int = 0
    )

    private val _state = MutableStateFlow(SpatializerState())
    val state: StateFlow<SpatializerState> = _state.asStateFlow()

    private var spatializerListener: Any? = null

    fun initialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            setupSpatializerApi33()
        } else {
            Log.i(TAG, "Device SDK (${Build.VERSION.SDK_INT}) < 33. Platform Spatializer not available, using custom crossfeed.")
            _state.value = SpatializerState(
                isSupportedByDevice = false,
                canBeSpatialized = false,
                isEnabled = false,
                isFallbackToCrossfeed = true
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.S_V2)
    private fun setupSpatializerApi33() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val spatializer = audioManager.spatializer

            val isAvailable = spatializer.isAvailable
            val sampleAudioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val sampleAudioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setSampleRate(48000)
                .build()

            val canSpatialize = spatializer.canBeSpatialized(sampleAudioAttributes, sampleAudioFormat)

            Log.i(TAG, "Platform Spatializer isAvailable=$isAvailable, canBeSpatialized=$canSpatialize")

            _state.value = _state.value.copy(
                isSupportedByDevice = isAvailable,
                canBeSpatialized = canSpatialize,
                isFallbackToCrossfeed = !isAvailable || !canSpatialize
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val listener = object : Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) {
                        Log.d(TAG, "OnSpatializerEnabledChanged: enabled=$enabled")
                        _state.value = _state.value.copy(isEnabled = enabled)
                    }
                    override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) {
                        Log.d(TAG, "OnSpatializerAvailableChanged: available=$available")
                        _state.value = _state.value.copy(canBeSpatialized = available)
                    }
                }
                spatializer.addOnSpatializerStateChangedListener(context.mainExecutor, listener)
                spatializerListener = listener
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup Spatializer API: ${e.message}")
            _state.value = SpatializerState(isSupportedByDevice = false, isFallbackToCrossfeed = true)
        }
    }

    fun updateSpatialAudioConfig(enabled: Boolean, preset: Int) {
        val currentState = _state.value
        if (enabled && currentState.isSupportedByDevice && !currentState.isFallbackToCrossfeed) {
            _state.value = currentState.copy(
                isEnabled = true,
                activePreset = preset,
                isFallbackToCrossfeed = false
            )
            Log.i(TAG, "Spatial Audio activated using Platform Spatializer (preset=$preset)")
        } else {
            _state.value = currentState.copy(
                isEnabled = false,
                activePreset = preset,
                isFallbackToCrossfeed = true
            )
            if (enabled) {
                Log.i(TAG, "Spatial Audio activated using Custom Crossfeed DSP fallback (preset=$preset)")
            } else {
                Log.d(TAG, "Spatial Audio disabled")
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && spatializerListener != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val listener = spatializerListener as Spatializer.OnSpatializerStateChangedListener
                audioManager.spatializer.removeOnSpatializerStateChangedListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister Spatializer listener: ${e.message}")
            }
        }
        spatializerListener = null
    }

    private companion object {
        const val TAG = "SpatializerManager"
    }
}

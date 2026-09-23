package com.naomi.hoshimusic.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.util.Log
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
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.naomi.hoshimusic.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Wrapper untuk DefaultAudioSink yang menambahkan dukungan perutean Bit-Perfect 
 * dan AudioMixerAttributes untuk Android 14+.
 */
@UnstableApi
class HoshiAudioSink(
    private val context: Context,
    private val preferences: UserPreferences,
    private val dspProcessor: HoshiDspProcessor?
) : AudioSink {

    private val outputProvider = HoshiAudioOutputProvider(context, preferences)
    
    private val delegate: DefaultAudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(dspProcessor?.let { arrayOf(it) } ?: emptyArray())
        .setEnableFloatOutput(true)
        .setAudioOutputProvider(outputProvider)
        .build()

    fun updateRouting(device: AudioDeviceInfo?) {
        Log.d("HoshiAudioSink", "Updating preferred device: ${device?.productName ?: "Default"}")
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
    ) = delegate.configure(inputFormat, specifiedBufferSize, outputChannels)

    override fun play() = delegate.play()
    
    override fun handleDiscontinuity() = delegate.handleDiscontinuity()

    @Throws(InitializationException::class, WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = delegate.playToEndOfStream()
    
    override fun isEnded(): Boolean = delegate.isEnded()
    
    override fun hasPendingData(): Boolean = delegate.hasPendingData()
    
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
    
    override fun setVolume(volume: Float) = delegate.setVolume(volume)
    
    override fun pause() = delegate.pause()
    
    override fun flush() = delegate.flush()
    
    override fun reset() = delegate.reset()
    
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
    
    init {
        scope.launch {
            preferences.wiredBitPerfectPreferred.collectLatest { enabled ->
                bitPerfectPreferredCache = enabled
            }
        }
    }

    private var lastPreferredDevice: AudioDeviceInfo? = null
    
    private val delegate = AudioTrackAudioOutputProvider.Builder(context)
        .setAudioTrackBuilderModifier { builder, _ ->
            val device = lastPreferredDevice
            val bitPerfectPreferred = bitPerfectPreferredCache
            
            if (bitPerfectPreferred && device != null && isUsbDevice(device)) {
                if (Build.VERSION.SDK_INT >= 34) {
                    applyMixerAttributes(builder, device)
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

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig) = delegate.getAudioOutput(config)
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
                Log.i("HoshiAudio", "Applied BIT_PERFECT MixerAttributes for ${device.productName}")
            } catch (e: Exception) {
                // Jika compileSdk sudah benar, pemanggilan langsung lebih baik
                // builder.setAudioMixerAttributes(bitPerfectAttr)
                Log.e("HoshiAudio", "Reflection call for setAudioMixerAttributes failed", e)
            }
        }
    }
}

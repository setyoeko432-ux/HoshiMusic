package com.naomi.hoshimusic.playback.route

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Mendeteksi perangkat audio kabel (3.5mm/USB) untuk kebutuhan perutean Bit-Perfect.
 */
class WiredRoutingManager(
    private val context: Context,
    private val onRoutingChanged: (AudioDeviceInfo?) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            evaluateRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            evaluateRouting()
        }
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        evaluateRouting()
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun evaluateRouting() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        // Prioritas perutean eksplisit untuk Bit-Perfect: USB > Wired Headphones/Headset
        val preferredDevice = devices.firstOrNull { it.type in USB_TYPES }
            ?: devices.firstOrNull { it.type in WIRED_TYPES }

        onRoutingChanged(preferredDevice)
    }

    companion object {
        private val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        )
    }
}

package com.naomi.hoshimusic.playback.route

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.util.UnstableApi

@UnstableApi
class VolumeObserver(
    private val context: Context,
    private val onVolumeChanged: (Float) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun register() {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, this
        )
        updateDspVolume() // Push initial volume
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        updateDspVolume()
    }

    private fun updateDspVolume() {
        try {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
            
            val volumeRatio = if (maxVol > 0f) currentVol / maxVol else 1.0f
            
            // Send the 0.0 to 1.0 ratio to the C++ Engine for Fletcher-Munson calculation
            onVolumeChanged(volumeRatio)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

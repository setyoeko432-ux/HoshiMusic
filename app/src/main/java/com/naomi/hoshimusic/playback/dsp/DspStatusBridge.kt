package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.playback.AudioPathStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Menyalurkan status jalur DSP yang SEBENARNYA dari service ke UI.
 * Sebelumnya service menghitung status ini lalu membuangnya ke field yang tidak
 * pernah dibaca, sementara UI menebak dari preferensi user.
 */
object DspStatusBridge {
    private val _status = MutableStateFlow(AudioPathStatus.DSP_BYPASS)
    val status = _status.asStateFlow()

    fun update(value: AudioPathStatus) {
        _status.value = value
    }
}

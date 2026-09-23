package com.naomi.hoshimusic.ui.playback

import androidx.media3.session.MediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.naomi.hoshimusic.ui.PlaybackUiState

internal class PlaybackTimerController(
    private val scope: CoroutineScope,
    private val mutableUiState: MutableStateFlow<PlaybackUiState>
) {
    private var sleepTimerJob: Job? = null

    fun setSleepTimer(controller: MediaController?, minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            mutableUiState.value = mutableUiState.value.copy(sleepTimerEndsAtMs = null)
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        mutableUiState.value = mutableUiState.value.copy(sleepTimerEndsAtMs = endAt)
        sleepTimerJob = scope.launch {
            while (isActive && System.currentTimeMillis() < endAt) {
                delay(1_000L)
            }
            controller?.pause()
            mutableUiState.value = mutableUiState.value.copy(sleepTimerEndsAtMs = null)
        }
    }
}

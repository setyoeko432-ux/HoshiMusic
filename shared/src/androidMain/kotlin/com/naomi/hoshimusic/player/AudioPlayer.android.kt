package com.naomi.hoshimusic.player

import com.naomi.hoshimusic.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class HoshiAudioPlayer actual constructor() {
    private val _playbackState = MutableStateFlow(PlaybackState())
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    actual val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    actual fun play(song: Song) {
        _currentSong.value = song
        _playbackState.value = PlaybackState(isPlaying = true, durationMs = song.durationMs)
    }

    actual fun pause() {
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    actual fun resume() {
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    actual fun stop() {
        _playbackState.value = PlaybackState()
        _currentSong.value = null
    }

    actual fun seekTo(positionMs: Long) {
        _playbackState.value = _playbackState.value.copy(currentTimeMs = positionMs)
    }

    actual fun setVolume(volume: Float) {}
}

actual class PlaylistQueue actual constructor() {
    private val _currentQueue = MutableStateFlow<List<Song>>(emptyList())
    actual val currentQueue: StateFlow<List<Song>> = _currentQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    actual val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    actual val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(0)
    actual val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    actual fun setQueue(songs: List<Song>, startIndex: Int) {
        _currentQueue.value = songs
        _currentIndex.value = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
    }

    actual fun next(): Song? {
        val queue = _currentQueue.value
        if (queue.isEmpty()) return null
        val nextIdx = (_currentIndex.value + 1) % queue.size
        _currentIndex.value = nextIdx
        return queue.getOrNull(nextIdx)
    }

    actual fun previous(): Song? {
        val queue = _currentQueue.value
        if (queue.isEmpty()) return null
        val prevIdx = if (_currentIndex.value - 1 < 0) queue.size - 1 else _currentIndex.value - 1
        _currentIndex.value = prevIdx
        return queue.getOrNull(prevIdx)
    }

    actual fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
    }

    actual fun cycleRepeat() {
        _repeatMode.value = (_repeatMode.value + 1) % 3
    }
}

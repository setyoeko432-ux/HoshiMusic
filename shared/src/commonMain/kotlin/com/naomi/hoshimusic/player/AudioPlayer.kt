package com.naomi.hoshimusic.player

import com.naomi.hoshimusic.data.Song
import kotlinx.coroutines.flow.StateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val error: String? = null
)

expect class HoshiAudioPlayer() {
    val playbackState: StateFlow<PlaybackState>
    val currentSong: StateFlow<Song?>

    fun play(song: Song)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
}

expect class PlaylistQueue() {
    val currentQueue: StateFlow<List<Song>>
    val currentIndex: StateFlow<Int>
    val isShuffleEnabled: StateFlow<Boolean>
    val repeatMode: StateFlow<Int>

    fun setQueue(songs: List<Song>, startIndex: Int = 0)
    fun next(): Song?
    fun previous(): Song?
    fun toggleShuffle()
    fun cycleRepeat()
}

package com.naomi.hoshimusic.ui.playback

import androidx.media3.session.MediaController
import com.naomi.hoshimusic.data.Song

internal class QueueController {
    fun playSongs(controller: MediaController?, songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || controller == null) return
        val safeIndex = startIndex.coerceIn(songs.indices)
        controller.setMediaItems(songs.map(Song::toMediaItem), safeIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun playNext(controller: MediaController?, song: Song) {
        controller?.let { active ->
            val insertAt = (active.currentMediaItemIndex + 1).coerceAtMost(active.mediaItemCount)
            active.addMediaItem(insertAt, song.toMediaItem())
        }
    }

    fun addToQueue(controller: MediaController?, song: Song) {
        controller?.addMediaItem(song.toMediaItem())
    }

    fun moveQueueItem(controller: MediaController?, fromIndex: Int, toIndex: Int) {
        controller?.let { active ->
            if (fromIndex !in 0 until active.mediaItemCount || toIndex !in 0 until active.mediaItemCount || fromIndex == toIndex) return
            active.moveMediaItem(fromIndex, toIndex)
        }
    }

    fun removeQueueItem(controller: MediaController?, index: Int) {
        controller?.let { active ->
            if (index !in 0 until active.mediaItemCount || active.mediaItemCount <= 1) return
            active.removeMediaItem(index)
        }
    }

    fun seekToQueueItem(controller: MediaController?, index: Int) {
        controller?.let { active ->
            if (index in 0 until active.mediaItemCount) {
                active.seekToDefaultPosition(index)
                active.play()
            }
        }
    }
}

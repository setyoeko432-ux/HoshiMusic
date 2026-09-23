package com.naomi.hoshimusic.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

internal class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "playback_state",
        Context.MODE_PRIVATE,
    )

    fun save(player: Player) {
        runCatching {
            val queue = JSONArray()
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val metadata = item.mediaMetadata
                queue.put(
                    JSONObject()
                        .put("id", item.mediaId)
                        .put("title", metadata.title?.toString().orEmpty())
                        .put("artist", metadata.artist?.toString().orEmpty())
                        .put("album", metadata.albumTitle?.toString().orEmpty())
                        .put("artwork", metadata.artworkUri?.toString().orEmpty())
                        .put("audio_uri", metadata.extras?.getString("hoshi.audio_uri").orEmpty())
                )
            }
            val position = player.currentPosition
                .takeUnless { it == C.TIME_UNSET }
                ?.coerceAtLeast(0L)
                ?: 0L
            preferences.edit()
                .putString(KEY_QUEUE, queue.toString())
                .putInt(KEY_INDEX, player.currentMediaItemIndex.coerceAtLeast(0))
                .putLong(KEY_POSITION, position)
                .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
                .putInt(KEY_REPEAT, player.repeatMode)
                .apply()
        }
    }

    fun restore(player: Player) {
        val rawQueue = preferences.getString(KEY_QUEUE, null) ?: return
        runCatching {
            val json = JSONArray(rawQueue)
            val items = buildList {
                for (index in 0 until json.length()) {
                    val entry = json.getJSONObject(index)
                    val mediaId = entry.optString("id")
                    if (mediaId.isBlank()) continue
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(entry.optString("title", "Tanpa judul"))
                        .setArtist(entry.optString("artist", "Artis tidak diketahui"))
                        .setAlbumTitle(entry.optString("album", "Album tidak diketahui"))
                    entry.optString("artwork")
                        .takeIf(String::isNotBlank)
                        ?.let { metadataBuilder.setArtworkUri(Uri.parse(it)) }
                    val audioUri = entry.optString("audio_uri")
                    if (!audioUri.startsWith("content://")) continue
                    
                    val extras = Bundle().apply {
                        putString("hoshi.audio_uri", audioUri)
                    }
                    add(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(Uri.parse(audioUri))
                            .setMediaMetadata(metadataBuilder.setExtras(extras).build())
                            .build()
                    )
                }
            }
            if (items.isEmpty()) {
                player.clearMediaItems()
                return@runCatching
            }
            val restoredIndex = preferences.getInt(KEY_INDEX, 0)
                .coerceIn(items.indices)
            val restoredPosition = preferences.getLong(KEY_POSITION, 0L)
                .coerceAtLeast(0L)
            player.shuffleModeEnabled = preferences.getBoolean(KEY_SHUFFLE, false)
            player.repeatMode = preferences.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
            player.setMediaItems(items, restoredIndex, restoredPosition)
            player.prepare()
        }.onFailure {
            player.clearMediaItems()
        }
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}

package com.naomi.hoshimusic.ui.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.naomi.hoshimusic.data.MediaMapper
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.playback.AudioPathStatus
import com.naomi.hoshimusic.playback.AudioSignalPathState
import com.naomi.hoshimusic.playback.SourceAudioFormat
import com.naomi.hoshimusic.ui.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class PlaybackStateSync(
    private val mutableUiState: MutableStateFlow<PlaybackUiState>,
    private val positionMsFlow: MutableStateFlow<Long>
) {
    private var transitionStartedAtMs = 0L

    fun syncState(controller: MediaController?, knownSongs: Map<String, Song>, masterVolume: Float, refreshQueue: Boolean = false) {
        val active = controller ?: run {
            mutableUiState.update { it.copy(isConnected = false) }
            return
        }
        
        val item = active.currentMediaItem
        val duration = active.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        positionMsFlow.value = active.currentPosition.coerceAtLeast(0L)
        
        val previous = mutableUiState.value
        
        // [A11] Sticky Metadata Logic:
        // When transitioning between songs (e.g., calling setMediaItems), Media3 briefly reports 
        // a null currentMediaItem. We bridge this gap by retaining the previous metadata if 
        // the player is busy and not intentionally IDLE.
        val currentMediaId = item?.mediaId
        val song = currentMediaId?.let { knownSongs[it] }
        
        val nowMs = System.currentTimeMillis()
        val canBridge = currentMediaId == null &&
                active.playbackState == Player.STATE_BUFFERING &&
                active.mediaItemCount > 0

        if (currentMediaId != null) {
            transitionStartedAtMs = 0L
        } else if (canBridge && transitionStartedAtMs == 0L) {
            transitionStartedAtMs = nowMs
        }

        val isTransitioning = canBridge &&
                transitionStartedAtMs != 0L &&
                (nowMs - transitionStartedAtMs) < STICKY_HOLD_MS

        val nextMediaId = currentMediaId ?: if (isTransitioning) previous.mediaId else null
        val sameTrackAsBefore = nextMediaId != null && nextMediaId == previous.mediaId

        // Urutan prioritas: database lokal -> metadata Media3 -> nilai sebelumnya.
        // `previous` HARUS jadi pilihan terakhir, bukan sebelum Media3, kalau tidak
        // judul bisa terkunci permanen pada nilai placeholder.
        val resolvedTitle = song?.title
            ?: item?.mediaMetadata?.title?.toString()
            ?: if (sameTrackAsBefore || isTransitioning) previous.title else "Belum ada lagu"

        val resolvedArtist = song?.artist
            ?: item?.mediaMetadata?.artist?.toString()
            ?: if (sameTrackAsBefore || isTransitioning) previous.artist else "Pilih lagu dari koleksi"

        // [V25]: Swap priority - Enriched runtime metadata (high-res) wins over static DB entry.
        val resolvedArtwork = item?.mediaMetadata?.artworkUri
            ?: song?.artworkUri
            ?: if (sameTrackAsBefore || isTransitioning) previous.artworkUri else null

        val sourceFormat = SourceAudioFormat(
            mime = song?.mimeType ?: "unknown",
            sampleRate = song?.sampleRate ?: 0,
            bitDepth = song?.bitDepth ?: 0,
            channels = song?.channels ?: 2
        )
        
        val currentStatus = previous.audioPathStatus
        val sinkCap = previous.audioRoute.sinkCapability

        // [V45]: Bit-perfect logic refined. We no longer claim bit-perfect just because formats match.
        // It requires explicit verification from the routing layer which is not yet fully plumbed.
        // For now, we set it to false and let the UI handle "No conversion detected" status.
        val bitPerfect = false

        val signalPath = AudioSignalPathState(
            source = sourceFormat,
            sink = sinkCap,
            status = currentStatus,
            actualSampleRate = null, // Will be filled from telemetry if available
            actualBitDepth = null,
            isBitPerfect = bitPerfect,
            btClarityActive = previous.btClarityActive
        )

        val next = previous.copy(
            isConnected = true, 
            mediaId = nextMediaId,
            title = resolvedTitle,
            artist = resolvedArtist,
            artworkUri = resolvedArtwork,
            isPlaying = active.isPlaying, 
            durationMs = if (duration > 0) duration else if (isTransitioning) previous.durationMs else 0L,
            shuffleEnabled = active.shuffleModeEnabled, 
            repeatMode = active.repeatMode,
            queueMediaIds = if ((refreshQueue || previous.queueMediaIds.isEmpty()) && !isTransitioning)
                (0 until active.mediaItemCount).map { active.getMediaItemAt(it).mediaId }
            else previous.queueMediaIds,
            queueSongs = if ((refreshQueue || previous.queueSongs.isEmpty()) && !isTransitioning)
                (0 until active.mediaItemCount).map { idx ->
                    val mItem = active.getMediaItemAt(idx)
                    knownSongs[mItem.mediaId] ?: MediaMapper.fromMediaItem(mItem)
                }
            else previous.queueSongs,
            currentQueueIndex = active.currentMediaItemIndex, 
            playbackSpeed = active.playbackParameters.speed, 
            outputVolume = masterVolume,
            signalPath = signalPath
        )

        if (next != previous) {
            mutableUiState.value = next
        }
    }

    private companion object {
        const val STICKY_HOLD_MS = 400L
    }
}

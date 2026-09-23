package com.naomi.hoshimusic.ui.playback

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.naomi.hoshimusic.data.LyricsRepository
import com.naomi.hoshimusic.data.LyricsResult
import com.naomi.hoshimusic.data.ReplayGainReader
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.PlaybackUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.pow

@androidx.media3.common.util.UnstableApi
internal class MediaEnhancementManager(
    private val context: Context,
    private val lyricsRepository: LyricsRepository,
    private val replayGainReader: ReplayGainReader,
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
    private val mutableUiState: MutableStateFlow<PlaybackUiState>,
    private val onReplayGainUpdated: (Float) -> Unit
) {
    private var enhancementJob: Job? = null
    private var paletteJob: Job? = null

    fun loadCurrentEnhancements(mediaId: String, song: Song) {
        val isNewSong = mutableUiState.value.mediaId != mediaId
        onReplayGainUpdated(1f)
        if (isNewSong) {
            mutableUiState.update { it.copy(lyrics = LyricsResult(), replayGainDb = null) }
        }
        
        enhancementJob?.cancel()
        enhancementJob = scope.launch {
            if (isNewSong) {
                mutableUiState.update { it.copy(lyrics = LyricsResult(sourceName = song.displayName)) }
            }
            val lyrics = lyricsRepository.load(
                song, 
                mutableUiState.value.lyricPriority, 
                mutableUiState.value.romanizationEnabled,
                mutableUiState.value.translationMode
            )
            
            val gainDb = song.replayGainTrack ?: song.replayGainAlbum ?: song.analyzedGainDb 
                ?: replayGainReader.read(song.contentUri)?.effectiveGainDb()
            
            if (mutableUiState.value.mediaId == mediaId) {
                val gainLinear = gainDb?.let { 10.0.pow(it.toDouble() / 20.0).toFloat() } ?: 1f
                onReplayGainUpdated(gainLinear)
                mutableUiState.update { it.copy(lyrics = lyrics, replayGainDb = gainDb) }
            }
        }
        
        if (mutableUiState.value.dynamicColorEnabled && (isNewSong || mutableUiState.value.accentColor == Color.Unspecified)) {
            paletteJob?.cancel()
            paletteJob = scope.launch {
                val accent = song.artworkUri?.let { extractAccentColor(it) } ?: Color.Unspecified
                if (mutableUiState.value.mediaId == mediaId) {
                    mutableUiState.update { it.copy(accentColor = accent) }
                }
            }
        }
    }

    private suspend fun extractAccentColor(uri: Uri): Color = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(256)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                bitmap?.let { 
                    val palette = Palette.from(it).generate()
                    val swatch = palette.vibrantSwatch ?: palette.dominantSwatch
                    swatch?.rgb?.let { Color(it) }
                }
            } else null
        }.getOrNull() ?: Color.Unspecified
    }
    
    fun cancelAll() {
        enhancementJob?.cancel()
        paletteJob?.cancel()
    }
}

package com.naomi.hoshimusic.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import com.naomi.hoshimusic.data.LyricDisplayMode
import com.naomi.hoshimusic.data.LyricSourcePriority
import com.naomi.hoshimusic.data.LyricsResult
import com.naomi.hoshimusic.data.PeqProfile
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.playback.AudioPathStatus
import com.naomi.hoshimusic.playback.route.AudioRouteState
import com.naomi.hoshimusic.playback.AudioSignalPathState
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset

@Immutable
internal data class PlaybackUiState(
    val isConnected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "Belum ada lagu",
    val artist: String = "Pilih lagu dari koleksi",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueMediaIds: List<String> = emptyList(),
    val queueSongs: List<Song> = emptyList(),
    val currentQueueIndex: Int = -1,
    val playbackSpeed: Float = 1f,
    val sleepTimerEndsAtMs: Long? = null,
    val audioRoute: AudioRouteState = AudioRouteState(),
    val outputVolume: Float = 1f,
    val volumeGuardActive: Boolean = false,
    val replayGainDb: Float? = null,
    val lyrics: LyricsResult = LyricsResult(),
    val accentColor: Color = Color.Unspecified,
    val lyricPriority: LyricSourcePriority = LyricSourcePriority.EXTERNAL_FIRST,
    val gaplessEnabled: Boolean = true,
    val normalizationEnabled: Boolean = false,
    val dynamicColorEnabled: Boolean = true,
    val appTheme: Int = 0,
    val romanizationEnabled: Boolean = true,
    val translationMode: Boolean = false,
    val displayMode: LyricDisplayMode = LyricDisplayMode.ORIGINAL,
    val landscapeDualPaneEnabled: Boolean = false,
    val dspEnabled: Boolean = false,
    val peqEnabled: Boolean = false,
    val dspPresetId: String = "reference",
    val customDspPresets: List<CustomDspPreset> = emptyList(),
    val btClarityActive: Boolean = false,
    val btGuardMode: Int = 0,
    val ambienceEnabled: Boolean = false,
    val preampDb: Float = 0f,
    val spatialAudioEnabled: Boolean = false,
    val spatialAudioPreset: Int = 0,
    val peqProfiles: List<PeqProfile> = emptyList(),
    val activePeqProfileId: String = "",
    val routeProfiles: Map<String, String> = emptyMap(),
    
    // Audio Pipeline Telemetry
    val signalPath: AudioSignalPathState = AudioSignalPathState(),
    val audioPathStatus: AudioPathStatus = AudioPathStatus.UNKNOWN_FORMAT,
    val autoSkipIntroOutro: Boolean = false,
    val boutiqueBypassEnabled: Boolean = false
)

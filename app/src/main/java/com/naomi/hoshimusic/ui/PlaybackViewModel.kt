package com.naomi.hoshimusic.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import com.google.common.util.concurrent.ListenableFuture
import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.data.metadata.InstrumentalTransitionDetector
import com.naomi.hoshimusic.util.HoshiRomanizer
import com.naomi.hoshimusic.playback.*
import com.naomi.hoshimusic.playback.dsp.*
import com.naomi.hoshimusic.playback.dsp.DspStatusBridge
import com.naomi.hoshimusic.playback.route.*
import com.naomi.hoshimusic.ui.playback.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.core.content.ContextCompat
import com.naomi.hoshimusic.util.DiagLog

@androidx.media3.common.util.UnstableApi
internal class PlaybackViewModel(application: Application) : AndroidViewModel(application), Player.Listener {
    private val appContext = application.applicationContext
    private val repository = MusicRepository(appContext)
    internal val lyricsRepository = LyricsRepository(appContext)
    private val replayGainReader = ReplayGainReader(appContext)
    private val preferences = UserPreferences(appContext)
    private val routeMonitor = AudioRouteMonitor(appContext)
    
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlayback: Pair<List<Song>, Int>? = null
    
    private var knownSongs: Map<String, Song> = emptyMap()
    private var replayGainLinear = 1f
    private var masterVolume = 1f
    private var wasUsbConnected = false
    private var lastTickMs = 0L
    private var accumulatedDeltaMs = 0L
    
    private val mutableUiState = MutableStateFlow(PlaybackUiState())
    val uiState = mutableUiState.asStateFlow()

    private val instrumentalDetector = InstrumentalTransitionDetector()

    // handleAutoSkip() jalan tiap detik, tapi baris vokal pertama/terakhir hanya
    // berubah saat daftar lirik berganti. Tanpa cache, tiap detakan memindai
    // seluruh baris lirik dua kali.
    private var cachedVocalLines: List<LyricLine>? = null
    private var firstVocalMs: Long? = null
    private var vocalEndMs: Long? = null

    private fun refreshVocalBounds(lines: List<LyricLine>) {
        if (cachedVocalLines === lines) return
        cachedVocalLines = lines
        firstVocalMs = lines.firstOrNull { it.annotationType == LyricAnnotationType.VOCAL }?.timestampMs
        vocalEndMs = lines.lastOrNull { it.annotationType == LyricAnnotationType.VOCAL }
            ?.let { if (it.vocalEndMs > 0) it.vocalEndMs else it.timestampMs + 2000L }
    }
    private val _presentationState = MutableStateFlow<LyricPresentationState>(LyricPresentationState.None)
    val presentationState = _presentationState.asStateFlow()

    val dspTelemetryFlow = DspTelemetryBridge.telemetry
        .onStart { DspTelemetryBridge.incrementSubscribers() }
        .onCompletion { DspTelemetryBridge.decrementSubscribers() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DspTelemetry())

    val vocalTelemetryFlow = DspTelemetryBridge.vocalState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val miniPlayerState: StateFlow<MiniPlayerState> = mutableUiState
        .map { MiniPlayerState(it.mediaId, it.title, it.artist, it.artworkUri, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniPlayerState())

    private val _positionMsFlow = MutableStateFlow(0L)
    val positionMsFlow = _positionMsFlow.asStateFlow()

    private val _liveDeltaMsFlow = MutableStateFlow(0L)
    val liveDeltaMsFlow = _liveDeltaMsFlow.asStateFlow()

    // Sub-controllers
    private val queueController = QueueController()
    private val timerController = PlaybackTimerController(viewModelScope, mutableUiState)
    private val enhancementManager = MediaEnhancementManager(
        context = appContext,
        lyricsRepository = lyricsRepository,
        replayGainReader = replayGainReader,
        imageLoader = appContext.imageLoader,
        scope = viewModelScope,
        mutableUiState = mutableUiState,
        onReplayGainUpdated = { replayGainLinear = it; applyOutputVolume() }
    )
    private val stateSync = PlaybackStateSync(mutableUiState, _positionMsFlow)
    private val prefCoordinator = PreferenceCoordinator(
        scope = viewModelScope,
        preferences = preferences,
        mutableUiState = mutableUiState,
        onReloadEnhancements = { loadCurrentEnhancements(it) },
        onApplyOutputVolume = { applyOutputVolume() },
        onSyncState = { syncState() },
        onRecallPresetForRoute = { recallPresetForRoute(it) }
    )

    internal val prefCoordinatorRef: PreferenceCoordinator get() = prefCoordinator
    internal val preferencesRef: UserPreferences get() = preferences

    init {
        connect()
        prefCoordinator.setupObservers()

        // [V17]: Warm up the Japanese dictionary in the background on startup
        viewModelScope.launch(Dispatchers.Default) {
            HoshiRomanizer.prepare()
        }
        
        viewModelScope.launch {
            repository.allSongs.collect { songs ->
                updateLibrary(songs)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(POSITION_INTERVAL_MS)
                val active = controller ?: continue
                val pos = active.currentPosition.coerceAtLeast(0L)
                _positionMsFlow.value = pos
                updatePresentationState(pos)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(LISTEN_TICK_MS)
                tickListeningTime()
            }
        }
        viewModelScope.launch {
            DspStatusBridge.status.collect { status ->
                android.util.Log.d("PlaybackViewModel", "Received DSP status: $status")
                mutableUiState.update { it.copy(audioPathStatus = status) }
                // signalPath ikut diturunkan dari audioPathStatus, jadi paksa recompute
                // agar Info sheet tidak sempat menampilkan kombinasi tidak konsisten.
                syncState()
            }
        }
        viewModelScope.launch {
            routeMonitor.state.collect { route ->
                if (route.isUsb && !wasUsbConnected) {
                    masterVolume = USB_SAFE_VOLUME
                    mutableUiState.update { it.copy(audioRoute = route, outputVolume = masterVolume, volumeGuardActive = true) }
                    applyOutputVolume()
                } else {
                    mutableUiState.update { it.copy(audioRoute = route) }
                }
                wasUsbConnected = route.isUsb
                recallPresetForRoute(mutableUiState.value.routeProfiles)
            }
        }
    }

    private fun updatePresentationState(pos: Long, isDiscontinuity: Boolean = false) {
        val lyrics = mutableUiState.value.lyrics.lines
        if (lyrics.isEmpty()) {
            _presentationState.value = LyricPresentationState.None
            return
        }

        val binaryIdx = lyrics.binarySearch { it.timestampMs.compareTo(pos) }
        val timeIdx = if (binaryIdx < 0) -(binaryIdx + 2) else binaryIdx
        
        if (timeIdx < 0) {
            // Check if we are in intro gap
            val firstLine = lyrics.first()
            if (firstLine.text == LrcConstants.INSTRUMENTAL_MARKER || firstLine.instrumentalGap != null) {
                // If the very first line is instrumental or has a gap, handle it?
                // Actually, LrcParser ensures 0L line exists if intro is long.
            }
            _presentationState.value = LyricPresentationState.None
            return
        }

        val currentLine = lyrics[timeIdx]
        
        // [V33]: Structural presentation resolving
        when (currentLine.annotationType) {
            LyricAnnotationType.SECTION_LABEL -> {
                _presentationState.value = LyricPresentationState.SectionLabel(timeIdx, currentLine.text)
            }
            LyricAnnotationType.SEPARATOR -> {
                _presentationState.value = LyricPresentationState.Vocal(timeIdx) // Render separator as normal line
            }
            else -> {
                val confidence = if (currentLine.tokens.isNotEmpty()) VocalTimingConfidence.HIGH else VocalTimingConfidence.MEDIUM
                _presentationState.value = instrumentalDetector.update(
                    playbackPos = pos,
                    currentLineIndex = timeIdx,
                    annotation = currentLine.instrumentalAnnotation,
                    confidence = confidence,
                    telemetry = vocalTelemetryFlow.value,
                    isDiscontinuity = isDiscontinuity
                )
            }
        }
    }

    private fun currentRouteKey(): String? {
        val route = mutableUiState.value.audioRoute
        if (route.name.isBlank()) return null
        return "${route.name}|${route.type.name}"
    }

    private var lastRecalledRouteKey: String? = null

    private fun recallPresetForRoute(map: Map<String, String>) {
        val key = currentRouteKey() ?: return
        if (key == lastRecalledRouteKey) return
        lastRecalledRouteKey = key
        val mapped = map[key] ?: return
        if (mapped != mutableUiState.value.dspPresetId) {
            viewModelScope.launch {
                // [DEFECT 1] Recall rute menulis `dspPresetId` langsung tanpa
                // pernah menyentuh `DspPreviewBridge`. Dulu itu berarti bridge
                // tetap memegang array preset sebelumnya: UI berganti ke preset
                // hasil recall, mesin tetap memainkan preset lama.
                //
                // Dua lapis sekarang diamankan. (1) Jembatan sudah menyimpan
                // `sourcePresetId`, jadi begitu `presetId` berubah, array basi
                // otomatis diabaikan oleh `DspConfigRepository`.
                // (2) Bridge juga dikosongkan di sini supaya recall berikutnya
                // tidak pernah menebak apakah array lama masih relevan.
                DspPreviewBridge.clearPreviewParams()
                preferences.setDspPresetId(mapped)
            }
        }
    }

    fun updateLibrary(songs: List<Song>) {
        knownSongs = songs.associateBy(Song::stableId)
        val activeId = mutableUiState.value.mediaId
        if (activeId != null && activeId in knownSongs && mutableUiState.value.lyrics.sourceName == null) {
            loadCurrentEnhancements(activeId)
        }
    }

    private fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }.onSuccess { connectedController ->
                controller = connectedController
                connectedController.addListener(this)
                masterVolume = if (mutableUiState.value.audioRoute.isUsb) minOf(connectedController.volume, USB_SAFE_VOLUME) else connectedController.volume.coerceIn(0f, 1f)
                applyOutputVolume()
                syncState()
                connectedController.currentMediaItem?.mediaId?.let { loadCurrentEnhancements(it) }
                pendingPlayback?.let { (songs, index) -> pendingPlayback = null; playSongs(songs, index) }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        knownSongs = knownSongs + songs.associateBy(Song::stableId)
        if (controller == null) { pendingPlayback = songs to startIndex; return }
        queueController.playSongs(controller, songs, startIndex)
    }

    fun playNext(song: Song) {
        knownSongs = knownSongs + (song.stableId to song)
        queueController.playNext(controller, song)
    }

    fun addToQueue(song: Song) {
        knownSongs = knownSongs + (song.stableId to song)
        queueController.addToQueue(controller, song)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        queueController.moveQueueItem(controller, fromIndex, toIndex)
        syncState(refreshQueue = true)
    }

    fun removeQueueItem(index: Int) {
        queueController.removeQueueItem(controller, index)
        syncState(refreshQueue = true)
    }

    fun seekToQueueItem(index: Int) {
        queueController.seekToQueueItem(controller, index)
    }

    fun togglePlayback() {
        controller?.let { active ->
            if (active.isPlaying) active.pause() else {
                if (active.playbackState == Player.STATE_ENDED) active.seekTo(0L)
                active.prepare(); active.play()
            }
        }
    }

    fun skipNext() { controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }
    fun skipPrevious() {
        controller?.let { active ->
            if (active.currentPosition > RESTART_THRESHOLD_MS) active.seekTo(0L)
            else if (active.hasPreviousMediaItem()) active.seekToPreviousMediaItem()
            else active.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0L)) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeatMode() {
        controller?.let { active ->
            active.repeatMode = when (active.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) { controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 2f)); syncState() }
    fun setOutputVolume(volume: Float) { 
        masterVolume = volume.coerceIn(0f, 1f)
        mutableUiState.update { it.copy(outputVolume = masterVolume, volumeGuardActive = it.audioRoute.isUsb && masterVolume <= USB_SAFE_VOLUME + 0.01f) }
        applyOutputVolume() 
    }

    fun setSleepTimer(minutes: Int) = timerController.setSleepTimer(controller, minutes)
    fun cancelSleepTimer() = setSleepTimer(0)

    private var playTrackingJob: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lastTickMs = System.currentTimeMillis()
        DiagLog.i("PlaybackViewModel", "transisi mediaItem -> ${mediaItem?.mediaId} reason=$reason")
        mediaIdTransition(mediaItem)
        syncState(refreshQueue = true)
    }

    private fun mediaIdTransition(item: MediaItem?) {
        playTrackingJob?.cancel()
        val mediaId = item?.mediaId ?: return
        loadCurrentEnhancements(mediaId)

        playTrackingJob = viewModelScope.launch {
            val duration = controller?.duration ?: 0L
            val targetMs = if (duration > 0) minOf(30_000L, duration / 2) else 30_000L
            var elapsed = 0L
            while (isActive && elapsed < targetMs) {
                delay(1000L)
                if (controller?.isPlaying == true) elapsed += 1000L
            }
            if (isActive) repository.recordPlay(mediaId)
        }
    }

    /**
     * [Fase 3] Menangkap PlaybackException ke DiagLog.
     *
     * Media3 melaporkan kegagalan sebagai state ERROR, bukan crash, jadi tidak
     * muncul di logcat crash. Dan logcat app diblokir OEM di perangkat ini.
     * Tanpa ini, penyebab "Unexpected runtime error" tidak bisa diketahui.
     */
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        DiagLog.e(
            "PlaybackViewModel",
            "onPlayerError code=${error.errorCodeName} msg=${error.message} " +
                "cause=${error.cause?.javaClass?.name}: ${error.cause?.message}",
            error
        )
    }

    override fun onEvents(player: Player, events: Player.Events) { 
        val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            instrumentalDetector.reset()
            updatePresentationState(player.currentPosition, isDiscontinuity = true)
        }
        
        syncState(refreshQueue = timelineChanged) 
    }

    // Delegated to PreferenceCoordinator
    fun setLyricPriority(p: LyricSourcePriority) = prefCoordinator.setLyricPriority(p)
    fun setGaplessEnabled(e: Boolean) = prefCoordinator.setGaplessEnabled(e)
    fun setNormalizationEnabled(e: Boolean) = prefCoordinator.setNormalizationEnabled(e)
    fun setDynamicColorEnabled(e: Boolean) = prefCoordinator.setDynamicColorEnabled(e)
    fun setAppTheme(t: Int) = prefCoordinator.setAppTheme(t)
    fun setRomanizationEnabled(e: Boolean) = prefCoordinator.setRomanizationEnabled(e)
    fun setTranslationMode(e: Boolean) = prefCoordinator.setTranslationMode(e)
    fun cycleLyricDisplayMode() = prefCoordinator.cycleLyricDisplayMode()
    fun setAutoSkipIntroOutro(e: Boolean) = prefCoordinator.setAutoSkipIntroOutro(e)
    fun setBoutiqueBypassEnabled(e: Boolean) = prefCoordinator.setBoutiqueBypassEnabled(e)
    fun setLowLatencyAudioEnabled(e: Boolean) = prefCoordinator.setLowLatencyAudioEnabled(e)
    fun setLandscapeDualPaneEnabled(e: Boolean) = prefCoordinator.setLandscapeDualPaneEnabled(e)
    fun setDspEnabled(e: Boolean) = prefCoordinator.setDspEnabled(e)
    fun setPeqEnabled(e: Boolean) = prefCoordinator.setPeqEnabled(e)
    fun setDspPreset(id: String) = prefCoordinator.setDspPreset(id) { currentRouteKey() }
    fun setBtGuardMode(m: Int) = prefCoordinator.setBtGuardMode(m)
    fun setAmbienceEnabled(e: Boolean) = prefCoordinator.setAmbienceEnabled(e)
    fun setPreampDb(d: Float) = prefCoordinator.setPreampDb(d)
    fun setSpatialAudioEnabled(e: Boolean) = prefCoordinator.setSpatialAudioEnabled(e)
    fun setSpatialAudioPreset(p: Int) = prefCoordinator.setSpatialAudioPreset(p)
    fun setActivePeqProfile(id: String) = prefCoordinator.setActivePeqProfile(id)
    fun savePeqProfile(id: String?, b: List<PeqBand>, p: Float, n: String) = prefCoordinator.savePeqProfile(id, b, p, n)
    fun deletePeqProfile(id: String) = prefCoordinator.deletePeqProfile(id)
    fun clearRouteProfiles() = prefCoordinator.clearRouteProfiles()
    fun saveCustomDspPreset(t: String, p: List<Float>) = prefCoordinator.saveCustomDspPreset(t, p)
    fun deleteCustomDspPreset(id: String) = prefCoordinator.deleteCustomDspPreset(id)

    fun saveTranslationOverride(mediaId: String, timestampMs: Long, text: String) {
        viewModelScope.launch {
            lyricsRepository.saveTranslationOverride(mediaId, timestampMs, text)
            // If the song being edited is the current song, reload enhancements
            if (mediaId == mutableUiState.value.mediaId) {
                loadCurrentEnhancements(mediaId)
            }
        }
    }

    private fun loadCurrentEnhancements(mediaId: String) {
        val song = knownSongs[mediaId] ?: return
        enhancementManager.loadCurrentEnhancements(mediaId, song)
    }

    private fun applyOutputVolume() {
        val finalGain = if (mutableUiState.value.normalizationEnabled) replayGainLinear else 1f
        controller?.volume = (masterVolume * finalGain).coerceIn(0f, 1f)
    }

    private fun tickListeningTime() {
        val active = controller ?: return
        val now = System.currentTimeMillis()
        if (active.isPlaying && lastTickMs > 0) {
            val delta = (now - lastTickMs).coerceAtMost(2_000L)  // guard lompatan
            if (delta > 0) {
                accumulatedDeltaMs += delta
                if (accumulatedDeltaMs >= 10_000L) { 
                    val toSave = accumulatedDeltaMs
                    accumulatedDeltaMs = 0L
                    viewModelScope.launch { preferences.incrementTotalListeningTime(toSave) }
                }
                _liveDeltaMsFlow.value = accumulatedDeltaMs 
            }
        }
        lastTickMs = now

        // [V18]: Smart Auto-Skip Logic
        if (mutableUiState.value.autoSkipIntroOutro && active.isPlaying) {
            handleAutoSkip(active)
        }
    }

    private fun handleAutoSkip(active: MediaController) {
        val pos = active.currentPosition
        val duration = active.duration
        if (duration <= 0) return

        val lyrics = mutableUiState.value.lyrics.lines
        
        // [Issue 11]: Robust Instrumental Handling
        // When lyrics are missing, use a duration-based heuristic skip.
        if (lyrics.isEmpty()) {
            val introSkip = minOf(3000L, (duration * 0.03).toLong())
            if (pos < introSkip && pos < 1000L) {
                active.seekTo(introSkip)
            }
            
            val outroThreshold = duration - minOf(5000L, (duration * 0.05).toLong())
            if (pos > outroThreshold) {
                if (active.repeatMode == Player.REPEAT_MODE_ONE) {
                    active.seekTo(0)
                } else if (active.hasNextMediaItem() || active.repeatMode == Player.REPEAT_MODE_ALL) {
                    active.seekToNext()
                }
            }
            return
        }
        
        // [V21]: Senior NDK Hybrid Gate - Confirm vocal silence before skipping
        // [V23]: Lowered threshold to 1.5% to catch soft tails/whispers
        val telemetry = dspTelemetryFlow.value
        val vocalSilenceThreshold = 0.015f 
        val isVocalSilent = telemetry.vocalEnergy < vocalSilenceThreshold

        refreshVocalBounds(lyrics)

        // 1. Intro Skip: Jump to first lyric if intro is long
        if (pos < 2000L) { // Only check in the first 2 seconds
            val introTarget = firstVocalMs
            if (introTarget != null && introTarget > 3000L) {
                active.seekTo(introTarget - 200L)
            }
        }

        // 2. Outro Skip: Handle end of song
        val vocalEnd = vocalEndMs
        if (vocalEnd != null) {
            // [V22]: Refined Outro Logic
            if (pos > vocalEnd + 2500L) {
                if (isVocalSilent) {
                    if (active.repeatMode == Player.REPEAT_MODE_ONE) {
                        active.seekTo(0)
                    } else if (active.hasNextMediaItem() || active.repeatMode == Player.REPEAT_MODE_ALL) {
                        active.seekToNext()
                    }
                }
            }
        }
    }

    private fun syncState(refreshQueue: Boolean = false) {
        stateSync.syncState(controller, knownSongs, masterVolume, refreshQueue)
    }

    override fun onCleared() {
        if (accumulatedDeltaMs > 0) { 
            val toSave = accumulatedDeltaMs
            accumulatedDeltaMs = 0L
            viewModelScope.launch { preferences.incrementTotalListeningTime(toSave) } 
        }
        enhancementManager.cancelAll()
        routeMonitor.close()
        controller?.removeListener(this)
        controllerFuture?.let { future -> MediaController.releaseFuture(future) }
        controllerFuture = null
        controller = null
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlaybackViewModel::class.java)) return PlaybackViewModel(application) as T
            throw IllegalArgumentException("ViewModel tidak dikenal: ${modelClass.name}")
        }
    }

    private companion object { 
        const val POSITION_INTERVAL_MS = 100L
        const val LISTEN_TICK_MS = 1000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val USB_SAFE_VOLUME = 0.25f 
    }
}

@Immutable
internal data class MiniPlayerState(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
)

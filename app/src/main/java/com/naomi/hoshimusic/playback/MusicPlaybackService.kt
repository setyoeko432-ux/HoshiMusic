package com.naomi.hoshimusic.playback

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import coil.imageLoader
import coil.request.ImageRequest
import com.naomi.hoshimusic.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.naomi.hoshimusic.playback.dsp.DspConfigRepository
import com.naomi.hoshimusic.playback.dsp.DspRuntimeCoordinator
import com.naomi.hoshimusic.playback.dsp.DspStatusBridge
import com.naomi.hoshimusic.playback.dsp.DspTelemetryBridge
import com.naomi.hoshimusic.playback.route.BluetoothClarityMonitor
import com.naomi.hoshimusic.playback.route.VolumeObserver
import com.naomi.hoshimusic.playback.route.WiredRoutingManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

@UnstableApi
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var preferences: UserPreferences
    private lateinit var dspConfigRepository: DspConfigRepository
    private lateinit var dspRuntimeCoordinator: DspRuntimeCoordinator
    
    // DspProcessor hanya dipakai bila DSP benar-benar aktif. Diinisialisasi lazy
    // untuk menghindari System.loadLibrary("hoshi_dsp") saat tidak diperlukan.
    private var dspProcessor: HoshiDspProcessor? = null
    
    private lateinit var volumeObserver: VolumeObserver
    private lateinit var btClarityMonitor: BluetoothClarityMonitor
    private lateinit var wiredRoutingManager: WiredRoutingManager

    private var hoshiAudioSink: HoshiAudioSink? = null
    private var currentBypassEnabled = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private fun updateAudioPathStatus() {
        val dsp = dspProcessor
        val isActuallyProcessing = dsp?.isActuallyProcessing ?: false
        val status = when {
            !HoshiDspProcessor.libraryLoaded -> AudioPathStatus.DSP_ERROR
            dsp == null -> AudioPathStatus.DSP_BYPASS
            isActuallyProcessing -> AudioPathStatus.DSP_ACTIVE
            // [V26]: If processor exists but not processing (e.g. disabled by user or negotiating),
            // report ATTACHED to keep UI signal path alive but accurate.
            dsp.isActive() -> AudioPathStatus.DSP_ATTACHED
            else -> AudioPathStatus.DSP_ATTACHED
        }
        DspStatusBridge.update(status)
    }

    private val savePositionTask = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.mediaItemCount > 0) {
                stateStore.save(player)
            }
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { enrichMetadata(it) }
            stateStore.save(player)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                stateStore.save(player)
            }
        }
    }

    private fun enrichMetadata(item: MediaItem) {
        val mediaId = item.mediaId
        // Cek apakah sudah punya artworkUri yang menunjuk ke file cache kita
        if (item.mediaMetadata.artworkUri?.toString()?.startsWith("file://") == true) return

        serviceScope.launch(Dispatchers.IO) {
            val uri = item.localConfiguration?.uri ?: return@launch
            val artFile = File(cacheDir, "art_${mediaId.hashCode()}.jpg")
            
            if (artFile.exists() && artFile.length() > 0) {
                val artworkUri = Uri.fromFile(artFile)
                // [A7] Pre-load art into Coil memory cache to prevent "blank flash" in UI
                val request = ImageRequest.Builder(this@MusicPlaybackService)
                    .data(artworkUri)
                    .precision(coil.size.Precision.INEXACT)
                    .build()
                imageLoader.enqueue(request)

                withContext(Dispatchers.Main) {
                    applyEnrichedMetadata(mediaId, artworkUri)
                }
                return@launch
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MusicPlaybackService, uri)
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    artFile.writeBytes(picture)
                    val artworkUri = Uri.fromFile(artFile)
                    // [A7] Pre-load
                    val request = ImageRequest.Builder(this@MusicPlaybackService)
                        .data(artworkUri)
                        .precision(coil.size.Precision.INEXACT)
                        .build()
                    imageLoader.enqueue(request)

                    withContext(Dispatchers.Main) {
                        applyEnrichedMetadata(mediaId, artworkUri)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("HoshiService", "Metadata enrichment failed for $mediaId: ${e.message}")
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun applyEnrichedMetadata(mediaId: String, artworkUri: Uri) {
        if (!::player.isInitialized) return
        runCatching {
            val count = player.mediaItemCount
            for (i in 0 until count) {
                val currentItem = player.getMediaItemAt(i)
                if (currentItem.mediaId == mediaId) {
                    val newMetadata = currentItem.mediaMetadata.buildUpon()
                        .setArtworkUri(artworkUri)
                        .build()
                    val newItem = currentItem.buildUpon().setMediaMetadata(newMetadata).build()
                    player.replaceMediaItem(i, newItem)
                    break
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        preferences = UserPreferences(this)
        dspConfigRepository = DspConfigRepository(preferences)
        dspRuntimeCoordinator = DspRuntimeCoordinator(serviceScope, dspConfigRepository)
        btClarityMonitor = BluetoothClarityMonitor(this)
        btClarityMonitor.register()
        
        volumeObserver = VolumeObserver(this) { linearVolume ->
            dspProcessor?.setSystemVolume(linearVolume)
        }
        volumeObserver.register()

        wiredRoutingManager = WiredRoutingManager(this) { device ->
            hoshiAudioSink?.updateRouting(device)
        }
        wiredRoutingManager.register()
        
        // [V22]: Always-On DSP initialization
        if (HoshiDspProcessor.libraryLoaded) {
            dspProcessor = HoshiDspProcessor().also {
                dspRuntimeCoordinator.attachProcessor(it)
            }
        }

        // [V28]: Ensure synchronous initialization for the first time 
        // to avoid null MediaSession during controller connection.
        initializePlayer()
        
        // [V28]: Monitor player routing preference changes
        serviceScope.launch {
            preferences.boutiqueBypassEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled != currentBypassEnabled || dspProcessor?.boutiqueBypassActive != enabled) {
                        currentBypassEnabled = enabled
                        dspProcessor?.boutiqueBypassActive = enabled
                        // Re-initialize only if already initialized to apply new routing
                        if (::player.isInitialized) {
                            initializePlayer(forceRecreate = true)
                        }
                    }
                }
        }
        
        handler.postDelayed(savePositionTask, SAVE_INTERVAL_MS)

        // [Issue 2]: Slow status polling for signal path correctness
        serviceScope.launch {
            while (isActive) {
                updateAudioPathStatus()
                kotlinx.coroutines.delay(5000L)
            }
        }

        // [Issue 2]: Optimized telemetry polling (off audio thread)
        serviceScope.launch {
            while (isActive) {
                val proc = dspProcessor
                if (proc != null) {
                    proc.updateTelemetry()
                    // High-frequency (200ms) only when Info sheet is visible,
                    // otherwise throttle to 500ms for power efficiency.
                    val delay = if (DspTelemetryBridge.hasSubscribers) 200L else 500L
                    kotlinx.coroutines.delay(delay)
                } else {
                    kotlinx.coroutines.delay(2000L)
                }
            }
        }

        // DSP Engine: monitor dspEnabled and update processor state
        serviceScope.launch {
            preferences.dspEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    dspProcessor?.setEnabled(enabled)
                    updateAudioPathStatus()
                }
        }

        // Monitoring parameter DSP via Coordinator
        serviceScope.launch {
            dspRuntimeCoordinator.currentConfig.collect { config ->
                updateAudioPathStatus()
            }
        }

        // BT Clarity Guard dsb sekarang ditangani oleh dspConfigRepository
        
        // [V18]: Wire SkipSilence sensor to user preference
        serviceScope.launch {
            preferences.autoSkipIntroOutro.collect { enabled ->
                if (::player.isInitialized) {
                    player.skipSilenceEnabled = enabled
                }
            }
        }

        // [V34]: Monitor Bit-Perfect preference changes
        serviceScope.launch {
            preferences.wiredBitPerfectPreferred.distinctUntilChanged().collect {
                wiredRoutingManager.evaluateRouting()
            }
        }
    }

    /**
     * Inisialisasi atau membangun ulang ExoPlayer.
     * @param forceRecreate Jika true, akan memaksa penghapusan player lama dan pemindahan state.
     */
    private fun initializePlayer(forceRecreate: Boolean = false) {
        val oldPlayer = if (forceRecreate && ::player.isInitialized) player else null
        
        // Capture state dari player lama
        val playlist = mutableListOf<androidx.media3.common.MediaItem>()
        val index: Int
        val position: Long
        val playWhenReady: Boolean
        val repeatMode: Int
        val shuffleMode: Boolean

        if (oldPlayer != null) {
            for (i in 0 until oldPlayer.mediaItemCount) {
                playlist.add(oldPlayer.getMediaItemAt(i))
            }
            index = oldPlayer.currentMediaItemIndex
            position = oldPlayer.currentPosition
            playWhenReady = oldPlayer.playWhenReady || oldPlayer.isPlaying
            repeatMode = oldPlayer.repeatMode
            shuffleMode = oldPlayer.shuffleModeEnabled

            oldPlayer.removeListener(playerListener)
            // [V27]: Ensure player is stopped and items cleared before release to avoid "ghost tracks" in AudioFlinger
            oldPlayer.stop()
            oldPlayer.clearMediaItems()
            oldPlayer.release()
        } else {
            index = C.INDEX_UNSET
            position = C.TIME_UNSET
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleMode = false
        }

        // [V22]: Always include DSP if library is loaded
        if (HoshiDspProcessor.libraryLoaded && dspProcessor == null) {
            dspProcessor = HoshiDspProcessor().also {
                dspRuntimeCoordinator.attachProcessor(it)
            }
        }
        dspProcessor?.boutiqueBypassActive = currentBypassEnabled
        updateAudioPathStatus()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sink = HoshiAudioSink(context, preferences, dspProcessor)
                this@MusicPlaybackService.hoshiAudioSink = sink
                // Sink akan di-update routing-nya segera setelah dibuat
                wiredRoutingManager.evaluateRouting()
                return sink
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        @Suppress("WrongConstant")
        val audioAttributes = if (currentBypassEnabled) {
            // [Issue 12]: God-Mode/Boutique Bypass Path (Optimized)
            // USAGE_GAME + FLAG_AUDIBILITY_ENFORCED to bypass OEM "Music" DSP layers.
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_GAME)
                .setFlags(C.FLAG_AUDIBILITY_ENFORCED)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                .setIsContentSpatialized(true)
                .build()
        } else {
            // [V28]: Standard High-Compatibility Path
            // Best for MP3/AAC and standard hardware paths
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                .setIsContentSpatialized(true)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)
        
        // Restore state
        if (playlist.isNotEmpty()) {
            player.setMediaItems(playlist)
            if (index != C.INDEX_UNSET) {
                player.prepare()
                player.seekTo(index, position)
            }
            player.repeatMode = repeatMode
            player.shuffleModeEnabled = shuffleMode
            player.playWhenReady = playWhenReady
        } else {
            stateStore.restore(player)
        }

        // [A6] Trigger metadata enrichment for the current item to ensure artwork 
        // appears in system UI immediately after creation or recreation.
        player.currentMediaItem?.let { enrichMetadata(it) }

        // Update MediaSession
        if (!::mediaSession.isInitialized) {
            mediaSession = MediaSession.Builder(this, player)
                .setBitmapLoader(CacheBitmapLoader(HoshiBitmapLoader(this)))
                .build()
        } else {
            mediaSession.player = player
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return if (::mediaSession.isInitialized) mediaSession else null
    }

    override fun onDestroy() {
        if (::btClarityMonitor.isInitialized) {
            btClarityMonitor.unregister()
        }
        if (::volumeObserver.isInitialized) {
            volumeObserver.unregister()
        }
        if (::wiredRoutingManager.isInitialized) {
            wiredRoutingManager.unregister()
        }
        handler.removeCallbacks(savePositionTask)
        serviceScope.cancel()
        
        if (::player.isInitialized) {
            stateStore.save(player)
            player.removeListener(playerListener)
            player.release()
        }
        if (::mediaSession.isInitialized) mediaSession.release()
        dspProcessor?.release()
        dspProcessor = null
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}

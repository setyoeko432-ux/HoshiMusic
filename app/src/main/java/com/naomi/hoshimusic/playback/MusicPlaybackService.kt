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
import com.naomi.hoshimusic.util.DiagLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

@UnstableApi
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    // [A7-AUDIT] AnalyticsListener adalah satu-satunya sumber resmi media3
    // untuk MEMBEDAKAN dua hal yang tadinya tercampur di log DiagLog:
    // underrun AudioTrack yang sungguhan (onAudioUnderrun), dan renderer
    // yang sengaja masuk BUFFERING. Tanpa ini, badai play/pause hanya
    // terlihat sebagai gejala, bukan penyebab.
    private val dspAnalyticsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        @Volatile
        private var underrunCount = 0

        @Volatile
        private var lastJankUs = 0L

        @Volatile
        private var bufferingEvents = 0

        @Volatile
        private var lastState = androidx.media3.common.Player.STATE_IDLE

        override fun onAudioUnderrun(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            audioSinkUnderrunCount: Int,
            jankDurationMs: Long,
            jankDurationUs: Long
        ) {
            underrunCount++
            lastJankUs = jankDurationUs
        }

        override fun onPlaybackStateChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            state: Int
        ) {
            if (state == androidx.media3.common.Player.STATE_BUFFERING) bufferingEvents++
            lastState = state
        }

        fun auditLine(): String =
            "underruns=$underrunCount jankUs=$lastJankUs " +
                "bufferingEvents=$bufferingEvents state=$lastState"
    }

    // [A7-AUDIT] Instrumen audio ditambahkan untuk membedakan
    // underrun asli dari BUFFERING yang disengaja.
    private lateinit var preferences: UserPreferences
    private lateinit var dspConfigRepository: DspConfigRepository
    private lateinit var dspRuntimeCoordinator: DspRuntimeCoordinator
    
    // DspProcessor hanya dipakai bila DSP benar-benar aktif. Diinisialisasi lazy
    // untuk menghindari System.loadLibrary("hoshi_dsp") saat tidak diperlukan.
    private var dspProcessor: HoshiDspProcessor? = null
    
    private lateinit var volumeObserver: VolumeObserver
    private lateinit var btClarityMonitor: BluetoothClarityMonitor
    private lateinit var wiredRoutingManager: WiredRoutingManager
    private lateinit var spatializerManager: SpatializerManager

    private var hoshiAudioSink: HoshiAudioSink? = null
    private var currentBypassEnabled = false

    // [A7-FREEZE] Berapa kali rantai audio sudah dibangun ulang karena beku.
    // Dibatasi supaya state macet tidak berubah jadi rebuild tak berujung.
    private var dspRecoveryAttempts = 0
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private fun updateAudioPathStatus() {
        val dsp = dspProcessor
        val isActuallyProcessing = dsp?.isActuallyProcessing ?: false
        val status = when {
            !HoshiDspProcessor.libraryLoaded -> AudioPathStatus.DSP_ERROR
            dsp == null -> AudioPathStatus.DSP_BYPASS
            isActuallyProcessing -> AudioPathStatus.DSP_ACTIVE
            // [V26]: Processor ada tapi belum memproses (nonaktif oleh pengguna,
            // atau sedang nego format). Laporkan ATTACHED supaya sinyal UI tetap
            // hidup tanpa mengklaim DSP benar-benar bekerja.
            else -> AudioPathStatus.DSP_ATTACHED
        }
        DspStatusBridge.update(status)
    }

    /**
     * [A7-FREEZE] Bangun ulang seluruh rantai audio setelah DSP beku.
     *
     * Rantai yang dibangun ulang: HoshiDspProcessor (+ native handle) →
     * DefaultAudioSink → AudioTrack. initializePlayer(forceRecreate = true)
     * sudahYy preserves playlist, index, posisi, playWhenReady, repeat, dan
     * shuffle, jadi pengguna tidak kehilangan letak lagu.
     *
     * Kalau sudah melewati batas percobaan, berhenti saja: lebih baik diam
     * daripada membangun ulang AudioTrack tanpa henti.
     */
    private fun recoverFromDspFreeze() {
        if (!::player.isInitialized) return
        if (dspRecoveryAttempts >= MAX_DSP_RECOVERY_ATTEMPTS) {
            DiagLog.w(
                "HoshiService",
                "DSP tetap beku setelah ${dspRecoveryAttempts}x rebuild → hentikan playback"
            )
            player.stop()
            return
        }
        dspRecoveryAttempts++
        DiagLog.w(
            "HoshiService",
            "DSP beku (frames tidak naik 3 detik) → rebuild rantai audio ke-${dspRecoveryAttempts}"
        )
        // Processor lama dibuang supaya yang baru dibuat initializePlayer()
        // benar-benar baru, lengkap dengan native handle yang bersih.
        // Rantai yang sama tidak akan pulih dari keadaan beku.
        dspProcessor?.resetFreezeState()
        dspProcessor = null
        initializePlayer(forceRecreate = true)
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

        // [P2]: Initialize Platform Spatializer (Android 13+)
        spatializerManager = SpatializerManager(this).apply { initialize() }
        
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

        // [A7-FREEZE] Watchdog + pemulihan.
        //
        // Gejalanya: DefaultAudioSink (media3 1.11.1) masuk play/pause loop
        // internal begitu ada AudioProcessor pengguna. Renderer berhenti
        // memanggil queueInput, jadi frames DSP membeku tanpa error dan tanpa
        // underrun. Media3 tidak menyediakan jalan keluar dari keadaan itu.
        //
        // PENTING — hasil ukur di vivo/iQOO: membangun ulang rantai audio
        // TIDAK menghilangkan keadaan beku, hanya memulai ulang hitungannya.
        // Tanpa rebuild playback bertahan ~56 detik lalu beku; dengan rebuild
        // hanya ~1,2 detik. Jadi penghitung hanya boleh disetel ulang setelah
        // pemutaran sehat bertahan lama (HEALTHY_RESET_NS), kalau tidak batas
        // rebuild tidak akan pernah tercapai dan audio akan terputus-putus.
        serviceScope.launch {
            var healthySinceNs = 0L
            while (isActive) {
                val proc = dspProcessor
                if (proc != null && ::player.isInitialized && player.playWhenReady) {
                    if (proc.checkFreeze(System.nanoTime())) {
                        healthySinceNs = 0L
                        recoverFromDspFreeze()
                    } else if (dspRecoveryAttempts > 0) {
                        if (healthySinceNs == 0L) healthySinceNs = System.nanoTime()
                        if (System.nanoTime() - healthySinceNs >= HEALTHY_RESET_NS) {
                            DiagLog.i(
                                "HoshiService",
                                "Pemutaran sehat ${HEALTHY_RESET_NS / 1_000_000_000L}s → " +
                                    "penghitung rebuild direset"
                            )
                            dspRecoveryAttempts = 0
                            healthySinceNs = 0L
                        }
                    }
                } else {
                    healthySinceNs = 0L
                }
                kotlinx.coroutines.delay(1000L)
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

        // [DIAG] Heartbeat telemetri DSP.
        //
        // `HoshiDspProcessor.updateTelemetry()` dan `auditSnapshot()` tadinya
        // DITULIS tapi tidak pernah dipanggil dari mana pun -- tidak ada cara
        // melihat keadaan mesin dari perangkat. Dan logcat diblokir OEM di
        // perangkat ini (lihat DiagLog), jadi tanpa heartbeat ini diagnosis
        // "preset tidak berbeda" sama sekali mustahil.
        //
        // Yang dicetak dipilih karena semuanya diturunkan dari parameter preset:
        // curAirBoostDb <- pAir, curVocalBoostDb <- pVocal,
        // curSideBoostDb <- pWidth, curVirtualBassAmount <- pBass.
        // Kalau preset benar-benar berbeda, angka-angka ini harus bergerak.
        serviceScope.launch {
            var beat = 0
            while (isActive) {
                kotlinx.coroutines.delay(TELEMETRY_HEARTBEAT_MS)
                val proc = dspProcessor ?: continue
                if (!::player.isInitialized || !player.isPlaying) continue
                beat++
                val snap = proc.updateTelemetry() ?: continue
                DiagLog.i(
                    "HoshiDsp",
                    "telemetry#$beat preset=${dspRuntimeCoordinator.currentConfig.value.presetId} " +
                        "frames=${snap.totalFramesProcessed} " +
                        "air=${String.format(Locale.US, "%.2f", snap.curAirBoostDb)}dB " +
                        "vocal=${String.format(Locale.US, "%.2f", snap.curVocalBoostDb)}dB " +
                        "side=${String.format(Locale.US, "%.2f", snap.curSideBoostDb)}dB " +
                        "vbass=${String.format(Locale.US, "%.3f", snap.curVirtualBassAmount)} " +
                        "lim=${String.format(Locale.US, "%.2f", snap.limiterGainReductionDb)}dB " +
                        "band=${snap.dominantBand}"
                )
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

        // [P2]: Monitor Spatial Audio preferences for Spatializer / Crossfeed coordination
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.spatialAudioEnabled,
                preferences.spatialAudioPreset
            ) { enabled, preset -> enabled to preset }
            .distinctUntilChanged()
            .collect { (enabled, preset) ->
                spatializerManager.updateSpatialAudioConfig(enabled, preset)
                val spatState = spatializerManager.state.value
                if (spatState.isEnabled && !spatState.isFallbackToCrossfeed) {
                    // Platform Spatializer aktif -> matikan custom crossfeed DSP agar tidak double processing
                    dspProcessor?.setSpatialAudio(false, preset)
                } else {
                    // Fallback / Standar -> jalankan custom C++ crossfeed DSP
                    dspProcessor?.setSpatialAudio(enabled, preset)
                }
            }
        }

        // [P1]: Monitor Low Latency Audio preference changes
        // distinctUntilChanged() menyaring nilai duplikat berurutan, BUKAN emisi
        // pertama — dan DataStore selalu emit nilai awal saat flow baru dikoleksi.
        // Tanpa penjaga di bawah, initializePlayer() yang barusan dibangun
        // langsung dibongkar dan dibangun ulang setiap kali service start:
        // AudioTrack di-churn dua kali dan MediaSession.player ditukar di
        // tengah koneksi controller. Lewati emisi pertama; nilai itu sudah
        // dipakai untuk membangun player di atas.
        var lowLatencyPrimed = false
        serviceScope.launch {
            preferences.lowLatencyAudio.distinctUntilChanged().collect { _ ->
                if (!lowLatencyPrimed) {
                    lowLatencyPrimed = true
                    return@collect
                }
                if (::player.isInitialized) {
                    initializePlayer(forceRecreate = true)
                }
            }
        }
    }

    /**
     * Inisialisasi atau membangun ulang ExoPlayer.
     * @param forceRecreate Jika true, akan memaksa penghapusan player lama dan pemindahan state.
     */
    private fun initializePlayer(forceRecreate: Boolean = false) {
        // Dipakai untuk memverifikasi di perangkat bahwa player TIDAK dibangun
        // ulang saat service start (regresi yang sebelumnya ada).
        DiagLog.i(
            "HoshiService",
            "initializePlayer forceRecreate=$forceRecreate sudahAda=${::player.isInitialized} " +
                "bypass=$currentBypassEnabled items=${if (::player.isInitialized) player.mediaItemCount else 0}"
        )
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
                // Diagnostik: memastikan processor benar-benar masuk rantai.
                // Format PCM_FLOAT pada AudioTrack BUKAN bukti — DefaultAudioSink
                // memakai float karena setEnableFloatOutput(true), bukan karena
                // processor ini aktif.
                DiagLog.i(
                    "HoshiService",
                    "buildAudioSink: dspProcessor=${dspProcessor != null} " +
                        "libLoaded=${HoshiDspProcessor.libraryLoaded} " +
                        "processorActive=${dspProcessor?.isActive}"
                )
                val sink = HoshiAudioSink(context, preferences, dspProcessor)
                this@MusicPlaybackService.hoshiAudioSink = sink
                // Sink akan di-update routing-nya segera setelah dibuat
                wiredRoutingManager.evaluateRouting()
                return sink
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        @Suppress("WrongConstant")
        val audioAttributes = if (currentBypassEnabled) {
            // [Issue 12]: Boutique Bypass Path — HANYA MEMINTA platform lewat
            // USAGE_GAME + FLAG_AUDIBILITY_ENFORCED. Android tidak menjamin DSP
            // vendor (SKTune/Dolby/efek OEM) mengabaikannya; pada sebagian OEM
            // efek tetap menempel ke session meski flag ini aktif. Verified di
            // vivo/iQOO (Android 15, SDK 35): efek OEM tetap enabled. Jangan
            // menyebut ini bypass yang terverifikasi.
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
            // [A7] enablePerStreamMediaProgression(true) dan LoadControl
            // 10s/60s sudah DIUJI dan DIKEMBALIKAN. Keduanya tidak
            // menghentikan badai play/pause (tetap ~4 siklus/detik,
            // bufferingEvents tetap naik). Alasannya keduanya tidak menyentuh
            // penyebab: yang macet bukan undersupply dan bukan transisi item,
            // melainkan kendali internal media3 saat AudioProcessor pengguna
            // ada di rantai. Bukti lengkap di files/hoshi_diag.log.
            .build()

        player.addListener(playerListener)
        player.addAnalyticsListener(dspAnalyticsListener)
        
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
        if (::spatializerManager.isInitialized) {
            spatializerManager.release()
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

  /**
   * Interval heartbeat telemetri DSP.
   *
   * 3 detik cukup: `curAirBoostDb` dan turunan preset lain dihaluskan dengan
   * koefisien 0,05 per 1024 frame, jadi sudah mencapai nilai steady dalam
   * beberapa detik setelah preset berganti.
   */
  const val TELEMETRY_HEARTBEAT_MS = 3_000L

        /**
         * [A7-FREEZE] Batas build ulang rantai audio sebelum menyerah.
         * Setelah ini playback dihentikan, daripada rebuild AudioTrack
         * tanpa henti — yang justru lebih buruk untuk baterai dan mixer.
         */
        const val MAX_DSP_RECOVERY_ATTEMPTS = 3

        /**
         * [A7-FREEZE] Durasi pemutaran sehat yang mereset penghitung rebuild.
         * Harus jauh lebih panjang dari "~1 detik yang didapat setelah
         * rebuild", kalau tidak batasnya tidak akan pernah tercapai.
         */
        const val HEALTHY_RESET_NS = 60_000_000_000L
    }
}

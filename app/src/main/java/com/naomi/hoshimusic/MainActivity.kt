@file:androidx.media3.common.util.UnstableApi

package com.naomi.hoshimusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.ui.HoshiMusicApp
import com.naomi.hoshimusic.ui.LibraryViewModel
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.theme.HoshiMusicTheme

class MainActivity : ComponentActivity() {
    private val libraryViewModel by viewModels<LibraryViewModel> {
        LibraryViewModel.Factory(application)
    }
    private val playbackViewModel by viewModels<PlaybackViewModel> {
        PlaybackViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
            HoshiMusicTheme(
                darkTheme = when (playbackState.appTheme) {
                    1 -> false
                    2 -> true
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                },
                dynamicColor = playbackState.dynamicColorEnabled,
            ) {
                var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
                var hasBluetoothPermission by remember { mutableStateOf(hasBluetoothConnectPermission()) }
                var pendingExport by remember { mutableStateOf<LocalPlaylist?>(null) }
                
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { results ->
                    hasAudioPermission = hasAudioPermission()
                    // If BT was part of the request, update its state too
                    if (results.containsKey(Manifest.permission.BLUETOOTH_CONNECT)) {
                        hasBluetoothPermission = hasBluetoothConnectPermission()
                    }
                }
                val importPlaylistLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(libraryViewModel::importM3u) }
                val exportPlaylistLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
                ) { uri ->
                    val playlist = pendingExport
                    if (uri != null && playlist != null) libraryViewModel.exportM3u(uri, playlist)
                    pendingExport = null
                }
                val musicFolderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    uri?.let {
                        contentResolver.takePersistableUriPermission(
                            it,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        libraryViewModel.onSelectMusicFolder(it.toString())
                    }
                }

                // BT Clarity Guard: best-effort via device name if permission denied.
                val btPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) hasAudioPermission = hasAudioPermission()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(hasAudioPermission) {
                    if (hasAudioPermission) libraryViewModel.refresh()
                }

                val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(libraryState.songs) { playbackViewModel.updateLibrary(libraryState.songs) }

                HoshiMusicApp(
                    hasAudioPermission = hasAudioPermission,
                    hasBluetoothPermission = hasBluetoothPermission,
                    libraryState = libraryState,
                    playbackViewModel = playbackViewModel,
                    onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                    onRequestBluetoothPermission = { permissionLauncher.launch(bluetoothPermissions()) },
                    onRefresh = libraryViewModel::refresh,
                    onQueryChange = libraryViewModel::updateQuery,
                    onToggleFavorite = libraryViewModel::toggleFavorite,
                    onToggleHiddenFolder = libraryViewModel::toggleHiddenFolder,
                    onCreatePlaylist = libraryViewModel::createPlaylist,
                    onDeletePlaylist = libraryViewModel::deletePlaylist,
                    onAddSongToPlaylist = libraryViewModel::addSongToPlaylist,
                    onRemoveSongFromPlaylist = libraryViewModel::removeSongFromPlaylist,
                    onImportPlaylist = {
                        importPlaylistLauncher.launch(
                            arrayOf(
                                "audio/x-mpegurl",
                                "audio/mpegurl",
                                "application/vnd.apple.mpegurl",
                                "text/plain",
                            )
                        )
                    },
                    onExportPlaylist = { playlist ->
                        pendingExport = playlist
                        val safeName = playlist.name.replace(Regex("[\\/:*?\"<>|]"), "_")
                        exportPlaylistLauncher.launch("$safeName.m3u8")
                    },
                    onSaveQueue = libraryViewModel::saveQueue,
                    onDeleteSavedQueue = libraryViewModel::deleteSavedQueue,
                    onNoticeShown = libraryViewModel::clearNotice,
                    onSetLyricPriority = playbackViewModel::setLyricPriority,
                    onSetGaplessEnabled = playbackViewModel::setGaplessEnabled,
                    onSetNormalizationEnabled = playbackViewModel::setNormalizationEnabled,
                    onSetDynamicColorEnabled = playbackViewModel::setDynamicColorEnabled,
                    onSetAppTheme = playbackViewModel::setAppTheme,
                    onSetRomanizationEnabled = playbackViewModel::setRomanizationEnabled,
                    onSetTranslationMode = playbackViewModel::setTranslationMode,
                    translationMode = playbackState.translationMode,
                    onSelectMusicFolder = { musicFolderLauncher.launch(null) },
                    onSetFilterShortTracks = libraryViewModel::setFilterShortTracks,
                    onPlaySongs = playbackViewModel::playSongs,
                    onPlayNext = playbackViewModel::playNext,
                    onAddToQueue = playbackViewModel::addToQueue,
                    onMoveQueueItem = playbackViewModel::moveQueueItem,
                    onRemoveQueueItem = playbackViewModel::removeQueueItem,
                    onSeekToQueueItem = playbackViewModel::seekToQueueItem,
                    onTogglePlayback = playbackViewModel::togglePlayback,
                    onSkipNext = playbackViewModel::skipNext,
                    onSkipPrevious = playbackViewModel::skipPrevious,
                    onSeek = playbackViewModel::seekTo,
                    onToggleShuffle = playbackViewModel::toggleShuffle,
                    onCycleRepeat = playbackViewModel::cycleRepeatMode,
                    onSetPlaybackSpeed = playbackViewModel::setPlaybackSpeed,
                    onSetOutputVolume = playbackViewModel::setOutputVolume,
                    onSetDspEnabled = playbackViewModel::setDspEnabled,
                    onSelectDspPreset = playbackViewModel::setDspPreset,
                    onSaveCustomDspPreset = playbackViewModel::saveCustomDspPreset,
                    onDeleteCustomDspPreset = playbackViewModel::deleteCustomDspPreset,
                    btGuardMode = playbackState.btGuardMode,
                    onSelectBtGuardMode = playbackViewModel::setBtGuardMode,
                    ambienceEnabled = playbackState.ambienceEnabled,
                    onToggleAmbience = playbackViewModel::setAmbienceEnabled,
                    preampDb = playbackState.preampDb,
                    onSetPreampDb = playbackViewModel::setPreampDb,
                    spatialAudioEnabled = playbackState.spatialAudioEnabled,
                    onToggleSpatialAudio = playbackViewModel::setSpatialAudioEnabled,
                    spatialAudioPreset = playbackState.spatialAudioPreset,
                    onSelectSpatialAudioPreset = playbackViewModel::setSpatialAudioPreset,
                    peqProfiles = playbackState.peqProfiles,
                    activePeqProfileId = playbackState.activePeqProfileId,
                    onSelectPeqProfile = playbackViewModel::setActivePeqProfile,
                    onSavePeqProfile = { b, p, n -> playbackViewModel.savePeqProfile(null, b, p, n) },
                    onDeletePeqProfile = playbackViewModel::deletePeqProfile,
                    routeProfiles = playbackState.routeProfiles,
                    onClearRouteProfiles = playbackViewModel::clearRouteProfiles,
                )
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun bluetoothPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
}

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.naomi.hoshimusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.ui.components.FastBlurMiniPlayer
import com.naomi.hoshimusic.ui.library.*
import com.naomi.hoshimusic.ui.settings.TranslationWorkbenchScreen
import com.naomi.hoshimusic.ui.motion.AppMotion
import com.naomi.hoshimusic.ui.navigation.MainScreen
import com.naomi.hoshimusic.ui.navigation.TopCircleButton
import com.naomi.hoshimusic.ui.player.ForcedLandscapeScreen
import com.naomi.hoshimusic.ui.player.NowPlayingScreen
import com.naomi.hoshimusic.ui.util.LockScreenOrientation

@androidx.media3.common.util.UnstableApi
@Composable
internal fun HoshiMusicApp(
    hasAudioPermission: Boolean,
    hasBluetoothPermission: Boolean,
    libraryState: LibraryUiState,
    playbackViewModel: PlaybackViewModel,
    onRequestPermission: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleHiddenFolder: (String) -> Unit,
    onCreatePlaylist: (String, List<String>) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onAddSongToPlaylist: (String, String) -> Unit,
    onRemoveSongFromPlaylist: (String, String) -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylist: (LocalPlaylist) -> Unit,
    onSaveQueue: (String, List<String>) -> Unit,
    onDeleteSavedQueue: (String) -> Unit,
    onNoticeShown: () -> Unit,
    onSetLyricPriority: (LyricSourcePriority) -> Unit,
    onSetGaplessEnabled: (Boolean) -> Unit,
    onSetNormalizationEnabled: (Boolean) -> Unit,
    onSetDynamicColorEnabled: (Boolean) -> Unit,
    onSetAppTheme: (Int) -> Unit,
    onSetRomanizationEnabled: (Boolean) -> Unit,
    onSetTranslationMode: (Boolean) -> Unit,
    translationMode: Boolean,
    onSelectMusicFolder: () -> Unit,
    onSetFilterShortTracks: (Boolean) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onSeekToQueueItem: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetOutputVolume: (Float) -> Unit,
    onSetDspEnabled: (Boolean) -> Unit,
    onSelectDspPreset: (String) -> Unit,
    onSaveCustomDspPreset: (String, List<Float>) -> Unit,
    onDeleteCustomDspPreset: (String) -> Unit,
    btGuardMode: Int,
    onSelectBtGuardMode: (Int) -> Unit,
    ambienceEnabled: Boolean,
    onToggleAmbience: (Boolean) -> Unit,
    preampDb: Float,
    onSetPreampDb: (Float) -> Unit,
    spatialAudioEnabled: Boolean,
    onToggleSpatialAudio: (Boolean) -> Unit,
    spatialAudioPreset: Int,
    onSelectSpatialAudioPreset: (Int) -> Unit,
    peqProfiles: List<PeqProfile>,
    activePeqProfileId: String,
    onSelectPeqProfile: (String) -> Unit,
    onSavePeqProfile: (List<PeqBand>, Float, String) -> Unit,
    onDeletePeqProfile: (String) -> Unit,
    routeProfiles: Map<String, String>,
    onClearRouteProfiles: () -> Unit,
) {
    val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var activeMainScreen by rememberSaveable { mutableStateOf(MainScreen.Home) }
    val songListState = rememberLazyListState()
    var isMiniPlayerDismissed by remember { mutableStateOf(false) }

    val onShowMiniPlayer = { isMiniPlayerDismissed = false }

    // Reset dismissal when track changes
    LaunchedEffect(playbackState.mediaId) {
        onShowMiniPlayer()
    }

    val currentSong = remember(playbackState.mediaId, libraryState.songs) {
        libraryState.songs.find { it.stableId == playbackState.mediaId }
    }

    if (playbackState.landscapeDualPaneEnabled) {
        LockScreenOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        val queueEntries = remember(playbackState.queueSongs) {
            playbackState.queueSongs.mapIndexed { index, song -> index to song }
        }
        ForcedLandscapeScreen(
            viewModel = playbackViewModel,
            currentSong = currentSong,
            isFavorite = currentSong?.stableId in libraryState.favoriteIds,
            queueEntries = queueEntries,
            onExitLandscape = { playbackViewModel.setLandscapeDualPaneEnabled(false) },
            onTogglePlayback = onTogglePlayback,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeek = onSeek,
            onToggleFavorite = { currentSong?.let { onToggleFavorite(it.stableId) } },
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            displayMode = playbackState.displayMode
        )
        return
    }

    SharedTransitionLayout {
        AnimatedContent(targetState = showPlayer, label = "playerTransition") { isPlayerOpen ->
            if (isPlayerOpen) {
                val animatedVisibilityScope = this@AnimatedContent
                NowPlayingScreen(
                    viewModel = playbackViewModel,
                    currentSong = currentSong,
                    allSongs = libraryState.songs,
                    isFavorite = currentSong?.stableId in libraryState.favoriteIds,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onBack = { showPlayer = false },
                    onToggleFavorite = { currentSong?.let { onToggleFavorite(it.stableId) } },
                    onTogglePlayback = onTogglePlayback,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSeek = onSeek,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onMoveQueueItem = onMoveQueueItem,
                    onRemoveQueueItem = onRemoveQueueItem,
                    onSeekToQueueItem = onSeekToQueueItem,
                    onSaveQueue = onSaveQueue,
                    onSetPlaybackSpeed = onSetPlaybackSpeed,
                    onSetOutputVolume = onSetOutputVolume,
                    landscapeDualPaneEnabled = playbackState.landscapeDualPaneEnabled,
                    displayMode = playbackState.displayMode
                )
            } else {
                val animatedVisibilityScope = this@AnimatedContent
                LibraryHome(
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermission = onRequestPermission,
                    libraryState = libraryState,
                    playbackState = playbackState,
                    playbackViewModel = playbackViewModel,
                    hasBluetoothPermission = hasBluetoothPermission,
                    onRequestBluetoothPermission = onRequestBluetoothPermission,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = animatedVisibilityScope,
                    activeMainScreen = activeMainScreen,
                    onActiveMainScreenChange = { activeMainScreen = it },
                    songListState = songListState,
                    isMiniPlayerDismissed = isMiniPlayerDismissed,
                    onDismissMiniPlayer = { isMiniPlayerDismissed = true },
                    onOpenPlayer = { showPlayer = true },
                    onRefresh = onRefresh,
                    onQueryChange = onQueryChange,
                    onToggleFavorite = onToggleFavorite,
                    onToggleHiddenFolder = onToggleHiddenFolder,
                    onCreatePlaylist = onCreatePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onAddSongToPlaylist = onAddSongToPlaylist,
                    onRemoveSongFromPlaylist = onRemoveSongFromPlaylist,
                    onImportPlaylist = onImportPlaylist,
                    onExportPlaylist = onExportPlaylist,
                    onDeleteSavedQueue = onDeleteSavedQueue,
                    onSetLyricPriority = onSetLyricPriority,
                    onSetGaplessEnabled = onSetGaplessEnabled,
                    onSetNormalizationEnabled = onSetNormalizationEnabled,
                    onSetDynamicColorEnabled = onSetDynamicColorEnabled,
                    onSetAppTheme = onSetAppTheme,
                    onSetRomanizationEnabled = onSetRomanizationEnabled,
                    onSetTranslationMode = onSetTranslationMode,
                    translationMode = translationMode,
                    onSelectMusicFolder = onSelectMusicFolder,
                    onSetFilterShortTracks = onSetFilterShortTracks,
                    onPlaySongs = { songs, index -> 
                        val selectedId = songs.getOrNull(index)?.stableId
                        if (selectedId != null && selectedId == playbackState.mediaId) {
                            onShowMiniPlayer()
                            showPlayer = true // [V18]: Smart Action - Open full player on same-song tap
                        } else {
                            onShowMiniPlayer()
                            onPlaySongs(songs, index) 
                        }
                    },
                    onPlayNext = { song ->
                        if (song.stableId == playbackState.mediaId) {
                            onShowMiniPlayer()
                            showPlayer = true // [V18]: Smart Action - Open full player on same-song tap
                        } else {
                            onShowMiniPlayer()
                            onPlayNext(song)
                        }
                    },
                    onAddToQueue = onAddToQueue,
                    onTogglePlayback = onTogglePlayback,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSeek = onSeek,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onSetPlaybackSpeed = onSetPlaybackSpeed,
                    onSetOutputVolume = onSetOutputVolume,
                    onSetDspEnabled = onSetDspEnabled,
                    onSelectDspPreset = onSelectDspPreset,
                    onSaveCustomDspPreset = onSaveCustomDspPreset,
                    onDeleteCustomDspPreset = onDeleteCustomDspPreset,
                    btGuardMode = btGuardMode,
                    onSelectBtGuardMode = onSelectBtGuardMode,
                    ambienceEnabled = ambienceEnabled,
                    onToggleAmbience = onToggleAmbience,
                    preampDb = preampDb,
                    onSetPreampDb = onSetPreampDb,
                    spatialAudioEnabled = spatialAudioEnabled,
                    onToggleSpatialAudio = onToggleSpatialAudio,
                    spatialAudioPreset = spatialAudioPreset,
                    onSelectSpatialAudioPreset = onSelectSpatialAudioPreset,
                    peqProfiles = peqProfiles,
                    activePeqProfileId = activePeqProfileId,
                    onSelectPeqProfile = onSelectPeqProfile,
                    onSavePeqProfile = onSavePeqProfile,
                    onDeletePeqProfile = onDeletePeqProfile,
                    routeProfiles = routeProfiles,
                    onClearRouteProfiles = onClearRouteProfiles,
                    onToggleLandscapeDualPane = { playbackViewModel.setLandscapeDualPaneEnabled(it) }
                )
            }
        }
    }

    if (libraryState.notice != null) {
        AlertDialog(
            onDismissRequest = onNoticeShown,
            title = { Text("Informasi") },
            text = { Text(libraryState.notice) },
            confirmButton = { TextButton(onClick = onNoticeShown) { Text("OK") } }
        )
    }
}

private val BottomNavigationScreens = listOf(
    MainScreen.Home,
    MainScreen.Library,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryHome(
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    libraryState: LibraryUiState,
    playbackState: PlaybackUiState,
    playbackViewModel: PlaybackViewModel,
    hasBluetoothPermission: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    activeMainScreen: MainScreen,
    onActiveMainScreenChange: (MainScreen) -> Unit,
    songListState: androidx.compose.foundation.lazy.LazyListState,
    isMiniPlayerDismissed: Boolean,
    onDismissMiniPlayer: () -> Unit,
    onOpenPlayer: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleHiddenFolder: (String) -> Unit,
    onCreatePlaylist: (String, List<String>) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onAddSongToPlaylist: (String, String) -> Unit,
    onRemoveSongFromPlaylist: (String, String) -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylist: (LocalPlaylist) -> Unit,
    onDeleteSavedQueue: (String) -> Unit,
    onSetLyricPriority: (LyricSourcePriority) -> Unit,
    onSetGaplessEnabled: (Boolean) -> Unit,
    onSetNormalizationEnabled: (Boolean) -> Unit,
    onSetDynamicColorEnabled: (Boolean) -> Unit,
    onSetAppTheme: (Int) -> Unit,
    onSetRomanizationEnabled: (Boolean) -> Unit,
    onSetTranslationMode: (Boolean) -> Unit,
    translationMode: Boolean,
    onSelectMusicFolder: () -> Unit,
    onSetFilterShortTracks: (Boolean) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetOutputVolume: (Float) -> Unit,
    onSetDspEnabled: (Boolean) -> Unit,
    onSelectDspPreset: (String) -> Unit,
    onSaveCustomDspPreset: (String, List<Float>) -> Unit,
    onDeleteCustomDspPreset: (String) -> Unit,
    btGuardMode: Int,
    onSelectBtGuardMode: (Int) -> Unit,
    ambienceEnabled: Boolean,
    onToggleAmbience: (Boolean) -> Unit,
    preampDb: Float,
    onSetPreampDb: (Float) -> Unit,
    spatialAudioEnabled: Boolean,
    onToggleSpatialAudio: (Boolean) -> Unit,
    spatialAudioPreset: Int,
    onSelectSpatialAudioPreset: (Int) -> Unit,
    peqProfiles: List<PeqProfile>,
    activePeqProfileId: String,
    onSelectPeqProfile: (String) -> Unit,
    onSavePeqProfile: (List<PeqBand>, Float, String) -> Unit,
    onDeletePeqProfile: (String) -> Unit,
    routeProfiles: Map<String, String>,
    onClearRouteProfiles: () -> Unit,
    onToggleLandscapeDualPane: (Boolean) -> Unit,
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var seededPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var detailPlaylistId by remember { mutableStateOf<String?>(null) }

    // [V31]: Improved Back Navigation logic to prevent accidental app exit.
    // 1. If search is open, close it.
    BackHandler(enabled = showSearch) {
        showSearch = false
    }

    // 2. If not on Home tab, go back to previous logical screen.
    BackHandler(enabled = !showSearch && activeMainScreen != MainScreen.Home) {
        when (activeMainScreen) {
            MainScreen.TranslationWorkbench -> onActiveMainScreenChange(MainScreen.Settings)
            else -> onActiveMainScreenChange(MainScreen.Home)
        }
    }

    val hideBars = activeMainScreen == MainScreen.TranslationWorkbench

    Scaffold(
        topBar = {
            if (!showSearch && !hideBars) {
                HoshiTopAppBar(
                    activeScreen = activeMainScreen,
                    onSearchClick = { showSearch = true },
                    onSettingsClick = { onActiveMainScreenChange(MainScreen.Settings) },
                    onBackClick = { onActiveMainScreenChange(MainScreen.Home) }
                )
            }
        },
        bottomBar = {
            if (!playbackState.landscapeDualPaneEnabled && !hideBars) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.height(65.dp)
                ) {
                    BottomNavigationScreens.forEach { screen ->
                        val selected = activeMainScreen == screen
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onActiveMainScreenChange(screen) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.iconFilled else screen.iconOutlined,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.animation.AnimatedContent(
                targetState = activeMainScreen,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = AppMotion.DURATION_SCREEN,
                            easing = AppMotion.EASING
                        )
                    ) { direction * it / 8 } + fadeIn(
                        animationSpec = tween(AppMotion.DURATION_FADE)
                    )) togetherWith (slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = AppMotion.DURATION_SCREEN,
                            easing = AppMotion.EASING
                        )
                    ) { -direction * it / 8 } + fadeOut(
                        animationSpec = tween(AppMotion.DURATION_FADE)
                    ))
                },
                label = "mainScreenTransition"
            ) { screen ->
                val animatedVisibilityScope = this@AnimatedContent
                when (screen) {
                    MainScreen.Home -> {
                        HomeContent(
                            libraryState = libraryState,
                            onSearchClick = { showSearch = true },
                            onPlaySongs = onPlaySongs,
                            onOpenPlaylist = { detailPlaylistId = it },
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                    MainScreen.Library -> {
                        LibraryContent(
                            hasAudioPermission = hasAudioPermission,
                            onRequestPermission = onRequestPermission,
                            onSelectMusicFolder = onSelectMusicFolder,
                            libraryState = libraryState,
                            onRefresh = onRefresh,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            songListState = songListState,
                            onPlaySongs = onPlaySongs,
                            onToggleFavorite = onToggleFavorite,
                            onAddToQueue = onAddToQueue,
                            onOpenActions = { actionSong = it },
                            onToggleHiddenFolder = onToggleHiddenFolder,
                            onCreatePlaylist = { seededPlaylistSong = null; showCreatePlaylist = true },
                            onImportPlaylist = onImportPlaylist,
                            onOpenPlaylist = { detailPlaylistId = it.id },
                            onExportPlaylist = onExportPlaylist,
                            onDeletePlaylist = onDeletePlaylist,
                            onDeleteSavedQueue = onDeleteSavedQueue,
                            onRemoveSongFromPlaylist = onRemoveSongFromPlaylist,
                            playbackMediaId = playbackState.mediaId
                        )
                    }
                    MainScreen.Settings -> {
                        SettingsContent(
                            hasBluetoothPermission = hasBluetoothPermission,
                            onRequestBluetoothPermission = onRequestBluetoothPermission,
                            lyricPriority = playbackState.lyricPriority,
                            onSetLyricPriority = onSetLyricPriority,
                            gaplessEnabled = playbackState.gaplessEnabled,
                            onToggleGapless = onSetGaplessEnabled,
                            normalizationEnabled = playbackState.normalizationEnabled,
                            onToggleNormalization = onSetNormalizationEnabled,
                            dynamicColorEnabled = playbackState.dynamicColorEnabled,
                            onToggleDynamicColor = onSetDynamicColorEnabled,
                            appTheme = playbackState.appTheme,
                            onSetAppTheme = onSetAppTheme,
                            romanizationEnabled = playbackState.romanizationEnabled,
                            onToggleRomanization = onSetRomanizationEnabled,
                            translationMode = translationMode,
                            onToggleTranslationMode = onSetTranslationMode,
                            selectedMusicFolder = libraryState.selectedMusicFolder,
                            onSelectMusicFolder = onSelectMusicFolder,
                            filterShortTracks = libraryState.filterShortTracks,
                            onToggleFilterShortTracks = onSetFilterShortTracks,
                            landscapeDualPaneEnabled = playbackState.landscapeDualPaneEnabled,
                            onToggleLandscapeDualPane = onToggleLandscapeDualPane,
                            dspEnabled = playbackState.dspEnabled,
                            onSetDspEnabled = onSetDspEnabled,
                            dspPresetId = playbackState.dspPresetId,
                            customDspPresets = playbackState.customDspPresets,
                            onSelectDspPreset = onSelectDspPreset,
                            onSaveCustomDspPreset = onSaveCustomDspPreset,
                            onDeleteCustomDspPreset = onDeleteCustomDspPreset,
                            btGuardMode = btGuardMode,
                            onSelectBtGuardMode = onSelectBtGuardMode,
                            ambienceEnabled = ambienceEnabled,
                            onToggleAmbience = onToggleAmbience,
                            preampDb = preampDb,
                            onSetPreampDb = onSetPreampDb,
                            spatialAudioEnabled = spatialAudioEnabled,
                            onToggleSpatialAudio = onToggleSpatialAudio,
                            spatialAudioPreset = spatialAudioPreset,
                            onSelectSpatialAudioPreset = onSelectSpatialAudioPreset,
                            peqProfiles = peqProfiles,
                            activePeqProfileId = activePeqProfileId,
                            onSelectPeqProfile = onSelectPeqProfile,
                            onSavePeqProfile = onSavePeqProfile,
                            onDeletePeqProfile = onDeletePeqProfile,
                            autoSkipIntroOutro = playbackState.autoSkipIntroOutro,
                            onToggleAutoSkip = { playbackViewModel.setAutoSkipIntroOutro(it) },
                            boutiqueBypassEnabled = playbackState.boutiqueBypassEnabled,
                            onToggleBoutiqueBypass = { playbackViewModel.setBoutiqueBypassEnabled(it) },
                            routeProfiles = routeProfiles,
                            onClearRouteProfiles = onClearRouteProfiles,
                            onActiveMainScreenChange = onActiveMainScreenChange
                        )
                    }
                    MainScreen.TranslationWorkbench -> {
                        TranslationWorkbenchScreen(
                            libraryState = libraryState,
                            playbackViewModel = playbackViewModel,
                            onBack = { onActiveMainScreenChange(MainScreen.Settings) }
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = playbackState.mediaId != null && !isMiniPlayerDismissed,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    FastBlurMiniPlayer(
                        viewModel = playbackViewModel,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onOpenPlayer = onOpenPlayer,
                        onTogglePlayback = onTogglePlayback,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onSeek = onSeek,
                        onDismiss = onDismissMiniPlayer
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showSearch,
            enter = fadeIn(tween(AppMotion.DURATION_FADE)) + slideInVertically(tween(AppMotion.DURATION_SCREEN, easing = AppMotion.EASING)) { -it / 20 },
            exit = fadeOut(tween(AppMotion.DURATION_FADE)) + slideOutVertically(tween(AppMotion.DURATION_SCREEN, easing = AppMotion.EASING)) { -it / 20 }
        ) {
            SearchContent(
                query = libraryState.query,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@AnimatedVisibility,
                onQueryChange = onQueryChange,
                onClose = { showSearch = false },
                filteredSongs = libraryState.filteredSongs,
                favoriteIds = libraryState.favoriteIds,
                currentMediaId = playbackState.mediaId,
                onPlaySongs = onPlaySongs,
                onToggleFavorite = onToggleFavorite,
                onAddToQueue = onAddToQueue,
                onOpenActions = { actionSong = it }
            )
        }
    }

    actionSong?.let { song ->
        SongActionsSheet(
            song = song,
            isFavorite = song.stableId in libraryState.favoriteIds,
            playlists = libraryState.playlists,
            onDismiss = { actionSong = null },
            onPlayNext = { onPlayNext(song) },
            onAddQueue = { onAddToQueue(song) },
            onToggleFavorite = { onToggleFavorite(song.stableId) },
            onAddToPlaylist = { playlistId -> onAddSongToPlaylist(playlistId, song.stableId) },
            onRequestNewPlaylist = { seededPlaylistSong = song; showCreatePlaylist = true }
        )
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            seededSong = seededPlaylistSong,
            onDismiss = { showCreatePlaylist = false },
            onCreate = onCreatePlaylist
        )
    }
}

@Composable
private fun HoshiTopAppBar(
    activeScreen: MainScreen,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(100.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = activeScreen,
            transitionSpec = {
                fadeIn(animationSpec = tween(AppMotion.DURATION_FADE)) togetherWith
                        fadeOut(animationSpec = tween(AppMotion.DURATION_FADE))
            },
            label = "topAppBarContentTransition"
        ) { screen ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (screen) {
                    MainScreen.Home -> {
                        Spacer(Modifier.weight(1f))
                        TopCircleButton(Icons.Rounded.Search, "Cari", onSearchClick)
                        Spacer(Modifier.width(8.dp))
                        TopCircleButton(Icons.Rounded.Settings, "Pengaturan", onSettingsClick)
                    }
                    MainScreen.Library -> {
                        Text(
                            text = "Koleksi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp).weight(1f)
                        )
                        TopCircleButton(Icons.Rounded.Search, "Cari", onSearchClick)
                        Spacer(Modifier.width(8.dp))
                        TopCircleButton(Icons.Rounded.Settings, "Pengaturan", onSettingsClick)
                    }
                    MainScreen.Settings,
                    MainScreen.TranslationWorkbench -> {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Kembali",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = when (screen) {
                                MainScreen.Settings -> "Pengaturan"
                                MainScreen.TranslationWorkbench -> "Workbench Editor"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

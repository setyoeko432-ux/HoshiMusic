package com.naomi.hoshimusic.ui.player

import android.os.Build
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.naomi.hoshimusic.data.LyricDisplayMode
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.TechnicalInfoSheet
import com.naomi.hoshimusic.ui.motion.AppMotion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@androidx.media3.common.util.UnstableApi
@Composable
internal fun NowPlayingScreen(
    viewModel: PlaybackViewModel,
    currentSong: Song?,
    allSongs: List<Song>,
    isFavorite: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onSeekToQueueItem: (Int) -> Unit,
    onSaveQueue: (String, List<String>) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetOutputVolume: (Float) -> Unit,
    landscapeDualPaneEnabled: Boolean,
    displayMode: LyricDisplayMode,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var isLyricsMode by rememberSaveable { mutableStateOf(value = false) }
    BackHandler(enabled = isLyricsMode) { isLyricsMode = false }
    BackHandler(enabled = !isLyricsMode) { onBack() }

    val positionState = viewModel.positionMsFlow.collectAsStateWithLifecycle()

    var activeSheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val shouldUseDualPane = isLandscape && landscapeDualPaneEnabled
    
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if ((available.y < -40f) && !isLyricsMode && (activeSheet == null)) {
                    activeSheet = PlayerSheet.Queue
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }

    val queueEntries = remember(state.queueSongs) {
        state.queueSongs.mapIndexed { index, song -> index to song }
    }

    val containerModifier = with(sharedTransitionScope) {
        Modifier
            .fillMaxSize()
            .sharedBounds(
                rememberSharedContentState(key = "active-player-container"),
                animatedVisibilityScope = animatedVisibilityScope,
                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop),
                boundsTransform = AppMotion.PlayerBoundsTransform,
                zIndexInOverlay = 1f
            )
            .nestedScroll(nestedScrollConnection)
    }

    Box(
        modifier = containerModifier
            .pointerInput(isLyricsMode) {
                if (!isLyricsMode) {
                    detectVerticalDragGestures { change, dragAmount ->
                        if (activeSheet == null && dragAmount < -15f) {
                            activeSheet = PlayerSheet.Queue
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val scrimBrush = remember {
            Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.55f), 
                    Color.Black.copy(alpha = 0.85f)
                )
            )
        }

        val context = LocalContext.current
        val artworkRequest = remember(state.artworkUri, shouldUseDualPane) {
            ImageRequest.Builder(context)
                .data(state.artworkUri)
                .size(if (shouldUseDualPane) 400 else 800)
                .precision(coil.size.Precision.INEXACT)
                .allowHardware(true)
                .crossfade(500)
                .build()
        }

        val sheetDim = animateFloatAsState(
            targetValue = if (activeSheet != null) 0.25f else 0f, // [9a] Scrim lebih tipis agar artwork tetap terlihat
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "sheetDim"
        )

        // [A8] Solid gradient background to prevent transparency flashes during transitions
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black,
                            androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                )
        )

        AsyncImage(
            model = artworkRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= 31)
                        Modifier.blur(36.dp, BlurredEdgeTreatment.Unbounded)
                    else Modifier
                )
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = scrimBrush)
                        drawRect(color = Color.Black, alpha = sheetDim.value)
                    }
                }
        )

        if (shouldUseDualPane) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = isLyricsMode,
                        transitionSpec = {
                            if (targetState) {
                                (slideInVertically(spring(stiffness = 500f)) { it / 3 } + fadeIn(tween(350))) togetherWith fadeOut(tween(250))
                            } else {
                                fadeIn(tween(350)) togetherWith (slideOutVertically(spring(stiffness = 500f)) { it / 3 } + fadeOut(tween(250)))
                            }
                        },
                        label = "playerModeTransition"
                    ) { lyricsActive ->
                        if (lyricsActive) {
                            FullscreenLyricsView(
                                state = state,
                                positionState = positionState,
                                onClose = { isLyricsMode = false },
                                onSeek = onSeek,
                                viewModel = viewModel,
                                displayMode = displayMode,
                                onTogglePlayback = onTogglePlayback,
                                onSkipNext = onSkipNext,
                                onSkipPrevious = onSkipPrevious,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat
                            )
                        } else {
                            DefaultAlbumView(
                                state = state,
                                currentSong = currentSong,
                                isFavorite = isFavorite,
                                positionState = positionState,
                                onBack = onBack,
                                onToggleFavorite = onToggleFavorite,
                                onOpenInfo = { activeSheet = PlayerSheet.Info },
                                onEnterLyricsMode = { isLyricsMode = true },
                                displayMode = displayMode,
                                onToggleRomanization = { enabled ->
                                    if (enabled) viewModel.setRomanizationEnabled(true)
                                    else {
                                        viewModel.setRomanizationEnabled(false)
                                        viewModel.setTranslationMode(false)
                                    }
                                },
                                onSeek = onSeek,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onTogglePlayback = onTogglePlayback,
                                onSkipNext = onSkipNext,
                                onSkipPrevious = onSkipPrevious,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                viewModel = viewModel
                            )
                        }
                    }
                }
                
                Surface(
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                    color = Color.Black.copy(alpha = 0.25f)
                ) {
                    QueueListContent(
                        entries = queueEntries,
                        currentIndex = state.currentQueueIndex,
                        onSelect = onSeekToQueueItem,
                        modifier = Modifier.fillMaxSize().padding(end = WindowInsets.navigationBars.asPaddingValues().calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = isLyricsMode,
                transitionSpec = {
                    if (targetState) {
                        (slideInVertically(spring(stiffness = 500f)) { it / 3 } + fadeIn(tween(350))) togetherWith fadeOut(tween(250))
                    } else {
                        fadeIn(tween(350)) togetherWith (slideOutVertically(spring(stiffness = 500f)) { it / 3 } + fadeOut(tween(250)))
                    }
                },
                label = "playerModeTransition"
            ) { lyricsActive ->
                if (lyricsActive) {
                    FullscreenLyricsView(
                        state = state,
                        positionState = positionState,
                        onClose = { isLyricsMode = false },
                        onSeek = onSeek,
                        viewModel = viewModel,
                        displayMode = displayMode,
                        onTogglePlayback = onTogglePlayback,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat
                    )
                } else {
                    DefaultAlbumView(
                        state = state,
                        currentSong = currentSong,
                        isFavorite = isFavorite,
                        positionState = positionState,
                        onBack = onBack,
                        onToggleFavorite = onToggleFavorite,
                        onOpenInfo = { activeSheet = PlayerSheet.Info },
                        onEnterLyricsMode = { isLyricsMode = true },
                        displayMode = displayMode,
                        onToggleRomanization = { enabled ->
                            if (enabled) viewModel.setRomanizationEnabled(true)
                            else {
                                viewModel.setRomanizationEnabled(false)
                                viewModel.setTranslationMode(false)
                            }
                        },
                        onSeek = onSeek,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onTogglePlayback = onTogglePlayback,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    if (activeSheet != null && (!isLandscape || activeSheet != PlayerSheet.Queue)) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            containerColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 400.dp)
                    .navigationBarsPadding(), // [9b] Padding navigasi di level Surface
                color = Color.Black.copy(alpha = 0.45f), // HUD style: sedikit lebih pekat untuk kontras teks putih
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) // [9a] Sudut membulat modern
            ) {
                when (activeSheet) {
                    PlayerSheet.Queue -> QueueSheet(entries = queueEntries, currentIndex = state.currentQueueIndex, onSelect = onSeekToQueueItem)
                    PlayerSheet.Speed -> SpeedSheet(state.playbackSpeed, onSetPlaybackSpeed)
                    PlayerSheet.Info -> currentSong?.let { song ->
                        val telemetry by viewModel.dspTelemetryFlow.collectAsStateWithLifecycle()
                        TechnicalInfoSheet(
                            song = song,
                            signalPath = state.signalPath,
                            routeType = state.audioRoute.type,
                            dspTelemetry = telemetry,
                            onClose = { activeSheet = null }
                        )
                    }
                    null -> {}
                }
            }
        }
    }
}

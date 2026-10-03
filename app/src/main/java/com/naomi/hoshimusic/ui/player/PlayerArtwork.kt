@file:androidx.media3.common.util.UnstableApi

package com.naomi.hoshimusic.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.naomi.hoshimusic.data.LyricDisplayMode
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.PlaybackUiState
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.motion.AppMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun DefaultAlbumView(
    state: PlaybackUiState,
    currentSong: Song?,
    isFavorite: Boolean,
    positionState: State<Long>,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenInfo: () -> Unit,
    onEnterLyricsMode: () -> Unit,
    displayMode: LyricDisplayMode,
    onCycleDisplayMode: () -> Unit = {},
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlaybackViewModel,
    isCompactForLandscape: Boolean = false,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!isCompactForLandscape) {
                TopAppBar(
                    title = { },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White) } },
                    actions = {
                        IconButton(onClick = onOpenInfo) { Icon(Icons.Rounded.Info, null, tint = Color.White) }
                        IconButton(onClick = onToggleFavorite) { Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (isCompactForLandscape) PaddingValues(0.dp) else padding)) {
            val artworkSize = if (isCompactForLandscape) {
                minOf(this@BoxWithConstraints.maxHeight * 0.45f, 200.dp)
            } else {
                minOf(this@BoxWithConstraints.maxWidth - 48.dp, 340.dp)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = if (isCompactForLandscape) 20.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isCompactForLandscape) Arrangement.Center else Arrangement.Top
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(if (isCompactForLandscape) 20.dp else 32.dp),
                        shadowElevation = if (isCompactForLandscape) 8.dp else 16.dp,
                        color = Color.Transparent,
                        border = null,
                        modifier = Modifier.size(artworkSize)
                    ) {
                        val artworkModifier = with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "active-player-hero-artwork"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ ->
                                    tween(durationMillis = 400, easing = FastOutSlowInEasing)
                                },
                                zIndexInOverlay = 2f
                            )
                        }
                        Artwork(
                            model = state.artworkUri ?: currentSong?.contentUri,
                            size = artworkSize,
                            cornerRadius = if (isCompactForLandscape) 20.dp else 32.dp,
                            modifier = artworkModifier,
                            highQuality = true
                        )
                    }
                }

                item { Spacer(Modifier.height(if (isCompactForLandscape) 16.dp else 32.dp)) }

                item {
                    Column(
                        modifier = with(sharedTransitionScope) {
                            Modifier
                                .fillMaxWidth()
                                .sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "active-player-text-info"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth),
                                    boundsTransform = AppMotion.PlayerBoundsTransform,
                                    zIndexInOverlay = 2f
                                )
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.title, 
                            style = if (isCompactForLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, 
                            color = Color.White, 
                            fontWeight = FontWeight.Black, 
                            textAlign = TextAlign.Center, 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis
                        )
                        currentSong?.romanizedTitle?.let { title ->
                            Text(title, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                        }
                        if (!isCompactForLandscape) {
                            Spacer(Modifier.height(14.dp))
                            currentSong?.let { song ->
                                val label = song.getQualityLabel()
                                val cleanFormat = song.getCleanFormat()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (label != null) {
                                        val labelColor = when (label) { "HI-RES" -> Color(0xFFFFD700); "LOSSLESS" -> Color(0xFFFF4081); "HQ" -> Color(0xFF2196F3); else -> Color.White }
                                        Text(label, color = labelColor, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                        Text("   •   ", color = Color.White.copy(alpha = 0.3f), fontWeight = FontWeight.Bold)
                                    }
                                    Text(cleanFormat, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                        Spacer(Modifier.height(if (isCompactForLandscape) 4.dp else 8.dp))
                        val artistDisplay = if (currentSong?.romanizedArtist.isNullOrBlank()) state.artist else "${state.artist} (${currentSong?.romanizedArtist ?: ""})"
                        Text(
                            artistDisplay, 
                            style = if (isCompactForLandscape) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, 
                            color = Color.White, 
                            textAlign = TextAlign.Center, 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!isCompactForLandscape) {
                    item {
                        LyricsPreviewSection(
                            lyrics = state.lyrics,
                            positionState = positionState,
                            displayMode = displayMode,
                            isPlaying = state.isPlaying,
                            playbackSpeed = state.playbackSpeed,
                            viewModel = viewModel,
                            onCycleDisplayMode = onCycleDisplayMode,
                            onEnterLyricsMode = onEnterLyricsMode
                        )
                    }
                }

                item { Spacer(Modifier.height(if (isCompactForLandscape) 20.dp else 32.dp)) }

                item {
                    SeekSection(
                        viewModel = viewModel,
                        isPlaying = state.isPlaying,
                        shuffleEnabled = state.shuffleEnabled,
                        repeatMode = state.repeatMode,
                        onSeek = onSeek,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onTogglePlayback = onTogglePlayback,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious
                    )
                }

                if (!isCompactForLandscape) {
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ForcedLandscapeScreen(
    viewModel: PlaybackViewModel,
    currentSong: Song?,
    isFavorite: Boolean,
    queueEntries: List<Pair<Int, Song>>,
    onExitLandscape: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    displayMode: LyricDisplayMode,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val positionState = viewModel.positionMsFlow.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    SharedTransitionLayout {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(state.artworkUri)
                    .size(200)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(36.dp)
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)))

            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                    // Panel landscape selalu menampilkan player, bukan lirik, jadi
                    // targetState sengaja konstan: AnimatedContent di sini hanya
                    // menyediakan AnimatedVisibilityScope untuk shared element
                    // di DefaultAlbumView, bukan untuk bertransisi.
                    @Suppress("UnusedContentLambdaTargetStateParameter")
                    AnimatedContent(targetState = false, label = "forcedPlayer") { _ ->
                        DefaultAlbumView(
                            state = state,
                            currentSong = currentSong,
                            isFavorite = isFavorite,
                            positionState = positionState,
                            onBack = { },
                            onToggleFavorite = onToggleFavorite,
                            onOpenInfo = { },
                            onEnterLyricsMode = { 
                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                            },
                            displayMode = displayMode,
                            onCycleDisplayMode = { viewModel.cycleLyricDisplayMode() },
                            onSeek = onSeek,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            onTogglePlayback = onTogglePlayback,
                            onSkipNext = onSkipNext,
                            onSkipPrevious = onSkipPrevious,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                            viewModel = viewModel,
                            isCompactForLandscape = true
                        )
                    }

                    IconButton(
                        onClick = onExitLandscape,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp).statusBarsPadding()
                    ) {
                        Icon(Icons.Rounded.CloseFullscreen, contentDescription = "Exit Landscape", tint = Color.White)
                    }
                }

                Surface(
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                    color = Color.Transparent
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).statusBarsPadding(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(3) { iteration ->
                                val isCurrent = pagerState.currentPage == iteration
                                val color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.3f)
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (isCurrent) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f)
                        ) { page ->
                            when (page) {
                                0 -> {
                                    QueueListContent(
                                        entries = queueEntries,
                                        currentIndex = state.currentQueueIndex,
                                        onSelect = { viewModel.seekToQueueItem(it) },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                1 -> {
                                    FullscreenLyricsView(
                                        state = state,
                                        positionState = positionState,
                                        onClose = { 
                                            coroutineScope.launch { pagerState.animateScrollToPage(0) } 
                                        },
                                        onSeek = onSeek,
                                        viewModel = viewModel,
                                        displayMode = displayMode,
                                        onTogglePlayback = onTogglePlayback,
                                        onSkipNext = onSkipNext,
                                        onSkipPrevious = onSkipPrevious,
                                        onToggleShuffle = onToggleShuffle,
                                        onCycleRepeat = onCycleRepeat,
                                        isCompactForLandscape = true
                                    )
                                }
                                else -> CarSettingsPage(viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.naomi.hoshimusic.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.os.Build
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.rememberSmoothPosition
import com.naomi.hoshimusic.ui.motion.AppMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun FastBlurMiniPlayer(
    viewModel: PlaybackViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val mini by viewModel.miniPlayerState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 64.dp.toPx() }
    val openThreshold = with(density) { 54.dp.toPx() }

    var dragX by remember(mini.mediaId) { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val animatedX by animateFloatAsState(targetValue = dragX, spring(dampingRatio = 0.78f, stiffness = 450f), label = "x")
    val animatedY by animateFloatAsState(targetValue = dragY, spring(dampingRatio = 0.78f, stiffness = 450f), label = "y")

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val animatedHeight by animateDpAsState(
        targetValue = if (isExpanded) 100.dp else 64.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "miniPlayerHeight"
    )

    val artworkSize by animateDpAsState(
        targetValue = if (isExpanded) 72.dp else 46.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "artworkSize"
    )

    val containerSharedBoundsModifier = with(sharedTransitionScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(key = "active-player-container"),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = AppMotion.PlayerBoundsTransform,
            zIndexInOverlay = 1f
        ).graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }

    Surface(
        shape = RoundedCornerShape(if (isExpanded) 24.dp else 18.dp),
        color = Color.Black,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .then(containerSharedBoundsModifier)
            .graphicsLayer {
                val currentX = animatedX
                val currentY = animatedY.coerceAtMost(0f)
                translationX = currentX
                translationY = currentY

                val pullProgress = (abs(currentY) / (openThreshold * 1.5f)).coerceIn(0f, 1f)
                scaleX = 1f + (pullProgress * 0.04f)
                scaleY = 1f + (pullProgress * 0.04f)

                alpha = (1f - (abs(currentX) / (swipeThreshold * 1.8f))).coerceIn(0.5f, 1f)
            }
            .pointerInput(mini.mediaId) {
                detectDragGestures(
                    onDragStart = { dragX = 0f; dragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount.y < 0f || dragY < 0f) {
                            // Vertical lock (Swipe up to open)
                            dragY = (dragY + dragAmount.y).coerceAtMost(0f)
                        } else {
                            // Horizontal lock (Dismiss ONLY)
                            dragX += dragAmount.x
                        }
                    },
                    onDragCancel = { dragX = 0f; dragY = 0f },
                    onDragEnd = {
                        val dismissThreshold = 48.dp.toPx()
                        if (dragY < -openThreshold) { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenPlayer() 
                        } else if (abs(dragX) > dismissThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }
                        dragX = 0f; dragY = 0f
                    }
                )
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { },
                onDoubleClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isExpanded = !isExpanded
                }
            )
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        val scrimBrush = remember {
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)))
        }

        val context = LocalContext.current
        
        // Phase 4: Hardware blur hardware-compatibility check
        val isLowSpec = remember(context) {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem < 4 * 1024 * 1024 * 1024L // < 4GB
        }
        
        val supportsRenderBlur = Build.VERSION.SDK_INT >= 31 && !isLowSpec
        val blurSourcePx = if (supportsRenderBlur) 128 else 24

        // API 31+ : sumber tajam, blur nyata via RenderEffect. Resep sama dengan
        //           NowPlayingScreen, hanya radiusnya lebih kecil.
        // API 26-30: Modifier.blur adalah no-op, jadi blur diambil dari upscaling 24x24.
        val backgroundRequest = remember(mini.artworkUri, blurSourcePx) {
            ImageRequest.Builder(context)
                .data(mini.artworkUri)
                .size(blurSourcePx)
                .memoryCacheKey("miniblur-$blurSourcePx-${mini.artworkUri}")
                .allowHardware(true)
                .crossfade(220)
                .build()
        }

        AsyncImage(
            model = backgroundRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (supportsRenderBlur)
                        Modifier.blur(24.dp, BlurredEdgeTreatment.Unbounded)
                    else Modifier
                )
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        drawRect(scrimBrush)
                    }
                }
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artworkModifier = with(sharedTransitionScope) {
                Modifier
                    .size(artworkSize)
                    .clip(RoundedCornerShape(12.dp))
                    .sharedElement(
                        rememberSharedContentState(key = "active-player-hero-artwork"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = AppMotion.PlayerBoundsTransform,
                        zIndexInOverlay = 2f
                    )
            }

            Artwork(
                model = mini.artworkUri,
                size = artworkSize,
                cornerRadius = 12.dp,
                modifier = artworkModifier
            )

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = mini.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = mini.artist,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onSkipPrevious, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = onTogglePlayback, modifier = Modifier.size(32.dp)) {
                                Icon(if (mini.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = onSkipNext, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            
                            Spacer(Modifier.width(12.dp))
                            CompactMiniSlider(viewModel = viewModel, onSeek = onSeek)
                        }
                    }
                }
            }

            if (!isExpanded) {
                IsolatedMiniProgress(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
internal fun CompactMiniSlider(
    viewModel: PlaybackViewModel,
    onSeek: (Long) -> Unit
) {
    val durationFlow = remember(viewModel) { viewModel.uiState.map { it.durationMs }.distinctUntilChanged() }
    val durationMs by durationFlow.collectAsStateWithLifecycle(0L)
    val rawPosition by viewModel.positionMsFlow.collectAsStateWithLifecycle()
    val isPlayingFlow = remember(viewModel) { viewModel.uiState.map { it.isPlaying }.distinctUntilChanged() }
    val isPlaying by isPlayingFlow.collectAsStateWithLifecycle(false)
    val speedFlow = remember(viewModel) { viewModel.uiState.map { it.playbackSpeed }.distinctUntilChanged() }
    val speed by speedFlow.collectAsStateWithLifecycle(1f)
    
    val smoothPosition by rememberSmoothPosition(rawPosition, isPlaying, speed)
    
    var draggedPosition by remember { mutableFloatStateOf(-1f) }
    val visiblePosition = if (draggedPosition >= 0f) draggedPosition.toLong() else smoothPosition

    Slider(
        value = visiblePosition.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
        onValueChange = { draggedPosition = it },
        onValueChangeFinished = {
            if (draggedPosition >= 0f) onSeek(draggedPosition.toLong())
            draggedPosition = -1f
        },
        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
        )
    )
}

@Composable
internal fun IsolatedMiniProgress(
    viewModel: PlaybackViewModel,
    modifier: Modifier = Modifier
) {
    val durationFlow = remember(viewModel) { viewModel.uiState.map { it.durationMs }.distinctUntilChanged() }
    val duration by durationFlow.collectAsStateWithLifecycle(0L)
    val rawPosition by viewModel.positionMsFlow.collectAsStateWithLifecycle()
    val isPlayingFlow = remember(viewModel) { viewModel.uiState.map { it.isPlaying }.distinctUntilChanged() }
    val isPlaying by isPlayingFlow.collectAsStateWithLifecycle(false)
    val speedFlow = remember(viewModel) { viewModel.uiState.map { it.playbackSpeed }.distinctUntilChanged() }
    val speed by speedFlow.collectAsStateWithLifecycle(1f)

    val smoothPosition by rememberSmoothPosition(rawPosition, isPlaying, speed)

    val progress = remember(smoothPosition, duration) {
        if (duration > 0L) (smoothPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    }

    Spacer(
        modifier = modifier.drawWithCache {
            val progressWidth = size.width * progress
            onDrawBehind {
                drawRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset.Zero,
                    size = Size(progressWidth, size.height)
                )
            }
        }
    )
}

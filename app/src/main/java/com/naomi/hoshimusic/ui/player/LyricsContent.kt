@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.naomi.hoshimusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naomi.hoshimusic.data.LyricDisplayMode
import com.naomi.hoshimusic.data.LyricLine
import com.naomi.hoshimusic.data.LyricsResult
import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.ui.PlaybackUiState
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.rememberSmoothPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@androidx.media3.common.util.UnstableApi
@Composable
internal fun FullscreenLyricsView(
    state: PlaybackUiState,
    positionState: State<Long>,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    viewModel: PlaybackViewModel,
    displayMode: LyricDisplayMode,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    isCompactForLandscape: Boolean = false
) {
    val listState = rememberLazyListState()
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    
    val smoothPositionState = rememberSmoothPosition(
        realPosition = positionState.value,
        isPlaying = state.isPlaying,
        playbackSpeed = state.playbackSpeed
    )
    
    val density = LocalDensity.current
    val activeLineOffsetPx = remember(density) {
        with(density) { 72.dp.roundToPx() }
    }
    
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) lastInteractionTime = System.currentTimeMillis()
            }
    }

    val presState by viewModel.presentationState.collectAsStateWithLifecycle()

    val activeIndex by remember(presState, state.lyrics.lines) {
        derivedStateOf {
            val lines = state.lyrics.lines
            if (lines.isEmpty()) return@derivedStateOf -1
            
            // [V24]: Check if lyrics are "Static" (Unsynchronized)
            // If all lines have very low timestamps (sequential index), treat as static.
            val isStatic = lines.all { it.timestampMs <= lines.size.toLong() }
            if (isStatic) return@derivedStateOf -1

            when (val s = presState) {
                is LyricPresentationState.Vocal -> s.lineIndex
                is LyricPresentationState.SectionLabel -> s.lineIndex
                is LyricPresentationState.Instrumental -> {
                    val nextIdx = s.previousLineIndex + 1
                    if (nextIdx < lines.size && lines[nextIdx].annotationType == LyricAnnotationType.EXPLICIT_INSTRUMENTAL) {
                        nextIdx
                    } else {
                        s.previousLineIndex
                    }
                }
                else -> -1
            }
        }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && (System.currentTimeMillis() - lastInteractionTime) > 3500L) {
            listState.animateScrollToItem(activeIndex, -activeLineOffsetPx)
        }
    }

    var swipeOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedSwipeOffsetY by animateFloatAsState(swipeOffsetY, spring(stiffness = 600f), label = "lyricsDismiss")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .offset { IntOffset(0, animatedSwipeOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0 || swipeOffsetY > 0) {
                            change.consume()
                            swipeOffsetY = (swipeOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (swipeOffsetY > 120f) onClose()
                        swipeOffsetY = 0f
                    },
                    onDragCancel = { swipeOffsetY = 0f }
                )
            }
    ) {
        if (!isCompactForLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(state.artworkUri, 42.dp, cornerRadius = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    Text(state.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = if (isCompactForLandscape) 24.dp else 60.dp, bottom = 60.dp), 
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            val isStatic = state.lyrics.lines.isNotEmpty() && state.lyrics.lines.all { it.timestampMs <= state.lyrics.lines.size.toLong() }
            
            if (state.lyrics.lines.isEmpty()) {
                item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("Lirik tidak tersedia.", color = Color.White.copy(alpha = 0.4f)) } }
            } else {
                itemsIndexed(state.lyrics.lines, key = { _, line -> line.timestampMs }) { index, line ->
                    val isActive = index == activeIndex
                    
                    val alpha by animateFloatAsState(
                        targetValue = if (isActive || isStatic) 1f else 0.30f, 
                        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
                        label = "lAlpha"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isActive) 1.04f else 1f, 
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
                        label = "lScale"
                    )
                    val offsetY by animateFloatAsState(
                        targetValue = when {
                            isStatic -> 0f
                            isActive -> 0f
                            activeIndex < 0 -> 0f
                            index > activeIndex -> 10f
                            else -> -10f
                        },
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
                        label = "lOffsetY"
                    )

                    val isInstrumental = line.annotationType == LyricAnnotationType.EXPLICIT_INSTRUMENTAL ||
                            (isActive && presState is LyricPresentationState.Instrumental && index == activeIndex)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                this.alpha = alpha
                                this.scaleX = scale
                                this.scaleY = scale
                                this.translationY = offsetY
                                this.transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .clickable { onSeek(line.timestampMs) },
                        horizontalAlignment = Alignment.Start
                    ) {
                        if (isInstrumental) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "♪",
                                    fontSize = if (isActive) 24.sp else 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.35f)
                                )
                                Spacer(Modifier.width(14.dp))
                                InstrumentalBreakDots(
                                    isActive = isActive,
                                    color = if (isActive) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.35f),
                                    dotSize = if (isActive) 6.dp else 5.dp
                                )
                            }
                        } else {
                            val originalText = line.originalLyricText()

                            // Secondary Line Slot (Translation or Romanization) - ON TOP
                            val rawSecondary = when (displayMode) {
                                LyricDisplayMode.TRANSLATED -> line.translation?.trim()
                                LyricDisplayMode.ROMANIZED -> line.resolvedRomanizedText()
                                else -> null
                            }
                            val secondaryText = sanitizeLyricText(rawSecondary)

                            if (!secondaryText.isNullOrBlank()) {
                                Text(
                                    text = secondaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isActive) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }

                            if (isActive && line.tokens.isNotEmpty()) {
                                val annotatedText = buildLyricAnnotatedString(
                                    line = line,
                                    currentPositionMs = smoothPositionState.value,
                                    activeColor = Color.White,
                                    inactiveColor = Color.White.copy(alpha = 0.45f),
                                    displayMode = displayMode
                                )
                                Text(
                                    text = annotatedText,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
                                    textAlign = TextAlign.Start,
                                    lineHeight = 30.sp
                                )
                            } else {
                                Text(
                                    text = originalText,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = if (isActive) 22.sp else 19.sp,
                                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold
                                    ),
                                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.35f),
                                    textAlign = TextAlign.Start,
                                    lineHeight = 28.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isCompactForLandscape) {
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

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
@androidx.media3.common.util.UnstableApi
internal fun LyricsPreviewSection(
    lyrics: LyricsResult,
    positionState: State<Long>,
    displayMode: LyricDisplayMode,
    isPlaying: Boolean,
    playbackSpeed: Float,
    viewModel: PlaybackViewModel,
    onToggleRomanization: (Boolean) -> Unit,
    onEnterLyricsMode: () -> Unit
) {
    val presState by viewModel.presentationState.collectAsStateWithLifecycle()
    
    val smoothPositionState = rememberSmoothPosition(
        realPosition = positionState.value,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed
    )
    
    val currentLyrics by remember(presState, lyrics.lines) {
        derivedStateOf {
            val lines = lyrics.lines
            if (lines.isEmpty()) return@derivedStateOf emptyList()
            
            // [V24]: Static Preview Support - Do not show in sync-based preview
            val isStatic = lines.all { it.timestampMs <= lines.size.toLong() }
            if (isStatic) return@derivedStateOf emptyList()

            when (val s = presState) {
                is LyricPresentationState.Vocal -> {
                    if (s.lineIndex >= 0 && s.lineIndex < lines.size) listOf(lines[s.lineIndex])
                    else emptyList()
                }
                is LyricPresentationState.SectionLabel -> {
                    if (s.lineIndex >= 0 && s.lineIndex < lines.size) listOf(lines[s.lineIndex])
                    else emptyList()
                }
                is LyricPresentationState.Instrumental -> {
                    listOf(LyricLine(timestampMs = s.startMs, text = "♪", annotationType = LyricAnnotationType.EXPLICIT_INSTRUMENTAL))
                }
                else -> emptyList()
            }
        }
    }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { },
                onDoubleClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleRomanization(displayMode != LyricDisplayMode.ROMANIZED)
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnterLyricsMode()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = currentLyrics to displayMode,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "lyricSeamlessModeSwitch"
        ) { (lyr, mode) ->
            if (lyr.isNotEmpty()) {
                val line = lyr.first()
                if (line.annotationType == LyricAnnotationType.EXPLICIT_INSTRUMENTAL) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "♪",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64B5F6)
                        )
                        Spacer(Modifier.width(12.dp))
                        InstrumentalBreakDots(
                            isActive = true,
                            color = Color(0xFF64B5F6),
                            dotSize = 5.dp
                        )
                    }
                } else {
                    val rawSecondary = when (mode) {
                        LyricDisplayMode.TRANSLATED -> line.translation?.trim()
                        LyricDisplayMode.ROMANIZED -> line.resolvedRomanizedText()
                        else -> null
                    }
                    val secondaryText = sanitizeLyricText(rawSecondary)

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!secondaryText.isNullOrBlank()) {
                            // Enhancement exists: show ONLY the enhancement in primary style
                            Text(
                                text = secondaryText,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            // No enhancement: show original
                            val annotatedText = buildLyricAnnotatedString(
                                line = line,
                                currentPositionMs = smoothPositionState.value,
                                activeColor = Color.White,
                                inactiveColor = Color.White.copy(alpha = 0.5f),
                                displayMode = mode
                            )
                            Text(
                                text = annotatedText,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

internal fun buildLyricAnnotatedString(
    line: LyricLine,
    currentPositionMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    displayMode: LyricDisplayMode
): AnnotatedString {
    if (displayMode == LyricDisplayMode.ROMANIZED) {
        // Jalur 1: karaoke akurat, timestamp diwarisi dari token asli.
        if (line.tokens.any { it.romanized != null || it.isMorphemeInterior }) {
            val built = buildAnnotatedString {
                line.tokens.forEachIndexed { index, token ->
                    val piece = token.romanized ?: if (token.isMorphemeInterior) null else token.text
                    if (piece == null) return@forEachIndexed
                    
                    val isActive = currentPositionMs >= token.timestampMs
                    withStyle(
                        style = SpanStyle(
                            color = if (isActive) activeColor else inactiveColor
                        )
                    ) {
                        append(piece)
                        if (index < line.tokens.lastIndex && !token.isMorphemeInterior) append(" ")
                    }
                }
            }
            if (built.isNotBlank()) return built
        }

        // Jalur 2: fallback baris utuh (LRC biasa atau format "asli|romaji").
        // Kualitas bacaan lebih baik karena punya konteks penuh, tapi tanpa karaoke.
        val romanized = line.resolvedRomanizedText()
        if (!romanized.isNullOrBlank()) {
            return AnnotatedString(romanized)
        }
    }

    if (line.tokens.isNotEmpty()) {
        return buildAnnotatedString {
            line.tokens.forEach { token ->
                val isActive = currentPositionMs >= token.timestampMs
                withStyle(style = SpanStyle(color = if (isActive) activeColor else inactiveColor)) {
                    append(token.text)
                }
            }
        }
    }

    return AnnotatedString(line.originalLyricText())
}

@Composable
internal fun InstrumentalBreakDots(
    isActive: Boolean,
    color: Color,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "instrumentalDots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(3) { index ->
            val alphaState = if (isActive) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha"
                )
            } else {
                null
            }
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { alpha = alphaState?.value ?: 0.35f }
                    .background(color, CircleShape)
            )
        }
    }
}

internal fun LyricLine.originalLyricText(): String {
    val cleanText = text.replace("\uE000", "").trim()
    return cleanText.substringBefore("|").trim()
}

internal fun LyricLine.resolvedRomanizedText(): String? {
    val explicitRomanized = romanizedText?.trim()
    if (!explicitRomanized.isNullOrBlank()) return explicitRomanized

    return if (text.contains("|")) {
        text.substringAfter("|").trim().takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/**
 * [V16]: Membersihkan teks lirik dari tag bocor ([...], <...>) dan simbol formatting (" , ').
 */
internal fun sanitizeLyricText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return text
        .replace(Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]"), "") // Strip [00:00.00]
        .replace(Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>"), "")   // Strip <00:00.00>
        .replace("\uE000", "")                                       // Strip secret mark
        .replace("\"", "")                                           // Strip "
        .replace("'", "")                                            // Strip '
        .replace(Regex("\\s+"), " ")                                 // Clean double spaces
        .trim()
}

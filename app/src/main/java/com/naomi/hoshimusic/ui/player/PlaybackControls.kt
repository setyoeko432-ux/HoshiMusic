@file:androidx.media3.common.util.UnstableApi

package com.naomi.hoshimusic.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.naomi.hoshimusic.ui.AnimatedPlayPauseIcon
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.rememberSmoothPosition
import com.naomi.hoshimusic.util.formatDuration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SeekSection(
    viewModel: PlaybackViewModel,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    val durationFlow = remember(viewModel) { viewModel.uiState.map { it.durationMs }.distinctUntilChanged() }
    val durationMs by durationFlow.collectAsStateWithLifecycle(0L)
    val rawPosition = viewModel.positionMsFlow.collectAsStateWithLifecycle()
    val playbackSpeedFlow = remember(viewModel) { viewModel.uiState.map { it.playbackSpeed }.distinctUntilChanged() }
    val playbackSpeed by playbackSpeedFlow.collectAsStateWithLifecycle(1f)
    
    val position by rememberSmoothPosition(rawPosition, isPlaying, playbackSpeed)
    
    var draggedPosition by remember { mutableFloatStateOf(-1f) }

    // PERFORMANCE FIX: State Deferral Lambda to prevent parent recomposition
    val sliderValueProvider = remember(position, draggedPosition) {
        { (if (draggedPosition >= 0f) draggedPosition else position.toFloat()).coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()) }
    }

    val visiblePosition = if (draggedPosition >= 0f) draggedPosition.toLong() else position

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Slider(
            value = sliderValueProvider(),
            onValueChange = { draggedPosition = it },
            onValueChangeFinished = {
                if (draggedPosition >= 0f) onSeek(draggedPosition.toLong())
                draggedPosition = -1f
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
        )
        Row(Modifier.fillMaxWidth()) {
            Text(text = formatDuration(visiblePosition), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(text = formatDuration(durationMs), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleShuffle) { Icon(Icons.Rounded.Shuffle, null, tint = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.3f)) }
            IconButton(onClick = onSkipPrevious) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            PressPlayButton(isPlaying, onTogglePlayback, accentColor = Color.White)
            IconButton(onClick = onSkipNext) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onCycleRepeat) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.White else Color.White.copy(alpha = 0.3f)) }
        }
    }
}

@Composable
internal fun PressPlayButton(isPlaying: Boolean, onClick: () -> Unit, compact: Boolean = false, accentColor: Color = Color.White) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "playButtonPress")
    val size = if (compact) 40.dp else 72.dp
    val iconSize = if (compact) 24.dp else 38.dp
    Surface(shape = CircleShape, color = accentColor, contentColor = Color.Black, modifier = Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { AnimatedPlayPauseIcon(isPlaying, iconSize, Color.Black) }
    }
}

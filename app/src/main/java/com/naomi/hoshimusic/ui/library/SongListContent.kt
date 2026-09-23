package com.naomi.hoshimusic.ui.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.EmptyState

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun SongList(
    songs: List<Song>,
    favoriteIds: Set<String>,
    currentMediaId: String?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    onPlaySongs: (List<Song>, Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenActions: (Song) -> Unit,
    emptyTitle: String,
    emptyBody: String,
) {
    if (songs.isEmpty()) {
        EmptyState(Icons.Rounded.MusicNote, emptyTitle, emptyBody)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        items(songs, key = { "s_${it.id}_${it.stableId}" }, contentType = { "song" }) { song ->
            SongRow(
                song = song,
                isFavorite = song.stableId in favoriteIds,
                isPlaying = song.stableId == currentMediaId,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onClick = { onPlaySongs(songs, songs.indexOf(song)) },
                onLongPress = { onOpenActions(song) },
                onToggleFavorite = { onToggleFavorite(song.stableId) },
                onSwipeQueue = { onAddToQueue(song) },
                onAction = { onOpenActions(song) },
            )
        }
    }
}

package com.naomi.hoshimusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork

@Composable
internal fun QueueListContent(entries: List<Pair<Int, Song>>, currentIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(32.dp))
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(entries, key = { (index, song) -> "$index-${song.stableId}" }) { (index, song) ->
                QueueRow(song = song, isCurrent = index == currentIndex, onSelect = { onSelect(index) })
            }
        }
    }
}

@Composable
internal fun QueueSheet(entries: List<Pair<Int, Song>>, currentIndex: Int, onSelect: (Int) -> Unit) {
    QueueListContent(
        entries = entries,
        currentIndex = currentIndex,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 800.dp).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    )
}

@Composable
internal fun QueueRow(song: Song, isCurrent: Boolean, onSelect: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).background(if (isCurrent) Color.White.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artworkUri, 56.dp, 14.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1)
        }
        if (isCurrent) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}

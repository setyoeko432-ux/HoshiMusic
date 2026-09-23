package com.naomi.hoshimusic.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.PlayStat
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.Artwork
import com.naomi.hoshimusic.util.formatDuration

@Composable
internal fun StatsScreen(
    songs: List<Song>,
    stats: Map<String, PlayStat>,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val byId = remember(songs) { songs.associateBy(Song::stableId) }
    val ranked = remember(stats, songs) {
        stats.entries.mapNotNull { (id, stat) -> byId[id]?.let { it to stat } }
            .filter { it.second.playCount > 0 }
            .sortedByDescending { it.second.playCount }
    }
    val totalPlays = ranked.sumOf { it.second.playCount }
    val listeningMs = ranked.sumOf { (song, stat) -> song.durationMs * stat.playCount.toLong() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Total putar", totalPlays.toString(), Icons.Rounded.BarChart, Modifier.weight(1f))
                MetricCard("Waktu", formatDuration(listeningMs), Icons.Rounded.Schedule, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("Paling sering diputar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        if (ranked.isEmpty()) item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text("Statistik akan muncul setelah kamu memutar beberapa lagu.", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
        itemsIndexed(ranked.take(30), key = { _, item -> item.first.stableId }, contentType = { _, _ -> "stat" }) { index, item ->
            val (song, stat) = item
            Row(
                Modifier.fillMaxWidth().clickable { onPlaySongs(listOf(song), 0) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((index + 1).toString(), Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black)
                Artwork(song.artworkUri, 48.dp, 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("${stat.playCount}×", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp)) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

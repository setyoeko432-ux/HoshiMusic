package com.naomi.hoshimusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.components.RhythmHeroCard
import com.naomi.hoshimusic.util.formatDuration

@Composable
internal fun HomeContent(
    libraryState: LibraryUiState,
    onSearchClick: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenLibrary: (() -> Unit)? = null,
) {
    val songsById by remember(libraryState.songs) {
        derivedStateOf { libraryState.songs.associateBy { it.stableId } }
    }

    val recentlyPlayed by remember(libraryState.playStats) {
        derivedStateOf {
            libraryState.playStats.entries
                .filter { it.value.lastPlayedAtMs > 0 }
                .sortedByDescending { it.value.lastPlayedAtMs }
                .take(10)
                .mapNotNull { songsById[it.key] }
        }
    }

    val frequentlyPlayed by remember(libraryState.playStats) {
        derivedStateOf {
            libraryState.playStats.entries
                .filter { it.value.playCount > 0 }
                .sortedByDescending { it.value.playCount }
                .take(5)
                .mapNotNull { songsById[it.key] }
        }
    }

    val carouselSongs by remember(libraryState.songs) {
        derivedStateOf {
            if (libraryState.songs.isEmpty()) emptyList()
            else {
                libraryState.songs
                    .groupBy { it.album.ifBlank { it.title } }
                    .map { it.value.first() }
                    .take(10)
            }
        }
    }

    val recentlyAdded by remember(libraryState.songs) {
        derivedStateOf {
            libraryState.songs
                .sortedByDescending { it.dateAddedMs }
                .take(10)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1) HERO CAROUSEL — UNCHANGED
        if (carouselSongs.isNotEmpty()) {
            item {
                val pagerState = rememberPagerState(pageCount = { carouselSongs.size })

                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        pageSpacing = 8.dp,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        val song = carouselSongs[page]
                        RhythmHeroCard(
                            song = song,
                            onPlayClick = { onPlaySongs(listOf(song), 0) },
                            onClick = { onPlaySongs(listOf(song), 0) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(carouselSongs.size) { iteration ->
                            val isCurrent = pagerState.currentPage == iteration
                            val dotWidth: Dp by animateDpAsState(
                                targetValue = if (isCurrent) 16.dp else 6.dp,
                                label = "heroDotWidth"
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .height(6.dp)
                                    .width(dotWidth)
                                    .background(
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }

        // 2) BARU DITAMBAHKAN (newest songs by dateAddedMs)
        item {
            HomeSectionHeader(
                title = "Baru Ditambahkan",
                onSeeAll = onOpenLibrary
            ) {
                if (recentlyAdded.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(recentlyAdded, key = { "ra_${it.id}_${it.stableId}" }) { song ->
                            RecentlyPlayedCard(
                                song = song,
                                onClick = { onPlaySongs(listOf(song), 0) }
                            )
                        }
                    }
                } else {
                    HomeSectionEmptyHint(text = "Belum ada lagu yang ditambahkan")
                }
            }
        }

        // 3) RECENTLY PLAYED
        item {
            HomeSectionHeader(
                title = "Baru Saja Diputar",
                onSeeAll = onOpenLibrary
            ) {
                if (recentlyPlayed.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(recentlyPlayed, key = { "rp_${it.id}_${it.stableId}" }) { song ->
                            RecentlyPlayedCard(
                                song = song,
                                onClick = { onPlaySongs(listOf(song), 0) }
                            )
                        }
                    }
                } else {
                    HomeSectionEmptyHint(text = "Putar lagu untuk melihat riwayat")
                }
            }
        }

        // 4) FREQUENTLY PLAYED
        item {
            HomeSectionHeader(
                title = "Sering Diputar",
                onSeeAll = onOpenLibrary
            ) {
                if (frequentlyPlayed.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequentlyPlayed.forEach { song ->
                            FrequentlyPlayedRow(
                                song = song,
                                isFavorite = song.stableId in libraryState.favoriteIds,
                                onClick = { onPlaySongs(listOf(song), 0) },
                                onToggleFavorite = { onToggleFavorite(song.stableId) }
                            )
                        }
                    }
                } else {
                    HomeSectionEmptyHint(text = "Belum ada lagu yang sering diputar")
                }
            }
        }

        // 5) YOUR PLAYLISTS
        item {
            HomeSectionHeader(
                title = "Playlist Kamu",
                onSeeAll = onOpenLibrary
            ) {
                if (libraryState.playlists.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(libraryState.playlists, key = { it.id }) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                songsById = songsById,
                                onClick = { onOpenPlaylist(playlist.id) }
                            )
                        }
                    }
                } else {
                    HomeSectionEmptyHint(text = "Buat playlist di tab Koleksi")
                }
            }
        }
    }
}

// ============================================================================
// SECTION HEADER — bold title (15.sp) + small gray "See all" on the right
// ============================================================================
@Composable
private fun HomeSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onSeeAll != null) {
                Text(
                    text = "Lihat semua",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onSeeAll)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ============================================================================
// RECENTLY PLAYED CARD — 112.dp cover, 16.dp corner, title + artist
// ============================================================================
@Composable
private fun RecentlyPlayedCard(
    song: Song,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clickable(onClick = onClick)
    ) {
        Artwork(song.artworkUri, 112.dp, 16.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
// FREQUENTLY PLAYED ROW — 46.dp artwork, title, artist, heart, duration
// ============================================================================
@Composable
private fun FrequentlyPlayedRow(
    song: Song,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(song.artworkUri, 46.dp, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Hapus dari favorit" else "Tambah ke favorit",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = formatDuration(song.durationMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp)
            )
        }
    }
}

// ============================================================================
// PLAYLIST CARD — 150.dp wide, surface + border, 20.dp radius, 84.dp cover
// ============================================================================
@Composable
private fun PlaylistCard(
    playlist: LocalPlaylist,
    songsById: Map<String, Song>,
    onClick: () -> Unit,
) {
    val firstSongArtwork = remember(playlist, songsById) {
        playlist.songIds.firstOrNull()?.let { songsById[it]?.artworkUri }
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(150.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (firstSongArtwork != null) {
                    Artwork(firstSongArtwork, size = 84.dp, cornerRadius = 14.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = playlist.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.songIds.size} lagu",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// SECTION EMPTY HINT — small gray one-liner when a section has no data yet
// ============================================================================
@Composable
private fun HomeSectionEmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}


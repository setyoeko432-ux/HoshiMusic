@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.naomi.hoshimusic.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import com.naomi.hoshimusic.ui.library.SongList
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.Song

@Composable
internal fun SearchContent(
    query: String,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    filteredSongs: List<Song>,
    favoriteIds: Set<String>,
    currentMediaId: String?,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenActions: (Song) -> Unit,
) {
    val categoryAccent = MaterialTheme.colorScheme.primary
    val categories = remember(categoryAccent) {
        listOf(
            SearchCategory("Pop", categoryAccent),
            SearchCategory("Rock", categoryAccent),
            SearchCategory("Jazz", categoryAccent),
            SearchCategory("Terbaru", categoryAccent),
            SearchCategory("Lokal", categoryAccent),
            SearchCategory("Santai", categoryAccent)
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(100.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Kembali",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            MotionIconButton(
                                onClick = { onQueryChange("") },
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Hapus",
                                modifier = Modifier.size(44.dp),
                                iconSize = 20.dp,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    placeholder = {
                        Text(
                            "Cari lagu, artis, atau album",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

    if (query.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Text(
                        text = "JELAJAHI KATEGORI",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        letterSpacing = 1.5.sp
                    )
                }
                item {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height(320.dp).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled = false
                    ) {
                        items(categories) { category ->
                            CategoryCard(category) { onQueryChange(category.name) }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PENCARIAN TERAKHIR",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            letterSpacing = 1.5.sp
                        )
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Belum ada riwayat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        } else {
            SongList(
                songs = filteredSongs,
                favoriteIds = favoriteIds,
                currentMediaId = currentMediaId,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onPlaySongs = onPlaySongs,
                onToggleFavorite = onToggleFavorite,
                onAddToQueue = onAddToQueue,
                onOpenActions = onOpenActions,
                emptyTitle = "Tidak ditemukan",
                emptyBody = "Coba kata kunci lain untuk hasil lebih baik."
            )
        }
    }
}

private data class SearchCategory(val name: String, val color: Color)

@Composable
private fun CategoryCard(category: SearchCategory, onClick: () -> Unit) {
    val density = LocalDensity.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .bounceClick(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    ) {
        Box(Modifier.padding(16.dp)) {
            Text(
                text = category.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 0.5.sp
            )
            Icon(
                Icons.Rounded.GraphicEq,
                null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        translationX = with(density) { 32.dp.toPx() }
                        translationY = with(density) { 32.dp.toPx() }
                    }
            )
        }
    }
}

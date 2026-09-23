package com.naomi.hoshimusic.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.LyricLine
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.ui.LibraryUiState
import com.naomi.hoshimusic.ui.PlaybackViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationWorkbenchScreen(
    libraryState: LibraryUiState,
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit
) {
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    var lyricsLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var userCorrections by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(selectedSong) {
        selectedSong?.let { song ->
            val result = playbackViewModel.lyricsRepository.load(song, useTranslation = true)
            lyricsLines = result.lines
            val overrides = playbackViewModel.lyricsRepository.getTranslationOverrides(song.stableId)
            userCorrections = overrides.associate { it.timestampMs to it.correctedText }
        }
    }

    if (isLandscape) {
        // Landscape View: side-by-side 0.4 / 0.6
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.4f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                CollectionList(libraryState.songs, selectedSong) { selectedSong = it }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(0.6f).fillMaxHeight()) {
                EditorGrid(selectedSong, lyricsLines, userCorrections, playbackViewModel) { userCorrections = it }
            }
        }
    } else {
        // Portrait View: Vertical Stack
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.4f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                CollectionList(libraryState.songs, selectedSong) { selectedSong = it }
            }
            Box(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(0.6f).fillMaxWidth()) {
                EditorGrid(selectedSong, lyricsLines, userCorrections, playbackViewModel) { userCorrections = it }
            }
        }
    }
}

@Composable
private fun CollectionList(songs: List<Song>, selected: Song?, onSelect: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(songs, key = { "tw_${it.id}_${it.stableId}" }) { song ->
            val isSelected = selected?.stableId == song.stableId
            ListItem(
                headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { onSelect(song) },
                colors = ListItemDefaults.colors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.media3.common.util.UnstableApi
@Composable
private fun EditorGrid(
    selectedSong: Song?,
    lyricsLines: List<LyricLine>,
    userCorrections: Map<Long, String>,
    playbackViewModel: PlaybackViewModel,
    onCorrectionsUpdate: (Map<Long, String>) -> Unit
) {
    if (selectedSong == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Pilih lagu dari koleksi untuk mulai mengedit", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Editor: ${selectedSong.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                val clipboard = LocalClipboardManager.current
                OutlinedIconButton(onClick = {
                    val prompt = buildString {
                        append("Translate these lyrics into Indonesian.\n")
                        append("IMPORTANT: \n")
                        append("- DO NOT change the timestamps inside brackets [ ]\n")
                        append("- DO NOT CHANGE THE MEANING OF THESE LYRICS.\n")
                        append("Reply ONLY using the format: [timestamp] Translation\n\n")
                        lyricsLines.forEach { line ->
                            if (line.text != "♪") {
                                val ts = formatTimestampCorrection(line.timestampMs)
                                append("$ts ${line.text}\n")
                            }
                        }
                    }
                    clipboard.setText(AnnotatedString(prompt))
                }) {
                    Icon(Icons.Rounded.ContentCopy, "Salin Prompt AI")
                }

                Spacer(Modifier.width(8.dp))
                
                var showPasteDialog by remember { mutableStateOf(false) }
                Button(onClick = { showPasteDialog = true }) {
                    Icon(Icons.Rounded.ContentPaste, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tempel AI")
                }
                
                            if (showPasteDialog) {
                                var pasteText by remember { mutableStateOf("") }
                                val clipboard = LocalClipboardManager.current
                                AlertDialog(
                                    onDismissRequest = { showPasteDialog = false },
                                    title = { Text("Tempel dari AI") },
                                    text = {
                                        Column {
                                            OutlinedTextField(
                                                value = pasteText,
                                                onValueChange = { pasteText = it },
                                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                                placeholder = { Text("Klik tombol di bawah untuk tempel instan...") }
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    clipboard.getText()?.let { pasteText = it.text }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                            ) {
                                                Icon(Icons.Rounded.ContentPaste, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Paste dari Clipboard")
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val text = pasteText
                                            var newMap = userCorrections
                                            
                                            // [V17]: Multi-segment parser using findAll
                                            val tsRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.,:](\\d{1,3}))?\\]")
                                            
                                            val tsMatches = tsRegex.findAll(text).toList()
                                            
                                            if (tsMatches.isNotEmpty()) {
                                                // Handle standard [mm:ss.ss] format with potential multiple segments per line
                                                tsMatches.forEachIndexed { i, match ->
                                                    val m = match.groupValues[1]
                                                    val s = match.groupValues[2]
                                                    val f = match.groupValues[3]
                                                    
                                                    val start = match.range.last + 1
                                                    val end = if (i + 1 < tsMatches.size) tsMatches[i + 1].range.first else text.length
                                                    val segmentText = text.substring(start, end).trim()
                                                    
                                                    val tsMs = com.naomi.hoshimusic.data.lrc.LrcTimestampParser.parseTimestamp(m, s, f)
                                                    val anchor = lyricsLines.minByOrNull { Math.abs(it.timestampMs - tsMs) }
                                                    
                                                    if (anchor != null && segmentText.isNotEmpty()) {
                                                        playbackViewModel.saveTranslationOverride(selectedSong.stableId, anchor.timestampMs, segmentText)
                                                        newMap = newMap + (anchor.timestampMs to segmentText)
                                                    }
                                                }
                                            } else {
                                                // Fallback to line-by-line ID-based parsing if no timestamps found
                                                text.split("\n").forEach { l ->
                                                    val match = Regex("(?:\\[)?(\\d+)(?:\\])?[:\\s-]+(.*)").find(l)
                                                    if (match != null) {
                                                        val ts = match.groupValues[1].toLongOrNull() ?: return@forEach
                                                        val txt = match.groupValues[2].trim()
                                                        if (txt.isNotEmpty()) {
                                                            playbackViewModel.saveTranslationOverride(selectedSong.stableId, ts, txt)
                                                            newMap = newMap + (ts to txt)
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            onCorrectionsUpdate(newMap)
                                            showPasteDialog = false
                                        }) { Text("Proses") }
                                    }
                                )
                            }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Lirik (Parser)", modifier = Modifier.weight(0.45f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Koreksi Baru", modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(lyricsLines) { line ->
                    if (line.text == "♪") return@items
                    
                    val timestampStr = formatTimestampCorrection(line.timestampMs)
                    var currentText by remember(line.timestampMs, userCorrections) {
                        mutableStateOf(userCorrections[line.timestampMs] ?: "")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$timestampStr ${line.text}",
                            modifier = Modifier.weight(0.45f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = currentText,
                            onValueChange = { currentText = it },
                            modifier = Modifier.weight(0.55f),
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("Koreksi Terjemahan", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                playbackViewModel.saveTranslationOverride(selectedSong.stableId, line.timestampMs, currentText)
                                onCorrectionsUpdate(userCorrections + (line.timestampMs to currentText))
                            })
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f))
                }
            }
        }
    }
}

private fun formatTimestampCorrection(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000) / 10
    return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, millis)
}

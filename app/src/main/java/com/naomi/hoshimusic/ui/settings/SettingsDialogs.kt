package com.naomi.hoshimusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import com.naomi.hoshimusic.playback.dsp.DspPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun SpatialAudioDialog(currentPreset: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text("Spatial Audio (Earphone)", fontWeight = FontWeight.Bold) }, text = {
            Column {
                SpatialOption(title = "Mode Standar (Binaural)", description = "Penyelarasan fase alami untuk imaging tengah yang akurat.", selected = currentPreset == 0, onClick = { onSelect(0) })
                Spacer(Modifier.height(12.dp))
                SpatialOption(title = "Mode Lebar (Stage)", description = "Ekspansi stereo maksimal — panggung suara luar kepala.", selected = currentPreset == 1, onClick = { onSelect(1) })
                Spacer(Modifier.height(12.dp))
                SpatialOption(title = "Mode Studio (Close)", description = "Karakter ruang studio yang intim dan terkontrol.", selected = currentPreset == 2, onClick = { onSelect(2) })
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Color.White) } })
}

@Composable
private fun SpatialOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton), shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PeqProfileDialog(
    profiles: List<PeqProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (List<PeqBand>, Float, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                    val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
                    val result = text?.let(DspPresets::parseAutoEq)
                    val bands = result?.first.orEmpty()
                    val preamp = result?.second ?: 0f
                    if (bands.isEmpty()) null else Triple(bands, preamp, queryDisplayName(context, uri))
                }.getOrNull()
            }
            when {
                outcome == null -> importError = "File tidak dapat dibaca / tidak ada band PEQ valid."
                else -> onImport(outcome.first, outcome.second, outcome.third)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Koreksi Headphone (AutoEq)", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                if (profiles.isEmpty()) {
                    item {
                        Text("Belum ada profil. Impor hasil AutoEq (.txt) untuk headphone Anda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(profiles, key = { it.id }) { profile ->
                    DspPresetRow(
                        title = profile.name,
                        subtitle = "${profile.bands.size} band PEQ",
                        selected = activeId == profile.id,
                        onClick = { onSelect(profile.id) },
                        onDelete = { onDelete(profile.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    importError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = { importLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Impor file .txt")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color.White) } }
    )
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull()?.removeSuffix(".txt")?.ifBlank { null } ?: "Profil PEQ"

@Composable
internal fun BtGuardModeDialog(currentMode: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text("BT Clarity Guard", fontWeight = FontWeight.Bold) }, text = {
            Column {
                BtGuardOption(title = "Auto (Disarankan)", description = "Aktif otomatis saat codec SBC/generik terdeteksi.", selected = currentMode == 0, onClick = { onSelect(0) })
                Spacer(Modifier.height(12.dp))
                BtGuardOption(title = "Selalu Aktif saat BT", description = "De-mud aktif untuk semua perangkat bluetooth.", selected = currentMode == 1, onClick = { onSelect(1) })
                Spacer(Modifier.height(12.dp))
                BtGuardOption(title = "Mati", description = "Tanpa pembersihan otomatis.", selected = currentMode == 2, onClick = { onSelect(2) })
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Color.White) } })
}

@Composable
private fun BtGuardOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton), shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun LyricPriorityDialog(currentPriority: LyricSourcePriority, onSelect: (LyricSourcePriority) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text("Prioritas Sumber Lirik", fontWeight = FontWeight.Bold) }, text = {
            Column {
                LyricPriorityOption(title = "File LRC Eksternal Pertama", description = "Mencari file .lrc di folder lagu.", selected = currentPriority == LyricSourcePriority.EXTERNAL_FIRST, onClick = { onSelect(LyricSourcePriority.EXTERNAL_FIRST) })
                Spacer(Modifier.height(12.dp))
                LyricPriorityOption(title = "Metadata Internal Pertama", description = "Menggunakan lirik yang tertanam di file.", selected = currentPriority == LyricSourcePriority.METADATA_FIRST, onClick = { onSelect(LyricSourcePriority.METADATA_FIRST) })
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Color.White) } })
}

@Composable
private fun LyricPriorityOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton), shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun DspPresetDialog(
    selectedId: String,
    customs: List<CustomDspPreset>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreateCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text("Preset DSP", fontWeight = FontWeight.Bold) }, text = {
        LazyColumn {
            item {
                Text("Bawaan", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            items(DspPresets.BUILTINS, key = { it.id }) { preset ->
                DspPresetRow(title = preset.title, subtitle = preset.subtitle, selected = selectedId == preset.id, onClick = { onSelect(preset.id) })
                Spacer(Modifier.height(8.dp))
            }
            if (customs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Kustom", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                items(customs, key = { it.id }) { preset ->
                    DspPresetRow(title = preset.title, subtitle = "Preset buatan sendiri", selected = selectedId == preset.id, onClick = { onSelect(preset.id) }, onDelete = { onDelete(preset.id) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onCreateCustom, modifier = Modifier.fillMaxWidth()) { Text("+ Buat Preset Kustom") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color.White) } })
}

@Composable
internal fun DspPresetRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton), shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Hapus preset", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun DspCustomPresetEditorDialog(onSave: (String, List<Float>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var bass by remember { mutableStateOf(1f) }
    var vocal by remember { mutableStateOf(1f) }
    var treble by remember { mutableStateOf(1f) }
    var warmth by remember { mutableStateOf(1f) }
    var width by remember { mutableStateOf(1f) }
    var punchAttack by remember { mutableStateOf(1f) }
    var body by remember { mutableStateOf(1f) }
    var saturate by remember { mutableStateOf(1f) }

    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text("Buat Preset Kustom", fontWeight = FontWeight.Bold) }, text = {
        LazyColumn {
            item {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 24) name = it }, label = { Text("Nama Preset") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            item { DspSliderRow("Bass", bass) { bass = it } }
            item { DspSliderRow("Vokal", vocal) { vocal = it } }
            item { DspSliderRow("Treble", treble) { treble = it } }
            item { DspSliderRow("Lebar Suara", width) { width = it } }
            item { DspSliderRow("Punch", punchAttack) { punchAttack = it } }
            item { DspSliderRow("Body (Sustain)", body) { body = it } }
            item { DspSliderRow("Saturasi", saturate) { saturate = it } }
        }
    },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val params = listOf(
                    bass, vocal, treble, warmth,
                    treble * 0.8f + 0.2f,
                    1f, width,
                    if (treble > 1f) 0.6f + 0.6f * treble else 1f,
                    1f, 1f, 1f, 1f,
                    punchAttack, body, saturate
                )
                onSave(name.trim(), params)
            }) { Text("Simpan", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Color.White) } })
}

@Composable
internal fun PatchNotesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Catatan Perubahan (Patch Notes)", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                item {
                    PatchVersionSection(
                        version = "1.4.7",
                        notes = listOf(
                            "DSP Numerical Stability: -ffast-math dihapus dari flag NDK global. Filter IIR/biquad TptSvf dan DspEngine::processFrame() sensitif terhadap reassociation floating-point dan denormal handling, sehingga fast-math kini hanya diterapkan selektif per-fungsi yang aman.",
                            "AI Audio Analysis: Tabel baru song_ai_tags menyimpan genre prediksi beserta confidence, BPM, nada dasar, versi model, status analisis, dan penanda offline. Database naik ke versi 13 dengan migrasi 12-13.",
                            "Lyrics Double-Tap Cycling: Ketukan ganda kini memutar ORIGINAL <-> ROMANIZED atau ORIGINAL <-> TRANSLATED sesuai mode aktif, menggantikan toggle boolean dua arah.",
                            "Low-Latency Audio: Setelan baru untuk latensi audio rendah, tersebar dari Settings sampai ViewModel.",
                            "Audio Sink Tracing: HoshiAudioSink mencatat transisi play/pause/flush/reset beserta ended, hasPendingData, dan bufferSizeUs agar terlihat mengapa audio berhenti mengalir.",
                            "Diagnostics Upgrade: DiagLog.w() kini menerima Throwable sebagai argumen ketiga.",
                            "Route Fallback Naming: routeFallback() menangani penamaan route Bluetooth dan wired yang sebelumnya ambigu.",
                            "Lyrical Vocal Bounds: refreshVocalBounds() memperbarui batas vokal untuk sinkronisasi highlight yang lebih rapat.",
                            "Fluid Motion: rememberSmoothPosition() untuk transisi posisi pemutar yang halus.",
                            "Engine Upgrade: media3 1.10.1 -> 1.11.1, Android Gradle Plugin 8.7.3 -> 9.3.0, Gradle 8.9 -> 9.6.0, Kotlin 2.0.21 -> 2.2.10, Room 2.6.1 -> 2.8.5.",
                            "Platform Alignment: compileSdk 35 -> 37 dan targetSdk 35 -> 36, membuka API Android 17 dan mengaktifkan perilaku runtime Android 16.",
                            "Compose Transition Fix: Shared element player kini memakai ResizeMode.scaleToBounds, menggantikan ScaleToBounds yang dihapus di Compose 1.12.1."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.6",
                        notes = listOf(
                            "DSP Pipeline Fix: Parameter ordering corrected — preset DSP params now applied before engine enable for accurate audio processing.",
                            "Spatial Audio: Replaced Crossfeed with 3-stage Spatial Audio (Standard/Stage/Close) adjusting stereo width and crossfeed.",
                            "Ambience Mode Functional: Now applies reverb-like modifier by adjusting active DSP preset parameters.",
                            "Car Mode Optimized: Removed non-driving settings (lyrics editing, detailed config); focused on DSP/Spatial/PEQ/Volume.",
                            "Audio Performance: Telemetry polling throttled to 500ms when idle; warmup priming optimized with isWarmupActive flag.",
                            "Technical Info Accuracy: Fixed hardcoded bit-depth assumptions; unified fallback logic across formatters.",
                            "Metadata Speed: Lazy probe chain — skips heavy MediaExtractor probes when TagEngine returns complete data; probes parallelized.",
                            "Lyrics Scanning: Active embedded/external lyrics detection during deep metadata scan; hasLyrics and lyricsSource fields added.",
                            "Album Fix: MediaMapper now preserves album_id from MediaItem extras; Next/Previous navigation stable.",
                            "Auto-Skip: Instrumental detection via lyrics presence with duration-based skip heuristics.",
                            "Boutique Bypass: Added FLAG_AUDIBILITY_ENFORCED; dynamic sample rate matching (min 48kHz, allows Hi-Res up to 192kHz).",
                            "Build Stability: Fixed EqualizerScreen compilation errors (exit confirm scope, TopAppBar subtitle, DspPresetRow import)."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.5",
                        notes = listOf(
                            "Boutique Cleanup: Removal of redundant 'Orphan' files and consolidated quality logic for 100% architectural purity.",
                            "Unified Quality Engine: Synchronized HI-RES and LOSSLESS detection across all UI components (Hero Cards & Song Rows).",
                            "Project Hygiene: Cleaned up duplicate 'LrcParseResult' definitions and empty legacy directories.",
                            "Stable Navigation: Refined back-navigation logic to prevent accidental app closure on sub-screens."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.4",
                        notes = listOf(
                            "Boutique Bypass Toggle: Switchable high-priority 'Game' routing to force-disable OEM effects (SKTune/Dolby).",
                            "Master Compatibility: Forced 48kHz resampling for non-FLAC formats (MP3/OGG) to ensure zero-silence on exclusive paths.",
                            "Engine Self-Awareness: Metadata & Parser versioning to automatically re-scan library when logic improves.",
                            "Linguistic Engine: Added support for Unsynchronized (Plain-Text) embedded lyrics with smart order preservation.",
                            "Search Harmony: Refreshed Search UI with consistent 100.dp top bar, modern pill shape, and bounce feedback.",
                            "Performance Hygiene: Fixed 'Duplicate Key' crashes and 'Ghost Tracks' resource leaks.",
                            "Fluid Motion: Added high-end slide-and-fade transitions for the Linguistic Workbench."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.3",
                        notes = listOf(
                            "Universal Compatibility: Robust software fallback for 16-bit and non-standard sample rates to ensure 100% playback reliability.",
                            "Hi-Res Float Path: Implementasi pipeline audio 32-bit Float murni untuk kejernihan suara maksimal.",
                            "Denormal Stall Prevention: Akselerasi hardware FTZ (Flush-to-Zero) dan penyeimbang epsilon untuk menghilangkan jitter pada audio rendah.",
                            "Magnetic Hybrid Sync: Perbaikan highlight lirik agar tetap 'menempel' pada suara vokal penyanyi secara natural.",
                            "Instant HD Artwork: Optimasi pre-caching untuk menghilangkan jeda kosong/hitam saat membuka layar pemutar."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.2",
                        notes = listOf(
                            "NDK Core Refactor: Penyelarasan memori 16KB (Android 15) dan akselerasi NEON SIMD untuk efisiensi CPU.",
                            "Atomic Control Flags: Penggunaan std::atomic untuk sinkronisasi thread-safe antara UI dan audio thread."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.4.0 - 1.4.1",
                        notes = listOf(
                            "Audio Stability: Perbaikan bug threading (deadlock) yang menyebabkan audio terhenti pada beberapa perangkat.",
                            "Filter Continuity: Implementasi smooth gain decay pada PEQ untuk mencegah bunyi 'klik' saat ganti preset."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.3.x Series",
                        notes = listOf(
                            "Pro Audio Suite: Implementasi 10-band Parametric EQ (AutoEq) dan layout Flagship Fader vertikal.",
                            "HyperOS Support: Kompatibilitas sistem database dan library native untuk perangkat Xiaomi/HyperOS.",
                            "Linguistic Engine: Penambahan fitur Romanisasi otomatis dan pembersihan karakter lirik internal."
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    PatchVersionSection(
                        version = "1.2.x Series",
                        notes = listOf(
                            "Linguistic Workbench: Editor terjemahan berbasis AI layar penuh dan dukungan tag SYLT binary.",
                            "Furigana-Style Stack: Perubahan visual dengan menempatkan Terjemahan/Romaji di atas lirik asli."
                        )
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color.White) } }
    )
}

@Composable
private fun PatchVersionSection(version: String, notes: List<String>) {
    Column {
        Text(
            text = "Versi $version",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        notes.forEach { note ->
            Text(
                text = "• $note",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
    }
}

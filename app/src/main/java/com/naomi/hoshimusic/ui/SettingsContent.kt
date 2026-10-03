@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.naomi.hoshimusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.BuildConfig
import com.naomi.hoshimusic.ui.PlaybackViewModel
import com.naomi.hoshimusic.ui.navigation.MainScreen
import com.naomi.hoshimusic.ui.settings.*
import java.util.*

@Composable
internal fun SettingsContent(
    hasBluetoothPermission: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    lyricPriority: LyricSourcePriority,
    onSetLyricPriority: (LyricSourcePriority) -> Unit,
    gaplessEnabled: Boolean,
    onToggleGapless: (Boolean) -> Unit,
    normalizationEnabled: Boolean,
    onToggleNormalization: (Boolean) -> Unit,
    dynamicColorEnabled: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    appTheme: Int,
    onSetAppTheme: (Int) -> Unit,
    romanizationEnabled: Boolean,
    onToggleRomanization: (Boolean) -> Unit,
    translationMode: Boolean,
    onToggleTranslationMode: (Boolean) -> Unit,
    selectedMusicFolder: String?,
    onSelectMusicFolder: () -> Unit,
    filterShortTracks: Boolean,
    onToggleFilterShortTracks: (Boolean) -> Unit,
    landscapeDualPaneEnabled: Boolean,
    onToggleLandscapeDualPane: (Boolean) -> Unit,
    dspEnabled: Boolean,
    onSetDspEnabled: (Boolean) -> Unit,
    dspPresetId: String,
    customDspPresets: List<CustomDspPreset>,
    onSelectDspPreset: (String) -> Unit,
    onSaveCustomDspPreset: (String, List<Float>) -> Unit,
    onDeleteCustomDspPreset: (String) -> Unit,
    btGuardMode: Int,
    onSelectBtGuardMode: (Int) -> Unit,
    ambienceEnabled: Boolean,
    onToggleAmbience: (Boolean) -> Unit,
    preampDb: Float,
    onSetPreampDb: (Float) -> Unit,
    spatialAudioEnabled: Boolean,
    onToggleSpatialAudio: (Boolean) -> Unit,
    spatialAudioPreset: Int,
    onSelectSpatialAudioPreset: (Int) -> Unit,
    peqProfiles: List<PeqProfile>,
    activePeqProfileId: String,
    onSelectPeqProfile: (String) -> Unit,
    onSavePeqProfile: (List<PeqBand>, Float, String) -> Unit,
    onDeletePeqProfile: (String) -> Unit,
    autoSkipIntroOutro: Boolean,
    onToggleAutoSkip: (Boolean) -> Unit,
    boutiqueBypassEnabled: Boolean,
    onToggleBoutiqueBypass: (Boolean) -> Unit,
    lowLatencyAudioEnabled: Boolean = false,
    onToggleLowLatencyAudio: (Boolean) -> Unit = {},
    aiTaggingEnabled: Boolean = true,
    onToggleAiTagging: (Boolean) -> Unit = {},
    routeProfiles: Map<String, String>,
    onClearRouteProfiles: () -> Unit,
    onActiveMainScreenChange: (MainScreen) -> Unit,
) {
    var showLyricPriorityDialog by remember { mutableStateOf(false) }
    var showDspPresetDialog by remember { mutableStateOf(false) }
    var showBtGuardDialog by remember { mutableStateOf(false) }
    var showDspEditorDialog by remember { mutableStateOf(false) }
    var showSpatialAudioDialog by remember { mutableStateOf(false) }
    var showPeqDialog by remember { mutableStateOf(false) }
    var showPatchNotesDialog by remember { mutableStateOf(false) }
    
    val selectedDspTitle = customDspPresets.firstOrNull { it.id == dspPresetId }?.title
        ?: DspPresets.builtinById(dspPresetId)?.title ?: "Reference"

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            SettingsGroup(title = "Audio & Perpustakaan") {
                SettingsItem(icon = Icons.Rounded.FolderSpecial, title = "Folder Musik Utama", subtitle = selectedMusicFolder ?: "Pilih satu folder khusus untuk memindai lagu", onClick = onSelectMusicFolder)
                SettingsDivider()
                SettingsItem(icon = Icons.Rounded.Lyrics, title = "Prioritas Lirik", subtitle = if (lyricPriority == LyricSourcePriority.EXTERNAL_FIRST) "Utamakan file lirik (.lrc)" else "Utamakan lirik di dalam metadata", onClick = { showLyricPriorityDialog = true })
                SettingsToggle(
                    icon = Icons.Rounded.Translate,
                    title = "Romanisasi",
                    subtitle = "Tampilkan romaji/latin di atas lirik asli (JP, CN, KR). Mengaktifkan ini akan mematikan Terjemahan.",
                    checked = romanizationEnabled,
                    onCheckedChange = onToggleRomanization
                )
                SettingsToggle(
                    icon = Icons.Rounded.Translate,
                    title = "Mode Terjemahan",
                    subtitle = "Tampilkan terjemahan bahasa Indonesia dari Editor. Mengaktifkan ini akan mematikan Romanisasi.",
                    checked = translationMode,
                    onCheckedChange = onToggleTranslationMode
                )
                SettingsItem(
                    icon = Icons.Rounded.EditNote,
                    title = "Editor Lirik & AI Workbench",
                    subtitle = "Koreksi terjemahan secara manual atau gunakan bantuan AI luar (Dual-Pane)",
                    onClick = { onActiveMainScreenChange(MainScreen.TranslationWorkbench) }
                )
                SettingsToggle(icon = Icons.Rounded.Tune, title = "Gapless Playback", subtitle = "Putar lagu tanpa jeda sunyi antar track", checked = gaplessEnabled, onCheckedChange = onToggleGapless)
                SettingsToggle(icon = Icons.Rounded.FastForward, title = "Auto Skip Intro & Outro", subtitle = "Lewati intro dan outro secara otomatis menggunakan data lirik", checked = autoSkipIntroOutro, onCheckedChange = onToggleAutoSkip)
                SettingsToggle(icon = Icons.Rounded.Tune, title = "Normalisasi Volume", subtitle = "Seimbangkan volume secara otomatis (ReplayGain)", checked = normalizationEnabled, onCheckedChange = onToggleNormalization)
                SettingsToggle(
                    icon = Icons.Rounded.Bolt,
                    title = "Boutique Bypass (Eksperimental)",
                    subtitle = "Meminta routing USAGE_GAME agar beberapa OEM melewati prosesnya. Tidak menjamin efek vendor (SKTune/Dolby) ikut terlewat, dan belum terverifikasi pada semua perangkat. Matikan jika lagu non-FLAC tidak berbunyi.",
                    checked = boutiqueBypassEnabled,
                    onCheckedChange = onToggleBoutiqueBypass
                )
                SettingsToggle(
                    icon = Icons.Rounded.Speed,
                    title = "Low Latency Mode (AAudio)",
                    subtitle = "Meminta PERFORMANCE_MODE_LOW_LATENCY agar buffer audio lebih kecil. Tidak dijamin didukung perangkat.",
                    checked = lowLatencyAudioEnabled,
                    onCheckedChange = onToggleLowLatencyAudio
                )
                SettingsToggle(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "AI Auto-Tagging (Beta)",
                    subtitle = "Deteksi genre & BPM otomatis saat scan library (100% offline & di dalam perangkat)",
                    checked = aiTaggingEnabled,
                    onCheckedChange = onToggleAiTagging
                )
                SettingsDivider()
                SettingsToggle(icon = Icons.Rounded.GraphicEq, title = "DSP Engine", subtitle = "Mastering adaptif: EQ, bass, stage & loudness", checked = dspEnabled, onCheckedChange = onSetDspEnabled)
                SettingsItem(icon = Icons.Rounded.Equalizer, title = "Preset DSP", subtitle = selectedDspTitle, onClick = { showDspPresetDialog = true })
                
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Preamp (-6..+6 dB)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(String.format(Locale.US, "%+.1f dB", preampDb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = preampDb.coerceIn(-6f, 6f),
                        onValueChange = { onSetPreampDb(it) },
                        valueRange = -6f..6f,
                        steps = 23
                    )
                }
                SettingsDivider()
                SettingsItem(
                    icon = Icons.Rounded.BluetoothAudio,
                    title = "BT Clarity Guard",
                    subtitle = when {
                        !hasBluetoothPermission -> "Izin Bluetooth diperlukan untuk deteksi codec"
                        btGuardMode == 1 -> "Selalu aktif saat speaker BT terhubung"
                        btGuardMode == 2 -> "Mati — tanpa de-mud otomatis"
                        else -> "Auto: aktif saat codec SBC/generik"
                    },
                    onClick = {
                        if (!hasBluetoothPermission) onRequestBluetoothPermission()
                        else showBtGuardDialog = true
                    }
                )
                SettingsToggle(icon = Icons.Rounded.SpatialAudio, title = "Ambience Mode", subtitle = "Ruang dengar virtual (FDN reflections) — disarankan untuk earphone", checked = ambienceEnabled, onCheckedChange = onToggleAmbience)
                SettingsItem(
                    icon = Icons.Rounded.Headset,
                    title = "Spatial Audio",
                    subtitle = when (spatialAudioPreset) {
                        1 -> "Mode Lebar (Stage)"
                        2 -> "Mode Studio (Close)"
                        else -> "Mode Standar (Binaural)"
                    },
                    onClick = { showSpatialAudioDialog = true }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Profil perangkat aktif: ${routeProfiles.size} tersimpan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = routeProfiles.isNotEmpty(), onClick = onClearRouteProfiles) { Text("Hapus") }
                }
                SettingsToggle(icon = Icons.Rounded.AudioFile, title = "Filter Durasi", subtitle = "Sembunyikan file di bawah 30 detik", checked = filterShortTracks, onCheckedChange = onToggleFilterShortTracks)
            }
        }

        item {
            SettingsGroup(title = "Antarmuka") {
                SettingsItem(icon = Icons.Rounded.Palette, title = "Tema Aplikasi", subtitle = when(appTheme) { 1 -> "Terang"; 2 -> "Gelap"; else -> "Ikuti Sistem" }, onClick = { onSetAppTheme((appTheme + 1) % 3) })
                SettingsDivider()
                SettingsToggle(icon = Icons.Rounded.ColorLens, title = "Warna Dinamis", subtitle = "Aksen UI mengikuti warna album", checked = dynamicColorEnabled, onCheckedChange = onToggleDynamicColor)
                SettingsDivider()
                SettingsToggle(
                    icon = Icons.Rounded.DirectionsCar,
                    title = "Mode Kendara (Forced Landscape)",
                    subtitle = "Kunci layar ke mendatar & fokus pada pemutar saja",
                    checked = landscapeDualPaneEnabled,
                    onToggleLandscapeDualPane
                )
            }
        }

        item {
            SettingsGroup(title = "Lainnya") {
                SettingsItem(
                    icon = Icons.Rounded.HistoryEdu,
                    title = "Catatan Perubahan (Patch Notes)",
                    subtitle = "Lihat riwayat pembaruan aplikasi",
                    onClick = { showPatchNotesDialog = true }
                )
                SettingsDivider()
                SettingsItem(icon = Icons.Rounded.Info, title = "Versi Aplikasi", subtitle = "${BuildConfig.VERSION_NAME} (Autonomous Audio Engine Build)", onClick = {})
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (showLyricPriorityDialog) {
        LyricPriorityDialog(currentPriority = lyricPriority, onSelect = { onSetLyricPriority(it); showLyricPriorityDialog = false }, onDismiss = { showLyricPriorityDialog = false })
    }
    if (showDspPresetDialog) {
        DspPresetDialog(
            selectedId = dspPresetId,
            customs = customDspPresets,
            onSelect = { onSelectDspPreset(it); showDspPresetDialog = false },
            onDelete = onDeleteCustomDspPreset,
            onCreateCustom = { showDspEditorDialog = true },
            onDismiss = { showDspPresetDialog = false }
        )
    }
    if (showBtGuardDialog) {
        BtGuardModeDialog(currentMode = btGuardMode, onSelect = { onSelectBtGuardMode(it); showBtGuardDialog = false }, onDismiss = { showBtGuardDialog = false })
    }
    if (showSpatialAudioDialog) {
        SpatialAudioDialog(
            currentPreset = spatialAudioPreset,
            onSelect = { onSelectSpatialAudioPreset(it); showSpatialAudioDialog = false },
            onDismiss = { showSpatialAudioDialog = false }
        )
    }
    if (showPeqDialog) {
        PeqProfileDialog(
            profiles = peqProfiles,
            activeId = activePeqProfileId,
            onSelect = { onSelectPeqProfile(it); showPeqDialog = false },
            onDelete = onDeletePeqProfile,
            onImport = { bands, preamp, name -> onSavePeqProfile(bands, preamp, name); showPeqDialog = false },
            onDismiss = { showPeqDialog = false }
        )
    }
    if (showDspEditorDialog) {
        DspCustomPresetEditorDialog(
            onSave = { title, params ->
                onSaveCustomDspPreset(title, params)
                showDspEditorDialog = false
                showDspPresetDialog = false
            },
            onDismiss = { showDspEditorDialog = false }
        )
    }
    if (showPatchNotesDialog) {
        PatchNotesDialog(onDismiss = { showPatchNotesDialog = false })
    }
}

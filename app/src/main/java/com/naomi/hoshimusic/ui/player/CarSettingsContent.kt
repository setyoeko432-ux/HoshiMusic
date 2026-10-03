@file:androidx.media3.common.util.UnstableApi

package com.naomi.hoshimusic.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.ui.PlaybackViewModel

@Composable
internal fun CarSettingsPage(viewModel: PlaybackViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPresetDialog by remember { mutableStateOf(false) }
    var showSpatialAudioDialog by remember { mutableStateOf(false) }
    var showPeqDialog by remember { mutableStateOf(false) }

    val presetTitle = state.customDspPresets.firstOrNull { it.id == state.dspPresetId }?.title
        ?: DspPresets.builtinById(state.dspPresetId)?.title
        ?: "Reference"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 28.dp)
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        item { Spacer(Modifier.height(10.dp)) }

        item { CarSettingsSwitchRow(Icons.Rounded.GraphicEq, "DSP Engine", state.dspEnabled) { viewModel.setDspEnabled(it) } }
        item { CarSettingsDivider() }
        item { CarSettingsActionRow(Icons.Rounded.Equalizer, "Preset DSP", presetTitle) { showPresetDialog = true } }
        item { CarSettingsDivider() }

        // BT Clarity Guard
        item {
            val guardLabel = when (state.btGuardMode) {
                1 -> "Selalu Aktif"
                2 -> "Mati"
                else -> "Auto"
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(58.dp).clickable { viewModel.setBtGuardMode((state.btGuardMode + 1) % 3) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.BluetoothAudio, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("BT Clarity Guard", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text("De-mud otomatis • $guardLabel", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
                }
                Text(guardLabel, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
        item { CarSettingsDivider() }

        // Ambience Mode
        item { CarSettingsSwitchRow(Icons.Rounded.SpatialAudio, "Ambience Mode", state.ambienceEnabled) { viewModel.setAmbienceEnabled(it) } }
        item { CarSettingsDivider() }

        // Spatial Audio
        item {
            val spatialLabel = when (state.spatialAudioPreset) {
                1 -> "Stage"
                2 -> "Close"
                else -> "Standard"
            }
            CarSettingsActionRow(Icons.Rounded.Headset, "Spatial Audio", spatialLabel) { showSpatialAudioDialog = true }
        }
        item { CarSettingsDivider() }

        // Koreksi Headphone (AutoEq)
        item {
            val peqLabel = state.peqProfiles.firstOrNull { it.id == state.activePeqProfileId }?.name ?: "Tidak aktif"
            CarSettingsActionRow(Icons.Rounded.Hearing, "Koreksi Headphone", peqLabel) { showPeqDialog = true }
        }
        item { CarSettingsDivider() }

        // Volume master
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Text("Volume", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(80.dp))
                Slider(
                    value = state.outputVolume.coerceIn(0f, 1f),
                    onValueChange = { viewModel.setOutputVolume(it) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }

    if (showPresetDialog) {
        CarPresetPickerDialog(
            selectedId = state.dspPresetId,
            customs = state.customDspPresets,
            onSelect = { viewModel.setDspPreset(it); showPresetDialog = false },
            onDismiss = { showPresetDialog = false }
        )
    }
    if (showSpatialAudioDialog) {
        CarSpatialAudioDialog(
            currentPreset = state.spatialAudioPreset,
            onSelect = { viewModel.setSpatialAudioPreset(it); showSpatialAudioDialog = false },
            onDismiss = { showSpatialAudioDialog = false }
        )
    }
}

@Composable
fun CarSettingsDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
}

@Composable
fun CarSettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun CarSettingsActionRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun CarPresetPickerDialog(
    selectedId: String,
    customs: List<CustomDspPreset>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151515),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Preset DSP", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                items(DspPresets.BUILTINS) { p ->
                    PresetPickRow(p.title, selectedId == p.id) { onSelect(p.id) }
                }
                if (customs.isNotEmpty()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { Text("KUSTOM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f)) }
                    items(customs) { c ->
                        PresetPickRow(c.title, selectedId == c.id) { onSelect(c.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color.White) } }
    )
}

@Composable
fun CarSpatialAudioDialog(
    currentPreset: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151515),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Spatial Audio", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                item { PresetPickRow("Mode Standar (Binaural)", currentPreset == 0) { onSelect(0) } }
                item { PresetPickRow("Mode Lebar (Stage)", currentPreset == 1) { onSelect(1) } }
                item { PresetPickRow("Mode Studio (Close)", currentPreset == 2) { onSelect(2) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color.White) } }
    )
}

@Composable
fun PresetPickRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

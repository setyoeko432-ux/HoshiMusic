package com.naomi.hoshimusic.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class PlayerSheet { Queue, Speed, Info }

@Composable
internal fun SpeedSheet(speed: Float, onSet: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Kecepatan Pemutaran", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(Modifier.height(24.dp))
        Text("${"%.2f".format(speed)}×", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Slider(value = speed, onValueChange = onSet, valueRange = 0.5f..2f, steps = 5, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { preset -> TextButton(onClick = { onSet(preset) }) { Text("${preset}×", color = Color.White, fontWeight = FontWeight.Bold) } }
        }
        Spacer(Modifier.height(32.dp))
    }
}

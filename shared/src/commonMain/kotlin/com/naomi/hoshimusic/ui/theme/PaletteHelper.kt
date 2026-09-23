package com.naomi.hoshimusic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class HeroPalette(
    val accentColor: Color,
    val ambientColor: Color,
    val onAccentColor: Color
)

@Composable
expect fun rememberHeroPalette(
    artworkUri: Any?,
    defaultAccent: Color = Color(0xFF2E406D),
    defaultAmbient: Color = Color(0xFF0F1420)
): HeroPalette

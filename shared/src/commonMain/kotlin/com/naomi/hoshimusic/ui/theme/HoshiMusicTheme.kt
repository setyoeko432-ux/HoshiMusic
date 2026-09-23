package com.naomi.hoshimusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFAE45),
    onPrimary = Color(0xFF231A00),
    primaryContainer = Color(0xFF3D2E00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFD8C4A0),
    onSecondary = Color(0xFF3A2F15),
    secondaryContainer = Color(0xFF39302A),
    onSecondaryContainer = Color(0xFFF5EDE0),
    tertiary = Color(0xFF9CCAF0),
    onTertiary = Color(0xFF002F49),
    background = Color(0xFF15161A),
    onBackground = Color(0xFFECE2CF),
    surface = Color(0xFF1B1D22),
    onSurface = Color(0xFFECE2CF),
    surfaceVariant = Color(0xFF26282F),
    onSurfaceVariant = Color(0xFFB5AEA0),
    surfaceContainerLowest = Color(0xFF101116),
    surfaceContainerLow = Color(0xFF1B1D22),
    surfaceContainer = Color(0xFF202228),
    surfaceContainerHigh = Color(0xFF26282F),
    surfaceContainerHighest = Color(0xFF31333B),
    outline = Color(0xFF8B857A),
    outlineVariant = Color(0xFF3A3C44),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5300),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2C1700),
    secondary = Color(0xFF6F5B3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E4CB),
    onSecondaryContainer = Color(0xFF271A04),
    tertiary = Color(0xFF31649E),
    onTertiary = Color.White,
    background = Color(0xFFF6F2EC),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFEBE3D4),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2EC),
    surfaceContainer = Color(0xFFF0ECE5),
    surfaceContainerHigh = Color(0xFFEAE6DF),
    surfaceContainerHighest = Color(0xFFE4E0D9),
    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFCFC6B4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun HoshiMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}

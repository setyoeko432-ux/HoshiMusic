package com.naomi.hoshimusic.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberHeroPalette(
    artworkUri: Any?,
    defaultAccent: Color,
    defaultAmbient: Color
): HeroPalette {
    val context = LocalContext.current
    var rawPalette by remember(artworkUri) {
        mutableStateOf(HeroPalette(defaultAccent, defaultAmbient, Color.White))
    }

    LaunchedEffect(artworkUri) {
        if (artworkUri == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(artworkUri)
                .allowHardware(false)
                .size(120, 120)
                .build()

            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = result.drawable.toBitmap()
                val palette = Palette.from(bitmap).generate()

                val accent = palette.darkMutedSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb

                val ambient = palette.darkMutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb

                val onAccent = palette.darkMutedSwatch?.titleTextColor
                    ?: android.graphics.Color.WHITE

                if (accent != null) {
                    rawPalette = HeroPalette(
                        accentColor = Color(accent),
                        ambientColor = ambient?.let { Color(it) } ?: defaultAmbient,
                        onAccentColor = Color(onAccent)
                    )
                }
            }
        }
    }

    val animatedAccent by animateColorAsState(rawPalette.accentColor, tween(500), label = "heroAccent")
    val animatedAmbient by animateColorAsState(rawPalette.ambientColor, tween(500), label = "heroAmbient")

    return HeroPalette(
        accentColor = animatedAccent,
        ambientColor = animatedAmbient,
        onAccentColor = rawPalette.onAccentColor
    )
}

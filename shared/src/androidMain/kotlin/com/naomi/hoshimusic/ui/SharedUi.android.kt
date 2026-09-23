package com.naomi.hoshimusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
actual fun Artwork(
    model: Any?,
    size: Dp,
    cornerRadius: Dp,
    fallbackIcon: ImageVector,
    modifier: Modifier,
    highQuality: Boolean,
    thumbnailModel: Any?,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            fallbackIcon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.4f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val sizePx = with(density) { size.roundToPx() }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .size(if (highQuality) coil.size.Size.ORIGINAL else coil.size.Size(sizePx, sizePx))
                    .precision(coil.size.Precision.INEXACT)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .crossfade(300)
                    .apply {
                        if (thumbnailModel != null) {
                            placeholderMemoryCacheKey(thumbnailModel.toString())
                        }
                    }
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

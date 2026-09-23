package com.naomi.hoshimusic.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class MainScreen(
    val label: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector
) {
    Home(
        label = "Beranda",
        iconOutlined = Icons.Outlined.Home,
        iconFilled = Icons.Rounded.Home
    ),
    Library(
        label = "Koleksi",
        iconOutlined = Icons.Outlined.LibraryMusic,
        iconFilled = Icons.Rounded.LibraryMusic
    ),
    Settings(
        label = "Atur",
        iconOutlined = Icons.Outlined.Settings,
        iconFilled = Icons.Rounded.Settings
    ),
    TranslationWorkbench(
        label = "Koreksi",
        iconOutlined = Icons.Outlined.Settings,
        iconFilled = Icons.Rounded.Settings
    ),
}

@Composable
internal fun TopCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

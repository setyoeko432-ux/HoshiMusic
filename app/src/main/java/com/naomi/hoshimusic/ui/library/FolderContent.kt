package com.naomi.hoshimusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.naomi.hoshimusic.domain.MusicGroup

@Composable
internal fun FolderScreen(
    groups: List<MusicGroup>,
    hiddenFolders: Set<String>,
    onPlayGroup: (MusicGroup) -> Unit,
    onToggleHiddenFolder: (String) -> Unit,
) {
    var showHidden by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (hiddenFolders.isNotEmpty()) {
            FilledTonalButton(
                onClick = { showHidden = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(Icons.Rounded.FolderOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("${hiddenFolders.size} folder disembunyikan")
            }
        }
        Box(Modifier.weight(1f)) {
            GroupList(
                groups = groups,
                icon = Icons.Rounded.Folder,
                emptyTitle = "Belum ada folder musik",
                onPlayGroup = onPlayGroup,
                onLongPressGroup = { onToggleHiddenFolder(it.key) },
            )
        }
    }
    if (showHidden) {
        AlertDialog(
            onDismissRequest = { showHidden = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Folder tersembunyi") },
            text = {
                LazyColumn {
                    items(hiddenFolders.sorted()) { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggleHiddenFolder(folder) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(folder, modifier = Modifier.weight(1f), maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Rounded.Restore, contentDescription = "Tampilkan kembali", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHidden = false }) { Text("Selesai", color = MaterialTheme.colorScheme.onSurface) } },
        )
    }
}

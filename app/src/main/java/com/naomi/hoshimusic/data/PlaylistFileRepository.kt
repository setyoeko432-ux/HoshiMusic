package com.naomi.hoshimusic.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Offline M3U/M3U8 import and export. No network access is used. */
class PlaylistFileRepository(private val context: Context) {
    data class ImportedPlaylist(
        val name: String,
        val songIds: List<String>,
        val unmatchedEntries: Int,
    )

    suspend fun importPlaylist(uri: Uri, songs: List<Song>): ImportedPlaylist =
        withContext(Dispatchers.IO) {
            val entries = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    lines.take(MAX_IMPORT_LINES)
                        .map(String::trim)
                        .filter { it.isNotBlank() && !it.startsWith('#') }
                        .toList()
                }
            }.orEmpty()

            val byStableId = songs.associateBy(Song::stableId)
            val byFileName = songs.groupBy { it.displayName.lowercase() }
            val byPortablePath = songs.associateBy { portablePath(it).lowercase() }
            var unmatched = 0
            val resolved = entries.mapNotNull { rawEntry ->
                val decoded = Uri.decode(rawEntry)
                    .removePrefix("file://")
                    .replace('\\', '/')
                    .trim()
                val direct = byStableId[decoded]
                val pathMatch = byPortablePath[decoded.trimStart('/').lowercase()]
                    ?: byPortablePath.entries.firstOrNull { (path, _) ->
                        decoded.lowercase().endsWith(path)
                    }?.value
                val nameMatch = byFileName[decoded.substringAfterLast('/').lowercase()]?.singleOrNull()
                (direct ?: pathMatch ?: nameMatch)?.stableId.also {
                    if (it == null) unmatched += 1
                }
            }.distinct()

            ImportedPlaylist(
                name = queryDisplayName(uri).substringBeforeLast('.').ifBlank { "Playlist impor" },
                songIds = resolved,
                unmatchedEntries = unmatched,
            )
        }

    suspend fun exportPlaylist(uri: Uri, playlist: LocalPlaylist, songs: List<Song>): Int =
        withContext(Dispatchers.IO) {
            val byId = songs.associateBy(Song::stableId)
            val exportedSongs = playlist.songIds.mapNotNull(byId::get)
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.appendLine("#EXTM3U")
                    writer.appendLine("#PLAYLIST:${playlist.name.sanitizeM3uText()}")
                    exportedSongs.forEach { song ->
                        val durationSeconds = (song.durationMs / 1_000L).coerceAtLeast(0L)
                        writer.appendLine(
                            "#EXTINF:$durationSeconds,${song.artist.sanitizeM3uText()} - " +
                                song.title.sanitizeM3uText()
                        )
                        writer.appendLine(portablePath(song))
                    }
                } ?: error("Tujuan playlist tidak dapat ditulis.")
            exportedSongs.size
        }

    private fun portablePath(song: Song): String = song.absolutePath
        ?.replace('\\', '/')
        ?: (song.relativePath.trimStart('/') + song.displayName)

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun String.sanitizeM3uText(): String = replace('\n', ' ').replace('\r', ' ').trim()

    private companion object {
        const val MAX_IMPORT_LINES = 50_000
    }
}

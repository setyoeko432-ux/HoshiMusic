package com.naomi.hoshimusic.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.util.UriUtils
import java.io.File

internal class MediaStoreSongSource(private val context: Context) {

    fun queryMediaStore(folderUri: String? = null): List<Song> {
        val folderInfo = folderUri?.let { UriUtils.getFolderInfo(context, Uri.parse(it)) }

        val collections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).mapNotNull { volume ->
                if (folderInfo != null) {
                    val volumeMatches = if (folderInfo.volumeId.equals("primary", ignoreCase = true)) {
                        volume.equals("external_primary", ignoreCase = true) || volume.equals("external", ignoreCase = true)
                    } else {
                        volume.equals(folderInfo.volumeId, ignoreCase = true)
                    }
                    if (!volumeMatches) return@mapNotNull null
                }
                volume to MediaStore.Audio.Media.getContentUri(volume)
            }
        } else {
            listOf("external" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        return collections.flatMap { (volume, collection) -> queryCollection(collection, volume, folderUri) }
    }

    private fun queryCollection(collection: Uri, volumeName: String, folderUri: String? = null): List<Song> =
        runCatching { queryCollectionInternal(collection, volumeName, includeExtended = true, folderUri = folderUri) }
            .getOrElse { queryCollectionInternal(collection, volumeName, includeExtended = false, folderUri = folderUri) }

    private fun queryCollectionInternal(
        collection: Uri,
        volumeName: String,
        includeExtended: Boolean,
        folderUri: String? = null
    ): List<Song> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID); add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED); add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.COMPOSER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
            else add(MediaStore.Audio.Media.DATA)
            if (includeExtended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(COLUMN_GENRE); add(COLUMN_ALBUM_ARTIST); add(COLUMN_BITRATE)
            }
            add(MediaStore.Audio.Media.DATA)
            if (includeExtended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(COLUMN_SAMPLE_RATE)
            }
        }.distinct().toTypedArray()

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?")
            if (folderUri != null) {
                append(" AND ")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append("${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?")
                } else {
                    append("${MediaStore.Audio.Media.DATA} LIKE ?")
                }
            }
        }
        
        val selectionArgs = buildList {
            add(MINIMUM_DURATION_MS.toString())
            if (folderUri != null) {
                val info = UriUtils.getFolderInfo(context, Uri.parse(folderUri))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info != null) {
                    val relPath = if (info.relativePath.isEmpty()) "" else info.relativePath.removeSuffix("/") + "/"
                    add("$relPath%")
                } else {
                    val path = info?.physicalPath ?: UriUtils.getPhysicalPath(context, Uri.parse(folderUri))
                    add("$path%")
                }
            }
        }.toTypedArray()

        val songs = mutableListOf<Song>()

        context.contentResolver.query(
            collection, projection, selection,
            selectionArgs, null
        )?.use { cursor ->
            val columns = CursorColumns(cursor)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(columns.id)
                val albumId = cursor.getLong(columns.albumId)
                val displayName = cursor.string(columns.displayName)
                val taggedTitle = cursor.string(columns.title).trim()
                val title = taggedTitle.ifBlank { displayName.substringBeforeLast('.') }
                    .ifBlank { "Tanpa judul" }
                val artist = cursor.string(columns.artist)
                    .takeUnless { it.isBlank() || it == "<unknown>" } ?: "Artis tidak diketahui"
                val album = cursor.string(columns.album)
                    .takeUnless { it.isBlank() || it == "<unknown>" } ?: "Album tidak diketahui"
                val albumArtist = cursor.string(columns.albumArtist)
                    .takeUnless { it.isBlank() || it == "<unknown>" } ?: artist
                val genre = cursor.string(columns.genre)
                    .takeUnless { it.isBlank() || it == "<unknown>" } ?: "Genre tidak diketahui"
                val rawPath = cursor.string(columns.path)
                val absolutePath = cursor.string(columns.data)
                val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawPath
                else File(rawPath).parentFile?.absolutePath.orEmpty()
                val artworkUri = if (albumId >= 0L)
                    ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null

                val mimeType = cursor.string(columns.mimeType).ifBlank { "audio/*" }
                val isLosslessMime = mimeType.contains("flac", true) || 
                                   mimeType.contains("wav", true) || 
                                   mimeType.contains("alac", true) ||
                                   mimeType.contains("aiff", true)

                songs += Song(
                    id = id,
                    albumId = albumId,
                    title = title, artist = artist, albumArtist = albumArtist,
                    album = album, genre = genre,
                    durationMs = cursor.long(columns.duration).coerceAtLeast(0L),
                    contentUri = ContentUris.withAppendedId(collection, id),
                    artworkUri = artworkUri,
                    folder = relativePath.substringBeforeLast('/').ifBlank { "Penyimpanan utama" },
                    relativePath = relativePath,
                    absolutePath = absolutePath,
                    displayName = displayName, volumeName = volumeName,
                    trackNumber = cursor.int(columns.track)
                        .let { if (it >= 1_000) it % 1_000 else it }.coerceAtLeast(0),
                    dateAddedMs = cursor.long(columns.dateAdded).coerceAtLeast(0L) * 1_000L,
                    mimeType = mimeType,
                    bitrate = cursor.optionalPositiveInt(columns.bitrate),
                    sampleRate = cursor.optionalPositiveInt(columns.sampleRate),
                    bitDepth = if (isLosslessMime) 24 else null,
                    fileSize = cursor.long(columns.size),
                    year = cursor.optionalPositiveInt(columns.year),
                    composer = cursor.string(columns.composer)
                        .takeUnless { it.isBlank() || it == "<unknown>" },
                    format = null
                )
            }
        }
        return songs
    }

    private class CursorColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val composer = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        else cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        val data = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        val genre = cursor.getColumnIndex(COLUMN_GENRE)
        val albumArtist = cursor.getColumnIndex(COLUMN_ALBUM_ARTIST)
        val bitrate = cursor.getColumnIndex(COLUMN_BITRATE)
        val sampleRate = cursor.getColumnIndex(COLUMN_SAMPLE_RATE)
    }

    private fun Cursor.string(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    private fun Cursor.long(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L
    private fun Cursor.int(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0
    private fun Cursor.optionalPositiveInt(index: Int): Int? =
        int(index).takeIf { it > 0 }

    companion object {
        const val MINIMUM_DURATION_MS = 30_000L
        const val COLUMN_GENRE = "genre"
        const val COLUMN_ALBUM_ARTIST = "album_artist"
        const val COLUMN_BITRATE = "bitrate"
        const val COLUMN_SAMPLE_RATE = "samplerate"
        val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

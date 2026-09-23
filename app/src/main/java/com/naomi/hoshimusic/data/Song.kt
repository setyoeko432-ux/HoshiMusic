package com.naomi.hoshimusic.data

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.util.QualityResolver
import com.naomi.hoshimusic.util.RomanizationLanguage

@Immutable
data class Song(
    val id: Long,
    val albumId: Long,
    val title: String, 
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val contentUri: Uri,
    val artworkUri: Uri?,
    val folder: String,
    val relativePath: String,
    val absolutePath: String?,
    val displayName: String,
    val volumeName: String,
    val trackNumber: Int,
    val dateAddedMs: Long,
    val mimeType: String,
    
    // Technical Metadata
    val bitrate: Int?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val format: String?,
    val container: String? = null,
    val codec: String? = null,
    val detectionSource: DetectionSource = DetectionSource.UNKNOWN,
    val isFormatVerified: Boolean = false,
    val metadataScanStatus: ScanStatus = ScanStatus.PENDING,
    val metadataFailureReason: String? = null,
    val metadataScannedAt: Long = 0L,
    val fileSize: Long,
    val channels: Int? = null,
    
    // Pro-Lyrics & Language (Senior Engineer Targets)
    val detectedLanguage: RomanizationLanguage = RomanizationLanguage.UNKNOWN,
    val romanizedTitle: String? = null,
    val romanizedArtist: String? = null,
    
    // Digital Identity
    val isrc: String? = null,
    val recordLabel: String? = null,
    val bpm: Int? = null,
    val year: Int? = null,
    val composer: String? = null,
    
    // ReplayGain & Lyrics
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,
    val embeddedLyrics: String? = null,
    val hasLyrics: Boolean = false,
    val lyricsSource: String? = null,

    // Hasil analyzer offline (fallback lagu tanpa tag ReplayGain)
    val analyzedGainDb: Float? = null,

    // Play Statistics
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0,
) {
    val stableId: String
        get() {
            val dir = relativePath.trim('/')
            val name = displayName.ifBlank { "unknown-$id" }
            return if (dir.isEmpty()) "$volumeName:$name:$fileSize"
            else "$volumeName:$dir/$name"
        }

    fun getQualityLabel(): String? = QualityResolver.resolve(this).label.takeIf { it.isNotBlank() }

    fun getCleanFormat(): String {
        if (isFormatVerified) {
            return AudioFormatInfo(container, codec).getCanonicalLabel()
        }
        
        if (!format.isNullOrBlank()) return format.uppercase()
        val raw = mimeType.substringAfter('/').uppercase()
        return when {
            raw.contains("MPEG") || raw.contains("MP3") -> "MP3"
            raw.contains("FLAC") -> "FLAC"
            raw.contains("WAV") -> "WAV"
            raw.contains("ALAC") -> "ALAC"
            raw.contains("OPUS") -> "OPUS"
            raw.contains("OGG") -> "OGG"
            else -> raw
        }
    }

    fun getTechnicalSummary(): String = QualityResolver.getTechnicalSummary(this)

    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putLong("album_id", albumId)
            putLong("media_store_id", id)
            putString("hoshi.format", getCleanFormat())
            putString("hoshi.container", container)
            putString("hoshi.codec", codec)
            putBoolean("hoshi.is_verified", isFormatVerified)
            putString("hoshi.audio_uri", contentUri.toString())
            sampleRate?.let { putInt("hoshi.sample_rate", it) }
            bitDepth?.let { putInt("hoshi.bit_depth", it) }
            putString("hoshi.language", detectedLanguage.name)
            romanizedTitle?.let { putString("hoshi.romanized_title", it) }
            romanizedArtist?.let { putString("hoshi.romanized_artist", it) }
        }
        return MediaItem.Builder()
            .setMediaId(stableId)
            .setUri(contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}

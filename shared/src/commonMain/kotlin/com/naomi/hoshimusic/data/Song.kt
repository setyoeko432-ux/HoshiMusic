package com.naomi.hoshimusic.data

import androidx.compose.runtime.Immutable
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
    val contentUri: String,
    val artworkUri: String?,
    val folder: String,
    val relativePath: String,
    val absolutePath: String?,
    val displayName: String,
    val volumeName: String,
    val trackNumber: Int,
    val dateAddedMs: Long,
    val mimeType: String,
    
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
    
    val detectedLanguage: RomanizationLanguage = RomanizationLanguage.UNKNOWN,
    val romanizedTitle: String? = null,
    val romanizedArtist: String? = null,
    
    val isrc: String? = null,
    val recordLabel: String? = null,
    val bpm: Int? = null,
    val year: Int? = null,
    val composer: String? = null,
    
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,
    val embeddedLyrics: String? = null,
    val hasLyrics: Boolean = false,
    val lyricsSource: String? = null,

    val analyzedGainDb: Float? = null,

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
}

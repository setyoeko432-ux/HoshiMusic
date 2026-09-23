package com.naomi.hoshimusic.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.naomi.hoshimusic.data.metadata.ScanStatus

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["genre"])
    ]
)
data class SongEntity(
    @PrimaryKey val mediaId: String,
    val mediaStoreId: Long,
    val volumeName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val relativePath: String,
    val absolutePath: String?,
    val albumId: Long,
    val artworkUri: String?,
    
    // Core Metadata
    val albumArtist: String,
    val genre: String,
    val displayName: String,
    val trackNumber: Int,
    val dateAddedMs: Long,
    val fileSize: Long,
    val mimeType: String,
    
    // Technical Metadata
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val format: String? = null,
    val container: String? = null,
    val codec: String? = null,
    val detectionSource: String = "UNKNOWN",
    val isFormatVerified: Boolean = false,
    val metadataScanStatus: String = ScanStatus.PENDING.name,
    val metadataFailureReason: String? = null,
    val metadataScannedAt: Long = 0L,
    val channels: Int? = null,
    
    // Pro-Lyrics & Language (Senior Engineer Targets)
    val detectedLanguage: String = "UNKNOWN",
    val romanizedTitle: String? = null,
    val romanizedArtist: String? = null,
    val languageOverride: String? = null,
    
    // Digital Identity
    val isrc: String? = null,
    val recordLabel: String? = null,
    val composer: String? = null,
    val releaseYear: Int? = null,
    val bpm: Int? = null,
    
    // ReplayGain
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,

    // Hasil analyzer offline utk lagu TANPA tag ReplayGain (gain dB RG-compatible, peak linear)
    val analyzedGainDb: Float? = null,
    val analyzedPeakDb: Float? = null,
    val analysisStatus: String = ScanStatus.PENDING.name, // PENDING / SUCCESS / TRANSIENT_FAILURE / PERMANENT_FAILURE
    
    // Embedded Lyrics
    val embeddedLyrics: String? = null,

    // Play Statistics
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0,
    
    val lastScannedMs: Long = 0
)

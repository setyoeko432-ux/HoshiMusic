package com.naomi.hoshimusic.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.naomi.hoshimusic.data.db.SongEntity
import com.naomi.hoshimusic.data.metadata.MojibakeRepairer
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.util.HoshiRomanizer
import com.naomi.hoshimusic.util.RomanizationLanguage

/**
 * Pemetaan dan Transformasi Data (Data Mapping)
 * Bertugas sebagai penerjemah data mentah menjadi objek domain.
 */
object MediaMapper {

    fun mergeWithExisting(song: Song, existing: SongEntity?): SongEntity {
        val base = toEntity(song)
        if (existing == null) return base

        // [B4] Merge logic based on source quality:
        // fresh verified > existing verified > fresh fallback > existing fallback
        
        val useFreshTechnical = song.isFormatVerified || !existing.isFormatVerified
        val useFreshStatus = song.metadataScanStatus != ScanStatus.PENDING &&
                song.metadataScannedAt >= existing.metadataScannedAt
        
        return base.copy(
            playCount = existing.playCount,
            lastPlayedAtMs = existing.lastPlayedAtMs,
            
            // Technical Metadata Merge
            bitrate = if (useFreshTechnical) song.bitrate else existing.bitrate,
            sampleRate = if (useFreshTechnical) song.sampleRate else existing.sampleRate,
            bitDepth = if (useFreshTechnical) song.bitDepth else existing.bitDepth,
            channels = if (useFreshTechnical) song.channels ?: 2 else existing.channels ?: 2,
            format = if (useFreshTechnical) song.format else existing.format,
            container = if (useFreshTechnical) song.container else existing.container,
            codec = if (useFreshTechnical) song.codec else existing.codec,
            detectionSource = if (useFreshTechnical) song.detectionSource.name else existing.detectionSource,
            isFormatVerified = song.isFormatVerified || existing.isFormatVerified,
            
            metadataScanStatus = if (useFreshStatus) song.metadataScanStatus.name else existing.metadataScanStatus,
            metadataFailureReason = if (useFreshStatus) song.metadataFailureReason else existing.metadataFailureReason,
            metadataScannedAt = if (useFreshStatus) song.metadataScannedAt else existing.metadataScannedAt,

            absolutePath = existing.absolutePath ?: song.absolutePath,
            isrc = song.isrc ?: existing.isrc,
            recordLabel = song.recordLabel ?: existing.recordLabel,
            composer = song.composer ?: existing.composer,
            releaseYear = song.year ?: existing.releaseYear,
            bpm = song.bpm ?: existing.bpm,
            
            replayGainTrack = song.replayGainTrack ?: existing.replayGainTrack,
            replayGainAlbum = song.replayGainAlbum ?: existing.replayGainAlbum,
            analyzedGainDb = existing.analyzedGainDb,
            analyzedPeakDb = existing.analyzedPeakDb,
            
            embeddedLyrics = song.embeddedLyrics ?: existing.embeddedLyrics,
            lastScannedMs = existing.lastScannedMs,
            detectedLanguage = existing.detectedLanguage,
            romanizedTitle = existing.romanizedTitle,
            romanizedArtist = existing.romanizedArtist,
            languageOverride = existing.languageOverride
        )
    }

    fun toEntity(song: Song) = SongEntity(
        mediaId = song.stableId,
        mediaStoreId = song.id,
        volumeName = song.volumeName,
        title = song.title, artist = song.artist, album = song.album,
        durationMs = song.durationMs, relativePath = song.relativePath,
        albumId = song.albumId,
        artworkUri = song.artworkUri?.toString(),
        albumArtist = song.albumArtist, genre = song.genre, displayName = song.displayName,
        trackNumber = song.trackNumber, dateAddedMs = song.dateAddedMs,
        fileSize = song.fileSize, mimeType = song.mimeType,
        bitrate = song.bitrate, sampleRate = song.sampleRate, bitDepth = song.bitDepth,
        format = song.format,
        container = song.container,
        codec = song.codec,
        detectionSource = song.detectionSource.name,
        isFormatVerified = song.isFormatVerified,
        metadataScanStatus = song.metadataScanStatus.name,
        metadataFailureReason = song.metadataFailureReason,
        metadataScannedAt = song.metadataScannedAt,
        absolutePath = song.absolutePath,
        isrc = song.isrc, recordLabel = song.recordLabel,
        composer = song.composer, releaseYear = song.year, bpm = song.bpm,
        channels = song.channels
    )

    fun resolveContentUri(entity: SongEntity): Uri {
        if (entity.mediaId.startsWith("content://")) return Uri.parse(entity.mediaId)
        return runCatching {
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.getContentUri(entity.volumeName),
                entity.mediaStoreId
            )
        }.getOrElse {
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                entity.mediaStoreId
            )
        }
    }

    fun mapToDomain(entity: SongEntity): Song {
        return Song(
            id = entity.mediaStoreId,
            albumId = entity.albumId,
            title = entity.title.ifBlank { "Tanpa judul" },
            artist = entity.artist.ifBlank { "Artis tidak diketahui" },
            albumArtist = entity.albumArtist.ifBlank {
                entity.artist.ifBlank { "Artis tidak diketahui" }
            },
            album = entity.album.ifBlank { "Album tidak diketahui" },
            genre = entity.genre.ifBlank { "Genre tidak diketahui" },
            durationMs = entity.durationMs,
            contentUri = resolveContentUri(entity),
            artworkUri = entity.artworkUri?.let { Uri.parse(it) },
            folder = entity.relativePath.substringBeforeLast('/').ifBlank { "Penyimpanan utama" },
            relativePath = entity.relativePath,
            absolutePath = entity.absolutePath,
            displayName = entity.displayName,
            volumeName = entity.volumeName,
            trackNumber = entity.trackNumber,
            dateAddedMs = entity.dateAddedMs,
            mimeType = entity.mimeType,
            bitrate = entity.bitrate,
            sampleRate = entity.sampleRate,
            bitDepth = entity.bitDepth,
            channels = entity.channels ?: 2,
            format = entity.format,
            container = entity.container,
            codec = entity.codec,
            detectionSource = runCatching { DetectionSource.valueOf(entity.detectionSource) }
                .getOrDefault(DetectionSource.UNKNOWN),
            isFormatVerified = entity.isFormatVerified,
            metadataScanStatus = runCatching { ScanStatus.valueOf(entity.metadataScanStatus) }
                .getOrDefault(ScanStatus.PENDING),
            metadataFailureReason = entity.metadataFailureReason,
            metadataScannedAt = entity.metadataScannedAt,
            fileSize = entity.fileSize,

            // Language State
            detectedLanguage = entity.detectedLanguage.toRomanizationLanguage(),
            romanizedTitle = entity.romanizedTitle,
            romanizedArtist = entity.romanizedArtist,

            isrc = entity.isrc,
            recordLabel = entity.recordLabel,
            bpm = entity.bpm,
            year = entity.releaseYear,
            composer = entity.composer,
            analyzedGainDb = entity.analyzedGainDb,
            embeddedLyrics = entity.embeddedLyrics,
            hasLyrics = !entity.embeddedLyrics.isNullOrBlank(),
            lyricsSource = if (entity.embeddedLyrics.isNullOrBlank()) null else "internal",
            playCount = entity.playCount,
            lastPlayedAtMs = entity.lastPlayedAtMs
        )
    }

    /** [V17]: Create a synthetic Song from a Media3 MediaItem for queue mapping fallback. */
    fun fromMediaItem(item: androidx.media3.common.MediaItem): Song {
        val meta = item.mediaMetadata
        val extras = item.mediaMetadata.extras
        val albumId = extras?.getLong("album_id", -1L) ?: -1L
        
        return Song(
            id = extras?.getLong("media_store_id", -1L) ?: -1L,
            albumId = albumId,
            title = meta.title?.toString() ?: "Tanpa judul",
            artist = meta.artist?.toString() ?: "Artis tidak diketahui",
            albumArtist = meta.albumArtist?.toString() ?: meta.artist?.toString() ?: "Artis tidak diketahui",
            album = meta.albumTitle?.toString() ?: "Album tidak diketahui",
            genre = "",
            durationMs = 0,
            contentUri = item.localConfiguration?.uri ?: Uri.EMPTY,
            artworkUri = meta.artworkUri,
            folder = "",
            relativePath = "",
            absolutePath = null,
            displayName = "",
            volumeName = "",
            trackNumber = 0,
            dateAddedMs = 0,
            mimeType = "",
            bitrate = null,
            sampleRate = null,
            bitDepth = null,
            format = null,
            fileSize = 0
        )
    }

    /**
     * [7d] Robust enum parsing (Audit MM4)
     */
    private fun String.toRomanizationLanguage(): RomanizationLanguage {
        return try {
            RomanizationLanguage.valueOf(this)
        } catch (_: Exception) {
            RomanizationLanguage.UNKNOWN
        }
    }

    /**
     * [C10] Mojibake repair for legacy CJK tags.
     *
     * MediaStore-sourced text can be Latin-1-mislabeled Shift-JIS/EUC-KR/
     * Big5/GB18030 garbage. Repairing BEFORE language detection is what makes
     * HoshiRomanizer's classification trustworthy; repairing AFTER would only
     * romanize the garbage shape. Idempotent: already-correct text passes
     * through unchanged, so calling this twice is harmless.
     */
    fun repairTexts(entity: SongEntity): SongEntity {
        val title = MojibakeRepairer.repair(entity.title) ?: entity.title
        val artist = MojibakeRepairer.repair(entity.artist) ?: entity.artist
        val albumArtist = entity.albumArtist
            .takeIf { it.isNotBlank() }
            ?.let { MojibakeRepairer.repair(it) }
            ?: entity.albumArtist
        val album = MojibakeRepairer.repair(entity.album) ?: entity.album
        val genre = MojibakeRepairer.repair(entity.genre) ?: entity.genre
        val composer = if (entity.composer.isNullOrBlank()) entity.composer
            else MojibakeRepairer.repair(entity.composer)
        val embeddedLyrics = if (entity.embeddedLyrics.isNullOrBlank()) entity.embeddedLyrics
            else MojibakeRepairer.repair(entity.embeddedLyrics)

        return entity.copy(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            composer = composer,
            embeddedLyrics = embeddedLyrics
        )
    }

    /**
     * Mesin Klasifikasi Bahasa & Romanisasi.
     * PERBAIKAN: hormati languageOverride user — jika ada, pakai itu
     * dan jangan deteksi ulang lalu menimpanya.
     * [C10]: detection now runs on mojibake-repaired text.
     */
    fun processMetadata(entity: SongEntity): SongEntity {
        val repaired = repairTexts(entity)

        val override = repaired.languageOverride
            ?.let { runCatching { RomanizationLanguage.valueOf(it) }.getOrNull() }

        // PRIORITAS: Lirik (jika ada) jauh lebih akurat daripada judul/artis untuk klasifikasi
        val lyrics = repaired.embeddedLyrics
        val metadataText = "${repaired.title} ${repaired.artist} ${repaired.album} ${repaired.genre}"

        // [1d] Narrow Guard: Deteksi bahasa TETAP jalan di device pre-Q (pure JVM)
        val lang = override ?: if (!lyrics.isNullOrBlank()) {
            val fromLyrics = HoshiRomanizer.detectLyricsLanguage(lyrics)
            if (fromLyrics != RomanizationLanguage.UNKNOWN && fromLyrics != RomanizationLanguage.LATIN) {
                fromLyrics
            } else {
                HoshiRomanizer.detectLanguage(metadataText, lyrics)
            }
        } else {
            HoshiRomanizer.detectLanguage(metadataText)
        }

        val supportsRomanization = HoshiRomanizer.isSupported()

        // Hint bahasa diteruskan: judul pure-kanji (HAN_AMBIGUOUS)
        // akan mengikuti bahasa lagu, bukan langsung dianggap Mandarin
        val titleResult = if (supportsRomanization) {
            HoshiRomanizer.romanize(repaired.title, lang)
        } else null
        
        // Artis: Gunakan Alias Resolver dulu, baru fallback ke Romanisasi murni
        val artistAlias = HoshiRomanizer.resolveArtistAlias(repaired.artist)
        val artistResult = artistAlias ?: if (supportsRomanization) {
            HoshiRomanizer.romanize(repaired.artist, lang).romanized
        } else null

        return repaired.copy(
            detectedLanguage = lang.name, // Tetap set detectedLanguage di device manapun
            romanizedTitle = titleResult?.romanized,
            romanizedArtist = artistResult
        )
    }

    // PERBAIKAN: regex di-hoist — dulu dikompilasi ulang setiap pemanggilan
    private val BRACKETED = Regex("(?i)\\[.*?\\]")
    private val PARENTHESIZED = Regex("(?i)\\(.*?\\)")

    /**
     * Pembersih judul heuristic (bukan AI): menghapus segmen [..] dan (..).
     * PERBAIKAN: diganti nama dari enrichWithAi agar jujur, dan beroperasi
     * pada String — hasilnya untuk tampilan/pencarian, JANGAN ditulis ke DB
     * (destruktif untuk judul sah seperti "(Don't Fear) The Reaper").
     */
}
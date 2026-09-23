package com.naomi.hoshimusic.data.metadata

import android.util.Log
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagException
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [A1][A4][A5][B7][B8][C10][D16] JAudiotagger orchestration layer.
 *
 * Single source of truth for tag reading:
 *  - A4  extension-independent parsing via magic-byte sniff retry.
 *  - A5  differentiated exception taxonomy → ScanStatus, failures logged.
 *  - A1  ISRC / record label / BPM / composer come from REAL tags here,
 *        not from fabricated MediaMetadataRetriever keys.
 *  - B7/B8 multi-value reads + Auxio-style TXXX priority chains.
 *  - C10 every decoded string passes through mojibake repair BEFORE storage.
 *  - D16 technical props probed from parsed headers (works on ALL API levels,
 *        unlike MediaMetadataRetriever's API 31+ sample-rate keys).
 */
internal object TagEngine {

    private const val TAG = "HoshiMeta"

    // [V31]: Metadata engine version for cache invalidation
    const val VERSION = 3

    data class RichTags(
        val status: ScanStatus,
        val failureReason: String? = null,
        // Editorial text (already mojibake-repaired)
        val title: String? = null,
        val artistPrimary: String? = null,
        val artists: List<String> = emptyList(),
        val albumArtistPrimary: String? = null,
        val albumArtists: List<String> = emptyList(),
        val album: String? = null,
        val genrePrimary: String? = null,
        val composerPrimary: String? = null,
        val isrc: String? = null,
        val recordLabel: String? = null,
        // Numerics (already sanitized)
        val year: Int? = null,
        val bpm: Int? = null,
        val trackNumber: Int? = null,
        // Lyrics candidates
        val lyrics: String? = null,
        val syltBytes: ByteArray? = null,
        val syltTimestampFormat: Int? = null,
        // Technical, probed from parsed stream header ([D16])
        val format: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null,
        val bitrateBps: Int? = null,
        val durationMs: Long? = null,
        val replayGainTrack: Float? = null,
        val replayGainAlbum: Float? = null,
        val replayGainPeak: Float? = null
    )

    /**
     * Reads [file] with extension-independent fallback and full failure
     * classification. Never throws.
     */
    fun read(file: File): RichTags {
        JulLogBridge.install()

        if (!file.exists() || !file.canRead()) {
            return RichTags(ScanStatus.TRANSIENT_FAILURE, "file missing/unreadable: ${file.path}")
        }

        return try {
            extract(readWithSniffFallback(file))
        } catch (e: FileNotFoundException) {
            log("not-found", true, e)
            RichTags(ScanStatus.TRANSIENT_FAILURE, "FileNotFoundException")
        } catch (e: IOException) {
            log("io", false, e)
            RichTags(ScanStatus.TRANSIENT_FAILURE, "IOException: ${e.message}")
        } catch (e: CannotReadException) {
            log("cannot-read", true, e)
            RichTags(ScanStatus.PERMANENT_FAILURE, "CannotReadException: ${e.message}")
        } catch (e: TagException) {
            log("tag", true, e)
            RichTags(ScanStatus.PERMANENT_FAILURE, "TagException: ${e.message}")
        } catch (e: InvalidAudioFrameException) {
            log("frame", true, e)
            RichTags(ScanStatus.PERMANENT_FAILURE, "InvalidAudioFrame: ${e.message}")
        } catch (e: ReadOnlyFileException) {
            log("readonly", true, e)
            RichTags(ScanStatus.PERMANENT_FAILURE, "ReadOnlyFileException: ${e.message}")
        } catch (e: Exception) {
            // Unknown runtime blowups stay transient so WorkManager can retry
            // later instead of permanently blacklisting the song.
            log("unexpected", false, e)
            RichTags(ScanStatus.TRANSIENT_FAILURE, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * [A4] Normal parse first; if rejected AND the magic bytes disagree with
     * the filename, retry with the sniffed container before giving up.
     */
    private fun readWithSniffFallback(file: File): AudioFile =
        try {
            AudioFileIO.read(file)
        } catch (first: CannotReadException) {
            val sniffed = MagicSniffer.detect(file) ?: throw first
            val format = sniffed.jaudiotaggerFormat ?: throw first
            Log.i(TAG, "Extension mismatch on ${file.name}; retrying as ${format.name}")
            try {
                AudioFileIO.readAs(file, format.name.lowercase())
            } catch (_: Exception) {
                throw first  // surface the original, clearer error
            }
        }

    // ---- field extraction --------------------------------------------------

    private fun extract(audioFile: AudioFile): RichTags {
        val tag: Tag? = audioFile.tag   // legitimately nullable per jaudiotagger docs
        if (tag == null) Log.w(TAG, "No tag in ${audioFile.file.name}; header-only read")

        val artists = multiValue(tag, FieldKey.ARTIST, txxxDescription = "artists")
        val albumArtists = multiValue(tag, FieldKey.ALBUM_ARTIST, txxxDescription = "albumartists")
        val genres = multiValue(tag, FieldKey.GENRE)
        val composers = multiValue(tag, FieldKey.COMPOSER)

        val header = audioFile.audioHeader

        return RichTags(
            status = ScanStatus.SUCCESS,

            title = repaired(first(tag, FieldKey.TITLE)),
            artistPrimary = artists.firstOrNull(),
            artists = artists,
            albumArtistPrimary = albumArtists.firstOrNull(),
            albumArtists = albumArtists,
            album = repaired(first(tag, FieldKey.ALBUM)),
            genrePrimary = genres.firstOrNull(),
            composerPrimary = composers.firstOrNull(),
            isrc = repaired(first(tag, FieldKey.ISRC))?.takeIf { it.isNotBlank() },
            recordLabel = repaired(first(tag, FieldKey.RECORD_LABEL))?.takeIf { it.isNotBlank() }
                ?: repaired(readTxxx(tag, "label"))?.takeIf { it.isNotBlank() },
            year = MetadataSanitizer.sanitizeYear(first(tag, FieldKey.YEAR)),
            bpm = MetadataSanitizer.sanitizeBpm(bpmFrom(first(tag, FieldKey.BPM))),
            trackNumber = first(tag, FieldKey.TRACK)?.substringBefore('/')?.trim()?.toIntOrNull()
                ?.takeIf { it in 0..999 },
            lyrics = lyricsCandidate(tag),
            syltBytes = syltCandidate(tag)?.first,
            syltTimestampFormat = syltCandidate(tag)?.second,

            format = runCatching { header?.format }.getOrNull(),
            sampleRate = MetadataSanitizer.sanitizeSampleRate(
                runCatching { header?.sampleRateAsNumber }.getOrNull()
            ),
            bitDepth = MetadataSanitizer.sanitizeBitDepth(
                runCatching { header?.bitsPerSample }.getOrNull()
            ),
            channels = MetadataSanitizer.sanitizeChannels(
                runCatching { header?.channels }.getOrNull()?.toIntOrNull()
            ),
            bitrateBps = MetadataSanitizer.sanitizeBitrateBps(
                runCatching { header?.bitRate }.getOrNull()?.trim()?.toIntOrNull()?.let { it * 1000 }
            ),
            durationMs = runCatching { header?.trackLength?.toLong()?.let { it * 1000 } }.getOrNull(),
            replayGainTrack = readTxxx(tag, "REPLAYGAIN_TRACK_GAIN")?.replace(',', '.')?.toFloatOrNull(),
            replayGainAlbum = readTxxx(tag, "REPLAYGAIN_ALBUM_GAIN")?.replace(',', '.')?.toFloatOrNull(),
            replayGainPeak = (readTxxx(tag, "REPLAYGAIN_TRACK_PEAK") ?: readTxxx(tag, "REPLAYGAIN_ALBUM_PEAK"))
                ?.replace(',', '.')?.toFloatOrNull()
        )
    }

    /** BPM tags arrive as "128", "128.5" or even "128 bpm". */
    private fun bpmFrom(raw: String?): Int? {
        val s = raw?.trim()?.substringBefore(' ') ?: return null
        return s.toFloatOrNull()?.toInt() ?: s.toIntOrNull()
    }

    /**
     * [B7][B8] Priority chain for a text field:
     *   1. ID3 TXXX extended frame (e.g. TXXX:ARTISTS from Picard/beets),
     *      preferred OVER the standard frame per Auxio's chain because it
     *      holds cleanly-delimited collaborators.
     *   2. Native values by indexed reads — covers ID3v2.4 NUL lists,
     *      repeated Vorbis comments and repeated MP4 atoms.
     *
     * Separator-based splitting (";" "/" "&") deliberately NOT applied —
     * auto-splitting mangles real names like "Black Country, New Road".
     */
    private fun multiValue(
        tag: Tag?,
        key: FieldKey,
        txxxDescription: String? = null
    ): List<String> {
        if (tag == null) return emptyList()

        val fromTxxx = txxxDescription
            ?.let { d -> splitNul(readTxxx(tag, d) ?: "") }
            .orEmpty()
        val native = allValues(tag, key).flatMap { splitNul(it) }

        return (fromTxxx + native)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private fun first(tag: Tag?, key: FieldKey): String? =
        if (tag == null) null
        else runCatching { tag.getFirst(key) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Indexed value walk; using getFields().size for efficiency. */
    private fun allValues(tag: Tag, key: FieldKey): List<String> {
        val fields = runCatching { tag.getFields(key) }.getOrNull()
        val count = fields?.size ?: 0
        if (count == 0) return listOfNotNull(first(tag, key))
        
        val out = mutableListOf<String>()
        for (i in 0 until minOf(count, 64)) {
            val v = runCatching { tag.getValue(key, i) }.getOrNull() ?: break
            if (v.isNotEmpty()) out += v
        }
        return out
    }

    /** Splits ID3v2.4 NUL-separated payload into individual values. */
    private fun splitNul(value: String): List<String> = value.split('\u0000')

    /**
     * Reads an arbitrary ID3v2 TXXX frame by description
     * ("artists"/"albumartists"/"label"). Runtime-safe across formats.
     */
    private fun readTxxx(tag: Tag?, description: String): String? {
        val id3 = tag as? AbstractID3v2Tag ?: return null
        return runCatching {
            id3.getFields("TXXX")
                .mapNotNull { field ->
                    val body = (field as? AbstractID3v2Frame)?.body as? FrameBodyTXXX
                        ?: return@mapNotNull null
                    body.takeIf { it.description.equals(description, ignoreCase = true) }?.text
                }
                .firstOrNull { !it.isNullOrBlank() }
        }.getOrNull()
    }

    /**
     * [Master Lyrics Priority] Merges candidates from USLT, SYNCEDLYRICS (Vorbis/TXXX),
     * and raw tags to ensure lyrics are found even in non-standard fields.
     */
    private fun lyricsCandidate(tag: Tag?): String? {
        if (tag == null) return null

        // 1. Standard keys (USLT, UNSYNCEDLYRICS mapping)
        val uslt = allValues(tag, FieldKey.LYRICS).firstOrNull { it.isNotBlank() }
        if (!uslt.isNullOrBlank()) return repaired(uslt)

        val unsynced = fk("UNSYNCEDLYRICS")?.let { allValues(tag, it).firstOrNull { it.isNotBlank() } }
        if (!unsynced.isNullOrBlank()) return repaired(unsynced)

        // 2. Custom TXXX frames common in Picard/Beets or manual tagging
        val txxxLyrics = readTxxx(tag, "LYRICS")
        if (!txxxLyrics.isNullOrBlank()) return repaired(txxxLyrics)

        val txxxSynced = readTxxx(tag, "SYNCEDLYRICS")
        if (!txxxSynced.isNullOrBlank()) return repaired(txxxSynced)

        // 3. Vorbis/FLAC specific raw keys (may not be mapped to FieldKey by jaudiotagger)
        val vorbisLyrics = runCatching { tag.getFirst("LYRICS") }.getOrNull()
        if (!vorbisLyrics.isNullOrBlank()) return repaired(vorbisLyrics)

        val vorbisSynced = runCatching { tag.getFirst("SYNCEDLYRICS") }.getOrNull()
        if (!vorbisSynced.isNullOrBlank()) return repaired(vorbisSynced)

        return null
    }

    /** [V17]: Extract official SYLT (Synchronized Lyrics) binary frame. */
    private fun syltCandidate(tag: Tag?): Pair<ByteArray, Int>? {
        val id3 = tag as? AbstractID3v2Tag ?: return null
        return runCatching {
            val field = id3.getFirstField("SYLT") ?: return@runCatching null
            val body = (field as? AbstractID3v2Frame)?.body as? org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
                ?: return@runCatching null
            
            body.lyrics to body.timeStampFormat
        }.getOrNull()
    }

    /** Runtime-safe FieldKey lookup for constants absent in some versions. */
    private fun fk(name: String): FieldKey? =
        runCatching { FieldKey.valueOf(name) }.getOrNull()

    /** [C10] Choke-point: no string leaves this engine unrepaired. */
    private fun repaired(raw: String?): String? =
        raw?.let { MojibakeRepairer.repair(it) }

    private fun log(kind: String, permanent: Boolean, e: Exception) {
        val msg = "[$kind] tag-read failure: ${e.message}"
        if (permanent) Log.w(TAG, msg) else Log.i(TAG, msg)
    }
}

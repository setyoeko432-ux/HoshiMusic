package com.naomi.hoshimusic.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.naomi.hoshimusic.data.metadata.LrcLoader
import com.naomi.hoshimusic.data.metadata.MagicSniffer
import com.naomi.hoshimusic.data.metadata.MetadataSanitizer
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.data.metadata.TagEngine
import com.naomi.hoshimusic.data.metadata.WavHeaderParser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mesin Pembaca Utama (Core Metadata Reader) V4
 *
 * Orchestrates three independent sources and merges them honestly:
 *   1. TagEngine    — real tags + parsed stream headers (jaudiotagger)
 *                     [A1][B7][B8][C10][D16]
 *   2. MediaMetadataRetriever — container-level hints (fallback only;
 *                     its fake custom keys are GONE per [A1])
 *   3. WavHeaderParser — deep RIFF/RF64 walk [D15]
 *
 * Hallucination policy ([A2]): a value is either READ or NULL. Nothing is
 * ever defaulted to plausible-looking constants. Numeric garbage is dropped
 * by MetadataSanitizer instead of being stored ([B9]).
 */
class AudioMetadataReader(private val context: Context) {

    /** Files larger than this are never copied to cache for tagging. */
    private companion object {
        const val TAG = "HoshiMeta"
        const val TEMP_COPY_LIMIT_BYTES = 64L * 1024 * 1024
        const val BITRATE_MISMATCH_TOLERANCE = 0.25 // [D13]
    }

    suspend fun readDeepMetadata(
        uri: Uri,
        mimeType: String,
        absolutePath: String? = null,
        fileSizeBytes: Long? = null,
        durationMsHint: Long? = null
    ): DeepMetadata = coroutineScope {
        // ---- Source 1: real tags (needs a readable File) --------------------
        val source = resolveReadableFile(uri, absolutePath)
        val tags = try {
            source?.file?.let { TagEngine.read(it) }
        } finally {
            source?.close()
        }

        // [Issue 4]: Lazy probe chain — skip heavy probes if TagEngine succeeded
        val hasEssentialTechnical = tags?.status == ScanStatus.SUCCESS && 
                tags.sampleRate != null && tags.durationMs != null && tags.format != null
        
        val needsFallback = !hasEssentialTechnical
        
        if (!needsFallback) {
            Log.d(TAG, "TagEngine success; skipping heavy probes for ${uri.lastPathSegment}")
        }

        // ---- Source 2: Magic Sniffer (Content-based) ------------------------
        // [Issue 4]: Only sniff if format is unknown or potentially misleading
        val sniffed = if (needsFallback && absolutePath != null) {
            MagicSniffer.detect(File(absolutePath))
        } else null

        // ---- Source 3 & 4: Codec-level probing (Parallel) -------------------
        // [Issue 4]: Parallelize independent probes when needed
        val extractorDeferred = if (needsFallback) async { probeMediaExtractor(uri) } else null
        val mmrDeferred = if (needsFallback) async { probeMediaRetriever(uri) } else null
        
        val extractorInfo = extractorDeferred?.await()
        val mmr = mmrDeferred?.await()

        // ---- Source 5: WAV deep walk ---------------------------------------
        val isWavFamily = needsFallback && (mimeType.contains("wav", true) ||
                mimeType.contains("wave", true) || 
                tags?.format.equals("wav", true) ||
                sniffed?.container == "WAV")

        val wav = if (isWavFamily) {
            WavHeaderParser.parse { runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() }
        } else null

        // ---- Honest merge: read-or-null, never fabricated --------------------
        val sampleRate = wav?.sampleRate
            ?: tags?.sampleRate
            ?: extractorInfo?.sampleRate
            ?: MetadataSanitizer.sanitizeSampleRate(mmr?.sampleRate)

        val bitDepth = wav?.bitDepth
            ?: tags?.bitDepth
            ?: MetadataSanitizer.sanitizeBitDepth(mmr?.bitDepth)

        val channels = wav?.channels
            ?: tags?.channels
            ?: extractorInfo?.channels

        // [A3] Differentiated Format/Codec Identification
        val formatInfo = resolveFormatInfo(
            tags = tags,
            sniffed = sniffed,
            extractor = extractorInfo,
            mmr = mmr,
            mimeType = mimeType,
            extension = absolutePath?.substringAfterLast('.', "")
        )

        // [D13] True average bitrate vs reported bitrate, with tolerance.
        val bitrate = resolveBitrateBps(
            reportedHeaderBps = tags?.bitrateBps,
            reportedContainerBps = MetadataSanitizer.sanitizeBitrateBps(
                extractorInfo?.bitrate ?: mmr?.bitrateBps
            ),
            fileSizeBytes = fileSizeBytes,
            durationMs = MetadataSanitizer.sanitizeDurationMs(
                durationMsHint ?: extractorInfo?.durationMs ?: mmr?.durationMs
            )
        )

        // [D14] Cross-validate durations; disagreement is diagnostic gold.
        crossCheckDuration(uri, extractorInfo?.durationMs ?: mmr?.durationMs, durationMsHint)

        // [Issue 6]: Identify lyrics source and availability
        val embeddedLyrics = tags?.lyrics
        val sidecarLyrics = if (embeddedLyrics == null && absolutePath != null) sidecarLrc(absolutePath) else null
        
        val lyrics = embeddedLyrics ?: sidecarLyrics
        val lyricsSource = when {
            embeddedLyrics != null -> "internal"
            sidecarLyrics != null -> "external"
            else -> null
        }

        val status = tags?.status ?: ScanStatus.PARTIAL
        DeepMetadata(
            status = status,
            failureReason = tags?.failureReason,
            title = tags?.title ?: mmr?.title,
            album = tags?.album ?: mmr?.album,
            artist = tags?.artistPrimary ?: mmr?.artist,
            albumArtist = tags?.albumArtistPrimary ?: mmr?.artist,
            genre = tags?.genrePrimary,
            durationMs = MetadataSanitizer.sanitizeDurationMs(durationMsHint ?: extractorInfo?.durationMs ?: mmr?.durationMs),
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            format = formatInfo.getCanonicalLabel(),
            container = formatInfo.container,
            codec = formatInfo.codec,
            detectionSource = formatInfo.source,
            isFormatVerified = formatInfo.isVerified,
            metadataScanStatus = status,
            metadataFailureReason = tags?.failureReason,
            metadataScannedAt = System.currentTimeMillis(),
            isrc = tags?.isrc,
            recordLabel = tags?.recordLabel,
            composer = tags?.composerPrimary ?: mmr?.composer,
            year = tags?.year,
            bpm = tags?.bpm,
            trackNumber = tags?.trackNumber,
            artists = tags?.artists.orEmpty(),
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            hasLyrics = !lyrics.isNullOrBlank(),
            replayGainTrack = tags?.replayGainTrack,
            replayGainAlbum = tags?.replayGainAlbum,
            replayGainPeak = tags?.replayGainPeak
        )
    }

    private fun resolveFormatInfo(
        tags: TagEngine.RichTags?,
        sniffed: MagicSniffer.SniffResult?,
        extractor: ExtractorInfo?,
        mmr: MmrProbe?,
        mimeType: String,
        extension: String?
    ): AudioFormatInfo {
        // High confidence: TagEngine + Magic Sniffer agreement
        if (tags?.status == ScanStatus.SUCCESS && tags.format != null) {
            val tagFmt = tags.format.uppercase()
            if (sniffed?.container != null && tagFmt.contains(sniffed.container)) {
                return AudioFormatInfo(sniffed.container, sniffed.codec ?: tagFmt, DetectionSource.PARSED_HEADER, true)
            }
        }

        // Mid confidence: Magic Sniffer
        if (sniffed != null) {
            return AudioFormatInfo(sniffed.container, sniffed.codec, DetectionSource.MAGIC_BYTES, true)
        }

        // Low confidence: MediaExtractor Codec MIME
        if (extractor?.mime != null) {
            val codec = when {
                extractor.mime.contains("mp4a", true) -> "AAC"
                extractor.mime.contains("mpeg", true) -> "MP3"
                extractor.mime.contains("alac", true) -> "ALAC"
                else -> extractor.mime.substringAfter("audio/").uppercase()
            }
            return AudioFormatInfo(null, codec, DetectionSource.MEDIA_EXTRACTOR, false)
        }

        // Fallback: Provider MIME or Extension
        val fallback = (mimeType.substringAfter("audio/", "").takeIf { it.isNotBlank() }
            ?: extension?.takeIf { it.isNotBlank() })?.uppercase()
        
        return AudioFormatInfo(
            container = null, 
            codec = fallback, 
            source = if (mimeType.isNotBlank() && mimeType != "application/octet-stream") 
                DetectionSource.PROVIDER_MIME else DetectionSource.FILE_EXTENSION,
            isVerified = false
        )
    }

    // ---- source plumbing ---------------------------------------------------

    private class SourceFile(
        val file: File,
        private val pfd: AutoCloseable? = null,
        private val isTemp: Boolean = false
    ) : AutoCloseable {
        override fun close() {
            runCatching { pfd?.close() }
            if (isTemp) runCatching { file.delete() }
        }
    }

    /**
     * Prefers the real path; falls back to the /proc/self/fd descriptor
     * shortcut, and only copies to cache as a last resort — with a hard
     * size limit so a misnamed multi-GB video can never fill the cache.
     */
    private fun resolveReadableFile(uri: Uri, absolutePath: String?): SourceFile? {
        absolutePath?.let { path ->
            val f = File(path)
            if (f.isFile && f.canRead()) return SourceFile(f)
        }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                tempCopy(uri)?.let { SourceFile(it, isTemp = true) }
            } else {
                if (pfd.statSize > TEMP_COPY_LIMIT_BYTES) {
                    Log.w(TAG, "Skip tagging ${uri.lastPathSegment}: too large (${pfd.statSize}B)")
                    pfd.close()
                    return null
                }
                SourceFile(File("/proc/self/fd/${pfd.fd}"), pfd = pfd)
            }
        } catch (e: Exception) {
            Log.i(TAG, "fd aliasing failed (${e.message}); trying temp copy")
            tempCopy(uri)?.let { SourceFile(it, isTemp = true) }
        }
    }

    private fun tempCopy(uri: Uri): File? {
        val tmp = File(context.cacheDir, "meta_${System.currentTimeMillis()}")
        var success = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> 
                    success = copyLimited(input, output, TEMP_COPY_LIMIT_BYTES)
                }
            }
            if (success) return tmp
        } catch (e: Exception) {
            Log.i(TAG, "Failed to create temp copy for $uri: ${e.message}")
        } finally {
            if (!success) tmp.delete()
        }
        return null
    }

    /** [Priority 3.C] Copy with hard size limit to prevent cache exhaustion. */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > limit) return false
            output.write(buffer, 0, n)
        }
        return true
    }

    private class MmrProbe(
        val title: String?,
        val artist: String?,
        val album: String?,
        val bitrateBps: Int?,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val durationMs: Long?,
        val composer: String?
    )

    private data class ExtractorInfo(
        val mime: String?,
        val sampleRate: Int?,
        val channels: Int?,
        val bitrate: Int?,
        val durationMs: Long?
    )

    private fun probeMediaExtractor(uri: Uri): ExtractorInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return ExtractorInfo(
                        mime = mime,
                        sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null,
                        channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null,
                        bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else null,
                        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.i(TAG, "Extractor probe failed for ${uri.lastPathSegment}: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * [A1] Only REAL MediaMetadataRetriever keys are used now. The old
     * extractMetadata(1000/1001/1002) ISRC/label/BPM calls were invalid
     * (unsupported keys throw or always-null) and have been removed —
     * those fields come exclusively from TagEngine.
     */
    private fun probeMediaRetriever(uri: Uri): MmrProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            } else null
            val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            } else null

            MmrProbe(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                composer = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                }.getOrNull()
            )
        } catch (e: Exception) {
            Log.i(TAG, "MMR probe failed for ${uri.lastPathSegment}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * [D13] VBR containers routinely report wrong/nominal bitrates
     * (see androidx/media PR #348 discussion). Computed average
     * `size × 8 ÷ duration` is ground truth; the reported value wins only
     * when they agree within tolerance.
     */
    private fun resolveBitrateBps(
        reportedHeaderBps: Int?,
        reportedContainerBps: Int?,
        fileSizeBytes: Long?,
        durationMs: Long?
    ): Int? {
        // [A1] Header-level bitrate (JAudiotagger) is most accurate as it
        // usually ignores non-audio metadata overhead (like large covers).
        if (reportedHeaderBps != null && reportedHeaderBps > 0) {
            return MetadataSanitizer.sanitizeBitrateBps(reportedHeaderBps)
        }

        val reported = reportedContainerBps

        val computed = if (fileSizeBytes != null && fileSizeBytes > 0 && durationMs != null && durationMs >= 500) {
            val bps = (fileSizeBytes * 8.0 / (durationMs / 1000.0)).roundToInt()
            MetadataSanitizer.sanitizeBitrateBps(bps)
        } else null

        return when {
            computed == null -> reported
            reported == null -> computed
            abs(computed - reported).toDouble() > BITRATE_MISMATCH_TOLERANCE * max(computed, reported) -> {
                Log.d(TAG, "Bitrate mismatch reported=$reported computed=$computed → trusting computed")
                computed
            }
            else -> reported
        }
    }

    /** [D14] Log-only cross-check; never overwrites MediaStore's duration. */
    private fun crossCheckDuration(uri: Uri, probed: Long?, cached: Long?) {
        val a = probed ?: return
        val b = cached ?: return
        if (b > 0 && abs(a - b) > 0.10 * max(a, b)) {
            Log.w(TAG, "Duration conflict ${uri.lastPathSegment}: extractor=${a}ms mediaStore=${b}ms")
        }
    }

    /** [C11] Sidecar lyrics through the encoding-cascade loader. */
    private fun sidecarLrc(absolutePath: String): String? {
        val audio = File(absolutePath)
        if (!audio.isFile) return null
        val lrc = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")
        return if (lrc.isFile) LrcLoader.loadSmart(lrc) else null
    }

    data class DeepMetadata(
        val status: ScanStatus,
        val failureReason: String? = null,
        val title: String? = null,
        val album: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val durationMs: Long? = null,
        val bitrate: Int? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null,
        val format: String? = null,
        val container: String? = null,
        val codec: String? = null,
        val detectionSource: DetectionSource = DetectionSource.UNKNOWN,
        val isFormatVerified: Boolean = false,
        val metadataScanStatus: ScanStatus = ScanStatus.PENDING,
        val metadataFailureReason: String? = null,
        val metadataScannedAt: Long = 0L,
        val isrc: String? = null,
        val recordLabel: String? = null,
        val composer: String? = null,
        val year: Int? = null,
        val bpm: Int? = null,
        val trackNumber: Int? = null,
        val artists: List<String> = emptyList(),
        val lyrics: String? = null,
        val lyricsSource: String? = null,
        val hasLyrics: Boolean = false,
        val replayGainTrack: Float? = null,
        val replayGainAlbum: Float? = null,
        val replayGainPeak: Float? = null
    )
}

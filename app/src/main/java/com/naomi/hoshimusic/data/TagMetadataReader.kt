package com.naomi.hoshimusic.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.data.metadata.TagEngine
import java.io.File
import java.io.FileOutputStream

/**
 * Thin compatibility facade over [TagEngine] ([A1][A5]).
 *
 * Kept because LyricsRepository consumes it; all real parsing lives in
 * TagEngine so MP3/FLAC/Ogg/MP4 paths cannot drift apart. The old inline
 * jaudiotagger calls here silently swallowed every exception — failures are
 * now classified and logged upstream.
 */
object TagMetadataReader {

    private const val TAG = "HoshiMeta"

    /** Files larger than this are never copied to cache for tagging. */
    private const val TEMP_COPY_LIMIT_BYTES = 64L * 1024 * 1024

    fun read(context: Context, uri: Uri): TagMetadata? {
        val source = resolveFile(context, uri) ?: return null
        return try {
            val tags = TagEngine.read(source.file)
            when (tags.status) {
                ScanStatus.TRANSIENT_FAILURE,
                ScanStatus.PERMANENT_FAILURE -> null.also {
                    Log.i(TAG, "Deep tag read failed (${tags.failureReason})")
                }
                else -> TagMetadata(
                    lyrics = tags.lyrics,
                    syltBytes = tags.syltBytes,
                    syltTimestampFormat = tags.syltTimestampFormat,
                    isrc = tags.isrc,
                    label = tags.recordLabel,
                    bpm = tags.bpm,
                    composer = tags.composerPrimary,
                    format = tags.format,
                    sampleRate = tags.sampleRate,
                    bitDepth = tags.bitDepth,
                    channels = tags.channels
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "TagMetadataReader failure: ${e.message}")
            null
        } finally {
            source.close()
        }
    }

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

    /** Same descriptor-aliasing strategy as AudioMetadataReader, size-guarded. */
    private fun resolveFile(context: Context, uri: Uri): SourceFile? {
        // 1) Descriptor aliasing (no copy at all).
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                if (pfd.statSize > TEMP_COPY_LIMIT_BYTES) {
                    Log.w(TAG, "Skip tagging: file too large (${pfd.statSize} bytes)")
                    pfd.close()
                    return null
                }
                return SourceFile(File("/proc/self/fd/${pfd.fd}"), pfd = pfd)
            }
        } catch (_: Exception) {
            // fall through to temp copy
        }

        // 2) Bounded cache copy for SAF-only providers.
        val extension = "audio" // Use generic audio extension if we can't get it cheaply
        val tmp = File(context.cacheDir, "tag_tmp_${System.currentTimeMillis()}.$extension")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> 
                    // Active byte-count limit during copy
                    input.copyLimited(output, TEMP_COPY_LIMIT_BYTES)
                }
            } ?: run { tmp.delete(); return null }
            SourceFile(tmp, isTemp = true)
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** Copy with active byte limit. Throws if limit exceeded. */
    private fun java.io.InputStream.copyLimited(out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        var bytes = read(buffer)
        while (bytes >= 0) {
            total += bytes
            if (total > limit) throw java.io.IOException("File too large for metadata extraction")
            out.write(buffer, 0, bytes)
            bytes = read(buffer)
        }
    }

    data class TagMetadata(
        val lyrics: String? = null,
        val syltBytes: ByteArray? = null,
        val syltTimestampFormat: Int? = null,
        val isrc: String? = null,
        val label: String? = null,
        val bpm: Int? = null,
        val composer: String? = null,
        val format: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null
    )
}

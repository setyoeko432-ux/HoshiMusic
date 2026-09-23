package com.naomi.hoshimusic.data.metadata

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * [D15] Robust RIFF/WAVE header parser.
 *
 * Upgrades over the previous inline parser:
 *  - WAVE_FORMAT_EXTENSIBLE (40-byte fmt chunk): reads the real format code
 *    from the SubFormat GUID when wFormatTag == 0xFFFE.
 *  - RF64/BW64 (files > 4 GB): parses the ds64 chunk for real data size.
 *  - Bounds guards: chunk-size sanity cap, chunk-iteration cap, strict full
 *    reads, EOF-safe skipping — a malformed file can no longer loop or lie.
 *
 * All multi-byte fields here are LITTLE-ENDIAN (RIFF convention).
 */
internal object WavHeaderParser {

    private const val CHUNK_SANITY_CAP = 64L * 1024 * 1024   // no legit header chunk is bigger
    private const val MAX_CHUNKS = 10_000                    // pathological-file guard

    /** PCM format codes (fmt chunk / extensible SubFormat GUID prefix). */
    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    data class WavInfo(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val formatCode: Int,
        val dataSizeBytes: Long?   // null unless RF64 ds64 provided it
    )

    /** Walks the RIFF chunk list from the given stream opener; null for non-WAV/malformed files. */
    fun parse(openStream: () -> InputStream?): WavInfo? {
        return try {
            openStream()?.use { input -> walk(input) }
        } catch (_: Exception) {
            null
        }
    }

    private fun walk(input: InputStream): WavInfo? {
        val riff = ByteArray(12)
        if (!input.readFully(riff)) return null

        val riffId = String(riff, 0, 4, Charsets.US_ASCII)
        val riffType = String(riff, 8, 4, Charsets.US_ASCII)
        val isRf64 = riffId == "RF64"
        if (!isRf64 && riffId != "RIFF") return null
        if (!isRf64 && riffType != "WAVE") return null

        var fmt: FmtChunk? = null
        var rf64DataSize: Long? = null

        val header = ByteArray(8)
        var chunks = 0
        while (chunks++ < MAX_CHUNKS) {
            if (!input.readFully(header)) break
            val chunkId = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            // Sanity gate: header chunks are tiny; absurd sizes mean corruption.
            if (size > CHUNK_SANITY_CAP) return null

            when {
                isRf64 && chunkId == "ds64" && size >= 28 -> {
                    // ds64 layout: 8B RIFF size, 8B data size, 8B sample count...
                    // We only need the 8-byte data size at offset 8.
                    val buf = ByteArray(16)
                    if (!input.readFully(buf)) return null
                    rf64DataSize = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getLong(8)
                    
                    if (!input.skipFully(size - 16)) return null
                    skipPadding(input, size)
                }
                chunkId == "fmt " && size >= 16 -> {
                    // fmt chunk is typically 16-40 bytes. Even with a 64MB cap, 
                    // we only read what we actually need to avoid OOM on corrupt files.
                    val readSize = min(size.toInt(), 40)
                    val body = ByteArray(readSize)
                    if (!input.readFully(body)) return null
                    fmt = parseFmt(body) ?: return null

                    if (!input.skipFully(size - readSize)) return null
                    skipPadding(input, size)
                }
                chunkId == "data" -> {
                    val f = fmt ?: return null             // data before fmt → malformed
                    return WavInfo(
                        sampleRate = f.sampleRate,
                        bitDepth = f.bitDepth,
                        channels = f.channels,
                        formatCode = f.formatCode,
                        dataSizeBytes = rf64DataSize ?: size
                    )
                }
                else -> {
                    // Unknown chunk: skip payload (+ pad byte for odd sizes).
                    if (!input.skipFully(min(size + (size and 1), CHUNK_SANITY_CAP))) return null
                }
            }
        }
        return null
    }

    private fun parseFmt(body: ByteArray): FmtChunk? {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val audioFormat = buf.short.toInt() and 0xFFFF      // offset 0
        val channels = buf.getShort(2).toInt() and 0xFFFF   // offset 2
        val sampleRate = buf.getInt(4)                       // offset 4
        val bitDepth = buf.getShort(14).toInt() and 0xFFFF  // offset 14

        var code = audioFormat
        if (audioFormat == FORMAT_EXTENSIBLE && body.size >= 40) {
            // cbSize@16, validBits@18, channelMask@20, SubFormat GUID@24.
            // First two GUID bytes (LE) carry the real format code.
            code = buf.getShort(24).toInt() and 0xFFFF
        }

        if (channels !in MetadataSanitizer.CHANNELS_MIN..MetadataSanitizer.CHANNELS_MAX) return null
        return FmtChunk(sampleRate, bitDepth, channels, code)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        if (size % 2 == 1L) input.skipFully(1)
    }

    private data class FmtChunk(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val formatCode: Int
    )

    // ---- stream helpers --------------------------------------------------

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) return false
            total += n
        }
        return true
    }

    private fun InputStream.skipFully(bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0) {
            val n = skip(remaining)
            if (n <= 0) return false
            remaining -= n
        }
        return true
    }
}

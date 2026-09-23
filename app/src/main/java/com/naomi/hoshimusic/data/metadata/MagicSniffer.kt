package com.naomi.hoshimusic.data.metadata

import android.util.Log
import org.jaudiotagger.audio.SupportedFileFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * [A4] Content-sniffs a file's magic bytes to determine its REAL audio container,
 * independent of the filename extension.
 */
internal object MagicSniffer {

    private const val TAG = "HoshiMeta"
    private const val PREFIX_LEN = 256
    private const val EXTENDED_PREFIX_LEN = 8192

    // Phase 6: Pre-computed byte constants
    private val MAGIC_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val MAGIC_RF64 = "RF64".toByteArray(Charsets.US_ASCII)
    private val MAGIC_BW64 = "BW64".toByteArray(Charsets.US_ASCII)
    private val MAGIC_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MAGIC_OGGS = "OggS".toByteArray(Charsets.US_ASCII)
    private val MAGIC_ID3  = "ID3".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    data class SniffResult(
        val container: String,
        val codec: String? = null,
        val jaudiotaggerFormat: SupportedFileFormat? = null
    )

    fun detect(file: File): SniffResult? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var buf = ByteArray(PREFIX_LEN)
                var read = raf.read(buf)
                if (read < 12) return null
                
                var result = detect(buf, read)
                
                // [A7] If ambiguous (ID3 but no frame sync), try larger buffer
                if (result == null && read >= 10 && String(buf, 0, 3, Charsets.US_ASCII) == "ID3") {
                    buf = ByteArray(EXTENDED_PREFIX_LEN)
                    raf.seek(0)
                    read = raf.read(buf)
                    result = detect(buf, read)
                }
                result
            }
        } catch (e: Exception) {
            Log.d(TAG, "Sniff failed for ${file.name}: ${e.message}")
            null
        }
    }

    fun detect(b: ByteArray, length: Int): SniffResult? {
        // Phase 6: Use pre-computed constants and avoid string allocation
        val match = { off: Int, pattern: ByteArray ->
            if (off >= 0 && off + pattern.size <= length) {
                var equal = true
                for (j in pattern.indices) {
                    if (b[off + j] != pattern[j]) {
                        equal = false
                        break
                    }
                }
                equal
            } else false
        }

        // 1. RIFF/WAV Family
        if ((match(0, MAGIC_RIFF) || match(0, MAGIC_RF64) || match(0, MAGIC_BW64)) && match(8, MAGIC_WAVE)) {
            return SniffResult("WAV", "PCM", SupportedFileFormat.WAV)
        }

        // 2. FLAC
        if (match(0, MAGIC_FLAC)) {
            return SniffResult("FLAC", "FLAC", SupportedFileFormat.FLAC)
        }

        // 3. OGG
        if (match(0, MAGIC_OGGS)) {
            val codec = when {
                contains(b, "OpusHead", 28, 100) -> "OPUS"
                contains(b, "\u0001vorbis", 28, 100) -> "VORBIS"
                contains(b, "fLaC", 28, 100) -> "FLAC"
                contains(b, "Speex", 28, 100) -> "SPEEX"
                else -> null
            }
            return SniffResult("OGG", codec, SupportedFileFormat.OGG)
        }

        // 4. MP4 / M4A
        if (match(4, MAGIC_FTYP)) {
            return SniffResult("MP4", jaudiotaggerFormat = SupportedFileFormat.MP4)
        }
        
        // ... rest of AIFF/DSD/ASF logic remains similar ...
        val ascii = { off: Int, len: Int ->
            if (off >= 0 && off + len <= length) String(b, off, len, Charsets.US_ASCII) else ""
        }
        
        val chunk0 = if (length >= 4) ascii(0, 4) else ""

        // 5. AIFF
        if (chunk0 == "FORM" && (ascii(8, 4) == "AIFF" || ascii(8, 4) == "AIFC")) {
            return SniffResult("AIFF", "PCM", SupportedFileFormat.AIF)
        }

        // 6. DSD
        if (chunk0 == "DSD ") return SniffResult("DSF", "DSD", matchByName("DSF"))
        if (chunk0 == "FRM8") return SniffResult("DFF", "DSD", matchByName("DFF"))

        // 7. WMA / ASF
        if ((b[0].toInt() and 0xFF) == 0x30 && (b[1].toInt() and 0xFF) == 0x26 &&
            (b[2].toInt() and 0xFF) == 0xB2 && (b[3].toInt() and 0xFF) == 0x75) {
            return SniffResult("ASF", "WMA", SupportedFileFormat.WMA)
        }

        // 8. MP3 / AAC ADTS (Check after ID3 if present)
        var offset = 0
        if (match(0, MAGIC_ID3)) {
            // [B3] ID3 prefix does NOT prove MP3. Skip and check frame.
            if (length > 10) {
                val s0 = b[6].toInt() and 0x7F
                val s1 = b[7].toInt() and 0x7F
                val s2 = b[8].toInt() and 0x7F
                val s3 = b[9].toInt() and 0x7F
                val tagSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                offset = 10 + tagSize
            }
        }

        val syncLimit = length - 2
        for (i in offset..minOf(offset + 4096, syncLimit)) {
            if (i < 0 || i >= syncLimit) break
            val b0 = b[i].toInt() and 0xFF
            val b1 = b[i + 1].toInt() and 0xFF
            
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                // Potential MPEG / ADTS
                val isAdts = (b1 and 0xF6) == 0xF0 // 1111 0..0 (MPEG-4 or MPEG-2 AAC)
                if (isAdts) return SniffResult("AAC", "AAC")
                
                // MPEG Audio (MP3/MP2)
                val layer = (b1 shr 1) and 0x03
                if (layer != 0) { // Layer 0 is reserved
                    return SniffResult("MP3", "MP3", SupportedFileFormat.MP3)
                }
            }
        }

        // Fallback guess if ID3 found but frame sync beyond buffer
        if (ascii(0, 3) == "ID3") {
             return SniffResult("MP3", "MP3", SupportedFileFormat.MP3)
        }

        return null
    }

    private fun contains(b: ByteArray, pattern: String, start: Int, end: Int): Boolean {
        val p = pattern.toByteArray(Charsets.US_ASCII)
        val limit = minOf(b.size - p.size, end)
        for (i in start..limit) {
            var match = true
            for (j in p.indices) {
                if (b[i + j] != p[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun matchByName(name: String): SupportedFileFormat? =
        SupportedFileFormat.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
}

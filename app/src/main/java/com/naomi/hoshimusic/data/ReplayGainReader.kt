package com.naomi.hoshimusic.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * [Priority 6] Reader for ReplayGain tags with caching to reduce song transition latency.
 */
class ReplayGainReader(private val context: Context) {
    
    // [Priority 6.3] Small LRU cache to avoid re-reading recently played files
    private val cache = LruCache<String, ReplayGainInfo>(32)
    private val NONE = ReplayGainInfo(null, null, null)

    suspend fun read(uri: Uri): ReplayGainInfo? = withContext(Dispatchers.IO) {
        val cacheKey = uri.toString()
        val cached = cache.get(cacheKey)
        if (cached === NONE) return@withContext null
        if (cached != null) return@withContext cached

        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                // [Priority 6.4] Only read necessary header portion
                while (total < MAX_SCAN_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_SCAN_BYTES - total))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray()
            } ?: return@runCatching null

            // Minimal allocation strategy: convert once to a ISO_8859_1 string for regex
            val searchable = bytes.toString(Charsets.ISO_8859_1)
                .replace("\u0000", "")
                .replace(',', '.')
            
            val track = TRACK_GAIN.find(searchable)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val album = ALBUM_GAIN.find(searchable)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val peak = TRACK_PEAK.find(searchable)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: ALBUM_PEAK.find(searchable)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            
            val info = if (track == null && album == null) {
                NONE
            } else {
                ReplayGainInfo(track, album, peak)
            }
            cache.put(cacheKey, info)
            if (info === NONE) null else info
        }.getOrNull()
    }

    private companion object {
        const val MAX_SCAN_BYTES = 1 * 1024 * 1024 // [Priority 6] Reduced from 2MB to 1MB
        val TRACK_GAIN = Regex("(?i)REPLAYGAIN_TRACK_GAIN[^+\\-0-9]{0,48}([+\\-]?\\d+(?:\\.\\d+)?)")
        val ALBUM_GAIN = Regex("(?i)REPLAYGAIN_ALBUM_GAIN[^+\\-0-9]{0,48}([+\\-]?\\d+(?:\\.\\d+)?)")
        val TRACK_PEAK = Regex("(?i)REPLAYGAIN_TRACK_PEAK[^0-9]{0,48}(\\d+(?:\\.\\d+)?)")
        val ALBUM_PEAK = Regex("(?i)REPLAYGAIN_ALBUM_PEAK[^0-9]{0,48}(\\d+(?:\\.\\d+)?)")
    }
}

package com.naomi.hoshimusic.data

import androidx.compose.runtime.Immutable

@Immutable
enum class DetectionSource {
    PARSED_HEADER,
    MAGIC_BYTES,
    MEDIA_EXTRACTOR,
    PROVIDER_MIME,
    FILE_EXTENSION,
    UNKNOWN
}

@Immutable
data class AudioFormatInfo(
    val container: String?,
    val codec: String?,
    val source: DetectionSource = DetectionSource.UNKNOWN,
    val isVerified: Boolean = false
) {
    fun getCanonicalLabel(): String {
        val ctn = container?.uppercase()
        val cdc = codec?.uppercase()

        return when {
            ctn == "FLAC" -> "FLAC"
            (ctn == "MPEG_AUDIO" || ctn == "MP3") && cdc == "MP3" -> "MP3"
            ctn == "WAV" -> "WAV • ${cdc ?: "PCM"}"
            ctn == "OGG" -> "OGG • ${cdc ?: "VORBIS"}"
            (ctn == "MP4" || ctn == "M4A") -> "M4A • ${cdc ?: "AAC"}"
            ctn == "AIFF" -> "AIFF"
            ctn == "ASF" || ctn == "WMA" -> "WMA"
            ctn == "DSF" -> "DSF"
            ctn == "DFF" -> "DFF"
            else -> buildString {
                append(ctn ?: "")
                if (!ctn.isNullOrBlank() && !cdc.isNullOrBlank()) append(" • ")
                append(cdc ?: "")
            }.ifBlank { "UNKNOWN" }
        }
    }
}

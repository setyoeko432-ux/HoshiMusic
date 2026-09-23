package com.naomi.hoshimusic.data

import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.data.lrc.LrcConstants.INSTRUMENTAL_MARKER
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.LINE_TIME_REGEX
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.OFFSET_REGEX
import com.naomi.hoshimusic.data.lrc.LrcNormalizer.METADATA_REGEX
import com.naomi.hoshimusic.data.lrc.LrcNormalizer.GENDER_MARKERS_REGEX

object LrcParser {
    const val INSTRUMENTAL = INSTRUMENTAL_MARKER

    data class LrcParseResult(
        val lines: List<LyricLine>,
        val metadata: Map<String, String>,
        val offsetMs: Long
    )

    fun parse(lrcContent: String): List<LyricLine> = parseDetailed(lrcContent).lines

    fun parseDetailed(lrcContent: String): LrcParseResult {
        val metadata = LinkedHashMap<String, String>()
        val parsedLines = mutableListOf<LyricLine>()

        val offsetMs = OFFSET_REGEX.find(lrcContent)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sanitizedContent = lrcContent.replace("\uFEFF", "")

        sanitizedContent.lineSequence().forEach { raw ->
            val line = raw.trim()
            METADATA_REGEX.find(line)?.let { m ->
                val key = m.groupValues[1].lowercase()
                val value = line.substringAfter(':').trimEnd(']').trim()
                if (key != "offset" && value.isNotEmpty()) metadata[key] = value
            }
            if (line.isEmpty() || METADATA_REGEX.matches(line)) return@forEach

            val lineTimestamps = mutableListOf<Long>()
            var contentStart = 0
            while (true) {
                val match = LINE_TIME_REGEX.find(line, contentStart)
                if (match != null && match.range.first == contentStart) {
                    lineTimestamps.add(LrcTimestampParser.parseTimestamp(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
                    contentStart = match.range.last + 1
                } else {
                    break
                }
            }

            if (lineTimestamps.isEmpty()) return@forEach

            val rawRemaining = line.substring(contentStart).replace(Regex("[\\s\\u00A0]+"), " ")
            val (orphanCleaned, _) = LrcNormalizer.stripOrphanTags(rawRemaining)
            val remainingText = orphanCleaned.replace(Regex("[\\s\\u00A0]+"), " ").trim()

            if (remainingText.startsWith("/") &&
                remainingText.trimStart('/').replace(Regex("[\\s\\u00A0]+"), " ").trim().isNotEmpty()
            ) {
                val translationText = remainingText.substring(1).trim()
                lineTimestamps.forEach { lineTs ->
                    val effectiveTs = (lineTs + offsetMs).coerceAtLeast(0L)
                    parsedLines.indices.filter { 
                        parsedLines[it].timestampMs == effectiveTs && parsedLines[it].text != INSTRUMENTAL 
                    }.forEach { idx ->
                        if (parsedLines[idx].translation == null) {
                            parsedLines[idx] = parsedLines[idx].copy(translation = translationText)
                        }
                    }
                }
                return@forEach
            }

            if (remainingText.isEmpty() || LrcNormalizer.isInstrumental(remainingText)) {
                lineTimestamps.forEach { lineTs ->
                    parsedLines.add(
                        LyricLine(
                            timestampMs = (lineTs + offsetMs).coerceAtLeast(0L),
                            text = if (remainingText.isEmpty()) "" else INSTRUMENTAL,
                            tokens = emptyList(),
                            agent = null,
                        )
                    )
                }
                return@forEach
            }

            val genderMatch = GENDER_MARKERS_REGEX.find(remainingText)
            val agent = genderMatch?.value?.firstOrNull()?.uppercaseChar()?.toString()
            val strippedMarkers = remainingText.replace(GENDER_MARKERS_REGEX, "")

            lineTimestamps.forEach { lineTs ->
                parsedLines.add(
                    LyricLine(
                        timestampMs = (lineTs + offsetMs).coerceAtLeast(0L),
                        text = strippedMarkers,
                        tokens = emptyList(),
                        agent = agent,
                    )
                )
            }
        }

        val sorted = parsedLines.sortedBy { it.timestampMs }
        return LrcParseResult(
            lines = sorted,
            metadata = metadata,
            offsetMs = offsetMs
        )
    }
}

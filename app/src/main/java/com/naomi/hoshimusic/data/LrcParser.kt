package com.naomi.hoshimusic.data

import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.data.lrc.LrcConstants.PURE_INSTRUMENTAL_THRESHOLD_MS
import com.naomi.hoshimusic.data.lrc.LrcConstants.MIN_SAFETY_MARGIN_MS
import com.naomi.hoshimusic.data.lrc.LrcConstants.MIN_LINE_GAP_PROPORTION
import com.naomi.hoshimusic.data.lrc.LrcConstants.SUSTAIN_PRONE_MAX_MS
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.LINE_TIME_REGEX
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.TOKEN_TIME_REGEX
import com.naomi.hoshimusic.data.lrc.LrcTimestampParser.OFFSET_REGEX
import com.naomi.hoshimusic.data.lrc.LrcNormalizer.METADATA_REGEX
import com.naomi.hoshimusic.data.lrc.LrcNormalizer.GENDER_MARKERS_REGEX
import com.naomi.hoshimusic.data.metadata.LrcCalibrationEngine
import com.naomi.hoshimusic.data.metadata.LrcInstrumentalEngine
import com.naomi.hoshimusic.util.RomanizationLanguage

object LrcParser {

    const val INSTRUMENTAL_MARKER = LrcConstants.INSTRUMENTAL_MARKER

    // [V33]: Parser version for cache invalidation
    const val VERSION = 3

    fun isInstrumental(text: String): Boolean = LrcNormalizer.isInstrumental(text)

    data class LrcParseResult(
        val lines: List<LyricLine>,
        val metadata: Map<String, String>,
        val offsetMs: Long,
        val warnings: List<String> = emptyList(),
    )

    fun parse(lrcContent: String, dedupeExactDuplicates: Boolean = false, lang: RomanizationLanguage = RomanizationLanguage.UNKNOWN): List<LyricLine> =
        parseDetailed(lrcContent, dedupeExactDuplicates, lang).lines

    fun parseDetailed(lrcContent: String, dedupeExactDuplicates: Boolean = false, lang: RomanizationLanguage = RomanizationLanguage.UNKNOWN): LrcParseResult {
        val metadata = LinkedHashMap<String, String>()
        val warnings = mutableListOf<String>()
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
            val (orphanCleaned, orphanHits) = LrcNormalizer.stripOrphanTags(rawRemaining)
            if (orphanHits > 0 && warnings.size < 20) {
                warnings.add("orphan-tag x$orphanHits @${rawRemaining.take(24)}")
            }
            val remainingText = orphanCleaned.replace(Regex("[\\s\\u00A0]+"), " ").trim()

            if (remainingText.startsWith("/") &&
                remainingText.trimStart('/').replace(Regex("[\\s\\u00A0]+"), " ").trim().isNotEmpty()
            ) {
                val translationText = remainingText.substring(1).trim()
                lineTimestamps.forEach { lineTs ->
                    val effectiveTs = (lineTs + offsetMs).coerceAtLeast(0L)
                    // [8c] Bilingual Matcher: Berikan terjemahan ke SEMUA baris pada timestamp yang sama
                    // (Handle duet/multi-vokalis tanpa kehilangan teks terjemahan)
                    parsedLines.indices.filter { 
                        parsedLines[it].timestampMs == effectiveTs && parsedLines[it].text != INSTRUMENTAL_MARKER 
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
                            text = if (remainingText.isEmpty()) "" else INSTRUMENTAL_MARKER,
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

            if (strippedMarkers.isBlank()) {
                lineTimestamps.forEach { lineTs ->
                    parsedLines.add(
                        LyricLine(
                            timestampMs = (lineTs + offsetMs).coerceAtLeast(0L),
                            text = INSTRUMENTAL_MARKER,
                            tokens = emptyList(),
                            agent = agent,
                        )
                    )
                }
                return@forEach
            }
            val hasRomaji = strippedMarkers.contains("|")

            val textForTokens = if (hasRomaji) strippedMarkers.substringBefore("|").trim() else strippedMarkers
            val romajiPart = if (hasRomaji) strippedMarkers.substringAfter("|").trim() else ""

            val hasEnhancedTimestamps = textForTokens.contains('<')

            val shiftedLineTimestamps = lineTimestamps.map { (it + offsetMs).coerceAtLeast(0L) }
            shiftedLineTimestamps.forEach { lineTs ->
                val lineTokens = mutableListOf<LyricToken>()

                if (hasEnhancedTimestamps) {
                    val parts = textForTokens.split(TOKEN_TIME_REGEX)
                    val tsMatches = TOKEN_TIME_REGEX.findAll(textForTokens).toList()
                    val rawTokens = mutableListOf<Triple<Long, String, String>>()

                    if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                        val clean = parts[0].replace(Regex("[\\s\\u00A0]+"), " ").trim()
                        if (clean.isNotEmpty()) {
                            rawTokens.add(Triple(lineTs, clean, parts[0]))
                        }
                    }

                    tsMatches.forEachIndexed { i, match ->
                        val tokenTs = LrcTimestampParser.parseTimestamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                        val shiftedTs = (tokenTs + offsetMs).coerceAtLeast(0L)
                        val rawToken = if (i + 1 < parts.size) parts[i + 1] else ""
                        val cleanToken = rawToken.replace(Regex("[\\s\\u00A0]+"), " ").trim()

                        if (cleanToken.isNotEmpty()) {
                            rawTokens.add(Triple(shiftedTs, cleanToken, rawToken))
                        }
                    }

                    rawTokens.forEachIndexed { index, (ts, text, raw) ->
                        val isLast = index == rawTokens.size - 1
                        val nextEntry = if (!isLast) rawTokens[index + 1] else null
                        val nextText = nextEntry?.second ?: ""

                        val endsWithHyphen = text.endsWith("-") || text.endsWith("(")
                        val isJapaneseOrCjk = text.lastOrNull()?.let { LrcNormalizer.isCjk(it) } == true
                        val nextIsCjk = nextText.firstOrNull()?.let { LrcNormalizer.isCjk(it) } == true

                        val joinMidWord = nextEntry != null &&
                                raw.lastOrNull()?.isWhitespace() != true &&
                                nextEntry.third.firstOrNull()?.isWhitespace() != true &&
                                text.lastOrNull()?.isLetterOrDigit() == true &&
                                nextText.firstOrNull()?.isLetterOrDigit() == true

                        val formattedText = when {
                            isLast -> text
                            joinMidWord -> text
                            !endsWithHyphen && !(isJapaneseOrCjk && nextIsCjk) -> "$text "
                            else -> text
                        }

                        lineTokens.add(LyricToken(ts, formattedText))
                    }
                }

                val baseText = if (lineTokens.isEmpty()) {
                    textForTokens.replace(TOKEN_TIME_REGEX, "").replace(Regex("[\\s\\u00A0]+"), " ").trim()
                } else {
                    lineTokens.joinToString("") { it.text }.trim()
                }

                val fullText = if (hasRomaji && romajiPart.isNotEmpty()) {
                    "$baseText|$romajiPart"
                } else {
                    baseText
                }

                parsedLines.add(LyricLine(timestampMs = lineTs, text = fullText, tokens = lineTokens, agent = agent))
            }
        }

        if (parsedLines.isEmpty() && sanitizedContent.isNotBlank()) {
            sanitizedContent.lineSequence()
                .filter { it.isNotBlank() && !METADATA_REGEX.matches(it) }
                .forEachIndexed { index, text ->
                    parsedLines.add(
                        LyricLine(
                            timestampMs = index.toLong(),
                            text = text.trim(),
                            tokens = emptyList(),
                            agent = null
                        )
                    )
                }
        }

        val sortedRaw = parsedLines.sortedBy { it.timestampMs }
        if (sortedRaw.isEmpty()) return LrcParseResult(emptyList(), metadata, offsetMs, warnings)

        val processedLines = sortedRaw.map { line ->
            val cleanText = line.text.replace("\uE000", "").trim()
            val type = when {
                LrcNormalizer.isInstrumental(cleanText) -> LyricAnnotationType.EXPLICIT_INSTRUMENTAL
                LrcNormalizer.isSectionLabel(cleanText) -> LyricAnnotationType.SECTION_LABEL
                LrcNormalizer.isSeparator(cleanText) || cleanText.isEmpty() -> LyricAnnotationType.SEPARATOR
                else -> LyricAnnotationType.VOCAL
            }
            line.copy(
                text = cleanText,
                annotationType = type
            )
        }

        val processed = LrcInstrumentalEngine.analyzeAndMark(processedLines, lang)

        return LrcParseResult(
            lines = attachTokenEndMs(processed, lang),
            metadata = metadata,
            offsetMs = offsetMs,
            warnings = warnings,
        )
    }

    private fun attachTokenEndMs(lines: List<LyricLine>, lang: RomanizationLanguage): List<LyricLine> =
        lines.mapIndexed { idx, line ->
            if (line.tokens.isEmpty()) return@mapIndexed line
            val nextStart = lines.getOrNull(idx + 1)?.timestampMs
            val lastToken = line.tokens.last()
            
            // [8d] Linguistic Timing: Gunakan durasi per-bahasa (Audit P5)
            val baseMsPerSyllable = LrcCalibrationEngine.getSyllableDuration(lang)
            val cleanWord = lastToken.text.trim()
            val syllables = LrcVocalTiming.countSyllables(cleanWord).coerceAtLeast(1)
            
            val baseDuration = (syllables * baseMsPerSyllable).coerceIn(1_500L, 4_500L)
            val estimate = lastToken.timestampMs +
                (if (LrcVocalTiming.isSustainProne(cleanWord)) minOf(baseDuration * 2, SUSTAIN_PRONE_MAX_MS) else baseDuration)
            
            val lineEnd = minOf(nextStart ?: Long.MAX_VALUE, maxOf(estimate, lastToken.timestampMs))
            val toks = line.tokens.mapIndexed { i, t ->
                val end = if (i == line.tokens.lastIndex) lineEnd else line.tokens[i + 1].timestampMs
                t.copy(endMs = end.coerceAtLeast(t.timestampMs))
            }
            line.copy(tokens = toks)
        }
}

package com.naomi.hoshimusic.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.naomi.hoshimusic.data.db.HoshiDatabase
import androidx.media3.common.util.UnstableApi
import com.naomi.hoshimusic.data.db.LyricsCacheEntity
import com.naomi.hoshimusic.data.metadata.LrcLoader
import com.naomi.hoshimusic.util.HoshiRomanizer
import android.util.LruCache
import com.naomi.hoshimusic.util.NonLexicalVocalizationDetector
import com.naomi.hoshimusic.util.RomajiHelper
import com.naomi.hoshimusic.util.RomanizationLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LyricsRepository (V10 - With Romanization & Translation)
 */
@UnstableApi
class LyricsRepository(private val context: Context) {

    private val db = HoshiDatabase.getDatabase(context)
    private val lyricsDao = db.lyricsDao()
    private val overrideDao = db.translationOverrideDao()

    // [V17]: In-memory cache for parsed lyrics results to speed up UI switching
    private val memoryCache = LruCache<String, LyricsResult>(256)

    suspend fun saveTranslationOverride(mediaId: String, timestampMs: Long, text: String) {
        if (text.isBlank()) {
            overrideDao.deleteOverride(mediaId, timestampMs)
        } else {
            overrideDao.saveOverride(com.naomi.hoshimusic.data.db.TranslationOverrideEntity(mediaId, timestampMs, text))
        }
        // [V16]: Invalidate cache to force a fresh merge of the correction
        lyricsDao.deleteLyrics(mediaId)
        memoryCache.evictAll() // Clear all to be safe, or we could filter by mediaId prefix
    }

    suspend fun getTranslationOverrides(mediaId: String) = overrideDao.getOverridesForSong(mediaId)

    suspend fun load(
        song: Song, 
        priority: LyricSourcePriority = LyricSourcePriority.EXTERNAL_FIRST, 
        romanize: Boolean = true,
        useTranslation: Boolean = false
    ): LyricsResult = withContext(Dispatchers.IO) {
        val sidecarData = findSidecarData(song, song.displayName.substringBeforeLast('.') + ".lrc")
        val sidecarModified = sidecarData?.second ?: 0L
        val translationVersion = 1
        
        val cacheKey = "${song.stableId}_${priority.name}_${romanize}_${useTranslation}_m${sidecarModified}_tr${translationVersion}_v${HoshiRomanizer.VERSION}_p${LrcParser.VERSION}"
        
        // Check memory cache first
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // [V16]: Refactor to allow override merge even on cache hits
        val cached = lyricsDao.getLyrics(cacheKey)
        
        var result: LyricsResult? = if (cached != null && cached.lastUpdatedMs >= song.dateAddedMs) {
            LyricsResult(
                lines = jsonToLines(cached.linesJson),
                plainText = cached.plainText,
                sourceName = cached.sourceName
            )
        } else null

        if (result == null) {
            val sidecarName = song.displayName.substringBeforeLast('.') + ".lrc"
            
            if (priority == LyricSourcePriority.EXTERNAL_FIRST) {
                val content = sidecarData?.first?.let(::readText) ?: loadLegacyExternal(song, sidecarName)
                if (!content.isNullOrBlank()) {
                    val lines = LrcParser.parse(content, lang = song.detectedLanguage)
                    result = LyricsResult(
                        lines = lines,
                        plainText = lines.joinToString("\n") { it.text },
                        sourceName = sidecarName
                    )
                } else {
                    result = loadDeepMetadata(song)
                }
            } else {
                result = loadDeepMetadata(song)
                if (result == null || result!!.lines.isEmpty()) {
                    val content = sidecarData?.first?.let(::readText) ?: loadLegacyExternal(song, sidecarName)
                    if (!content.isNullOrBlank()) {
                        val lines = LrcParser.parse(content, lang = song.detectedLanguage)
                        result = LyricsResult(
                            lines = lines,
                            plainText = lines.joinToString("\n") { it.text },
                            sourceName = sidecarName
                        )
                    }
                }
            }

            if (result == null) {
                result = LyricsResult(sourceName = null)
            } else {
                // [1b] Fallback deteksi bahasa dari lirik jika metadata masih UNKNOWN
                val effectiveLang = if (song.detectedLanguage == RomanizationLanguage.UNKNOWN) {
                    HoshiRomanizer.detectLyricsLanguage(result!!.plainText)
                } else song.detectedLanguage

                if (romanize && HoshiRomanizer.isSupported()) {
                    result = applyRomanizationRealTime(result!!, effectiveLang)
                }

                // Save to cache
                lyricsDao.insertLyrics(LyricsCacheEntity(
                    mediaId = cacheKey,
                    linesJson = linesToJson(result!!.lines),
                    plainText = result!!.plainText,
                    sourceName = result!!.sourceName,
                    lastUpdatedMs = System.currentTimeMillis()
                ))
            }
        }

        // [Smart Merge] Apply User Corrections with Highest Priority - ALWAYS runs
        val overrides = overrideDao.getOverridesForSong(song.stableId)
            .associateBy { it.timestampMs }
        
        if (overrides.isNotEmpty() && result != null) {
            val linesWithOverrides = result.lines.map { line ->
                val correction = overrides[line.timestampMs]?.correctedText
                if (!correction.isNullOrBlank()) {
                    line.copy(translation = correction)
                } else line
            }
            result = applySecondaryTranslation(result.copy(lines = linesWithOverrides))
        }

        val finalResult = result ?: LyricsResult(sourceName = null)
        memoryCache.put(cacheKey, finalResult)
        finalResult
    }

    private fun linesToJson(lines: List<LyricLine>): String {
        val array = JSONArray()
        lines.forEach { line ->
            val obj = JSONObject().apply {
                put("ts", line.timestampMs)
                put("txt", line.text)
                if (line.agent != null) put("agent", line.agent)
                if (line.vocalEndMs > 0) put("vEnd", line.vocalEndMs)
                if (line.translation != null) put("trans", line.translation)
                if (line.romanizedText != null) put("rom", line.romanizedText)
                if (line.tokens.isNotEmpty()) {
                    val tokensArray = JSONArray()
                    line.tokens.forEach { token ->
                        tokensArray.put(JSONObject().apply {
                            put("ts", token.timestampMs)
                            put("txt", token.text)
                            put("end", token.endMs)
                            if (token.romanized != null) put("rom", token.romanized)
                            if (token.isMorphemeInterior) put("int", true)
                        })
                    }
                    put("tokens", tokensArray)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToLines(json: String): List<LyricLine> {
        val array = JSONArray(json)
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val tokensArray = obj.optJSONArray("tokens")
            val tokens = mutableListOf<LyricToken>()
            if (tokensArray != null) {
                for (j in 0 until tokensArray.length()) {
                    val tObj = tokensArray.getJSONObject(j)
                    tokens.add(
                        LyricToken(
                            timestampMs = tObj.getLong("ts"),
                            text = tObj.getString("txt"),
                            endMs = tObj.getLong("end"),
                            romanized = if (tObj.has("rom")) tObj.getString("rom") else null,
                            isMorphemeInterior = tObj.optBoolean("int", false)
                        )
                    )
                }
            }
            lines.add(LyricLine(
                timestampMs = obj.getLong("ts"),
                text = obj.getString("txt"),
                tokens = tokens,
                agent = if (obj.has("agent")) obj.getString("agent") else null,
                vocalEndMs = obj.optLong("vEnd", 0L),
                translation = if (obj.has("trans")) obj.getString("trans") else null,
                romanizedText = if (obj.has("rom")) obj.getString("rom") else null
            ))
        }
        return lines
    }

    @android.annotation.SuppressLint("NewApi")
    private fun applyRomanizationRealTime(result: LyricsResult, lang: RomanizationLanguage): LyricsResult {
        if (!HoshiRomanizer.isSupported()) return result

        return result.copy(lines = result.lines.map { line ->
            val rawText = line.text.trim()

            // 1. Skip baris kosong, penanda instrumental, dan baris format "asli|romaji"
            if (rawText.isBlank() || LrcParser.isInstrumental(rawText) || rawText.contains("|")) {
                return@map line
            }

            // [V18]: Sanitize mark before romanization engine
            val cleanForRomanizer = rawText.replace("\uE000", "").trim()

            // [Fase 3] Pilih strategi berdasarkan bahasa
            if (lang == RomanizationLanguage.JA) {
                applyJaRomanization(line, cleanForRomanizer)
            } else {
                applyGenericRomanization(line, cleanForRomanizer, lang)
            }
        })
    }

    private fun applyJaRomanization(line: LyricLine, rawText: String): LyricLine {
        val morphemes = HoshiRomanizer.furiganaTokens(rawText)
        if (morphemes.isEmpty()) return line

        // [3b] Penyatuan mesin JA: derive romanizedText dari morfem yang sama
        val lineRomanized = morphemes.joinToString(" ") { it.romaji ?: it.surface }.trim()
        val lineHasRomaji = lineRomanized != rawText

        if (line.tokens.isEmpty()) {
            return line.copy(romanizedText = if (lineHasRomaji) lineRomanized else null)
        }

        // [3a] Algoritma Alignment Offset
        val morphemeRanges = mutableListOf<Pair<IntRange, String>>()
        var mOffset = 0
        morphemes.forEach { m ->
            val start = rawText.indexOf(m.surface, mOffset)
            if (start != -1) {
                val end = start + m.surface.length
                morphemeRanges.add(IntRange(start, end - 1) to (m.romaji ?: m.surface))
                mOffset = end
            }
        }

        var tOffset = 0
        val updatedTokens = line.tokens.map { token ->
            val tStart = tOffset
            val tEnd = tOffset + token.text.length
            val tRange = IntRange(tStart, tEnd - 1)
            tOffset = tEnd

            if (token.text.trim().isEmpty()) return@map token

            val overlaps = morphemeRanges.filter { it.first.first <= tRange.last && it.first.last >= tRange.first }
            if (overlaps.isEmpty()) return@map token

            val romaji = buildString {
                overlaps.forEach { (mRange, mRomaji) ->
                    // Jika token ini mengandung AKHIR dari morfem, tulis romajinya (Fase 3a)
                    if (tRange.contains(mRange.last)) {
                        append(mRomaji).append(" ")
                    }
                }
            }.trim()

            // Jika token ada di dalam morfem tapi bukan bagian akhirnya,
            // beri isMorphemeInterior=true agar UI tidak fallback ke Kanji mentah (Audit A5)
            // tanpa menghilangkan seluruh line di Romaji mode.
            if (romaji.isNotEmpty()) token.copy(romanized = romaji, isMorphemeInterior = false)
            else token.copy(romanized = null, isMorphemeInterior = true)
        }

        return line.copy(
            romanizedText = if (lineHasRomaji) lineRomanized else line.romanizedText,
            tokens = updatedTokens
        )
    }

    private fun applyGenericRomanization(line: LyricLine, rawText: String, lang: RomanizationLanguage): LyricLine {
        val romanized = romanizeFragment(rawText, lang)
        val lineHasRomaji = romanized.isNotBlank() && !romanized.equals(rawText, ignoreCase = true)

        val romanizedTokens = if (line.tokens.isEmpty()) {
            line.tokens
        } else {
            line.tokens.map { token ->
                val fragment = token.text.trim()
                if (fragment.isBlank()) token
                else {
                    val tokenRomaji = romanizeFragment(fragment, lang)
                    if (tokenRomaji.isNotBlank() && !tokenRomaji.equals(fragment, ignoreCase = true)) {
                        token.copy(romanized = tokenRomaji)
                    } else token
                }
            }
        }

        val tokensChanged = romanizedTokens.any { it.romanized != null }
        return if (lineHasRomaji || tokensChanged) {
            line.copy(
                romanizedText = if (lineHasRomaji) romanized else line.romanizedText,
                tokens = romanizedTokens
            )
        } else line
    }

    internal fun applySecondaryTranslationLines(lines: List<LyricLine>): List<LyricLine> =
        lines.map { line ->
            val translated = line.translation?.trim()
            if (!translated.isNullOrBlank()) {
                // [Audit V14]: Stop copy ke romanizedText. Gunakan field translation asli.
                // Kosongkan tokens: cegah Jalur 2 buildLyricAnnotatedString menduplikat
                // romaji ke baris utama. Karaoke per-kata mati di baris ini.
                line.copy(tokens = emptyList())
            } else if (line.text.contains("|")) {
                // Cegah kebocoran mode: buang bagian romaji inline supaya
                // resolvedRomanizedText() tidak jatuh ke sana saat mode Terjemahan aktif.
                line.copy(text = line.text.substringBefore("|").trim())
            } else {
                line
            }
        }

    private fun applySecondaryTranslation(result: LyricsResult): LyricsResult =
        result.copy(lines = applySecondaryTranslationLines(result.lines))

    @android.annotation.SuppressLint("NewApi")
    private fun romanizeFragment(text: String, lang: RomanizationLanguage): String =
        when (lang) {
            RomanizationLanguage.JA -> RomajiHelper.toRomaji(text)
            RomanizationLanguage.KO,
            RomanizationLanguage.ZH -> HoshiRomanizer.romanize(text, lang).romanized ?: ""
            else -> "" // [1a] Stop paksa JA untuk Mandarin/Unknown/Mixed
        }.trim()

    private fun loadLegacyExternal(song: Song, sidecarName: String): String? {
        return song.absolutePath?.let(::File)?.parentFile?.resolve(sidecarName)?.let(LrcLoader::loadSmart)
    }

    private fun loadDeepMetadata(song: Song): LyricsResult? {
        val tag = TagMetadataReader.read(context, song.contentUri) ?: return null

        // 1. Priority: Official Synchronized SYLT binary frame
        if (tag.syltBytes != null && tag.syltTimestampFormat != null) {
            val syltLines = com.naomi.hoshimusic.data.metadata.SyltParser.parse(
                tag.syltBytes,
                tag.syltTimestampFormat,
                song.sampleRate
            )
            if (syltLines.isNotEmpty()) {
                return LyricsResult(
                    lines = syltLines,
                    plainText = tag.lyrics ?: syltLines.joinToString("\n") { it.text },
                    sourceName = "Embedded (SYLT)"
                )
            }
        }

        // 2. Fallback: Merged TagEngine lyrics (USLT, LYRICS, SYNCEDLYRICS, etc.)
        if (!tag.lyrics.isNullOrBlank()) {
            val lines = LrcParser.parse(tag.lyrics, lang = song.detectedLanguage)

            // [Smart Auto-Detect]: Check for LRC timestamps (e.g. [00:00.00] or [00:00:00])
            val hasLrcTimestamps = tag.lyrics.contains(Regex("\\[\\d+:\\d+[.:]\\d+\\]"))

            val isStatic = lines.isNotEmpty() && lines.all { it.timestampMs < lines.size } && !hasLrcTimestamps

            val sourceName = when {
                hasLrcTimestamps -> "Embedded (Synchronized)"
                isStatic -> "Embedded (Static)"
                else -> "Embedded"
            }

            return LyricsResult(
                lines = lines,
                plainText = tag.lyrics,
                sourceName = sourceName
            )
        }

        return null
    }

    private fun findSidecarData(song: Song, sidecarName: String): Pair<Uri, Long>? {
        val col = runCatching { MediaStore.Files.getContentUri(song.volumeName) }.getOrElse { MediaStore.Files.getContentUri("external") }
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        context.contentResolver.query(col, projection, selection, arrayOf(sidecarName, song.relativePath), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val modified = cursor.getLong(1)
                return ContentUris.withAppendedId(col, id) to modified
            }
        }
        return null
    }

    private fun readText(uri: Uri): String? = runCatching<String?> {
        context.contentResolver.openInputStream(uri)?.use { LrcLoader.decodeSmart(it.readBytes()) }
    }.getOrNull()

    internal fun LyricLine.originalLyricText(): String {
        val cleanText = text.replace("\uE000", "").trim()
        return cleanText.substringBefore("|").trim()
    }
}

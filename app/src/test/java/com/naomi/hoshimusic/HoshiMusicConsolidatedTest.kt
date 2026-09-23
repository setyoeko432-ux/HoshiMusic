package com.naomi.hoshimusic

import com.naomi.hoshimusic.data.*
import com.naomi.hoshimusic.util.*
import com.naomi.hoshimusic.ui.*
import com.naomi.hoshimusic.ui.player.originalLyricText
import com.naomi.hoshimusic.ui.player.resolvedRomanizedText
import com.naomi.hoshimusic.data.lrc.*
import com.naomi.hoshimusic.data.metadata.InstrumentalTransitionDetector
import com.naomi.hoshimusic.data.metadata.LrcInstrumentalEngine
import com.naomi.hoshimusic.playback.dsp.VocalTelemetryState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CONSOLIDATED TEST SUITE for HoshiMusic
 * Combines all individual tests for LrcParser and HoshiRomanizer into a single file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HoshiMusicConsolidatedTest {

    private fun parseLyrics(lrc: String) =
        LrcParser.parse(lrc).filter { it.text != LrcParser.INSTRUMENTAL_MARKER }
            .map { it.copy(text = it.text.replace("\uE000", "")) }

    private fun isMarker(line: LyricLine) = line.text == LrcParser.INSTRUMENTAL_MARKER

    // =========================================================================
    // SECTION 1: LrcParser Standard & Enhanced (from LrcParserTest.kt)
    // =========================================================================

    @Test
    fun `enhanced english canonical - word tokens with trailing spaces`() {
        val lrc = """
            [ti:Somebody to Love]
            [ar:Jefferson Airplane]
            [00:00.00]<00:00.04>When <00:00.16>the <00:00.82>truth <00:01.29>is found
            [00:06.47]<00:07.67>And <00:07.94>all the joy within you dies
        """.trimIndent()

        val lines = parseLyrics(lrc)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals(0L, first.timestampMs)
        assertEquals(4, first.tokens.size)
        assertEquals("When ", first.tokens[0].text)
        assertEquals(40L, first.tokens[0].timestampMs)
        assertEquals("When the truth is found", first.text)
    }

    @Test
    fun `enhanced japanese per-character tokens join without spaces`() {
        val lrc = """
            [ti:サンプル]
            [re:enhanced-lrc-example]
            [00:10.00]<00:10.00>こ<00:10.25>ん<00:10.50>に<00:10.75>ち<00:11.00>は
        """.trimIndent()
        val line = parseLyrics(lrc).single()
        assertEquals(5, line.tokens.size)
        assertEquals("こんにちは", line.text)
    }

    @Test
    fun `enhanced mid-word split keeps separate tokens but joins display text`() {
        val lrc = "[00:05.00]<00:05.00>wind<00:05.65>ing <00:06.20>road"
        val line = parseLyrics(lrc).single()
        assertEquals(3, line.tokens.size)
        assertEquals("winding road", line.text)
    }

    @Test
    fun `offset tag shifts all line and word timestamps`() {
        val lrc = """
            [offset:2000]
            [00:10.00]<00:10.50>Dua detik lebih lambat
        """.trimIndent()
        val line = parseLyrics(lrc).single()
        assertEquals(12000L, line.timestampMs)
        assertEquals(12500L, line.tokens[0].timestampMs)
    }

    // =========================================================================
    // SECTION 2: LrcParser Tier 1 (from ParserTier1Test.kt)
    // =========================================================================

    @Test
    fun `parseDetailed captures id metadata`() {
        val lrc = """
            [ti:Fortnight]
            [ar:Taylor Swift]
            [al:Corpus Album]
            [by:HoshiCorpus]
            [00:12.00]Gathering the starlight tonight
        """.trimIndent()
        val result = LrcParser.parseDetailed(lrc)
        assertEquals("Fortnight", result.metadata["ti"])
        assertEquals("Taylor Swift", result.metadata["ar"])
    }

    @Test
    fun `orphan partial tag removed but heart preserved`() {
        val lrc = "[00:10.00]I <3 you <00:1 broken tonight"
        val lines = parseLyrics(lrc)
        assertEquals("I <3 you broken tonight", lines.single().text)
    }

    @Test
    fun `token endMs equals next token start and last token ends at estimate`() {
        val lrc = """
            [00:10.00]<00:10.00>one <00:11.00>two <00:12.00>three
            [00:15.00]next line
        """.trimIndent()
        val line = parseLyrics(lrc).first { it.tokens.isNotEmpty() }
        assertEquals(11_000L, line.tokens[0].endMs)
        // [Fase 8] linguistic duration: "three" (1 syll) * 400ms (UNK) = 400ms -> coerced to 1500ms
        assertEquals(13_500L, line.tokens[2].endMs)
    }

    // =========================================================================
    // SECTION 3: LrcParser Tier 2 (from ParserTier2Test.kt)
    // =========================================================================

    @Test
    fun `walaoke gender markers stored as agent`() {
        val lrc = """
            [00:10.00]M:suara pria di kiri
            [00:14.00]F:suara wanita di kanan
            [00:18.00]D:berdua bersama
        """.trimIndent()
        val lines = parseLyrics(lrc)
        assertEquals("M", lines[0].agent)
        assertEquals("F", lines[1].agent)
        assertEquals("D", lines[2].agent)
    }

    @Test
    fun `slash line becomes translation of matching timestamp`() {
        val lrc = """
            [00:10.00]Line A
            [00:10.00]Line B
            [00:10.00]/Translation for both
        """.trimIndent()
        val lines = parseLyrics(lrc)
        // [Fase 8] Fix race condition P1: both lines should get translation
        assertEquals("Translation for both", lines[0].translation)
        assertEquals("Translation for both", lines[1].translation)
    }

    @Test
    fun `translation edge cases`() {
        val lrc = """
            [00:10.00]Line 1
            [00:10.00]/Translation 1
            [00:20.00]/Translation without main line
            [00:30.00]Line 2 with / slash in middle
            [00:40.00]Line 3
            [00:40.00]/  Translation with spaces  
            [00:50.00]M:Line 4 (Duet)
            [00:50.00]/Translation for Duet
        """.trimIndent()
        val lines = parseLyrics(lrc)
        
        // 1. Translation dipasangkan pada timestamp yang sama
        assertEquals("Line 1", lines[0].text)
        assertEquals("Translation 1", lines[0].translation)
        
        // 2. Translation tanpa baris utama tetap dibuang (perilaku lama)
        assertFalse(lines.any { it.text.contains("without main line") })
        
        // 3. Teks biasa dengan slash di tengah bukan translation
        assertEquals("Line 2 with / slash in middle", lines[1].text)
        assertNull(lines[1].translation)
        
        // 4. Baris tanpa translation menghasilkan null
        assertEquals("Line 3", lines[2].text)
        assertEquals("Translation with spaces", lines[2].translation) // Di-trim
        
        // 5. Tokens, agent, dsb tetap terjaga
        assertEquals("M", lines[3].agent)
        assertEquals("Line 4 (Duet)", lines[3].text)
        assertEquals("Translation for Duet", lines[3].translation)
    }

    // =========================================================================
    // SECTION 4: LrcCorpus Regression (from LrcCorpus40Test.kt)
    // =========================================================================

    @Test
    fun `all 40 corpus songs parse to sorted non-empty lyrics`() {
        assertTrue("Korpus harus 40 lagu", corpusSongs.size == 40)
        corpusSongs.forEach { song ->
            val lines = LrcParser.parse(song.lrc)
            assertTrue("[${song.title}] must not be empty", lines.isNotEmpty())
            assertTrue("[${song.title}] must have vocal lines", lines.any { !isMarker(it) })
            assertTrue("[${song.title}] must be sorted", lines.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
        }
    }

    @Test
    fun `enhanced leading token shifted exactly once with global offset`() {
        val lrc = "[offset:+400]\n[00:10.00]Hello <00:10.50>world <00:11.00>now"
        val lines = parseLyrics(lrc)
        val line = lines.single()
        assertEquals(10_400L, line.timestampMs)
        assertEquals(10_400L, line.tokens.first().timestampMs)
    }

    // =========================================================================
    // SECTION 5: HoshiRomanizer Tier 1 (from RomanizerTier1Test.kt & DetectTest.kt)
    // =========================================================================

    @Test
    fun `artist alias resolver is separate from pure romanization`() {
        val name = "周杰伦"
        val alias = HoshiRomanizer.resolveArtistAlias(name)
        val romanized = HoshiRomanizer.romanize(name, RomanizationLanguage.ZH).romanized
        
        assertEquals("Jay Chou", alias)
        if (HoshiRomanizer.isSupported()) {
            // Romanisasi murni Pinyin dari 周杰伦 (Zhōu Jiélún)
            // Seharusnya tidak berisi "Jay Chou"
            assertNotEquals("Jay Chou", romanized)
            if (romanized != null) {
                // Strip tones/accents for robust testing
                val flat = java.text.Normalizer.normalize(romanized, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                    .replace(" ", "")
                assertTrue("Expected $romanized (flat: $flat) to contain zhou", 
                    flat.contains("zhou", ignoreCase = true))
            }
        }
    }

    @Test
    fun `focused romanization and language detection tests`() {
        // 1. Japanese dengan kana dan kanji
        if (HoshiRomanizer.isSupported()) {
            val ja = HoshiRomanizer.romanize("夜空の光", RomanizationLanguage.JA)
            if (ja.romanized != null) {
                val flat = java.text.Normalizer.normalize(ja.romanized, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                    .replace(" ", "")
                assertTrue("Expected ${ja.romanized} (flat: $flat) to contain yozora", 
                    flat.contains("yozora", ignoreCase = true))
            }
        }

        // 2. Japanese Han-only dengan explicit Japanese hint
        val jaHint = HoshiRomanizer.detectLanguage("夜空", null) // Ambiguous without hint
        assertEquals(RomanizationLanguage.HAN_AMBIGUOUS, jaHint)
        if (HoshiRomanizer.isSupported()) {
             val jaResult = HoshiRomanizer.romanize("夜空", RomanizationLanguage.JA)
             if (jaResult.romanized != null) {
                 val flat = java.text.Normalizer.normalize(jaResult.romanized, java.text.Normalizer.Form.NFD)
                     .replace(Regex("\\p{M}"), "")
                     .replace(" ", "")
                 assertTrue("Expected ${jaResult.romanized} (flat: $flat) to contain yozora", 
                     flat.contains("yozora", ignoreCase = true))
             }
        }

        // 3. Chinese Han dengan explicit Chinese hint
        if (HoshiRomanizer.isSupported()) {
            val zhResult = HoshiRomanizer.romanize("夜空", RomanizationLanguage.ZH)
            if (zhResult.romanized != null) {
                val flat = java.text.Normalizer.normalize(zhResult.romanized, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                    .replace(" ", "")
                assertTrue("Expected ${zhResult.romanized} (flat: $flat) to contain ye", 
                    flat.contains("ye", ignoreCase = true))
            }
        }

        // 4. Han-only tanpa hint menghasilkan ambiguous, bukan dipaksa Chinese (sesuai detectLanguage)
        val ambiguous = HoshiRomanizer.detectLanguage("夜空", null)
        assertEquals(RomanizationLanguage.HAN_AMBIGUOUS, ambiguous)

        // 5. Korean Hangul
        val koDetect = HoshiRomanizer.detectLanguage("안녕하세요", null)
        assertEquals(RomanizationLanguage.KO, koDetect)

        // 6. Latin-only tidak berubah
        val latin = HoshiRomanizer.romanize("Hello 123", null)
        assertNull(latin.romanized) // romanizedText null if no change needed

        // 7. Mixed Japanese/Latin/angka
        if (HoshiRomanizer.isSupported()) {
            val mixed = HoshiRomanizer.romanize("夜空 Stage 1", RomanizationLanguage.JA)
            if (mixed.romanized != null) {
                val flat = java.text.Normalizer.normalize(mixed.romanized, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                    .replace(" ", "")
                assertTrue("Expected ${mixed.romanized} (flat: $flat) to contain yozora", 
                    flat.contains("yozora", ignoreCase = true))
                assertTrue("Expected ${mixed.romanized} to contain Stage 1", 
                    mixed.romanized.contains("Stage 1", ignoreCase = true))
            }
        }

        // 8. Punctuation dan whitespace
        val punct = HoshiRomanizer.normalizeFullWidth("！　？")
        assertEquals("! ?", punct)

        // 9. Input kosong
        val empty = HoshiRomanizer.romanize("  ", null)
        assertEquals(RomanizationLanguage.UNKNOWN, empty.language)
        assertNull(empty.romanized)

        // 10. Unknown token tidak hilang (diuji via normalizeFullWidth dan fallback romanize)
        val unknown = HoshiRomanizer.normalizeFullWidth("§±")
        assertEquals("§±", unknown)

        // 11-13. Kontrak Model (Verified via separate test above, but re-confirming logic)
        val line = LyricLine(timestampMs = 0L, text = "夜空", translation = "Night Sky")
        val romanizedVal = "yozora"
        val updatedLine = line.copy(romanizedText = romanizedVal)
        assertEquals("夜空", updatedLine.text) // Text tetap asli
        assertEquals("yozora", updatedLine.romanizedText) // romanizedText terpisah
        assertEquals("Night Sky", updatedLine.translation) // Translation tidak berubah

        // 14. Cache tidak tertukar antara hint Japanese dan Chinese
        if (HoshiRomanizer.isSupported()) {
            val resJA = HoshiRomanizer.romanize("夜空", RomanizationLanguage.JA)
            val resZH = HoshiRomanizer.romanize("夜空", RomanizationLanguage.ZH)
            assertNotEquals(resJA.romanized, resZH.romanized)
        }

        // 15. Romanisasi deterministik
        if (HoshiRomanizer.isSupported()) {
            val res1 = HoshiRomanizer.romanize("夜空", RomanizationLanguage.JA)
            val res2 = HoshiRomanizer.romanize("夜空", RomanizationLanguage.JA)
            assertEquals(res1.romanized, res2.romanized)
        }
    }

    @Test
    fun `instrumental keywords win over everything`() {
        val result = HoshiRomanizer.detectLanguage("Instrumental", "off vocal version")
        assertEquals(RomanizationLanguage.INSTRUMENTAL, result)
    }

    @Test
    fun `astral han extension B is still counted as Han`() {
        val result = HoshiRomanizer.detectLanguage("𠮷野家", null)
        assertEquals(RomanizationLanguage.HAN_AMBIGUOUS, result)
    }

    // =========================================================================
    // SECTION 6: HoshiRomanizer Tier 2 (from RomanizerTier2Test.kt)
    // =========================================================================

    @Test
    fun `basic katakana to romaji`() {
        assertEquals("yozora", HoshiRomanizer.katakanaToRomaji("ヨゾラ"))
        assertEquals("shainingu", HoshiRomanizer.katakanaToRomaji("シャイニング"))
    }

    @Test
    fun `sokuon doubles next consonant`() {
        assertEquals("kitto", HoshiRomanizer.katakanaToRomaji("キット"))
        assertEquals("kotchi", HoshiRomanizer.katakanaToRomaji("コッチ"))
    }

    @Test
    fun `kanji word yields katakana reading and romaji`() {
        val tokens = HoshiRomanizer.furiganaTokens("夜空")
        assertTrue(tokens.isNotEmpty())
        assertEquals("yozora", tokens.joinToString("") { it.romaji.orEmpty() })
    }

    // =========================================================================
    // SECTION 7: Romanizer Corpus Regression (from RomanizerCorpus40Test.kt)
    // =========================================================================

    @Test
    fun `all 40 songs detected with correct language`() {
        corpusSongs.forEach { song ->
            val actual = HoshiRomanizer.detectLyricsLanguage(song.lrc)
            val expected = when (song.lang) {
                CorpusLang.EN -> RomanizationLanguage.EN
                CorpusLang.JA -> RomanizationLanguage.JA
                CorpusLang.ZH -> RomanizationLanguage.ZH
                CorpusLang.KO -> RomanizationLanguage.KO
            }
            assertEquals("${song.artist} - ${song.title} failure", expected, actual)
        }
    }

    // =========================================================================
    // SECTION 8: Phase 2 - Iteration Marks (々, ゝ, ゞ)
    // =========================================================================

    @Test
    fun `kogoro iteration mark test`() {
        if (HoshiRomanizer.isSupported()) {
            val res = HoshiRomanizer.romanize("こゞろ", RomanizationLanguage.JA)
            assertEquals("ko go ro", res.romanized)
        }
    }

    @Test
    fun `kanji iteration mark duplication test`() {
        if (HoshiRomanizer.isSupported()) {
            val res = HoshiRomanizer.romanize("代々", RomanizationLanguage.JA)
            assertEquals("daidai", res.romanized)
        }
    }

    @Test
    fun `furiganaTokens handles iteration marks correctly`() {
        val tokens = HoshiRomanizer.furiganaTokens("人々")
        assertTrue(tokens.isNotEmpty())
        // Hit dictionary "人々" (hitobito) or fallback "hitohito" (unvoiced)
        // IPADIC usually has "人々" as "ヒトビト"
        assertTrue(tokens.joinToString("") { it.romaji.orEmpty() }.contains("hito"))
    }

    // =========================================================================
    // SECTION 9: Formatters (from FormattersTest.kt)
    // =========================================================================

    // =========================================================================
    // SECTION 10: TRAINING ENGINE (Calibration)
    // =========================================================================

    @Test
    fun `train instrumental pause engine`() {
        val outputFile = java.io.File(System.getProperty("java.io.tmpdir"), "hoshi_training_results.txt")
        val sb = StringBuilder()
        sb.append("--- CALIBRATION RESULTS (Training with 40 Songs) ---\n")
        
        CorpusLang.values().forEach { lang ->
            val songs = corpusSongs.filter { it.lang == lang }
            var totalMs: Long = 0
            var totalSyllables = 0
            var linesCount = 0

            songs.forEach { song ->
                val detailed = LrcParser.parseDetailed(song.lrc)
                val lines = detailed.lines
                
                for (i in 0 until lines.size - 1) {
                    val current = lines[i]
                    val next = lines[i + 1]
                    
                    val gap = next.timestampMs - current.timestampMs
                    if (gap in 500..5999 && current.text != LrcParser.INSTRUMENTAL_MARKER) {
                        val method = LrcVocalTiming::class.java.getDeclaredMethod("countSyllables", String::class.java)
                        method.isAccessible = true
                        val syllables = method.invoke(LrcVocalTiming, current.text) as Int
                        
                        totalMs += gap
                        totalSyllables += syllables
                        linesCount++
                    }
                }
            }

            if (totalSyllables > 0) {
                val avg = totalMs.toDouble() / totalSyllables
                sb.append("LANG: $lang | AVG: ${"%.2f".format(avg)}ms per syllable | Sample Size: $linesCount lines\n")
            }
        }
        sb.append("----------------------------------------------------\n")
        outputFile.writeText(sb.toString())
    }

    // =========================================================================
    // SECTION 11: TRANSLATOR ROBUSTNESS (Calibration & Mock Test)
    // =========================================================================

    @Test
    fun `test translator robustness and coverage on 40 songs`() {
        val outputFile = java.io.File(System.getProperty("java.io.tmpdir"), "hoshi_translator_robustness_results.txt")
        val sb = StringBuilder()
        sb.append("--- TRANSLATOR ROBUSTNESS REPORT (40 Songs) ---\n")

        var totalLines = 0
        var totalInstrumental = 0
        var totalBilingual = 0
        var totalTranslationCandidates = 0

        corpusSongs.forEach { song ->
            val detailed = LrcParser.parseDetailed(song.lrc)
            val lines = detailed.lines
            
            var songTranslationPotential = 0
            
            lines.forEach { line ->
                totalLines++
                val clean = line.text.substringBefore("|").replace("\uE000", "").trim()
                
                when {
                    LrcParser.isInstrumental(clean) -> totalInstrumental++
                    line.translation != null -> totalBilingual++
                    clean.isNotEmpty() -> {
                        totalTranslationCandidates++
                        songTranslationPotential++
                    }
                }
            }
            
            val coverage = if (lines.isNotEmpty()) (songTranslationPotential.toDouble() / lines.size * 100) else 0.0
            sb.append("SONG: ${song.title.padEnd(20)} | Lang: ${song.lang.toString().padEnd(5)} | Potential: ${"%3d".format(songTranslationPotential)} lines | Coverage: ${"%.1f".format(coverage)}%\n")
        }

        sb.append("\n--- AGGREGATE METRICS ---\n")
        sb.append("Total Lines Scanned: $totalLines\n")
        sb.append("Instrumental Lines:  $totalInstrumental (Skip)\n")
        sb.append("Existing Bilingual:  $totalBilingual (Preserve)\n")
        sb.append("ML Kit Candidates:   $totalTranslationCandidates (${"%.1f".format(totalTranslationCandidates.toDouble()/totalLines*100)}% of all lines)\n")
        sb.append("-----------------------------------------------\n")
        
        outputFile.writeText(sb.toString())
        
        // Assert that at least some lines are candidates for translation
        assertTrue("At least 50% of lines should be candidates or already translated", 
            (totalTranslationCandidates + totalBilingual).toDouble() / totalLines > 0.5)
    }

    // =========================================================================
    // SECTION 12: ROMANIZATION UI LOGIC (V11 Regression Tests)
    // =========================================================================

    @Test
    fun `romanizedText property is prioritized over pipe format`() {
        val line = LyricLine(timestampMs = 0L, text = "夜空|romaji_old", romanizedText = "yozora_new")
        assertEquals("yozora_new", line.resolvedRomanizedText())
    }

    @Test
    fun `fallback to pipe format when romanizedText is null`() {
        val line = LyricLine(timestampMs = 0L, text = "夜空|yozora", romanizedText = null)
        assertEquals("yozora", line.resolvedRomanizedText())
    }

    @Test
    fun `originalLyricText strips markers and romanization`() {
        val line = LyricLine(timestampMs = 0L, text = "夜空\uE000|yozora")
        assertEquals("夜空", line.originalLyricText())
    }

    @Test
    fun `no romanization returns null`() {
        val line = LyricLine(timestampMs = 0L, text = "Hello World")
        assertNull(line.resolvedRomanizedText())
        assertEquals("Hello World", line.originalLyricText())
    }

    @Test
    fun `latin text with marker remains correct`() {
        val line = LyricLine(timestampMs = 0L, text = "Hello\uE000")
        assertEquals("Hello", line.originalLyricText())
        assertNull(line.resolvedRomanizedText())
    }

    @Test
    fun `romanization available even if tokens present`() {
        val tokens = listOf(LyricToken(0L, "夜"), LyricToken(500L, "空"))
        val line = LyricLine(timestampMs = 0L, text = "夜空", tokens = tokens, romanizedText = "yozora")
        assertEquals("yozora", line.resolvedRomanizedText())
        assertEquals("夜空", line.originalLyricText())
    }

    // =========================================================================
    // SECTION 13: SECONDARY LINE MODE (Romaji ⇄ Translation)
    // =========================================================================

    @Test
    fun `applySecondaryTranslationLines preserves translation and clears tokens`() {
        val repo = LyricsRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val tokens = listOf(LyricToken(0L, "Kimi", romanized = "Kimi"))
        val line = LyricLine(timestampMs = 1000L, text = "君の声", translation = "Suaramu", tokens = tokens)
        
        val result = repo.applySecondaryTranslationLines(listOf(line))
        val processed = result.single()
        
        assertNull("[Audit V14]: romanizedText must now be null for translation lines", processed.romanizedText)
        assertEquals("Suaramu", processed.translation)
        assertTrue("Tokens must be cleared for translation lines", processed.tokens.isEmpty())
        assertEquals("君の声", processed.text)
    }

    @Test
    fun `applySecondaryTranslationLines prevents mode leak for pipe format`() {
        val repo = LyricsRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val line = LyricLine(timestampMs = 1000L, text = "夜空|yozora", translation = null)
        
        val result = repo.applySecondaryTranslationLines(listOf(line))
        val processed = result.single()
        
        assertNull("Romaji must not leak when translation mode is active but translation is null", processed.romanizedText)
        assertEquals("夜空", processed.text)
        assertFalse("Pipe should be stripped", processed.text.contains("|"))
    }

    @Test
    fun `applySecondaryTranslationLines keeps tokens if no translation`() {
        val repo = LyricsRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val tokens = listOf(LyricToken(0L, "Hello"))
        val line = LyricLine(timestampMs = 1000L, text = "Hello", tokens = tokens)
        
        val result = repo.applySecondaryTranslationLines(listOf(line))
        val processed = result.single()
        
        assertEquals(tokens, processed.tokens)
        assertNull(processed.romanizedText)
    }

    // =========================================================================
    // SECTION 15: V13/V14 REGRESSION TESTS (Blank Lines & Mode Refactor)
    // =========================================================================

    @Test
    fun `buildLyricAnnotatedString handles multi-token kanji morphemes without blanking`() {
        // [V13 Bug]: "夜空" split into <...>夜<...>空
        val tokens = listOf(
            LyricToken(0L, "夜", romanized = null, isMorphemeInterior = true),
            LyricToken(500L, "空", romanized = "yozora", isMorphemeInterior = false)
        )
        val line = LyricLine(timestampMs = 0L, text = "夜空", tokens = tokens)
        
        // Simulasikan renderer Romaji
        val result = com.naomi.hoshimusic.ui.player.buildLyricAnnotatedString(
            line = line,
            currentPositionMs = 0L,
            activeColor = androidx.compose.ui.graphics.Color.White,
            inactiveColor = androidx.compose.ui.graphics.Color.Gray,
            displayMode = LyricDisplayMode.ROMANIZED
        )
        
        assertFalse("Rendered line must not be blank", result.text.isBlank())
        assertTrue("Rendered text must contain romaji", result.text.contains("yozora"))
    }

    @Test
    fun `buildLyricAnnotatedString falls back to original if Jalur 1 results in blank`() {
        // Ekstrem: Semua token interior (data malformed tapi harus selamat)
        val tokens = listOf(
            LyricToken(0L, "夜", romanized = null, isMorphemeInterior = true)
        )
        val line = LyricLine(timestampMs = 0L, text = "夜", tokens = tokens)
        
        val result = com.naomi.hoshimusic.ui.player.buildLyricAnnotatedString(
            line = line,
            currentPositionMs = 0L,
            activeColor = androidx.compose.ui.graphics.Color.White,
            inactiveColor = androidx.compose.ui.graphics.Color.Gray,
            displayMode = LyricDisplayMode.ROMANIZED
        )
        
        assertEquals("夜", result.text) // Safety-net fallback to original text
    }

    @Test
    fun `buildLyricAnnotatedString returns original text in TRANSLATED mode`() {
        val line = LyricLine(timestampMs = 0L, text = "夜空", translation = "Langit Malam")
        
        val result = com.naomi.hoshimusic.ui.player.buildLyricAnnotatedString(
            line = line,
            currentPositionMs = 0L,
            activeColor = androidx.compose.ui.graphics.Color.White,
            inactiveColor = androidx.compose.ui.graphics.Color.Gray,
            displayMode = LyricDisplayMode.TRANSLATED
        )
        
        // [Audit V14]: buildLyricAnnotatedString now returns original text only.
        // The translation is rendered by the caller in a separate Text slot.
        assertEquals("夜空", result.text)
    }

    @Test
    fun `buildLyricAnnotatedString falls back to original if translation is missing`() {
        val line = LyricLine(timestampMs = 0L, text = "夜空", translation = null)
        
        val result = com.naomi.hoshimusic.ui.player.buildLyricAnnotatedString(
            line = line,
            currentPositionMs = 0L,
            activeColor = androidx.compose.ui.graphics.Color.White,
            inactiveColor = androidx.compose.ui.graphics.Color.Gray,
            displayMode = LyricDisplayMode.TRANSLATED
        )
        
        assertEquals("夜空", result.text)
    }

    // =========================================================================
    // SECTION 14: ENGLISH DETECTION & TRANSLATION REFINEMENT
    // =========================================================================

    @Test
    fun `detectLanguage identifies english with markers`() {
        val result = HoshiRomanizer.detectLanguage("The truth is found", null)
        assertEquals(RomanizationLanguage.EN, result)
    }

    @Test
    fun `detectLanguage falls back to latin for unknown latin scripts`() {
        val result = HoshiRomanizer.detectLanguage("Lorem ipsum dolor sit amet", null)
        assertEquals(RomanizationLanguage.LATIN, result)
    }

    // =========================================================================
    // SECTION 6: Instrumental Logic & False Positives (V33)
    // =========================================================================

    @Test
    fun `test explicit marker - transitions to instrumental`() {
        val detector = InstrumentalTransitionDetector()
        val ann = InstrumentalAnnotation(InstrumentalSource.EXPLICIT_LRC, 1000, 5000)
        
        // Before timestamp
        var state = detector.update(500, 0, ann, VocalTimingConfidence.HIGH, null, false)
        assertTrue(state is LyricPresentationState.Vocal)
        
        // At timestamp
        state = detector.update(1000, 0, ann, VocalTimingConfidence.HIGH, null, false)
        assertTrue(state is LyricPresentationState.Instrumental)
    }

    @Test
    fun `test inferred gap - ignored in explicit only policy`() {
        val detector = InstrumentalTransitionDetector()
        val ann = InstrumentalAnnotation(InstrumentalSource.INFERRED_GAP, 1000, 10000)
        val state = detector.update(2000, 0, ann, VocalTimingConfidence.HIGH, null, false)
        assertTrue("Inferred gaps must be ignored", state is LyricPresentationState.Vocal)
    }

    @Test
    fun `test parser classification - explicit markers`() {
        val lrc = """
            [00:00.00]♪
            [00:01.00][Instrumental]
            [00:02.00]間奏
            [00:03.00]Solo
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(4, lines.size)
        lines.forEach { 
            assertEquals("Failed for ${it.text}", LyricAnnotationType.EXPLICIT_INSTRUMENTAL, it.annotationType) 
        }
    }

    @Test
    fun `test parser classification - section labels`() {
        val lrc = """
            [00:00.00][Chorus]
            [00:01.00]Verse 1
            [00:02.00](Bridge)
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        lines.forEach { 
            assertEquals("Failed for ${it.text}", LyricAnnotationType.SECTION_LABEL, it.annotationType) 
            assertNotEquals(LyricAnnotationType.EXPLICIT_INSTRUMENTAL, it.annotationType)
        }
    }

    @Test
    fun `test engine - 30s gap does not create explicit marker`() {
        val lrc = """
            [00:00.00]Line 1
            [00:35.00]Line 2
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals(LyricAnnotationType.VOCAL, first.annotationType)
        assertNotNull(first.instrumentalAnnotation)
        assertEquals(InstrumentalSource.INFERRED_GAP, first.instrumentalAnnotation?.source)
        
        // Ensure no "♪" line was inserted
        assertFalse(lines.any { it.text == "♪" })
    }

    @Test
    fun `test engine - last line never has inferred gap`() {
        val lrc = "[00:00.00]Last Line"
        val lines = LrcParser.parse(lrc)
        assertNull(lines[0].instrumentalAnnotation)
    }

    @Test
    fun `test blank line with timestamp is separator`() {
        val lrc = "[00:05.00] "
        val lines = LrcParser.parse(lrc)
        assertEquals(LyricAnnotationType.SEPARATOR, lines[0].annotationType)
    }
}

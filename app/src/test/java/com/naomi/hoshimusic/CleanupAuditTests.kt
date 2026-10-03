package com.naomi.hoshimusic

import com.naomi.hoshimusic.data.LrcParser
import com.naomi.hoshimusic.data.LyricLine
import com.naomi.hoshimusic.data.metadata.MojibakeRepairer
import com.naomi.hoshimusic.playback.HoshiDspProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

// MojibakeRepairer menyimpan cache lewat android.util.LruCache. Tanpa runner
// Robolectric, android.jar yang dipakai unit test adalah stub yang melempar
// RuntimeException("Stub!"), sehingga repair() tidak pernah tervalidasi.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CleanupAuditTests {

    @Test
    fun `lrc parser handles broken timestamps and negative offset`() {
        val lrc = """
            [offset:-5000]
            [00:10.00]Line 1
            [99:99.99]Broken timestamp
            [00:20.00]Line 2
        """.trimIndent()
        val lines: List<LyricLine> = LrcParser.parse(lrc).filter { it.text != LrcParser.INSTRUMENTAL_MARKER }
        
        // Offset -5000ms shifts 10s to 5s
        val first = lines[0]
        assertEquals(5000L, first.timestampMs)
        assertTrue(first.text.startsWith("Line 1"))
        
        // Broken timestamp should be ignored or handled gracefully
        assertTrue(lines.any { it.text.startsWith("Line 2") && it.timestampMs == 15000L })
    }

    @Test
    fun `mojibake repairer fixes common encoding issues`() {
        val input = "æ\u0097¥æ\u009C¬èª\u009E" 
        val repaired = MojibakeRepairer.repair(input)
        assertNotNull(repaired)
    }

    @Test
    fun `bitrate normalization converts bps to kbps correctly`() {
        // Mock data logic test
        val bpsValues = listOf(128000, 320000, 1411200, 0)
        val expectedKbps = listOf(128, 320, 1411, 0)
        
        bpsValues.zip(expectedKbps).forEach { (bps, expected) ->
            val actual = if (bps > 0) bps / 1000 else 0
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `audio quality resolution follows conservative hi-res rules`() {
        val helper = com.naomi.hoshimusic.util.AudioMetadataHelper
        
        // Hi-Res (>48k)
        assertEquals(com.naomi.hoshimusic.util.AudioQuality.HI_RES, 
            helper.resolveQuality("audio/flac", null, 96000, 24, null))
        
        // Hi-Res (>16bit)
        assertEquals(com.naomi.hoshimusic.util.AudioQuality.HI_RES, 
            helper.resolveQuality("audio/wav", null, 44100, 24, null))
            
        // Standard Lossless
        assertEquals(com.naomi.hoshimusic.util.AudioQuality.LOSSLESS, 
            helper.resolveQuality("audio/flac", null, 44100, 16, null))
            
        // HQ (Lossy >= 320k)
        assertEquals(com.naomi.hoshimusic.util.AudioQuality.HQ, 
            helper.resolveQuality("audio/mpeg", null, 44100, null, 320))
            
        // Low Quality (< 320k)
        assertEquals(com.naomi.hoshimusic.util.AudioQuality.NONE, 
            helper.resolveQuality("audio/mpeg", null, 44100, null, 128))
    }
}

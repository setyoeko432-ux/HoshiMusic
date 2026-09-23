package com.naomi.hoshimusic

import com.naomi.hoshimusic.playback.dsp.DspPresets
import com.naomi.hoshimusic.data.PeqBand
import org.junit.Assert.assertEquals
import org.junit.Test

class HoshiDspTest {

    @Test
    fun `autoeq parser correctly parses preamp and bands`() {
        val autoEqText = """
            Preamp: -6.7 dB
            Filter 1: ON PK Fc 20 Hz Gain 3.5 dB Q 1.41
            Filter 2: ON LSC Fc 105 Hz Gain -2.0 dB Q 0.71
            Filter 3: ON HSC Fc 10000 Hz Gain 1.5 dB Q 0.71
        """.trimIndent()
        
        val (bands, preamp) = DspPresets.parseAutoEq(autoEqText)
        
        assertEquals(-6.7f, preamp)
        assertEquals(3, bands.size)
        
        // Band 1: PK
        assertEquals(0, bands[0].type)
        assertEquals(20f, bands[0].fc)
        assertEquals(3.5f, bands[0].gain)
        assertEquals(1.41f, bands[0].q)
        
        // Band 2: LS (LSC)
        assertEquals(1, bands[1].type)
        assertEquals(105f, bands[1].fc)
        assertEquals(-2.0f, bands[1].gain)
        
        // Band 3: HS (HSC)
        assertEquals(2, bands[2].type)
    }

    @Test
    fun `autoeq parser handles case insensitivity and extra spaces`() {
        val autoEqText = "preamp: -1.2db\nFILTER 1: on pk FC 1000 HZ GAIN 0.0 DB q 0.707"
        val (bands, preamp) = DspPresets.parseAutoEq(autoEqText)
        
        assertEquals(-1.2f, preamp)
        assertEquals(1, bands.size)
        assertEquals(1000f, bands[0].fc)
    }

    @Test
    fun `autoeq parser handles triple format fallback`() {
        val text = "1000, -3.5, 1.0\n2000; 2.0; 0.5"
        val (bands, _) = DspPresets.parseAutoEq(text)
        assertEquals(2, bands.size)
        assertEquals(1000f, bands[0].fc)
        assertEquals(-3.5f, bands[0].gain)
        assertEquals(1.0f, bands[0].q)
    }

    @Test
    fun `processor handles queueInput during bypass correctly`() {
        val processor = com.naomi.hoshimusic.playback.HoshiDspProcessor()
        // libraryLoaded will be false in local JVM tests
        org.junit.Assert.assertFalse(com.naomi.hoshimusic.playback.HoshiDspProcessor.libraryLoaded)
        
        val input = java.nio.ByteBuffer.allocate(1024)
        // Should not crash and should use software fallback
        processor.queueInput(input)
        
        processor.release()
    }
}

package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.playback.HoshiDspProcessor
import com.naomi.hoshimusic.playback.dsp.DspPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memastikan kontrak parameter DSP antara Kotlin dan Native tetap konsisten.
 */
class DspContractTest {

    @Test
    fun testParamCountConsistency() {
        // Kontrak: Mesin C++ mengharapkan tepat 15 parameter
        assertEquals("HoshiDspProcessor.DSP_PARAM_COUNT harus 15", 15, HoshiDspProcessor.DSP_PARAM_COUNT)
        assertEquals("DspPresets.PARAM_COUNT harus 15", 15, DspPresets.PARAM_COUNT)
    }

    @Test
    fun testPeqMaxConsistency() {
        // Kontrak: Mesin C++ mengharapkan maksimal 10 band PEQ
        assertEquals("HoshiDspProcessor.DSP_PEQ_MAX harus 10", 10, HoshiDspProcessor.DSP_PEQ_MAX)
    }

    @Test
    fun testBuiltinPresetsValidity() {
        DspPresets.BUILTINS.forEach { preset ->
            assertEquals("Preset '${preset.id}' harus memiliki ${DspPresets.PARAM_COUNT} parameter", 
                DspPresets.PARAM_COUNT, preset.params.size)
            
            preset.params.forEachIndexed { index, value ->
                assertTrue("Parameter $index di preset '${preset.id}' tidak boleh NaN/Infinite", 
                    value.isFinite())
                assertTrue("Parameter $index di preset '${preset.id}' ($value) di luar batas aman [0.0, 2.0]", 
                    value in 0f..2f)
            }
        }
    }

    @Test
    fun testAutoEqParser() {
        val autoEqText = """
            Preamp: -4.5 dB
            Filter 1: ON PK Fc 30 Hz Gain 5.2 dB Q 1.10
            Filter 2: ON LS Fc 105 Hz Gain -3.0 dB Q 0.71
            Filter 3: ON PK Fc 1000 Hz Gain 1.5 dB Q 2.00
            Filter 4: ON HS Fc 10000 Hz Gain 2.0 dB Q 0.71
        """.trimIndent()

        val (bands, preamp) = DspPresets.parseAutoEq(autoEqText)

        assertEquals("Preamp mismatch", -4.5f, preamp)
        assertEquals("Jumlah band mismatch", 4, bands.size)

        // Band 1: PK
        assertEquals(0, bands[0].type)
        assertEquals(30f, bands[0].fc)
        assertEquals(5.2f, bands[0].gain)
        assertEquals(1.1f, bands[0].q)

        // Band 2: LS
        assertEquals(1, bands[1].type)
        assertEquals(105f, bands[1].fc)
        assertEquals(-3.0f, bands[1].gain)
        assertEquals(0.71f, bands[1].q)
        
        // Band 4: HS
        assertEquals(2, bands[3].type)
        assertEquals(10000f, bands[3].fc)
        assertEquals(2.0f, bands[3].gain)
        assertEquals(0.71f, bands[3].q)
    }
    
    @Test
    fun testAutoEqParserTripleFallback() {
        val text = "1000, 5.0, 1.0"
        val (bands, _) = DspPresets.parseAutoEq(text)
        assertEquals(1, bands.size)
        assertEquals(0, bands[0].type) // PK fallback
        assertEquals(1000f, bands[0].fc)
        assertEquals(5f, bands[0].gain)
        assertEquals(1f, bands[0].q)
    }

    @Test
    fun testAutoEqParserLimit() {
        val longText = (1..15).joinToString("\n") { "Filter $it: ON PK Fc $it Gain 0 Q 1" }
        val (bands, _) = DspPresets.parseAutoEq(longText)
        assertEquals("Parser tidak boleh melebihi DSP_PEQ_MAX", 10, bands.size)
    }
}

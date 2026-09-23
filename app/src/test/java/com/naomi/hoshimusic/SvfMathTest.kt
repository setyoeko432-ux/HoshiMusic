package com.naomi.hoshimusic

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * Proving the TPT SVF math used in the native C++ core.
 * This ensures that a +6dB boost at 1kHz actually produces a boost.
 */
class SvfMathTest {

    class KotlinSvf {
        private var s1 = 0f
        private var s2 = 0f
        private var g = 0f
        private var k = 0f
        private var m0 = 1f
        private var m1 = 0f
        private var m2 = 0f

        fun setPeaking(fs: Float, fc: Float, gainDb: Float, q: Float) {
            val a = 10.0.pow(gainDb.toDouble() / 40.0).toFloat()
            g = tan(PI.toFloat() * fc / fs)
            k = 1.0f / (q * a)
            m0 = 1.0f; m1 = k * (a * a - 1.0f); m2 = 0.0f
        }

        fun process(x: Float): Float {
            val input = x + 1e-20f
            val v3 = input - s2
            val v1 = (g * v3 + s1) / (1.0f + g * (g + k))
            val v2 = s2 + g * v1
            s1 = 2.0f * v1 - s1
            s2 = 2.0f * v2 - s2
            return m0 * input + m1 * v1 + m2 * v2
        }
    }

    @Test
    fun `peaking filter at 1kHz with plus 6dB boost`() {
        val fs = 44100f
        val fc = 1000f
        val boostDb = 6.0f
        val svf = KotlinSvf()
        svf.setPeaking(fs, fc, boostDb, 1.41f)

        // Generate 1kHz sine wave
        val samples = 441
        var maxIn = 0f
        var maxOut = 0f

        for (i in 0 until samples) {
            val t = i / fs
            val x = sin(2.0 * PI * fc * t).toFloat()
            val y = svf.process(x)
            
            // Wait for stabilization
            if (i > 200) {
                maxIn = max(maxIn, abs(x))
                maxOut = max(maxOut, abs(y))
            }
        }

        val actualGainDb = 20.0 * log10((maxOut / maxIn).toDouble())
        println("Target Boost: $boostDb dB, Actual: $actualGainDb dB")
        
        // Tolerance 0.5dB due to transient stabilization in short test
        assertTrue("Gain should be approx 6dB", abs(actualGainDb - 6.0) < 0.5)
    }

    @Test
    fun `flat filter produces zero gain`() {
        val fs = 44100f
        val svf = KotlinSvf()
        svf.setPeaking(fs, 1000f, 0.0f, 1.0f)

        val x = 0.5f
        val y = svf.process(x)
        assertTrue("Output should match input for 0dB gain", abs(x - y) < 0.0001f)
    }
}

package com.naomi.hoshimusic.playback.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated Kotlin Configuration & Resolver Test Suite for Root Cause Analysis.
 *
 * Verifies Test C (Preset Preview Override & Ownership Contract):
 *   - Ensures stale preview overrides from a previous preset DO NOT override new preset selections.
 *   - Ensures custom presets override builtin presets when matching ID.
 *   - Ensures ambience modifier operations do not mutate source arrays.
 *   - Ensures all 14 builtin presets produce distinct parameter profiles.
 */
class DspRootCauseResolverTest {

    // ================================================================
    // TEST C — Preset Preview Override & Resolution Sequence
    // ================================================================

    @Test
    fun testC_PresetPreviewSequenceAndStaleOverrideIsolation() {
        // Step 1: Resolve Reference
        val refParams = DspParamResolver.resolve("reference", emptyList(), null)
        assertEquals("Reference must have 15 parameters", 15, refParams.size)

        // Step 2: Preview Bass Boost (stale preview attached to "bass_boost")
        val bassBoostPreview = DspPreviewBridge.PreviewParams(
            sourcePresetId = "bass_boost",
            params = DspPresets.builtinById("bass_boost")!!.params.toFloatArray()
        )

        // Step 3: Resolve Bass Boost with matching preview -> Preview should be used
        val resolvedBass = DspParamResolver.resolve("bass_boost", emptyList(), bassBoostPreview)
        assertArrayEquals(
            "Bass boost preview should be active when sourcePresetId matches",
            DspPresets.builtinById("bass_boost")!!.params.toFloatArray(),
            resolvedBass,
            0f
        )

        // Step 4: User selects Vocal Clarity WITHOUT clearing the Bass Boost preview
        // -> Stale Bass Boost preview MUST BE IGNORED, Vocal Clarity params MUST BE RETURNED
        val resolvedVocal = DspParamResolver.resolve("vocal", emptyList(), bassBoostPreview)
        assertArrayEquals(
            "Stale preview from 'bass_boost' MUST NOT override active preset 'vocal'",
            DspPresets.builtinById("vocal")!!.params.toFloatArray(),
            resolvedVocal,
            0f
        )

        // Step 5: User selects Instrument Stage
        val resolvedStage = DspParamResolver.resolve("stage", emptyList(), bassBoostPreview)
        assertArrayEquals(
            "Stale preview MUST NOT override active preset 'stage'",
            DspPresets.builtinById("stage")!!.params.toFloatArray(),
            resolvedStage,
            0f
        )

        // Step 6: Return to Reference
        val resolvedRef = DspParamResolver.resolve("reference", emptyList(), bassBoostPreview)
        assertArrayEquals(
            "Stale preview MUST NOT override active preset 'reference'",
            DspPresets.builtinById("reference")!!.params.toFloatArray(),
            resolvedRef,
            0f
        )
    }

    @Test
    fun testC_CustomPresetPrecedenceOverBuiltin() {
        val customWarm = CustomDspPreset(
            id = "warm",
            title = "My Custom Warm",
            params = List(15) { 1.88f }
        )

        val resolved = DspParamResolver.resolve("warm", listOf(customWarm), null)
        assertArrayEquals(
            "Custom preset with matching ID 'warm' must override builtin 'warm'",
            List(15) { 1.88f }.toFloatArray(),
            resolved,
            0f
        )
    }

    @Test
    fun testC_AmbienceModifierDoesNotMutateSourceParams() {
        val stageBase = DspPresets.builtinById("stage")!!.params.toFloatArray()
        val stageCopy = stageBase.copyOf()
        val preview = DspPreviewBridge.PreviewParams("stage", stageBase)

        val resolvedWithAmbience = DspParamResolver.resolve(
            presetId = "stage",
            customs = emptyList(),
            previewParams = preview,
            ambienceEnabled = true
        )

        assertArrayEquals(
            "Source preview array must NOT be mutated by ambience modifier",
            stageCopy,
            stageBase,
            0f
        )

        assertFalse(
            "Resolved array with ambience enabled must differ from base params",
            resolvedWithAmbience.contentEquals(stageCopy)
        )
    }

    @Test
    fun testH_AllBuiltinPresetsProduceDistinctParameterArrays() {
        val builtins = DspPresets.BUILTINS
        assertEquals("Must have 14 builtin presets", 14, builtins.size)

        for (i in builtins.indices) {
            for (j in i + 1 until builtins.size) {
                val presetA = builtins[i]
                val presetB = builtins[j]

                val paramsA = DspParamResolver.resolve(presetA.id, emptyList(), null)
                val paramsB = DspParamResolver.resolve(presetB.id, emptyList(), null)

                assertFalse(
                    "Builtin presets '${presetA.id}' and '${presetB.id}' MUST NOT produce identical parameter arrays!",
                    paramsA.contentEquals(paramsB)
                )
            }
        }
    }

    @Test
    fun testContract_FlatPresetNeutrality() {
        val flatParams = DspParamResolver.resolve("flat", emptyList(), null)
        val (bassDb, airDb) = DspParamResolver.presetShelfGainsDb(flatParams)

        assertEquals("Flat preset must produce 0 dB bass shelf gain", 0f, bassDb, 1e-4f)
        assertEquals("Flat preset must produce 0 dB air shelf gain", 0f, airDb, 1e-4f)
        assertEquals("Flat preset must have mud cut multiplier = 1.0", 1f, DspParamResolver.mudCutMultiplier(flatParams), 1e-4f)
    }
}

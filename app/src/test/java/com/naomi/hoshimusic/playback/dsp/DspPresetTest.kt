package com.naomi.hoshimusic.playback.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test untuk resolusi parameter preset DSP.
 *
 * Padanannya adalah `DspLifecycleTest` di `androidTest`: itu menguji siklus
 * hidup *runtime* DSP (library termuat, configure, flush, reset). Berkas ini
 * menguji *preset* — parameter apa yang sebenarnya dikirim ke mesin.
 *
 * Setiap `defect` di bawah diberi label karena masing-masing pernah menjadi
 * penyebab nyata di perangkat:
 *
 *  - DEFECT 1: `previewParams` membayangi `dspPresetId` secara permanen
 *              sehingga semua preset terdengar sama persis.
 *  - DEFECT 5: `prm[5]` / `IDX_TRANSIENT` tidak pernah dibaca native.
 *  - DEFECT 6: preset `flat` ternyata bukan flat.
 *
 * Uji auditory (preset berbeda menghasilkan PCM berbeda lewat mesin native
 * asli) ada di `DspPresetEngineTest` pada `androidTest`, karena itu butuh
 * `.so` yang benar-benar termuat.
 */
class DspPresetTest {

    // ================================================================
    // DEFECT 1 - preview membayangi preset
    // ================================================================

    @Test
    fun `preview dari preset lain harus diabaikan`() {
        // Jembatan masih memegang array milik "warm"...
        val stale = DspPreviewBridge.PreviewParams(
            sourcePresetId = "warm",
            params = DspPresets.builtinById("warm")!!.params.toFloatArray()
        )

        // ...tapi preset yang aktif sudah "bright".
        val resolved = DspParamResolver.resolve(
            presetId = "bright",
            customs = emptyList(),
            previewParams = stale
        )

        assertArrayEquals(
            "Preview basi harus diabaikan; preset aktif yang menentukan",
            DspPresets.builtinById("bright")!!.params.toFloatArray(),
            resolved,
            0f
        )
    }

    @Test
    fun `preview milik preset aktif dipakai`() {
        val active = DspPreviewBridge.PreviewParams(
            sourcePresetId = "vocal",
            params = DspPresets.builtinById("vocal")!!.params.toFloatArray()
        )

        val resolved = DspParamResolver.resolve(
            presetId = "vocal",
            customs = emptyList(),
            previewParams = active
        )

        assertArrayEquals(
            "Preview yang masih relevan harus dipakai (jalur tanpa jeda)",
            DspPresets.builtinById("vocal")!!.params.toFloatArray(),
            resolved,
            0f
        )
    }

    @Test
    fun `berpindah preset menghasilkan parameter baru bukan array lama`() {
        // Regresi inti dari gejala "semua preset sama": setelah satu tap,
        // bridge memegang array itu selamanya.
        val first = DspPreviewBridge.PreviewParams(
            sourcePresetId = "warm",
            params = DspPresets.builtinById("warm")!!.params.toFloatArray()
        )
        val afterFirstTap = DspParamResolver.resolve("warm", emptyList(), first)

        // Pengguna lalu memilih preset lain. Bridge TIDAK dikosongkan --
        // itulah skenario yang dulu merusak.
        val afterSecondTap = DspParamResolver.resolve("bass_boost", emptyList(), first)

        assertFalse(
            "Dua preset berbeda WAJIB menghasilkan parameter berbeda",
            afterFirstTap.contentEquals(afterSecondTap)
        )
        assertArrayEquals(
            "Preset kedua harus benar-benar memakai parameter bass_boost",
            DspPresets.builtinById("bass_boost")!!.params.toFloatArray(),
            afterSecondTap,
            0f
        )
    }

    @Test
    fun `semua preset bawaan menghasilkan parameter yang berbeda satu sama lain`() {
        val ids = DspPresets.BUILTINS.map { it.id }

        // Determinisme: resolusi yang sama harus menghasilkan nilai yang sama.
        ids.forEach { id ->
            val a = DspParamResolver.resolve(id, emptyList(), null)
            val b = DspParamResolver.resolve(id, emptyList(), null)
            assertArrayEquals("Resolusi harus deterministik untuk '$id'", a, b, 0f)
        }

        // Dan tidak ada dua preset yang boleh menghasilkan array identik.
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = DspParamResolver.resolve(ids[i], emptyList(), null)
                val b = DspParamResolver.resolve(ids[j], emptyList(), null)
                assertFalse(
                    "Preset '${ids[i]}' dan '${ids[j]}' menghasilkan parameter IDENTIK -- " +
                        "salah satunya tidak akan pernah terdengar",
                    a.contentEquals(b)
                )
            }
        }
    }

    @Test
    fun `PreviewParams memakai isi array untuk equals dan hashCode`() {
        // FloatArray memakai identitas referensinya. Tanpa equals berbasis isi,
        // StateFlow dan distinctUntilChanged akan menganggap dua array berbeda
        // sebagai nilai yang sama dan lewati emisi yang dibutuhkan.
        val a = DspPreviewBridge.PreviewParams("warm", floatArrayOf(1f, 2f, 3f))
        val b = DspPreviewBridge.PreviewParams("warm", floatArrayOf(1f, 2f, 3f))
        val c = DspPreviewBridge.PreviewParams("warm", floatArrayOf(1f, 2f, 4f))
        val d = DspPreviewBridge.PreviewParams("bright", floatArrayOf(1f, 2f, 3f))

        assertEquals("Isi sama harus dianggap sama", a, b)
        assertEquals("Hash harus sama", a.hashCode(), b.hashCode())
        assertNotEquals("Isi beda harus dianggap beda", a, c)
        assertNotEquals("Preset asal beda harus dianggap beda", a, d)
    }

    @Test
    fun `modifier ambience tidak memutasi array milik jembatan`() {
        val bridgeArray = DspPresets.builtinById("stage")!!.params.toFloatArray()
        val bridge = DspPreviewBridge.PreviewParams("stage", bridgeArray)
        val before = bridgeArray.copyOf()

        val resolved = DspParamResolver.resolve(
            presetId = "stage",
            customs = emptyList(),
            previewParams = bridge,
            ambienceEnabled = true
        )

        assertArrayEquals(
            "Array jembatan tidak boleh ikut termutasi modifier ambience",
            before,
            bridgeArray,
            0f
        )
        assertFalse(
            "Modifier ambience harus benar-benar mengubah hasil",
            resolved.contentEquals(before)
        )
        assertTrue(
            "Ambience harus menaikkan lebar panggung",
            resolved[DspPresets.IDX_WIDTH] > before[DspPresets.IDX_WIDTH]
        )
    }

    // ================================================================
    // Precedence preset kustom
    // ================================================================

    @Test
    fun `preset kustom mengalahkan preset bawaan`() {
        val customs = listOf(
            CustomDspPreset("warm", "Warm Buatan Saya", List(DspPresets.PARAM_COUNT) { 1.75f })
        )
        val resolved = DspParamResolver.resolve("warm", customs, null)
        assertArrayEquals(
            "Preset kustom dengan id sama harus menang",
            List(DspPresets.PARAM_COUNT) { 1.75f }.toFloatArray(),
            resolved,
            0f
        )
    }

    @Test
    fun `preset tidak dikenal jatuh ke default netral`() {
        val resolved = DspParamResolver.resolve("tidak-ada-preset-ini", emptyList(), null)
        assertArrayEquals(
            "Preset asing harus memakai DEFAULT_PARAMS",
            DspPresets.DEFAULT_PARAMS.toFloatArray(),
            resolved,
            0f
        )
    }

    // ================================================================
    // DEFECT 6 - preset `flat` ternyata bukan flat
    // ================================================================

    @Test
    fun `flat tidak boleh imposing pemotongan bass dan udara`() {
        val flat = DspParamResolver.resolve("flat", emptyList(), null)

        val (bassDb, airDb) = DspParamResolver.presetShelfGainsDb(flat)
        assertEquals(
            "Preset 'flat' tidak boleh memotong bass (0f = -4.5 dB)",
            0f, bassDb, 1e-4f
        )
        assertEquals(
            "Preset 'flat' tidak boleh memotong udara (0f = -3.5 dB)",
            0f, airDb, 1e-4f
        )

        assertEquals(
            "Preset 'flat' tidak boleh memperdalam mud cut jadi 4x",
            1f, DspParamResolver.mudCutMultiplier(flat), 1e-4f
        )
    }

    @Test
    fun `reference benar-benar netral di semua indeks`() {
        val reference = DspParamResolver.resolve(DspPresets.REFERENCE_ID, emptyList(), null)
        assertArrayEquals(
            "Reference harus seluruhnya 1.0",
            FloatArray(DspPresets.PARAM_COUNT) { 1f },
            reference,
            0f
        )
        assertEquals(
            "Reference tidak boleh punya shelf bass",
            0f, DspParamResolver.presetShelfGainsDb(reference).first, 1e-4f
        )
        assertFalse(
            "Reference adalah baseline, harus nonaktif secara fungsional",
            DspPresets.builtinById(DspPresets.REFERENCE_ID)!!.isEffectivelyActive()
        )
    }

    @Test
    fun `flat berbeda dari reference`() {
        // Kalau keduanya identik, salah satunya tidak berguna sebagai kontrol.
        val flat = DspParamResolver.resolve("flat", emptyList(), null)
        val reference = DspParamResolver.resolve(DspPresets.REFERENCE_ID, emptyList(), null)
        assertFalse(
            "Preset 'flat' tidak boleh identik dengan 'reference'",
            flat.contentEquals(reference)
        )
    }

    // ================================================================
    // DEFECT 5 - IDX_TRANSIENT dulu parameter mati
    // ================================================================

    @Test
    fun `hampir semua indeks parameter bervariasi antar preset`() {
        // Kalau suatu indeks konstan di semua preset, mesin tidak bisa
        // membedakannya -- persis kondisi prm[5] yang dulu tidak pernah dibaca.
        val perIndex = Array(DspPresets.PARAM_COUNT) { mutableSetOf<Float>() }
        DspPresets.BUILTINS.forEach { preset ->
            preset.params.forEachIndexed { idx, value -> perIndex[idx].add(value) }
        }

        var varying = 0
        val indicesToCheck = (0 until DspPresets.PARAM_COUNT)
            .filter { it != DspPresets.IDX_TRANSIENT }

        for (idx in indicesToCheck) {
            if (perIndex[idx].size > 1) varying++
        }

        assertTrue(
            "Hanya $varying dari ${indicesToCheck.size} indeks yang bervariasi antar preset -- " +
                "indeks yang konstan tidak akan pernah mengubah suara",
            varying >= indicesToCheck.size - 1
        )
    }

    @Test
    fun `IDX_TRANSIENT punya nilai non-unity di sebagian preset`() {
        val varied = DspPresets.BUILTINS.count { it.params[DspPresets.IDX_TRANSIENT] != 1.0f }
        assertTrue(
            "IDX_TRANSIENT harus punya nilai non-unity di beberapa preset; " +
                "kalau tidak, perbaikan prm[5] tidak benar-benar teruji",
            varied >= 3
        )
    }

    // ================================================================
    // Keamanan parameter
    // ================================================================

    @Test
    fun `semua preset menghasilkan parameter valid setelah modifier ambience`() {
        DspPresets.BUILTINS.forEach { preset ->
            val resolved = DspParamResolver.resolve(
                presetId = preset.id,
                customs = emptyList(),
                previewParams = null,
                ambienceEnabled = true
            )
            assertEquals(
                "Panjang harus tetap ${DspPresets.PARAM_COUNT}",
                DspPresets.PARAM_COUNT, resolved.size
            )
            resolved.forEachIndexed { idx, value ->
                assertTrue(
                    "Preset '${preset.id}' indeks $idx = $value bukan angka finit",
                    value.isFinite()
                )
                assertTrue(
                    "Preset '${preset.id}' indeks $idx = $value di luar [0,2] " +
                        "(mesin clamp ke rentang ini)",
                    value in 0f..2f
                )
            }
        }
    }

    @Test
    fun `setiap resolusi mengembalikan array baru`() {
        // `DspPresets.BUILTINS` menyimpan `List<Float>`. Kalau caller
        // menerima array yang menunjuk ke data itu, modifier ambience akan
        // merusak preset tersebut untuk selamanya.
        val first = DspParamResolver.resolve("warm", emptyList(), null)
        val second = DspParamResolver.resolve("warm", emptyList(), null)
        assertFalse("Setiap resolusi harus mengembalikan array baru", first === second)
    }
}

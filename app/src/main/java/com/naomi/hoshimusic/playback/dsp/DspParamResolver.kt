package com.naomi.hoshimusic.playback.dsp

/**
 * Resolusi parameter preset -> array 15 float, sebagai fungsi MURNI.
 *
 * ## Kenapa dipisah dari [DspConfigRepository]
 *
 * Gejalanya dulu "semua preset terdengar sama" bisa lolos ke rilis begitu saja
 * karena logikanya tertinggal di dalam lambda `combine` yang butuh DataStore +
 * `Flow`. Tidak ada satu pun unit test yang bisa memanggilnya.
 *
 * Logika ini sekarang berdiri sendiri tanpa Android, jadi setiap aturan di
 * bawah bisa diuji langsung di JVM.
 */
object DspParamResolver {

    /** Pengali ambience untuk tiap indeks (lihat [applyAmbience]). */
    private const val AMBIENCE_WIDTH = 1.25f
    private const val AMBIENCE_SATURATE = 1.15f
    private const val AMBIENCE_DEESSER = 0.85f

    /**
     * @param presetId preset aktif menurut DataStore.
     * @param customs preset kustom milik pengguna.
     * @param previewParams override berumur pendek dari [DspPreviewBridge].
     * @param ambienceEnabled apakah modifier ruang/virtual room aktif.
     * @return array BARU sepanjang [DspPresets.PARAM_COUNT]; array yang
     *         diterima sebagai masukan tidak pernah dimutasi.
     */
    fun resolve(
        presetId: String,
        customs: List<CustomDspPreset>,
        previewParams: DspPreviewBridge.PreviewParams?,
        ambienceEnabled: Boolean = false
    ): FloatArray {
        // [DEFECT 1] Jembatan hanya dipercaya kalau array-nya masih milik preset
        // yang sedang aktif.
        //
        // `previewParams` duluan `FloatArray?` biasa yang SELALU menang begitu
        // diisi sekali, tanpa pernah dikosongkan. Begitu pengguna menyentuh
        // dialog preset satu kali, `presetId` kehilangan seluruh pengaruhnya
        // dan semua preset berikutnya menghasilkan suara yang sama persis.
        //
        // Sekarang array membawa `sourcePresetId`, jadi begitu `presetId`
        // berubah — tap manual, recall rute, apa pun — array basi otomatis
        // diabaikan, tanpa ada jalur yang perlu mengingat mengosongkannya.
        val source: FloatArray =
            if (previewParams?.sourcePresetId == presetId) {
                previewParams.params
            } else {
                DspPresets.resolveParams(presetId, customs).toFloatArray()
            }

        // Salin: `applyAmbience` menulis ke dalam array ini, dan array milik
        // jembatan tidak boleh ikut berubah diam-diam.
        val params = source.copyOf()
        if (ambienceEnabled) applyAmbience(params)
        return params
    }

    /**
     * [Issue 8] Ambience Mode: modifier refleksi ruang.
     *
     * Mengubah lebar panggung, saturasi (glue), dan sensitivitas de-esser.
     */
    private fun applyAmbience(params: FloatArray) {
        params[DspPresets.IDX_WIDTH] =
            (params[DspPresets.IDX_WIDTH] * AMBIENCE_WIDTH).coerceAtMost(2.0f)
        params[DspPresets.IDX_SATURATE] =
            (params[DspPresets.IDX_SATURATE] * AMBIENCE_SATURATE).coerceAtMost(2.0f)
        params[DspPresets.IDX_DEESSER] =
            (params[DspPresets.IDX_DEESSER] * AMBIENCE_DEESSER).coerceAtLeast(0f)
    }

    /**
     * Gain dB yang dihitung native untuk shelf preset.
     *
     * Cerminan dari `DspEngine::updatePresetShelves()`:
     * ```
     *   presetBassDb = (pBass - 1) * 4.5
     *   presetAirDb  = (pAir  - 1) * 3.5
     * ```
     *
     * Dipakai unit test untuk menangkap [DEFECT 6]: `0f` bukan "netral", ia
     * menghasilkan shelf yang benar-benar memotong. Menyetel `flat` ke `0f`
     * berarti memotong 4,5 dB bass dan 3,5 dB udara — persis kebalikan dari
     * yang biasanya diharapkan pengguna.
     */
    fun presetShelfGainsDb(params: FloatArray): Pair<Float, Float> {
        val bass = params.getOrElse(DspPresets.IDX_BASS) { 1f }
        val air = params.getOrElse(DspPresets.IDX_AIR) { 1f }
        return Pair((bass - 1.0f) * 4.5f, (air - 1.0f) * 3.5f)
    }

    /**
     * Pengali mud-cut yang dipakai telemetry:
     * ```
     *   warmDiv = 1 / max(pWarm, 0.25)
     * ```
     *
     * `pWarm = 0f` memberi pengali 4x — itu sebabnya `flat` lama memotong
     * 280 Hz jauh lebih dalam daripada bass boost mana pun.
     */
    fun mudCutMultiplier(params: FloatArray): Float {
        val warm = params.getOrElse(DspPresets.IDX_WARMTH) { 1f }
        return 1.0f / maxOf(warm, 0.25f)
    }
}

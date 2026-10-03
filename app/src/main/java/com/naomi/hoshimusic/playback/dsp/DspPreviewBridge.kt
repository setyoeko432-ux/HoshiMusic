package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.data.PeqProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Jembatan override DSP berumur pendek untuk penerapan tanpa jeda.
 *
 * ## [DEFECT 1] Mengapa array sekarang membawa `sourcePresetId`
 *
 * Dulu `previewParams` hanyalah `FloatArray?` yang ditulis setiap kali preset
 * dipilih, dan TIDAK PERNAH dikembalikan ke `null`. Di `DspConfigRepository`
 * sendiri CABUT-nya seperti ini:
 *
 * ```kotlin
 * val params = previewParams ?: DspPresets.resolveParams(presetId, customs)
 * ```
 *
 * Begitu pengguna menyentuh dialog preset sekali saja, `previewParams` selalu
 * menang atas `presetId` — selamanya. Semua preset berikutnya menghasilkan
 * suara yang sama persis, persis seperti gejala yang dilaporkan.
 *
 * Dua jalur lain memperburuknya:
 *
 * 1. `PlaybackViewModel.recallPresetForRoute()` menulis `dspPresetId` langsung
 *    ke DataStore tanpa pernah menyentuh jembatan ini. UI berganti ke preset
 *    hasil recall, mesin tetap menjalankan array lama.
 * 2. `PreferenceCoordinator.setDspPreset()` menulis `dspPresetId` lebih dulu,
 *    lalu menunda pada `customDspPresets.first()` (I/O DataStore) sebelum
 *    mendorong array. Kalau coroutine-nya dibatalkan di tengah (sheet ditutup),
 *    preset tersimpan sudah berubah tetapi array tidak pernah ikut terpush.
 *
 * Perbaikannya bukan "ingat untuk mengosongkan jembatan" — setiap jalur baru
 * akan lupa. Array sekarang mencatat preset mana yang menghasilkannya, dan
 * `DspConfigRepository` hanya memakainya kalau `presetId` masih sama. Begitu
 * `presetId` berubah — lewat tap manual, recall rute, atau apa pun — array
 * basi otomatis diabaikan dan `resolveParams` yang menentukan. Self-healing,
 * tanpa state yang harus dibersihkan manual.
 */
object DspPreviewBridge {
    private val _previewPeq = MutableStateFlow<PeqProfile?>(null)
    val previewPeq = _previewPeq.asStateFlow()

    /**
     * Array parameter beserta preset asal-usulnya.
     *
     * `equals`/`hashCode` dihitung dari ISI array: `FloatArray` memakai
     * identitas referensinya, jadi tanpa ini `MutableStateFlow` dan
     * `distinctUntilChanged` akan menganggap dua array berbeda sebagai
     * nilai yang sama dan lewati emisi yang sebenarnya perlu.
     */
    data class PreviewParams(
        val sourcePresetId: String,
        val params: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PreviewParams) return false
            return sourcePresetId == other.sourcePresetId && params.contentEquals(other.params)
        }

        override fun hashCode(): Int = 31 * sourcePresetId.hashCode() + params.contentHashCode()
    }

    private val _previewParams = MutableStateFlow<PreviewParams?>(null)
    val previewParams = _previewParams.asStateFlow()

    fun setPreviewPeq(profile: PeqProfile?) {
        _previewPeq.value = profile
    }

    /** Dorong parameter hasil resolusi [presetId] supaya langsung berbunyi. */
    fun setPreviewParams(presetId: String, params: FloatArray) {
        _previewParams.value = PreviewParams(presetId, params)
    }

    fun clearPreviewParams() {
        _previewParams.value = null
    }
}

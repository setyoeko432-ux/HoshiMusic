package com.naomi.hoshimusic.playback.dsp

import androidx.compose.runtime.Immutable

import com.naomi.hoshimusic.data.PeqBand
import java.util.Locale

/**
 * Preset DSP bawaan / kustom. Parameter (15 float) diskala langsung di dalam
 * mesin C++ (bukan sekadar label), jadi tiap preset benar-benar mengubah suara.
 */
@Immutable
data class DspPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val params: List<Float>
) {
    fun isEffectivelyActive(): Boolean = params.any { it != 1.0f }
}

@Immutable
data class CustomDspPreset(
    val id: String,
    val title: String,
    val params: List<Float>
)

object DspPresets {
    const val PARAM_COUNT = 15
    const val REFERENCE_ID = "reference"

    // Index parameter (harus sinkron dengan hoshi_dsp_core.cpp)
    const val IDX_BASS = 0       // Virtual bass + low body
    const val IDX_VOCAL = 1      // Vocal presence 2-4kHz & consonant pluck
    const val IDX_AIR = 2        // High shelf "air" preset (center ~7 kHz di native)
    const val IDX_WARMTH = 3     // Kedalaman potongan mud/low-mid (200-500Hz)
    const val IDX_EXCITER = 4    // Harmonic exciter >6kHz
    const val IDX_TRANSIENT = 5  // Punch transien (M/S gate)
    const val IDX_WIDTH = 6      // Lebar stereo (side gain + presence)
    const val IDX_DEESSER = 7    // Sensitivitas de-esser
    const val IDX_CROSSFEED = 8  // Binaural crossfeed
    const val IDX_AGC = 9        // Batas pre-gain AGC
    const val IDX_LIMITER = 10   // Ceiling peak limiter
    const val IDX_LOUDNESS = 11  // Kompensasi Fletcher-Munson
    const val IDX_PUNCH = 12     // True transient shaper: attack (Punch)
    const val IDX_BODY = 13      // True transient shaper: sustain (Body)
    const val IDX_SATURATE = 14  // Saturasi band-split (tape glue low-band + waveshaper global)

    /** Perilaku default yang sudah di-tuning: semua skala 1.0 */
    val DEFAULT_PARAMS: List<Float> = List(PARAM_COUNT) { 1f }

    val BUILTINS: List<DspPreset> = listOf(
        DspPreset(
            id = "reference", title = "Reference", subtitle = "Baseline mesin (skala 1.0) — warna berasal dari kurva adaptif bawaan",
            params = listOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
        ),
        DspPreset(
            id = "dev_preference", title = "Preferensi Developer",
            subtitle = "Signature developer: bright terbuka, vokal maju, imaging luas, transien instrumen tajam",
            // Signature khas developer (tidak lagi identik dgn Reference):
            // lebih bright & berkilau, vokal/konsonan maju, side imaging dilebarkan,
            // pukulan transien instrumen ditajamkan; de-esser ikut naik agar tetap bersih.
            params = listOf(1.00f, 1.30f, 1.35f, 0.90f, 1.25f, 1.15f, 1.20f, 1.35f, 1f, 1f, 1f, 1f, 1.30f, 0.95f, 1.10f)
        ),
        DspPreset(
            id = "analog", title = "Analog Warmth", subtitle = "Pita analog virtual: glue hangat, punch terjaga — showcase saturasi",
            // Diambil dari 12 nilai Warm lalu ditambah Punch/Body/Saturate khas pita analog:
            params = listOf(1.15f, 0.95f, 0.70f, 1.40f, 0.50f, 0.90f, 1.00f, 1.00f, 1.10f, 1f, 1f, 1f, 1.10f, 1.20f, 1.60f)
        ),
        DspPreset(
            id = "flat", title = "Flat",
            subtitle = "Nol pewarnaan tambahan di atas baseline mesin (AGC & limiter tetap aktif)",
            // [DEFECT 6] Nilai IDX_BASS/IDX_AIR/IDX_WARMTH dinaikkan ke 1.0f.
            //
            // Semuanya 0f somber tidak netral, padahal tidak. Di native:
            //   presetBassDb = (pBass - 1) * 4.5  -> 0f = -4.5 dB low shelf @105 Hz
            //   presetAirDb  = (pAir  - 1) * 3.5  -> 0f = -3.5 dB high shelf @7 kHz
            //   warmDiv      = 1 / max(pWarm, 0.25) -> 0f = 4x, jadi mud cut 280 Hz
            //                                      jadi -4.8 s/d -8 dB
            // Jadi "Flat" justru memotong bass dan udara — persis kebalikan dari
            // yang biasanya diharapkan pengguna, dan terbaca sebagai "bypass".
            //
            // 1.0f membuat shelf 0 dB dan warmDiv 1.0 (tanpa mud cut berlebih).
            // Sisa adaptive baseline mesin tetap berlaku, sama seperti preset
            // `reference` — itu perilaku mesin, bukan preset ini.
            params = listOf(1f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f)
        ),
        DspPreset(
            id = "warm", title = "Warm", subtitle = "Body 200–500 Hz naik, treble rileks — analog & nyaman",
            params = listOf(1.15f, 0.95f, 0.70f, 1.40f, 0.50f, 0.90f, 1.00f, 1.00f, 1.10f, 1f, 1f, 1f, 0.90f, 1.25f, 1.50f)
        ),
        DspPreset(
            id = "bright", title = "Bright", subtitle = "Kilau udara & detail dinaikkan — terbuka namun terkontrol",
            params = listOf(0.90f, 1.05f, 1.50f, 0.85f, 1.40f, 1.05f, 1.05f, 1.40f, 1.00f, 1f, 1f, 1f, 1.20f, 0.85f, 0.80f)
        ),
        DspPreset(
            id = "soft", title = "Soft", subtitle = "Transien & treble diredam, AGC lembut — halus di telinga",
            params = listOf(0.95f, 0.80f, 0.85f, 1.10f, 0.70f, 0.70f, 1.05f, 1.00f, 1.20f, 1.15f, 0.70f, 1f, 0.75f, 1.10f, 1.20f)
        ),
        DspPreset(
            id = "bass_boost", title = "Bass Boost", subtitle = "Low shelf ~105 Hz didorong kuat — dentum dalam & tebal",
            params = listOf(1.80f, 0.95f, 0.95f, 1.20f, 0.90f, 1.00f, 1.00f, 1.00f, 1.00f, 1f, 1f, 1f, 1.35f, 1.20f, 1.40f)
        ),
        DspPreset(
            id = "vocal", title = "Vocal Clarity", subtitle = "Presence 2–4 kHz maju — vokal jernih di depan",
            params = listOf(0.90f, 1.45f, 1.05f, 0.95f, 1.00f, 1.00f, 1.00f, 1.25f, 1.00f, 1f, 1f, 1f, 1.15f, 1.00f, 1.25f)
        ),
        DspPreset(
            id = "stage", title = "Instrum. Stage", subtitle = "Stereo melebar, transien terjaga — panggung instrument luas",
            params = listOf(1.00f, 0.90f, 1.15f, 1.00f, 1.10f, 1.15f, 1.30f, 1.05f, 1.00f, 1f, 1f, 1f, 1.25f, 1.05f, 1.00f)
        ),
        DspPreset(
            id = "v_shape", title = "V-Shaped", subtitle = "Bass & treble energik, midland mundur — karakter V ala Head-Fi",
            params = listOf(1.50f, 0.75f, 1.35f, 0.90f, 1.20f, 1.05f, 1.15f, 1.20f, 1.00f, 1f, 1f, 1f, 1.30f, 1.15f, 1.20f)
        ),
        DspPreset(
            id = "late_night", title = "Late Night", subtitle = "Dinamika dipadatkan, loudness lembut — aman untuk volume rendah",
            params = listOf(1.05f, 0.90f, 0.80f, 1.05f, 0.70f, 0.75f, 1.00f, 1.00f, 1.30f, 0.80f, 0.55f, 1.30f, 0.70f, 1.00f, 1.30f)
        ),
        DspPreset(
            id = "tube_warmth", title = "Vintage Tube", subtitle = "Karakter tabung klasik: harmoni hangat, low-end tebal, dan high yang halus",
            params = listOf(1.10f, 1.00f, 0.85f, 1.45f, 0.80f, 1.00f, 0.95f, 1.10f, 1.00f, 1f, 1f, 1.15f, 1.05f, 1.40f, 1.70f)
        ),
        DspPreset(
            id = "air_shimmer", title = "Crystal Air", subtitle = "Ekstrem high-end detail: kilau instrumen dan separasi ruang yang maksimal",
            params = listOf(0.95f, 1.00f, 1.80f, 0.80f, 1.50f, 1.10f, 1.15f, 1.40f, 1.00f, 1f, 1f, 1f, 1.15f, 0.90f, 0.75f)
        )
    )

    fun builtinById(id: String): DspPreset? = BUILTINS.firstOrNull { it.id == id }

    /** Ambil parameter preset; kustom dicek dulu lalu bawaan, selain itu default netral. */
    fun resolveParams(presetId: String, customs: List<CustomDspPreset>): List<Float> {
        customs.firstOrNull { it.id == presetId }?.let { return it.params }
        builtinById(presetId)?.let { return it.params }
        return DEFAULT_PARAMS
    }

    /**
     * Parser AutoEq: dukung baris gaya EqualizerAPO
     *   `Filter 1: ON PK Fc 55 Hz Gain 2.5 dB Q 1.000`
     * (PK→peaking, LS/LSC→low shelf, HS/HSC→high shelf; toleran kapital/spasi ekstra)
     * DAN fallback baris tripel `Fc,Gain,Q`. Maksimal 10 band.
     * Mendukung pembacaan `Preamp: -X.X dB`.
     */
    fun parseAutoEq(text: String): Pair<List<PeqBand>, Float> {
        val bands = ArrayList<PeqBand>(10)
        var preamp = 0f
        val filterLine = Regex(
            pattern = "(?i)^\\s*Filter\\s*\\d+.*?(PK|LS|LSC|HS|HSC).*?Fc\\s*([0-9]+(?:\\.[0-9]+)?).*?Gain\\s*(-?[0-9]+(?:\\.[0-9]+)?).*?Q\\s*([0-9]+(?:\\.[0-9]+)?)"
        )
        val tripleLine = Regex(
            pattern = "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[,;]\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*[,;]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$"
        )
        val preampLine = Regex(pattern = "(?i)^\\s*Preamp\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*dB")

        text.lineSequence().forEach { line ->
            val p = preampLine.find(line)
            if (p != null) {
                preamp = p.groupValues[1].toFloatOrNull() ?: preamp
                return@forEach
            }

            if (bands.size >= 10) return@forEach
            val m = filterLine.find(line)
            if (m != null) {
                val type = when (m.groupValues[1].uppercase(Locale.US)) {
                    "LS", "LSC" -> 1
                    "HS", "HSC" -> 2
                    else -> 0
                }
                bands += PeqBand(type, m.groupValues[2].toFloatOrNull() ?: return@forEach, m.groupValues[3].toFloatOrNull() ?: return@forEach, m.groupValues[4].toFloatOrNull() ?: 0.707f)
                return@forEach
            }
            // Fallback: tripel `Fc,Gain,Q` → peaking
            val t = tripleLine.find(line)
            if (t != null) {
                val fc = t.groupValues[1].toFloatOrNull()
                val gain = t.groupValues[2].toFloatOrNull()
                val q = t.groupValues[3].toFloatOrNull()
                if (fc != null && gain != null && q != null && fc > 0f) bands += PeqBand(0, fc, gain, q)
            }
        }
        return Pair(bands.take(10), preamp)
    }
}

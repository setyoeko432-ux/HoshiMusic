package com.naomi.hoshimusic.playback.dsp

data class DspTelemetry(
    val bassRatio: Float = 0.33f,
    val midRatio: Float = 0.34f,
    val highRatio: Float = 0.33f,
    val curMudCutDb: Float = -3.2f,
    val curLowMidGapCutDb: Float = -1.8f,
    val curFormantBoostDb: Float = 1.6f,
    val curVocalBoostDb: Float = 3.0f,
    val curConsonantBoostDb: Float = 2.6f,
    val curAirBoostDb: Float = 3.6f,
    val curSideBoostDb: Float = 2.8f,
    val curDeEsserGainDb: Float = 0f,
    val curExciterAmount: Float = 0.09f,
    val curVirtualBassAmount: Float = 0.0f,
    val curTransientDelta: Float = 0f,
    val peakEnvelope: Float = 0f,
    val limiterGainReductionDb: Float = 0f,
    val vocalEnergy: Float = 0f,      // [V21]: Real-time vocal energy for hybrid sync
    val totalFramesProcessed: Long = 0L,
    val requestedPeqRevision: Int = 0,
    val appliedPeqRevision: Int = 0,
    val nativeInitialized: Boolean = false,
    val dominantBand: String = "PRISTINE BRIGHT",
    val sampleRate: Int = 44100,
    /**
     * TRACE: crossfade wet/dry rantai adaptive.
     *
     * Nilai ~0 berarti `processFrame()` dijalankan tapi hasilnya dicampur
     * kembali ke sinyal kering, sehingga output identik dengan input. Ini
     * satu-satunya cara DSP "diproses tapi tidak terdengar".
     */
    val adspWet: Float = 0f,
    /** [TRACE] Atomik `adaptiveDspEnabled` yang dibaca sisi native. */
    val adspEnabled: Boolean = false,
    /** [TRACE] Gain pre-gain AGC yang sudah merambat. */
    val agcPreGain: Float = 1f,
    /** [TRACE] State detektor AGC — source of truth untuk targetPreGain. */
    val slowRms: Float = 0.1f,
    /** [TRACE] Nilai `l` tepat saat masuk loop process() (preamp sudah
     *  diaplikasikan, sebelum filter apapun) — verifikasi buffer masuk
     *  benar-benar berisi PCM, bukan kosong/0. */
    val dbgLAtEntry: Float = 0f,
    /** [TRACE] Nilai `l` tepat sebelum detektor AGC — bila ~0 padahal
     *  output bukan nol, berarti rantai filter merusak/menghilangkan sinyal. */
    val dbgLAtDetector: Float = 0f,
    /** [TRACE] Nilai wet persis pada saat crossfade dihitung — bila 1.0
     *  tapi output masih identik input, berarti filter tidak mengubah sinyal. */
    val dbgWetAtCrossfade: Float = 0f,
    /** [TRACE] Koefisien shelf bass statis: M0/M1 dari TPT low-shelf dan
     *  G = tan(PI*fc/fs)*sqrt(a). M0 != 1 berarti shelf aktif. */
    val dbgShelfM0: Float = 0f,
    val dbgShelfM1: Float = 0f,
    val dbgShelfG: Float = 0f,
    val medRms: Float = 0.1f
)

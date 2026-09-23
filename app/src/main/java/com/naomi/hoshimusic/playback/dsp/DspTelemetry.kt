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
    val sampleRate: Int = 44100
)

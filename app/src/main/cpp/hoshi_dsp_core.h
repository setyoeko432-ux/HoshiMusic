#pragma once
#include <atomic>
#include <cstdint>

namespace hoshi {

static inline int nextPowerOfTwo(int n) {
    if (n <= 0) return 1;
    n--;
    n |= n >> 1;
    n |= n >> 2;
    n |= n >> 4;
    n |= n >> 8;
    n |= n >> 16;
    n++;
    return n;
}

class TptSvf {
private:
    float s1 = 0.0f, s2 = 0.0f;
    float g = 0.0f, k = 0.0f;
    float m0 = 1.0f, m1 = 0.0f, m2 = 0.0f;
public:
    void reset();
    float process(float x);
    void setPeaking(float fs, float fc, float gainDb, float q);
    void setHighShelf(float fs, float fc, float gainDb, float q);
    void setLowShelf(float fs, float fc, float gainDb, float q);
    void setLowPass(float fs, float fc, float q);
    void setHighPass(float fs, float fc, float q);
    void setAllPass(float fs, float fc, float q);

    void setBandPass(float fs, float fc, float q);
};

struct DspTelemetryData {
    float bassRatio = 0.33f;
    float midRatio = 0.34f;
    float highRatio = 0.33f;
    float curMudCutDb = -1.5f;
    float curLowMidGapCutDb = -1.2f;
    float curFormantBoostDb = 1.0f;
    float curVocalBoostDb = 1.8f;
    float curConsonantBoostDb = 0.8f;
    float curAirBoostDb = 1.2f;
    float curSideBoostDb = 1.0f;
    float curDeEsserGainDb = 0.0f;
    float curExciterAmount = 0.02f;
    float curVirtualBassAmount = 0.0f;
    float curTransientDelta = 0.0f;
    float peakEnvelope = 0.0f;
    float limiterGainReductionDb = 0.0f;
    float vocalEnergy = 0.0f;        // [V21]: Real-time vocal energy for hybrid sync
    uint64_t totalFramesProcessed = 0;
    uint32_t appliedPeqRevision = 0;
    bool nativeInitialized = false;
    char dominantBand[32] = "NATURAL BRIGHT";
    int sampleRate = 44100;
};

class DspEngine {
public:
    static constexpr int PARAM_COUNT = 15;

    DspEngine();
    ~DspEngine(); // Priority 3.A
    void init(int fs);
    void process(float* buffer, int numSamples);
    void flush();
    void reset();
    void setSystemVolume(float volume);
    DspTelemetryData getTelemetry();

    // User control: master bypass + preset scaling (1.0f = tuned default)
    void setEnabled(bool on);            // DEPRECATED: use setAdaptiveDspEnabled
    bool isEnabled() const;

    void setAdaptiveDspEnabled(bool on);
    void setPeqEnabled(bool on);

    void setParams(const float* p, int count); // copies into atomics, clamped [0..2]
    // BT Clarity Guard: auto de-mud saat output speaker bluetooth generik (SBC)
    void setBtClarity(bool on);

    // Ambience Mode (Eksperimen): jaringan early-reflection FDN mini (default OFF)
    void setAmbience(bool on);

    // Preamp linear (1.0 = netral), diterapkan tepat setelah konversi int16→float
    void setPreampGain(float g);

    // Crossfeed preset earphone: 0=Chu Moy, 1=Jan Meier, 2=RME Wide
    void setCrossfeedPreset(int preset);

    // PEQ cascade koreksi headphone (impor AutoEq): maks 10 band.
    // type: 0=PEAKING, 1=LOW_SHELF, 2=HIGH_SHELF
    void setPeqBand(int slot, int type, float fc, float gain, float q);
    void setPeqCount(int n);

    // [V35]: Sample-accurate Snapshot System
    struct PeqSnapshot {
        bool enabled = false;
        float preampDb = 0.0f;
        int count = 0;
        int types[10];
        float fc[10];
        float gain[10];
        float q[10];
        uint32_t revision = 0;
    };

    void applyPeqSnapshot(
        bool enabled, float preampDb, int count,
        const int* types, const float* fc, const float* gain, const float* q,
        uint32_t revision
    );

private:
    int sampleRate;
    void setupStaticCoefficients();
    void updateAdaptiveCoefficients(float dt);
    void updatePeqCoefficients(float dt);

    void processFrame(
        float& l, float& r,
        float pBass, float pVocal, float pAir, float pWarm, float pExciter,
        float pWidth, float pDeEsser, float pCrossfeed, float pAgc,
        float pLimiter, float pLoudness, float pPunch, float pBody, float pSat,
        float limiterCeiling, float baseFeed, float& currentVocalAccum
    );

    DspTelemetryData telemetryData;
    std::atomic<uint32_t> telemetrySeq{0};

    std::atomic<bool> enabled{false};
    std::atomic<bool> adaptiveDspEnabled{false};
    std::atomic<bool> peqEnabled{false};

    // [V35]: Atomic Double-Buffering for PEQ
    PeqSnapshot peqSnapshots[2];
    std::atomic<int> publishedSnapshotIdx{0};
    uint32_t lastProcessedRevision = 0xFFFFFFFF;

    // [V35]: Persistent smoothing states
    float curPreampLinear = 1.0f;
    float curPeqWet = 0.0f; // 0.0 = Dry/Bypass, 1.0 = Wet/Active
    float targetPeqWet = 0.0f;

    std::atomic<bool> btClarity{false};
    std::atomic<bool> ambience{false};
    std::atomic<float> prm[PARAM_COUNT];
    std::atomic<float> preampGain{1.0f};   // linear (10^(dB/20))
    std::atomic<int> xfeedPreset{0};       // 0=Chu Moy, 1=Jan Meier, 2=RME Wide
    std::atomic<float> systemVolume{1.0f}; // Fix: Atomic (Priority 2.2)

    TptSvf subSonicL, subSonicR;

    // Preset Static Tonal Shaping EQ filters
    TptSvf presetStaticLowShelfL, presetStaticLowShelfR;
    TptSvf presetStaticHighShelfL, presetStaticHighShelfR;

    // Band Detectors (HPF + LPF series for mid/sibilance)
    TptSvf analyzeLowL, analyzeLowR;
    TptSvf analyzeMidHpfL, analyzeMidHpfR, analyzeMidLpfL, analyzeMidLpfR;
    TptSvf analyzeHighL, analyzeHighR;

    TptSvf mSubCut, mMudCut, mLowMidGapCut, mBoxinessCut, mVocalFormant, mVocalPresence, mConsonantPluck, mUltraAirShelf;
    TptSvf mHarshGuard;              // B1: dip proteksi @3.5 kHz (skala AIR)
    TptSvf sLowCut, sPresence, sAirShelf, sDecorrelator1, sDecorrelator2;
    TptSvf bassExtractLpf, bassHarmonicHpf_gen, bassHarmonicLpf_gen, bassHarmonicHpf_final;
    TptSvf deEsserHpf, deEsserLpf, deEsserNotch;
    TptSvf essBand[3];               // C3: bank BP pelacak sibilance (5.8k/7.5k/9.2k)
    float essEnv[3] = {0.0f, 0.0f, 0.0f};
    float curNotchFc = 7500.0f;      // C3: posisi notch de-esser saat ini (di-glide)

    TptSvf exciterHpfL, exciterHpfR;
    TptSvf crossfeedLpfL, crossfeedLpfR;

    // Sub-band bass protector: batasi hanya komponen <150 Hz agar driver kecil
    // tak "fart-out" saat bass boost/saturasi mendorong level terlalu keras.
    TptSvf protSplitLpfL, protSplitLpfR;
    float protEnvL = 0.0f, protEnvR = 0.0f;

    // Koreksi headphone: cascade PEQ (maks 10 band), parameter disimpan di atomics
    static constexpr int PEQ_SLOTS = 10;
    TptSvf peqL[PEQ_SLOTS];
    TptSvf peqR[PEQ_SLOTS];
    float curPeqFc[PEQ_SLOTS];
    float curPeqGain[PEQ_SLOTS];
    float curPeqQ[PEQ_SLOTS];
    std::atomic<int> peqCount{0};
    std::atomic<int> peqType[PEQ_SLOTS];
    std::atomic<float> peqFc[PEQ_SLOTS];
    std::atomic<float> peqGain[PEQ_SLOTS];
    std::atomic<float> peqQ[PEQ_SLOTS];

    // K-Weighting detector (BS.1770-inspired) — agar AGC "mendengar" seperti telinga
    TptSvf kShelfL, kShelfR, kHpfL, kHpfR;
    // Auto-unmasking: detektor vokal di Mid + duck dinamis di Side
    TptSvf unmaskDetBp, unmaskSidePeak;

    // Band-split saturation & FDN ambience
    TptSvf satLowLpfL, satLowLpfR;   // LPF 160 Hz untuk low-band tape glue
    TptSvf erHpfL, erHpfR;           // HPF 250 Hz agar balikan early-reflection tetap airy
    float* erBufL = nullptr;         // Buffer delay melingkar early-reflection (FDN)
    float* erBufR = nullptr;
    int erCapacity = 0;              // Kapasitas dalam sampel (~130 ms)
    int erIdx = 0;

    float envLow = 0.001f, envMid = 0.001f, envHigh = 0.001f;
    float transFastM = 0.0f, transSlowM = 0.0f, transFastS = 0.0f, transSlowS = 0.0f;
    float dcOffsetL = 0.0f, dcOffsetR = 0.0f;
    float deEsserEnv = 0.0f, envelope = 0.0f, currentGain = 1.0f;
    int sampleCounter = 0;
    uint32_t ditherSeed = 0x1337BEEF;
    float tpPrevL = 0.0f, tpPrevR = 0.0f; // sampel sebelumnya utk estimator intersample

    float curMudCutDb = -1.5f, curLowMidGapCutDb = -1.2f, curFormantBoostDb = 1.0f, curVocalBoostDb = 1.8f;
    float curConsonantBoostDb = 0.8f, curAirBoostDb = 1.2f, curSideBoostDb = 1.0f;
    float curDeEsserGainDb = 0.0f, curExciterAmount = 0.02f, curVirtualBassAmount = 0.0f;

    float slowRms = 0.1f;
    float medRms = 0.1f;              // Envelope medium utk upward knee AGC
    float vocalEnv = 0.0f;            // Envelope energi vokal (auto-unmasking)
    float curUnmaskDb = 0.0f;         // Duck Side saat ini (dB)
    float curPreGain = 1.0f;
    std::atomic<uint64_t> frameCounter{0};

    // Optimization flags & timing (Priority 2)
    int samplesSinceLastUpdate = 0;
    std::atomic<bool> staticDirty{true};
    std::atomic<bool> peqDirty{true};
    std::atomic<bool> xfeedDirty{true};
    std::atomic<bool> btClarityDirty{true};
    std::atomic<bool> ambienceDirty{true};
    std::atomic<bool> preampDirty{true};
    int lastSampleRate = 0;
};
}

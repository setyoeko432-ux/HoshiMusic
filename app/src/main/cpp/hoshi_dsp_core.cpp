#include "hoshi_dsp_core.h"
#include <android/log.h>
#if defined(__arm__) || defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <new>
#include <jni.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <cstdlib>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace hoshi {

#if defined(__aarch64__)
static inline void enableFlushToZero() {
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24); // FZ bit (Flush-to-Zero)
    asm volatile("msr fpcr, %0" :: "r"(fpcr));
}
#elif defined(__arm__)
static inline void enableFlushToZero() {
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24); // FZ bit (Flush-to-Zero)
    asm volatile("vmsr fpscr, %0" :: "r"(fpscr));
}
#else
static inline void enableFlushToZero() {}
#endif

// Padé-style rational tanh approximation (murah, cukup akurat utk audio)
static inline float tanhApprox(float x) {
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

void TptSvf::reset() { s1 = 0.0f; s2 = 0.0f; }

float TptSvf::process(float x) {
    float input = x + 1e-25f;
    float v3 = input - s2;
    float v1 = (g * v3 + s1) / (1.0f + g * (g + k));
    float v2 = s2 + g * v1;
    s1 = 2.0f * v1 - s1;
    s2 = 2.0f * v2 - s2;
    return m0 * input + m1 * v1 + m2 * v2;
}

void TptSvf::setPeaking(float fs, float fc, float gainDb, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    float a = std::pow(10.0f, gainDb / 40.0f);
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs);
    k = 1.0f / (q * a);
    m0 = 1.0f; m1 = k * (a * a - 1.0f); m2 = 0.0f;
}

void TptSvf::setHighShelf(float fs, float fc, float gainDb, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    float a = std::pow(10.0f, gainDb / 40.0f);
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs) * std::sqrt(a);
    k = 1.0f / q;
    m0 = a * a; m1 = k * a * (1.0f - a); m2 = 1.0f - a * a;
}

// Low shelf = cermin frekuensi dari high shelf: plat LF = a², plat HF = unity.
// Struktur TPT tetap stabil karena umpan balik hanya ditentukan oleh g dan k.
void TptSvf::setLowShelf(float fs, float fc, float gainDb, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    float a = std::pow(10.0f, gainDb / 40.0f);
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs) * std::sqrt(a);
    k = 1.0f / q;
    m0 = 1.0f;
    m1 = k * a * (a - 1.0f);
    m2 = a * a - 1.0f;
}

void TptSvf::setLowPass(float fs, float fc, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs); k = 1.0f / q;
    m0 = 0.0f; m1 = 0.0f; m2 = 1.0f;
}

void TptSvf::setHighPass(float fs, float fc, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs); k = 1.0f / q;
    m0 = 1.0f; m1 = -k; m2 = -1.0f;
}

void TptSvf::setAllPass(float fs, float fc, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs); k = 1.0f / q;
    m0 = 1.0f; m1 = -2.0f * k; m2 = 0.0f;
}

void TptSvf::setBandPass(float fs, float fc, float q) {
    float fc_safe = fminf(fc, fs * 0.45f); // FIX: Nyquist guard
    g = std::tan(static_cast<float>(M_PI) * fc_safe / fs); k = 1.0f / q;
    m0 = 0.0f; m1 = 1.0f; m2 = 0.0f;
}

DspEngine::DspEngine() {
    sampleRate = 0; // Fix: Explicit initial value (Priority 2.1)
    lastSampleRate = 0;
    telemetrySeq.store(0, std::memory_order_relaxed);
    enabled.store(false, std::memory_order_relaxed);
    for (int i = 0; i < PARAM_COUNT; ++i) prm[i].store(1.0f, std::memory_order_relaxed);
    for (int i = 0; i < PEQ_SLOTS; ++i) {
        peqType[i].store(0, std::memory_order_relaxed);
        peqFc[i].store(1000.0f, std::memory_order_relaxed);
        peqGain[i].store(0.0f, std::memory_order_relaxed);
        peqQ[i].store(0.707f, std::memory_order_relaxed);
        curPeqFc[i] = 1000.0f;
        curPeqGain[i] = 0.0f;
        curPeqQ[i] = 0.707f;
    }
    peqCount.store(0, std::memory_order_relaxed);
    preampGain.store(1.0f, std::memory_order_relaxed);
    xfeedPreset.store(0, std::memory_order_relaxed);
    systemVolume.store(1.0f, std::memory_order_relaxed); // Fix: Atomic init
}

DspEngine::~DspEngine() {
    if (erBufL) free(erBufL);
    erBufL = nullptr;
    if (erBufR) free(erBufR);
    erBufR = nullptr;
}

void DspEngine::setEnabled(bool on) {
    enabled.store(on, std::memory_order_relaxed);
    adaptiveDspEnabled.store(on, std::memory_order_relaxed);
}

bool DspEngine::isEnabled() const {
    return enabled.load(std::memory_order_relaxed);
}

void DspEngine::setAdaptiveDspEnabled(bool on) {
    adaptiveDspEnabled.store(on, std::memory_order_relaxed);
    enabled.store(on, std::memory_order_relaxed);
}

void DspEngine::setPeqEnabled(bool on) {
    peqEnabled.store(on, std::memory_order_relaxed);
}

void DspEngine::setParams(const float* p, int count) {
    if (p == nullptr) return;
    const int n = (count < PARAM_COUNT) ? count : PARAM_COUNT;
    for (int i = 0; i < n; ++i) {
        prm[i].store(fminf(fmaxf(p[i], 0.0f), 2.0f), std::memory_order_relaxed);
    }
}

void DspEngine::setBtClarity(bool on) {
    if (btClarity.load(std::memory_order_relaxed) != on) {
        btClarity.store(on, std::memory_order_relaxed);
        btClarityDirty.store(true, std::memory_order_release);
    }
}

void DspEngine::setAmbience(bool on) {
    if (ambience.load(std::memory_order_relaxed) != on) {
        ambience.store(on, std::memory_order_relaxed);
        ambienceDirty.store(true, std::memory_order_release);
    }
}

void DspEngine::setPreampGain(float g) {
    float clamped = fminf(fmaxf(g, 0.25f), 4.0f);
    if (preampGain.load(std::memory_order_relaxed) != clamped) {
        preampGain.store(clamped, std::memory_order_relaxed);
        preampDirty.store(true, std::memory_order_release);
    }
}

void DspEngine::setCrossfeedPreset(int preset) {
    int safe = preset < 0 ? 0 : (preset > 2 ? 2 : preset);
    if (xfeedPreset.load(std::memory_order_relaxed) != safe) {
        xfeedPreset.store(safe, std::memory_order_relaxed);
        xfeedDirty.store(true, std::memory_order_release);
    }
}

void DspEngine::setPeqBand(int slot, int type, float fc, float gain, float q) {
    if (slot < 0 || slot >= PEQ_SLOTS) return;
    peqType[slot].store(type < 0 ? 0 : (type > 2 ? 2 : type), std::memory_order_relaxed);
    peqFc[slot].store(fminf(fmaxf(fc, 20.0f), 20000.0f), std::memory_order_relaxed);
    peqGain[slot].store(fminf(fmaxf(gain, -15.0f), 15.0f), std::memory_order_relaxed);
    peqQ[slot].store(fminf(fmaxf(q, 0.1f), 10.0f), std::memory_order_relaxed);
    peqDirty.store(true, std::memory_order_release);
}

void DspEngine::setPeqCount(int n) {
    int safe = n < 0 ? 0 : (n > PEQ_SLOTS ? PEQ_SLOTS : n);
    if (peqCount.load(std::memory_order_relaxed) != safe) {
        peqCount.store(safe, std::memory_order_relaxed);
        peqDirty.store(true, std::memory_order_release);
    }
}

void DspEngine::applyPeqSnapshot(
    bool enabled, float preampDb, int count,
    const int* types, const float* fc, const float* gain, const float* q,
    uint32_t revision
) {
    // Write to the back buffer (non-published)
    int backIdx = 1 - publishedSnapshotIdx.load(std::memory_order_relaxed);
    PeqSnapshot& snap = peqSnapshots[backIdx];

    snap.enabled = enabled;
    snap.preampDb = preampDb;
    snap.count = (count < 0) ? 0 : (count > 10 ? 10 : count);
    snap.revision = revision;

    for (int i = 0; i < 10; ++i) {
        snap.types[i] = types[i];
        snap.fc[i] = fc[i];
        snap.gain[i] = gain[i];
        snap.q[i] = q[i];
    }

    // Publish: switch indices
    publishedSnapshotIdx.store(backIdx, std::memory_order_release);
    peqDirty.store(true, std::memory_order_release);
}

void DspEngine::init(int fs) {
    enableFlushToZero();
    if (sampleRate != fs) {
        sampleRate = fs;
        staticDirty.store(true, std::memory_order_release);
    }
    setupStaticCoefficients();
    // Alokasi buffer early-reflection FDN (~130 ms, S2), zero-filled; realokasi hanya jika fs berubah
    const int capacity = nextPowerOfTwo(static_cast<int>(fs * 0.13f));
    if (capacity > 0 && capacity != erCapacity) {
        if (erBufL) free(erBufL);
        if (erBufR) free(erBufR);

        // ARM NEON requires 16-byte alignment for vld1q_f32
        if (posix_memalign(reinterpret_cast<void**>(&erBufL), 16, capacity * sizeof(float)) != 0) erBufL = nullptr;
        if (posix_memalign(reinterpret_cast<void**>(&erBufR), 16, capacity * sizeof(float)) != 0) erBufR = nullptr;

        if (erBufL) std::memset(erBufL, 0, capacity * sizeof(float));
        if (erBufR) std::memset(erBufR, 0, capacity * sizeof(float));

        erCapacity = capacity;
        erIdx = 0;
    }
    reset();
}

void DspEngine::setupStaticCoefficients() {
    if (!staticDirty.load(std::memory_order_acquire) && sampleRate == lastSampleRate) return;
    float fs = static_cast<float>(sampleRate);
    lastSampleRate = sampleRate;
    staticDirty.store(false, std::memory_order_release);

    // TUNING: Subsonic HPF 45Hz
    subSonicL.setHighPass(fs, 45.0f, 0.707f);
    subSonicR.setHighPass(fs, 45.0f, 0.707f);

    presetStaticLowShelfL.setLowShelf(fs, 105.0f, 0.0f, 0.707f);
    presetStaticLowShelfR.setLowShelf(fs, 105.0f, 0.0f, 0.707f);
    presetStaticHighShelfL.setHighShelf(fs, 12500.0f, 0.0f, 0.707f);
    presetStaticHighShelfR.setHighShelf(fs, 12500.0f, 0.0f, 0.707f);

    // FIX: Band detectors using real HPF+LPF instead of no-op peaking
    analyzeLowL.setLowPass(fs, 200.0f, 0.707f);
    analyzeLowR.setLowPass(fs, 200.0f, 0.707f);

    analyzeMidHpfL.setHighPass(fs, 200.0f, 0.5f);
    analyzeMidHpfR.setHighPass(fs, 200.0f, 0.5f);
    analyzeMidLpfL.setLowPass(fs, 4500.0f, 0.5f);
    analyzeMidLpfR.setLowPass(fs, 4500.0f, 0.5f);

    analyzeHighL.setHighPass(fs, 4500.0f, 0.707f);
    analyzeHighR.setHighPass(fs, 4500.0f, 0.707f);

    mSubCut.setHighPass(fs, 48.0f, 0.707f);
    mBoxinessCut.setPeaking(fs, 450.0f, -1.0f, 1.2f); // TUNING: Boxiness reduction

    // FIX: Virtual Bass Chain
    bassExtractLpf.setLowPass(fs, 60.0f, 0.707f);
    bassHarmonicHpf_gen.setHighPass(fs, 80.0f, 0.707f);
    bassHarmonicLpf_gen.setLowPass(fs, 250.0f, 0.707f);
    bassHarmonicHpf_final.setHighPass(fs, 60.0f, 0.707f);

    // FIX: De-Esser Detector Band-Limit (5.5k - 9.5k)
    deEsserHpf.setHighPass(fs, 5500.0f, 0.5f);
    deEsserLpf.setLowPass(fs, 9500.0f, 0.5f);

    // C3: bank band-pass pelacak sibilance (3 zona 'ess') utk notch adaptif
    essBand[0].setBandPass(fs, 5800.0f, 4.0f);
    essBand[1].setBandPass(fs, 7500.0f, 4.0f);
    essBand[2].setBandPass(fs, 9200.0f, 4.0f);

    exciterHpfL.setHighPass(fs, 6000.0f, 0.707f); // TUNING: Subtle exciter
    exciterHpfR.setHighPass(fs, 6000.0f, 0.707f);

    // Sub-band bass protector: split LPF 140 Hz (proteksi driver kecil)
    protSplitLpfL.setLowPass(fs, 140.0f, 0.707f);
    protSplitLpfR.setLowPass(fs, 140.0f, 0.707f);

    crossfeedLpfL.setLowPass(fs, 700.0f, 0.5f);
    crossfeedLpfR.setLowPass(fs, 700.0f, 0.5f);

    // K-Weighting detector: shelf +4 dB >1.5 kHz + HPF infrasonik (BS.1770-inspired)
    kShelfL.setHighShelf(fs, 1500.0f, 4.0f, 0.707f); kShelfR.setHighShelf(fs, 1500.0f, 4.0f, 0.707f);
    kHpfL.setHighPass(fs, 55.0f, 0.5f); kHpfR.setHighPass(fs, 55.0f, 0.5f);
    // Auto-unmasking: band-pass vokal utk deteksi Mid
    unmaskDetBp.setBandPass(fs, 2500.0f, 1.2f);

    // SATURATION band-split: LPF 160 Hz untuk low-band tape glue
    satLowLpfL.setLowPass(fs, 160.0f, 0.707f);
    satLowLpfR.setLowPass(fs, 160.0f, 0.707f);
    // AMBIENCE: HPF 250 Hz agar balikan early-reflection tetap airy
    erHpfL.setHighPass(fs, 250.0f, 0.707f);
    erHpfR.setHighPass(fs, 250.0f, 0.707f);

    sLowCut.setHighPass(fs, 160.0f, 0.707f);
    sDecorrelator1.setAllPass(fs, 1600.0f, 0.5f);
    sDecorrelator2.setAllPass(fs, 4400.0f, 0.5f);

    updateAdaptiveCoefficients(0.01f); // Initial update
}

void DspEngine::updateAdaptiveCoefficients(float dt) {
    float fs = static_cast<float>(sampleRate);
    if (fs < 8000.0f) return;

    // TUNING: Masking reduction & Vocal clarity focus
    const float pBass      = prm[0].load(std::memory_order_relaxed);
    const float pVocal     = prm[1].load(std::memory_order_relaxed);
    const float pAir       = prm[2].load(std::memory_order_relaxed);
    const float pWarm      = prm[3].load(std::memory_order_relaxed);
    const float pExciter   = prm[4].load(std::memory_order_relaxed);
    const float pPunch     = prm[12].load(std::memory_order_relaxed);
    const float pBody      = prm[13].load(std::memory_order_relaxed);

    const bool clarityOn = btClarity.load(std::memory_order_relaxed);

    mMudCut.setPeaking(fs, 280.0f, curMudCutDb, 1.0f);
    mLowMidGapCut.setPeaking(fs, 380.0f, curLowMidGapCutDb, 1.2f);

    if (btClarityDirty.load(std::memory_order_acquire)) {
        mBoxinessCut.setPeaking(fs, 450.0f, clarityOn ? -2.8f : -1.0f, 1.2f);
        btClarityDirty.store(false, std::memory_order_release);
    }

    const bool vbActive = curVirtualBassAmount > 0.005f;
    mSubCut.setHighPass(fs, vbActive ? 62.0f : 48.0f, 0.707f);

    mVocalFormant.setPeaking(fs, 1800.0f, curFormantBoostDb, 1.6f);
    mVocalPresence.setPeaking(fs, 3000.0f, curVocalBoostDb, 1.1f);
    mConsonantPluck.setPeaking(fs, 5000.0f, curConsonantBoostDb, 1.3f);
    mUltraAirShelf.setHighShelf(fs, 15200.0f, curAirBoostDb, 0.707f);

    sPresence.setPeaking(fs, 3800.0f, curSideBoostDb, 0.8f);
    sAirShelf.setHighShelf(fs, 12000.0f, curAirBoostDb * 0.6f, 0.707f);

    deEsserNotch.setPeaking(fs, curNotchFc, curDeEsserGainDb, 1.8f);

    const float guardDepth = fminf(1.2f, 0.35f * fmaxf(pAir, 0.0f));
    mHarshGuard.setPeaking(fs, 3500.0f, -guardDepth, 0.9f);

    unmaskSidePeak.setPeaking(fs, 2500.0f, curUnmaskDb, 1.0f);

    if (xfeedDirty.load(std::memory_order_acquire)) {
        const int xp = xfeedPreset.load(std::memory_order_relaxed);
        const float cut = xp == 1 ? 650.f : (xp == 2 ? 800.f : 700.f);
        crossfeedLpfL.setLowPass(fs, cut, 0.5f);
        crossfeedLpfR.setLowPass(fs, cut, 0.5f);
        xfeedDirty.store(false, std::memory_order_release);
    }
}

void DspEngine::updatePeqCoefficients(float dt) {
    float fs = static_cast<float>(sampleRate);
    if (fs < 8000.0f) return;

    int currentIdx = publishedSnapshotIdx.load(std::memory_order_acquire);
    const PeqSnapshot& snap = peqSnapshots[currentIdx];

    if (snap.revision != lastProcessedRevision) {
        lastProcessedRevision = snap.revision;
        targetPeqWet = snap.enabled ? 1.0f : 0.0f;
    }

    const float tau = 0.020f;
    const float alpha = 1.0f - expf(-dt / tau);
    const float invAlpha = 1.0f - alpha;

    const int nBands = snap.count;
    bool stillSmoothing = false;

    if (fabsf(curPeqWet - targetPeqWet) > 0.001f) {
        curPeqWet = curPeqWet * invAlpha + targetPeqWet * alpha;
        stillSmoothing = true;
    } else {
        curPeqWet = targetPeqWet;
    }

    for (int b = 0; b < PEQ_SLOTS; ++b) {
        if (b < nBands) {
            const int bt = snap.types[b];
            const float targetFc = snap.fc[b];
            const float targetGain = snap.gain[b];
            const float targetQ = snap.q[b];

            float diff = fabsf(curPeqFc[b] - targetFc) + fabsf(curPeqGain[b] - targetGain) + fabsf(curPeqQ[b] - targetQ);
            if (diff > 0.001f) {
                curPeqFc[b] = curPeqFc[b] * invAlpha + targetFc * alpha;
                curPeqGain[b] = curPeqGain[b] * invAlpha + targetGain * alpha;
                curPeqQ[b] = curPeqQ[b] * invAlpha + targetQ * alpha;
                stillSmoothing = true;
            } else {
                curPeqFc[b] = targetFc;
                curPeqGain[b] = targetGain;
                curPeqQ[b] = targetQ;
            }

            switch (bt) {
                case 1:
                    peqL[b].setLowShelf(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    peqR[b].setLowShelf(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    break;
                case 2:
                    peqL[b].setHighShelf(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    peqR[b].setHighShelf(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    break;
                default:
                    peqL[b].setPeaking(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    peqR[b].setPeaking(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
                    break;
            }
        } else if (fabsf(curPeqGain[b]) > 0.01f) {
            curPeqGain[b] = curPeqGain[b] * invAlpha;
            peqL[b].setPeaking(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
            peqR[b].setPeaking(fs, curPeqFc[b], curPeqGain[b], curPeqQ[b]);
            stillSmoothing = true;
        }
    }

    if (!stillSmoothing) {
        peqDirty.store(false, std::memory_order_release);
    }
}

void DspEngine::processFrame(
    float& l, float& r,
    float pBass, float pVocal, float pAir, float pWarm, float pExciter,
    float pWidth, float pDeEsser, float pCrossfeed, float pAgc,
    float pLimiter, float pLoudness, float pPunch, float pBody, float pSat,
    float limiterCeiling, float baseFeed, float& currentVocalAccum
) {
    float fs = static_cast<float>(sampleRate);
    float presetBassDb = (pBass - 1.0f) * 4.5f;
    float presetAirDb = (pAir - 1.0f) * 3.5f;

    presetStaticLowShelfL.setLowShelf(fs, 105.0f, presetBassDb, 0.707f);
    presetStaticLowShelfR.setLowShelf(fs, 105.0f, presetBassDb, 0.707f);
    presetStaticHighShelfL.setHighShelf(fs, 12500.0f, presetAirDb, 0.707f);
    presetStaticHighShelfR.setHighShelf(fs, 12500.0f, presetAirDb, 0.707f);

    l = presetStaticLowShelfL.process(l);
    l = presetStaticHighShelfL.process(l);
    r = presetStaticLowShelfR.process(r);
    r = presetStaticHighShelfR.process(r);

    l = subSonicL.process(l);
    r = subSonicR.process(r);

    if (!std::isfinite(l)) l = 0.0f;
    if (!std::isfinite(r)) r = 0.0f;

    const float kW_l = kHpfL.process(kShelfL.process(l));
    const float kW_r = kHpfR.process(kShelfR.process(r));
    float currentSampleRms = std::sqrt((kW_l * kW_l + kW_r * kW_r) * 0.5f);
    if (!std::isfinite(currentSampleRms)) currentSampleRms = 0.0f;

    medRms = medRms * 0.999f + currentSampleRms * 0.001f + 1e-25f;
    slowRms = slowRms * 0.99995f + currentSampleRms * 0.00005f + 1e-25f;
    if (!std::isfinite(medRms)) medRms = 0.1f;
    if (!std::isfinite(slowRms)) slowRms = 0.1f;

    float targetPreGain = 1.0f;
    if (slowRms > 0.001f) {
        targetPreGain = 0.18f / slowRms;
        targetPreGain = fmaxf(0.5f, fminf(targetPreGain, 1.5f * fmaxf(pAgc, 0.25f)));
        const float upKnee = 0.045f * fmaxf(0.0f, fminf(pLoudness, 2.0f));
        if (medRms < upKnee && medRms > 0.0015f) {
            const float upGain = fminf(2.0f, upKnee / medRms);
            targetPreGain *= (0.6f + 0.4f * upGain);
            targetPreGain = fminf(targetPreGain, 2.2f * fmaxf(pAgc, 0.25f));
        }
    }

    if (targetPreGain > curPreGain) {
        curPreGain = curPreGain * 0.999996f + targetPreGain * 0.000004f;
    } else {
        curPreGain = curPreGain * 0.9995f + targetPreGain * 0.0005f;
    }

    l *= curPreGain;
    r *= curPreGain;

    if (pSat > 0.01f) {
        float lowL = satLowLpfL.process(l);
        float lowR = satLowLpfR.process(r);
        const float dLow = 1.0f + 1.6f * pSat;
        float glueL = tanhApprox(lowL * dLow) / dLow;
        float glueR = tanhApprox(lowR * dLow) / dLow;
        const float wetLow = fminf(0.9f, 0.45f * pSat);
        l += (glueL - lowL) * wetLow;
        r += (glueR - lowR) * wetLow;

        const float dGlob = 1.0f + 0.8f * pSat;
        const float wetGlob = fminf(0.36f, 0.18f * pSat);
        l += (tanhApprox(l * dGlob) / dGlob - l) * wetGlob;
        r += (tanhApprox(r * dGlob) / dGlob - r) * wetGlob;
    }

    {
        float protLowL = protSplitLpfL.process(l);
        float protLowR = protSplitLpfR.process(r);
        const float absLowL = fmaxf(fabsf(protLowL), 1e-6f);
        const float absLowR = fmaxf(fabsf(protLowR), 1e-6f);
        protEnvL = fmaxf(absLowL, protEnvL * 0.92f) + 1e-25f;
        protEnvR = fmaxf(absLowR, protEnvR * 0.92f) + 1e-25f;
        const float protThr = 0.85f;
        if (protEnvL > protThr) { const float g = protThr / protEnvL; l += (protLowL * g - protLowL); }
        if (protEnvR > protThr) { const float g = protThr / protEnvR; r += (protLowR * g - protLowR); }
    }

    float lowL = analyzeLowL.process(l); float lowR = analyzeLowR.process(r);
    float midL = analyzeMidHpfL.process(l); midL = analyzeMidLpfL.process(midL);
    float midR = analyzeMidHpfR.process(r); midR = analyzeMidLpfR.process(midR);
    float highL = analyzeHighL.process(l); float highR = analyzeHighR.process(r);

    float curLowPwr = (lowL * lowL + lowR * lowR) * 0.5f;
    float curMidPwr = (midL * midL + midR * midR) * 0.5f;
    float curHighPwr = (highL * highL + highR * highR) * 0.5f;

    envLow = envLow * 0.9995f + curLowPwr * 0.0005f + 1e-25f;
    envMid = envMid * 0.9995f + curMidPwr * 0.0005f + 1e-25f;
    envHigh = envHigh * 0.9995f + curHighPwr * 0.0005f + 1e-25f;

    if (!std::isfinite(envLow)) envLow = 0.001f;
    if (!std::isfinite(envMid)) envMid = 0.001f;
    if (!std::isfinite(envHigh)) envHigh = 0.001f;

    float m = (l + r) * 0.5f;
    float s = (l - r) * 0.5f;

    float absM = std::abs(m);
    transFastM = transFastM * 0.80f + absM * 0.20f + 1e-25f;
    const float bodyRateM = fminf(0.032f, fmaxf(0.002f, 0.008f / fmaxf(pBody, 0.25f)));
    transSlowM = transSlowM * (1.0f - bodyRateM) + absM * bodyRateM + 1e-25f;
    float deltaM = fmaxf(0.0f, transFastM - transSlowM);
    float gateM = fminf(1.0f, absM * 25.0f);
    m += m * deltaM * (0.12f * pPunch) * gateM;
    if (pBody > 1.0f) {
        const float bodyAddM = ((m >= 0.0f) ? transSlowM : -transSlowM) * (0.28f * (pBody - 1.0f)) * fminf(1.0f, transSlowM * 20.0f);
        m += bodyAddM;
    }
    m = fmaxf(-2.0f, fminf(m, 2.0f));

    float absS = std::abs(s);
    transFastS = transFastS * 0.82f + absS * 0.18f + 1e-25f;
    const float bodyRateS = fminf(0.032f, fmaxf(0.002f, 0.010f / fmaxf(pBody, 0.25f)));
    transSlowS = transSlowS * (1.0f - bodyRateS) + absS * bodyRateS + 1e-25f;
    float deltaS = fmaxf(0.0f, transFastS - transSlowS);
    float gateS = fminf(1.0f, absS * 25.0f);
    s += s * deltaS * (0.14f * pPunch) * gateS;
    if (pBody > 1.0f) {
        const float bodyAddS = ((s >= 0.0f) ? transSlowS : -transSlowS) * (0.30f * (pBody - 1.0f)) * fminf(1.0f, transSlowS * 20.0f);
        s += bodyAddS;
    }
    s = fmaxf(-2.0f, fminf(s, 2.0f));

    if (curVirtualBassAmount > 0.005f) {
        float deepSub = bassExtractLpf.process(m);
        float subHarmonic = (deepSub * deepSub * 1.8f);
        subHarmonic = fmaxf(-0.3f, fminf(subHarmonic, 0.3f));
        float filteredHarmonic = bassHarmonicHpf_gen.process(subHarmonic);
        filteredHarmonic = bassHarmonicLpf_gen.process(filteredHarmonic);
        filteredHarmonic = bassHarmonicHpf_final.process(filteredHarmonic);
        m += filteredHarmonic * curVirtualBassAmount;
    }

    m = mSubCut.process(m);
    m = mMudCut.process(m);
    m = mLowMidGapCut.process(m);
    m = mBoxinessCut.process(m);
    m = mVocalFormant.process(m);
    m = mVocalPresence.process(m);
    m = mHarshGuard.process(m);
    m = mConsonantPluck.process(m);
    m = mUltraAirShelf.process(m);

    for (int e = 0; e < 3; ++e) {
        const float b = std::abs(essBand[e].process(m));
        essEnv[e] = (b > essEnv[e]) ? (essEnv[e] * 0.60f + b * 0.40f) : (essEnv[e] * 0.998f + b * 0.002f);
        essEnv[e] += 1e-25f;
    }
    float sibM = deEsserHpf.process(m);
    sibM = deEsserLpf.process(sibM);
    float sibilanceEnergy = std::abs(sibM);
    deEsserEnv = (sibilanceEnergy > deEsserEnv) ?
                 (deEsserEnv * 0.60f + sibilanceEnergy * 0.40f) :
                 (deEsserEnv * 0.9992f + sibilanceEnergy * 0.0008f);
    deEsserEnv += 1e-25f;

    float deEsserThreshold = 0.14f / fmaxf(pDeEsser, 0.25f);
    float targetDeEssGain = (deEsserEnv > deEsserThreshold) ?
                            -fminf(6.0f * pDeEsser, (deEsserEnv - deEsserThreshold) * 24.0f) : 0.0f;
    curDeEsserGainDb = curDeEsserGainDb * 0.80f + targetDeEssGain * 0.20f;
    curDeEsserGainDb = fmaxf(curDeEsserGainDb, -12.0f);
    m = deEsserNotch.process(m);

    s = sLowCut.process(s);
    s = sPresence.process(s);
    s = sAirShelf.process(s);

    {
        const float vBand = std::abs(unmaskDetBp.process(m));
        currentVocalAccum += vBand; // [V21]: Real-time vocal energy sensor
        vocalEnv = (vBand > vocalEnv) ? (vocalEnv * 0.60f + vBand * 0.40f)
                                      : (vocalEnv * 0.998f + vBand * 0.002f);
        vocalEnv += 1e-25f;
        const float vThr = 0.05f;
        const float depthScale = fmaxf(0.0f, fminf(pVocal, 2.0f));
        const float targetDuck = (vocalEnv > vThr)
            ? -fminf(2.0f, (vocalEnv - vThr) * 30.0f) * depthScale
            : 0.0f;
        curUnmaskDb = (targetDuck < curUnmaskDb)
            ? curUnmaskDb * 0.75f + targetDuck * 0.25f
            : curUnmaskDb * 0.998f + targetDuck * 0.002f;
        s = unmaskSidePeak.process(s);
    }

    s = sDecorrelator1.process(s);
    s = sDecorrelator2.process(s);

    s *= pWidth;

    l = m + s;
    r = m - s;

    if (ambience.load(std::memory_order_relaxed) && erCapacity > 0 && erBufL != nullptr && erBufR != nullptr) {
        const float inMono = (l + r) * 0.5f;
        const int cap = erCapacity;
        const int mask = cap - 1;
        const int o1 = erIdx - static_cast<int>(sampleRate * 0.013f);
        const int o2 = erIdx - static_cast<int>(sampleRate * 0.029f);
        const int o3 = erIdx - static_cast<int>(sampleRate * 0.047f);
        const int o4 = erIdx - static_cast<int>(sampleRate * 0.071f);
        const int jr = (cap / 13) | 1;
        float erL = erBufL[o1 & mask] * 1.0f + erBufL[o3 & mask] * 0.65f;
        float erR = erBufR[(o2 - jr) & mask] * 0.8f + erBufR[(o4 - 2 * jr) & mask] * 0.5f;

        const int o5 = erIdx - static_cast<int>(sampleRate * 0.095f);
        const int o6 = erIdx - static_cast<int>(sampleRate * 0.118f);
        erL += erBufL[o5 & mask] * 0.30f;
        erR += erBufR[o6 & mask] * 0.26f;

        float nextL = inMono + 0.32f * (erL + erR) * 0.5f;
        float nextR = -inMono + 0.32f * (erL + erR) * 0.5f;

        // Prevent noise spikes from propagating through the delay line
        if (!std::isfinite(nextL)) nextL = 0.0f;
        if (!std::isfinite(nextR)) nextR = 0.0f;

        erBufL[erIdx] = nextL;
        erBufR[erIdx] = nextR;
        erIdx = (erIdx + 1) & mask;

        erL = erHpfL.process(erL);
        erR = erHpfR.process(erR);

        const float erMix = 0.115f;
        l += erL * erMix + erR * erMix * 0.75f;
        r += erR * erMix + erL * erMix * 0.75f;
    }

    const float exGate = 0.35f + 0.65f * fminf(1.0f, deltaM * 55.0f);
    float excL = exciterHpfL.process(l);
    float excR = exciterHpfR.process(r);
    if (excL > 0.0f) l += ((excL + 0.3f * excL * excL) / (1.0f + 0.2f * std::abs(excL))) * curExciterAmount * exGate;
    if (excR > 0.0f) r += ((excR + 0.3f * excR * excR) / (1.0f + 0.2f * std::abs(excR))) * curExciterAmount * exGate;

    dcOffsetL = dcOffsetL * 0.9995f + l * 0.0005f + 1e-25f;
    dcOffsetR = dcOffsetR * 0.9995f + r * 0.0005f + 1e-25f;
    l -= dcOffsetL;
    r -= dcOffsetR;

    const float feedAmt = fminf(0.45f, baseFeed * pCrossfeed);
    const float dryAmt = 1.0f - feedAmt;
    float feedL = crossfeedLpfL.process(l) * feedAmt;
    float feedR = crossfeedLpfR.process(r) * feedAmt;
    l = (l * dryAmt) + feedR;
    r = (r * dryAmt) + feedL;

    const float midLtp = 0.5f * (tpPrevL + l), midRtp = 0.5f * (tpPrevR + r);
    float peak = fmaxf(fmaxf(fabsf(l), fabsf(r)),
                       fmaxf(fabsf(midLtp) + 0.125f * fabsf(l - tpPrevL),
                             fabsf(midRtp) + 0.125f * fabsf(r - tpPrevR)));
    tpPrevL = l; tpPrevR = r;

    envelope = (peak > envelope) ? (envelope * 0.80f + peak * 0.20f) : (envelope * 0.9992f + peak * 0.0008f);
    envelope += 1e-25f;
    if (!std::isfinite(envelope)) envelope = 0.0f;
    float targetGain = (envelope > limiterCeiling) ? (limiterCeiling / envelope) : 1.0f;
    currentGain = currentGain * 0.90f + targetGain * 0.10f;
    if (!std::isfinite(currentGain)) currentGain = 1.0f;
    l *= currentGain;
    r *= currentGain;
}

void DspEngine::process(float* buffer, int numSamples) {
    if (sampleRate < 8000) return;
    const float dt = static_cast<float>(numSamples) / static_cast<float>(sampleRate);

    // 1. Update coefficients if needed
    if (staticDirty.load(std::memory_order_acquire)) {
        setupStaticCoefficients();
    }

    const bool adsp = adaptiveDspEnabled.load(std::memory_order_relaxed);
    if (adsp) updateAdaptiveCoefficients(dt);

    const bool peqRequest = peqDirty.load(std::memory_order_relaxed);
    if (peqRequest || curPeqWet > 0.001f || targetPeqWet > 0.001f) {
        updatePeqCoefficients(dt);
    }

    // 2. Preamp Smoothing Setup
    int currentIdx = publishedSnapshotIdx.load(std::memory_order_acquire);
    const float targetPreampDb = peqSnapshots[currentIdx].preampDb;
    const float targetPreampLinear = std::pow(10.0f, targetPreampDb / 20.0f);

    // Sample-rate independent smoothing (25ms tau)
    const float tau = 0.025f;
    const float alpha = 1.0f - expf(- (1.0f / sampleRate) / tau);

    const float pBass      = prm[0].load(std::memory_order_relaxed);
    const float pVocal     = prm[1].load(std::memory_order_relaxed);
    const float pAir       = prm[2].load(std::memory_order_relaxed);
    const float pWarm      = prm[3].load(std::memory_order_relaxed);
    const float pExciter   = prm[4].load(std::memory_order_relaxed);
    const float pWidth     = prm[6].load(std::memory_order_relaxed);
    const float pDeEsser   = prm[7].load(std::memory_order_relaxed);
    const float pCrossfeed = prm[8].load(std::memory_order_relaxed);
    const float pAgc       = prm[9].load(std::memory_order_relaxed);
    const float pLimiter   = prm[10].load(std::memory_order_relaxed);
    const float pLoudness  = prm[11].load(std::memory_order_relaxed);
    const float pPunch     = prm[12].load(std::memory_order_relaxed);
    const float pBody      = prm[13].load(std::memory_order_relaxed);
    const float pSat       = prm[14].load(std::memory_order_relaxed);
    const float limiterCeiling = 0.91f + 0.04f * pLimiter;

    const int peqN = peqSnapshots[currentIdx].count;
    const int xpreset = xfeedPreset.load(std::memory_order_relaxed);
    const float baseFeed = (xpreset == 1) ? 0.335f : ((xpreset == 2) ? 0.40f : 0.50f);

    float currentVocalAccum = 0.0f;
    uint64_t framesProcessed = 0;

    for (int i = 0; i < numSamples; ++i) {
        framesProcessed += 1;

        // Apply sample-accurate preamp smoothing
        curPreampLinear = curPreampLinear * (1.0f - alpha) + targetPreampLinear * alpha;

        float fl = buffer[i * 2] * curPreampLinear;
        float fr = buffer[i * 2 + 1] * curPreampLinear;

        if (!std::isfinite(fl)) fl = 0.0f;
        if (!std::isfinite(fr)) fr = 0.0f;

        // 1. Terapkan Rantai Adaptive Mastering Terlebih Dahulu
        if (adsp) {
            processFrame(fl, fr, pBass, pVocal, pAir, pWarm, pExciter, pWidth, pDeEsser, pCrossfeed, pAgc, pLimiter, pLoudness, pPunch, pBody, pSat, limiterCeiling, baseFeed, currentVocalAccum);
        }

        // 2. Terapkan PEQ Koreksi Headphone Terakhir (Paling Dekat Dengan Telinga/Output)
        if (curPeqWet > 0.001f) {
            float peqL_out = fl;
            float peqR_out = fr;
            for (int b = 0; b < PEQ_SLOTS; ++b) {
                if (b < peqN || fabsf(curPeqGain[b]) > 0.01f) {
                    peqL_out = peqL[b].process(peqL_out);
                    peqR_out = peqR[b].process(peqR_out);
                }
            }
            fl = fl * (1.0f - curPeqWet) + peqL_out * curPeqWet;
            fr = fr * (1.0f - curPeqWet) + peqR_out * curPeqWet;
        }

        buffer[i * 2] = fmaxf(-1.0f, fminf(1.0f, fl));
        buffer[i * 2 + 1] = fmaxf(-1.0f, fminf(1.0f, fr));
    }

    frameCounter.fetch_add(framesProcessed, std::memory_order_relaxed);

    samplesSinceLastUpdate += numSamples;
    if (samplesSinceLastUpdate >= 1024) {
        float dt_telemetry = static_cast<float>(samplesSinceLastUpdate) / static_cast<float>(sampleRate);
        samplesSinceLastUpdate = 0;

        float totalPwr = envLow + envMid + envHigh + 1e-9f;
        float bassRatio = envLow / totalPwr;
        float midRatio = envMid / totalPwr;
        float highRatio = envHigh / totalPwr;

        const float pWarm_local      = prm[3].load(std::memory_order_relaxed);
        const float warmDiv = 1.0f / fmaxf(pWarm_local, 0.25f);
        const bool clarityOn = btClarity.load(std::memory_order_relaxed);
        const float pLoudness_local  = prm[11].load(std::memory_order_relaxed);

        float targetMudCut = ((bassRatio > 0.40f) ? -2.0f : -1.2f) * warmDiv;
        float targetLowMidGapCut = ((bassRatio > 0.40f) ? -1.5f : -0.8f) * warmDiv;
        float targetVocalBoost = ((midRatio > 0.35f) ? 2.2f : 1.6f) * pVocal;
        float targetConsonant = ((highRatio < 0.20f) ? 1.0f : 0.4f) * pVocal;
        float targetAirBoost = ((highRatio < 0.15f) ? 1.8f : 0.8f) * pAir;
        float targetSideBoost = ((midRatio > 0.35f) ? 1.4f : 0.6f) * pWidth;
        float targetExciter = ((highRatio < 0.20f) ? 0.03f : 0.01f) * pExciter;

        float bassScale = fmaxf(0.15f, 1.0f - (bassRatio / 0.45f));
        float targetVirtualBass = fmaxf(0.0f, 0.30f - bassRatio) * 0.15f;
        if (bassRatio > 0.35f) { targetVirtualBass = (bassRatio - 0.35f) * 0.02f; }
        targetVirtualBass = (targetVirtualBass * 0.3f + 0.05f * bassScale) * pBass;

        float volumeFactor = fmaxf(0.0f, 0.5f - systemVolume.load(std::memory_order_relaxed)) * 2.0f * pLoudness_local;
        float fletcherBassBoost = volumeFactor * 0.04f;
        targetVirtualBass = fminf(0.06f, targetVirtualBass + fletcherBassBoost);
        if (clarityOn) targetVirtualBass = fminf(targetVirtualBass, 0.02f);
        float fletcherAirBoost = volumeFactor * 1.5f;
        targetAirBoost += fletcherAirBoost;
        curFormantBoostDb = 0.8f + (volumeFactor * 0.6f);

        curMudCutDb = curMudCutDb * 0.95f + targetMudCut * 0.05f;
        curLowMidGapCutDb = curLowMidGapCutDb * 0.95f + targetLowMidGapCut * 0.05f;
        curVocalBoostDb = curVocalBoostDb * 0.95f + targetVocalBoost * 0.05f;
        curConsonantBoostDb = curConsonantBoostDb * 0.95f + targetConsonant * 0.05f;
        curSideBoostDb = curSideBoostDb * 0.95f + targetSideBoost * 0.05f;
        curAirBoostDb = curAirBoostDb * 0.95f + targetAirBoost * 0.05f;
        curExciterAmount = curExciterAmount * 0.95f + targetExciter * 0.05f;
        curVirtualBassAmount = curVirtualBassAmount * 0.95f + targetVirtualBass * 0.05f;

        int bestE = 0; if (essEnv[1] > essEnv[bestE]) bestE = 1; if (essEnv[2] > essEnv[bestE]) bestE = 2;
        static const float essFc[3] = {5800.0f, 7500.0f, 9200.0f};
        const float tgtFc = (essEnv[bestE] > 0.02f) ? essFc[bestE] : 7500.0f;
        curNotchFc = curNotchFc * 0.95f + tgtFc * 0.05f;

        // updateDynamicCoefficients is gone, we updated curFooDb directly above.

        DspTelemetryData snap;
        snap.bassRatio = bassRatio;
        snap.midRatio = midRatio;
        snap.highRatio = highRatio;
        snap.curMudCutDb = curMudCutDb;
        snap.curLowMidGapCutDb = curLowMidGapCutDb;
        snap.curFormantBoostDb = curFormantBoostDb;
        snap.curVocalBoostDb = curVocalBoostDb;
        snap.curConsonantBoostDb = curConsonantBoostDb;
        snap.curAirBoostDb = curAirBoostDb;
        snap.curSideBoostDb = curSideBoostDb;
        snap.curDeEsserGainDb = curDeEsserGainDb;
        snap.curExciterAmount = curExciterAmount;
        snap.curVirtualBassAmount = curVirtualBassAmount;
        snap.curTransientDelta = transFastM - transSlowM;
        snap.peakEnvelope = envelope;
        snap.limiterGainReductionDb = (envelope > limiterCeiling) ? 20.0f * std::log10(limiterCeiling / envelope) : 0.0f;
        snap.totalFramesProcessed = frameCounter.load(std::memory_order_relaxed);
        snap.appliedPeqRevision = lastProcessedRevision;
        snap.sampleRate = sampleRate;
        snap.vocalEnergy = vocalEnv;
        snap.nativeInitialized = true;

        if (highRatio > 0.32f) std::strcpy(snap.dominantBand, "NATURAL BRIGHT");
        else if (midRatio > 0.42f) std::strcpy(snap.dominantBand, "CLEAR VOCAL");
        else std::strcpy(snap.dominantBand, "BALANCED STAGE");

        uint32_t s = telemetrySeq.load(std::memory_order_relaxed);
        telemetrySeq.store(s + 1, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        telemetryData = snap;
        std::atomic_thread_fence(std::memory_order_release);
        telemetrySeq.store(s + 2, std::memory_order_relaxed);
    }
}


void DspEngine::flush() {
    subSonicL.reset(); subSonicR.reset();
    presetStaticLowShelfL.reset(); presetStaticLowShelfR.reset();
    presetStaticHighShelfL.reset(); presetStaticHighShelfR.reset();
    analyzeLowL.reset(); analyzeLowR.reset();
    analyzeMidHpfL.reset(); analyzeMidHpfR.reset(); analyzeMidLpfL.reset(); analyzeMidLpfR.reset();
    analyzeHighL.reset(); analyzeHighR.reset();
    mSubCut.reset(); mMudCut.reset(); mLowMidGapCut.reset(); mBoxinessCut.reset();
    mVocalFormant.reset(); mVocalPresence.reset(); mConsonantPluck.reset(); mUltraAirShelf.reset();
    mHarshGuard.reset();
    sLowCut.reset(); sPresence.reset(); sAirShelf.reset();
    sDecorrelator1.reset(); sDecorrelator2.reset();
    bassExtractLpf.reset(); bassHarmonicHpf_gen.reset(); bassHarmonicLpf_gen.reset(); bassHarmonicHpf_final.reset();
    deEsserHpf.reset(); deEsserLpf.reset(); deEsserNotch.reset();
    for (int e = 0; e < 3; ++e) { essBand[e].reset(); essEnv[e] = 0.0f; }
    curNotchFc = 7500.0f;
    exciterHpfL.reset(); exciterHpfR.reset();
    crossfeedLpfL.reset(); crossfeedLpfR.reset();
    protSplitLpfL.reset(); protSplitLpfR.reset();
    for (int b = 0; b < PEQ_SLOTS; ++b) {
        peqL[b].reset();
        peqR[b].reset();
    }
    satLowLpfL.reset(); satLowLpfR.reset();
    erHpfL.reset(); erHpfR.reset();
    envLow = 0.001f; envMid = 0.001f; envHigh = 0.001f;
    transFastM = 0.0f; transSlowM = 0.0f;
    transFastS = 0.0f; transSlowS = 0.0f;
    dcOffsetL = 0.0f; dcOffsetR = 0.0f;
    deEsserEnv = 0.0f; envelope = 0.0f; currentGain = 1.0f; samplesSinceLastUpdate = 0;
    slowRms = 0.1f;
    medRms = 0.1f;
    vocalEnv = 0.0f;
    curUnmaskDb = 0.0f;
    curPreGain = 1.0f;
    protEnvL = 0.0f; protEnvR = 0.0f;
    tpPrevL = 0.0f; tpPrevR = 0.0f;
    frameCounter.store(0, std::memory_order_relaxed);
    if (erBufL != nullptr && erBufR != nullptr && erCapacity > 0) {
        std::memset(erBufL, 0, sizeof(float) * erCapacity);
        std::memset(erBufR, 0, sizeof(float) * erCapacity);
    }
    erIdx = 0;
}

void DspEngine::reset() {
    flush();
}

void DspEngine::setSystemVolume(float volume) {
    if (std::isfinite(volume)) {
        systemVolume.store(fminf(fmaxf(volume, 0.0f), 1.0f), std::memory_order_relaxed);
    }
}

DspTelemetryData DspEngine::getTelemetry() {
    DspTelemetryData snap;
    uint32_t s1, s2;
    do {
        s1 = telemetrySeq.load(std::memory_order_acquire);
        snap = telemetryData;
        std::atomic_thread_fence(std::memory_order_acquire);
        s2 = telemetrySeq.load(std::memory_order_relaxed);
    } while (s1 != s2 || (s1 & 1));

    snap.totalFramesProcessed = frameCounter.load(std::memory_order_relaxed);
    snap.nativeInitialized = true;
    return snap;
}

} // namespace hoshi

extern "C" {

struct JniCache {
    jclass telemetryClass;
    jfieldID bassRatio;
    jfieldID midRatio;
    jfieldID highRatio;
    jfieldID curMudCutDb;
    jfieldID curLowMidGapCutDb;
    jfieldID curFormantBoostDb;
    jfieldID curVocalBoostDb;
    jfieldID curConsonantBoostDb;
    jfieldID curAirBoostDb;
    jfieldID curSideBoostDb;
    jfieldID curDeEsserGainDb;
    jfieldID curExciterAmount;
    jfieldID curVirtualBassAmount;
    jfieldID curTransientDelta;
    jfieldID peakEnvelope;
    jfieldID limiterGainReductionDb;
    jfieldID vocalEnergy;
    jfieldID totalFramesProcessed;
    jfieldID appliedPeqRevision;
    jfieldID nativeInitialized;
    jfieldID dominantBand;
    jfieldID sampleRate;
} g_cache;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cls = env->FindClass("com/naomi/hoshimusic/playback/dsp/DspTelemetry");
    if (cls == nullptr) return JNI_ERR;
    g_cache.telemetryClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));

    g_cache.bassRatio = env->GetFieldID(cls, "bassRatio", "F");
    g_cache.midRatio = env->GetFieldID(cls, "midRatio", "F");
    g_cache.highRatio = env->GetFieldID(cls, "highRatio", "F");
    g_cache.curMudCutDb = env->GetFieldID(cls, "curMudCutDb", "F");
    g_cache.curLowMidGapCutDb = env->GetFieldID(cls, "curLowMidGapCutDb", "F");
    g_cache.curFormantBoostDb = env->GetFieldID(cls, "curFormantBoostDb", "F");
    g_cache.curVocalBoostDb = env->GetFieldID(cls, "curVocalBoostDb", "F");
    g_cache.curConsonantBoostDb = env->GetFieldID(cls, "curConsonantBoostDb", "F");
    g_cache.curAirBoostDb = env->GetFieldID(cls, "curAirBoostDb", "F");
    g_cache.curSideBoostDb = env->GetFieldID(cls, "curSideBoostDb", "F");
    g_cache.curDeEsserGainDb = env->GetFieldID(cls, "curDeEsserGainDb", "F");
    g_cache.curExciterAmount = env->GetFieldID(cls, "curExciterAmount", "F");
    g_cache.curVirtualBassAmount = env->GetFieldID(cls, "curVirtualBassAmount", "F");
    g_cache.curTransientDelta = env->GetFieldID(cls, "curTransientDelta", "F");
    g_cache.peakEnvelope = env->GetFieldID(cls, "peakEnvelope", "F");
    g_cache.limiterGainReductionDb = env->GetFieldID(cls, "limiterGainReductionDb", "F");
    g_cache.vocalEnergy = env->GetFieldID(cls, "vocalEnergy", "F");
    g_cache.totalFramesProcessed = env->GetFieldID(cls, "totalFramesProcessed", "J");
    g_cache.appliedPeqRevision = env->GetFieldID(cls, "appliedPeqRevision", "I");
    g_cache.nativeInitialized = env->GetFieldID(cls, "nativeInitialized", "Z");
    g_cache.dominantBand = env->GetFieldID(cls, "dominantBand", "Ljava/lang/String;");
    g_cache.sampleRate = env->GetFieldID(cls, "sampleRate", "I");

    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeCreate(JNIEnv* env, jobject thiz) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    hoshi::DspEngine* engine = new (std::nothrow) hoshi::DspEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeInit(JNIEnv* env, jobject thiz, jlong handle, jint sampleRate) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) {
        if (sampleRate < 8000 || sampleRate > 768000) return; // Sanity check
        dsp->init(sampleRate);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeProcess(JNIEnv* env, jobject thiz, jlong handle, jobject buffer, jint length) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp && buffer) {
        const jlong capacity = env->GetDirectBufferCapacity(buffer);
        if (length > capacity) length = static_cast<jint>(capacity);
        if (length < 8) return;
        length &= ~7; // Must be multiple of 8 (Float Stereo frame)

        float* ptr = static_cast<float*>(env->GetDirectBufferAddress(buffer));
        if (ptr) dsp->process(ptr, length / 8);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeFlush(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->flush();
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->reset();
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetSystemVolume(JNIEnv* env, jobject thiz, jlong handle, jfloat volume) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setSystemVolume(volume);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeApplyPeqSnapshot(
    JNIEnv* env, jobject thiz, jlong handle,
    jboolean enabled, jfloat preampDb, jint count,
    jintArray typesArr, jfloatArray fcArr, jfloatArray gainArr, jfloatArray qArr,
    jint revision
) {
    auto* engine = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (!engine) return;

    jint types[10];
    jfloat fc[10];
    jfloat gain[10];
    jfloat q[10];

    env->GetIntArrayRegion(typesArr, 0, 10, types);
    env->GetFloatArrayRegion(fcArr, 0, 10, fc);
    env->GetFloatArrayRegion(gainArr, 0, 10, gain);
    env->GetFloatArrayRegion(qArr, 0, 10, q);

    engine->applyPeqSnapshot(enabled == JNI_TRUE, preampDb, count, types, fc, gain, q, static_cast<uint32_t>(revision));
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetAdaptiveDspEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setAdaptiveDspEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetPeqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setPeqEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetParams(JNIEnv* env, jobject thiz, jlong handle, jfloatArray arr) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (!dsp || !arr) return;
    const jsize len = env->GetArrayLength(arr);
    if (len <= 0) return;
    jfloat* elems = env->GetFloatArrayElements(arr, nullptr);
    if (elems) {
        dsp->setParams(elems, (len < hoshi::DspEngine::PARAM_COUNT) ? len : hoshi::DspEngine::PARAM_COUNT);
        env->ReleaseFloatArrayElements(arr, elems, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetBtClarity(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setBtClarity(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetAmbience(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setAmbience(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetPreampGain(JNIEnv* env, jobject thiz, jlong handle, jfloat gain) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setPreampGain(gain);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetCrossfeedPreset(JNIEnv* env, jobject thiz, jlong handle, jint preset) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setCrossfeedPreset(preset);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetPeqBand(JNIEnv* env, jobject thiz, jlong handle, jint slot, jint type, jfloat fc, jfloat gain, jfloat q) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) {
        if (!std::isfinite(fc) || !std::isfinite(gain) || !std::isfinite(q)) return;
        dsp->setPeqBand(slot, type, fc, gain, q);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeSetPeqCount(JNIEnv* env, jobject thiz, jlong handle, jint n) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setPeqCount(n);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    delete dsp;
}

JNIEXPORT jobject JNICALL Java_com_naomi_hoshimusic_playback_HoshiDspProcessor_nativeGetTelemetrySnapshot(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (!dsp || !g_cache.telemetryClass) return nullptr;
    hoshi::DspTelemetryData data = dsp->getTelemetry();
    jobject obj = env->AllocObject(g_cache.telemetryClass);
    if (!obj) return nullptr;
    env->SetFloatField(obj, g_cache.bassRatio, data.bassRatio);
    env->SetFloatField(obj, g_cache.midRatio, data.midRatio);
    env->SetFloatField(obj, g_cache.highRatio, data.highRatio);
    env->SetFloatField(obj, g_cache.curMudCutDb, data.curMudCutDb);
    env->SetFloatField(obj, g_cache.curLowMidGapCutDb, data.curLowMidGapCutDb);
    env->SetFloatField(obj, g_cache.curFormantBoostDb, data.curFormantBoostDb);
    env->SetFloatField(obj, g_cache.curVocalBoostDb, data.curVocalBoostDb);
    env->SetFloatField(obj, g_cache.curConsonantBoostDb, data.curConsonantBoostDb);
    env->SetFloatField(obj, g_cache.curAirBoostDb, data.curAirBoostDb);
    env->SetFloatField(obj, g_cache.curSideBoostDb, data.curSideBoostDb);
    env->SetFloatField(obj, g_cache.curDeEsserGainDb, data.curDeEsserGainDb);
    env->SetFloatField(obj, g_cache.curExciterAmount, data.curExciterAmount);
    env->SetFloatField(obj, g_cache.curVirtualBassAmount, data.curVirtualBassAmount);
    env->SetFloatField(obj, g_cache.curTransientDelta, data.curTransientDelta);
    env->SetFloatField(obj, g_cache.peakEnvelope, data.peakEnvelope);
    env->SetFloatField(obj, g_cache.limiterGainReductionDb, data.limiterGainReductionDb);
    env->SetFloatField(obj, g_cache.vocalEnergy, data.vocalEnergy);
    env->SetLongField(obj, g_cache.totalFramesProcessed, (jlong)data.totalFramesProcessed);
    env->SetIntField(obj, g_cache.appliedPeqRevision, (jint)data.appliedPeqRevision);
    env->SetBooleanField(obj, g_cache.nativeInitialized, data.nativeInitialized ? JNI_TRUE : JNI_FALSE);
    jstring dominantStr = env->NewStringUTF(data.dominantBand);
    env->SetObjectField(obj, g_cache.dominantBand, dominantStr);
    env->DeleteLocalRef(dominantStr);
    env->SetIntField(obj, g_cache.sampleRate, data.sampleRate);
    return obj;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_cache.telemetryClass) {
            env->DeleteGlobalRef(g_cache.telemetryClass);
            g_cache.telemetryClass = nullptr;
        }
    }
}

}

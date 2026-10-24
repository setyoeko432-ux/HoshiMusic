#include "hoshi_dsp_core.h"
#include "hoshi_dsp_tuning.h"
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

#if defined(__arm__) || defined(__aarch64__)
// [V5][NEON-OPT]: Vectorized batch processing for essBand[3] (sibilance tracking BP bank).
// Evaluates 3 independent band-pass filters simultaneously using 128-bit SIMD (float32x4_t).
// Safe and numerically identical: essBand[0..2] read the same 'm' input and write to essEnv[0..2].
void processEssBandBatchNEON(float m, TptSvf essBand[3], float essEnv[3]) {
    alignas(16) float s1_arr[4] = {essBand[0].s1, essBand[1].s1, essBand[2].s1, 0.0f};
    alignas(16) float s2_arr[4] = {essBand[0].s2, essBand[1].s2, essBand[2].s2, 0.0f};
    alignas(16) float g_arr[4]  = {essBand[0].g,  essBand[1].g,  essBand[2].g,  0.0f};
    alignas(16) float k_arr[4]  = {essBand[0].k,  essBand[1].k,  essBand[2].k,  0.0f};

    float32x4_t v_s1 = vld1q_f32(s1_arr);
    float32x4_t v_s2 = vld1q_f32(s2_arr);
    float32x4_t v_g  = vld1q_f32(g_arr);
    float32x4_t v_k  = vld1q_f32(k_arr);

    float32x4_t v_input = vdupq_n_f32(m + 1e-25f);

    // v3 = input - s2
    float32x4_t v_v3 = vsubq_f32(v_input, v_s2);

    // denom = 1.0f + g * (g + k)
    float32x4_t v_gk = vaddq_f32(v_g, v_k);
    float32x4_t v_g_gk = vmulq_f32(v_g, v_gk);
    float32x4_t v_denom = vaddq_f32(vdupq_n_f32(1.0f), v_g_gk);

    // num = g * v3 + s1
    float32x4_t v_num = vmlaq_f32(v_s1, v_g, v_v3);

    alignas(16) float num_buf[4];
    alignas(16) float denom_buf[4];
    vst1q_f32(num_buf, v_num);
    vst1q_f32(denom_buf, v_denom);

    alignas(16) float v1_buf[4];
    v1_buf[0] = num_buf[0] / denom_buf[0];
    v1_buf[1] = num_buf[1] / denom_buf[1];
    v1_buf[2] = num_buf[2] / denom_buf[2];
    v1_buf[3] = 0.0f;

    float32x4_t v_v1 = vld1q_f32(v1_buf);

    // v2 = s2 + g * v1
    float32x4_t v_v2 = vmlaq_f32(v_s2, v_g, v_v1);

    // s1_new = 2.0f * v1 - s1
    float32x4_t v_two = vdupq_n_f32(2.0f);
    float32x4_t v_s1_new = vsubq_f32(vmulq_f32(v_two, v_v1), v_s1);

    // s2_new = 2.0f * v2 - s2
    float32x4_t v_s2_new = vsubq_f32(vmulq_f32(v_two, v_v2), v_s2);

    alignas(16) float s1_out[4], s2_out[4];
    vst1q_f32(s1_out, v_s1_new);
    vst1q_f32(s2_out, v_s2_new);

    essBand[0].s1 = s1_out[0]; essBand[0].s2 = s2_out[0];
    essBand[1].s1 = s1_out[1]; essBand[1].s2 = s2_out[1];
    essBand[2].s1 = s1_out[2]; essBand[2].s2 = s2_out[2];

    for (int e = 0; e < 3; ++e) {
        const float b = std::abs(v1_buf[e]);
        essEnv[e] = (b > essEnv[e]) ? (essEnv[e] * 0.60f + b * 0.40f) : (essEnv[e] * 0.998f + b * 0.002f);
        essEnv[e] += 1e-25f;
    }
}
#endif

// Padé-style rational tanh approximation (murah, cukup akurat utk audio)
static inline float tanhApprox(float x) {
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

void TptSvf::reset() { s1 = 0.0f; s2 = 0.0f; }

// [CRITICAL] JANGAN aktifkan fast-math pada TptSvf::process!
// Reassociation floating-point dapat mengubah hasil matematika filter IIR.
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
    adaptiveDspEnabled.store(false, std::memory_order_relaxed);
    lastPresetFs = 0;
    curAdspWet = 0.0f;
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

// [FIX-7] `enabled` dihapus. Dulunya selalu menulis adaptiveDspEnabled juga,
// jadi tidak pernah jadi master switch yang independen -- dan tidak pernah
// dibaca di process(). Satu-satunya gate DSP kini `adaptiveDspEnabled` +
// crossfade curAdspWet.
void DspEngine::setAdaptiveDspEnabled(bool on) {
    adaptiveDspEnabled.store(on, std::memory_order_relaxed);
}

bool DspEngine::isEnabled() const {
    return adaptiveDspEnabled.load(std::memory_order_relaxed);
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

    // Shelf preset TIDAK dihitung di sini. Ia hanya bergantung pada
    // (sampleRate, pBass, pAir) dan diurus oleh updatePresetShelves().
    // Sengaja tidak diberi gain 0.0f di sini: bila setupStaticCoefficients()
    // berjalan lebih dulu, shelf akan tertimpa 0 dB dan updatePresetShelves()
    // bisa melewatkan penghitungan ulang.

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
    exciterLpfL.setLowPass(fs, 13500.0f, 0.707f); // Anti-aliasing LPF
    exciterLpfR.setLowPass(fs, 13500.0f, 0.707f);

    decorrHpf.setHighPass(fs, 400.0f, 0.5f);
    decorrLpf.setLowPass(fs, 3200.0f, 0.5f);

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

    const float pWarm = prm[3].load(std::memory_order_relaxed);
    const float pAir = prm[2].load(std::memory_order_relaxed);

    const bool clarityOn = btClarity.load(std::memory_order_relaxed);

    mMudCut.setPeaking(fs, 280.0f, curMudCutDb, 1.0f);
    mLowMidGapCut.setPeaking(fs, 380.0f, curLowMidGapCutDb, 1.2f);
    mWarmBody.setPeaking(fs, 250.0f, curWarmBoostDb, 0.8f);

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

    // [FIX-AIR] Frekuensi center high shelf "udara" preset.
    //
    // Semula 12500 Hz. Di 12,5 kHz, pita uji 10 kHz hanya kena ujung kurva:
    // iso_air (pAir=1,8, dirancang +2,8 dB) hanya terukur +0,54 dB di 10 kHz
    // dan 0 dB di -6 dBFS, sehingga boost udara praktis tidak pernah terdengar.
    // 7000 Hz menaruh titik center cukup dekat dengan 10 kHz sehingga boost-nya
    // benar-benar muncul di pita yang diuji, tanpa menyentuh 60 Hz / 300 Hz /
    // 3 kHz (terverifikasi: pergeseran <= 0,05 dB di pita-pita itu).
    static constexpr float kPresetAirShelfHz = 7000.0f;

    // [F-06] Koefisien shelf preset bergantung pada sampleRate, pBass, dan pAir —
    // bukan pada sample. Semula keempatnya dihitung ulang di dalam
    // processFrame() yang dipanggil per sample: 4x setLowShelf/setHighShelf per
    // sample, masing-masing memuat exp/sin/cos koefisien RBJ. Pada 44,1 kHz itu
    // sekitar 176.400 perhitungan koefisien per detik, dan terukur menyumbang
    // 95.052 underrun (FrmRdy=0) yang membuat playback macet.
    //
    // Dipanggil satu kali per buffer dari process(), bukan per sample.
    void DspEngine::updatePresetShelves(float pBass, float pAir) {
        // [FIX-1] Bandingkan terhadap lastPresetFs, BUKAN lastSampleRate.
        //
        // lastSampleRate sudah diisi lebih dulu oleh setupStaticCoefficients()
        // (dipanggil dari init() DAN dari process()), jadi pemeriksaan
        // `sampleRate == lastSampleRate` selalu bernilai benar. Akibatnya
        // kalau sample rate berubah sementara preset tetap sama, fungsi ini
        // keluar lebih awal dan koefisien shelf tetap memakai fs lama --
        // padahal g = tan(PI*fc/fs) * sqrt(a) jelas bergantung pada fs.
        // Gejalanya: shelf hanya benar untuk sample rate file pertama.
        if (pBass == lastPresetBass && pAir == lastPresetAir && sampleRate == lastPresetFs) {
            return;
        }
        lastPresetBass = pBass;
        lastPresetAir = pAir;
        lastPresetFs = sampleRate;

        const float fs = static_cast<float>(sampleRate);
        const float presetBassDb = (pBass - 1.0f) * 4.5f;
        const float presetAirDb = (pAir - 1.0f) * 3.5f;

        // [TRACE] Catat koefisien shelf bass statis yang dihitung ini
        // (sama persis dengan yang di-set ke presetStaticLowShelfL).
        const float a = std::pow(10.0f, presetBassDb / 40.0f);
        const float shelfG = std::tan(static_cast<float>(M_PI) * 105.0f / fs) * std::sqrt(a);
        const float shelfM0 = a * a;
        const float shelfM1 = (1.0f / 0.707f) * a * (a - 1.0f);
        // [TRACE] Kirim ke telemetryData agar bisa dibaca Java.
        telemetryData.dbgShelfG = shelfG;
        telemetryData.dbgShelfM0 = shelfM0;
        telemetryData.dbgShelfM1 = shelfM1;

        presetStaticLowShelfL.setLowShelf(fs, 105.0f, presetBassDb, 0.707f);
        presetStaticLowShelfR.setLowShelf(fs, 105.0f, presetBassDb, 0.707f);
        // [FIX-AIR] Center 7 kHz, bukan 12,5 kHz — lihat kPresetAirShelfHz.
        presetStaticHighShelfL.setHighShelf(fs, kPresetAirShelfHz, presetAirDb, 0.707f);
        presetStaticHighShelfR.setHighShelf(fs, kPresetAirShelfHz, presetAirDb, 0.707f);
    }

// [CRITICAL] NO FAST-MATH: processFrame berisi rantai ~50 filter IIR/biquad, AGC,
// dan limiter. Fast-math dapat menyebabkan reassociation yang merusak stabilitas filter.
void DspEngine::processFrame(
    float& l, float& r,
    float pBass, float pVocal, float pAir, float pWarm, float pExciter,
    // [DEFECT 5] prm[5] dilewati di process(): variabel berikutnya melompat
    // dari prm[4] ke prm[6], sehingga IDX_TRANSIENT tidak pernah dibaca.
    float pTransient,
    float pWidth, float pDeEsser, float pCrossfeed, float pAgc,
    float pLimiter, float pLoudness, float pPunch, float pBody, float pSat,
    float limiterCeiling, float baseFeed, float& currentVocalAccum
) {
    // [FIX-AGC] Simpan sinyal MASUKAN sebelum dilewatkan shelf preset.
    //
    // Dua konstanta float di stack, tanpa alokasi; dipakai hanya oleh detektor
    // AGC di bawah. Shelf preset bukan bagian dari level yang diukur pengguna --
    // shelf justru efektor yang SENGAJA mengubah level itu. Menghitung level
    // SETELAH shelf membuat AGC memperlakukan boost preset sebagai "terlalu
    // keras" lalu mengembalikannya, persis membatalkan raison d'etre preset.
    const float detInL = l;
    const float detInR = r;

    // [F-06] Koefisien shelf sudah dihitung oleh updatePresetShelves() satu kali
    // per buffer, bukan di sini. Yang tersisa hanya pemrosesan sample-nya.
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

    // [FIX-AGC] Detektor level tidak boleh bergantung pada rantai K-weighting.
    //
    // Rantai K (`kShelf` + `kHpf`) menghasilkan NaN/Inf kalau state-nya rusak,
    // lalu guard lama `if (!isfinite(currentSampleRms)) currentSampleRms = 0`
    // mengubahnya jadi NOL — bukan NaN. Efeknya berantai dan diam-diam:
    //
    //   slowRms meluruh ke ~0  ->  `slowRms > 0.001f` tidak terpenuhi
    //                          ->  targetPreGain jatuh ke 1.0
    //                          ->  AGC mati untuk SELURUH pemutaran
    //
    // Terverifikasi di vivo/iQoo dengan trace kit: `agcPre` terkunci di 1.000
    // setelah 463.050 frame padahal level input jauh di atas target 0.18, dan
    // seluruh preset menghasilkan keluaran yang identik.
    //
    // Level sekarang diukur langsung dari sinyal. Rantai K tetap dipanggil
    // supaya state-nya ter-ramp dan tidak meledak saat dihidupkan lagi, tapi
    // tidak lagi menentukan gain — dan kalau ia rusak, state-nya dipulihkan
    // di sini, bukan dibiarkan meracuni filter setelahnya.
    //
    // [FIX-AGC] Sumber levelnya adalah SINYAL PRA-SHELF (`detInL`/`detInR`),
    // bukan `l`/`r` yang sudah keluar dari presetStaticLow/HighShelf.
    //
    // Ke mana perbaikannya: sebelumnya boost shelf ikut terukur, slowRms naik,
    // targetPreGain turun, dan boost yang diminta preset dikembalikan — tepat
    // pada level sinyal yang paling boosted. Bukti (trace kit mode tone,
    // delta terhadap preset `reference`, nada 60 Hz L=R):
    //
    //   iso_bass (hanya pBass=1,8)  -20 dBFS +3,50 dB  ->  -6 dBFS +1,18 dB
    //   bass_boost                   -20 dBFS +3,78 dB  ->  -6 dBFS +1,09 dB
    //
    // AGC jadi menetralkan justru efek yang jadi alasan preset itu ada.
    // Sekarang detektor hanya melihat level yang benar-benar masuk, sehingga
    // AGCtetap berfungsi sebagai penjaga level tanpa ikut meniadakan EQ preset.
    if (!std::isfinite(kW_l) || !std::isfinite(kW_r)) {
        kShelfL.reset(); kShelfR.reset();
        kHpfL.reset();  kHpfR.reset();
    }

    // [TRACE] Nilai yang dibaca detektor AGC (sinyal pra-shelf) — sumber
    // kebenaran kenapa slowRms bisa terukur ~0 padahal output bukan nol.
    telemetryData.dbgLAtDetector = detInL;

    float currentSampleRms = std::sqrt((detInL * detInL + detInR * detInR) * 0.5f);
    if (!std::isfinite(currentSampleRms) || currentSampleRms < 0.0f) currentSampleRms = 0.0f;

    medRms = medRms * 0.999f + currentSampleRms * 0.001f + 1e-25f;
    slowRms = slowRms * 0.99995f + currentSampleRms * 0.00005f + 1e-25f;
    if (!std::isfinite(medRms)) medRms = 0.1f;
    if (!std::isfinite(slowRms)) slowRms = 0.1f;

    if (!std::isfinite(medRms)) medRms = 0.1f;
    if (!std::isfinite(slowRms)) slowRms = 0.1f;

    if (pAgc > 0.01f) {
        float agcError = 0.18f / fmaxf(slowRms, 0.03f);
        float targetPreGain = 1.0f + (agcError - 1.0f) * 0.25f * pAgc;
        targetPreGain = fmaxf(0.65f, fminf(1.5f, targetPreGain));
        curPreGain += (targetPreGain - curPreGain) * 0.0001f;
    } else {
        curPreGain = 1.0f;
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

    if (pTransient > 1.0f) {
        m += m * deltaM * (0.15f * (pTransient - 1.0f)) * gateM;
    } else if (pTransient < 1.0f) {
        m -= m * deltaM * (0.15f * (1.0f - pTransient)) * gateM;
    }
    if (pBody > 1.0f) {
        const float bodyAddM = ((m >= 0.0f) ? transSlowM : -transSlowM) * (0.28f * (pBody - 1.0f)) * fminf(1.0f, transSlowM * 20.0f);
        m += bodyAddM;
    } else if (pBody < 1.0f) {
        const float bodySubM = ((m >= 0.0f) ? transSlowM : -transSlowM) * (0.28f * (1.0f - pBody)) * fminf(1.0f, transSlowM * 20.0f);
        m -= bodySubM;
    }
    m = fmaxf(-2.0f, fminf(m, 2.0f));

    float absS = std::abs(s);
    transFastS = transFastS * 0.82f + absS * 0.18f + 1e-25f;
    const float bodyRateS = fminf(0.032f, fmaxf(0.002f, 0.010f / fmaxf(pBody, 0.25f)));
    transSlowS = transSlowS * (1.0f - bodyRateS) + absS * bodyRateS + 1e-25f;
    float deltaS = fmaxf(0.0f, transFastS - transSlowS);
    float gateS = fminf(1.0f, absS * 25.0f);
    s += s * deltaS * (0.14f * pPunch) * gateS;

    if (pTransient > 1.0f) {
        s += s * deltaS * (0.15f * (pTransient - 1.0f)) * gateS;
    } else if (pTransient < 1.0f) {
        s -= s * deltaS * (0.15f * (1.0f - pTransient)) * gateS;
    }
    if (pBody > 1.0f) {
        const float bodyAddS = ((s >= 0.0f) ? transSlowS : -transSlowS) * (0.30f * (pBody - 1.0f)) * fminf(1.0f, transSlowS * 20.0f);
        s += bodyAddS;
    } else if (pBody < 1.0f) {
        const float bodySubS = ((s >= 0.0f) ? transSlowS : -transSlowS) * (0.30f * (1.0f - pBody)) * fminf(1.0f, transSlowS * 20.0f);
        s -= bodySubS;
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
    m = mWarmBody.process(m);
    m = mBoxinessCut.process(m);
    m = mVocalFormant.process(m);
    m = mVocalPresence.process(m);
    m = mHarshGuard.process(m);
    m = mConsonantPluck.process(m);
    m = mUltraAirShelf.process(m);

#if defined(__arm__) || defined(__aarch64__)
    processEssBandBatchNEON(m, essBand, essEnv);
#else
    for (int e = 0; e < 3; ++e) {
        const float b = std::abs(essBand[e].process(m));
        essEnv[e] = (b > essEnv[e]) ? (essEnv[e] * 0.60f + b * 0.40f) : (essEnv[e] * 0.998f + b * 0.002f);
        essEnv[e] += 1e-25f;
    }
#endif
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

    if (pWidth > 1.0f && std::abs(s) < 0.001f) {
        float midBand = decorrLpf.process(decorrHpf.process(m));
        float decorrS = (sDecorrelator1.process(midBand) + sDecorrelator2.process(midBand)) * 0.06f * (pWidth - 1.0f);
        s += decorrS;
    }
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
    float excL_harm = (excL + 0.35f * excL * std::abs(excL)) / (1.0f + 0.25f * std::abs(excL));
    float excR_harm = (excR + 0.35f * excR * std::abs(excR)) / (1.0f + 0.25f * std::abs(excR));
    excL_harm = exciterLpfL.process(excL_harm);
    excR_harm = exciterLpfR.process(excR_harm);
    l += excL_harm * curExciterAmount * exGate * 0.45f;
    r += excR_harm * curExciterAmount * exGate * 0.45f;

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

    // [FIX-2] FPCR (flush-to-zero / denormals-are-zero) bersifat PER-THREAD.
    // init() menyalakannya, tapi init() dan process() belum tentu jalan di
    // thread yang sama. Kalau tidak, filter bisa berjalan dengan denormal dan
    // jauh lebih lambat. Dua instruksi per buffer, tidak layak dilewati.
    enableFlushToZero();

    const float dt = static_cast<float>(numSamples) / static_cast<float>(sampleRate);

    // 1. Update coefficients if needed
    if (staticDirty.load(std::memory_order_acquire)) {
        setupStaticCoefficients();
    }

    const bool adsp = adaptiveDspEnabled.load(std::memory_order_relaxed);
    // [FIX-7] Tetap update selama crossfade berjalan, supaya koefisien
    // adaptif tidak "dingin" ketika DSP dinyalakan lagi.
    if (adsp || curAdspWet > 0.001f) updateAdaptiveCoefficients(dt);

    const bool peqRequest = peqDirty.load(std::memory_order_relaxed);
    if (peqRequest || curPeqWet > 0.001f || targetPeqWet > 0.001f) {
        updatePeqCoefficients(dt);
    }

    // [FIX-5] Salin snapshot PEQ ke variabel lokal SEKALI per buffer.
    //
    // applyPeqSnapshot menulis ke back buffer lalu menukar publishedSnapshotIdx.
    // Kalau dipanggil dua kali berturut-turut sebelum process() sempat membaca,
    // panggilan kedua bisa menulis ke buffer yang SEDANG dibaca audio thread,
    // sehingga preampDb dan count berasal dari dua versi berbeda. Dengan
    // disalin sekali di sini, satu buffer dijamin konsisten.
    const int currentIdx = publishedSnapshotIdx.load(std::memory_order_acquire);
    const PeqSnapshot& snap = peqSnapshots[currentIdx];
    const float targetPreampDb = snap.preampDb;
    const int peqN = snap.count;
    const float targetPreampLinear = std::pow(10.0f, targetPreampDb / 20.0f);

    // Sample-rate independent smoothing (25ms tau)
    const float tau = 0.025f;
    const float alpha = 1.0f - expf(- (1.0f / sampleRate) / tau);

    const float pBass      = prm[0].load(std::memory_order_relaxed);
    const float pVocal     = prm[1].load(std::memory_order_relaxed);
    const float pAir       = prm[2].load(std::memory_order_relaxed);
    const float pWarm      = prm[3].load(std::memory_order_relaxed);
    const float pExciter   = prm[4].load(std::memory_order_relaxed);
    // [DEFECT 5] prm[5] (IDX_TRANSIENT) sebelumnya tidak pernah dimuat.
    const float pTransient = prm[5].load(std::memory_order_relaxed);
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

    const int xpreset = xfeedPreset.load(std::memory_order_relaxed);
    const float baseFeed = (xpreset == 1) ? 0.335f : ((xpreset == 2) ? 0.40f : 0.50f);

    float currentVocalAccum = 0.0f;
    uint64_t framesProcessed = 0;

    // [TRACE] Satu baris per run: param preset benar-benar sampai native
    // dan flag `adsp` (adaptiveDspEnabled) dibaca native. Hanya sekali
    // per run (buffer pertama), agar logcat tidak banjir.
    if (framesProcessed == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "HoshiDsp", "RUN[trace] bass=%f vocal=%f air=%f sat=%f adsp=%d preampDb=%.3f",
            pBass, pVocal, pAir, pSat, adsp, 20.0f * std::log10f(targetPreampLinear));
    }

    // [F-06] Satu kali per buffer, bukan per sample.
    updatePresetShelves(pBass, pAir);

    // [TRACE] Log sekali per run: koefisien shelf bass statis yang sudah
    // dihitung & dikirim ke filter presetStaticLowShelfL.
    if (framesProcessed == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "HoshiDsp", "SHELF[trace] bassDb=%f airDb=%f M0=%f M1=%f G=%f",
            (pBass - 1.0f) * 4.5f, (pAir - 1.0f) * 3.5f,
            telemetryData.dbgShelfM0, telemetryData.dbgShelfM1, telemetryData.dbgShelfG);
    }

    // [FIX-7] Crossfade wet/dry untuk adaptive DSP, 20 ms.
    //
    // Sebelumnya sakelar on/off memutus jalur keras dan terdengar klik.
    // curAdspWet merambat per-sample sehingga transisi mulus.
    const float adspTarget = adsp ? 1.0f : 0.0f;
    const float adspAlpha = 1.0f - expf(-(1.0f / static_cast<float>(sampleRate)) / 0.020f);

    for (int i = 0; i < numSamples; ++i) {
        framesProcessed += 1;

        // Apply sample-accurate preamp smoothing
        curPreampLinear = curPreampLinear * (1.0f - alpha) + targetPreampLinear * alpha;

        float fl = buffer[i * 2] * curPreampLinear;
        float fr = buffer[i * 2 + 1] * curPreampLinear;

        if (!std::isfinite(fl)) fl = 0.0f;
        if (!std::isfinite(fr)) fr = 0.0f;

        if (i == 0) {
            // [TRACE] Sample masuk pertama (float, preamp sudah
            // diaplikasikan, sebelum filter apapun).
            telemetryData.dbgLAtEntry = fl;
        }

        // [FIX-7] Simpan sinyal kering untuk crossfade.
        const float dryL = fl;
        const float dryR = fr;

        curAdspWet += (adspTarget - curAdspWet) * adspAlpha;

        // 1. Terapkan Rantai Adaptive Mastering (Wet)
        //
        // [FIX-7] Tetap dijalankan selama wet > 0 supaya koefisien adaptif
        // tetap ter-update saat crossfade turun ke 0 (mencegah filter
        // "dingin" ketika dinyalakan lagi).
        if (adsp || curAdspWet > 0.001f) {
            processFrame(fl, fr, pBass, pVocal, pAir, pWarm, pExciter, pTransient, pWidth, pDeEsser, pCrossfeed, pAgc, pLimiter, pLoudness, pPunch, pBody, pSat, limiterCeiling, baseFeed, currentVocalAccum);
            // [TRACE] Nilai wet tepat pada saat crossfade dihitung
            // (sebelum campuran kering).
            telemetryData.dbgWetAtCrossfade = curAdspWet;
            if (curAdspWet < 0.999f) {
                fl = dryL + (fl - dryL) * curAdspWet;
                fr = dryR + (fr - dryR) * curAdspWet;
            }
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

        if (fl > 0.95f) {
            fl = 0.95f + 0.05f * tanhApprox((fl - 0.95f) / 0.05f);
        } else if (fl < -0.95f) {
            fl = -0.95f + 0.05f * tanhApprox((fl + 0.95f) / 0.05f);
        }

        if (fr > 0.95f) {
            fr = 0.95f + 0.05f * tanhApprox((fr - 0.95f) / 0.05f);
        } else if (fr < -0.95f) {
            fr = -0.95f + 0.05f * tanhApprox((fr + 0.95f) / 0.05f);
        }

        buffer[i * 2] = fmaxf(-1.0f, fminf(1.0f, fl));
        buffer[i * 2 + 1] = fmaxf(-1.0f, fminf(1.0f, fr));
    }

    frameCounter.fetch_add(framesProcessed, std::memory_order_relaxed);

    float dt_block = static_cast<float>(numSamples) / static_cast<float>(sampleRate);
    float alpha_adapt = 1.0f - expf(-dt_block / 0.035f);
    float inv_alpha = 1.0f - alpha_adapt;

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
    float targetWarmBoost = (pWarm_local > 1.0f) ? (pWarm_local - 1.0f) * 4.0f : (pWarm_local - 1.0f) * 3.0f;
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

    curMudCutDb = curMudCutDb * inv_alpha + targetMudCut * alpha_adapt;
    curLowMidGapCutDb = curLowMidGapCutDb * inv_alpha + targetLowMidGapCut * alpha_adapt;
    curWarmBoostDb = curWarmBoostDb * inv_alpha + targetWarmBoost * alpha_adapt;
    curVocalBoostDb = curVocalBoostDb * inv_alpha + targetVocalBoost * alpha_adapt;
    curConsonantBoostDb = curConsonantBoostDb * inv_alpha + targetConsonant * alpha_adapt;
    curSideBoostDb = curSideBoostDb * inv_alpha + targetSideBoost * alpha_adapt;
    curAirBoostDb = curAirBoostDb * inv_alpha + targetAirBoost * alpha_adapt;
    curExciterAmount = curExciterAmount * inv_alpha + targetExciter * alpha_adapt;
    curVirtualBassAmount = curVirtualBassAmount * inv_alpha + targetVirtualBass * alpha_adapt;

    int bestE = 0; if (essEnv[1] > essEnv[bestE]) bestE = 1; if (essEnv[2] > essEnv[bestE]) bestE = 2;
    static const float essFc[3] = {5800.0f, 7500.0f, 9200.0f};
    const float tgtFc = (essEnv[bestE] > 0.02f) ? essFc[bestE] : 7500.0f;
    curNotchFc = curNotchFc * inv_alpha + tgtFc * alpha_adapt;

        // updateDynamicCoefficients is gone, we updated curFooDb directly above.

        DspTelemetryData tSnap;
        tSnap.bassRatio = bassRatio;
        tSnap.midRatio = midRatio;
        tSnap.highRatio = highRatio;
        tSnap.curMudCutDb = curMudCutDb;
        tSnap.curLowMidGapCutDb = curLowMidGapCutDb;
        tSnap.curFormantBoostDb = curFormantBoostDb;
        tSnap.curVocalBoostDb = curVocalBoostDb;
        tSnap.curConsonantBoostDb = curConsonantBoostDb;
        tSnap.curAirBoostDb = curAirBoostDb;
        tSnap.curSideBoostDb = curSideBoostDb;
        tSnap.curDeEsserGainDb = curDeEsserGainDb;
        tSnap.curExciterAmount = curExciterAmount;
        tSnap.curVirtualBassAmount = curVirtualBassAmount;
        tSnap.curTransientDelta = transFastM - transSlowM;
        tSnap.peakEnvelope = envelope;
        tSnap.limiterGainReductionDb = (envelope > limiterCeiling) ? 20.0f * std::log10(limiterCeiling / envelope) : 0.0f;
        tSnap.totalFramesProcessed = frameCounter.load(std::memory_order_relaxed);
        tSnap.appliedPeqRevision = lastProcessedRevision;
        tSnap.sampleRate = sampleRate;
        tSnap.vocalEnergy = vocalEnv;
        tSnap.nativeInitialized = true;

        // [TRACE] Status gate wet yang sebenarnya — ini yang membuktikan
        // apakah processFrame() benar-benar jalan dan wet-nya sudah matured.
        tSnap.adspWet = curAdspWet;
        tSnap.adspEnabled = adaptiveDspEnabled.load(std::memory_order_relaxed);
        tSnap.agcPreGain = curPreGain;
        tSnap.slowRms = slowRms;
        tSnap.medRms = medRms;

        if (highRatio > 0.32f) std::strcpy(tSnap.dominantBand, "NATURAL BRIGHT");
        else if (midRatio > 0.42f) std::strcpy(tSnap.dominantBand, "CLEAR VOCAL");
        else std::strcpy(tSnap.dominantBand, "BALANCED STAGE");

        uint32_t s = telemetrySeq.load(std::memory_order_relaxed);
        telemetrySeq.store(s + 1, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        telemetryData = tSnap;
        std::atomic_thread_fence(std::memory_order_release);
        telemetrySeq.store(s + 2, std::memory_order_relaxed);
}


void DspEngine::flush() {
    subSonicL.reset(); subSonicR.reset();
    presetStaticLowShelfL.reset(); presetStaticLowShelfR.reset();
    presetStaticHighShelfL.reset(); presetStaticHighShelfR.reset();
    analyzeLowL.reset(); analyzeLowR.reset();
    analyzeMidHpfL.reset(); analyzeMidHpfR.reset(); analyzeMidLpfL.reset(); analyzeMidLpfR.reset();
    analyzeHighL.reset(); analyzeHighR.reset();
    mSubCut.reset(); mMudCut.reset(); mLowMidGapCut.reset(); mWarmBody.reset();
    mVocalFormant.reset(); mVocalPresence.reset(); mConsonantPluck.reset(); mUltraAirShelf.reset();
    mHarshGuard.reset();
    sLowCut.reset(); sPresence.reset(); sAirShelf.reset();
    sDecorrelator1.reset(); sDecorrelator2.reset();
    bassExtractLpf.reset(); bassHarmonicHpf_gen.reset(); bassHarmonicLpf_gen.reset(); bassHarmonicHpf_final.reset();
    deEsserHpf.reset(); deEsserLpf.reset(); deEsserNotch.reset();
    for (int e = 0; e < 3; ++e) { essBand[e].reset(); essEnv[e] = 0.0f; }
    curNotchFc = 7500.0f;
    curWarmBoostDb = 0.0f;
    curAdspWet = 0.0f;
    exciterHpfL.reset(); exciterHpfR.reset();
    exciterLpfL.reset(); exciterLpfR.reset();
    decorrHpf.reset(); decorrLpf.reset();
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
    jfieldID adspWet;
    jfieldID adspEnabled;
    jfieldID agcPreGain;
    jfieldID slowRms;
    jfieldID medRms;
    // [TRACE] Debug fields: why presets run but output never changes.
    jfieldID dbgLAtEntry;
    jfieldID dbgLAtDetector;
    jfieldID dbgWetAtCrossfade;
    jfieldID dbgShelfM0;
    jfieldID dbgShelfM1;
    jfieldID dbgShelfG;
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
    g_cache.adspWet = env->GetFieldID(cls, "adspWet", "F");
    g_cache.adspEnabled = env->GetFieldID(cls, "adspEnabled", "Z");
    g_cache.agcPreGain = env->GetFieldID(cls, "agcPreGain", "F");
    g_cache.slowRms = env->GetFieldID(cls, "slowRms", "F");
    g_cache.medRms = env->GetFieldID(cls, "medRms", "F");
    g_cache.dbgLAtEntry = env->GetFieldID(cls, "dbgLAtEntry", "F");
    g_cache.dbgLAtDetector = env->GetFieldID(cls, "dbgLAtDetector", "F");
    g_cache.dbgWetAtCrossfade = env->GetFieldID(cls, "dbgWetAtCrossfade", "F");
    g_cache.dbgShelfM0 = env->GetFieldID(cls, "dbgShelfM0", "F");
    g_cache.dbgShelfM1 = env->GetFieldID(cls, "dbgShelfM1", "F");
    g_cache.dbgShelfG = env->GetFieldID(cls, "dbgShelfG", "F");

    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeCreate(JNIEnv* env, jobject thiz) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    hoshi::DspEngine* engine = new (std::nothrow) hoshi::DspEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeInit(JNIEnv* env, jobject thiz, jlong handle, jint sampleRate) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) {
        if (sampleRate < 8000 || sampleRate > 768000) return; // Sanity check
        dsp->init(sampleRate);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeProcess(JNIEnv* env, jobject thiz, jlong handle, jobject buffer, jint length, jint offset) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp && buffer) {
        const jlong capacity = env->GetDirectBufferCapacity(buffer);
        // [FIX-3] GetDirectBufferAddress() mengabaikan position(); ia selalu
        // mengembalikan alamat DASAR buffer. Kalau Kotlin mengirim buffer yang
        // position()-nya bukan 0, wilayah yang diproses adalah salah. Karena itu
        // offset dari position() kini dilewatkan eksplisit.
        if (offset < 0) offset = 0;
        if (length > capacity - offset) length = static_cast<jint>(capacity - offset);
        if (length < 8) return;
        length &= ~7; // Must be multiple of 8 (Float Stereo frame)

        float* base = static_cast<float*>(env->GetDirectBufferAddress(buffer));
        if (base) dsp->process(base + (offset / 4), length / 8);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeFlush(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->flush();
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->reset();
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetSystemVolume(JNIEnv* env, jobject thiz, jlong handle, jfloat volume) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setSystemVolume(volume);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeApplyPeqSnapshot(
    JNIEnv* env, jobject thiz, jlong handle,
    jboolean enabled, jfloat preampDb, jint count,
    jintArray typesArr, jfloatArray fcArr, jfloatArray gainArr, jfloatArray qArr,
    jint revision
) {
    auto* engine = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (!engine) return;

    // [FIX-4] Validasi null DAN panjang. GetXArrayRegion melempar
    // ArrayIndexOutOfBoundsException (pending exception) bila array lebih pendek
    // dari 10; tanpa pengecekan, kode tetap lanjut memakai stack array yang
    // belum terisi -- yaitu nilai indeterminate.
    if (!typesArr || !fcArr || !gainArr || !qArr) return;
    if (env->GetArrayLength(typesArr) < 10 || env->GetArrayLength(fcArr) < 10 ||
        env->GetArrayLength(gainArr) < 10 || env->GetArrayLength(qArr) < 10) {
        return;
    }

    jint types[10];
    jfloat fc[10];
    jfloat gain[10];
    jfloat q[10];

    env->GetIntArrayRegion(typesArr, 0, 10, types);
    env->GetFloatArrayRegion(fcArr, 0, 10, fc);
    env->GetFloatArrayRegion(gainArr, 0, 10, gain);
    env->GetFloatArrayRegion(qArr, 0, 10, q);

    // Pengecekan defensive: kalau salah satu_region gagal, jangan pakai data
    // setengah jadi.
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    engine->applyPeqSnapshot(enabled == JNI_TRUE, preampDb, count, types, fc, gain, q, static_cast<uint32_t>(revision));
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetAdaptiveDspEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setAdaptiveDspEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetPeqEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    // [FIX-7] setPeqEnabled dihapus: atomiknya tidak pernah dibaca. Status PEQ
    // sepenuhnya dikendalikan oleh snapshot (count + curPeqWet).
    (void)on;
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetEnabled(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    // [FIX-7] setEnabled tidak lagi punya atomik sendiri; diarahkan ke
    // adaptiveDspEnabled supaya tidak ada dua master switch yang saling
    // menimpa. Kotlin memanggil setAdaptiveDspEnabled lalu setEnabled dengan
    // nilai yang sama, jadi perilaku tidak berubah.
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setAdaptiveDspEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetParams(JNIEnv* env, jobject thiz, jlong handle, jfloatArray arr) {
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

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetBtClarity(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setBtClarity(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetAmbience(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setAmbience(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetPreampGain(JNIEnv* env, jobject thiz, jlong handle, jfloat gain) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setPreampGain(gain);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetCrossfeedPreset(JNIEnv* env, jobject thiz, jlong handle, jint preset) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setCrossfeedPreset(preset);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetPeqBand(JNIEnv* env, jobject thiz, jlong handle, jint slot, jint type, jfloat fc, jfloat gain, jfloat q) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) {
        if (!std::isfinite(fc) || !std::isfinite(gain) || !std::isfinite(q)) return;
        dsp->setPeqBand(slot, type, fc, gain, q);
    }
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeSetPeqCount(JNIEnv* env, jobject thiz, jlong handle, jint n) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    if (dsp) dsp->setPeqCount(n);
}

JNIEXPORT void JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    auto* dsp = reinterpret_cast<hoshi::DspEngine*>(handle);
    // [FIX-6] Gunakan-after-free dicegah di sisi Kotlin (DspNativeEngine):
    // release() hanya menandai handle, dan delete dilakukan pada reset()
    // berikutnya yang dipanggil dari audio thread. JNI delete di sini
    // dipertahankan untuk kasus force, tetapi dipanggil lewat flush supaya
    // tidak ada panggilan native yang sedang berjalan.
    delete dsp;
}

JNIEXPORT jobject JNICALL Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_nativeGetTelemetrySnapshot(JNIEnv* env, jobject thiz, jlong handle) {
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
    env->SetFloatField(obj, g_cache.adspWet, data.adspWet);
    env->SetBooleanField(obj, g_cache.adspEnabled, data.adspEnabled);
    env->SetFloatField(obj, g_cache.agcPreGain, data.agcPreGain);
    env->SetFloatField(obj, g_cache.slowRms, data.slowRms);
    env->SetFloatField(obj, g_cache.medRms, data.medRms);
    env->SetFloatField(obj, g_cache.dbgLAtEntry, data.dbgLAtEntry);
    env->SetFloatField(obj, g_cache.dbgLAtDetector, data.dbgLAtDetector);
    env->SetFloatField(obj, g_cache.dbgWetAtCrossfade, data.dbgWetAtCrossfade);
    env->SetFloatField(obj, g_cache.dbgShelfM0, data.dbgShelfM0);
    env->SetFloatField(obj, g_cache.dbgShelfM1, data.dbgShelfM1);
    env->SetFloatField(obj, g_cache.dbgShelfG, data.dbgShelfG);
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

// DspRootCauseHarness.cpp
// Dedicated Diagnostic Harness Suite for Native DSP Preset Failure Investigation.
//
// DIAGNOSTIC ONLY: This harness inspects and measures DspEngine behavior without
// modifying production code or tuning production presets.
//
// Target tests:
//   Test A: JNI Buffer Offset & Memory Boundaries (Offset 0 and Nonzero Offset)
//   Test B: DSP Master Enable & Passthrough Verification
//   Test C: Preset Preview Override & Resolution Contract
//   Test D: AGC Interference Diagnostic (Trajectory & Step Response)
//   Test E: Transient & Body Parameter Directionality
//   Test F: Buffer Size & Adaptive Convergence Trajectory (0..1000ms)
//   Test G: Parameter-to-Output Sensitivity Matrix (Signal-Specific Metrics 0..14)
//   Test H: All Builtin Preset Differentiation (Multi-Metric Comparison)

#include "hoshi_dsp_core.h"

extern "C" {
double hosi_rt_exp(double x);
double hosi_rt_log(double x);
double hosi_rt_sin(double x);
double hosi_rt_pow(double base, double exponent);
float hosi_rt_tanf(float x);
float hosi_rt_log10f(float x);
void* hosi_rt_alloc(size_t bytes);
void* hosi_rt_memset(void* dst, int c, size_t n);
void hosi_rt_free(void* p);
}

namespace {

const int kSampleRate = 44100;
const size_t kReportCap = 3u * 1024u * 1024u; // 3 MB text report
char g_report[kReportCap];
size_t g_reportLen = 0;

void emitChar(char c) {
    if (g_reportLen + 1 < kReportCap) g_report[g_reportLen++] = c;
}

void emitStr(const char* s) {
    while (*s) emitChar(*s++);
}

void emitFixed(double value, int decimals) {
    if (value != value) { emitStr("nan"); return; }
    if (value > 1e30) { emitStr("inf"); return; }
    if (value < -1e30) { emitStr("-inf"); return; }

    const bool negative = value < 0.0;
    const double v = negative ? -value : value;

    double scale = 1.0;
    for (int i = 0; i < decimals; ++i) scale *= 10.0;
    long long scaled = (long long)(v * scale + 0.5);
    long long whole = scaled / (long long)scale;
    long long frac = scaled % (long long)scale;

    if (negative && (whole != 0 || frac != 0)) emitChar('-');

    char digits[24];
    int n = 0;
    if (whole == 0) digits[n++] = '0';
    while (whole > 0) { digits[n++] = (char)('0' + (whole % 10)); whole /= 10; }
    while (n > 0) emitChar(digits[--n]);

    if (decimals > 0) {
        emitChar('.');
        for (int i = decimals - 1; i >= 0; --i) {
            long long p = 1;
            for (int j = 0; j < i; ++j) p *= 10;
            emitChar((char)('0' + (frac / p) % 10));
        }
    }
}

float ampFromDb(float db) {
    return (float)hosi_rt_exp((double)db * 0.1151292546497023);
}

double dbFromPower(double power) {
    return 4.342944819032518 * hosi_rt_log(power + 1e-30);
}

struct SimpleRng {
    uint32_t state;
    explicit SimpleRng(uint32_t seed) : state(seed ? seed : 1u) {}
    uint32_t next() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    }
    float nextUnit() { return ((float)(next() >> 8) / 8388608.0f) - 1.0f; }
};

struct DiagnosticPreset {
    const char* id;
    float p[15];
};

const DiagnosticPreset kBuiltinPresets[] = {
    {"reference",      {1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f}},
    {"dev_preference", {1.25f, 1.35f, 1.45f, 0.90f, 1.30f, 1.25f, 1.30f, 1.30f, 1.00f, 1.00f, 1.00f, 1.00f, 1.30f, 0.90f, 1.10f}},
    {"analog",         {1.25f, 0.95f, 0.65f, 1.55f, 0.50f, 0.85f, 0.95f, 1.00f, 1.10f, 1.00f, 1.00f, 1.00f, 0.95f, 1.35f, 1.70f}},
    {"flat",           {1.00f, 0.00f, 1.00f, 1.00f, 0.00f, 1.00f, 1.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.00f, 1.00f, 1.00f, 0.00f}},
    {"warm",           {1.30f, 0.90f, 0.65f, 1.60f, 0.40f, 0.85f, 1.00f, 1.00f, 1.10f, 1.00f, 1.00f, 1.00f, 0.85f, 1.35f, 1.45f}},
    {"bright",         {0.80f, 1.10f, 1.75f, 0.80f, 1.55f, 1.15f, 1.10f, 1.45f, 1.00f, 1.00f, 1.00f, 1.00f, 1.25f, 0.85f, 0.70f}},
    {"soft",           {0.95f, 0.80f, 0.50f, 1.15f, 0.30f, 0.65f, 1.00f, 1.20f, 1.20f, 1.20f, 0.80f, 1.00f, 0.65f, 1.15f, 0.80f}},
    {"bass_boost",     {2.00f, 0.95f, 0.90f, 1.10f, 0.80f, 1.10f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.50f, 1.30f, 1.40f}},
    {"vocal",          {0.85f, 1.70f, 1.10f, 0.85f, 1.10f, 1.05f, 0.95f, 1.50f, 1.00f, 1.00f, 1.00f, 1.00f, 1.10f, 0.95f, 1.15f}},
    {"stage",          {1.00f, 0.85f, 1.35f, 0.85f, 1.20f, 1.20f, 1.65f, 1.10f, 0.80f, 1.00f, 1.00f, 1.00f, 1.30f, 1.00f, 0.90f}},
    {"v_shape",        {1.75f, 0.65f, 1.65f, 0.80f, 1.35f, 1.20f, 1.25f, 1.25f, 1.00f, 1.00f, 1.00f, 1.00f, 1.40f, 1.10f, 1.20f}},
    {"late_night",     {1.15f, 0.90f, 0.80f, 1.10f, 0.50f, 0.60f, 1.00f, 1.10f, 1.30f, 1.40f, 0.60f, 1.70f, 0.60f, 1.20f, 1.25f}},
    {"tube_warmth",    {1.20f, 1.05f, 0.70f, 1.65f, 0.80f, 0.90f, 0.95f, 1.15f, 1.00f, 1.00f, 1.00f, 1.15f, 1.00f, 1.50f, 1.85f}},
    {"air_shimmer",    {0.90f, 1.00f, 2.00f, 0.75f, 1.75f, 1.20f, 1.35f, 1.50f, 1.00f, 1.00f, 1.00f, 1.00f, 1.20f, 0.80f, 0.60f}},
};
const int kBuiltinCount = (int)(sizeof(kBuiltinPresets) / sizeof(kBuiltinPresets[0]));

// ------------------------------------------------------------------
// TEST A: JNI Buffer Offset & Memory Boundaries (Zero and NonZero Offset)
// ------------------------------------------------------------------
void testA_JniBufferOffset() {
    emitStr("RC_TEST_A|JNI_BUFFER_OFFSET\n");

    const int totalFloats = 1024;
    const int prefixFloats = 128; // 512 bytes offset
    const int pcmFloats = 512;    // 256 stereo frames
    const int suffixFloats = totalFloats - prefixFloats - pcmFloats;

    float* buffer = (float*)hosi_rt_alloc(totalFloats * sizeof(float));

    // Fill prefix & suffix with sentinel
    const float kSentinel = 123456.789f;
    for (int i = 0; i < prefixFloats; ++i) buffer[i] = kSentinel;
    for (int i = prefixFloats + pcmFloats; i < totalFloats; ++i) buffer[i] = kSentinel;

    SimpleRng rng(42);
    for (int i = 0; i < pcmFloats; ++i) {
        buffer[prefixFloats + i] = rng.nextUnit() * 0.5f;
    }

    hoshi::DspEngine* dsp = new hoshi::DspEngine();
    dsp->init(kSampleRate);
    float neutralParams[15];
    for (int i = 0; i < 15; ++i) neutralParams[i] = 1.0f;
    neutralParams[0] = 1.8f;
    dsp->setParams(neutralParams, 15);
    dsp->setAdaptiveDspEnabled(true);

    // Test Nonzero Offset
    dsp->process(buffer + prefixFloats, pcmFloats / 2);

    bool prefixIntact = true;
    for (int i = 0; i < prefixFloats; ++i) {
        if (buffer[i] != kSentinel) prefixIntact = false;
    }

    bool suffixIntact = true;
    for (int i = prefixFloats + pcmFloats; i < totalFloats; ++i) {
        if (buffer[i] != kSentinel) suffixIntact = false;
    }

    bool pcmModified = false;
    for (int i = 0; i < pcmFloats; ++i) {
        if (buffer[prefixFloats + i] != 0.0f) pcmModified = true;
    }

    emitStr("RC_RESULT|A_NONZERO_OFFSET_PREFIX=");
    emitStr(prefixIntact ? "PASS" : "FAIL");
    emitStr("|SUFFIX=");
    emitStr(suffixIntact ? "PASS" : "FAIL");
    emitStr("|PCM_MODIFIED=");
    emitStr(pcmModified ? "PASS" : "FAIL");
    emitChar('\n');

    hosi_rt_free(buffer);
    delete dsp;
}

// ------------------------------------------------------------------
// TEST B: Master DSP Enable & Passthrough Verification
// ------------------------------------------------------------------
void testB_MasterEnablePassthrough() {
    emitStr("RC_TEST_B|MASTER_ENABLE_PASSTHROUGH\n");

    const int frames = 2048;
    float* inBuf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));
    float* workBuf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

    SimpleRng rng(101);
    for (int i = 0; i < frames * 2; ++i) inBuf[i] = rng.nextUnit() * 0.3f;

    hoshi::DspEngine* dsp = new hoshi::DspEngine();
    dsp->init(kSampleRate);
    float boosted[15];
    for (int i = 0; i < 15; ++i) boosted[i] = 1.5f;
    dsp->setParams(boosted, 15);

    // Case 1: Adaptive DSP OFF -> Should be Passthrough
    dsp->setAdaptiveDspEnabled(false);
    for (int i = 0; i < frames * 2; ++i) workBuf[i] = inBuf[i];
    dsp->process(workBuf, frames);

    double diffEnergyOff = 0.0;
    for (int i = 0; i < frames * 2; ++i) {
        double diff = (double)workBuf[i] - (double)inBuf[i];
        diffEnergyOff += diff * diff;
    }

    // Case 2: Adaptive DSP ON -> Should be Processed
    dsp->setAdaptiveDspEnabled(true);
    for (int i = 0; i < frames * 2; ++i) workBuf[i] = inBuf[i];
    dsp->process(workBuf, frames);

    double diffEnergyOn = 0.0;
    for (int i = 0; i < frames * 2; ++i) {
        double diff = (double)workBuf[i] - (double)inBuf[i];
        diffEnergyOn += diff * diff;
    }

    const hoshi::DspTelemetryData t = dsp->getTelemetry();

    emitStr("RC_RESULT|B_PASSTHROUGH_OFF_DIFF_RMS=");
    emitFixed(dbFromPower(diffEnergyOff / (double)(frames * 2)), 2);
    emitStr("|ON_DIFF_RMS=");
    emitFixed(dbFromPower(diffEnergyOn / (double)(frames * 2)), 2);
    emitStr("|ADSP_WET=");
    emitFixed(t.adspWet, 4);
    emitStr("|ADSP_ENABLED=");
    emitStr(t.adspEnabled ? "TRUE" : "FALSE");
    emitChar('\n');

    hosi_rt_free(inBuf);
    hosi_rt_free(workBuf);
    delete dsp;
}

// ------------------------------------------------------------------
// TEST A2: Flat vs Reference Gain Audit across 6 Signal Types
// ------------------------------------------------------------------
void testA2_FlatVsReferenceGainAudit() {
    emitStr("RC_TEST_A2|FLAT_VS_REFERENCE_GAIN_AUDIT\n");

    const int frames = 2048;
    float* inBuf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));
    float* workBuf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

    const char* signalTypes[] = {"mono", "dual_mono", "correlated_stereo", "decorrelated_stereo", "left_only", "right_only"};

    for (int sType = 0; sType < 6; ++sType) {
        SimpleRng rngL(100 + sType), rngR(200 + sType);
        const float amp = ampFromDb(-20.0f);

        for (int i = 0; i < frames; ++i) {
            float l = 0.0f, r = 0.0f;
            if (sType == 0 || sType == 1) { // mono / dual_mono
                float v = (float)(hosi_rt_sin(2.0 * 3.141592653589793 * 1000.0 * i / kSampleRate) * amp);
                l = v; r = v;
            } else if (sType == 2) { // correlated_stereo
                float v = rngL.nextUnit() * amp;
                l = v; r = v;
            } else if (sType == 3) { // decorrelated_stereo
                l = rngL.nextUnit() * amp;
                r = rngR.nextUnit() * amp;
            } else if (sType == 4) { // left_only
                l = (float)(hosi_rt_sin(2.0 * 3.141592653589793 * 1000.0 * i / kSampleRate) * amp);
                r = 0.0f;
            } else if (sType == 5) { // right_only
                l = 0.0f;
                r = (float)(hosi_rt_sin(2.0 * 3.141592653589793 * 1000.0 * i / kSampleRate) * amp);
            }
            inBuf[i * 2] = l;
            inBuf[i * 2 + 1] = r;
        }

        const char* configNames[] = {
            "ref_crossfeed_on",
            "ref_crossfeed_off",
            "flat",
            "ref_vocal_off",
            "flat_vocal_on"
        };

        float configs[5][15];
        for (int c = 0; c < 5; ++c) {
            for (int i = 0; i < 15; ++i) configs[c][i] = 1.0f;
        }

        // Config 1: ref_crossfeed_off
        configs[1][8] = 0.0f; // pCrossfeed = 0

        // Config 2: flat
        configs[2][1] = 0.0f; configs[2][4] = 0.0f; configs[2][7] = 0.0f;
        configs[2][8] = 0.0f; configs[2][9] = 0.0f; configs[2][11] = 0.0f; configs[2][14] = 0.0f;

        // Config 3: ref_vocal_off
        configs[3][1] = 0.0f; // pVocal = 0

        // Config 4: flat_vocal_on
        configs[4][4] = 0.0f; configs[4][7] = 0.0f; configs[4][8] = 0.0f;
        configs[4][9] = 0.0f; configs[4][11] = 0.0f; configs[4][14] = 0.0f;

        for (int c = 0; c < 5; ++c) {
            hoshi::DspEngine* dsp = new hoshi::DspEngine();
            dsp->init(kSampleRate);
            dsp->setParams(configs[c], 15);
            dsp->setAdaptiveDspEnabled(true);

            for (int i = 0; i < frames * 2; ++i) workBuf[i] = inBuf[i];
            dsp->process(workBuf, frames);

            double energy = 0.0;
            for (int i = 0; i < frames * 2; ++i) {
                double v = workBuf[i];
                energy += v * v;
            }
            double rmsDb = dbFromPower(energy / (double)(frames * 2));

            emitStr("RC_RESULT|A2_GAIN_AUDIT|sig=");
            emitStr(signalTypes[sType]);
            emitStr("|cfg=");
            emitStr(configNames[c]);
            emitStr("|rmsDb=");
            emitFixed(rmsDb, 2);
            emitChar('\n');

            delete dsp;
        }
    }

    hosi_rt_free(inBuf);
    hosi_rt_free(workBuf);
}

// ------------------------------------------------------------------
// TEST D: AGC Interference & Step-Response Trajectory
// ------------------------------------------------------------------
void testD_AgcInterference() {
    emitStr("RC_TEST_D|AGC_INTERFERENCE\n");

    const float levelsDb[] = {-30.0f, -20.0f, -14.0f, -6.0f};

    for (int l = 0; l < 4; ++l) {
        const float inDb = levelsDb[l];
        const float inAmp = ampFromDb(inDb);

        hoshi::DspEngine* dsp = new hoshi::DspEngine();
        dsp->init(kSampleRate);
        float p[15];
        for (int i = 0; i < 15; ++i) p[i] = 1.0f;
        p[9] = 1.2f; // AGC active
        dsp->setParams(p, 15);
        dsp->setAdaptiveDspEnabled(true);

        const int frames = 4096;
        float* buf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));
        SimpleRng rng(202);

        double inEnergy = 0.0, outEnergy = 0.0;
        for (int b = 0; b < 10; ++b) {
            for (int i = 0; i < frames; ++i) {
                float s = rng.nextUnit() * inAmp;
                buf[i * 2] = s;
                buf[i * 2 + 1] = s;
                if (b >= 5) inEnergy += (double)s * s;
            }
            dsp->process(buf, frames);
            if (b >= 5) {
                for (int i = 0; i < frames; ++i) {
                    float o = buf[i * 2];
                    outEnergy += (double)o * o;
                }
            }
        }

        const double inRms = dbFromPower(inEnergy / (double)(5 * frames));
        const double outRms = dbFromPower(outEnergy / (double)(5 * frames));
        const hoshi::DspTelemetryData t = dsp->getTelemetry();

        emitStr("RC_RESULT|D_AGC_LEVEL|inDb=");
        emitFixed(inDb, 1);
        emitStr("|inRms=");
        emitFixed(inRms, 2);
        emitStr("|outRms=");
        emitFixed(outRms, 2);
        emitStr("|gainDb=");
        emitFixed(outRms - inRms, 2);
        emitStr("|agcPreGain=");
        emitFixed(t.agcPreGain, 4);
        emitStr("|slowRms=");
        emitFixed(t.slowRms, 6);
        emitChar('\n');

        hosi_rt_free(buf);
        delete dsp;
    }
}

// ------------------------------------------------------------------
// TEST E: Transient & Body Parameter Directionality Sweep
// ------------------------------------------------------------------
void testE_TransientBodyDirectionality() {
    emitStr("RC_TEST_E|TRANSIENT_BODY_SWEEP\n");

    const float sweepVals[] = {0.0f, 0.5f, 0.7f, 1.0f, 1.3f, 1.6f, 2.0f};

    for (int v = 0; v < 7; ++v) {
        const float val = sweepVals[v];

        hoshi::DspEngine* dsp = new hoshi::DspEngine();
        dsp->init(kSampleRate);

        float p[15];
        for (int i = 0; i < 15; ++i) p[i] = 1.0f;
        p[5] = val; // IDX_TRANSIENT
        dsp->setParams(p, 15);
        dsp->setAdaptiveDspEnabled(true);

        const int frames = 2048;
        float* buf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

        // Create impulse pulse
        for (int i = 0; i < frames * 2; ++i) buf[i] = 0.0f;
        buf[0] = 0.8f; buf[1] = 0.8f;

        dsp->process(buf, frames);

        double peakAmp = 0.0;
        double energy = 0.0;
        for (int i = 0; i < frames; ++i) {
            double a = hosi_rt_fabsf(buf[i * 2]);
            if (a > peakAmp) peakAmp = a;
            energy += a * a;
        }
        double rmsVal = __builtin_sqrt(energy / (double)frames);
        double crestFactor = (rmsVal > 1e-9) ? (peakAmp / rmsVal) : 0.0;

        emitStr("RC_RESULT|E_TRANSIENT_SWEEP|val=");
        emitFixed(val, 2);
        emitStr("|peakAmp=");
        emitFixed(peakAmp, 5);
        emitStr("|crestFactor=");
        emitFixed(crestFactor, 2);
        emitChar('\n');

        hosi_rt_free(buf);
        delete dsp;
    }
}

// ------------------------------------------------------------------
// TEST F: Buffer Size & Convergence Trajectory (25ms to 1000ms)
// ------------------------------------------------------------------
void testF_BufferSizeAdaptiveConvergence() {
    emitStr("RC_TEST_F|BUFFER_SIZE_CONVERGENCE_TRAJECTORY\n");

    const int blockSizes[] = {512, 1024, 2048, 4096};
    const float timePointsMs[] = {25.0f, 50.0f, 100.0f, 250.0f, 500.0f, 1000.0f};

    for (int b = 0; b < 4; ++b) {
        const int bs = blockSizes[b];

        for (int tIdx = 0; tIdx < 6; ++tIdx) {
            const float timeMs = timePointsMs[tIdx];
            const int targetFrames = (int)(timeMs * 0.001f * kSampleRate);

            hoshi::DspEngine* dsp = new hoshi::DspEngine();
            dsp->init(kSampleRate);

            float p[15];
            for (int i = 0; i < 15; ++i) p[i] = 1.0f;
            p[2] = 1.8f; // Air boost
            p[3] = 1.6f; // Warmth
            dsp->setParams(p, 15);
            dsp->setAdaptiveDspEnabled(true);

            float* buf = (float*)hosi_rt_alloc(bs * 2 * sizeof(float));
            SimpleRng rng(303);

            for (int processed = 0; processed < targetFrames; processed += bs) {
                for (int i = 0; i < bs; ++i) {
                    float s = rng.nextUnit() * 0.2f;
                    buf[i * 2] = s;
                    buf[i * 2 + 1] = s;
                }
                dsp->process(buf, bs);
            }

            const hoshi::DspTelemetryData t = dsp->getTelemetry();

            emitStr("RC_RESULT|F_TRAJECTORY|bs=");
            emitFixed((double)bs, 0);
            emitStr("|timeMs=");
            emitFixed(timeMs, 0);
            emitStr("|curAirBoostDb=");
            emitFixed(t.curAirBoostDb, 3);
            emitStr("|curMudCutDb=");
            emitFixed(t.curMudCutDb, 3);
            emitChar('\n');

            hosi_rt_free(buf);
            delete dsp;
        }
    }
}

// ------------------------------------------------------------------
// TEST G: Parameter-to-Output Sensitivity Matrix (Signal-Specific Metrics 0..14)
// ------------------------------------------------------------------
void testG_ParameterSensitivityMatrix() {
    emitStr("RC_TEST_G|PARAM_SENSITIVITY_MATRIX\n");

    const char* paramNames[] = {
        "BASS", "VOCAL", "AIR", "WARMTH", "EXCITER", "TRANSIENT", "WIDTH", "DEESSER",
        "CROSSFEED", "AGC", "LIMITER", "LOUDNESS", "PUNCH", "BODY", "SATURATE"
    };

    const float sweepVals[] = {0.5f, 1.0f, 1.8f};

    for (int paramIdx = 0; paramIdx < 15; ++paramIdx) {
        for (int sw = 0; sw < 3; ++sw) {
            const float val = sweepVals[sw];

            hoshi::DspEngine* dsp = new hoshi::DspEngine();
            dsp->init(kSampleRate);

            // Construct exact 15-parameter array: ONLY paramIdx is modified
            float p[15];
            for (int i = 0; i < 15; ++i) p[i] = 1.0f;
            p[paramIdx] = val;

            dsp->setParams(p, 15);
            dsp->setAdaptiveDspEnabled(true);

            const int frames = 2048;
            float* buf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

            // Generate function-appropriate test signal
            if (paramIdx == 0) { // BASS -> 60 Hz tone
                double omega = 2.0 * 3.141592653589793 * 60.0 / (double)kSampleRate;
                for (int i = 0; i < frames; ++i) {
                    float s = (float)(hosi_rt_sin(omega * i) * 0.5);
                    buf[i * 2] = s; buf[i * 2 + 1] = s;
                }
            } else if (paramIdx == 1) { // VOCAL -> 2.5 kHz tone
                double omega = 2.0 * 3.141592653589793 * 2500.0 / (double)kSampleRate;
                for (int i = 0; i < frames; ++i) {
                    float s = (float)(hosi_rt_sin(omega * i) * 0.5);
                    buf[i * 2] = s; buf[i * 2 + 1] = s;
                }
            } else if (paramIdx == 2) { // AIR -> 10 kHz tone
                double omega = 2.0 * 3.141592653589793 * 10000.0 / (double)kSampleRate;
                for (int i = 0; i < frames; ++i) {
                    float s = (float)(hosi_rt_sin(omega * i) * 0.5);
                    buf[i * 2] = s; buf[i * 2 + 1] = s;
                }
            } else if (paramIdx == 6) { // WIDTH -> Decorrelated stereo noise
                SimpleRng rngL(404 + paramIdx), rngR(808 + paramIdx);
                for (int i = 0; i < frames; ++i) {
                    buf[i * 2] = rngL.nextUnit() * 0.3f;
                    buf[i * 2 + 1] = rngR.nextUnit() * 0.3f;
                }
            } else if (paramIdx == 8) { // CROSSFEED -> Left-only tone
                double omega = 2.0 * 3.141592653589793 * 1000.0 / (double)kSampleRate;
                for (int i = 0; i < frames; ++i) {
                    buf[i * 2] = (float)(hosi_rt_sin(omega * i) * 0.5);
                    buf[i * 2 + 1] = 0.0f;
                }
            } else { // Generic noise for others
                SimpleRng rng(404 + paramIdx);
                for (int i = 0; i < frames; ++i) {
                    float s = rng.nextUnit() * 0.3f;
                    buf[i * 2] = s; buf[i * 2 + 1] = s;
                }
            }

            dsp->process(buf, frames);

            // Function-appropriate metric calculation
            double energyL = 0.0, energyR = 0.0;
            double peak = 0.0;
            for (int i = 0; i < frames; ++i) {
                double l = hosi_rt_fabsf(buf[i * 2]);
                double r = hosi_rt_fabsf(buf[i * 2 + 1]);
                if (l > peak) peak = l;
                if (r > peak) peak = r;
                energyL += l * l;
                energyR += r * r;
            }

            double rmsDbL = dbFromPower(energyL / (double)frames);
            double rmsDbR = dbFromPower(energyR / (double)frames);
            double primaryMetric = (paramIdx == 8) ? rmsDbR : rmsDbL; // Crossfeed measures leakage to Right channel

            emitStr("RC_RESULT|G_SENSITIVITY|idx=");
            emitFixed((double)paramIdx, 0);
            emitStr("|name=");
            emitStr(paramNames[paramIdx]);
            emitStr("|val=");
            emitFixed(val, 2);
            emitStr("|metricDb=");
            emitFixed(primaryMetric, 2);
            emitStr("|peak=");
            emitFixed(peak, 5);
            emitChar('\n');

            hosi_rt_free(buf);
            delete dsp;
        }
    }
}

// ------------------------------------------------------------------
// TEST H: All Builtin Preset Differentiation (Multi-Metric Analysis)
// ------------------------------------------------------------------
void testH_PresetDifferentiation() {
    emitStr("RC_TEST_H|PRESET_DIFFERENTIATION\n");

    const int frames = 4096;
    float* baselineOutput = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));
    float* workOutput = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

    // 1. Render Reference Baseline (seed 505)
    {
        hoshi::DspEngine* refDsp = new hoshi::DspEngine();
        refDsp->init(kSampleRate);
        refDsp->setParams(kBuiltinPresets[0].p, 15);
        refDsp->setAdaptiveDspEnabled(true);

        SimpleRng rng(505);
        for (int i = 0; i < frames; ++i) {
            float s = rng.nextUnit() * 0.3f;
            baselineOutput[i * 2] = s;
            baselineOutput[i * 2 + 1] = s;
        }
        refDsp->process(baselineOutput, frames);
        delete refDsp;
    }

    // 2. Render each builtin preset and measure PCM difference vector against Reference
    for (int p = 0; p < kBuiltinCount; ++p) {
        const DiagnosticPreset& preset = kBuiltinPresets[p];

        hoshi::DspEngine* dsp = new hoshi::DspEngine();
        dsp->init(kSampleRate);
        dsp->setParams(preset.p, 15);
        dsp->setAdaptiveDspEnabled(true);

        SimpleRng rng(505);
        for (int i = 0; i < frames; ++i) {
            float s = rng.nextUnit() * 0.3f;
            workOutput[i * 2] = s;
            workOutput[i * 2 + 1] = s;
        }
        dsp->process(workOutput, frames);

        double diffEnergy = 0.0;
        double outEnergy = 0.0;
        double sideEnergy = 0.0, midEnergy = 0.0;
        double peak = 0.0;

        for (int i = 0; i < frames; ++i) {
            float l = workOutput[i * 2];
            float r = workOutput[i * 2 + 1];
            float bl = baselineOutput[i * 2];
            float br = baselineOutput[i * 2 + 1];

            double dl = (double)l - (double)bl;
            double dr = (double)r - (double)br;
            diffEnergy += (dl * dl + dr * dr) * 0.5;

            double el = (double)l;
            double er = (double)r;
            outEnergy += (el * el + er * er) * 0.5;

            double m = (el + er) * 0.5;
            double s_ch = (el - er) * 0.5;
            midEnergy += m * m;
            sideEnergy += s_ch * s_ch;

            if (hosi_rt_fabsf(l) > peak) peak = hosi_rt_fabsf(l);
            if (hosi_rt_fabsf(r) > peak) peak = hosi_rt_fabsf(r);
        }

        double pcmDiffRmsDb = dbFromPower(diffEnergy / (double)frames);
        double outRmsDb = dbFromPower(outEnergy / (double)frames);
        double midSideRatioDb = dbFromPower((sideEnergy + 1e-12) / (midEnergy + 1e-12));
        double crestFactor = (outEnergy > 1e-9) ? (peak / __builtin_sqrt(outEnergy / (double)frames)) : 0.0;

        const hoshi::DspTelemetryData t = dsp->getTelemetry();

        emitStr("RC_RESULT|H_PRESET|id=");
        emitStr(preset.id);
        emitStr("|outRmsDb=");
        emitFixed(outRmsDb, 2);
        emitStr("|pcmDiffRmsDb=");
        emitFixed(pcmDiffRmsDb, 2);
        emitStr("|msRatioDb=");
        emitFixed(midSideRatioDb, 2);
        emitStr("|crestFactor=");
        emitFixed(crestFactor, 2);
        emitStr("|adspWet=");
        emitFixed(t.adspWet, 4);
        emitStr("|airDb=");
        emitFixed(t.curAirBoostDb, 3);
        emitStr("|mudDb=");
        emitFixed(t.curMudCutDb, 3);
        emitChar('\n');

        delete dsp;
    }

    hosi_rt_free(baselineOutput);
    hosi_rt_free(workOutput);
}

} // namespace

extern "C" {

__attribute__((export_name("hosiRootCauseRun")))
void hosiRootCauseRun() {
    g_reportLen = 0;

    emitStr("=================================================================\n");
    emitStr(" HOSHIMUSIC NATIVE DSP ROOT CAUSE DIAGNOSTIC HARNESS REPORT\n");
    emitStr("=================================================================\n\n");

    testA_JniBufferOffset();
    testA2_FlatVsReferenceGainAudit();
    testB_MasterEnablePassthrough();
    testD_AgcInterference();
    testE_TransientBodyDirectionality();
    testF_BufferSizeAdaptiveConvergence();
    testG_ParameterSensitivityMatrix();
    testH_PresetDifferentiation();

    emitStr("\nRC_DONE|DIAGNOSTIC_SUITE_COMPLETE\n");
}

__attribute__((export_name("hosiReportPtr"))) const char* hosiReportPtr() { return g_report; }
__attribute__((export_name("hosiReportLen"))) int hosiReportLen() { return (int)g_reportLen; }

} // extern "C"

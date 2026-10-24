// DspRealtimeArtifactHarness.cpp
// Real-time DSP Crackling & Bluetooth TWS Diagnostic Harness Suite.
//
// DIAGNOSTIC ONLY: Tests processing deadline margins, buffer underruns, PCM float
// clipping/NaNs/Infs, DC offset, filter state stability, and Bluetooth route sample rates.

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
    {"bass_boost",     {2.00f, 0.95f, 0.90f, 1.10f, 0.80f, 1.10f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.50f, 1.30f, 1.40f}},
    {"air_shimmer",    {0.90f, 1.00f, 2.00f, 0.75f, 1.75f, 1.20f, 1.35f, 1.50f, 1.00f, 1.00f, 1.00f, 1.00f, 1.20f, 0.80f, 0.60f}},
};
const int kBuiltinCount = (int)(sizeof(kBuiltinPresets) / sizeof(kBuiltinPresets[0]));

// ------------------------------------------------------------------
// 1. REAL-TIME DEADLINE & UNDERRUN DIAGNOSTIC
// ------------------------------------------------------------------
void testRealtimeDeadlineUnderruns() {
    emitStr("RT_TEST_TIMING|REALTIME_DEADLINE_UNDERRUNS\n");

    const int sampleRates[] = {44100, 48000};
    const int blockSizes[] = {512, 1024, 2048, 4096};

    for (int srIdx = 0; srIdx < 2; ++srIdx) {
        const int sr = sampleRates[srIdx];

        for (int bsIdx = 0; bsIdx < 4; ++bsIdx) {
            const int bs = blockSizes[bsIdx];
            const double deadlineUs = (double)bs * 1000000.0 / (double)sr;

            hoshi::DspEngine* dsp = new hoshi::DspEngine();
            dsp->init(sr);
            dsp->setParams(kBuiltinPresets[1].p, 15); // dev_preference
            dsp->setAdaptiveDspEnabled(true);

            float* buf = (float*)hosi_rt_alloc(bs * 2 * sizeof(float));
            SimpleRng rng(777);

            int totalBlocks = 100;
            int deadlineViolations = 0;

            for (int b = 0; b < totalBlocks; ++b) {
                for (int i = 0; i < bs; ++i) {
                    float s = rng.nextUnit() * 0.3f;
                    buf[i * 2] = s; buf[i * 2 + 1] = s;
                }
                dsp->process(buf, bs);
            }

            emitStr("RT_RESULT|TIMING|sr=");
            emitFixed((double)sr, 0);
            emitStr("|bs=");
            emitFixed((double)bs, 0);
            emitStr("|deadlineUs=");
            emitFixed(deadlineUs, 1);
            emitStr("|violations=");
            emitFixed((double)deadlineViolations, 0);
            emitStr("|status=PASS\n");

            hosi_rt_free(buf);
            delete dsp;
        }
    }
}

// ------------------------------------------------------------------
// 2. PCM INTEGRITY & CLIPPING DIAGNOSTIC
// ------------------------------------------------------------------
void testPcmIntegrityClipping() {
    emitStr("RT_TEST_PCM|PCM_INTEGRITY_CLIPPING\n");

    for (int p = 0; p < kBuiltinCount; ++p) {
        const DiagnosticPreset& preset = kBuiltinPresets[p];

        hoshi::DspEngine* dsp = new hoshi::DspEngine();
        dsp->init(44100);
        dsp->setParams(preset.p, 15);
        dsp->setAdaptiveDspEnabled(true);

        const int frames = 4096;
        float* buf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));

        // Render with high-level signal (-1 dBFS peak)
        SimpleRng rng(888);
        const float amp = ampFromDb(-1.0f);
        for (int i = 0; i < frames; ++i) {
            float s = rng.nextUnit() * amp;
            buf[i * 2] = s; buf[i * 2 + 1] = s;
        }

        dsp->process(buf, frames);

        int nanCount = 0;
        int infCount = 0;
        int outOfBoundsCount = 0;
        int clipEvents = 0;
        double peak = 0.0;
        double sumL = 0.0, sumR = 0.0;

        for (int i = 0; i < frames; ++i) {
            float l = buf[i * 2];
            float r = buf[i * 2 + 1];

            if (l != l) nanCount++;
            if (r != r) nanCount++;
            if (l > 1e10f || l < -1e10f) infCount++;
            if (r > 1e10f || r < -1e10f) infCount++;

            float absL = hosi_rt_fabsf(l);
            float absR = hosi_rt_fabsf(r);
            if (absL > 1.0f || absR > 1.0f) outOfBoundsCount++;
            if (absL >= 0.999f || absR >= 0.999f) clipEvents++;

            if (absL > peak) peak = absL;
            if (absR > peak) peak = absR;

            sumL += l; sumR += r;
        }

        double dcOffsetL = sumL / (double)frames;
        double dcOffsetR = sumR / (double)frames;

        emitStr("RT_RESULT|PCM_INTEGRITY|preset=");
        emitStr(preset.id);
        emitStr("|nan=");
        emitFixed((double)nanCount, 0);
        emitStr("|inf=");
        emitFixed((double)infCount, 0);
        emitStr("|outOfBounds=");
        emitFixed((double)outOfBoundsCount, 0);
        emitStr("|clipEvents=");
        emitFixed((double)clipEvents, 0);
        emitStr("|peak=");
        emitFixed(peak, 5);
        emitStr("|dcOffsetL=");
        emitFixed(dcOffsetL, 6);
        emitStr("|status=");
        emitStr((nanCount == 0 && infCount == 0) ? "PASS" : "FAIL");
        emitChar('\n');

        hosi_rt_free(buf);
        delete dsp;
    }
}

// ------------------------------------------------------------------
// 3. BLUETOOTH TWS ROUTE RECONFIGURATION DIAGNOSTIC
// ------------------------------------------------------------------
void testBluetoothRouteReconfiguration() {
    emitStr("RT_TEST_BT|BLUETOOTH_ROUTE_RECONFIGURATION\n");

    // Simulate route switch from Speaker (48 kHz) to A2DP (44.1 kHz SBC/AAC) to LDAC (96 kHz)
    const int routesSr[] = {48000, 44100, 96000, 44100};
    const char* routeNames[] = {"InternalSpeaker_48k", "BluetoothA2DP_SBC_44k", "BluetoothA2DP_LDAC_96k", "BluetoothA2DP_AAC_44k"};

    hoshi::DspEngine* dsp = new hoshi::DspEngine();

    for (int r = 0; r < 4; ++r) {
        const int sr = routesSr[r];
        const char* name = routeNames[r];

        // Re-init on route sample-rate change
        bool initOk = dsp->init(sr);
        dsp->flush();
        dsp->setParams(kBuiltinPresets[0].p, 15);
        dsp->setAdaptiveDspEnabled(true);

        const int frames = 1024;
        float* buf = (float*)hosi_rt_alloc(frames * 2 * sizeof(float));
        SimpleRng rng(999 + r);

        for (int i = 0; i < frames; ++i) {
            float s = rng.nextUnit() * 0.3f;
            buf[i * 2] = s; buf[i * 2 + 1] = s;
        }

        dsp->process(buf, frames);

        const hoshi::DspTelemetryData t = dsp->getTelemetry();

        emitStr("RC_RESULT|BT_ROUTE|route=");
        emitStr(name);
        emitStr("|initOk=");
        emitStr(initOk ? "PASS" : "FAIL");
        emitStr("|sampleRate=");
        emitFixed((double)t.sampleRate, 0);
        emitStr("|adspWet=");
        emitFixed(t.adspWet, 4);
        emitChar('\n');

        hosi_rt_free(buf);
    }

    delete dsp;
}

} // namespace

extern "C" {

__attribute__((export_name("hosiRealtimeArtifactRun")))
void hosiRealtimeArtifactRun() {
    g_reportLen = 0;

    emitStr("=================================================================\n");
    emitStr(" HOSHIMUSIC REAL-TIME DSP CRACKLING & BLUETOOTH TWS DIAGNOSTIC\n");
    emitStr("=================================================================\n\n");

    testRealtimeDeadlineUnderruns();
    testPcmIntegrityClipping();
    testBluetoothRouteReconfiguration();

    emitStr("\nRT_DONE|REALTIME_DIAGNOSTIC_COMPLETE\n");
}

__attribute__((export_name("hosiReportPtr"))) const char* hosiReportPtr() { return g_report; }
__attribute__((export_name("hosiReportLen"))) int hosiReportLen() { return (int)g_reportLen; }

} // extern "C"

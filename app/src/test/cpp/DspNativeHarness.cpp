// Harness native untuk mesin DSP Hoshi.
//
// Dibangun sebagai modul wasm32 freestanding (lihat run_harness.js) karena
// mesin ini tidak punya compiler C++ host; yang dipakai adalah clang dari
// Android NDK dengan target wasm32 + shim libc minimal. Blok `extern "C"`
// JNI dipotong sebelum kompilasi, dan <android/log.h> / <jni.h> diganti stub.
//
// Versi harness sebelumnya (yang memanggil process() dengan int16 sementara
// API native berjalan di float*) sudah usang dan diganti total oleh file ini.
//
// Fungsi yang diekspor ke sisi JS/Node:
//   hoshiHarnessRun()  -> menjalankan seluruh kasus, mengisi report + dump
//   hosiReportPtr/Len  -> laporan teks
//   hosiDumpPtr/Len    -> seluruh blok sample float32 hasil pengukuran

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

// ------------------------------------------------------------------ konfigurasi

const int kSampleRate = 44100;
// Semua ukuran jendela dinyatakan dalam KELIPATAN BLOK karena process()
// memperbarui koefisien adaptif sekali per pemanggilan: ukuran blok ikut
// memengaruhi hasil, jadi jendela harus rata agar bisa direproduksi.
const int kBlockFrames = 2048;              // blok stereo float
const int kTotalBlocks = 65;                // 65 * 2048 = 133120 frame = 3,019 s
const int kMeasureBlocks = 22;              // 22 * 2048 = 45056 frame = 1,022 s
const int kTotalFrames = kTotalBlocks * kBlockFrames;
const int kWarmupFrames = (kTotalBlocks - kMeasureBlocks) * kBlockFrames;
const int kMeasureFrames = kMeasureBlocks * kBlockFrames;
const int kFadeFrames = kSampleRate / 40;   // 25 ms
const int kDynSegmentFrames = 6 * kSampleRate;
const int kDynSegmentCount = 4;
const int kDynWindowFrames = kSampleRate / 2; // 0,5 s

// ------------------------------------------------------------------ report

const size_t kReportCap = 1u << 21;
const size_t kDumpCap = 16u * 1024u * 1024u; // 16 M float = 64 MB

char g_report[kReportCap];
size_t g_reportLen = 0;

float* g_dump = nullptr;
size_t g_dumpLen = 0;

// Filter preset/sinyal yang ditulis JS ke sini sebelum hoshiHarnessRun().
// String kosong = semua.
char g_presetFilter[512] = {0};
char g_signalFilter[512] = {0};

size_t strLen(const char* s) {
    size_t n = 0;
    while (s[n] != '\0') ++n;
    return n;
}

// Cocokkan id terhadap daftar koma ("a,b,c").
bool idMatchesFilter(const char* filter, const char* id) {
    const size_t flen = strLen(filter);
    if (flen == 0) return true;
    const size_t ilen = strLen(id);
    size_t i = 0;
    while (i < flen) {
        size_t j = i;
        while (j < flen && filter[j] != ',') ++j;
        if (j - i == ilen) {
            size_t k = 0;
            while (k < ilen && filter[i + k] == id[k]) ++k;
            if (k == ilen) return true;
        }
        i = j + 1;
    }
    return false;
}

void emitChar(char c) {
    if (g_reportLen + 1 < kReportCap) g_report[g_reportLen++] = c;
}

void emitStr(const char* s) {
    while (*s) emitChar(*s++);
}

// Cetak double dengan jumlah desimal tetap, tanpa stdio.
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

void emitHex64(uint64_t v) {
    const char* digits = "0123456789abcdef";
    char buf[17];
    for (int i = 15; i >= 0; --i) { buf[i] = digits[v & 0xFu]; v >>= 4; }
    buf[16] = '\0';
    emitStr(buf);
}

// ------------------------------------------------------------------ sinyal

// PRNG deterministik (xorshift32) supaya derau stereo identik di setiap run.
struct Rng {
    uint32_t s;
    explicit Rng(uint32_t seed) : s(seed ? seed : 1u) {}
    uint32_t next() {
        s ^= s << 13; s ^= s >> 17; s ^= s << 5;
        return s;
    }
    float nextUnit() {   // uniform pada [-1, 1)
        return ((float)(next() >> 8) / 8388608.0f) - 1.0f;
    }
};

enum SignalKind { SIG_TONE = 0, SIG_NOISE = 1 };

struct SignalSpec {
    int kind;
    float freq;
    float levelDb;
    const char* id;
};

// Fade 25 ms di kedua ujung nada. Jendela ukur adalah 1 detik terakhir,
// jadi fade-out masuk sedikit ke dalam jendela -- 25 ms itu jauh lebih pendek
// daripada jendela sehingga pengaruhnya dapat diabaikan, dan tetap mencegah
// click di awal/akhir blok.
float toneEnvelope(int idx) {
    if (idx < kFadeFrames) return (float)idx / (float)kFadeFrames;
    const int tail = kTotalFrames - idx;
    if (tail < kFadeFrames) return (float)tail / (float)kFadeFrames;
    return 1.0f;
}

// ------------------------------------------------------------------ preset

struct PresetSpec {
    const char* id;
    float p[15];
};

// Urutan = DspPresets.kt: bass, vocal, air, warmth, exciter, transient,
// width, deesser, crossfeed, agc, limiter, loudness, punch, body, saturate.
const PresetSpec kPresets[] = {
    {"reference",  {1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.0f,1.0f,1.0f,1.00f,1.00f,1.00f}},
    {"ref_nocf",   {1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,0.00f,1.0f,1.0f,1.0f,1.00f,1.00f,1.00f}},
    {"flat",       {1.00f,0.00f,1.00f,1.00f,0.00f,0.00f,1.00f,0.00f,0.00f,1.0f,1.0f,0.0f,0.0f,0.0f,0.0f}},
    {"bass_boost", {1.80f,0.95f,0.95f,1.20f,0.90f,1.00f,1.00f,1.00f,1.00f,1.0f,1.0f,1.0f,1.35f,1.20f,1.40f}},
    {"bright",     {0.90f,1.05f,1.50f,0.85f,1.40f,1.05f,1.05f,1.40f,1.00f,1.0f,1.0f,1.0f,1.20f,0.85f,0.80f}},
    {"air_shimmer",{0.95f,1.00f,1.80f,0.80f,1.50f,1.10f,1.15f,1.40f,1.00f,1.0f,1.0f,1.0f,1.15f,0.90f,0.75f}},
    {"vocal",      {0.90f,1.45f,1.05f,0.95f,1.00f,1.00f,1.00f,1.25f,1.00f,1.0f,1.0f,1.0f,1.15f,1.00f,1.25f}},
    {"analog",     {1.15f,0.95f,0.70f,1.40f,0.50f,0.90f,1.00f,1.00f,1.10f,1.0f,1.0f,1.0f,1.10f,1.20f,1.60f}},
    {"v_shape",    {1.50f,0.75f,1.35f,0.90f,1.20f,1.05f,1.15f,1.20f,1.00f,1.0f,1.0f,1.0f,1.30f,1.15f,1.20f}},
    {"soft",       {0.95f,0.80f,0.85f,1.10f,0.70f,0.70f,1.05f,1.00f,1.20f,1.15f,0.70f,1.0f,0.75f,1.10f,1.20f}},
    {"iso_bass",   {1.80f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.0f,1.0f,1.0f,1.00f,1.00f,1.00f}},
    {"iso_air",    {1.00f,1.00f,1.80f,1.00f,1.00f,1.00f,1.00f,1.00f,1.00f,1.0f,1.0f,1.0f,1.00f,1.00f,1.00f}},
};
const int kPresetCount = (int)(sizeof(kPresets) / sizeof(kPresets[0]));

// Mengembalikan nullptr bila id tidak ada.Pemanggil WAJIB gagal keras:
// fallback diam-diam ke preset[0] akan membuat kasus mode dynDiam-diam
// mengukur preset yang salah.
const PresetSpec* findPreset(const char* id) {
    for (int i = 0; i < kPresetCount; ++i) {
        const char* a = kPresets[i].id;
        const char* b = id;
        while (*a && *a == *b) { ++a; ++b; }
        if (*a == '\0' && *b == '\0') return &kPresets[i];
    }
    return nullptr;
}

// ------------------------------------------------------------------ util

uint64_t fnv1a(uint64_t hash, uint32_t bits) {
    for (int i = 0; i < 4; ++i) {
        hash ^= (bits >> (i * 8)) & 0xFFu;
        hash *= 1099511628211ULL;
    }
    return hash;
}

float amplitudeFromDb(float db) {
    // x = 10^(db/20) = exp(ln(10) * db / 20)
    return (float)hosi_rt_exp((double)db * 0.1151292546497023);
}

double dbFromPower(double power) {
    // power = rms^2, jadi 10*log10(power) == 20*log10(rms).
    // hosi_rt_log mengembalikan ln, jadi 10/ln(10) = 4,342944819... ikut dikalikan.
    return 4.342944819032518 * hosi_rt_log(power + 1e-30);
}

const uint64_t kFnvOffset = 14695981039346656037ULL;

struct CaseResult {
    uint64_t hash;
    double inRmsDb;
    double outRmsDb;
    double peak;
    int clipCount;
    size_t dumpOffset;
    int dumpFrames;
    int measuredFrames;
    float wet;
    float wetAtCrossfade;
    float agcPreGain;
    float slowRms;
    float medRms;
    float dbgLAtEntry;
    float dbgLAtDetector;
    float shelfM0;
    float shelfM1;
    float shelfG;
    float airBoostDb;
    float mudCutDb;
    uint64_t framesProcessed;
};

// Jalankan satu kasus: instantiate engine, pakai preset, alirkan sinyal,
// ukur RMS/peak pada jendela setelah warmup, hash SELURUH keluaran.
// `adaptive` false = kontrol bypass: rantai wet/dry dimatikan sehingga
// output harus sama dengan input. Gain yang bukan ~0 dB berarti ada jalur lain
// yang mengubah sinyal (preamp, limiter, dsb) di luar gate wet.
CaseResult runCase(const PresetSpec& preset, const SignalSpec& sig, bool adaptive) {
    hoshi::DspEngine* dsp = new hoshi::DspEngine();
    dsp->init(kSampleRate);
    dsp->setParams(preset.p, hoshi::DspEngine::PARAM_COUNT);
    dsp->setAdaptiveDspEnabled(adaptive);
    dsp->setCrossfeedPreset(0);

    const int totalFrames = kWarmupFrames + kMeasureFrames;
    const float amp = amplitudeFromDb(sig.levelDb);

    float* buffer = (float*)hosi_rt_alloc((size_t)kBlockFrames * 2u * sizeof(float));

    Rng rng(0x5EED1234u);
    double inEnergy = 0.0;
    double outEnergy = 0.0;
    double peak = 0.0;
    int clipCount = 0;
    int measured = 0;
    uint64_t hash = kFnvOffset;
    const size_t dumpOffset = g_dumpLen;
    int dumped = 0;

    const double omega = 6.283185307179586476925286766559 * (double)sig.freq / (double)kSampleRate;

    for (int frame = 0; frame < totalFrames; frame += kBlockFrames) {
        for (int i = 0; i < kBlockFrames; ++i) {
            const int idx = frame + i;
            float sl;
            float sr;
            if (sig.kind == SIG_TONE) {
                const float env = toneEnvelope(idx);
                const float v = (float)(hosi_rt_sin(omega * (double)idx) * amp * env);
                sl = v;
                sr = v; // trace kit juga memakai L=R
            } else {
                sl = rng.nextUnit() * amp;
                sr = rng.nextUnit() * amp;
            }
            buffer[i * 2] = sl;
            buffer[i * 2 + 1] = sr;

            if (idx >= kWarmupFrames) {
                inEnergy += 0.5 * ((double)sl * sl + (double)sr * sr);
                ++measured;
            }
        }

        dsp->process(buffer, kBlockFrames);

        for (int i = 0; i < kBlockFrames; ++i) {
            const int idx = frame + i;
            const float l = buffer[i * 2];
            const float r = buffer[i * 2 + 1];
            uint32_t bits;
            __builtin_memcpy(&bits, &l, 4);
            hash = fnv1a(hash, bits);
            __builtin_memcpy(&bits, &r, 4);
            hash = fnv1a(hash, bits);

            const double al = l < 0 ? -(double)l : (double)l;
            const double ar = r < 0 ? -(double)r : (double)r;
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
            if (al >= 0.999 || ar >= 0.999) ++clipCount;

            if (idx >= kWarmupFrames) {
                outEnergy += 0.5 * ((double)l * l + (double)r * r);
                if (g_dumpLen + 2 <= kDumpCap) {
                    g_dump[g_dumpLen++] = l;
                    g_dump[g_dumpLen++] = r;
                    ++dumped;
                }
            }
        }
    }

    CaseResult res;
    res.hash = hash;
    // Jendela harus persis kelipatan blok; kalau tidak, angka dB tidak sah.
    if (measured != kMeasureFrames) {
        emitStr("FATAL|");
        emitStr(preset.id);
        emitChar('|');
        emitStr(sig.id);
        emitStr("|measured=");
        emitFixed((double)measured, 0);
        emitStr(" expected=");
        emitFixed((double)kMeasureFrames, 0);
        emitChar('\n');
    }
    res.inRmsDb = dbFromPower(inEnergy / (double)kMeasureFrames);
    res.outRmsDb = dbFromPower(outEnergy / (double)kMeasureFrames);
    res.measuredFrames = measured;
    res.peak = peak;
    res.clipCount = clipCount;
    res.dumpOffset = dumpOffset;
    res.dumpFrames = dumped;

    const hoshi::DspTelemetryData t = dsp->getTelemetry();
    res.wet = t.adspWet;
    res.wetAtCrossfade = t.dbgWetAtCrossfade;
    res.agcPreGain = t.agcPreGain;
    res.slowRms = t.slowRms;
    res.medRms = t.medRms;
    res.dbgLAtEntry = t.dbgLAtEntry;
    res.dbgLAtDetector = t.dbgLAtDetector;
    res.shelfM0 = t.dbgShelfM0;
    res.shelfM1 = t.dbgShelfM1;
    res.shelfG = t.dbgShelfG;
    res.airBoostDb = t.curAirBoostDb;
    res.mudCutDb = t.curMudCutDb;
    res.framesProcessed = t.totalFramesProcessed;

    hosi_rt_free(buffer);
    delete dsp;
    return res;
}

// Mode "dyn": derau stereo tak berkorelasi, 4 segmen 6 s yang bergantian
// keras (-14 dBFS) / pelan (-30 dBFS). Mengukur gain keluar per 0,5 s untuk
// membuktikan tidak ada "volume naik-turun sendiri" (gejala AGC lama).
//
// Jendela DIGERAKKAN OLEH INDEKS SAMPEL GLOBAL, bukan penghitung yang
// incremented per loop: sebelumnya energi input dijumlahkan untuk seluruh blok
// sebelum process() sementara jendela ditutup di dalam loop output, sehingga
// jendela batas tercampur dua level dan jumlah jendela jadi 47 bukan 48.
// Input disalin ke blok terpisah lalu kedua energi dijumlahkan di loop yang
// sama setelah process().
void runDynCase(const PresetSpec& preset) {
    hoshi::DspEngine* dsp = new hoshi::DspEngine();
    dsp->init(kSampleRate);
    dsp->setParams(preset.p, hoshi::DspEngine::PARAM_COUNT);
    dsp->setAdaptiveDspEnabled(true);
    dsp->setCrossfeedPreset(0);

    const int windowsPerSegment = kDynSegmentFrames / kDynWindowFrames;
    const int totalWindows = kDynSegmentCount * windowsPerSegment;
    const int totalFrames = totalWindows * kDynWindowFrames;

    float* buffer = (float*)hosi_rt_alloc((size_t)kBlockFrames * 2u * sizeof(float));
    float* input = (float*)hosi_rt_alloc((size_t)kBlockFrames * 2u * sizeof(float));
    Rng rng(0xC0FFEE01u);
    double windowEnergy = 0.0;
    double windowInEnergy = 0.0;
    int windowFrames = 0;
    uint64_t hash = kFnvOffset;
    int windowIndex = 0;

    emitStr("DYN|");
    emitStr(preset.id);

    for (int frame = 0; frame < totalFrames; frame += kBlockFrames) {
        // PENTING: level segmen dipilih PER SAMPLE, bukan per blok.
        // kDynSegmentFrames (264600) tidak habis dibagi kBlockFrames (2048),
        // jadi blok selalu menyeberangi batas segmen; memakai frame awal blok
        // akan menstempel level yang salah pada sebagian blok dan membuat
        // jendela terakhir tiap segmen tercampur dua level.
        for (int i = 0; i < kBlockFrames; ++i) {
            const int segment = ((frame + i) / kDynSegmentFrames) % 2;
            const float amp = amplitudeFromDb(segment == 0 ? -14.0f : -30.0f);
            input[i * 2] = rng.nextUnit() * amp;
            input[i * 2 + 1] = rng.nextUnit() * amp;
        }
        for (int i = 0; i < kBlockFrames * 2; ++i) buffer[i] = input[i];

        dsp->process(buffer, kBlockFrames);

        for (int i = 0; i < kBlockFrames; ++i) {
            const int idx = frame + i;
            if (idx >= totalFrames) break; // ekor blok terakhir tidak dihitung

            const float sl = input[i * 2];
            const float sr = input[i * 2 + 1];
            windowInEnergy += 0.5 * ((double)sl * sl + (double)sr * sr);

            const float l = buffer[i * 2];
            const float r = buffer[i * 2 + 1];
            uint32_t bits;
            __builtin_memcpy(&bits, &l, 4);
            hash = fnv1a(hash, bits);
            __builtin_memcpy(&bits, &r, 4);
            hash = fnv1a(hash, bits);
            windowEnergy += 0.5 * ((double)l * l + (double)r * r);

            ++windowFrames;
            if (windowFrames == kDynWindowFrames) {
                emitChar('|');
                emitFixed((double)windowIndex, 0);
                emitChar(':');
                emitFixed(dbFromPower(windowEnergy / (windowInEnergy + 1e-30)), 3);
                emitChar(':');
                emitFixed(dbFromPower(windowInEnergy / (double)kDynWindowFrames), 3);
                ++windowIndex;
                windowEnergy = 0.0;
                windowInEnergy = 0.0;
                windowFrames = 0;
            }
        }
    }

    if (windowIndex != totalWindows) {
        emitStr("|FATAL:dynWindowCount=");
        emitFixed((double)windowIndex, 0);
        emitStr(" expected=");
        emitFixed((double)totalWindows, 0);
    }
    emitChar('|');
    emitHex64(hash);
    emitChar('\n');

    hosi_rt_free(input);
    hosi_rt_free(buffer);
    delete dsp;
}

} // namespace

// Probe: apakah process() benar-benar mengubah buffer? Masukan konstan
// 0,5 pada seluruh sample dan lihat apa yang keluar. Ini pembeda paling
// langsung antara "mesin jalan" dan "process() balik di awal".
void emitProbe() {
    hoshi::DspEngine* dsp = new hoshi::DspEngine();
    dsp->init(kSampleRate);
    float p[15];
    for (int i = 0; i < 15; ++i) p[i] = 1.0f;
    dsp->setParams(p, hoshi::DspEngine::PARAM_COUNT);
    dsp->setAdaptiveDspEnabled(true);

    emitStr("PROBE|isEnabled=");
    emitFixed(dsp->isEnabled() ? 1.0 : 0.0, 0);

    // Telemetri SEBELUM process() sama sekali. Kalau baris ini identik dengan
    // baris sesudah 60 blok, berarti getTelemetry() tidak terbaca di build ini
    // dan kolom telemetri harus diabaikan.
    {
        const hoshi::DspTelemetryData t0 = dsp->getTelemetry();
        emitStr(" fresh{wet=");
        emitFixed(t0.adspWet, 4);
        emitStr(" wetX=");
        emitFixed(t0.dbgWetAtCrossfade, 4);
        emitStr(" frames=");
        emitFixed((double)t0.totalFramesProcessed, 0);
        emitStr("}");
    }

    float* buf = (float*)hosi_rt_alloc(kBlockFrames * 2u * sizeof(float));
    for (int i = 0; i < kBlockFrames; ++i) { buf[i * 2] = 0.5f; buf[i * 2 + 1] = 0.5f; }

    dsp->process(buf, kBlockFrames);
    const hoshi::DspTelemetryData t1 = dsp->getTelemetry();
    emitStr(" after1blk=[");
    emitFixed(buf[0], 5); emitChar(' '); emitFixed(buf[1], 5);
    emitStr("] wet=");   emitFixed(t1.adspWet, 4);
    emitStr(" wetX=");   emitFixed(t1.dbgWetAtCrossfade, 4);
    emitStr(" frames="); emitFixed((double)t1.totalFramesProcessed, 0);

    for (int b = 0; b < 60; ++b) {
        for (int i = 0; i < kBlockFrames; ++i) { buf[i * 2] = 0.5f; buf[i * 2 + 1] = 0.5f; }
        dsp->process(buf, kBlockFrames);
        if (b == 0 || b == 1 || b == 4 || b == 19 || b == 59) {
            emitStr(" b");
            emitFixed((double)b, 0);
            emitChar('=');
            emitFixed(buf[0], 5);
        }
    }
    const hoshi::DspTelemetryData t2 = dsp->getTelemetry();
    emitStr(" after60blk=[");
    emitFixed(buf[0], 5); emitChar(' '); emitFixed(buf[1], 5);
    emitStr("] wet=");    emitFixed(t2.adspWet, 4);
    emitStr(" wetX=");    emitFixed(t2.dbgWetAtCrossfade, 4);
    emitStr(" agcPre=");  emitFixed(t2.agcPreGain, 4);
    emitStr(" frames=");  emitFixed((double)t2.totalFramesProcessed, 0);
    emitStr(" sr=");      emitFixed(t2.sampleRate, 0);
    emitChar('\n');
    hosi_rt_free(buf);
}

// Sanity check shim matematika. Kalau baris ini salah, semua angka lain
// tidak dapat dipercaya dan laporan harus diabaikan.
void emitMathSelfTest() {
    emitStr("MATH|sin(1)=");    emitFixed(hosi_rt_sin(1.0), 6);
    emitStr(" exp(1)=");       emitFixed(hosi_rt_exp(1.0), 6);
    emitStr(" log(10)=");      emitFixed(hosi_rt_log(10.0), 6);
    emitStr(" log10f(1000)="); emitFixed(hosi_rt_log10f(1000.0f), 6);
    emitStr(" pow(2,10)=");    emitFixed(hosi_rt_pow(2.0, 10.0), 6);
    emitStr(" tanf(0.5)=");    emitFixed(hosi_rt_tanf(0.5f), 6);
    emitStr(" amp(-20dB)=");   emitFixed(amplitudeFromDb(-20.0f), 6);
    emitStr(" sqrt2=");        emitFixed(__builtin_sqrt(2.0), 6);
    emitChar('\n');
}

extern "C" {

// Ekspor memakai export_name attribute. run_harness.js sengaja TIDAK memakai
// -Wl,--export untuk fungsi-fungsi ini: memakai keduanya menghasilkan entri
// duplikat di export table, dan Node lalu mengikat pemanggil ke stub kosong.
__attribute__((export_name("hosiHarnessRun")))
void hoshiHarnessRun() {
    g_reportLen = 0;
    g_dumpLen = 0;
    if (g_dump == nullptr) {
        g_dump = (float*)hosi_rt_alloc(kDumpCap * sizeof(float));
    }
    if (g_dump == nullptr) {
        emitStr("FATAL: alokasi dump gagal\n");
        return;
    }

    const SignalSpec signals[] = {
        {SIG_TONE,   60.0f,  -20.0f, "tone60_m20"},
        {SIG_TONE,   60.0f,   -6.0f, "tone60_m6"},
        {SIG_TONE,  300.0f,  -20.0f, "tone300_m20"},
        {SIG_TONE,  300.0f,   -6.0f, "tone300_m6"},
        {SIG_TONE, 3000.0f,  -20.0f, "tone3k_m20"},
        {SIG_TONE, 3000.0f,   -6.0f, "tone3k_m6"},
        {SIG_TONE, 1000.0f,  -20.0f, "tone1k_m20"},
        {SIG_TONE, 1000.0f,   -6.0f, "tone1k_m6"},
        {SIG_TONE,10000.0f,  -20.0f, "tone10k_m20"},
        {SIG_TONE,10000.0f,   -6.0f, "tone10k_m6"},
        {SIG_NOISE,   0.0f,  -14.0f, "noise14"},
    };
    const int signalCount = (int)(sizeof(signals) / sizeof(signals[0]));

    int casesRun = 0;
    emitMathSelfTest();
    emitProbe();
    emitStr("CASE|preset|signal|hash|outDb|inDb|gainDb|peak|clip|dumpOff|dumpFrames|measuredFrames\n");

    for (int pi = 0; pi < kPresetCount; ++pi) {
        if (!idMatchesFilter(g_presetFilter, kPresets[pi].id)) continue;
        for (int si = 0; si < signalCount; ++si) {
            if (!idMatchesFilter(g_signalFilter, signals[si].id)) continue;
            ++casesRun;
            const CaseResult r = runCase(kPresets[pi], signals[si], true);
            emitStr("CASE|");
            emitStr(kPresets[pi].id);
            emitChar('|');
            emitStr(signals[si].id);
            emitChar('|');
            emitHex64(r.hash);
            emitChar('|');
            emitFixed(r.outRmsDb, 3);
            emitChar('|');
            emitFixed(r.inRmsDb, 3);
            emitChar('|');
            emitFixed(r.outRmsDb - r.inRmsDb, 3);
            emitChar('|');
            emitFixed(r.peak, 5);
            emitChar('|');
            emitStr(r.clipCount == 0 ? "0" : "1");
            emitChar('|');
            emitFixed((double)r.dumpOffset, 0);
            emitChar('|');
            emitFixed((double)r.dumpFrames, 0);
            emitChar('|');
            emitFixed((double)r.measuredFrames, 0);
            emitChar('\n');

            emitStr("TELEM|");
            emitStr(kPresets[pi].id);
            emitChar('|');
            emitStr(signals[si].id);
            emitChar('|');
            emitStr("wet=");        emitFixed(r.wet, 4);
            emitStr(" wetX=");      emitFixed(r.wetAtCrossfade, 4);
            emitStr(" agcPre=");    emitFixed(r.agcPreGain, 4);
            emitStr(" slowRms=");   emitFixed(r.slowRms, 6);
            emitStr(" medRms=");    emitFixed(r.medRms, 6);
            emitStr(" entry=");     emitFixed(r.dbgLAtEntry, 6);
            emitStr(" detect=");    emitFixed(r.dbgLAtDetector, 6);
            emitStr(" M0=");        emitFixed(r.shelfM0, 6);
            emitStr(" M1=");        emitFixed(r.shelfM1, 6);
            emitStr(" G=");         emitFixed(r.shelfG, 6);
            emitStr(" airDb=");     emitFixed(r.airBoostDb, 4);
            emitStr(" mudDb=");     emitFixed(r.mudCutDb, 4);
            emitStr(" frames=");    emitFixed((double)r.framesProcessed, 0);
            emitChar('\n');
        }
    }

    // Kontrol bypass: adaptive DSP dimatikan, semua parameter 1,0. Output harus
// identik input (gain 0,00 ± 0,05 dB). Kalau tidak, ada jalur pemrosesan di
// luar gate wet yang belum ketahuan.
const char* kControlSignals[] = {"tone1k_m20", "noise14"};

for (int ci = 0; ci < 2; ++ci) {
    const SignalSpec* sig = nullptr;
    for (int si = 0; si < signalCount; ++si) {
        const char* a = signals[si].id;
        const char* b = kControlSignals[ci];
        while (*a && *a == *b) { ++a; ++b; }
        if (*a == '\0' && *b == '\0') { sig = &signals[si]; break; }
    }
    if (sig == nullptr) { emitStr("FATAL|sinyal kontrol tidak ditemukan\n"); continue; }
    const CaseResult r = runCase(kPresets[0], *sig, false);
    ++casesRun;
    emitStr("CASE|control_bypass|");
    emitStr(sig->id);
    emitChar('|');
    emitHex64(r.hash);
    emitChar('|');
    emitFixed(r.outRmsDb, 3);
    emitChar('|');
    emitFixed(r.inRmsDb, 3);
    emitChar('|');
    emitFixed(r.outRmsDb - r.inRmsDb, 3);
    emitChar('|');
    emitFixed(r.peak, 5);
    emitChar('|');
    emitStr(r.clipCount == 0 ? "0" : "1");
    emitChar('|');
    emitFixed((double)r.dumpOffset, 0);
    emitChar('|');
    emitFixed((double)r.dumpFrames, 0);
    emitChar('|');
    emitFixed((double)r.measuredFrames, 0);
    emitChar('\n');
}

const char* dynPresets[] = {"reference", "ref_nocf", "flat", "bass_boost", "v_shape", "air_shimmer"};
for (int i = 0; i < 6; ++i) {
    const PresetSpec* p = findPreset(dynPresets[i]);
    if (p == nullptr) {
        emitStr("FATAL|preset dyn tidak ditemukan: ");
        emitStr(dynPresets[i]);
        emitChar('\n');
        continue;
    }
    runDynCase(*p);
}

    emitStr("DONE|cases=");
    emitFixed((double)casesRun, 0);
    emitChar('\n');
}

__attribute__((export_name("hosiReportPtr"))) const char* hosiReportPtr() { return g_report; }
__attribute__((export_name("hosiReportLen"))) int hosiReportLen() { return (int)g_reportLen; }
__attribute__((export_name("hosiDumpPtr"))) const float* hosiDumpPtr() { return g_dump; }
__attribute__((export_name("hosiDumpLen"))) int hosiDumpLen() { return (int)g_dumpLen; }
// Ditulis JS sebelum hoshiHarnessRun() untuk membatasi kasus.
__attribute__((export_name("hosiPresetFilterPtr"))) const char* hosiPresetFilterPtr() { return g_presetFilter; }
__attribute__((export_name("hosiSignalFilterPtr"))) const char* hosiSignalFilterPtr() { return g_signalFilter; }

} // extern "C"
// Runtime freestanding untuk harness DSP: matematika double-precision,
// bump allocator, dan operator new.
//
// Semua fungsi matematika dihitung dalam `double` dengan deret Taylor yang
// direduksi argumennya, sehingga akurasinya setara libm platform (galat relatif
// < 1e-15). Yang paling penting: hasilnya deterministik, jadi baseline dan
// build sesudahnya bisa dibandingkan bit-exact.
#include "hosi_rt.h"
#include "new"

namespace {

const double kPi = 3.14159265358979323846264338327950288;
const double kLn2 = 0.69314718055994530941723212145817657;
const double kLn10 = 2.30258509299404568401799145468436421;

inline double roundNearest(double v) {
    return v >= 0.0 ? (double)(long long)(v + 0.5) : (double)(long long)(v - 0.5);
}

// exp(x) = 2^k * exp(r), k = round(x/ln2) sehingga |r| <= ln2/2.
} // namespace

extern "C" double hosi_rt_exp(double x) {
    if (x != x) return x;
    if (x > 709.0) return 1.0e308 * 1.0e308;
    if (x < -745.0) return 0.0;

    const double kd = roundNearest(x * (1.0 / kLn2));
    const double r = x - kd * kLn2;

    double term = 1.0;
    double sum = 1.0;
    for (int i = 1; i <= 16; ++i) {
        term *= r / (double)i;
        sum += term;
    }

    long long k = (long long)kd;
    double scale = 1.0;
    if (k > 0) { for (long long i = 0; i < k; ++i) scale *= 2.0; }
    else       { for (long long i = 0; i > k; --i) scale *= 0.5; }
    return sum * scale;
}

extern "C" double hosi_rt_log(double x) {
    if (!(x > 0.0)) {
        if (x == 0.0) return -1.0e308;
        return 0.0 / 0.0;
    }

    // Dekomposisi manual ke m * 2^e dengan m in [1, 2).
    // __builtin_frexp tidak dipakai: di target freestanding wasm32 ia lowering
    //-nya butuh pustaka luar sehingga hasilnya selalu 0.
    int e = 0;
    double m = x;
    while (m >= 2.0) { m *= 0.5; ++e; }
    while (m < 1.0) { m *= 2.0; --e; }

    // log(m) = 2 * atanh(z), z = (m-1)/(m+1), |z| <= 1/3
    const double z = (m - 1.0) / (m + 1.0);
    const double z2 = z * z;
    double term = z;
    double sum = z;
    for (int i = 3; i <= 45; i += 2) {
        term *= z2;
        sum += term / (double)i;
    }
    return 2.0 * sum + (double)e * kLn2;
}

extern "C" double hosi_rt_pow(double base, double exp) {
    if (exp == 0.0) return 1.0;
    if (base == 0.0) return 0.0;
    if (base < 0.0) return 0.0;
    return hosi_rt_exp(exp * hosi_rt_log(base));
}

extern "C" double hosi_rt_sin(double x) {
    // Reduksi ke kuadran: r = x - k*(pi/2) dengan |r| <= pi/4, lalu putar
    // sesuai k mod 4. Reduksi ini jauh lebih akurat daripada x - k*pi (yang
    // membuat |r| sampai pi sehingga deret Taylor butuh lebih banyak suku).
    const double kd = roundNearest(x * (2.0 / kPi));
    const double r = x - kd * (kPi * 0.5);
    const double r2 = r * r;

    double st = r, ss = r;      // sin(r)
    double ct = 1.0, cs = 1.0;  // cos(r)
    for (int n = 1; n <= 12; ++n) {
        st *= -r2 / (double)((2 * n) * (2 * n + 1));
        ct *= -r2 / (double)((2 * n - 1) * (2 * n));
        ss += st;
        cs += ct;
    }

    const long long k = (long long)kd;
    switch (((k % 4) + 4) % 4) {
        case 0: return ss;
        case 1: return cs;
        case 2: return -ss;
        default: return -cs;
    }
}

extern "C" float hosi_rt_tanf(float xf) {
    const double x = (double)xf;
    const double kd = roundNearest(x * (2.0 / kPi));
    const double r = x - kd * (kPi * 0.5); // |r| <= pi/4
    const double r2 = r * r;

    double st = r, ss = r;                 // sin(r)
    double ct = 1.0, cs = 1.0;             // cos(r)
    for (int n = 1; n <= 12; ++n) {
        st *= -r2 / (double)((2 * n) * (2 * n + 1));
        ct *= -r2 / (double)((2 * n - 1) * (2 * n));
        ss += st;
        cs += ct;
    }

    const long long k = (long long)kd;
    if (k & 1LL) return (float)(-cs / ss);
    return (float)(ss / cs);
}

extern "C" float hosi_rt_expf(float x) { return (float)hosi_rt_exp((double)x); }
extern "C" float hosi_rt_log10f(float x) {
    // log10(x) = ln(x) / ln(10)  -- BUKAN / ln(2).
    return (float)(hosi_rt_log((double)x) * (1.0 / kLn10));
}

// ------------------------------------------------------------------ memori

namespace {

// Bump allocator di atas blok statis. Target wasm32-unknown-unknown tidak
// punya heap, dan harness ini hanya butuh aloka sekali lalu bebas.
alignas(16) unsigned char g_heap[128u * 1024u * 1024u];
size_t g_heapOffset = 0;

} // namespace

extern "C" int hoshi_memalign_raw(void** out, size_t alignment, size_t bytes) {
    if (out == nullptr) return 22; // EINVAL
    if (alignment < 16) alignment = 16;
    const size_t base = (g_heapOffset + alignment - 1u) & ~(alignment - 1u);
    const size_t alignedEnd = (base + bytes + 15u) & ~(size_t)15u;
    if (alignedEnd > sizeof(g_heap)) return 12; // ENOMEM
    g_heapOffset = alignedEnd;
    *out = g_heap + base;
    return 0;
}

extern "C" void* hosi_rt_alloc(size_t bytes) {
    void* p = nullptr;
    if (hoshi_memalign_raw(&p, 16, bytes) != 0) return nullptr;
    return p;
}

extern "C" void hosi_rt_free(void*) { /* bump allocator: tidak pernah dipakai */ }

extern "C" void* hosi_rt_memset(void* dst, int c, size_t n) {
    unsigned char* d = (unsigned char*)dst;
    for (size_t i = 0; i < n; ++i) d[i] = (unsigned char)c;
    return dst;
}

extern "C" void* hosi_rt_memcpy(void* dst, const void* src, size_t n) {
    unsigned char* d = (unsigned char*)dst;
    const unsigned char* s = (const unsigned char*)src;
    for (size_t i = 0; i < n; ++i) d[i] = s[i];
    return dst;
}

extern "C" char* hosi_rt_strcpy(char* dst, const char* src) {
    char* d = dst;
    while ((*d++ = *src++) != '\0') {}
    return dst;
}

// ------------------------------------------------------------- operator new

namespace std {
const nothrow_t nothrow(0);
} // namespace std

void* operator new(size_t size) {
    void* p = hosi_rt_alloc(size);
    if (p == nullptr) __builtin_trap();
    return p;
}
void* operator new(size_t size, const std::nothrow_t&) noexcept { return hosi_rt_alloc(size); }
void operator delete(void*) noexcept {}
void operator delete(void*, const std::nothrow_t&) noexcept {}
void operator delete(void*, size_t) noexcept {}
void operator delete(void*, size_t, const std::nothrow_t&) noexcept {}
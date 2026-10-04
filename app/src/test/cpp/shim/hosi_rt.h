// Header interne runtime harness freestanding (wasm32-unknown-unknown).
//
// Tidak ada libc di target freestanding, jadi semua fungsi yang dipakai
// hoshi_dsp_core.cpp dideklarasikan di sini dan diimplementasikan di
// hosi_rt.cpp. Matematika dihitung dalam `double` supaya akurasinya setara
// libm platform.
#pragma once

#include <stddef.h>
#include <stdint.h>

extern "C" {

double hosi_rt_exp(double x);
double hosi_rt_log(double x);
double hosi_rt_pow(double base, double exp);
double hosi_rt_sin(double x);
float hosi_rt_tanf(float x);
float hosi_rt_expf(float x);
float hosi_rt_log10f(float x);

void* hosi_rt_alloc(size_t bytes);
void hosi_rt_free(void* p);
void* hosi_rt_memset(void* dst, int c, size_t n);
void* hosi_rt_memcpy(void* dst, const void* src, size_t n);
char* hosi_rt_strcpy(char* dst, const char* src);
// Mengembalikan 0 kalau berhasil, errno-ish kalau gagal (tanda tangan POSIX).
int hoshi_memalign_raw(void** out, size_t alignment, size_t bytes);

} // extern "C"

static inline float hosi_rt_fminf(float a, float b) { return a < b ? a : b; }
static inline float hosi_rt_fmaxf(float a, float b) { return a > b ? a : b; }
static inline float hosi_rt_fabsf(float a) { return __builtin_fabsf(a); }
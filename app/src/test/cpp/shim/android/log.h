// Stub <android/log.h> untuk harness native.
//
// DspEngine::process() memanggil __android_log_print beberapa kali; di host
// harness panggilan itu harus jadi no-op supaya tidak ada I/O sama sekali.
#pragma once

#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio;
    (void)tag;
    (void)fmt;
    return 0;
}
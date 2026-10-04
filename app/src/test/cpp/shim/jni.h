// Stub <jni.h> untuk harness native.
//
// Harness hanya memakai kelas hoshi::DspEngine; seluruh blok `extern "C"`
// JNI dipotong sebelum kompilasi (lihat run_harness.js -> trimSource).
#pragma once

// Sengaja kosong: deklarasi JNI hanya dibutuhkan oleh blok JNI yang dihapus.
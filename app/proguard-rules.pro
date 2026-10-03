# Hoshi Music keeps no reflective application models.
# Media3 and AndroidX ship their consumer ProGuard rules automatically.

-keeppackagenames com.atilika.kuromoji.**
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Pengamanan Komprehensif JNI Hoshi DSP Engine (Mencegah Obfuscation Bidang/Metode)
-keep class com.naomi.hoshimusic.playback.dsp.DspTelemetry { *; }
-keepclassmembers,includedescriptorclasses class com.naomi.hoshimusic.playback.dsp.DspTelemetry {
    <fields>;
    <init>(...);
}

# PENTING: kelas yang punya method native yang dipanggil dari C++ lewat JNI
# adalah DspNativeEngine, BUKAN HoshiDspProcessor. Semua simbol di
# hoshi_dsp_core.cpp bernama:
#   Java_com_naomi_hoshimusic_playback_dsp_DspNativeEngine_native*
# Native mencarinya berdasarkan NAMA, jadi R8 boleh me-rename atau menghapus
# method Kotlin tanpa merusak referensi Kotlin — tapi binding JNI-nya mati.
# Gejalanya: library tetap load (System.loadLibrary sukses) lalu setiap
# pemanggilan melempar UnsatisfiedLinkError, dan DSP diam-diam tidak berjalan.
# Hanya terlihat di build release/minified.
#
# BENTUK INI PENTING: memakai `-keep class ... { *; }`, bukan
# `-keepclasseswithmembernames` atau `-keepclassmembers`. Yang terakhir hanya
# mencegah NAMA kelas atau MENELEKSI member, tapi tidak selalu mencegah
# obfuscasi nama method di R8 full mode (default AGP 8+). Terverifikasi di
# APK release: dengan `-keepclasseswithmembernames`, method tetap ter-obfuscate
# menjadi .e/.g/.j/.k sehingga JNI gagal bind. Kelas ini kecil, jadi
# menjaga seluruh member tidak menambah ukuran berarti.
-keep class com.naomi.hoshimusic.playback.dsp.DspNativeEngine {
    *;
}

-keep class com.naomi.hoshimusic.playback.HoshiDspProcessor { *; }
-keepclassmembers,includedescriptorclasses class com.naomi.hoshimusic.playback.HoshiDspProcessor {
    native <methods>;
    <fields>;
    <init>(...);
}

-keep class com.naomi.hoshimusic.playback.dsp.DspTelemetryBridge { *; }
-keepclassmembers class com.naomi.hoshimusic.playback.dsp.DspTelemetryBridge { *; }

# --- Room Database (KSP Generated) ---
# Ensures that classes like TranslationOverrideDao_Impl are not removed.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Entity class * { *; }
# Keep the generated implementation classes (suffix _Impl)
-keep class **_Impl { *; }
-dontwarn androidx.room.paging.**

# --- Kotlin / Coroutines (Reflection) ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- App-specific Entity/DAO Safety ---
-keep class com.naomi.hoshimusic.data.db.** { *; }

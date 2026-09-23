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

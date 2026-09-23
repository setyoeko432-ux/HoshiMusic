# Hoshi Music 1.4.5

Pemutar musik Android **lokal, pribadi, tanpa iklan, tanpa akun, dan tanpa izin jaringan (INTERNET/ACCESS_NETWORK_STATE)**.

- Application ID: `com.naomi.hoshimusic`
- Minimum Android: 8.0 / API 26
- Target / compile SDK: 35
- Bahasa: Kotlin + Jetpack Compose
- Playback: AndroidX Media3 / ExoPlayer + `MediaSessionService`
- DSP Engine: Native C++ (High-Performance 32-bit Float Processing)
- Filosofi: **Offline-First AI Synergy** — Kekuatan AI luar (Gemini/GPT) dalam balutan privasi total.

## Patch Note (V1.4.5)
- **Boutique Cleanup**: Removal of redundant 'Orphan' files and consolidated quality logic for 100% architectural purity.
- **Unified Quality Engine**: Synchronized HI-RES and LOSSLESS detection across all UI components (Hero Cards & Song Rows).
- **Project Hygiene**: Cleaned up duplicate 'LrcParseResult' definitions and empty legacy directories.
- **Stable Navigation**: Refined back-navigation logic to prevent accidental app closure on sub-screens.
- **Exclusive Path Polish**: Implemented hardware priming to ensure seamless playback on Vivo/iQOO high-res paths.

## Patch Note (V1.4.4)
- **Boutique Bypass Toggle**: Switchable high-priority 'Game' routing to force-disable OEM effects (SKTune/Dolby).
- **Master Compatibility**: Forced 48kHz resampling for non-FLAC formats (MP3/OGG) to ensure zero-silence on exclusive paths.
- **Engine Self-Awareness**: Metadata & Parser versioning to automatically re-scan library when logic improves.
- **Linguistic Engine**: Added support for Unsynchronized (Plain-Text) embedded lyrics with smart order preservation.
- **Search Harmony**: Refreshed Search UI with consistent 100.dp top bar, modern pill shape, and bounce feedback.
- **Performance Hygiene**: Fixed 'Duplicate Key' crashes and 'Ghost Tracks' resource leaks.
- **Fluid Motion**: Added high-end slide-and-fade transitions for the Linguistic Workbench.

## Fitur Unggulan (V1.2.0)

### 🛠️ Linguistic Workbench (AI Editor)
- **Fullscreen AI Editor**: Lingkungan kerja profesional untuk mengoreksi lirik. Pisahkan antara koleksi (kiri) dan editor grid (kanan).
- **AI Bridge**: Tombol satu-sentuh untuk menyalin prompt puitis ke Gemini/ChatGPT dan fitur "Paste dari Clipboard" yang cerdas.
- **Universal Parser**: Mendukung berbagai format timestamp AI (`[00:10.00]`, `10000:`, dll) secara otomatis.
- **Dynamic Fallback**: Gunakan terjemahan manual untuk baris sulit, dan biarkan lirik asli untuk sisanya.

### 🎶 Lyrics Rendering & Advanced Sync
- **Official SYLT Support**: Native binary synchronized lyrics decoding (ID3v2 standard).
- **Instant Loading**: High-speed metadata parsing with automated dictionary warm-up.
- **Responsive Instrumental Trigger**: Unified logic for the ♪ marker across all views with zero latency.
- **Furigana-Style Stack**: Tampilan bilingual yang dioptimalkan — Terjemahan/Romaji (Biru) kini berada di **ATAS** lirik asli (Putih).
- **Exclusive Preview**: Preview lirik di beranda otomatis berubah menjadi terjemahan/romaji murni saat diaktifkan, meminimalkan gangguan visual.
- **Zero-Blank Fix**: Algoritma baru untuk menangani morfem Kanji Jepang yang terpecah oleh token LRC word-level, memastikan tidak ada baris kosong.
- **Responsive Focus**: Auto-scroll kembali ke penyanyi setelah 3,5 detik jeda interaksi, bahkan saat bagian instrumen (♪).

### 🚀 Optimasi Performa "Butter Smooth"
- **Memoized Math**: Seluruh logika pengelompokan Album, Artis, dan Genre kini di-cache (memoized), menghilangkan stutter saat scrolling koleksi besar.
- **State Throttling**: Optimasi sistem "heartbeat" untuk mengurangi beban CPU dan menghemat baterai tanpa mengorbankan akurasi sinkronisasi.
- **Scroll Memory**: App mengingat posisi scroll terakhir kamu di tab Koleksi, bahkan setelah membuka dan menutup pemutar.

### 🔒 Privasi dan Keamanan Total
- **Tanpa Izin Jaringan**: Menjamin 100% data tidak pernah meninggalkan perangkat (Zero Network Permissions).
- **Offline Total**: Penghapusan dependensi Google ML Kit untuk memperkecil ukuran APK dan menghentikan pengecekan model di latar belakang.
- **Data Lokal**: Playlist, koreksi lirik, dan statistik disimpan di database Room lokal yang aman.

## Struktur Proyek

- `app/src/main/cpp/` — Mesin DSP Native C++ (PEQ, Crossfeed, Limiter).
- `data/` — Implementasi MediaStore, Room DB (termasuk Translation Overrides), dan LRC Parser.
- `playback/` — Media3 Session, Audio Route Monitor, dan JNI Bridge.
- `ui/` — Komponen Jetpack Compose, M3 Theming, dan Linguistic Workbench.
- `worker/` — Background tasks untuk pemindaian metadata dan loudness.

## Cara Compile

1. Instal Android Studio versi stabil terbaru.
2. Pastikan NDK 26.1.10909125 tersedia.
3. Build APK Debug: `./gradlew :app:assembleDebug`
4. Jalankan Unit Test: `./gradlew :app:testDebugUnitTest`

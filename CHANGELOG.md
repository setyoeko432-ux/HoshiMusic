# Changelog

Semua entri ditulis berdasarkan `git diff` terhadap working tree, bukan hasil asumsi.

---

## 1.4.7 — 27 Sep 2026

`versionCode` 21 → **22**

### Rilis ini berisi dua kelompok perubahan yang berbeda asal-usulnya

1. **Fitur & perbaikan produk** — sudah ada di working tree sebelum upgrade toolchain dimulai.
2. **Upgrade toolchain** — dilakukan bersama media3 1.11.1 (lihat bagian terpisah di bawah).

Keduanya belum di-commit, jadi belum dipisah ke commit masing-masing.

---

### 🎵 Perbaikan audio — kestabilan numerik DSP

**`-ffast-math` dihapus dari flag kompilasi global.**

`app/src/main/cpp/CMakeLists.txt` sekarang hanya memakai
`-O3 -flto -fno-finite-math-only -fsigned-zeros`. Alasannya tercatat langsung di file:
filter IIR/biquad `TptSvf` dan `DspEngine::processFrame()` sangat sensitif terhadap
reassociation floating-point dan denormal handling, sehingga `-ffast-math` secara global
berisiko menyebabkan ketidakstabilan filter dan deviasi numerik. Fast-math kini hanya
diterapkan selektif per-fungsi yang aman (mis. `tanhApprox`) lewat
`__attribute__((optimize("fast-math")))`.

Ini perubahan **korektif untuk audio correctness**, bukan sekadar optimalisasi.

### 🔍 Diagnostik audio

- `HoshiAudioSink` —added `trace(op)` untuk mencatat transisi state sink
  (`play` / `pause` / `flush` / `reset`) beserta `ended`, `hasPendingData`, dan
  `bufferSizeUs`, supaya terlihat mengapa audio berhenti mengalir.
- `HoshiDspProcessor` dan `MusicPlaybackService` diperluas untuk mendukung penelusuran
  rantai audio yang sama.
- `DiagLog.w()` sekarang menerima `Throwable?` sebagai argumen ketiga.
- `routeFallback(routeType, original)` untuk penamaan route Bluetooth/wired.

### 🏷️ Analisis audio otomatis (fitur baru)

Tabel Room baru `song_ai_tags` dengan `AiTagsDao`:

| Kolom | Isi |
|---|---|
| `predictedGenre` / `genreConfidence` | genre hasil prediksi + confidence |
| `bpmDetected` / `keyDetected` | tempo & nada dasar |
| `modelVersion` | versi model, default `1.0.0` |
| `analysisStatus` | default `PENDING` |
| `analyzedAtMs` / `isOffline` | waktu analisis & penanda offline (default `1`) |

Foreign key ke `songs(mediaId)` dengan `ON UPDATE NO ACTION ON DELETE CASCADE`,
dilengkapi index `song_ai_tags_songMediaId`.

Setelan baru `PreferenceKeys.AiTaggingEnabled` dengan
`setAiTaggingEnabled()` pada `LibraryPreferences` dan
`PreferenceCoordinator`.

> [!CAUTION]
> **Room database 12 → 13.** `MIGRATION_12_13` didaftarkan di rantai migrasi
> (`MIGRATION_3_4` … `MIGRATION_11_12`, lalu `MIGRATION_12_13`).using
> `CREATE TABLE IF NOT EXISTS` plus `CREATE INDEX IF NOT EXISTS`.
>
> **Migrasi ini belum pernah dijalankan di perangkat.** Test migrasi tinggal di
> `app/src/androidTest` yang bersifat instrumented, dan tidak ada device yang
> tersambung saat rilis ini disiapkan. Jalankan sebelum rilis.

### 🎼 Mode tampilan lirik — double-tap cycling

`onToggleRomanization(enabled: Boolean)` diganti menjadi `onCycleDisplayMode()` tanpa
argumen, dengan `viewModel.cycleLyricDisplayMode()` →
`prefCoordinator.cycleLyricDisplayMode()`. Double-tap kini memutar
`ORIGINAL ↔ ROMANIZED` atau `ORIGINAL ↔ TRANSLATED` sesuai mode yang aktif, bukan
toggle boolean dua arah.

Dua unit test baru menutup perilaku ini:
`test double tap cycle in Romaji toggle mode toggles between ORIGINAL and ROMANIZED`
dan
`test double tap cycle in Terjemahan toggle mode toggles between ORIGINAL and TRANSLATED`.

Ada pula `refreshVocalBounds(lines: List<LyricLine>)` untuk pemrosesan lirik.

### 🔊 Audio low-latency (fitur baru)

Setelan `PreferenceKeys.LowLatencyAudio` dengan `setLowLatencyAudio()` dan
`setLowLatencyAudioEnabled()` yang diteruskan sampai ke `PlaybackViewModel`.

### 🎨 Antarmuka

- `rememberSmoothPosition(...)` untuk transisi posisi yang halus.
- `TechnicalInfoFormatters` diperluas (+57 baris).
- `SettingsContent`, `SharedUi`, `PreferenceCoordinator`, `MetadataWorker`,
  `HoshiMusicApplication` dan `SpatializerManager` disesuaikan.
- `PlayerArtwork` diberi anotasi `@file:UnstableApi`, dan `ForcedLandscapeScreen`
  diberi komentar yang menjelaskan kenapa `targetState` sengaja dibuat konstan.
- `MiniPlayer`, `PlaybackControls`, `LyricsContent`, `CarSettingsContent`,
  `RomajiHelper`, `UserPreferences` ikut menyesuaikan.

### 🧪 Test

`HoshiConsolidatedTest` (+64 baris) dan `CleanupAuditTests` (+8 baris) diperluas.
Status terverifikasi: **89 test, 0 failure, 0 error, 0 skipped** dari 11 kelas test.

---

### 🛠️ Upgrade toolchain (menjadikan media3 1.11.1 mungkin)

| Komponen | Dari | Ke |
|---|---|---|
| Gradle | 8.9 | 9.6.0 |
| Android Gradle Plugin | 8.7.3 | 9.3.0 |
| Kotlin | 2.0.21 | 2.2.10 |
| KSP | 2.0.21-1.0.28 | 2.3.12 |
| Compose Multiplatform | 1.7.0 | 1.9.3 |
| compileSdk / targetSdk | 35 / 35 | 37 / 36 |
| **media3** | **1.9.0** | **1.11.1** |
| Room | 2.6.1 | 2.8.5 |
| Compose BOM | 2024.12.01 | 2026.09.00 |
| core-ktx / activity | 1.15.0 / 1.10.0 | 1.18.0 / 1.13.0 |
| lifecycle | 2.8.7 | 2.11.0 |
| work / datastore | 2.10.0 / 1.1.1 | 2.12.0 / 1.2.1 |
| coroutines / guava | 1.9.0 / 33.0.0 | 1.11.0 / 33.7.1 |

**media3 1.11.1 sendiri tidak butuh satu baris pun perubahan kode.** 40+ override
`AudioSink` di `HoshiAudioSink`, `HoshiAudioOutputProvider`, `HoshiDspProcessor`,
`HoshiBitmapLoader`, dan `MusicPlaybackService` semuanya kompilasi utuh pada 1.11.1.

 necessitated oleh `minCompileSdk = 36` yang dipaksakan oleh AAR media3 1.11.x, lalu
Compose 1.12.1 dan lifecycle 2.11.0 menaikkan kebutuhan minimum ke `minCompileSdk = 37`.

Perubahan build script lain:

- Modul `:shared` dipindahkan ke `com.android.kotlin.multiplatform.library`, karena
  AGP 9 melarang `org.jetbrains.kotlin.multiplatform` berada di modul yang sama
  dengan `com.android.library`.
- Plugin `org.jetbrains.kotlin.android` dihapus (built-in Kotlin AGP 9),
  `android.kotlinOptions` dihapus, `assets.srcDirs` → `assets.directories`.
- Dua perbaikan sumber akibat Compose 1.12.1:
  `SharedTransitionScope.ResizeMode.ScaleToBounds(x)` → `scaleToBounds(x)` di
  `PlayerArtwork.kt` dan `NowPlayingScreen.kt`.

> [!WARNING]
> **Upgrade Room 2.6.1 → 2.8.5 dipaksakan oleh KSP2**, bukan pilihan. KSP 2.3.x
> menghapus backend KSP1 yang masih dibutuhkan Room 2.6.x. Versi dan skema database
> tidak diubah oleh upgrade ini — perubahan 12 → 13 berasal dari fitur analisis
> audio di atas.

> [!CAUTION]
> **Behaviour runtime Android 16 baru aktif karena `targetSdk` naik ke 36.**
> Yang paling perlu diperiksa di perangkat: pada display `sw ≥ 600dp`,
> `setRequestedOrientation()` diabaikan oleh Android 16, sehingga
> `ui/util/OrientationHelper.kt` (mode player paksa landscape) bisa diam-diam tidak
> bekerja di tablet/foldable.

---

### ✅ Yang sudah diverifikasi

| Perintah | Hasil |
|---|---|
| `:app:compileDebugKotlin` | SUCCESSFUL, 0 error |
| `:app:testDebugUnitTest` | SUCCESSFUL — 11 kelas, 89 test, 0 failure |
| `:app:assembleDebug` | SUCCESSFUL — 4 APK per-ABI |
| `:shared:assemble` | SUCCESSFUL |
| `:app:lintDebug` | SUCCESSFUL |
| `:app:assembleRelease` | SUCCESSFUL — 4 APK ~17,4 MB, R8 + resource shrinking |

---

### ⚠️ Yang belum diverifikasi — perlu sebelum rilis

1. **Migrasi Room 12 → 13 di perangkat nyata.** Area berisiko tertinggi. Test-nya
   instrumented dan tidak sempat dijalankan.
2. **Build belum ditandatangani.** `signingConfig` tidak ada di `app/build.gradle.kts`
   dan tidak ada keystore di project, sehingga APK release keluar sebagai
   `*-unsigned.apk` dan **tidak bisa diinstal**. Signature block di
   `configuration.txt`: nol entri `REMOVED CONSUMER RULE`.
3. **Perilaku audio di perangkat.** Perubahan `-ffast-math` dan default
   `DefaultAudioTrackBufferSizeProvider` yang berubah di media3 1.11.1 sama-sama
   memengaruhi jalur output. Bit-perfect dan rantai DSP **belum** diukur ulang —
   kompilasi lolos bukan bukti.
4. **Perilaku remote control.** Media3 1.11.x tidak lagi membagikan data session ke
   controller yang tidak dipercaya secara default; perlu dicek pada
   `BluetoothClarityMonitor`, `VolumeObserver`, dan `WiredRoutingManager`.
5. **Tidak ada instrumented test yang dijalankan**, dan aplikasi tidak terpasang di
   perangkat mana pun saat catatan ini dibuat.

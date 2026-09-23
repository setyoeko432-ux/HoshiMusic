# Arsitektur Hoshi Music

## Tujuan

Hoshi Music adalah pemutar file audio lokal. Arsitektur sengaja kecil dan offline: tidak ada backend, akun, iklan, analitik, atau permission internet.

## Modul

| Modul | Peran |
|---|---|
| `:app` | Aplikasi Android utama (MediaStore, ExoPlayer, Room, Compose UI, NDK DSP, WorkManager) |
| `:shared` | Modul KMP Compose Multiplatform — UI bersama, expect/actual untuk Platform, AudioPlayer, SongRepository, PlaylistQueue, Artwork, PlatformUtils |

## Alur Data Utama

```text
MediaStore ──> MusicRepository ──> LibraryViewModel ──> Compose Library
                                        │
DataStore <── UserPreferences <─────────┤
                                        │
M3U / LRC / ReplayGain <──────────────┘

Compose Player ──> PlaybackViewModel ──> MediaController
                                           │
                                           v
                                MusicPlaybackService
                                           │
                                      ExoPlayer
                                           │
                                  HoshiDspProcessor (C++ JNI)
```

## Komponen

### `MusicRepository`

- Memindai seluruh volume MediaStore yang terlihat bagi aplikasi (atau folder kustom pilihan pengguna via SAF).
- Menggunakan kolom dasar pada semua versi, lalu mencoba metadata tambahan pada API yang mendukungnya.
- Jika query kolom tambahan gagal pada vendor tertentu, repository mengulang dengan projection dasar.
- Menghasilkan ID stabil berbasis `content://` URI; tidak menebak raw path pada scoped storage.
- Mendeteksi format audio sesungguhnya (codec, container, sample rate, bit depth) via `MediaMetadataRetriever` + `AudioFormatInfo`.
- Migrasi `mediaId` otomatis saat upgrade (disimpan di Room DB).
- Background scan menggunakan WorkManager (`MetadataWorker`, `LoudnessScanWorker`).

### `UserPreferences`

Satu `Preferences DataStore` (`user_preferences`) diakses melalui 5 wrapper:

- **`PlaybackPreferences`**: lyric source priority, gapless, normalization, BT guard mode, ambience, preamp dB, spatial audio, boutique bypass, auto-skip intro/outro, wired bit-perfect preferred.
- **`AppearancePreferences`**: dynamic color, app theme, romanization, translation mode, landscape dual-pane.
- **`LibraryPreferences`**: favorite song IDs, hidden folders, filter short tracks, music folder URI, mediaId migration state.
- **`DspPreferences`**: DSP enabled, PEQ enabled/profiles, DSP presets, route profiles (JSON-encoded dalam Preferences).
- **`MusicDataStores`**: playlists, saved queues, total listening time, loudness scan statistics (JSON-encoded dalam Preferences).

Kompleksitas tinggi (playlists, queues, PEQ profiles, DSP presets, route profiles) diserialisasikan sebagai JSON string (`org.json`) di dalam Preferences DataStore.

### `HoshiDatabase` (Room)

- **Version 12** dengan 7 migrasi eksplisit (3→4, 6→7, 7→8, 8→9, 9→10, 10→11, 11→12).
- **Entitas**: `SongEntity`, `LyricsCacheEntity`, `TranslationOverrideEntity`.
- Migrasi 10→11 membuat tabel `translation_overrides` untuk koreksi manual lirik/terjemahan.
- Migrasi 8→9 menambahkan kolom `container`, `codec`, `detectionSource`, `isFormatVerified`.
- `exportSchema = true` — skema database tercatat.

### `PlaylistFileRepository`

- Impor M3U/M3U8 dari URI SAF (Storage Access Framework).
- Pencocokan berurutan: content URI, portable path, lalu nama file yang tidak ambigu.
- Ekspor `#EXTM3U`, `#PLAYLIST`, `#EXTINF`, dan path portabel.
- Batas 50.000 baris saat impor untuk mencegah input yang tidak terkendali.

### `LyricsRepository`

- Mencari sidecar `.lrc` bernama sama di folder lagu.
- Parser LRC versi 3 dengan invalidasi cache otomatis saat versi parser berubah.
- Mendukung banyak timestamp per baris, fraksi detik, offset, metadata, serta fallback teks polos.
- **Cache in-memory** (`LruCache` 256 entries) untuk percepatan switching lirik.
- `saveTranslationOverride()` — menyimpan koreksi manual pengguna di Room DB.
- Dekode multi-codec untuk file LRC (UTF-8 BOM, UTF-16, GB18030, EUC-KR, Shift_JIS, Big5).

### `LrcLoader`

- Membaca file LRC dengan batas 5 MiB.
- Cascade decoding: BOM sniff → strict UTF-8 → legacy codec candidates.
- Normalisasi timestamp, pengurutan berdasarkan waktu, penghapusan word-level tags (`<00:00.00>`).

### `ReplayGainReader`

- Membaca maksimal 2 MiB awal file dan mencari tag track/album gain serta peak.
- Gain diterapkan pada volume player; nilai peak membatasi gain positif untuk mengurangi clipping.
- Ini bukan loudness scanner dan tidak mengubah file.

### `AudioRouteMonitor`

Mengamati dan mengklasifikasikan semua perangkat output Android:
- **Route types**: Speaker, Headphones, Bluetooth, HDMI, USB, Watch, Cast.
- State dilaporkan sebagai `AudioRouteState` (nama, deskripsi, type, USB flag, sink capability).

### `BluetoothClarityMonitor`

- Mendeteksi speaker Bluetooth generik berkualitas rendah (codec SBC).
- Mengaktifkan **BT Clarity Guard** secara otomatis untuk menghindari kualitas audio rendah.
- `BtClarityBridge` menjadi jembatan status ke UI tanpa menyentuh service langsung.

### `WiredRoutingManager`

- Mendeteksi perangkat audio kabel (3.5mm / USB).
- Prioritas perutean Bit-Perfect: USB > Wired Headphones/Headset.
- `AudioDeviceCallback` mendengarkan perubahan perangkat secara real-time.

### `MusicPlaybackService`

Merupakan `MediaSessionService` dengan `ExoPlayer` dan `MediaSession`.

- **Audio Path Status**: Melacak status DSP secara real-time:
  - `DSP_ERROR` — library native gagal dimuat
  - `DSP_BYPASS` — DSP dinonaktifkan pengguna
  - `DSP_ACTIVE` — DSP sedang memproses audio
  - `DSP_ATTACHED` — DSP terpasang tapi belum memproses (negotiation)
- **State Saving**: Menyimpan timeline, indeks, posisi, shuffle, repeat, dan `playWhenReady` ke `SharedPreferences` (`playback_state`) setiap pergantian lagu dan peristiwa player kunci.
- **Artwork Caching**: Pra-muat artwork ke Coil memory cache untuk mencegah "blank flash" pada UI.
- **Metadata Enrichment**: Menggunakan `MediaMetadataRetriever` untuk mengekstrak embedded art dari file audio.
- **Boutique Bypass**: Path routing khusus dengan forced sample rate matching (min 48kHz) untuk format non-FLAC pada perangkat tertentu.

### `HoshiDspProcessor` (JNI Bridge)

- JNI bridge ke mesin DSP C++ (`hoshi_dsp`).
- Native functions: `nativeCreate`, `nativeInit`, `nativeProcess`, `nativeFlush`, `nativeReset`, `nativeRelease`, `nativeSetSystemVolume`, `nativeGetTelemetrySnapshot`, `nativeApplyPeqSnapshot`, `nativeSetAdaptiveDspEnabled`, `nativeSetEnabled`, `nativeSetParams`, `nativeSetBtClarity`, `nativeSetAmbience`, `nativeSetPreampGain`.
- **Fitur**: PEQ (10 band), Crossfeed, Limiter, Bass Boost, Spatial Audio (3 preset), Ambience, Preamp, Boutique Bypass.
- `libraryLoaded` — flag fallback aman jika library native gagal dimuat.
- `isHardwareSupported` — deteksi dukungan hardware priming (Vivo/iQOO).
- `warmupFrames` — counter hardware priming.
- State dikunci dengan `ReentrantReadWriteLock` pada audio thread.

### `AudioSignalModels`

- `AudioPathStatus` enum: UNKNOWN_FORMAT, DSP_ERROR, DSP_BYPASS, DSP_ACTIVE, DSP_ATTACHED.
- `OutputSinkCapability` — deskripsi kemampuan output sink audio.
- `PlaybackState` — isPlaying, currentTimeMs, durationMs, isBuffering, error.

### ViewModel

- **`LibraryViewModel`**: Menggabungkan hasil scan, pencarian, favorit, hidden folders, playlist (membuat/menghapus/menambah/menghapus lagu), named queue, dan statistik pemutaran. Juga menangani import/export playlist M3U dan refresh library.
- **`PlaybackViewModel`**: Boundary ke `MediaController`. Mengelola antrean, shuffle, repeat, speed, seek, volume, DSP state, lirik, ReplayGain, output volume, route state, dan audio path status. Termasuk `PreferenceCoordinator` untuk persistensi semua pengaturan.

### `HoshiMusicApplication`

- `ImageLoaderFactory` — membuat Coil `ImageLoader` kustom dengan `AudioArtworkFetcher` (fetcher khusus untuk artwork dari file audio tertanam).
- `AudioArtworkFetcher.Factory` — memutuskan apakah URI adalah audio berdasarkan MIME type dan ekstensi file (mp3, flac, wav, m4a, aac, ogg, opus, alac, wma, dsf, dff).

## UI & Navigation

| Tab | Label | Konten Utama |
|---|---|---|
| 🏠 Home | Beranda | Hero cards (rhythm), recently/frequently played, lirik preview, mini player |
| 📚 Library | Koleksi | Daftar lagu, playlist, artist/album/genre grids, pencarian |
| ⚙️ Settings | Atur | DSP (PEQ, presets, spatial audio, ambience, boutique bypass), appearance (theme, dynamic color, romanization, translation mode), privacy, audio routing |
| ✍️ Workbench | Koreksi | Translation Workbench — edit lirik & terjemahan, landscape dual-pane |

- `NowPlayingScreen` — Full-screen player dengan artwork blur, kontrol playback, queue, DSP status, technical info sheet.
- `MiniPlayer` — Mini player dengan blur radius lebih kecil, navigasi ke NowPlayingScreen.
- `SharedUi.kt` (shared module) — `Artwork` (Coil-based), `EmptyState`, `ErrorBanner`, `PermissionScreen`, `rememberSmoothPosition`.

## Motion dan Gesture

- Library ↔ Player menggunakan `AnimatedContent` vertikal.
- Perubahan tab menggunakan transisi horizontal yang mengikuti arah indeks.
- Artwork dan ikon play/pause memakai fade/scale.
- Song row menggabungkan horizontal drag, tap, long-press, haptic, dan spring return.
- Mini-player membedakan gestur horizontal dan vertikal.
- Artwork Now Playing memberi offset serta rotasi sebelum commit previous/next.
- Queue row memberi swipe-to-remove dan tombol reorder eksplisit sebagai fallback yang aksesibel.

## Keamanan dan Privasi

- **Tidak ada** `android.permission.INTERNET` atau `android.permission.ACCESS_NETWORK_STATE` di manifest.
- **Tidak ada** `MANAGE_EXTERNAL_STORAGE`.
- SAF dipakai untuk M3U sehingga aplikasi tidak membutuhkan akses file global.
- `android:allowBackup="false"` — data tidak di-backup ke cloud.
- `backup_rules.xml` dan `data_extraction_rules.xml` dibersihkan mencegah backup cloud data sensitif (DataStore/DB).
- Input playlist dibatasi dan tidak dieksekusi.
- Tidak ada klaim bit-perfect/USB-exclusive karena rute akhir tetap dikelola Android.

## Kode Native (C++)

- **Library**: `hoshi_dsp` (shared library, dimuat via `System.loadLibrary("hoshi_dsp")`).
- **CMake**: `-O3 -ffast-math -flto -fno-finite-math-only -fsigned-zeros` (optimasi agresif).
- **Linker**: `-Wl,-z,max-page-size=16384 -flto`.
- Sumber: `hoshi_dsp_core.cpp` (50.3 KB) + header `hoshi_dsp_core.h`.

## Lisensi

Kode Hoshi Music adalah implementasi independen berlisensi MIT. Tidak ada kode/aset proprietary dari PixelPlayer, Poweramp, UAPP, Musicolet, Neutron, atau aplikasi lain yang disalin.

# Hoshi Music

Pemutar musik Android **lokal, pribadi, tanpa iklan, tanpa akun, dan tanpa izin jaringan** (tanpa `INTERNET` / `ACCESS_NETWORK_STATE`).

## Informasi Build

| Komponen | Detail |
|---|---|
| **Versi** | 1.4.6 |
| **Version Code** | 21 |
| **Application ID** | `com.naomi.hoshimusic` |
| **Min Android** | 8.0 / API 26 |
| **Target / Compile SDK** | 35 |
| **NDK** | 26.1.10909125 |
| **Bahasa** | Kotlin + Jetpack Compose (Material 3) |
| **Modul** | `:app` (Android), `:shared` (KMP Compose Multiplatform) |

## Fitur Utama

### 🎶 Pemutaran Musik Lokal
- Memindai perpustakaan musik dari **MediaStore** secara otomatis
- **Media3 ExoPlayer** dengan **MediaSessionService** (foreground service)
- Dukungan format: MP3, FLAC, WAV, M4A, AAC, OGG, Opus, ALAC, WMA, DSF, DFF
- Kontrol lengkap: mainkan, jeda, skip next/previous, shuffle, repeat, volume, kecepatan pemutaran, seek
- Playlist queue dengan drag-and-drop reorder

### 🎛️ DSP Engine Native (C++)
- Mesin DSP berperforma tinggi **32-bit Float Processing** via C++ (`hoshi_dsp`)
- **PEQ 10-Band** (Parametric Equalizer) dengan profil tersimpan
- **Crossfeed**, **Limiter**, **Bass Boost**
- **Spatial Audio** 3 mode: Standar (Binaural), Lebar (Stage), Studio (Close)
- **Ambience** (tambahan ruang suara)
- **ReplayGain** (pembacaan tag track/album gain & peak)
- **Preamp** (penyesuaian gain input dB)
- **Boutique Bypass** — bypass paksa efek OEM (SKTune/Dolby) dengan routing prioritas tinggi
- Catatan: shim matematika harness menggunakan double precision. Valid untuk regresi perilaku dan perbandingan output harness, bukan klaim bit-identical terhadap floating-point/device implementation.

### 📝 Lirik & Romanisasi
- **Parser LRC** (versi 3) dengan validasi cache invalidation
- **Dukungan SYLT** (synchronized lyrics binary, standar ID3v2)
- **Romanisasi** multi-bahasa: Mandarin (Han-Latin + Pinyin), Jepang (Kuromoji), Korea, Inggris
- **Furigana per-token** untuk lirik Jepang (karaoke akurat tanpa ICU)
- **Terjemahan bilingual** (lirik asli + terjemahan)
- **Zero-Blank Fix** — algoritma penanganan morfem Kanji terpecah untuk mencegah baris kosong

### ✍️ Translation Workbench (Koreksi Lirik)
- Editor profesional untuk mengoreksi lirik dan terjemahan
- Pisahkan antara koleksi (kiri) dan editor grid (kanan)
- Transisi animasi slide-and-fade

### 📂 Playlist
- **Impor/ekspor M3U/M3U8** via Storage Access Framework (SAF)
- Playlist lokal (m3u8), Saved Queue, serta statistik pemutaran (play count, last played)
- Folder musik kustom (pilih folder via SAF dengan persistable permission)

### 🔍 Manajemen Library
- Pencarian berdasarkan query teks
- Tandai favorit & sembunyikan folder tertentu
- Filter lagu pendek (short track filter)
- Info detail: audio format, metadata, embedded artwork

### 🔵 Bluetooth & Audio Routing
- Deteksi perangkat Bluetooth (nama & codec detection)
- **Bluetooth Clarity Monitor** — pemantauan routing clarity
- **Wired Routing Manager** — manajemen output audio wired
- **Boutique Bypass** dengan forced sample rate matching (min 48kHz untuk format non-FLAC)

### ⚙️ Pengaturan
- **Tema**: Dynamic Color, Dark/Light/System, tema kustom
- **DSP Presets**: Simpan, hapus, dan atur profil PEQ custom
- **Spatial Audio Presets**: Pilih dari 3 mode
- **Romanisasi** & **Mode Terjemahan**: Aktif/nonaktifkan per lagu
- **Gapless playback**, **Normalisasi audio**, **Playback speed**
- **Priority lyric source**: Atur prioritas sumber lirik
- **Landscape dual-pane** mode (tablet)

### 🔒 Privasi Total
- **Tanpa izin jaringan**: `INTERNET` dan `ACCESS_NETWORK_STATE` **tidak ada** di manifest
- `android:allowBackup="false"` — data tidak di-backup ke cloud
- **Offline total**: semua data tersimpan lokal (Room DB, DataStore, file)
- `backup_rules.xml` dan `data_extraction_rules.xml` dibersihkan mencegah backup data sensitif

## Struktur Proyek

```
app/                          # Modul Android utama
├── src/main/
│   ├── cpp/                  # DSP Native C++ (hoshi_dsp_core)
│   ├── java/                 # Kotlin source code
│   │   ├── playback/         # MediaSessionService, ExoPlayer, DSP bridge, routing
│   │   ├── data/             # Repository, LRC parser, Room DB, lyrics, metadata
│   │   ├── ui/               # Compose UI, navigation, settings, player, themes
│   │   └── util/             # Romanizer, formatters, helpers
│   └── res/                  # Resources, themes, icons
shared/                       # Modul KMP Compose Multiplatform
├── src/commonMain/           # Expect declarations (AudioPlayer, SongRepository, etc.)
├── src/androidMain/          # Android actual implementations
└── build.gradle.kts          # Android + Compose Multiplatform targets
gradle/                       # Gradle wrapper & version catalog
```

### Alur Data

```text
MediaStore ──> MusicRepository ──> LibraryViewModel ──> Compose Library
                                        │
DataStore <── UserPreferences <────────┤
                                        │
M3U / LRC / ReplayGain <───────────────┘

Compose Player ──> PlaybackViewModel ──> MediaController
                                           │
                                           v
                                MusicPlaybackService
                                           │
                                      ExoPlayer
                                           │
                                HoshiDspProcessor (C++ JNI)
```

### Navigasi

| Tab | Label | Fungsi |
|---|---|---|
| 🏠 Home | Beranda | Perpustakaan musik, lirik, mini player |
| 📚 Library | Koleksi | Daftar lagu, playlist, filter, pencarian |
| ⚙️ Settings | Atur | DSP, tema, privacy, audio routing |
| ✍️ Workbench | Koreksi | Translation Workbench editor lirik |

## Cara Compile

1. Instal **Android Studio** versi stabil terbaru.
2. Pastikan **NDK 26.1.10909125** tersedia (SDK Manager → SDK Tools).
3. Sync Gradle.
4. Build APK Debug:
   ```bash
   ./gradlew :app:assembleDebug
   ```
5. Jalankan Unit Test:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

## Lisensi

MIT License — lihat [LICENSE](LICENSE)

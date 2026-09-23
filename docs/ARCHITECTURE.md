# Arsitektur Hoshi Music 1.1

## Tujuan

Hoshi Music adalah pemutar file audio lokal. Arsitektur sengaja kecil dan offline: tidak ada backend, akun, iklan, analitik, atau permission internet.

## Alur data

```text
MediaStore ──> MusicRepository ──> LibraryViewModel ──> Compose Library
                                        │
DataStore <── UserPreferences <─────────┤
                                        │
M3U/LRC/ReplayGain <────────────────────┘

Compose Player ──> PlaybackViewModel ──> MediaController
                                           │
                                           v
                                MusicPlaybackService
                                           │
                                      ExoPlayer
```

## Komponen

### `MusicRepository`

- Memindai seluruh volume MediaStore yang terlihat bagi aplikasi.
- Menggunakan kolom dasar pada semua versi, lalu mencoba metadata tambahan pada API yang mendukungnya.
- Jika query kolom tambahan gagal pada vendor tertentu, repository mengulang dengan projection dasar.
- Menghasilkan ID stabil berbasis `content://` URI; tidak menebak raw path pada scoped storage.

### `UserPreferences`

Satu DataStore `user_preferences` menyimpan:

- `favorite_song_ids`
- `hidden_folders`
- `playlists_json`
- `saved_queues_json`
- `play_stats_json`

JSON dipilih agar upgrade ini tidak memerlukan Room/KSP baru. Semua mutasi serial melalui DataStore `edit`.

### `PlaylistFileRepository`

- Impor M3U/M3U8 dari URI SAF.
- Pencocokan berurutan: content URI, portable path, lalu nama file yang tidak ambigu.
- Ekspor `#EXTM3U`, `#PLAYLIST`, `#EXTINF`, dan path portabel.
- Batas 50.000 baris saat impor untuk mencegah input yang tidak terkendali.

### `LyricsRepository`

Mencari sidecar `.lrc` bernama sama di folder lagu. Parser mendukung beberapa timestamp per baris, fraksi detik, offset, metadata, serta fallback teks polos.

### `ReplayGainReader`

Membaca maksimal 2 MiB awal file dan mencari tag track/album gain serta peak. Gain diterapkan pada volume player; nilai peak membatasi gain positif untuk mengurangi clipping. Ini bukan loudness scanner dan tidak mengubah file.

### `AudioRouteMonitor`

Mengamati perangkat output Android dan mengklasifikasikan USB, kabel, Bluetooth, HDMI, atau speaker. Pada transisi baru ke USB, `PlaybackViewModel` menetapkan master volume aplikasi ke 25%. Pengguna masih memegang kontrol penuh setelah peringatan.

### `MusicPlaybackService`

- Memiliki `ExoPlayer` dan `MediaSession`.
- Menangani audio focus dan becoming noisy melalui Media3.
- Menyimpan timeline, indeks, posisi, shuffle, repeat, dan `playWhenReady` secara berkala.
- Bertahan saat UI ditutup selama playback aktif.

### ViewModel

`LibraryViewModel` menggabungkan hasil scan, pencarian, favorit, hidden folders, playlist, named queue, dan statistik.

`PlaybackViewModel` menjadi boundary ke `MediaController`, mengelola antrean, speed, timer, lirik, ReplayGain, output volume, serta route state.

## Motion dan gesture

- `MotionIconButton` menggunakan `MutableInteractionSource`, ripple, spring scale, dan target 48 dp.
- Library ↔ Player menggunakan `AnimatedContent` vertikal.
- Perubahan tab menggunakan transisi horizontal yang mengikuti arah indeks.
- Artwork dan ikon play/pause memakai fade/scale.
- Song row menggabungkan horizontal drag, tap, long-press, haptic, dan spring return.
- Mini-player membedakan gestur horizontal dan vertikal.
- Artwork Now Playing memberi offset serta rotasi sebelum commit previous/next.
- Queue row memberi swipe-to-remove dan tombol reorder eksplisit sebagai fallback yang aksesibel.

## Keamanan dan privasi

- Tidak ada `android.permission.INTERNET` atau `android.permission.ACCESS_NETWORK_STATE`.
- Tidak ada `MANAGE_EXTERNAL_STORAGE`.
- SAF dipakai untuk M3U sehingga aplikasi tidak membutuhkan akses file global.
- Input playlist dibatasi dan tidak dieksekusi.
- Tidak ada klaim bit-perfect/USB-exclusive karena rute akhir tetap dikelola Android.

## Lisensi

Kode Hoshi Music adalah implementasi independen berlisensi MIT. Tidak ada kode/aset proprietary dari PixelPlayer, Poweramp, UAPP, Musicolet, Neutron, atau aplikasi lain yang disalin.

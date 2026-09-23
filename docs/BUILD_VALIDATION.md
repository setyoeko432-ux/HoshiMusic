# Build validation — Hoshi Music 1.4.5 (Privacy Enforcement)

Tanggal validasi: 13 September 2026

## Perbaikan Privasi & Kontradiksi

- **Manifest**: Menghapus `android.permission.INTERNET` dan `android.permission.ACCESS_NETWORK_STATE` karena aplikasi murni offline.
- **Testing**: Menambahkan `PrivacyIntegrityTest.kt` yang memverifikasi ketiadaan permission jaringan menggunakan Robolectric.
- **Audit Kode**: Tidak ditemukan penggunaan API `java.net`, `HttpURLConnection`, atau library networking pihak ketiga.
- **Build**: Memastikan build tetap sukses tanpa permission tersebut.

## Cara Reproduksi

- **Struktur Proyek**: 65 berkas Kotlin di modul `:app`, 2 berkas native C++, 6 berkas XML resource.
- **Hygiene**: Menghapus `.kotlin/`, cache, log, dan versi lama `MetadataWorker.kt` di root. `.gitignore` diperbarui.
- **Build System**: Dependency `media3-exoplayer` duplikat telah dihapus. Flag `-O3/-ffast-math` dikonsolidasi ke CMake.
- **Permissions**: 
    - `BLUETOOTH_SCAN` dihapus (tidak diperlukan untuk deteksi perangkat terhubung).
    - Request `BLUETOOTH_CONNECT` diubah menjadi kontekstual (tidak otomatis saat startup).
    - `READ_MEDIA_AUDIO` diminta saat membuka library.
- **Privasi**: 
    - `android:allowBackup="false"` diterapkan.
    - `backup_rules.xml` dan `data_extraction_rules.xml` dibersihkan untuk mencegah backup cloud data sensitif (DataStore/DB).
    - Tidak ada izin `INTERNET` atau `ACCESS_NETWORK_STATE`.
    - `PrivacyIntegrityTest` memvalidasi ketiadaan permission jaringan pada merged manifest.
- **Audio & DSP**:
    - `HoshiDspProcessor` sekarang mendukung fallback aman jika library native gagal dimuat.
    - Mode DSP Bypass (OFF) tidak lagi memaksa jalur PCM 16-bit, memungkinkan output float/high-res.
    - Penanganan alignment frame 16-bit stereo dijamin oleh carry-over buffer.
- **Lint**: Memperbaiki `NewApi` error pada `LyricsRepository` dan `HoshiRomanizer` melalui guard versi SDK yang eksplisit.
- **Error UI**: Mengintegrasikan `ErrorBanner` untuk menampilkan kesalahan pemindaian library dan query database.
- **Hygiene Final**: Menghapus kode yatim (`LyricsUtils`, `Gson`, orphan DAO methods) dan membersihkan sisa metadata.
- **Testing**: Menambahkan suite `CleanupAuditTests` untuk memverifikasi ketahanan parser, mojibake repair, dan fallback DSP.

## Cara Reproduksi

```bash
# Gunakan JDK 17
./gradlew clean :app:assembleDebug
```

## Integritas
Checksum seluruh berkas sumber tersedia di `SHA256SUMS.txt`.

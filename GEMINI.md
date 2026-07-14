# GEMINI.md — Maktabah Android

Panduan ini membantu Gemini memahami proyek dan bekerja secara konsisten di dalamnya.

## Deskripsi Proyek

Maktabah adalah aplikasi Android untuk membaca koleksi kitab klasik Islam. Proyek ini merupakan **porting dari versi iOS**. Jaga konsistensi nama variabel dan alur logika dengan versi iOS untuk memudahkan maintenance lintas platform.

## Perintah Penting

```bash
# Build debug
./gradlew assembleDebug

# Install ke device/emulator
./gradlew installDebug

# Build release
./gradlew assembleRelease

# Jalankan unit test
./gradlew test

# Jalankan lint
./gradlew lint
```

Build memerlukan `local.properties` dengan konfigurasi signing, CloudKit token, Firebase RTDB URL, dan Cloudflare Worker URL. Lihat README.md untuk daftar lengkap key-nya.

## Arsitektur

Pola **MVVM** wajib diikuti:

- **View** (`ui/`): Jetpack Compose. Tidak boleh mengandung logika bisnis.
- **ViewModel**: Ekspos state ke UI via `StateFlow`. Hindari penggunaan `Context` langsung — gunakan application context atau injeksi jika perlu.
- **Repository / Manager**: Semua akses data (database, network) diletakkan di kelas terpisah, bukan di ViewModel maupun UI.

## Struktur Paket

```
com.maktabah/
├── cloudKit/      # Sinkronisasi anotasi via CloudKit bridge + Firebase FCM
├── database/      # AnnotationManager — akses SQLite untuk anotasi
├── downloader/    # CoreDatabaseDownloader, ConnectivityMonitor, download kitab
├── manager/       # LibraryDataManager — data perpustakaan dari main.sqlite
├── models/        # Data class & model domain
├── search/        # Logika pencarian teks Arab (dengan normalisasi)
├── ui/
│   ├── annotation/
│   ├── cloudKit/
│   ├── common/    # BootstrapScreen, MainScreen, UpdateDialog
│   ├── history/
│   ├── library/   # LibraryViewModel
│   ├── reader/
│   ├── search/
│   └── settings/
├── update/        # UpdateManager, UpdateRepository, UpdateViewModel
├── utils/         # Utilitas umum, normalizeArabic, dsb.
└── MainActivity.kt
```

## Database

SQLite diakses secara **native via JNI** (C++, di `src/main/cpp/`). Gunakan wrapper `SQLiteDB` untuk semua interaksi database — jangan akses JNI langsung dari layer atas.

- `main.sqlite` — data perpustakaan kitab (read-only dari bundle/download)
- `annotations.sqlite` — anotasi pengguna (baca-tulis)

## Sinkronisasi

- **Firebase FCM** digunakan sebagai trigger sinkronisasi global (topic: `global_sync`).
- **CloudKitSyncManager** mengimplementasikan sinkronisasi anotasi mengikuti logika iOS.
- Sinkronisasi dipicu ulang saat koneksi internet kembali (`ConnectivityMonitor`).

## UI & Tema

- Gunakan **Material 3** (`MaterialTheme`) untuk konsistensi.
- Tema menggunakan skema warna **Sepia** (light & dark), didefinisikan di `MainActivity.kt`.
- Pecah composable kompleks menjadi fungsi yang lebih kecil dan reusable.
- Gunakan `remember` dan `derivedStateOf` dengan tepat untuk menghindari recomposition berlebihan.

## Concurrency

| Dispatcher | Digunakan untuk |
|---|---|
| `Dispatchers.IO` | Database, network |
| `Dispatchers.Default` | Komputasi berat (filter list besar, dsb.) |
| `Dispatchers.Main` | Update UI |

Selalu gunakan `viewModelScope` untuk coroutine di dalam ViewModel.

## Text Arab

Gunakan fungsi `normalizeArabic` (di `utils/`) setiap kali memproses teks Arab untuk pencarian agar konsisten dengan versi iOS.

## Code Style

- Kotlin Coroutines, bukan callback/thread manual.
- Package name: `com.maktabah.*`
- Hapus import yang tidak digunakan sebelum commit.
- Commit message menggunakan format: `feat:`, `fix:`, `refactor:`, dsb.
- Lihat [RULES.md](RULES.md) untuk panduan lengkap.

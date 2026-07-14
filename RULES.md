# RULES.md

Dokumen ini berisi aturan dan panduan pengembangan untuk proyek Android Maktabah, yang merupakan porting dari versi iOS.

## 1. Arsitektur (MVVM)
Proyek ini menggunakan pola **Model-View-ViewModel (MVVM)**.
- **View**: Gunakan Jetpack Compose. Dilarang memasukkan logika bisnis ke dalam file UI.
- **ViewModel**: Gunakan `StateFlow` untuk mengekspos state ke UI. Hindari penggunaan `Context` di dalam ViewModel jika memungkinkan (gunakan aplikasi context atau injeksi dependensi jika perlu).
- **Model/Repository**: Pisahkan logika akses data (Database, Network) ke kelas terpisah (misal: `LibraryDataManager`, `CloudKitSyncManager`).

## 2. Jetpack Compose
- Gunakan `MaterialTheme` untuk konsistensi UI.
- Pisahkan komponen UI yang kompleks menjadi fungsi `Composable` yang lebih kecil dan reusable.
- Gunakan `remember` dan `derivedStateOf` secara tepat untuk optimasi recomposition.

## 3. Database & Concurrency
- **SQLite**: Proyek ini menggunakan SQLite via JNI (`Source/Android/app/src/main/cpp/`). Gunakan wrapper `SQLiteDB` untuk interaksi database.
- **Coroutines**: Gunakan Kotlin Coroutines untuk tugas asinkron.
    - `Dispatchers.IO`: Operasi Database dan Network.
    - `Dispatchers.Default`: Komputasi berat (misal: filtering list besar).
    - `Dispatchers.Main`: Update UI.
- Selalu gunakan `viewModelScope` untuk meluncurkan coroutine di dalam ViewModel agar lifecycle terkelola dengan baik.

## 4. Sinkronisasi & Networking
- **FCM**: Digunakan untuk trigger sinkronisasi global.
- **CloudKit Bridge**: Implementasi sinkronisasi data (seperti anotasi) mengikuti logika sinkronisasi iOS.

## 5. Porting dari iOS
- Jaga konsistensi nama variabel dan alur logika dengan versi iOS untuk memudahkan maintenance lintas platform.
- Gunakan utilitas string yang sudah ada (seperti `normalizeArabic`) untuk memproses teks Arab agar konsisten dengan fitur pencarian.

## 6. Code Style
- Ikuti standar Kotlin coding conventions.
- Pastikan semua file baru memiliki package name yang sesuai (`com.maktabah.*`).
- Hapus import yang tidak digunakan dan lakukan format kode sebelum commit.

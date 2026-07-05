# Chat AI Mobile & Chess Screen Assistant

## Chess Screen Assistant

Versi ini menyediakan analisis papan dari gambar yang dipilih pengguna.

### Cara menggunakan

1. Buka menu samping dan pilih **Chess Assistant**.
2. Mulai sesi ketika papan masih berada pada posisi awal permainan.
3. Ambil gambar papan catur.
4. Tekan **Pilih Screenshot** lalu pilih gambar tersebut.
5. Setelah sebuah langkah dimainkan, ambil gambar terbaru dan pilih kembali tanpa menekan **Reset Sesi**.
6. Tekan **Reset Sesi** sebelum memulai permainan baru.

### Cara kerja

- Aplikasi mencari pola papan 8×8.
- Petak yang berisi bidak dikenali dari perbedaan visual terhadap warna petak.
- Identitas bidak dipertahankan dari riwayat langkah sejak posisi awal.
- Posisi diubah menjadi FEN.
- Mesin catur Kotlin lokal menghitung saran langkah dengan pencarian alpha-beta.
- Kedalaman efektif mesin lokal dibatasi hingga 3 untuk menjaga kinerja ponsel.

### Privasi

- Gambar dipilih langsung oleh pengguna melalui pemilih file Android.
- Pemrosesan dilakukan secara lokal.
- Gambar dilepas dari memori setelah analisis.
- Aplikasi tidak melakukan sentuhan atau pemindahan bidak otomatis.

### Keterbatasan

- Sesi harus dimulai dari posisi awal catur standar.
- Papan harus terlihat penuh, lurus, dan tidak tertutup menu atau animasi.
- Tema papan harus mempunyai dua warna petak yang cukup berbeda.
- Setiap gambar berikutnya harus berasal dari permainan dan orientasi papan yang sama.
- Mesin lokal bawaan merupakan mesin ringan, bukan pengganti Stockfish.
- Mode pembacaan terus-menerus masih memerlukan validasi perangkat lebih lanjut.

### Pengujian

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Menjalankan aplikasi

1. Buka proyek dengan Android Studio.
2. Siapkan `.env` mengikuti `.env.example` untuk fitur chat utama.
3. Jalankan aplikasi pada emulator atau perangkat Android.

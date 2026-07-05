# Chat AI Mobile & Chess Screen Assistant

## Chess Screen Assistant

Fitur ini membaca papan catur langsung dari layar menggunakan Android MediaProjection. Pengguna harus memberi izin terlebih dahulu, lalu aplikasi memproses frame secara lokal sekitar satu kali per detik.

### Cara menggunakan

1. Buka menu samping dan pilih **Chess Assistant**.
2. Tekan **Mulai Membaca Layar**.
3. Berikan izin **Tampil di atas aplikasi lain** agar panah dapat muncul.
4. Setujui dialog perekaman layar Android.
5. Pada Android yang mendukung berbagi satu aplikasi, pilih aplikasi catur yang ingin dibaca.
6. Buka papan catur pada posisi awal standar dan tampilkan seluruh papan.
7. Tunggu panah dan kartu petunjuk muncul di atas papan.
8. Mainkan langkah secara manual; rekomendasi akan diperbarui saat posisi berubah.
9. Tekan **Stop** dari aplikasi atau notifikasi setelah selesai.

### Cara kerja

- `MediaProjection` dan `ImageReader` mengambil frame layar.
- Frame diproses lokal dan tidak disimpan ke galeri.
- Aplikasi mencari pola papan 8×8 dan mendeteksi petak yang terisi.
- Identitas bidak dipertahankan dari riwayat langkah sejak posisi awal.
- Posisi diubah menjadi FEN.
- Mesin catur Kotlin lokal menghitung saran langkah dengan pencarian alpha-beta.
- Koordinat langkah dipetakan ke posisi papan lalu digambar sebagai panah overlay.
- Kedalaman efektif mesin lokal dibatasi hingga 3 agar penggunaan CPU tetap aman.

### Petunjuk visual

- Lingkaran tipis menandai petak asal.
- Lingkaran terisi menandai petak tujuan.
- Panah menunjukkan arah gerakan.
- Kartu kecil menampilkan format seperti **C2 → C4**.
- Overlay tidak menerima sentuhan dan tidak menggerakkan bidak secara otomatis.

### Privasi

- Pembacaan layar hanya dimulai setelah izin Android diberikan.
- Notifikasi foreground tetap aktif selama proses berjalan.
- Frame diproses di perangkat dan tidak dikirim ke Gemini, Firebase, server, atau API lain.
- Bitmap dilepas dari memori setelah pemrosesan.
- Aplikasi tidak memakai AccessibilityService, tap otomatis, swipe otomatis, atau pemindahan bidak otomatis.

### Keterbatasan

- Sesi harus dimulai dari posisi awal catur standar.
- Papan harus terlihat penuh, lurus, dan tidak tertutup menu atau animasi.
- Tema papan harus mempunyai dua warna petak yang cukup berbeda.
- Orientasi papan sebaiknya tidak berubah selama satu sesi.
- Posisi panah bergantung pada ketepatan deteksi batas papan.
- Mesin lokal bawaan merupakan mesin ringan, bukan pengganti Stockfish.

### Pengujian

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Menjalankan aplikasi

1. Buka proyek dengan Android Studio.
2. Siapkan `.env` mengikuti `.env.example` untuk fitur chat utama.
3. Jalankan aplikasi pada emulator atau perangkat Android.

# Chat AI Mobile & Chess Screen Assistant

## Chess Screen Assistant

### Server Stockfish

Analisis catur online menggunakan endpoint produksi:

`https://chat-ai-lutfula.vercel.app/api/chess/analyze`

Endpoint menerima `POST` JSON berisi `fen`, `requestId`, serta opsi `movetimeMs` atau `depth`. Endpoint menjalankan Stockfish WebAssembly dan mengembalikan langkah terbaik, evaluasi, kedalaman, nodes, serta principal variation.

Pemeriksaan kesehatan dapat dilakukan dengan request `GET` ke endpoint yang sama.

### Cara Mengaktifkan Fitur

1. Buka menu samping dari halaman utama chat.
2. Pilih **Chess Assistant**.
3. Tekan ikon **Settings** untuk memeriksa URL endpoint dan opsi tampilan.
4. Tekan **Start**.
5. Berikan izin **tampil di atas aplikasi lain** dan izin **perekaman layar** ketika diminta.

### Cara Menghentikan Fitur

- Tekan tombol **Stop** di halaman Chess Assistant; atau
- Tekan tombol **Stop** pada notifikasi Chess Screen Assistant.

### Privasi Data

Frame dan screenshot tidak dikirim ke server. Pemrosesan gambar tetap dilakukan di perangkat. Hanya posisi papan dalam format FEN dan ID request yang dikirim ke endpoint Stockfish ketika mode online aktif.

### Keterbatasan Pembacaan Papan

Pengenalan papan dan bidak masih dalam tahap MVP. Tema papan, ukuran, orientasi, animasi, dan posisi yang kompleks dapat menyebabkan pembacaan kurang akurat. Stockfish online sudah nyata, tetapi kualitas rekomendasi tetap bergantung pada ketepatan FEN yang dihasilkan oleh aplikasi.

### Mesin dan Lisensi

- Komunikasi mesin menggunakan UCI (Universal Chess Interface).
- Server menggunakan paket `stockfish.wasm` berlisensi GPL-3.0.
- Fallback lokal menggunakan binari Stockfish yang tersedia untuk arsitektur perangkat.

### Pengujian Android

Jalankan:

```bash
./gradlew testDebugUnitTest
```

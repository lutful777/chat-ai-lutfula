# Chat AI Mobile & Chess Screen Assistant

## Chess Screen Assistant

### Server Stockfish

Analisis catur online dapat dijalankan melalui Vercel atau Render.

Endpoint Vercel:

`https://chat-ai-lutfula.vercel.app/api/chess/analyze`

Endpoint Render setelah service dibuat:

`https://NAMA-SERVICE.onrender.com/api/chess/analyze`

Endpoint menerima `POST` JSON berisi `fen`, `requestId`, serta opsi `movetimeMs` atau `depth`. Endpoint menjalankan Stockfish WebAssembly dan mengembalikan langkah terbaik, evaluasi, kedalaman, nodes, serta principal variation.

Pemeriksaan kesehatan tersedia melalui:

- `GET /api/chess/analyze`
- `GET /healthz` pada Render

### Konfigurasi Render

Repository sudah menyediakan `server.js`, perintah `npm start`, dan `render.yaml`.

Untuk pembuatan Web Service secara manual, gunakan:

- Language: **Node**
- Branch: **main**
- Region: **Singapore**
- Root Directory: kosong
- Build Command: `npm ci --omit=dev`
- Start Command: `npm start`
- Health Check Path: `/healthz`

Server membaca port dari variabel `PORT` milik Render dan mendengarkan pada `0.0.0.0`.

### Cara Mengaktifkan Fitur

1. Buka menu samping dari halaman utama chat.
2. Pilih **Chess Assistant**.
3. Tekan ikon **Settings** untuk memeriksa URL endpoint dan opsi tampilan.
4. Masukkan endpoint Vercel atau Render yang aktif.
5. Tekan **Start**.
6. Berikan izin **tampil di atas aplikasi lain** dan izin **perekaman layar** ketika diminta.

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

### Pengujian

Pemeriksaan sintaks server:

```bash
npm run check
```

Pengujian Android:

```bash
./gradlew testDebugUnitTest
```

# Chat AI Mobile & Chess Screen Assistant

## Chess Screen Assistant

### Cara Mengaktifkan Fitur
1. Buka menu samping dari halaman utama chat.
2. Pilih "Chess Assistant".
3. Tekan ikon "Settings" (roda gigi) untuk mengatur konfigurasi seperti batas kedalaman mesin, fps, dsb.
4. Tekan tombol "Start" di halaman Chess Assistant.

### Izin Screen Capture
- Aplikasi akan meminta izin (MediaProjection) untuk merekam layar.
- Setujui dialog izin dari sistem Android.
- Notifikasi status aktif (foreground service) akan muncul.

### Cara Menghentikan Fitur
- Tekan tombol "Stop" di dalam aplikasi pada halaman Chess Assistant, ATAU
- Buka laci notifikasi dan tekan tombol "Stop" di notifikasi "Chess Screen Assistant".

### Keterbatasan Pembacaan Papan
- Saat ini merupakan tahap MVP menggunakan metode pengenalan dummy/sederhana.
- Posisi resolusi dan tema visual yang sangat kompleks mungkin belum terdeteksi 100%.

### Informasi Mesin Catur & Lisensi
- Menggunakan arsitektur protokol komunikasi UCI (Universal Chess Interface).
- Silakan gunakan binari mesin catur (seperti Stockfish - GPLv3) untuk diproses melalui class `UciEngine.kt`.

### Privasi Data
- Screen capture dijalankan sepenuhnya **offline dan lokal**.
- Tidak ada data, gambar, frame, atau screenshot yang dikirim ke internet, API, atau Firebase.
- Image buffer dihapus setiap kali frame diproses selesai dari memory.

### Cara Menjalankan Pengujian
Jalankan unit test standar atau instrumented UI test via gradle:
`./gradlew testDebugUnitTest`

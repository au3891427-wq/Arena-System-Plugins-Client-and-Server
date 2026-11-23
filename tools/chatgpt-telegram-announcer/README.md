# ArenaSystem Telegram Announcer

Chrome Extension Manifest V3 untuk memindahkan draft dari percakapan ChatGPT ke topic Telegram ArenaSystem.

## Fitur

- Mengambil teks terpilih atau jawaban ChatGPT terakhir.
- Menampilkan preview yang bisa diedit.
- Memerlukan konfirmasi eksplisit sebelum mengirim.
- Menyimpan API key di `chrome.storage.local`.
- Tidak memantau percakapan atau mengirim otomatis di latar belakang.

## Instalasi

1. Buka `chrome://extensions` di Google Chrome.
2. Aktifkan **Developer mode**.
3. Klik **Load unpacked** dan pilih folder ini.
4. Isi API key di popup extension, lalu simpan.
5. Buka `chatgpt.com`, buat draft, dan buka popup extension.
6. Klik **Ambil jawaban ChatGPT**, edit preview, lalu klik **Kirim ke topic Update** dan setujui dialog konfirmasi.

Pastikan Telegram bot sudah dikonfigurasi ke topic yang benar dengan menjalankan `/setreleasechannel` sebagai admin grup. API key hanya dikirim melalui HTTPS ke domain Railway bot yang ditetapkan di extension.

## API

- Endpoint: `POST /api/announcement`
- Header: `Authorization: Bearer <ANNOUNCEMENT_API_KEY>`
- Header: `Content-Type: application/json`
- Body: `{"text":"Isi pengumuman"}`

Endpoint hanya menggunakan destination yang disimpan oleh ReleaseMonitor. API key tidak boleh dimasukkan ke repository atau dibagikan.

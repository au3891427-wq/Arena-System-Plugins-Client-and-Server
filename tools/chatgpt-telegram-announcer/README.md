# ArenaSystem Telegram Announcer

Chrome Extension Manifest V3 untuk mengirim pengumuman terstruktur dari ChatGPT ke topic Telegram ArenaSystem.

## Cara kerja

- Extension hanya membaca code block dari **pesan assistant terbaru**.
- Jika metadata bahasa tersedia, hanya blok berlabel `json` yang diterima.
- Jika DOM ChatGPT tidak mengekspos metadata bahasa, blok tanpa label hanya diterima bila JSON-nya cocok dengan skema `telegram_announcement` versi 1. JSON biasa dan blok dengan bahasa lain yang terdeteksi tetap ditolak.
- Isi di luar code block, pesan user, dan jawaban assistant sebelumnya tidak pernah dimasukkan ke preview.
- Harus ada tepat satu blok pengumuman yang valid.
- Preview hanya mengambil properti `message`; pengiriman memerlukan pemeriksaan preview dan konfirmasi eksplisit.
- Tidak ada pemantauan percakapan atau pengiriman otomatis di latar belakang.

## Format yang harus dibuat ChatGPT

Minta ChatGPT membuat satu blok kode berlabel `json` dengan format:

```json
{
  "type": "telegram_announcement",
  "version": 1,
  "message": "📢 ArenaSystem Client Update!\n\nChangelog:\n- Fixed synchronization issue."
}
```

Validasi:
- `type` harus `telegram_announcement`.
- `version` harus angka `1`.
- `message` harus string tidak kosong, maksimal 3.900 karakter.
- Tidak ada blok pengumuman valid atau ada lebih dari satu: ekstraksi dibatalkan.

## Instalasi

1. Buka `chrome://extensions` di Google Chrome.
2. Aktifkan Developer mode.
3. Klik Load unpacked dan pilih folder ini.
4. Isi API key di popup extension, lalu simpan.
5. Buka percakapan ChatGPT di tab aktif, lalu klik **Ambil JSON pengumuman**.
6. Periksa dan edit preview, lalu klik **Kirim ke topic Update** dan setujui konfirmasi.

Pastikan Telegram bot sudah dikonfigurasi ke topic yang benar dengan menjalankan `/setreleasechannel` sebagai admin grup.

## API yang digunakan

- Endpoint: `POST https://arenasystem-telegram-bot-production.up.railway.app/api/announcement`
- Header: `Authorization: Bearer <ANNOUNCEMENT_API_KEY>`
- Header: `Content-Type: application/json`
- Body: `{"text":"Isi pengumuman"}`

Backend tetap menentukan destination dari konfigurasi `ReleaseMonitor`. API key tidak boleh dimasukkan ke repository atau dibagikan.

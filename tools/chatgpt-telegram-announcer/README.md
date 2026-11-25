# ArenaSystem Telegram Announcer

Chrome Extension Manifest V3 untuk mengirim pengumuman terstruktur dari ChatGPT ke topic Telegram ArenaSystem.

## Cara kerja

- Extension hanya membaca blok kode berlabel `json` dari **pesan assistant terbaru**.
- Isi di luar blok JSON, pesan user, dan blok kode dengan bahasa lain tidak pernah dimasukkan ke preview.
- Harus ada tepat satu blok JSON dengan skema yang didukung. Tidak ada fallback ke seluruh jawaban atau teks yang dipilih.
- Preview hanya mengambil properti `message`; properti lain tidak dikirim.
- Pengiriman tetap memerlukan pemeriksaan preview dan konfirmasi eksplisit.
- API key disimpan di `chrome.storage.local`; extension tidak memantau percakapan atau mengirim otomatis di latar belakang.

## Format yang harus dibuat ChatGPT

Minta ChatGPT membuat satu blok kode berlabel `json` dengan format:

```json
{
  "type": "telegram_announcement",
  "version": 1,
  "message": "📢 ArenaSystem Client Update!\\n\\nChangelog:\\n- Fixed synchronization issue."
}
```

Validasi:
- `type` harus `telegram_announcement`.
- `version` harus angka `1`.
- `message` harus string tidak kosong, maksimal 3.900 karakter.
- Tidak ada blok JSON atau ada lebih dari satu blok JSON: ekstraksi dibatalkan.

Percakapan biasa tidak menghasilkan pengiriman. Hanya permintaan pengumuman Telegram yang perlu menghasilkan format tersebut.

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

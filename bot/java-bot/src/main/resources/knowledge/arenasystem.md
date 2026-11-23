# Basis Pengetahuan ArenaSystem

## Gambaran umum proyek
ArenaSystem adalah proyek Minecraft yang terdiri dari dua komponen utama yang bekerja sama: plugin server Paper dan mod klien Fabric. Plugin server mengelola sistem arena, tim, pertandingan, kelas, kemampuan, dan medan permainan. Mod klien membantu memverifikasi koneksi ke server serta memeriksa pembaruan. Distribusi resmi yang dianggap stabil diterbitkan melalui halaman GitHub Releases ArenaSystem.

## Plugin server Paper
Plugin Paper adalah komponen sisi server ArenaSystem. Plugin ini mengelola arena, tim, pertandingan, kelas, kemampuan, dan medan permainan. Aturan permainan yang terperinci, statistik kelas, efek kemampuan, waktu jeda kemampuan, nilai kerusakan, serta persyaratan pemasangan harus merujuk pada dokumentasi atau catatan rilis stabil yang sesuai. Jangan menebak detail yang belum dikonfirmasi.

## Mod klien Fabric
ArenaSystemClient adalah mod sisi klien berbasis Fabric. Mod ini memverifikasi koneksi ke server ArenaSystem melalui protokol handshake yang memiliki versi. Mod ini juga memeriksa pembaruan klien melalui HTTPS dan memverifikasi berkas menggunakan SHA-256. Jangan mengasumsikan kompatibilitas dengan versi Minecraft, Fabric Loader, Java, atau server tertentu kecuali versi tersebut disebutkan secara eksplisit pada rilis stabil saat ini.

## Protokol handshake
Mod klien memverifikasi koneksi ke server melalui proses handshake dengan versi protokol tertentu. Tujuannya memastikan klien dan server dapat berkomunikasi menggunakan versi protokol yang diharapkan. Rincian kolom paket, perilaku batas waktu, serta aturan kompatibilitas belum dijelaskan dalam basis pengetahuan ini. Jangan mengarang rincian tersebut.

## Pemeriksa pembaruan klien
Mod klien memeriksa pembaruan melalui HTTPS dan memverifikasi berkas yang diunduh menggunakan SHA-256. Berkas yang dapat diunduh sebagai rilis stabil diterbitkan melalui GitHub Releases. Jangan menyatakan bahwa pembaruan tersedia sebelum metadata rilis mengonfirmasikannya.

## Rilis stabil dan unduhan
Repositori distribusi stabil ArenaSystem adalah https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server. Halaman rilisnya tersedia di https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server/releases. Rilis stabil tidak boleh berstatus draft atau prarilis dan harus memiliki berkas JAR yang berhasil diunggah sebelum ditampilkan sebagai unduhan stabil. Catatan rilis merupakan sumber utama untuk perubahan dan kompatibilitas pada versi tertentu.

## Pemasangan dan kompatibilitas
Petunjuk pemasangan dan kompatibilitas versi dapat berbeda pada setiap rilis. Informasi lengkapnya belum tersedia dalam basis pengetahuan umum ini. Tanyakan versi rilis stabil yang digunakan pengguna dan arahkan mereka ke catatan rilis versi tersebut. Jangan menebak versi Minecraft, Paper, Fabric Loader, Java, dependensi, atau lokasi pemasangan.

## Kelas dan kemampuan
Plugin Paper mengelola kelas dan kemampuan, tetapi basis pengetahuan ini belum memuat uraian, statistik, waktu jeda, nilai kerusakan, atau perilaku permainan yang telah dikonfirmasi untuk setiap kelas. Jangan mengarang detail. Arahkan pengguna ke catatan rilis stabil terbaru atau minta pengelola proyek menambahkan dokumentasi yang sudah diverifikasi.

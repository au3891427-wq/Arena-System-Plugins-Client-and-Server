# Basis Pengetahuan ArenaSystem

## Gambaran umum proyek
Pertanyaan terkait: Apa itu ArenaSystem?; ArenaSystem itu apa?; Jelaskan proyek ArenaSystem; Apa fungsi ArenaSystem?; Apa saja fitur utama ArenaSystem?; Fitur utama ArenaSystem apa saja?; ArenaSystem terdiri dari komponen apa saja?; What is ArenaSystem?; Explain the ArenaSystem project; What are the main features of ArenaSystem?
ArenaSystem adalah proyek Minecraft yang terdiri dari dua komponen utama yang bekerja sama: plugin server Paper dan mod klien Fabric. Plugin server mengelola sistem arena, tim, pertandingan, kelas, kemampuan, dan medan permainan. Mod klien membantu memverifikasi koneksi ke server serta memeriksa pembaruan. Distribusi resmi yang dianggap stabil diterbitkan melalui halaman GitHub Releases ArenaSystem.

## Plugin server Paper
Pertanyaan terkait: Apa itu plugin server ArenaSystem?; Apa fungsi plugin Paper?; Plugin Paper mengelola apa?; Apa saja fitur plugin server?; Fitur server ArenaSystem apa saja?; Bagaimana plugin mengelola arena dan pertandingan?; What does the ArenaSystem Paper plugin do?; What are the server plugin features?
Plugin Paper adalah komponen sisi server ArenaSystem. Plugin ini mengelola arena, tim, pertandingan, kelas, kemampuan, dan medan permainan. Aturan permainan yang terperinci, statistik kelas, efek kemampuan, waktu jeda kemampuan, nilai kerusakan, serta persyaratan pemasangan harus merujuk pada dokumentasi atau catatan rilis stabil yang sesuai. Jangan menebak detail yang belum dikonfirmasi.

## Mod klien Fabric
Pertanyaan terkait: Apa itu ArenaSystemClient?; Apa fungsi mod klien Fabric?; Mod klien ArenaSystem untuk apa?; Apa kegunaan mod Fabric ArenaSystem?; Apa fungsi client mod?; Bagaimana mod klien bekerja?; What is the ArenaSystem Fabric client mod?; What does the client mod do?
ArenaSystemClient adalah mod sisi klien berbasis Fabric. Mod ini memverifikasi koneksi ke server ArenaSystem melalui protokol handshake yang memiliki versi. Mod ini juga memeriksa pembaruan klien melalui HTTPS dan memverifikasi berkas menggunakan SHA-256. Jangan mengasumsikan kompatibilitas dengan versi Minecraft, Fabric Loader, Java, atau server tertentu kecuali versi tersebut disebutkan secara eksplisit pada rilis stabil saat ini.

## Protokol handshake
Pertanyaan terkait: Apa itu handshake ArenaSystem?; Apa fungsi protokol handshake?; Bagaimana proses koneksi client ke server?; Bagaimana klien memverifikasi server?; Apa yang diperiksa saat handshake?; Mengapa handshake diperlukan?; What is the ArenaSystem handshake protocol?; How does the client verify the server?
Mod klien memverifikasi koneksi ke server melalui proses handshake dengan versi protokol tertentu. Tujuannya memastikan klien dan server dapat berkomunikasi menggunakan versi protokol yang diharapkan. Rincian kolom paket, perilaku batas waktu, serta aturan kompatibilitas belum dijelaskan dalam basis pengetahuan ini. Jangan mengarang rincian tersebut.

## Pemeriksa pembaruan klien
Pertanyaan terkait: Bagaimana cara memeriksa update mod klien?; Bagaimana sistem pemeriksa pembaruan bekerja?; Bagaimana file update diverifikasi?; Apa fungsi SHA-256 pada updater?; Apakah mod klien memeriksa update otomatis?; How does the client update checker work?; How are downloaded client files verified?
Mod klien memeriksa pembaruan melalui HTTPS dan memverifikasi berkas yang diunduh menggunakan SHA-256. Berkas yang dapat diunduh sebagai rilis stabil diterbitkan melalui GitHub Releases. Jangan menyatakan bahwa pembaruan tersedia sebelum metadata rilis mengonfirmasikannya.

## Rilis stabil dan unduhan
Pertanyaan terkait: Di mana mengunduh ArenaSystem?; Di mana download plugin atau mod ArenaSystem?; Bagaimana cara mengunduh file JAR?; Di mana rilis stabil terbaru?; Bagaimana menemukan versi terbaru?; Apa itu rilis stabil ArenaSystem?; Bagaimana membedakan rilis stabil dan prarilis?; Where can I download ArenaSystem?; Where are stable releases published?
Repositori distribusi stabil ArenaSystem adalah https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server. Halaman rilisnya tersedia di https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server/releases. Rilis stabil tidak boleh berstatus draft atau prarilis dan harus memiliki berkas JAR yang berhasil diunggah sebelum ditampilkan sebagai unduhan stabil. Catatan rilis merupakan sumber utama untuk perubahan dan kompatibilitas pada versi tertentu.

## Pemasangan dan kompatibilitas
Pertanyaan terkait: Bagaimana cara install ArenaSystem?; Cara instal ArenaSystem; Cara memasang plugin ArenaSystem di server bagaimana?; Bagaimana cara install mod klien Fabric?; Apa langkah pemasangan plugin Paper?; ArenaSystem kompatibel dengan Minecraft versi berapa?; Paper versi berapa yang didukung?; Fabric Loader versi berapa yang diperlukan?; Java versi berapa yang dibutuhkan?; Versi server dan client apa yang kompatibel?; How do I install ArenaSystem?; Which Minecraft, Paper, Fabric Loader, or Java versions are supported?
Petunjuk pemasangan dan kompatibilitas versi dapat berbeda pada setiap rilis. Informasi lengkapnya belum tersedia dalam basis pengetahuan umum ini. Tanyakan versi rilis stabil yang digunakan pengguna dan arahkan mereka ke catatan rilis versi tersebut. Jangan menebak versi Minecraft, Paper, Fabric Loader, Java, dependensi, atau lokasi pemasangan.

## Kelas dan kemampuan
Pertanyaan terkait: Apa saja kelas yang tersedia di ArenaSystem?; Apa kemampuan setiap kelas?; Apa skill tiap kelas?; Berapa damage setiap kelas?; Berapa cooldown skill?; Bagaimana statistik kelas Assassin?; Apa efek kemampuan kelas?; What classes and abilities are available?; What are the class damage values or ability cooldowns?
Plugin Paper mengelola kelas dan kemampuan, tetapi basis pengetahuan ini belum memuat uraian, statistik, waktu jeda, nilai kerusakan, atau perilaku permainan yang telah dikonfirmasi untuk setiap kelas. Jangan mengarang detail. Arahkan pengguna ke catatan rilis stabil terbaru atau minta pengelola proyek menambahkan dokumentasi yang sudah diverifikasi.

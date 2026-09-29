# DanzOverlay

Overlay post-processing (upscale, AA, penajaman, tone mapping, tahap neural)
untuk Android root. Tanpa injeksi ke proses game. Ditulis dengan Kotlin +
renderer native C++ (GLES 3.x), dibangun lewat GitHub Actions.

## Struktur
- `app/`     aplikasi Kotlin + renderer native (`src/main/cpp`)
- `helper/`  helper root untuk capture layer (tahap 3, belum ada kode)
- `scripts/` validasi struktur repo
- `.github/workflows/build.yml` build APK debug otomatis

## Tahap
0. Cek perangkat (selesai)
1. Kerangka repo + GitHub Actions
2. Overlay kosong (selesai)
3. Capture layer game (selesai: overlay terbukti ikut tertangkap MediaProjection)
3b. Jalur display virtual (4a: VD + viewer, selesai; VD id terbentuk di Android 13)
4. Renderer GLES + upscale/AA  <- kamu di sini (4b: GL + sharpen + upscale, helper input root)
5. Penajaman, tone mapping, pseudo-HDR + mask HUD
6. Resolusi render bawaan game + upscale overlay
7. Pass neural (Anime4K-style)
8. Temporal dan penyempurnaan

## Cek tahap 1
Unduh artifact `DanzOverlay-debug` dari tab Actions, instal APK, buka app.
Baris "Native:" harus menampilkan `renderer-stub 0.1 (stage1)`. Tekan
"Tes root", beri izin di KernelSU, dan hasilnya harus `uid=0`.

## Cek tahap 3
1. Tekan "Beri izin via root", lalu "Mulai capture" dan setujui dialog sistem.
2. Status harus menampilkan resolusi layar, jumlah frame, dan rata-rata RGB.
   FPS hanya akurat kalau isi layar bergerak (buka game, tarik bayangan
   notifikasi untuk melihat fps).
3. Tekan "Uji: overlay ikut tertangkap?". Blok magenta dari overlay dicari di
   frame capture. Hasilnya menentukan strategi anti-feedback untuk renderer tahap 4.
4. "Tampilkan pill FPS" memunculkan pill mengambang (FPS dari capture + suhu baterai).
   Geser dengan jari; posisi tersimpan. FPS hanya terisi saat capture berjalan.

## Cek tahap 4a (display virtual)
Hasil tahap 3: overlay ikut tertangkap capture, jadi renderer tidak bisa
memproses layar yang sama dengan tempat ia menggambar. Jalur baru: game
dijalankan di display virtual milik app, hasilnya diolah lalu ditampilkan.
1. Isi nama paket (bawaan `com.android.settings` untuk uji awal), pilih skala render.
2. Tekan "viewer display virtual". Info di kiri atas menampilkan id display dan
   hasil `am start` (ketuk info untuk menyembunyikan).
3. Ketuk/geser di viewer: diteruskan ke display virtual lewat `input -d` (root).
4. Ulangi dengan paket game. Kirim info di kiri atas kalau gagal.
FPS bisa dilihat dengan menyalakan capture + pill sebelum membuka viewer.

## Cek tahap 4b (renderer + input root)
1. "Pilih app / game..." mengisi nama paket dari daftar app terpasang (juga menampilkan paket yang salah ketik).
   App di Dual Apps/Second Space dicari otomatis di semua user.
2. "Render": Langsung (tanpa GL) / GL polos / GL + sharpen + upscale (CAS + Catmull-Rom).
3. Buka viewer. Info kiri atas: FPS game (frame yang dihasilkan game), FPS layar, dan mode sentuhan.
   "helper root" = multi-touch penuh; "input tap/swipe" = cadangan kalau helper gagal.
4. Pill FPS sekarang membaca FPS game dari renderer, tidak perlu capture.
5. Coba skala 75% atau 50%: game dirender lebih kecil, lalu di-upscale ke layar.

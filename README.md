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
2. Overlay kosong  <- kamu di sini
3. Capture layer game
4. Renderer GLES + upscale/AA
5. Penajaman, tone mapping, pseudo-HDR + mask HUD
6. Resolusi render bawaan game + upscale overlay
7. Pass neural (Anime4K-style)
8. Temporal dan penyempurnaan

## Cek tahap 1
Unduh artifact `DanzOverlay-debug` dari tab Actions, instal APK, buka app.
Baris "Native:" harus menampilkan `renderer-stub 0.1 (stage1)`. Tekan
"Tes root", beri izin di KernelSU, dan hasilnya harus `uid=0`.

#!/usr/bin/env python3
"""Cek struktur repo tahap 1 sebelum build. Keluar dengan kode 1 jika ada yang kurang."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/cpp/renderer.cpp",
    "app/src/main/java/com/danzku/overlay/MainActivity.kt",
    "app/src/main/java/com/danzku/overlay/NativeBridge.kt",
    "app/src/main/java/com/danzku/overlay/RootShell.kt",
    "app/src/main/java/com/danzku/overlay/OverlayService.kt",
    "app/src/main/java/com/danzku/overlay/CaptureService.kt",
    "app/src/main/java/com/danzku/overlay/PillService.kt",
    "app/src/main/java/com/danzku/overlay/VdActivity.kt",
    "app/src/main/java/com/danzku/overlay/CaptureStats.kt",
    ".github/workflows/build.yml",
]

errors = []

# file modul app harus di app/, bukan di root
if (ROOT / "src").exists():
    errors.append("folder src/ ada di root; pindahkan isinya ke app/src/")
root_build = ROOT / "build.gradle.kts"
if root_build.is_file() and "android {" in root_build.read_text():
    errors.append("build.gradle.kts root berisi blok android {}; itu harus di app/build.gradle.kts")

for rel in REQUIRED:
    if not (ROOT / rel).is_file():
        errors.append(f"file hilang: {rel}")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
if manifest.is_file():
    try:
        ET.parse(manifest)
    except ET.ParseError as e:
        errors.append(f"manifest tidak valid: {e}")

# service wajib terdaftar di manifest (CaptureService harus bertipe mediaProjection)
if manifest.is_file():
    m = manifest.read_text()
    for svc in (".OverlayService", ".CaptureService", ".PillService"):
        if svc not in m:
            errors.append(f"service {svc} belum terdaftar di manifest")
    if ".VdActivity" not in m:
        errors.append("activity .VdActivity belum terdaftar di manifest")
    if "mediaProjection" not in m:
        errors.append("CaptureService perlu android:foregroundServiceType=\"mediaProjection\"")

# nama fungsi JNI harus cocok dengan package + kelas Kotlin
cpp = ROOT / "app/src/main/cpp/renderer.cpp"
if cpp.is_file() and "Java_com_danzku_overlay_NativeBridge_version" not in cpp.read_text():
    errors.append("fungsi JNI Java_com_danzku_overlay_NativeBridge_version tidak ditemukan")

if errors:
    print("VALIDASI GAGAL")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("VALIDASI OK")

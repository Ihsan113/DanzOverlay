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
    ".github/workflows/build.yml",
]

errors = []
for rel in REQUIRED:
    if not (ROOT / rel).is_file():
        errors.append(f"file hilang: {rel}")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
if manifest.is_file():
    try:
        ET.parse(manifest)
    except ET.ParseError as e:
        errors.append(f"manifest tidak valid: {e}")

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

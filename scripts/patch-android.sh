#!/usr/bin/env bash
# Dijalankan di CI setelah `npx cap add android`: pasang ikon, warna status bar, dan target SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
RES=android/app/src/main/res

# 1. Ikon aplikasi
cp -r resources/android/res/. "$RES/"

# 2. Target SDK 34: layar tidak digambar di bawah status bar (tampilan sama dengan di browser)
if grep -qE 'targetSdkVersion *= *[0-9]+' android/variables.gradle; then
  sed -i -E 's/targetSdkVersion *= *[0-9]+/targetSdkVersion = 34/' android/variables.gradle
else
  echo "PERINGATAN: targetSdkVersion tidak ditemukan di variables.gradle" >&2
fi

# 3. Warna status bar dan navigation bar
STYLES="$RES/values/styles.xml"
if grep -q 'android:background">@null' "$STYLES" && ! grep -q statusBarColor "$STYLES"; then
  sed -i 's|<item name="android:background">@null</item>|<item name="android:background">@null</item>\n        <item name="android:statusBarColor">#0A4D43</item>\n        <item name="android:navigationBarColor">#FFFFFF</item>|' "$STYLES"
else
  echo "PERINGATAN: styles.xml tidak diubah (pola tidak ditemukan atau sudah ada)" >&2
fi
# 4. Keyboard: ubah ukuran layar saat keyboard muncul, supaya kolom balasan tidak tertutup
MANIFEST=android/app/src/main/AndroidManifest.xml
if ! grep -q windowSoftInputMode "$MANIFEST"; then
  sed -i 's|android:launchMode="singleTask"|android:launchMode="singleTask"\n            android:windowSoftInputMode="adjustResize"|' "$MANIFEST"
fi
grep -q windowSoftInputMode "$MANIFEST" || echo "PERINGATAN: windowSoftInputMode tidak terpasang" >&2

echo "patch-android selesai"

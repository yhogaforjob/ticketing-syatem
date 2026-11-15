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

# 5. Pembaruan dalam aplikasi: izin pasang APK, plugin native, FileProvider, versionCode
APPID=id.nuvera.tiket
JAVA=android/app/src/main/java/id/nuvera/tiket
if ! grep -q REQUEST_INSTALL_PACKAGES "$MANIFEST"; then
  sed -i 's|</manifest>|    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n</manifest>|' "$MANIFEST"
fi
grep -q REQUEST_INSTALL_PACKAGES "$MANIFEST" || { echo "GAGAL: izin REQUEST_INSTALL_PACKAGES tidak terpasang" >&2; exit 1; }
grep -q 'fileprovider' "$MANIFEST" || { echo "GAGAL: FileProvider tidak ada di manifest" >&2; exit 1; }
mkdir -p "$RES/xml"
cat > "$RES/xml/file_paths.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="my_images" path="." />
    <cache-path name="my_cache_images" path="." />
</paths>
XML
cp android-src/ApkInstaller.java "$JAVA/ApkInstaller.java"
cat > "$JAVA/MainActivity.java" <<'JAVA'
package id.nuvera.tiket;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ApkInstaller.class);
        super.onCreate(savedInstanceState);
    }
}
JAVA
VC="${GITHUB_RUN_NUMBER:-1}"
GR=android/app/build.gradle
sed -i -E "s/versionCode +[0-9]+/versionCode $VC/" "$GR"
grep -q "versionCode $VC" "$GR" || echo "PERINGATAN: versionCode tidak diubah" >&2

# 6. Tanda tangan tetap: tanpa ini tiap build bertanda tangan beda dan Android menolak pembaruan
mkdir -p ~/.android
cp signing/tiket.keystore ~/.android/debug.keystore
if ! grep -q 'tiket.keystore' "$GR"; then
  sed -i '0,/^android {/s//android {\n    signingConfigs {\n        debug {\n            storeFile file("..\/..\/signing\/tiket.keystore")\n            storePassword "android"\n            keyAlias "androiddebugkey"\n            keyPassword "android"\n        }\n    }/' "$GR"
fi
grep -q 'tiket.keystore' "$GR" || { echo "GAGAL: signingConfigs tidak terpasang" >&2; exit 1; }

echo "patch-android selesai"

#!/usr/bin/env bash
# Build fullscreen-web APK without Gradle.
# Requires: JDK 17, Android SDK build-tools 34.0.0 + platform 34.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
MIN_SDK=21
TARGET_SDK=34

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; echo "set ANDROID_HOME to an SDK with build-tools 34.0.0 + platform 34" >&2; exit 1; }
done

cd "$HERE"
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --auto-add-overlay

echo "[3/6] javac"
javac -source 11 -target 11 -nowarn -Xlint:-options \
  -cp "$AJAR" \
  -d build/classes \
  $(find src build/gen -name '*.java')

echo "[4/6] d8"
"$BT/d8" --lib "$AJAR" --min-api "$MIN_SDK" --release \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "[5/6] package dex"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
if [ ! -f keystore/debug.keystore ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/debug.keystore -alias debug \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Fullscreen Web, O=Vitaly, C=RU" >/dev/null 2>&1
  echo "  generated keystore/debug.keystore (keep it: same key = in-place upgrades)"
fi
"$BT/apksigner" sign \
  --ks keystore/debug.keystore --ks-key-alias debug \
  --ks-pass pass:android --key-pass pass:android \
  --out fullscreen-web.apk build/aligned.apk

echo
"$BT/apksigner" verify --print-certs fullscreen-web.apk | head -5
"$BT/aapt2" dump badging fullscreen-web.apk | grep -E '^(package|launchable-activity|sdkVersion|targetSdkVersion|application-label)' 
echo "OK: $HERE/fullscreen-web.apk ($(stat -c%s fullscreen-web.apk) bytes)"

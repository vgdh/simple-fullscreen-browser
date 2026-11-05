#!/usr/bin/env bash
# Build Simple Fullscreen Browser (GeckoView engine) without a Gradle wrapper.
# Requires: JDK 17+, Android SDK (platform 36 + build-tools 36), Gradle 9.x.
#
# Override ANDROID_HOME / GRADLE if yours live elsewhere.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="${GRADLE:-/opt/gradle-9.8.2/bin/gradle}"

cd "$HERE"

# Signing key: identical key = in-place upgrade over the installed app.
if [ ! -f keystore/debug.keystore ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/debug.keystore -alias debug \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Fullscreen Web, O=Vitaly, C=RU" >/dev/null 2>&1
  echo "  generated keystore/debug.keystore (keep it: same key = in-place upgrades)"
fi

TASK="${1:-assembleRelease}"
"$GRADLE" -p "$HERE" --console=plain "$TASK"

OUT="$(ls build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "$OUT" ] || { echo "no APK produced" >&2; exit 1; }
cp "$OUT" fullscreen-web.apk

BT="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)"
BT="${BT%/}"
if [ -x "$BT/apksigner" ]; then
  "$BT/apksigner" verify --print-certs fullscreen-web.apk | head -3
  "$BT/aapt2" dump badging fullscreen-web.apk \
    | grep -E '^(package|launchable-activity|sdkVersion|targetSdkVersion|native-code)'
fi
echo "OK: $HERE/fullscreen-web.apk ($(du -h fullscreen-web.apk | cut -f1))"

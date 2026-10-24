#!/usr/bin/env bash
# Installs a release APK, launches it, and fails if the app crashes or dies within ~15 s.
# Catches R8/ProGuard stripping that only shows up at runtime.
set -euo pipefail
DIR="$1"; PKG=com.ragul84.hypurr
APK="$DIR/app-release.apk"
if [ ! -f "$APK" ]; then
  # No upload key (e.g. fork/PR without secrets): sign the unsigned R8 build with a throwaway key just to run it.
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  keytool -genkeypair -keystore smoke.jks -storepass smokesmoke -keypass smokesmoke -alias smoke \
    -keyalg RSA -keysize 2048 -validity 1 -dname CN=smoke >/dev/null 2>&1
  APK=smoke-signed.apk
  "$BT/apksigner" sign --ks smoke.jks --ks-pass pass:smokesmoke --out "$APK" "$DIR/app-release-unsigned.apk"
fi
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 15
adb logcat -d -b crash > crash.log || true
if grep -q "$PKG" crash.log; then cat crash.log; echo "release build crashed"; exit 1; fi
adb shell pidof "$PKG" >/dev/null || { adb logcat -d | tail -200; echo "app is not running"; exit 1; }
adb shell screencap -p /sdcard/smoke.png && adb pull /sdcard/smoke.png release-smoke.png
echo "release smoke OK"

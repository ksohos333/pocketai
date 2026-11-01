#!/bin/bash
# Builds companion-extension.xpi (a zip of the extension folder) for Fennec's
# "Load Temporary Add-on…" picker, and stages the unpacked folder for
# Chromium's "Load unpacked" flow.
set -e
cd "$(dirname "$0")/../companion-extension"
OUT="$(dirname "$0")/../build/pocketai-companion.xpi"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
zip -qr "$OUT" .
echo "built $OUT"
if command -v adb >/dev/null 2>&1; then
  adb push "$OUT" /sdcard/Download/ >/dev/null
  adb shell mkdir -p /sdcard/Download/pocketai-ext
  adb push . /sdcard/Download/pocketai-ext/ >/dev/null
  echo "staged on device: /sdcard/Download/pocketai-companion.xpi + /sdcard/Download/pocketai-ext/"
fi

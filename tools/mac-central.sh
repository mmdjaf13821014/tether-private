#!/bin/sh
# Optional: real-radio test of the Tether link from this Mac (no iPhone needed). Builds the TetherCore
# test central as a small .app (macOS only grants Bluetooth to an app with its own usage string),
# asks the Android phone for a fresh pairing code over adb, and runs a pair + full sync.
# macOS will ask once to allow Bluetooth for "TetherCentral".
#   tools/mac-central.sh            # pair with a fresh code and sync for 60 s
#   tools/mac-central.sh run 60     # reconnect with the stored test identity
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
(cd "$ROOT/ios/TetherCore" && swift build -c release --product tether-central >/dev/null)
APP=$ROOT/tools/out/TetherCentral.app
rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS"
cp "$ROOT/ios/TetherCore/.build/release/tether-central" "$APP/Contents/MacOS/TetherCentral"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>app.tether.central-test</string>
<key>CFBundleExecutable</key><string>TetherCentral</string>
<key>CFBundleName</key><string>TetherCentral</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSUIElement</key><true/>
<key>NSBluetoothAlwaysUsageDescription</key><string>Tests the Tether BLE link against the Android phone.</string>
</dict></plist>
PLIST
codesign -s - --force "$APP" >/dev/null 2>&1
LOG=$ROOT/tools/out/central.log
if [ "${1:-pair}" = "pair" ]; then
  CODE=$("$ROOT/tools/dbg.sh" pair_code)
  open -W --stdout "$LOG" --stderr "$LOG" "$APP" --args pair "$CODE" "${2:-60}"
else
  open -W --stdout "$LOG" --stderr "$LOG" "$APP" --args run "${2:-60}"
fi
cat "$LOG"

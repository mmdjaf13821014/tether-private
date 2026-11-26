#!/bin/sh
# Phase 0 spike: build the shell helper as a standalone dex, push it to the phone and run it
# as the shell user (uid 2000) with app_process.
#   tools/spike.sh probe            # list audio devices, try every capture / inject method
#   tools/spike.sh selftest         # during a live call: tone-injection loopback test
#   tools/spike.sh record voice_downlink 10 /data/local/tmp/down.pcm
#   tools/spike.sh tone intercept 5
. "$(dirname -- "$0")/lib.sh"; pick_android
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
OUT=$ROOT/tools/out
mkdir -p "$OUT"
(cd "$ROOT/android" && ./gradlew -q :helper:assembleRelease)
STDLIB=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-stdlib-2.2.21.jar' | head -1)
D8=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)d8
"$D8" --release --min-api 31 --lib "$SDK/platforms/android-36/android.jar" --output "$OUT/helper.zip" \
  "$ROOT/android/helper/build/intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar" "$STDLIB"
adb push -q "$OUT/helper.zip" /data/local/tmp/tether-helper.jar
adb shell CLASSPATH=/data/local/tmp/tether-helper.jar app_process / app.tether.helper.HelperMain "${@:-probe}"

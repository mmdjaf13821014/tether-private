#!/bin/sh
# Starts the Tether call-audio helper (uid 2000) from the Mac over adb, before / instead of the in-app
# Wireless debugging pairing. The app supplies the exact command (APK path, port, auth token).
. "$(dirname -- "$0")/lib.sh"; pick_android
set -e
CMD=$(adb shell am broadcast -n app.tether.android/app.tether.link.DebugReceiver -a app.tether.DEBUG --es cmd helper_cmd \
  | sed -n 's/.*data="\(.*\)"$/\1/p')
[ -n "$CMD" ] || { echo "Tether app not installed or not responding" >&2; exit 1; }
adb shell "$CMD"
sleep 2
adb shell am broadcast -n app.tether.android/app.tether.link.DebugReceiver -a app.tether.DEBUG --es cmd state | sed -n 's/.*data="\(.*\)"$/\1/p'

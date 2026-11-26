# Shared by the tools scripts (POSIX sh). Loads tools/local.env (git-ignored, see local.env.example) and picks the
# Android phone: ANDROID_SERIAL / TETHER_ANDROID_SERIAL if set, else the only device adb sees.
TOOLS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
[ -f "$TOOLS_DIR/local.env" ] && . "$TOOLS_DIR/local.env"

pick_android() {
  [ -z "$ANDROID_SERIAL" ] && [ -n "$TETHER_ANDROID_SERIAL" ] && ANDROID_SERIAL=$TETHER_ANDROID_SERIAL
  if [ -z "$ANDROID_SERIAL" ]; then
    devs=$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')
    n=$(printf '%s\n' "$devs" | grep -c .)
    if [ "$n" -eq 0 ]; then
      echo "No Android phone found by adb. Connect it with USB debugging on (docs/BUILD.md)." >&2; exit 1
    elif [ "$n" -gt 1 ]; then
      echo "Several Android devices are connected; choose one with ANDROID_SERIAL=<serial> or" >&2
      echo "TETHER_ANDROID_SERIAL in tools/local.env:" >&2
      printf '  %s\n' $devs >&2; exit 1
    fi
    ANDROID_SERIAL=$devs
  fi
  export ANDROID_SERIAL
}

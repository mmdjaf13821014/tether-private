#!/bin/zsh
# Builds, signs and installs the iPhone / iPad app on your device. With a free Apple account the signing lasts
# 7 days: run this again before it runs out (the app's data and pairing are kept).
#   tools/reinstall-ios.sh            build and install; renew the signing profile when it has < 6 days left
#   tools/reinstall-ios.sh --force    renew the signing profile now
# Needs: Xcode signed in to your Apple account (Xcode › Settings › Accounts), ios/Config/Local.xcconfig with your
# team and bundle id (docs/BUILD.md), the device unlocked and connected (cable, or the same Wi-Fi once paired in
# Xcode). With several devices connected, set TETHER_IOS_DEVICE in tools/local.env or pick one when asked.
# Log: ~/Library/Logs/tether-reinstall.log

ROOT=${0:A:h:h}
[[ -f "$ROOT/tools/local.env" ]] && source "$ROOT/tools/local.env"
if [[ -z "$DEVELOPER_DIR" && -d /Applications/Xcode.app ]]; then export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer; fi
RENEW_WHEN_DAYS_LEFT=6
PROFILES="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
APP="$ROOT/ios/build/Build/Products/Debug-iphoneos/Tether.app"
LOG="$HOME/Library/Logs/tether-reinstall.log"
STAMP="$HOME/Library/Application Support/tether-reinstall.last"
mkdir -p "${STAMP:h}" "${LOG:h}"

log() { print -r -- "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; [[ -t 1 ]] && print -r -- "$*"; return 0; }
notify() { osascript -e "display notification \"$2\" with title \"Tether reinstall\" subtitle \"$1\"" >/dev/null 2>&1; }
fail() { log "FAILED: $1"; notify "Not reinstalled" "$1"; exit 1; }

# Days until the given .mobileprovision expires (negative when expired, -999 when unreadable).
days_left() {
  local exp epoch
  exp=$(security cms -D -i "$1" 2>/dev/null | plutil -extract ExpirationDate raw - 2>/dev/null) || { print -- -999; return; }
  epoch=$(date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$exp" +%s 2>/dev/null) || { print -- -999; return; }
  print -- $(( (epoch - $(date +%s)) / 86400 ))
}

force=0; [[ "$1" == "--force" ]] && force=1
log "---- run start (force=$force)"
[[ -f "$ROOT/ios/Config/Local.xcconfig" ]] || fail "ios/Config/Local.xcconfig is missing: copy Local.xcconfig.example and set your team and bundle id."
command -v xcodegen >/dev/null && (cd "$ROOT/ios" && xcodegen generate -q >/dev/null 2>&1)

# 1. The device: TETHER_IOS_DEVICE, else the only iPhone / iPad devicectl knows, else ask.
DEVICE=$TETHER_IOS_DEVICE
if [[ -z "$DEVICE" ]]; then
  json=$(mktemp); xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1
  n=$(plutil -extract result.devices raw -o - "$json" 2>/dev/null || print 0)
  ids=(); names=()
  for (( i = 0; i < n; i++ )); do
    [[ "$(plutil -extract result.devices.$i.hardwareProperties.platform raw -o - "$json" 2>/dev/null)" == iOS ]] || continue
    ids+=("$(plutil -extract result.devices.$i.hardwareProperties.udid raw -o - "$json" 2>/dev/null)")
    names+=("$(plutil -extract result.devices.$i.deviceProperties.name raw -o - "$json" 2>/dev/null)")
  done
  rm -f "$json"
  if (( ${#ids} == 1 )); then DEVICE=$ids[1]
  elif (( ${#ids} == 0 )); then fail "No iPhone or iPad found. Connect it with a cable, unlock it and trust this Mac."
  elif [[ -t 0 ]]; then
    for (( i = 1; i <= ${#ids}; i++ )); do print -r -- "  $i) ${names[$i]}  (${ids[$i]})"; done
    read "pick?Which device? " ; DEVICE=$ids[$pick]
    [[ -n "$DEVICE" ]] || fail "No device chosen."
  else fail "Several devices found: set TETHER_IOS_DEVICE in tools/local.env."
  fi
fi

state=""
for try in {1..18}; do
  xcrun devicectl device info details --device "$DEVICE" --timeout 20 >/dev/null 2>&1
  state=$(xcrun devicectl list devices 2>/dev/null | grep "$DEVICE")
  [[ -n "$state" && "$state" != *"unavailable"* ]] && break
  (( try == 1 )) && [[ -t 1 ]] && print -r -- "Waiting for the device (unlock it; over Wi-Fi it must be on the same network as this Mac)…"
  sleep 5
done
[[ -n "$state" && "$state" != *"unavailable"* ]] || fail "Device not reachable after 90 s. Unlock it and connect it with a cable or to the same Wi-Fi."
log "device reachable"

# 2. Renew this app's signing profile before it runs out: with its local copy gone, Xcode fetches a fresh one.
#    Only profiles for this bundle id are touched.
BUNDLE=$(cd "$ROOT/ios" && xcodebuild -project Tether.xcodeproj -scheme Tether -configuration Debug -showBuildSettings 2>/dev/null \
  | awk -F' = ' '/ PRODUCT_BUNDLE_IDENTIFIER = / { print $2; exit }')
left=-999
[[ -f "$APP/embedded.mobileprovision" ]] && left=$(days_left "$APP/embedded.mobileprovision")
# No previous build (its folder was cleaned up): judge by this app's newest saved profile instead of renewing,
# since renewing needs the Apple account in Xcode and the saved profile usually still has days left.
if [[ ! -f "$APP/embedded.mobileprovision" ]]; then
  for p in "$PROFILES"/*.mobileprovision(N); do
    appid=$(security cms -D -i "$p" 2>/dev/null | plutil -extract Entitlements.application-identifier raw - 2>/dev/null)
    [[ -n "$BUNDLE" && "$appid" == *".$BUNDLE" ]] || continue
    d=$(days_left "$p"); (( d > left )) && left=$d
  done
fi
log "this app's signing profile has $left day(s) left"
moved=()
if (( force || left < RENEW_WHEN_DAYS_LEFT )); then
  mkdir -p "$PROFILES/old"
  for p in "$PROFILES"/*.mobileprovision(N); do
    appid=$(security cms -D -i "$p" 2>/dev/null | plutil -extract Entitlements.application-identifier raw - 2>/dev/null)
    [[ -n "$BUNDLE" && "$appid" == *".$BUNDLE" ]] && { mv "$p" "$PROFILES/old/"; moved+=("${p:t}"); }
  done
  rm -rf "$APP"
  log "requesting a fresh profile (${#moved} old one(s) set aside)"
fi
restore_profiles() { for p in $moved; do [[ -f "$PROFILES/old/$p" ]] && mv "$PROFILES/old/$p" "$PROFILES/"; done; }

# 3. Build and sign. First with the paid-team entitlements in Tether.entitlements (Communication Notifications:
#    sender's picture in message notifications; Default Calling App: tel: links open this app); a free Apple account
#    can have neither, so the build is repeated without them.
build() {   # $1 = comm | plain
  local -a extra
  [[ "$1" == comm ]] && extra=(TETHER_ENTITLEMENTS=Tether/Resources/Tether.entitlements TETHER_COMM_NOTIFICATIONS=YES)
  [[ "$1" == plain ]] && extra=(TETHER_ENTITLEMENTS= TETHER_COMM_NOTIFICATIONS=NO)
  out=$(cd "$ROOT/ios" && xcodebuild -project Tether.xcodeproj -scheme Tether -configuration Debug \
    -destination "id=$DEVICE" -destination-timeout 60 -derivedDataPath build -allowProvisioningUpdates -skipMacroValidation \
    "${extra[@]}" build 2>&1)
  [[ "$out" == *"BUILD SUCCEEDED"* ]]
}
network_refused() { [[ "$out" == *"403"* || "$out" == *"Unable to log in"* || "$out" == *"Communication with Apple failed"* ]]; }

variant=comm
if ! build comm; then
  print -r -- "$out" | grep -E "error:|403|Unable to log in" | head -8 >> "$LOG"
  if network_refused; then restore_profiles; fail "Apple's signing service could not be reached or refused the request (network or account)."; fi
  log "build with the paid-team entitlements (notifications, default calling app) failed: building without them"
  variant=plain
  if ! build plain; then
    print -r -- "$out" | grep -E "error:|403|Unable to log in" | head -8 >> "$LOG"
    restore_profiles
    # Renewal needs the Apple account in Xcode › Settings › Accounts. Without it, sign with the profile still on disk
    # while it lasts, and say when it runs out.
    if (( ${#moved} )) && (( left > 0 )) && [[ "$out" == *"No Accounts"* || "$out" == *"No profiles for"* ]]; then
      log "could not renew the profile (no Apple account in Xcode): signing with the current one, $left day(s) left"
      renew_warning="Sign in to Xcode (Settings › Accounts) before the profile runs out in $left day(s)."
      moved=()
      if ! build plain; then
        print -r -- "$out" | grep -E "error:|403|Unable to log in" | head -8 >> "$LOG"
        fail "Build failed. See ~/Library/Logs/tether-reinstall.log"
      fi
    else
      network_refused && fail "Apple's signing service could not be reached or refused the request (network or account)."
      fail "Build failed. See ~/Library/Logs/tether-reinstall.log"
    fi
  fi
fi
rm -rf "$PROFILES/old"

# 4. Install (keeps the app's data and pairing).
inst=$(xcrun devicectl device install app --device "$DEVICE" "$APP" 2>&1)
[[ "$inst" == *"App installed"* ]] || { print -r -- "$inst" | tail -5 >> "$LOG"; fail "Install failed. Unlock the device and keep it near the Mac."; }

left=$(days_left "$APP/embedded.mobileprovision")
print -r -- "$(date +%F)" > "$STAMP"
print -r -- "$variant" > "$STAMP.variant"
if [[ "$variant" == comm ]]; then
  summary="Installed with sender pictures in notifications and as a possible default calling app. Valid $left day(s)."
else
  summary="Installed without sender pictures in notifications or the default calling app (both need a paid Apple developer account). Valid $left day(s)."
fi
[[ -n "${renew_warning:-}" ]] && summary="$summary $renew_warning"
log "OK: $summary"
print -r -- "$summary"
notify "Reinstalled" "$summary"
exit 0

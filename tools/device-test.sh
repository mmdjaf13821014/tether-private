#!/bin/zsh
# On-device checks with the Android phone attached (adb). Only Tether's own test line and test numbers are used:
# no real call, no SMS, nothing written to the phone's call log, contacts or messages.
#   tools/device-test.sh                 call matrix on the Android phone (in-process test client): answer, decline,
#                                        remote hang-up, outgoing; a dry sync; the phone-tools state
#   tools/device-test.sh --iphone [s]    also one test call through the linked iPhone: the app is relaunched with
#                                        auto-answer and hangs up after s seconds (default 20). It RINGS the iPhone,
#                                        so run it only when that is fine. Needs the iPhone on the same Wi-Fi or USB.
# Install the build you want to test first (docs/BUILD.md, tools/reinstall-ios.sh). Exit status 1 when a check fails.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
iphone=0; secs=20
while (( $# )); do
  case $1 in
    --iphone) iphone=1; [[ ${2:-} == <-> ]] && { secs=$2; shift } ;;
    -h|--help) sed -n 2,10p "$0"; exit 0 ;;
    *) print -u2 "unknown option $1"; exit 2 ;;
  esac
  shift
done
[[ -z ${DEVELOPER_DIR:-} && -d /Applications/Xcode.app/Contents/Developer ]] &&
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
dbg() { tools/dbg.sh "$@"; }
failed=()
check() {   # check <name> <ok:0|1> <detail>
  if [[ $2 == 1 ]]; then print "• $1 … ok  $3"; else print "• $1 … FAILED  $3"; failed+=("$1"); fi
}

state=$(dbg state) || { print -u2 "The Android phone did not answer (adb, app installed?)."; exit 1; }
print -- "$state" | grep -q '"ble_running":true' || print "  note: Bluetooth service not running on the Android phone"

for mode in answer decline remote outgoing; do
  out=$(dbg loopback_call $mode)
  case $mode in
    decline) ok=$(print -- "$out" | grep -qE 'states=\[ringing, ended\]' && echo 1 || echo 0) ;;
    *) down=$(print -- "$out" | grep -oE 'downFrames=[0-9]+' | cut -d= -f2); up=$(print -- "$out" | grep -oE 'upFrames=[0-9]+' | cut -d= -f2)
       ok=$([[ -n $down && -n $up && $down -ge 240 && $up -ge 240 ]] && echo 1 || echo 0) ;;
  esac
  check "test-line call: $mode" $ok "$(print -- "$out" | grep -oE 'states=\[[^]]*\]|downFrames=[0-9]+ upFrames=[0-9]+|ring→iPhone=[0-9.]+ ms|end→ended=[0-9.]+ ms' | tr '\n' ' ')"
done

sync=$(dbg loopback)
check "dry sync (handshake, texts, calls, contacts)" $(print -- "$sync" | grep -q 'handshake ok' && print -- "$sync" | grep -q 'sms.batch' && echo 1 || echo 0) \
  "$(print -- "$sync" | grep -oE 'items=\{[^}]*\}')"
check "carrier-code check never sends" $(dbg ussd_test '*100#' | grep -q 'sent=false' && dbg ussd_test 'hello' | grep -q 'valid=false' && echo 1 || echo 0) ""
check "phone tools in status" $(dbg state | grep -q '"tools":{' && echo 1 || echo 0) ""

if (( iphone )); then
  dev=$(grep -E '^TETHER_IOS_DEVICE=' tools/local.env 2>/dev/null | cut -d= -f2- | tr -d '"')
  bid=$(grep -E '^TETHER_BUNDLE_ID' ios/Config/Local.xcconfig 2>/dev/null | sed 's/.*= *//;s/ //g')
  if [[ -z $dev || -z $bid ]]; then
    check "iPhone test call" 0 "(set TETHER_IOS_DEVICE in tools/local.env and TETHER_BUNDLE_ID in ios/Config/Local.xcconfig)"
  else
    log=$(mktemp)
    (timeout $((secs + 50)) xcrun devicectl device process launch --console --device "$dev" --terminate-existing "$bid" \
      -- -TetherAutoAnswer -TetherAutoHangup $secs >"$log" 2>&1 &)
    for i in {1..40}; do sleep 1; dbg state | grep -q '"link_up":true' && break; done
    sleep 3
    dbg fake_incoming ring-iphone +10000000001 >/dev/null
    sleep $((secs + 9))
    [[ $(dbg state | grep -oE '"calls":[0-9]+' | cut -d: -f2) != 0 ]] && dbg fake_end >/dev/null
    line=$(dbg linklog 8 | tr '|' '\n' | grep 'call audio: down [0-9]* frames' | tail -1)
    down=$(print -- "$line" | grep -oE 'down [0-9]+ frames' | grep -oE '[0-9]+'); up=$(print -- "$line" | grep -oE 'up [0-9]+ frames' | grep -oE '[0-9]+')
    dropped=$(print -- "$line" | grep -oE 'uplink dropped [0-9]+' | grep -oE '[0-9]+$')
    heard=$(grep -oE 'playout delay [0-9]+ ms[^,]*|concealed [0-9]+ ms' "$log" | head -2 | tr '\n' ' ')
    check "iPhone test call (audio both ways)" $([[ -n $down && -n $up && $up -ge $((secs * 40)) && ${dropped:-0} -le $((up / 50)) ]] && echo 1 || echo 0) \
      "down ${down:-?} up ${up:-?} uplink dropped ${dropped:-?}; iPhone: ${heard:-no console output}"
    rm -f "$log"
  fi
fi

dbg only off >/dev/null
print
if (( ${#failed} )); then print "FAILED: ${(j:, :)failed}"; exit 1; fi
print "All device checks passed."

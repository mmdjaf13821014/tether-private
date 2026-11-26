#!/bin/sh
# Sends a debug command to the Tether app (adb-only hook) and prints its answer.
#   tools/dbg.sh state | pair_code | loopback | probe | helper_start | helper_quit | helper_cmd
#   tools/dbg.sh fake_incoming [ring-iphone] [num] | fake_outgoing [ring-iphone] | fake_end | dryrun_on | dryrun_off
#     (test line only; ring-iphone lets the ring / call reach a linked iPhone on purpose)
#   tools/dbg.sh loopback_call [answer|decline|remote|outgoing|selftest|during-sync] | loopback_handover   (test line only)
#   tools/dbg.sh adb_pair <code> | adb_pair_clear | adb_shell "<cmd>"
#   tools/dbg.sh net [data|hotspot|bt on|off] | airplane on|off | share on|off|loopback|noloopback|state
#   tools/dbg.sh drop (close live sessions from the Android side)
#   tools/dbg.sh inject_ms 20..200|off (uplink playback buffer cap for the next calls; default 40)
#   tools/dbg.sh inject auto|intercept|telephony_tx|policy_mix|state (uplink injection method for the next calls)
#   tools/dbg.sh mic_mute on|off|state (mute the A54's own mic during bridged calls; default off; prints the uplink check)
#   tools/dbg.sh mic auto|mute|open|state [keep] (the A54's own mic during bridged calls: auto = a redirect audio mode, else the vendor mic key; keep: do not forget what was remembered)
#   tools/dbg.sh linklog [n] | only none|off | forget_kind <kind> | contact_test | test_contact +1000000000x | star <id> on|off
#   tools/dbg.sh test_sms +10000000001 "text" | test_sms_delete <id|thread:N> | test_call +10000000001 | test_call_delete <id>
#   tools/dbg.sh block +10000000001 on|off | mute <thread> on|off | lists      (test numbers +1000000000x only)
#   tools/dbg.sh ussd_test '*123#' (dry: validates and logs, never sends) | phone_find on|off (rings the A54!) | ringer (state only)
#   tools/dbg.sh ext (extensions, what each client announced, counters) | ext_echo [data] (x.tether.echo round trip)
#   tools/dbg.sh report (build the problem report of Help › Send a problem report in the app's cache: path, size, outline)
#   tools/dbg.sh report_lines [from] [n] (print lines of the last report, " | " between lines)
. "$(dirname -- "$0")/lib.sh"; pick_android
cmd=${1:-state}; shift 2>/dev/null || true
extra=""
case "$cmd" in
  adb_pair) extra="--es code $1";;
  adb_shell) extra="--es sh '$1'";;
  fake_incoming) [ "$1" = "ring-iphone" ] && { extra="--es allow iphone"; shift; }; [ -n "$1" ] && extra="$extra --es num $1";;
  fake_outgoing) [ "$1" = "ring-iphone" ] && extra="--es allow iphone";;
  net) [ -n "$1" ] && extra="--es what $1 --es on ${2:-on}";;
  net_datasub) extra="--es sub $1";;
  share) extra="--es on ${1:-state}";;
  sms_read) extra="--es thread $1 --es upTo $2";;
  test_sms) extra="--es num $1 --es body '${2:-Tether test}' --es out ${3:-0}";;
  test_sms_delete|test_call_delete) extra="--es what $1";;
  test_call) extra="--es num $1";;
  block) extra="--es num $1 --es on ${2:-on}";;
  ussd_test) extra="--es code '$1'";;
  phone_find) extra="--es on ${1:-off}";;
  mute) extra="--es thread $1 --es on ${2:-on}";;
  loopback_call) if [ "$1" = "during-sync" ]; then extra="--es sync full"; elif [ -n "$1" ]; then extra="--es mode $1"; fi;;
  only) extra="--es kind ${1:-off}";;
  inject_ms) extra="--es ms ${1:-off}";;
  inject) extra="--es m ${1:-state}";;
  mic_mute) extra="--es on ${1:-state}";;
  mic) extra="--es on ${1:-state}${2:+ --es keep $2}";;
  forget_kind) extra="--es kind $1";;
  linklog) extra="--es n ${1:-120}";;
  report_lines) extra="--es from ${1:-0} --es n ${2:-200}";;
  ext_echo) [ -n "$1" ] && extra="--es data '$1'";;
  airplane) extra="--es on ${1:-on}";;
  test_contact) extra="--es num $1";;
  star) extra="--es id $1 --es on ${2:-on}";;
  test_contact_delete) extra="--es id $1";;
esac
adb shell "am broadcast -n app.tether.android/app.tether.link.DebugReceiver -a app.tether.DEBUG --es cmd $cmd $extra" \
  | sed -n 's/.*data="\(.*\)"$/\1/p'

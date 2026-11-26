# Features by Android version

Tether's phone app installs on Android 8.0 (API 26) and later; the Android client app needs Android 10. What works
depends on the version, mostly because the shell user (which the audio helper runs as) is given more permissions by
newer Android releases. Only the Samsung Galaxy A54 on
Android 17 has been tested; the other rows follow from the Android sources and API levels, and are expected, not tried.

| Feature | Android 8.0-9 | Android 10 | Android 11-12 | Android 13+ |
| --- | --- | --- | --- | --- |
| Link to the iPhone (L2CAP, GATT fallback), pairing | GATT only (L2CAP channels need Android 10): works, with less throughput | yes | yes | yes |
| Messages, contacts, call log, notifications | yes | yes | yes | yes |
| Answering, placing and ending calls from the iPhone (also hold, DTMF) | yes | yes | yes | yes |
| Default phone app request | Telecom's "change default dialer" dialog | dialer role | dialer role | dialer role |
| Call audio on the iPhone | **no** (the shell cannot capture a call); the iPhone says so in Link › Call audio | **no** (the shell cannot capture a call) | best effort: captures the voice call, injects through the incall-music path (`voice_downlink` / `voice_call`, `telephony_tx`); works only where the phone's audio HAL allows it | yes: call-audio interception API, older routes as fallback |
| Muting the phone's own earpiece during a bridged call | no (nothing is bridged) | Samsung only | Samsung keys, else the voice-call stream | Samsung keys, else the voice-call stream |
| Taking the phone's own microphone out of a bridged call (setting "Off during calls", the default) | no | no | no: the redirect modes need Android 13; the microphone stays on (or the opt-in mute) | yes where the phone's audio system allows it: `MODE_CALL_REDIRECT` (then `MODE_CALL_SCREENING` where declared), set by the helper and checked in each call; else the microphone stays on |
| Audio helper (mobile data, data SIM, airplane mode, network mode) | optional, needs the TCP adb path; mobile data and data SIM expected to work, airplane mode and network mode may report an error (their shell commands are newer) | needs the TCP adb path (see [SETUP.md](../SETUP.md)); hotspot and Bluetooth tethering switches are not available | yes | yes |
| Wireless debugging pairing | not before Android 11: use TCP adb | not in Android 10: use TCP adb | yes (needs a Wi-Fi network) | yes |
| Hotspot and Bluetooth tethering switches | no (hidden) | no (hidden) | yes | yes |
| Signal and network type per SIM | yes (network type from the plain data type) | yes (network type from the plain data type) | yes | yes |
| SIM phone number in the status | from the SIM record, often empty | from the SIM record, often empty | from the SIM record | from the subscription service |
| Call notification | plain notification with buttons | plain notification with buttons | plain with buttons (11) / call style (12) | call style |

Notes:

- The redirect audio modes (`AudioManager.MODE_CALL_REDIRECT` / `MODE_COMMUNICATION_REDIRECT`, Android 13;
  `MODE_CALL_SCREENING`, Android 11, only where the audio policy declares `call_screen_mode_supported`): AOSP's audio
  policy routes a call to the phone's own microphone and earpiece only in `MODE_IN_CALL`, while the interception API
  stays usable in `IN_CALL`, `CALL_SCREENING` and `CALL_REDIRECT`. Setting `IN_CALL` / `CALL_REDIRECT` /
  `COMMUNICATION_REDIRECT` needs `MODIFY_PHONE_STATE`, which the shell has; the newest privileged request owns the mode,
  and it returns to Telecom's when the helper withdraws it or dies. What the vendor's audio HAL does in these modes is
  not specified, so Tether checks each phone (MicPath / RedirectGuard in the helper) and remembers the answer.

- Below Android 12 the Bluetooth runtime permissions do not exist; Tether uses the install-time Bluetooth
  permissions. Android 13 added the notification permission.
- Android 8 and 9 have no L2CAP connection-oriented channels: the phone answers the PSM read with an empty value and
  the iPhone (or Android client) uses the GATT transport at once. Calls and texts work the same; large syncs take longer.
- Before Android 10 a call's direction is not reported by Android; Tether takes it from the state the call was first
  seen in (connecting / dialing: outgoing; new / ringing: incoming). A call already running when the app restarts
  counts as incoming.
- Nothing the app does needs the audio helper on Android 8 to 10: texts, contacts, call log and call control work
  without it.
- On Android 8 to 10 a call's SIM is found by the SIM's card id; if the phone does not expose it, the SIM choice for
  placing a call may fall back to the default.
- The app, the helper and `./gradlew :app:lintRelease :helper:lintRelease` treat calls to newer APIs without a version
  check as errors, so a regression shows up in the build.

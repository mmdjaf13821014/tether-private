# Device notes: Samsung Galaxy A54 (SM-A546E), the reference test phone

Measured on 2026-11-21 over USB `adb`, with `tools/spike.sh probe`.

| Item | Value |
|---|---|
| Build | Android 17 (SDK 37), One UI, `CP2A.260605.016.A546EXXUNGZJ5`, Exynos 1380 (`s5e8835`) |
| SIMs | 2 loaded (dual SIM) |
| Default dialer / SMS app | `com.samsung.android.dialer` / `com.google.android.apps.messaging` |
| Wireless debugging (`adb_wifi_enabled`) | 1 (on) |

**Shell user (uid 2000) permissions that matter** (`dumpsys package com.android.shell`):
`CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`,
`CAPTURE_MEDIA_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `MODIFY_AUDIO_SETTINGS_PRIVILEGED`,
`MODIFY_PHONE_STATE`, `CONTROL_INCALL_EXPERIENCE`, `RECORD_AUDIO`, `WRITE_SECURE_SETTINGS`,
`MANAGE_ROLE_HOLDERS`, `BLUETOOTH_PRIVILEGED`.

**Audio policy** (`dumpsys media.audio_policy`): device ports `Telephony Tx`
(`AUDIO_DEVICE_OUT_TELEPHONY_TX`) and `Telephony Rx` (`AUDIO_DEVICE_IN_TELEPHONY_RX`), and an
output profile `incall_music_uplink` with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` whose sink is
Telephony Tx. That is the hardware path for injecting audio into the call uplink.

**Helper probe as shell, no call active** (`app_process`, see `android/helper`):

| Method | Result |
|---|---|
| `AudioManager.isPstnCallAudioInterceptable()` | **true** |
| capture `intercept` (`getCallDownlinkExtractionAudioRecord`) | refused: "not available in mode MODE_NORMAL" (needs a live call) |
| capture `voice_downlink` (`AudioSource.VOICE_DOWNLINK`) | opens and records (silence, no call) |
| capture `voice_call`, `voice_uplink` | open and record |
| capture `telephony_rx` (preferred device Telephony Rx) | opens, all zeros |
| inject `intercept` (`getCallUplinkInjectionAudioTrack`) | refused: "not available in mode MODE_NORMAL" (needs a live call) |
| inject `telephony_tx` (AudioTrack, preferred device Telephony) | plays, routed to device type 18 (Telephony) |
| inject `policy_mix` (dynamic AudioPolicy mix → Telephony Tx) | plays, routed to device type 18 |

**Conclusion.** The A54 supports Android's system call-audio interception API, which is
exactly downlink extraction + uplink injection for PSTN calls, and the shell user holds its
permission. It is the first method the helper tries; `voice_downlink` / `telephony_tx` /
`policy_mix` are fallbacks. What is still unproven is the audio content during a real call:
no call was placed (no test number was available). Run `tools/spike.sh selftest` during a
call, or the in-app self-test, which plays a 1 kHz tone through each inject method while
recording `VOICE_UPLINK` and detects it with a Goertzel filter.

Samsung-specific workaround needed: the fake `ActivityThread` needs a
`ConfigurationController`, otherwise `CompatSandbox` crashes while creating the system context.

## Call-audio measurements (2026-11-23, test line)

The app's self-managed test line runs the interception API on its **VoIP path** (audio mode IN_COMMUNICATION: the
record and track are remote-submix policy mixes), not the PSTN path a real call uses. Its capture comes in bursts
(backlog up to 160 ms, average 40-55 ms), the inject track reports 256 ms of latency, and `VOICE_UPLINK` does not
hear the injected tone (the self-test's inject checks fail there). So loopback figures for capture and inject do not
carry over to real calls; codec, link and Telecom figures do:

| Figure | Value |
| --- | --- |
| Ring → client (Telecom to the hub) | 160-250 ms |
| End → ended | 13-19 ms |
| Opus encode / decode, 20 ms frame (Concentus, complexity 3) | ~3 ms (~0.9 ms once the CPU has ramped up) / ~0.5 ms |
| Inject buffer cap 40 ms (default) / 20 ms | 0 underruns / 60-70 underruns and ~95 dropped uplink frames per 5 s call: keep 40 |
| LE PHY on the owner's link | 2M |
| Voice-call stream mute as shell (`HelperMain silence on/off` with `TETHER_SILENCE=other`) | works (index 0, restored) |
| Inject track's sink on the test line, nothing reading it / read as the far end (the helper does since 2026-11-23) | 14.4 kHz for 16 kHz (10 % slow: the uplink backed up and frames were dropped) / 16.0 kHz |
| Mixer period of the inject path: test line (remote submix) / real call (Telephony Tx, `dumpsys media.audio_flinger`) | 32 ms (512 frames at 16 kHz) / 20 ms (960 frames at 48 kHz, HAL 10 ms) |
| Capture clock (interception record), test line | 16.0 kHz against the monotonic clock |
| iPhone uplink that arrived before the interception path was open (it refuses until the audio mode switches) | 0.3-1 s polled every 150 ms; 0.15-0.3 s polled every 30 ms (no longer forwarded) |

Real-iPhone test calls on the reference link (2026-11-23, 20 s, Bluetooth tethering on, room noise): connection
interval 15 ms, latency 1, PHY 2M; 5-10 hold-ups of 60-77 ms per call in each direction (one of 119 ms); downlink
arrival jitter ~15 ms, uplink ~10 ms. The iPhone's clock against the Android phone's capture: within ±100 ppm.

| Uplink (A54 helper) | Before | After |
| --- | --- | --- |
| Voice frames lost (dropped / trimmed) of ~975 | 42-54 / 13-17 | 0 |
| Delay ahead of a frame (queue + track) | queue held full by the slow test-line sink | 64-71 ms average (incl. the start), target 41-60 |
| Inject track underruns | 0 (the queue never emptied) | 3-9 per call, about half in the first second |

| Downlink (iPhone playout) | Before | After |
| --- | --- | --- |
| Reported target | 105-110 ms | 65-77 ms |
| Playout delay (mean buffer level) | not measured | 44-46 ms |
| Concealed / underruns | 0 / 0 (Link log) | 0.3-1.1 % / 0-1 |
| Drained in quiet frames per call | 275-295 ms | 340-470 ms (start-up burst, gaps' leftovers) |

## Redirect audio modes (2026-11-23, issue #2)

Asked for by the helper as the shell user (`HelperMain mode <n>`, and during test-line calls); no real call was
placed, so what the Samsung audio HAL does with a live PSTN call in these modes is still open (the self-test's
`mic:call_redirect` row during a real call answers it).

| Check | Result |
| --- | --- |
| `isCallScreeningModeSupported()` | true |
| `setMode(MODE_CALL_REDIRECT)` / `MODE_CALL_SCREENING` / `MODE_COMMUNICATION_REDIRECT` from the shell, no call | accepted in 4-6 ms (the shell's request owns the mode), released to `MODE_NORMAL` |
| Same during a test-line call (Telecom's `MODE_IN_COMMUNICATION`) | `COMMUNICATION_REDIRECT` accepted; Telecom did not take it back during the call; the helper's running interception record and track kept going (251/251 frames both ways); withdrawn at the call's end, mode back to Telecom's |
| Interception API opened inside `MODE_CALL_REDIRECT` with no call | record and track open (track routed to Telephony, type 18); on a second try opening the record there blocked, so the helper opens both in the call's own mode first and only then switches |
| `getMode()` in a process whose context targets Android 10 or lower | reports `CALL_SCREENING` as `IN_CALL` (AudioManager compatibility): the helper's context reports the current SDK |
| Self-test on the test line | `mic:communication_redirect` accepted, "unverified" (VoIP path: `VOICE_UPLINK` shows neither the mic nor the tone, the test line's downlink is silent) |

## Real calls (2026-11-23/24, the owner's calls)

What the owner's own calls showed (link logs), and what the helper does with it since 1.2.0:

| Finding | Since 1.2.0 |
| --- | --- |
| `MODE_CALL_REDIRECT` and `MODE_CALL_SCREENING` on a real PSTN call: the downlink went silent within 3 s each time (`breaks-downlink`, remembered) | Not used again on this model; the default setting falls through to Samsung's tx key in the same call |
| `VOICE_UPLINK` does not reliably show the injected voice: with the tx key on it heard the iPhone once (`uplink check ok`, 11-24) and not at all in another call (silent with and without the key) | A check that finds the capture blind no longer leaves the mic open: on Samsung (Exynos) the tx key goes back on and the model is remembered as `unverified` (no more checks) |
| Samsung's tx key (`l_voice_tx_control_mode=1`) without Android's mic mute: the far end heard the iPhone on every real call of 11-24 | The default setting uses it automatically when no redirect mode holds (`CallAudio.Silence.SAMSUNG.blindSafe`) |
| Outgoing calls: `MODE_NORMAL` for seconds of dialing, the interception API refusing; after the old 4 s wait the helper fell back to `voice_downlink` / `telephony_tx` (4 downlink frames in the call) and the app restarted the helper up to 3 times (five starts in one second) | The paths open only in a call's audio mode (retried quietly meanwhile), no fallback for that refusal, one restart at most |
| The iPhone's uplink during dialing piled up (the modem takes nothing before the call connects): 0.5-1 s ahead, 232 of 308 frames dropped in one call, inject clock 15.2-15.7 kHz measured over dialing | The uplink is dropped until the call is active and starts afresh then (`uplinkBeforeActiveFrames` in the figures) |
| With Bluetooth tethering on, the uplink from the iPhone had up to 253 long gaps per call, worst 814-880 ms | Hold-ups every second raise the uplink target to 60 ms (else 45); what a stall delivers late is cut at once (`late cut`) instead of over the next minute |

On 2026-11-25 the test line's capture showed a 55 ms standing backlog and a 15.77 kHz capture clock with both the 1.1.0
build and 1.2.0 (16/92 ms and 16.0 kHz on 11-23): the phone's state, not the build.

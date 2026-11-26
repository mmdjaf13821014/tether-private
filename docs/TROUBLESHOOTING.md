# Troubleshooting

Start with the logs: on the iPhone, **Link › Link log** (share button at the top); on the Android phone, **Call audio ›
Link log** (Share or Copy; in 1.2.1 and older only `tools/dbg.sh linklog` with USB debugging). To report a problem,
send a [problem report](#what-to-include-in-a-report) from each app instead: it holds the logs and much more. They show every connect, drop (with Bluetooth's reason code) and setup step.

## The iPhone does not link

| Symptom | What to do |
| --- | --- |
| Link tab says *Searching* for a long time | Bluetooth on both phones; the Android phone's Tether notification should say *Advertising, waiting for iPhone*. If it says *Bluetooth off* or nothing, open Tether on the Android phone once |
| "The Android phone does not recognise this iPhone" | The pairing was removed on the Android phone: pair again (SETUP.md step 2) |
| "Pairing code expired" | The QR code is valid for 5 minutes and once only, and is lost if Tether restarts: tap Pair iPhone again for a new one |
| Links, then drops every few seconds | Check the link logs for the reason. Battery savers: give Tether unrestricted battery use on Android |
| Nothing after an update of the Android app | Open Tether on the Android phone once (some phones do not restart its service after an update) |

### Bluetooth tethering

Bluetooth tethering pairs the two phones over classic Bluetooth. iOS then runs Tether's GATT connection over that
classic link whenever it is the only one, where Tether's fast L2CAP channel cannot open, and moves it to Bluetooth LE
as soon as an LE link appears, which ends the session running at that moment. To keep one stable session, the Android
app holds an LE link to the iPhone while a session is up (link log: *LE link held*), so iOS keeps it on LE and
L2CAP and nothing moves underneath it. If a session does start over the classic link, the first LE link ends it
once and the iPhone sets up again over LE within seconds.

Tether never uses Bluetooth's own encryption or pairing, so the pairing made for tethering cannot break it. Older
versions failed in this setup with *"PSM read failed: the encryption key size is insufficient"*, or connected and
dropped over and over: update both apps.

If it still drops while tethering, send both link logs (each session ends with a line saying its transport, how long
it lasted and why it ended) and use the **Wi-Fi hotspot** instead of Bluetooth tethering meanwhile: it does not
touch Bluetooth at all and is faster.

### After closing the app

- **Opened again**: the app links within a few seconds (about 1-3 s in tests): it starts a fresh connect every time
  it comes to the foreground.
- **Swiped away in the app switcher** (force quit): iOS does not start the app again for Bluetooth until you open
  it yourself, so calls and texts do not reach the iPhone meanwhile (calls still ring on the Android phone, and
  show on the iPhone as missed once it links). No app can change this. When iOS lets it, the app posts a
  "Calls paused" notification as it closes. Leave Tether in the background instead of swiping it away; it uses
  almost no battery while it waits.
- **Stopped by iOS** in the background (low memory): iOS starts it again when the Android phone brings a Bluetooth
  link up, which it tries every 5 s for a minute after the link was lost, then every 30 s. Usually seconds, but
  not guaranteed: open the app if calls stop arriving.
- **In the background**: the session stays up; nothing to do.

## No call audio

On Android 8 to 10 the phone cannot bridge call audio at all, and on Android 11 and 12 it works only if the phone's audio
system allows capturing the call: see [features by Android version](devices/android-versions.md). The Call audio
card in the Android app says which case you are in, and so does Link › Call audio on the iPhone.

1. In Tether on the Android phone, the **Audio helper** must say *Running*. If not, see the next section.
2. During a call, tap **Run call-audio self-test**: it lists each capture and inject path and whether it works.
3. The Android version must be 13 or later, and the build must let the shell user intercept call audio. Not every
   manufacturer allows it. If no path works, calls still work from the iPhone but their audio stays on the
   Android phone. Please report your model and the self-test result. When the self-test shows every `capture:` row
   with "only zeros" and the inject rows routed to the phone itself (its model name instead of the call), as on a
   Galaxy A73 5G with Android 16, the phone's software does not give Tether the call: from 1.2.2 Tether notices this
   in the first seconds of a call, gives the call back to the phone (earpiece and microphone on) and says
   "blocked" in Link › Call audio, until a system update or a self-test during a call hears the call.
   A call you answer or dial on the Android phone itself always stays on it.
   **Samsung phones with a Qualcomm chip (Galaxy A71, A73, Fold and others) on Tether 1.2.3 or older:** if calls on
   the phone itself also lost their sound, Tether's Samsung call keys or redirect mode had left the phone's voice
   channel stuck (`voice_start_usecase: cannot open device … Device or resource busy` in the phone's own log). Update
   to 1.2.4, which uses neither there, and restart the phone once.
4. You hear the other side on the Android phone too: Tether silences the phone's own earpiece during a bridged call
   with each vendor's controls: Samsung (tested), Qualcomm and MediaTek (from their audio sources, untested), the
   voice-call volume on other phones (on Samsung phones with a Qualcomm chip both). The phone's own microphone is
   taken out of the call automatically where the phone allows it safely (see the next section). The Link tab shows it
   under **Call audio › Android phone silent**.
5. The Link log says what the phone did not deliver: `the downlink capture … reads only zeros` or `delivered nothing`
   (the iPhone hears nothing: the phone does not give the call's audio to Tether), `the uplink injection … is not being
   taken by the phone` (the other side hears nothing of the iPhone), `routed …: NOT the call's uplink` in the
   `call audio path:` line (the iPhone's voice plays on the Android phone instead of going into the call), and
   `opened … s after the start` when the call took long to get its audio.

### Headset or car

- The call does not go to the headset or car: it must be connected as a hands-free (calls) device in the iPhone's
  Bluetooth settings; music-only (A2DP) speakers have no microphone and are not offered for calls.
- The Link log shows each route change as `audio route bluetooth / car / receiver / speaker` with its reason. If
  the route changes but the far end goes quiet for more than a second, please report the lines around it.

## The other side cannot hear me

You hear the other side, but they hear nothing (or only the Android phone's surroundings) of what you say on the
iPhone. The iPhone's voice is injected into the call's uplink by the Android phone. Things that can stop it:

- **The injection did not open, or does not go into the call.** Some phones refuse it, or refuse it until the call is
  connected (Tether waits for that), or play it on the phone's own speaker (the `call audio path:` line then says
  `NOT the call's uplink`, and a later line `is not being taken by the phone` when the phone takes nothing from it).
- **A way of keeping the phone's own microphone out of the call that the phone mishandles.** By default (**This phone's
  microphone during bridged calls: Off during calls**, Android app › Call audio, or the picker in the iPhone's Link
  tab) Tether tries, one after the other, what this phone model has not shown to fail: Android's redirect audio modes
  (Android 13+; the phone's own microphone and earpiece leave the call), then the phone maker's own microphone switch
  (Samsung, MediaTek), and otherwise leaves the microphone on. It never uses Android's general microphone mute on its
  own: on several phones that also mutes your voice. Each step is checked during the call: when the downlink goes
  silent, or your speech stops reaching the uplink, Tether undoes it and remembers that for the phone model (switching
  to another setting and back keeps that; choosing **Off during calls** again while it is chosen, or the self-test,
  tries the redirect modes anew). Some
  phones cannot show their uplink to this check: a redirect mode is then not used at all (not even for the rest of that
  call, and no other untested mode is tried after it), and the maker's switch is
  kept only where it is known to keep your voice (Samsung's, verified on real calls); otherwise the microphone stays
  on rather than risk a silent call.
- **A route into the call that the phone's audio configuration does not wire up.** On some Qualcomm phones (seen
  on a Redmi Note 8 with a custom Android 16) the interception API's uplink plays into a mixer path that does not
  exist, so the other side hears only the Android phone's own microphone (you are heard only when it lies close).
  Tether detects this from the vendor's mixer configuration and sends your voice through the in-call music route
  instead; the `call audio path:` line then says `via telephony_tx (… not via intercept: this phone's audio
  configuration has no afe-proxy-playback path …)`. The self-test cannot see this case: put the Android phone in
  another room and check that the other side still hears you.
- **The Android phone's microphone mute** (only with **Muted**). On several phones (MediaTek for certain, Qualcomm
  audio systems) muting the phone's microphone also mutes the injected voice. Where the phone maker has its own
  microphone switch Tether uses that; on Qualcomm phones without one (Xiaomi, Redmi, Poco, OnePlus, Samsung with a
  Qualcomm chip and others) Android's microphone mute is never used, since the uplink check could not see that it
  silences your voice: the microphone stays on and the Android app says so under **Muted**. Elsewhere Tether checks
  during each call, turns the mute off when your voice does not get through and remembers it.
- **The iPhone app lost its CallKit registration** (before 1.2.5): calls dialed on the iPhone
  end at once and incoming calls ring but stay silent both ways until Tether is force-quit. iOS had restarted its
  call service under the running app. Versions from 1.2.5 on set CallKit up again by themselves (`CallKit: … provider
  set up again` in the iPhone's link log); with an older one, swipe Tether away in the app switcher and open it again.
- **The iPhone sends silence**: Tether may not use the iPhone's microphone (Settings › Privacy & Security ›
  Microphone), or the call is muted on the iPhone. Tether asks for the microphone once it is paired; if that was
  refused, the call screen says so in yellow, the Link tab in red (**Your voice in the call** then reads "Does not
  reach"), and a call answered from the lock screen brings a notification. A microphone that delivers only silence
  for several seconds of a connected call is reported the same way, and the Link log has a `microphone:` line.

With **On** the Android phone's own microphone stays in the call: the other side may hear your voice twice (the
phone in the room hears you too), the bag's noise, and their own voice from the iPhone's speaker. **Off during
calls** avoids that where the phone allows it.

What to do:

1. Make sure the setting is **Off during calls** (the default) and call again. If the other side still hears
   nothing, try **On**: if that helps, please report it (step 4).
2. During a call, on the iPhone: **Link › Call audio › Run call-audio self-test** (or the same button in the Android
   app). It pauses the call audio for a few seconds and plays tones the other side may hear. Its rows:
   - `call-audio`: how your call ran until then: the inject and capture methods (`NONE` when the injection did not
     open) and what the phone's microphone did (out of the call in which mode, muted, or on).
   - `inject:<method>`: a tone through each inject method, listened for in the call's uplink.
   - `mic:call_redirect` (and `mic:call_screening` where the phone declares it): the phone's microphone level, the
     tone and the downlink without and with that mode, and a verdict: `ok` (the microphone left the call, or the tone
     still gets through), `no-effect` (the microphone stays in the call), `breaks-uplink` / `breaks-downlink` (a
     direction went silent), `refused`, `unverified` (this phone shows neither). Tether remembers it for the model.
   - `inject:<method>, phone mic muted`: the working method once more with the microphone muted.
3. Open **Link › Link log** on the iPhone (or **Call audio › Link log** on the Android phone). Each call writes a line
   starting `call audio path:` (whether the uplink injection opened, with which method, what the microphone does),
   `call audio: call_redirect on` / `reverted` lines when the mode changed (with the reason; with the default setting
   the summary then says `then muted by the vendor key` or `then open`), `uplink check …` lines,
   `remembered for this phone …` when a result was stored, and a `call audio:` summary at the end with the uplink
   figures (`uplink speech N frames`: 0 means the iPhone sent only silence).
4. Report it (GitHub issue) with: the Android phone's model and Android version, the iPhone model and iOS version,
   the microphone setting, every self-test row (name, ✓ / ✗ and its note), and the `call audio path:`,
   `call audio: …` and `uplink check` lines of one failed call. Leave out phone numbers.

## The audio helper does not start

- Wireless debugging needs a Wi-Fi connection when the helper starts. Connect the Android phone to Wi-Fi, then tap
  **Start**.
- With TCP debugging (SETUP.md): the TCP mode is lost on every phone restart; connect USB and run `adb tcpip 5555`
  again, accept the key prompt, then tap **Start**. If you used another port, Tether only tries the one it was
  enabled with (default 5555).
- If the phone forgot the Wireless debugging pairing (some updates do), pair again (SETUP.md, Wireless debugging).
- From a computer, `tools/start-helper.sh` starts the helper over USB `adb` as a fallback.

## The iPhone app stopped opening

Apps signed with a free Apple account stop after 7 days. Run `tools/reinstall-ios.sh` again; your data and pairing
are kept. The Link tab shows how many days are left.

## Messages or calls are missing

Pull down to refresh in Messages, Recents or Contacts (syncs again with the Android phone). Texts deleted on the
Android phone disappear from the iPhone at the next sync.

## What to include in a report

Send a **problem report** from each app and attach the files to the issue. Make it right after the problem happened
(for call or audio problems, right after the call):

- **Android phone:** Tether › **Help** › **Send a problem report** › Share (a `.txt` file) or Copy.
- **iPhone:** **Link › Send a problem report**.
- **Tether Client:** **Link › Send a problem report**.

One file holds what is needed to find a problem with setup, pairing, link drops, calls, call audio, texts, contacts or
tethering: the app version, the phone and its Android version (and One UI on Samsung), each setup step, the link and
its transport, Bluetooth and tethering state, the SIMs' network and signal, the call-audio settings, what the phone
remembered and the last self-test, the link log and the app's log. On the Android phone it also has **call-audio
diagnostics** from the helper: the audio mode, the interception API, every active recording with whether Android
silences it (`isClientSilenced`), the call's audio routes and selected lines of `dumpsys audio` and
`dumpsys media.audio_policy`. The helper takes the same snapshot by itself when a call's audio path looks dead (only
zeros, nothing delivered, the uplink not taken) and at the end of every bridged call; the last three are in the report.
This tells "Android silences the recording" apart from "the phone delivers no call audio at all" (#13).

**Privacy.** Phone numbers, contact, SIM, carrier and device names, message text, e-mail addresses, full Bluetooth
addresses (only the last two bytes stay), IP addresses, IMEI- and serial-like numbers and keys are removed before
the file is written. Look through it before posting anyway.

Older versions without the report: the phone models, Android and iOS versions, whether Bluetooth tethering was on,
both link logs (the iPhone's **Link › Link log**, the Android phone's **Call audio › Link log**) and, for audio
problems, the self-test result.

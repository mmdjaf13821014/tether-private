# Architecture

```
 iPhone / iPad (Swift, ios/)                        Android phone (Kotlin, android/)
 ┌───────────────────────────────┐                 ┌──────────────────────────────────────┐
 │ SwiftUI app (ios/Tether)      │                 │ Tether app: default phone app        │
 │  Recents · Contacts · Keypad  │   Bluetooth LE  │  LinkService (foreground service)    │
 │  Messages · Link              │   L2CAP channel │  BlePeripheral · Connection · Hub    │
 │  CallKit · AVAudioEngine/Opus │ ◄─────────────► │  SMS / contacts / call log repos     │
 │                               │  (GATT fallback)│  Telecom InCallService · Share proxy │
 │ TetherCore (Swift package)    │   P-256 + AES-GCM│                    │ local socket,   │
 │  TetherLink (CoreBluetooth)   │                 │                    ▼ HMAC-auth        │
 │  handshake · records · types  │                 │  Shell helper (uid 2000, app_process)│
 └───────────────────────────────┘                 │   call-audio interception, switches  │
                                                   └──────────────────────────────────────┘
```

## Parts

| Part | Where | What it does |
| --- | --- | --- |
| Shared Android core | `android/core` | Protocol code used by both Android apps: framing, the handshake (both sides), sealed records, message types, G.711 and Opus (Concentus) |
| Android client | `android/client` | The iPhone app's job on a second Android phone: BLE central with L2CAP / GATT pipe and the iPhone's reconnect rules, self-managed Telecom calls, call audio with the ported playout buffer, the five tabs, notifications. See [ANDROID-CLIENT.md](ANDROID-CLIENT.md) |
| Android app | `android/app` | BLE peripheral and foreground service (`link/`), sync of SMS, contacts and call log (`data/`), Telecom: the default-dialer InCallService, call screening and a self-managed test line (`telecom/`), the audio bridge to the helper (`audio/`), in-app Wireless debugging pairing (`adb/`), the Share VPN proxy, its own small UI |
| Shell helper | `android/helper` | Started by the app as the shell user over the phone's own Wireless debugging. Captures the call downlink and injects the uplink through Android's call-audio interception API (with fallbacks), and changes network settings an app may not (mobile data, hotspot, tethering, airplane mode, network mode, data SIM) |
| iOS app | `ios/Tether` | SwiftUI: Recents, Contacts with Favourites, Keypad, Messages, Link. CallKit for calls, AVAudioEngine with voice processing and Opus for audio, local notifications |
| TetherCore | `ios/TetherCore` | Swift package with everything testable without UI: framing, the handshake, sealed records, message types, the CoreBluetooth central (`TetherLink`), the playout buffer, number matching and search, rich-text and one-time-code detection. Also a macOS test central (`tether-central`) and the vector generator |
| Protocol | `protocol/` | [PROTOCOL.md](../protocol/PROTOCOL.md): transport, handshake, records, every message; `vectors.json`: crypto and framing test vectors both sides reproduce |
| Tools | `tools/` | `reinstall-ios.sh` (build, sign, install), `dbg.sh` (adb debug hooks), helper scripts, a Mac test central |

## Data flow

- **Sync**: on connect the iPhone asks for everything newer than its last completed sync; the Android phone sends
  pages of texts, calls and contacts, then the ids that still exist (rows deleted on either side disappear), the
  block and mute lists, and which texts are unread. After that, changes are pushed live.
- **Calls**: Telecom tells the app's InCallService about every call; the hub sends `call.state` to the iPhone, which
  reports it to CallKit. Answer, end, hold and keypad go back the same way. The call audio is captured and injected
  by the helper, Opus-encoded by the app and carried on the link's audio channel.
- **Ownership**: the Android phone is the source of truth for texts, contacts, calls, read state, blocks and
  favourites. The iPhone keeps a snapshot for instant start and offline viewing.

## libopus on the iPhone

`ios/TetherCore/Sources/COpus` vendors libopus 1.5.2 (floating point, no assembly or intrinsics; `config/config.h` is
ours, `include/copus_shim.h` wraps the variadic `opus_*_ctl` calls for Swift). `LibOpusDecoder` / `LibOpusEncoder`
(`TetherCore/LibOpus.swift`) expose mono 16-bit decode with packet-loss concealment (`opus_decode` without data) and
VOIP encode. The tests round-trip a tone and decode both ways against AudioToolbox's `kAudioFormatOpus`.

## Transports

The primary transport is one L2CAP channel (LE credit-based). If it cannot be opened (for example when iOS reaches
the phone over classic Bluetooth because the phones are also paired for tethering), the same byte stream runs over
two GATT characteristics. Bluetooth link security is never used; security is end-to-end (see
[SECURITY.md](../SECURITY.md)). During a session the Android app holds an LE link to the iPhone, so the session stays on LE
and L2CAP even when the phones are also connected over classic Bluetooth for tethering (iOS would otherwise move
GATT between the links and drop the session). Between sessions it only pulses an LE link now and then, which lets
a waiting iPhone app connect (see protocol/PROTOCOL.md §1).

## Background behaviour

- Android: a foreground service with a connected-device type keeps advertising and the link alive; the phone ignores
  battery optimisation for Tether.
- iOS: CoreBluetooth background mode with state restoration and a pending connect; a GATT "wake" notification
  before urgent traffic (ringing, new text) wakes the suspended app.

## Debugging hooks

`tools/dbg.sh` sends commands to a broadcast receiver that only adb can reach (it requires the `DUMP` permission).
They include a loopback client that runs full calls on the app's self-managed test line, test texts and contacts
restricted to the numbers `+10000000001…9`, and the link log. A test client can never place a real call, send a
real text or touch a real call.

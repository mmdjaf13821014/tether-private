# The Swift SDK (TetherCore)

TetherCore is the Swift implementation of the Tether protocol: pairing codes, the handshake, sealed records, every
control message, call audio (Opus, G.711, the playout buffer), text helpers and, on Apple platforms, a complete
Bluetooth LE client (`TetherLink`). The iPhone app is built on it; you can build your own client, a test tool, a
bridge to another transport, or a phone-side implementation with it.

| | |
| --- | --- |
| Product | `TetherCore` (one library; libopus comes with it) |
| Swift | 5.9 or later (Linux is tested with Swift 6.0) |
| Platforms | iOS 16, macOS 13, tvOS 16, watchOS 9, visionOS 1, Linux (see the matrix below) |
| Dependencies | swift-crypto on platforms without CryptoKit; swift-docc-plugin for documentation only |
| Licence | MIT; libopus BSD-3-Clause (see NOTICE) |

## Install

In `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/amirwmr/tether", from: "1.2.0"),
],
targets: [
    .target(name: "MyClient", dependencies: [.product(name: "TetherCore", package: "tether")]),
]
```

In Xcode: File › Add Package Dependencies…, enter `https://github.com/amirwmr/tether`, add the `TetherCore` library.

The repository root holds the SDK's `Package.swift`. It builds the sources in `ios/TetherCore/Sources`, the same files
the iPhone app uses through its own local package (`ios/TetherCore/Package.swift`), so the app and the SDK never
drift apart. Versions follow the repository's release tags.

## Ten lines

```swift
import TetherCore

let clientKey = TetherPrivateKey()                         // store clientKey.rawRepresentation
guard let code = PairingCode.parse(scannedText) else { return }
let handshake = CentralHandshake(staticKey: clientKey, peerStatic: code.peripheralKey, token: code.token)
send(handshake.hello().encoded())
// HELLO_ACK in → session + FIN out → the phone's FIN in:
let (session, fin) = try handshake.receive(helloAck)
try CentralHandshake.verifyFinish(session, keys: handshake.keys!, record: phoneFin)
let record = try session.seal(channel: Proto.channelControl,
                              try Control.encode(MsgType.sync, SyncBody(sms: 0, calls: 0, contacts: "")))
```

On Apple platforms `TetherLink` does all of that over Bluetooth, and reconnects by itself:

```swift
let link = TetherLink(store: myIdentityStore)   // your Keychain- or file-backed IdentityStore
link.delegate = self                             // state changes, messages, audio frames
link.pair(with: code)                            // once; later launches reconnect on their own
link.send(MsgType.smsSend, SmsSend(to: "+10000000001", body: "Hi", sub: nil), expectReply: true)
```

## Writing a client: what the phone expects

The iPhone app and the Android client follow these rules; a client of your own should too (details in
[protocol/PROTOCOL.md](../protocol/PROTOCOL.md)):

- **Pairing codes come in two forms.** The QR code holds `tether://pair?c=…` since 1.2 (the iPhone's Camera app opens
  it in the app); typed or pasted codes and 1.1 phones use `tether1:…`. `PairingCode.parse` reads both. A link opened
  from outside your app must never pair by itself: ask first. The code holds the token: never log it.
- **An empty PSM means GATT.** Phones on Android 8 and 9 have no L2CAP channels and answer the PSM read with an empty
  value: use the GATT transport at once instead of waiting for a channel (§1).
- **Send the microphone only once the call is `active`.** The modem takes no uplink while a call dials; the phone
  drops what arrives before that, and sending it only costs airtime the downlink needs. Start the uplink afresh when
  the call becomes active (or active again after hold).
- **Check the microphone.** On iOS a refused microphone permission records silence without an error: ask for it before
  the first call and tell the user when the input is refused or silent.
- **Read `status.helper.audio`.** "full" (Android 13+), "fallback" (11–12, best effort) or "unavailable" (8–10): with
  "unavailable" calls are controlled from the client but their audio stays on the phone, so say so instead of showing
  a silent call.
- **Expect gaps.** With Bluetooth tethering on the same two phones, audio can stall for several hundred milliseconds.
  `PlayoutBuffer` keeps a stall's backlog and drains it in pauses (it cuts only beyond
  `PlayoutBuffer.maxBacklogSeconds`); if you write your own buffer, do not drop speech after every gap.
- **Answer wake notifications.** When the wake characteristic notifies, send something at once (a ping): the phone
  closes a session that stays silent after a wake. With no session, or one that does not answer, start a new one;
  do the same when the phone's Tether service changes (`didModifyServices`). `TetherLink` does all of this.
- **Ignore what you do not know.** Unknown message types and fields are skipped, never an error (§5); switch over
  public enums with a `default`.

## Examples

| Example | Runs on | What it shows |
| --- | --- | --- |
| [`examples/swift/Loopback`](../examples/swift/Loopback) | macOS, Linux | A whole encrypted session in memory between a minimal phone and a client: pairing code, handshake, sync, status, a text with a one-time code, Opus audio through the playout buffer, resume, a rejected stranger, a tampered record. Start here. |
| [`examples/swift/MacCentral`](../examples/swift/MacCentral) | macOS | A real Bluetooth client: pair with the code your phone shows, print its status and the latest texts. |

```sh
cd examples/swift/Loopback && swift run
```

Both depend on the SDK by relative path; CI builds them on every push.

## Documentation

The API reference and four articles (pairing and the handshake, sending messages, receiving call audio, using the
playout buffer) are a DocC catalog in `ios/TetherCore/Sources/TetherCore/TetherCore.docc`:

```sh
swift package generate-documentation --target TetherCore          # → .build/plugins/Swift-DocC/outputs/
swift package --disable-sandbox preview-documentation --target TetherCore   # local preview server
```

In Xcode: Product › Build Documentation. The wire format itself is specified in
[protocol/PROTOCOL.md](../protocol/PROTOCOL.md).

## API overview

| Area | Types |
| --- | --- |
| Pairing | `PairingCode` (`tether1:` code, `tether://pair?c=` QR link; `parse` reads both), `TetherPrivateKey`, `randomBytes(_:)` |
| Handshake (protocol §3) | `CentralHandshake` (client), `PeripheralHandshake` (phone), `KeySchedule`, `SessionKeys`, `HelloMessage`, `HelloAckMessage`, `HandshakeMode`, `HandshakeError` |
| Records (§2, §4) | `Record`, `RecordKind`, `RecordReader` (reassembles a byte stream), `SecureSession` (AES-256-GCM, strict counters), `SessionError`, `FramingError`, `Proto` (channels, lengths) |
| Control messages (§5) | `Control` (encode / decode the `{t, id, re, b}` envelope), `MessageHeader`, `MsgType` (every type name), one `Codable` struct per body: `StatusBody` (with `helper.audio` / `audioUnavailable` since 1.2), `SyncBody`, `Sms`, `SmsNew`, `SmsBatch`, `SmsSend`, `Contact`, `ContactsBatch`, `CallInfo`, `CallEntry`, `CallsBatch`, `NetState`, `BlockLists`, `SyncIds`, … |
| Call audio | `AudioFrame` (channel 1 payload), `AudioStart`, `LibOpusEncoder`, `LibOpusDecoder` (with packet-loss concealment), `G711`, `PlayoutBuffer` (stall backlog kept and drained in pauses since 1.2: `maxBacklogSeconds`, `speechTrimmed`), `SampleRing`, `JitterBuffer` |
| Call rules | `HangUpGuard` (late call states after a local hang-up) |
| Text | `MessageText` / `ParsedMessage` (links, numbers, e-mail, one-time codes, `*bold*`), `OTPExtractor`, `PhoneNumber`, `PhoneFormat` (grouping by country), `DialLink` (`tel:` links, numbers in free text), `NumberShape`, `SearchText`, `UssdCode` |
| Bluetooth link (Apple) | `TetherLink`, `TetherLinkDelegate`, `IdentityStore`, `TetherUUID` |

## Platforms

| | iOS | macOS | tvOS | watchOS | visionOS | Linux |
| --- | --- | --- | --- | --- | --- | --- |
| Protocol, crypto, records, messages | yes | yes | yes | yes | yes | yes (swift-crypto) |
| Opus (bundled libopus), G.711, playout buffer | yes | yes | yes | yes | yes | yes |
| Text: links / numbers / addresses | NSDataDetector | NSDataDetector | NSDataDetector | NSDataDetector | NSDataDetector | regular expressions: links (scheme, `www.`, common domain endings), e-mail, numbers; no postal addresses |
| Text: one-time codes, formatting, dial links, search | yes | yes | yes | yes | yes | yes |
| `TetherLink` (CoreBluetooth client) | yes, the iPhone app | yes, MacCentral | builds, not tried on a device | builds, not tried on a device | builds, not tried on a device | no: bring your own transport (for example BlueZ) and use the records and handshake |
| Checked in CI | build + tests | tests | build | build | build | build + tests (Swift 6.0) |

Other platforms swift-crypto supports (Android with the Swift SDK for Android, Windows) are expected to work for
everything except `TetherLink`, but nothing checks them yet. `TetherLink` keeps the iOS-only state restoration behind
`#if os(iOS)`; on watchOS and tvOS CoreBluetooth has its own background limits.

## Stability

From the first release that contains the root `Package.swift`, TetherCore follows semantic versioning:

- **Stable:** every `public` declaration of `TetherCore` (everything in the overview above). A minor release only
  adds: new types, new `MsgType` names, new optional fields in message bodies (with defaulted initialiser
  parameters, so existing calls still compile), new methods. Removing or renaming a public declaration, or changing
  its meaning, waits for the next major version.
- **Wire format:** the bytes TetherCore produces and accepts never change in a minor release; installed phones and
  clients keep talking to each other. Protocol additions are optional fields and new message types, which older
  peers ignore (protocol §5).
- **Switch with a default.** Public enums (`HandshakeError`, `SessionError`, `AudioFrame.Codec`, `RecordKind`,
  `TetherLink.State`, …) may gain cases in a minor release.
- **Not covered:** declarations behind `@_spi(TetherApp)` (the iPhone app's audio-route policy: `RouteKind`,
  `RouteOption`, `RoutePort`, `RoutePlan`, `AudioRoutes`, `EngineFormat`, `EngineRebuild`, `EngineDecision`, and since
  1.2 `CallUplink` and `UnfairLock`), anything
  `internal` (reachable only with `@testable import`), the text heuristics' exact results (which strings count as a
  number or a one-time code improve over time), the playout buffer's tuning numbers, log text, and
  `ios/TetherCore/Package.swift`'s own executables (`tether-vectors`, `tether-central`).

## Notes for contributors

- New code in `ios/TetherCore/Sources/TetherCore` must build on Linux: put CoreBluetooth, AVFoundation or UIKit code
  behind `#if canImport(…)`, import crypto as `#if canImport(CryptoKit) import CryptoKit #else import Crypto #endif`,
  use `UnfairLock` (not `os_unfair_lock`), and write regular expressions with ICU escapes (`\x{00A0}`, not
  `\u{00A0}`, which only Apple's Foundation accepts).
- Check both: `swift test` at the repository root (the SDK) and in `ios/TetherCore` (the app's package), and on
  Linux `docker run --rm -v "$PWD":/src -w /src swift:6.0 swift test --scratch-path /tmp/build` (the scratch path
  inside the container keeps the Linux build out of your `.build`).

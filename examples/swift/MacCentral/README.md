# MacCentral

A Mac client for a Tether phone over real Bluetooth LE. It pairs with the code the phone shows, then prints the link
state, the phone's status (battery, SIMs, network, signal), the round trip and the five latest texts, and keeps
printing new texts and call states until it exits. It is built on `TetherLink`, the same Bluetooth client the iPhone
app uses (discovery, L2CAP with a GATT fallback, handshake, keep-alive, reconnects).

```sh
swift build
.build/debug/MacCentral pair "tether://pair?c=…" 60     # the pairing code as text (see below)
.build/debug/MacCentral run 120                 # later: reconnect with the stored identity
.build/debug/MacCentral unpair                  # forget the phone
```

What it prints looks like this (illustrative; the values are your phone's):

```
pairing with My phone
   0.0s  link: searching
   1.2s  link: connecting
   1.6s  link: handshaking
   1.8s  link: connected(transport: "l2cap")
   1.9s  status: My phone, battery 64 %; SIM 1: LTE, signal 3/4
   1.9s  round trip 41 ms
   4.5s  latest texts:
          …
```

Notes:

- The pairing code is the text inside the QR code on the phone's Tether › Pair screen (`tether://pair?c=…` since
  1.2; the older `tether1:…` form works too); read it with any QR scanner and paste it.
- macOS asks once for Bluetooth access. The binary carries its usage string in an embedded Info.plist (see
  `Package.swift`); when you start it from a terminal, the permission is asked for (and listed under) that terminal app.
- The client identity (a P-256 key and the paired phone's public key) is kept in
  `~/Library/Application Support/TetherMacCentral/identity.json`, readable by you only. Delete the file to start over.
- The phone sends the full history after a pairing (a full `sync`); the example keeps only what it prints.
- Unknown message types are ignored, as the protocol asks (newer phones may send more). See `Client.link(_:didReceive:data:)`
  for where to handle more of them, and [docs/SDK-swift.md](../../../docs/SDK-swift.md) for the API.
- `ios/TetherCore/Sources/tether-central` is the older, undocumented test tool this example grew from;
  `tools/mac-central.sh` still uses it.

In your own package, depend on the SDK by URL instead of the relative path in this example's `Package.swift`:

```swift
.package(url: "https://github.com/amirwmr/tether", from: "1.2.0")
```

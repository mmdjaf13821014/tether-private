# Loopback

The "hello world" of the Tether Swift SDK: a complete encrypted session between a phone and a client, in memory,
using only TetherCore's public API. No Bluetooth, no phone, runs on macOS and Linux.

```sh
swift run
```

```
• pairing code tether://pair?c=BMS_DaCPPd5V… (141 characters) for "Demo phone"
• paired: session up, transcript a6873b2d2489…
• phone got sync, client decodes opus, pcmu
• status: Demo phone, battery 87 %, SIM 1 LTE
• text from +10000000001: "Your Example code is 482913" (one-time code 482913)
• audio: 25 Opus frames, 480 ms played after a 40 ms buffer, level 0.17, 0 underruns
• resumed without the pairing code (mode resume)
• an unknown client is rejected (reason 2)
• a tampered record is refused (authFailed)
done: 3012 bytes carried in the first session
```

What [`Sources/Loopback/main.swift`](Sources/Loopback/main.swift) does, step by step:

1. **Pairing code.** The phone makes a long-term key (`TetherPrivateKey`) and a one-time token and shows them as a
   `tether://pair?c=…` link, the form the QR code holds since 1.2 (`PairingCode.link`; typed codes are `tether1:…`,
   `PairingCode.string`); the client parses it (`parse` reads both).
2. **Handshake.** `CentralHandshake` (client) and `PeripheralHandshake` (phone) exchange HELLO, HELLO_ACK and two FIN
   records and end up with matching `SecureSession`s. Records cross a `Wire` that cuts them into 7-byte chunks and
   puts them back together with `RecordReader`, as a Bluetooth stream would.
3. **Messages.** The client sends `sync` (`SyncBody`); the phone answers with `status` (`StatusBody`) and `sms.new`
   (`SmsNew`). The client dispatches on `MessageHeader.t` and decodes the body with `Control.body`; `MessageText`
   finds the one-time code in the text.
4. **Audio.** The phone encodes a 440 Hz tone with `LibOpusEncoder`, one `AudioFrame` per 20 ms on the audio channel.
   The client decodes it with `LibOpusDecoder` and plays it through a `PlayoutBuffer`, pulling 10 ms at a time as an
   audio output would.
5. **Reconnect.** A second handshake in resume mode (no token) succeeds with the stored client key; a stranger's
   key gets a REJECT record.
6. **Tampering.** One flipped bit in a sealed record makes `SecureSession.open` throw `authFailed`.

The program exits with status 1 if any step goes wrong, so CI runs it as a test.

In your own package, depend on the SDK by URL instead of the relative path in this example's `Package.swift`:

```swift
.package(url: "https://github.com/amirwmr/tether", from: "1.2.0")
```

More: [docs/SDK-swift.md](../../../docs/SDK-swift.md), the protocol in
[protocol/PROTOCOL.md](../../../protocol/PROTOCOL.md).

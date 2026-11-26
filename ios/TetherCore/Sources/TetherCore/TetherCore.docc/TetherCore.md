# ``TetherCore``

Talk to an Android phone running Tether: pairing, the encrypted session, control messages, and call audio.

## Overview

Tether puts an Android phone's calls, texts, contacts and call history on another device over an end-to-end
encrypted Bluetooth LE link, with no server and no account. TetherCore is the Swift side of that protocol. It is what
the iPhone app is built on, and you can build your own client, a test tool or a phone-side implementation with it.

- **Protocol:** record framing (``Record``, ``RecordReader``), the handshake (``CentralHandshake``,
  ``PeripheralHandshake``, ``KeySchedule``), the AES-256-GCM record layer (``SecureSession``) and pairing codes
  (``PairingCode``). These are byte-for-byte compatible with the Android implementation and checked against shared
  vectors (`protocol/vectors.json`).
- **Messages:** the JSON control envelope (``Control``, ``MessageHeader``, ``MsgType``) and a type for every message
  body (``StatusBody``, ``Sms``, ``CallInfo``, ``Contact`` and others).
- **Audio:** Opus through the bundled libopus (``LibOpusEncoder``, ``LibOpusDecoder``), G.711 µ-law (``G711``),
  audio frames (``AudioFrame``) and a playout buffer made for Bluetooth jitter (``PlayoutBuffer``).
- **Text helpers:** rich SMS text with links, numbers and one-time codes (``MessageText``, ``OTPExtractor``), phone
  number formatting (``PhoneFormat``), dial links (``DialLink``) and search (``SearchText``).
- **Link (Apple platforms):** ``TetherLink``, a complete CoreBluetooth central: discovery, L2CAP with a GATT
  fallback, handshake, keep-alive and reconnects.

Everything except ``TetherLink`` builds on Linux and other platforms as well, with swift-crypto in place of CryptoKit.

The wire format is specified in `protocol/PROTOCOL.md` in the repository; the articles below show the API with code.

## Topics

### Essentials

- <doc:PairingAndHandshake>
- <doc:SendingMessages>
- <doc:ReceivingCallAudio>
- <doc:UsingThePlayoutBuffer>

### Pairing and handshake

- ``PairingCode``
- ``TetherPrivateKey``
- ``CentralHandshake``
- ``PeripheralHandshake``
- ``HandshakeMode``
- ``HandshakeError``
- ``KeySchedule``
- ``SessionKeys``
- ``HelloMessage``
- ``HelloAckMessage``
- ``randomBytes(_:)``

### Records and the session

- ``Record``
- ``RecordKind``
- ``RecordReader``
- ``FramingError``
- ``SecureSession``
- ``SessionError``
- ``Proto``

### Control messages

- ``Control``
- ``MessageHeader``
- ``MsgType``
- ``Empty``
- ``SyncBody``
- ``ClientHello``
- ``StatusBody``
- ``PingBody``
- ``ErrorBody``

### Calls and audio

- ``CallInfo``
- ``AudioStart``
- ``AudioFrame``
- ``LibOpusEncoder``
- ``LibOpusDecoder``
- ``G711``
- ``PlayoutBuffer``
- ``SampleRing``
- ``JitterBuffer``
- ``HangUpGuard``

### Texts, contacts and call history

- ``Sms``
- ``Contact``
- ``Phone``
- ``CallEntry``
- ``MessageText``
- ``ParsedMessage``
- ``OTPExtractor``
- ``PhoneNumber``
- ``PhoneFormat``
- ``DialLink``
- ``SearchText``

### The Bluetooth link (Apple platforms)

- ``TetherLink``
- ``TetherLinkDelegate``
- ``IdentityStore``
- ``TetherUUID``

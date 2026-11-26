# Pairing and the handshake

Pair a client with a phone once, then reconnect with a short handshake that proves both sides.

## Overview

Every phone and every client has a long-term P-256 key (``TetherPrivateKey``). Pairing introduces the two keys to
each other; each later connection runs a handshake that mixes three Diffie-Hellman results into the session keys
(protocol §3). There is no server, no account and no Bluetooth-level pairing.

On Apple platforms ``TetherLink`` does all of this for you: call ``TetherLink/pair(with:)`` with a scanned
``PairingCode`` and watch ``TetherLinkDelegate/link(_:didChange:)``. This article shows the steps underneath, which
you need on other platforms, with another transport, or on the phone side.

### The pairing code

The phone shows its public key, a one-time 16-byte token and its name as a QR code. Since 1.2 the QR code holds the
link form `tether://pair?c=<key>.<token>.<name>` (the iPhone's Camera app opens it in the app); typed or pasted codes,
and 1.1 phones, use `tether1:<key>.<token>.<name>` (base64url in both):

```swift
let phoneKey = TetherPrivateKey()
let token = randomBytes(16)
let code = PairingCode(peripheralKey: phoneKey.publicKey.x963Representation,
                       token: token, deviceName: "My phone")
print(code.link)     // tether://pair?c=BMFZ…  (the QR code)
print(code.string)   // tether1:BMFZ…          (typed or pasted)
```

The client parses what it scanned. ``PairingCode/parse(_:)`` returns `nil` for anything damaged or of another shape:

```swift
guard let scanned = PairingCode.parse(text) else { return }
```

### Pair mode

The client sends HELLO, the phone answers HELLO_ACK, both send FIN. Records are carried by whatever transport you
have; ``Record/encoded()`` gives the bytes and a ``RecordReader`` reassembles them on the other side, whatever the
chunk sizes.

```swift
// Client
let clientKey = TetherPrivateKey()                       // keep it: it is the client's identity
let handshake = CentralHandshake(staticKey: clientKey,
                                 peerStatic: scanned.peripheralKey,
                                 token: scanned.token)    // a token selects pair mode
send(handshake.hello())

// … HELLO_ACK arrives (a REJECT record throws HandshakeError.rejected)
let (session, fin) = try handshake.receive(helloAck)
send(fin)

// … the phone's FIN arrives
try CentralHandshake.verifyFinish(session, keys: handshake.keys!, record: phoneFin)
// `session` is ready: see <doc:SendingMessages>
```

```swift
// Phone
let phone = PeripheralHandshake(staticKey: phoneKey)
let reply = try phone.receiveHello(hello, paired: nil, token: token)   // HELLO_ACK, or REJECT
send(reply)
// … the client's FIN arrives
let (session, fin) = try phone.receiveFinish(clientFin)
send(fin)
let pairedClientKey = phone.hello!.staticKey   // store it: the client reconnects with this key
```

### Resume mode

Once paired, the client passes `token: nil` and the phone accepts only the static key it stored:

```swift
let handshake = CentralHandshake(staticKey: clientKey, peerStatic: phonePublicKey, token: nil)
```

```swift
let reply = try PeripheralHandshake(staticKey: phoneKey)
    .receiveHello(hello, paired: pairedClientKey, token: nil)
```

An unknown key gets a REJECT record (reason 2); a wrong token or a tampered message makes the FIN check fail
(``HandshakeError/finishMismatch``) and no session comes up.

### Keeping keys

``TetherPrivateKey`` is CryptoKit's (or swift-crypto's) `P256.KeyAgreement.PrivateKey`. Store
`rawRepresentation` (32 bytes) in the Keychain or a protected file and restore it with
`TetherPrivateKey(rawRepresentation:)`. With ``TetherLink`` you do this in your ``IdentityStore``.

The complete exchange runs in `examples/swift/Loopback` in the repository.

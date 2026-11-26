# Sending and receiving messages

Exchange JSON control messages over a sealed session.

## Overview

After the handshake every record is sealed with AES-256-GCM under a strict counter per direction
(``SecureSession``). The plaintext starts with a channel byte: ``Proto/channelControl`` (0) for control messages,
``Proto/channelAudio`` (1) for audio frames. A control message is JSON, `{"t": type, "id"?, "re"?, "b": body}`
(protocol §5). ``MsgType`` names every type and each body has a `Codable` type.

### With TetherLink

``TetherLink/send(_:_:expectReply:re:)`` encodes, seals and queues a message from any thread. It returns the request
id; replies carry it in ``MessageHeader/re``:

```swift
link.send(MsgType.sync, SyncBody(sms: 0, calls: 0, contacts: "", codecs: ["opus"]))
let id = link.send(MsgType.smsSend, SmsSend(to: "+10000000001", body: "On my way", sub: nil), expectReply: true)
```

Incoming messages arrive in ``TetherLinkDelegate/link(_:didReceive:data:)`` with the decoded header and the whole
JSON. Decode the body for the type you expect:

```swift
func link(_ link: TetherLink, didReceive header: MessageHeader, data: Data) {
    switch header.t {
    case MsgType.status:
        let status = try? Control.body(StatusBody.self, from: data)
    case MsgType.smsNew:
        let sms = try? Control.body(SmsNew.self, from: data).item
    case MsgType.smsResult where header.re == id:
        let result = try? Control.body(SmsResult.self, from: data)
    default:
        break   // unknown types are ignored: newer phones may send more
    }
}
```

### With your own transport

Seal and open records with the ``SecureSession`` the handshake returned:

```swift
let json = try Control.encode(MsgType.ping, PingBody(ts: 1), id: 7)
let record = try session.seal(channel: Proto.channelControl, json)
write(record.encoded())

// Receiving: feed bytes into a RecordReader and open what comes out.
reader.append(bytes)
while let record = try reader.next() {
    let (channel, payload) = try session.open(record)
    if channel == Proto.channelControl {
        let header = try Control.header(payload)
        // dispatch on header.t as above
    }
}
```

``SecureSession/open(_:)`` throws ``SessionError/authFailed`` for an altered record and
``SessionError/counterMismatch(expected:got:)`` for a replayed, lost or reordered one. Close the connection then;
the protocol never resynchronises inside a session.

### Rules worth knowing

- A record body holds at most 65 534 bytes; long lists come in pages (`more: true`).
- Times are Unix milliseconds; phone numbers are as the phone stores them.
- Optional fields may be missing from older phones: every such field is optional in the Swift types.

Use ``MessageText`` to show an SMS body with links, numbers and one-time codes, and ``PhoneFormat`` to group
numbers for display.

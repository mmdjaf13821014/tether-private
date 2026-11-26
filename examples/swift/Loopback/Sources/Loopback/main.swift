// Loopback: a complete Tether session in memory, using only the SDK's public API.
//
// A minimal "phone" (the role the Android app plays) and a client (the role the iPhone app plays) pair with a
// pairing code, run the handshake, and exchange sealed records over an in-memory byte stream that stands in for
// the Bluetooth L2CAP channel: a sync request, a status message, a text message and half a second of call audio, which
// the client decodes and plays out through the playout buffer. Then the client reconnects without the pairing
// code (resume) and a tampered record is refused.
//
// Exits with status 1 if any step does not behave as the protocol says.
import Foundation
import TetherCore

// MARK: - The byte stream

/// One direction of the link. Records are framed (`len ‖ kind ‖ body`); the bytes arrive in arbitrary chunks, as
/// they would over L2CAP or GATT, and a `RecordReader` puts the records back together.
struct Wire {
    private var reader = RecordReader()
    private(set) var bytesCarried = 0

    /// Sends one record and returns it as the other side reads it.
    mutating func carry(_ record: Record) throws -> Record {
        let bytes = record.encoded()
        bytesCarried += bytes.count
        var i = bytes.startIndex
        var received: Record?
        while i < bytes.endIndex {
            let end = min(i + 7, bytes.endIndex)       // 7-byte chunks: boundaries mean nothing
            reader.append(Data(bytes[i..<end]))
            i = end
            if let r = try reader.next() { received = r }
        }
        guard let received else { throw Failure("the record did not arrive whole") }
        return received
    }
}

struct Failure: Error, CustomStringConvertible {
    let description: String
    init(_ d: String) { description = d }
}

func check(_ ok: Bool, _ what: String) throws {
    guard ok else { throw Failure(what) }
}

func step(_ s: String) { print("• " + s) }

// MARK: - The session

func run() throws {
    var toPhone = Wire(), toClient = Wire()

    // 1. Pairing code. The phone has a long-term P-256 key; it shows its public key, a one-time 16-byte token and
    //    its name as a QR code: the link form `tether://pair?c=…` since 1.2 (typed or pasted codes are `tether1:…`;
    //    `parse` reads both). The client scans it.
    let phoneKey = TetherPrivateKey()
    let token = randomBytes(16)
    let shown = PairingCode(peripheralKey: phoneKey.publicKey.x963Representation, token: token, deviceName: "Demo phone")
    guard let scanned = PairingCode.parse(shown.link), PairingCode.parse(shown.string) == scanned else {
        throw Failure("pairing code did not parse")
    }
    step("pairing code \(shown.link.prefix(28))… (\(shown.link.count) characters) for \"\(scanned.deviceName)\"")

    // 2. Handshake, pair mode: HELLO → HELLO_ACK → FIN ← FIN. Both sides derive the same session keys from three
    //    ECDH results and the token; each FIN proves it.
    let clientKey = TetherPrivateKey()
    let client = CentralHandshake(staticKey: clientKey, peerStatic: scanned.peripheralKey, token: scanned.token)
    let phone = PeripheralHandshake(staticKey: phoneKey)

    let hello = try toPhone.carry(client.hello())
    let ack = try toClient.carry(try phone.receiveHello(hello, paired: nil, token: token))
    try check(ack.kind == RecordKind.helloAck.rawValue, "the phone rejected the hello")
    let (clientSession, clientFin) = try client.receive(ack)
    let (phoneSession, phoneFin) = try phone.receiveFinish(try toPhone.carry(clientFin))
    guard let keys = client.keys else { throw Failure("no session keys") }
    try CentralHandshake.verifyFinish(clientSession, keys: keys, record: try toClient.carry(phoneFin))
    // The phone remembers the client's static key: from now on the client reconnects without a token.
    guard let pairedClient = phone.hello?.staticKey else { throw Failure("no client key") }
    step("paired: session up, transcript \(keys.transcriptHash.prefix(6).hex)…")

    // 3. Control messages are JSON `{t, id?, re?, b}` sealed on channel 0 (AES-256-GCM, one counter per direction).
    func sendControl<B: Encodable>(_ session: SecureSession, _ wire: inout Wire, _ type: String, _ body: B,
                                   id: Int? = nil, re: Int? = nil) throws -> Record {
        try wire.carry(try session.seal(channel: Proto.channelControl, try Control.encode(type, body, id: id, re: re)))
    }

    // The client asks for everything (a full sync) and says which codecs it can decode.
    let sync = try sendControl(clientSession, &toPhone, MsgType.sync,
                               SyncBody(sms: 0, calls: 0, contacts: "", codecs: ["opus", "pcmu"]))

    // 4. The phone reads the request and answers with its status and a new text message.
    let (syncChannel, syncData) = try phoneSession.open(sync)
    let request = try Control.header(syncData)
    try check(syncChannel == Proto.channelControl && request.t == MsgType.sync, "the phone did not get the sync")
    let codecs = try Control.body(SyncBody.self, from: syncData).codecs ?? []
    step("phone got \(request.t), client decodes \(codecs.joined(separator: ", "))")

    let status = StatusBody(
        device: "Demo phone", battery: 87, charging: false,
        sims: [SimInfo(slot: 0, sub: 1, name: "SIM 1", carrier: "Example Mobile", signal: 3, net: "LTE", service: "in")],
        defaultSub: 1, dialer: true, helper: HelperInfo(state: "ready"), wifi: true, adbWifi: false)
    let text = Sms(id: 1, thread: 1, addr: "+10000000001", body: "Your *Example* code is 482913",
                   date: Int64(Date().timeIntervalSince1970 * 1000), box: 1, read: false)
    let inbox = [
        try sendControl(phoneSession, &toClient, MsgType.status, status),
        try sendControl(phoneSession, &toClient, MsgType.smsNew, SmsNew(item: text)),
    ]

    // 5. The client dispatches on the message type, as TetherLinkDelegate.link(_:didReceive:data:) would.
    var gotStatus = false, gotText = false
    for record in inbox {
        let (_, data) = try clientSession.open(record)
        switch try Control.header(data).t {
        case MsgType.status:
            let s = try Control.body(StatusBody.self, from: data)
            step("status: \(s.device), battery \(s.battery) %, \(s.sims.map { "\($0.name) \($0.net ?? "?")" }.joined(separator: ", "))")
            gotStatus = true
        case MsgType.smsNew:
            let sms = try Control.body(SmsNew.self, from: data).item
            let parsed = MessageText.parse(sms.body)
            step("text from \(sms.addr): \"\(parsed.text)\" (one-time code \(parsed.code ?? "none"))")
            gotText = parsed.code == "482913"
        case let other:
            step("ignored \(other)")
        }
    }
    try check(gotStatus && gotText, "status or text missing")

    // 6. Call audio: Opus frames (16 kHz, 20 ms) sealed on channel 1 as `codec ‖ seq ‖ payload`. The client decodes
    //    them and feeds the playout buffer, which the audio output thread drains 10 ms at a time.
    let rate = 16_000, frameSamples = rate / 50
    guard let encoder = LibOpusEncoder(rate: rate), let decoder = LibOpusDecoder(rate: rate) else {
        throw Failure("no Opus codec")
    }
    let playout = PlayoutBuffer(rate: Double(rate))
    var out = [Float](repeating: 0, count: rate / 100)
    var arrival: UInt64 = 1_000_000_000, real = 0, pulls = 0, energy: Float = 0
    for seq in 0..<25 {                                // 500 ms of a 440 Hz tone
        let tone = (0..<frameSamples).map { i -> Int16 in
            let t = Double(seq * frameSamples + i) / Double(rate)
            return Int16(8000 * sin(2 * Double.pi * 440 * t))
        }
        let frame = AudioFrame(codec: .opus, seq: UInt16(seq), payload: encoder.encode(tone))
        let record = try toClient.carry(try phoneSession.seal(channel: Proto.channelAudio, frame.encoded()))

        // Client, network queue: open, parse, decode, push with the arrival time.
        let (channel, payload) = try clientSession.open(record)
        guard channel == Proto.channelAudio, let received = AudioFrame.parse(payload), received.codec == .opus else {
            throw Failure("not an audio frame")
        }
        playout.push(decoder.decode(received.payload), at: arrival)
        arrival += 20_000_000                          // frames arrive every 20 ms

        // Client, audio output thread: takes 10 ms at a time, twice per frame. Until the buffer holds its target
        // (40 ms at the start) it plays silence; a late frame would be concealed, not cut.
        for _ in 0..<2 {
            real += out.withUnsafeMutableBufferPointer { playout.pull(into: $0.baseAddress!, count: $0.count) }
            energy += out.reduce(0) { $0 + $1 * $1 }
            pulls += 1
        }
    }
    let rms = (energy / Float(pulls * out.count)).squareRoot()
    step("audio: 25 Opus frames, \(real * 1000 / rate) ms played after a \(playout.target * 1000 / rate) ms buffer, " +
         "level \(String(format: "%.2f", rms)), \(playout.underruns) underruns")
    try check(real > 0 && rms > 0.05, "no audio came out")

    // 7. Reconnect: resume mode needs no token; the phone accepts only the static key it paired with.
    var toPhone2 = Wire(), toClient2 = Wire()
    let client2 = CentralHandshake(staticKey: clientKey, peerStatic: phoneKey.publicKey.x963Representation, token: nil)
    let phone2 = PeripheralHandshake(staticKey: phoneKey)
    let ack2 = try toClient2.carry(try phone2.receiveHello(try toPhone2.carry(client2.hello()), paired: pairedClient, token: nil))
    let (session2, fin2) = try client2.receive(ack2)
    let (_, phoneFin2) = try phone2.receiveFinish(try toPhone2.carry(fin2))
    try CentralHandshake.verifyFinish(session2, keys: client2.keys!, record: try toClient2.carry(phoneFin2))
    step("resumed without the pairing code (mode \(client2.mode))")

    // A stranger's key gets a REJECT record instead of a HELLO_ACK.
    let stranger = CentralHandshake(staticKey: TetherPrivateKey(), peerStatic: phoneKey.publicKey.x963Representation, token: nil)
    let refusal = try PeripheralHandshake(staticKey: phoneKey).receiveHello(stranger.hello(), paired: pairedClient, token: nil)
    try check(refusal.kind == RecordKind.reject.rawValue, "a stranger was let in")
    step("an unknown client is rejected (reason \(refusal.body.first ?? 0))")

    // 8. A record changed on the way is refused, never delivered.
    var tampered = try phoneSession.seal(channel: Proto.channelControl, try Control.encode(MsgType.ping, PingBody(ts: 1)))
    tampered.body[tampered.body.count - 1] ^= 0x01
    do {
        _ = try clientSession.open(tampered)
        throw Failure("a tampered record was accepted")
    } catch SessionError.authFailed {
        step("a tampered record is refused (authFailed)")
    }

    print("done: \(toPhone.bytesCarried + toClient.bytesCarried) bytes carried in the first session")
}

do {
    try run()
} catch {
    print("FAILED: \(error)")
    exit(1)
}

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import Testing
@testable import TetherCore

private func loadVectors() throws -> [String: Any] {
    let url = try #require(Bundle.module.url(forResource: "vectors", withExtension: "json"))
    return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
}

private func hex(_ v: Any?) -> Data { Data(hex: v as! String)! }

@Suite struct FramingTests {
    @Test func roundTripAcrossArbitraryChunks() throws {
        let records = (0..<50).map { i in Record(kind: UInt8(i % 4 + 1), body: Data(repeating: UInt8(i), count: i * 37)) }
        let stream = records.reduce(Data()) { $0 + $1.encoded() }
        var reader = RecordReader()
        var got: [Record] = []
        var i = 0
        var step = 1
        while i < stream.count {
            let end = min(stream.count, i + step)
            reader.append(stream.subdata(in: i..<end))
            got += try reader.drain()
            i = end
            step = step % 97 + 3
        }
        #expect(got == records)
        #expect(reader.pendingBytes == 0)
    }

    @Test func zeroLengthIsRejected() {
        var reader = RecordReader()
        reader.append(Data([0, 0, 1]))
        #expect(throws: FramingError.zeroLength) { try reader.next() }
    }

    @Test func maxBody() throws {
        let r = Record(kind: 0x10, body: Data(repeating: 9, count: 65534))
        var reader = RecordReader()
        reader.append(r.encoded())
        #expect(try reader.next() == r)
    }
}

@Suite struct VectorTests {
    @Test func handshakeVectorsMatch() throws {
        let v = try loadVectors()
        let hs = v["handshakes"] as! [[String: Any]]
        #expect(hs.count == 2)
        for h in hs {
            let sP = try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(h["s_P_priv"]))
            let sC = try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(h["s_C_priv"]))
            let eP = try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(h["e_P_priv"]))
            let eC = try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(h["e_C_priv"]))
            let token = (h["token"] as! String).isEmpty ? nil : hex(h["token"])
            let central = CentralHandshake(staticKey: sC, peerStatic: sP.publicKey.x963Representation,
                                           token: token, ephemeral: eC, nonce: hex(h["n_C"]))
            let hello = central.hello()
            #expect(hello.encoded() == hex(h["hello_record"]))
            var reader = RecordReader()
            reader.append(hex(h["hello_ack_record"]))
            let ack = try #require(try reader.next())
            let (session, fin) = try central.receive(ack)
            #expect(central.keys?.transcriptHash == hex(h["th"]))
            #expect(central.keys?.c2p == hex(h["k_c2p"]))
            #expect(central.keys?.p2c == hex(h["k_p2c"]))
            #expect(fin.encoded() == hex(h["fin_c2p_record"]))
            reader.append(hex(h["fin_p2c_record"]))
            try CentralHandshake.verifyFinish(session, keys: central.keys!, record: try #require(try reader.next()))
            let plain = hex(h["c2p_ctr1_plain"])
            let sealed = try session.seal(channel: plain.first!, plain.dropFirst())
            #expect(sealed.encoded() == hex(h["c2p_ctr1_record"]))
            reader.append(hex(h["p2c_ctr1_record"]))
            let (ch, payload) = try session.open(try #require(try reader.next()))
            #expect(Data([ch]) + payload == hex(h["p2c_ctr1_plain"]))
        }
    }

    @Test func pairingCodeVector() throws {
        let p = try loadVectors()["pairing_code"] as! [String: Any]
        let code = try #require(PairingCode.parse(p["string"] as! String))
        #expect(code.peripheralKey == hex(p["S_P"]))
        #expect(code.token == hex(p["token"]))
        #expect(code.deviceName == p["name"] as! String)
        #expect(code.string == p["string"] as! String)
        #expect(code.link == p["link"] as! String)
        #expect(PairingCode.parse(p["link"] as! String) == code)
        #expect(PairingCode.parse("tether1:abc") == nil)
        #expect(PairingCode.parse("tether://pair?c=abc") == nil)
        #expect(PairingCode.parse("tether://pair?x=1") == nil)
        #expect(PairingCode.parse("tether://dial?number=%2B10000000001") == nil)
        #expect(PairingCode.parse("https://example.com") == nil)
    }

    @Test func g711Vector() throws {
        let g = try loadVectors()["g711"] as! [String: Any]
        let pcm = (g["pcm"] as! [Int]).map { Int16($0) }
        #expect(G711.encode(pcm) == hex(g["ulaw"]))
        #expect(G711.decode(hex(g["ulaw"])).map(Int.init) == g["decoded"] as! [Int])
    }

    @Test func otpVectors() throws {
        for c in try loadVectors()["otp"] as! [[String: Any]] {
            #expect(OTPExtractor.extract(c["text"] as! String) == c["code"] as? String)
        }
    }
}

@Suite struct SessionTests {
    func pair(token: Data? = Data(repeating: 1, count: 16), paired: Data? = nil) throws
        -> (SecureSession, SecureSession) {
        let sP = P256.KeyAgreement.PrivateKey(), sC = P256.KeyAgreement.PrivateKey()
        let c = CentralHandshake(staticKey: sC, peerStatic: sP.publicKey.x963Representation, token: token)
        let p = PeripheralHandshake(staticKey: sP)
        let ack = try p.receiveHello(c.hello(), paired: paired ?? sC.publicKey.x963Representation, token: token)
        let (cs, fin) = try c.receive(ack)
        let (ps, pfin) = try p.receiveFinish(fin)
        try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: pfin)
        return (cs, ps)
    }

    @Test func loopback() throws {
        let (c, p) = try pair()
        for i in 0..<100 {
            let msg = Data("hello \(i)".utf8)
            let (ch, got) = try p.open(try c.seal(channel: 0, msg))
            #expect(ch == 0 && got == msg)
            let (ch2, got2) = try c.open(try p.seal(channel: 1, msg))
            #expect(ch2 == 1 && got2 == msg)
        }
    }

    @Test func replayAndTamperRejected() throws {
        let (c, p) = try pair()
        let r0 = try c.seal(channel: 0, Data("a".utf8))
        _ = try p.open(r0)
        #expect(throws: SessionError.counterMismatch(expected: 2, got: 1)) { try p.open(r0) }
        var r1 = try c.seal(channel: 0, Data("b".utf8))
        r1.body[r1.body.count - 1] ^= 1
        #expect(throws: SessionError.authFailed) { try p.open(r1) }
    }

    @Test func wrongTokenFailsFinish() throws {
        let sP = P256.KeyAgreement.PrivateKey(), sC = P256.KeyAgreement.PrivateKey()
        let c = CentralHandshake(staticKey: sC, peerStatic: sP.publicKey.x963Representation, token: Data(repeating: 1, count: 16))
        let p = PeripheralHandshake(staticKey: sP)
        let ack = try p.receiveHello(c.hello(), paired: nil, token: Data(repeating: 2, count: 16))
        let (_, fin) = try c.receive(ack)
        #expect(throws: SessionError.authFailed) { try p.receiveFinish(fin) }
    }

    @Test func unknownDeviceRejected() throws {
        let sP = P256.KeyAgreement.PrivateKey(), sC = P256.KeyAgreement.PrivateKey()
        let c = CentralHandshake(staticKey: sC, peerStatic: sP.publicKey.x963Representation, token: nil)
        let p = PeripheralHandshake(staticKey: sP)
        let r = try p.receiveHello(c.hello(), paired: P256.KeyAgreement.PrivateKey().publicKey.x963Representation, token: nil)
        #expect(r == Record(.reject, Data([2])))
        #expect(throws: HandshakeError.rejected(2)) { try c.receive(r) }
    }
}

@Suite struct MessageTests {
    @Test func envelopeRoundTrip() throws {
        let sms = Sms(id: 5, thread: 2, addr: "+15550100", body: "سلام", date: 1, box: 1, read: false, sub: 3)
        let data = try Control.encode(MsgType.smsNew, SmsNew(item: sms))
        let h = try Control.header(data)
        #expect(h.t == "sms.new" && h.id == nil)
        #expect(try Control.body(SmsNew.self, from: data).item == sms)
    }

    @Test func decodesAndroidStyleJson() throws {
        let json = #"{"t":"call.state","b":{"cid":"c1","num":"123","state":"ringing","dir":"in","hold":false,"extra":1}}"#
        let c = try Control.body(CallInfo.self, from: Data(json.utf8))
        #expect(c.cid == "c1" && c.state == "ringing" && c.name == nil)
    }

    @Test func audioStatsFromAndroid() throws {
        let json = #"{"t":"audio.stats","b":{"summary":"down 250 frames","helper":{"captureDroppedMs":72}}}"#
        #expect(try Control.header(Data(json.utf8)).t == MsgType.audioStats)
        #expect(try Control.body(AudioStats.self, from: Data(json.utf8)).summary == "down 250 frames")
    }

    @Test func audioFrame() {
        let f = AudioFrame(codec: .opus, seq: 65535, payload: Data([1, 2, 3]))
        #expect(AudioFrame.parse(f.encoded()) == f)
        #expect(AudioFrame.parse(Data([9, 0, 0])) == nil)
    }
}

@Suite struct JitterTests {
    @Test func reordersAndDropsLate() {
        var jb = JitterBuffer<Int>(targetDepth: 2, maxDepth: 8)
        jb.push(seq: 10, 10)
        #expect(jb.pop() == nil) // not primed
        jb.push(seq: 12, 12)
        jb.push(seq: 11, 11)
        #expect(jb.pop() == 10)
        #expect(jb.pop() == 11)
        jb.push(seq: 9, 9) // late
        #expect(jb.dropped == 1)
        #expect(jb.pop() == 12)
    }

    @Test func wrapsAround() {
        var jb = JitterBuffer<UInt16>(targetDepth: 1)
        for s: UInt16 in [65534, 65535, 0, 1] { jb.push(seq: s, s) }
        var out: [UInt16] = []
        for _ in 0..<4 { if let f = jb.pop() { out.append(f) } }
        #expect(out == [65534, 65535, 0, 1])
    }

    @Test func phoneMatch() {
        #expect(PhoneNumber.matchKey("+98 912 345 6789") == PhoneNumber.matchKey("09123456789"))
        #expect(PhoneNumber.matchKey("۰۹۱۲۳۴۵۶۷۸۹") == "123456789")
    }
}

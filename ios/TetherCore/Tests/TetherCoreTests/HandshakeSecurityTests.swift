#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import XCTest
@testable import TetherCore

/// Negative paths of the handshake and the record layer: whatever an attacker or a broken link does, a session
/// never comes up with the wrong peer and a record is never accepted out of order or altered.
final class HandshakeSecurityTests: XCTestCase {
    private let token = Data(repeating: 7, count: 16)

    private struct Pair {
        let sP = P256.KeyAgreement.PrivateKey(), sC = P256.KeyAgreement.PrivateKey()
    }

    /// Runs a full pairing handshake; returns both sessions.
    private func handshake(_ k: Pair = Pair(), token: Data? = Data(repeating: 7, count: 16)) throws -> (SecureSession, SecureSession, CentralHandshake) {
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
        let p = PeripheralHandshake(staticKey: k.sP)
        let ack = try p.receiveHello(c.hello(), paired: k.sC.publicKey.x963Representation, token: token)
        let (cs, fin) = try c.receive(ack)
        let (ps, pfin) = try p.receiveFinish(fin)
        try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: pfin)
        return (cs, ps, c)
    }

    // MARK: handshake

    /// A central that pins the wrong Android key (an impostor peripheral) never gets a session.
    func testImpostorPeripheralFailsKeyConfirmation() throws {
        let k = Pair(), impostor = P256.KeyAgreement.PrivateKey()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: nil)
        let p = PeripheralHandshake(staticKey: impostor)
        let ack = try p.receiveHello(c.hello(), paired: k.sC.publicKey.x963Representation, token: nil)
        let (cs, fin) = try c.receive(ack)
        XCTAssertThrowsError(try p.receiveFinish(fin)) { XCTAssertEqual($0 as? SessionError, .authFailed) }
        // Even a FIN the impostor makes with its own keys does not verify on the central.
        let forged = try SecureSession(sendKey: p.keys!.p2c, recvKey: p.keys!.c2p).seal(channel: Proto.channelFinish, p.keys!.transcriptHash)
        XCTAssertThrowsError(try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: forged)) { XCTAssertEqual($0 as? SessionError, .authFailed) }
    }

    /// Resume with a static key the Android phone does not know: rejected with reason 2, before any key material.
    func testResumeWithUnknownOrMissingPairingIsRejected() throws {
        let k = Pair()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: nil)
        let p = PeripheralHandshake(staticKey: k.sP)
        XCTAssertEqual(try p.receiveHello(c.hello(), paired: nil, token: token), Record(.reject, Data([2])))
        XCTAssertNil(p.keys)
        XCTAssertThrowsError(try p.receiveFinish(Record(.sealed, Data(count: 40)))) { XCTAssertEqual($0 as? HandshakeError, .unexpectedRecord) }
    }

    /// Pairing when the Android phone shows no code (token expired or used): reason 3.
    func testPairWithoutOpenTokenIsRejected() throws {
        let k = Pair()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
        XCTAssertEqual(c.mode, .pair)
        let r = try PeripheralHandshake(staticKey: k.sP).receiveHello(c.hello(), paired: nil, token: nil)
        XCTAssertEqual(r, Record(.reject, Data([3])))
        XCTAssertThrowsError(try c.receive(r)) { XCTAssertEqual($0 as? HandshakeError, .rejected(3)) }
        XCTAssertThrowsError(try c.receive(Record(.reject, Data()))) { XCTAssertEqual($0 as? HandshakeError, .rejected(0)) }
    }

    /// A HELLO from a newer protocol version gets reject 1; a malformed one is refused outright.
    func testMalformedHello() throws {
        let k = Pair()
        let p = PeripheralHandshake(staticKey: k.sP)
        let good = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: nil).hello()
        var v2 = good; v2.body[0] = 2
        XCTAssertEqual(try p.receiveHello(v2, paired: nil, token: nil), Record(.reject, Data([1])))
        var badMode = good; badMode.body[1] = 9
        XCTAssertThrowsError(try p.receiveHello(badMode, paired: nil, token: nil))
        for body in [Data(), good.body.dropLast(), good.body + Data([0])] {
            XCTAssertThrowsError(try p.receiveHello(Record(.hello, Data(body)), paired: nil, token: nil)) {
                XCTAssertEqual($0 as? HandshakeError, .badLength)
            }
        }
        XCTAssertThrowsError(try p.receiveHello(Record(.helloAck, good.body), paired: nil, token: nil)) {
            XCTAssertEqual($0 as? HandshakeError, .unexpectedRecord)
        }
        // A static key that is not a point on the curve.
        var notAPoint = good; notAPoint.body.replaceSubrange((notAPoint.body.startIndex + 83)..<(notAPoint.body.startIndex + 148), with: Data(repeating: 0x04, count: 65))
        XCTAssertThrowsError(try p.receiveHello(notAPoint, paired: Data(notAPoint.body.suffix(65)), token: nil)) {
            XCTAssertEqual($0 as? HandshakeError, .badKey)
        }
    }

    /// A malformed or unexpected HELLO_ACK never yields a session.
    func testMalformedHelloAck() throws {
        let k = Pair()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: nil)
        let ack = try PeripheralHandshake(staticKey: k.sP).receiveHello(c.hello(), paired: k.sC.publicKey.x963Representation, token: nil)
        XCTAssertThrowsError(try c.receive(Record(.helloAck, ack.body.dropLast()))) { XCTAssertEqual($0 as? HandshakeError, .badLength) }
        var v = ack; v.body[0] = 9
        XCTAssertThrowsError(try c.receive(v)) { XCTAssertEqual($0 as? HandshakeError, .badVersion) }
        var e = ack; e.body.replaceSubrange((e.body.startIndex + 1)..<(e.body.startIndex + 66), with: Data(repeating: 0x04, count: 65))
        XCTAssertThrowsError(try c.receive(e)) { XCTAssertEqual($0 as? HandshakeError, .badKey) }
        XCTAssertThrowsError(try c.receive(Record(.sealed, ack.body))) { XCTAssertEqual($0 as? HandshakeError, .unexpectedRecord) }
        XCTAssertNil(c.keys)
    }

    /// Any bit changed in HELLO or HELLO_ACK on the way (transcript) makes the two sides' keys differ: FIN fails.
    func testTamperedTranscriptFailsFinish() throws {
        for target in ["hello-nonce", "ack-nonce"] {
            let k = Pair()
            let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
            let p = PeripheralHandshake(staticKey: k.sP)
            var hello = c.hello()
            if target == "hello-nonce" { hello.body[70] ^= 0x01 }
            var ack = try p.receiveHello(hello, paired: nil, token: token)
            if target == "ack-nonce" { ack.body[70] ^= 0x01 }
            let (_, fin) = try c.receive(ack)
            XCTAssertThrowsError(try p.receiveFinish(fin), target) { XCTAssertEqual($0 as? SessionError, .authFailed) }
        }
    }

    /// Key confirmation fails closed: a FIN under the right keys but carrying something other than the transcript
    /// hash, or on another channel, is refused.
    func testFinishWithWrongContentOrChannel() throws {
        let k = Pair()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
        let p = PeripheralHandshake(staticKey: k.sP)
        let ack = try p.receiveHello(c.hello(), paired: nil, token: token)
        _ = try c.receive(ack)
        let keys = try XCTUnwrap(c.keys)
        let wrongPayload = try SecureSession(sendKey: keys.c2p, recvKey: keys.p2c).seal(channel: Proto.channelFinish, Data(count: 32))
        XCTAssertThrowsError(try PeripheralHandshake(staticKey: k.sP).receiveFinish(wrongPayload))   // no hello: unexpected
        XCTAssertThrowsError(try p.receiveFinish(wrongPayload)) { XCTAssertEqual($0 as? HandshakeError, .finishMismatch) }

        let c2 = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
        let p2 = PeripheralHandshake(staticKey: k.sP)
        _ = try c2.receive(try p2.receiveHello(c2.hello(), paired: nil, token: token))
        let k2 = try XCTUnwrap(c2.keys)
        let wrongChannel = try SecureSession(sendKey: k2.c2p, recvKey: k2.p2c).seal(channel: Proto.channelControl, k2.transcriptHash)
        XCTAssertThrowsError(try p2.receiveFinish(wrongChannel)) { XCTAssertEqual($0 as? HandshakeError, .finishMismatch) }
    }

    /// The central refuses a reject, a FIN of another session, or a plain record in place of the peripheral's FIN.
    func testCentralVerifyFinishNegative() throws {
        let k = Pair()
        let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: token)
        let p = PeripheralHandshake(staticKey: k.sP)
        let (cs, fin) = try c.receive(try p.receiveHello(c.hello(), paired: nil, token: token))
        let (_, pfin) = try p.receiveFinish(fin)
        XCTAssertThrowsError(try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: Record(.reject, Data([4])))) {
            XCTAssertEqual($0 as? HandshakeError, .rejected(4))
        }
        XCTAssertThrowsError(try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: Record(.hello, pfin.body))) {
            XCTAssertEqual($0 as? SessionError, .notSealed)
        }
        let (_, _, other) = try handshake()
        let foreign = try SecureSession(sendKey: other.keys!.p2c, recvKey: other.keys!.c2p).seal(channel: Proto.channelFinish, other.keys!.transcriptHash)
        XCTAssertThrowsError(try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: foreign))
        try CentralHandshake.verifyFinish(cs, keys: c.keys!, record: pfin)   // the real one still works afterwards
    }

    /// A new session on the same pairing gets fresh keys (fresh ephemerals and nonces), so its counters starting at
    /// 0 again never reuse an AES-GCM nonce under the same key.
    func testRestartedSessionHasFreshKeys() throws {
        let k = Pair()
        var seen = Set<Data>()
        for _ in 0..<5 {
            let c = CentralHandshake(staticKey: k.sC, peerStatic: k.sP.publicKey.x963Representation, token: nil)
            let p = PeripheralHandshake(staticKey: k.sP)
            _ = try c.receive(try p.receiveHello(c.hello(), paired: k.sC.publicKey.x963Representation, token: nil))
            let keys = try XCTUnwrap(c.keys)
            XCTAssertNotEqual(keys.c2p, keys.p2c, "each direction has its own key")
            XCTAssertTrue(seen.insert(keys.c2p).inserted); XCTAssertTrue(seen.insert(keys.p2c).inserted)
        }
    }

    /// Same keys, same counters, different session: records of an old session do not open in a new one.
    func testRecordsFromAnOldSessionDoNotOpen() throws {
        let k = Pair()
        let (oldC, _, _) = try handshake(k)
        let (_, newP, _) = try handshake(k)
        let stale = try oldC.seal(channel: Proto.channelControl, Data("x".utf8))   // ctr 1, as newP expects
        XCTAssertThrowsError(try newP.open(stale)) { XCTAssertEqual($0 as? SessionError, .authFailed) }
    }

    // MARK: record layer

    func testCountersAreStrictAndFailuresDoNotAdvance() throws {
        let (c, p, _) = try handshake()
        let r1 = try c.seal(channel: 0, Data("1".utf8)), r2 = try c.seal(channel: 0, Data("2".utf8))
        // Reordered: r2 first is refused, and the refusal does not consume r1's slot.
        XCTAssertThrowsError(try p.open(r2)) { XCTAssertEqual($0 as? SessionError, .counterMismatch(expected: 1, got: 2)) }
        var bad = r1; bad.body[10] ^= 0x80
        XCTAssertThrowsError(try p.open(bad)) { XCTAssertEqual($0 as? SessionError, .authFailed) }
        XCTAssertEqual(p.recvCounter, 1)
        XCTAssertEqual(try p.open(r1).1, Data("1".utf8))
        XCTAssertEqual(try p.open(r2).1, Data("2".utf8))
        XCTAssertThrowsError(try p.open(r1)) { XCTAssertEqual($0 as? SessionError, .counterMismatch(expected: 3, got: 1)) }
    }

    /// Every byte of a sealed record is covered: counter, channel, ciphertext and tag.
    func testEveryByteIsAuthenticated() throws {
        let (c, p, _) = try handshake()
        let r = try c.seal(channel: Proto.channelAudio, Data("payload bytes".utf8))
        for i in 0..<r.body.count {
            var t = r; t.body[t.body.startIndex + i] ^= 0x01
            XCTAssertThrowsError(try p.open(t), "byte \(i)")
        }
        XCTAssertEqual(try p.open(r).0, Proto.channelAudio)
    }

    /// Own records looped back (a reflection) do not open: each direction has its own key.
    func testReflectionIsRefused() throws {
        let (c, _, _) = try handshake()
        let r = try c.seal(channel: 0, Data("echo".utf8))
        XCTAssertThrowsError(try c.open(r)) { XCTAssertEqual($0 as? SessionError, .authFailed) }
    }

    func testTruncatedAndWrongKindRecords() throws {
        let (c, p, _) = try handshake()
        let r = try c.seal(channel: 0, Data())
        XCTAssertEqual(r.body.count, 25, "counter + channel byte + tag")
        XCTAssertThrowsError(try p.open(Record(.sealed, r.body.dropLast()))) { XCTAssertEqual($0 as? SessionError, .tooShort) }
        XCTAssertThrowsError(try p.open(Record(.sealed, Data()))) { XCTAssertEqual($0 as? SessionError, .tooShort) }
        XCTAssertThrowsError(try p.open(Record(.helloAck, r.body))) { XCTAssertEqual($0 as? SessionError, .notSealed) }
        XCTAssertThrowsError(try p.open(Record(kind: 0x11, body: r.body))) { XCTAssertEqual($0 as? SessionError, .notSealed) }
        let (ch, payload) = try p.open(r)
        XCTAssertEqual(ch, 0); XCTAssertTrue(payload.isEmpty)
    }

    /// Records sealed from many threads at once each get their own counter (the lock).
    func testConcurrentSealsUseDistinctCounters() throws {
        let (c, p, _) = try handshake()
        let lock = NSLock()
        var records: [Record] = []
        DispatchQueue.concurrentPerform(iterations: 200) { i in
            let r = try! c.seal(channel: 0, Data("\(i)".utf8))
            lock.lock(); records.append(r); lock.unlock()
        }
        XCTAssertEqual(c.sendCounter, 201)
        let sorted = records.sorted { $0.body.readBE64(at: 0) < $1.body.readBE64(at: 0) }
        XCTAssertEqual(Set(sorted.map { $0.body.readBE64(at: 0) }).count, 200)
        for r in sorted { _ = try p.open(r) }
    }

    /// The nonce and AAD layout both implementations share: 4 zero bytes then the counter, kind then the counter.
    func testNonceLayoutIsCounterBigEndian() {
        XCTAssertEqual(SecureSession.nonce(0x0102030405060708).hex, "000000000102030405060708")
        XCTAssertEqual(SecureSession.aad(1).hex, "100000000000000001")
    }
}

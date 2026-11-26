#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

/// A P-256 key-agreement private key: the long-term identity of a phone or a client, and the handshake's ephemeral
/// keys. It is `P256.KeyAgreement.PrivateKey` from CryptoKit on Apple platforms and from swift-crypto elsewhere, so
/// code using this name needs neither import.
public typealias TetherPrivateKey = P256.KeyAgreement.PrivateKey

public enum HandshakeMode: UInt8, Sendable {
    case resume = 0
    case pair = 1
}

public enum HandshakeError: Error, Equatable {
    case badVersion
    case badLength
    case badKey
    case rejected(UInt8)
    case finishMismatch
    case unexpectedRecord
}

public enum Proto {
    public static let version: UInt8 = 1
    public static let thLabel = Data("TETHER-HS-1".utf8)
    public static let hkdfInfo = Data("tether/1 keys".utf8)
    public static let helloLength = 148
    public static let helloAckLength = 82
    public static let channelControl: UInt8 = 0x00
    public static let channelAudio: UInt8 = 0x01
    public static let channelFinish: UInt8 = 0xFF
}

public struct SessionKeys: Equatable, Sendable {
    public let transcriptHash: Data
    public let c2p: Data
    public let p2c: Data
}

/// Pure key schedule (protocol §3), shared by both roles and the test vectors.
public enum KeySchedule {
    public static func transcriptHash(hello: Data, helloAck: Data) -> Data {
        var h = SHA256()
        h.update(data: Proto.thLabel)
        h.update(data: hello)
        h.update(data: helloAck)
        return Data(h.finalize())
    }

    public static func derive(dh1: Data, dh2: Data, dh3: Data, psk: Data, th: Data) -> SessionKeys {
        let ikm = dh1 + dh2 + dh3 + psk
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                         salt: th, info: Proto.hkdfInfo, outputByteCount: 64)
        let bytes = okm.withUnsafeBytes { Data($0) }
        return SessionKeys(transcriptHash: th, c2p: bytes.prefix(32), p2c: Data(bytes.suffix(32)))
    }

    public static func ecdh(_ priv: P256.KeyAgreement.PrivateKey, _ pubX963: Data) throws -> Data {
        guard let pub = try? P256.KeyAgreement.PublicKey(x963Representation: pubX963) else {
            throw HandshakeError.badKey
        }
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }
}

public struct HelloMessage: Equatable, Sendable {
    public var version: UInt8 = Proto.version
    public var mode: HandshakeMode
    public var ephemeral: Data
    public var nonce: Data
    public var staticKey: Data

    public init(mode: HandshakeMode, ephemeral: Data, nonce: Data, staticKey: Data) {
        self.mode = mode
        self.ephemeral = ephemeral
        self.nonce = nonce
        self.staticKey = staticKey
    }

    public func encoded() -> Data {
        var d = Data([version, mode.rawValue])
        d += ephemeral + nonce + staticKey
        return d
    }

    public static func parse(_ d: Data) throws -> HelloMessage {
        guard d.count == Proto.helloLength else { throw HandshakeError.badLength }
        let b = [UInt8](d)
        guard b[0] == Proto.version else { throw HandshakeError.badVersion }
        guard let mode = HandshakeMode(rawValue: b[1]) else { throw HandshakeError.badLength }
        return HelloMessage(mode: mode, ephemeral: Data(b[2..<67]), nonce: Data(b[67..<83]),
                            staticKey: Data(b[83..<148]))
    }
}

public struct HelloAckMessage: Equatable, Sendable {
    public var version: UInt8 = Proto.version
    public var ephemeral: Data
    public var nonce: Data

    public init(ephemeral: Data, nonce: Data) {
        self.ephemeral = ephemeral
        self.nonce = nonce
    }

    public func encoded() -> Data { Data([version]) + ephemeral + nonce }

    public static func parse(_ d: Data) throws -> HelloAckMessage {
        guard d.count == Proto.helloAckLength else { throw HandshakeError.badLength }
        let b = [UInt8](d)
        guard b[0] == Proto.version else { throw HandshakeError.badVersion }
        return HelloAckMessage(ephemeral: Data(b[1..<66]), nonce: Data(b[66..<82]))
    }
}

public func randomBytes(_ n: Int) -> Data {
    var g = SystemRandomNumberGenerator()
    return Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &g) })
}

/// Central (iPhone) side of the handshake.
public final class CentralHandshake {
    public let mode: HandshakeMode
    private let staticKey: P256.KeyAgreement.PrivateKey
    private let ephemeral: P256.KeyAgreement.PrivateKey
    private let peerStatic: Data
    private let psk: Data
    private let nonce: Data
    public private(set) var helloBody = Data()
    public private(set) var keys: SessionKeys?

    public init(staticKey: P256.KeyAgreement.PrivateKey, peerStatic: Data, token: Data?,
                ephemeral: P256.KeyAgreement.PrivateKey = .init(), nonce: Data = randomBytes(16)) {
        self.staticKey = staticKey
        self.ephemeral = ephemeral
        self.peerStatic = peerStatic
        self.psk = token ?? Data()
        self.mode = token == nil ? .resume : .pair
        self.nonce = nonce
    }

    public func hello() -> Record {
        let msg = HelloMessage(mode: mode, ephemeral: ephemeral.publicKey.x963Representation,
                               nonce: nonce, staticKey: staticKey.publicKey.x963Representation)
        helloBody = msg.encoded()
        return Record(.hello, helloBody)
    }

    /// Process HELLO_ACK; returns the session and the FIN record to send.
    public func receive(_ record: Record) throws -> (SecureSession, Record) {
        if record.kind == RecordKind.reject.rawValue {
            throw HandshakeError.rejected(record.body.first ?? 0)
        }
        guard record.kind == RecordKind.helloAck.rawValue else { throw HandshakeError.unexpectedRecord }
        let ack = try HelloAckMessage.parse(record.body)
        let dh1 = try KeySchedule.ecdh(ephemeral, ack.ephemeral)
        let dh2 = try KeySchedule.ecdh(ephemeral, peerStatic)
        let dh3 = try KeySchedule.ecdh(staticKey, ack.ephemeral)
        let th = KeySchedule.transcriptHash(hello: helloBody, helloAck: record.body)
        let k = KeySchedule.derive(dh1: dh1, dh2: dh2, dh3: dh3, psk: psk, th: th)
        keys = k
        let session = SecureSession(sendKey: k.c2p, recvKey: k.p2c)
        let fin = try session.seal(channel: Proto.channelFinish, th)
        return (session, fin)
    }

    /// Check the peripheral's FIN.
    public static func verifyFinish(_ session: SecureSession, keys: SessionKeys, record: Record) throws {
        if record.kind == RecordKind.reject.rawValue {
            throw HandshakeError.rejected(record.body.first ?? 0)
        }
        let (ch, payload) = try session.open(record)
        guard ch == Proto.channelFinish, payload == keys.transcriptHash else {
            throw HandshakeError.finishMismatch
        }
    }
}

/// Peripheral (phone) side of the handshake. The real peripheral is the Android app; this one serves tests, the
/// examples and other phone-side implementations.
public final class PeripheralHandshake {
    private let staticKey: P256.KeyAgreement.PrivateKey
    private let ephemeral: P256.KeyAgreement.PrivateKey
    private let nonce: Data
    public private(set) var hello: HelloMessage?
    public private(set) var keys: SessionKeys?
    private var session: SecureSession?

    public init(staticKey: P256.KeyAgreement.PrivateKey,
                ephemeral: P256.KeyAgreement.PrivateKey = .init(), nonce: Data = randomBytes(16)) {
        self.staticKey = staticKey
        self.ephemeral = ephemeral
        self.nonce = nonce
    }

    /// `acceptStatic` decides whether a central's static key is allowed; `token` is the open pairing token.
    public func receiveHello(_ record: Record, paired: Data?, token: Data?) throws -> Record {
        guard record.kind == RecordKind.hello.rawValue else { throw HandshakeError.unexpectedRecord }
        let h: HelloMessage
        do { h = try HelloMessage.parse(record.body) } catch HandshakeError.badVersion {
            return Record(.reject, Data([1]))
        }
        hello = h
        let psk: Data
        switch h.mode {
        case .resume:
            guard let paired, paired == h.staticKey else { return Record(.reject, Data([2])) }
            psk = Data()
        case .pair:
            guard let token else { return Record(.reject, Data([3])) }
            psk = token
        }
        let ack = HelloAckMessage(ephemeral: ephemeral.publicKey.x963Representation, nonce: nonce).encoded()
        let dh1 = try KeySchedule.ecdh(ephemeral, h.ephemeral)
        let dh2 = try KeySchedule.ecdh(staticKey, h.ephemeral)
        let dh3 = try KeySchedule.ecdh(ephemeral, h.staticKey)
        let th = KeySchedule.transcriptHash(hello: record.body, helloAck: ack)
        let k = KeySchedule.derive(dh1: dh1, dh2: dh2, dh3: dh3, psk: psk, th: th)
        keys = k
        session = SecureSession(sendKey: k.p2c, recvKey: k.c2p)
        return Record(.helloAck, ack)
    }

    public func receiveFinish(_ record: Record) throws -> (SecureSession, Record) {
        guard let session, let keys else { throw HandshakeError.unexpectedRecord }
        let (ch, payload) = try session.open(record)
        guard ch == Proto.channelFinish, payload == keys.transcriptHash else {
            throw HandshakeError.finishMismatch
        }
        return (session, try session.seal(channel: Proto.channelFinish, keys.transcriptHash))
    }
}

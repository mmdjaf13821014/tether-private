#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

public enum SessionError: Error, Equatable {
    case notSealed
    case tooShort
    case counterMismatch(expected: UInt64, got: UInt64)
    case authFailed
    case exhausted
}

/// AES-256-GCM record layer with one strict counter per direction (protocol §4).
public final class SecureSession: @unchecked Sendable {
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private let lock = NSLock()
    public private(set) var sendCounter: UInt64 = 0
    public private(set) var recvCounter: UInt64 = 0

    public init(sendKey: Data, recvKey: Data) {
        self.sendKey = SymmetricKey(data: sendKey)
        self.recvKey = SymmetricKey(data: recvKey)
    }

    static func nonce(_ ctr: UInt64) -> Data {
        var n = Data([0, 0, 0, 0])
        n.appendBE(ctr)
        return n
    }

    static func aad(_ ctr: UInt64) -> Data {
        var a = Data([RecordKind.sealed.rawValue])
        a.appendBE(ctr)
        return a
    }

    public func seal(channel: UInt8, _ payload: Data) throws -> Record {
        lock.lock(); defer { lock.unlock() }
        guard sendCounter < (1 << 32) else { throw SessionError.exhausted }
        let ctr = sendCounter
        sendCounter += 1
        return try Self.seal(key: sendKey, ctr: ctr, plaintext: Data([channel]) + payload)
    }

    public static func seal(key: SymmetricKey, ctr: UInt64, plaintext: Data) throws -> Record {
        let box = try AES.GCM.seal(plaintext, using: key,
                                   nonce: AES.GCM.Nonce(data: nonce(ctr)),
                                   authenticating: aad(ctr))
        var body = Data()
        body.appendBE(ctr)
        body += box.ciphertext + box.tag
        return Record(.sealed, body)
    }

    public func open(_ record: Record) throws -> (UInt8, Data) {
        lock.lock(); defer { lock.unlock() }
        guard record.kind == RecordKind.sealed.rawValue else { throw SessionError.notSealed }
        let body = record.body
        guard body.count >= 8 + 16 + 1 else { throw SessionError.tooShort }
        let ctr = body.readBE64(at: 0)
        guard ctr == recvCounter else { throw SessionError.counterMismatch(expected: recvCounter, got: ctr) }
        let ct = body.subdata(in: (body.startIndex + 8)..<(body.endIndex - 16))
        let tag = body.suffix(16)
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Self.nonce(ctr)), ciphertext: ct, tag: tag)
            plain = try AES.GCM.open(box, using: recvKey, authenticating: Self.aad(ctr))
        } catch {
            throw SessionError.authFailed
        }
        recvCounter += 1
        return (plain[plain.startIndex], Data(plain.dropFirst()))
    }
}

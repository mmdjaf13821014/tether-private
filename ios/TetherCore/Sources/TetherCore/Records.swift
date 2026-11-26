import Foundation

/// Record kinds on the wire (protocol §2).
public enum RecordKind: UInt8, Sendable {
    case hello = 0x01
    case helloAck = 0x02
    case reject = 0x03
    case sealed = 0x10
}

public struct Record: Equatable, Sendable {
    public var kind: UInt8
    public var body: Data

    public init(kind: UInt8, body: Data) {
        self.kind = kind
        self.body = body
    }

    public init(_ kind: RecordKind, _ body: Data) {
        self.init(kind: kind.rawValue, body: body)
    }

    /// `len: u16 ‖ kind ‖ body`.
    public func encoded() -> Data {
        precondition(body.count <= 65534, "record body too large")
        let len = UInt16(body.count + 1)
        var out = Data(capacity: body.count + 3)
        out.append(UInt8(len >> 8))
        out.append(UInt8(len & 0xff))
        out.append(kind)
        out.append(body)
        return out
    }
}

public enum FramingError: Error, Equatable {
    case zeroLength
}

/// Accumulates stream bytes and yields complete records. Chunk boundaries carry no meaning.
public struct RecordReader: Sendable {
    private var buffer = Data()

    public init() {}

    public var pendingBytes: Int { buffer.count }

    public mutating func append(_ data: Data) {
        buffer.append(data)
    }

    /// Returns the next complete record, or nil if more bytes are needed.
    public mutating func next() throws -> Record? {
        guard buffer.count >= 2 else { return nil }
        let start = buffer.startIndex
        let len = Int(buffer[start]) << 8 | Int(buffer[start + 1])
        if len == 0 { throw FramingError.zeroLength }
        guard buffer.count >= 2 + len else { return nil }
        let kind = buffer[start + 2]
        let body = Data(buffer[(start + 3)..<(start + 2 + len)])
        buffer = Data(buffer[(start + 2 + len)...])
        return Record(kind: kind, body: body)
    }

    public mutating func drain() throws -> [Record] {
        var out: [Record] = []
        while let r = try next() { out.append(r) }
        return out
    }
}

extension Data {
    mutating func appendBE(_ v: UInt64) {
        for shift in stride(from: 56, through: 0, by: -8) {
            append(UInt8((v >> UInt64(shift)) & 0xff))
        }
    }

    mutating func appendBE(_ v: UInt16) {
        append(UInt8(v >> 8))
        append(UInt8(v & 0xff))
    }

    func readBE64(at offset: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 { v = v << 8 | UInt64(self[startIndex + offset + i]) }
        return v
    }

    func readBE16(at offset: Int) -> UInt16 {
        UInt16(self[startIndex + offset]) << 8 | UInt16(self[startIndex + offset + 1])
    }

    public var hex: String { map { String(format: "%02x", $0) }.joined() }

    public init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var out = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let b = UInt8(hex[idx..<next], radix: 16) else { return nil }
            out.append(b)
            idx = next
        }
        self = out
    }

    public func base64URL() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public init?(base64URL s: String) {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        guard let d = Data(base64Encoded: t) else { return nil }
        self = d
    }
}

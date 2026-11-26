import Foundation
import XCTest
@testable import TetherCore

/// The record stream (L2CAP and GATT): chunk boundaries carry no meaning, and broken input ends in an error or
/// waits for more bytes, never in a wrong record.
final class RecordStreamTests: XCTestCase {
    func testSeveralRecordsInOneChunk() throws {
        let rs = [Record(.hello, Data([1, 2])), Record(.sealed, Data()), Record(kind: 0x7F, body: Data(repeating: 3, count: 300))]
        var reader = RecordReader()
        reader.append(rs.reduce(Data()) { $0 + $1.encoded() })
        XCTAssertEqual(try reader.drain(), rs)
        XCTAssertEqual(reader.pendingBytes, 0)
        XCTAssertNil(try reader.next())
    }

    /// An empty body is a valid record (length 1: the kind byte alone).
    func testEmptyBody() throws {
        let r = Record(.reject, Data())
        XCTAssertEqual(r.encoded(), Data([0, 1, 3]))
        var reader = RecordReader()
        reader.append(r.encoded())
        XCTAssertEqual(try reader.next(), r)
    }

    /// Byte by byte, including a split inside the 2-byte length.
    func testOneByteAtATime() throws {
        let r = Record(.sealed, Data(repeating: 0xAB, count: 0x1FF))
        var reader = RecordReader()
        let bytes = r.encoded()
        for (i, b) in bytes.enumerated() {
            reader.append(Data([b]))
            let got = try reader.next()
            if i < bytes.count - 1 { XCTAssertNil(got) } else { XCTAssertEqual(got, r) }
        }
    }

    /// A length claiming more than arrived waits (the session's stall check ends it), it does not misparse.
    func testOversizedLengthWaits() throws {
        var reader = RecordReader()
        reader.append(Data([0xFF, 0xFF, 0x10]) + Data(repeating: 0, count: 100))
        XCTAssertNil(try reader.next())
        XCTAssertEqual(reader.pendingBytes, 103)
    }

    /// A zero length after good records: the good ones come out first, then the stream fails.
    func testZeroLengthAfterGoodRecords() throws {
        var reader = RecordReader()
        let good = Record(.hello, Data([9]))
        reader.append(good.encoded() + Data([0, 0, 5, 5]))
        XCTAssertEqual(try reader.next(), good)
        XCTAssertThrowsError(try reader.next()) { XCTAssertEqual($0 as? FramingError, .zeroLength) }
        var r2 = RecordReader()
        r2.append(good.encoded() + Data([0, 0]))
        XCTAssertThrowsError(try r2.drain())
    }

    /// Random garbage never crashes the reader and never yields a record that opens in a session.
    func testGarbageNeverOpens() throws {
        var rng = SeededRandom(seed: 0xC0FFEE)
        let rx = SecureSession(sendKey: Data(repeating: 2, count: 32), recvKey: Data(repeating: 1, count: 32))
        for _ in 0..<300 {
            var reader = RecordReader()
            let n = Int.random(in: 0..<400, using: &rng)
            reader.append(Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &rng) }))
            while let r = try? reader.next() {
                XCTAssertThrowsError(try rx.open(r))
            }
        }
    }

    func testRecordRoundTripsBetweenChunkings() throws {
        var rng = SeededRandom(seed: 42)
        let records = (0..<40).map { i in Record(kind: UInt8(i), body: Data((0..<Int.random(in: 0..<600, using: &rng)).map { UInt8($0 & 0xff) })) }
        let stream = records.reduce(Data()) { $0 + $1.encoded() }
        for _ in 0..<20 {
            var reader = RecordReader(), got: [Record] = [], i = 0
            while i < stream.count {
                let end = min(stream.count, i + Int.random(in: 1...700, using: &rng))
                reader.append(stream.subdata(in: i..<end)); got += try reader.drain(); i = end
            }
            XCTAssertEqual(got, records)
        }
    }

    func testHexAndBase64URL() {
        let d = Data([0, 0xFB, 0xFF, 0x10])
        XCTAssertEqual(d.hex, "00fbff10")
        XCTAssertEqual(Data(hex: "00fbff10"), d)
        XCTAssertNil(Data(hex: "abc")); XCTAssertNil(Data(hex: "zz"))
        XCTAssertEqual(d.base64URL(), "APv_EA")
        XCTAssertEqual(Data(base64URL: "APv_EA"), d)
        XCTAssertNil(Data(base64URL: "!!!"))
        var rng = SeededRandom(seed: 7)
        for n in 0..<40 {
            let x = Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &rng) })
            XCTAssertEqual(Data(base64URL: x.base64URL()), x)
            XCTAssertFalse(x.base64URL().contains { "+/=".contains($0) })
        }
    }
}

/// GATT chunking beyond loss and reordering: any chunk size the link negotiates delivers the same messages.
final class GattChunkSizeTests: XCTestCase {
    private let k1 = Data(repeating: 1, count: 32), k2 = Data(repeating: 2, count: 32)

    func testEveryChunkSizeDelivers() throws {
        let tx = SecureSession(sendKey: k1, recvKey: k2)
        var bytes = Data()
        let msgs = (0..<10).map { "message \($0) " + String(repeating: "x", count: $0 * 30) }
        for m in msgs { bytes += try tx.seal(channel: Proto.channelControl, Data(m.utf8)).encoded() }
        for size in [20, 23, 100, 182, 244, 512, bytes.count] {
            let rx = SecureSession(sendKey: k2, recvKey: k1)
            var reader = RecordReader(), got: [String] = []
            for off in stride(from: 0, to: bytes.count, by: size) {
                reader.append(bytes.subdata(in: off..<min(off + size, bytes.count)))
                while let r = try reader.next() { got.append(String(decoding: try rx.open(r).1, as: UTF8.self)) }
            }
            XCTAssertEqual(got, msgs, "chunk \(size)")
        }
    }

    /// A chunk from a stale session (old keys) in the middle of the stream ends it with an error.
    func testStaleSessionChunkIsDetected() throws {
        let tx = SecureSession(sendKey: k1, recvKey: k2), old = SecureSession(sendKey: k2, recvKey: k1)
        let rx = SecureSession(sendKey: k2, recvKey: k1)
        var reader = RecordReader()
        reader.append(try tx.seal(channel: 0, Data("a".utf8)).encoded())
        reader.append(try old.seal(channel: 0, Data("stale".utf8)).encoded())
        XCTAssertEqual(try rx.open(try XCTUnwrap(try reader.next())).1, Data("a".utf8))
        XCTAssertThrowsError(try rx.open(try XCTUnwrap(try reader.next())))
    }
}

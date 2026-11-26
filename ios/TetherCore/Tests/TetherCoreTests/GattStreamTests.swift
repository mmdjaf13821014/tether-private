import XCTest
@testable import TetherCore

/// The GATT transport carries the record stream in small chunks. A lost, duplicated or reordered chunk must never
/// deliver a wrong message: it ends in an error, or leaves the stream stuck mid-record (TetherLink ends such a
/// session after 10 s, as the Android side does).
final class GattStreamTests: XCTestCase {
    private let k1 = Data(repeating: 1, count: 32), k2 = Data(repeating: 2, count: 32)

    private func chunks(_ n: Int) throws -> [Data] {
        let tx = SecureSession(sendKey: k1, recvKey: k2)
        var bytes = Data()
        for i in 0..<n { bytes += try tx.seal(channel: Proto.channelControl, Data("message \(i) padded to be longer than one chunk".utf8)).encoded() }
        return stride(from: 0, to: bytes.count, by: 20).map { Data(bytes[bytes.startIndex + $0 ..< bytes.startIndex + min($0 + 20, bytes.count)]) }
    }

    /// Messages received, whether an error ended the stream, and bytes left half received.
    private func receive(_ cs: [Data]) -> (msgs: [String], failed: Bool, left: Int) {
        let rx = SecureSession(sendKey: k2, recvKey: k1)
        var reader = RecordReader()
        var out: [String] = []
        do {
            for c in cs {
                reader.append(c)
                while let r = try reader.next() { out.append(String(decoding: try rx.open(r).1, as: UTF8.self)) }
            }
        } catch { return (out, true, reader.pendingBytes) }
        return (out, false, reader.pendingBytes)
    }

    func testInOrder() throws {
        let r = receive(try chunks(20))
        XCTAssertFalse(r.failed); XCTAssertEqual(r.msgs.count, 20); XCTAssertEqual(r.left, 0)
    }

    func testLossIsNeverMisdelivered() throws {
        let cs = try chunks(20)
        for drop in [0, 1, 7, cs.count / 2, cs.count - 1] {
            var lossy = cs; lossy.remove(at: drop)
            let r = receive(lossy)
            XCTAssertTrue(r.msgs.allSatisfy { $0.hasPrefix("message ") && $0.hasSuffix("chunk") })
            XCTAssertTrue(r.failed || r.left > 0, "loss at \(drop) must be detected")
            XCTAssertLessThan(r.msgs.count, 20)
        }
    }

    func testDuplicateOrReorderIsDetected() throws {
        let cs = try chunks(20)
        var dup = cs; dup.insert(cs[5], at: 5)
        let a = receive(dup); XCTAssertTrue(a.failed || a.left > 0)
        var swapped = cs; swapped.swapAt(3, 4)
        let b = receive(swapped); XCTAssertTrue(b.failed || b.left > 0)
    }
}

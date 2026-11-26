#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import XCTest
@testable import TetherCore

/// Pairing QR codes, the G.711 fallback codec, the jitter buffer and search folding.
final class UtilTests: XCTestCase {
    private func code() -> PairingCode {
        PairingCode(peripheralKey: P256.KeyAgreement.PrivateKey().publicKey.x963Representation, token: Data(repeating: 5, count: 16), deviceName: "Test Phone ۱")
    }

    func testPairingCodeRoundTripAndWhitespace() {
        let c = code()
        XCTAssertEqual(PairingCode.parse(c.string), c)
        XCTAssertEqual(PairingCode.parse("  \n" + c.string + "\n"), c)
        XCTAssertEqual(PairingCode.parse(PairingCode(peripheralKey: c.peripheralKey, token: c.token, deviceName: "").string)?.deviceName, "")
    }

    /// A scanned code that is damaged or of another shape is refused, never half-used.
    func testPairingLinkForms() {
        let c = code()
        XCTAssertTrue(c.link.hasPrefix("tether://pair?c="))
        XCTAssertEqual(PairingCode.parse(c.link), c)
        XCTAssertEqual(PairingCode.parse(" " + c.link + "\n"), c)
        let body = String(c.string.dropFirst(PairingCode.prefix.count))
        XCTAssertEqual(PairingCode.parse("tether:pair?c=" + body), c)
        XCTAssertEqual(PairingCode.parse("TETHER://PAIR?v=2&c=" + body), c)
        XCTAssertEqual(PairingCode.parse(URL(string: c.link)!.absoluteString), c)
        XCTAssertTrue(PairingCode.isPairLink(c.link))
        XCTAssertFalse(PairingCode.isPairLink(c.string))
        XCTAssertFalse(PairingCode.isPairLink("tether://dial?number=1"))
        XCTAssertNil(PairingCode.parse("tether://pair?c=" + body + ".x"))
        XCTAssertNil(PairingCode.parse("tether://pair"))
    }

    func testPairingCodeRejectsDamage() {
        let c = code()
        let parts = c.string.dropFirst(PairingCode.prefix.count).split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        let shortKey = Data(c.peripheralKey.dropLast()).base64URL()
        var compressed = c.peripheralKey; compressed[0] = 0x02
        let bads: [String] = [
            "TETHER1:" + parts.joined(separator: "."),
            PairingCode.prefix + parts[0] + "." + parts[1],
            PairingCode.prefix + parts.joined(separator: ".") + ".x",
            PairingCode.prefix + shortKey + "." + parts[1] + "." + parts[2],
            PairingCode.prefix + compressed.base64URL() + "." + parts[1] + "." + parts[2],
            PairingCode.prefix + parts[0] + "." + Data(count: 15).base64URL() + "." + parts[2],
            PairingCode.prefix + parts[0] + "." + parts[1] + "." + Data([0xFF, 0xFE]).base64URL(),
            PairingCode.prefix + parts[0] + "." + parts[1] + "." + "!!",
        ]
        for bad in bads {
            XCTAssertNil(PairingCode.parse(bad), bad)
        }
    }

    func testG711RoundTripIsCloseAndMonotonic() {
        var prev = Int16.min
        for s in stride(from: Int(Int16.min), through: Int(Int16.max), by: 97) {
            let d = G711.decode(G711.encode(Int16(s)))
            XCTAssertLessThanOrEqual(abs(Int(d) - s), max(16, abs(s) / 16) + 132, "\(s)")
            XCTAssertGreaterThanOrEqual(d, prev, "monotonic")
            prev = d
        }
        XCTAssertEqual(G711.decode(G711.encode(0)), 0)
        for b in UInt8.min...UInt8.max { XCTAssertEqual(G711.encode(G711.decode(b)), b == 0x7F ? 0xFF : b, "\(b)") }
    }

    /// Random arrival order, duplicates, losses and sequence wrap: the jitter buffer plays each frame at most
    /// once, in sequence order, and its depth stays bounded.
    func testJitterBufferProperties() {
        var rng = SeededRandom(seed: 314)
        for start: UInt16 in [0, 65000, 65535] {
            var jb = JitterBuffer<UInt16>(targetDepth: 3, maxDepth: 12)
            var played: [UInt16] = []
            var seq = start
            for _ in 0..<2000 {
                var batch: [UInt16] = []
                for _ in 0..<Int.random(in: 0...2, using: &rng) { batch.append(seq); seq &+= 1 }
                if Int.random(in: 0..<10, using: &rng) == 0, let last = batch.last { batch.append(last) }     // duplicate
                if batch.count > 1 && Bool.random(using: &rng) { batch.swapAt(0, 1) }                          // reorder
                if Int.random(in: 0..<20, using: &rng) == 0, !batch.isEmpty { batch.removeFirst() }            // loss
                for s in batch { jb.push(seq: s, s) }
                XCTAssertLessThanOrEqual(jb.depth, jb.maxDepth)
                if let f = jb.pop() { played.append(f) }
            }
            XCTAssertGreaterThan(played.count, 1000)
            for (a, b) in zip(played, played.dropFirst()) {
                XCTAssertGreaterThan(JitterBuffer<UInt16>.distance(b, a), 0, "in order, no repeats (start \(start))")
            }
        }
    }

    func testJitterResetAndDistance() {
        XCTAssertEqual(JitterBuffer<Int>.distance(0, 65535), 1)
        XCTAssertEqual(JitterBuffer<Int>.distance(65535, 0), -1)
        XCTAssertEqual(JitterBuffer<Int>.distance(100, 100), 0)
        var jb = JitterBuffer<Int>(targetDepth: 1)
        jb.push(seq: 500, 1); jb.reset()
        jb.push(seq: 10, 2)
        XCTAssertEqual(jb.pop(), 2, "after a reset an older sequence starts a new stream")
    }

    func testSearchFolding() {
        XCTAssertEqual(SearchText.fold("ي ك ة أ إ آ"), "ی ک ه ا ا ا")
        XCTAssertEqual(SearchText.fold("می\u{200C}خواهم"), "میخواهم")
        XCTAssertTrue(SearchText.matches("میخواهم", names: ["می‌خواهم"], numbers: []))
        XCTAssertTrue(SearchText.matches("  ", names: [], numbers: []), "an empty query shows everything")
        XCTAssertTrue(SearchText.matches("ＡＢＣ", names: ["abc"], numbers: []), "full-width letters")
        XCTAssertFalse(SearchText.matches("1", names: [], numbers: ["+10000000001"]), "one digit is too short to search numbers")
    }

    func testNumberCore() {
        XCTAssertEqual(SearchText.numberCore("+98 912 123 4567"), "9121234567")
        XCTAssertEqual(SearchText.numberCore("0098 912 123 4567"), "9121234567")
        XCTAssertEqual(SearchText.numberCore("09121234567"), "9121234567")
        XCTAssertEqual(SearchText.numberCore("+1 000 000 0001"), "10000000001")
        XCTAssertEqual(SearchText.numberCore("9812"), "9812", "a short number starting 98 keeps its digits")
        XCTAssertEqual(PhoneNumber.matchKey("+1 000 000 0001"), "000000001")
        XCTAssertEqual(PhoneNumber.matchKey("12"), "12")
    }
}

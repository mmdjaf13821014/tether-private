import Foundation
import XCTest
@testable import TetherCore

/// Playout under random arrival traces (fixed seeds): whatever the link does, the buffer's books balance, its
/// target stays within its clamps and the output stays in range. Protects the call audio from silent drift.
final class PlayoutPropertyTests: XCTestCase {
    private struct Books { var pushed = 0, played = 0 }

    /// Feeds `steps` render periods of 10 ms; arrivals are random (bursts, gaps, losses, quiet and loud frames,
    /// clock jumps) and the invariants are checked after every step.
    private func run(seed: UInt64, rate: Double, steps: Int = 3000, hugeGaps: Bool = false) {
        var rng = SeededRandom(seed: seed)
        let b = PlayoutBuffer(rate: rate)
        let frame = Int(rate * 0.02), pullN = Int(rate * 0.01)
        var books = Books()
        var clock: UInt64 = 5_000_000_000
        var out = [Float](repeating: 0, count: pullN)
        var pending = 0   // frames owed by the far end
        for step in 0..<steps {
            if step % 2 == 0 { pending += 1 }
            // Deliver some of what is owed: none (a hold-up), one, or a burst.
            let r = Int.random(in: 0..<100, using: &rng)
            let deliver = r < 15 ? 0 : r < 85 ? min(pending, 1) : pending
            for _ in 0..<deliver {
                let lost = Int.random(in: 0..<100, using: &rng) < 3
                let loud = Int.random(in: 0..<100, using: &rng) < 60
                let pcm: [Int16] = loud ? (0..<frame).map { Int16(9000 * sin(Double($0) * 0.3)) } : [Int16](repeating: Int16.random(in: -3...3, using: &rng), count: frame)
                b.push(pcm, at: clock, missing: lost ? 1 : 0, concealed: Int.random(in: 0..<100, using: &rng) < 2)
                books.pushed += pcm.count
                pending -= 1
            }
            if pending > 40 { pending = 40 }
            let real = out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: pullN) }
            books.played += real
            clock += 10_000_000
            if hugeGaps && Int.random(in: 0..<1000, using: &rng) == 0 { clock += 600_000_000_000 }   // 10 minutes
            if hugeGaps && Int.random(in: 0..<2000, using: &rng) == 0 { clock -= 3_000_000_000 }    // clock stepped back

            // Checked without XCTest per step (fast); the first broken invariant fails the test with its step.
            var broken: String?
            if real < 0 || real > pullN { broken = "real \(real)" }
            else if books.pushed + b.inserted - b.trimmed - books.played != b.buffered { broken = "pushed + inserted - trimmed - played != buffered" }
            else if b.target < b.minTarget || b.target > b.maxTarget { broken = "target \(b.target) outside clamps" }
            else if b.jitterFloor < b.minTarget || b.jitterFloor > b.maxTarget { broken = "jitter floor \(b.jitterFloor)" }
            else if b.buffered > b.target + b.maxTarget + frame { broken = "buffered \(b.buffered): a burst must be cut" }
            else if !out.allSatisfy({ $0.isFinite && abs($0) <= 1 }) { broken = "output out of range" }
            else if b.speechTrimmed < 0 || b.speechTrimmed > b.trimmed { broken = "speech cut \(b.speechTrimmed) outside what was trimmed" }
            if let broken { XCTFail("\(broken) (seed \(seed), rate \(rate), step \(step))"); return }
        }
        XCTAssertGreaterThanOrEqual(b.concealedSamples, 0)
        XCTAssertGreaterThanOrEqual(b.meanBuffered, 0)
    }

    func testInvariantsAt16kHz() { for seed: UInt64 in [1, 2, 3, 4] { run(seed: seed, rate: 16000) } }
    func testInvariantsAt8kAnd48kHz() { run(seed: 11, rate: 8000); run(seed: 12, rate: 48000, steps: 1500) }
    func testInvariantsWithHugeGapsAndClockSteps() { for seed: UInt64 in [21, 22] { run(seed: seed, rate: 16000, steps: 6000, hugeGaps: true) } }

    /// After a reset (a new call on the same buffer) playout starts clean: unprimed and empty.
    func testResetStartsClean() {
        let b = PlayoutBuffer(rate: 16000)
        b.push([Int16](repeating: 4000, count: 1280), at: 1_000_000_000)
        b.reset()
        XCTAssertEqual(b.buffered, 0)
        var out = [Float](repeating: 1, count: 160)
        XCTAssertEqual(out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: 160) }, 0)
        XCTAssertTrue(out.allSatisfy { $0 == 0 }, "unprimed output is silence")
    }

    func testSampleRingWrapsAndOverwritesOldest() {
        let ring = SampleRing(capacity: 4)
        let src: [Float] = [1, 2, 3, 4, 5, 6]
        src.withUnsafeBufferPointer { ring.write($0.baseAddress!, 6) }
        XCTAssertEqual(ring.available, 4)
        var dst = [Float](repeating: 0, count: 8)
        XCTAssertEqual(dst.withUnsafeMutableBufferPointer { ring.read(into: $0.baseAddress!, 8) }, 4)
        XCTAssertEqual(Array(dst.prefix(4)), [3, 4, 5, 6])
        let stereo: [Float] = [1, -1, 2, -2]
        stereo.withUnsafeBufferPointer { ring.write($0.baseAddress!, 2, stride: 2) }
        ring.skip(1)
        XCTAssertEqual(dst.withUnsafeMutableBufferPointer { ring.read(into: $0.baseAddress!, 8) }, 1)
        XCTAssertEqual(dst[0], 2)
        ring.skip(10); ring.clear()
        XCTAssertEqual(ring.available, 0)
    }
}

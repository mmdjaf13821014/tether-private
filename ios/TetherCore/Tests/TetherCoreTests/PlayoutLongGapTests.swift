import Foundation
import XCTest
@testable import TetherCore

/// Playout over Bluetooth with tethering on (the owner's real calls, 2026-11-24): hold-ups of 40-100 ms about once a
/// second and a few long stalls (300-880 ms) after which the held frames arrive in one burst. What the listener must
/// get: every word (real speech is never cut to catch up), the delay a stall left behind removed in the pauses, and
/// no long-lasting extra delay.
final class PlayoutLongGapTests: XCTestCase {
    struct Result {
        var concealedMs = 0.0, underruns = 0, drainedMs = 0.0, speechCutMs = 0.0, addedMs = 0.0
        var meanDelayMs = 0.0, maxDelayMs = 0.0
        /// Seconds the playout delay stayed above 250 ms (the delay a stall left behind, not yet removed).
        var secondsAbove250 = 0.0
    }

    /// A conversation as 20 ms frames: talk spurts of 0.6-2.4 s with short pauses between words (40-120 ms) and a
    /// soft tail after each word, then listening pauses of 0.5-3 s. Quiet frames carry a little noise, not zeros.
    static func talk(frames: Int, seed: UInt64) -> [(pcm: [Int16], speech: Bool)] {
        var rng = SeededRandom(seed: seed)
        var out: [(pcm: [Int16], speech: Bool)] = []
        var k = 0
        func noise() -> [Int16] { (0..<320).map { _ in Int16.random(in: -40...40, using: &rng) } }
        while out.count < frames {
            var spurt = Int.random(in: 30...120, using: &rng)
            while spurt > 0 && out.count < frames {
                let word = Int.random(in: 6...20, using: &rng)
                let f = Double.random(in: 0.08...0.3, using: &rng)
                for _ in 0..<word { out.append(((0..<320).map { Int16(7000 * sin(Double($0 + k * 320) * f)) }, true)); k += 1 }
                out.append(((0..<320).map { Int16(350 * sin(Double($0 + k * 320) * f)) }, true)); k += 1   // the soft tail
                for _ in 0..<Int.random(in: 2...6, using: &rng) { out.append((noise(), false)) }
                spurt -= word + 1
            }
            for _ in 0..<Int.random(in: 25...150, using: &rng) { out.append((noise(), false)) }
        }
        return Array(out.prefix(frames))
    }

    /// Arrival times (ms) over 15 ms connection events with the far clock `drift` slow (-) or fast (+), hold-ups of
    /// 40-100 ms every 0.5-2 s, and long stalls (start ms, length ms) whose frames all arrive at their end.
    static func tethering(frames: Int, seed: UInt64, drift: Double = -0.00048, stalls: [(Double, Double)]) -> [Double] {
        var rng = SeededRandom(seed: seed)
        var holds: [(Double, Double)] = []
        var t = 500.0
        while t < Double(frames) * 20 + 5000 {
            holds.append((t, Double.random(in: 40...100, using: &rng)))
            t += Double.random(in: 500...2000, using: &rng)
        }
        holds += stalls
        return (0..<frames).map { k in
            var a = ceil((Double(k) * 20 / (1 + drift) + 0.5) / 15) * 15
            for (s, len) in holds where a >= s && a < s + len { a = s + len }
            return a
        }
    }

    static func run(_ arrivals: [Double], talk: [(pcm: [Int16], speech: Bool)]) -> Result {
        let rate = 16000.0
        let b = PlayoutBuffer(rate: rate)
        var r = Result()
        var out = [Float](repeating: 0, count: 160)
        var next = 0, pulls = 0
        var delaySum = 0.0
        var t = 0.0
        let end = (arrivals.max() ?? 0) + 3000
        while t < end {
            while next < arrivals.count && arrivals[next] <= t {
                b.push(talk[next].pcm, at: UInt64((arrivals[next] + 1_000_000) * 1e6))
                next += 1
            }
            out.withUnsafeMutableBufferPointer { _ = b.pull(into: $0.baseAddress!, count: 160) }
            let d = Double(b.buffered) / rate * 1000
            delaySum += d; pulls += 1
            r.maxDelayMs = max(r.maxDelayMs, d)
            if d > 250 { r.secondsAbove250 += 0.01 }
            t += 10
        }
        r.concealedMs = Double(b.concealedSamples) / rate * 1000
        r.underruns = b.underruns
        r.drainedMs = Double(b.trimmed) / rate * 1000
        r.speechCutMs = Double(b.speechTrimmed) / rate * 1000
        r.addedMs = Double(b.inserted) / rate * 1000
        r.meanDelayMs = delaySum / Double(pulls)
        return r
    }

    static func report(_ name: String, _ r: Result) -> String {
        String(format: "%@: concealed %.0f ms, underruns %d, drained %.0f ms (speech %.0f ms), added %.0f ms, delay mean %.0f max %.0f ms, %.1f s above 250 ms",
               name, r.concealedMs, r.underruns, r.drainedMs, r.speechCutMs, r.addedMs, r.meanDelayMs, r.maxDelayMs, r.secondsAbove250)
    }

    static let frames = 6000   // 2 minutes, as the owner's call

    /// The owner's 2-minute call with tethering on, with stalls of 300, 435 and 880 ms.
    func testStallsNeverCutSpeech() {
        let talk = Self.talk(frames: Self.frames, seed: 7)
        let tr = Self.tethering(frames: Self.frames, seed: 7, stalls: [(20_000, 300), (55_000, 435), (90_000, 880)])
        let r = Self.run(tr, talk: talk)
        print("[trace] " + Self.report("tethering+stalls", r))
        XCTAssertEqual(r.speechCutMs, 0, "real speech is never dropped to catch up after a stall")
        XCTAssertLessThan(r.secondsAbove250, 12, "the delay a stall leaves is removed in the pauses within a few seconds")
        XCTAssertLessThan(r.meanDelayMs, 110)
    }

    /// Without long stalls the frequent hold-ups alone: few concealments, nothing cut from speech.
    func testFrequentHoldUps() {
        let talk = Self.talk(frames: Self.frames, seed: 3)
        let r = Self.run(Self.tethering(frames: Self.frames, seed: 3, stalls: []), talk: talk)
        print("[trace] " + Self.report("tethering", r))
        XCTAssertEqual(r.speechCutMs, 0)
        XCTAssertEqual(r.secondsAbove250, 0)
    }

    /// A very long stall (10 s, seen once at a call's end): the backlog is bounded, the newest audio plays, and the
    /// delay never grows past the bound.
    func testVeryLongStallIsBounded() {
        let talk = Self.talk(frames: 3000, seed: 5)
        let tr = Self.tethering(frames: 3000, seed: 5, stalls: [(20_000, 10_000)])
        let r = Self.run(tr, talk: talk)
        print("[trace] " + Self.report("10 s stall", r))
        XCTAssertLessThanOrEqual(r.maxDelayMs, PlayoutBuffer.maxBacklogSeconds * 1000 + 25)
    }

    /// A stall's burst is no measure of the link's spread: the target is not held at its maximum after it (with the
    /// backlog kept, that maximum would be the delay for the next ~10 s).
    func testStallDoesNotRaiseTheArrivalSpread() {
        let talk = Self.talk(frames: 1500, seed: 9)
        let tr = Self.tethering(frames: 1500, seed: 9, drift: 0, stalls: [(10_000, 600)]).map { a in a }
        let b = PlayoutBuffer(rate: 16000)
        var out = [Float](repeating: 0, count: 160)
        var next = 0, t = 0.0, worstFloor = 0
        while t < 30_000 {
            while next < tr.count && tr[next] <= t { b.push(talk[next].pcm, at: UInt64((tr[next] + 1_000_000) * 1e6)); next += 1 }
            out.withUnsafeMutableBufferPointer { _ = b.pull(into: $0.baseAddress!, count: 160) }
            if t > 10_600 { worstFloor = max(worstFloor, b.jitterFloor) }
            t += 10
        }
        XCTAssertLessThan(worstFloor, b.maxTarget, "the 600 ms stall did not pin the floor at its maximum")
        XCTAssertEqual(b.speechTrimmed, 0)
    }
}

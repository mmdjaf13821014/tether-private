import Foundation
import XCTest
@testable import TetherCore

/// Playout against simulated arrival traces (virtual clock): frames of 20 ms arrive at given times, the render
/// thread pulls 10 ms every 10 ms. Measures what a listener gets: concealed audio, underruns and the mean delay.
final class PlayoutTraceTests: XCTestCase {
    struct Result { var concealedMs: Double; var underruns: Int; var meanDelayMs: Double; var meanTargetMs: Double }

    /// Talk spurts: 1.2 s of speech-like tone, then 0.8 s of silence (the drain works in the quiet frames).
    static func tone(_ n: Int, _ k: Int) -> [Int16] {
        k % 100 < 60 ? (0..<n).map { Int16(8000 * sin(Double($0 + k * n) * 0.2)) } : [Int16](repeating: 0, count: n)
    }

    /// `arrivals[k]`: ms at which frame k arrives (sorted or not; delivery is in order of time, then index).
    static func run(_ arrivals: [Double], seconds: Double = 60, hi: Double? = nil) -> Result {
        let rate = 16000.0
        let b = hi.map { PlayoutBuffer(rate: rate, hiPercentile: $0) } ?? PlayoutBuffer(rate: rate)
        let order = arrivals.indices.sorted { (arrivals[$0], $0) < (arrivals[$1], $1) }
        var next = 0
        var out = [Float](repeating: 0, count: 160)
        var delaySum = 0.0, targetSum = 0.0, pulls = 0
        var t = arrivals.min() ?? 0
        let end = t + seconds * 1000
        while t < end {
            while next < order.count && arrivals[order[next]] <= t {
                let k = order[next]
                b.push(tone(320, k), at: UInt64((arrivals[k] + 1_000_000) * 1e6))
                next += 1
            }
            out.withUnsafeMutableBufferPointer { _ = b.pull(into: $0.baseAddress!, count: 160) }
            delaySum += Double(b.buffered) / rate * 1000; targetSum += Double(b.target) / rate * 1000; pulls += 1
            t += 10
        }
        return Result(concealedMs: Double(b.concealedSamples) / rate * 1000, underruns: b.underruns,
                      meanDelayMs: delaySum / Double(pulls), meanTargetMs: targetSum / Double(pulls))
    }

    static let frames = 3000   // 60 s

    static func steady() -> [Double] {
        // Split up: older compilers (CI) cannot type-check the one-line form in time.
        (0..<frames).map { (i: Int) -> Double in
            let base: Double = Double(i) * 20
            let wobble: Double = Double((i * 7) % 3) * 0.3
            return base + wobble
        }
    }
    /// Frames produced every 20 ms, delivered at the next 15 ms connection event.
    static func ble15() -> [Double] {
        (0..<frames).map { (i: Int) -> Double in
            let produced: Double = Double(i) * 20 + 0.5
            return ceil(produced / 15) * 15
        }
    }
    /// 15 ms events, and every 2 s a 70 ms hold-up (the frames in it come together at its end).
    static func ble15Holdups() -> [Double] {
        ble15().enumerated().map { k, a in
            let phase = a.truncatingRemainder(dividingBy: 2000)
            return phase < 70 ? a - phase + 70 : a
        }
    }
    /// 30 ms events with a 60-77 ms gap every ~3 s (what a real iPhone saw, 2026-11-22).
    static func ble30Gaps() -> [Double] {
        (0..<frames).map { k in
            let a = ceil((Double(k) * 20 + 0.5) / 30) * 30
            let cycle = 3100.0, phase = a.truncatingRemainder(dividingBy: cycle)
            let gap = 60 + Double((Int(a / cycle) * 17) % 18)
            return phase < gap ? a - phase + gap : a
        }
    }
    /// The phone's clock 0.1 % fast against ours, 15 ms events.
    static func drift() -> [Double] {
        (0..<frames).map { (i: Int) -> Double in
            let produced: Double = Double(i) * 19.98 + 0.5
            return ceil(produced / 15) * 15
        }
    }

    /// 15 ms events with one 300 ms hold-up half-way (a freak spike).
    static func spike() -> [Double] { ble15().map { a in a >= 30_000 && a < 30_300 ? 30_300 : a } }

    /// The reference link (2026-11-23): 15 ms events, a hold-up every ~2 s leaving a 60-77 ms gap; `drift`: the far
    /// clock that much fast (+) or slow (-).
    static func measured(drift: Double = 0) -> [Double] {
        (0..<frames).map { k in
            let a = ceil((Double(k) * 20 / (1 + drift) + 0.5) / 15) * 15
            let cycle = 1900.0 + Double((Int(a / 1900) * 7) % 5) * 100, phase = a.truncatingRemainder(dividingBy: cycle)
            let hold = 45 + Double((Int(a / cycle) * 11) % 18)
            return phase < hold ? a - phase + hold : a
        }
    }

    /// Before (2026-11-23, the playout without a drift line or quiet-frame filling, same traces): measured 168 ms
    /// concealed / 50 ms mean delay; +2 % 130 ms / 106 ms (the spread grew with the drift and held the target at its
    /// maximum); -2 % 1190 ms concealed (the buffer ran dry every few seconds).
    func testMeasuredLinkWithDrift() {
        for (name, tr) in [("measured", Self.measured()), ("measured+2%", Self.measured(drift: 0.02)), ("measured-2%", Self.measured(drift: -0.02))] {
            let r = Self.run(tr)
            print("[trace] " + Self.report(name, r))
            XCTAssertLessThan(r.concealedMs, 300, name)    // < 0.5 % of the minute
            XCTAssertLessThan(r.meanDelayMs, 75, name)
            XCTAssertLessThanOrEqual(r.underruns, 1, name)
        }
    }

    /// The connection interval being shortened as the call starts held frames for ~260 ms on a real iPhone: that
    /// must not hold the target at its maximum for the next 10 s.
    func testStartupStallDoesNotRaiseTheTarget() {
        let tr = Self.measured().map { a in a >= 300 && a < 560 ? 560 : a }
        let rate = 16000.0
        let b = PlayoutBuffer(rate: rate)
        var out = [Float](repeating: 0, count: 160)
        var next = 0
        var t = 0.0
        while t < 6000 {
            while next < tr.count && tr[next] <= t { b.push(Self.tone(320, next), at: UInt64((tr[next] + 1_000_000) * 1e6)); next += 1 }
            out.withUnsafeMutableBufferPointer { _ = b.pull(into: $0.baseAddress!, count: 160) }
            t += 10
        }
        XCTAssertLessThan(Double(b.jitterFloor) / rate * 1000, 90, "the floor follows the link after the start, not the stall")
    }

    static func report(_ name: String, _ r: Result) -> String {
        String(format: "%@: concealed %.0f ms, underruns %d, mean delay %.1f ms, mean target %.1f ms", name, r.concealedMs, r.underruns, r.meanDelayMs, r.meanTargetMs)
    }

    func testTraces() {
        let traces: [(String, [Double])] = [("steady", Self.steady()), ("ble15", Self.ble15()), ("ble15+holdups", Self.ble15Holdups()),
                                            ("ble30+gaps", Self.ble30Gaps()), ("drift", Self.drift()), ("spike", Self.spike())]
        for (name, tr) in traces {
            let r = Self.run(tr)
            print("[trace] " + Self.report(name, r))
            XCTAssertLessThanOrEqual(r.meanDelayMs, 130, name)
            if name != "spike" { XCTAssertEqual(r.underruns, 0, name) }   // nothing covers a 300 ms hold-up
        }
    }

    /// A clean link keeps the delay low; the statistics only raise it where arrival really spreads.
    func testSmoothArrivalStaysShallow() {
        let r = Self.run(Self.ble15())
        XCTAssertEqual(r.concealedMs, 0)
        XCTAssertLessThan(r.meanDelayMs, 25)
    }

    /// Measured before the arrival statistics (2026-11-23, same traces): 325 ms and 1015 ms concealed per minute.
    func testPeriodicHoldUpsAreAbsorbed() {
        XCTAssertLessThan(Self.run(Self.ble15Holdups()).concealedMs, 100)
        XCTAssertLessThan(Self.run(Self.ble30Gaps()).concealedMs, 400)
    }

    func testOneSpikeDoesNotKeepTheBufferDeep() {
        let r = Self.run(Self.spike())
        XCTAssertLessThan(r.meanDelayMs, 40, "a single 300 ms hold-up is not taken as the link's normal spread")
    }
}

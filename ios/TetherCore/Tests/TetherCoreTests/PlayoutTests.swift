import XCTest
@testable import TetherCore

final class PlayoutTests: XCTestCase {
    private func tone(_ n: Int, amp: Double = 8000) -> [Int16] { (0..<n).map { Int16(amp * sin(Double($0) * 0.2)) } }
    /// Virtual time: pulling n samples takes n / 16 kHz, pushes are stamped with it (the arrival statistics read it).
    private var clock: UInt64 = 1_000_000_000
    private func push(_ b: PlayoutBuffer, _ pcm: [Int16]) { b.push(pcm, at: clock) }
    private func pull(_ b: PlayoutBuffer, _ n: Int) -> (real: Int, out: [Float]) {
        clock += UInt64(n) * 1_000_000_000 / 16000
        var out = [Float](repeating: 0, count: n)
        let r = out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: n) }
        return (r, out)
    }

    func testShortGapIsConcealedNotSilent() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640))                 // 40 ms: primes at the 40 ms target
        _ = pull(b, 640)
        let (real, out) = pull(b, 160)    // nothing queued: a 10 ms shortfall
        XCTAssertEqual(real, 0)
        XCTAssertGreaterThan(out.map(abs).max() ?? 0, 0.05, "the gap continues the last audio, faded, instead of silence")
        XCTAssertEqual(b.underruns, 0, "a short gap is not counted as an underrun")
    }

    func testLongGapRebuffersDeeper() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640)); _ = pull(b, 640)
        let before = b.target
        for _ in 0..<12 { _ = pull(b, 160) }   // 120 ms of nothing
        XCTAssertEqual(b.underruns, 1)
        XCTAssertGreaterThan(b.target, before)
        let (real, _) = pull(b, 160)
        XCTAssertEqual(real, 0, "un-primed until the target refills")
    }

    func testDriftIsDrainedInSilenceButNotInSpeech() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640)); _ = pull(b, 320)
        for _ in 0..<3 { push(b, tone(320)) }                 // speech backs up: never cut
        XCTAssertEqual(b.trimmed, 0)
        push(b, [Int16](repeating: 0, count: 320))           // a quiet frame: the excess silence is dropped
        XCTAssertGreaterThan(b.trimmed, 0)
    }

    func testDrainNeverDropsQueuedSpeech() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640)); _ = pull(b, 320)
        for _ in 0..<3 { push(b, tone(320)) }                 // 80 ms of speech queued
        let queued = 320 + 3 * 320
        push(b, [Int16](repeating: 0, count: 320))           // quiet frame over the target
        let (real, out) = pull(b, queued)
        XCTAssertEqual(real, queued, "all queued speech is still there")
        XCTAssertGreaterThan(out.suffix(320).map(abs).max() ?? 0, 0.05, "the newest speech plays, not cut by the drain")
    }

    func testAfterAGapItRefillsBeforePlaying() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640)); _ = pull(b, 640)
        _ = pull(b, 160)                                    // a 10 ms gap: concealing
        push(b, tone(160))                                   // 10 ms arrives: under half the 40 ms target
        XCTAssertEqual(pull(b, 160).real, 0, "keeps concealing while the buffer refills")
        push(b, tone(320))                                   // now 30 ms queued
        XCTAssertEqual(pull(b, 160).real, 160)
    }

    /// Concealment continues exactly the newest 10 ms played, in order, also when the render thread took the audio in
    /// pieces shorter than 10 ms (the kept samples are shifted within their own buffer: an overlapping copy, #15).
    func testConcealmentContinuesTheLastTenMillisecondsAfterShortPulls() {
        let b = PlayoutBuffer(rate: 16000)
        // Loud and every sample distinct (never a quiet frame, never steered).
        let pcm = (0..<640).map { (i: Int) -> Int16 in Int16((i % 2 == 0 ? 1 : -1) * (6000 + 7 * i)) }
        push(b, pcm)
        var played: [Float] = []
        while played.count + 37 <= 640 { played += pull(b, 37).out }     // 17 pulls of 37: always the shifting path
        played += pull(b, 640 - played.count).out                          // the last 11 samples
        XCTAssertEqual(played.count, 640)
        XCTAssertEqual(played, pcm.map { Float($0) / 32768 }, "all of it played as queued")
        let (real, out) = pull(b, 160)                                     // nothing left: concealment
        XCTAssertEqual(real, 0)
        let fadeLen: Float = 16000 * 0.06
        for i in 0..<160 {
            let g = max(0, 1 - Float(i) / fadeLen)
            XCTAssertEqual(out[i], played[480 + i] * g, accuracy: 1e-6, "sample \(i) continues the newest 10 ms in order")
        }
    }

    func testShortGapsDeepenTheBufferAndCleanPlayoutShrinksIt() {
        let b = PlayoutBuffer(rate: 16000)
        push(b, tone(640)); _ = pull(b, 640)
        let start = b.target
        for _ in 0..<4 {                        // four short gaps, each followed by enough audio to play again
            _ = pull(b, 160)
            push(b, tone(1600)); _ = pull(b, 1600)
        }
        XCTAssertGreaterThan(b.target, start, "bursty arrival deepens the buffer")
        XCTAssertLessThanOrEqual(b.target, b.maxTarget)
        let deep = b.target
        for _ in 0..<700 { push(b, tone(160)); _ = pull(b, 160) }   // ~7 s without a gap
        XCTAssertLessThan(b.target, deep, "and it shrinks back once arrival is smooth")
    }
}

final class PlayoutConcealedFrameTests: XCTestCase {
    /// A lost frame filled by the decoder's concealment takes its place on the timeline: the frames after it are
    /// not taken for late ones (the arrival spread stays at the minimum).
    func testConcealedFramesKeepTheTimeline() {
        let b = PlayoutBuffer(rate: 16000)
        let frame = [Int16](repeating: 1000, count: 320)
        var t: UInt64 = 1_000_000_000
        var out = [Float](repeating: 0, count: 320)
        for k in 0..<200 {
            if k % 10 == 5 { b.push(frame, at: t, concealed: true) }      // frame k was lost: concealment instead
            else { b.push(frame, at: t) }
            out.withUnsafeMutableBufferPointer { _ = b.pull(into: $0.baseAddress!, count: 320) }
            t += 20_000_000
        }
        XCTAssertEqual(b.concealedFrames, 20)
        XCTAssertEqual(b.jitterFloor, b.minTarget)
    }
}

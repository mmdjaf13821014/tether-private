import Foundation

/// Fixed-size sample ring shared with a real-time audio thread: no allocation and a very short
/// critical section under an unfair lock on that thread.
public final class SampleRing: @unchecked Sendable {
    private let lock = UnfairLock()
    private let buf: UnsafeMutablePointer<Float>
    private let cap: Int
    private var r = 0, w = 0, count = 0

    public init(capacity: Int) {
        cap = capacity
        buf = .allocate(capacity: capacity)
        buf.initialize(repeating: 0, count: capacity)
    }
    deinit { buf.deallocate() }

    public var available: Int { lock.lock(); defer { lock.unlock() }; return count }

    /// Overwrites the oldest samples when full.
    public func write(_ src: UnsafePointer<Float>, _ n: Int, stride: Int = 1) {
        lock.lock(); defer { lock.unlock() }
        for i in 0..<n {
            buf[w] = src[i * stride]
            w = (w + 1) % cap
            if count == cap { r = (r + 1) % cap } else { count += 1 }
        }
    }

    @discardableResult
    public func read(into dst: UnsafeMutablePointer<Float>, _ n: Int) -> Int {
        lock.lock(); defer { lock.unlock() }
        let k = min(n, count)
        for i in 0..<k { dst[i] = buf[r]; r = (r + 1) % cap }
        count -= k
        return k
    }

    public func skip(_ n: Int) {
        lock.lock(); defer { lock.unlock() }
        let k = min(n, count); r = (r + k) % cap; count -= k
    }

    public func clear() { lock.lock(); r = 0; w = 0; count = 0; lock.unlock() }
}

/// Playout buffer for decoded far-end audio.
/// - A short shortfall is concealed (the last 10 ms faded out over ~60 ms) instead of dropping to silence and
///   re-buffering; only ~100 ms without audio un-primes it.
/// - The level is steered in quiet frames (inaudible): above `target` a quiet frame is written short (clock drift,
///   bursts, the delay a gap left behind); below it, while the far clock runs measurably slow, a quiet frame is written
///   longer. Speech is never cut or stretched: after a stall (with Bluetooth tethering on, up to ~0.9 s, its frames then
///   arriving in one burst) the backlog plays late and its delay goes in the following pauses; only a backlog over
///   `maxBacklogSeconds` loses its oldest audio (2026-11-25; before, anything 120 ms over the target was cut, words
///   included: 0.7 s of speech in the 2-minute tethering trace).
/// - `target` follows the spread of frame arrival times over the last ~10 s (up at once, down slowly), grows by 5 ms
///   with every concealed gap (more after a real underrun) and shrinks after ~3 s clean, never below that spread.
///   The spread is measured around the far clock's own rate (a drift line), so drift does not widen it; a stall's
///   burst is left out of it (no buffer covers a stall, and it held the target at its maximum for ~10 s).
public final class PlayoutBuffer: @unchecked Sendable {
    private let ring: SampleRing
    private let lock = UnfairLock()
    public private(set) var target: Int
    public let minTarget: Int, maxTarget: Int
    /// The most audio kept queued (samples, `maxBacklogSeconds`).
    public let maxBacklog: Int
    private let rate: Int
    private var primed = false
    public private(set) var underruns = 0
    public private(set) var trimmed = 0
    /// Of `trimmed`, samples that were not quiet frames (a backlog over `maxBacklogSeconds` cut from its oldest end).
    public private(set) var speechTrimmed = 0
    public private(set) var concealedSamples = 0
    /// Samples added by writing quiet frames longer (the level was below target).
    public private(set) var inserted = 0
    private var cleanPulls = 0
    // Actual playout delay: the level at every pull once primed (render thread).
    private var levelSum = 0.0, levelPulls = 0
    /// Mean samples queued when the render thread took audio: the playout delay the listener had (beyond the output's own).
    public var meanBuffered: Double { lock.lock(); defer { lock.unlock() }; return levelPulls > 0 ? levelSum / Double(levelPulls) : 0 }
    /// The far clock against ours from the arrival line (parts per million; > 0: frames come faster than real time).
    public private(set) var driftPpm = 0.0
    /// Mean square of the quietest recent frames: what "quiet" is measured against (audio queue).
    private var noiseFloor: Float = 1e-6
    // Concealment state, render thread only.
    private let last: UnsafeMutablePointer<Float>
    private let lastLen: Int
    private var concealRun = 0

    /// Which late frames the buffer covers: this percentile of the arrival delays in the ~10 s window.
    public let hiPercentile: Double

    public init(rate: Double, hiPercentile: Double = 0.998) {
        self.rate = Int(rate)
        self.hiPercentile = hiPercentile
        minTarget = Int(rate * 0.03)   // 30 ms
        maxTarget = Int(rate * 0.12)   // 120 ms
        target = Int(rate * 0.04)      // start at 40 ms
        jitterFloor = Int(rate * 0.03)
        delays = [Double](repeating: 0, count: 500)
        times = [Double](repeating: 0, count: 500)
        maxBacklog = Int(rate * Self.maxBacklogSeconds)
        ring = SampleRing(capacity: Int(rate * (Self.maxBacklogSeconds + 0.25)))   // the ceiling plus a frame never overwrites
        lastLen = Int(rate * 0.01)     // 10 ms
        last = .allocate(capacity: lastLen)
        last.initialize(repeating: 0, count: lastLen)
    }
    deinit { last.deallocate() }

    // Arrival statistics (audio queue only): each frame's delay against an ideal 20 ms clock, last ~10 s.
    private var delays: [Double]
    private var times: [Double]
    private var delayCount = 0, delayNext = 0, sinceStats = 0
    private var firstArrival: UInt64 = 0
    private var startupArrivals = 100
    private var expectedSamples = 0
    /// Stall detection (audio queue): the last frame's time and delay in the window, and the delay before a stall
    /// while its burst is still arriving.
    private var lastNoted = 0.0, lastDelay = 0.0
    private var stallBase: Double?
    /// The target the arrival statistics ask for (samples); `target` never shrinks below it and jumps up to it.
    /// Written on the audio queue, read for statistics.
    public private(set) var jitterFloor: Int
    /// Lost frames filled in by the decoder's concealment (pushed with `concealed`).
    public private(set) var concealedFrames = 0

    /// Audio queue (not real-time): converts outside any lock the render thread uses. `at`: when the frame arrived
    /// (uptime ns); `missing`: frames lost just before this one (they never come, so they do not count as late).
    /// `concealed`: decoder concealment standing in for a lost frame (fills its place, is no arrival).
    public func push(_ pcm: [Int16], at: UInt64 = DispatchTime.now().uptimeNanoseconds, missing: Int = 0, concealed: Bool = false) {
        if concealed { expectedSamples += pcm.count; concealedFrames += 1 } else { noteArrival(samples: pcm.count, missing: missing, at: at) }
        let f = pcm.map { Float($0) / 32768 }
        let energy = f.reduce(Float(0)) { $0 + $1 * $1 } / Float(max(1, f.count))
        noiseFloor = energy < noiseFloor ? max(energy, 1e-10) : noiseFloor * 1.01   // falls at once, rises ~2 dB/s
        let quiet = energy < 1e-3 && (energy < 1e-5 || energy < noiseFloor * 6)
        lock.lock()
        let tgt = target, isPrimed = primed
        lock.unlock()
        // Steering in quiet frames only (never queued speech): above target + 10 ms the frame is written short. Below
        // target - 10 ms, while the far clock runs measurably slow, it is written longer by repeating its start (else the
        // buffer would run dry every few seconds; without drift a gap refills it by itself, and filling up to a target
        // that one freak stall raised would only add delay).
        var n = f.count
        let before = ring.available
        var add = 0
        if quiet && before + n > tgt + minTarget / 3 { n -= min(before + n - tgt, n) }
        else if quiet && isPrimed && driftPpm < -Self.slowClockPpm && before + n + minTarget / 3 < tgt { add = min(tgt - before - n, f.count) }
        if n > 0 { f.withUnsafeBufferPointer { ring.write($0.baseAddress!, n) } }
        if add > 0 { f.withUnsafeBufferPointer { ring.write($0.baseAddress!, add) } }
        var live = before + n + add
        var skip = f.count - n
        // A backlog beyond the ceiling (a stall of most of a second, then its frames in one burst) loses its oldest
        // audio. Below it nothing is cut from speech: the listener already waited through the stall, and every word
        // still comes, a little late; the delay goes again in the next pauses (quiet frames written short, above).
        var cut = 0
        let ceiling = max(tgt + maxTarget, maxBacklog)
        if live > ceiling { cut = live - ceiling; ring.skip(cut); skip += cut; live = ceiling }
        lock.lock()
        if !primed && live >= tgt { primed = true }
        trimmed += skip
        speechTrimmed += cut
        inserted += add
        lock.unlock()
    }

    /// Real-time thread: fills `out`, returns how many samples were real audio.
    @discardableResult
    public func pull(into out: UnsafeMutablePointer<Float>, count: Int) -> Int {
        lock.lock()
        let isPrimed = primed, tgt = target
        lock.unlock()
        guard isPrimed else { for i in 0..<count { out[i] = 0 }; return 0 }
        let level = ring.available
        // After a gap keep concealing until half the target is back: frames lost (not late) would otherwise leave
        // the buffer running on empty, and every small jitter spike would be another gap.
        let n = concealRun > 0 && ring.available < tgt / 2 ? 0 : ring.read(into: out, count)
        if n == count {
            if concealRun > 0 && n > 0 {
                // Fade back in over the first 2 ms after concealment (no click).
                let ramp = min(n, rate / 500)
                for i in 0..<ramp { out[i] *= Float(i) / Float(ramp) }
            }
            concealRun = 0
            remember(out, count)
            lock.lock()
            levelSum += Double(level); levelPulls += 1
            cleanPulls += 1
            // ~3 s clean: shrink, but not below what the arrival statistics ask for.
            if cleanPulls > 300 && target > max(minTarget, jitterFloor) { target = max(jitterFloor, target - minTarget / 6); cleanPulls = 0 }
            lock.unlock()
            return n
        }
        // Shortfall: continue the last 10 ms, fading out over ~60 ms, instead of a hard gap.
        // A new gap also deepens the buffer a little: frames over Bluetooth arrive in bursts (gaps of 60-77 ms
        // against a 40 ms target were concealed every few seconds on a real iPhone, 2026-11-22), and only a full
        // 100 ms dropout used to grow the target. It shrinks again after ~3 s without a gap.
        if concealRun == 0 {
            lock.lock()
            target = min(maxTarget, target + rate / 200)
            lock.unlock()
        }
        let fadeLen = Float(rate) * 0.06
        for i in n..<count {
            let g = max(0, 1 - Float(concealRun) / fadeLen)
            out[i] = last[(concealRun) % lastLen] * g
            concealRun += 1
        }
        lock.lock()
        levelSum += Double(level); levelPulls += 1
        concealedSamples += count - n
        cleanPulls = 0
        if concealRun > rate / 10 {
            // ~100 ms with nothing: really out of audio. Re-buffer a little deeper.
            primed = false
            underruns += 1
            target = min(maxTarget, target + minTarget / 3)
            concealRun = 0
        }
        lock.unlock()
        return n
    }

    /**
     * The delay of each frame against an ideal clock started by the first one; the spread of the last ~10 s is how much
     * the buffer must hold so the late frames are still in time. The spread is taken around a drift line through the
     * delays (the far clock's rate against ours: a drift of 2 % would otherwise add 200 ms in 10 s), from
     * the 2nd percentile to the [hiPercentile] (one freak spike does not count; the late frames over Bluetooth are a
     * few periodic hold-ups of 60-77 ms, and the buffer should cover most of them). Recomputed every 0.5 s; the target
     * follows it up at once (before the next gap is concealed, not after) and down only by the clean-playout shrink.
     */
    private func noteArrival(samples: Int, missing: Int, at: UInt64) {
        // The first ~2 s are no measure of the link: the connection interval is still being shortened for the call
        // (a 250-280 ms hold-up then, on a real iPhone, 2026-11-23), which kept the target at its maximum for
        // the next 10 s. Concealed gaps deepen the buffer meanwhile.
        if startupArrivals > 0 { startupArrivals -= 1; return }
        if firstArrival == 0 || at < firstArrival { firstArrival = at; expectedSamples = 0; delayCount = 0; delayNext = 0; lastNoted = 0; stallBase = nil }
        expectedSamples += missing * samples
        let t = Double(at - firstArrival) / 1e6
        let d = t - Double(expectedSamples) * 1000 / Double(rate)
        expectedSamples += samples
        // A stall far longer than any buffer could cover (no frame for over `stallMs`, then its frames in one burst)
        // says nothing about the link's usual spread: its late frames are left out of the window, or one stall held
        // the target at its maximum, and with the stall's backlog kept (not cut) that maximum was the delay for ~10 s.
        if lastNoted > 0 && t - lastNoted > Self.stallMs { stallBase = lastDelay }
        lastNoted = t
        if let base = stallBase {
            // The burst: every frame later than the spread already covers (it ends with the first one in time).
            if d > base + Double(jitterFloor) * 1000 / Double(rate) { return }
            stallBase = nil
        }
        lastDelay = d
        delays[delayNext] = d
        times[delayNext] = t
        delayNext = (delayNext + 1) % delays.count
        delayCount = min(delayCount + 1, delays.count)
        sinceStats += 1
        guard sinceStats >= 25, delayCount >= 50 else { return }
        sinceStats = 0
        let (spread, slope) = Self.spread(times: times, delays: delays, count: delayCount, hi: hiPercentile)
        driftPpm = -slope * 1e6
        let floor = min(maxTarget, max(minTarget, Int((spread + Self.marginMs) * Double(rate) / 1000)))
        lock.lock()
        jitterFloor = floor
        if target < floor { target = floor }
        lock.unlock()
    }

    /// Percentile spread of `delays` around their drift line against `times` (ms), and the line's slope. The slope comes
    /// from the early frames (10th percentile) of each half of the window: late frames and stalls do not tilt it.
    static func spread(times: [Double], delays: [Double], count n: Int, lo: Double = 0.02, hi: Double) -> (Double, Double) {
        let slope = driftSlope(times: times, delays: delays, count: n)
        var r = [Double](repeating: 0, count: n)
        for i in 0..<n { r[i] = delays[i] - slope * times[i] }
        r.sort()
        return (r[min(n - 1, Int(Double(n - 1) * hi))] - r[Int(Double(n - 1) * lo)], slope)
    }

    /// ms of delay per ms of time; 0 for a window shorter than ~4 s (too short to tell drift from jitter).
    static func driftSlope(times: [Double], delays: [Double], count n: Int) -> Double {
        let idx = (0..<n).sorted { times[$0] < times[$1] }
        guard n >= 100, let t0 = idx.first.map({ times[$0] }), let t1 = idx.last.map({ times[$0] }), t1 - t0 > 4000 else { return 0 }
        func early(_ part: ArraySlice<Int>) -> (Double, Double) {
            let d = part.map { delays[$0] }.sorted()
            let t = part.map { times[$0] }.reduce(0, +) / Double(part.count)
            return (t, d[d.count / 10])
        }
        let a = early(idx[0..<n / 2]), b = early(idx[n / 2..<n])
        return b.0 > a.0 ? (b.1 - a.1) / (b.0 - a.0) : 0
    }

    /// The arrival window's residual delays (ms, around the drift line) at a few percentiles, for traces. Audio queue only.
    public func arrivalSummary() -> String {
        guard delayCount >= 50 else { return "arrivals: \(delayCount)" }
        let n = delayCount
        let slope = Self.driftSlope(times: times, delays: delays, count: n)
        let r = (0..<n).map { delays[$0] - slope * times[$0] }.sorted()
        func p(_ q: Double) -> Double { r[min(n - 1, Int(Double(n - 1) * q))] - r[Int(Double(n - 1) * 0.02)] }
        return String(format: "spread p50 %.0f p90 %.0f p99 %.0f p99.8 %.0f max %.0f ms (n %d, drift %.0f ppm)", p(0.5), p(0.9), p(0.99), p(0.998), p(1), n, -slope * 1e6)
    }

    /// A far clock slower than this (ppm) is made up for in quiet frames. Real clocks are tens of ppm apart; the
    /// estimate from a 10 s window with hold-ups wanders by up to ~800 ppm.
    static let slowClockPpm = 3000.0

    /// No frame for this long (ms) is a stall, not jitter (the arrival statistics leave its burst out).
    static let stallMs = 250.0

    /// The most audio kept queued (s): beyond it the oldest is cut, speech or not.
    public static let maxBacklogSeconds = 0.8

    /// Room beyond the arrival spread: the render thread takes 10 ms at a time.
    static let marginMs = 10.0

    private func remember(_ out: UnsafeMutablePointer<Float>, _ count: Int) {
        let k = min(count, lastLen)
        // Keep the newest 10 ms, in order, so concealment continues the waveform. A pull shorter than 10 ms shifts the
        // kept samples down first: source and destination overlap there, so it is a memmove (an explicit one: the
        // copy direction matters and must not depend on how a pointer helper is implemented).
        if k == lastLen { last.update(from: out + (count - k), count: k) }
        else {
            memmove(last, last + k, (lastLen - k) * MemoryLayout<Float>.stride)
            (last + (lastLen - k)).update(from: out + (count - k), count: k)
        }
    }

    /** Samples queued right now (the playout delay beyond the output's own). */
    public var buffered: Int { ring.available }

    public func reset() { ring.clear(); lock.lock(); primed = false; concealRun = 0; lock.unlock(); firstArrival = 0 }
}

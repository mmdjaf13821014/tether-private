package app.tether.client.calls

import java.util.Locale

/**
 * Playout buffer for decoded far-end audio, a port of TetherCore's PlayoutBuffer (same numbers, same tests):
 * - a short shortfall is concealed (the last 10 ms faded out over ~60 ms) instead of dropping to silence and
 *   re-buffering; only ~100 ms without audio un-primes it;
 * - the level is steered in quiet frames (inaudible): above [target] a quiet frame is written short (clock drift,
 *   bursts, the delay a gap left behind); below it, while the far clock runs measurably slow, a quiet frame is written
 *   longer. Speech is never cut or stretched: after a stall (with Bluetooth tethering on, up to ~0.9 s, its frames then
 *   arriving in one burst) the backlog plays late and its delay goes in the following pauses; only a backlog over
 *   [MAX_BACKLOG_SECONDS] loses its oldest audio;
 * - [target] follows the spread of frame arrival times over the last ~10 s (up at once, down slowly), grows by 5 ms
 *   with every concealed gap (more after a real underrun) and shrinks after ~3 s clean, never below that spread.
 *   The spread is measured around the far clock's own rate (a drift line), so drift does not widen it; a stall's
 *   burst is left out of it (no buffer covers a stall, and it held the target at its maximum for ~10 s).
 * [push] runs on the decode thread, [pull] on the playback thread.
 */
class PlayoutBuffer(rate: Int, val hiPercentile: Double = 0.998) {
    private val rate = rate
    val minTarget = (rate * 0.03).toInt()     // 30 ms
    val maxTarget = (rate * 0.12).toInt()     // 120 ms
    @Volatile var target = (rate * 0.04).toInt(); private set   // start at 40 ms
    /** The most audio kept queued (samples, [MAX_BACKLOG_SECONDS]). */
    val maxBacklog = (rate * MAX_BACKLOG_SECONDS).toInt()
    private val ring = FloatArray((rate * (MAX_BACKLOG_SECONDS + 0.25)).toInt())   // the ceiling plus a frame never overwrites
    private var r = 0; private var w = 0; private var count = 0
    private val lock = Any()
    private var primed = false
    @Volatile var underruns = 0; private set
    @Volatile var trimmed = 0; private set
    /** Of [trimmed], samples that were not quiet frames (a backlog over [MAX_BACKLOG_SECONDS] cut from its oldest end). */
    @Volatile var speechTrimmed = 0; private set
    @Volatile var concealedSamples = 0; private set
    /** Samples added by writing quiet frames longer (the level was below target). */
    @Volatile var inserted = 0; private set
    private var cleanPulls = 0
    // Actual playout delay: the level at every pull once primed (playback thread).
    private var levelSum = 0.0; private var levelPulls = 0
    /** Mean samples queued when the playback thread took audio: the playout delay the listener had (beyond the output's own). */
    val meanBuffered: Double get() = synchronized(lock) { if (levelPulls > 0) levelSum / levelPulls else 0.0 }
    /** The far clock against ours from the arrival line (ppm; > 0: frames come faster than real time). */
    @Volatile var driftPpm = 0.0; private set
    /** Mean square of the quietest recent frames: what "quiet" is measured against (decode thread). */
    private var noiseFloor = 1e-6f
    // Concealment state, playback thread only.
    private val lastLen = (rate * 0.01).toInt()   // 10 ms
    private val last = FloatArray(lastLen)
    private var concealRun = 0

    // Arrival statistics (decode thread only): each frame's delay against an ideal 20 ms clock, last ~10 s.
    private val delays = DoubleArray(500)
    private val times = DoubleArray(500)
    private var delayCount = 0; private var delayNext = 0; private var sinceStats = 0
    private var firstArrival = 0L
    private var startupArrivals = 100
    private var expectedSamples = 0
    // Stall detection (decode thread): the last frame's time and delay in the window, and the delay before a stall
    // while its burst is still arriving.
    private var lastNoted = 0.0; private var lastDelay = 0.0
    private var stallBase: Double? = null
    /** The target the arrival statistics ask for (samples); [target] never shrinks below it and jumps up to it. */
    @Volatile var jitterFloor = (rate * 0.03).toInt(); private set
    /** Lost frames filled in by the decoder's concealment (pushed with `concealed`). */
    @Volatile var concealedFrames = 0; private set

    /** Samples queued right now (the playout delay beyond the output's own). */
    val buffered: Int get() = synchronized(lock) { count }
    val available: Int get() = buffered

    private fun writeLocked(src: FloatArray, n: Int) {
        for (i in 0 until n) {
            ring[w] = src[i]
            w = (w + 1) % ring.size
            if (count == ring.size) r = (r + 1) % ring.size else count++
        }
    }

    private fun readLocked(dst: FloatArray, n: Int): Int {
        val k = minOf(n, count)
        for (i in 0 until k) { dst[i] = ring[r]; r = (r + 1) % ring.size }
        count -= k
        return k
    }

    private fun skipLocked(n: Int) { val k = minOf(n, count); r = (r + k) % ring.size; count -= k }

    /**
     * Decode thread. [at]: when the frame came off the link (System.nanoTime); [missing]: frames lost just before this
     * one (they never come, so they do not count as late). [concealed]: decoder concealment standing in for a lost
     * frame (fills its place, is no arrival).
     */
    fun push(pcm: ShortArray, at: Long = System.nanoTime(), missing: Int = 0, concealed: Boolean = false) {
        if (concealed) { expectedSamples += pcm.size; concealedFrames++ } else noteArrival(pcm.size, missing, at)
        val f = FloatArray(pcm.size) { pcm[it] / 32768f }
        var energy = 0f
        for (x in f) energy += x * x
        energy /= maxOf(1, f.size).toFloat()
        noiseFloor = if (energy < noiseFloor) maxOf(energy, 1e-10f) else noiseFloor * 1.01f   // falls at once, rises ~2 dB/s
        val quiet = energy < 1e-3f && (energy < 1e-5f || energy < noiseFloor * 6)
        synchronized(lock) {
            val tgt = target
            // Steering in quiet frames only (never queued speech): above target + 10 ms the frame is written short.
            // Below target - 10 ms, while the far clock runs measurably slow, it is written longer by repeating its start.
            var n = f.size
            val before = count
            var add = 0
            if (quiet && before + n > tgt + minTarget / 3) n -= minOf(before + n - tgt, n)
            else if (quiet && primed && driftPpm < -SLOW_CLOCK_PPM && before + n + minTarget / 3 < tgt) add = minOf(tgt - before - n, f.size)
            if (n > 0) writeLocked(f, n)
            if (add > 0) writeLocked(f, add)
            var live = before + n + add
            var skip = f.size - n
            // A backlog beyond the ceiling (a stall of most of a second, then its frames in one burst) loses its oldest
            // audio. Below it nothing is cut from speech: every word still comes, a little late; the delay goes again
            // in the next pauses (quiet frames written short, above).
            var cut = 0
            val ceiling = maxOf(tgt + maxTarget, maxBacklog)
            if (live > ceiling) { cut = live - ceiling; skipLocked(cut); skip += cut; live = ceiling }
            if (!primed && live >= tgt) primed = true
            trimmed += skip
            speechTrimmed += cut
            inserted += add
        }
    }

    /** Playback thread: fills [out] (n samples); returns how many were real audio. */
    fun pull(out: FloatArray, n: Int = out.size): Int {
        val isPrimed: Boolean; val tgt: Int
        synchronized(lock) { isPrimed = primed; tgt = target }
        if (!isPrimed) { java.util.Arrays.fill(out, 0, n, 0f); return 0 }
        var level = 0
        // After a gap keep concealing until half the target is back: frames lost (not late) would otherwise leave
        // the buffer running on empty, and every small jitter spike would be another gap.
        val got = synchronized(lock) { level = count; if (concealRun > 0 && count < tgt / 2) 0 else readLocked(out, n) }
        if (got == n) {
            if (concealRun > 0 && got > 0) {
                // Fade back in over the first 2 ms after concealment (no click).
                val ramp = minOf(got, rate / 500)
                for (i in 0 until ramp) out[i] *= i.toFloat() / ramp.toFloat()
            }
            concealRun = 0
            remember(out, n)
            synchronized(lock) {
                levelSum += level.toDouble(); levelPulls++
                cleanPulls++
                // ~3 s clean: shrink, but not below what the arrival statistics ask for.
                if (cleanPulls > 300 && target > maxOf(minTarget, jitterFloor)) { target = maxOf(jitterFloor, target - minTarget / 6); cleanPulls = 0 }
            }
            return got
        }
        // Shortfall: continue the last 10 ms, fading out over ~60 ms, instead of a hard gap. A new gap also deepens
        // the buffer a little (frames over Bluetooth arrive in bursts); it shrinks again after ~3 s without a gap.
        if (concealRun == 0) synchronized(lock) { target = minOf(maxTarget, target + rate / 200) }
        val fadeLen = rate.toFloat() * 0.06f
        for (i in got until n) {
            val g = maxOf(0f, 1 - concealRun.toFloat() / fadeLen)
            out[i] = last[concealRun % lastLen] * g
            concealRun++
        }
        synchronized(lock) {
            levelSum += level.toDouble(); levelPulls++
            concealedSamples += n - got
            cleanPulls = 0
            if (concealRun > rate / 10) {
                // ~100 ms with nothing: really out of audio. Re-buffer a little deeper.
                primed = false
                underruns++
                target = minOf(maxTarget, target + minTarget / 3)
                concealRun = 0
            }
        }
        return got
    }

    /**
     * The delay of each frame against an ideal clock started by the first one; the spread of the last ~10 s (around a
     * drift line, from the 2nd percentile to [hiPercentile]) is how much the buffer must hold so the late frames are
     * still in time. Recomputed every 0.5 s; the target follows it up at once and down only by the clean-playout shrink.
     */
    private fun noteArrival(samples: Int, missing: Int, at: Long) {
        // The first ~2 s are no measure of the link: the connection interval is still being shortened for the call.
        if (startupArrivals > 0) { startupArrivals--; return }
        if (firstArrival == 0L || at < firstArrival) { firstArrival = at; expectedSamples = 0; delayCount = 0; delayNext = 0; lastNoted = 0.0; stallBase = null }
        expectedSamples += missing * samples
        val t = (at - firstArrival).toDouble() / 1e6
        val d = t - expectedSamples.toDouble() * 1000 / rate.toDouble()
        expectedSamples += samples
        // A stall far longer than any buffer could cover (no frame for over STALL_MS, then its frames in one burst)
        // says nothing about the link's usual spread: its late frames are left out of the window.
        if (lastNoted > 0 && t - lastNoted > STALL_MS) stallBase = lastDelay
        lastNoted = t
        stallBase?.let { base ->
            // The burst: every frame later than the spread already covers (it ends with the first one in time).
            if (d > base + jitterFloor * 1000.0 / rate) return
            stallBase = null
        }
        lastDelay = d
        delays[delayNext] = d
        times[delayNext] = t
        delayNext = (delayNext + 1) % delays.size
        delayCount = minOf(delayCount + 1, delays.size)
        sinceStats++
        if (sinceStats < 25 || delayCount < 50) return
        sinceStats = 0
        val (spread, slope) = spread(times, delays, delayCount, hi = hiPercentile)
        driftPpm = -slope * 1e6
        val floor = minOf(maxTarget, maxOf(minTarget, ((spread + MARGIN_MS) * rate.toDouble() / 1000).toInt()))
        synchronized(lock) {
            jitterFloor = floor
            if (target < floor) target = floor
        }
    }

    /** The arrival window's residual delays (ms, around the drift line) at a few percentiles, for traces. Decode thread only. */
    fun arrivalSummary(): String {
        if (delayCount < 50) return "arrivals: $delayCount"
        val n = delayCount
        val slope = driftSlope(times, delays, n)
        val r = DoubleArray(n) { delays[it] - slope * times[it] }.also { it.sort() }
        fun p(q: Double) = r[minOf(n - 1, ((n - 1) * q).toInt())] - r[((n - 1) * 0.02).toInt()]
        return String.format(Locale.ROOT, "spread p50 %.0f p90 %.0f p99 %.0f p99.8 %.0f max %.0f ms (n %d, drift %.0f ppm)",
            p(0.5), p(0.9), p(0.99), p(0.998), p(1.0), n, -slope * 1e6)
    }

    private fun remember(out: FloatArray, n: Int) {
        val k = minOf(n, lastLen)
        // Keep the newest 10 ms, in order, so concealment continues the waveform.
        if (k == lastLen) System.arraycopy(out, n - k, last, 0, k)
        else { System.arraycopy(last, k, last, 0, lastLen - k); System.arraycopy(out, n - k, last, lastLen - k, k) }
    }

    fun reset() { synchronized(lock) { r = 0; w = 0; count = 0; primed = false; concealRun = 0 }; firstArrival = 0 }

    companion object {
        /** A far clock slower than this (ppm) is made up for in quiet frames; the 10 s estimate wanders by up to ~800 ppm. */
        const val SLOW_CLOCK_PPM = 3000.0
        /** No frame for this long (ms) is a stall, not jitter (the arrival statistics leave its burst out). */
        const val STALL_MS = 250.0
        /** The most audio kept queued (s): beyond it the oldest is cut, speech or not. */
        const val MAX_BACKLOG_SECONDS = 0.8
        /** Room beyond the arrival spread: the playback thread takes 10 ms at a time. */
        const val MARGIN_MS = 10.0

        /** Percentile spread of [delays] around their drift line against [times] (ms), and the line's slope. */
        internal fun spread(times: DoubleArray, delays: DoubleArray, n: Int, lo: Double = 0.02, hi: Double): Pair<Double, Double> {
            val slope = driftSlope(times, delays, n)
            val r = DoubleArray(n) { delays[it] - slope * times[it] }
            r.sort()
            return Pair(r[minOf(n - 1, ((n - 1) * hi).toInt())] - r[((n - 1) * lo).toInt()], slope)
        }

        /**
         * ms of delay per ms of time; 0 for a window shorter than ~4 s (too short to tell drift from jitter). The slope
         * comes from the early frames (10th percentile) of each half of the window: late frames and stalls do not tilt it.
         */
        internal fun driftSlope(times: DoubleArray, delays: DoubleArray, n: Int): Double {
            if (n < 100) return 0.0
            val idx = (0 until n).sortedBy { times[it] }   // stable, as Swift's sort
            val t0 = times[idx.first()]; val t1 = times[idx.last()]
            if (t1 - t0 <= 4000) return 0.0
            fun early(part: List<Int>): Pair<Double, Double> {
                val d = part.map { delays[it] }.sorted()
                var s = 0.0
                for (i in part) s += times[i]
                return Pair(s / part.size, d[d.size / 10])
            }
            val a = early(idx.subList(0, n / 2)); val b = early(idx.subList(n / 2, n))
            return if (b.first > a.first) (b.second - a.second) / (b.first - a.first) else 0.0
        }
    }
}

/**
 * What this client does with a call state for a call it already hung up (the Android phone may still send states that
 * were on their way): drop them, ask again if the Android phone still reports the call 2 s later, forget after an end.
 * Port of TetherCore's HangUpGuard.
 */
class HangUpGuard {
    enum class Verdict { PASS, DROP, DROP_AND_RESEND }
    private val hungUp = HashMap<String, Long>()

    fun hungUp(cid: String, at: Long = System.currentTimeMillis()) { hungUp[cid] = at }
    fun reset() = hungUp.clear()
    operator fun contains(cid: String) = cid in hungUp

    fun check(cid: String, ended: Boolean, now: Long = System.currentTimeMillis()): Verdict {
        val t = hungUp[cid] ?: return Verdict.PASS             // any other call, including every new one
        if (ended) { hungUp.remove(cid); return Verdict.PASS }   // the end we wanted: let it close what is left
        val age = now - t
        if (age > 30_000) { hungUp.remove(cid); return Verdict.DROP }   // give up asking; never resurrect it
        if (age > 2_000) { hungUp[cid] = now; return Verdict.DROP_AND_RESEND }
        return Verdict.DROP
    }
}

package app.tether.helper

/**
 * The uplink between the link and the inject track: the iPhone's decoded voice waits here until the track has room.
 *
 * - Bursts are kept: frames over Bluetooth come 0-3 per connection event and after a hold-up of 60-77 ms several at
 *   once, and the first words after answering arrive while the call's audio path is still opening (up to ~0.6 s on
 *   the reference phone). The buffer holds [capacity] (1 s), so only a stall far beyond those loses voice
 *   ([overflowFrames]); what a slow start leaves queued is caught up in the next pauses.
 * - The delay that matters is what waits ahead of an arriving frame: this queue plus what the track holds. Its smallest
 *   value over the last ~25 frames that came on time is the standing delay; it is steered to [target]: what the longest
 *   arrival gap of the last ~10 s needs (~12 ms for even connection events, up to 60 ms where Bluetooth tethering
 *   shares the radio and hold-ups of 60-80 ms come every second), so the next hold-up finds audio, plus what the
 *   track's sink needs (its mixer takes 20-32 ms at a time; learnt from the track's underruns). A stall of
 *   [LATE_MS] or more does not count: no cushion covers it, the late cut below deals with it.
 * - A long hold-up (Bluetooth tethering: up to 880 ms, 2026-11-24) delivers its frames all at once, far more than the
 *   target: the far end already heard the gap, and playing what came late only delays everything after it (537 ms
 *   ahead, 1.6 s cut in pauses over the next minute). So once the delay ahead is over [target] + [LATE_MS], it is cut
 *   back at once to [target] + [KEEP_MS]: quiet frames first, then the oldest voiced ones, joined with a short
 *   crossfade ([lateCut]).
 * - Steering happens where it is inaudible: in a quiet frame, part or all of it is cut (level above target) or a
 *   piece of it is repeated (level below target). Clock drift between the iPhone's microphone and the call's clock,
 *   the extra delay an underrun or a slow start leaves behind, and a missing cushion are all corrected that way. Only
 *   when no quiet frame comes for ~0.5 s (or ≥ 2 frames are too much), or the track is about to run dry, are voiced
 *   frames shortened or lengthened by up to 3 % each (linear resampling: at most half a semitone, while it lasts).
 *
 * Pure arithmetic apart from the wait in [take]: one producer thread ([push]) and one consumer thread ([take] / [poll]).
 */
class UplinkBuffer(
    private val frame: Int = 320,
    private val rate: Int = 16000,
    private val capacity: Int = frame * 50,
) {
    private val lock = Object()
    private val q = ArrayDeque<ShortArray>()
    private var queued = 0

    // Arrival gaps of the last ~10 s (500 frames), for the target.
    private val gaps = DoubleArray(500)
    private var gapCount = 0
    private var gapNext = 0
    private var lastArrival = 0L
    private var lastGapNs = 0L
    private var sinceTarget = 0

    /** Smallest delay to keep ahead of a frame arriving on time (queue + track, samples): covers the recent hold-ups. */
    @Volatile var target = rate * 5 / 1000; private set
    private var gapTarget = rate * 5 / 1000
    /**
     * Extra delay the track's sink asks for: a mixer that takes 20-32 ms at a time runs the track dry although audio
     * is on its way. +5 ms per underrun, -1 ms per ~3 s without one, at most 30 ms.
     */
    @Volatile var sinkExtra = 0; private set
    private var underrunsSeen = -1
    private var sinceUnderrun = 0
    /**
     * Delay ahead of the last ~25 frames that arrived on time (not the ones a hold-up delivered late: they find less
     * ahead by design), each plus the [removed] total at its arrival, so a correction moves them all at once.
     */
    private val ahead = DoubleArray(25)
    private var aheadCount = 0
    private var aheadNext = 0
    /** Samples taken out minus samples put in so far. */
    private var removed = 0.0
    private var lateUntil = 0L
    /** Frames taken since the level first stood above the band without a quiet frame to cut. */
    private var excessPolls = 0
    /** Mean square of the quietest recent frames (normalised): what "quiet" is measured against (consumer thread). */
    private var floor = 1e-6

    /** Frames were dropped from the queue's head: the next one handed out is crossfaded from the last sample written. */
    private var splice = false
    /** The last sample handed out (consumer thread). */
    private var lastOut: Short = 0

    // Statistics (read for the call's figures).
    @Volatile var arrived = 0; private set
    /** Samples dropped at once after a long hold-up (see [LATE_MS]). */
    @Volatile var lateCut = 0; private set
    @Volatile var overflowFrames = 0; private set
    @Volatile var quietCut = 0; private set
    @Volatile var inserted = 0; private set
    @Volatile var compressed = 0; private set
    @Volatile var stretched = 0; private set
    @Volatile var levelMax = 0; private set
    @Volatile private var levelSum = 0L
    @Volatile private var levelN = 0
    /** Average delay ahead of an arriving frame (queue + track), samples. */
    val levelAvg: Int get() = if (levelN > 0) (levelSum / levelN).toInt() else 0

    /**
     * Link side: never blocks; a full buffer (a stall of ~1 s) drops its oldest frame. [trackQueued]: samples written
     * to the track and not yet played, < 0 while the track has not started playing (then the frame is no measure of
     * the standing delay: it only waits for the start). What waits ahead of this frame (queue + track) is its delay.
     */
    fun push(pcm: ShortArray, nowNs: Long = System.nanoTime(), trackQueued: Int = 0) = synchronized(lock) {
        noteArrival(nowNs)
        arrived++
        val a = queued + maxOf(0, trackQueued)
        if (trackQueued >= 0) { levelSum += a; levelN++; if (a > levelMax) levelMax = a }
        // A frame after a hold-up (gap > 45 ms) and the ones delivered with it (< 3 ms apart) came late.
        val gap = if (lastGapNs > 0) lastGapNs else 0L
        if (gap > 45_000_000L) lateUntil = nowNs + 3_000_000L
        else if (nowNs > lateUntil) lateUntil = 0L
        if (lateUntil == 0L && trackQueued >= 0) {
            ahead[aheadNext] = a + removed
            aheadNext = (aheadNext + 1) % ahead.size
            aheadCount = minOf(aheadCount + 1, ahead.size)
        } else if (gap <= 45_000_000L) lateUntil = nowNs + 3_000_000L
        while (queued + pcm.size > capacity && q.isNotEmpty()) { queued -= q.removeFirst().size; overflowFrames++ }
        q.addLast(pcm); queued += pcm.size
        if (lateUntil == 0L) lateCutting = false
        if (trackQueued >= 0) cutLate(queued + trackQueued)
        lock.notifyAll()
    }

    /** A late burst is being cut: every further frame of it goes down to target + [KEEP_MS] too. */
    private var lateCutting = false

    /** Holding the lock: [ahead] (queue + track) is far over target after a hold-up: drop late audio at once (see class). */
    private fun cutLate(ahead: Int) {
        val keep = target + rate * KEEP_MS / 1000
        val limit = if (lateCutting) keep + frame else target + rate * LATE_MS / 1000
        if (ahead <= limit || q.size < 2) return
        lateCutting = lateUntil != 0L
        var excess = ahead - keep
        val before = queued
        // Quiet frames first (the newest stays: it is what was just said).
        val it = q.iterator()
        var i = 0
        while (excess > 0 && it.hasNext()) {
            val f = it.next()
            if (++i == q.size) break
            if (meanSquare(f) < QUIET_MS) { it.remove(); queued -= f.size; excess -= f.size }
        }
        while (excess > 0 && q.size > 1) { val f = q.removeFirst(); queued -= f.size; excess -= f.size }
        val cut = before - queued
        if (cut > 0) { lateCut += cut; removed += cut; splice = true }
    }

    /** Track side: waits up to [timeoutMs] for a frame, then as [poll]. */
    fun take(timeoutMs: Long): ShortArray? {
        synchronized(lock) { if (q.isEmpty()) lock.wait(timeoutMs) }
        return poll()
    }

    /** The next piece to write, shaped (it may be empty: a quiet frame cut whole), or null when nothing is queued. */
    fun poll(): ShortArray? = shape()?.also { out ->
        if (out.isNotEmpty()) lastOut = out[out.size - 1]
    }

    private fun shape(): ShortArray? {
        val f: ShortArray
        val excess: Int
        synchronized(lock) {
            f = q.removeFirstOrNull() ?: return null
            queued -= f.size
            if (splice) { splice = false; crossfade(f, lastOut) }
            excess = if (aheadCount < 5) 0 else {
                var m = Double.MAX_VALUE
                for (i in 0 until aheadCount) if (ahead[i] < m) m = ahead[i]
                (m - removed - target).toInt()
            }
        }
        val e = meanSquare(f)
        floor = if (e < floor) maxOf(e, 1e-10) else floor * 1.01   // falls at once, rises ~2 dB/s
        val quiet = e < 1e-3 && (e < 1e-6 || e < floor * 6)
        // Hysteresis: take out above target + 10 ms, put in below target - 3 ms.
        val over = excess > frame / 2
        val under = excess < -frame * 3 / 20
        if (over && !quiet) excessPolls++ else excessPolls = 0
        val out = when {
            over && quiet -> {
                val cut = minOf(excess, f.size)
                quietCut += cut
                if (cut == f.size) ShortArray(0) else f.copyOf(f.size - cut)
            }
            under && quiet -> {
                val add = minOf(-excess, f.size)
                inserted += add
                ShortArray(f.size + add) { if (it < f.size) f[it] else f[it - f.size] }
            }
            // Too much and no pause for ~0.5 s (or far too much): speed voiced frames up by ≤ 3 %. Too little: slow
            // them down at once (the track is about to run dry, which would be a gap).
            over && (excess >= 2 * frame || excessPolls > 25) -> {
                val k = minOf(excess, f.size / 32)
                compressed += k
                resample(f, f.size - k)
            }
            under -> {
                val k = minOf(-excess, f.size / 32)
                stretched += k
                resample(f, f.size + k)
            }
            else -> return f
        }
        // Every later frame finds that much less (more) ahead of it.
        synchronized(lock) { removed += f.size - out.size }
        return out
    }

    private fun noteArrival(nowNs: Long) {
        lastGapNs = if (lastArrival != 0L) nowNs - lastArrival else 0L
        if (lastArrival != 0L) {
            gaps[gapNext] = (nowNs - lastArrival) / 1e6
            gapNext = (gapNext + 1) % gaps.size
            gapCount = minOf(gapCount + 1, gaps.size)
        }
        lastArrival = nowNs
        if (++sinceTarget >= 25 && gapCount >= 25) {
            sinceTarget = 0
            // The longest gap G of the last ~10 s: the frame before it left the track about G - 20 ms to play, so that
            // much (+2 ms) must be ahead of on-time frames, 5-45 ms. Even 15 ms connection events give gaps of ~30 ms
            // (12 ms ahead); after hold-ups like the recent ones the next finds audio to play, a longer one may still
            // run dry for a few ms (covering it fully would cost its whole length in delay all call long). Without
            // hold-ups for 10 s it falls back.
            var b = 0.0; var holdUps = 0
            for (i in 0 until gapCount) {
                if (gaps[i] > b && gaps[i] < LATE_MS) b = gaps[i]
                if (gaps[i] > 63.0) holdUps++
            }
            // A few hold-ups in 10 s: up to 45 ms (more would cost its whole length in delay all call long for a few
            // gaps). Hold-ups every second (Bluetooth tethering): up to 60 ms, else the track runs dry every second.
            val cap = if (holdUps >= FREQUENT_HOLDUPS) MAX_GAP_TARGET_MS else RARE_GAP_TARGET_MS
            gapTarget = ((b - 18.0).coerceIn(5.0, cap) * rate / 1000).toInt()
            target = minOf(gapTarget + sinkExtra, rate * MAX_TARGET_MS / 1000)
        }
    }

    /**
     * Track side, after each write: the track's underrun count so far (from when the path has started; < 0 unknown).
     * Each new underrun deepens the target a little; it eases off again while the track keeps playing.
     */
    fun noteUnderruns(total: Int) = synchronized(lock) {
        if (total < 0) return@synchronized
        if (underrunsSeen < 0) { underrunsSeen = total; return@synchronized }
        if (total > underrunsSeen) {
            sinkExtra = minOf(sinkExtra + minOf(total - underrunsSeen, 2) * rate / 200, rate * 30 / 1000)
            sinceUnderrun = 0
        } else if (++sinceUnderrun >= 150 && sinkExtra > 0) {
            sinkExtra = maxOf(0, sinkExtra - rate / 1000); sinceUnderrun = 0
        }
        underrunsSeen = total
        target = minOf(gapTarget + sinkExtra, rate * MAX_TARGET_MS / 1000)
    }


    fun clear() = synchronized(lock) {
        q.clear(); queued = 0; aheadCount = 0; aheadNext = 0; removed = 0.0; lateUntil = 0; excessPolls = 0; lastArrival = 0
        lastGapNs = 0; gapCount = 0; gapNext = 0; sinceTarget = 0; target = rate * 5 / 1000; gapTarget = target; floor = 1e-6
        sinkExtra = 0; underrunsSeen = -1; sinceUnderrun = 0; splice = false; lateCutting = false
        arrived = 0; overflowFrames = 0; quietCut = 0; inserted = 0; compressed = 0; stretched = 0; levelMax = 0; levelSum = 0; levelN = 0; lateCut = 0
    }

    /** Samples queued (not yet handed to the track). */
    val size: Int get() = synchronized(lock) { queued }

    companion object {
        /** Over target by more than this (ms) after a hold-up: cut back at once to target + [KEEP_MS]. */
        const val LATE_MS = 150
        const val KEEP_MS = 30
        /** Largest delay the gaps may ask for (ms): with a few hold-ups in the window, with [FREQUENT_HOLDUPS] or more; and with the sink's extra. */
        const val RARE_GAP_TARGET_MS = 45.0
        const val MAX_GAP_TARGET_MS = 60.0
        const val FREQUENT_HOLDUPS = 6
        const val MAX_TARGET_MS = 90
        /** A frame below this mean square (about -40 dBFS) is quiet enough to drop first. */
        const val QUIET_MS = 1e-4
        /** Samples over which a frame after a dropped stretch is faded in from the last sample written (2.5 ms). */
        const val FADE = 40

        /** Fades the start of [f] from [from] (in place): no click where dropped audio left a jump. */
        fun crossfade(f: ShortArray, from: Short) {
            val n = minOf(FADE, f.size)
            for (i in 0 until n) { val t = (i + 1).toDouble() / (n + 1); f[i] = (from * (1 - t) + f[i] * t).toInt().toShort() }
        }

        fun meanSquare(f: ShortArray): Double {
            var s = 0.0
            for (v in f) { val x = v / 32768.0; s += x * x }
            return s / maxOf(1, f.size)
        }

        /** Linear resampling of [f] to [n] samples (n a few % off f.size: the frame plays that much faster or slower). */
        fun resample(f: ShortArray, n: Int): ShortArray {
            if (n <= 1 || f.size <= 1) return f.copyOf(maxOf(0, n))
            val step = (f.size - 1).toDouble() / (n - 1)
            return ShortArray(n) {
                val x = it * step
                val i = x.toInt().coerceAtMost(f.size - 2)
                val t = x - i
                (f[i] * (1 - t) + f[i + 1] * t).toInt().toShort()
            }
        }
    }
}

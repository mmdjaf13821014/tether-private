package app.tether.client

import app.tether.client.calls.PlayoutBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * TetherCore's PlayoutLongGapTests, ported: tethering hold-ups and long stalls over a 2-minute talk trace. The random
 * generator is Swift's SplitMix64 with the standard library's range algorithms, so the traces are the same and the
 * printed numbers match the Swift run exactly.
 */
class PlayoutLongGapTest {
    /** Swift's SeededRandom (SplitMix64) with `random(in:)` for Int, Int16 and Double. */
    class SeededRandom(seed: Long) {
        private var state = seed
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        private fun high(r: Long, ub: Long) = Math.multiplyHigh(r, ub) + (if (r < 0) ub else 0)
        /** Lemire's multiply-shift on 64 bits, as `RandomNumberGenerator.next(upperBound:)`. */
        private fun below64(ub: Long): Long {
            var r = next()
            var low = r * ub
            if (java.lang.Long.compareUnsigned(low, ub) < 0) {
                val t = java.lang.Long.remainderUnsigned(-ub, ub)
                while (java.lang.Long.compareUnsigned(low, t) < 0) { r = next(); low = r * ub }
            }
            return high(r, ub)
        }
        fun int(lo: Int, hi: Int): Int = lo + below64((hi - lo + 1).toLong()).toInt()
        fun int16(lo: Int, hi: Int): Short {
            val ub = hi - lo + 1
            var r = next().toInt() and 0xFFFF
            var m = r * ub
            if ((m and 0xFFFF) < ub) {
                val t = (0x10000 - ub) % ub
                while ((m and 0xFFFF) < t) { r = next().toInt() and 0xFFFF; m = r * ub }
            }
            return (lo + (m ushr 16)).toShort()
        }
        fun double(lo: Double, hi: Double): Double {
            val maxSig = 1L shl 53
            return (hi - lo) * (below64(maxSig + 1).toDouble() / maxSig.toDouble()) + lo
        }
    }

    class Frame(val pcm: ShortArray, val speech: Boolean)
    class Result {
        var concealedMs = 0.0; var underruns = 0; var drainedMs = 0.0; var speechCutMs = 0.0; var addedMs = 0.0
        var meanDelayMs = 0.0; var maxDelayMs = 0.0
        /** Seconds the playout delay stayed above 250 ms (the delay a stall left behind, not yet removed). */
        var secondsAbove250 = 0.0
    }

    companion object {
        const val FRAMES = 6000   // 2 minutes, as the owner's call

        /** A conversation as 20 ms frames: talk spurts with short pauses between words and a soft tail, then listening pauses. */
        fun talk(frames: Int, seed: Long): List<Frame> {
            val rng = SeededRandom(seed)
            val out = ArrayList<Frame>()
            var k = 0
            fun noise() = ShortArray(320) { rng.int16(-40, 40) }
            while (out.size < frames) {
                var spurt = rng.int(30, 120)
                while (spurt > 0 && out.size < frames) {
                    val word = rng.int(6, 20)
                    val f = rng.double(0.08, 0.3)
                    repeat(word) { out.add(Frame(ShortArray(320) { (7000 * sin((it + k * 320).toDouble() * f)).toInt().toShort() }, true)); k++ }
                    out.add(Frame(ShortArray(320) { (350 * sin((it + k * 320).toDouble() * f)).toInt().toShort() }, true)); k++   // the soft tail
                    repeat(rng.int(2, 6)) { out.add(Frame(noise(), false)) }
                    spurt -= word + 1
                }
                repeat(rng.int(25, 150)) { out.add(Frame(noise(), false)) }
            }
            return out.take(frames)
        }

        /** Arrival times (ms) over 15 ms connection events, hold-ups of 40-100 ms every 0.5-2 s, and long stalls (start, length) whose frames all arrive at their end. */
        fun tethering(frames: Int, seed: Long, drift: Double = -0.00048, stalls: List<Pair<Double, Double>>): DoubleArray {
            val rng = SeededRandom(seed)
            val holds = ArrayList<Pair<Double, Double>>()
            var t = 500.0
            while (t < frames * 20.0 + 5000) {
                holds.add(t to rng.double(40.0, 100.0))
                t += rng.double(500.0, 2000.0)
            }
            holds.addAll(stalls)
            return DoubleArray(frames) { k ->
                var a = ceil((k * 20.0 / (1 + drift) + 0.5) / 15) * 15
                for ((s, len) in holds) if (a >= s && a < s + len) a = s + len
                a
            }
        }

        fun run(arrivals: DoubleArray, talk: List<Frame>): Result {
            val rate = 16000.0
            val b = PlayoutBuffer(16000)
            val r = Result()
            val out = FloatArray(160)
            var next = 0; var pulls = 0
            var delaySum = 0.0
            var t = 0.0
            val end = (arrivals.maxOrNull() ?: 0.0) + 3000
            while (t < end) {
                while (next < arrivals.size && arrivals[next] <= t) {
                    b.push(talk[next].pcm, at = ((arrivals[next] + 1_000_000) * 1e6).toLong())
                    next++
                }
                b.pull(out, 160)
                val d = b.buffered / rate * 1000
                delaySum += d; pulls++
                r.maxDelayMs = max(r.maxDelayMs, d)
                if (d > 250) r.secondsAbove250 += 0.01
                t += 10
            }
            r.concealedMs = b.concealedSamples / rate * 1000
            r.underruns = b.underruns
            r.drainedMs = b.trimmed / rate * 1000
            r.speechCutMs = b.speechTrimmed / rate * 1000
            r.addedMs = b.inserted / rate * 1000
            r.meanDelayMs = delaySum / pulls
            return r
        }

        fun report(name: String, r: Result) = String.format(Locale.ROOT,
            "%s: concealed %.0f ms, underruns %d, drained %.0f ms (speech %.0f ms), added %.0f ms, delay mean %.0f max %.0f ms, %.1f s above 250 ms",
            name, r.concealedMs, r.underruns, r.drainedMs, r.speechCutMs, r.addedMs, r.meanDelayMs, r.maxDelayMs, r.secondsAbove250)
    }

    /** The owner's 2-minute call with tethering on, with stalls of 300, 435 and 880 ms. */
    @Test fun stallsNeverCutSpeech() {
        val r = run(tethering(FRAMES, 7, stalls = listOf(20_000.0 to 300.0, 55_000.0 to 435.0, 90_000.0 to 880.0)), talk(FRAMES, 7))
        println("[trace] " + report("tethering+stalls", r))
        assertEquals("real speech is never dropped to catch up after a stall", 0.0, r.speechCutMs, 0.0)
        assertTrue("the delay a stall leaves is removed in the pauses within a few seconds", r.secondsAbove250 < 12)
        assertTrue(r.meanDelayMs < 110)
        // What the Swift run prints.
        assertEquals("tethering+stalls: concealed 818 ms, underruns 4, drained 1588 ms (speech 0 ms), added 0 ms, delay mean 89 max 760 ms, 1.3 s above 250 ms", report("tethering+stalls", r))
    }

    /** Without long stalls the frequent hold-ups alone: few concealments, nothing cut from speech. */
    @Test fun frequentHoldUps() {
        val r = run(tethering(FRAMES, 3, stalls = emptyList()), talk(FRAMES, 3))
        println("[trace] " + report("tethering", r))
        assertEquals(0.0, r.speechCutMs, 0.0)
        assertEquals(0.0, r.secondsAbove250, 0.0)
        assertEquals("tethering: concealed 528 ms, underruns 1, drained 318 ms (speech 0 ms), added 0 ms, delay mean 73 max 120 ms, 0.0 s above 250 ms", report("tethering", r))
    }

    /** A very long stall (10 s, seen once at a call's end): the backlog is bounded, the newest audio plays. */
    @Test fun veryLongStallIsBounded() {
        val r = run(tethering(3000, 5, stalls = listOf(20_000.0 to 10_000.0)), talk(3000, 5))
        println("[trace] " + report("10 s stall", r))
        assertTrue(r.maxDelayMs <= PlayoutBuffer.MAX_BACKLOG_SECONDS * 1000 + 25)
        assertEquals("10 s stall: concealed 456 ms, underruns 2, drained 10056 ms (speech 4120 ms), added 0 ms, delay mean 67 max 790 ms, 0.5 s above 250 ms", report("10 s stall", r))
    }

    /** A stall's burst is no measure of the link's spread: the target is not held at its maximum after it. */
    @Test fun stallDoesNotRaiseTheArrivalSpread() {
        val talk = talk(1500, 9)
        val tr = tethering(1500, 9, drift = 0.0, stalls = listOf(10_000.0 to 600.0))
        val b = PlayoutBuffer(16000)
        val out = FloatArray(160)
        var next = 0; var t = 0.0; var worstFloor = 0
        while (t < 30_000) {
            while (next < tr.size && tr[next] <= t) { b.push(talk[next].pcm, at = ((tr[next] + 1_000_000) * 1e6).toLong()); next++ }
            b.pull(out, 160)
            if (t > 10_600) worstFloor = max(worstFloor, b.jitterFloor)
            t += 10
        }
        assertTrue("the 600 ms stall did not pin the floor at its maximum", worstFloor < b.maxTarget)
        assertEquals(0, b.speechTrimmed)
    }
}

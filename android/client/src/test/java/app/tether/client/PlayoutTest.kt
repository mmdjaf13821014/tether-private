package app.tether.client

import app.tether.client.calls.PlayoutBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/** TetherCore's PlayoutTests and PlayoutConcealedFrameTests, ported case by case. */
class PlayoutTest {
    private fun tone(n: Int, amp: Double = 8000.0) = ShortArray(n) { (amp * sin(it * 0.2)).toInt().toShort() }
    /** Virtual time: pulling n samples takes n / 16 kHz, pushes are stamped with it (the arrival statistics read it). */
    private var clock = 1_000_000_000L
    private fun push(b: PlayoutBuffer, pcm: ShortArray) = b.push(pcm, at = clock)
    private fun pull(b: PlayoutBuffer, n: Int): Pair<Int, FloatArray> {
        clock += n.toLong() * 1_000_000_000 / 16000
        val out = FloatArray(n); return b.pull(out, n) to out
    }

    @Test fun shortGapIsConcealedNotSilent() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 640)
        val (real, out) = pull(b, 160)
        assertEquals(0, real)
        assertTrue("the gap continues the last audio, faded, instead of silence", out.maxOf { abs(it) } > 0.05f)
        assertEquals("a short gap is not counted as an underrun", 0, b.underruns)
    }

    @Test fun longGapRebuffersDeeper() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 640)
        val before = b.target
        repeat(12) { pull(b, 160) }
        assertEquals(1, b.underruns)
        assertTrue(b.target > before)
        assertEquals("un-primed until the target refills", 0, pull(b, 160).first)
    }

    @Test fun driftIsDrainedInSilenceButNotInSpeech() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 320)
        repeat(3) { push(b, tone(320)) }
        assertEquals(0, b.trimmed)
        push(b, ShortArray(320))
        assertTrue(b.trimmed > 0)
    }

    @Test fun drainNeverDropsQueuedSpeech() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 320)
        repeat(3) { push(b, tone(320)) }
        val queued = 320 + 3 * 320
        push(b, ShortArray(320))
        val (real, out) = pull(b, queued)
        assertEquals("all queued speech is still there", queued, real)
        assertTrue("the newest speech plays, not cut by the drain", out.takeLast(320).maxOf { abs(it) } > 0.05f)
    }

    @Test fun afterAGapItRefillsBeforePlaying() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 640)
        pull(b, 160)
        push(b, tone(160))
        assertEquals("keeps concealing while the buffer refills", 0, pull(b, 160).first)
        push(b, tone(320))
        assertEquals(160, pull(b, 160).first)
    }

    @Test fun shortGapsDeepenTheBufferAndCleanPlayoutShrinksIt() {
        val b = PlayoutBuffer(16000)
        push(b, tone(640)); pull(b, 640)
        val start = b.target
        repeat(4) { pull(b, 160); push(b, tone(1600)); pull(b, 1600) }
        assertTrue("bursty arrival deepens the buffer", b.target > start)
        assertTrue(b.target <= b.maxTarget)
        val deep = b.target
        repeat(700) { push(b, tone(160)); pull(b, 160) }
        assertTrue("and it shrinks back once arrival is smooth", b.target < deep)
    }

    /** A lost frame filled by the decoder's concealment takes its place on the timeline. */
    @Test fun concealedFramesKeepTheTimeline() {
        val b = PlayoutBuffer(16000)
        val frame = ShortArray(320) { 1000 }
        var t = 1_000_000_000L
        val out = FloatArray(320)
        for (k in 0 until 200) {
            if (k % 10 == 5) b.push(frame, at = t, concealed = true) else b.push(frame, at = t)
            b.pull(out, 320)
            t += 20_000_000
        }
        assertEquals(20, b.concealedFrames)
        assertEquals(b.minTarget, b.jitterFloor)
    }
}

/**
 * TetherCore's PlayoutTraceTests, ported: the same simulated arrival traces (virtual clock, 20 ms frames, 10 ms pulls),
 * the same bounds. The printed numbers match the Swift run exactly.
 */
class PlayoutTraceTest {
    data class Result(val concealedMs: Double, val underruns: Int, val meanDelayMs: Double, val meanTargetMs: Double)

    companion object {
        /** Talk spurts: 1.2 s of speech-like tone, then 0.8 s of silence. */
        fun tone(n: Int, k: Int) =
            if (k % 100 < 60) ShortArray(n) { (8000 * sin((it + k * n).toDouble() * 0.2)).toInt().toShort() } else ShortArray(n)

        fun run(arrivals: DoubleArray, seconds: Double = 60.0, hi: Double? = null): Result {
            val rate = 16000.0
            val b = if (hi != null) PlayoutBuffer(16000, hi) else PlayoutBuffer(16000)
            val order = arrivals.indices.sortedWith(compareBy<Int>({ arrivals[it] }, { it }))
            var next = 0
            val out = FloatArray(160)
            var delaySum = 0.0; var targetSum = 0.0; var pulls = 0
            var t = arrivals.minOrNull() ?: 0.0
            val end = t + seconds * 1000
            while (t < end) {
                while (next < order.size && arrivals[order[next]] <= t) {
                    val k = order[next]
                    b.push(tone(320, k), at = ((arrivals[k] + 1_000_000) * 1e6).toLong())
                    next++
                }
                b.pull(out, 160)
                delaySum += b.buffered / rate * 1000; targetSum += b.target / rate * 1000; pulls++
                t += 10
            }
            return Result(b.concealedSamples / rate * 1000, b.underruns, delaySum / pulls, targetSum / pulls)
        }

        const val FRAMES = 3000   // 60 s

        fun steady() = DoubleArray(FRAMES) { it * 20.0 + ((it * 7) % 3) * 0.3 }
        /** Frames produced every 20 ms, delivered at the next 15 ms connection event. */
        fun ble15() = DoubleArray(FRAMES) { ceil((it * 20.0 + 0.5) / 15) * 15 }
        /** 15 ms events, and every 2 s a 70 ms hold-up. */
        fun ble15Holdups() = ble15().map { a -> val phase = a % 2000; if (phase < 70) a - phase + 70 else a }.toDoubleArray()
        /** 30 ms events with a 60-77 ms gap every ~3 s. */
        fun ble30Gaps() = DoubleArray(FRAMES) { k ->
            val a = ceil((k * 20.0 + 0.5) / 30) * 30
            val cycle = 3100.0; val phase = a % cycle
            val gap = 60 + ((((a / cycle).toInt()) * 17) % 18).toDouble()
            if (phase < gap) a - phase + gap else a
        }
        /** The phone's clock 0.1 % fast against ours, 15 ms events. */
        fun drift() = DoubleArray(FRAMES) { ceil((it * 19.98 + 0.5) / 15) * 15 }
        /** 15 ms events with one 300 ms hold-up half-way. */
        fun spike() = ble15().map { a -> if (a >= 30_000 && a < 30_300) 30_300.0 else a }.toDoubleArray()
        /** The reference link (2026-11-23): 15 ms events, a hold-up every ~2 s; [drift]: the far clock that much fast (+) or slow (-). */
        fun measured(drift: Double = 0.0) = DoubleArray(FRAMES) { k ->
            val a = ceil((k * 20.0 / (1 + drift) + 0.5) / 15) * 15
            val cycle = 1900.0 + ((((a / 1900).toInt()) * 7) % 5) * 100.0; val phase = a % cycle
            val hold = 45 + ((((a / cycle).toInt()) * 11) % 18).toDouble()
            if (phase < hold) a - phase + hold else a
        }

        fun report(name: String, r: Result) = String.format(Locale.ROOT,
            "%s: concealed %.0f ms, underruns %d, mean delay %.1f ms, mean target %.1f ms", name, r.concealedMs, r.underruns, r.meanDelayMs, r.meanTargetMs)
    }

    @Test fun measuredLinkWithDrift() {
        for ((name, tr) in listOf("measured" to measured(), "measured+2%" to measured(0.02), "measured-2%" to measured(-0.02))) {
            val r = run(tr)
            println("[trace] " + report(name, r))
            assertTrue(name, r.concealedMs < 300)
            assertTrue(name, r.meanDelayMs < 75)
            assertTrue(name, r.underruns <= 1)
        }
    }

    @Test fun startupStallDoesNotRaiseTheTarget() {
        val tr = measured().map { a -> if (a >= 300 && a < 560) 560.0 else a }
        val rate = 16000.0
        val b = PlayoutBuffer(16000)
        val out = FloatArray(160)
        var next = 0
        var t = 0.0
        while (t < 6000) {
            while (next < tr.size && tr[next] <= t) { b.push(tone(320, next), at = ((tr[next] + 1_000_000) * 1e6).toLong()); next++ }
            b.pull(out, 160)
            t += 10
        }
        assertTrue("the floor follows the link after the start, not the stall", b.jitterFloor / rate * 1000 < 90)
    }

    @Test fun traces() {
        val traces = listOf("steady" to steady(), "ble15" to ble15(), "ble15+holdups" to ble15Holdups(),
            "ble30+gaps" to ble30Gaps(), "drift" to drift(), "spike" to spike())
        for ((name, tr) in traces) {
            val r = run(tr)
            println("[trace] " + report(name, r))
            assertTrue(name, r.meanDelayMs <= 130)
            if (name != "spike") assertEquals(name, 0, r.underruns)
        }
    }

    @Test fun smoothArrivalStaysShallow() {
        val r = run(ble15())
        assertEquals(0.0, r.concealedMs, 0.0)
        assertTrue(r.meanDelayMs < 25)
    }

    @Test fun periodicHoldUpsAreAbsorbed() {
        assertTrue(run(ble15Holdups()).concealedMs < 100)
        assertTrue(run(ble30Gaps()).concealedMs < 400)
    }

    @Test fun oneSpikeDoesNotKeepTheBufferDeep() {
        assertTrue("a single 300 ms hold-up is not taken as the link's normal spread", run(spike()).meanDelayMs < 40)
    }
}

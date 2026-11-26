package app.tether.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimingTest {
    private fun ms(v: Double) = (v * 1e6).toLong()

    @Test fun steadyStreamHasNoJitter() {
        val s = Spacing()
        for (i in 0 until 50) s.mark(ms(1000.0 + i * 20.0))
        assertEquals(49, s.gaps)
        assertEquals(0.0, s.jitterMs, 1e-6)
        assertEquals(20.0, s.worstMs, 1e-6)
        assertEquals(0, s.longGaps)
    }

    @Test fun burstsShowAsJitterAndLongGaps() {
        val s = Spacing()
        // Pairs of frames every 40 ms (0 / 40 ms gaps), then a 69.5 ms hold-up.
        var t = 1000.0
        repeat(10) { s.mark(ms(t)); s.mark(ms(t + 0.5)); t += 40.0 }
        s.mark(ms(t + 30.0))
        assertEquals(20, s.gaps)
        assertEquals(69.5, s.worstMs, 1e-6)
        assertEquals(1, s.longGaps)   // only the long one is over 2.5 frames
        assertEquals((10 * 19.5 + 9 * 19.5 + 49.5) / 20, s.jitterMs, 1e-6)
    }

    @Test fun queueDelayAverages() {
        val q = QueueDelay()
        q.written(2.0); q.written(4.0); q.droppedLate(); q.droppedOverflow()
        assertEquals(2, q.sent); assertEquals(3.0, q.avgMs, 1e-9); assertEquals(4.0, q.maxMs, 1e-9)
        assertEquals(1, q.late); assertEquals(1, q.overflow)
        q.reset(); assertEquals(0, q.sent); assertEquals(0.0, q.avgMs, 1e-9)
    }
}

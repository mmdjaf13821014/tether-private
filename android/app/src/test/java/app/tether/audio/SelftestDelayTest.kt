package app.tether.audio

import app.tether.helper.CallAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelftestDelayTest {
    @Test fun onsetIsTheFirstFrameAtThreshold() {
        val level = DoubleArray(10) { if (it >= 4) 100.0 else 1.0 }
        val readAt = LongArray(10) { 1_000_000_000L + (it + 1) * 20_000_000L }
        assertEquals(100L, CallAudio.onsetMs(level, readAt, t0 = 1_000_000_000L, threshold = 50.0))
    }

    @Test fun noOnsetWithoutTone() {
        assertNull(CallAudio.onsetMs(DoubleArray(5) { 1.0 }, LongArray(5), 0, threshold = 50.0))
        assertNull(CallAudio.onsetMs(DoubleArray(5), LongArray(5), 0, threshold = 0.0))
    }
}

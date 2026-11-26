package app.tether.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GATT fallback cuts the byte stream into notifications the central reassembles in order. */
class GattChunkTest {
    private val data = ByteArray(1000) { it.toByte() }

    @Test fun chunksFitTheMtuAndRejoinToTheSameBytes() {
        for (mtu in listOf(23, 24, 185, 247, 517, 2000)) {
            val c = GattTransport.chunk(data, mtu)
            assertTrue(c.all { it.size <= maxOf(20, mtu - 3) && it.isNotEmpty() })
            assertArrayEquals(data, c.fold(ByteArray(0)) { a, b -> a + b })
        }
    }

    @Test fun aTinyOrBogusMtuStillMovesTwentyBytes() {
        assertEquals(50, GattTransport.chunk(data, 0).size)
        assertEquals(50, GattTransport.chunk(data, 23).size)
    }

    @Test fun emptyWriteSendsNothing() {
        assertEquals(0, GattTransport.chunk(ByteArray(0), 23).size)
    }

    @Test fun backPressureLimitAllowsAFullRecord() {
        // A 65 000-byte message must still fit after the queue drains below the limit.
        assertTrue(GattTransport.MAX_QUEUED >= 16 * 1024)
    }
}

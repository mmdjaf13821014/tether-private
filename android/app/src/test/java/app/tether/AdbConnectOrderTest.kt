package app.tether

import app.tether.adb.AdbEndpoints
import app.tether.adb.ConnectOrder
import app.tether.adb.connectInOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbConnectOrderTest {
    private class Fake(val tls: Result<Boolean>, val legacy: Result<Boolean>) : AdbEndpoints {
        val calls = mutableListOf<String>()
        override fun connectTls(): Boolean { calls += "tls"; return tls.getOrThrow() }
        override fun connectLegacy(port: Int): Boolean { calls += "legacy:$port"; return legacy.getOrThrow() }
    }
    private fun ok() = Result.success(true)
    private fun no() = Result.success(false)
    private fun boom() = Result.failure<Boolean>(java.io.IOException("refused"))

    @Test fun tlsWinsAndLegacyIsNotTried() {
        val f = Fake(ok(), ok())
        assertEquals("tls", connectInOrder(f, true, true, 5555))
        assertEquals(listOf("tls"), f.calls)
    }

    @Test fun legacyIsTriedSecondAfterTlsFailsOrThrows() {
        for (tls in listOf(no(), boom())) {
            val f = Fake(tls, ok())
            val log = mutableListOf<String>()
            assertEquals("legacy", connectInOrder(f, true, true, 5556) { log += it })
            assertEquals(listOf("tls", "legacy:5556"), f.calls)
            assertEquals(if (tls.isFailure) 1 else 0, log.size)
        }
    }

    @Test fun legacyIsOptIn() {
        val f = Fake(no(), ok())
        assertNull(connectInOrder(f, true, false, 5555))
        assertEquals(listOf("tls"), f.calls)
    }

    @Test fun androidWithoutWirelessDebuggingGoesStraightToLegacy() {
        assertEquals(false, ConnectOrder.tlsSupported(29))
        assertEquals(true, ConnectOrder.tlsSupported(30))
        val f = Fake(ok(), ok())
        assertEquals("legacy", connectInOrder(f, false, true, 5555))
        assertEquals(listOf("legacy:5555"), f.calls)
    }

    @Test fun bothFailing() {
        val f = Fake(no(), boom())
        assertNull(connectInOrder(f, true, true, 5555))
        assertEquals(listOf("tls", "legacy:5555"), f.calls)
    }
}

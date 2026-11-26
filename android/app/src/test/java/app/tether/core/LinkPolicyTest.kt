package app.tether.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkPolicyTest {
    // ------------------------------------------------------------------ GATT stream: loss, duplication, reordering

    private val k1 = ByteArray(32) { 1 }
    private val k2 = ByteArray(32) { 2 }

    /** The byte stream of [n] sealed records, cut into GATT-sized chunks (20 bytes, as at a 23-byte MTU). */
    private fun chunks(n: Int): List<ByteArray> {
        val tx = SecureSession(k1, k2)
        val bytes = (0 until n).flatMap { tx.seal(Proto.CH_CONTROL, "message $it padded to be longer than one chunk".toByteArray()).encoded().toList() }.toByteArray()
        return bytes.toList().chunked(20).map { it.toByteArray() }
    }

    /** Bytes left half-received by the last [receive] (a stream stuck mid-record: the A54 ends such a session). */
    private var leftOver = 0

    /** Feeds chunks to a reader + session as the receiving side does; returns the messages, or the error. */
    private fun receive(cs: List<ByteArray>): Pair<List<String>, Exception?> {
        val rx = SecureSession(k2, k1)
        val reader = RecordReader()
        val out = ArrayList<String>()
        return try {
            for (c in cs) {
                reader.append(c)
                while (true) { val r = reader.next() ?: break; out.add(String(rx.open(r).second)) }
            }
            leftOver = reader.pendingBytes
            out to null
        } catch (e: Exception) { out to e }
    }

    @Test fun inOrderChunksDeliverEverything() {
        val (msgs, err) = receive(chunks(20))
        assertNull(err); assertEquals(20, msgs.size); assertEquals("message 19 padded to be longer than one chunk", msgs.last())
    }

    @Test fun aLostChunkIsDetectedNeverMisdelivered() {
        val cs = chunks(20)
        for (drop in listOf(0, 1, 7, cs.size / 2, cs.size - 1)) {
            val (msgs, err) = receive(cs.filterIndexed { i, _ -> i != drop })
            // Nothing wrong is ever delivered, and the loss is noticed: either an error, or the stream is left stuck
            // mid-record, which the A54 ends after 10 s (Connection.stalledMs) and the iPhone after its own check.
            assertTrue(msgs.all { it.startsWith("message ") && it.endsWith("chunk") })
            assertTrue("loss at $drop must be detected", err != null || leftOver > 0)
            assertTrue(msgs.size < 20)
        }
    }

    @Test fun aDuplicatedOrReorderedChunkIsDetected() {
        val cs = chunks(20)
        val dup = cs.toMutableList().apply { add(5, cs[5]) }
        assertTrue(receive(dup).second != null)
        val swapped = cs.toMutableList().apply { val t = this[3]; this[3] = this[4]; this[4] = t }
        assertTrue(receive(swapped).second != null)
    }

    @Test fun aReplayedRecordIsRejected() {
        val tx = SecureSession(k1, k2); val rx = SecureSession(k2, k1)
        val r = tx.seal(Proto.CH_CONTROL, "x".toByteArray())
        rx.open(r)
        try { rx.open(r); fail("replay accepted") } catch (_: SessionException) {}
    }

    // ------------------------------------------------------------------ which bearer change ends a GATT session

    @Test fun gattSessionOnClassicEndsWhenLeAppearsOrClassicGoes() {
        assertTrue(LinkPolicy.gattPipeEnds(pipeOnLe = false, aclIsLe = true, aclUp = true))    // iOS moves to LE
        assertTrue(LinkPolicy.gattPipeEnds(pipeOnLe = false, aclIsLe = false, aclUp = false))  // classic link gone
        assertFalse(LinkPolicy.gattPipeEnds(pipeOnLe = false, aclIsLe = true, aclUp = false))  // some LE link went: not ours
        assertFalse(LinkPolicy.gattPipeEnds(pipeOnLe = false, aclIsLe = false, aclUp = true))
    }

    @Test fun gattSessionOnLeEndsOnlyWhenLeGoes() {
        assertTrue(LinkPolicy.gattPipeEnds(pipeOnLe = true, aclIsLe = true, aclUp = false))
        assertFalse(LinkPolicy.gattPipeEnds(pipeOnLe = true, aclIsLe = false, aclUp = false))  // tethering link dropped
        assertFalse(LinkPolicy.gattPipeEnds(pipeOnLe = true, aclIsLe = false, aclUp = true))
        assertFalse(LinkPolicy.gattPipeEnds(pipeOnLe = true, aclIsLe = true, aclUp = true))
    }

    // ------------------------------------------------------------------ a repeated pair hello after a lost FIN

    @Test fun repeatedPairHelloFromTheSameCentralIsAcceptedForAWhile() {
        var t = 0L
        val p = PairingTokens(windowMs = 600_000) { t }
        val token = byteArrayOf(9, 9); val iphone = byteArrayOf(1); val other = byteArrayOf(2)
        assertNull(p.tokenFor(iphone, open = null))
        p.completed(token, iphone)
        assertArrayEquals(token, p.tokenFor(iphone, open = null))
        assertNull("another device may not use a spent code", p.tokenFor(other, open = null))
        t = 600_001
        assertNull("only for a while", p.tokenFor(iphone, open = null))
        val fresh = byteArrayOf(7)
        assertArrayEquals("an open code always wins", fresh, p.tokenFor(iphone, open = fresh))
    }

    // ------------------------------------------------------------------ our own LE link (close / reopen, 2026-11-22)

    @Test fun ourLeLinkIsHeldOnlyDuringASession() {
        assertTrue(LinkPolicy.holdLeLink(sessionUp = true))
        assertFalse("a held link blocks the reopened app's connect", LinkPolicy.holdLeLink(sessionUp = false))
    }

    @Test fun pulsesOnlyWhilePairedWithoutASession() {
        assertTrue(LinkPolicy.pulseLeLink(paired = true, sessionUp = false, linkOpen = false))
        assertFalse(LinkPolicy.pulseLeLink(paired = true, sessionUp = true, linkOpen = false))   // suspended app: no wake-ups
        assertFalse(LinkPolicy.pulseLeLink(paired = false, sessionUp = false, linkOpen = false)) // unpaired: nothing
        assertFalse("never into an existing (lingering) link", LinkPolicy.pulseLeLink(paired = true, sessionUp = false, linkOpen = true))
    }

    @Test fun pulseScheduleCoversTheLingerAndStaysCheap() {
        // The first pulse comes after Android's ~4 s linger of a released link would have started, and the burst is
        // over before the regular period takes over; a pulse is short against its period.
        // A pulse plus Android's ~4 s linger after release is over before a check could start the next one only when
        // the check sees no link (pulseLeLink's linkOpen); checks are frequent at first, then every 30 s.
        assertTrue(LinkPolicy.PULSE_UP_MS <= 3_000)
        assertTrue(LinkPolicy.PULSE_SOON_EVERY_S in 3..10 && LinkPolicy.PULSE_SOON_FOR_S >= 30)
        assertTrue(LinkPolicy.PULSE_UP_MS * 5 <= LinkPolicy.PULSE_EVERY_S * 1000)
    }

    // ------------------------------------------------------------------ idle sessions

    @Test fun silentClientIsWokenBeforeItIsDropped() {
        val m = 60_000L
        assertEquals(LinkPolicy.Idle.KEEP, LinkPolicy.idle(14 * m, null))
        assertEquals(LinkPolicy.Idle.WAKE, LinkPolicy.idle(15 * m, null))
        assertEquals(LinkPolicy.Idle.KEEP, LinkPolicy.idle(15 * m + 1000, 1000))     // woken, waiting for its answer
        assertEquals(LinkPolicy.Idle.CLOSE, LinkPolicy.idle(16 * m, 60_000))         // woken a minute ago, still silent
        assertEquals(LinkPolicy.Idle.KEEP, LinkPolicy.idle(5_000, null))             // it answered: silence counts afresh
    }

    @Test fun withoutASessionThePairedIphoneIsWokenEveryThreeMinutes() {
        // 2026-11-25: a suspended iPhone app was not told the session closed (tethering keeps its Bluetooth connection
        // up over classic) and stayed without one for 21 minutes; one wake every 3 minutes lets it set one up again.
        assertTrue(LinkPolicy.wakeUnlinked(Long.MAX_VALUE / 2))
        assertTrue(LinkPolicy.wakeUnlinked(3 * 60_000L))
        assertFalse(LinkPolicy.wakeUnlinked(3 * 60_000L - 1))
    }
}

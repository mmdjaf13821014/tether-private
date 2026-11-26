package app.tether.client

import app.tether.client.calls.CallTracker
import app.tether.client.model.Call
import app.tether.core.T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's CallController rules as ported to CallTracker, driven state by state without a session. */
class CallTrackerTest {
    private val link = ClientStateTest.FakeLink()
    private val sink = RecordingSink()
    private var clock = 1_000L   // never 0: a cancelled dial is stamped with the clock
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val t = CallTracker(link, sink, now = { clock }, schedule = { ms, b -> timers += ms to b })

    private fun call(cid: String, state: String, dir: String = "in", num: String = "+10000000001", elsewhere: Boolean = false,
                     cause: String? = null) = Call(cid, num, null, state, dir, null, null, cause, true, elsewhere, if (elsewhere) "Tablet" else null)
    private fun h(vararg cs: Call) = cs.forEach(t::handle)
    private fun sentTypes() = link.sent.map { it.t }

    /** Table: a sequence of states from the Android phone → what the local call system was told. */
    @Test fun stateSequences() {
        val cases = listOf(
            listOf(call("a", "ringing"), call("a", "ended")) to listOf("incoming a", "ended UNANSWERED", "idle"),
            listOf(call("a", "ringing"), call("a", "active"), call("a", "ended")) to listOf("incoming a", "active", "ended REMOTE", "idle"),
            listOf(call("a", "active")) to listOf("started a active", "active"),
            listOf(call("a", "dialing", "out"), call("a", "ended", "out", cause = "BUSY")) to listOf("started a dialing", "ended BUSY", "idle"),
            listOf(call("a", "active"), call("a", "holding"), call("a", "holding"), call("a", "active")) to
                listOf("started a active", "active", "held true", "active"),
            listOf(call("a", "ringing"), call("a", "ringing", elsewhere = true)) to listOf("incoming a", "ended ANSWERED_ELSEWHERE", "idle"),
            listOf(call("a", "ended")) to emptyList<String>(),
        )
        for ((input, want) in cases) {
            val sk = RecordingSink()
            val tr = CallTracker(link, sk, now = { clock })
            input.forEach(tr::handle)
            assertEquals(input.joinToString { "${it.cid}:${it.state}" }, want, sk.events)
        }
    }

    @Test fun ringbackWhileDialingStopsWhenConnected() {
        h(call("o", "dialing", "out")); assertTrue(sink.ringback)
        h(call("o", "active", "out")); assertFalse(sink.ringback)
        assertNotNullConnectedAt()
    }

    private fun assertNotNullConnectedAt() = assertTrue(t.calls.single().connectedAt != null)

    @Test fun hangUpGuardDropsLateStatesThenAsksAgain() {
        h(call("a", "ringing"))
        val local = t.localOf("a")!!
        t.performEnd(local)
        assertEquals(T.CALL_REJECT, link.sent.last().t)
        sink.events.clear()
        clock += 500; h(call("a", "ringing"))
        assertTrue("a late ring does not ring again", sink.events.isEmpty())
        clock += 2000; h(call("a", "active"))
        assertEquals("still not ended after 2 s: asked again", T.CALL_END, link.sent.last().t)
        assertFalse(t.hasCalls)
    }

    @Test fun aDialHungUpBeforeThePhoneReportedItIsEndedWhenItShowsUp() {
        t.performStart("L", "+10000000002", 3)
        assertEquals(3, link.last(T.CALL_DIAL).b.getInt("sub"))
        assertEquals("dialing", t.calls.single().state)
        t.performEnd("L")
        assertFalse(t.hasCalls)
        clock += 1000; h(call("o", "dialing", "out", num = "+1 000 000 0002"))
        assertEquals("o", link.last(T.CALL_END).b.getString("cid"))
        assertFalse(t.hasCalls)
    }

    @Test fun aQuickRedialToAnotherNumberIsNotTakenForTheCancelledOne() {
        t.performStart("L1", "+10000000002", null); t.performEnd("L1")
        t.performStart("L2", "+10000000003", null)
        h(call("o2", "dialing", "out", num = "+10000000003"))
        assertEquals("o2", t.cidOf("L2"))
        assertTrue(link.sent.none { it.t == T.CALL_END })
    }

    @Test fun aCancelledDialIsForgottenAfter30s() {
        t.performStart("L", "+10000000002", null); t.performEnd("L")
        clock += 30_000; h(call("o", "dialing", "out", num = "+10000000002"))
        assertTrue("shown as a call dialed on the phone", t.hasCalls)
        assertTrue(link.sent.none { it.t == T.CALL_END })
    }

    @Test fun aDialThePhoneNeverReportsFailsAfter30s() {
        t.performStart("L", "+10000000002", null)
        t.dialAccepted()
        assertEquals(30_000L, timers.single().first)
        timers.single().second()
        assertTrue(sink.events.contains("ended FAILED")); assertFalse(t.hasCalls)
        // Bound in time: the timer does nothing.
        t.performStart("M", "+10000000002", null); t.dialAccepted()
        h(call("o", "dialing", "out", num = "+10000000002"))
        sink.events.clear(); timers.last().second()
        assertTrue(sink.events.isEmpty()); assertEquals("o", t.cidOf("M"))
    }

    @Test fun answerHoldDtmfMuteGoToThePhone() {
        h(call("a", "ringing"))
        val l = t.localOf("a")!!
        t.performAnswer(l)
        assertEquals("a", link.last(T.CALL_ANSWER).b.getString("cid"))
        assertTrue(t.performHold(l, true))
        assertTrue(link.last(T.CALL_HOLD).b.getBoolean("on"))
        assertFalse("no call id: nothing to hold", t.performHold("nope", true))
        t.performDtmf(l, "12#")
        assertEquals(listOf("1", "2", "#"), link.sent.filter { it.t == T.CALL_DTMF }.map { it.b.getString("d") })
        t.performMute(true)
        assertTrue(link.last(T.CALL_MUTE).b.getBoolean("on"))
    }

    @Test fun callWaitingOrdersTheCurrentCallFirst() {
        h(call("a", "active"), call("b", "ringing"))
        assertEquals(listOf("a", "b"), t.calls.map { it.cid })
        h(call("a", "holding"), call("b", "active"))
        assertEquals(listOf("b", "a"), t.calls.map { it.cid })
        assertTrue(t.calls.last().held)
        h(call("b", "ended"))
        assertFalse("a held call keeps the audio alive", sink.events.contains("idle"))
        h(call("a", "ended"))
        assertEquals("idle", sink.events.last())
    }

    @Test fun elsewhereOffersMoveHereAndTakeAsksForTheAudio() {
        h(call("a", "active"))
        h(call("a", "active", elsewhere = true))
        assertTrue(sink.events.contains("ended ANSWERED_ELSEWHERE"))
        assertEquals("Tablet", t.remote["a"]!!.where)
        t.take("a")
        assertEquals("a", link.last(T.CALL_TAKE).b.getString("cid"))
        assertTrue(t.remote.isEmpty())
        sink.events.clear()
        h(call("a", "active"))
        assertEquals("comes back as a started call", listOf("started a active", "active"), sink.events)
        t.take("zzz")
        assertEquals("unknown calls are not taken", 1, link.sent.count { it.t == T.CALL_TAKE })
        h(call("b", "active", elsewhere = true)); h(call("b", "ended"))
        assertTrue("an ended remote call is no longer offered", t.remote.isEmpty())
    }

    @Test fun reconnectReconcilesAndLinkLossEndsEverything() {
        h(call("a", "active"), call("b", "holding"), call("c", "active", elsewhere = true))
        t.reconcile(listOf(call("a", "active")))
        assertEquals(listOf("a"), t.calls.map { it.cid }); assertTrue(t.remote.isEmpty())
        t.performStart("L", "+10000000002", null)
        sink.events.clear()
        t.linkLost()
        assertEquals(listOf("ended FAILED", "ended FAILED", "idle"), sink.events)
        assertFalse(t.hasCalls); assertNull(t.pendingOutgoing)
    }

    @Test fun aCallTelecomRefusedShowsUpAgainAsStarted() {
        h(call("a", "ringing"))
        t.localFailed(t.localOf("a")!!)
        sink.events.clear()
        h(call("a", "active"))
        assertEquals(listOf("started a active", "active"), sink.events)
        assertTrue(sentTypes().none { it == T.CALL_END })
    }

    @Test fun aRefusedDialEndsOnlyTheDialAndStopsRingback() {
        h(call("a", "active"))
        t.performStart("L", "+10000000002", null)
        assertTrue(sink.ringback)
        t.dialFailed()
        assertFalse(sink.ringback); assertEquals(listOf("a"), t.calls.map { it.cid })
    }
}

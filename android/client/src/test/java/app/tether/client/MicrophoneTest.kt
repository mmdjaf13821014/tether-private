package app.tether.client

import app.tether.client.calls.CallTracker
import app.tether.client.calls.CallUplink
import app.tether.client.calls.MicPolicy
import app.tether.client.calls.MicWarning
import app.tether.client.calls.SilenceDetector
import app.tether.client.calls.UplinkGate
import app.tether.client.model.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's CallUplink (469736a) and MicPolicy (01fe22f) rules as ported to the client. */
class MicrophoneTest {
    // ------------------------------------------------------------------ the rule (same table as the iPhone's test)

    @Test fun uplinkOnlyWhileACallIsConnected() {
        assertFalse(CallUplink.open(emptyList()))
        for (s in listOf("dialing", "connecting", "ringing", "holding")) assertFalse(s, CallUplink.open(listOf(s)))
        assertTrue(CallUplink.open(listOf("active")))
        assertTrue("call waiting: the active one talks", CallUplink.open(listOf("holding", "active")))
        assertTrue(CallUplink.open(listOf("active", "ringing")))
    }

    // ------------------------------------------------------------------ fed from the call tracker

    private val sink = RecordingSink()
    private val t = CallTracker(ClientStateTest.FakeLink(), sink, now = { 1_000L })
    private fun call(state: String, dir: String = "out") = Call("a", "+10000000001", null, state, dir, null, null, null, true, false, null)

    @Test fun trackerOpensTheUplinkOnlyWhenActiveAndAfresh() {
        t.handle(call("dialing")); t.handle(call("connecting"))
        assertTrue("nothing while dialing", sink.uplinkChanges.isEmpty())
        t.handle(call("active")); assertEquals(listOf(true), sink.uplinkChanges)
        t.handle(call("active")); assertEquals("no repeat", listOf(true), sink.uplinkChanges)
        t.handle(call("holding")); assertEquals(listOf(true, false), sink.uplinkChanges)
        t.handle(call("active")); assertEquals("afresh after hold", listOf(true, false, true), sink.uplinkChanges)
        t.handle(call("ended")); assertEquals(listOf(true, false, true, false), sink.uplinkChanges)
    }

    @Test fun trackerClosesTheUplinkWhenTheLinkIsLost() {
        t.handle(call("active")); assertTrue(sink.uplink)
        t.linkLost(); assertFalse(sink.uplink)
    }

    @Test fun incomingRingingStaysClosedUntilAnswered() {
        t.handle(call("ringing", "in")); assertTrue(sink.uplinkChanges.isEmpty())
        t.handle(call("active", "in")); assertTrue(sink.uplink)
    }

    // ------------------------------------------------------------------ the recorder thread's gate

    @Test fun gateSendsNothingWhileDialingThenSendsAfreshAndLogsOnce() {
        val log = ArrayList<String>()
        val g = UplinkGate { log += it }
        repeat(3) { assertEquals(UplinkGate.Admit.HOLD, g.admit()) }
        assertTrue(log.isEmpty())
        g.set(true)
        assertEquals(UplinkGate.Admit.OPENED, g.admit())
        assertEquals(listOf("call audio: uplink open (3 frames held back before the call connected)"), log)
        repeat(5) { assertEquals(UplinkGate.Admit.SEND, g.admit()) }
        assertEquals("logged once", 1, log.size)
        // Hold, then active again: held back, then afresh.
        g.set(false)
        repeat(2) { assertEquals(UplinkGate.Admit.HOLD, g.admit()) }
        g.set(true)
        assertEquals(UplinkGate.Admit.OPENED, g.admit())
        assertEquals("call audio: uplink open (2 frames held back before the call connected)", log.last())
        g.reset()
        assertEquals(UplinkGate.Admit.HOLD, g.admit())
    }

    // ------------------------------------------------------------------ silent or refused microphone

    @Test fun silenceIsReportedOnceAfterSixSecondsAndTakenBackWhenSoundComes() {
        val d = SilenceDetector(16000)
        val zeros = ShortArray(320)
        var events = ArrayList<SilenceDetector.Event>()
        repeat(299) { d.feed(zeros, 320)?.let { e -> events += e } }  // 5.98 s
        assertTrue(events.isEmpty())
        d.feed(zeros, 320)?.let { events += it }                      // 6.0 s
        repeat(50) { d.feed(zeros, 320)?.let { e -> events += e } }
        assertEquals(listOf(SilenceDetector.Event.SILENT), events)
        val noise = ShortArray(320).also { it[7] = 1 }
        assertEquals(SilenceDetector.Event.SOUND, d.feed(noise, 320))
        assertNull(d.feed(zeros, 320))
    }

    @Test fun anySoundBeforeSixSecondsMeansNoSilence() {
        val d = SilenceDetector(16000)
        repeat(50) { assertNull(d.feed(ShortArray(320), 320)) }
        assertNull(d.feed(ShortArray(320).also { it[0] = -1 }, 320))
        repeat(500) { assertNull(d.feed(ShortArray(320), 320)) }
        d.reset()
        repeat(299) { assertNull(d.feed(ShortArray(320), 320)) }
        assertEquals(SilenceDetector.Event.SILENT, d.feed(ShortArray(320), 320))
    }

    @Test fun warningPriorityAndCallCheck() {
        assertNull(MicPolicy.warning(granted = true, recorderFailed = false, captureSilent = false))
        assertEquals(MicWarning.DENIED, MicPolicy.warning(granted = false, recorderFailed = true, captureSilent = true))
        assertEquals(MicWarning.UNAVAILABLE, MicPolicy.warning(granted = true, recorderFailed = true, captureSilent = true))
        assertEquals(MicWarning.SILENT, MicPolicy.warning(granted = true, recorderFailed = false, captureSilent = true))
        assertEquals(MicPolicy.CallCheck.FINE, MicPolicy.atCall(true))
        assertEquals(MicPolicy.CallCheck.ASK, MicPolicy.atCall(false))
    }
}

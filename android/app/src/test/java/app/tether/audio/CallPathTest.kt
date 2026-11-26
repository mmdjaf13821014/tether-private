package app.tether.audio

import app.tether.helper.MicPath.Mode
import app.tether.helper.OpenGate
import app.tether.helper.PathWatch
import app.tether.helper.PathWatch.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the helper opens a call's audio (OpenGate), what it reports about dead paths (PathWatch), when the app restarts it. */
class CallPathTest {
    private val refused = "IllegalStateException:  not available in mode MODE_NORMAL"

    @Test fun nothingOpensWhileTheCallHasNoAudioMode() {
        // 2026-11-24, reference phone: an outgoing call stayed in MODE_NORMAL for seconds of dialing; the old 4 s wait
        // ran out and the fallbacks gave a capture with 4 frames in the whole call.
        val g = OpenGate()
        assertFalse(g.ready(Mode.NORMAL, activeForMs = -1))
        assertFalse(g.ready(Mode.RINGTONE, activeForMs = -1))
        assertTrue(g.ready(Mode.IN_CALL, activeForMs = -1))
        assertTrue("the VoIP test line", g.ready(Mode.IN_COMMUNICATION, activeForMs = -1))
        assertTrue("a phone that never reports a call's mode still gets its audio once the call is up", g.ready(Mode.NORMAL, activeForMs = 3000))
        assertFalse(g.ready(Mode.NORMAL, activeForMs = 2000))
    }

    @Test fun theInterceptionApiIsRetriedQuietlyNotGivenUpForAnOlderMethod() {
        val g = OpenGate(graceMs = 2000)
        // Dialing for 30 s in NORMAL: retried all along.
        for (t in 0L..30_000L step 500) assertTrue(g.retry("intercept", refused, Mode.NORMAL, t, -1))
        // In the call's mode the API gets 2 s to follow, then the next method.
        assertTrue(g.retry("intercept", refused.replace("NORMAL", "IN_CALL"), Mode.IN_CALL, 31_000, 500))
        assertTrue(g.retry("intercept", refused, Mode.IN_CALL, 32_900, 2400))
        assertFalse(g.retry("intercept", refused, Mode.IN_CALL, 33_100, 2600))
    }

    @Test fun otherErrorsAndOtherMethodsMoveOnAtOnce() {
        val g = OpenGate()
        assertFalse("not supported at all: the older methods are what this phone has",
            g.retry("intercept", "UnsupportedOperationException: PSTN Call audio not accessible", Mode.NORMAL, 0, -1))
        assertFalse(g.retry("voice_downlink", refused, Mode.NORMAL, 0, -1))
        assertTrue(OpenGate.transient(refused))
    }

    // ------------------------------------------------------------------ path watch

    private fun sec(active: Boolean = true, down: Int = 50, nz: Int = 8000, waiting: Boolean = true, played: Long = 16000) =
        PathWatch.Second(active, down, nz, waiting, played)

    @Test fun aHealthyCallSaysNothing() {
        val w = PathWatch(testLine = false)
        assertTrue(List(30) { w.second(sec()) }.all { it == null })
    }

    @Test fun dialingIsNotWatched() {
        // While dialing the VoLTE downlink is silent and the modem takes no uplink: nothing to report.
        val w = PathWatch(testLine = false)
        assertTrue(List(30) { w.second(sec(active = false, nz = 0, played = 0)) }.all { it == null })
    }

    @Test fun aDownlinkOfZerosIsReportedOnce() {
        // Issue #13 ("no audio either way"): a capture that opens but reads only zeros.
        val w = PathWatch(testLine = false)
        val events = List(10) { w.second(sec(nz = 0)) }
        assertEquals(listOf(Event.DOWN_ZEROS), events.filterNotNull())
        assertEquals(5, events.indexOf(Event.DOWN_ZEROS))
        assertNull("not on the test line: its far end plays nothing", PathWatch(testLine = true).let { t -> List(10) { t.second(sec(nz = 0)) }.firstOrNull { it != null } })
        val alive = PathWatch(testLine = false)
        alive.second(sec()); assertTrue("had audio once: a quiet far end is no dead path", List(10) { alive.second(sec(nz = 0)) }.all { it == null })
    }

    @Test fun aCaptureThatDeliversNothing() {
        val w = PathWatch(testLine = true)
        assertEquals(listOf(Event.DOWN_NOTHING), List(5) { w.second(sec(down = 0, nz = 0)) }.filterNotNull())
    }

    @Test fun anInjectionNobodyTakes() {
        // Issue #14 (the far end does not hear the iPhone): the track's play position stands still while audio waits.
        val w = PathWatch(testLine = false)
        assertEquals(listOf(Event.UP_STUCK), List(6) { w.second(sec(played = 0)) }.filterNotNull())
        val unknown = PathWatch(testLine = false)
        assertTrue("no timestamp: nothing claimed", List(6) { unknown.second(sec(played = -1)) }.all { it == null })
        val idle = PathWatch(testLine = false)
        assertTrue("nothing to play (the iPhone sends nothing): not stuck", List(6) { idle.second(sec(waiting = false, played = 0)) }.all { it == null })
        assertTrue(PathWatch.text(Event.UP_STUCK, "intercept", "intercept", "18:Telephony").contains("the far end cannot hear the iPhone"))
    }

    // ------------------------------------------------------------------ app: restart at most once

    private fun info(capture: String, inject: String, vararg errors: String) =
        JSONObject().put("capture", capture).put("inject", inject).put("errors", JSONArray(errors.toList()))

    @Test fun workingAudioIsNeverRestarted() {
        assertFalse(AudioRestart.wanted(34, info("intercept", "intercept"), "auto", 0, 10_000, true))
        // Issue #15: a phone without the interception API runs on the older methods: restarting only cut working audio.
        assertFalse(AudioRestart.wanted(34, info("voice_downlink", "telephony_tx", "inject:intercept UnsupportedOperationException: PSTN Call audio not accessible"), "auto", 0, 10_000, true))
        assertFalse("Android 11-12 have no interception API", AudioRestart.wanted(31, info("voice_downlink", "telephony_tx"), "auto", 0, 10_000, true))
    }

    @Test fun oneRestartForARefusalInTheCallsOwnMode() {
        val fell = info("voice_downlink", "telephony_tx", "capture:intercept $refused", "inject:intercept $refused")
        assertFalse("not before 3 s", AudioRestart.wanted(34, fell, "auto", 0, 2000, true))
        assertFalse("only for an active call", AudioRestart.wanted(34, fell, "auto", 0, 5000, false))
        assertTrue(AudioRestart.wanted(34, fell, "auto", 0, 5000, true))
        assertFalse("once", AudioRestart.wanted(34, fell, "auto", 1, 60_000, true))
        assertFalse("the self-test chose an older method: never overridden", AudioRestart.wanted(34, fell, "telephony_tx", 0, 5000, true))
        assertFalse("no INFO of this call's start yet", AudioRestart.wanted(34, null, "auto", 0, 5000, true))
    }
}

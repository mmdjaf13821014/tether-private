package app.tether.audio

import app.tether.helper.MicPath.Mode
import app.tether.helper.MicPath.Redirect
import app.tether.helper.MicPath.Verdict
import app.tether.helper.RedirectGuard
import app.tether.helper.RedirectGuard.Action
import app.tether.helper.RedirectGuard.Second
import app.tether.helper.RedirectGuard.State
import app.tether.helper.UplinkCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A redirect audio mode during one call, against synthetic one-second traces (downlink, audio mode, uplink). */
class RedirectGuardTest {
    private val r = Redirect.CALL_REDIRECT
    private val inCall = Mode.IN_CALL
    private val on = r.audioMode
    private fun s(mode: Int = on, down: Boolean = true, uplink: UplinkCheck.Window? = null) =
        Second(downFrames = 50, downNonZero = if (down) 16000 else 0, audioMode = mode, uplink = uplink)
    private val silentDown = Second(50, 0, on)
    private val blocked = Second(0, 0, on)   // the downlink record stopped delivering
    private val talkHeard = UplinkCheck.Window(40, 2000.0, 1500.0)
    private val talkQuiet = UplinkCheck.Window(40, 2000.0, 0.0)

    private fun run(g: RedirectGuard, trace: List<Second>) = trace.map { g.second(it) }

    @Test fun waitsForTheDownlinkThenSetsTheMode() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        val acts = run(g, listOf(s(inCall, down = false), s(inCall, down = false), s(inCall)))
        assertEquals(listOf(Action.NONE, Action.NONE, Action.APPLY), acts)
        assertEquals(State.ON, g.state)
        assertEquals("on", g.state.wire)
    }

    @Test fun theTestLineSetsItAtOnce() {
        val g = RedirectGuard(Redirect.COMMUNICATION_REDIRECT, waitDownlink = false, speechCheck = false)
        assertEquals(Action.APPLY, g.second(Second(0, 0, Mode.IN_COMMUNICATION)))
        // No downlink check without a far end: a silent downlink changes nothing.
        assertTrue(run(g, List(10) { Second(50, 0, Mode.COMMUNICATION_REDIRECT) }).all { it == Action.NONE })
        assertEquals(State.ON, g.state)
    }

    @Test fun telecomTakesTheModeBackItIsSetAgainThenGivenUp() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false, maxReasserts = 2)
        g.second(s(inCall))
        val acts = run(g, listOf(s(inCall), s(), s(inCall), s(), s(inCall)))
        assertEquals(listOf(Action.REASSERT, Action.NONE, Action.REASSERT, Action.NONE, Action.REVERT), acts)
        assertEquals(State.REVERTED, g.state)
        assertNull("the mode worked in between (hold / unhold): not a refusal", g.verdict)
        assertEquals(2, g.reasserts)
    }

    @Test fun aModeThePhoneNeverEntersIsRefused() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false, maxReasserts = 2)
        g.second(s(inCall))
        assertEquals(listOf(Action.REASSERT, Action.REASSERT, Action.REVERT), run(g, List(3) { s(inCall) }))
        assertEquals(Verdict.REFUSED, g.verdict)
    }

    @Test fun anotherModeMeansTheCallEndedNothingIsRemembered() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        assertEquals(Action.REVERT, g.second(s(Mode.NORMAL)))
        assertEquals(State.STOPPED, g.state)
        assertNull(g.verdict)
    }

    @Test fun downlinkDiesInTheModeAndComesBackWithoutIt() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))   // APPLY
        val acts = run(g, listOf(s(), silentDown, blocked, silentDown))   // a second of settling, then 3 s silent
        assertEquals(listOf(Action.NONE, Action.NONE, Action.NONE, Action.REVERT), acts)
        assertEquals(State.REVERTED, g.state)
        g.second(s(inCall, down = false))
        g.second(s(inCall))
        assertEquals(Verdict.BREAKS_DOWNLINK, g.verdict)
    }

    @Test fun downlinkSilentWithAndWithoutTheModeIsNotBlamedOnIt() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        run(g, listOf(s(), silentDown, silentDown, silentDown))
        run(g, List(6) { s(inCall, down = false) })
        assertNull(g.verdict)
        assertEquals(State.REVERTED, g.state)
    }

    @Test fun aShortGapInTheDownlinkIsFine() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        assertTrue(run(g, listOf(s(), silentDown, silentDown, s(), silentDown, silentDown, s())).all { it == Action.NONE })
        assertEquals(State.ON, g.state)
    }

    @Test fun untestedModeTheUplinkHearsTheIphoneOk() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        run(g, listOf(s(uplink = talkQuiet), s(uplink = talkHeard), s(uplink = talkHeard), s(uplink = talkHeard)))
        assertEquals(State.ON, g.state)
        assertEquals(Verdict.OK, g.verdict)
    }

    @Test fun untestedModeSilencesTheUplinkUndoneAndRemembered() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        // UplinkCheck skips its first second, then 3 quiet seconds of speech: revert.
        val acts = run(g, listOf(s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet)))
        assertEquals(Action.REVERT, acts.last { it != Action.NONE })
        assertEquals(State.REVERTED, g.state)
        run(g, listOf(s(inCall, uplink = talkQuiet), s(inCall, uplink = talkHeard), s(inCall, uplink = talkHeard)))
        assertEquals(Verdict.BREAKS_UPLINK, g.verdict)
    }

    @Test fun blindUplinkIsUnverifiedNotBroken() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        run(g, List(5) { s(uplink = talkQuiet) })
        run(g, List(5) { s(inCall, uplink = talkQuiet) })
        assertEquals(Verdict.UNVERIFIED, g.verdict)
    }

    @Test fun theNextLayerWaitsUntilTheModeIsSettled() {
        // The mute must not come on while the uplink after-check compares "with the mode" against "without it".
        val up = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        up.second(s(inCall))
        assertTrue("waiting / on: not settled", !up.settled)
        run(up, List(5) { s(uplink = talkQuiet) })
        assertEquals(State.REVERTED, up.state)
        assertTrue("reverted for the uplink, its after-check still running", !up.settled)
        run(up, List(5) { s(inCall, uplink = talkQuiet) })
        assertTrue(up.settled)
        assertTrue("the capture showed the iPhone neither way: blind", up.blindUplink)
        // A downlink revert: the mute does not affect what its after-check looks at.
        val down = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        down.second(s(inCall))
        run(down, listOf(s(), silentDown, silentDown, silentDown))
        assertEquals(State.REVERTED, down.state)
        assertTrue(down.settled)
        assertTrue(!down.blindUplink)
        // The call ended (another mode): settled, nothing to take over.
        val ended = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        ended.second(s(inCall)); ended.second(s(Mode.NORMAL))
        assertTrue(ended.settled)
    }

    @Test fun theOwnersCallsOf2026_11_23() {
        // A54, real calls, default setting: call_redirect reverted for a silent VOICE_UPLINK, then "unverified" (no
        // longer used: MicPath test), next call call_redirect killed the downlink → breaks-downlink. Both settle, so
        // the vendor key takes the mic in the same call instead of leaving it open.
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        run(g, listOf(s(inCall, down = false), s(inCall), s(), silentDown, silentDown, silentDown, s(inCall)))
        assertEquals(Verdict.BREAKS_DOWNLINK, g.verdict)
        assertTrue(g.settled)
    }

    @Test fun noUplinkCaptureMeansTheUntestedModeIsNotUsed() {
        // Poco F3 / X3 Pro (custom ROMs, Android 16, 2026-11-25): the far end heard nothing of the iPhone. With no
        // VOICE_UPLINK capture nothing can tell whether the mode silences the uplink, so it is not set at all.
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.uplinkUnavailable("voice_uplink capture: refused")
        assertEquals(Verdict.UNVERIFIED, g.verdict)
        assertEquals(Action.NONE, g.second(s(inCall)))
        assertEquals(State.REVERTED, g.state)
        assertTrue("the next layer takes the mic in this call", g.settled)
        assertTrue("treated like a blind uplink by the next layer", g.blindUplink)
        assertTrue(g.note, g.note.contains("not used"))
        assertEquals("stays off for the rest of the call", listOf(Action.NONE, Action.NONE), run(g, listOf(s(inCall), s(inCall))))
    }

    @Test fun theUplinkCaptureStoppingUndoesAModeAlreadySet() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        assertEquals(Action.APPLY, g.second(s(inCall)))
        g.second(s(uplink = talkHeard.copy(speechFrames = 0)))
        g.uplinkUnavailable("voice_uplink capture stopped (-6)")
        assertEquals(Action.REVERT, g.second(s()))
        assertEquals(State.REVERTED, g.state)
        assertEquals(Verdict.UNVERIFIED, g.verdict)
        assertTrue(g.settled)
    }

    @Test fun aModeWithoutSpeechCheckIsNotAffected() {
        // A mode that passed a check before (speechCheck off) keeps working: there is nothing to capture for it.
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.uplinkUnavailable("voice_uplink capture: refused")
        assertNull(g.verdict)
        assertEquals(Action.APPLY, g.second(s(inCall)))
        assertEquals(State.ON, g.state)
    }
}

package app.tether.audio

import app.tether.helper.InjectMeter
import app.tether.helper.UplinkCheck
import app.tether.helper.UplinkCheck.Action
import app.tether.helper.UplinkCheck.State
import app.tether.helper.UplinkCheck.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-call uplink check against synthetic one-second energy traces. */
class UplinkCheckTest {
    private val talk = Window(speechFrames = 40, speechRms = 2000.0, uplinkRms = 0.0)
    private fun heard(level: Double = 1500.0) = talk.copy(uplinkRms = level)
    private val quietIphone = Window(speechFrames = 2, speechRms = 400.0, uplinkRms = 0.0)

    /** Feeds [trace], returns every action taken. */
    private fun run(c: UplinkCheck, trace: List<Window>) = trace.map { c.window(it) }

    @Test fun uplinkHeardWithTheMuteOn() {
        val c = UplinkCheck()
        val acts = run(c, listOf(talk, heard(), heard()))   // the first second is skipped
        assertEquals(State.OK, c.state)
        assertFalse(c.unmuted)
        assertTrue(acts.all { it == Action.NONE })
    }

    @Test fun silentUplinkWhileTalkingUndoesTheMuteThenFixed() {
        val c = UplinkCheck()
        val acts = run(c, listOf(talk, talk, talk, talk))
        assertEquals(listOf(Action.NONE, Action.NONE, Action.NONE, Action.UNMUTE), acts)
        assertTrue(c.unmuted)
        assertEquals(State.CHECKING, c.state)
        run(c, listOf(talk, heard(), heard()))   // a second of grace, then heard twice
        assertEquals(State.FIXED, c.state)
        assertEquals("muted-locally-fixed", c.state.wire)
    }

    @Test fun silentWithoutTheMuteTooIsUnverified() {
        val c = UplinkCheck()
        run(c, List(4) { talk })
        assertTrue(c.unmuted)
        run(c, List(4) { talk })
        assertEquals(State.UNVERIFIED, c.state)
        assertTrue("the mute stays undone", c.unmuted)
    }

    @Test fun aQuietIphoneDecidesNothing() {
        val c = UplinkCheck(maxWindows = 30)
        val acts = run(c, List(29) { quietIphone })
        assertTrue(acts.all { it == Action.NONE })
        assertEquals(State.CHECKING, c.state)
        c.window(quietIphone); c.window(quietIphone)
        assertEquals(State.UNVERIFIED, c.state)
        assertFalse(c.unmuted)
    }

    @Test fun pausesBetweenWordsDoNotCount() {
        val c = UplinkCheck()
        run(c, listOf(talk, talk, quietIphone, quietIphone, talk, quietIphone))
        assertFalse("only 2 quiet speaking seconds", c.unmuted)
        assertEquals(Action.UNMUTE, c.window(talk))
    }

    @Test fun noiseFarBelowTheSpeechIsNotHearing() {
        // Bag noise or comfort noise at 3 % of the injected level: the uplink does not carry the speech.
        val c = UplinkCheck()
        val acts = run(c, List(4) { heard(60.0) })
        assertEquals(Action.UNMUTE, acts.last())
    }

    @Test fun captureFailureIsUnverifiedAndHarmless() {
        val c = UplinkCheck()
        c.fail("voice_uplink capture: SecurityException")
        assertEquals(State.UNVERIFIED, c.state)
        assertFalse(c.unmuted)
        assertEquals(Action.NONE, c.window(talk))
    }

    @Test fun doneChecksIgnoreLaterWindows() {
        val c = UplinkCheck()
        run(c, listOf(talk, heard(), heard()))
        assertEquals(Action.NONE, c.window(talk)); assertEquals(Action.NONE, c.window(talk)); assertEquals(Action.NONE, c.window(talk))
        assertEquals(State.OK, c.state)
    }

    @Test fun meterCountsSpeechFramesOnly() {
        val m = InjectMeter()
        m.add(0.0); m.add(100.0); m.add(1000.0); m.add(3000.0)
        assertEquals(2 to 2000.0, m.take())
        assertEquals(0 to 0.0, m.take())
    }
}

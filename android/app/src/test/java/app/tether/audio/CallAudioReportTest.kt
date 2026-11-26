package app.tether.audio

import app.tether.helper.LocalMutes.Memory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link-log lines a reporter of "the other side cannot hear me" sends. */
class CallAudioReportTest {
    private fun uplink(state: String, micMuted: Boolean, telecom: Boolean = false, unmuted: Boolean = false, note: String = "") =
        JSONObject().put("state", state).put("micMuted", micMuted).put("telecomMicMute", telecom).put("unmuted", unmuted).put("note", note)

    @Test fun openInjectionWithMethodAndMicState() {
        val info = JSONObject().put("capture", "intercept").put("inject", "intercept").put("routed", "18:Telephony").put("earpiece", "samsung rx=1 tx=0")
            .put("errors", JSONArray()).put("uplink", uplink("off", false))
        val line = CallAudioReport.pathLine(info, setting = false, memory = null)
        assertTrue(line, line.contains("uplink injection open via intercept (routed 18:Telephony)"))
        assertTrue(line, line.contains("phone mic not muted (setting off)"))
        assertTrue(line, line.contains("earpiece samsung rx=1 tx=0"))
    }

    @Test fun injectionThatDidNotOpenIsSaidLoudly() {
        val info = JSONObject().put("capture", "intercept").put("inject", JSONObject.NULL)
            .put("errors", JSONArray().put("inject:intercept IllegalStateException: not available in mode MODE_NORMAL").put("local mute x"))
            .put("uplink", uplink("unverified", false))
        val line = CallAudioReport.pathLine(info, setting = true, memory = Memory.BREAKS_UPLINK)
        assertTrue(line, line.contains("uplink injection NOT open"))
        assertTrue(line, line.contains("not available in mode"))
        assertTrue(line, line.contains("the mute silenced the uplink"))
        assertTrue(line, line.contains("errors: local mute x"))
    }

    @Test fun aSkippedInterceptionRouteIsSaid() {
        val info = JSONObject().put("capture", "intercept").put("inject", "telephony_tx").put("routed", "18:Redmi Note 8")
            .put("routedToCall", true).put("interceptInjectDead", true).put("errors", JSONArray()).put("uplink", uplink("off", false))
        val line = CallAudioReport.pathLine(info, setting = false, memory = null)
        assertTrue(line, line.contains("uplink injection open via telephony_tx"))
        assertTrue(line, line.contains("no afe-proxy-playback path"))
    }

    @Test fun aMuteSettingThePhoneCannotHonourIsSaid() {
        val info = JSONObject().put("capture", "intercept").put("inject", "intercept").put("errors", JSONArray()).put("uplink", uplink("off", false))
        val line = CallAudioReport.pathLine(info, setting = true, memory = Memory.OK, canMute = false)
        assertTrue(line, line.contains("Android's mic mute also silences the injected voice"))
    }

    @Test fun anInjectionNotRoutedToTheCallIsSaid() {
        // Issue #15: telephony_tx granted on the phone's speaker instead of Telephony Tx.
        val info = JSONObject().put("capture", "intercept").put("inject", "intercept").put("routed", "2:Speaker").put("routedToCall", false)
            .put("errors", JSONArray()).put("uplink", uplink("off", false)).put("openWaitMs", 6200)
        val line = CallAudioReport.pathLine(info, setting = false, memory = null)
        assertTrue(line, line.contains("(routed 2:Speaker: NOT the call's uplink"))
        assertTrue(line, line.contains("opened 6.2 s after the start"))
    }

    @Test fun theAutomaticVendorMute() {
        val mic = JSONObject().put("setting", "auto").put("path", "muted").put("layer", "vendor-mute")
            .put("note", "redirect modes not used on this phone: call_redirect breaks-downlink; the vendor's mic key mutes the mic at its source")
        val kept = CallAudioReport.micLine(uplink("unverified", true).put("kept", true), setting = false, memory = null, mic = mic)
        assertTrue(kept, kept.startsWith("phone mic MUTED via the vendor key (automatic: redirect modes not used"))
        assertTrue(kept, kept.contains("kept although the uplink cannot be checked"))
        val blind = CallAudioReport.micLine(uplink("unverified", true), setting = false, memory = Memory.UNVERIFIED, mic = mic)
        assertTrue(blind, blind.endsWith("the uplink cannot be checked on this phone (the vendor key is known to keep the injected voice)"))
    }

    @Test fun mutedViaTelecomWhileChecking() {
        assertEquals("phone mic MUTED via Android (Telecom), uplink check running",
            CallAudioReport.micLine(uplink("checking", true, telecom = true), setting = true, memory = null))
    }

    @Test fun uplinkEventsOnlyWhenSomethingChanged() {
        assertNull("no mute, nothing to check: said once by the path line", CallAudioReport.uplinkEvent(uplink("off", false), null))
        val checking = uplink("checking", true)
        assertNull(CallAudioReport.uplinkEvent(checking, null))
        val undone = uplink("checking", false, unmuted = true, note = "mute undone")
        assertEquals("call audio: mute undone", CallAudioReport.uplinkEvent(undone, checking))
        assertNull(CallAudioReport.uplinkEvent(undone, undone))
        assertEquals("call audio: uplink check muted-locally-fixed: fixed", CallAudioReport.uplinkEvent(uplink("muted-locally-fixed", false, unmuted = true, note = "fixed"), undone))
    }

    @Test fun anIphoneThatSentOnlySilenceIsFlagged() {
        assertTrue(CallAudioReport.silentIphone(JSONObject().put("uplinkFrames", 900).put("uplinkSpeechFrames", 0))!!.contains("microphone"))
        assertNull(CallAudioReport.silentIphone(JSONObject().put("uplinkFrames", 900).put("uplinkSpeechFrames", 12)))
        assertNull("a very short call", CallAudioReport.silentIphone(JSONObject().put("uplinkFrames", 100).put("uplinkSpeechFrames", 0)))
        assertNull("an older helper", CallAudioReport.silentIphone(JSONObject().put("uplinkFrames", 900)))
    }

    private fun mic(state: String, verdict: String? = null, note: String = "") = JSONObject().put("setting", "auto").put("path", if (state == "on") "off" else "open")
        .put("mode", "call_redirect").put("state", state).put("note", note).apply { verdict?.let { put("verdict", it) } }

    @Test fun redirectModeInThePathLine() {
        val base = JSONObject().put("capture", "intercept").put("inject", "intercept").put("routed", "18:Telephony").put("errors", JSONArray())
            .put("uplink", uplink("checking", false))
        val waiting = CallAudioReport.pathLine(JSONObject(base.toString()).put("mic", mic("waiting", note = "waiting for audio in the downlink")), setting = false, memory = null)
        assertTrue(waiting, waiting.contains("phone mic open until call_redirect is set (waiting for audio in the downlink)"))
        val noMode = JSONObject(base.toString()).put("mic", JSONObject().put("setting", "auto").put("path", "open").put("note", "no redirect mode before Android 13"))
        assertTrue(CallAudioReport.pathLine(noMode, false, null).contains("phone mic open (no redirect mode before Android 13)"))
        val open = JSONObject(base.toString()).put("mic", JSONObject().put("setting", "open").put("path", "open"))
        assertTrue(CallAudioReport.pathLine(open, false, null).contains("phone mic open (setting open)"))
    }

    @Test fun redirectEventsOnlyWhenSomethingChanged() {
        assertNull("the path line said so already", CallAudioReport.micEvent(mic("waiting"), null))
        assertEquals("call audio: call_redirect on: mode set", CallAudioReport.micEvent(mic("on", note = "mode set"), mic("waiting")))
        assertNull(CallAudioReport.micEvent(mic("on", note = "again"), mic("on")))
        assertEquals("call audio: call_redirect reverted (breaks-downlink): x",
            CallAudioReport.micEvent(mic("reverted", "breaks-downlink", "x"), mic("reverted", note = "y")))
        assertNull("no redirect mode this call", CallAudioReport.micEvent(JSONObject().put("setting", "open").put("path", "open"), null))
    }
}

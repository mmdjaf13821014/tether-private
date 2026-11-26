package app.tether.audio

import app.tether.core.ReportScrubber
import app.tether.report.PhoneReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The automatic call-audio snapshots kept for problem reports, and how a snapshot reads in a report. */
class DiagStoreTest {
    private fun snap(trigger: String, silenced: Boolean, testLine: Boolean = false) = JSONObject().put("trigger", trigger).put("testLine", testLine)
        .put("autodiag", JSONObject().put("audioMode", "MODE_IN_CALL").put("interceptable", true).put("silence", "qualcomm")
            .put("recordings", JSONArray().put(JSONObject().put("uid", 2000).put("package", "com.android.shell").put("self", true)
                .put("source", "VOICE_DOWNLINK").put("device", "telephony").put("rate", 16000).put("session", 33041).put("isClientSilenced", silenced)))
            .put("playbacks", JSONArray())
            .put("inputs", JSONArray().put(JSONObject().put("id", 14).put("type", "builtin_mic").put("name", "Built-In Mic")))
            .put("outputs", JSONArray())
            .put("helper", JSONObject().put("capture", "intercept").put("downReads", 500).put("downNonZeroSamples", 0))
            .put("props", JSONObject().put("ro.board.platform", "taro"))
            .put("dumpsysAudio", JSONArray().put("11-25 15:14:58:540 rec update riid:29519 uid:2000 src:VOICE_DOWNLINK silenced pack:com.android.shell"))
            .put("helperLog", JSONArray().put("11-25 16:20:13.123 path: the downlink capture (intercept) reads only zeros")))

    @Test fun keepsTheLastThreeAcrossRestarts() {
        val f = File.createTempFile("diag", ".json").apply { delete(); deleteOnExit() }
        val store = DiagStore(f)
        store.add(snap("call-end", false), now = 1)
        store.add(snap("down-zeros", true), JSONObject().put("bridged", true), now = 2)
        store.add(snap("call-end", true, testLine = true), now = 3)
        store.add(snap("up-stuck", false), now = 4)
        val again = DiagStore(f).all()
        assertEquals(listOf("down-zeros", "call-end", "up-stuck"), again.map { it.getString("trigger") })
        assertTrue(again[1].getBoolean("testLine"))
        assertTrue(again[0].getJSONObject("context").getBoolean("bridged"))
        // An INFO without a snapshot is not kept.
        store.add(JSONObject().put("trigger", "x"))
        assertEquals(3, store.all().size)
    }

    @Test fun reportLinesShowSilencingPerRecording() {
        val lines = DiagStore.lines(snap("down-zeros", true).getJSONObject("autodiag"))
        val text = lines.joinToString("\n")
        assertTrue(text, "uid 2000 com.android.shell (the helper) · VOICE_DOWNLINK · device telephony · 16000 Hz · session 33041 · isClientSilenced=true" in text)
        assertTrue(text, "audio mode: MODE_IN_CALL · interception API: true · silence: qualcomm" in text)
        assertTrue(text, "downNonZeroSamples=0" in text)
        assertTrue(text, "  ro.board.platform: taro" in text)
        assertTrue(text, "input devices: builtin_mic#14 (Built-In Mic)" in text)
        assertTrue(text, "helper log (newest 1 lines):" in text)
        // Nothing in it trips the scrubber (the figures stay readable).
        assertEquals(text, ReportScrubber().scrub(text))
    }

    @Test fun headingSaysWhatAndWhen() {
        val f = File.createTempFile("diag", ".json").apply { delete(); deleteOnExit() }
        val s = DiagStore(f).apply { add(snap("down-zeros", true, testLine = true), JSONObject().put("bridged", true)) }.all().single()
        val h = DiagStore.heading(s)
        assertTrue(h, h.startsWith("-- snapshot ") && "the downlink capture read only zeros in the active call (test line) · bridged=true" in h)
        assertFalse(DiagStore.triggerText("call-end").isEmpty())
    }

    @Test fun oneUiVersion() {
        assertEquals("8.0", PhoneReport.oneUiVersion("80000"))
        assertEquals("6.1", PhoneReport.oneUiVersion("60100"))
        assertEquals("5.1.1", PhoneReport.oneUiVersion("50101"))
        assertEquals(null, PhoneReport.oneUiVersion(""))
        assertEquals(null, PhoneReport.oneUiVersion(null))
    }
}

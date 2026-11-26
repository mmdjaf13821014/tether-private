package app.tether.client

import app.tether.client.calls.UplinkGate
import app.tether.client.report.ClientReport
import app.tether.core.ReportScrubber
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tether Client's problem report: the Android phone's status goes in without numbers or names. */
class ProblemReportTest {
    private val status = JSONObject().put("device", "Someone's Galaxy").put("battery", 80).put("dialer", true)
        .put("sims", JSONArray().put(JSONObject().put("slot", 0).put("sub", 3).put("name", "Home SIM").put("carrier", "Carrier X")
            .put("number", "+989123456789").put("signal", 3).put("net", "LTE")))
        .put("clients", JSONArray().put(JSONObject().put("name", "Someone's iPhone").put("kind", "ios").put("self", false)))
        .put("helper", JSONObject().put("state", "running").put("audio", "full"))

    @Test fun statusLosesNumbersAndNames() {
        val s = ClientReport.sanitizedStatus(status)
        val text = s.toString()
        for (bad in listOf("Someone", "Home SIM", "Carrier X", "+98")) assertFalse(bad, bad in text)
        assertEquals("LTE", s.getJSONArray("sims").getJSONObject(0).getString("net"))
        assertEquals("ios", s.getJSONArray("clients").getJSONObject(0).getString("kind"))
        assertEquals("full", s.getJSONObject("helper").getString("audio"))
        // The original is not changed.
        assertTrue(status.has("device"))
    }

    @Test fun statusLineScrubsAsAWhole() {
        val line = ReportScrubber(names = listOf("Someone's iPhone")).scrub("status: " + ClientReport.sanitizedStatus(status))
        assertFalse(line, "Someone" in line)
        assertTrue(line, "\"battery\":80" in line)
    }

    @Test fun uplinkGateStateForTheReport() {
        val g = UplinkGate {}
        assertFalse(g.isWanted || g.isOpen)
        g.set(true); g.admit()
        assertTrue(g.isWanted && g.isOpen)
    }

    @Test fun oneUiVersion() {
        assertEquals("8.0", ClientReport.oneUiVersion("80000"))
        assertEquals(null, ClientReport.oneUiVersion(" "))
    }
}

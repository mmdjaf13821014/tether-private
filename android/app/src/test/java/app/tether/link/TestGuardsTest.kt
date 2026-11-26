package app.tether.link

import app.tether.core.T
import app.tether.telecom.TestConnectionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guards that keep on-device tests away from the owner's real calls, rows and radios. */
class TestGuardsTest {
    @Test fun debugHooksAcceptOnlyTheTestRange() {
        for (n in listOf("+10000000001", "+10000000009", "10000000005", "+1 000 000 0003")) assertTrue(n, TestGuards.isTestNumber(n))
        for (n in listOf("+10000000000", "+100000000010", "+1000000000", "+15550100", "+989121234567", "09121234567",
            "", "+1000000000x", "+10000000001;", "tel:+10000000001", "+20000000001")) assertFalse(n, TestGuards.isTestNumber(n))
    }

    @Test fun testLineDialsOnlyExactTestNumbers() {
        assertTrue(TestConnectionService.dialable("+10000000009"))
        for (n in listOf("10000000009", "+10000000000", "+1 0000000009", "+15550100", "+100000000091")) assertFalse(n, TestConnectionService.dialable(n))
    }

    @Test fun fakeRingNeverReachesALinkedIphoneUnlessAskedFor() {
        assertFalse(TestGuards.fakeCallAllowed(listOf("iphone"), null))
        assertFalse(TestGuards.fakeCallAllowed(listOf("android", "iphone"), "yes"))
        assertTrue(TestGuards.fakeCallAllowed(listOf("iphone"), "iphone"))
        assertTrue(TestGuards.fakeCallAllowed(emptyList(), null))
        assertTrue(TestGuards.fakeCallAllowed(listOf("android"), null))
    }

    @Test fun testClientNeverControlsARealCall() {
        for (t in TestGuards.callControls) {
            // A real call alone, or beside a test call: no control at all for a call that is not on the test line.
            assertFalse(t, TestGuards.callControlAllowed(true, t, listOf(false), cidIsTest = false))
            assertFalse(t, TestGuards.callControlAllowed(true, t, listOf(true, false), cidIsTest = false))
            // Real clients are never restricted.
            assertTrue(t, TestGuards.callControlAllowed(false, t, listOf(false), cidIsTest = false))
        }
    }

    @Test fun takingTheAudioNeedsEveryCallOnTheTestLine() {
        // A real call next to the test call: answer / take / selftest would take the real call's audio.
        for (t in listOf(T.CALL_ANSWER, T.CALL_TAKE, T.SELFTEST)) {
            assertFalse(t, TestGuards.callControlAllowed(true, t, listOf(true, false), cidIsTest = true))
            assertFalse(t, TestGuards.callControlAllowed(true, t, emptyList(), cidIsTest = true))
            assertTrue(t, TestGuards.callControlAllowed(true, t, listOf(true), cidIsTest = true))
        }
        // Ending its own test call stays possible beside a real call.
        for (t in listOf(T.CALL_END, T.CALL_REJECT, T.CALL_HOLD, T.CALL_DTMF))
            assertTrue(t, TestGuards.callControlAllowed(true, t, listOf(true, false), cidIsTest = true))
    }

    @Test fun otherMessagesAreNotCallControls() {
        assertTrue(TestGuards.callControlAllowed(true, T.PING, listOf(false), cidIsTest = false))
    }

    @Test fun testTransportIsAlwaysDryAndDebugDryRunOnlyForAdmittedClients() {
        assertTrue(TestGuards.isDry(transportDry = true, dryRun = false, admitted = false))
        assertTrue(TestGuards.isDry(transportDry = true, dryRun = false, admitted = true))
        assertTrue(TestGuards.isDry(transportDry = false, dryRun = true, admitted = true))
        assertFalse(TestGuards.isDry(transportDry = false, dryRun = true, admitted = false))   // a real iPhone linked meanwhile
        assertFalse(TestGuards.isDry(transportDry = false, dryRun = false, admitted = true))
    }

    @Test fun onlyNeverHidesARealCallOrText() {
        // 2026-11-25: a test left `only none` on; a call dialed from the iPhone was placed and answered, and the iPhone
        // was never told (the far end heard silence). Real events reach every client whatever `only` says.
        assertTrue(TestGuards.receivesBroadcasts(true, true, "none", "iphone", false, real = true))
        assertFalse("test traffic is still kept away", TestGuards.receivesBroadcasts(true, true, "none", "iphone", false, real = false))
        assertFalse("a client that is not up gets nothing", TestGuards.receivesBroadcasts(false, true, null, "iphone", false, real = true))
        assertFalse("a test holding the link alone still excludes others", TestGuards.receivesBroadcasts(true, false, null, "iphone", false, real = true))
    }

    @Test fun onlySwitchesItselfOff() {
        assertTrue(TestGuards.onlyActive(setAt = 1_000, now = 1_000 + TestGuards.ONLY_MS - 1))
        assertFalse(TestGuards.onlyActive(setAt = 1_000, now = 1_000 + TestGuards.ONLY_MS))
    }

    @Test fun onlyFiltersRealClientsButNeverOnDeviceTestClients() {
        assertTrue(TestGuards.receivesBroadcasts(true, true, null, "iphone", false))
        assertFalse(TestGuards.receivesBroadcasts(true, true, "none", "iphone", false))
        assertTrue(TestGuards.receivesBroadcasts(true, true, "none", "iphone", true))
        assertTrue(TestGuards.receivesBroadcasts(true, true, "iphone", "iphone", false))
        assertFalse(TestGuards.receivesBroadcasts(false, true, null, "iphone", true))
        assertFalse(TestGuards.receivesBroadcasts(true, false, null, "iphone", true))   // a test holds the link exclusively
    }

    @Test fun testClientNeverFlipsRadios() {
        for (w in listOf("data", "hotspot", "bt", "dataSub", "share", "airplane", "rat")) {
            assertEquals(w, TestGuards.netSwitch(T.NET_SET, false, w))
            assertNull(w, TestGuards.netSwitch(T.NET_SET, true, w))
            assertNull(w, TestGuards.netSwitch(T.NET_GET, false, w))
        }
        assertNull(TestGuards.netSwitch(T.NET_SET, false, "wifi"))
        assertNull(TestGuards.netSwitch(T.NET_SET, false, null))
    }

    @Test fun onlyAnIphoneOnARealLinkIsRememberedAndHeld() {
        assertTrue(TestGuards.holdsLe("l2cap", "iphone", true))
        assertTrue(TestGuards.holdsLe("gatt", "iphone", true))
        assertFalse(TestGuards.holdsLe("l2cap", "android", true))   // an Android client never replaces the iPhone's address
        assertFalse(TestGuards.holdsLe("memory", "iphone", true))
        assertFalse(TestGuards.holdsLe("l2cap", "iphone", false))
    }
}

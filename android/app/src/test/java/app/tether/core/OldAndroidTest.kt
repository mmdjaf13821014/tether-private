package app.tether.core

import android.telecom.Call
import app.tether.adb.ConnectOrder
import app.tether.audio.HelperBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone app on Android 8.0 to 9 (API 26-28): GATT-only link, call direction from the first state, no call audio. */
class OldAndroidTest {
    @Test fun psmIsLittleEndianAndHonoursTheReadOffset() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), OldAndroid.psmValue(0x81, 0))
        assertArrayEquals(byteArrayOf(0x34, 0x12), OldAndroid.psmValue(0x1234, 0))
        assertArrayEquals(byteArrayOf(0x12), OldAndroid.psmValue(0x1234, 1))
        assertArrayEquals(ByteArray(0), OldAndroid.psmValue(0x1234, 2))
        assertArrayEquals(ByteArray(0), OldAndroid.psmValue(0x1234, 9))
    }

    @Test fun noL2capListenerAnswersAnEmptyPsmSoClientsUseGattAtOnce() {
        // The iPhone and the Android client both treat a value shorter than 2 bytes as "no PSM" and start the GATT pipe.
        assertEquals(0, OldAndroid.psmValue(0, 0).size)
        assertEquals(0, OldAndroid.psmValue(0, 1).size)
    }

    @Test fun outgoingCallsAreSeenFirstWhileConnectingOrDialing() {
        assertTrue(OldAndroid.outgoingFromFirstState(Call.STATE_CONNECTING))
        assertTrue(OldAndroid.outgoingFromFirstState(Call.STATE_DIALING))
        assertTrue(OldAndroid.outgoingFromFirstState(Call.STATE_SELECT_PHONE_ACCOUNT))
        assertTrue(OldAndroid.outgoingFromFirstState(Call.STATE_PULLING_CALL))
    }

    @Test fun incomingAndUnknownCallsCountAsIncoming() {
        assertFalse(OldAndroid.outgoingFromFirstState(Call.STATE_RINGING))
        assertFalse(OldAndroid.outgoingFromFirstState(Call.STATE_NEW))
        // First seen mid-call (the app restarted): the direction is unknown, and "in" never triggers outgoing-only paths.
        assertFalse(OldAndroid.outgoingFromFirstState(Call.STATE_ACTIVE))
        assertFalse(OldAndroid.outgoingFromFirstState(Call.STATE_HOLDING))
    }

    @Test fun androidEightAndNineReportNoCallAudioAndNoWirelessDebugging() {
        for (sdk in 26..29) {
            assertEquals("unavailable", HelperBridge.audioLevelFor(sdk))
            assertFalse(ConnectOrder.tlsSupported(sdk))
        }
        assertEquals("fallback", HelperBridge.audioLevelFor(30))
        assertEquals("full", HelperBridge.audioLevelFor(33))
    }
}

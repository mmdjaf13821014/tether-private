package app.tether.core

import android.telecom.Call

/**
 * What the phone app does without the Android 10 APIs (Android 8.0 to 9, API 26-28), as pure functions so the JVM tests
 * cover them. The version checks themselves stay at the call sites, where lint can see them.
 */
object OldAndroid {
    /**
     * The PSM characteristic's value at [offset]: the PSM as uint16 little-endian. Without an L2CAP listener ([psm] 0:
     * Android 8 and 9 have none) the value is empty, and the central goes straight to the GATT transport instead of
     * opening a channel that cannot exist and waiting for its timeout (iPhone and Android client both do so).
     */
    fun psmValue(psm: Int, offset: Int): ByteArray {
        if (psm <= 0) return ByteArray(0)
        val v = byteArrayOf((psm and 0xff).toByte(), (psm shr 8).toByte())
        return v.copyOfRange(minOf(maxOf(offset, 0), 2), 2)
    }

    /**
     * A call's direction before `Call.Details.getCallDirection` (Android 10), from the state the in-call service first
     * saw it in: an outgoing call starts connecting, dialing or waiting for a SIM choice; an incoming one is new or
     * ringing. A call first seen later (the app restarted mid-call) counts as incoming.
     */
    fun outgoingFromFirstState(state: Int): Boolean = state == Call.STATE_CONNECTING || state == Call.STATE_DIALING ||
        state == Call.STATE_SELECT_PHONE_ACCOUNT || state == Call.STATE_PULLING_CALL
}

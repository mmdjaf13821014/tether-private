package app.tether.client

import app.tether.client.calls.nextAudioRoute
import app.tether.client.link.ClientLinkPolicy
import app.tether.client.link.ClientLinkPolicy.Reject
import app.tether.client.model.telNumber
import android.telecom.CallAudioState.ROUTE_BLUETOOTH
import android.telecom.CallAudioState.ROUTE_EARPIECE
import android.telecom.CallAudioState.ROUTE_SPEAKER
import android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure link, route and tel: rules the platform code relies on (BleCentral, CallActivity, DialActivity). */
class LinkRulesTest {
    @Test fun eachRejectReason() {
        val p = ClientLinkPolicy { 0 }
        assertEquals(Reject.PairingExpired, p.rejected(3, pairing = true))
        assertEquals("3 outside pairing is an ordinary failure", Reject.Fail, p.rejected(3, pairing = false))
        assertEquals(Reject.Wait(60_000, "unknown_device"), p.rejected(2, pairing = false))
        assertEquals(Reject.Wait(60_000, "unknown_device"), p.rejected(2, pairing = true))
        assertEquals(Reject.Wait(5_000, null), p.rejected(4, pairing = false))
        assertEquals(Reject.Fail, p.rejected(1, pairing = false))
        assertEquals(Reject.Fail, p.rejected(0, pairing = false))
    }

    @Test fun timingsMatchTheIphone() {
        assertEquals(12_000L, ClientLinkPolicy.SETUP_WATCHDOG_MS)
        assertEquals(10_000L, ClientLinkPolicy.STALL_MS)
        assertEquals(10_000L, ClientLinkPolicy.CONNECT_WATCHDOG_MS)
    }

    @Test fun restartsOnAnOpenConnectionResetWhenASessionComesUpOrTheConnectionGoes() {
        val p = ClientLinkPolicy { 0 }
        p.failed("x", true, false); p.failed("x", true, false)
        p.up()
        assertEquals(ClientLinkPolicy.Next.RestartOnConnection, p.failed("x", true, false))
        p.disconnected()
        assertEquals(0, p.restartsOnOpenConnection)
    }

    @Test fun foregroundForgetsTheBackOff() {
        val p = ClientLinkPolicy { 0 }
        repeat(6) { p.disconnected() }
        p.forget()
        assertEquals(ClientLinkPolicy.Next.Reconnect(0), p.disconnected())
    }

    @Test fun audioRouteCycle() {
        val all = ROUTE_EARPIECE or ROUTE_SPEAKER or ROUTE_BLUETOOTH or ROUTE_WIRED_HEADSET
        assertEquals(ROUTE_SPEAKER, nextAudioRoute(all, ROUTE_EARPIECE))
        assertEquals(ROUTE_BLUETOOTH, nextAudioRoute(all, ROUTE_SPEAKER))
        assertEquals(ROUTE_EARPIECE, nextAudioRoute(all, ROUTE_WIRED_HEADSET))
        assertEquals("missing routes are skipped", ROUTE_EARPIECE, nextAudioRoute(ROUTE_EARPIECE or ROUTE_SPEAKER, ROUTE_SPEAKER))
        assertEquals("no state yet: earpiece → speaker", ROUTE_SPEAKER, nextAudioRoute(null, null))
        assertEquals("a route not offered goes to the first one", ROUTE_SPEAKER, nextAudioRoute(ROUTE_SPEAKER, ROUTE_BLUETOOTH))
        assertNull(nextAudioRoute(0, ROUTE_EARPIECE))
    }

    @Test fun telLinks() {
        assertEquals("+10000000001", telNumber("+1 000-000-0001"))
        assertEquals("+10000000001", telNumber("%2B1%20000%200000001"))
        assertEquals("*21#", telNumber("*21%23"))
        assertEquals("+98900", telNumber("+۹۸۹۰۰"))
        assertEquals("a bad escape is left alone", "1001", telNumber("%zz1001"))
        assertEquals("not a hex escape", "+1", telNumber("%+1"))
        assertEquals("", telNumber(null))
        assertEquals("", telNumber("call me"))
    }
}

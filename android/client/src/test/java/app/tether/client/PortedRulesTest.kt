package app.tether.client

import app.tether.client.calls.HangUpGuard
import app.tether.client.link.ClientLinkPolicy
import app.tether.client.model.Call
import app.tether.client.model.Search
import app.tether.client.model.dialable
import app.tether.core.Sms
import app.tether.core.T
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's hard-won rules, ported: the same cases as TetherCore's CallGuardTests (PlayoutTests: PlayoutTest), and more. */
class PortedRulesTest {
    // ------------------------------------------------------------------ HangUpGuard (TetherCore CallGuardTests)

    @Test fun guardNeverBlocksANewCall() {
        val g = HangUpGuard(); g.hungUp("aaaa1111", 0)
        assertEquals(HangUpGuard.Verdict.PASS, g.check("bbbb2222", false, 100))
        assertEquals(HangUpGuard.Verdict.PASS, g.check("bbbb2222", true, 100))
    }

    @Test fun guardDropsLateStatesAndAsksAgain() {
        val g = HangUpGuard(); g.hungUp("c1", 0)
        assertEquals(HangUpGuard.Verdict.DROP, g.check("c1", false, 500))
        assertEquals(HangUpGuard.Verdict.DROP_AND_RESEND, g.check("c1", false, 2500))
        assertEquals(HangUpGuard.Verdict.DROP, g.check("c1", false, 3000))
        assertEquals(HangUpGuard.Verdict.PASS, g.check("c1", true, 4000))
        assertFalse("c1" in g)
        assertEquals(HangUpGuard.Verdict.PASS, g.check("c1", false, 5000))
        g.hungUp("c2", 0); g.reset()
        assertEquals(HangUpGuard.Verdict.PASS, g.check("c2", false, 1))
    }

    // ------------------------------------------------------------------ link policy (TetherLink's reconnect rules)

    @Test fun reconnectAtOnceThenBackOff() {
        var t = 0L
        val p = ClientLinkPolicy { t }
        repeat(3) { assertEquals(ClientLinkPolicy.Next.Reconnect(0), p.disconnected()); t += 1000 }
        assertEquals(ClientLinkPolicy.Next.Reconnect(8000), p.disconnected())
        assertEquals(ClientLinkPolicy.Next.Reconnect(10_000), p.disconnected())
        t += 61_000
        assertEquals("failures older than a minute are forgotten", ClientLinkPolicy.Next.Reconnect(0), p.disconnected())
        repeat(20) { p.disconnected() }
        assertEquals("never longer than 30 s", ClientLinkPolicy.Next.Reconnect(30_000), p.disconnected())
        p.up()
        assertEquals(ClientLinkPolicy.Next.Reconnect(0), p.disconnected())
    }

    @Test fun sessionRestartsOnAnOpenConnectionTwiceAtMost() {
        val p = ClientLinkPolicy { 0 }
        assertEquals(ClientLinkPolicy.Next.RestartOnConnection, p.failed("L2CAP stream ended", connectionUp = true, pairing = false))
        assertEquals(ClientLinkPolicy.Next.RestartOnConnection, p.failed("protocol error", connectionUp = true, pairing = false))
        assertTrue(p.failed("again", connectionUp = true, pairing = false) is ClientLinkPolicy.Next.Reconnect)
        assertTrue("a setup timeout starts from a fresh connection", p.failed(ClientLinkPolicy.SETUP_TIMEOUT, true, false) is ClientLinkPolicy.Next.Reconnect)
        assertTrue("so does a pairing attempt", p.failed("x", true, pairing = true) is ClientLinkPolicy.Next.Reconnect)
        assertTrue(p.failed("x", connectionUp = false, pairing = false) is ClientLinkPolicy.Next.Reconnect)
    }

    // ------------------------------------------------------------------ synced state

    @Test fun readStateBothWaysAndDeletes() {
        val phone = FakePhone()
        val now = TestEffects().clock
        phone.sms += listOf(Sms(1, 7, "+10000000001", "a", now - 3, 1, false, null), Sms(2, 7, "+10000000001", "b", now - 2, 1, false, null),
            Sms(3, 9, "+10000000002", "c", now - 1, 1, false, null))
        phone.unread += listOf(1, 2, 3)
        val token = ByteArray(16) { 2 }; phone.token = token
        val c = TestClient(phone)
        c.connect(token)
        assertEquals(3, c.state.unreadTotal)
        // Read on the Android phone itself: sms.unread follows.
        phone.unread.clear(); phone.unread += 3
        phone.send(T.SMS_UNREAD, JSONObject().put("ids", org.json.JSONArray(phone.unread)).put("all", true)); c.pump()
        assertEquals(1, c.state.unreadTotal)
        // Read here: sms.read goes to the phone.
        c.state.markRead(9); c.pump()
        assertEquals(9L, phone.got.last { it.t == T.SMS_READ }.b.getLong("thread"))
        assertEquals(0, c.state.unreadTotal)
        // Delete a text: gone at once, the phone confirms.
        c.state.deleteMessages(listOf(1)); c.pump()
        assertEquals(listOf(2L, 3L), c.state.sms.map { it.id })
        assertTrue(phone.sms.none { it.id == 1L })
        // Mute and block: the lists come back from the phone.
        c.state.setMuted(7, true); c.state.setBlocked("+10000000002", true); c.pump()
        assertTrue(c.state.isMuted(7)); assertTrue(c.state.isBlocked("0000000002"))
        // A new text in a muted conversation is not notified.
        phone.send(T.SMS_NEW, JSONObject().put("item", Sms(4, 7, "+10000000001", "d", now, 1, false, null).json())); c.pump()
        assertFalse(c.fx.notified.any { it == "sms 4" })
    }

    @Test fun staleUnconfirmedTextsTurnNotDeliveredButAreNeverResent() {
        val phone = FakePhone(); val token = ByteArray(16) { 4 }; phone.token = token
        val c = TestClient(phone)
        c.connect(token)
        c.state.sendSms("+10000000007", "x", null)   // sent, but the link drops before the phone sees it
        c.wire!!.cut()
        c.fx.clock += 5 * 60_000
        c.connect()
        assertEquals("never resent: the phone may have sent it", 0, phone.got.count { it.t == T.SMS_SEND })
        assertEquals("Not delivered", 5, c.state.sms.single().box)
    }

    @Test fun callStateParsing() {
        val c = Call.of(JSONObject("""{"cid":"a","num":"+1","state":"active","dir":"out","hold":true,"elsewhere":true,"where":"iPhone"}"""))
        assertTrue(c.elsewhere); assertEquals("iPhone", c.where); assertFalse(c.isIncoming)
        assertEquals(c, Call.of(c.json()))
    }

    @Test fun searchAndDialable() {
        assertTrue(Search.matches("0900", emptyList(), listOf("+98 900 000 0000")))
        assertTrue(Search.matches("۰۹۰۰", emptyList(), listOf("+98 900 000 0001")))
        assertTrue(Search.matches("علي", listOf("علی رضایی"), emptyList()))
        assertTrue(Search.matches("ame", listOf("Amélie"), emptyList()))
        assertFalse(Search.matches("zz", listOf("Amy"), listOf("123")))
        assertEquals("+98900*#", dialable("+۹۸ (900) *#"))
        assertNull(Search.digits("abc").takeIf { it.isNotEmpty() })
    }
}

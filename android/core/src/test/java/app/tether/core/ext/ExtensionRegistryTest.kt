package app.tether.core.ext

import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The extension registry (protocol §7): names, routing, isolation, capabilities, limits. Shared by both Android apps. */
class ExtensionRegistryTest {
    class Link(override val peerName: String = "Test iPhone", override var inCall: Boolean = false) : ExtensionLink {
        override val peerKind = "iphone"
        override val transport = "memory"
        val sent = ArrayList<Message>()
        var up = true
        override fun send(m: Message): Boolean { if (!up) return false; sent += m; return true }
        fun last() = sent.last()
    }

    /** Records what it gets; optionally throws. */
    open class Probe(override val id: String = "x.org.example.probe", override val versions: List<Int> = listOf(1),
                     override val types: Set<String> = setOf(id, "$id.ask")) : TetherExtension {
        val got = ArrayList<Message>()
        val links = ArrayList<Pair<ExtensionSession, Boolean>>()
        var throwing = false
        var host: ExtensionHost? = null
        var stopped = false
        var statusFields: JSONObject? = null
        override fun start(host: ExtensionHost) { this.host = host }
        override fun stop() { stopped = true }
        override fun onLink(session: ExtensionSession, up: Boolean) { links += session to up }
        override fun onMessage(session: ExtensionSession, message: Message) {
            if (throwing) throw IllegalStateException("boom")
            got += message
        }
        override fun status() = statusFields
    }

    private var now = 0L
    private val logs = ArrayList<String>()
    private var statusPushes = 0
    private val reg = ExtensionRegistry(log = { logs += it }, clock = { now }, onStatusChanged = { statusPushes++ })
    private val link = Link()

    private fun caps(vararg c: String) = JSONArray(c.toList())
    private fun msg(t: String, b: JSONObject = JSONObject(), id: Int? = null, re: Int? = null) = Message(t, b, id, re)

    private fun registered(p: Probe = Probe()): Probe { reg.register(p); reg.start(); reg.linkUp(link); return p }

    // ------------------------------------------------------------------ names

    @Test fun namesFollowTheReservedNamespace() {
        for (ok in listOf("x.tether.echo", "x.com.example.battery", "x.io.dev-team.cam2")) assertTrue(ok, ExtensionRegistry.validId(ok))
        for (bad in listOf("echo", "x.echo", "x.", "x.Com.example", "y.com.example", "x.com..example", "x.com.example.", "x.com.-a",
                           "x." + "a".repeat(60) + ".b")) assertFalse(bad, ExtensionRegistry.validId(bad))
        assertTrue(ExtensionRegistry.validType("x.com.example.battery", "x.com.example.battery"))
        assertTrue(ExtensionRegistry.validType("x.com.example.battery.low", "x.com.example.battery"))
        assertFalse("two more labels", ExtensionRegistry.validType("x.com.example.battery.low.now", "x.com.example.battery"))
        assertFalse("another id", ExtensionRegistry.validType("x.com.example.batteryx", "x.com.example.battery"))
    }

    @Test fun coreTypesCanNeverBeClaimed() {
        val core = T::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        assertTrue(core.size > 40)
        for (t in core) {
            try { reg.register(Probe(types = setOf("x.org.example.probe", t))); fail("$t was accepted") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!, "core type" in e.message!!) }
        }
        try { reg.register(Probe(id = "sms")); fail("a core name as id") } catch (_: IllegalArgumentException) {}
        assertTrue(reg.extensions.isEmpty())
    }

    @Test fun aTypeBelongsToOneExtensionOnly() {
        reg.register(Probe())
        try { reg.register(Probe()); fail("same id twice") } catch (_: IllegalArgumentException) {}
        try { reg.register(Probe(id = "x.org.example.other", types = setOf("x.org.example.probe.ask"))); fail("someone else's type") }
        catch (_: IllegalArgumentException) {}
        try { reg.register(Probe(versions = emptyList(), id = "x.org.example.v")); fail("no version") } catch (_: IllegalArgumentException) {}
    }

    // ------------------------------------------------------------------ routing

    @Test fun coreMessagesPassThroughUntouched() {
        val p = registered()
        for (t in listOf(T.SYNC, T.SMS_SEND, T.CALL_ANSWER, T.STATUS, "future.core.type")) assertFalse(t, reg.route(link, msg(t, id = 4)))
        assertFalse("an error that answers no extension request is the app's", reg.route(link, msg(T.ERROR, re = 4)))
        assertFalse(reg.route(link, msg(T.ERROR)))
        assertTrue(p.got.isEmpty()); assertTrue(link.sent.isEmpty())
    }

    @Test fun extensionMessagesReachTheirExtension() {
        val p = registered()
        assertTrue(reg.route(link, msg("x.org.example.probe.ask", JSONObject().put("q", 1), id = 7)))
        assertEquals(listOf("x.org.example.probe.ask"), p.got.map { it.t })
        assertEquals(1, p.got[0].b.getInt("q"))
        assertTrue("handled: nothing sent by the registry", link.sent.isEmpty())
    }

    @Test fun unknownExtensionTypeWithIdIsAnsweredAndWithoutIdIgnored() {
        registered()
        assertTrue(reg.route(link, msg("x.com.example.nobody", id = 12)))
        val e = link.last()
        assertEquals(T.ERROR, e.t); assertEquals(12, e.re); assertNull(e.id)
        assertEquals("x.com.example.nobody", e.b.getString("t")); assertEquals(Ext.ERR_UNKNOWN, e.b.getString("code")); assertTrue(e.b.has("msg"))
        assertTrue(reg.route(link, msg("x.com.example.nobody")))
        assertEquals("no id: no answer", 1, link.sent.size)
    }

    @Test fun aThrowingExtensionIsIsolatedAnsweredAndSwitchedOffAfterTenFailures() {
        val p = registered().apply { throwing = true }
        val ok = Probe(id = "x.org.example.fine")
        reg.register(ok)
        reg.peerCaps(link, caps("x.org.example.probe/1", "x.org.example.fine/1"))
        for (i in 1..9) assertTrue(reg.route(link, msg("x.org.example.probe", id = i)))
        assertEquals(Ext.ERR_FAILED, link.last().b.getString("code")); assertEquals(9, link.last().re)
        assertTrue(reg.isEnabled(p.id)); assertTrue("caps still announced", "x.org.example.probe/1" in reg.caps())
        reg.route(link, msg("x.org.example.probe", id = 10))
        assertFalse("switched off after 10 failures in a row", reg.isEnabled(p.id))
        assertTrue(p.stopped)
        assertFalse("its caps are withdrawn", "x.org.example.probe/1" in reg.caps())
        assertTrue("a status push announces it", statusPushes > 0)
        p.links.last().let { (session, up) -> assertTrue(session.link === link); assertFalse(up) }
        reg.route(link, msg("x.org.example.probe", id = 11))
        assertEquals("now an unknown type", Ext.ERR_UNKNOWN, link.last().b.getString("code"))
        // Other extensions and core routing are untouched.
        reg.route(link, msg("x.org.example.fine", id = 12)); assertEquals(1, ok.got.size)
        assertFalse(reg.route(link, msg(T.PING, id = 13)))
        assertTrue(logs.any { "switched off" in it })
    }

    @Test fun aSuccessResetsTheFailureCount() {
        val p = registered()
        repeat(9) { p.throwing = true; reg.route(link, msg(p.id)) }
        p.throwing = false; reg.route(link, msg(p.id))
        repeat(9) { p.throwing = true; reg.route(link, msg(p.id)) }
        assertTrue(reg.isEnabled(p.id))
    }

    @Test fun throwingInStartOrStatusIsCaughtToo() {
        val bad = object : Probe(id = "x.org.example.bad") {
            override fun start(host: ExtensionHost) = throw IllegalStateException("no")
            override fun status(): JSONObject = throw IllegalStateException("no")
        }
        reg.register(bad); reg.start(); reg.linkUp(link)
        reg.peerCaps(link, caps("x.org.example.bad/1"))
        assertNull(reg.statusFor(link))
    }

    // ------------------------------------------------------------------ capabilities

    @Test fun capsAnnounceEveryMajorAndTheHighestCommonOneIsAgreed() {
        val p = registered(Probe(versions = listOf(2, 1)))
        assertEquals(listOf("x.org.example.probe/1", "x.org.example.probe/2"), reg.caps())
        assertNull("nothing announced yet", reg.version(p, link))
        reg.peerCaps(link, caps("x.org.example.probe/1", "x.org.example.probe/3", "x.com.other/1", "core-feature", "bad/x", "x.Bad/1"))
        assertEquals(1, reg.version(p, link))
        assertEquals(listOf(true), p.links.map { it.second })
        reg.peerCaps(link, caps("x.org.example.probe/2", "x.org.example.probe/1"))
        assertEquals(2, reg.version(p, link))
        assertEquals("version change: no second up", 1, p.links.size)
        reg.peerCaps(link, null)
        assertNull("absent caps: an older peer, nothing agreed", reg.version(p, link))
        assertEquals(listOf(true, false), p.links.map { it.second })
    }

    @Test fun capsAreCappedAndMalformedOnesIgnored() {
        val many = JSONArray((1..100).map { "x.org.example.e$it/1" }).put(5).put(JSONObject())
        assertEquals(Ext.MAX_CAPS, ExtensionRegistry.parseCaps(many).size)
        assertNull(ExtensionRegistry.parseCap("x.org.example/0"))
        assertNull(ExtensionRegistry.parseCap("/1"))
        assertEquals("x.org.example" to 3, ExtensionRegistry.parseCap("x.org.example/3"))
    }

    @Test fun unsolicitedMessagesOnlyToPeersThatAgreed() {
        val p = registered()
        val s = { reg.sessions(p) }
        assertTrue("no agreement: no session", s().isEmpty())
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        val session = s().single()
        assertTrue(session.send("x.org.example.probe", JSONObject().put("a", 1)))
        assertEquals("x.org.example.probe", link.last().t); assertNull(link.last().id)
        reg.peerCaps(link, caps())
        assertFalse("withdrawn: not sent", session.send("x.org.example.probe"))
        // A reply is always allowed: the peer asked.
        assertTrue(session.reply(msg("x.org.example.probe.ask", id = 3), JSONObject().put("ok", true)))
        assertEquals(3, link.last().re)
        assertFalse("no id: nothing to reply to", session.reply(msg("x.org.example.probe.ask"), JSONObject()))
    }

    @Test fun anExtensionCanSendOnlyItsOwnTypes() {
        val p = registered()
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        val session = reg.sessions(p).single()
        for (t in listOf(T.SMS_SEND, T.CALL_DIAL, T.UNPAIR, "x.org.example.other")) {
            try { session.send(t); fail("$t was sent") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(link.sent.isEmpty())
    }

    @Test fun requestsGetTheirAnswerAndErrorsByRe() {
        val p = registered()
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        val id = reg.sessions(p).single().request("x.org.example.probe.ask", JSONObject())!!
        assertTrue("extension ids are far from the apps' own", id >= ExtensionRegistry.FIRST_ID)
        assertEquals(id, link.last().id)
        assertTrue("the error answering it is the extension's", reg.route(link, msg(T.ERROR, JSONObject().put("msg", "x"), re = id)))
        assertEquals(T.ERROR, p.got.single().t)
        assertFalse("answered once only", reg.route(link, msg(T.ERROR, re = id)))
    }

    @Test fun linkDownEndsTheSessions() {
        val p = registered()
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        reg.linkDown(link)
        assertEquals(listOf(true, false), p.links.map { it.second })
        assertTrue(reg.sessions(p).isEmpty())
    }

    // ------------------------------------------------------------------ limits

    @Test fun oversizedMessagesAreRefusedBothWays() {
        val p = registered()
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        val big = JSONObject().put("data", "a".repeat(Ext.MAX_BYTES))
        assertTrue(reg.route(link, msg("x.org.example.probe", big, id = 5)))
        assertTrue(p.got.isEmpty()); assertEquals(Ext.ERR_TOO_LARGE, link.last().b.getString("code"))
        link.sent.clear()
        assertFalse(reg.sessions(p).single().send("x.org.example.probe", big))
        assertTrue(link.sent.isEmpty())
        // During a call the limit is 2 KiB.
        link.inCall = true
        val mid = JSONObject().put("data", "a".repeat(3000))
        assertFalse(reg.sessions(p).single().send("x.org.example.probe", mid))
        reg.route(link, msg("x.org.example.probe", mid, id = 6))
        assertEquals(Ext.ERR_TOO_LARGE, link.last().b.getString("code"))
        link.inCall = false
        assertTrue(reg.sessions(p).single().send("x.org.example.probe", mid))
    }

    @Test fun floodsAreDroppedAtTheRateLimit() {
        val p = registered()
        repeat(100) { reg.route(link, msg("x.org.example.probe")) }
        assertEquals("a burst of 40", Ext.BURST, p.got.size)
        now += 1000
        repeat(100) { reg.route(link, msg("x.org.example.probe")) }
        assertEquals("then 20 per second", Ext.BURST + Ext.RATE_PER_S, p.got.size)
        assertFalse("core traffic is never limited by it", reg.route(link, msg(T.PING)))
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        val s = reg.sessions(p).single()
        assertEquals(Ext.BURST, (1..100).count { s.send("x.org.example.probe") })
    }

    @Test fun statusFieldsOnlyForPeersThatAgreedAndSmall() {
        val p = registered().apply { statusFields = JSONObject().put("level", 3) }
        assertNull(reg.statusFor(link))
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        assertEquals(3, reg.statusFor(link)!!.getJSONObject("x.org.example.probe").getInt("level"))
        p.statusFields = JSONObject().put("blob", "a".repeat(Ext.MAX_STATUS_BYTES))
        assertNull("over 1 KiB: left out", reg.statusFor(link))
        p.host!!.statusChanged(); assertEquals(1, statusPushes)
    }

    @Test fun debugShowsCountsNotContents() {
        val p = registered()
        reg.peerCaps(link, caps("x.org.example.probe/1"))
        reg.route(link, msg(p.id, JSONObject().put("secret", "+10000000001")))
        val d = reg.debug().toString()
        assertTrue(d.contains("\"handled\":1"))
        assertFalse(d.contains("+10000000001"))
    }

    // ------------------------------------------------------------------ echo

    @Test fun echoAnswersRequestsAndMeasuresAnswers() {
        var clock = 1_000L
        val echo = EchoExtension { clock }
        reg.register(echo); reg.start(); reg.linkUp(link)
        reg.route(link, msg(Ext.ECHO, JSONObject().put("ts", 900).put("data", "abc"), id = 2))
        val r = link.last()
        assertEquals(Ext.ECHO, r.t); assertEquals(2, r.re)
        assertEquals(900L, r.b.getLong("ts")); assertEquals("abc", r.b.getString("data")); assertEquals(1000L, r.b.getLong("at"))
        assertEquals("memory", r.b.getString("via"))
        reg.route(link, msg(Ext.ECHO, JSONObject().put("ts", 900)))
        assertEquals("no id: not answered", 1, link.sent.size)

        reg.peerCaps(link, caps("x.tether.echo/1"))
        val id = echo.ping(reg.sessions(echo).single())!!
        assertEquals(1000L, link.last().b.getLong("ts"))
        clock = 1_040L
        reg.route(link, msg(Ext.ECHO, JSONObject().put("ts", 1000).put("at", 5_020).put("via", "l2cap"), re = id))
        assertEquals(EchoExtension.Result("Test iPhone", 40, 4_000, "l2cap", 0), echo.last)
    }
}

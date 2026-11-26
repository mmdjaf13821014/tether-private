package app.tether.client

import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import app.tether.core.ext.EchoExtension
import app.tether.core.ext.ExtensionRegistry
import app.tether.core.ext.ExtensionSession
import app.tether.core.ext.TetherExtension
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extensions (protocol §7) on the Android client, over a real session to a fake phone that routes extension messages
 * the way Hub does: capabilities in sync and status, echo both ways, errors, and old peers on either side.
 */
class ExtensionsTest {
    /** Asks for a type the phone does not know; keeps what comes back. */
    class Asker : TetherExtension {
        override val id = "x.org.example.ask"
        override val types = setOf(id, "$id.nothing")
        val got = ArrayList<Message>()
        override fun onMessage(session: ExtensionSession, message: Message) { got += message }
    }

    private fun phoneWithExtensions() = FakePhone().apply {
        extensions = ExtensionRegistry().apply { register(EchoExtension()); start() }
    }

    private fun clientRegistry(vararg extra: TetherExtension) = ExtensionRegistry().apply {
        register(EchoExtension()); extra.forEach(::register); start()
    }

    private fun linked(phone: FakePhone, ext: ExtensionRegistry?): TestClient {
        val c = TestClient(phone, extensions = ext)
        phone.paired += app.tether.core.P256.encode(c.key.public)
        c.connect()
        return c
    }

    @Test fun syncAnnouncesCapsAndStatusAgreesTheVersion() {
        val phone = phoneWithExtensions()
        val reg = clientRegistry()
        val c = linked(phone, reg)
        val sync = phone.got.last { it.t == T.SYNC }
        assertEquals(listOf("x.tether.echo/1"), (0 until sync.b.getJSONArray("caps").length()).map { sync.b.getJSONArray("caps").getString(it) })
        val echo = reg.extensions.first()
        assertEquals("the client agreed v1 from the phone's status", 1, reg.version(echo, c.extLink))
        assertEquals(1, phone.extensions!!.version(phone.extensions!!.extensions.first(), phone.extLink))
    }

    @Test fun echoBothWays() {
        val phone = phoneWithExtensions()
        val reg = clientRegistry()
        val c = linked(phone, reg)
        val mine = reg.extensions.first() as EchoExtension
        assertNotNull(mine.ping(reg.sessions(mine).single(), "hello"))
        c.pump()
        assertEquals("memory", mine.last?.via); assertEquals(5, mine.last?.bytes); assertNull(mine.last?.error)

        val theirs = phone.extensions!!.extensions.first() as EchoExtension
        assertNotNull(theirs.ping(phone.extensions!!.sessions(theirs).single()))
        c.pump()
        assertEquals("the client answered the phone's echo", "memory", theirs.last?.via)
        assertNull("extension traffic is no problem for the user", c.state.problem)
    }

    @Test fun unknownExtensionTypeIsAnsweredWithAnErrorThatGoesToTheAsker() {
        val phone = phoneWithExtensions()
        val ask = Asker()
        val reg = clientRegistry(ask)
        val c = linked(phone, reg)
        phone.send(T.STATUS, JSONObject().put("caps", org.json.JSONArray().put("x.org.example.ask/1")))
        c.pump()
        val id = reg.sessions(ask).single().request("x.org.example.ask.nothing")
        assertNotNull(id)
        c.pump()
        val err = ask.got.single()
        assertEquals(T.ERROR, err.t); assertEquals(id, err.re); assertEquals(Ext.ERR_UNKNOWN, err.b.getString("code"))
        assertNull("an extension's error is not shown as a problem", c.state.problem)
    }

    @Test fun anErrorForACoreRequestStillReachesTheUser() {
        val c = linked(phoneWithExtensions(), clientRegistry())
        c.phone.send(T.ERROR, JSONObject().put("msg", "unknown type"), re = 3)
        c.pump()
        assertEquals("unknown type", c.state.problem?.detail)
    }

    @Test fun oldPhoneWithoutCapsAgreesNothingAndCoreKeepsWorking() {
        val phone = FakePhone()   // no extensions, no caps in status
        val reg = clientRegistry()
        val c = linked(phone, reg)
        phone.send(T.STATUS, JSONObject().put("device", "Old phone"))
        c.pump()
        val echo = reg.extensions.first() as EchoExtension
        assertNull(reg.version(echo, c.extLink))
        assertTrue(reg.sessions(echo).isEmpty())
        assertEquals("Old phone", c.state.status?.optString("device"))
        assertTrue("the old phone just ignores caps in the sync", phone.got.any { it.t == T.SYNC })
    }

    @Test fun clientWithoutExtensionsSendsTheSyncOfBefore() {
        val phone = phoneWithExtensions()
        val c = linked(phone, null)
        assertFalse(phone.got.last { it.t == T.SYNC }.b.has("caps"))
        // An extension request from the phone gets no answer from a client without extensions, and breaks nothing.
        phone.sendMessage(Message("x.tether.echo", JSONObject().put("ts", 1), id = 9))
        c.pump()
        assertNull(c.state.problem)
    }

    @Test fun phoneRequestForAnUnknownExtensionIsRefusedByTheClient() {
        val phone = phoneWithExtensions()
        linked(phone, clientRegistry()).also { c ->
            phone.sendMessage(Message("x.org.example.other", JSONObject(), id = 42))
            c.pump()
        }
        val err = phone.got.last { it.t == T.ERROR }
        assertEquals(42, err.re); assertEquals("x.org.example.other", err.b.getString("t")); assertEquals(Ext.ERR_UNKNOWN, err.b.getString("code"))
    }
}

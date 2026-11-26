package app.tether.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The A54's half of the wire contract, pinned by the shared fixtures in protocol/fixtures (the iPhone's
 * ContractTests read the same files). See protocol/fixtures/README.md.
 */
class ContractTest {
    private enum class K { STR, INT, BOOL, ARR, OBJ }
    private data class F(val key: String, val kind: K, val required: Boolean = false)

    /** What Hub.onMessage reads from each body a client sends (keys and JSON types; required = read without a default that works). */
    private val received: Map<String, List<F>> = mapOf(
        "ping" to listOf(F("ts", K.INT, true)),
        "pong" to listOf(F("ts", K.INT, true)),
        "sync" to listOf(F("sms", K.INT, true), F("calls", K.INT, true), F("contacts", K.STR, true), F("codecs", K.ARR), F("client", K.OBJ),
            F("caps", K.ARR)),
        "sms.send" to listOf(F("to", K.STR, true), F("body", K.STR, true), F("sub", K.INT)),
        "sms.read" to listOf(F("thread", K.INT, true), F("upTo", K.INT, true)),
        "sms.delete" to listOf(F("ids", K.ARR), F("thread", K.INT)),
        "calls.delete" to listOf(F("ids", K.ARR), F("all", K.BOOL)),
        "lists.set" to listOf(F("what", K.STR, true), F("on", K.BOOL, true), F("num", K.STR), F("thread", K.INT)),
        "contact.add" to listOf(F("name", K.STR, true), F("phones", K.ARR, true), F("emails", K.ARR)),
        "contact.update" to listOf(F("id", K.INT, true), F("name", K.STR, true), F("phones", K.ARR, true), F("emails", K.ARR)),
        "contact.star" to listOf(F("id", K.INT, true), F("on", K.BOOL, true)),
        "call.answer" to listOf(F("cid", K.STR, true)),
        "call.reject" to listOf(F("cid", K.STR, true)),
        "call.end" to listOf(F("cid", K.STR, true)),
        "call.take" to listOf(F("cid", K.STR, true)),
        "call.hold" to listOf(F("cid", K.STR, true), F("on", K.BOOL, true)),
        "call.mute" to listOf(F("on", K.BOOL, true)),
        "call.dtmf" to listOf(F("cid", K.STR, true), F("d", K.STR, true)),
        "call.dial" to listOf(F("num", K.STR, true), F("sub", K.INT)),
        "selftest" to emptyList(),
        "audio.set" to listOf(F("micMute", K.BOOL), F("mic", K.STR)),
        "net.get" to emptyList(),
        "net.set" to listOf(F("what", K.STR, true), F("on", K.BOOL, true), F("sub", K.INT), F("slot", K.INT), F("mode", K.STR)),
        "ussd.send" to listOf(F("code", K.STR, true), F("sub", K.INT)),
        "phone.find" to listOf(F("on", K.BOOL, true)),
        "ringer.set" to listOf(F("mode", K.STR, true)),
        "unpair" to emptyList(),
        "error" to listOf(F("msg", K.STR, true), F("t", K.STR), F("code", K.STR)),
    )

    /** T constants with no fixture on purpose (none today). */
    private val noFixture = emptyMap<String, String>()

    private val dir = File(javaClass.classLoader!!.getResource("fixtures")!!.toURI())
    private val allFixtures: Map<String, JSONObject> = dir.listFiles { f -> f.name.endsWith(".json") }!!
        .associate { f -> JSONObject(f.readText()).let { it.getString("t") to it } }
    /** Core types (§5); extension fixtures (`x.…`, §7) are checked by the extension tests below. */
    private val fixtures = allFixtures.filterKeys { !it.startsWith(Ext.PREFIX) }
    private val extFixtures = allFixtures.filterKeys { it.startsWith(Ext.PREFIX) }

    private fun variants(t: String): Map<String, JSONObject> =
        allFixtures.getValue(t).getJSONObject("variants").let { v -> keysOf(v).associateWith { v.getJSONObject(it) } }

    private fun tConstants(): Set<String> = T::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }.toSet()

    /** Types named in PROTOCOL.md §5: the first column of the message table and every `type` after an arrow. */
    private fun protocolTypes(): Set<String> {
        val text = javaClass.classLoader!!.getResource("PROTOCOL.md")!!.readText()
        val sec = text.substringAfter("## 5. Control messages").substringBefore("\n### 5.1")
        val out = HashSet<String>()
        for (line in sec.lines().filter { it.startsWith("| `") }) {
            Regex("`([a-z.]+)`").findAll(line.split("|")[1]).forEach { out.add(it.groupValues[1]) }
            Regex("→ `([a-z.]+)`").findAll(line).forEach { out.add(it.groupValues[1]) }
        }
        return out
    }

    private fun keysOf(o: JSONObject): List<String> = o.keys().asSequence().toList()

    /** Deep JSON equality (org.json's `similar` is missing from the Android stubs this module compiles against). */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> keysOf(a).toSet() == keysOf(b).toSet() && keysOf(a).all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toLong() == b.toLong() && kindOf(a) == kindOf(b)
        else -> a == b
    }

    private fun kindOf(v: Any?): K? = when (v) {
        is String -> K.STR; is Int, is Long -> K.INT; is Boolean -> K.BOOL
        is JSONArray -> K.ARR; is JSONObject -> K.OBJ; else -> null
    }

    @Test fun everyTypeIsInTheSpecAndHasAFixture() {
        val ts = tConstants()
        assertEquals("T and PROTOCOL.md §5 must name the same types", protocolTypes(), ts)
        for (t in ts) assertTrue("$t has no fixture in protocol/fixtures", t in fixtures || t in noFixture)
        assertEquals("fixtures for types T does not know", emptySet<String>(), fixtures.keys - ts)
    }

    @Test fun everyBodyTheA54ReceivesParses() {
        val clientSent = fixtures.filterValues { it.getString("dir") != "phone-to-client" }.keys
        assertEquals("every client-sent type needs its read spec here", clientSent, received.keys)
        for (t in clientSent) for ((name, b) in variants(t)) {
            val env = JSONObject().put("t", t).put("id", 5).put("b", b).toString().toByteArray()
            val m = Message.parse(env)
            assertEquals(t, m.t); assertEquals(5, m.id)
            val spec = received.getValue(t).associateBy { it.key }
            for (k in keysOf(m.b)) {
                val f = spec[k] ?: run { fail("$t/$name: '$k' is sent but the A54 does not read it"); return }
                assertEquals("$t/$name: type of '$k'", f.kind, kindOf(m.b.get(k)))
            }
            for (f in spec.values.filter { it.required }) assertTrue("$t/$name lacks required '${f.key}'", m.b.has(f.key))
        }
    }

    @Test fun phonesInContactBodiesHaveNumbers() {
        for (t in listOf("contact.add", "contact.update")) for ((name, b) in variants(t)) {
            val a = b.getJSONArray("phones")
            for (i in 0 until a.length()) assertTrue("$t/$name phone $i", a.getJSONObject(i).get("n") is String)
        }
    }

    private fun assertSame(what: String, expected: JSONObject, actual: JSONObject) =
        assertTrue("$what\nexpected $expected\nactual   $actual", same(expected, actual))

    private fun sms(o: JSONObject) = Sms(o.getLong("id"), o.getLong("thread"), o.getString("addr"), o.getString("body"),
        o.getLong("date"), o.getInt("box"), o.getBoolean("read"), if (o.has("sub")) o.getInt("sub") else null)

    @Test fun smsJsonMatchesFixtures() {
        val items = variants("sms.batch").getValue("full").getJSONArray("items")
        for (i in 0 until items.length()) items.getJSONObject(i).let { assertSame("sms[$i]", it, sms(it).json()) }
        variants("sms.new").values.forEach { v -> v.getJSONObject("item").let { assertSame("sms.new", it, sms(it).json()) } }
    }

    @Test fun contactJsonMatchesFixtures() {
        val items = variants("contacts.batch").getValue("full").getJSONArray("items")
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val ph = o.getJSONArray("phones").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .map { Phone(it.getString("n"), it.getString("type"), it.optString("label").ifEmpty { null }) }
            val em = o.getJSONArray("emails").let { a -> (0 until a.length()).map { a.getString(it) } }
            val c = Contact(o.getLong("id"), o.getString("name"), ph, em, o.optString("photo").ifEmpty { null }, o.getBoolean("starred"))
            assertSame("contact[$i]", o, c.json())
        }
    }

    @Test fun callEntryJsonMatchesFixtures() {
        val items = variants("calls.batch").getValue("full").getJSONArray("items")
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val e = CallEntry(o.getLong("id"), o.getString("num"), o.optString("name").ifEmpty { null }, o.getLong("date"),
                o.getInt("dur"), o.getInt("type"), if (o.has("sub")) o.getInt("sub") else null)
            assertSame("call entry[$i]", o, e.json())
        }
    }

    @Test fun callInfoJsonMatchesFixtures() {
        // `elsewhere` / `where` are added per receiver by Hub.callJson, so they are not part of CallInfo.json().
        for ((name, v) in variants("call.state").filterKeys { it != "unknown" }) {
            val o = JSONObject(v.toString()).apply { remove("elsewhere"); remove("where") }
            val c = CallInfo(o.getString("cid"), o.getString("num"), o.optString("name").ifEmpty { null }, o.getString("state"),
                o.getString("dir"), if (o.has("sub")) o.getInt("sub") else null, if (o.has("since")) o.getLong("since") else null,
                o.optString("cause").ifEmpty { null }, o.getBoolean("hold"))
            assertSame("call.state/$name", o, c.json())
        }
    }

    @Test fun idRangesMatchSyncIdsFixture() {
        val full = variants("sync.ids").getValue("full")
        assertTrue(full.getJSONArray("sms").let { a -> same(a, idRanges(listOf(120L, 5, 1, 2, 3, 4) + (6L..118L) + 118L)) })
        assertTrue(full.getJSONArray("calls").let { a -> same(a, idRanges(listOf(901L, 900L))) })
        assertTrue(variants("sync.ids").getValue("old").getJSONArray("sms").let { a -> same(a, idRanges(emptyList())) })
    }

    @Test fun envelopeRoundTrips() {
        val b = variants("status").getValue("full")
        val m = Message.parse(Message(T.STATUS, b, id = 3, re = 9).toJson())
        assertEquals(T.STATUS, m.t); assertEquals(3, m.id); assertEquals(9, m.re); assertSame("status", b, m.b)
        // An old producer may leave out `b` for an empty body.
        assertEquals(0, Message.parse("""{"t":"audio.stop"}""".toByteArray()).b.length())
    }

    // ------------------------------------------------------------------ extensions (§7)

    private class Link(override val transport: String) : app.tether.core.ext.ExtensionLink {
        override val peerName = "Test iPhone"; override val peerKind = "iphone"
        val sent = ArrayList<Message>()
        override fun send(m: Message): Boolean { sent += m; return true }
    }

    @Test fun extensionFixturesAreExtensionTypesNamedInSection7() {
        assertTrue("x.tether.echo" in extFixtures && "x.org.example.unknown" in extFixtures)
        val text = javaClass.classLoader!!.getResource("PROTOCOL.md")!!.readText()
        val sec7 = text.substringAfter("## 7. Extensions")
        for ((t, f) in extFixtures) {
            // Every extension type is a valid name in the namespace (an id, or an id and one more label).
            assertTrue("$t is not a valid extension type", app.tether.core.ext.ExtensionRegistry.validId(t))
            assertTrue("$t: dir", f.getString("dir") in setOf("both", "phone-to-client", "client-to-phone"))
            assertTrue("$t is not named in PROTOCOL.md §7", "`$t`" in sec7)
        }
        assertTrue("no extension type in §5's table", protocolTypes().none { it.startsWith(Ext.PREFIX) })
    }

    @Test fun echoAnswersEveryRequestFixtureLikeTheReplyFixtures() {
        val reply = variants(Ext.ECHO).getValue("reply")
        val at = reply.getLong("at")
        for ((name, b) in variants(Ext.ECHO).filterKeys { !it.startsWith("reply") }) {
            val reg = app.tether.core.ext.ExtensionRegistry().apply { register(app.tether.core.ext.EchoExtension { at }); start() }
            val link = Link("l2cap")
            assertTrue(reg.route(link, Message.parse(Message(Ext.ECHO, b, id = 5).toJson())))
            val out = Message.parse(link.sent.single().toJson())
            assertEquals(Ext.ECHO, out.t); assertEquals(5, out.re)
            val want = if (b.has("data")) reply else JSONObject(reply.toString()).apply { remove("data") }
            assertSame("echo/$name", want, out.b)
        }
        // The answer's shape is the one the reply fixtures pin (an asker on any side reads these fields).
        for ((name, b) in variants(Ext.ECHO).filterKeys { it.startsWith("reply") })
            for (k in listOf("ts", "at")) assertEquals("$name.$k", K.INT, kindOf(b.get(k)))
    }

    @Test fun unknownExtensionIsRefusedWithTheErrorFixture() {
        val reg = app.tether.core.ext.ExtensionRegistry().apply { register(app.tether.core.ext.EchoExtension()); start() }
        val link = Link("gatt")
        val b = variants("x.org.example.unknown").getValue("request")
        assertTrue(reg.route(link, Message.parse(Message("x.org.example.unknown", b, id = 8).toJson())))
        val err = Message.parse(link.sent.single().toJson())
        assertEquals(T.ERROR, err.t); assertEquals(8, err.re)
        assertSame("error/extension", variants(T.ERROR).getValue("extension"), err.b)
        assertTrue("without an id: ignored, never answered", reg.route(link, Message("x.org.example.unknown", b)))
        assertEquals(1, link.sent.size)
    }

    @Test fun capsInSyncAndStatusFixturesAgreeEcho() {
        val reg = app.tether.core.ext.ExtensionRegistry().apply { register(app.tether.core.ext.EchoExtension()); start() }
        val echo = reg.extensions.single()
        for ((t, v) in listOf("sync" to "full", "sync" to "android", "status" to "full", "status" to "unknown")) {
            val link = Link("l2cap")
            reg.peerCaps(link, variants(t).getValue(v).getJSONArray(Ext.CAPS))
            assertEquals("$t/$v", 1, reg.version(echo, link))
        }
        for (t in listOf("sync", "status")) {
            val link = Link("l2cap")
            reg.peerCaps(link, variants(t).getValue("old").optJSONArray(Ext.CAPS))
            assertEquals("$t/old: an app from before extensions agrees nothing", null, reg.version(echo, link))
        }
        assertEquals(listOf("x.tether.echo/1"), reg.caps())
        val ext = variants("status").getValue("full").getJSONObject(Ext.STATUS)
        for (k in keysOf(ext)) assertTrue(k, app.tether.core.ext.ExtensionRegistry.validId(k))
    }
}

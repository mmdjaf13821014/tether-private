package app.tether.link

import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import app.tether.core.ext.EchoExtension
import app.tether.core.ext.ExtensionLink
import app.tether.core.ext.ExtensionRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the Android phone's Hub hands messages to extensions (protocol §7): only in the fallback of its message
 * switch, after every core type, so no extension ever sees or replaces a core message; and the registry as Hub builds
 * it passes every core type back.
 */
class ExtensionHookTest {
    private val hub = File("src/main/java/app/tether/link/Hub.kt").readText()
    private val onMessage = hub.substringAfter("override fun onMessage(c: Connection, m: Message) {").substringBefore("\n    // -----")

    @Test fun extensionsAreReachedOnlyFromTheFallbackOfTheSwitch() {
        val routeLines = onMessage.lines().filter { "extensions.route" in it }
        assertEquals("one hand-over to the extensions", 1, routeLines.size)
        assertTrue("it is the switch's else branch", routeLines.single().trim().startsWith("else -> if (!extensions.route(ext(c), m))"))
        assertTrue("…and keeps the old fallback", "Log.d(TAG, \"ignored message \${m.t}\")" in routeLines.single())
        // Every core branch comes before it.
        val elseAt = onMessage.indexOf(routeLines.single().trim())
        for (t in listOf("T.PING", "T.SYNC", "T.SMS_SEND", "T.CALL_ANSWER", "T.UNPAIR"))
            assertTrue("$t before the fallback", onMessage.indexOf("$t ->").let { it in 0 until elseAt })
        assertEquals("nothing else in Hub routes to extensions", 1, Regex("extensions\\.route").findAll(hub).count())
    }

    @Test fun capsAreReadFromEverySyncAndSentInEveryStatus() {
        val sync = onMessage.substringAfter("T.SYNC -> {").substringBefore("T.SMS_SEND")
        assertTrue("sync caps", "extensions.peerCaps(ext(c), m.b.optJSONArray(Ext.CAPS))" in sync)
        assertTrue("status caps", ".put(Ext.CAPS, extensions.capsJson())" in hub)
        assertTrue("status fields per client", "extensions.statusFor(ext(c))" in hub)
        assertTrue("a closed link ends its extension sessions", "extLinks.remove(c)?.let(extensions::linkDown)" in hub)
        assertTrue("the phone's extension list is registered", "for (e in phoneExtensions(app))" in hub)
        val list = File("src/main/java/app/tether/ext/PhoneExtensions.kt").readText()
        assertTrue("the built-in echo is on it", "EchoExtension()" in list)
    }

    @Test fun hubsRegistryPassesEveryCoreTypeBack() {
        val reg = ExtensionRegistry().apply { register(EchoExtension()); start() }
        val sent = ArrayList<Message>()
        val link = object : ExtensionLink {
            override val peerName = "Test iPhone"; override val peerKind = "iphone"; override val transport = "memory"
            override fun send(m: Message): Boolean { sent += m; return true }
        }
        val core = T::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        for (t in core) assertFalse(t, reg.route(link, Message(t, JSONObject(), id = 3)))
        assertTrue("never answered either", sent.isEmpty())
        assertTrue(reg.route(link, Message(Ext.ECHO, JSONObject().put("ts", 1), id = 4)))
        assertEquals(4, sent.single().re)
    }
}

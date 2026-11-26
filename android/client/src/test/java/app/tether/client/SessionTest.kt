package app.tether.client

import app.tether.audio.OpusCodec
import app.tether.client.link.CentralSession
import app.tether.client.link.Pipe
import app.tether.client.link.SessionListener
import app.tether.client.model.Problem
import app.tether.core.AudioFrame
import app.tether.core.Bytes
import app.tether.core.CallEntry
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.PairingCode
import app.tether.core.RecordReader
import app.tether.core.Sms
import app.tether.core.T
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

/** The client's session stack against protocol/vectors.json and against a fake Android phone over a memory wire. */
class SessionTest {
    private val vectors: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("vectors.json")!!.readBytes().toString(Charsets.UTF_8))
    }

    private fun JSONObject.hex(k: String) = Bytes.unhex(getString(k))
    private fun keyPair(priv: ByteArray, pub: ByteArray) = KeyPair(P256.decode(pub), P256.privateFromScalar(priv))

    @Test fun reproducesTheVectors() {
        val hs = vectors.getJSONArray("handshakes")
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            val written = ArrayList<ByteArray>()
            val pipe = object : Pipe {
                override val label = "vectors"; override val backlog = 0
                override fun write(bytes: ByteArray) { written += bytes }
                override fun close() {}
            }
            var up = false
            val audio = ArrayList<AudioFrame>()
            val token = h.getString("token").takeIf { it.isNotEmpty() }?.let(Bytes::unhex)
            val s = CentralSession(keyPair(h.hex("s_C_priv"), h.hex("S_C")), h.hex("S_P"), token, pipe, object : SessionListener {
                override fun onUp(s: CentralSession) { up = true }
                override fun onMessage(s: CentralSession, m: Message) {}
                override fun onAudio(s: CentralSession, f: AudioFrame) { audio += f }
                override fun onRejected(s: CentralSession, reason: Int) {}
                override fun onFailed(s: CentralSession, reason: String) { throw AssertionError(reason) }
            }, ephemeral = keyPair(h.hex("e_C_priv"), h.hex("E_C")), nonce = h.hex("n_C"))
            s.start()
            assertArrayEquals("HELLO of handshake $i", h.hex("hello_record"), written[0])
            s.received(h.hex("hello_ack_record"))
            assertArrayEquals("FIN of handshake $i", h.hex("fin_c2p_record"), written[1])
            assertFalse(up)
            s.received(h.hex("fin_p2c_record"))
            assertTrue(s.isUp)
            // The phone's first sealed record (counter 1): an audio frame.
            s.received(h.hex("p2c_ctr1_record"))
            val plain = h.hex("p2c_ctr1_plain")
            assertEquals(1, audio.size)
            assertArrayEquals(plain.copyOfRange(1, plain.size), audio[0].encoded())
        }
    }

    @Test fun pairingCodeFromTheVectors() {
        val pc = vectors.getJSONObject("pairing_code")
        val code = PairingCode.parse(pc.getString("string"))!!
        assertEquals(pc.getString("name"), code.deviceName)
        assertEquals(pc.getString("name"), PairingCode.parse(pc.getString("link"))!!.deviceName)
        assertNull(PairingCode.parse("tether1:not-a-code"))
        assertNull(PairingCode.parse("tether://pair?c=not-a-code"))
    }

    private fun pairedClient(chunk: Int = 4096): Pair<FakePhone, TestClient> {
        val phone = FakePhone()
        val token = ByteArray(16) { 3 }
        phone.token = token
        val c = TestClient(phone)
        c.connect(token, chunk)
        return phone to c
    }

    @Test fun pairThenFullSync() {
        val phone = FakePhone()
        phone.sms += listOf(Sms(1, 7, "+10000000001", "one", 1000, 1, true, 2), Sms(2, 7, "+10000000001", "two", 2000, 2, true, 2),
            Sms(3, 8, "+10000000002", "three", 3000, 1, false, 3))
        phone.unread += 3
        phone.calls += listOf(CallEntry(10, "+10000000001", null, 1000, 5, 1, 2), CallEntry(11, "+10000000002", null, 2000, 0, 3, 3))
        phone.contacts += listOf(JSONObject("""{"id":1,"name":"Zed","phones":[{"n":"+10000000001","type":"mobile"}],"emails":[],"starred":true}"""),
            JSONObject("""{"id":2,"name":"Amy","phones":[{"n":"+10000000002","type":"mobile"}],"emails":[],"starred":false}"""),
            JSONObject("""{"id":3,"name":"Bob","phones":[],"emails":[],"starred":false}"""))
        val token = ByteArray(16) { 9 }
        phone.token = token
        val c = TestClient(phone)
        val s = c.connect(token, chunk = 20)   // GATT-sized chunks both ways
        assertTrue(s.isUp)
        assertEquals(1, phone.paired.size)
        val sync = phone.got.first { it.t == T.SYNC }.b
        assertEquals(0, sync.getLong("sms")); assertEquals("", sync.getString("contacts"))
        assertEquals("android", sync.getJSONObject("client").getString("kind"))
        assertEquals("opus", sync.getJSONArray("codecs").getString(0))
        val st = c.state
        assertEquals(3, st.sms.size)
        assertEquals(listOf("Amy", "Bob", "Zed"), st.contacts.map { it.name })
        assertEquals("v1", st.contactsVersion)
        assertEquals("Zed", st.name("0000000001"))
        assertEquals(2, st.conversations.size)
        assertEquals(1, st.unreadTotal)
        assertEquals(2, st.recents.size)
        assertEquals("a first sync's missed calls are history, not missed", 0, st.missedCount)
        assertTrue(c.fx.notified.isEmpty())
        assertEquals(3L, st.toJson().getLong("smsSyncedUpTo"))
    }

    @Test fun sendTextThenRealRowReplacesThePlaceholder() {
        val (phone, c) = pairedClient()
        c.state.sendSms("+10000000005", "hello there", 2)
        val placeholder = c.state.sms.single()
        assertTrue(placeholder.id < 0); assertEquals(4, placeholder.box)
        c.pump()
        assertEquals("hello there", phone.got.last { it.t == T.SMS_SEND }.b.getString("body"))
        val rows = c.state.sms
        assertEquals(1, rows.size)
        assertEquals(1000L, rows[0].id)
        assertEquals(2, rows[0].box)
    }

    @Test fun offlineTextsWaitInTheOutboxAndGoOnReconnect() {
        val (phone, c) = pairedClient()
        c.wire!!.cut()
        assertFalse(c.port.isConnected)
        c.state.sendSms("+10000000006", "later", null)
        assertEquals(Problem.SEND_LATER, c.state.problem?.kind)
        assertEquals(1, c.state.outbox.size)
        c.connect()   // resume: the phone knows this client now
        assertTrue(c.session!!.isUp)
        assertEquals(1, phone.got.count { it.t == T.SMS_SEND })
        assertTrue(c.state.outbox.isEmpty())
        assertEquals(listOf(1000L), c.state.sms.map { it.id })
    }

    @Test fun reconnectResumesAndSyncsIncrementally() {
        val phone = FakePhone()
        val t0 = TestEffects().clock - 60_000
        phone.sms += Sms(1, 7, "+10000000001", "one", t0, 1, true, 2)
        phone.sms += Sms(2, 7, "+10000000001", "two", t0 + 1, 1, true, 2)
        val token = ByteArray(16) { 1 }; phone.token = token
        val c = TestClient(phone)
        c.connect(token)
        assertEquals(2, c.state.sms.size)
        c.wire!!.cut()
        assertEquals("transport closed", c.failed)
        // While away: one text deleted on the phone, one new.
        phone.sms.removeAll { it.id == 1L }
        phone.sms += Sms(3, 7, "+10000000001", "three", t0 + 2, 1, false, 2)
        phone.unread += 3
        phone.got.clear()
        val s = c.connect()
        assertTrue(s.isUp)
        assertFalse(s.pairing)
        assertEquals(2L, phone.got.first { it.t == T.SYNC }.b.getLong("sms"))
        assertEquals("deleted on the phone while away: gone here too (sync.ids)", listOf(2L, 3L), c.state.sms.map { it.id }.sorted())
        assertEquals(listOf("sms 3"), c.fx.notified)   // a new unread text from an incremental sync is notified
    }

    @Test fun rejects() {
        // Unknown device (the phone forgot this client): REJECT 2, keys kept.
        val phone = FakePhone()
        val c = TestClient(phone)
        c.connect()
        assertEquals(2, c.rejected)
        // Pairing closed / token expired: REJECT 3.
        c.connect(ByteArray(16) { 5 })
        assertEquals(3, c.rejected)
        // A wrong token: the phone's FIN check fails and it drops the link.
        phone.token = ByteArray(16) { 6 }
        val s = c.connect(ByteArray(16) { 7 })
        assertFalse(s.isUp)
        assertTrue(phone.paired.isEmpty())
        // Busy (a test holds the link): REJECT 4.
        phone.token = ByteArray(16) { 6 }; phone.busy = true
        c.connect(ByteArray(16) { 6 })
        assertEquals(4, c.rejected)
    }

    @Test fun pingIsAnsweredAndTheRoundTripMeasured() {
        val (phone, c) = pairedClient()
        phone.send(T.PING, JSONObject().put("ts", 5))
        c.pump()
        // The client answered the phone's ping on its own; its own ping comes back as a pong with a round trip.
        c.session!!.ping(); c.pump()
        assertNotNull(c.session!!.roundTripMs)
        assertTrue(phone.got.any { it.t == T.PING })
    }

    @Test fun aStreamStuckMidRecordIsSeen() {
        val (phone, c) = pairedClient(chunk = 8)
        c.wire!!.holdBackToClient = 1
        phone.send(T.STATUS, JSONObject().put("device", "A").put("battery", 50))
        c.pump()
        assertTrue(c.session!!.stalledMs(System.currentTimeMillis() + 11_000) > 10_000)
        c.wire!!.holdBackToClient = 0
        c.pump()
        assertEquals(0, c.session!!.stalledMs())
        assertEquals(50, c.state.status!!.getInt("battery"))
    }

    @Test fun incomingCallAnswerAudioBothWaysAndEnd() {
        val (phone, c) = pairedClient()
        phone.setCall("c1", "ringing"); c.pump()
        assertEquals(listOf("incoming c1"), c.sink.events)
        val local = c.calls.localOf("c1")!!
        c.calls.performAnswer(local); c.pump()
        assertEquals("c1", phone.got.last { it.t == T.CALL_ANSWER }.b.getString("cid"))
        assertEquals("opus", c.audio.codec)
        assertTrue("active" in c.sink.events)
        // Audio: the phone sends Opus frames, the client decodes them; the client's uplink reaches the phone.
        val enc = OpusCodec()
        repeat(5) { i -> phone.sendAudio(AudioFrame(AudioFrame.OPUS, i, enc.encode(ShortArray(320) { k -> (1000 * Math.sin(k * 0.3)).toInt().toShort() })[0])) }
        c.pump()
        assertEquals((0..4).toList(), c.audio.frames.map { it.seq })
        assertNotNull(OpusCodec().decode(c.audio.frames[0].payload))
        repeat(3) { i -> assertTrue(c.session!!.sendAudio(AudioFrame(AudioFrame.OPUS, i, enc.encode(ShortArray(320))[0]))) }
        c.pump()
        assertEquals(listOf(0, 1, 2), phone.audioIn.map { it.seq })
        // Hang up here: call.end, the guard drops late states, the end closes it.
        c.calls.performEnd(local)
        c.pump()
        assertEquals("c1", phone.got.last { it.t == T.CALL_END }.b.getString("cid"))
        assertFalse(c.calls.hasCalls)
        assertEquals(null, c.audio.codec)   // audio.stop
        assertTrue("idle" in c.sink.events)
    }

    @Test fun outgoingCallFromThisPhone() {
        val (phone, c) = pairedClient()
        c.calls.performStart("L1", "+10000000003", 2)
        assertTrue(c.sink.ringback)
        c.pump()
        val dial = phone.got.last { it.t == T.CALL_DIAL }.b
        assertEquals("+10000000003", dial.getString("num")); assertEquals(2, dial.getInt("sub"))
        assertEquals("L1", c.calls.localOf("out1"))
        phone.setCall("out1", "active", num = "+10000000003", dir = "out"); c.pump()
        assertFalse(c.sink.ringback)
        phone.setCall("out1", "ended", num = "+10000000003", dir = "out"); c.pump()
        assertEquals("ended REMOTE", c.sink.events.last { it.startsWith("ended") })
    }

    @Test fun aCallAnsweredElsewhereEndsHereAndCanBeMovedBack() {
        val (phone, c) = pairedClient()
        phone.setCall("c2", "ringing"); c.pump()
        phone.setCall("c2", "active", elsewhere = "iPhone"); c.pump()
        assertTrue("ended ANSWERED_ELSEWHERE" in c.sink.events)
        assertEquals("iPhone", c.calls.remote["c2"]!!.where)
        c.calls.take("c2"); c.pump()
        assertEquals("c2", phone.got.last { it.t == T.CALL_TAKE }.b.getString("cid"))
        assertTrue(c.sink.events.last().startsWith("started c2") || c.sink.events.last() == "active")
        assertNotNull(c.calls.localOf("c2"))
    }

    @Test fun linkLossEndsLocalCallsAndCallListReconciles() {
        val (phone, c) = pairedClient()
        phone.setCall("c3", "ringing"); c.pump()
        c.wire!!.cut()
        assertEquals("ended FAILED", c.sink.events.last { it.startsWith("ended") })
        assertFalse(c.calls.hasCalls)
        // On the next session the phone's call.list shows the call still live: it comes back as a started call.
        phone.setCall("c3", "active")   // queued on the dead wire: lost
        c.connect()
        assertTrue(c.sink.events.any { it == "started c3 active" })
    }
}

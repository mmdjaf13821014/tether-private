package app.tether.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair

class ProtocolTest {
    private val vectors: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("vectors.json")!!.readBytes().toString(Charsets.UTF_8)
        JSONObject(text)
    }

    private fun JSONObject.hex(k: String) = Bytes.unhex(getString(k))

    private fun keyPair(priv: ByteArray, pub: ByteArray) = KeyPair(P256.decode(pub), P256.privateFromScalar(priv))

    private fun single(hex: ByteArray): Record {
        val r = RecordReader(); r.append(hex)
        return r.next()!!.also { assertEquals(0, r.pendingBytes) }
    }

    @Test fun peripheralMatchesSwiftVectors() {
        val hs = vectors.getJSONArray("handshakes")
        assertEquals(2, hs.length())
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            val sP = P256.privateFromScalar(h.hex("s_P_priv"))
            val eP = keyPair(h.hex("e_P_priv"), h.hex("E_P"))
            val token = h.getString("token").takeIf { it.isNotEmpty() }?.let(Bytes::unhex)
            val p = PeripheralHandshake(sP, eP, h.hex("n_P"))

            val ack = p.receiveHello(single(h.hex("hello_record")), h.hex("S_C"), token)
            assertArrayEquals(h.hex("hello_ack_record"), ack.encoded())
            assertArrayEquals(h.hex("th"), p.keys!!.th)
            assertArrayEquals(h.hex("k_c2p"), p.keys!!.c2p)
            assertArrayEquals(h.hex("k_p2c"), p.keys!!.p2c)

            val (session, fin) = p.receiveFinish(single(h.hex("fin_c2p_record")))
            assertArrayEquals(h.hex("fin_p2c_record"), fin.encoded())

            val (ch, payload) = session.open(single(h.hex("c2p_ctr1_record")))
            assertArrayEquals(h.hex("c2p_ctr1_plain"), byteArrayOf(ch.toByte()) + payload)
            val plain = h.hex("p2c_ctr1_plain")
            val sealed = session.seal(plain[0].toInt() and 0xff, plain.copyOfRange(1, plain.size))
            assertArrayEquals(h.hex("p2c_ctr1_record"), sealed.encoded())
            assertEquals("ping", Message.parse(payload).t)
        }
    }

    @Test fun centralMatchesSwiftVectors() {
        val h = vectors.getJSONArray("handshakes").getJSONObject(0)
        val sC = keyPair(h.hex("s_C_priv"), h.hex("S_C"))
        val eC = keyPair(h.hex("e_C_priv"), h.hex("E_C"))
        val c = CentralHandshake(sC, h.hex("S_P"), h.hex("token"), eC, h.hex("n_C"))
        assertArrayEquals(h.hex("hello_record"), c.hello().encoded())
        val (s, fin) = c.receive(single(h.hex("hello_ack_record")))
        assertArrayEquals(h.hex("fin_c2p_record"), fin.encoded())
        c.verifyFinish(s, single(h.hex("fin_p2c_record")))
        assertArrayEquals(h.hex("dh1"), P256.ecdh(eC.private, h.hex("E_P")))
    }

    @Test fun loopbackAndReplay() {
        val sP = P256.generate(); val sC = P256.generate()
        val token = ByteArray(16) { 7 }
        val c = CentralHandshake(sC, P256.encode(sP.public), token)
        val p = PeripheralHandshake(sP.private)
        val (cs, fin) = c.receive(p.receiveHello(c.hello(), null, token))
        val (ps, pfin) = p.receiveFinish(fin)
        c.verifyFinish(cs, pfin)
        repeat(50) {
            val r = cs.seal(0, "m$it".toByteArray())
            assertEquals("m$it", String(ps.open(r).second))
        }
        val r = cs.seal(0, "x".toByteArray())
        ps.open(r)
        try { ps.open(r); fail("replay accepted") } catch (e: SessionException) { assertTrue(e.message!!.contains("counter")) }
        val t = cs.seal(0, "y".toByteArray())
        t.body[t.body.size - 1] = (t.body[t.body.size - 1].toInt() xor 1).toByte()
        try { ps.open(t); fail("tamper accepted") } catch (e: SessionException) { assertEquals("auth failed", e.message) }
    }

    @Test fun rejects() {
        val sP = P256.generate(); val sC = P256.generate()
        val p = PeripheralHandshake(sP.private)
        val resume = CentralHandshake(sC, P256.encode(sP.public), null)
        assertEquals(Record(Kind.REJECT, byteArrayOf(2)), p.receiveHello(resume.hello(), P256.encode(P256.generate().public), null))
        val pair = CentralHandshake(sC, P256.encode(sP.public), ByteArray(16))
        assertEquals(Record(Kind.REJECT, byteArrayOf(3)), PeripheralHandshake(sP.private).receiveHello(pair.hello(), null, null))
        // Wrong token: finish fails.
        val p2 = PeripheralHandshake(sP.private)
        val c2 = CentralHandshake(sC, P256.encode(sP.public), ByteArray(16) { 1 })
        val (_, fin) = c2.receive(p2.receiveHello(c2.hello(), null, ByteArray(16) { 2 }))
        try { p2.receiveFinish(fin); fail() } catch (e: SessionException) { }
    }

    @Test fun framingChunks() {
        val recs = (0 until 40).map { Record(it % 4 + 1, ByteArray(it * 53) { b -> b.toByte() }) }
        val stream = recs.fold(ByteArray(0)) { a, r -> a + r.encoded() }
        val reader = RecordReader()
        val got = ArrayList<Record>()
        var i = 0; var step = 1
        while (i < stream.size) {
            val n = minOf(step, stream.size - i)
            reader.append(stream, i, n); got += reader.drain(); i += n; step = step % 89 + 2
        }
        assertEquals(recs, got)
    }

    @Test fun pairingCodeVector() {
        val pc = vectors.getJSONObject("pairing_code")
        val code = PairingCode.parse(pc.getString("string"))!!
        assertArrayEquals(pc.hex("S_P"), code.peripheralKey)
        assertArrayEquals(pc.hex("token"), code.token)
        assertEquals(pc.getString("name"), code.deviceName)
        assertEquals(pc.getString("string"), code.encode())
        assertEquals(pc.getString("link"), code.link())
        assertNull(PairingCode.parse("tether1:zz"))
    }

    /** The QR code's link form (1.2) and the old `tether1:` form (1.1 phones, typed codes) both parse. */
    @Test fun pairingLinkForms() {
        val pc = vectors.getJSONObject("pairing_code")
        val body = pc.getString("string").removePrefix(PairingCode.PREFIX)
        for (s in listOf(pc.getString("link"), " " + pc.getString("link") + "\n", "tether:pair?c=$body",
            "TETHER://PAIR?v=2&c=$body", pc.getString("string"))) {
            val c = PairingCode.parse(s) ?: throw AssertionError(s)
            assertArrayEquals(pc.hex("S_P"), c.peripheralKey)
            assertArrayEquals(pc.hex("token"), c.token)
            assertEquals(pc.getString("name"), c.deviceName)
        }
        assertTrue(PairingCode.isPairLink(pc.getString("link")))
        assertFalse(PairingCode.isPairLink(pc.getString("string")))
        for (bad in listOf("tether://pair?c=zz", "tether://pair?x=1", "tether://pair", "tether://pair?c=$body.x",
            "tether://dial?number=1", "https://example.com/?c=$body")) assertNull(bad, PairingCode.parse(bad))
    }

    @Test fun g711Vector() {
        val g = vectors.getJSONObject("g711")
        val pcmArr = g.getJSONArray("pcm")
        val pcm = ShortArray(pcmArr.length()) { pcmArr.getInt(it).toShort() }
        assertEquals(g.getString("ulaw"), Bytes.hex(G711.encode(pcm)))
        val dec = g.getJSONArray("decoded")
        val d = G711.decode(G711.encode(pcm))
        for (k in d.indices) assertEquals(dec.getInt(k), d[k].toInt())
    }

    @Test fun messagesAndPaging() {
        val m = Message(T.SMS_SEND, JSONObject().put("to", "123").put("body", "سلام"), id = 4)
        val back = Message.parse(m.toJson())
        assertEquals("sms.send", back.t); assertEquals(4, back.id); assertEquals("سلام", back.b.getString("body"))
        val pages = paginate((1..100).toList(), 1000) { 90 }
        assertEquals(10, pages.size); assertEquals(100, pages.sumOf { it.size })
        assertEquals(1, paginate(emptyList<Int>(), 10) { 1 }.size)
        val f = AudioFrame(AudioFrame.OPUS, 65535, byteArrayOf(1, 2))
        val pf = AudioFrame.parse(f.encoded())!!
        assertEquals(65535, pf.seq); assertArrayEquals(byteArrayOf(1, 2), pf.payload)
    }
}

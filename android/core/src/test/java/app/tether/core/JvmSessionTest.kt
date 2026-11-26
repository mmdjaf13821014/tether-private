package app.tether.core

import app.tether.audio.OpusCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The library on a plain JVM (no Android): a paired session, a message each way and an Opus frame. */
class JvmSessionTest {
    @Test fun sessionOnPlainJvm() {
        val phoneKey = P256.generate()
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val code = PairingCode.parse(PairingCode(P256.encode(phoneKey.public), token, "Phone").encode())!!
        val client = CentralHandshake(P256.generate(), code.peripheralKey, code.token)
        val phone = PeripheralHandshake(phoneKey.private)
        val ack = phone.receiveHello(client.hello(), { false }, token)
        assertEquals(Kind.HELLO_ACK, ack.kind)
        val (cs, cFin) = client.receive(ack)
        val (ps, pFin) = phone.receiveFinish(cFin)
        client.verifyFinish(cs, pFin)

        val m = Message(T.STATUS, JSONObject().put("battery", 50))
        val (ch, body) = cs.open(ps.seal(Proto.CH_CONTROL, m.toJson()))
        assertEquals(Proto.CH_CONTROL, ch)
        assertEquals(50, Message.parse(body).b.getInt("battery"))

        val opus = OpusCodec()
        val packet = opus.encode(ShortArray(320) { (it * 50).toShort() }).single()
        val (ach, abody) = ps.open(cs.seal(Proto.CH_AUDIO, AudioFrame(AudioFrame.OPUS, 7, packet).encoded()))
        assertEquals(Proto.CH_AUDIO, ach)
        val f = AudioFrame.parse(abody)!!
        assertEquals(7, f.seq)
        assertTrue((OpusCodec().decode(f.payload)?.size ?: 0) == 320)
    }

    @Test fun wrongTokenIsRejected() {
        val phoneKey = P256.generate()
        val client = CentralHandshake(P256.generate(), P256.encode(phoneKey.public), ByteArray(16))
        val ack = PeripheralHandshake(phoneKey.private).receiveHello(client.hello(), { false }, ByteArray(16) { 1 })
        val (s, fin) = client.receive(ack)
        assertTrue(runCatching { PeripheralHandshake(phoneKey.private).receiveFinish(fin) }.isFailure)
        assertTrue(s.sendCounter == 1L)
    }
}

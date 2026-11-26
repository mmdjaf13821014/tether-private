package app.tether.helper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Helper link authentication (protocol §6): only the app that launched the helper gets call audio and radio control. */
class HelperAuthTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val h = ByteArray(16) { it.toByte() }
    private val a = ByteArray(16) { (100 + it).toByte() }

    /** Runs the app side and the helper side against each other over in-memory pipes. */
    private fun exchange(appToken: String, helperToken: String): Pair<Boolean, ByteArray?> {
        val toHelper = PipedOutputStream(); val helperIn = PipedInputStream(toHelper, 1 shl 16)
        val toApp = PipedOutputStream(); val appIn = PipedInputStream(toApp, 1 shl 16)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val helper = pool.submit<ByteArray?> {
                HelperAuth.helperVerify(DataInputStream(helperIn), DataOutputStream(toApp), helperToken, h)
                    ?.also { HelperAuth.writeProof(DataOutputStream(toApp), it) }
                    ?: run { HelperAuth.writeProof(DataOutputStream(toApp), ByteArray(32)); null }   // a refusing helper sends no valid proof
            }
            val ok = HelperAuth.appAuthenticate(DataInputStream(appIn), DataOutputStream(toHelper), appToken, a)
            return ok to helper.get(5, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    @Test fun sameTokenAuthenticatesBothWays() {
        val (ok, proof) = exchange(token, token)
        assertTrue(ok); assertNotNull(proof)
    }

    @Test fun wrongTokenIsRejectedByBothSides() {
        val (ok, proof) = exchange("ffffffffffffffffffffffffffffffff", token)
        assertNull(proof); assertFalse(ok)
    }

    @Test fun anImpostorHelperCannotProveItself() {
        // A helper with another token: the app refuses it even though the impostor would accept anything.
        val (ok, _) = exchange(token, "not the token")
        assertFalse(ok)
    }

    @Test fun macsMatchTheProtocolLabelsAndOrder() {
        // Fixed byte layout: AUTH = a ‖ HMAC(token, "tether-helper app" ‖ h ‖ a); PROOF = HMAC(token, "tether-helper helper" ‖ a ‖ h).
        val out = ByteArrayOutputStream()
        val appIn = DataInputStream(ByteArrayInputStream(byteArrayOf(HelperAuth.CHALLENGE.toByte(), 0, 0, 0, 16) + h +
            byteArrayOf(HelperAuth.PROOF.toByte(), 0, 0, 0, 32) + HelperAuth.mac(token, "tether-helper helper", a, h)))
        assertTrue(HelperAuth.appAuthenticate(appIn, DataOutputStream(out), token, a))
        val sent = out.toByteArray()
        assertEquals(HelperAuth.AUTH, sent[0].toInt()); assertEquals(48, sent[4].toInt())
        assertArrayEquals(a, sent.copyOfRange(5, 21))
        assertArrayEquals(HelperAuth.mac(token, "tether-helper app", h, a), sent.copyOfRange(21, 53))
        assertEquals(53, sent.size)   // the token itself never crosses the socket
        assertFalse(String(sent, Charsets.ISO_8859_1).contains(token))
    }

    private fun helperGets(vararg frames: ByteArray, ours: ByteArray = h): ByteArray? {
        val o = ByteArrayOutputStream()
        return runCatching { HelperAuth.helperVerify(DataInputStream(ByteArrayInputStream(frames.fold(ByteArray(0)) { x, y -> x + y })), DataOutputStream(o), token, ours) }.getOrNull()
    }

    private fun auth(challenge: ByteArray, nonce: ByteArray = a, tok: String = token) =
        byteArrayOf(HelperAuth.AUTH.toByte(), 0, 0, 0, 48) + nonce + HelperAuth.mac(tok, "tether-helper app", challenge, nonce)

    @Test fun helperAcceptsAValidAnswerOnly() {
        assertArrayEquals(HelperAuth.mac(token, "tether-helper helper", a, h), helperGets(auth(h)))
        assertNull(helperGets(auth(h, tok = "wrong")))
    }

    @Test fun aRecordedAnswerIsUselessAgainstANewChallenge() {
        val recorded = auth(h)
        assertNull(helperGets(recorded, ours = ByteArray(16) { 7 }))
    }

    @Test fun malformedAnswersAreRejected() {
        val good = auth(h)
        assertNull(helperGets(byteArrayOf(HelperAuth.PROOF.toByte()) + good.copyOfRange(1, good.size)))   // wrong frame type
        assertNull(helperGets(byteArrayOf(HelperAuth.AUTH.toByte(), 0, 0, 0, 47) + good.copyOfRange(5, good.size)))   // wrong length
        assertNull(helperGets(good.copyOfRange(0, 30)))   // cut short
        assertNull(helperGets())
    }

    @Test fun appSendsNothingToAListenerWithoutAChallenge() {
        val out = ByteArrayOutputStream()
        assertFalse(HelperAuth.appAuthenticate(DataInputStream(ByteArrayInputStream(byteArrayOf(1, 0, 0, 0, 16) + h)), DataOutputStream(out), token, a))
        assertEquals(0, out.size())
        assertFalse(HelperAuth.appAuthenticate(DataInputStream(ByteArrayInputStream(byteArrayOf(12, 0, 0, 0, 8) + h)), DataOutputStream(out), token, a))
        assertEquals(0, out.size())
    }
}

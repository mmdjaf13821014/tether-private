package app.tether.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 helpers: X9.63 uncompressed encoding, ECDH, HKDF. */
object P256 {
    val params: ECParameterSpec by lazy {
        val p = AlgorithmParameters.getInstance("EC")
        p.init(ECGenParameterSpec("secp256r1"))
        p.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    fun encode(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return Bytes.concat(byteArrayOf(4), fixed(w.affineX), fixed(w.affineY))
    }

    fun decode(x963: ByteArray): PublicKey {
        if (x963.size != 65 || x963[0] != 4.toByte()) throw HandshakeException("bad public key")
        val x = BigInteger(1, x963.copyOfRange(1, 33))
        val y = BigInteger(1, x963.copyOfRange(33, 65))
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (e: Exception) {
            throw HandshakeException("bad public key: ${e.message}")
        }
    }

    fun privateFromScalar(s: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, s), params))

    /** ECDH; works with software keys and AndroidKeyStore keys (PURPOSE_AGREE_KEY). */
    fun ecdh(priv: PrivateKey, peer: ByteArray): ByteArray {
        val ka = if (priv.javaClass.name.contains("android", ignoreCase = true) && priv.javaClass.name.contains("keystore", ignoreCase = true))
            KeyAgreement.getInstance("ECDH", "AndroidKeyStore") else KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(decode(peer), true)
        return ka.generateSecret()
    }

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }
}

class HandshakeException(msg: String, val reject: Int? = null) : Exception(msg)
class SessionException(msg: String) : Exception(msg)

object Proto {
    const val VERSION = 1
    const val MODE_RESUME = 0
    const val MODE_PAIR = 1
    const val HELLO_LEN = 148
    const val HELLO_ACK_LEN = 82
    const val CH_CONTROL = 0x00
    const val CH_AUDIO = 0x01
    const val CH_FINISH = 0xFF
    val TH_LABEL = "TETHER-HS-1".toByteArray()
    val HKDF_INFO = "tether/1 keys".toByteArray()
}

class SessionKeys(val th: ByteArray, val c2p: ByteArray, val p2c: ByteArray)

object KeySchedule {
    fun transcript(hello: ByteArray, ack: ByteArray) = P256.sha256(Proto.TH_LABEL, hello, ack)

    fun derive(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, psk: ByteArray, th: ByteArray): SessionKeys {
        val okm = P256.hkdf(th, Bytes.concat(dh1, dh2, dh3, psk), Proto.HKDF_INFO, 64)
        return SessionKeys(th, okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }
}

class Hello(val mode: Int, val ephemeral: ByteArray, val nonce: ByteArray, val staticKey: ByteArray) {
    fun encoded() = Bytes.concat(byteArrayOf(Proto.VERSION.toByte(), mode.toByte()), ephemeral, nonce, staticKey)

    companion object {
        fun parse(b: ByteArray): Hello {
            if (b.size != Proto.HELLO_LEN) throw HandshakeException("bad hello length")
            if (b[0].toInt() != Proto.VERSION) throw HandshakeException("bad version", Reject.BAD_VERSION)
            val mode = b[1].toInt()
            if (mode != Proto.MODE_PAIR && mode != Proto.MODE_RESUME) throw HandshakeException("bad mode")
            return Hello(mode, b.copyOfRange(2, 67), b.copyOfRange(67, 83), b.copyOfRange(83, 148))
        }
    }
}

/** AES-256-GCM record layer with strict per-direction counters (protocol §4). Thread-safe. */
class SecureSession(sendKey: ByteArray, recvKey: ByteArray) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val recvKey = SecretKeySpec(recvKey, "AES")
    private val sendLock = Any()
    private val recvLock = Any()
    var sendCounter = 0L; private set
    var recvCounter = 0L; private set

    fun seal(channel: Int, payload: ByteArray): Record = synchronized(sendLock) {
        if (sendCounter >= (1L shl 32)) throw SessionException("session exhausted")
        val ctr = sendCounter++
        sealWith(sendKey, ctr, byteArrayOf(channel.toByte()) + payload)
    }

    /** Returns (channel, payload). Throws on any counter mismatch or auth failure. */
    fun open(r: Record): Pair<Int, ByteArray> = synchronized(recvLock) {
        if (r.kind != Kind.SEALED) throw SessionException("not sealed")
        val b = r.body
        if (b.size < 8 + 16 + 1) throw SessionException("too short")
        val ctr = Bytes.readBe64(b, 0)
        if (ctr != recvCounter) throw SessionException("counter mismatch: expected $recvCounter got $ctr")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(ctr)))
        c.updateAAD(aad(ctr))
        val plain = try {
            c.doFinal(b, 8, b.size - 8)
        } catch (e: Exception) {
            throw SessionException("auth failed")
        }
        recvCounter++
        (plain[0].toInt() and 0xff) to plain.copyOfRange(1, plain.size)
    }

    companion object {
        fun nonce(ctr: Long) = ByteArray(4) + Bytes.be64(ctr)
        fun aad(ctr: Long) = byteArrayOf(Kind.SEALED.toByte()) + Bytes.be64(ctr)

        fun sealWith(key: SecretKeySpec, ctr: Long, plain: ByteArray): Record {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(ctr)))
            c.updateAAD(aad(ctr))
            return Record(Kind.SEALED, Bytes.be64(ctr) + c.doFinal(plain))
        }
    }
}

/**
 * Peripheral (Android) side of the handshake.
 * [staticKey] may be an AndroidKeyStore key. [staticPublic] is its X9.63 encoding.
 */
class PeripheralHandshake(
    private val staticKey: PrivateKey,
    private val ephemeral: KeyPair = P256.generate(),
    private val nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
) {
    var hello: Hello? = null; private set
    var keys: SessionKeys? = null; private set
    private var session: SecureSession? = null

    /**
     * @param paired the stored central static key, or null
     * @param token the open pairing token, or null
     * @return HELLO_ACK or REJECT
     */
    fun receiveHello(r: Record, paired: ByteArray?, token: ByteArray?): Record =
        receiveHello(r, { k -> paired != null && paired.contentEquals(k) }, token)

    /** [isKnown] says whether a static key belongs to one of the paired centrals (iPhone, Mac…). */
    fun receiveHello(r: Record, isKnown: (ByteArray) -> Boolean, token: ByteArray?): Record =
        receiveHello(r, isKnown) { token }

    /** [tokenFor] gives the pairing token a pair-mode HELLO from that static key must prove (null: pairing closed). */
    fun receiveHello(r: Record, isKnown: (ByteArray) -> Boolean, tokenFor: (ByteArray) -> ByteArray?): Record {
        if (r.kind != Kind.HELLO) throw HandshakeException("expected HELLO")
        val h = try {
            Hello.parse(r.body)
        } catch (e: HandshakeException) {
            return Record(Kind.REJECT, byteArrayOf((e.reject ?: Reject.BAD_VERSION).toByte()))
        }
        hello = h
        val psk = when (h.mode) {
            Proto.MODE_RESUME -> {
                if (!isKnown(h.staticKey)) {
                    return Record(Kind.REJECT, byteArrayOf(Reject.UNKNOWN_DEVICE.toByte()))
                }
                ByteArray(0)
            }
            else -> tokenFor(h.staticKey) ?: return Record(Kind.REJECT, byteArrayOf(Reject.PAIRING_CLOSED.toByte()))
        }
        val ack = Bytes.concat(byteArrayOf(Proto.VERSION.toByte()), P256.encode(ephemeral.public), nonce)
        val dh1 = P256.ecdh(ephemeral.private, h.ephemeral)
        val dh2 = P256.ecdh(staticKey, h.ephemeral)
        val dh3 = P256.ecdh(ephemeral.private, h.staticKey)
        val th = KeySchedule.transcript(r.body, ack)
        val k = KeySchedule.derive(dh1, dh2, dh3, psk, th)
        keys = k
        session = SecureSession(k.p2c, k.c2p)
        return Record(Kind.HELLO_ACK, ack)
    }

    /** Verifies the central's FIN; returns the session and our FIN. */
    fun receiveFinish(r: Record): Pair<SecureSession, Record> {
        val s = session ?: throw HandshakeException("no hello")
        val k = keys!!
        val (ch, payload) = s.open(r)
        if (ch != Proto.CH_FINISH || !payload.contentEquals(k.th)) throw HandshakeException("finish mismatch")
        return s to s.seal(Proto.CH_FINISH, k.th)
    }
}

/** Central side; used by tests and the Mac-side tooling. */
class CentralHandshake(
    private val staticKey: KeyPair,
    private val peerStatic: ByteArray,
    private val token: ByteArray?,
    private val ephemeral: KeyPair = P256.generate(),
    private val nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
) {
    lateinit var helloBody: ByteArray; private set
    var keys: SessionKeys? = null; private set

    fun hello(): Record {
        val mode = if (token == null) Proto.MODE_RESUME else Proto.MODE_PAIR
        helloBody = Hello(mode, P256.encode(ephemeral.public), nonce, P256.encode(staticKey.public)).encoded()
        return Record(Kind.HELLO, helloBody)
    }

    fun receive(r: Record): Pair<SecureSession, Record> {
        if (r.kind == Kind.REJECT) throw HandshakeException("rejected", r.body.firstOrNull()?.toInt())
        if (r.kind != Kind.HELLO_ACK || r.body.size != Proto.HELLO_ACK_LEN) throw HandshakeException("bad ack")
        val eP = r.body.copyOfRange(1, 66)
        val dh1 = P256.ecdh(ephemeral.private, eP)
        val dh2 = P256.ecdh(ephemeral.private, peerStatic)
        val dh3 = P256.ecdh(staticKey.private, eP)
        val th = KeySchedule.transcript(helloBody, r.body)
        val k = KeySchedule.derive(dh1, dh2, dh3, token ?: ByteArray(0), th)
        keys = k
        val s = SecureSession(k.c2p, k.p2c)
        return s to s.seal(Proto.CH_FINISH, th)
    }

    fun verifyFinish(s: SecureSession, r: Record) {
        val (ch, p) = s.open(r)
        if (ch != Proto.CH_FINISH || !p.contentEquals(keys!!.th)) throw HandshakeException("finish mismatch")
    }
}

/**
 * Pairing code `tether1:<S_P>.<token>.<name>`; the QR code holds its link form `tether://pair?c=<S_P>.<token>.<name>`
 * (since 1.2: the iPhone's Camera app offers to open it in the app). [parse] accepts both.
 */
data class PairingCode(val peripheralKey: ByteArray, val token: ByteArray, val deviceName: String) {
    private fun body() = Bytes.b64url(peripheralKey) + "." + Bytes.b64url(token) + "." +
        Bytes.b64url(deviceName.toByteArray(Charsets.UTF_8))

    fun encode() = PREFIX + body()

    /** The link form, for the QR code. */
    fun link() = LINK_PREFIX + body()

    companion object {
        const val PREFIX = "tether1:"
        const val LINK_PREFIX = "tether://pair?c="

        fun isPairLink(s: String): Boolean {
            val t = s.trim().lowercase()
            return t.startsWith("tether://pair?") || t.startsWith("tether:pair?") || t.startsWith("tether://pair/?")
        }

        fun parse(s: String): PairingCode? {
            val t = s.trim()
            if (isPairLink(t)) {
                val q = t.substringAfter('?')
                val c = q.split('&').map { it.split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0] == "c" } ?: return null
                return parseBody(try { java.net.URLDecoder.decode(c[1], "UTF-8") } catch (e: IllegalArgumentException) { return null })
            }
            if (!t.startsWith(PREFIX)) return null
            return parseBody(t.removePrefix(PREFIX))
        }

        private fun parseBody(body: String): PairingCode? {
            val parts = body.split(".")
            if (parts.size != 3) return null
            return try {
                val k = Bytes.unb64url(parts[0])
                val tok = Bytes.unb64url(parts[1])
                if (k.size != 65 || k[0] != 4.toByte() || tok.size != 16) null
                else PairingCode(k, tok, String(Bytes.unb64url(parts[2]), Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

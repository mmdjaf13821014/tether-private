package app.tether.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * The helper link's mutual challenge-response (protocol §6), both sides. The helper sends a 16-byte challenge; the
 * app answers with its own nonce and HMAC-SHA256(token, "tether-helper app" ‖ challenge ‖ nonce); the helper proves
 * itself with HMAC(token, "tether-helper helper" ‖ nonce ‖ challenge). The token never crosses the socket.
 */
object HelperAuth {
    const val AUTH = 10; const val CHALLENGE = 12; const val PROOF = 14
    private const val APP = "tether-helper app"
    private const val HELPER = "tether-helper helper"

    fun mac(token: String, label: String, a: ByteArray, b: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256"))
            update(label.toByteArray()); update(a); update(b); doFinal()
        }

    /**
     * Helper side, up to the app's proof: sends [ours] as the challenge and checks the answer. Returns the PROOF
     * frame's payload to send once the caller is ready, or null when the peer did not prove it knows [token].
     */
    fun helperVerify(input: DataInputStream, o: DataOutputStream, token: String, ours: ByteArray): ByteArray? {
        o.writeByte(CHALLENGE); o.writeInt(ours.size); o.write(ours); o.flush()
        if (input.readUnsignedByte() != AUTH) return null
        if (input.readInt() != 48) return null
        val theirs = ByteArray(16).also { input.readFully(it) }
        val got = ByteArray(32).also { input.readFully(it) }
        if (!MessageDigest.isEqual(got, mac(token, APP, ours, theirs))) return null
        return mac(token, HELPER, theirs, ours)
    }

    fun writeProof(o: DataOutputStream, proof: ByteArray) { o.writeByte(PROOF); o.writeInt(proof.size); o.write(proof); o.flush() }

    /** App side: true when the listener sent a challenge and then proved it knows [token]; nothing is sent to an impostor before its challenge. */
    fun appAuthenticate(input: DataInputStream, o: DataOutputStream, token: String, ours: ByteArray): Boolean {
        if (input.readUnsignedByte() != CHALLENGE || input.readInt() != 16) return false
        val theirs = ByteArray(16).also { input.readFully(it) }
        o.writeByte(AUTH); o.writeInt(48); o.write(ours); o.write(mac(token, APP, theirs, ours)); o.flush()
        if (input.readUnsignedByte() != PROOF || input.readInt() != 32) return false
        val proof = ByteArray(32).also { input.readFully(it) }
        return MessageDigest.isEqual(proof, mac(token, HELPER, ours, theirs))
    }
}

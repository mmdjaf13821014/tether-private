package app.tether.link

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.tether.core.P256
import app.tether.core.PairingCode
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Long-term identity. The static P-256 key lives in the Android Keystore (PURPOSE_AGREE_KEY,
 * non-exportable). If the Keystore cannot do ECDH on this device we fall back to a software key
 * in app-private storage.
 */
class Keys(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Volatile private var pairingToken: ByteArray? = null
    @Volatile var pairingExpiry = 0L; private set

    val staticPrivate: PrivateKey by lazy { loadOrCreate().first }
    val staticPublic: ByteArray by lazy { loadOrCreate().second }

    private var cached: Pair<PrivateKey, ByteArray>? = null

    @Synchronized private fun loadOrCreate(): Pair<PrivateKey, ByteArray> {
        cached?.let { return it }
        val result = if (prefs.getBoolean("software_key", false)) software() else try {
            keystore().also { (priv, _) ->
                // Prove ECDH works with this key before relying on it.
                P256.ecdh(priv, P256.encode(P256.generate().public))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore ECDH unavailable, using a software key", t)
            prefs.edit().putBoolean("software_key", true).apply()
            software()
        }
        cached = result
        return result
    }

    private fun keystore(): Pair<PrivateKey, ByteArray> {
        if (!ks.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build())
            g.generateKeyPair()
        }
        val priv = ks.getKey(ALIAS, null) as PrivateKey
        val pub = ks.getCertificate(ALIAS).publicKey
        // Re-encode through a plain KeyFactory so P256.encode sees a standard ECPublicKey.
        val std = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub.encoded))
        return priv to P256.encode(std)
    }

    private fun software(): Pair<PrivateKey, ByteArray> {
        val stored = prefs.getString("sw_priv", null)
        if (stored != null) {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP)))
            return priv to Base64.decode(prefs.getString("sw_pub", "")!!, Base64.NO_WRAP)
        }
        val kp = P256.generate()
        val pub = P256.encode(kp.public)
        prefs.edit().putString("sw_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("sw_pub", Base64.encodeToString(pub, Base64.NO_WRAP)).apply()
        return kp.private to pub
    }

    /** One paired client (the iPhone): its static P-256 key and a display name. */
    data class Central(val key: ByteArray, val name: String, val kind: String = "iphone") {
        val id: String get() = Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** All paired clients. Migrates the single-central format of earlier versions. */
    @Synchronized fun centrals(): List<Central> {
        prefs.getString("central", null)?.let { legacy ->
            val name = prefs.getString("central_name", null) ?: "iPhone"
            prefs.edit().remove("central").remove("central_name").apply()
            save(listOf(Central(Base64.decode(legacy, Base64.NO_WRAP), name)))
        }
        val arr = org.json.JSONArray(prefs.getString("centrals", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Central(Base64.decode(o.getString("key"), Base64.NO_WRAP), o.optString("name", "iPhone"), o.optString("kind", "iphone"))
        }
    }

    private fun save(list: List<Central>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("key", it.id).put("name", it.name).put("kind", it.kind)) }
        prefs.edit().putString("centrals", arr.toString()).apply()
    }

    fun isKnown(key: ByteArray) = centrals().any { it.key.contentEquals(key) }
    fun central(key: ByteArray?) = key?.let { k -> centrals().firstOrNull { it.key.contentEquals(k) } }

    /** Adds (or renames) a client. Pairing a second device never forgets the first. */
    @Synchronized fun addCentral(key: ByteArray, name: String, kind: String = "iphone") =
        save(centrals().filterNot { it.key.contentEquals(key) } + Central(key, name, kind))

    @Synchronized fun updateCentral(key: ByteArray, name: String, kind: String) {
        val list = centrals()
        if (list.none { it.key.contentEquals(key) && (it.name != name || it.kind != kind) }) return
        save(list.map { if (it.key.contentEquals(key)) Central(key, name, kind) else it })
    }

    @Synchronized fun removeCentral(key: ByteArray) = save(centrals().filterNot { it.key.contentEquals(key) })

    /** Tests snapshot and restore the list verbatim. */
    fun snapshot(): String? { centrals(); return prefs.getString("centrals", null) }
    fun restore(s: String?) = prefs.edit().apply { if (s == null) remove("centrals") else putString("centrals", s) }.apply()

    val isPaired get() = centrals().isNotEmpty()

    fun openPairing(): PairingCode {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        pairingToken = token
        pairingExpiry = System.currentTimeMillis() + PAIRING_TTL_MS
        return PairingCode(staticPublic, token, deviceName())
    }

    /** The open pairing code again (same token), e.g. to show it in another form; null once it expired. */
    fun openPairingCurrent(): PairingCode? = currentToken()?.let { PairingCode(staticPublic, it, deviceName()) }

    fun currentToken(): ByteArray? =
        pairingToken?.takeIf { System.currentTimeMillis() < pairingExpiry }

    fun closePairing() {
        pairingToken = null
        pairingExpiry = 0
    }

    private val repeatPairing = app.tether.core.PairingTokens(REPEAT_PAIR_MS)

    /** A pairing completed: the code is closed, but the same central may repeat its pair hello for a while. */
    fun completePairing(centralKey: ByteArray) {
        currentToken()?.let { repeatPairing.completed(it, centralKey) }
        closePairing()
    }

    /**
     * The token a pair-mode HELLO from [centralKey] must prove: the open pairing code's, or, for 10 minutes after a
     * pairing completed, that pairing's token for the same central only. The central may not have received our FIN
     * (the link moved or dropped right then) and says hello in pair mode again; refusing it made it forget the pairing.
     */
    fun tokenFor(centralKey: ByteArray): ByteArray? = repeatPairing.tokenFor(centralKey, currentToken())

    fun deviceName(): String = android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
        ?: Build.MODEL

    companion object {
        const val TAG = "Tether"
        const val ALIAS = "tether_static_p256"
        const val PAIRING_TTL_MS = 5 * 60 * 1000L
        const val REPEAT_PAIR_MS = 10 * 60_000L
    }
}

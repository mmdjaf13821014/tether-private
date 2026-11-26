package app.tether.client.link

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.tether.core.P256
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * This client's long-term identity and its pairing: the static P-256 key (in the Android Keystore with
 * PURPOSE_AGREE_KEY on Android 12+, else a software key in app-private storage), the Android phone's static key and
 * name, and the Bluetooth address it was last reached at (a first guess; the service scan finds it otherwise).
 */
class Identity(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val keyPair: KeyPair by lazy { loadOrCreate() }

    private fun loadOrCreate(): KeyPair {
        if (Build.VERSION.SDK_INT >= 31 && !prefs.getBoolean("software_key", false)) {
            try {
                return keystore().also { kp -> P256.ecdh(kp.private, P256.encode(P256.generate().public)) }   // prove ECDH works
            } catch (t: Throwable) {
                Log.w(TAG, "Keystore ECDH unavailable, using a software key", t)
                prefs.edit().putBoolean("software_key", true).apply()
            }
        }
        return software()
    }

    private fun keystore(): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
            }.generateKeyPair()
        }
        val priv = ks.getKey(ALIAS, null) as PrivateKey
        // Re-encode through a plain KeyFactory so P256.encode sees a standard ECPublicKey.
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ks.getCertificate(ALIAS).publicKey.encoded))
        return KeyPair(pub, priv)
    }

    private fun software(): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        val priv = prefs.getString("sw_priv", null); val pub = prefs.getString("sw_pub", null)
        if (priv != null && pub != null) return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
            kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))))
        val kp = P256.generate()
        prefs.edit().putString("sw_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("sw_pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)).apply()
        return kp
    }

    var peerKey: ByteArray?
        get() = prefs.getString("peer", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) { prefs.edit().apply { if (v == null) remove("peer") else putString("peer", Base64.encodeToString(v, Base64.NO_WRAP)) }.apply() }

    var peerName: String?
        get() = prefs.getString("peer_name", null)
        set(v) { prefs.edit().putString("peer_name", v).apply() }

    var lastAddress: String?
        get() = prefs.getString("address", null)
        set(v) { prefs.edit().putString("address", v).apply() }

    val isPaired get() = peerKey != null

    companion object {
        const val TAG = "TetherClient"
        const val ALIAS = "tether_client_p256"
    }
}

/**
 * The last few hundred link events (connects, drops and their reasons, setup steps, call events), kept across launches
 * so a drop can be explained afterwards (Link › Link log), like the iPhone's LinkJournal. No numbers or texts go in.
 */
class LinkJournal(dir: File) {
    private val file = File(dir, "link-log.txt")
    private val lines = ArrayList<String>()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "journal").apply { isDaemon = true } }
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    init { runCatching { lines.addAll(file.readLines().takeLast(MAX)) } }

    fun add(event: String) {
        Log.i(Identity.TAG, event)
        val line = "${synchronized(fmt) { fmt.format(java.util.Date()) }} $event"
        synchronized(lines) { lines.add(line); while (lines.size > MAX) lines.removeAt(0) }
        io.execute { runCatching { file.writeText(synchronized(lines) { lines.joinToString("\n", postfix = "\n") }) } }
    }

    /** Newest first. */
    val text: String get() = synchronized(lines) { lines.reversed().joinToString("\n") }

    companion object { const val MAX = 400 }
}

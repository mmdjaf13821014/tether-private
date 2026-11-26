package app.tether.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.tether.R
import app.tether.TetherApp
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADB client to the phone's own adbd over Wireless debugging (TLS, loopback/Wi-Fi), via libadb-android.
 * Our RSA key + self-signed certificate are what adbd remembers after the one-time pairing.
 */
class Adb private constructor(private val ctx: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(ctx.filesDir, "adb_key.pk8")
    private val certFile = File(ctx.filesDir, "adb_cert.der")
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
        if (!keyFile.exists() || !certFile.exists()) generate()
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
    }

    private fun generate() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Tether")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000),
            Date(now + 20L * 365 * 86_400_000), name, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val c = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(builder.build(signer))
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(c.encoded)
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "tether"

    /** Pairs with the 6-digit code shown in Settings › Wireless debugging › Pair device with pairing code. */
    fun pairWithCode(code: String): Boolean {
        val (host, port) = findPairingService()
            ?: throw IllegalStateException("pairing service not found; keep the pairing dialog open")
        if (!pairAt(host, port, code)) throw IllegalStateException("wrong or expired pairing code")
        return true
    }

    /** The pairing service Settings announces while "Pair device with pairing code" is open (mDNS, up to 15 s). */
    fun findPairingService(): Pair<String, Int>? = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15)

    /** False when adbd refused the code (wrong, or the dialog was closed and it expired). */
    fun pairAt(host: String, port: Int, code: String): Boolean {
        Log.i(TAG, "pairing with the pairing service on port $port")
        return pair(host, port, code.trim())
    }

    @Synchronized fun ensureConnected(): Boolean {
        if (isConnected) return true
        val prefs = (ctx as? TetherApp)?.prefs
        val legacy = prefs?.getBoolean(PREF_LEGACY, false) ?: false
        val port = prefs?.getInt(PREF_LEGACY_PORT, ConnectOrder.DEFAULT_LEGACY_PORT) ?: ConnectOrder.DEFAULT_LEGACY_PORT
        val how = connectInOrder(object : AdbEndpoints {
            override fun connectTls() = autoConnect(ctx, 10_000)
            override fun connectLegacy(port: Int) = connect("127.0.0.1", port)
        }, ConnectOrder.tlsSupported(Build.VERSION.SDK_INT), legacy, port) { Log.i(TAG, it) }
        if (how == "legacy") Log.i(TAG, "adb connected over TCP mode on port $port")
        return how != null
    }

    /** Runs a shell command and returns its output (stdout + stderr). */
    @Synchronized fun shell(cmd: String): String {
        if (!ensureConnected()) throw IllegalStateException("adb not connected")
        // "shell,raw:" runs without a PTY, so a backgrounded helper is not hung up when the stream closes.
        openStream("shell,raw:$cmd").use { s ->
            val input = s.openInputStream()
            val out = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (e: Exception) { -1 }
                if (n < 0) break
                out.append(String(buf, 0, n))
            }
            return out.toString()
        }
    }

    private fun discover(type: String, timeoutSec: Long): Pair<String, Int>? {
        var found: Pair<String, Int>? = null
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, type) { addr: InetAddress?, port: Int ->
            if (addr != null && port > 0) { found = (addr.hostAddress ?: "127.0.0.1") to port; latch.countDown() }
        }
        mdns.start()
        latch.await(timeoutSec, TimeUnit.SECONDS)
        mdns.stop()
        return found
    }

    companion object {
        const val TAG = "Tether"
        /** Opt-in legacy TCP adb (`adb tcpip`): the setting and its port (default 5555). */
        const val PREF_LEGACY = "adb_legacy"
        const val PREF_LEGACY_PORT = "adb_legacy_port"
        @Volatile private var instance: Adb? = null
        fun get(ctx: Context): Adb = instance ?: synchronized(this) {
            instance ?: Adb(ctx.applicationContext).also { instance = it }
        }

        /** Drops the current connection (it may be hung on a restarted adbd); the next get() starts fresh. */
        fun reset() {
            val old = synchronized(this) { instance.also { instance = null } } ?: return
            Thread({ runCatching { old.close() } }, "adb-close").apply { isDaemon = true }.start()
        }

        fun hasSecureSettings(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Turns Wireless debugging on (needs WRITE_SECURE_SETTINGS and Wi-Fi). */
        fun enableWirelessDebugging(ctx: Context): Boolean = try {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
        } catch (e: SecurityException) {
            false
        }

        /** After pairing: grant ourselves what we need through adb once. */
        fun bootstrap(ctx: Context): String {
            val adb = get(ctx)
            val pkg = ctx.packageName
            val sb = StringBuilder()
            sb.append(adb.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1"))
            for (p in listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
                "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS") +
                (if (Build.VERSION.SDK_INT >= 31) listOf("BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE") else emptyList()) +
                (if (Build.VERSION.SDK_INT >= 33) listOf("POST_NOTIFICATIONS") else emptyList())) {
                sb.append(adb.shell("pm grant $pkg android.permission.$p 2>&1"))
            }
            sb.append(adb.shell("cmd deviceidle whitelist +$pkg 2>&1"))
            sb.append(allowSmsWrite(ctx))
            return sb.toString()
        }

        /**
         * Lets Tether mark messages as read although it is not the default SMS app: the WRITE_SMS app-op,
         * which only the shell may set. Cheap and idempotent, so it is repeated whenever the helper starts.
         */
        fun allowSmsWrite(ctx: Context): String = try {
            get(ctx).shell("appops set ${ctx.packageName} WRITE_SMS allow 2>&1")
        } catch (t: Throwable) {
            "appops: ${t.message}"
        }

        // ------------------------------------------------------------------ notification flow

        const val NOTIF_ADB = 30
        const val KEY_CODE = "code"

        fun showPairingNotification(ctx: Context, text: String = ctx.getString(R.string.adb_notif_text)) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(ctx.getString(R.string.adb_notif_hint)).build()
            val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AdbPairingReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val action = Notification.Action.Builder(null, ctx.getString(R.string.adb_notif_action), pi)
                .addRemoteInput(input).build()
            val n = Notification.Builder(ctx, TetherApp.CH_SETUP)
                .setSmallIcon(R.drawable.ic_tether)
                .setContentTitle(ctx.getString(R.string.adb_notif_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .addAction(action)
                .setOngoing(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ADB, n)
        }

        fun updateNotification(ctx: Context, text: String, done: Boolean) {
            if (!done) { showPairingNotification(ctx, text); return }
            val n = Notification.Builder(ctx, TetherApp.CH_SETUP).setSmallIcon(R.drawable.ic_tether)
                .setContentTitle(ctx.getString(R.string.adb_notif_title)).setContentText(text).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ADB, n)
        }
    }
}

/** The notification's code field. Only checks the code and hands the work to [AdbPairing] (never blocks the broadcast). */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Adb.KEY_CODE)?.toString()
        val code = AdbPairing.normalizeCode(typed)
        // Always answer the reply: a notification that is not posted again keeps its spinner forever.
        if (code == null) { AdbPairing.show(context, AdbPairing.Result.BAD_CODE, null); return }
        AdbPairing.start(context, code)
    }
}

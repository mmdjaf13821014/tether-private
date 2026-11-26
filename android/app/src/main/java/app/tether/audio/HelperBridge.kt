package app.tether.audio

import android.content.Context
import android.util.Log
import app.tether.adb.Adb
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * App side of the shell-helper link (protocol §6). SELinux does not let the shell domain connect to an
 * app's Unix socket, so the helper (uid 2000) listens on 127.0.0.1:<port> and we connect to it. The
 * helper and we prove to each other that we know the random token passed on its command line (HMAC challenge-
 * response, protocol §6); the token itself never crosses the socket, and a new one is made for every launch.
 */
class HelperBridge(private val ctx: Context) {
    companion object {
        const val TAG = "Tether"
        const val HELLO = 1; const val START = 2; const val STOP = 3; const val DOWN = 4; const val UP = 5
        const val INFO = 6; const val PROBE = 7; const val QUIT = 8; const val SELFTEST = 9; const val AUTH = 10
        const val NET = 11; const val CHALLENGE = 12; const val PROOF = 14; const val LOCAL = 15; const val CALL = 16; const val DIAG = 17
        private const val PREF_BLOCKED = "audio_blocked_fingerprint"
        /** Largest frame we accept from the helper (a 20 ms PCM frame is 640 bytes; JSON infos are small). */
        const val MAX_FRAME = 1 shl 20

        /** Call-audio level by Android version, see [audioLevel]. */
        fun audioLevelFor(sdk: Int) = when {
            sdk >= 33 -> "full"
            sdk >= 30 -> "fallback"
            else -> "unavailable"
        }
    }

    private val prefs = ctx.getSharedPreferences("helper", Context.MODE_PRIVATE)
    /** The running helper's port: a new one each launch, so an app squatting on it cannot keep call audio off. */
    @Volatile var port: Int = prefs.getInt("port", 0).takeIf { it > 0 } ?: newPort()
        private set

    private fun newPort(): Int = (47000 + SecureRandom().nextInt(2000)).also { prefs.edit().putInt("port", it).apply() }
    /** The running helper's token (set when we launch it; a new one each launch). */
    @Volatile private var token: String = prefs.getString("token", null) ?: newToken()

    private fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        .also { prefs.edit().putString("token", it).apply() }

    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile var hello: JSONObject? = null; private set
    @Volatile var lastInfo: JSONObject? = null; private set
    /** Standing-delay figures of the last bridged call (capture backlog, inject queue, drops). */
    @Volatile var lastStats: JSONObject? = null; private set
    /** The helper's uplink report (mic mute, uplink check), from the start INFO or a later `{uplink}` INFO; null between calls. */
    @Volatile var lastUplink: JSONObject? = null; private set
    /** The helper's report on the phone's own mic in this call (INFO `mic`: setting, path, redirect mode); null between calls. */
    @Volatile var lastMic: JSONObject? = null; private set
    /** Every mic report (also the one inside the start INFO). */
    @Volatile var onMic: ((JSONObject) -> Unit)? = null
    @Volatile var onDown: ((ShortArray) -> Unit)? = null
    /** A call's start INFO (methods, mutes, errors). */
    @Volatile var onInfo: ((JSONObject) -> Unit)? = null
    /** A path-watch finding during a call (INFO `path`: `event`, `note`). */
    @Volatile var onPath: ((JSONObject) -> Unit)? = null
    /** A diagnostics snapshot the helper took by itself (INFO `{autodiag, trigger, testLine}`: a dead path, a call's end). */
    @Volatile var onDiag: ((JSONObject) -> Unit)? = null
    /**
     * Id of the last START sent; its start INFO echoes it. [lastInfo] is cleared with every START, so until this call's
     * paths are open (the helper waits for the call's audio mode) nothing of the previous call is taken for this one's.
     */
    @Volatile var startId = 0; private set
    /** Every uplink report (also the one inside the start INFO). */
    @Volatile var onUplink: ((JSONObject) -> Unit)? = null
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var pending: Pair<String, CompletableFuture<JSONObject>>? = null

    val connected get() = sock != null && hello != null

    /** Kept for symmetry with the service start-up; tries to attach to an already running helper. */
    fun listen() {
        Thread({ tryConnect() }, "helper-connect").start()
    }

    @Synchronized fun tryConnect(): Boolean {
        if (sock != null) return true
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 500)
            s.tcpNoDelay = true
            s.soTimeout = 1500
            val input = DataInputStream(s.getInputStream().buffered())
            val o = DataOutputStream(s.getOutputStream().buffered())
            // Whoever listens must first send a challenge and then prove it knows the token: nothing is sent to an
            // impostor that took the port, and the token never goes on the wire.
            val ours = ByteArray(16).also { SecureRandom().nextBytes(it) }
            if (!app.tether.helper.HelperAuth.appAuthenticate(input, o, token, ours)) {
                Log.w(TAG, "the listener on the helper port could not prove itself: not using it"); s.close(); return false
            }
            s.soTimeout = 0
            sock = s
            out = o
            // Downlink audio is encoded on this thread: urgent-audio priority keeps it on a fast core
            // (at default priority a call in the background measured 6.4 ms per Opus frame instead of ~2 ms).
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); readLoop(s, input) }, "helper-rx").start()
            repeat(20) { if (hello != null) return true; Thread.sleep(50) }
            hello != null
        } catch (e: Exception) {
            false
        }
    }

    private fun readLoop(c: Socket, input: DataInputStream) {
        try {
            while (true) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len !in 0..MAX_FRAME) throw java.io.IOException("helper frame of $len bytes")
                val p = ByteArray(len).also { input.readFully(it) }
                when (type) {
                    HELLO -> { hello = JSONObject(String(p)); Log.i(TAG, "helper connected: $hello"); onChange?.invoke() }
                    DOWN -> {
                        val s = ShortArray(len / 2) { (p[2 * it].toInt() and 0xff or (p[2 * it + 1].toInt() shl 8)).toShort() }
                        onDown?.invoke(s)
                    }
                    INFO -> {
                        val o = JSONObject(String(p))
                        val pend = pending
                        if (o.has("autodiag")) {
                            Log.i(TAG, "helper diagnostics snapshot: ${o.optString("trigger")}")
                            onDiag?.invoke(o)
                        } else if (pend != null && o.has(pend.first)) {
                            pending = null
                            pend.second.complete(o.getJSONObject(pend.first))
                        } else if (o.has("stats")) {
                            lastStats = o.getJSONObject("stats")
                            lastUplink = null; lastMic = null
                            Log.i(TAG, "helper call-audio stats: $lastStats")
                        } else if (o.has("capture") || o.has("inject")) {
                            if (o.has("id") && o.optInt("id", -1) != startId) { Log.i(TAG, "start info of an older start dropped"); continue }
                            lastInfo = o
                            lastUplink = o.optJSONObject("uplink")
                            lastMic = o.optJSONObject("mic")
                            Log.i(TAG, "helper info: $o")
                            onInfo?.invoke(o)
                            lastUplink?.let { u -> onUplink?.invoke(u) }
                            lastMic?.let { m -> onMic?.invoke(m) }
                            onChange?.invoke()
                        } else if (o.has("path")) {
                            o.optJSONObject("path")?.let { pth -> Log.i(TAG, "helper path: $pth"); onPath?.invoke(pth) }
                        } else if (o.has("uplink") || o.has("mic")) {
                            o.optJSONObject("uplink")?.let { u -> lastUplink = u; Log.i(TAG, "helper uplink: $u"); onUplink?.invoke(u) }
                            o.optJSONObject("mic")?.let { m -> lastMic = m; Log.i(TAG, "helper mic: $m"); onMic?.invoke(m) }
                            onChange?.invoke()
                        } else {
                            // A reply that came after its request timed out: never mistaken for the audio methods.
                            Log.i(TAG, "late helper reply dropped: ${o.keys().asSequence().toList()}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "helper disconnected: ${e.message}")
        } finally {
            if (sock === c) {
                sock = null; out = null; hello = null; lastInfo = null; lastUplink = null; lastMic = null
                // Whoever waits for an answer from this helper stops waiting now (not after its 20-25 s timeout).
                pending?.second?.completeExceptionally(java.io.IOException("helper gone")); pending = null
            }
            runCatching { c.close() }
            onChange?.invoke()
        }
    }

    private fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val o = out ?: return false
        return try {
            synchronized(o) { o.writeByte(type); o.writeInt(payload.size); o.write(payload); o.flush() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * [testLine]: the call is Tether's own test line (no network behind it): the helper plays the far end's listener.
     * [mic]: the phone-mic setting (MicPath.Setting: auto, mute, open); [micMute]: with "mute", whether to mute this
     * call (see LocalMutes); [micCheck]: then watch the uplink; [redirect]: what this phone remembers of the redirect modes.
     */
    fun start(rate: Int, capture: String = "auto", inject: String = "auto", injectMs: Int = 40, testLine: Boolean = false,
              mic: String = "auto", micMute: Boolean = false, micCheck: Boolean = true, redirect: JSONObject = JSONObject(),
              muteMemory: String? = null, active: Boolean = true): Boolean {
        val id = ++startId
        lastInfo = null; lastUplink = null; lastMic = null
        return send(START, JSONObject().put("id", id).put("rate", rate).put("capture", capture).put("inject", inject).put("injectMs", injectMs)
            .put("testLine", testLine).put("mic", mic).put("micMute", micMute).put("micCheck", micCheck).put("redirect", redirect)
            .put("muteMemory", muteMemory ?: JSONObject.NULL).put("active", active)
            .toString().toByteArray())
    }

    /** The phone-mic setting changed during a bridged call. */
    fun local(mic: String, micMute: Boolean, micCheck: Boolean, redirect: JSONObject = JSONObject(), muteMemory: String? = null) =
        send(LOCAL, JSONObject().put("mic", mic).put("micMute", micMute).put("micCheck", micCheck).put("redirect", redirect)
            .put("muteMemory", muteMemory ?: JSONObject.NULL).toString().toByteArray())

    /** The bridged call is now active (connected) or no longer: the helper passes the uplink only while it is. */
    fun call(active: Boolean) = send(CALL, JSONObject().put("active", active).toString().toByteArray())

    fun stop() = send(STOP)

    fun up(pcm: ShortArray): Boolean {
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { b[2 * i] = pcm[i].toInt().toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        return send(UP, b)
    }

    fun quit() = send(QUIT)

    /**
     * Network switches (mobile data, hotspot, Bluetooth tethering). [what] null only reads the state.
     * Returns {"data","hotspot","bt"[,"err"]} or null when the helper is not reachable.
     */
    private val requestLock = Any()

    fun net(what: String? = null, on: Boolean = false, sub: Int? = null, extra: JSONObject? = null): JSONObject? {
        // Its own lock, not the bridge's monitor: a slow switch must never hold up tryConnect / audio control.
        synchronized(requestLock) {
            val f = CompletableFuture<JSONObject>()
            pending = "net" to f
            val req = (extra ?: JSONObject()).apply { if (what != null) put("what", what).put("on", on); sub?.let { put("sub", it) } }
            if (!send(NET, req.toString().toByteArray())) { pending = null; return null }
            return try { f.get(25, TimeUnit.SECONDS) } catch (e: Exception) { pending = null; null }
        }
    }

    /** Sends PROBE / SELFTEST / DIAG (with [body], e.g. `{methods, micMute}`) and waits for the matching INFO. */
    fun request(kind: String, timeoutSec: Long = 20, body: JSONObject? = null): JSONObject? {
        synchronized(requestLock) {
            val f = CompletableFuture<JSONObject>()
            pending = kind to f
            val type = when (kind) { "probe" -> PROBE; "diag" -> DIAG; else -> SELFTEST }
            if (!send(type, body?.toString()?.toByteArray() ?: ByteArray(0))) { pending = null; return null }
            return try { f.get(timeoutSec, TimeUnit.SECONDS) } catch (e: Exception) { pending = null; null }
        }
    }

    /**
     * Shell command that starts the helper detached from the ADB stream. The subshell + setsid detach it
     * into its own session; the trailing sleep keeps the ADB shell (and its process group) alive until
     * that has happened, otherwise adbd can kill the child while the stream closes.
     */
    fun launchCommand(): String {
        val apk = ctx.applicationInfo.sourceDir
        // Any older helper (previous install or token) is stopped first so the new one can take the port. The class
        // name is spelled through a variable so pkill never matches this launching shell itself.
        return "pkill -f 'app[.]tether[.]helper[.]HelperMain serve' 2>/dev/null; sleep 0.3; " +
            "(M=HelperMain; setsid sh -c \"CLASSPATH=$apk exec app_process / app.tether.helper.\$M serve $port $token\" " +
            "</dev/null >/data/local/tmp/tether-helper.log 2>&1 &); sleep 1; echo launched"
    }

    /** A helper started from an older APK keeps running old code after an update: replace it. */
    val isStale: Boolean get() {
        val h = hello ?: return false
        return h.optString("apk") != ctx.applicationInfo.sourceDir
    }

    private val launching = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "helper-launch").apply { isDaemon = true } }
    @Volatile private var lastError = "helper did not connect"
    @Volatile private var nextAttemptAt = 0L
    @Volatile private var failures = 0

    /**
     * Starts (or replaces) the helper in the background and returns at once. Never blocks the caller:
     * an ADB session to an adbd that has just restarted (USB re-plug, Wireless debugging toggled) can
     * hang, and that must not stall call audio control. Failed launches back off up to 30 s.
     */
    fun kick() {
        if (connected && !isStale) return
        if (System.currentTimeMillis() < nextAttemptAt) return
        if (!launching.compareAndSet(false, true)) return
        pool.execute {
            try {
                val err = launch()
                if (err == null) { failures = 0; nextAttemptAt = 0 }
                else {
                    failures++
                    lastError = err
                    nextAttemptAt = System.currentTimeMillis() + minOf(30_000L, 3000L * failures)
                    Log.w(TAG, "helper launch failed ($failures): $err")
                }
            } finally {
                launching.set(false)
                onChange?.invoke()
            }
        }
    }

    /** Waits (bounded) for a running helper; null when it is up, otherwise the reason. */
    fun ensureRunning(waitMs: Long = 25_000): String? {
        if ((connected || tryConnect()) && !isStale) return null
        nextAttemptAt = 0
        kick()
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            if (connected && !isStale) return null
            if (!launching.get()) break
            Thread.sleep(100)
        }
        return if (connected && !isStale) null else if (launching.get()) "starting" else lastError
    }

    private fun launch(): String? {
        if ((connected || tryConnect()) && isStale) {
            Log.i(TAG, "helper is from an older install (${hello?.optString("apk")}); restarting it")
            quit()
            repeat(20) { if (!connected) return@repeat; Thread.sleep(100) }
            runCatching { sock?.close() }
            sock = null; out = null; hello = null
            Thread.sleep(300)
        }
        if (connected || tryConnect()) return null
        var last = "helper did not connect"
        token = newToken()   // a fresh token for every launch
        port = newPort()
        for (attempt in 1..3) {
            val f = pool.submit<String> { Adb.get(ctx).shell(launchCommand()) }
            try {
                f.get(12, TimeUnit.SECONDS)
                repeat(25) { if (tryConnect()) return null; Thread.sleep(200) }
            } catch (t: Throwable) {
                // Timeout or a dead session: throw the connection away so the next attempt starts clean.
                f.cancel(true)
                val cause = (t as? java.util.concurrent.ExecutionException)?.cause ?: t
                last = if (t is java.util.concurrent.TimeoutException) "adb session timed out" else (cause.message ?: cause.javaClass.simpleName)
                Log.w(TAG, "helper launch attempt $attempt failed: $last")
            }
            Adb.reset()
            Thread.sleep(1500L * attempt)
        }
        return last
    }

    fun state(): JSONObject {
        val info = lastInfo
        val u = lastUplink
        return JSONObject()
            .put("state", if (connected) "running" else "stopped")
            .apply { info?.optString("inject")?.takeIf { it.isNotEmpty() && it != "null" }?.let { put("method", it) } }
            .apply { info?.optString("capture")?.takeIf { it.isNotEmpty() && it != "null" }?.let { put("capture", it) } }
            .put("up", info?.optString("inject")?.let { it.isNotEmpty() && it != "null" } ?: false)
            .put("down", info?.optString("capture")?.let { it.isNotEmpty() && it != "null" } ?: false)
            .put("audio", audioLevel())
            // A redirect mode took the call off the phone: fully silent. After the uplink check undid the mic mute (or a
            // redirect mode was undone), the phone is no longer fully silent.
            .apply { info?.optString("silent")?.takeIf { it.isNotEmpty() }?.let { put("silent", silentNow(it, u, lastMic)) } }
    }

    /** Status `silent` from the start INFO's [atStart] and the latest uplink / mic reports. */
    private fun silentNow(atStart: String, u: JSONObject?, mic: JSONObject?): String = when {
        atStart == "none" && mic?.optString("path") != "off" -> "none"
        mic?.optString("path") == "off" -> "full"
        u?.optBoolean("micMuted", false) == true -> "full"
        else -> if (atStart == "none") "none" else "earpiece"
    }

    /**
     * "full" on Android 13+ (interception API), "fallback" on 11-12 (voice-call capture, incall-music inject),
     * "unavailable" on 8-10, "blocked" when this phone's software gave Tether no call audio in a real call
     * ([audioBlocked]).
     */
    fun audioLevel(): String = if (audioBlocked()) "blocked" else audioLevelFor(android.os.Build.VERSION.SDK_INT)

    /**
     * This firmware gives Tether no call audio (the downlink capture read only zeros in a real call, issue #13):
     * remembered by build fingerprint, so a system update or a self-test that hears call audio clears it.
     */
    fun audioBlocked(): Boolean = app.tether.link.AudioOwner.blocked(prefs.getString(PREF_BLOCKED, null), blockedKey())
    fun rememberAudioBlocked() = prefs.edit().putString(PREF_BLOCKED, blockedKey()).apply()

    /** The firmware and this Tether build: a system update or a new Tether version tries call audio again (a fault
     *  of Tether's own could look like a phone that gives none, as on the A73 before 1.2.4, issue #13). */
    private fun blockedKey(): String {
        val code = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)
        return "${android.os.Build.FINGERPRINT}|$code"
    }
    fun forgetAudioBlocked() { if (prefs.contains(PREF_BLOCKED)) prefs.edit().remove(PREF_BLOCKED).apply() }
}

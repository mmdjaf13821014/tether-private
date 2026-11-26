package app.tether.helper

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Entry point run as the shell user:
 *
 *   CLASSPATH=$(pm path app.tether.android | cut -d: -f2) app_process / app.tether.helper.HelperMain <cmd>
 *
 * Commands: `serve <port> <token>` (talks to the app over 127.0.0.1), `probe`, `selftest`,
 * `record <method> <seconds> <file.pcm>`, `tone <method> <seconds>`, `silence [on|off [mic]]`, `mode [<n> [ms]]`.
 */
object HelperMain {
    const val VERSION = 3   // 3: mutual challenge-response instead of a token on the wire

    object F {
        const val HELLO = 1; const val START = 2; const val STOP = 3; const val DOWN = 4; const val UP = 5
        const val INFO = 6; const val PROBE = 7; const val QUIT = 8; const val SELFTEST = 9; const val AUTH = 10; const val CHALLENGE = 12; const val PROOF = 14
        const val NET = 11; const val LOCAL = 15
        /** App → helper `{"active": bool}`: the bridged call is (no longer) active; the uplink flows only while it is. */
        const val CALL = 16
        /** App → helper `{logcat?}`: call-audio diagnostics for a problem report (answers INFO `{diag: …}`, see Diag). */
        const val DIAG = 17
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            ShellEnv.init()
            when (args.firstOrNull() ?: "serve") {
                "probe" -> println(CallAudio.probe().toString(2))
                "diag" -> println(Diag.full(null).toString(2))
                "selftest" -> println(CallAudio.selfTest().toString(2))
                "record" -> record(args[1], args[2].toInt(), args[3])
                "tone" -> tone(args[1], args[2].toInt())
                "param" -> { // param <key=value>... : set vendor audio parameters, then read each key back
                    for (kv in args.drop(1)) {
                        if ('=' in kv) ShellEnv.audioManager.setParameters(kv)
                        val k = kv.substringBefore('=')
                        println("$k -> '${ShellEnv.audioManager.getParameters(k)}'")
                    }
                }
                "mode" -> mode(args.getOrNull(1)?.toInt(), args.getOrNull(2)?.toLong() ?: 1000)
                "silence" -> { // silence [on|off [mic]] : the phone's own call silencing (CallAudio.Silence) without a call, for testing
                    println("silence mode: ${CallAudio.silence.name.lowercase()}")
                    if (args.size >= 2) println(CallAudio.localCallAudio(args[1] == "on", args.getOrNull(2) == "mic", JSONArray()))
                }
                "net" -> { // net [data|hotspot|bt on|off] : flip a network switch, then print the state
                    if (args.size >= 3 && args[1] == "datasub") println("data SIM ${args[2]}: ${Net.setDataSub(args[2].toInt()) ?: "ok"}")
                    else if (args.size >= 4 && args[1] == "rat") println("slot ${args[2]} → ${args[3]}: ${Net.setRat(args[2].toInt(), args[3]) ?: "ok"}")
                    else if (args.size >= 3) println("set ${args[1]} ${args[2]}: ${Net.set(args[1], args[2] == "on") ?: "ok"}")
                    if (args.size >= 3) Thread.sleep(2500)
                    println(Net.state())
                }
                "serve" -> Server(args[1].toInt(), args[2]).serveForever()
                else -> { System.err.println("unknown command"); exitProcess(2) }
            }
            exitProcess(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            exitProcess(1)
        }
    }

    private fun record(method: String, secs: Int, path: String) {
        val rate = 16000
        val c = CallAudio.openCapture(method, rate)
        val buf = ShortArray(rate / 50)
        FileOutputStream(File(path)).use { out ->
            val bytes = ByteArray(buf.size * 2)
            repeat(secs * 50) {
                val n = c.read(buf)
                for (i in 0 until maxOf(0, n)) { bytes[2 * i] = buf[i].toInt().toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                if (n > 0) out.write(bytes, 0, n * 2)
            }
        }
        c.stop()
        println("recorded $secs s via $method to $path")
    }

    /**
     * `mode [<n> [ms]]`: prints the audio mode and whether call screening mode is declared; with [n], asks for audio
     * mode n (MicPath.Mode: 4 call screening, 5 call redirect, 6 communication redirect) as the shell for [ms], opens the
     * interception record and track in it, prints every mode change meanwhile, and withdraws the request.
     */
    private fun mode(n: Int?, ms: Long) {
        val am = ShellEnv.audioManager
        println("mode ${MicPath.Mode.name(am.mode)}, call screening mode declared: ${CallAudio.screeningSupported()}, interceptable: ${CallAudio.isInterceptable()}")
        if (n == null) return
        // Opened first, in the mode the phone is in (as in a call: the helper opens them in the call's own mode).
        val cap = runCatching { CallAudio.openCapture("intercept", 16000) }.onFailure { println("capture intercept: ${CallAudio.describe(it)}") }.getOrNull()
        val inj = runCatching { CallAudio.openInject("intercept", 16000) }.onFailure { println("inject intercept: ${CallAudio.describe(it)}") }.getOrNull()
        val t0 = System.nanoTime()
        val ok = CallAudio.requestMode(n)
        println("setMode(${MicPath.Mode.name(n)}): ${if (ok) "in it" else "NOT in it (mode ${MicPath.Mode.name(am.mode)})"} after ${(System.nanoTime() - t0) / 1_000_000} ms")
        cap?.let { c -> val b = ShortArray(320); var nz = 0; var r = 0; repeat(10) { val k = c.read(b); if (k > 0) { r += k; nz += CallAudio.nonZero(b, k) } }; println("capture intercept in it: read $r, non-zero $nz") }
        inj?.let { i -> repeat(10) { i.write(ShortArray(320)) }; println("inject intercept in it: routed ${i.routedTo()}, played ${i.playedFrames()}") }
        var last = am.mode
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { val m = am.mode; if (m != last) { println("mode changed to ${MicPath.Mode.name(m)}"); last = m }; Thread.sleep(50) }
        CallAudio.requestMode(MicPath.Mode.NORMAL)
        Thread.sleep(200)
        println("released: mode ${MicPath.Mode.name(am.mode)}")
        cap?.stop(); inj?.stop()
    }

    private fun tone(method: String, secs: Int) {
        val rate = 16000
        val i = CallAudio.openInject(method, rate)
        println("routed to ${i.routedTo()}")
        repeat(secs * 50) { i.write(CallAudio.tone(800.0, rate, rate / 50, phase0 = it * rate / 50)) }
        i.stop()
    }

    /**
     * Listens on 127.0.0.1:[port]. SELinux forbids shell → app Unix sockets, so the app connects to us.
     * Mutual challenge-response (protocol §6): we send a random challenge, the peer answers with its own nonce and
     * HMAC-SHA256(token, "app" ‖ ours ‖ its), we prove ourselves with HMAC(token, "helper" ‖ its ‖ ours). The token
     * (known only to the app that launched us) never crosses the socket. Each connection authenticates on its own
     * thread with a short timeout, so nothing that merely connects can keep the app out.
     */
    class Server(private val port: Int, private val token: String) {
        @Volatile private lateinit var out: DataOutputStream
        /** The iPhone's voice on its way to the inject track (see UplinkBuffer); a new one per call. */
        @Volatile private var up = UplinkBuffer()
        @Volatile private var capture: Capture? = null
        @Volatile private var inject: Inject? = null
        @Volatile private var running = false
        private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        /** Unblock the audio threads' blocking reads and writes (stop() runs them before joining, issue #15). */
        private val halts = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        /**
         * start / stop / the mic setting / a session taking over all hold this (issue #15: they raced). Never held by
         * the audio threads themselves while stop() joins them, except through [tryLockFor].
         */
        private val audioLock = java.util.concurrent.locks.ReentrantLock()
        /** Bumped by every start and stop: an audio thread of an older call stops on its own. */
        @Volatile private var callGen = 0
        @Volatile private var localMuted = false
        /** How this call mutes the phone's microphone (see LocalMutes); the uplink check may undo it. */
        @Volatile private var plan = LocalMutes.Plan.NONE
        @Volatile private var check: UplinkCheck? = null
        /** The mute was put back after its check found VOICE_UPLINK blind (the vendor key is trusted, LocalMutes). */
        @Volatile private var keptBlind = false
        /** Bumped whenever a check is started or abandoned: an older check thread stops on its own. */
        @Volatile private var checkGen = 0
        private val meter = InjectMeter()
        /** Downlink frames and their non-zero samples since the last take (RedirectGuard's downlink check). */
        private val downMeter = DownMeter()
        /** The same for the path watch. */
        private val watchMeter = DownMeter()
        /** The user's "phone mic during bridged calls" and what this phone model remembers of the redirect modes and the mute. */
        @Volatile private var mic = MicPath.Setting.AUTO
        @Volatile private var redirectMemory: Map<MicPath.Redirect, MicPath.Verdict> = emptyMap()
        @Volatile private var muteMemory: LocalMutes.Memory? = null
        /** An app that predates `muteMemory` said not to mute (setting "mute" after the mute broke the uplink). */
        @Volatile private var legacyNoMute = false
        @Volatile private var testLine = false
        /** This call's redirect audio mode (null: none), and whether we asked Android for a mode (withdrawn at stop). */
        @Volatile private var guard: RedirectGuard? = null
        @Volatile private var modeHeld = false
        @Volatile private var redirectGen = 0
        /** With the default setting: the vendor mute is the next layer once the redirect mode is off for good. */
        @Volatile private var fallbackPending = false
        /** Guards [plan] changes from the check thread against the session thread (never the object's monitor, see sendLock). */
        private val planLock = Any()
        /** The call's audio paths are open (the open thread finished); until then a mic change is only noted. */
        @Volatile private var pathsOpen = false

        /**
         * The app says whether the bridged call is active (connected). Before that the iPhone's voice is dropped: a real
         * modem takes nothing from the uplink while dialing, so it only piled up (1 s ahead, 232 of 308 frames dropped
         * in one call, 2026-11-24). An app that never says counts as active from the start.
         */
        @Volatile private var callActive = true
        @Volatile private var activeSinceMs = -1L
        /** Bumped when the call becomes active: the up thread restarts its first-second figures. */
        @Volatile private var activeEpoch = 0
        /** The app's id of this START, echoed in its INFO (an INFO of an older START is never taken for this one's). */
        @Volatile private var startId: Any = JSONObject.NULL

        @Volatile private var current: Socket? = null

        fun serveForever() {
            // Reuse the address: a just-stopped helper's connections linger in TIME_WAIT on this port.
            val ss = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8) }
            ShellEnv.log("listening on 127.0.0.1:$port")
            // A helper that died mid-call would leave the phone muted for its next call: always start clean.
            CallAudio.restoreLocalCallAudio()
            // At most a few connections authenticating at once, each with one 2 s deadline for the whole exchange
            // (not per read). When all are taken the oldest is dropped, so a local app holding them open cannot lock
            // the real app out; it can only slow its connect a little.
            val pending = java.util.concurrent.Semaphore(4)
            val waiting = java.util.concurrent.ConcurrentLinkedDeque<Socket>()
            val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auth-deadline").apply { isDaemon = true } }
            while (true) {
                val s = ss.accept()
                if (!pending.tryAcquire()) {
                    waiting.pollFirst()?.let { runCatching { it.close() } }
                    if (!pending.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { runCatching { s.close() }; continue }
                }
                waiting.addLast(s)
                val released = java.util.concurrent.atomic.AtomicBoolean(false)
                val authDone = { if (released.compareAndSet(false, true)) { waiting.remove(s); pending.release() } }
                // Claims the slot itself, so it can never close a connection that authenticated at the same moment.
                deadlines.schedule({ if (released.compareAndSet(false, true)) { waiting.remove(s); pending.release(); runCatching { s.close() } } },
                    2, java.util.concurrent.TimeUnit.SECONDS)
                Thread({
                    var quit = false
                    try {
                        s.tcpNoDelay = true
                        quit = session(s, authDone)
                    } catch (e: Exception) {
                        ShellEnv.log("session ended: $e")
                    } finally {
                        // Only the session that owns the audio may stop it (a newer one may already have started).
                        withAudio { if (current === s) { stop(); current = null } }
                        runCatching { s.close() }
                        authDone()
                    }
                    if (quit) exitProcess(0)
                }, "session").start()
            }
        }

        private inline fun <T> withAudio(f: () -> T): T { audioLock.lock(); try { return f() } finally { audioLock.unlock() } }

        /** For an audio thread: the audio lock, or false once its call ([gen]) is over (stop() may be joining it). */
        private fun tryLockFor(gen: Int): Boolean {
            while (running && callGen == gen) if (audioLock.tryLock(20, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (running && callGen == gen) return true
                audioLock.unlock(); return false
            }
            return false
        }

        /** Returns true when the app asked us to quit. */
        private fun session(s: Socket, authDone: () -> Unit): Boolean {
            val input = DataInputStream(s.getInputStream().buffered())
            val o = DataOutputStream(s.getOutputStream().buffered())
            s.soTimeout = 2000
            val ours = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val proof = HelperAuth.helperVerify(input, o, token, ours) ?: run { ShellEnv.log("bad proof"); return false }
            authDone()   // before PROOF: the deadline or an eviction must never close an authenticated connection
            HelperAuth.writeProof(o, proof)
            s.soTimeout = 0
            // Authenticated: this connection takes over (an older one is closed and stops nothing of ours).
            withAudio { current?.let { old -> if (old !== s) runCatching { old.close() } }; current = s; stop(); out = o }
            send(F.HELLO, JSONObject().put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid())
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("version", VERSION)
                .put("apk", System.getProperty("java.class.path") ?: "").toString().toByteArray())
            while (true) {
                val type = try { input.readUnsignedByte() } catch (e: Exception) { return false }
                val len = input.readInt()
                if (len < 0 || len > 1 shl 20) return false
                val payload = ByteArray(len).also { input.readFully(it) }
                when (type) {
                    F.START -> start(JSONObject(String(payload)))
                    F.STOP -> withAudio { stop() }
                    F.UP -> {
                        if (!callActive) { stats.upBeforeActive++; continue }
                        val sh = ShortArray(len / 2) { (payload[2 * it].toInt() and 0xff or (payload[2 * it + 1].toInt() shl 8)).toShort() }
                        // Frames arrive in bursts (Bluetooth connection events carry 0-3, more after a hold-up): the
                        // buffer keeps them and steers the delay ahead of each frame (queue + track) in quiet frames.
                        up.push(sh, System.nanoTime(), inject?.pendingFrames() ?: -1)
                    }
                    F.CALL -> setActive(runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject()).optBoolean("active", true))
                    F.PROBE -> { withAudio { stop() }; info(JSONObject().put("probe", CallAudio.probe())) }
                    // Never stops a call's audio: it only looks (dumpsys takes a second or two, so on its own thread).
                    F.DIAG -> {
                        val req = runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject())
                        Thread({ info(JSONObject().put("diag", Diag.full(ownState(), logcat = req.optBoolean("logcat")))) }, "diag").start()
                    }
                    F.SELFTEST -> { withAudio { stop() }; info(JSONObject().put("selftest", selfTest(runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject())))) }
                    F.LOCAL -> setMicMute(runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject()))
                    F.NET -> {   // {"what": "data"|"hotspot"|"bt", "on": bool} or {} to just read the state
                        val req = runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject())
                        Thread({
                            val err = when {
                                req.optString("what") == "dataSub" -> Net.setDataSub(req.optInt("sub", -1))
                                req.optString("what") == "rat" -> Net.setRat(req.optInt("slot", -1), req.optString("mode"))
                                req.has("what") -> Net.set(req.optString("what"), req.optBoolean("on"))
                                else -> null
                            }
                            if (req.has("what")) Thread.sleep(1500)
                            info(JSONObject().put("net", Net.state().apply { err?.let { put("err", it) } }))
                        }, "net").start()
                    }
                    F.QUIT -> return true
                }
            }
        }

        /** Its own lock: a takeover holds the audio lock while stop() joins the audio threads, which may be
         *  sending right then (on that lock they could not finish, and every join waited its full timeout). */
        private val sendLock = Any()
        private fun send(type: Int, payload: ByteArray) = synchronized(sendLock) {
            out.writeByte(type); out.writeInt(payload.size); out.write(payload); out.flush()
        }

        private fun info(o: JSONObject) = runCatching { send(F.INFO, o.toString().toByteArray()) }

        /** F.CALL: the bridged call became active (connected) or stopped being so (hold, ended). */
        private fun setActive(active: Boolean) {
            if (active == callActive) return
            callActive = active
            if (active) {
                activeSinceMs = System.currentTimeMillis()
                // What the iPhone said while dialing never reaches anyone: start the uplink and its figures afresh.
                up.clear()
                stats.activated()
                activeEpoch++
                ShellEnv.log("call active: uplink starts now")
            } else activeSinceMs = -1
        }

        private fun activeForMs(): Long = if (callActive && activeSinceMs > 0) System.currentTimeMillis() - activeSinceMs else -1

        private fun start(cfg: JSONObject) = withAudio {
            stop()
            val rate = cfg.optInt("rate", 16000)
            this.rate = rate
            val errors = JSONArray()
            if (android.os.Build.VERSION.SDK_INT < CallAudio.MIN_SDK_AUDIO) errors.put("call audio is not available: it needs Android 11 or later")
            val capOrder = cfg.optString("capture", "auto").let { if (it == "auto") CallAudio.captureMethods else listOf(it) }
            val injOrder = UplinkRoutes.fixedOrAuto(cfg.optString("inject", "auto"), CallAudio.injectMethods)
            val injectMs = cfg.optInt("injectMs", 40)
            startId = cfg.opt("id") ?: JSONObject.NULL
            callActive = cfg.optBoolean("active", true)
            activeSinceMs = if (callActive) System.currentTimeMillis() else -1
            up = UplinkBuffer(frame = rate / 50, rate = rate)
            running = true
            pathsOpen = false
            val gen = ++callGen
            localMuted = true
            // The call is bridged to the iPhone: silence the Android phone's own earpiece always (the microphone is
            // decided once the paths are open, see applyMic). The downlink is still captured, the injected uplink still
            // goes out.
            mic = MicPath.Setting.of(cfg.optString("mic").takeIf { cfg.has("mic") }, cfg.optBoolean("micMute", false))
            redirectMemory = MicPath.memoryOf(cfg.optJSONObject("redirect"))
            muteMemory = LocalMutes.Memory.of(cfg.optString("muteMemory").takeIf { cfg.has("muteMemory") })
            legacyNoMute = !cfg.has("muteMemory") && !cfg.optBoolean("micMute", true)
            testLine = cfg.optBoolean("testLine")
            plan = LocalMutes.Plan.NONE; keptBlind = false
            val local = CallAudio.localCallAudio(muted = true, mic = false, errors = errors)
            localNote = local.note; localOk = local.ok
            stats.reset()
            meter.take()
            downReads = 0; downSamples = 0; downNonZero = 0; downLiveReads = 0; callStartedAt = System.currentTimeMillis()
            // The paths open on their own thread: the session keeps reading (a STOP is never stuck behind a wait for
            // the call's audio mode, issue #15), and a START never queues up behind another.
            threads.add(Thread({
                val t0 = System.currentTimeMillis()
                val gate = OpenGate()
                // Nothing of the call's audio exists before the phone routes it (see OpenGate).
                val waitedMode = CallAudio.audioMode()
                while (running && callGen == gen && !gate.ready(CallAudio.audioMode(), activeForMs())) Thread.sleep(30)
                if (!running || callGen != gen) return@Thread
                fun <T> open(order: List<String>, kind: String, f: (String) -> T): T? {
                    for (m in order) {
                        while (running && callGen == gen) {
                            try { return f(m) } catch (t: Throwable) {
                                val msg = CallAudio.describe(t)
                                // Polled often: every ms here delays both directions' first audio.
                                if (gate.retry(m, msg, CallAudio.audioMode(), System.currentTimeMillis(), activeForMs())) { Thread.sleep(30); continue }
                                errors.put("$kind:$m $msg"); break
                            }
                        }
                    }
                    return null
                }
                val cap = open(capOrder, "capture") { CallAudio.openCapture(it, rate) }
                val inj = open(injOrder, "inject") { CallAudio.openInject(it, rate, injectMs) }
                if (!tryLockFor(gen)) { cap?.stop(); inj?.stop(); return@Thread }
                try {
                    capture = cap; inject = inj
                    cap?.let { c -> halts.add { c.halt() } }
                    inj?.let { i -> halts.add { i.halt() } }
                    stats.openWaitMs = System.currentTimeMillis() - t0
                    // What the capture buffered while the inject side was still being refused is stale (and overran
                    // its buffer: a 55 ms standing backlog and a slow capture clock on the test line, 2026-11-25).
                    cap?.drain(rate)
                    startStreams(rate, gen)
                    pathsOpen = true
                    applyMic(rate)
                    val routedCall = inj?.let { CallAudio.routedToCall(it.routedType(), testLine) }
                    info(JSONObject().put("id", startId).put("capture", cap?.name ?: JSONObject.NULL).put("inject", inj?.name ?: JSONObject.NULL)
                        .put("testLineFarEnd", farEnd).put("routed", inj?.routedTo() ?: JSONObject.NULL).put("routedToCall", routedCall ?: JSONObject.NULL)
                        .put("interceptInjectDead", CallAudio.interceptInjectDead)
                        .put("openWaitMs", stats.openWaitMs).put("openMode", MicPath.Mode.name(CallAudio.audioMode())).put("active", callActive)
                        .put("earpiece", localNote).put("silent", LocalMutes.silent(plan, localOk, guard?.state == RedirectGuard.State.ON))
                        .put("mic", micJson())
                        .put("silence", CallAudio.silence.name.lowercase()).put("telecomMicMute", plan.telecomMic).put("errors", errors)
                        .put("uplink", uplinkJson(uplinkWhy()))
                        .put("buffers", JSONObject().put("captureFrames", cap?.bufferFrames() ?: 0)
                            .put("injectFrames", inj?.bufferFrames() ?: 0).put("injectLatencyMs", inj?.latencyMs() ?: -1)))
                    ShellEnv.log("call paths open after ${stats.openWaitMs} ms (waited in ${MicPath.Mode.name(waitedMode)}): capture ${cap?.name} inject ${inj?.name}")
                } finally { audioLock.unlock() }
            }, "open").apply { start() })
        }

        @Volatile private var farEnd = false
        /** This call's downlink reads: how many, their samples, the non-zero ones (Diag: "reads only zeros" or not). */
        @Volatile private var downReads = 0L
        @Volatile private var downSamples = 0L
        @Volatile private var downNonZero = 0L
        @Volatile private var downLiveReads = 0L
        @Volatile private var callStartedAt = 0L

        /** The helper's own view of this call's audio for Diag: methods, routing, what the downlink and uplink did. */
        private fun ownState(): JSONObject {
            val cap = capture; val inj = inject
            return JSONObject().put("running", running).put("pathsOpen", pathsOpen).put("testLine", testLine).put("active", callActive)
                .put("callSeconds", if (running && callStartedAt > 0) (System.currentTimeMillis() - callStartedAt) / 1000 else JSONObject.NULL)
                .put("capture", cap?.name ?: JSONObject.NULL).put("inject", inj?.name ?: JSONObject.NULL)
                .put("routed", inj?.routedType()?.takeIf { it >= 0 }?.let { DiagFilter.deviceName(it) } ?: JSONObject.NULL)
                .put("routedToCall", inj?.let { CallAudio.routedToCall(it.routedType(), testLine) } ?: JSONObject.NULL)
                .put("downReads", downReads).put("downSamples", downSamples).put("downNonZeroSamples", downNonZero).put("downReadsWithSound", downLiveReads)
                .put("captureFrames", cap?.capturedFrames() ?: -1)
                .put("upWrites", stats.upWrites).put("upPlayedFrames", inj?.playedFrames() ?: -1).put("upSpeechFrames", stats.speechFrames)
                .put("injectUnderruns", inj?.underruns() ?: -1).put("openWaitMs", stats.openWaitMs)
                .put("mic", runCatching { micJson() }.getOrDefault(JSONObject())).put("uplink", runCatching { uplinkJson() }.getOrDefault(JSONObject()))
        }

        /** An automatic snapshot (a dead path, a call's end): the fast part now, the rest on its own thread. */
        private fun autoDiag(trigger: String) {
            val own = ownState()
            val quick = runCatching { Diag.quick(own) }.getOrNull() ?: return
            Thread({
                val full = runCatching { Diag.full(own, quick) }.getOrDefault(quick)
                info(JSONObject().put("autodiag", full).put("trigger", trigger).put("testLine", own.optBoolean("testLine")))
            }, "autodiag").apply { isDaemon = true; start() }
        }

        /** The audio threads of call [gen], once its paths are open (holding the audio lock). */
        private fun startStreams(rate: Int, gen: Int) {
            capture?.let { c ->
                threads.add(Thread({
                    audioPriority("down")
                    val buf = ShortArray(rate / 50)
                    val bytes = ByteArray(buf.size * 2)
                    // Standing delay guard: the smallest backlog over a 1 s window is delay that never drains (a slow
                    // start, a GC pause); when it is over one frame, drop down to half a frame. The minimum, not the
                    // instant value: some capture paths deliver in bursts of 100+ ms (remote submix on the test line),
                    // and right after a burst the backlog is high without any lasting delay — trimming then threw away
                    // far-end voice (1.3 s of a 6.5 s call, 2026-11-23). Only audio already buffered is discarded
                    // (non-blocking reads), since the estimate comes from timestamps and can be wrong.
                    var windowMin = Int.MAX_VALUE
                    var windowReads = 0
                    while (running && callGen == gen) {
                        val n = c.read(buf)
                        if (n <= 0) { if (n < 0) break; Thread.sleep(2); continue }   // a stuck record: no busy spin
                        val nz = CallAudio.nonZero(buf, n)
                        downMeter.add(n, nz); watchMeter.add(n, nz)
                        downReads++; downSamples += n; downNonZero += nz; if (nz > 0) downLiveReads++
                        stats.captureClock.mark(System.nanoTime(), c.capturedFrames())
                        val b = c.backlogFrames()
                        if (b >= 0) {
                            stats.backlog(b * 1000 / rate)
                            windowMin = minOf(windowMin, b)
                            if (++windowReads >= 50) {
                                if (windowMin > rate / 50) stats.trim(windowMin - rate / 100, c.drain(windowMin - rate / 100))
                                windowMin = Int.MAX_VALUE; windowReads = 0
                            }
                        }
                        for (i in 0 until n) { bytes[2 * i] = buf[i].toInt().toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                        try { send(F.DOWN, bytes.copyOf(n * 2)) } catch (e: Exception) { break }
                    }
                }, "down").apply { start() })
            }
            // Tether's test line has no network behind it: on its VoIP path nothing reads the injected uplink, and an
            // unread remote submix takes it ~10 % slower than real time (14.4 kHz for 16 kHz on the reference phone,
            // 2026-11-23), which no real call does. Read it as the far end would, so test calls run at the call's clock.
            farEnd = testLine && inject?.name == "intercept" && ShellEnv.audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            if (farEnd) threads.add(Thread({
                runCatching { CallAudio.openCapture("voice_communication", rate) }
                    .onFailure { ShellEnv.log("test line far end: ${CallAudio.describe(it)}") }.getOrNull()?.let { far ->
                        halts.add { far.halt() }
                        val b = ShortArray(rate / 50)
                        while (running && callGen == gen) { val n = far.read(b); if (n < 0) break; if (n == 0) Thread.sleep(2) }
                        far.stop()
                    }
            }, "far-end").apply { start() })
            inject?.let { inj ->
                threads.add(Thread({
                    // No silence padding: an underrunning AudioTrack plays silence by itself, while padded
                    // silence would sit in the buffer and push every later frame back. The buffer corrects the delay
                    // in quiet frames instead (see UplinkBuffer).
                    audioPriority("up")
                    var writes = 0
                    var epoch = activeEpoch
                    while (running && callGen == gen) {
                        val buf = up
                        val s = buf.take(100) ?: continue
                        if (epoch != activeEpoch) { epoch = activeEpoch; writes = 0 }
                        if (s.isEmpty()) continue   // a quiet frame cut whole
                        try { inj.write(s) } catch (e: Exception) { break }
                        stats.upWrites++
                        val level = CallAudio.rms(s, s.size)
                        meter.add(level)
                        if (level >= UplinkCheck.SPEECH_RMS) stats.speechFrames++
                        stats.injectClock.mark(System.nanoTime(), inj.playedFrames())
                        if (++writes % 10 == 0) inj.queuedFrames().takeIf { it >= 0 }?.let { stats.queued(it * 1000 / rate) }
                        if (writes == 50) stats.startUnderruns = inj.underruns()   // the first second: the path starting up
                        if (writes >= 50) buf.noteUnderruns(inj.underruns())
                    }
                }, "up").apply { start() })
            }
            startWatch(gen)
        }

        /** Once a second: what a person reporting "no audio" needs in the link log (PathWatch). */
        private fun startWatch(gen: Int) {
            val w = PathWatch(testLine)
            threads.add(Thread({
                var lastPlayed = -1L; var lastWrites = 0
                watchMeter.take()
                while (running && callGen == gen) {
                    repeat(10) { if (running && callGen == gen) Thread.sleep(100) }
                    if (!running || callGen != gen) break
                    val (df, dnz) = watchMeter.take()
                    val inj = inject
                    val played = inj?.playedFrames() ?: -1
                    val delta = if (played >= 0 && lastPlayed >= 0) played - lastPlayed else -1
                    lastPlayed = played
                    val writes = stats.upWrites
                    val waiting = inj != null && (writes > lastWrites || up.size > 0)
                    lastWrites = writes
                    val e = w.second(PathWatch.Second(callActive, df, dnz, waiting, delta)) ?: continue
                    val text = PathWatch.text(e, capture?.name, inj?.name, inj?.routedTo())
                    ShellEnv.log("path: $text")
                    // While the record and track are still open: is Android silencing them, or does the phone deliver nothing?
                    autoDiag(e.wire)
                    info(JSONObject().put("path", JSONObject().put("event", e.wire).put("note", text)))
                }
            }, "watch").apply { start() })
        }

        @Volatile private var localNote = ""
        @Volatile private var localOk = true

        /** Why no uplink check runs this call (null: one does). */
        private fun uplinkWhy(): String? = when {
            check != null || guard?.check != null -> null
            inject == null -> "no uplink injection"
            guard != null -> "the redirect mode was checked on this phone before"
            keptBlind -> "this phone's VOICE_UPLINK cannot show the injected voice"
            !plan.micMuted -> "the phone's mic is not muted"
            muteMemory == LocalMutes.Memory.UNVERIFIED -> "this phone's VOICE_UPLINK cannot show the injected voice; the vendor's mic key is known to keep it"
            else -> "checked on this phone before"
        }

        /**
         * The uplink as far as the helper knows: `state` checking | ok | muted-locally-fixed | unverified | off (no local
         * mic mute, nothing to check), `micMuted`, `telecomMicMute` (the app mutes the mic through Telecom), `unmuted`
         * (the check undid the mute this call), `blind` (VOICE_UPLINK shows nothing of the injected voice), `kept` (the
         * mute was put back anyway: the vendor's key is known to keep it), `note`. [why]: the reason when no check runs.
         */
        private fun uplinkJson(why: String? = null): JSONObject {
            val g = guard
            val c = check ?: g?.check
            val state = when {
                c != null -> c.state.wire
                inject == null -> UplinkCheck.State.UNVERIFIED.wire
                g != null -> (if (redirectMemory[g.redirect] == MicPath.Verdict.OK) UplinkCheck.State.OK else UplinkCheck.State.UNVERIFIED).wire
                !plan.micMuted -> "off"
                muteMemory == LocalMutes.Memory.UNVERIFIED -> UplinkCheck.State.UNVERIFIED.wire
                else -> UplinkCheck.State.OK.wire   // muted, and a check on this phone saw the uplink work before
            }
            return JSONObject().put("state", state).put("for", (c?.mitigation ?: if (g != null) UplinkCheck.Mitigation.REDIRECT else UplinkCheck.Mitigation.MUTE).wire)
                .put("micMuted", plan.micMuted).put("telecomMicMute", plan.telecomMic)
                .put("vendorMicMute", plan.vendorMic).put("unmuted", c?.unmuted == true && !keptBlind).put("blind", c?.blind == true).put("kept", keptBlind)
                .put("note", c?.note?.takeIf { it.isNotEmpty() }?.let { if (keptBlind) "$it; the vendor's mic key is known to keep the injected voice: mute put back" else it } ?: why ?: "")
                .put("local", localNote)
        }

        private fun reportUplink() = info(JSONObject().put("uplink", uplinkJson()).put("mic", micJson()))

        /**
         * The phone's own mic in this call (INFO `mic`): `setting` auto | mute | open; `path` off (a redirect mode took it
         * out of the call) | muted | open; `layer` (MicPath.Layer) with the default setting; with a redirect mode:
         * `mode`, `state` waiting | on | reverted | stopped, `verdict` (what this call found out, for the app to
         * remember), `reasserts`, `note`.
         */
        private fun micJson(): JSONObject {
            val g = guard
            val path = when {
                g?.state == RedirectGuard.State.ON -> "off"
                plan.micMuted -> "muted"
                else -> "open"
            }
            return JSONObject().put("setting", mic.wire).put("path", path).apply {
                if (mic == MicPath.Setting.AUTO) put("layer", when {
                    g != null && (g.state == RedirectGuard.State.ON || g.state == RedirectGuard.State.WAITING || fallbackPending) -> MicPath.Layer.REDIRECT.wire
                    plan.vendorMic -> MicPath.Layer.VENDOR_MUTE.wire
                    else -> MicPath.Layer.OPEN.wire
                })
                if (g != null) {
                    put("mode", g.redirect.wire).put("state", g.state.wire).put("reasserts", g.reasserts).put("note", g.note)
                    g.verdict?.let { put("verdict", it.wire) }
                } else put("note", autoNote ?: if (mic != MicPath.Setting.AUTO) "setting ${mic.wire}" else "")
            }
        }

        /** With the default setting and no redirect mode: why, and what the chain does instead. */
        @Volatile private var autoNote: String? = null

        /**
         * The phone's mic for this call, from the setting (holding the audio lock, the paths open): the user's choice, or
         * the automatic chain (MicPath): a redirect mode where this phone allows one, else (and after it is undone) the
         * vendor's mic-source key, else open.
         */
        private fun applyMic(rate: Int) = synchronized(planLock) {
            checkGen++; check = null
            redirectGen++; releaseMode(); guard = null
            fallbackPending = false; autoNote = null; keptBlind = false
            val errs = JSONArray()
            val next = when (mic) {
                MicPath.Setting.OPEN -> LocalMutes.Plan.NONE
                MicPath.Setting.MUTE -> if (legacyNoMute) LocalMutes.Plan.NONE else LocalMutes.manualPlan(CallAudio.silence, muteMemory)
                MicPath.Setting.AUTO -> {
                    val callMode = CallAudio.audioMode()
                    val r = MicPath.choose(android.os.Build.VERSION.SDK_INT, mic, callMode, inject?.name, CallAudio.screeningSupported(), redirectMemory,
                        samsung = CallAudio.hasSamsungCallMute)
                    if (r != null) { startRedirect(rate, r); fallbackPending = true; LocalMutes.Plan.NONE }
                    else {
                        val p = LocalMutes.autoPlan(CallAudio.silence, muteMemory)
                        autoNote = MicPath.noRedirectWhy(android.os.Build.VERSION.SDK_INT, inject?.name, callMode, redirectMemory, CallAudio.hasSamsungCallMute) + "; " + autoMuteNote(p)
                        p
                    }
                }
            }
            if (next.vendorMic != plan.vendorMic) CallAudio.vendorMicMute(next.vendorMic, errs)?.let { localNote = CallAudio.mergeLocal(localNote, it) }
            plan = next
            if (plan.micMuted && plan.check && inject != null) startCheck(rate)
        }

        private fun autoMuteNote(p: LocalMutes.Plan): String = when {
            p.vendorMic -> "the vendor's mic key mutes the mic at its source" + if (p.check) " (uplink checked)" else ""
            !CallAudio.silence.vendorMicKeys -> "no mic-source key known for this phone (${CallAudio.silence.name.lowercase()}): mic open"
            muteMemory == LocalMutes.Memory.BREAKS_UPLINK -> "the vendor's mic key silenced the uplink on this phone before: mic open"
            else -> "the vendor's mic key cannot be checked on this phone and is not known to keep the injected voice: mic open"
        }

        /** The default chain's next layer once the redirect mode is off for good (called from the redirect thread). */
        private fun applyFallback(rate: Int, g: RedirectGuard) = synchronized(planLock) {
            if (!fallbackPending || guard !== g || !running) return@synchronized
            fallbackPending = false
            // This call showed VOICE_UPLINK blind: a check of the mute would only undo it, as it did the mode.
            val mem = if (g.blindUplink && muteMemory == null) LocalMutes.Memory.UNVERIFIED else muteMemory
            val p = LocalMutes.autoPlan(CallAudio.silence, mem)
            val errs = JSONArray()
            if (p.vendorMic) CallAudio.vendorMicMute(true, errs)?.let { localNote = CallAudio.mergeLocal(localNote, it) }
            plan = p
            if (p.vendorMic && mem == LocalMutes.Memory.UNVERIFIED) muteMemory = mem
            autoNote = "${g.redirect.wire} ${g.verdict?.wire ?: g.state.wire}; " + autoMuteNote(p)
            ShellEnv.log("mic: after ${g.redirect.wire}: ${autoNote}")
            if (p.micMuted && p.check && inject != null) startCheck(rate)
        }

        /**
         * Sets this call's redirect audio mode and watches it once a second (RedirectGuard): the mode held against
         * Telecom, the downlink alive, and, while the mode is untested on this phone, the iPhone's voice in
         * VOICE_UPLINK. A revert gives the call back its own mode for the rest of the call; once settled, the default
         * chain's next layer takes the mic (applyFallback).
         */
        private fun startRedirect(rate: Int, r: MicPath.Redirect) {
            val callMode = CallAudio.audioMode()
            val g = RedirectGuard(r, waitDownlink = !testLine, speechCheck = MicPath.wantSpeechCheck(redirectMemory[r]))
            guard = g
            val gen = ++redirectGen
            val call = callGen
            ShellEnv.log("redirect: ${r.wire} for a call in ${MicPath.Mode.name(callMode)} (remembered ${redirectMemory[r]?.wire ?: "nothing"})")
            threads.add(Thread({
                var cap: Capture? = null
                if (g.check != null) cap = try { CallAudio.openCapture("voice_uplink", rate).also { c -> halts.add { c.halt() } } } catch (t: Throwable) {
                    g.uplinkUnavailable("voice_uplink capture: ${CallAudio.describe(t)}"); null
                }
                val buf = ShortArray(rate / 50)
                var last = ""
                try {
                    meter.take(); downMeter.take()
                    while (running && redirectGen == gen) {
                        var window: UplinkCheck.Window? = null
                        val c = cap
                        if (c != null && g.check?.done == false) {
                            var frames = 0; var sum = 0.0
                            while (frames < 50 && running && redirectGen == gen) {
                                val n = c.read(buf)
                                if (n < 0) { g.uplinkUnavailable("voice_uplink capture stopped ($n)"); break }
                                if (n == 0) { Thread.sleep(2); continue }
                                sum += CallAudio.rms(buf, n); frames++
                            }
                            val (speech, speechRms) = meter.take()
                            if (frames >= 50) window = UplinkCheck.Window(speech, speechRms, sum / frames)
                        } else {
                            if (c != null) { c.stop(); cap = null }
                            repeat(10) { if (running && redirectGen == gen) Thread.sleep(100) }
                            meter.take()
                        }
                        if (!running || redirectGen != gen) break
                        val (df, dnz) = downMeter.take()
                        when (g.second(RedirectGuard.Second(df, dnz, CallAudio.audioMode(), window))) {
                            RedirectGuard.Action.APPLY, RedirectGuard.Action.REASSERT -> {
                                val ok = CallAudio.requestMode(r.audioMode)
                                modeHeld = true
                                ShellEnv.log("redirect: ${r.wire} ${if (ok) "on" else "asked, phone in ${MicPath.Mode.name(CallAudio.audioMode())}"} (reasserts ${g.reasserts})")
                            }
                            RedirectGuard.Action.REVERT -> releaseMode()
                            RedirectGuard.Action.NONE -> {}
                        }
                        // The next layer takes the mic under the audio lock (it may start a check thread).
                        if (fallbackPending && g.settled && tryLockFor(call)) try { applyFallback(rate, g) } finally { audioLock.unlock() }
                        val now = "${g.state}|${g.verdict}|${g.note}|${g.check?.state}|${g.reasserts}|${plan.micMuted}"
                        if (now != last) { last = now; ShellEnv.log("redirect: ${g.state.wire} ${g.verdict?.wire ?: ""} ${g.note}"); reportUplink() }
                    }
                } finally { cap?.stop() }
            }, "redirect").apply { start() })
        }

        /** Withdraws our audio-mode request: the call has its own mode (Telecom's) again. */
        private fun releaseMode() {
            if (!modeHeld) return
            modeHeld = false
            CallAudio.requestMode(MicPath.Mode.NORMAL)
            ShellEnv.log("redirect: released, mode ${MicPath.Mode.name(CallAudio.audioMode())}")
        }

        /**
         * Captures VOICE_UPLINK next to the call's own audio and feeds [UplinkCheck] once a second. Opened only while the
         * phone's mic is muted locally (a second capture of the call is one more thing a HAL may mishandle). When the
         * check finds the capture blind and the vendor's key is known to keep the injected voice, the mute goes back on.
         */
        private fun startCheck(rate: Int) {
            val c = UplinkCheck()
            check = c
            val gen = ++checkGen
            val muted = plan
            threads.add(Thread({
                val cap = try { CallAudio.openCapture("voice_uplink", rate).also { k -> halts.add { k.halt() } } } catch (t: Throwable) {
                    c.fail("voice_uplink capture: ${CallAudio.describe(t)}"); reportUplink(); return@Thread
                }
                try {
                    val buf = ShortArray(rate / 50)
                    var frames = 0; var sum = 0.0
                    meter.take()
                    while (running && checkGen == gen && !c.done) {
                        val n = cap.read(buf)
                        if (n < 0) { c.fail("voice_uplink capture stopped ($n)"); break }
                        if (n == 0) { Thread.sleep(2); continue }
                        sum += CallAudio.rms(buf, n); frames++
                        if (frames < 50) continue
                        val (speech, speechRms) = meter.take()
                        val act = c.window(UplinkCheck.Window(speech, speechRms, sum / frames))
                        frames = 0; sum = 0.0
                        if (act == UplinkCheck.Action.UNMUTE) synchronized(planLock) {
                            if (checkGen != gen) return@synchronized
                            val errs = JSONArray()
                            if (plan.vendorMic) CallAudio.vendorMicMute(false, errs)?.let { localNote = CallAudio.mergeLocal(localNote, it) }
                            plan = LocalMutes.undone(plan)
                            ShellEnv.log("uplink check: ${c.note}")
                            reportUplink()
                        }
                        if (c.done) {
                            if (c.blind && c.unmuted && LocalMutes.keepWhenBlind(muted, CallAudio.silence)) synchronized(planLock) {
                                if (checkGen != gen) return@synchronized
                                val errs = JSONArray()
                                CallAudio.vendorMicMute(true, errs)?.let { localNote = CallAudio.mergeLocal(localNote, it) }
                                plan = muted.copy(check = false); keptBlind = true
                            }
                            ShellEnv.log("uplink check: ${c.state.wire} (${c.note})${if (keptBlind) ", mute put back" else ""}"); reportUplink()
                        }
                    }
                } finally { cap.stop() }
            }, "uplink-check").apply { start() })
        }

        /** The user changed the phone-mic setting during a call: apply it now (a redirect mode or the mute, with its checks). */
        private fun setMicMute(req: JSONObject) {
            withAudio {
                if (!running) return
                mic = MicPath.Setting.of(req.optString("mic").takeIf { req.has("mic") }, req.optBoolean("micMute", false))
                if (req.has("redirect")) redirectMemory = MicPath.memoryOf(req.optJSONObject("redirect"))
                muteMemory = LocalMutes.Memory.of(req.optString("muteMemory").takeIf { req.has("muteMemory") })
                legacyNoMute = !req.has("muteMemory") && !req.optBoolean("micMute", true)
                if (pathsOpen) applyMic(rate)   // else the open thread applies it once the paths are open
            }
            reportUplink()
        }

        /**
         * SELFTEST `{methods?, micMute?, redirect?}`: with micMute the vendor's mic key is on during the test (the app
         * mutes Telecom); without it the redirect modes are tested too (unless `redirect` is false).
         */
        private fun selfTest(req: JSONObject): JSONObject {
            val methods = req.optJSONArray("methods")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.filter { it in CallAudio.injectMethods }
                ?: CallAudio.injectMethods
            val mic = req.optBoolean("micMute", false)
            val errs = JSONArray()
            if (mic) CallAudio.vendorMicMute(true, errs)
            val r = try { CallAudio.selfTest(methods = methods, captures = !mic) } finally { if (mic) CallAudio.vendorMicMute(false, errs) }
            // The redirect modes (MicPath): which one takes this phone's mic out of the call without silencing anything.
            if (!mic && req.optBoolean("redirect", true)) {
                val red = runCatching { CallAudio.redirectSelfTest() }.getOrElse { JSONObject().put("methods", JSONArray().put(JSONObject().put("name", "mic:redirect").put("ok", false).put("note", CallAudio.describe(it)))) }
                red.optJSONArray("methods")?.let { a -> for (i in 0 until a.length()) r.getJSONArray("methods").put(a.get(i)) }
                red.optJSONObject("redirect")?.takeIf { it.length() > 0 }?.let { r.put("redirect", it) }
                r.put("callMode", red.optString("callMode"))
            }
            return r.put("silence", CallAudio.silence.name.lowercase()).put("micByTelecom", !CallAudio.silence.vendorMicKeys)
                .put("blindSafe", CallAudio.silence.blindSafe).put("micMuted", mic).apply { if (errs.length() > 0) put("errors", errs) }
        }

        /** Standing-delay figures for one call (sent with INFO every 5 s and at stop). */
        private class Stats {
            var backlogSum = 0L; var backlogN = 0; var backlogMax = 0
            var queuedSum = 0L; var queuedN = 0; var queuedMax = 0
            @Volatile var capDropped = 0
            /** Capture trims: how often the guard fired, and how often its estimate found nothing buffered to drop. */
            var trims = 0; var trimMisses = 0
            /** The capture's and the track's sample clocks against the monotonic clock (a real call: the modem's). */
            val captureClock = ClockRate(); val injectClock = ClockRate()
            @Volatile var startUnderruns = -1
            /** Injected frames with speech in them: none in a whole call means the iPhone sent only silence. */
            @Volatile var speechFrames = 0
            /** Uplink frames dropped because the call was not active yet (dialing, ringing, on hold). */
            @Volatile var upBeforeActive = 0
            /** Frames written to the inject track (the path watch's "is the uplink waiting"). */
            @Volatile var upWrites = 0
            /** How long the paths waited for the call's audio mode / the interception API, ms. */
            @Volatile var openWaitMs = -1L
            fun reset() { speechFrames = 0; backlogSum = 0; backlogN = 0; backlogMax = 0; queuedSum = 0; queuedN = 0; queuedMax = 0; capDropped = 0; trims = 0; trimMisses = 0; captureClock.reset(); injectClock.reset(); startUnderruns = -1; upBeforeActive = 0; upWrites = 0; openWaitMs = -1 }
            /** The call became active: the uplink's figures count from here (dialing is no measure of the call's clock). */
            fun activated() { injectClock.reset(); startUnderruns = -1; queuedSum = 0; queuedN = 0; queuedMax = 0 }
            fun trim(estimated: Int, dropped: Int) {
                trims++; capDropped += dropped
                if (dropped * 2 < estimated) trimMisses++
            }
            fun backlog(ms: Int) { backlogSum += ms; backlogN++; backlogMax = maxOf(backlogMax, ms) }
            fun queued(ms: Int) { queuedSum += ms; queuedN++; queuedMax = maxOf(queuedMax, ms) }
            fun json(rate: Int, up: UplinkBuffer): JSONObject {
                val ms = { samples: Int -> samples * 1000 / rate }
                return JSONObject()
                    .put("captureBacklogMs", if (backlogN > 0) backlogSum / backlogN else -1).put("captureBacklogMaxMs", backlogMax)
                    .put("injectQueuedMs", if (queuedN > 0) queuedSum / queuedN else -1).put("injectQueuedMaxMs", queuedMax)
                    .put("captureDroppedMs", capDropped * 1000L / rate).put("captureTrims", trims).put("captureTrimMisses", trimMisses)
                    // Uplink: frames lost to a full buffer (voice), and what the delay steering did (quiet frames cut /
                    // repeated, speech sped up / slowed down, late audio dropped after a hold-up), the delay ahead of
                    // an arriving frame and its target.
                    .put("uplinkFrames", up.arrived).put("uplinkDroppedFrames", up.overflowFrames).put("uplinkSpeechFrames", speechFrames)
                    .put("uplinkBeforeActiveFrames", upBeforeActive)
                    .put("uplinkQuietCutMs", ms(up.quietCut)).put("uplinkQuietAddedMs", ms(up.inserted)).put("uplinkLateCutMs", ms(up.lateCut))
                    .put("uplinkSpedUpMs", ms(up.compressed)).put("uplinkSlowedMs", ms(up.stretched))
                    .put("uplinkAheadMs", ms(up.levelAvg)).put("uplinkAheadMaxMs", ms(up.levelMax)).put("uplinkTargetMs", ms(up.target)).put("uplinkSinkExtraMs", ms(up.sinkExtra))
                    .put("captureClockHz", captureClock.hz()).put("injectClockHz", injectClock.hz()).put("injectUnderrunsFirstSecond", startUnderruns)
                    .put("openWaitMs", openWaitMs)
            }
        }
        /** Samples per second of a sample clock, from its first and latest position after a short settling time. */
        class ClockRate {
            private var n = 0; private var t0 = 0L; private var f0 = 0L; private var t1 = 0L; private var f1 = 0L
            fun reset() { n = 0 }
            fun mark(nowNs: Long, frames: Long) {
                if (frames < 0) return
                if (++n == 25) { t0 = nowNs; f0 = frames } else if (n > 25) { t1 = nowNs; f1 = frames }
            }
            /** Rounded to 0.1 Hz; -1 until ~1 s was seen. */
            fun hz(): Double = if (n > 75 && t1 > t0) Math.round((f1 - f0) * 1e10 / (t1 - t0)) / 10.0 else -1.0
        }
        private val stats = Stats()
        @Volatile private var rate = 16000

        /** Holding the audio lock. The audio threads are woken first (their blocking reads and writes return), then joined. */
        private fun stop() {
            if (running && pathsOpen) autoDiag("call-end")
            if (running) info(JSONObject().put("stats", stats.json(rate, up).put("injectUnderruns", inject?.underruns() ?: -1)
                .put("injectBufferFrames", inject?.bufferFrames() ?: -1).put("uplink", uplinkJson()).put("mic", micJson())))
            running = false
            callGen++
            checkGen++
            redirectGen++
            pathsOpen = false
            for (h in halts) runCatching { h() }
            threads.forEach { it.join(1500) }
            threads.clear()
            halts.clear()
            releaseMode()   // before anything else of the call stops: the phone's own call path comes back first
            guard = null; fallbackPending = false
            capture?.stop(); capture = null
            inject?.stop(); inject = null
            if (localMuted) { localMuted = false; CallAudio.localCallAudio(muted = false, mic = false, errors = JSONArray()) }
            plan = LocalMutes.Plan.NONE; check = null; keptBlind = false
            callActive = true; activeSinceMs = -1
        }
    }

/** The down thread adds every frame it reads; the redirect guard takes a second's worth. */
internal class DownMeter {
    private var frames = 0
    private var nonZero = 0
    @Synchronized fun add(n: Int, nz: Int) { frames++; nonZero += nz }
    /** Frames read and their non-zero samples since the last call. */
    @Synchronized fun take(): Pair<Int, Int> = (frames to nonZero).also { frames = 0; nonZero = 0 }
}

/** Urgent-audio priority for the helper's audio threads (logged when the shell user may not raise it). */
internal fun audioPriority(name: String) {
    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
    catch (t: Throwable) { System.err.println("helper: $name thread keeps default priority: ${t.message}") }
}

}

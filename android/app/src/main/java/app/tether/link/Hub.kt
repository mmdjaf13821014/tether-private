package app.tether.link

import app.tether.core.LinkPolicy
import android.os.SystemClock
import android.util.Log
import app.tether.TetherApp
import app.tether.adb.Adb
import app.tether.audio.AudioRestart
import app.tether.audio.CallAudioReport
import app.tether.audio.DiagStore
import app.tether.audio.HelperBridge
import app.tether.audio.OpusCodec
import app.tether.audio.Spacing
import app.tether.audio.PcmuCodec
import app.tether.audio.VoiceCodec
import app.tether.core.AudioFrame
import app.tether.core.CallInfo
import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import app.tether.core.ext.ExtensionLink
import app.tether.core.ext.ExtensionRegistry
import app.tether.ext.phoneExtensions
import app.tether.core.idRanges
import app.tether.core.paginate
import app.tether.data.ContactsRepo
import app.tether.data.Ussd
import app.tether.data.UssdCode
import app.tether.telecom.FindPhone
import app.tether.telecom.RingerControl
import app.tether.telecom.CallRegistry
import app.tether.helper.CallAudio
import app.tether.helper.LocalMutes
import app.tether.helper.MicPath
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import app.tether.core.Phone as PhoneEntry

/** Routes everything between the iPhone link, the phone's data, calls and call audio. */
class Hub(private val app: TetherApp) : ConnectionListener {
    companion object {
        const val TAG = "Tether"
        const val PAGE_WEIGHT = 12_000
        /** Before the phone-mic setting: "mute this phone's microphone during bridged calls" (true reads as "mute"). */
        const val PREF_MIC_MUTE = "phone_mic_mute"
        /** "This phone's microphone during bridged calls": MicPath.Setting auto (default) | mute | open (issue #2). */
        const val PREF_MIC = "phone_mic"
        /** Debug flag (adb): validate send/dial/add without touching the radio or the provider. */
        @Volatile var dryRun = false
    }

    /** Every connected client (the protocol allows several; today the iPhone). */
    val conns = CopyOnWriteArrayList<Connection>()
    /** The client call audio goes to, else any connected client (UI / notification). */
    val conn: Connection? get() = audio.target ?: targets().firstOrNull()
    val helper = HelperBridge(app)
    /** "Share VPN" proxy on the tethering interfaces (see ShareProxy). */
    val share = ShareProxy(app).apply { sharing = app.prefs.getBoolean("share_vpn", false) }
    /** Carrier codes, "find my phone" and the ringer (iPhone Link tab › Phone). */
    val ussd = Ussd(app)
    private val ussdBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    val ringer = RingerControl(app)
    val finder = FindPhone(app).apply {
        onChange = { on -> broadcast(Message(T.PHONE_FINDING, JSONObject().put("on", on))) }
    }
    private val work = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hub") }
    /** Call-audio control runs apart from sync/status work so it is never queued behind a big sync. */
    private val audioCtl = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "audio-ctl") }
    private val smsRequests = ConcurrentHashMap<Int, Pair<Connection, Int>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val audio = AudioSession()
    private val radioPushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val endedSent = ConcurrentHashMap<String, Long>()
    private val unreadPushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastUnread = ""
    /** Slow helper work (network switches up to ~50 s, self-test up to 40 s) runs here, not on the hub thread
     *  that SMS sends, reads, deletes and sync share. */
    private val slow = Executors.newSingleThreadExecutor { r -> Thread(r, "hub-slow") }
    /** sms.unread: the Android phone's unread incoming ids; `all` false when capped (then only newer ones are known). */
    private fun unreadMessage(ids: List<Long>) = Message(T.SMS_UNREAD, JSONObject().put("ids", JSONArray(ids)).put("all", ids.size < 3000))

    private fun slowBg(block: () -> Unit) = slow.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "hub task failed", t) } }
    @Volatile var lastSelftest: JSONObject? = null; private set

    /** Runs on the hub thread; exceptions are logged instead of vanishing inside the executor. */
    private fun bg(block: () -> Unit) = work.execute {
        try { block() } catch (t: Throwable) { Log.e(TAG, "hub task failed", t) }
    }

    /** Extensions (protocol §7, docs/EXTENDING.md): their handlers run on their own thread, never on a link's reader. */
    private val extThread = Executors.newSingleThreadExecutor { r -> Thread(r, "ext") }
    val extensions = ExtensionRegistry(log = { Log.i(TAG, "ext: $it") }, clock = SystemClock::elapsedRealtime,
        dispatch = { extThread.execute(it) }, onStatusChanged = { bg { pushStatus() } }).apply {
        for (e in phoneExtensions(app)) runCatching { register(e) }.onFailure { Log.e(TAG, "extension not registered", it) }
    }
    private val extLinks = ConcurrentHashMap<Connection, ExtensionLink>()

    /** A connection as the extensions see it. Unsolicited extension messages follow the broadcast rules (test isolation). */
    private fun ext(c: Connection): ExtensionLink = extLinks.getOrPut(c) {
        object : ExtensionLink {
            override val peerName get() = c.name
            override val peerKind get() = c.kind
            override val transport get() = c.transport.label
            override val inCall get() = audio.running && audio.target === c
            override val dry get() = dry(c)
            override fun send(m: Message): Boolean {
                if (!c.isUp || (m.re == null && c !in targets())) return false
                c.send(m); return true
            }
        }.also { extensions.linkUp(it) }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun refresh() = listeners.forEach { runCatching { it() } }

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Once per process: a recreated LinkService must not add a second set of listeners and timers. */
    fun startBackground() {
        if (!started.compareAndSet(false, true)) return
        extensions.start()
        helper.listen()
        helper.onChange = { refresh(); bg { pushStatus() } }
        helper.onInfo = { audio.onHelperInfo(it) }
        helper.onUplink = { audio.onHelperUplink(it) }
        helper.onMic = { audio.onHelperMic(it) }
        helper.onPath = { audio.onHelperPath(it) }
        helper.onDiag = { audio.onHelperDiag(it) }
        // Airplane mode switched on from a client before a restart: keep the fail-safe running.
        if (app.prefs.getBoolean("airplane_by_tether", false)) bg { airplaneFailSafe(true) }
        // Call events go out from the Telecom callback itself, ahead of anything queued (ring latency).
        CallRegistry.addListener { info -> onCall(info) }
        app.sms.observe { s -> if (forwardable(s)) targets(real = !TestGuards.isTestNumber(s.addr)).forEach { it.send(Message(T.SMS_NEW, JSONObject().put("item", s.json()))); if (!app.lists.isMuted(s.thread)) wake(it) } }
        // Start from the newest row now, so the first call logged after a restart is pushed live too.
        bg { if (lastCallId < 0) lastCallId = app.callLog.since(0, 1).lastOrNull()?.id ?: 0 }
        app.callLog.observe { bg { pushRecentCalls() } }
        // Read on the Android phone itself (or by its SMS app): the iPhone follows, at most every 2 s.
        app.sms.changeListener = {
            if (unreadPushPending.compareAndSet(false, true)) work.schedule({
                unreadPushPending.set(false)
                val ids = app.sms.unreadIds() ?: return@schedule
                val key = ids.joinToString(",")
                if (key != lastUnread) { lastUnread = key; broadcast(unreadMessage(ids)) }
            }, 2, TimeUnit.SECONDS)
        }
        // Signal / network type changes reach the iPhone within ~2 s (several changes at once send one status).
        app.sims.watch { if (radioPushPending.compareAndSet(false, true)) work.schedule({ radioPushPending.set(false); runCatching { pushStatus() } }, 2, TimeUnit.SECONDS) }
        work.scheduleWithFixedDelay({ runCatching { pushStatus(); watchdog() }.onFailure { Log.e(TAG, "watchdog", it) } }, 60, 60, TimeUnit.SECONDS)
        work.scheduleWithFixedDelay({ runCatching { stallCheck() } }, 5, 5, TimeUnit.SECONDS)
        work.scheduleWithFixedDelay({ runCatching { lePulse() } }, LinkPolicy.PULSE_EVERY_S, LinkPolicy.PULSE_EVERY_S, TimeUnit.SECONDS)
        pulseBurst()   // a restart drops the iPhone's session like an app close does
        audioCtl.scheduleWithFixedDelay({ runCatching { audio.reconcile() }.onFailure { Log.e(TAG, "audio reconcile", it) } }, 1, 1, TimeUnit.SECONDS)
        audioCtl.execute { keepHelperWarm() }
        // Follow the hotspot / Bluetooth PAN coming and going (the proxy and its PAC answer there only).
        work.scheduleWithFixedDelay({ runCatching { share.refresh() }; runCatching { LinkService.running?.peripheral?.ensureAdvertising() } }, 2, 10, TimeUnit.SECONDS)
        // JIT-compile the Opus encoder/decoder now: cold, the first seconds of a call cost 3-4× more per frame.
        Thread({ runCatching { OpusCodec().apply { repeat(300) { i -> encode(ShortArray(320) { ((it * 37 + i) % 2000 - 1000).toShort() }).forEach { f -> decode(f) } } } } }, "opus-warmup")
            .apply { priority = Thread.MIN_PRIORITY }.start()
    }

    // ------------------------------------------------------------------ connection lifecycle

    /** Set by on-device tests: only this transport may hold the link while it is set. */
    @Volatile var exclusive: Transport? = null
    /** Same for tests with several fake clients at once (call hand-over). */
    @Volatile var exclusiveSet: Set<Transport>? = null

    /** Dry run (debug) touches only the test session's connections, never a real client linked meanwhile. */
    private fun dry(c: Connection) = TestGuards.isDry(c.transport.dry, dryRun, admits(c))

    override fun admits(c: Connection) = exclusive.let { it == null || it === c.transport } &&
        exclusiveSet.let { it == null || c.transport in it }

    /** Clients that get broadcasts; a test holding the link exclusively gets them alone (the iPhone never rings). */
    fun targets(real: Boolean = false): List<Connection> =
        conns.filter { TestGuards.receivesBroadcasts(it.isUp, admits(it), onlyKind, it.kind, it.transport.dry, real) }

    /** Clients for this call's events: a real call (not on the test line) reaches every client whatever `only` says. */
    private fun callTargets(cid: String) = targets(real = !CallRegistry.isTestCall(app, cid))

    /**
     * Debug (adb): only clients of this kind (e.g. "none" for on-device tests) see test calls and test texts, so tests
     * never ring the iPhone. On-device test clients (dry transports) are never filtered; real calls and texts always go
     * to every client; it switches itself off after [TestGuards.ONLY_MS]. Setting and clearing it is in the link log.
     */
    var onlyKind: String?
        get() = onlyValue?.takeIf { TestGuards.onlyActive(onlySetAt, SystemClock.elapsedRealtime()) }
        set(v) {
            onlyValue = v; onlySetAt = SystemClock.elapsedRealtime()
            LinkLog.add(if (v == null) "test filter off: every client gets every call and text" else
                "test filter on (only $v): test calls and test texts go to $v clients only; real calls and texts reach every client; off by itself in ${TestGuards.ONLY_MS / 60_000} min")
        }
    @Volatile private var onlyValue: String? = null
    @Volatile private var onlySetAt = 0L

    fun broadcast(m: Message) = targets().forEach { it.send(m) }

    fun onTransport(t: Transport) {
        Connection(t, app.keys, this).start()
    }

    override fun onEstablished(c: Connection, newlyPaired: Boolean) {
        if (!admits(c)) { c.close("link reserved by a test"); return }
        // The same client reconnecting (new L2CAP channel) replaces its old link; other clients stay.
        // A reconnecting client keeps its place: its last action, and the call audio if it had it. The new link
        // is listed first, so closing the old one never sees "no client" (audio stop, advertising restart).
        app.keys.central(c.peerKey)?.let { c.name = it.name; c.kind = it.kind }
        val olds = conns.filter { it !== c && it.peerKey.contentEquals(c.peerKey) }
        conns.add(c)
        olds.forEach { old ->
            c.lastAction = maxOf(c.lastAction, old.lastAction)
            audio.handOver(old, c)
            old.close("replaced")
        }
        lastClientSeen = SystemClock.elapsedRealtime()
        LinkService.running?.peripheral?.setLinked(true)
        LinkLog.add("link up (${c.name}, ${c.transport.label}) from ${LinkLog.short(c.transport.address)}")
        refresh()
        c.send(Message(T.CALL_LIST, JSONObject().put("calls", JSONArray(CallRegistry.list().map { callJson(it, c) }))))
        // A client paired just now has no kind yet (iPhone or Android phone): it says so in its first sync.
        if (newlyPaired) kindPending.add(c) else holdLeFor(c)
        ext(c)
        LinkService.running?.peripheral?.tune(c.transport.address, high = audio.target === c || audio.owner === c)
        audioCtl.execute { audio.reconcile() }
        bg { pushStatus() }
    }

    /** Connections paired in this session whose kind is not known until their first sync. */
    private val kindPending = java.util.concurrent.ConcurrentHashMap.newKeySet<Connection>()

    /**
     * Remember where the iPhone is (LE pulses while it is away), and hold LE during the session (BlePeripheral.holdLe).
     * Only for an iPhone: an Android client keeps its own connection and is not reachable by a pulse, and its address
     * must never replace the iPhone's.
     */
    private fun holdLeFor(c: Connection) {
        if (TestGuards.holdsLe(c.transport.label, c.kind, android.bluetooth.BluetoothAdapter.checkBluetoothAddress(c.transport.address))) {
            app.prefs.edit().putString("central_bt_address", c.transport.address).apply()
            LinkService.running?.peripheral?.holdLe(c.transport.address)
        }
    }

    override fun onClosed(c: Connection) {
        kindPending.remove(c)
        extLinks.remove(c)?.let(extensions::linkDown)
        lastClientSeen = SystemClock.elapsedRealtime()
        if (conns.remove(c)) {
            if (conns.none { it.isUp }) LinkService.running?.peripheral?.setLinked(false)
            // A reconnect replaces a link from the same address: its tuning belongs to the new link now.
            if (conns.none { it.transport.address == c.transport.address }) {
                LinkService.running?.peripheral?.untune(c.transport.address)
                LinkService.running?.peripheral?.releaseLe(c.transport.address)   // a held link blocks the app's next connect
                // Android keeps the released LE link ~4 s; an app reopened in that window attaches its connect to it and
                // iOS never completes it once the link goes. Pulses soon after the linger complete such a stuck connect
                // (then the regular 30 s pulse; none at all once a session is back).
                pulseBurst()
                // An LE session ended and no new one came within 3 s (the app died, or will not restart on this
                // connection): close the connection iOS keeps open, so a fresh link can come up for the next connect.
                if (c.transport.label == "l2cap") {
                    val addr = c.transport.address
                    work.schedule({
                        if (conns.none { it.transport.address == addr }) LinkService.running?.peripheral?.closeLeConnection(addr)
                    }, 3, TimeUnit.SECONDS)
                }
            }
            if (!app.keys.isPaired) { LinkService.running?.peripheral?.dropKeepers(); app.prefs.edit().remove("central_bt_address").apply() }
            audioCtl.execute { audio.reconcile() }
        }
        refresh()
    }

    override fun onAudio(c: Connection, payload: ByteArray) { if (c === audio.target) audio.onUplink(payload) }

    override fun onMessage(c: Connection, m: Message) {
        // A test client (dry transport) may only touch calls on Tether's own test line, never a real call.
        // Taking the audio (answer, take) or a self-test needs every live call to be on the test line.
        if (c.transport.dry && m.t in TestGuards.callControls) {
            val active = CallRegistry.active().map { CallRegistry.isTestCall(app, CallRegistry.cidOf(it)) }
            if (!TestGuards.callControlAllowed(true, m.t, active, CallRegistry.isTestCall(app, m.b.optString("cid")))) {
                Log.w(TAG, "test client: ${m.t} for a call not on the test line ignored"); return
            }
        }
        when (m.t) {
            T.PING -> c.send(Message(T.PONG, JSONObject().put("ts", m.b.optLong("ts")), re = m.id))
            T.PONG -> Unit
            T.SYNC -> {
                m.b.optJSONArray("codecs")?.let { a -> c.codecs = (0 until a.length()).map { a.getString(it) } }
                m.b.optJSONObject("client")?.let { cl ->
                    c.kind = cl.optString("kind", c.kind); c.name = cl.optString("name", c.name).take(60)
                    c.peerKey?.let { app.keys.updateCentral(it, c.name, c.kind) }
                    refresh()
                }
                if (kindPending.remove(c) && c.isUp) holdLeFor(c)
                extensions.peerCaps(ext(c), m.b.optJSONArray(Ext.CAPS))   // absent (older clients): no extensions
                bg { sync(c, m.b.optLong("sms"), m.b.optLong("calls"), m.b.optString("contacts")) }
            }
            T.SMS_SEND -> bg { sendSms(c, m) }
            // A conversation was read on a client: mark it read here and tell the other devices.
            T.SMS_READ -> bg {
                val thread = m.b.optLong("thread", -1); val upTo = m.b.optLong("upTo", -1)
                if (thread >= 0 && upTo > 0) {
                    val pending = app.sms.unreadCount(thread, upTo)
                    var n = if (dry(c) || pending == 0) 0 else app.sms.markRead(thread, upTo)
                    // Without the WRITE_SMS app-op Android ignores the write silently (0 rows) or refuses it:
                    // grant it through adb and retry once (fresh install, or the default SMS app changed).
                    if (!dry(c) && pending > 0 && n <= 0) {
                        Log.i(TAG, "sms.read: write ignored, granting WRITE_SMS: ${runCatching { Adb.allowSmsWrite(app) }.getOrNull()?.trim()}")
                        n = app.sms.markRead(thread, upTo)
                    }
                    Log.i(TAG, "sms.read thread=$thread upTo=$upTo -> $n row(s)")
                    targets().filter { it !== c }.forEach { it.send(Message(T.SMS_READ, JSONObject().put("thread", thread).put("upTo", upTo))) }
                }
            }
            T.SMS_DELETE -> bg { deleteSms(c, m) }
            T.CALLS_DELETE -> bg { deleteCalls(c, m) }
            T.LISTS_SET -> bg { setLists(c, m) }
            T.CONTACT_ADD -> bg { addContact(c, m) }
            T.CONTACT_UPDATE -> bg { updateContact(c, m) }
            // Favourites: star / unstar on the Android phone; the contacts version changes, so every client re-syncs them.
            T.CONTACT_STAR -> bg {
                val id = m.b.optLong("id", -1); val on = m.b.optBoolean("on")
                val n = if (id < 0) -1 else app.contacts.setStarred(id, on, dry(c))
                Log.i(TAG, "contact.star $id=$on -> $n")
                c.send(Message(T.CONTACT_RESULT, JSONObject().put("ok", n >= 0).put("id", id).apply { if (n < 0) put("err", "Android refused the change") }, re = m.id))
            }
            T.CALL_ANSWER -> { audio.claim(c); CallRegistry.answer(m.b.optString("cid")) }
            // "Move the call here": call audio switches to this client.
            T.CALL_TAKE -> audio.claim(c)
            T.CALL_REJECT -> CallRegistry.reject(m.b.optString("cid"))
            T.CALL_END -> CallRegistry.end(m.b.optString("cid"))
            T.CALL_HOLD -> CallRegistry.hold(m.b.optString("cid"), m.b.optBoolean("on"))
            T.CALL_MUTE -> if (c === audio.target) audio.uplinkMuted = m.b.optBoolean("on")
            T.CALL_DTMF -> m.b.optString("d").firstOrNull()?.let { CallRegistry.dtmf(m.b.optString("cid"), it) }
            T.CALL_DIAL -> bg {
                if (!dry(c)) audio.claim(c)
                val sub = if (m.b.has("sub")) m.b.getInt("sub") else null
                val err = CallRegistry.dial(app, m.b.optString("num"), sub, dry(c))
                c.send(Message(T.CALL_DIALED, JSONObject().put("ok", err == null).apply { err?.let { put("err", it) } }, re = m.id))
            }
            // Link tab switches on the iPhone: mobile data, Wi-Fi hotspot, Bluetooth tethering (via the helper)
            // and "Share VPN" (Tether's own proxy). The state also says whether a VPN is up on the Android phone.
            T.NET_GET, T.NET_SET -> slowBg {
                // A test client only reads: it never flips the Android phone's radios, airplane mode or sharing.
                val what = TestGuards.netSwitch(m.t, c.transport.dry, m.b.optString("what"))
                val on = m.b.optBoolean("on")
                if (what == "share") {
                    app.prefs.edit().putBoolean("share_vpn", on).apply()
                    share.sharing = on
                }
                val err = helper.ensureRunning()
                // "dataSub" picks the SIM that carries mobile data; only a currently active SIM is accepted.
                val sub = if (what == "dataSub") m.b.optInt("sub", -1).takeIf { s -> app.sims.sims().any { it.optInt("sub") == s } } else null
                val state = when {
                    err != null -> null
                    what == "dataSub" && sub == null -> JSONObject().put("err", "unknown SIM")
                    what == "rat" && (m.b.optInt("slot", -1) !in 0..1 || m.b.optString("mode") !in setOf("2G", "3G", "4G", "5G")) ->
                        JSONObject().put("err", "unknown network mode")
                    what == "airplane" -> airplane(on)
                    else -> helper.net(what?.takeIf { it != "share" }, on, sub,
                        if (what == "rat") JSONObject().put("slot", m.b.optInt("slot")).put("mode", m.b.optString("mode")) else null)
                }
                if (what == "airplane") airplaneFailSafe(on)
                share.refresh()
                if (what != null) Log.i(TAG, "net.set $what=$on -> $state")
                val body = (state ?: JSONObject().put("err", err ?: "helper did not answer")).put("share", share.sharing).put("proxy", share.json())
                c.send(Message(T.NET_STATE, body, re = m.id))
                // Sharing changes show on the other client's switch at once.
                if (what == "share" || what == "airplane") targets().filter { it !== c }.forEach { it.send(Message(T.NET_STATE, body)) }
            }
            T.USSD_SEND -> {
                val code = m.b.optString("code"); val sub = if (m.b.has("sub")) m.b.optInt("sub") else null
                if (dry(c) || !UssdCode.valid(code) || !ussdBusy.compareAndSet(false, true)) {
                    // Test clients and invalid codes never reach the network.
                    val err = if (!UssdCode.valid(code)) "Not a carrier code (like *123#)" else if (dry(c)) "dry run: not sent" else "Another code is still waiting for its answer"
                    c.send(Message(T.USSD_RESULT, JSONObject().put("ok", false).put("err", err), re = m.id))
                } else Thread({
                    val r = try { ussd.send(code, sub) } finally { ussdBusy.set(false) }
                    Log.i(TAG, "ussd -> ok=${r.optBoolean("ok")}")
                    c.send(Message(T.USSD_RESULT, r, re = m.id))
                }, "ussd").start()
            }
            T.PHONE_FIND -> bg {
                val on = m.b.optBoolean("on")
                val err = if (dry(c)) "dry run" else if (on) finder.start() else { finder.stop(); null }
                Log.i(TAG, "phone.find $on -> ${err ?: "ok"}")
                c.send(Message(T.PHONE_FINDING, JSONObject().put("on", finder.active).apply { err?.let { put("err", it) } }, re = m.id))
            }
            T.RINGER_SET -> bg {
                val mode = m.b.optString("mode")
                val err = if (dry(c)) "dry run" else ringer.set(mode)
                Log.i(TAG, "ringer.set $mode -> ${err ?: "ok"}")
                c.send(Message(T.RINGER_RESULT, JSONObject().put("ok", err == null).put("mode", ringer.mode()).apply { err?.let { put("err", it) } }, re = m.id))
                pushStatus()
            }
            T.SELFTEST -> slowBg { c.send(Message(T.SELFTEST_RESULT, selftest(), re = m.id)) }
            // {mic} (auto | mute | open) or, from older clients, {micMute}: "the Android phone's microphone during bridged
            // calls"; answered with a status.
            T.AUDIO_SET -> bg {
                if ((m.b.has("mic") || m.b.has("micMute")) && !dry(c))
                    audio.setMic(MicPath.Setting.of(m.b.optString("mic").takeIf { m.b.has("mic") }, m.b.optBoolean("micMute")))
                Log.i(TAG, "audio.set ${m.b} (dry=${dry(c)})")
                lastStatusSent.remove(c)   // the asking client always gets the status, also when nothing changed
                pushStatus()
            }
            T.UNPAIR -> if (!c.transport.dry) {
                c.peerKey?.let { app.keys.removeCentral(it) }
                c.close("unpaired by ${c.name}")
                refresh()
            }
            // Extension types (x.…) and errors answering an extension's request; everything else stays ignored.
            else -> if (!extensions.route(ext(c), m)) Log.d(TAG, "ignored message ${m.t}")
        }
    }

    // ------------------------------------------------------------------ sync

    private fun sync(c: Connection, smsSince: Long, callsSince: Long, contactsVersion: String) {
        val t0 = SystemClock.elapsedRealtime()
        val sms = app.sms.since(smsSince)
        // During a call, small pages: a big record would hold the next audio frames up (one record goes out whole).
        val weight = if (audio.running) 1500 else PAGE_WEIGHT
        val smsPages = paginate(sms, weight) { 120 + it.body.length * 3 }
        smsPages.forEachIndexed { i, p ->
            c.send(Message(T.SMS_BATCH, JSONObject().put("items", JSONArray(p.map { it.json() })).put("more", i < smsPages.size - 1)))
        }
        val calls = app.callLog.since(callsSince)
        val callPages = paginate(calls, weight) { 110 }
        callPages.forEachIndexed { i, p ->
            c.send(Message(T.CALLS_BATCH, JSONObject().put("items", JSONArray(p.map { it.json() })).put("more", i < callPages.size - 1)))
        }
        val version = app.contacts.version()
        if (version != contactsVersion) {
            val contacts = app.contacts.all()
            val pages = paginate(contacts, weight) { it.weight() }
            pages.forEachIndexed { i, p ->
                c.send(Message(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray(p.map { it.json() }))
                    .put("more", i < pages.size - 1).put("version", version).put("full", true)))
            }
        }
        // What still exists (rows deleted on the Android phone or by the other client disappear on this client too),
        // and the block / mute lists.
        // Paged so even a phone with thousands of gaps stays under the record limit (one page in the usual case).
        val sr = idRanges(app.sms.allIds()); val cr = idRanges(app.callLog.allIds())
        val per = 1400 // ≤ ~2 × 1400 × 18 bytes even with 8-digit ids: well under the 65,000-byte record cap
        val pages = maxOf(1, (maxOf(sr.length(), cr.length()) + per - 1) / per)
        for (i in 0 until pages) {
            fun part(a: JSONArray) = JSONArray().apply { for (k in i * per until minOf(a.length(), (i + 1) * per)) put(a.get(k)) }
            val b = JSONObject().put("sms", part(sr)).put("calls", part(cr))
            if (pages > 1) b.put("more", i < pages - 1)
            c.send(Message(T.SYNC_IDS, b))
        }
        c.send(Message(T.LISTS, app.lists.json()))
        app.sms.unreadIds()?.let { c.send(unreadMessage(it)) }
        Log.i(TAG, "sync: ${sms.size} sms, ${calls.size} calls, contacts ${if (version != contactsVersion) "sent" else "unchanged"} " +
            "in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private var lastCallId = -1L

    private fun pushRecentCalls() {
        if (targets().isEmpty()) return
        if (lastCallId < 0) lastCallId = app.callLog.since(0, 1).lastOrNull()?.id ?: 0
        val news = app.callLog.since(lastCallId, 50)
        if (news.isEmpty()) return
        lastCallId = news.maxOf { it.id }
        broadcast(Message(T.CALLS_BATCH, JSONObject().put("items", JSONArray(news.map { it.json() })).put("more", false)))
    }

    fun pollSms() = app.sms.poll { s -> if (forwardable(s)) broadcast(Message(T.SMS_NEW, JSONObject().put("item", s.json()))) }

    /** A text from a blocked sender is not forwarded live (nor notified); it stays in the Android phone's history. */
    private fun forwardable(s: app.tether.core.Sms) = s.box != 1 || !app.lists.isBlocked(s.addr)

    private fun sendSms(c: Connection, m: Message) {
        val req = m.id ?: 0
        val localId = (System.nanoTime() and 0xffff).toInt() * 1000 + (req % 1000)
        smsRequests[localId] = c to req
        val sub = if (m.b.has("sub")) m.b.getInt("sub") else null
        val err = app.sms.send(m.b.optString("to"), m.b.optString("body"), sub, localId, dry(c))
        if (err != null || dry(c)) {
            smsRequests.remove(localId)
            c.send(Message(T.SMS_RESULT, JSONObject().put("ok", err == null).put("state", if (err == null) "sent" else "failed")
                .apply { err?.let { put("err", it) } }, re = req))
        }
    }

    fun smsResult(localId: Int, ok: Boolean, state: String, err: String?) {
        val (c, re) = smsRequests[localId] ?: return
        if (state != "sent") smsRequests.remove(localId)
        // Many networks never send a delivery report: forget the request a while after "sent".
        else work.schedule({ smsRequests.remove(localId) }, 10, TimeUnit.MINUTES)
        c.send(Message(T.SMS_RESULT, JSONObject().put("ok", ok).put("state", state).apply { err?.let { put("err", it) } }, re = re))
        pollSms()
    }

    // ------------------------------------------------------------------ airplane mode fail-safe

    @Volatile private var airplaneCheck: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Airplane mode switched on from a client keeps Bluetooth on, but if the link to every client is still
     * gone 90 s later (Bluetooth went down after all, the iPhone cannot reconnect), airplane mode is switched
     * off again by itself: otherwise nobody could ever switch it off from the iPhone.
     */
    private fun airplaneFailSafe(on: Boolean) {
        airplaneCheck?.cancel(false)
        app.prefs.edit().putBoolean("airplane_by_tether", on).apply()
        if (!on) return
        LinkLog.add("airplane mode on from a client; fail-safe armed")
        airplaneCheck = work.scheduleWithFixedDelay({
            if (!app.prefs.getBoolean("airplane_by_tether", false)) { airplaneCheck?.cancel(false); return@scheduleWithFixedDelay }
            val live = targets().isNotEmpty()
            val since = SystemClock.elapsedRealtime() - lastClientSeen
            if (!live && since > 90_000) {
                LinkLog.add("no client for ${since / 1000} s in airplane mode: switching it off (fail-safe)")
                helper.ensureRunning()
                airplane(false)
                app.prefs.edit().putBoolean("airplane_by_tether", false).apply()
                airplaneCheck?.cancel(false)
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    /**
     * Airplane mode through the helper. Android switches the hotspot and Bluetooth tethering off with it and
     * does not bring them back: remember what was on and switch it back on when airplane mode ends.
     */
    /** What a client's airplane switch does (also the adb test hook). */
    fun setAirplane(on: Boolean): JSONObject? { val st = airplane(on); airplaneFailSafe(on); return st }

    private fun airplane(on: Boolean): JSONObject? {
        if (on) {
            helper.net()?.let { st ->
                app.prefs.edit().putBoolean("apm_restore_hotspot", st.optBoolean("hotspot"))
                    .putBoolean("apm_restore_bt", st.optBoolean("bt"))
                    // Wi-Fi too: the helper is (re)started over wireless debugging, which needs Wi-Fi.
                    .putBoolean("apm_restore_wifi", android.provider.Settings.Global.getInt(app.contentResolver, "wifi_on", 0) != 0).apply()
            }
            return helper.net("airplane", true)
        }
        var st = helper.net("airplane", false)
        if (app.prefs.getBoolean("apm_restore_wifi", false)) {
            Thread.sleep(1000)
            val wifiOn = android.provider.Settings.Global.getInt(app.contentResolver, "wifi_on", 0) != 0
            if (!wifiOn) { runCatching { Adb.get(app).shell("svc wifi enable") }; LinkLog.add("after airplane mode: Wi-Fi back on") }
        }
        val restore = listOf("hotspot" to app.prefs.getBoolean("apm_restore_hotspot", false), "bt" to app.prefs.getBoolean("apm_restore_bt", false))
        app.prefs.edit().remove("apm_restore_hotspot").remove("apm_restore_bt").remove("apm_restore_wifi").apply()
        for ((what, was) in restore) if (was) {
            Thread.sleep(1500)   // let the radios come back first
            st = helper.net(what, true) ?: st
            LinkLog.add("after airplane mode: $what back on")
        }
        return st
    }

    /** Last time any client link was up (for the airplane fail-safe). */
    @Volatile private var lastClientSeen = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------------ delete, block, mute

    private fun longs(a: JSONArray?): List<Long> = a?.let { (0 until it.length()).map { i -> it.getLong(i) } } ?: emptyList()

    /** Deletes messages or a whole conversation; every client (the asking one included) gets sms.deleted. */
    private fun deleteSms(c: Connection, m: Message) {
        val body = applySmsDelete(longs(m.b.optJSONArray("ids")), if (m.b.has("thread")) m.b.getLong("thread") else null, dry(c))
        c.send(Message(T.SMS_DELETED, body, re = m.id))
        if (body.optBoolean("ok")) targets().filter { it !== c }.forEach { it.send(Message(T.SMS_DELETED, body)) }
    }

    /** The deletion itself (also used by the adb test hook); returns the sms.deleted body. */
    fun applySmsDelete(ids: List<Long>, thread: Long?, dry: Boolean = dryRun): JSONObject {
        var gone = if (dry) (if (thread != null) app.sms.idsIn(thread) else ids) else app.sms.delete(ids, thread)
        if (gone == null) {
            Log.i(TAG, "sms.delete refused, granting WRITE_SMS: ${runCatching { Adb.allowSmsWrite(app) }.getOrNull()?.trim()}")
            gone = app.sms.delete(ids, thread)
        }
        Log.i(TAG, "sms.delete thread=$thread ids=${ids.size} -> ${gone?.size ?: "refused"}")
        if (gone == null) return JSONObject().put("ok", false).put("ids", JSONArray()).put("err", "Android refused the deletion")
        if (thread != null && !dry) app.lists.setMuted(thread, false)
        return JSONObject().put("ok", true).put("ids", JSONArray(gone)).apply { thread?.let { put("thread", it) } }
    }

    private fun deleteCalls(c: Connection, m: Message) {
        val all = m.b.optBoolean("all")
        val body = applyCallsDelete(if (all) null else longs(m.b.optJSONArray("ids")), dry(c))
        c.send(Message(T.CALLS_DELETED, body, re = m.id))
        if (body.optBoolean("ok")) targets().filter { it !== c }.forEach { it.send(Message(T.CALLS_DELETED, body)) }
    }

    /** Deletes call-log rows (all when [ids] is null); returns the calls.deleted body. */
    fun applyCallsDelete(ids: List<Long>?, dry: Boolean = dryRun): JSONObject {
        val n = if (dry) (ids?.size ?: 0) else app.callLog.delete(ids)
        Log.i(TAG, "calls.delete all=${ids == null} ids=${ids?.size} -> $n")
        return if (n < 0) JSONObject().put("ok", false).put("ids", JSONArray()).put("err", "Android refused the deletion")
        else JSONObject().put("ok", true).put("all", ids == null).put("ids", JSONArray(ids ?: emptyList<Long>()))
    }

    /** lists.set {what:"block", num, on} | {what:"mute", thread, on}; every client gets the new lists. */
    private fun setLists(c: Connection, m: Message) {
        val body = applyListsSet(m.b, dry(c))
        c.send(Message(T.LISTS, body, re = m.id))
        targets().filter { it !== c }.forEach { it.send(Message(T.LISTS, body)) }
    }

    fun applyListsSet(b: JSONObject, dry: Boolean = dryRun): JSONObject {
        val on = b.optBoolean("on")
        val where = when (b.optString("what")) {
            "block" -> if (dry) "dry" else app.lists.setBlocked(b.optString("num"), on)
            "mute" -> { if (!dry) app.lists.setMuted(b.optLong("thread", -1), on); "tether" }
            else -> "error: unknown list"
        }
        Log.i(TAG, "lists.set ${b.optString("what")} on=$on -> $where")
        return app.lists.json().put("where", where).apply { if (where.startsWith("error")) put("err", where) }
    }

    private fun addContact(c: Connection, m: Message) {
        val phones = m.b.optJSONArray("phones")?.let { a -> (0 until a.length()).map { val p = a.getJSONObject(it); PhoneEntry(p.getString("n"), p.optString("type", "mobile"), if (p.has("label")) p.optString("label") else null) } } ?: emptyList()
        val emails = m.b.optJSONArray("emails")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val res = try {
            val id = app.contacts.add(m.b.optString("name"), phones, emails, dry(c))
            JSONObject().put("ok", id != -1L).put("id", id)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
        c.send(Message(T.CONTACT_RESULT, res, re = m.id))
    }

    private fun updateContact(c: Connection, m: Message) {
        val phones = m.b.optJSONArray("phones")?.let { a -> (0 until a.length()).map { val p = a.getJSONObject(it); PhoneEntry(p.getString("n"), p.optString("type", "mobile"), if (p.has("label")) p.optString("label") else null) } } ?: emptyList()
        val emails = m.b.optJSONArray("emails")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val res = try {
            val err = app.contacts.update(m.b.optLong("id", -1), m.b.optString("name"), phones, emails, dry(c))
            JSONObject().put("ok", err == null).put("id", m.b.optLong("id", -1)).apply { err?.let { put("err", it) } }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
        Log.i(TAG, "contact.update ${m.b.optLong("id", -1)} -> $res")
        c.send(Message(T.CONTACT_RESULT, res, re = m.id))
    }

    // ------------------------------------------------------------------ status, calls

    /** `tools` says what the Phone section of the iPhone's Link tab may offer (absent from older builds: hidden there). */
    fun statusJson(): JSONObject = app.sims.status(audio.helperStatus())
        .put("tools", JSONObject().put("ussd", true).put("find", true).put("ringer", true))
        .put("ringer", ringer.mode()).put("dnd", ringer.dndAccess())
        .put(Ext.CAPS, extensions.capsJson())

    /** Status plus the list of connected clients (each client sees which one is itself). */
    /** Synchronized: callers on other threads (battery, data SIM) must not send a stale status after a newer one. */
    @Synchronized fun pushStatus() {
        val live = targets()
        if (live.isEmpty()) return
        val base = statusJson().toString()
        for (c in live) {
            val clients = JSONArray(live.map { o -> JSONObject().put("name", o.name).put("kind", o.kind)
                .put("self", o === c).put("audio", o === audio.target) })
            val body = JSONObject(base).put("clients", clients)
            extensions.statusFor(ext(c))?.let { body.put(Ext.STATUS, it) }
            // Only when something changed: every status wakes the iPhone app and makes it save.
            val key = body.toString()
            if (lastStatusSent[c] == key) continue
            lastStatusSent[c] = key
            c.send(Message(T.STATUS, body))
        }
        lastStatusSent.keys.retainAll(live.toSet())
    }

    private val lastStatusSent = ConcurrentHashMap<Connection, String>()

    /**
     * A suspended iPhone sends nothing for long stretches, so silence is normal: never drop the link for it.
     * The BLE supervision timeout and L2CAP EOF tell us when the iPhone is really gone. We ping once a minute; after
     * 15 min of silence we wake it once and end the session only if it still does not answer (LinkPolicy.idle).
     */
    /** Pulses soon after a session ended or the service started, while the iPhone is most likely to be reconnecting. */
    private fun pulseBurst() {
        for (d in LinkPolicy.PULSE_SOON_EVERY_S..LinkPolicy.PULSE_SOON_FOR_S step LinkPolicy.PULSE_SOON_EVERY_S)
            work.schedule({ runCatching { lePulse() } }, d, TimeUnit.SECONDS)
    }

    /** Paired but no session with the iPhone: a short LE link pulse (BlePeripheral.pulseLe); unpaired: none at all. */
    private fun lePulse() {
        val p = LinkService.running?.peripheral ?: return
        if (!app.keys.isPaired) { p.dropKeepers(); return }
        val addr = app.prefs.getString("central_bt_address", null) ?: return
        if (!LinkPolicy.pulseLeLink(paired = true, sessionUp = conns.any { it.isUp && it.transport.address == addr }, linkOpen = false)) return
        p.pulseLe(addr)
    }

    /** A session whose stream is stuck mid-record (a GATT chunk lost when iOS moved the link) is ended at once. */
    private fun stallCheck() {
        for (c in conns) if (c.stalledMs() > 10_000) c.close("stream stalled mid-record")
    }

    private fun watchdog() {
        audioCtl.execute { keepHelperWarm() }
        if (targets().isNotEmpty()) lastClientSeen = SystemClock.elapsedRealtime()
        for (c in conns) {
            val now = SystemClock.elapsedRealtime()
            if (c.wokeAt in 1 until c.lastRx) { LinkLog.add("${c.name} answered the wake in %.1f s".format((c.lastRx - c.wokeAt) / 1000.0)); c.wokeAt = 0 }
            when (LinkPolicy.idle(now - c.lastRx, c.wokeAt.takeIf { it > c.lastRx }?.let { now - it })) {
                LinkPolicy.Idle.CLOSE -> { c.close("no answer to a wake after 15 min of silence"); continue }
                LinkPolicy.Idle.WAKE -> { c.wokeAt = now; LinkLog.add("${c.name} silent for 15 min: wake ${if (c.transport.label != "memory") wake(c) else "skipped (test link)"}") }
                LinkPolicy.Idle.KEEP -> Unit
            }
            c.send(Message(T.PING, JSONObject().put("ts", System.currentTimeMillis())))
        }
        wakeWithoutSession()
    }

    /**
     * No session with a paired client: wake whatever is connected to our GATT server every [LinkPolicy.WAKE_UNLINKED_MS]
     * (BlePeripheral.wakeUnlinked), so a suspended iPhone app finds out and sets a session up again. Logged when the
     * outcome changes.
     */
    @Volatile private var lastUnlinkedWake = 0L
    @Volatile private var lastUnlinkedWakeNote = ""
    private fun wakeWithoutSession() {
        val now = SystemClock.elapsedRealtime()
        if (!app.keys.isPaired || conns.any { it.isUp && !it.transport.dry }) { lastUnlinkedWakeNote = ""; return }
        if (!LinkPolicy.wakeUnlinked(now - lastUnlinkedWake)) return
        lastUnlinkedWake = now
        val note = LinkService.running?.peripheral?.wakeUnlinked() ?: return
        val shape = note.replace(Regex("…[0-9A-F:]+"), "…")
        if (shape != lastUnlinkedWakeNote) { lastUnlinkedWakeNote = shape; LinkLog.add("no session: $note") }
    }

    /** The helper is started ahead of time so answering a call never waits for an ADB launch. */
    private fun keepHelperWarm() {
        if ((helper.connected || helper.tryConnect()) && !helper.isStale) return
        if (app.prefs.getBoolean("adb_paired", false) || CallRegistry.wantsAudio()) helper.kick()
    }

    /** GATT notification on the wake characteristic: reliably wakes a suspended iPhone app. */
    fun wake(c: Connection): String = LinkService.running?.peripheral?.wake(c.transport.address) ?: "no peripheral"

    /**
     * Call info as one client sees it. `elsewhere` = the call's audio is on another client (it was answered
     * or dialed there): this client stops ringing / shows "on <name>" with a button to move it here.
     */
    fun callJson(info: CallInfo, c: Connection): JSONObject {
        val o = info.json()
        val t = audio.owner
        if (AudioOwner.elsewhere(info.state, t, c, helper.audioBlocked()))
            o.put("elsewhere", true).put("where", AudioOwner.where(t?.takeIf { it !== c }?.name, app.keys.deviceName()))
        return o
    }

    private fun onCall(info: CallInfo) {
        // Ownership first, so `elsewhere` is right in this very event (answer on another client → this one stops ringing).
        // Never let audio control hold up a ring: wait for the audio lock briefly, then announce the call anyway.
        if (!audio.updateOwner(300)) Log.w(TAG, "call ${info.cid}: audio lock busy, announcing without updating the owner")
        // Telecom reports the end several times (disconnecting, disconnected, removed): send it once.
        if (info.state == "ended") { if (endedSent.put(info.cid, SystemClock.elapsedRealtime()) != null) return }
        endedSent.entries.removeIf { SystemClock.elapsedRealtime() - it.value > 60_000 }
        val to = callTargets(info.cid)
        Log.i(TAG, "call ${info.cid} ${info.state} → ${to.size} client(s)")
        for (c in to) {
            c.sendUrgent(Message(T.CALL_STATE, callJson(info, c)))
            // Every call change wakes a suspended iPhone app (answered on the Android phone or elsewhere: stop ringing now).
            wake(c)
        }
        // Fast link from the first ring / dial, not from audio start: the switch to a 15 ms interval takes ~1.7 s, and
        // starting the call on the idle interval (45 ms, latency 5) made its first seconds bursty (gaps up to 77 ms
        // concealed on the iPhone, measured 2026-11-22). A call that ends without audio goes back to balanced here.
        val anyCall = CallRegistry.active().isNotEmpty()
        if (info.state in setOf("ringing", "connecting", "dialing") || (info.state == "ended" && !anyCall && audio.target == null))
            for (c in to) if (c.transport.label != "memory") LinkService.running?.peripheral?.tune(c.transport.address, high = info.state != "ended")
        audioCtl.execute { audio.reconcile() }
        refresh()
    }

    /** Re-sends every call's state (after call audio moved between clients). */
    private fun broadcastCalls() {
        for (info in CallRegistry.list()) for (c in callTargets(info.cid)) c.sendUrgent(Message(T.CALL_STATE, callJson(info, c)))
    }

    fun selftest(): JSONObject {
        val err = helper.ensureRunning()
        val result = when {
            err != null -> JSONObject().put("methods", JSONArray().put(JSONObject().put("name", "helper").put("ok", false).put("note", err)))
            !CallRegistry.hasActiveCall() -> {
                val probe = helper.request("probe")
                JSONObject().put("methods", JSONArray().put(JSONObject().put("name", "live-call").put("ok", false)
                    .put("note", "needs an active call; probe=${probe?.optBoolean("interceptable")}")))
                    .apply { probe?.let { put("probe", it) } }
            }
            else -> {
                // How the call's own audio ran until now (methods, the phone's mic mute): the test itself pauses it.
                val path = audio.pathRow()
                audio.pause()
                try {
                    val r = helper.request("selftest", 50) ?: JSONObject().put("methods", JSONArray())
                    val chosen = r.optString("chosen").takeIf { it.isNotEmpty() }
                    // The redirect modes' answers (MicPath), remembered for this phone model like the in-call check's.
                    if (!audio.onlyTestLine()) MicPath.memoryOf(r.optJSONObject("redirect")).forEach { (mode, v) -> audio.rememberRedirect(mode, v, "self-test") }
                    chosen?.let { app.prefs.edit().putString("inject", it).apply() }
                    val rows = JSONArray().put(path)
                    r.optJSONArray("methods")?.let { a -> for (i in 0 until a.length()) rows.put(a.get(i)) }
                    // The tone reached the uplink without any local mute: does it with the phone's mic muted too? (Not where
                    // every mic mute this phone has also mutes the injected voice: VOICE_UPLINK may still show it there.)
                    if (chosen != null && LocalMutes.canMute(CallAudio.silence)) muteRow(chosen, r.optBoolean("micByTelecom"))?.let { rows.put(it) }
                    r.put("methods", rows)
                    // A capture method heard the call: this firmware gives call audio after all.
                    val heard = (0 until rows.length()).any { i -> rows.optJSONObject(i)?.let { it.optString("name").startsWith("capture:") && it.optBoolean("ok") } == true }
                    if (heard && helper.audioBlocked()) { helper.forgetAudioBlocked(); LinkLog.add("self-test: call audio heard: calls are bridged on this phone again") }
                    r
                } finally {
                    audio.resume()
                }
            }
        }
        lastSelftest = result
        refresh()
        return result
    }

    /**
     * The self-test's second pass: the chosen inject method again with the phone's microphone muted (the vendor key in
     * the helper, or Android's mic mute here). Its answer is remembered for this phone model, as the in-call check's is.
     */
    private fun muteRow(method: String, byTelecom: Boolean): JSONObject? {
        if (byTelecom) { CallRegistry.mute(true); Thread.sleep(300) }
        val r = try {
            helper.request("selftest", 20, JSONObject().put("methods", JSONArray().put(method)).put("micMute", true).put("redirect", false))
        } finally {
            if (byTelecom) CallRegistry.mute(false)
        }
        val row = r?.optJSONArray("methods")?.optJSONObject(0) ?: return null
        val ok = row.optBoolean("ok")
        audio.remember(if (ok) LocalMutes.Memory.OK else LocalMutes.Memory.BREAKS_UPLINK)
        LinkLog.add("self-test: $method with the phone's mic muted (${if (byTelecom) "Android" else "vendor key"}) " +
            (if (ok) "still reaches the uplink" else "does NOT reach the uplink: mic mute kept off on this phone"))
        return JSONObject().put("name", "inject:$method, phone mic muted").put("ok", ok).put("note",
            (if (ok) "the iPhone's voice still reaches the call with this phone's mic muted" else
                "muting this phone's mic silences the iPhone's voice: Tether keeps the mic on") + "; " + row.optString("note"))
    }

    // ------------------------------------------------------------------ call audio

    inner class AudioSession {
        @Volatile private var codec: VoiceCodec? = null
        /** Client the call audio currently flows to. */
        @Volatile var target: Connection? = null; private set
        /** Client that should have the call audio (answered / dialed / took it), null when there is no call. */
        @Volatile var owner: Connection? = null; private set
        @Volatile var uplinkMuted = false
        @Volatile var running = false; private set
        @Volatile private var paused = false
        private var seq = 0
        private var lastUpSeq = -1
        private var backwards = 0
        @Volatile var upLost = 0; private set
        /**
         * Until the helper's start INFO says this call's audio path is open (HelperBridge clears the last one with every
         * start), uplink frames are not forwarded (the far end cannot hear them yet; queued they only added up to 1 s of
         * delay to the first words, which then had to be caught up).
         */
        @Volatile var upEarly = 0; private set
        /** When this call's start INFO came (elapsed ms; 0: not yet), the method choice the start used, restarts so far. */
        @Volatile private var infoAt = 0L
        private var startChoice = "auto"
        private var restarts = 0
        /** What the helper was last told: the bridged call is active (the uplink flows only then). */
        @Volatile private var sentActive = false
        private var savedVolume = -1
        private var lastNoHelperLog = 0L
        /** The helper's plan for this call says: mute the phone's mic through Telecom (only when the user asked, see LocalMutes). */
        @Volatile private var telecomWanted = false
        /** Telecom's mute was switched on by us (only then do we switch it off again). */
        @Volatile private var telecomMutedByUs = false
        /** The helper's last uplink report this call (to log only what changed). */
        @Volatile private var lastUplinkSeen: JSONObject? = null
        private val am = app.getSystemService(android.media.AudioManager::class.java)

        // Latency instrumentation (see README "Latency").
        @Volatile var downFrames = 0L; private set
        @Volatile var encodeNanos = 0L; private set
        @Volatile var upFrames = 0L; private set
        @Volatile var decodeNanos = 0L; private set
        /** Echo instrumentation: when the far side echoes frame N back as uplink frame N (loopback test). */
        private val sentAt = LongArray(1024)
        @Volatile var echoNanos = 0L; private set
        @Volatile var echoCount = 0; private set
        /** Frame spacing as the helper delivers the downlink and as the uplink arrives from the client. */
        val downSpacing = Spacing()
        val upSpacing = Spacing()
        fun resetStats() {
            downFrames = 0; encodeNanos = 0; upFrames = 0; decodeNanos = 0; echoNanos = 0; echoCount = 0; upLost = 0; upEarly = 0
            downSpacing.reset(); upSpacing.reset(); target?.audioDelay?.reset()
        }

        /** [timingSummary] of the last call (debug hooks). */
        @Volatile var lastTiming = ""; private set

        /** The Android phone's half of the call statistics (sent to the client as `audio.stats`, kept in the link log). */
        fun timingSummary(c: Connection? = target): String {
            val d = c?.audioDelay
            val link = c?.let { cc -> LinkService.running?.peripheral?.let { p ->
                listOfNotNull(p.linkParams[cc.transport.address], p.linkPhy[cc.transport.address]?.let { "PHY $it" }).joinToString(", ").ifEmpty { null } } }
            return "down %d frames, encode %.2f ms, from helper jitter %.1f ms worst %.0f ms (%d long gaps); ".format(
                downFrames, if (downFrames > 0) encodeNanos / downFrames / 1e6 else 0.0, downSpacing.jitterMs, downSpacing.worstMs, downSpacing.longGaps) +
                (d?.let { "link queue %.1f ms avg %.0f ms max, %d late, %d overflow; ".format(it.avgMs, it.maxMs, it.late, it.overflow) } ?: "") +
                "up %d frames (%d before the call's audio path opened), arrival jitter %.1f ms worst %.0f ms (%d long gaps), concealed %d".format(
                    upFrames, upEarly, upSpacing.jitterMs, upSpacing.worstMs, upSpacing.longGaps, upLost) +
                (link?.let { "; $it" } ?: "")
        }

        /** After the helper's own figures for the call came in (they follow its stop), tell the client and log both. */
        private fun reportStats(c: Connection?, summary: String, before: JSONObject?) {
            val testLine = onlyTestLine()
            Thread({
                repeat(20) { if (helper.lastStats !== before) return@repeat; Thread.sleep(50) }
                val h = helper.lastStats?.takeIf { it !== before }
                val text = summary + (h?.let { "; helper " + helperSummary(it) } ?: "")
                LinkLog.add("call audio: $text")
                if (!testLine) h?.let { CallAudioReport.silentIphone(it) }?.let { LinkLog.add(it) }
                if (c != null && c.isUp && c.transport.label != "memory")
                    c.sendUrgent(Message(T.AUDIO_STATS, JSONObject().put("summary", text).apply { h?.let { put("helper", it) } }))
            }, "audio-stats").apply { isDaemon = true }.start()
        }

        private fun helperSummary(h: JSONObject) = ("paths open after %d ms, capture backlog %d/%d ms, trimmed %d ms, inject queue %d/%d ms (%d underruns, %d in the first second), " +
            "uplink dropped %d of %d frames (%d held back before the call was active), ahead %d/%d ms (target %d), quiet cut %d ms added %d ms, late cut %d ms, speech sped up %d ms slowed %d ms" +
            "; clocks capture %.1f inject %.1f Hz; uplink speech %d frames, check %s, phone mic %s").format(
            h.optLong("openWaitMs", -1), h.optInt("captureBacklogMs"), h.optInt("captureBacklogMaxMs"), h.optInt("captureDroppedMs"),
            h.optInt("injectQueuedMs"), h.optInt("injectQueuedMaxMs"), h.optInt("injectUnderruns", -1), h.optInt("injectUnderrunsFirstSecond", -1),
            h.optInt("uplinkDroppedFrames"), h.optInt("uplinkFrames"), h.optInt("uplinkBeforeActiveFrames"), h.optInt("uplinkAheadMs"), h.optInt("uplinkAheadMaxMs"), h.optInt("uplinkTargetMs"),
            h.optInt("uplinkQuietCutMs"), h.optInt("uplinkQuietAddedMs"), h.optInt("uplinkLateCutMs"), h.optInt("uplinkSpedUpMs"), h.optInt("uplinkSlowedMs"),
            h.optDouble("captureClockHz", -1.0), h.optDouble("injectClockHz", -1.0), h.optInt("uplinkSpeechFrames", -1),
            h.optJSONObject("uplink")?.optString("state") ?: "–",
            (h.optJSONObject("mic")?.takeIf { it.has("mode") }?.let { m -> "${m.optString("mode")} ${m.optString("state")}" + (m.optString("verdict").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") +
                (if (m.optString("state") != "on" && m.optString("state") != "waiting") (if (h.optJSONObject("uplink")?.optBoolean("micMuted") == true) ", then muted by the vendor key" else ", then open") else "") }
                ?: h.optJSONObject("uplink")?.let { if (it.optBoolean("micMuted")) "muted" + (if (it.optBoolean("kept")) " (kept: vendor key trusted)" else "") else if (it.optBoolean("unmuted")) "unmuted by the check" else "not muted" } ?: "–"))

        // ---------------------------------------------------------- the phone's own microphone (issue #2)

        /**
         * The user's "this phone's microphone during bridged calls" (MicPath): auto (default: a redirect mode takes it out
         * of the call where the phone allows it, else it stays open), mute (LocalMutes), open. A phone set up before the
         * setting existed keeps its "mute" choice.
         */
        val mic: MicPath.Setting get() = MicPath.Setting.of(app.prefs.getString(PREF_MIC, null), app.prefs.getBoolean(PREF_MIC_MUTE, false))
        /** The mute is chosen (LocalMutes); kept for the old status field `micMute`. */
        val micSetting: Boolean get() = mic == MicPath.Setting.MUTE
        private val memoryKey get() = "uplink_mute:" + android.os.Build.MODEL
        private val redirectKey get() = "mic_redirect:" + android.os.Build.MODEL
        /** What this phone model showed of each redirect mode (in-call guard or self-test). */
        fun redirectMemory(): Map<MicPath.Redirect, MicPath.Verdict> =
            MicPath.memoryOf(app.prefs.getString(redirectKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() })

        fun rememberRedirect(mode: MicPath.Redirect, v: MicPath.Verdict, by: String) {
            val m = redirectMemory().toMutableMap()
            if (m[mode] == v) return
            // A later "unverified" never hides what a check did see.
            if (v == MicPath.Verdict.UNVERIFIED && m[mode] != null) return
            m[mode] = v
            app.prefs.edit().putString(redirectKey, MicPath.memoryJson(m).toString()).apply()
            LinkLog.add("call audio: remembered for this phone ($by): ${mode.wire} ${v.wire}" +
                (if (v.usable) "" else ", calls keep the phone's mic in the call unless another mode works"))
        }
        /** What a check found for this phone model (in-call check or self-test). */
        fun memory(): LocalMutes.Memory? = LocalMutes.Memory.of(app.prefs.getString(memoryKey, null))

        fun remember(m: LocalMutes.Memory) {
            if (memory() == m) return
            app.prefs.edit().putString(memoryKey, m.wire).apply()
            LinkLog.add("call audio: remembered for this phone: " + when (m) {
                LocalMutes.Memory.OK -> "the mic mute keeps the uplink"
                LocalMutes.Memory.BREAKS_UPLINK -> "the mic mute silences the uplink, calls start without it"
                LocalMutes.Memory.UNVERIFIED -> "its VOICE_UPLINK cannot show the injected voice: the mic mute is no longer checked (kept only where the vendor's key is known to keep the uplink)"
            })
        }

        /**
         * Changed on this phone or from a client. A new choice forgets what the mute's check found; what the redirect modes
         * did is kept when only switching between settings (each retry of a mode that breaks the downlink cost a few
         * seconds of silence and a late uplink for the rest of that call: Redmi Note 8, 2026-11-25) and forgotten only when
         * the setting already chosen is chosen again ("Choose this again to try anew") or by a self-test's new answer.
         * [forget] false: all kept, for the debug hook's tests on the test line, which remember nothing themselves.
         */
        fun setMic(setting: MicPath.Setting, forget: Boolean = true) {
            val again = setting == mic
            app.prefs.edit().putString(PREF_MIC, setting.wire).remove(PREF_MIC_MUTE)
                .apply { if (forget) remove(memoryKey); if (forget && again) remove(redirectKey) }.apply()
            if (forget) helper.forgetAudioBlocked()
            LinkLog.add("call audio: the phone's own mic during bridged calls: ${setting.wire}")
            if (running) helper.local(setting.wire, LocalMutes.wantMicMute(setting == MicPath.Setting.MUTE, memory()), LocalMutes.wantCheck(memory()),
                redirect = if (onlyTestLine()) JSONObject() else MicPath.memoryJson(redirectMemory()), muteMemory = if (onlyTestLine()) null else memory()?.wire)
            refresh()
        }

        /** Kept for the debug hook `mic_mute on|off`: on = mute, off = the default. */
        fun setMicSetting(on: Boolean) = setMic(if (on) MicPath.Setting.MUTE else MicPath.Setting.AUTO)

        /**
         * Status `helper`: the helper's state, the setting (`mic`, and `micMute` for older clients), what the phone's mic
         * does in this call (`micPath` off | muted | open) and the uplink (this call's check, else what this phone remembers).
         */
        fun helperStatus(): JSONObject = helper.state().put("mic", mic.wire).put("micMute", micSetting).apply {
            val live = if (running) helper.lastUplink?.optString("state")?.takeIf { it.isNotEmpty() } else null
            (live ?: if (mic == MicPath.Setting.MUTE) memory()?.wire else redirectMemory().values.firstOrNull { it == MicPath.Verdict.OK }?.wire)?.let { put("uplink", it) }
            if (running) helper.lastMic?.optString("path")?.takeIf { it.isNotEmpty() }?.let { put("micPath", it) }
        }

        /** The self-test's first row: how this call's audio ran before the test paused it. */
        fun pathRow(): JSONObject {
            val info = helper.lastInfo
            val inject = info?.optString("inject")?.takeIf { it.isNotEmpty() && it != "null" }
            val capture = info?.optString("capture")?.takeIf { it.isNotEmpty() && it != "null" }
            return JSONObject().put("name", "call-audio").put("ok", running && inject != null).put("note",
                if (!running || info == null) "this call's audio was not bridged" else
                    "inject=${inject ?: "NONE"} capture=${capture ?: "NONE"}; ${CallAudioReport.micLine(helper.lastUplink, micSetting, memory(), helper.lastMic, LocalMutes.canMute(CallAudio.silence))}; " +
                        "the tests below run with the phone's mic unmuted and the call in its own mode, except the mic rows")
        }

        /** The helper's start INFO for a call: one link-log line saying whether the uplink injection opened. */
        fun onHelperInfo(o: JSONObject) {
            infoAt = SystemClock.elapsedRealtime()
            if (running) LinkLog.add(CallAudioReport.pathLine(o, micSetting, memory(), LocalMutes.canMute(CallAudio.silence)))
        }

        /** A path-watch finding (the downlink delivering nothing, the injection not taken): straight into the link log. */
        fun onHelperPath(p: JSONObject) {
            if (running) p.optString("note").takeIf { it.isNotEmpty() }?.let { LinkLog.add("call audio: $it") }
            // This phone gives Tether no call audio (only zeros in a real call; PathWatch never says so on the test
            // line): give the call back to the phone (its own earpiece and microphone on again) instead of leaving
            // both ends silent, and do not bridge this firmware's calls again (issue #13).
            if (running && p.optString("event") == "down-zeros" && !onlyTestLine()) {
                helper.rememberAudioBlocked()
                LinkLog.add("call audio: this phone gives Tether no call audio: the call goes back to the Android phone " +
                    "(its earpiece and microphone), and calls are not bridged on this phone until its software changes " +
                    "or a self-test during a call hears call audio. If calls on the phone itself have no sound either, restart the phone once")
                audioCtl.execute { reconcile(); broadcastCalls(); pushStatus() }
            }
        }

        /** Call-audio diagnostics the helper took by itself: the last few are kept for a problem report (DiagStore). */
        val diag = DiagStore(java.io.File(app.filesDir, "call-audio-diag.json"))

        fun onHelperDiag(o: JSONObject) {
            val info = helper.lastInfo
            val context = JSONObject().put("bridged", running).put("calls", CallRegistry.active().size)
                .put("capture", info?.optString("capture") ?: "–").put("inject", info?.optString("inject") ?: "–")
                .put("mic", mic.wire).put("audio", helper.audioLevel())
            diag.add(o, context)
            LinkLog.add("call audio: diagnostics kept for a problem report (${DiagStore.triggerText(o.optString("trigger"))}" +
                (if (o.optBoolean("testLine")) ", test line" else "") + ")")
        }

        /** The helper's mic report: log what changed, remember what the redirect guard found for this phone model. */
        @Volatile private var lastMicSeen: JSONObject? = null
        fun onHelperMic(m: JSONObject) {
            val before = lastMicSeen
            lastMicSeen = m
            CallAudioReport.micEvent(m, before)?.let { LinkLog.add(it) }
            val mode = MicPath.Redirect.of(m.optString("mode")) ?: return
            MicPath.Verdict.of(m.optString("verdict"))?.let { if (!onlyTestLine()) rememberRedirect(mode, it, "in-call check") }
        }

        /** An uplink report (mic mute, check): follow its Telecom mute, log what changed, remember a result. */
        fun onHelperUplink(u: JSONObject) {
            val before = lastUplinkSeen
            lastUplinkSeen = u
            telecomWanted = u.optBoolean("telecomMicMute")
            CallAudioReport.uplinkEvent(u, before)?.let { LinkLog.add(it) }
            // Only the mute's check counts for the mute (the redirect mode's results come with the mic report). The test
            // line says nothing about real calls.
            if (u.optString("for", "mute") == "mute" && !onlyTestLine()) when (u.optString("state")) {
                "ok" -> if (u.optBoolean("micMuted") && before?.optString("state") == "checking") remember(LocalMutes.Memory.OK)
                "muted-locally-fixed" -> remember(LocalMutes.Memory.BREAKS_UPLINK)
                // VOICE_UPLINK showed the iPhone neither with the mute nor without it: never checked again on this phone.
                "unverified" -> if (u.optBoolean("blind") && memory() == null) remember(LocalMutes.Memory.UNVERIFIED)
            }
            audioCtl.execute { reconcile() }
        }

        private fun startHelper(rate: Int, inject: String) {
            val mem = memory()
            telecomWanted = false
            lastUplinkSeen = null
            lastMicSeen = null
            infoAt = 0
            startChoice = inject
            sentActive = CallRegistry.hasActiveCall()
            // The test line is VoIP with no far end: its results say nothing about real calls, so they are not remembered.
            val test = onlyTestLine()
            helper.start(rate, "auto", inject, app.prefs.getInt("inject_ms", 40), test, mic = mic.wire,
                micMute = LocalMutes.wantMicMute(micSetting, mem), micCheck = LocalMutes.wantCheck(mem),
                redirect = if (test) JSONObject() else MicPath.memoryJson(redirectMemory()),
                muteMemory = if (test) null else mem?.wire, active = sentActive)
        }

        /** Tells the helper when the bridged call becomes active (dialing → connected, hold → unhold) or stops being so. */
        private fun updateActive() {
            val now = CallRegistry.hasActiveCall()
            if (now == sentActive) return
            sentActive = now
            helper.call(now)
            Log.i(TAG, "bridged call ${if (now) "active: uplink on" else "not active: uplink held back"}")
        }

        /** A client reconnected (new link replacing its old one): the call audio follows it. */
        fun handOver(old: Connection, new: Connection) {
            lock.lock(); try { if (owner === old) owner = new } finally { lock.unlock() }
        }

        /** Audio control lock (a ReentrantLock so the ring path can give up waiting for it). */
        private val lock = java.util.concurrent.locks.ReentrantLock()

        /** A client answered, dialed or asked to move the call to itself. */
        fun claim(c: Connection) {
            c.lastAction = SystemClock.elapsedRealtime()
            lock.lock(); try { owner = c; claimedKey = keyOf(c); claimedAt = c.lastAction } finally { lock.unlock() }
            audioCtl.execute { reconcile() }
        }

        /**
         * Keeps [owner] valid: dropped when the call or the client is gone; picked when a call needs audio
         * but no client claimed it (answered on the Android phone itself): the client used last, else the iPhone.
         */
        /** [updateOwnerLocked] for callers outside audio control; false when the lock stayed busy for [waitMs]. */
        fun updateOwner(waitMs: Long): Boolean {
            if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return false
            try { updateOwnerLocked(); return true } finally { lock.unlock() }
        }

        private fun updateOwnerLocked(): Boolean {
            val before = owner
            val live = targets(real = CallRegistry.list().any { it.state != "ended" && !CallRegistry.isTestCall(app, it.cid) })
            if (owner?.let { it !in live } == true) owner = null
            val calls = CallRegistry.list()
            val now = SystemClock.elapsedRealtime()
            if (!CallRegistry.wantsAudio() && !calls.any { it.state == "dialing" || it.state == "connecting" }) {
                // Keep a claim made a moment ago (dial in flight, answer not yet active).
                if (owner != null && now - owner!!.lastAction > 5000) owner = null
                if (owner == null && !AudioOwner.keepClaim(calls.map { it.state }) && now - claimedAt > 5000) claimedKey = null
            } else if (owner == null) {
                // Back to the client that answered, dialed or took a call during these calls (it reconnected); a call
                // nobody claimed (answered or dialed on the Android phone itself) stays on the phone (AudioOwner).
                owner = AudioOwner.pick(live, ::keyOf, claimedKey)
            }
            return owner !== before
        }

        /** The client that answered, dialed or took a call during the current calls (its key), and when. */
        @Volatile private var claimedKey: String? = null
        @Volatile private var claimedAt = 0L
        private fun keyOf(c: Connection): String = c.peerKey?.joinToString("") { "%02x".format(it) } ?: "link@${System.identityHashCode(c)}"

        /** Called every second and on every call/link change, on the audio-ctl thread. */
        /** Owner the clients were last told about (claim() changes [owner] directly). */
        private var announcedOwner: Connection? = null

        fun reconcile() {
            val t0 = SystemClock.elapsedRealtime()
            lock.lock()
            try { reconcileLocked() } finally {
                lock.unlock()
                SystemClock.elapsedRealtime().minus(t0).takeIf { it > 300 }?.let { Log.w(TAG, "audio reconcile held the lock $it ms") }
            }
        }

        private fun reconcileLocked() {
            updateOwnerLocked()
            val o = owner
            val changed = o !== announcedOwner
            announcedOwner = o
            val want = o != null && o.isUp && CallRegistry.wantsAudio() && !paused && !helper.audioBlocked()
            if (want && !running) start(o!!)
            else if (!want && running) stop()
            else if (running && o != null && target !== o) retarget(o)
            else if (running) { checkMethods(); muteLocalMic(true) }
            if (running) updateActive()
            if (changed) broadcastCalls()
            // Keep the helper alive between calls too, so answering never waits for a launch.
            if (!helper.connected && app.prefs.getBoolean("adb_paired", false)) helper.kick()
        }

        fun pause() { paused = true; audioCtl.submit { reconcile() }.get() }
        fun resume() { paused = false; audioCtl.execute { reconcile() } }

        private fun codecFor(c: Connection): VoiceCodec = try {
            if ("opus" in c.codecs && OpusCodec.available()) OpusCodec() else PcmuCodec()
        } catch (t: Throwable) {
            Log.w(TAG, "opus init failed, using pcmu", t); PcmuCodec()
        }

        private fun startMessage(k: VoiceCodec) = Message(T.AUDIO_START, JSONObject().put("codec", k.name).put("rate", k.rate)
            .put("ms", 20).put("up", true).put("bitrate", (k as? OpusCodec)?.bitrate ?: 64000))

        private fun start(c: Connection) {
            // Never wait for the helper here (this holds the audio lock): kick it; it attaches in the background
            // and the next 1 s reconcile starts the audio.
            if (!helper.connected || helper.isStale) {
                helper.kick()
                val now = SystemClock.elapsedRealtime()
                if (now - lastNoHelperLog > 5000) { lastNoHelperLog = now; Log.w(TAG, "no call audio yet: helper not running, starting it") }
                return
            }
            val k = codecFor(c)
            codec = k
            target = c
            seq = 0
            lastUpSeq = -1
            restarts = 0
            running = true
            uplinkMuted = false
            resetStats()
            helper.onDown = { pcm ->
                val cc = target
                val kk = codec
                if (cc != null && kk != null && running) {
                    val t0 = System.nanoTime()
                    downSpacing.mark(t0)
                    val frames = kk.encode(pcm)
                    encodeNanos += System.nanoTime() - t0
                    downFrames++
                    for (f in frames) {
                        val sq = seq++ and 0xffff
                        sentAt[sq and 1023] = t0
                        cc.sendAudio(AudioFrame(kk.id, sq, f).encoded())
                    }
                }
            }
            quietLocalEarpiece(true) // before the helper drops it to 0, so the saved level is the user's
            startHelper(k.rate, app.prefs.getString("inject", "auto") ?: "auto")
            c.sendUrgent(startMessage(k))
            muteLocalMic(true)
            LinkService.running?.peripheral?.tune(c.transport.address, high = true)
            Log.i(TAG, "call audio started: ${k.name} @ ${k.rate} to ${c.name}")
        }

        /** Moves running call audio to another client; the helper (and the Android phone's muting) keep running. */
        private fun retarget(c: Connection) {
            val old = target
            val k = codecFor(c)
            val oldCodec = codec
            target = c
            codec = k
            seq = 0
            lastUpSeq = -1
            uplinkMuted = false
            oldCodec?.release()
            old?.sendUrgent(Message(T.AUDIO_STOP))
            old?.let { LinkService.running?.peripheral?.tune(it.transport.address, high = false) }
            c.sendUrgent(startMessage(k))
            LinkService.running?.peripheral?.tune(c.transport.address, high = true)
            Log.i(TAG, "call audio moved from ${old?.name} to ${c.name}")
            refresh()
        }

        /** Every call that wants audio is on Tether's own test line (none reaches a SIM). */
        fun onlyTestLine(): Boolean {
            val calls = CallRegistry.active()
            return calls.isNotEmpty() && calls.all { CallRegistry.isTestCall(app, CallRegistry.cidOf(it)) }
        }

        /** The helper fell back from the interception API in the call's own mode: one restart at most (AudioRestart). */
        private fun checkMethods() {
            val info = helper.lastInfo ?: return
            val since = if (infoAt > 0) SystemClock.elapsedRealtime() - infoAt else 0
            if (!AudioRestart.wanted(android.os.Build.VERSION.SDK_INT, info, startChoice, restarts, since, CallRegistry.hasActiveCall())) return
            restarts++
            LinkLog.add("call audio: the phone refused the interception API in the call (capture ${info.optString("capture")}, inject ${info.optString("inject")}): restarting the call's audio once")
            startHelper(codec?.rate ?: 16000, startChoice)
        }

        fun stop(sendStop: Boolean = true) { lock.lock(); try { stopLocked(sendStop) } finally { lock.unlock() } }

        private fun stopLocked(sendStop: Boolean) {
            if (!running) return
            running = false
            helper.onDown = null
            val statsBefore = helper.lastStats
            helper.stop()
            val t = target
            lastTiming = timingSummary(t)
            reportStats(t, lastTiming, statsBefore)
            if (sendStop) t?.sendUrgent(Message(T.AUDIO_STOP))
            codec?.release()
            codec = null
            target = null
            telecomWanted = false
            muteLocalMic(false)
            quietLocalEarpiece(false)
            t?.let { LinkService.running?.peripheral?.tune(it.transport.address, high = false) }
            Log.i(TAG, "call audio stopped (down $downFrames frames, avg encode ${if (downFrames > 0) encodeNanos / downFrames / 1000 else 0} µs; " +
                "up $upFrames frames, avg decode ${if (upFrames > 0) decodeNanos / upFrames / 1000 else 0} µs)")
        }

        /**
         * Android's mic mute (Telecom) for a bridged call, only where the helper's plan says so: the user turned the
         * phone's mic mute on, the vendor has no key of its own, and the uplink check has not undone it. On several HALs
         * this mute also silences the injected uplink (issue #2), so it is never on by default. The iPhone's mute button
         * is separate (it silences the injected uplink).
         */
        private fun muteLocalMic(on: Boolean) {
            val want = on && telecomWanted
            if (want && !CallRegistry.isMuted()) {
                if (CallRegistry.mute(true)) { telecomMutedByUs = true; Log.i(TAG, "Android phone mic muted through Telecom for bridged call") }
            } else if (!want && telecomMutedByUs) {
                if (!CallRegistry.isMuted() || CallRegistry.mute(false)) { telecomMutedByUs = false; Log.i(TAG, "Android phone mic unmuted through Telecom") }
            }
        }

        /** While the call is bridged to the iPhone, the Android phone's own earpiece goes to its lowest volume. */
        private fun quietLocalEarpiece(on: Boolean) {
            try {
                val s = android.media.AudioManager.STREAM_VOICE_CALL
                if (on) {
                    if (savedVolume < 0) savedVolume = am.getStreamVolume(s)
                    am.setStreamVolume(s, if (android.os.Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else 0, 0)
                } else if (savedVolume >= 0) {
                    am.setStreamVolume(s, savedVolume, 0)
                    savedVolume = -1
                }
            } catch (t: Throwable) {
                Log.w(TAG, "volume change refused: ${t.message}")
            }
        }

        fun onUplink(payload: ByteArray) {
            val k = codec ?: return
            val f = AudioFrame.parse(payload) ?: return
            if (f.codec != k.id) return
            val t0 = System.nanoTime()
            // A gap in the sequence (frames dropped for lateness): conceal up to 2 frames instead of a hard gap.
            val gap = if (lastUpSeq < 0) 1 else ((f.seq - lastUpSeq) and 0xffff)
            if (gap == 0 || gap > 0x8000) {
                // Duplicate / late. Several "late" frames in a row mean the client restarted its counter (its audio
                // engine restarted after a route change): follow it instead of dropping its voice until it catches up.
                if (gap == 0 || ++backwards < 3) return
                Log.i(TAG, "uplink sequence restarted (${lastUpSeq} → ${f.seq}): following it")
            }
            backwards = 0
            lastUpSeq = f.seq
            upSpacing.mark(t0)
            // Decoded (and concealed) anyway, so the decoder's state stays continuous; sent once the helper's path is open.
            val ready = helper.lastInfo != null
            if (gap in 2..3) repeat(gap - 1) { k.conceal()?.let { if (ready) helper.up(if (uplinkMuted) ShortArray(it.size) else it) }; upLost++ }
            val pcm = k.decode(f.payload) ?: return
            val t1 = System.nanoTime()
            decodeNanos += t1 - t0
            upFrames++
            val sent = sentAt[f.seq and 1023]
            if (sent != 0L && t1 - sent < 1_000_000_000L) { echoNanos += t1 - sent; echoCount++ }
            if (!ready) { upEarly++; return }
            helper.up(if (uplinkMuted) ShortArray(pcm.size) else pcm)
        }
    }
}

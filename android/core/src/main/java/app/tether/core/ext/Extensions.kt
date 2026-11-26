package app.tether.core.ext

import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One peer as the extensions see it: a connected client on the Android phone, the Android phone on a client.
 * The host app implements it over its own connection; the registry decides what may be sent.
 */
interface ExtensionLink {
    /** The peer's name as the user knows it ("iPhone", the client's device name, the Android phone's name). */
    val peerName: String
    /** "iphone" or "android" for a client, "phone" for the Android phone. */
    val peerKind: String
    /** "l2cap", "gatt" or "memory" (tests and on-device test clients). */
    val transport: String
    /** Call audio runs on this link now: extension messages are kept small (protocol §7.3). */
    val inCall: Boolean get() = false
    /** A test transport: an extension must not touch anything real for it (it may answer). */
    val dry: Boolean get() = false
    /** Sends one control message as it is; false when it could not be sent. */
    fun send(m: Message): Boolean
}

/**
 * A feature added on top of the link without changing the core apps (protocol §7, docs/EXTENDING.md).
 *
 * The registry calls an extension only for its own message types, never for a core one, and catches everything it
 * throws: a failing extension cannot break calls, texts or the session. Calls arrive on the host's extension thread
 * (one thread, in order); [status] may be called from another thread and must be quick.
 */
interface TetherExtension {
    /** `x.<reverse-dns>`, for example `x.com.example.battery`; `x.tether.*` is reserved for this repository. */
    val id: String
    /** Major versions this build speaks; announced as `<id>/<major>` in `caps`. */
    val versions: List<Int> get() = listOf(1)
    /** The message types it handles and sends: [id] itself or `<id>.<name>`. */
    val types: Set<String> get() = setOf(id)

    fun start(host: ExtensionHost) {}
    fun stop() {}
    /**
     * A peer that speaks a common version came ([up]) or went (link down, version withdrawn, extension switched off).
     * A change between two common versions is no new [up]: [ExtensionSession.version] always tells the agreed one.
     */
    fun onLink(session: ExtensionSession, up: Boolean) {}
    /**
     * A message of one of [types] from the peer, or an `error` answering a request this extension sent.
     * A request carries `id` (answer with [ExtensionSession.reply]); an answer carries `re`.
     */
    fun onMessage(session: ExtensionSession, message: Message)
    /** Optional fields for the Android phone's `status.ext[id]` (≤ 1 KiB encoded), or null. Phone only. */
    fun status(): JSONObject? = null
}

/** What an extension may ask of the app that hosts it. */
interface ExtensionHost {
    /** Live links on which a common version was agreed. */
    fun sessions(): List<ExtensionSession>
    /** [TetherExtension.status] changed: the Android phone sends a new `status` soon. */
    fun statusChanged()
    fun log(line: String)
}

/** An extension's handle on one link: everything it sends goes through the registry's checks. */
class ExtensionSession internal constructor(private val registry: ExtensionRegistry, val extension: TetherExtension,
                                            val link: ExtensionLink) {
    val peerName get() = link.peerName
    val peerKind get() = link.peerKind
    val transport get() = link.transport
    val dry get() = link.dry
    /** The agreed major version, or null when the peer did not announce one this build speaks. */
    val version: Int? get() = registry.version(extension, link)

    /** An unsolicited message; only to a peer that agreed a version. False when not sent. */
    fun send(t: String, b: JSONObject = JSONObject()): Boolean = registry.out(this, t, b, request = false, re = null) != null
    /** A request (with `id`); the answer, or an `error` with `re`, arrives in onMessage. Returns the id, null when not sent. */
    fun request(t: String, b: JSONObject = JSONObject()): Int? = registry.out(this, t, b, request = true, re = null)
    /** The answer to [to] (which carried an `id`); allowed even when the peer announced nothing. */
    fun reply(to: Message, b: JSONObject, t: String = to.t): Boolean {
        val id = to.id ?: return false
        return registry.out(this, t, b, request = false, re = id) != null
    }

    override fun equals(other: Any?) = other is ExtensionSession && other.extension === extension && other.link === link
    override fun hashCode() = System.identityHashCode(extension) * 31 + System.identityHashCode(link)
}

/**
 * Routes extension messages (protocol §7) between the links and the registered extensions, and keeps them from
 * touching anything else: core types are never routed here or sent from here, sizes and rates are capped, an
 * exception is logged and answered, an extension failing [Ext.MAX_FAILURES] times in a row is switched off.
 *
 * [dispatch] is where handlers run (the Android phone: its extension thread; a client: its main thread; tests: inline).
 */
class ExtensionRegistry(
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatch: (Runnable) -> Unit = { it.run() },
    private val onStatusChanged: () -> Unit = {},
) {
    companion object {
        private const val LABEL = "[a-z0-9][a-z0-9-]*"
        private val ID = Regex("^x(\\.$LABEL){2,}$")
        private val ONE_LABEL = Regex("^$LABEL$")
        /** Request ids of extensions start here, far from the ids the apps' own requests use. */
        const val FIRST_ID = 1 shl 30
        private const val MAX_PENDING = 256

        fun isExtensionType(t: String) = t.startsWith(Ext.PREFIX)
        fun validId(id: String) = id.length <= Ext.MAX_ID && ID.matches(id)
        fun validType(t: String, id: String) = t.length <= Ext.MAX_TYPE &&
            (t == id || (t.startsWith("$id.") && ONE_LABEL.matches(t.substring(id.length + 1))))

        /** "<id>/<major>" → (id, major), or null for anything else (core capabilities included: none defined yet). */
        fun parseCap(s: String): Pair<String, Int>? {
            val i = s.lastIndexOf('/')
            if (i <= 0 || s.length > Ext.MAX_TYPE) return null
            val id = s.substring(0, i); val v = s.substring(i + 1).toIntOrNull() ?: return null
            return if (v >= 1 && validId(id)) id to v else null
        }

        /** The `caps` array of a sync or status: extension id → majors (malformed entries ignored, at most [Ext.MAX_CAPS]). */
        fun parseCaps(a: JSONArray?): Map<String, Set<Int>> {
            if (a == null) return emptyMap()
            val out = HashMap<String, MutableSet<Int>>()
            for (k in 0 until minOf(a.length(), Ext.MAX_CAPS)) {
                val (id, v) = (a.opt(k) as? String)?.let(::parseCap) ?: continue
                out.getOrPut(id) { HashSet() }.add(v)
            }
            return out
        }

        /** The highest major both sides speak, or null. */
        fun agree(ours: List<Int>, theirs: Set<Int>?): Int? = theirs?.let { t -> ours.filter { it in t }.maxOrNull() }
    }

    private class Bucket(private val rate: Int, private val burst: Int) {
        private var tokens = burst.toDouble()
        private var at = Long.MIN_VALUE
        @Synchronized fun take(now: Long): Boolean {
            if (at != Long.MIN_VALUE) tokens = minOf(burst.toDouble(), tokens + (now - at).coerceAtLeast(0) * rate / 1000.0)
            at = now
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }

    private class Peer {
        @Volatile var caps: Map<String, Set<Int>> = emptyMap()
        val agreed = ConcurrentHashMap<String, Int>()
        val pending = object : LinkedHashMap<Int, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > MAX_PENDING
        }
        val inBucket = Bucket(Ext.RATE_PER_S, Ext.BURST)
        val outBucket = Bucket(Ext.RATE_PER_S, Ext.BURST)
    }

    private val exts = CopyOnWriteArrayList<TetherExtension>()
    private val byType = ConcurrentHashMap<String, TetherExtension>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val peers = ConcurrentHashMap<ExtensionLink, Peer>()
    private val ids = AtomicInteger(FIRST_ID)
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile private var started = false

    val extensions: List<TetherExtension> get() = exts.toList()
    fun isEnabled(id: String) = exts.any { it.id == id } && id !in disabled

    /**
     * Adds an extension. Refused (IllegalArgumentException) when its id or a type is not in the extension namespace,
     * names a core type, does not belong to its id, or is already taken: an extension can never replace a core message
     * or another extension.
     */
    @Synchronized fun register(e: TetherExtension) {
        require(validId(e.id)) { "extension id '${e.id}' is not x.<reverse-dns> (protocol §7.1)" }
        require(e.versions.isNotEmpty() && e.versions.all { it >= 1 }) { "${e.id}: versions must be ≥ 1" }
        require(e.types.isNotEmpty()) { "${e.id}: no message types" }
        require(exts.none { it.id == e.id }) { "${e.id} is already registered" }
        for (t in e.types) {
            require(isExtensionType(t)) { "${e.id}: '$t' is a core type; extensions cannot handle or replace core messages" }
            require(validType(t, e.id)) { "${e.id}: '$t' is not ${e.id} or ${e.id}.<name>" }
            require(!byType.containsKey(t)) { "${e.id}: '$t' is already handled by ${byType[t]?.id}" }
        }
        exts.add(e)
        e.types.forEach { byType[it] = e }
        if (started) startOne(e)
    }

    /** What this side announces in `caps`: every enabled extension's majors. */
    fun caps(): List<String> = exts.filter { it.id !in disabled }.flatMap { e -> e.versions.distinct().sorted().map { "${e.id}/$it" } }
    fun capsJson() = JSONArray(caps())

    fun start() {
        if (started) return
        started = true
        exts.forEach(::startOne)
    }

    fun stop() {
        if (!started) return
        started = false
        for (e in exts) guard(e, "stop") { e.stop() }
    }

    private fun startOne(e: TetherExtension) {
        val host = object : ExtensionHost {
            override fun sessions(): List<ExtensionSession> = this@ExtensionRegistry.sessions(e)
            override fun statusChanged() { onStatusChanged() }
            override fun log(line: String) { this@ExtensionRegistry.log("${e.id}: $line") }
        }
        guard(e, "start") { e.start(host) }
    }

    /** Live links on which [e] agreed a version (debug hooks and the extension's own host). */
    fun sessions(e: TetherExtension): List<ExtensionSession> =
        peers.entries.filter { (_, p) -> p.agreed.containsKey(e.id) }.map { ExtensionSession(this, e, it.key) }

    fun linkUp(link: ExtensionLink) { peers.putIfAbsent(link, Peer()) }

    fun linkDown(link: ExtensionLink) {
        val p = peers.remove(link) ?: return
        for (id in p.agreed.keys) exts.firstOrNull { it.id == id }?.let { e -> dispatch(Runnable { guard(e, "onLink") { e.onLink(ExtensionSession(this, e, link), false) } }) }
    }

    private fun peer(link: ExtensionLink) = peers.getOrPut(link) { Peer() }

    /** The peer's `caps` (from its latest sync or status; absent: none). Calls onLink for every change. */
    fun peerCaps(link: ExtensionLink, caps: JSONArray?) {
        val p = peer(link)
        p.caps = parseCaps(caps)
        for (e in exts) {
            val v = if (e.id in disabled) null else agree(e.versions, p.caps[e.id])
            val old = if (v == null) p.agreed.remove(e.id) else p.agreed.put(e.id, v)
            if ((old == null) != (v == null)) dispatch(Runnable { guard(e, "onLink") { e.onLink(ExtensionSession(this, e, link), v != null) } })
        }
    }

    fun version(e: TetherExtension, link: ExtensionLink): Int? = if (e.id in disabled) null else peers[link]?.agreed?.get(e.id)
    fun peerCaps(link: ExtensionLink): Map<String, Set<Int>> = peers[link]?.caps ?: emptyMap()

    private fun limit(link: ExtensionLink) = if (link.inCall) Ext.MAX_BYTES_IN_CALL else Ext.MAX_BYTES

    /**
     * Called by the app's router for a message no core handler took (and for every `error`). True when it was an
     * extension's: handled, refused or dropped. False for core types: the router carries on as before.
     * [size] is the encoded envelope's length when the caller has it.
     */
    fun route(link: ExtensionLink, m: Message, size: Int = -1): Boolean {
        val p = peer(link)
        if (m.t == T.ERROR) {
            val re = m.re ?: return false
            val owner = synchronized(p.pending) { p.pending.remove(re) } ?: return false
            val e = exts.firstOrNull { it.id == owner && it.id !in disabled } ?: return true
            deliver(e, link, m)
            return true
        }
        if (!isExtensionType(m.t)) return false
        if (!p.inBucket.take(clock())) { count("dropped.rate"); return true }
        if ((if (size >= 0) size else m.toJson().size) > limit(link)) { refuse(link, m, Ext.ERR_TOO_LARGE); return true }
        m.re?.let { re -> synchronized(p.pending) { p.pending.remove(re) } }
        val e = byType[m.t]?.takeIf { it.id !in disabled }
        if (e == null) { refuse(link, m, Ext.ERR_UNKNOWN); return true }
        deliver(e, link, m)
        return true
    }

    private fun refuse(link: ExtensionLink, m: Message, code: String) {
        count("refused.$code")
        if (code != Ext.ERR_UNKNOWN) log("refused ${m.t} from the ${link.peerKind}: $code")
        val id = m.id ?: return
        link.send(Message(T.ERROR, JSONObject().put("msg", "${m.t}: $code").put("t", m.t).put("code", code), re = id))
    }

    private fun deliver(e: TetherExtension, link: ExtensionLink, m: Message) = dispatch(Runnable {
        val ok = guard(e, "onMessage ${m.t}") { e.onMessage(ExtensionSession(this, e, link), m) }
        if (ok) count("handled") else if (m.id != null && m.t != T.ERROR) refuse(link, m, Ext.ERR_FAILED)
    })

    /** Runs extension code; never lets anything escape. False when it threw. */
    private inline fun guard(e: TetherExtension, what: String, block: () -> Unit): Boolean = try {
        block()
        failures.remove(e.id)
        true
    } catch (t: Throwable) {
        val n = failures.merge(e.id, 1, Int::plus) ?: 1
        log("extension ${e.id} failed in $what: ${t.javaClass.simpleName}${t.message?.let { ": " + it.take(200) } ?: ""}")
        if (n >= Ext.MAX_FAILURES) disable(e)
        false
    }

    private fun disable(e: TetherExtension) {
        if (!disabled.add(e.id)) return
        log("extension ${e.id} switched off after ${Ext.MAX_FAILURES} failures in a row")
        for ((link, p) in peers) if (p.agreed.remove(e.id) != null) runCatching { e.onLink(ExtensionSession(this, e, link), false) }
        runCatching { e.stop() }
        onStatusChanged()
    }

    /** Checks and sends for [ExtensionSession]: the id for a request, 0 for another message, null when not sent. */
    internal fun out(s: ExtensionSession, t: String, b: JSONObject, request: Boolean, re: Int?): Int? {
        val e = s.extension
        require(t in e.types) { "${e.id} may only send its own types, not '$t'" }
        if (e.id in disabled) return null
        val p = peers[s.link] ?: return null
        if (re == null && version(e, s.link) == null) { count("unsent.no-version"); return null }
        if (!p.outBucket.take(clock())) { count("unsent.rate"); log("${e.id}: $t not sent (rate limit)"); return null }
        val id = if (request) ids.incrementAndGet().let { if (it < FIRST_ID) { ids.set(FIRST_ID); FIRST_ID } else it } else null
        val m = Message(t, b, id, re)
        if (m.toJson().size > limit(s.link)) { count("unsent.too-large"); log("${e.id}: $t not sent (over ${limit(s.link)} bytes)"); return null }
        if (id != null) synchronized(p.pending) { p.pending[id] = e.id }
        if (!s.link.send(m)) { if (id != null) synchronized(p.pending) { p.pending.remove(id) }; return null }
        count("sent")
        return id ?: 0
    }

    /** `status.ext` for this link: the fields of every extension agreed with it that has some (each ≤ 1 KiB). */
    fun statusFor(link: ExtensionLink): JSONObject? {
        val p = peers[link] ?: return null
        val out = JSONObject()
        for (e in exts) {
            if (e.id in disabled || !p.agreed.containsKey(e.id)) continue
            var fields: JSONObject? = null
            guard(e, "status") { fields = e.status() }
            val f = fields ?: continue
            if (f.toString().toByteArray().size > Ext.MAX_STATUS_BYTES) { log("${e.id}: status over ${Ext.MAX_STATUS_BYTES} bytes, left out"); continue }
            out.put(e.id, f)
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun count(what: String) { counts.merge(what, 1, Int::plus) }

    /** For debug hooks: extensions, links and counters (no message contents). */
    fun debug(): JSONObject = JSONObject()
        .put("extensions", JSONArray(exts.map { e ->
            JSONObject().put("id", e.id).put("versions", JSONArray(e.versions)).put("types", JSONArray(e.types.sorted()))
                .put("on", e.id !in disabled).put("failures", failures[e.id] ?: 0)
        }))
        .put("caps", capsJson())
        .put("links", JSONArray(peers.entries.map { (l, p) ->
            JSONObject().put("peer", l.peerName).put("kind", l.peerKind).put("transport", l.transport)
                .put("caps", JSONArray(p.caps.flatMap { (id, vs) -> vs.sorted().map { "$id/$it" } }.sorted()))
                .put("agreed", JSONObject(p.agreed.toMap()))
        }))
        .put("counts", JSONObject(counts.toMap()))
}

package app.tether.client

import app.tether.client.calls.CallSink
import app.tether.client.calls.CallTracker
import app.tether.client.calls.EndReason
import app.tether.client.link.CentralSession
import app.tether.client.link.Pipe
import app.tether.client.link.SessionListener
import app.tether.client.model.AudioPort
import app.tether.client.model.Call
import app.tether.client.model.ClientCore
import app.tether.client.model.ClientHello
import app.tether.client.model.ClientState
import app.tether.client.model.Effects
import app.tether.client.model.LinkPort
import app.tether.core.AudioFrame
import app.tether.core.CallEntry
import app.tether.core.HandshakeException
import app.tether.core.Kind
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.PeripheralHandshake
import app.tether.core.Proto
import app.tether.core.Record
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.Sms
import app.tether.core.T
import app.tether.core.ext.ExtensionLink
import app.tether.core.ext.ExtensionRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair

/**
 * An in-memory wire between the client's session and a [FakePhone]: bytes are queued per direction and delivered by
 * [pump] (one thread, deterministic), cut into [chunk]-byte pieces like the GATT pipe's MTU-3 chunks.
 */
class Wire(val chunk: Int = 4096) {
    private val toPhone = ArrayDeque<ByteArray>()
    private val toClient = ArrayDeque<ByteArray>()
    var phone: FakePhone? = null
    var client: CentralSession? = null
    var closed = false
    /** Bytes the next [pump] delivers to the client last, held back (a stalled stream). */
    var holdBackToClient = 0

    val clientPipe = object : Pipe {
        override val label = "memory"
        override fun write(bytes: ByteArray) { if (!closed) split(bytes).forEach { toPhone.addLast(it) } }
        override val backlog get() = toPhone.sumOf { it.size }
        override fun close() { closed = true }
    }

    fun phoneWrite(bytes: ByteArray) { if (!closed) split(bytes).forEach { toClient.addLast(it) } }

    private fun split(b: ByteArray): List<ByteArray> = (b.indices step chunk).map { b.copyOfRange(it, minOf(b.size, it + chunk)) }

    /** Delivers everything queued, both ways, until nothing moves. */
    fun pump() {
        var guard = 0
        while ((toPhone.isNotEmpty() || toClient.size > if (holdBackToClient > 0) 1 else 0) && guard++ < 100_000) {
            toPhone.removeFirstOrNull()?.let { phone?.received(it) }
            if (toClient.size > (if (holdBackToClient > 0) 1 else 0)) toClient.removeFirstOrNull()?.let { client?.received(it) }
        }
    }

    /** The link drops (both ends see it). */
    fun cut() { closed = true; toPhone.clear(); toClient.clear(); client?.close("transport closed") }
}

/**
 * The Android phone's side of a session for tests: the shared core's PeripheralHandshake and SecureSession, and a
 * small hub answering like android/app's Hub (sync pages, sync.ids, lists, sms.unread, sms.send → sms.result + sms.new,
 * calls with audio ownership, call.list on every session, ping, delete / block / mute).
 */
class FakePhone(val static: KeyPair = P256.generate()) {
    val paired = ArrayList<ByteArray>()
    var token: ByteArray? = null
    var busy = false
    private var wire: Wire? = null
    private var reader = RecordReader()
    private var hs: PeripheralHandshake? = null
    private var session: SecureSession? = null
    private var pendingKey: ByteArray? = null
    private var pairMode = false
    var up = false; private set
    val got = ArrayList<Message>()
    val audioIn = ArrayList<AudioFrame>()

    // Data the "phone" holds.
    val sms = ArrayList<Sms>()
    val calls = ArrayList<CallEntry>()
    val contacts = ArrayList<JSONObject>()
    var contactsVersion = "v1"
    val unread = ArrayList<Long>()
    val blocked = ArrayList<String>()
    val muted = ArrayList<Long>()
    val live = LinkedHashMap<String, JSONObject>()
    var audioOwner = false
    var nextSmsId = 1000L
    var pageSize = 2
    /** Extensions on the "phone", routed like Hub does (protocol §7); null: a phone from before extensions. */
    var extensions: ExtensionRegistry? = null
    val extLink = object : ExtensionLink {
        override val peerName = "Test phone client"
        override val peerKind = "android"
        override val transport = "memory"
        override fun send(m: Message): Boolean { if (session == null || !up) return false; sendMessage(m); return true }
    }

    fun attach(w: Wire) {
        wire = w; w.phone = this
        reader = RecordReader(); hs = null; session = null; up = false
    }

    fun received(b: ByteArray) {
        reader.append(b)
        while (true) {
            val r = reader.next() ?: break
            handle(r)
        }
    }

    private fun write(r: Record) = wire!!.phoneWrite(r.encoded())

    private fun handle(r: Record) {
        if (hs == null) {
            val p = PeripheralHandshake(static.private)
            hs = p
            val reply = if (busy) Record(Kind.REJECT, byteArrayOf(4))
                else p.receiveHello(r, { k -> paired.any { it.contentEquals(k) } }) { token }
            write(reply)
            if (reply.kind == Kind.REJECT) return
            pendingKey = p.hello!!.staticKey
            pairMode = p.hello!!.mode == Proto.MODE_PAIR
            return
        }
        if (session == null) {
            val (s, fin) = try { hs!!.receiveFinish(r) } catch (e: Exception) { wire!!.cut(); return }
            session = s
            if (pairMode) { paired.removeAll { it.contentEquals(pendingKey!!) }; paired.add(pendingKey!!); token = null }
            write(fin)
            up = true
            extensions?.linkUp(extLink)
            send(T.CALL_LIST, JSONObject().put("calls", JSONArray(live.values.toList())))
            return
        }
        val (ch, payload) = session!!.open(r)
        when (ch) {
            Proto.CH_CONTROL -> onMessage(Message.parse(payload))
            Proto.CH_AUDIO -> AudioFrame.parse(payload)?.let { audioIn.add(it) }
        }
    }

    fun send(t: String, b: JSONObject = JSONObject(), re: Int? = null) {
        val s = session ?: throw HandshakeException("no session")
        write(s.seal(Proto.CH_CONTROL, Message(t, b, null, re).toJson()))
    }

    fun sendMessage(m: Message) = write(session!!.seal(Proto.CH_CONTROL, m.toJson()))

    fun sendAudio(f: AudioFrame) = write(session!!.seal(Proto.CH_AUDIO, f.encoded()))

    private fun onMessage(m: Message) {
        got.add(m)
        when (m.t) {
            T.PING -> send(T.PONG, JSONObject().put("ts", m.b.optLong("ts")), re = m.id)
            T.SYNC -> {
                extensions?.peerCaps(extLink, m.b.optJSONArray("caps"))
                sync(m.b.optLong("sms"), m.b.optLong("calls"), m.b.optString("contacts"))
                extensions?.let { send(T.STATUS, JSONObject().put("caps", it.capsJson())) }
            }
            T.SMS_SEND -> {
                send(T.SMS_RESULT, JSONObject().put("ok", true).put("state", "sent"), re = m.id)
                val row = Sms(nextSmsId++, 7, m.b.getString("to"), m.b.getString("body"), System.currentTimeMillis(), 2, true, m.b.optInt("sub"))
                sms.add(row)
                send(T.SMS_NEW, JSONObject().put("item", row.json()))
            }
            T.SMS_READ -> { val upTo = m.b.getLong("upTo"); unread.removeAll { id -> sms.any { it.id == id && it.thread == m.b.getLong("thread") && id <= upTo } } }
            T.SMS_DELETE -> {
                val ids = (0 until (m.b.optJSONArray("ids")?.length() ?: 0)).map { m.b.getJSONArray("ids").getLong(it) }
                sms.removeAll { it.id in ids }
                send(T.SMS_DELETED, JSONObject().put("ok", true).put("ids", JSONArray(ids)), re = m.id)
            }
            T.LISTS_SET -> {
                if (m.b.optString("what") == "block") { if (m.b.optBoolean("on")) blocked.add(m.b.getString("num")) else blocked.remove(m.b.getString("num")) }
                else { if (m.b.optBoolean("on")) muted.add(m.b.getLong("thread")) else muted.remove(m.b.getLong("thread")) }
                send(T.LISTS, lists(), re = m.id)
            }
            T.CALL_ANSWER -> { audioOwner = true; setCall(m.b.getString("cid"), "active"); startAudio() }
            T.CALL_DIAL -> {
                audioOwner = true
                send(T.CALL_DIALED, JSONObject().put("ok", true), re = m.id)
                setCall("out1", "dialing", num = m.b.getString("num"), dir = "out")
            }
            T.CALL_END, T.CALL_REJECT -> { setCall(m.b.getString("cid"), "ended"); if (audioOwner) { audioOwner = false; send(T.AUDIO_STOP) } }
            T.CALL_TAKE -> { audioOwner = true; live[m.b.getString("cid")]?.let { it.remove("elsewhere"); it.remove("where"); send(T.CALL_STATE, it) }; startAudio() }
            else -> extensions?.route(extLink, m)
        }
    }

    fun startAudio() = send(T.AUDIO_START, JSONObject().put("codec", "opus").put("rate", 16000).put("ms", 20).put("up", true).put("bitrate", 32000))

    fun setCall(cid: String, state: String, num: String = "+10000000001", dir: String = "in", elsewhere: String? = null) {
        val c = live[cid] ?: JSONObject().put("cid", cid).put("num", num).put("dir", dir).put("hold", true)
        c.put("state", state)
        if (state == "active") c.put("since", System.currentTimeMillis())
        if (elsewhere != null) c.put("elsewhere", true).put("where", elsewhere) else { c.remove("elsewhere"); c.remove("where") }
        if (state == "ended") live.remove(cid) else live[cid] = c
        send(T.CALL_STATE, JSONObject(c.toString()))
    }

    fun lists() = JSONObject().put("blocked", JSONArray(blocked)).put("muted", JSONArray(muted)).put("system", true)

    private fun ranges(ids: List<Long>) = app.tether.core.idRanges(ids)

    private fun sync(smsSince: Long, callsSince: Long, version: String) {
        val newSms = sms.filter { it.id > smsSince }.sortedBy { it.id }
        val smsPages = newSms.chunked(pageSize).ifEmpty { listOf(emptyList()) }
        smsPages.forEachIndexed { i, p -> send(T.SMS_BATCH, JSONObject().put("items", JSONArray(p.map { it.json() })).put("more", i < smsPages.size - 1)) }
        val newCalls = calls.filter { it.id > callsSince }
        val callPages = newCalls.chunked(pageSize).ifEmpty { listOf(emptyList()) }
        callPages.forEachIndexed { i, p -> send(T.CALLS_BATCH, JSONObject().put("items", JSONArray(p.map { it.json() })).put("more", i < callPages.size - 1)) }
        if (version != contactsVersion) {
            val pages = contacts.chunked(pageSize).ifEmpty { listOf(emptyList()) }
            pages.forEachIndexed { i, p -> send(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray(p)).put("more", i < pages.size - 1).put("version", contactsVersion).put("full", true)) }
        }
        send(T.SYNC_IDS, JSONObject().put("sms", ranges(sms.map { it.id })).put("calls", ranges(calls.map { it.id })))
        send(T.LISTS, lists())
        send(T.SMS_UNREAD, JSONObject().put("ids", JSONArray(unread)).put("all", true))
    }
}

/** Records what the call tracker asks of Telecom. */
class RecordingSink : CallSink {
    val events = ArrayList<String>()
    var ringback = false
    override fun reportIncoming(local: String, c: Call) { events += "incoming ${c.cid}" }
    override fun reportStarted(local: String, c: Call) { events += "started ${c.cid} ${c.state}" }
    override fun update(local: String, c: Call) {}
    override fun setActive(local: String) { events += "active" }
    override fun setHeld(local: String, on: Boolean) { events += "held $on" }
    override fun ended(local: String, reason: EndReason) { events += "ended $reason" }
    override fun ringback(on: Boolean) { ringback = on }
    val uplinkChanges = ArrayList<Boolean>()
    var uplink = false
    override fun uplink(open: Boolean) { uplink = open; uplinkChanges += open }
    override fun idle() { events += "idle" }
}

class RecordingAudio : AudioPort {
    var codec: String? = null
    val frames = ArrayList<AudioFrame>()
    override fun configure(codec: String, rate: Int, bitrate: Int) { this.codec = codec }
    override fun remoteStopped() { codec = null }
    override fun frame(f: AudioFrame) { frames += f }
}

class TestEffects : Effects {
    var clock = 1_700_000_000_000L
    val notified = ArrayList<String>()
    val timers = ArrayList<Pair<Long, () -> Unit>>()
    override fun showSms(m: Sms, from: String?, sim: String?) { notified += "sms ${m.id}" }
    override fun showMissedCall(c: CallEntry, from: String?, sim: String?) { notified += "missed ${c.id}" }
    override fun schedule(ms: Long, block: () -> Unit) { timers += (clock + ms) to block }
    override fun now() = clock
}

/**
 * The client as the app wires it, minus Bluetooth and Telecom: a [CentralSession] per link, the [ClientCore] with the
 * synced state, the call tracker and an audio recorder, over a [Wire] to a [FakePhone].
 */
class TestClient(val phone: FakePhone, val key: KeyPair = P256.generate(), val fx: TestEffects = TestEffects(),
                 val extensions: ExtensionRegistry? = null) {
    var session: CentralSession? = null
    var rejected: Int? = null
    var failed: String? = null
    var wire: Wire? = null
    val port = object : LinkPort {
        override val isConnected get() = session?.isUp == true
        override fun send(t: String, b: JSONObject, expectReply: Boolean) = session?.send(t, b, expectReply) ?: 0
    }
    val state = ClientState(port, fx)
    val sink = RecordingSink()
    val audio = RecordingAudio()
    var clock = 0L
    val calls = CallTracker(port, sink, nameOf = { state.name(it) }, now = { clock })
    val extLink = object : ExtensionLink {
        override val peerName = "Test phone"
        override val peerKind = "phone"
        override val transport = "memory"
        override val inCall get() = calls.hasCalls
        override fun send(m: Message) = session?.sendMessage(m) ?: false
    }
    val core = if (extensions == null) ClientCore(state, calls, audio, ClientHello("android", "Test phone", listOf("opus", "pcmu")))
        else ClientCore(state, calls, audio, ClientHello("android", "Test phone", listOf("opus", "pcmu")), extensions, extLink)

    /** Opens a session over a fresh wire; [token] for pair mode. */
    fun connect(token: ByteArray? = null, chunk: Int = 4096): CentralSession {
        val w = Wire(chunk); wire = w
        phone.attach(w)
        rejected = null; failed = null
        val s = CentralSession(key, P256.encode(phone.static.public), token, w.clientPipe, object : SessionListener {
            override fun onUp(s: CentralSession) = core.linkUp()
            override fun onMessage(s: CentralSession, m: Message) = core.message(m)
            override fun onAudio(s: CentralSession, f: AudioFrame) = core.audio(f)
            override fun onRejected(s: CentralSession, reason: Int) { rejected = reason }
            override fun onFailed(s: CentralSession, reason: String) { failed = reason; core.linkDown() }
        })
        w.client = s
        session = s
        s.start()
        w.pump()
        return s
    }

    fun pump() = wire!!.pump()
}

package app.tether.client.link

import app.tether.core.AudioFrame
import app.tether.core.CentralHandshake
import app.tether.core.FramingException
import app.tether.core.HandshakeException
import app.tether.core.Kind
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.Proto
import app.tether.core.Record
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.SessionException
import app.tether.core.T
import org.json.JSONObject
import java.security.KeyPair
import java.security.SecureRandom

/** A reliable, ordered byte pipe to the Android phone (an L2CAP channel, the GATT pipe, or a test pipe). */
interface Pipe {
    val label: String
    /** Queues bytes for sending; never blocks for long. */
    fun write(bytes: ByteArray)
    /** Bytes queued and not yet handed to the radio (audio is dropped above a small backlog). */
    val backlog: Int
    fun close()
}

interface SessionListener {
    fun onUp(s: CentralSession)
    fun onMessage(s: CentralSession, m: Message)
    fun onAudio(s: CentralSession, f: AudioFrame)
    /** The Android phone refused the HELLO: 1 bad version, 2 unknown device, 3 pairing closed / token expired, 4 busy. */
    fun onRejected(s: CentralSession, reason: Int)
    /** The session is over (protocol error, stream stalled, closed). Called once. */
    fun onFailed(s: CentralSession, reason: String)
}

/**
 * Central side of one session (protocol §3–4) over a [Pipe]: HELLO, HELLO_ACK, both FINs, then sealed records.
 * The same state machine as TetherCore's TetherLink without CoreBluetooth: answers pings, measures the round trip of
 * its own pings, tracks a record left half received (a stream stuck mid-record is ended by [stalledMs]).
 * Thread-safe; [received] is called from the transport's reader thread.
 */
class CentralSession(
    staticKey: KeyPair,
    peerKey: ByteArray,
    token: ByteArray?,
    val pipe: Pipe,
    private val listener: SessionListener,
    private val now: () -> Long = System::currentTimeMillis,
    ephemeral: KeyPair = P256.generate(),
    nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
) {
    private val hs = CentralHandshake(staticKey, peerKey, token, ephemeral, nonce)
    private val reader = RecordReader()
    private var session: SecureSession? = null
    private var finished = false
    @Volatile private var over = false
    private var nextId = 1
    /** Pair mode: the HELLO proves a pairing token. */
    val pairing = token != null
    @Volatile var isUp = false; private set
    @Volatile var upAt = 0L; private set
    @Volatile var lastRx = now(); private set
    @Volatile private var partialSince = 0L
    /** Round trip of the last ping of ours that came back, in ms. */
    @Volatile var roundTripMs: Int? = null; private set

    fun start() = synchronized(this) { pipe.write(hs.hello().encoded()) }

    /** How long a record has been half received (0: none). */
    fun stalledMs(at: Long = now()) = partialSince.let { if (it == 0L) 0L else at - it }

    fun received(b: ByteArray, off: Int = 0, len: Int = b.size) {
        val events = ArrayList<() -> Unit>()
        synchronized(this) {
            if (over) return
            lastRx = now()
            try {
                reader.append(b, off, len)
                while (true) {
                    val r = reader.next() ?: break
                    handle(r, events)
                    if (over) break
                }
                partialSince = if (reader.pendingBytes == 0) 0L else partialSince.takeIf { it != 0L } ?: lastRx
            } catch (e: FramingException) {
                fail("framing: ${e.message}", events)
            } catch (e: HandshakeException) {
                fail("handshake: ${e.message}", events)
            } catch (e: SessionException) {
                fail("session: ${e.message}", events)
            } catch (e: Exception) {
                fail("protocol error: $e", events)
            }
        }
        // Listener calls run outside the lock: a listener that sends (sync on up) must not deadlock with a writer.
        events.forEach { it() }
    }

    private fun handle(r: Record, events: MutableList<() -> Unit>) {
        if (!finished && session == null) {
            if (r.kind == Kind.REJECT) {
                val reason = r.body.firstOrNull()?.toInt() ?: 0
                over = true
                events += { listener.onRejected(this, reason) }
                return
            }
            val (s, fin) = hs.receive(r)
            session = s
            pipe.write(fin.encoded())
            return
        }
        val s = session ?: return
        if (!finished) {
            hs.verifyFinish(s, r)
            finished = true
            isUp = true
            upAt = now()
            events += { listener.onUp(this) }
            return
        }
        val (ch, payload) = s.open(r)
        when (ch) {
            Proto.CH_CONTROL -> {
                val m = Message.parse(payload)
                if (m.t == T.PING) sendLocked(T.PONG, JSONObject().put("ts", m.b.optLong("ts")), re = m.id)
                else if (m.t == T.PONG) {
                    val ts = m.b.optLong("ts"); val t = now()
                    if (t >= ts && t - ts < 30_000) roundTripMs = (t - ts).toInt()
                }
                events += { listener.onMessage(this, m) }
            }
            Proto.CH_AUDIO -> AudioFrame.parse(payload)?.let { f -> events += { listener.onAudio(this, f) } }
            else -> Unit
        }
    }

    /** Sends a control message; returns its id when [expectReply] (the answer carries it as `re`), else 0. */
    fun send(t: String, b: JSONObject = JSONObject(), expectReply: Boolean = false): Int = synchronized(this) {
        if (!isUp || over) return 0
        val id = if (expectReply) ++nextId else null
        sendLocked(t, b, id = id)
        id ?: 0
    }

    /** Sends [m] with its own `id` / `re` (extensions: their requests and answers). False when the session is not up. */
    fun sendMessage(m: Message): Boolean = synchronized(this) {
        if (!isUp || over) return false
        sendLocked(m.t, m.b, m.id, m.re)
        true
    }

    private fun sendLocked(t: String, b: JSONObject, id: Int? = null, re: Int? = null) {
        val s = session ?: return
        val data = Message(t, b, id, re).toJson()
        // A record body holds at most 65534 bytes: an oversized message is refused, never sent.
        if (data.size > 65_000) return
        pipe.write(s.seal(Proto.CH_CONTROL, data).encoded())
    }

    /** Drop-late: with more than ~100 ms of audio queued in front of it, the frame is skipped (before sealing). */
    fun sendAudio(f: AudioFrame): Boolean = synchronized(this) {
        val s = session
        if (!isUp || over || s == null || pipe.backlog > MAX_AUDIO_BACKLOG) return false
        pipe.write(s.seal(Proto.CH_AUDIO, f.encoded()).encoded())
        true
    }

    fun ping() = send(T.PING, JSONObject().put("ts", now()))

    /** Ends the session from our side (the pipe is closed); [SessionListener.onFailed] follows once. */
    fun close(reason: String) {
        val events = ArrayList<() -> Unit>()
        synchronized(this) { fail(reason, events) }
        events.forEach { it() }
    }

    private fun fail(reason: String, events: MutableList<() -> Unit>) {
        if (over) return
        over = true
        isUp = false
        runCatching { pipe.close() }
        events += { listener.onFailed(this, reason) }
    }

    val isOver get() = over

    override fun toString() = "CentralSession(${pipe.label}, up=$isUp, pairing=$pairing)"

    companion object {
        const val MAX_AUDIO_BACKLOG = 4096
    }
}

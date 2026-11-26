package app.tether.link

import android.os.SystemClock
import android.util.Log
import app.tether.core.FramingException
import app.tether.core.HandshakeException
import app.tether.core.Kind
import app.tether.core.Message
import app.tether.core.PeripheralHandshake
import app.tether.core.Proto
import app.tether.core.Record
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.SessionException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

interface ConnectionListener {
    /** False while another (test) session holds the link exclusively: the peer is told to retry. */
    fun admits(c: Connection): Boolean = true
    fun onEstablished(c: Connection, newlyPaired: Boolean)
    fun onMessage(c: Connection, m: Message)
    fun onAudio(c: Connection, payload: ByteArray)
    fun onClosed(c: Connection)
}

/** One link to the iPhone: handshake, then sealed records in both directions. */
class Connection(val transport: Transport, private val keys: Keys, private val listener: ConnectionListener) {
    private enum class State { HELLO, FINISH, UP, CLOSED }

    private val reader = RecordReader()
    private var state = State.HELLO
    private var handshake: PeripheralHandshake? = null
    private var pendingStatic: ByteArray? = null
    private var pairing = false
    @Volatile var session: SecureSession? = null; private set
    /** Normal lane (sync pages, status…) and priority lane (handshake, call events, audio). */
    private val queue = LinkedBlockingDeque<Item>()
    private val prio = LinkedBlockingDeque<Item>()
    @Volatile var lastRx = SystemClock.elapsedRealtime(); private set
    /** When the hub last woke this client for being silent (see LinkPolicy.idle); 0: never. */
    @Volatile var wokeAt = 0L
    /** Since when a record has been half received (0: none). A live stream completes records within milliseconds. */
    @Volatile private var partialSince = 0L
    @Volatile private var upAt = 0L
    /** How long a record has been half received: a lost or corrupted chunk leaves the stream stuck mid-record. */
    fun stalledMs(now: Long = SystemClock.elapsedRealtime()) = partialSince.let { if (it == 0L) 0L else now - it }
    /** The client's static key once the handshake is done: identifies iPhone vs Mac across reconnects. */
    @Volatile var peerKey: ByteArray? = null; private set
    /** From the client's sync: "iphone" / "android" (the Android client), its name, and the codecs it can decode. */
    @Volatile var kind = "iphone"
    @Volatile var name = "iPhone"
    @Volatile var codecs: List<String> = listOf("pcmu")
    /** Last user action (answer, dial, take) from this client, for choosing where call audio goes. */
    @Volatile var lastAction = 0L

    private class Item(val channel: Int, val payload: ByteArray, val audio: Boolean,
                       val raw: Record? = null, val at: Long = SystemClock.elapsedRealtime(), val atNs: Long = System.nanoTime())

    /** Audio records: queue → written delay, and frames dropped late or for overflow (call statistics). */
    val audioDelay = app.tether.audio.QueueDelay()

    val isUp get() = state == State.UP

    fun start() {
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); writerLoop() }, "link-tx").start()
        transport.start({ b, n -> onBytes(b, n) }, { close("transport closed") })
        // A peer that never finishes the handshake is dropped.
        Thread({
            Thread.sleep(15_000)
            if (state != State.UP && state != State.CLOSED) close("handshake timeout")
        }, "hs-timeout").start()
    }


    fun send(m: Message) {
        if (state != State.UP) return
        val b = fits(m) ?: return
        queue.put(Item(Proto.CH_CONTROL, b, false))
    }

    /** A record body holds at most 65534 bytes: an oversized message is dropped with a log line, never sent
     *  (the writer would fail and close the link, and every re-sync would do it again). */
    private fun fits(m: Message): ByteArray? = m.toJson().takeIf { it.size <= 65_000 }
        ?: run { Log.e(TAG, "dropped ${m.t}: over the record limit"); null }

    /** Call events jump ahead of normal messages (a big sync may be queued) but keep their own order:
     *  "ringing" then "ended" must never arrive as "ended" then "ringing". */
    fun sendUrgent(m: Message) {
        if (state != State.UP) return
        val b = fits(m) ?: return
        prio.put(Item(Proto.CH_CONTROL, b, false))
    }

    /** Audio is dropped when the link is backed up (> 60 ms old at send time). */
    fun sendAudio(frame: ByteArray) {
        if (state != State.UP) return
        // Never let audio pile up: drop the oldest audio frame (it is the stalest), never a call event or record.
        if (prio.size > 25) prio.firstOrNull { it.audio }?.let { if (prio.remove(it)) audioDelay.droppedOverflow() }
        prio.put(Item(Proto.CH_AUDIO, frame, true))
    }

    private fun sendRaw(r: Record) = prio.put(Item(0, ByteArray(0), false, raw = r))

    private fun writerLoop() {
        try {
            while (state != State.CLOSED) {
                val it = prio.poll() ?: queue.poll() ?: prio.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (it.raw != null) { transport.write(it.raw.encoded()); continue }
                if (it.audio && SystemClock.elapsedRealtime() - it.at > 60) { audioDelay.droppedLate(); continue }
                val s = session ?: continue
                transport.write(s.seal(it.channel, it.payload).encoded())
                if (it.audio) audioDelay.written((System.nanoTime() - it.atNs) / 1e6)
            }
        } catch (e: Exception) {
            close("write failed: ${e.message}")
        }
    }

    @Synchronized private fun onBytes(b: ByteArray, n: Int) {
        if (state == State.CLOSED) return
        lastRx = SystemClock.elapsedRealtime()
        try {
            reader.append(b, 0, n)
            while (true) {
                val r = reader.next() ?: break
                handle(r)
                if (state == State.CLOSED) return
            }
            partialSince = if (reader.pendingBytes == 0) 0L else partialSince.takeIf { it != 0L } ?: lastRx
        } catch (e: FramingException) {
            close("framing: ${e.message}")
        } catch (e: HandshakeException) {
            close("handshake: ${e.message}")
        } catch (e: SessionException) {
            close("session: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "link error", e)
            close("error: $e")
        }
    }

    private fun handle(r: Record) {
        when (state) {
            State.HELLO -> {
                if (r.kind != Kind.HELLO) throw HandshakeException("expected HELLO, got ${r.kind}")
                val hs = PeripheralHandshake(keys.staticPrivate)
                handshake = hs
                val reply = if (!listener.admits(this)) Record(Kind.REJECT, byteArrayOf(app.tether.core.Reject.BUSY.toByte()))
                    else hs.receiveHello(r, { k -> keys.isKnown(k) }) { k -> keys.tokenFor(k) }
                sendRaw(reply)
                if (reply.kind == Kind.REJECT) {
                    Log.i(TAG, "rejected hello: reason ${reply.body[0]}")
                    // Let the REJECT go out before closing.
                    Thread { Thread.sleep(300); close("rejected") }.start()
                    state = State.FINISH
                    handshake = null
                    return
                }
                pairing = hs.hello!!.mode == Proto.MODE_PAIR
                pendingStatic = hs.hello!!.staticKey
                state = State.FINISH
            }
            State.FINISH -> {
                val hs = handshake ?: throw HandshakeException("rejected peer kept talking")
                val (s, fin) = hs.receiveFinish(r)
                if (pairing && transport.label == "memory") {
                    keys.closePairing() // on-device test session: never replaces the real pairing
                } else if (pairing) {
                    // Name and kind arrive with the first sync; until then it is listed as a new device.
                    keys.addCentral(pendingStatic!!, keys.central(pendingStatic)?.name ?: "New device")
                    keys.completePairing(pendingStatic!!)
                    Log.i(TAG, "paired with new central (${keys.centrals().size} paired)")
                }
                session = s
                peerKey = pendingStatic
                sendRaw(fin)
                state = State.UP
                upAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "link up via ${transport.label} (${if (pairing) "pair" else "resume"})")
                listener.onEstablished(this, pairing)
            }
            State.UP -> {
                // A central that dropped its side (and kept the GATT connection, as iOS does over classic
                // Bluetooth) starts again with a HELLO on the same pipe: end this session and hand the HELLO to a
                // new one, instead of failing "not sealed" and leaving the central waiting for an answer.
                if (r.kind == Kind.HELLO && transport is GattTransport) {
                    val t = transport
                    close("peer started a new session")
                    t.peerRestarted(r.encoded())
                    return
                }
                val (ch, payload) = session!!.open(r)
                when (ch) {
                    Proto.CH_CONTROL -> listener.onMessage(this, Message.parse(payload))
                    Proto.CH_AUDIO -> listener.onAudio(this, payload)
                    else -> Log.w(TAG, "unknown channel $ch")
                }
            }
            State.CLOSED -> Unit
        }
    }

    fun close(reason: String) {
        synchronized(this) {
            if (state == State.CLOSED) return
            val wasUp = state == State.UP
            state = State.CLOSED
            Log.i(TAG, "link closed (${transport.label}): $reason")
            val lasted = if (upAt > 0) " after ${(SystemClock.elapsedRealtime() - upAt) / 1000} s" else ""
            LinkLog.add("link down ($name, ${transport.label})$lasted: $reason")
            if (!wasUp) { transport.close(); return }
        }
        // We end it (not the iPhone, not a dead link): wake the iPhone app first. A suspended app is not woken for its
        // closed channel and only noticed ~30 s later, when iOS dropped the idle connection (seen 2026-11-22).
        if (reason != "transport closed" && transport.label != "memory") LinkService.running?.peripheral?.wake(transport.address)
        transport.close()
        listener.onClosed(this)
    }

    companion object { const val TAG = "Tether" }
}

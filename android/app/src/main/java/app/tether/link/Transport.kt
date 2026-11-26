package app.tether.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A reliable, ordered byte pipe to the iPhone. */
interface Transport {
    val label: String
    val address: String
    /** A test transport: whatever its client asks is never done for real (no call, SMS or edit), however late it runs. */
    val dry: Boolean get() = false
    fun start(onBytes: (ByteArray, Int) -> Unit, onClosed: () -> Unit)
    fun write(bytes: ByteArray)
    fun close()
}

/** L2CAP connection-oriented channel (primary). */
class L2capTransport(private val socket: BluetoothSocket) : Transport {
    override val label = "l2cap"
    @SuppressLint("MissingPermission")
    override val address: String = socket.remoteDevice?.address ?: "?"
    private val out = socket.outputStream
    @Volatile private var closed = false

    override fun start(onBytes: (ByteArray, Int) -> Unit, onClosed: () -> Unit) {
        Thread({
            // Uplink audio is decoded on this thread (Connection → Hub.onAudio): audio priority.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(4096)
            try {
                val input = socket.inputStream
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onBytes(buf, n)
                }
            } catch (e: IOException) {
                if (!closed) Log.i(TAG, "l2cap read ended: ${e.message}")
            } finally {
                close()
                onClosed()
            }
        }, "l2cap-rx").start()
    }

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("closed")
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object { const val TAG = "Tether" }
}

/**
 * GATT fallback: RX writes and TX notifications carry the same byte stream as L2CAP,
 * chunked to MTU-3. Notifications are flow-controlled by onNotificationSent.
 */
@SuppressLint("MissingPermission")
class GattTransport(
    private val server: BluetoothGattServer,
    private val device: BluetoothDevice,
    private val tx: BluetoothGattCharacteristic,
) : Transport {
    override val label = "gatt"
    override val address: String = device.address
    @Volatile var mtu = 23
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val sent = java.util.concurrent.Semaphore(1)
    val isClosed get() = closed

    /** Set by the peripheral: the central started a new session on this GATT connection (its first record). */
    var onPeerRestart: ((ByteArray) -> Unit)? = null
    fun peerRestarted(firstRecord: ByteArray) { onPeerRestart?.invoke(firstRecord) }
    private var onBytes: ((ByteArray, Int) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    @Volatile private var closed = false

    override fun start(onBytes: (ByteArray, Int) -> Unit, onClosed: () -> Unit) {
        this.onBytes = onBytes
        this.onClosed = onClosed
        Thread({
            try {
                var retry: ByteArray? = null   // a chunk the stack refused: sent again first, so order is kept
                while (!closed) {
                    val c = retry ?: chunks.poll(1, TimeUnit.SECONDS)?.also { queued.addAndGet(-it.size) } ?: continue
                    retry = null
                    val acquired = sent.tryAcquire(2, TimeUnit.SECONDS)
                    if (!acquired) Log.w(TAG, "gatt notify ack timeout")
                    awaitingAck = true
                    val rc = GattCompat.notify(server, device, tx, c)
                    if (rc != GattCompat.SUCCESS) {
                        awaitingAck = false
                        if (acquired) sent.release()
                        Thread.sleep(10)
                        retry = c
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "gatt-tx").start()
    }

    fun received(value: ByteArray) {
        onBytes?.invoke(value, value.size)
    }

    /** A TX notification was delivered. Wake notifications share the callback: only an awaited one counts. */
    @Volatile private var awaitingAck = false
    fun notificationSent() { if (awaitingAck) { awaitingAck = false; sent.release() } }

    /** Bytes queued but not yet notified: the writer waits above [MAX_QUEUED] (back-pressure, see write). */
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("closed")
        // Back-pressure: without it a sync put megabytes into this queue at once (a GATT pipe moves a few kB/s at a
        // 23-byte MTU), the session could not answer anything for minutes, and a stale pipe kept sending for long.
        while (queued.get() > MAX_QUEUED) { if (closed) throw IOException("closed"); Thread.sleep(5) }
        for (c in chunk(bytes, mtu)) { queued.addAndGet(c.size); chunks.put(c) }
    }

    override fun close() {
        if (closed) return
        closed = true
        chunks.clear()
        onClosed?.invoke()
    }

    companion object {
        const val TAG = "Tether"; const val MAX_QUEUED = 32 * 1024
        /** [bytes] cut into notification-sized pieces (MTU - 3, at least 20), in order. */
        fun chunk(bytes: ByteArray, mtu: Int): List<ByteArray> {
            val step = (mtu - 3).coerceAtLeast(20)
            return (bytes.indices step step).map { bytes.copyOfRange(it, minOf(bytes.size, it + step)) }
        }
    }
}

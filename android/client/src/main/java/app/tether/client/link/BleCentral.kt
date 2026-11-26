package app.tether.client.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import app.tether.core.AudioFrame
import app.tether.core.Message
import app.tether.core.PairingCode
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the link tells the app. Called on the link thread (audio: on the transport's reader thread). */
interface LinkEvents {
    fun onState(s: BleCentral.State)
    fun onUp(transport: String)
    fun onDown()
    fun onMessage(m: Message)
    fun onAudio(f: AudioFrame)
    /** Pairing code expired / used, or the Android phone does not know this phone. */
    fun onProblem(kind: String)
    fun log(line: String)
}

/**
 * BLE central side of the link (protocol §1): finds the Android phone by its service UUID (and tries the address it
 * was last reached at), connects, reads the PSM, opens an L2CAP channel (GATT pipe as fallback), runs a
 * [CentralSession] over it, and reconnects for ever with the iPhone's rules ([ClientLinkPolicy]): at once after a
 * failure, a session restarted on a connection that is still up, a 12 s setup watchdog, a stall check, back-off only
 * when setup keeps failing. All state lives on one handler thread.
 */
@SuppressLint("MissingPermission")
class BleCentral(private val ctx: Context, private val identity: Identity, private val events: LinkEvents) {
    enum class State { OFF, UNPAIRED, SEARCHING, CONNECTING, HANDSHAKING, CONNECTED }

    private val thread = HandlerThread("link").apply { start() }
    private val h = Handler(thread.looper)
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val policy = ClientLinkPolicy()

    @Volatile var state = State.UNPAIRED; private set
    @Volatile var transport = "none"; private set
    @Volatile private var session: CentralSession? = null
    @Volatile private var gatt: BluetoothGatt? = null
    /** An L2CAP channel still being opened (closed when the GATT pipe takes over after the timeout). */
    @Volatile private var opening: BluetoothSocket? = null
    private var gattConnected = false
    private var pairing: PairingCode? = null
    private var settingUp = false
    private var scanning = false
    private var scanStartedAt = 0L
    private var psm = -1
    private var mtu = 23
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var wakeChar: BluetoothGattCharacteristic? = null
    private var l2capSocket: BluetoothSocket? = null
    private var gattPipe: GattPipe? = null
    private var sessionSince = 0L
    private var started = false
    /** Direct connects to the last known address since the last session (or app start). */
    private var knownTries = 0
    /** One pending retry at a time; one watchdog of each kind. */
    private val retry = Runnable { startIfReady() }
    private val setupWatchdog = Runnable {
        if (state != State.CONNECTED && gattConnected) failAndRetry(ClientLinkPolicy.SETUP_TIMEOUT)
    }
    private val connectWatchdog = Runnable {
        val g = gatt
        if (g != null && !gattConnected && !settingUp) {
            log("connect still pending after 10 s: renewing")
            closeGatt()
            startIfReady()
        }
    }
    private val l2capTimeout = Runnable {
        opening?.let { runCatching { it.close() } }; opening = null
        startGattFallback("L2CAP timeout")
    }

    val isConnected get() = state == State.CONNECTED && session?.isUp == true
    val roundTripMs get() = session?.roundTripMs
    val isPaired get() = identity.isPaired

    // ------------------------------------------------------------------ public API (any thread)

    fun start() = h.post {
        if (!started) {
            started = true
            ctx.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            h.postDelayed(tick, TICK_MS)
        }
        startIfReady()
    }

    fun pair(code: PairingCode) = h.post {
        log("pairing with a new Android phone")
        pairing = code
        identity.peerKey = code.peripheralKey
        identity.peerName = code.deviceName
        identity.lastAddress = null
        disconnect("pairing")
        policy.forget()
        startIfReady()
    }

    fun unpair() = h.post {
        session?.takeIf { it.isUp }?.send(app.tether.core.T.UNPAIR)
        h.postDelayed({
            identity.peerKey = null; identity.peerName = null; identity.lastAddress = null
            pairing = null
            disconnect("unpaired")
            stopScan()
            setState(State.UNPAIRED)
        }, 300)
    }

    /** Sends a control message; returns the request id when [expectReply] (0 when not connected). */
    fun send(t: String, b: JSONObject = JSONObject(), expectReply: Boolean = false): Int =
        session?.takeIf { it.isUp }?.send(t, b, expectReply) ?: 0

    fun sendAudio(f: AudioFrame): Boolean = session?.takeIf { it.isUp }?.sendAudio(f) ?: false

    /** A control message with its own `id` / `re` (extensions). */
    fun sendMessage(m: Message): Boolean = session?.takeIf { it.isUp }?.sendMessage(m) ?: false

    /** The app came to the front without a session: start over now, forgetting the back-off. */
    fun appActive() = h.post {
        if (!identity.isPaired || session != null || settingUp) return@post
        log("app active without a session: connecting afresh")
        policy.forget()
        knownTries = 0
        h.removeCallbacks(retry)
        if (gatt != null && !gattConnected) closeGatt()
        startIfReady()
    }

    /** During calls: the fast connection interval (the central asks for it on Android). */
    fun setHighPriority(on: Boolean) = h.post {
        gatt?.takeIf { gattConnected }?.let { g ->
            runCatching { g.requestConnectionPriority(if (on) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        }
    }

    fun ping() = h.post { session?.takeIf { it.isUp }?.ping() }

    // ------------------------------------------------------------------ connecting

    private fun setState(s: State) { if (state != s) { state = s; events.onState(s) } }
    private fun log(s: String) = events.log(s)

    private fun startIfReady() {
        val a = adapter
        if (a == null || !a.isEnabled) { setState(State.OFF); return }
        if (!identity.isPaired) { setState(State.UNPAIRED); return }
        if (gatt != null) return   // connecting or connected
        // The address it was last reached at, twice: it may have rotated since (the service scan finds it then).
        identity.lastAddress?.takeIf { pairing == null && knownTries < 2 && BluetoothAdapter.checkBluetoothAddress(it) }?.let { addr ->
            knownTries++
            log("connecting to the known address")
            connect(a.getRemoteDevice(addr))
        }
        if (gatt == null) setState(State.SEARCHING)
        startScan()
    }

    private fun connect(d: BluetoothDevice) {
        gattConnected = false
        setState(State.CONNECTING)
        gatt = d.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        h.removeCallbacks(connectWatchdog)
        h.postDelayed(connectWatchdog, ClientLinkPolicy.CONNECT_WATCHDOG_MS)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        // A low-latency scan stops being honoured after a long time: renew a scan older than 10 minutes.
        if (scanning && SystemClock.elapsedRealtime() - scanStartedAt < 600_000) return
        if (scanning) runCatching { scanner.stopScan(scanCallback) }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback); scanning = true; scanStartedAt = SystemClock.elapsedRealtime() }
            .onFailure { log("scan not started: ${it.message}") }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { h.post { discovered(result.device, result.rssi) } }
        override fun onScanFailed(errorCode: Int) { h.post { scanning = false; log("scan failed: $errorCode"); h.postDelayed(retry, 5_000) } }
    }

    private fun discovered(d: BluetoothDevice, rssi: Int) {
        if (!identity.isPaired) return
        val cur = gatt
        if (cur != null) {
            if (gattConnected || cur.device.address == d.address) return
            // The pending connect targets a stale address (the Android phone's address rotated): switch.
            closeGatt()
        }
        log("found the service at ${short(d.address)} (rssi $rssi)")
        stopScan()
        connect(d)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> h.post { log("Bluetooth on"); startIfReady() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> h.post {
                    disconnect("bluetooth off"); scanning = false; setState(State.OFF)
                }
            }
        }
    }

    // ------------------------------------------------------------------ GATT

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { h.post { connectionChanged(g, status, newState) } }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { h.post { if (g === gatt) { mtu = m; g.discoverServices() } } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { h.post { if (g === gatt) servicesDiscovered(g) } }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = c.value
            h.post { if (g === gatt) characteristicRead(g, c, v, status) }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            h.post { if (g === gatt) characteristicRead(g, c, value, status) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { h.post { if (g === gatt) descriptorWritten(d, status) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { if (g === gatt) gattPipe?.written() }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return   // the variant below is called
            @Suppress("DEPRECATION") changed(g, c, c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = changed(g, c, value)
    }

    private fun changed(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        // The wake characteristic needs nothing here: an Android client is never suspended like an iPhone app.
        if (g === gatt && c.uuid == TX_CHAR) gattPipe?.received(value)
    }

    private fun connectionChanged(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) { if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }; return }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            h.removeCallbacks(connectWatchdog)
            gattConnected = true
            log("connected to ${short(g.device.address)}")
            stopScan()
            if (settingUp) return
            beginSetup(g, fresh = true)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            log("disconnected (status $status)")
            val wasConnecting = !gattConnected
            disconnect("peripheral disconnected")
            setState(if (identity.isPaired) State.SEARCHING else State.UNPAIRED)
            // A connect that never came up (status 133 and friends) is a failure like any other.
            if (wasConnecting) log("connect failed")
            reconnect(policy.disconnected())
        }
    }

    private fun beginSetup(g: BluetoothGatt, fresh: Boolean) {
        settingUp = true
        setState(State.CONNECTING)
        h.removeCallbacks(setupWatchdog)
        h.postDelayed(setupWatchdog, ClientLinkPolicy.SETUP_WATCHDOG_MS)
        psm = -1
        if (fresh) {
            runCatching { g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
            if (!g.requestMtu(517)) g.discoverServices()
        } else g.discoverServices()
    }

    private fun servicesDiscovered(g: BluetoothGatt) {
        val s = g.getService(SERVICE) ?: run { failAndRetry("service not found"); return }
        rxChar = s.getCharacteristic(RX_CHAR); txChar = s.getCharacteristic(TX_CHAR); wakeChar = s.getCharacteristic(WAKE_CHAR)
        val p = s.getCharacteristic(PSM_CHAR)
        // Nothing here needs link encryption: Tether's own pairing secures the link, never a Bluetooth bond.
        if (p == null || !g.readCharacteristic(p)) subscribeWake(g)
    }

    private fun characteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray?, status: Int) {
        if (c.uuid != PSM_CHAR) return
        psm = if (status == BluetoothGatt.GATT_SUCCESS && v != null && v.size >= 2) (v[0].toInt() and 0xff) or ((v[1].toInt() and 0xff) shl 8) else -1
        if (psm < 0) log("PSM read failed (status $status): GATT pipe")
        subscribeWake(g)
    }

    /** The central always subscribes to the wake characteristic (protocol §1); then the transport opens. */
    private fun subscribeWake(g: BluetoothGatt) {
        val w = wakeChar
        if (w == null || !writeCccd(g, w)) openTransport()
    }

    private fun writeCccd(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val d = c.getDescriptor(CCCD) ?: return false
        g.setCharacteristicNotification(c, true)
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
    }

    private fun descriptorWritten(d: BluetoothGattDescriptor, status: Int) {
        when (d.characteristic.uuid) {
            WAKE_CHAR -> if (transport == "none" && settingUp) openTransport()
            TX_CHAR -> if (status == BluetoothGatt.GATT_SUCCESS && transport == "none" && l2capSocket == null) {
                val g = gatt ?: return
                val pipe = GattPipe(g, rxChar ?: return)
                gattPipe = pipe
                openSession("gatt", pipe)
            } else if (status != BluetoothGatt.GATT_SUCCESS) failAndRetry("GATT subscribe failed: $status")
        }
    }

    private fun openTransport() {
        if (transport != "none") return
        val g = gatt ?: return
        if (psm <= 0) { startGattFallback("no PSM"); return }
        log("opening L2CAP")
        val d = g.device
        val p = psm
        h.removeCallbacks(l2capTimeout)
        h.postDelayed(l2capTimeout, ClientLinkPolicy.L2CAP_TIMEOUT_MS)
        Thread({
            val sock = try { d.createInsecureL2capChannel(p).also { opening = it; it.connect() } } catch (e: IOException) {
                h.post { if (g === gatt && transport == "none") startGattFallback("L2CAP open failed: ${e.message}") }
                null
            }
            if (sock != null) h.post {
                if (opening === sock) opening = null
                h.removeCallbacks(l2capTimeout)
                // Late or duplicate channel (the GATT pipe already runs, or this connection is gone): never restart a live session.
                if (g !== gatt || transport != "none" || !settingUp) { runCatching { sock.close() }; return@post }
                l2capSocket = sock
                openSession("l2cap", L2capPipe(sock))
            }
        }, "l2cap-open").start()
    }

    private fun startGattFallback(why: String) {
        val g = gatt ?: return
        val tx = txChar
        if (transport != "none" || tx == null || rxChar == null || !settingUp) return
        log("GATT fallback: $why")
        if (!writeCccd(g, tx)) failAndRetry("GATT subscribe refused")
    }

    // ------------------------------------------------------------------ session

    private fun openSession(name: String, pipe: Pipe) {
        val peer = identity.peerKey ?: run { failAndRetry("no peer key"); return }
        transport = name
        setState(State.HANDSHAKING)
        val s = CentralSession(identity.keyPair, peer, pairing?.token, pipe, sessionListener, now = { SystemClock.elapsedRealtime() })
        session = s
        if (pipe is L2capPipe) pipe.startReading(s) { h.post { if (session === s) failAndRetry("L2CAP stream ended") } }
        s.start()
    }

    private val sessionListener = object : SessionListener {
        override fun onUp(s: CentralSession) {
            h.post {
                if (session !== s) return@post
                if (pairing != null) { pairing = null; log("paired") }
                gatt?.let { identity.lastAddress = it.device.address }
                stopScan()
                settingUp = false
                h.removeCallbacks(setupWatchdog)
                h.removeCallbacks(retry)
                policy.up()
                knownTries = 0
                sessionSince = SystemClock.elapsedRealtime()
                lastPingAt = sessionSince
                setState(State.CONNECTED)
                log("session up over $transport")
                events.onUp(transport)
            }
        }
        override fun onMessage(s: CentralSession, m: Message) { if (session === s) events.onMessage(m) }
        override fun onAudio(s: CentralSession, f: AudioFrame) { if (session === s) events.onAudio(f) }
        override fun onRejected(s: CentralSession, reason: Int) { h.post { if (session === s) rejected(reason) } }
        override fun onFailed(s: CentralSession, reason: String) { h.post { if (session === s) failAndRetry(reason) } }
    }

    private fun rejected(reason: Int) {
        log("rejected by the Android phone: $reason")
        when (val r = policy.rejected(reason, pairing != null)) {
            ClientLinkPolicy.Reject.PairingExpired -> {
                // Pairing code expired or already used: stop this pairing attempt.
                events.onProblem("pairing_expired")
                pairing = null
                identity.peerKey = null
                disconnect("pairing rejected")
                stopScan()
                setState(State.UNPAIRED)
            }
            is ClientLinkPolicy.Reject.Wait -> {
                // 2: the Android phone does not (currently) accept this phone. Keep the keys (it may only be busy with a
                // test or restoring) but back off instead of retrying at once. 4: busy (a test holds the link).
                r.problem?.let { events.onProblem(it) }
                disconnect(if (reason == 4) "peripheral busy" else "rejected $reason")
                setState(State.SEARCHING)
                h.postDelayed(retry, r.ms)
            }
            ClientLinkPolicy.Reject.Fail -> failAndRetry("rejected $reason")
        }
    }

    private fun failAndRetry(reason: String) {
        log("link failed: $reason")
        val next = policy.failed(reason, connectionUp = gattConnected && gatt != null, pairing = pairing != null)
        if (next == ClientLinkPolicy.Next.RestartOnConnection) {
            // The session ended but the Bluetooth connection is still up: a new session on it (twice in a row at most).
            resetSession(reason)
            log("restarting the session on the open connection ($reason)")
            beginSetup(gatt!!, fresh = false)
            return
        }
        disconnect(reason)
        setState(if (identity.isPaired) State.SEARCHING else State.UNPAIRED)
        reconnect(next)
    }

    private fun reconnect(next: ClientLinkPolicy.Next) {
        val ms = (next as? ClientLinkPolicy.Next.Reconnect)?.afterMs ?: 0
        h.removeCallbacks(retry)
        if (ms == 0L) { log("reconnecting at once"); h.postDelayed(retry, 300) }
        else { log("backing off ${ms / 1000} s"); startScan(); h.postDelayed(retry, ms) }
    }

    /** Ends the session (channel, pipe, timers) but not the Bluetooth connection. */
    private fun resetSession(reason: String) {
        val s = session
        session = null
        if (sessionSince > 0) {
            log("session over $transport ended after ${(SystemClock.elapsedRealtime() - sessionSince) / 1000} s: $reason")
            sessionSince = 0
        }
        h.removeCallbacks(setupWatchdog); h.removeCallbacks(l2capTimeout)
        val wasUp = s?.isUp == true
        s?.close(reason)
        runCatching { l2capSocket?.close() }
        l2capSocket = null
        opening?.let { runCatching { it.close() } }; opening = null
        gattPipe?.close(); gattPipe = null
        transport = "none"
        settingUp = false
        silenceSince = 0
        if (wasUp) events.onDown()
    }

    private fun disconnect(reason: String) {
        resetSession(reason)
        closeGatt()
    }

    private fun closeGatt() {
        h.removeCallbacks(connectWatchdog)
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        gattConnected = false
    }

    // ------------------------------------------------------------------ keep-alive

    private var lastPingAt = 0L
    private var silenceSince = 0L

    private val tick: Runnable = object : Runnable {
        override fun run() {
            h.postDelayed(this, TICK_MS)
            val s = session ?: return
            val now = SystemClock.elapsedRealtime()
            if (s.stalledMs(now) > ClientLinkPolicy.STALL_MS) { failAndRetry("stream stalled mid-record"); return }
            if (!s.isUp) return
            if (now - s.lastRx > ClientLinkPolicy.SILENCE_MS) {
                // Ping first, then judge: a CPU that slept through our timers is not a dead link.
                if (silenceSince == 0L) { silenceSince = now; s.ping() }
                else if (now - silenceSince > ClientLinkPolicy.SILENCE_GRACE_MS) { failAndRetry("no answer for ${(now - s.lastRx) / 1000} s"); return }
            } else silenceSince = 0
            if (now - lastPingAt >= ClientLinkPolicy.PING_MS) { lastPingAt = now; s.ping() }
        }
    }

    // ------------------------------------------------------------------ transports

    /** L2CAP channel: a writer thread (writes may block on credits) and a reader thread. */
    private class L2capPipe(private val sock: BluetoothSocket) : Pipe {
        override val label = "l2cap"
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicInteger(0)
        @Volatile private var closed = false
        override val backlog get() = queued.get()

        init {
            Thread({
                try {
                    val out = sock.outputStream
                    while (!closed) {
                        val b = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        queued.addAndGet(-b.size)
                        out.write(b); out.flush()
                    }
                } catch (_: Exception) { close() }
            }, "l2cap-tx").apply { priority = Thread.MAX_PRIORITY }.start()
        }

        fun startReading(s: CentralSession, onEnd: () -> Unit) {
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val buf = ByteArray(4096)
                try {
                    val input = sock.inputStream
                    while (!closed) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) s.received(buf, 0, n)
                    }
                } catch (_: IOException) {
                } finally {
                    val mine = !closed
                    close()
                    if (mine) onEnd()
                }
            }, "l2cap-rx").start()
        }

        override fun write(bytes: ByteArray) { if (closed) return; queued.addAndGet(bytes.size); queue.put(bytes) }
        override fun close() { if (closed) return; closed = true; queue.clear(); runCatching { sock.close() } }
    }

    /** GATT pipe: RX writes (without response, one in flight, chunked to MTU-3) and TX notifications. */
    private inner class GattPipe(private val g: BluetoothGatt, private val rx: BluetoothGattCharacteristic) : Pipe {
        override val label = "gatt"
        private val chunks = java.util.ArrayDeque<ByteArray>()
        private var inFlight = false
        private var inFlightAt = 0L
        private val queued = AtomicInteger(0)
        @Volatile private var closed = false
        override val backlog get() = queued.get()

        override fun write(bytes: ByteArray) {
            if (closed) return
            val step = maxOf(20, mtu - 3)
            synchronized(chunks) {
                var i = 0
                while (i < bytes.size) { val e = minOf(bytes.size, i + step); chunks.add(bytes.copyOfRange(i, e)); i = e }
            }
            queued.addAndGet(bytes.size)
            h.post { pump() }
        }

        fun written() { h.post { inFlight = false; pump() } }

        fun received(v: ByteArray) { session?.takeIf { gattPipe === this }?.received(v) }

        private fun pump() {
            if (closed) return
            // A write whose callback never came (seen on some stacks): go on after a second.
            if (inFlight && SystemClock.elapsedRealtime() - inFlightAt < 1000) return
            val c = synchronized(chunks) { chunks.pollFirst() } ?: run { inFlight = false; return }
            inFlight = true
            inFlightAt = SystemClock.elapsedRealtime()
            val ok = if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(rx, c, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; rx.value = c; g.writeCharacteristic(rx) }
            if (ok) queued.addAndGet(-c.size)
            else {
                // The stack is busy: keep the order, try again shortly.
                synchronized(chunks) { chunks.addFirst(c) }
                inFlight = false
                h.postDelayed({ pump() }, 10)
            }
        }

        override fun close() { closed = true; synchronized(chunks) { chunks.clear() } }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("5ABF5C3C-2BF1-4B1C-9E8E-5D153F835BD5")
        val PSM_CHAR: UUID = UUID.fromString("6C0D1EB1-67F4-4181-9752-9CC90D6EFF88")
        val RX_CHAR: UUID = UUID.fromString("214E1CD9-F397-4A0F-BB6C-ED1D8C7A38A5")
        val TX_CHAR: UUID = UUID.fromString("7BA52139-0888-4234-A49D-0271E0F2A647")
        val WAKE_CHAR: UUID = UUID.fromString("9A3F1C52-7E0B-4D8A-B1E6-2C5D4F7A8E31")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TICK_MS = 5_000L
        fun short(addr: String) = "…" + addr.takeLast(5)
    }
}

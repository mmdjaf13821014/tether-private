package app.tether.link

import app.tether.core.LinkPolicy
import app.tether.core.LinkSecurity
import app.tether.core.OldAndroid
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE peripheral: advertises the Tether service, serves the PSM characteristic (encrypted read,
 * which triggers one-time bonding), accepts L2CAP channels, and offers the GATT fallback pipe.
 */
@SuppressLint("MissingPermission")
class BlePeripheral(private val ctx: Context, private val onTransport: (Transport) -> Unit) {
    companion object {
        const val TAG = "Tether"
        val SERVICE: UUID = UUID.fromString("5ABF5C3C-2BF1-4B1C-9E8E-5D153F835BD5")
        val PSM_CHAR: UUID = UUID.fromString("6C0D1EB1-67F4-4181-9752-9CC90D6EFF88")
        val RX_CHAR: UUID = UUID.fromString("214E1CD9-F397-4A0F-BB6C-ED1D8C7A38A5")
        val TX_CHAR: UUID = UUID.fromString("7BA52139-0888-4234-A49D-0271E0F2A647")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Notify-only "wake" characteristic: a notification wakes a suspended iOS app reliably. */
        val WAKE_CHAR: UUID = UUID.fromString("9A3F1C52-7E0B-4D8A-B1E6-2C5D4F7A8E31")
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var l2cap: BluetoothServerSocket? = null
    private var advSet: AdvertisingSet? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val gattPipes = ConcurrentHashMap<String, GattTransport>()
    private var wakeChar: BluetoothGattCharacteristic? = null
    private val wakeSubscribers = ConcurrentHashMap.newKeySet<String>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()
    /** Last connection parameters per link, for the latency report. */
    val linkParams = ConcurrentHashMap<String, String>()
    /** LE PHY of each link ("2M", "1M", "coded"; tx/rx when they differ), from the stack's PHY callbacks. */
    val linkPhy = ConcurrentHashMap<String, String>()
    @Volatile var psm = 0; private set
    @Volatile var running = false; private set
    @Volatile var advertising = false; private set

    val enabled get() = adapter?.isEnabled == true

    @Synchronized fun start() {
        if (running || !enabled) return
        running = true
        try {
            // L2CAP channels need Android 10; Android 8 and 9 link over GATT only (the PSM read answers empty).
            if (Build.VERSION.SDK_INT >= 29) startL2cap()
            startGatt()
            startAdvertising()
            Log.i(TAG, "peripheral up, psm=$psm")
        } catch (t: Throwable) {
            Log.e(TAG, "peripheral start failed", t)
            stop()
        }
    }

    @Synchronized fun stop() {
        running = false
        advertising = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) }   // also a pending start's set
        startPending = false
        advSet = null
        runCatching { l2cap?.close() }
        l2cap = null
        gattPipes.values.forEach { it.close() }
        gattPipes.clear()
        runCatching { server?.close() }
        server = null
        dropKeepers()
    }

    @android.annotation.TargetApi(29) // started only on Android 10+
    private fun startL2cap() {
        val ss = adapter!!.listenUsingInsecureL2capChannel()   // insecure on purpose: see LinkSecurity
        l2cap = ss
        psm = ss.psm
        Thread({
            while (running) {
                try {
                    val sock = ss.accept()
                    Log.i(TAG, "l2cap accepted from ${sock.remoteDevice.address}")
                    onTransport(L2capTransport(sock))
                } catch (e: IOException) {
                    if (running) {
                        // The listener died while we still run (Bluetooth hiccup): open a new one, or every later
                        // link would wait for the L2CAP timeout and fall back to GATT.
                        Log.w(TAG, "l2cap accept failed: ${e.message}; reopening the listener")
                        LinkLog.add("L2CAP listener failed; reopening")
                        Thread.sleep(1000)
                        if (running) runCatching { startL2cap() }.onFailure { LinkLog.add("L2CAP reopen failed: ${it.message}") }
                    }
                    break
                }
            }
        }, "l2cap-accept").start()
    }

    private fun startGatt() {
        val s = manager.openGattServer(ctx, gattCallback) ?: throw IOException("no GATT server")
        server = s
        // No characteristic or descriptor asks for link encryption: the link is secured by Tether's own pairing
        // (P-256 + AES-GCM), never by a Bluetooth bond. An encrypted permission made the Android phone check the LE key of
        // whatever bond it has with the iPhone; the bond made for Bluetooth tethering left that key recorded with
        // size 0, and every read failed "insufficient encryption key size" (2026-11-22). See LinkSecurity.
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(PSM_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ, LinkSecurity.CHAR_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(RX_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            LinkSecurity.CHAR_WRITE))
        val tx = BluetoothGattCharacteristic(TX_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY, LinkSecurity.CHAR_READ)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, LinkSecurity.DESC_READ_WRITE))
        service.addCharacteristic(tx)
        txChar = tx
        val wake = BluetoothGattCharacteristic(WAKE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY, LinkSecurity.CHAR_READ)
        wake.addDescriptor(BluetoothGattDescriptor(CCCD, LinkSecurity.DESC_READ_WRITE))
        service.addCharacteristic(wake)
        wakeChar = wake
        s.addService(service)
        // The mask must match Android's own values (it is unit-tested without the framework).
        val androidMask = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM or
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM or
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED or BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM
        check(androidMask == LinkSecurity.SECURE_MASK && BluetoothGattCharacteristic.PERMISSION_READ == LinkSecurity.PERMISSION_READ &&
            BluetoothGattCharacteristic.PERMISSION_WRITE == LinkSecurity.PERMISSION_WRITE) { "LinkSecurity constants differ from Android's" }
        securityCheck = LinkSecurity.check(service.characteristics.map { c -> c.uuid.toString() to (listOf(c.permissions) + c.descriptors.map { it.permissions }) })
        if (securityCheck != "ok") { Log.e(TAG, "GATT security check: $securityCheck"); LinkLog.add("GATT security check failed: $securityCheck") }
    }

    /** "ok" when nothing in the service asks for link encryption (shown by the state hook). */
    @Volatile var securityCheck = "not started"; private set

    /** A start was requested and its callback has not come yet: never start (or stop) another one meanwhile. */
    @Volatile private var startPending = false

    private fun advParams() = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            // ~100 ms while nobody is linked (quick reconnects), ~250 ms while linked: less radio time taken
            // from the connection events (call audio) and less battery.
            .setInterval(if (linked) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

    /**
     * One advertising set for the life of the service. Stopping and starting sets on every link change (to switch
     * the interval) leaked them: a stop while a start was still pending orphaned that set, it kept its slot, and
     * after enough link changes Android refused all advertising ("too many advertisers", seen 2026-11-22), so the
     * iPhone could not find the phone. Interval changes now go to the same set.
     */
    private fun startAdvertising() {
        if (advSet != null || startPending) return
        val adv = adapter!!.bluetoothLeAdvertiser ?: throw IOException("no advertiser")
        val params = advParams()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .setIncludeDeviceName(false)
            .build()
        startPending = true
        try { adv.startAdvertisingSet(params, data, AdvertiseData.Builder().build(), null, null, advCallback) }
        catch (t: Throwable) { startPending = false; throw t }
    }

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            startPending = false
            advSet = set
            advertising = status == ADVERTISE_SUCCESS
            // The link changed while the start was pending: give the set the interval wanted now.
            if (status == ADVERTISE_SUCCESS && set != null && linked != startedLinked) applyInterval(set)
            Log.i(TAG, "advertising started status=$status txPower=$txPower")
            if (status != ADVERTISE_SUCCESS) LinkLog.add("advertising failed to start: status=$status")
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advertising = false
            if (set === advSet) advSet = null
            if (!stoppingOurselves) LinkLog.add("advertising stopped by the system")
            stoppingOurselves = false
        }
    }

    /**
     * The iPhone can only find the Android phone again while it advertises. Samsung's stack can stop an advertising set
     * (seen after Bluetooth reconfigurations); nothing restarted it. Called every 10 s by the hub.
     */
    @Volatile private var linked = false
    @Volatile private var stoppingOurselves = false

    /** A client linked / the last one left: restart advertising at the matching interval. */
    /** The interval the running set was last given (see [applyInterval]). */
    @Volatile private var startedLinked = false

    /** Pause, change the interval, resume: the same set, so nothing leaks. */
    private fun applyInterval(set: AdvertisingSet) {
        startedLinked = linked
        runCatching {
            set.enableAdvertising(false, 0, 0)
            set.setAdvertisingParameters(advParams())
            set.enableAdvertising(true, 0, 0)
        }.onFailure { LinkLog.add("advertising interval not changed: ${it.message}") }
    }

    @Synchronized fun setLinked(on: Boolean) {
        if (linked == on || !running) { linked = on; return }
        linked = on
        advSet?.let { applyInterval(it) }   // no set yet: onAdvertisingSetStarted applies it
    }

    @Synchronized fun ensureAdvertising() {
        if (!running || !enabled || advertising || startPending) return
        val set = advSet
        if (set != null) {
            LinkLog.add("advertising was off: enabling it again")
            runCatching { set.enableAdvertising(true, 0, 0) }
            return
        }
        LinkLog.add("advertising was off: starting it")
        runCatching { startAdvertising() }.onFailure { LinkLog.add("advertising start failed: ${it.message}") }
    }

    /** Sends a 1-byte notification on the wake characteristic (wakes the iPhone app from suspension). */
    fun wake(address: String): String {
        val s = server ?: return "no GATT server"
        val c = wakeChar ?: return "no wake characteristic"
        if (address !in wakeSubscribers) return "not subscribed in this run"
        val d = devices[address] ?: return "no GATT connection"
        return runCatching { GattCompat.notify(s, d, c, byteArrayOf(1)); "sent" }.getOrElse { "failed: ${it.javaClass.simpleName}" }
    }

    /**
     * Wakes every device connected to our GATT server while no session is up (the paired iPhone, in practice). A
     * suspended iOS app is not told when the L2CAP channel closes, and with Bluetooth tethering its connection to this
     * phone stays up over classic, so no disconnect or new connect wakes it either: only a notification does (seen
     * 2026-11-25: 21 minutes without a session until the app was opened). Sent also to a device that did not subscribe
     * in this run of the app (after an update or restart the subscriptions are forgotten here, iOS still has them).
     * Returns what was done, for the link log.
     */
    fun wakeUnlinked(): String {
        val s = server ?: return "no GATT server"
        val c = wakeChar ?: return "no wake characteristic"
        if (devices.isEmpty()) return "no GATT connection to wake"
        return devices.values.joinToString(", ") { d ->
            val sub = if (d.address in wakeSubscribers) "subscribed" else "not subscribed in this run"
            "${LinkLog.short(d.address)} " + runCatching { GattCompat.notify(s, d, c, byteArrayOf(1)); "woken ($sub)" }.getOrElse { "failed (${it.javaClass.simpleName})" }
        }
    }

    /**
     * LE link to the paired iPhone, opened by us (a GATT *client* over LE), in two modes:
     *  - held for as long as a session with it is up: while the phones are also linked over classic Bluetooth
     *    (tethering) iOS runs GATT over whichever link it has and moves it to LE when one appears, dropping the
     *    session; holding LE keeps it there. Released the moment the session ends: a link we hold blocks a freshly
     *    started app's connect on iOS (seen 2026-11-22: after the app was closed and reopened it never connected
     *    while we held the link, and connected within 2 s once we let it go).
     *  - pulsed while paired without a session: up for [PULSE_MS], then dropped. A link coming up completes the
     *    pending connect of a suspended or relaunched app even when iOS cannot follow our rotating address.
     * Connection priority is requested through the held link (the GATT server API cannot): HIGH during calls.
     */
    private val keepers = ConcurrentHashMap<String, BluetoothGatt>()
    private val keeperUp = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val wantedPriority = ConcurrentHashMap<String, Int>()
    private val pulseTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "le-pulse").apply { isDaemon = true } }

    private fun openLe(address: String, onUp: (BluetoothGatt) -> Unit) {
        if (!running || !BluetoothAdapter.checkBluetoothAddress(address) || keepers.containsKey(address)) return
        val d = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        runCatching {
            keepers[address] = d.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) { keeperUp.add(address); onUp(g) }
                    else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        keeperUp.remove(address)
                        if (keepers[address] === g) keepers.remove(address)
                        runCatching { g.close() }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }.onFailure { Log.w(TAG, "LE link not opened: ${it.message}") }
    }

    /** A session with this central is up: hold the LE link (see above). */
    fun holdLe(address: String) {
        held.add(address)
        if (address in keeperUp) {
            keepers[address]?.let { runCatching { it.requestConnectionPriority(wantedPriority[address] ?: BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } }
            return
        }
        openLe(address) { g ->
            g.requestConnectionPriority(wantedPriority[address] ?: BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            LinkLog.add("LE link held to ${LinkLog.short(address)}")
        }
    }

    /** The session ended: let the link go at once (a held link blocks the app's next connect). */
    fun releaseLe(address: String) {
        held.remove(address); wantedPriority.remove(address)
        keepers.remove(address)?.let { g -> keeperUp.remove(address); runCatching { g.disconnect(); g.close() } }
    }

    /** Paired, no session: bring an LE link up briefly, then drop it unless a session came up meanwhile. */
    fun pulseLe(address: String) {
        if (!LinkPolicy.pulseLeLink(paired = true, sessionUp = address in held, linkOpen = keepers.containsKey(address) || address in leLinks)) return
        openLe(address) { _ ->
            LinkLog.add("LE pulse to ${LinkLog.short(address)}")
            pulseTimer.schedule({ if (address !in held) releaseLe(address) }, LinkPolicy.PULSE_UP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        // Not even connected after a while (out of range): give the attempt up, the next pulse tries again.
        pulseTimer.schedule({ if (address !in held && address !in keeperUp) releaseLe(address) }, 10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Close the central's LE connection (we have no session on it any more). iOS keeps the connection of an app that
     * died for up to ~40 s, and while any LE link to us exists it does not complete the reopened app's new connect
     * (seen 2026-11-22); closing it lets the next pulse bring a fresh link up, which completes it.
     */
    fun closeLeConnection(address: String) {
        val s = server ?: return
        val d = devices[address] ?: return
        if (address !in leLinks) return
        runCatching { s.cancelConnection(d) }
        LinkLog.add("closed the idle LE connection to ${LinkLog.short(address)}")
    }

    /** Nothing is paired any more: no link is held or pulsed. */
    fun dropKeepers() {
        held.clear()
        keepers.keys.toList().forEach { releaseLe(it) }
    }

    fun tune(address: String, high: Boolean) {
        val prio = if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        wantedPriority[address] = prio
        val g = keepers[address]?.takeIf { address in keeperUp && address in held } ?: return
        runCatching { g.requestConnectionPriority(prio) }
        Log.i(TAG, "link priority ${if (high) "high" else "balanced"} requested")
    }

    fun untune(address: String) = tune(address, high = false)

    private val txSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Centrals with an LE link right now (from the ACL broadcasts); a GATT pipe without one runs over classic. */
    private val leLinks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Per GATT pipe: whether it was opened over LE (true) or classic Bluetooth (false). */
    private val pipeOnLe = ConcurrentHashMap<String, Boolean>()

    /**
     * ACL up / down for a central. iOS runs GATT over whichever transport it has and moves it when the other one
     * appears or goes; a session on a GATT pipe dies with its bearer, so end it at once (the central sets up again on
     * the new bearer) instead of leaving a stale pipe that keeps sending its queue into the new one.
     */
    fun onAcl(address: String?, le: Boolean, up: Boolean) {
        address ?: return
        if (le) { if (up) leLinks.add(address) else leLinks.remove(address) }
        val onLe = pipeOnLe[address] ?: return
        if (LinkPolicy.gattPipeEnds(onLe, le, up)) {
            Log.i(TAG, "gatt pipe for $address ends: ${if (le) "LE" else "classic"} link ${if (up) "up" else "down"}")
            pipeOnLe.remove(address)
            gattPipes.remove(address)?.close()
        }
    }

    /** A new GATT pipe (and session) for this central; an older pipe of the same central is closed first. */
    private fun openPipe(s: BluetoothGattServer, device: BluetoothDevice): GattTransport {
        gattPipes.remove(device.address)?.close()
        val t = GattTransport(s, device, txChar!!)
        t.mtu = mtus[device.address] ?: 23
        pipeOnLe[device.address] = device.address in leLinks
        t.onPeerRestart = { first -> openPipe(s, device).received(first) }
        gattPipes[device.address] = t
        Log.i(TAG, "gatt fallback pipe opened for ${device.address}")
        onTransport(t)
        return t
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "gatt ${device.address} state=$newState status=$status bond=${device.bondState}")
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                devices[device.address] = device
                // 2M PHY roughly halves air time per packet (lower latency, more headroom for audio).
                runCatching { server?.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
                runCatching { server?.readPhy(device) }
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                gattPipes.remove(device.address)?.close()
                txSubscribers.remove(device.address)
                mtus.remove(device.address)
                linkPhy.remove(device.address)
                wakeSubscribers.remove(device.address)
                devices.remove(device.address)
            }
        }

        /**
         * Hidden in the SDK stubs (so no `override`), but ART dispatches it by signature: the real
         * connection interval (×1.25 ms), peripheral latency and supervision timeout of each link.
         */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            linkParams[device.address] = "interval %.2f ms, latency %d, timeout %d ms".format(interval * 1.25, latency, timeout * 10)
            Log.i(TAG, "link ${device.address}: ${linkParams[device.address]}")
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) = phy(device, txPhy, rxPhy, status)
        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) = phy(device, txPhy, rxPhy, status)

        private fun phy(device: BluetoothDevice, tx: Int, rx: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            fun n(p: Int) = when (p) { BluetoothDevice.PHY_LE_2M -> "2M"; BluetoothDevice.PHY_LE_CODED -> "coded"; else -> "1M" }
            val v = if (tx == rx) n(tx) else "tx ${n(tx)} rx ${n(rx)}"
            if (linkPhy.put(device.address, v) != v) Log.i(TAG, "phy ${device.address}: $v")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "mtu ${device.address} = $mtu")
            mtus[device.address] = mtu
            gattPipes[device.address]?.mtu = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            val s = server ?: return
            if (characteristic.uuid == PSM_CHAR) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, OldAndroid.psmValue(psm, offset))
            } else {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val s = server ?: return
            if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == WAKE_CHAR) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() and 1 == 1
                if (on) wakeSubscribers.add(device.address) else wakeSubscribers.remove(device.address)
                devices[device.address] = device
                Log.i(TAG, "wake notifications ${if (on) "on" else "off"} for ${device.address}")
            } else if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == TX_CHAR) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() and 1 == 1
                // A (re)subscription always starts a fresh pipe: the central is setting up a new session.
                if (on) { txSubscribers.add(device.address); openPipe(s, device) }
                else { txSubscribers.remove(device.address); gattPipes.remove(device.address)?.close() }
            }
            if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic.uuid == RX_CHAR && value != null) {
                // A pipe whose session ended is never reused; a subscribed central writing again gets a fresh one.
                val p = gattPipes[device.address]?.takeIf { !it.isClosed }
                    ?: server?.takeIf { device.address in txSubscribers }?.let { openPipe(it, device) }
                p?.received(value)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            gattPipes[device.address]?.notificationSent()
        }
    }
}

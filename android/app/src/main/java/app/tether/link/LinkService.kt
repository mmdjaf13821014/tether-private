package app.tether.link

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import app.tether.R
import app.tether.TetherApp
import app.tether.adb.Adb
import app.tether.telecom.TestConnectionService
import app.tether.ui.MainActivity
import org.json.JSONObject

/** Foreground service that keeps the BLE peripheral and the hub alive. */
class LinkService : Service() {
    private lateinit var app: TetherApp
    private var ble: BlePeripheral? = null
    private val uiListener: () -> Unit = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        app = application as TetherApp
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_LINK, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_LINK, notification())   // Android 8-9: no service types
        running = this
        app.hub.startBackground()
        app.hub.addListener(uiListener)
        ble = BlePeripheral(this) { t -> app.hub.onTransport(t) }
        startBle()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        LinkLog.init(this)
        LinkLog.add("service started")
        registerReceiver(aclReceiver, IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
        })
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        watchWifi()
        runCatching { TestConnectionService.register(this) }
    }

    /**
     * Before the first session of this version there is no remembered address: the only bonded iPhone / iPad with
     * LE support, if there is exactly one (iOS names them so by default). Replaced by the real address at the first
     * session (Hub.onEstablished).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun guessPairedIphone(): String? = runCatching {
        val bonded = getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.bondedDevices
        bonded.filter { d ->
            d.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC &&
                d.bluetoothClass?.majorDeviceClass in setOf(android.bluetooth.BluetoothClass.Device.Major.PHONE, android.bluetooth.BluetoothClass.Device.Major.COMPUTER) &&
                (d.name ?: "").let { it.contains("iPhone") || it.contains("iPad") }
        }.singleOrNull()?.address.also { Log.i(TAG, "LE keeper guess: ${if (it == null) "none" else "one bonded iPhone"}") }
    }.getOrNull()

    private fun startBle() {
        try {
            ble?.start()
            // Where the paired iPhone is, for the LE pulses (Hub.lePulse); learned at the first session otherwise.
            if (app.keys.isPaired && app.prefs.getString("central_bt_address", null) == null)
                guessPairedIphone()?.let { app.prefs.edit().putString("central_bt_address", it).apply() }
        } catch (e: SecurityException) {
            Log.w(TAG, "missing Bluetooth permission")
        }
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ble?.running != true) startBle()
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        app.hub.removeListener(uiListener)
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(aclReceiver) }
        LinkLog.add("service stopped")
        ble?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    val peripheral get() = ble

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> startBle()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> { ble?.stop(); updateNotification() }
            }
            app.hub.refresh()
        }
    }

    /** Classic / LE connections, bonding and Bluetooth tethering, for the link log (drops during tethering). */
    private var lastTethered = ""
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: android.bluetooth.BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            val transport = when (i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_TRANSPORT, -1)) { 1 -> "classic"; 2 -> "LE"; else -> "?" }
            val who = LinkLog.short(d?.address)
            val t = i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_TRANSPORT, -1)
            when (i.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    LinkLog.add("ACL up $who $transport")
                    if (t == 1 || t == 2) ble?.onAcl(d?.address, le = t == 2, up = true)
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    LinkLog.add("ACL down $who $transport reason=${i.getIntExtra("android.bluetooth.device.extra.REASON", -1)}")
                    if (t == 1 || t == 2) ble?.onAcl(d?.address, le = t == 2, up = false)
                }
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                    LinkLog.add("bond $who ${i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)}→${i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)}")
                "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED" ->
                    LinkLog.add("PAN $who state=${i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)} role=${i.getIntExtra("android.bluetooth.pan.extra.LOCAL_ROLE", -1)}")
                "android.net.conn.TETHER_STATE_CHANGED" -> {
                    val t = i.getStringArrayListExtra("tetherArray")?.sorted()?.joinToString(",") ?: ""
                    if (t != lastTethered) { lastTethered = t; LinkLog.add("tethered: [$t]") }
                }
            }
        }
    }

    private var lastBattery = -1
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            if (level != lastBattery) { lastBattery = level; Thread { app.hub.pushStatus() }.start() }
        }
    }

    /** After a reboot, Wireless debugging is off until Wi-Fi is up; turn it back on and restart the helper. */
    private fun watchWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!Adb.hasSecureSettings(this@LinkService)) return
                Thread {
                    val wasOn = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
                    Adb.enableWirelessDebugging(this@LinkService)
                    if (!wasOn) Thread.sleep(6000) // let adbd bring its TLS service up
                    if (app.prefs.getBoolean("adb_paired", false)) app.hub.helper.ensureRunning()
                    app.hub.pushStatus()
                }.start()
            }
        })
    }

    private fun notification(): Notification {
        val c = app.hub.conn
        val text = when {
            ble != null && ble?.enabled == false -> getString(R.string.notif_bt_off)
            !app.keys.isPaired -> getString(R.string.notif_link_unpaired)
            c?.isUp == true -> getString(R.string.notif_link_connected)
            else -> getString(R.string.notif_link_waiting)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, TetherApp.CH_LINK)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_LINK, notification())

    fun debugState(): JSONObject = JSONObject()
        .put("ble_running", ble?.running).put("advertising", ble?.advertising).put("psm", ble?.psm).put("gattSecurity", ble?.securityCheck)
        .put("isPaired", app.keys.isPaired).put("link_up", app.hub.conn?.isUp == true)
        .put("transport", app.hub.conn?.transport?.label).put("helper", app.hub.helper.state())
        .put("clients", org.json.JSONArray(app.hub.targets().map { c -> JSONObject().put("name", c.name).put("kind", c.kind)
            .put("transport", c.transport.label).put("audio", c === app.hub.audio.target)
            .put("link", ble?.linkParams?.get(c.transport.address)).put("phy", ble?.linkPhy?.get(c.transport.address)) }))
        .put("paired", org.json.JSONArray(app.keys.centrals().map { it.name }))

    companion object {
        const val TAG = "Tether"
        const val NOTIF_LINK = 10
        @Volatile var running: LinkService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, LinkService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Tether", "boot/update: ${intent.action}")
        try {
            LinkService.start(context)
        } catch (t: Throwable) {
            Log.w("Tether", "could not start link service", t)
        }
    }
}

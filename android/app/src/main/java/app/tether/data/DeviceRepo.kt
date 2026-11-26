package app.tether.data

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import app.tether.core.CallEntry
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject

class CallLogRepo(private val ctx: Context, private val subs: SimRepo) {
    private var observer: ContentObserver? = null

    @SuppressLint("MissingPermission")
    fun since(since: Long, cap: Int = 1000): List<CallEntry> {
        val out = ArrayList<CallEntry>()
        val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID)
        val (sel, args, order) = if (since <= 0) Triple(null, null, "${CallLog.Calls._ID} DESC")
        else Triple("${CallLog.Calls._ID} > ?", arrayOf(since.toString()), "${CallLog.Calls._ID} ASC")
        try {
            val qa = android.os.Bundle().apply {
                sel?.let { putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                args?.let { putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, cap)
            }
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, qa, null)?.use { c ->
                while (out.size < cap && c.moveToNext()) out.add(CallEntry(c.getLong(0), c.getString(1) ?: "", c.getString(2), c.getLong(3),
                    c.getInt(4), c.getInt(5), subs.subForAccountId(c.getString(6))))
            }
        } catch (e: SecurityException) {
            Log.w("Tether", "no call log permission")
        }
        return if (since <= 0) out.reversed() else out
    }

    /** Deletes call-log rows (as the default phone app); all of them when [ids] is null. Returns rows deleted or -1. */
    fun delete(ids: List<Long>?): Int = try {
        if (ids == null) ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        else ids.chunked(200).sumOf { ch -> ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ch.joinToString(",")})", null) }
    } catch (t: Throwable) {
        Log.w("Tether", "call log delete refused: ${t.message}")
        -1
    }

    fun allIds(): List<Long> = try {
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} ASC")
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
    } catch (t: Throwable) { emptyList() }

    /** Debug (adb): a throw-away call-log row with a fake number, to test deletion. */
    fun insertTest(num: String): Long = try {
        val v = android.content.ContentValues().apply {
            put(CallLog.Calls.NUMBER, num); put(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
            put(CallLog.Calls.DATE, System.currentTimeMillis()); put(CallLog.Calls.DURATION, 0); put(CallLog.Calls.NEW, 0)
        }
        ctx.contentResolver.insert(CallLog.Calls.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull() ?: -1
    } catch (t: Throwable) { Log.w("Tether", "call log insert refused: ${t.message}"); -1 }

    fun observe(onChange: () -> Unit) {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        observer = o
        try { ctx.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, o) } catch (_: SecurityException) {}
    }
}

/** SIMs, signal, battery, roles: the "status" message. */
class SimRepo(private val ctx: Context) {
    private val sm = ctx.getSystemService(SubscriptionManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val telecom = ctx.getSystemService(TelecomManager::class.java)

    /** What the status bar of each SIM shows, kept current by telephony callbacks (see [watch]). */
    class Radio(@Volatile var level: Int = 0, @Volatile var net: String? = null, @Volatile var service: String = "in",
                @Volatile var roaming: Boolean = false)
    private val live = java.util.concurrent.ConcurrentHashMap<Int, Radio>()
    private val callbacks = java.util.concurrent.ConcurrentHashMap<Int, Pair<TelephonyManager, Any>>()
    private val watchExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sim-watch") }
    private var onRadioChange: (() -> Unit)? = null

    /**
     * Signal level, network type (2G/E, 3G, H, H+, 4G/LTE, LTE+, 5G) and service / roaming per SIM, pushed
     * live: [onChange] runs after each change (the hub throttles it). Re-registers when SIMs change.
     */
    @SuppressLint("MissingPermission")
    fun watch(onChange: () -> Unit) {
        onRadioChange = onChange
        rewatch()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                sm.addOnSubscriptionsChangedListener(watchExec, object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() { rewatch() }
                })
            } else {
                // Android 8-10: the listener uses the looper of the thread that creates it, so create it on the main one.
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        sm.addOnSubscriptionsChangedListener(object : SubscriptionManager.OnSubscriptionsChangedListener() {
                            override fun onSubscriptionsChanged() { watchExec.execute { rewatch() } }
                        })
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun rewatch() {
        val subs = try { sm.activeSubscriptionInfoList?.map { it.subscriptionId } ?: emptyList() } catch (_: Throwable) { emptyList() }
        for ((sub, reg) in callbacks) if (sub !in subs) { runCatching { unregister(reg.first, reg.second) }; callbacks.remove(sub); live.remove(sub) }
        for (sub in subs) if (!callbacks.containsKey(sub)) {
            val t = tm.createForSubscriptionId(sub)
            val r = live.getOrPut(sub) { Radio() }
            try {
                val cb: Any = if (Build.VERSION.SDK_INT >= 31) modernCallback(r).also { registerModern(t, it) }
                    else legacyListener(t, r).also { listen(t, it) }
                callbacks[sub] = t to cb
            } catch (e: Throwable) { Log.w("Tether", "SIM $sub: cannot watch signal: ${e.message}") }
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun registerModern(t: TelephonyManager, cb: Any) = t.registerTelephonyCallback(watchExec, cb as android.telephony.TelephonyCallback)

    private fun serviceLabel(st: android.telephony.ServiceState): String = when (st.state) {
        android.telephony.ServiceState.STATE_IN_SERVICE -> "in"
        android.telephony.ServiceState.STATE_EMERGENCY_ONLY -> "emergency"
        android.telephony.ServiceState.STATE_POWER_OFF -> "off"
        else -> if (Build.VERSION.SDK_INT >= 30 && st.networkRegistrationInfoList.any { it.isRegistered &&
            it.domain and android.telephony.NetworkRegistrationInfo.DOMAIN_PS != 0 }) "in" else "none"
    }

    private fun applyService(r: Radio, st: android.telephony.ServiceState) {
        val svc = serviceLabel(st)
        if (r.service != svc || r.roaming != st.roaming) { r.service = svc; r.roaming = st.roaming; onRadioChange?.invoke() }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun modernCallback(r: Radio): Any = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.SignalStrengthsListener,
        android.telephony.TelephonyCallback.DisplayInfoListener, android.telephony.TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength) {
            if (r.level != ss.level) { r.level = ss.level; onRadioChange?.invoke() }
        }
        override fun onDisplayInfoChanged(d: android.telephony.TelephonyDisplayInfo) {
            val n = netLabel(d.networkType, d.overrideNetworkType)
            if (r.net != n) { r.net = n; onRadioChange?.invoke() }
        }
        override fun onServiceStateChanged(st: android.telephony.ServiceState) = applyService(r, st)
    }

    /** Android 8 to 11: PhoneStateListener (deprecated from 12). Android 8-10 have no display info; the plain data type stands in. */
    @Suppress("DEPRECATION")
    private open inner class LegacyListener(val t: TelephonyManager, val r: Radio) : android.telephony.PhoneStateListener() {
        override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength) {
            if (r.level != ss.level) { r.level = ss.level; onRadioChange?.invoke() }
        }
        override fun onServiceStateChanged(st: android.telephony.ServiceState) {
            applyService(r, st)
            if (Build.VERSION.SDK_INT < 30) {
                val n = try { netLabel(t.dataNetworkType, 0) } catch (_: Throwable) { null }
                if (r.net != n) { r.net = n; onRadioChange?.invoke() }
            }
        }
    }

    @SuppressLint("NewApi") // created only on Android 11+
    @Suppress("DEPRECATION")
    private inner class LegacyListener30(t: TelephonyManager, r: Radio) : LegacyListener(t, r) {
        override fun onDisplayInfoChanged(d: android.telephony.TelephonyDisplayInfo) {
            val n = netLabel(d.networkType, d.overrideNetworkType)
            if (r.net != n) { r.net = n; onRadioChange?.invoke() }
        }
    }

    /** A PhoneStateListener takes the looper of the thread that creates it: build it on the main thread. */
    private fun legacyListener(t: TelephonyManager, r: Radio): Any {
        fun make(): Any = if (Build.VERSION.SDK_INT >= 30) LegacyListener30(t, r) else LegacyListener(t, r)
        if (Looper.myLooper() == Looper.getMainLooper()) return make()
        val box = java.util.concurrent.atomic.AtomicReference<Any>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { try { box.set(make()) } finally { latch.countDown() } }
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) error("main thread busy")
        return box.get() ?: error("listener not created")
    }

    @Suppress("DEPRECATION")
    private fun listen(t: TelephonyManager, l: Any) {
        var mask = android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE
        if (Build.VERSION.SDK_INT >= 30) mask = mask or android.telephony.PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
        t.listen(l as android.telephony.PhoneStateListener, mask)
    }

    @Suppress("DEPRECATION")
    private fun unregister(t: TelephonyManager, l: Any) {
        if (Build.VERSION.SDK_INT >= 31) t.unregisterTelephonyCallback(l as android.telephony.TelephonyCallback)
        else t.listen(l as android.telephony.PhoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
    }

    /** The label phones show for a network: data technology plus Samsung / AOSP display overrides. */
    fun netLabel(type: Int, override: Int): String? = when (override) {
        android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
        android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
        android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
        android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
        else -> when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "E"
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    fun sims(): List<JSONObject> = try {
        (sm.activeSubscriptionInfoList ?: emptyList()).map { info ->
            val t = tm.createForSubscriptionId(info.subscriptionId)
            JSONObject().put("slot", info.simSlotIndex).put("sub", info.subscriptionId)
                .put("name", info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}")
                .put("carrier", t.networkOperatorName?.takeIf { it.isNotBlank() } ?: info.carrierName?.toString() ?: "")
                .apply { country(t)?.let { put("country", it) } }
                .put("signal", live[info.subscriptionId]?.level ?: (if (Build.VERSION.SDK_INT >= 28) t.signalStrength?.level else null) ?: 0)
                .apply { live[info.subscriptionId]?.let { l ->
                    l.net?.let { put("net", it) }; put("service", l.service); put("roaming", l.roaming)
                } }
                .apply {
                    try { phoneNumber(info).takeIf { it.isNotBlank() }?.let { put("number", it) } } catch (_: Throwable) {}
                }
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** The SIM's country (lowercase ISO 3166), else the network's; the iPhone groups national numbers by it. */
    private fun country(t: TelephonyManager): String? = try {
        (t.simCountryIso?.takeIf { it.isNotBlank() } ?: t.networkCountryIso?.takeIf { it.isNotBlank() })?.lowercase()
    } catch (_: Throwable) { null }

    /** CallLog PHONE_ACCOUNT_ID is the subscription id or ICCID depending on the build. */
    @SuppressLint("MissingPermission")
    fun subForAccountId(id: String?): Int? {
        if (id == null) return null
        id.toIntOrNull()?.let { n -> if (n > 0 && n < 100000) return n }
        return try {
            sm.activeSubscriptionInfoList?.firstOrNull { it.iccId == id }?.subscriptionId
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A concrete subscription: the requested one, else the default voice/SMS SIM, else the first active SIM.
     * Never "ask every time": the Android phone is in a bag and nobody would see the SIM chooser.
     */
    @SuppressLint("MissingPermission")
    fun resolveSub(sub: Int?, forSms: Boolean): Int? {
        val active = try { sm.activeSubscriptionInfoList?.map { it.subscriptionId } ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (sub != null && sub in active) return sub
        val def = if (forSms) SubscriptionManager.getDefaultSmsSubscriptionId() else SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (def != SubscriptionManager.INVALID_SUBSCRIPTION_ID && def in active) return def
        return active.firstOrNull() ?: sub
    }

    /** The line number the SIM reports: `getPhoneNumber` from Android 13, the older SubscriptionInfo field before. */
    @SuppressLint("MissingPermission")
    private fun phoneNumber(info: android.telephony.SubscriptionInfo): String =
        if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId)
        else @Suppress("DEPRECATION") (info.number ?: "")

    /**
     * Subscription behind a calling account: `getSubscriptionId` from Android 11; on Android 8-10 a SIM account's id is the
     * SIM's ICCID, so match on that (best effort), and with a single SIM there is only one answer.
     */
    @SuppressLint("MissingPermission")
    private fun subIdOf(h: PhoneAccountHandle): Int {
        if (Build.VERSION.SDK_INT >= 30) return tm.getSubscriptionId(h)
        val list = sm.activeSubscriptionInfoList ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return list.firstOrNull { it.iccId == h.id }?.subscriptionId
            ?: list.singleOrNull()?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    @SuppressLint("MissingPermission")
    fun accountFor(sub: Int?): PhoneAccountHandle? {
        val s = resolveSub(sub, forSms = false) ?: return null
        return try {
            telecom.callCapablePhoneAccounts.firstOrNull { subIdOf(it) == s }
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun subFor(handle: PhoneAccountHandle?): Int? = try {
        handle?.let { subIdOf(it).takeIf { s -> s != SubscriptionManager.INVALID_SUBSCRIPTION_ID } }
    } catch (_: Throwable) {
        null
    }

    fun status(helper: JSONObject): JSONObject {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) } ?: 0
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val def = SubscriptionManager.getDefaultVoiceSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        return JSONObject()
            .put("device", Settings.Global.getString(ctx.contentResolver, "device_name") ?: android.os.Build.MODEL)
            .put("battery", level).put("charging", plugged)
            .put("sims", JSONArray(sims()))
            .apply { def?.let { put("defaultSub", it) } }
            .put("dialer", isDialer())
            .put("helper", helper)
            .put("wifi", wifi)
            .put("adbWifi", Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1)
    }

    /** Whether Tether is the default phone app: the dialer role from Android 10, Telecom's default dialer before. */
    fun isDialer() = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        else telecom.defaultDialerPackage == ctx.packageName
}

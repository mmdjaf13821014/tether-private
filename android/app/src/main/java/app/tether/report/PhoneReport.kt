package app.tether.report

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.tether.TetherApp
import app.tether.adb.Adb
import app.tether.audio.DiagStore
import app.tether.core.ProblemReport
import app.tether.core.ReportScrubber
import app.tether.helper.CallAudio
import app.tether.link.LinkLog
import app.tether.link.LinkService
import app.tether.telecom.CallRegistry
import org.json.JSONObject

/**
 * The Android phone app's problem report (Help › Send a problem report; docs/TROUBLESHOOTING.md): device, setup, link,
 * telephony, calls and audio, the helper's call-audio diagnostics (now and the last automatic snapshots), the link log
 * and the app's log. Phone numbers, names, message text, e-mail, addresses and keys are removed (ReportScrubber). Slow
 * (asks the helper, reads logcat): never on the main thread.
 */
object PhoneReport {
    const val WHICH = "Android phone app"

    fun build(app: TetherApp, now: Long = System.currentTimeMillis()): ProblemReport {
        val scrubber = ReportScrubber(names = privateNames(app), keep = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.BRAND, Build.MANUFACTURER, Build.BOARD))
        val r = ProblemReport(ProblemReport.appLine(versionName(app), versionCode(app), WHICH), ProblemReport.created(now), scrubber)
        val hub = app.hub
        val helper = hub.helper
        // The helper (the shell user) sees what the app cannot: dumpsys, all of Tether's log lines, system properties.
        val adbReady = Adb.hasSecureSettings(app) && (app.prefs.getBoolean("adb_paired", false) || app.prefs.getBoolean(Adb.PREF_LEGACY, false))
        val helperErr = if (helper.connected && !helper.isStale) null else if (adbReady) runCatching { helper.ensureRunning(10_000) }.getOrElse { it.message } else "Wireless debugging is not set up"
        val diag = if (helperErr == null) runCatching { helper.request("diag", 20, JSONObject().put("logcat", true)) }.getOrNull() else null
        val net = if (helperErr == null) runCatching { helper.net() }.getOrNull() else null
        val props = diag?.optJSONObject("props")

        device(r, app, props)
        setup(r, app, helperErr)
        link(r, app, net)
        telephony(r, app)
        audio(r, app)
        val d = r.section("Call audio diagnostics")
        d.line("-- now: ${DiagStore.triggerText("now")}")
        if (diag != null) d.lines(DiagStore.lines(diag)) else d.line("not available: ${helperErr ?: "the helper did not answer"}")
        val stored = hub.audio.diag.all()
        d.line("-- automatic snapshots kept (${stored.size}, newest last; taken when a call's audio path looked dead and at the end of each bridged call)")
        for (s in stored) { d.line(DiagStore.heading(s)); s.optJSONObject("diag")?.let { d.lines(DiagStore.lines(it, helperLog = 60)) } }

        LinkLog.init(app)
        r.log("Link log", LinkLog.tail(600).lines().filter { it.isNotBlank() })
        val appLog = ArrayList<String>()
        appLog += "-- this app's process (logcat --pid)"
        appLog += ownLogcat(800)
        diag?.optJSONArray("logcat")?.let { a ->
            appLog += "-- Tether's lines from every process, also earlier runs (via the helper)"
            for (i in 0 until a.length()) appLog += a.optString(i)
        }
        r.log("App log", appLog)
        return r
    }

    // ---------------------------------------------------------------- sections

    private fun device(r: ProblemReport, app: TetherApp, props: JSONObject?) {
        val s = r.section("Device")
        s.kv("Manufacturer", Build.MANUFACTURER).kv("Brand", Build.BRAND).kv("Model", Build.MODEL).kv("Device", Build.DEVICE)
            .kv("Product", Build.PRODUCT).kv("Board", Build.BOARD).kv("Hardware", Build.HARDWARE)
            .kv("Platform", props?.optString("ro.board.platform")?.takeIf { it.isNotEmpty() } ?: getprop("ro.board.platform"))
        if (Build.VERSION.SDK_INT >= 31) s.kv("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        s.kv("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").kv("Security patch", Build.VERSION.SECURITY_PATCH)
            .kv("Build", Build.DISPLAY).kv("Fingerprint", Build.FINGERPRINT)
        val oneUi = props?.optString("ro.build.version.oneui")?.takeIf { it.isNotEmpty() } ?: getprop("ro.build.version.oneui")
        if (Build.MANUFACTURER.equals("samsung", true) || !oneUi.isNullOrEmpty()) s.kv("One UI", oneUiVersion(oneUi))
        s.kv("Locale", app.resources.configuration.locales[0]?.toLanguageTag()).kv("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
    }

    private fun setup(r: ProblemReport, app: TetherApp, helperErr: String?) {
        val s = r.section("Setup")
        val (granted, missing) = permissions(app)
        s.kv("Permissions granted", granted.joinToString(", ")).kv("Permissions missing", missing.joinToString(", ").ifEmpty { "none" })
        s.kv("Notifications allowed", app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        s.kv("Default phone app is Tether", app.sims.isDialer())
        s.kv("Default phone app", runCatching { app.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull())
        s.kv("Default SMS app", runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull())
        s.kv("Battery optimisation off for Tether", app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName))
        s.kv("Secure settings granted (over ADB)", Adb.hasSecureSettings(app))
        s.kv("Wireless debugging paired", app.prefs.getBoolean("adb_paired", false))
        s.kv("Wireless debugging on", Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1)
        s.kv("TCP adb (legacy)", if (app.prefs.getBoolean(Adb.PREF_LEGACY, false)) "on, port ${app.prefs.getInt(Adb.PREF_LEGACY_PORT, 5555)}" else "off")
        s.kv("Do Not Disturb access", runCatching { app.hub.ringer.dndAccess() }.getOrNull())
        val h = app.hub.helper.hello
        s.kv("Helper", if (app.hub.helper.connected) "running" else "not running (${helperErr ?: "–"})")
        if (h != null) s.kv("Helper details", "version ${h.optInt("version")}, uid ${h.optInt("uid")}, sdk ${h.optInt("sdk")}, from this install ${!app.hub.helper.isStale}")
        s.kv("Link service", if (LinkService.running != null) "running" else "not running")
    }

    @SuppressLint("MissingPermission")
    private fun link(r: ProblemReport, app: TetherApp, net: JSONObject?) {
        val s = r.section("Link")
        val hub = app.hub
        val centrals = app.keys.centrals()
        s.kv("Paired clients", "${centrals.size}" + (if (centrals.isNotEmpty()) " (" + centrals.groupingBy { it.kind }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" } + ")" else ""))
        val live = hub.conns.filter { it.isUp }
        s.kv("Connected clients", live.size)
        val ble = LinkService.running?.peripheral
        for ((i, c) in live.withIndex()) {
            val params = ble?.linkParams?.get(c.transport.address); val phy = ble?.linkPhy?.get(c.transport.address)
            s.kv("Client ${i + 1}", listOfNotNull(c.kind, "via ${c.transport.label}", if (c === hub.audio.target) "has the call audio" else null,
                if (c in hub.targets()) null else "not receiving broadcasts", params, phy?.let { "PHY $it" }).joinToString(" · "))
        }
        if (hub.onlyKind != null) s.kv("Test filter (only)", hub.onlyKind)
        val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
        s.kv("Bluetooth on", bt?.isEnabled)
        s.kv("BLE advertising", if (ble == null) "service not running" else if (ble.advertising) "on" else "off")
        s.kv("L2CAP channel", if (Build.VERSION.SDK_INT < 29) "not on Android ${Build.VERSION.RELEASE} (GATT only)" else if ((ble?.psm ?: 0) > 0) "listening" else "not listening")
        s.kv("GATT security check", ble?.securityCheck)
        s.kv("Multiple advertisement supported", runCatching { bt?.isMultipleAdvertisementSupported }.getOrNull())
        s.kv("Extended advertising supported", runCatching { bt?.isLeExtendedAdvertisingSupported }.getOrNull())
        s.kv("LE 2M PHY supported", runCatching { bt?.isLe2MPhySupported }.getOrNull())
        s.kv("Wi-Fi on", runCatching { app.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull())
        s.kv("Mobile data on", runCatching { app.getSystemService(TelephonyManager::class.java).isDataEnabled }.getOrNull())
        if (net != null) {
            s.kv("Hotspot on", net.opt("hotspot")).kv("Bluetooth tethering on", net.opt("bt"))
                .kv("Tethered interfaces", net.optJSONArray("tethered")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }?.ifEmpty { "none" })
                .kv("Airplane mode", net.opt("airplane")).kv("VPN up", net.opt("vpn"))
                .kv("Tethering possible", net.optJSONObject("can")?.toString())
        } else s.kv("Tethering", "unknown (the helper is not running)")
        s.kv("Share VPN", app.hub.share.sharing)
    }

    @SuppressLint("MissingPermission")
    private fun telephony(r: ProblemReport, app: TetherApp) {
        val s = r.section("Telephony")
        val sims = runCatching { app.sims.sims() }.getOrDefault(emptyList())
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val voiceSub = SubscriptionManager.getDefaultVoiceSubscriptionId()
        s.kv("SIMs", sims.size)
        // Never the SIM's name, the carrier's name or the number: slot, network, signal, roaming and roles only.
        for (sim in sims) s.kv("SIM slot ${sim.optInt("slot") + 1}", listOfNotNull(sim.optString("net").ifEmpty { null } ?: "no network type",
            "signal ${sim.optInt("signal")}/4", sim.optString("service").ifEmpty { null }?.let { if (it == "in") "in service" else "service: $it" },
            if (sim.optBoolean("roaming")) "roaming" else null, sim.optString("country").ifEmpty { null },
            if (sim.optInt("sub") == dataSub) "data SIM" else null, if (sim.optInt("sub") == voiceSub) "default for calls" else null).joinToString(" · "))
    }

    private fun audio(r: ProblemReport, app: TetherApp) {
        val s = r.section("Calls and audio")
        val hub = app.hub; val helper = hub.helper; val a = hub.audio
        s.kv("Call audio level", helper.audioLevel()).kv("Phone mic during bridged calls", a.mic.wire)
        s.kv("Redirect modes remembered", a.redirectMemory().entries.joinToString(", ") { "${it.key.wire} ${it.value.wire}" }.ifEmpty { "none" })
        s.kv("Mic mute remembered", a.memory()?.wire ?: "none")
        s.kv("Silence method", CallAudio.silence.name.lowercase())
        s.kv("Inject method / buffer", "${app.prefs.getString("inject", "auto")} / ${app.prefs.getInt("inject_ms", 40)} ms")
        s.kv("Calls now", CallRegistry.list().size).kv("A call is active", CallRegistry.hasActiveCall()).kv("Call audio bridged now", a.running)
        if (a.running) s.kv("This call is on the test line", a.onlyTestLine())
        s.kv("Helper status", a.helperStatus())
        s.kv("Last start INFO", helper.lastInfo).kv("Last uplink report", helper.lastUplink).kv("Last mic report", helper.lastMic)
        s.kv("Last call figures (helper)", helper.lastStats).kv("Last call figures (app)", a.lastTiming)
        val st = hub.lastSelftest
        if (st == null) s.kv("Last self-test", "none since the app started")
        else {
            s.kv("Last self-test", "chosen ${st.optString("chosen", "–")}, silence ${st.optString("silence", "–")}, call mode ${st.optString("callMode", "–")}")
            st.optJSONArray("methods")?.let { m -> for (i in 0 until m.length()) m.optJSONObject(i)?.let { row ->
                s.line("  ${if (row.optBoolean("ok")) "ok  " else "FAIL"} ${row.optString("name")}: ${row.optString("note")}" +
                    (if (row.has("delayMs")) " (${row.optInt("delayMs")} ms)" else ""))
            } }
            st.optJSONObject("redirect")?.let { s.kv("Self-test redirect verdicts", it) }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Everything that names a person or a device here: the clients, SIMs and carriers, Bluetooth devices, this phone. */
    @SuppressLint("MissingPermission")
    fun privateNames(app: TetherApp): List<String> {
        val names = ArrayList<String>()
        names += app.keys.centrals().map { it.name }
        names += app.hub.conns.map { it.name }
        runCatching { app.sims.sims().forEach { names += it.optString("name"); names += it.optString("carrier") } }
        runCatching {
            val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
            bt?.bondedDevices?.forEach { d -> d.name?.let { names += it }; if (Build.VERSION.SDK_INT >= 30) d.alias?.let { names += it } }
            bt?.name?.let { names += it }
        }
        runCatching { Settings.Global.getString(app.contentResolver, "device_name")?.let { names += it } }
        runCatching { Settings.Secure.getString(app.contentResolver, "bluetooth_name")?.let { names += it } }
        return names.filter { it.isNotBlank() }.distinct()
    }

    private fun permissions(ctx: Context): Pair<List<String>, List<String>> {
        val info: PackageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val req = info.requestedPermissions ?: return emptyList<String>() to emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(req.size)
        val granted = ArrayList<String>(); val missing = ArrayList<String>()
        for ((i, p) in req.withIndex()) {
            val short = p.removePrefix("android.permission.")
            if (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted += short else missing += short
        }
        return granted to missing
    }

    fun versionName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    @Suppress("DEPRECATION")
    fun versionCode(ctx: Context): Long = runCatching {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    }.getOrDefault(-1)

    /** Samsung's `ro.build.version.oneui` (e.g. 80000) as "8.0". */
    fun oneUiVersion(raw: String?): String? {
        val v = raw?.trim()?.toIntOrNull() ?: return raw?.takeIf { it.isNotBlank() }
        return "${v / 10000}.${(v % 10000) / 100}" + ((v % 100).takeIf { it > 0 }?.let { ".$it" } ?: "")
    }

    /** A system property this app may read (`getprop` needs no permission for these). */
    private fun getprop(key: String): String? = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** This process's own log lines (an app may read its own without any permission). */
    fun ownLogcat(n: Int): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.takeLast(n)
    }.getOrElse { listOf("logcat not readable: ${it.message}") }
}

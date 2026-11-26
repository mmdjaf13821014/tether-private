package app.tether.client.report

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.role.RoleManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import app.tether.client.ClientApp
import app.tether.core.ProblemReport
import app.tether.core.ReportScrubber
import org.json.JSONObject

/**
 * Tether Client's problem report (Link › Send a problem report): device, setup, link (with the Android phone's last
 * status, its numbers, SIM and client names removed), calls and audio, the link log and the app's log, in the format
 * of the phone app's and the iPhone's reports ([ProblemReport]). Slow (reads logcat): never on the main thread.
 */
object ClientReport {
    const val WHICH = "Tether Client"

    fun build(app: ClientApp, now: Long = System.currentTimeMillis()): ProblemReport {
        val scrubber = ReportScrubber(names = privateNames(app), keep = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.BRAND, Build.MANUFACTURER, Build.BOARD))
        val r = ProblemReport(ProblemReport.appLine(versionName(app), versionCode(app), WHICH), ProblemReport.created(now), scrubber)

        val d = r.section("Device")
        d.kv("Manufacturer", Build.MANUFACTURER).kv("Brand", Build.BRAND).kv("Model", Build.MODEL).kv("Device", Build.DEVICE)
            .kv("Product", Build.PRODUCT).kv("Board", Build.BOARD).kv("Hardware", Build.HARDWARE).kv("Platform", getprop("ro.board.platform"))
        if (Build.VERSION.SDK_INT >= 31) d.kv("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        d.kv("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").kv("Security patch", Build.VERSION.SECURITY_PATCH)
            .kv("Build", Build.DISPLAY).kv("Fingerprint", Build.FINGERPRINT)
        getprop("ro.build.version.oneui")?.let { d.kv("One UI", oneUiVersion(it)) }
        d.kv("Locale", app.resources.configuration.locales[0]?.toLanguageTag()).kv("ABI", Build.SUPPORTED_ABIS.joinToString(", "))

        val s = r.section("Setup")
        val (granted, missing) = permissions(app)
        s.kv("Permissions granted", granted.joinToString(", ")).kv("Permissions missing", missing.joinToString(", ").ifEmpty { "none" })
        s.kv("Link permissions", app.hasLinkPermissions()).kv("Microphone permission", app.hasMicPermission())
        s.kv("Notifications allowed", app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        s.kv("Battery optimisation off for Tether Client", app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName))
        val tm = app.getSystemService(TelecomManager::class.java)
        val account = runCatching { tm.getPhoneAccount(app.telecom.account) }.getOrNull()
        s.kv("Telecom phone account", if (account == null) "not registered" else "registered, ${if (account.isEnabled) "enabled" else "disabled"}")
        s.kv("Default phone app", runCatching { tm.defaultDialerPackage }.getOrNull())
        s.kv("Dialer role held by Tether Client", runCatching { app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER) }.getOrNull())

        val l = r.section("Link")
        l.kv("State", app.linkState.name.lowercase()).kv("Transport", app.transport).kv("Paired", app.identity.isPaired)
        l.kv("Round trip", app.link.roundTripMs?.let { "$it ms" })
        val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
        l.kv("Bluetooth on", bt?.isEnabled)
        l.kv("Android phone's status (numbers and names removed)", app.state.status?.let { sanitizedStatus(it) })
        l.kv("Network switches (last answer)", app.state.net?.let { JSONObject(it.toString()).apply { remove("proxy") } })

        val a = r.section("Calls and audio")
        a.kv("Calls now", app.calls.hasCalls).kv("Mic warning", app.micWarning?.toString() ?: "none")
        a.kv("Microphone failed to open", app.audio.recorderFailed).kv("Microphone captured only silence", app.audio.captureSilent)
        a.kv("Uplink gate", "wanted ${app.audio.uplinkWanted}, open ${app.audio.uplinkOpen}")
        a.kv("Last call's playout", app.audio.lastStats ?: "no call since the app started")
        a.kv("Audio route", app.telecom.audioState?.let { "route ${it.route}, muted ${it.isMuted}" })
        val st = app.state.selftest
        if (st == null) a.kv("Last self-test", "none since the app started")
        else st.optJSONArray("methods")?.let { m -> a.kv("Last self-test", "chosen ${st.optString("chosen", "–")}")
            for (i in 0 until m.length()) m.optJSONObject(i)?.let { row -> a.line("  ${if (row.optBoolean("ok")) "ok  " else "FAIL"} ${row.optString("name")}: ${row.optString("note")}") } }

        r.log("Link log", app.journal.text.lines().reversed().filter { it.isNotBlank() })
        r.log("App log", listOf("-- this app's process (logcat --pid)") + ownLogcat(800))
        return r
    }

    /** The Android phone's status without its numbers, SIM, carrier, device or client names. */
    fun sanitizedStatus(status: JSONObject): JSONObject {
        val o = JSONObject(status.toString())
        o.remove("device")
        o.optJSONArray("sims")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.apply { remove("number"); remove("name"); remove("carrier") } }
        o.optJSONArray("clients")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.remove("name") }
        return o
    }

    /** Names that identify a person or device here: the paired phone, this phone, SIMs and carriers, clients, Bluetooth devices. */
    @SuppressLint("MissingPermission")
    fun privateNames(app: ClientApp): List<String> {
        val names = ArrayList<String>()
        app.identity.peerName?.let { names += it }
        names += app.deviceName()
        app.state.status?.let { st ->
            names += st.optString("device")
            st.optJSONArray("sims")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { names += it.optString("name"); names += it.optString("carrier") } }
            st.optJSONArray("clients")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { names += it.optString("name") } }
        }
        app.state.sims.forEach { names += it.name; names += it.carrier }
        runCatching {
            val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
            bt?.bondedDevices?.forEach { d -> d.name?.let { names += it }; if (Build.VERSION.SDK_INT >= 30) d.alias?.let { names += it } }
            bt?.name?.let { names += it }
        }
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

    private fun versionName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    private fun versionCode(ctx: Context): Long = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(-1)

    /** Samsung's `ro.build.version.oneui` (e.g. 80000) as "8.0". */
    fun oneUiVersion(raw: String?): String? {
        val v = raw?.trim()?.toIntOrNull() ?: return raw?.takeIf { it.isNotBlank() }
        return "${v / 10000}.${(v % 10000) / 100}" + ((v % 100).takeIf { it > 0 }?.let { ".$it" } ?: "")
    }

    private fun getprop(key: String): String? = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun ownLogcat(n: Int): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.takeLast(n)
    }.getOrElse { listOf("logcat not readable: ${it.message}") }

}

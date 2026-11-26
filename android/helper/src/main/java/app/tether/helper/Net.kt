package app.tether.helper

import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Network switches the iPhone can flip from the Link tab: mobile data, the Wi-Fi hotspot and Bluetooth
 * tethering. The shell user holds MODIFY_PHONE_STATE and TETHER_PRIVILEGED, so the helper can do what the
 * quick-settings tiles do.
 */
object Net {
    private const val TETHERING_WIFI = 0
    private const val TETHERING_BLUETOOTH = 2

    private fun sh(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        out.trim()
    } catch (t: Throwable) { "" }

    fun state(): JSONObject {
        val o = JSONObject()
        o.put("data", sh("settings", "get", "global", "mobile_data") == "1")
        // Which SIM carries mobile data (subscription id, as in the status message's sims[].sub).
        // (The static SubscriptionManager getter answers -1 in this process; the setting is the source of truth.)
        o.put("dataSub", sh("settings", "get", "global", "multi_sim_data_call").toIntOrNull() ?: -1)
        // "Tether state:" lists every tetherable interface with its state machine state.
        val teth = sh("dumpsys", "tethering")
        val block = teth.substringAfter("Tether state:", "").lineSequence().drop(1)   // rest of the header line
            .takeWhile { it.startsWith("    ") }
            .filter { " - " in it && "State" in it.substringAfter(" - ").substringBefore(" ") }.toList()
        fun tethered(vararg prefixes: String) = block.any { l ->
            val iface = l.trim().substringBefore(" ")
            prefixes.any { iface.startsWith(it) } && "TetheredState" in l
        }
        o.put("hotspot", tethered("swlan", "softap", "ap_br", "wlan"))
        val pan = sh("dumpsys", "bluetooth_manager").lineSequence().firstOrNull { "mTetherOn" in it }
        o.put("bt", pan?.contains("true") == true || tethered("bt-pan"))
        // Downstream interfaces currently tethered (the app's share proxy listens on these only).
        o.put("tethered", org.json.JSONArray(block.filter { "TetheredState" in it }.map { it.trim().substringBefore(" ") }))
        // A VPN is up: a tun interface exists (only VPN apps create them; "tunl0" is the kernel's IP-in-IP device).
        o.put("vpn", java.io.File("/sys/class/net").list()?.any { Regex("tun\\d+").matches(it) } == true)
        o.put("airplane", sh("settings", "get", "global", "airplane_mode_on") == "1")
        // What this phone can tether at all (the iPhone hides switches it lacks). "tetherable…Regexs: []" means no;
        // a missing line (unknown dumpsys format) counts as yes. Before Android 11 the switches cannot work (see tether).
        fun can(key: String) = android.os.Build.VERSION.SDK_INT >= 30 && teth.lineSequence().firstOrNull { it.trim().startsWith("$key:") }
            ?.substringAfter(":")?.trim()?.let { it != "[]" && it.isNotEmpty() } ?: true
        o.put("can", org.json.JSONObject().put("hotspot", can("tetherableWifiRegexs")).put("bt", can("tetherableBluetoothRegexs")))
        o.put("rat", org.json.JSONArray((0..1).mapNotNull { slot -> ratOf(slot)?.let { org.json.JSONObject().put("slot", slot).put("mode", it) } }))
        return o
    }

    /**
     * Airplane mode, keeping Bluetooth on: the iPhone reaches the Android phone only over Bluetooth, so airplane mode
     * must never take it down (Android 14+ keeps Bluetooth on when the user had it on in airplane mode before;
     * if it went off anyway it is switched straight back on).
     */
    private fun airplane(on: Boolean): String? {
        sh("cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable")
        if (on) {
            for (i in 0 until 20) {
                Thread.sleep(250)
                if (sh("settings", "get", "global", "bluetooth_on") == "0") {
                    sh("svc", "bluetooth", "enable")
                    System.err.println("airplane: Bluetooth went off, switched back on")
                    break
                }
            }
        }
        val now = sh("settings", "get", "global", "airplane_mode_on") == "1"
        return if (now == on) null else "airplane mode did not change"
    }

    // Allowed network types per SIM slot, as the phone's own "Network mode" menu offers them.
    private val ratBits = linkedMapOf(
        "2G" to listOf("GSM", "GPRS", "EDGE"),
        "3G" to listOf("UMTS", "HSDPA", "HSUPA", "HSPA", "HSPA+", "TD_SCDMA"),
        "4G" to listOf("LTE", "LTE_CA"),
        "5G" to listOf("NR"))
    private val bit = mapOf("GPRS" to 1, "EDGE" to 2, "UMTS" to 4, "HSDPA" to 128, "HSUPA" to 256, "HSPA" to 512,
        "LTE" to 4096, "HSPA+" to 16384, "GSM" to 32768, "TD_SCDMA" to 65536, "LTE_CA" to 262144, "NR" to 524288)

    /** "5G" / "4G" / "3G" / "2G": the highest generation the slot may use (null: no SIM / unreadable). */
    fun ratOf(slot: Int): String? {
        val out = sh("cmd", "phone", "get-allowed-network-types-for-users", "-s", slot.toString())
        if (out.isBlank() || out.contains("rror") || !out.contains("GSM") && !out.contains("LTE")) return null
        val names = out.split('|', ' ', '\n').map { it.trim() }.toSet()
        return ratBits.keys.reversed().firstOrNull { g -> ratBits[g]!!.any { it in names } }
    }

    /** Allows every technology up to [mode] on [slot] ("auto connect", like the phone's own menu). */
    fun setRat(slot: Int, mode: String): String? {
        val gens = ratBits.keys.toList()
        val upTo = gens.indexOf(mode).takeIf { it >= 0 } ?: return "unknown network mode $mode"
        val mask = gens.take(upTo + 1).flatMap { ratBits[it]!! }.sumOf { bit[it] ?: 0 }
        // The shell command takes the bitmask written in binary.
        val out = sh("cmd", "phone", "set-allowed-network-types-for-users", "-s", slot.toString(), Integer.toBinaryString(mask))
        if (out.contains("rror") || out.contains("ailed")) return out.take(120)
        return if (ratOf(slot) == mode) null else "the phone did not accept $mode"
    }

    /** Makes [sub] the SIM used for mobile data (what the "Mobile data" SIM picker in Settings does). */
    fun setDataSub(sub: Int): String? = try {
        // Straight to the subscription service: SubscriptionManager needs a telephony service registry
        // that only exists in app processes started by the zygote.
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "isub")
        val isub = Class.forName("com.android.internal.telephony.ISub\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        isub.javaClass.getMethod("setDefaultDataSubId", Int::class.javaPrimitiveType).invoke(isub, sub)
        null
    } catch (t: Throwable) {
        CallAudio.describe(t)
    }

    fun set(what: String, on: Boolean): String? = try {
        when (what) {
            "data" -> { sh("svc", "data", if (on) "enable" else "disable"); null }
            "hotspot" -> tether(TETHERING_WIFI, on)
            "bt" -> tether(TETHERING_BLUETOOTH, on)
            "airplane" -> airplane(on)
            else -> "unknown switch $what"
        }
    } catch (t: Throwable) {
        CallAudio.describe(t)
    }

    /** TetheringManager.startTethering / stopTethering through reflection (system API, not in the SDK jar). */
    private fun tether(type: Int, on: Boolean): String? {
        // Built by hand with our shell context: the cached system-service instance would present the
        // wrong package name, and the tethering service then answers "no permission" (error 14).
        if (android.os.Build.VERSION.SDK_INT < 30) return "this switch needs Android 11 or later"
        val cls = Class.forName("android.net.TetheringManager")
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        val supplier = java.util.function.Supplier<android.os.IBinder?> { binder.invoke(null, "tethering") as android.os.IBinder? }
        val tm = cls.getConstructor(android.content.Context::class.java, java.util.function.Supplier::class.java)
            .newInstance(ShellEnv.context, supplier)
        Thread.sleep(300) // the manager fetches its connector asynchronously
        if (!on) {
            cls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, type)
            return null
        }
        val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val reqBuilder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val request = reqBuilder.getConstructor(Int::class.javaPrimitiveType).newInstance(type).let {
            runCatching { reqBuilder.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(it, false) }
            reqBuilder.getMethod("build").invoke(it)
        }
        val latch = CountDownLatch(1)
        var error: String? = null
        val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> latch.countDown()
                "onTetheringFailed" -> { error = "tethering error ${args?.firstOrNull()}"; latch.countDown() }
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
                "toString" -> return@newProxyInstance "TetherCallback"
            }
            null
        }
        cls.getMethod("startTethering", request.javaClass, Executor::class.java, cbClass)
            .invoke(tm, request, Executor { it.run() }, cb)
        if (!latch.await(15, TimeUnit.SECONDS)) return "no answer from the tethering service"
        return error
    }
}

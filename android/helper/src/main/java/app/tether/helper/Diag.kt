package app.tether.helper

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The helper's own log lines (ShellEnv.log), the newest few hundred, for problem reports. */
object LogRing {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun add(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized fun tail(n: Int = MAX): List<String> = lines.toList().takeLast(n)
}

/**
 * Call-audio diagnostics for problem reports (request DIAG, and taken by itself when a call's path looks dead and at
 * the end of each bridged call; protocol §6). It answers the question of issue #13 (a capture that reads only zeros):
 * does Android silence the helper's recording (`AudioRecordingConfiguration.isClientSilenced`), or does the phone's
 * audio HAL deliver nothing? Only allow-listed system properties and filtered lines of `dumpsys audio` /
 * `dumpsys media.audio_policy` go in: never serials, IMEI, Wi-Fi or Bluetooth names and addresses.
 */
object Diag {
    private val am: AudioManager get() = ShellEnv.audioManager

    /** The fast part (system APIs only, a few ms): safe to take while a call's audio is about to stop. */
    fun quick(own: JSONObject?): JSONObject = JSONObject()
        .put("at", System.currentTimeMillis())
        .put("sdk", Build.VERSION.SDK_INT)
        .put("audioMode", MicPath.Mode.name(CallAudio.audioMode()))
        .put("interceptable", CallAudio.isInterceptable() ?: JSONObject.NULL)
        .put("silence", CallAudio.silence.name.lowercase())
        .put("recordings", runCatching { recordings() }.getOrElse { JSONArray().put(JSONObject().put("error", CallAudio.describe(it))) })
        .put("playbacks", runCatching { playbacks() }.getOrElse { JSONArray().put(JSONObject().put("error", CallAudio.describe(it))) })
        .put("inputs", runCatching { devices(AudioManager.GET_DEVICES_INPUTS) }.getOrElse { JSONArray() })
        .put("outputs", runCatching { devices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrElse { JSONArray() })
        .apply { own?.let { put("helper", it) } }

    /** [quick] plus the slow parts: system properties, filtered dumpsys sections and the helper's log. */
    fun full(own: JSONObject?, quick: JSONObject = quick(own), logcat: Boolean = false): JSONObject = quick.apply {
        put("props", runCatching { DiagFilter.props(run("getprop")) }.getOrElse { JSONObject().put("error", CallAudio.describe(it)) })
        put("dumpsysAudio", JSONArray(runCatching { DiagFilter.audio(run("dumpsys audio").lines()) }.getOrElse { listOf("error: ${CallAudio.describe(it)}") }))
        put("dumpsysPolicy", JSONArray(runCatching { DiagFilter.policy(run("dumpsys media.audio_policy").lines()) }.getOrElse { listOf("error: ${CallAudio.describe(it)}") }))
        put("helperLog", JSONArray(LogRing.tail(200)))
        // Tether's own lines from every process (the app's earlier runs too): the shell may read the whole log.
        if (logcat) put("logcat", JSONArray(runCatching { run("logcat -d -t 1500 -s Tether:V").lines().takeLast(800) }.getOrElse { listOf("error: ${CallAudio.describe(it)}") }))
    }

    /** The active recordings: whose, which source and device, and whether Android silences them. */
    private fun recordings(): JSONArray = JSONArray().apply {
        for (c in am.activeRecordingConfigurations) {
            val uid = hidden(c, "getClientUid") as? Int
            put(JSONObject()
                .put("uid", uid ?: JSONObject.NULL)
                .put("package", (hidden(c, "getClientPackageName") as? String) ?: JSONObject.NULL)
                .put("self", uid == android.os.Process.myUid())
                .put("source", DiagFilter.sourceName(c.clientAudioSource))
                .put("session", c.clientAudioSessionId)
                .put("device", c.audioDevice?.let { DiagFilter.deviceName(it.type) } ?: JSONObject.NULL)
                .put("rate", c.clientFormat.sampleRate)
                .apply { if (Build.VERSION.SDK_INT >= 29) put("isClientSilenced", c.isClientSilenced) else put("isClientSilenced", "unknown before Android 10") }
                .apply { (hidden(c, "getClientPortId") as? Int)?.let { put("portId", it) } })
        }
    }

    /** Active players that matter for calls (voice, ringtone, signalling) and the helper's own. */
    private fun playbacks(): JSONArray = JSONArray().apply {
        for (p in am.activePlaybackConfigurations) {
            val usage = p.audioAttributes.usage
            val uid = hidden(p, "getClientUid") as? Int
            val self = uid == android.os.Process.myUid()
            if (!self && usage !in CALL_USAGES) continue
            put(JSONObject().put("uid", uid ?: JSONObject.NULL).put("self", self).put("usage", DiagFilter.usageName(usage))
                .put("state", (hidden(p, "getPlayerState") as? Int)?.let { DiagFilter.playerState(it) } ?: JSONObject.NULL)
                .apply { if (Build.VERSION.SDK_INT >= 34) p.audioDeviceInfo?.let { put("device", DiagFilter.deviceName(it.type)) } })
        }
    }

    private val CALL_USAGES = setOf(2, 3, 6, 17)   // voice communication, its signalling, ringtone, call assistant

    /** Input or output devices: type and id; a product name only for the phone's own (built-in) devices. */
    private fun devices(flag: Int) = JSONArray().apply {
        for (d in am.getDevices(flag)) put(JSONObject().put("id", d.id).put("type", DiagFilter.deviceName(d.type))
            .apply { if (DiagFilter.builtIn(d.type)) put("name", d.productName.toString()) })
    }

    /** A hidden getter by reflection (the helper is not bound by the app's hidden-API list); null when absent. */
    private fun hidden(o: Any, name: String): Any? = runCatching { o.javaClass.getMethod(name).invoke(o) }.getOrNull()

    /** Runs a shell command (we are the shell user) with a time limit and returns its output. */
    private fun run(cmd: String, timeoutSec: Long = 6): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 4_000_000) out.append(it).append('\n') } } }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); out.append("(timed out after $timeoutSec s)\n") }
        reader.join(1000)
        return out.toString()
    }
}

/** The pure parts of [Diag]: which properties and which dumpsys lines go into a report. JVM-tested. */
object DiagFilter {
    private val PROP_PREFIXES = listOf("ro.product.", "ro.soc.", "ro.build.version.", "ro.vendor.audio.", "persist.vendor.audio.",
        "ro.audio.", "vendor.audio.", "ro.config.vc_call", "persist.audio.", "ro.af.", "af.")
    private val PROP_KEYS = setOf("ro.board.platform", "ro.hardware", "ro.build.fingerprint", "ro.vendor.build.fingerprint",
        "ro.boot.hardware.revision", "ro.build.display.id", "ro.build.type", "ro.build.characteristics", "ro.telephony.default_network",
        "ro.hardware.chipname", "ro.chipname", "ro.vendor.qti.va_aosp.support", "ro.mediatek.platform")
    /** Never, even under an allowed prefix. */
    private val PROP_NEVER = Regex("serial|imei|meid|mac|bt|bluetooth|wifi|wlan|ssid|name.*user|owner|account|phone.?number|iccid|imsi|hostname|device_name", RegexOption.IGNORE_CASE)
    private val GETPROP = Regex("^\\[([^\\]]+)]: \\[(.*)]$")

    /** The same values again for each partition, attestation copies and long code-name lists: left out (noise). */
    private val PROP_NOISE = Regex("^ro\\.product\\.(bootimage|odm|odm_dlkm|product|system|system_ext|system_dlkm|vendor_dlkm)\\.|_for_attestation$|codenames$")

    fun allowedProp(key: String): Boolean =
        (key in PROP_KEYS || PROP_PREFIXES.any { key.startsWith(it) }) && !PROP_NEVER.containsMatchIn(key.removePrefix("ro.product."))
            && !key.contains("serial", ignoreCase = true) && !PROP_NOISE.containsMatchIn(key)

    /** `getprop` output (`[key]: [value]` lines) → the allowed keys, sorted, at most 200. */
    fun props(getprop: String): JSONObject {
        val o = JSONObject()
        getprop.lineSequence().mapNotNull { GETPROP.matchEntire(it.trim()) }.map { it.groupValues[1] to it.groupValues[2] }
            .filter { allowedProp(it.first) && it.second.isNotEmpty() }.sortedBy { it.first }.take(200)
            .forEach { (k, v) -> o.put(k, v.take(200)) }
        return o
    }

    /** `name:…` (a device's name) up to the next field, a quoted port name on a device line, long profile lists. */
    private val NAME_FIELD = Regex("(\\bname[:=])(.*?)(?=\\s+(?:profiles|descriptors|isPrivileged|from|eventSource|codec|group|disabled)[:=]|[,\\]\\)}]|$)")
    private val PROFILES = Regex("\\s*profiles:\\[.*?]\\s*descriptors:\\[.*?]")
    private val QUOTED = Regex("\"[^\"]*\"")

    /** A dumpsys line with device names removed (addresses are cut by the report's scrubber). */
    fun clean(line: String): String {
        var s = PROFILES.replace(line, "")
        s = NAME_FIELD.replace(s) { m -> m.groupValues[1] + "–" }
        if (s.contains("AUDIO_DEVICE_") || s.contains("DeviceInfo") || s.contains("Sink:") || s.contains("Source:")) s = QUOTED.replace(s, "\"–\"")
        return s.trimEnd().take(300)
    }

    /**
     * From `dumpsys audio`: the audio mode and its owner, the phone-state and device-connection events, the recording
     * activity (with "silenced" per recording), the mic-mute log and the communication route.
     */
    fun audio(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        out += block(lines, Regex("^## Audio mode"), max = 12, label = "audio mode")
        out += block(lines, Regex("^## RecordActivityMonitor"), until = Regex("^### Recording Activity"), max = 20, label = "recording monitor")
        out += block(lines, Regex("^### Recording Activity"), until = Regex("^[A-Za-z#]"), tail = 40, label = "recording activity")
        out += block(lines, Regex("^### Phone state changes"), until = Regex("^#{1,3} "), tail = 20, label = "phone state changes")
        out += block(lines, Regex("^### Device connections"), until = Regex("^#{1,3} "), tail = 25, label = "device connections")
        out += block(lines, Regex("^setMicrophoneMute use"), until = Regex("^[A-Za-z#]"), tail = 10, label = "mic mute log")
        out += lines.filter { it.contains("Communication route", true) || it.contains("mCommunicationRouteClients", true) ||
            it.contains("Computed Preferred communication device", true) }.take(10).map { clean(it) }
        val silenced = lines.filter { it.contains("silenced", true) && !it.contains("not silenced", true) }.map { clean(it) }
        if (silenced.isNotEmpty()) out += listOf("-- lines saying silenced") + silenced.takeLast(20)
        return out
    }

    /** From `dumpsys media.audio_policy`: phone state and forced routes, input devices (types), active inputs and their
     *  clients, patches, the call state (Samsung), the "record silenced" log and any line saying silenced. */
    fun policy(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        out += lines.filter { l -> l.trimStart().let { it.startsWith("Phone state:") || it.startsWith("Force use for communications") ||
            it.startsWith("Force use for record") || it.startsWith("Communication Strategy") } }.take(6).map { clean(it) }
        val devIdx = lines.indexOfFirst { it.trimStart().startsWith("Available input devices") }
        if (devIdx >= 0) {
            out += "-- " + lines[devIdx].trim()
            out += lines.drop(devIdx + 1).takeWhile { it.isBlank() || it.startsWith("  ") }
                .filter { Regex("^\\s{1,3}\\d+\\. Port ID").containsMatchIn(it) }
                .map { l -> Regex("Port ID: \\d+").find(l)?.value + " " + (Regex("AUDIO_DEVICE_IN_\\w+").find(l)?.value ?: "?") }.take(12)
        }
        out += block(lines, Regex("^ Inputs \\("), until = Regex("^ \\S"), max = 60, label = "inputs", skip = PROFILE_LINES)
        out += block(lines, Regex("^ Audio Patches"), until = Regex("^ \\S"), max = 20, label = "audio patches")
        out += block(lines, Regex("^ Audio Policy Mix"), until = Regex("^ \\S"), max = 20, label = "policy mixes")
        out += block(lines, Regex("^CallStateManager:"), until = Regex("^\\S"), max = 16, label = "call state")
        out += block(lines, Regex("^Record Silenced Logs"), until = Regex("^\\s*$"), tail = 20, label = "record silenced log")
        val silenced = lines.filter { it.contains("silenc", true) && !it.startsWith("Record Silenced Logs") }.map { clean(it) }
        if (silenced.isNotEmpty()) out += listOf("-- lines saying silenced") + silenced.takeLast(20)
        return out
    }

    private val PROFILE_LINES = Regex("^\\s+(sampling rates|channel masks|AUDIO_ENCAPSULATION|Encapsulation modes|Port Hal ID|\\d+\\. \"\";)")

    /** The lines after the first line matching [start] (and that line), up to [until] or a blank line. */
    private fun block(lines: List<String>, start: Regex, until: Regex? = null, max: Int = 30, tail: Int = 0, label: String,
                      skip: Regex? = null): List<String> {
        val i = lines.indexOfFirst { start.containsMatchIn(it) }
        if (i < 0) return emptyList()
        val body = ArrayList<String>()
        for (l in lines.drop(i + 1)) {
            if (until != null && until.containsMatchIn(l)) break
            if (until == null && l.isBlank()) break
            if (l.isBlank() || (skip != null && skip.containsMatchIn(l))) continue
            body += clean(l)
        }
        val picked = if (tail > 0) body.takeLast(tail) else body.take(max)
        return listOf("-- $label: " + clean(lines[i]).trim()) + picked
    }

    fun sourceName(s: Int) = when (s) {
        0 -> "DEFAULT"; 1 -> "MIC"; 2 -> "VOICE_UPLINK"; 3 -> "VOICE_DOWNLINK"; 4 -> "VOICE_CALL"; 5 -> "CAMCORDER"
        6 -> "VOICE_RECOGNITION"; 7 -> "VOICE_COMMUNICATION"; 8 -> "REMOTE_SUBMIX"; 9 -> "UNPROCESSED"; 10 -> "VOICE_PERFORMANCE"
        1997 -> "ECHO_REFERENCE"; 1998 -> "RADIO_TUNER"; 1999 -> "HOTWORD"; else -> "source $s"
    }

    fun usageName(u: Int) = when (u) {
        1 -> "MEDIA"; 2 -> "VOICE_COMMUNICATION"; 3 -> "VOICE_COMMUNICATION_SIGNALLING"; 4 -> "ALARM"; 5 -> "NOTIFICATION"
        6 -> "NOTIFICATION_RINGTONE"; 13 -> "ASSISTANCE_SONIFICATION"; 15 -> "VIRTUAL_SOURCE"; 17 -> "CALL_ASSISTANT"; else -> "usage $u"
    }

    fun playerState(s: Int) = when (s) { 1 -> "idle"; 2 -> "started"; 3 -> "paused"; 4 -> "stopped"; 0 -> "released"; else -> "state $s" }

    /** The phone's own devices (earpiece, speaker, built-in mic, telephony, speaker safe, remote submix, echo reference). */
    fun builtIn(type: Int) = type in setOf(1, 2, 15, 18, 24, 25, 28)

    fun deviceName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"; AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"; AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"; AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"; AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker_safe"; AudioDeviceInfo.TYPE_FM_TUNER -> "fm_tuner"
        AudioDeviceInfo.TYPE_IP -> "ip"; AudioDeviceInfo.TYPE_BUS -> "bus"
        26 -> "ble_headset"; 27 -> "ble_speaker"; 28 -> "echo_reference"; 29 -> "hdmi_earc"; 30 -> "ble_broadcast"
        else -> "type $type"
    }
}

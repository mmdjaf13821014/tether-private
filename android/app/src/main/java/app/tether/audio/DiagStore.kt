package app.tether.audio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few call-audio diagnostics snapshots the helper took by itself (a dead path in a call, the end of every
 * bridged call; protocol §6 INFO `autodiag`), kept in memory and in a small file so a problem report made later still
 * has them. Snapshots of the test line are kept too, marked as such.
 */
class DiagStore(private val file: File?, private val max: Int = 3) {
    private val items = ArrayDeque<JSONObject>()

    init {
        runCatching {
            val f = file ?: return@runCatching
            if (f.exists()) JSONArray(f.readText()).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.addLast(it) } }
            while (items.size > max) items.removeFirst()
        }
    }

    /** Adds the helper's INFO `{autodiag, trigger, testLine}` with the app's view of the call ([context]). */
    @Synchronized fun add(info: JSONObject, context: JSONObject = JSONObject(), now: Long = System.currentTimeMillis()) {
        val snap = info.optJSONObject("autodiag") ?: return
        items.addLast(JSONObject().put("at", now).put("trigger", info.optString("trigger", "?"))
            .put("testLine", info.optBoolean("testLine")).put("context", context).put("diag", snap))
        while (items.size > max) items.removeFirst()
        runCatching { file?.writeText(JSONArray(items.toList()).toString()) }
    }

    /** Oldest first. */
    @Synchronized fun all(): List<JSONObject> = items.toList()

    companion object {
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        /** What a trigger means, in a report. */
        fun triggerText(t: String) = when (t) {
            "down-zeros" -> "the downlink capture read only zeros in the active call"
            "down-nothing" -> "the downlink capture delivered nothing"
            "up-stuck" -> "the phone took nothing from the uplink injection"
            "call-end" -> "end of a bridged call"
            "now" -> "taken for this report"
            else -> t
        }

        /**
         * A diagnostics snapshot (the helper's INFO `diag`, or one of [all]'s `diag`) as report lines: the audio mode and
         * the interception API, each active recording with isClientSilenced, call-related players, devices, the helper's
         * own view of the call, the allow-listed properties, the filtered dumpsys lines and the helper's log.
         */
        fun lines(d: JSONObject, helperLog: Int = 200): List<String> {
            val out = ArrayList<String>()
            out += "audio mode: ${d.optString("audioMode", "?")} · interception API: ${d.opt("interceptable") ?: "?"} · silence: ${d.optString("silence", "?")}"
            val recs = d.optJSONArray("recordings") ?: JSONArray()
            out += "recordings now (${recs.length()}):"
            for (i in 0 until recs.length()) recs.optJSONObject(i)?.let { r ->
                if (r.has("error")) out += "  error: ${r.optString("error")}"
                else out += "  uid ${r.opt("uid")} ${r.optString("package", "")}${if (r.optBoolean("self")) " (the helper)" else ""} · ${r.optString("source")} · " +
                    "device ${r.opt("device")} · ${r.optInt("rate")} Hz · session ${r.optInt("session")} · isClientSilenced=${r.opt("isClientSilenced")}"
            }
            val plays = d.optJSONArray("playbacks") ?: JSONArray()
            out += "call players now (${plays.length()}):"
            for (i in 0 until plays.length()) plays.optJSONObject(i)?.let { p ->
                out += if (p.has("error")) "  error: ${p.optString("error")}" else
                    "  uid ${p.opt("uid")}${if (p.optBoolean("self")) " (the helper)" else ""} · ${p.optString("usage")} · ${p.opt("state")}" +
                        (p.optString("device").takeIf { it.isNotEmpty() }?.let { " · device $it" } ?: "")
            }
            fun devs(k: String) = d.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .joinToString(", ") { it.optString("type") + "#" + it.optInt("id") + (it.optString("name").takeIf { n -> n.isNotEmpty() }?.let { n -> " ($n)" } ?: "") } } ?: "–"
            out += "input devices: ${devs("inputs")}"
            out += "output devices: ${devs("outputs")}"
            d.optJSONObject("helper")?.let { h ->
                out += "helper's call audio: " + h.keys().asSequence().sorted().filter { it != "mic" && it != "uplink" }.joinToString(", ") { "$it=${h.opt(it)}" }
                h.optJSONObject("mic")?.takeIf { it.length() > 0 }?.let { out += "helper's mic report: $it" }
                h.optJSONObject("uplink")?.takeIf { it.length() > 0 }?.let { out += "helper's uplink report: $it" }
            }
            d.optJSONObject("props")?.let { p ->
                out += "system properties (allow-listed):"
                p.keys().asSequence().sorted().forEach { out += "  $it: ${p.opt(it)}" }
            }
            fun block(k: String, title: String, max: Int = 400) = d.optJSONArray(k)?.let { a ->
                out += "$title:"
                (0 until minOf(a.length(), max)).forEach { out += "  " + a.optString(it) }
            }
            block("dumpsysAudio", "dumpsys audio (selected lines)")
            block("dumpsysPolicy", "dumpsys media.audio_policy (selected lines)")
            d.optJSONArray("helperLog")?.let { a ->
                out += "helper log (newest ${minOf(a.length(), helperLog)} lines):"
                (maxOf(0, a.length() - helperLog) until a.length()).forEach { out += "  " + a.optString(it) }
            }
            return out
        }

        /** The heading line of a stored snapshot. */
        fun heading(s: JSONObject): String = "-- snapshot ${fmt.format(Date(s.optLong("at")))}: ${triggerText(s.optString("trigger"))}" +
            (if (s.optBoolean("testLine")) " (test line)" else "") +
            (s.optJSONObject("context")?.takeIf { it.length() > 0 }?.let { c -> " · " + c.keys().asSequence().sorted().joinToString(", ") { "$it=${c.opt(it)}" } } ?: "")
    }
}

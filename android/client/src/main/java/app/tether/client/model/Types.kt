package app.tether.client.model

import app.tether.core.CallEntry
import app.tether.core.Contact
import app.tether.core.Phone
import app.tether.core.Sms
import app.tether.core.numberKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// Parsing the protocol's objects (§5) into the shared core's types, and the client's own ones.

fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

fun smsOf(o: JSONObject) = Sms(o.getLong("id"), o.optLong("thread"), o.optString("addr"), o.optString("body"),
    o.optLong("date"), o.optInt("box", 1), o.optBoolean("read"), o.optIntOrNull("sub"))

fun contactOf(o: JSONObject) = Contact(o.getLong("id"), o.optString("name"),
    o.optJSONArray("phones").mapObjects { Phone(it.optString("n"), it.optString("type", "mobile"), it.optStringOrNull("label")) },
    o.optJSONArray("emails").strings(), o.optStringOrNull("photo"), o.optBoolean("starred"))

fun callEntryOf(o: JSONObject) = CallEntry(o.getLong("id"), o.optString("num"), o.optStringOrNull("name"), o.optLong("date"),
    o.optInt("dur"), o.optInt("type"), o.optIntOrNull("sub"))

val Sms.incoming get() = box == 1

/** A call as the Android phone reports it (`call.state`), with `elsewhere` / `where` (protocol §5.1). */
data class Call(val cid: String, val num: String, val name: String?, val state: String, val dir: String, val sub: Int?,
                val since: Long?, val cause: String?, val hold: Boolean, val elsewhere: Boolean, val where: String?) {
    val isEnded get() = state == "ended"
    val isIncoming get() = dir == "in"

    fun json(): JSONObject = JSONObject().put("cid", cid).put("num", num).put("state", state).put("dir", dir).put("hold", hold)
        .apply {
            name?.let { put("name", it) }; sub?.let { put("sub", it) }; since?.let { put("since", it) }
            cause?.let { put("cause", it) }; if (elsewhere) put("elsewhere", true); where?.let { put("where", it) }
        }

    companion object {
        fun of(o: JSONObject) = Call(o.optString("cid"), o.optString("num"), o.optStringOrNull("name"), o.optString("state"),
            o.optString("dir", "in"), o.optIntOrNull("sub"), o.optLongOrNull("since"), o.optStringOrNull("cause"),
            o.optBoolean("hold"), o.optBoolean("elsewhere"), o.optStringOrNull("where"))
    }
}

/** One SIM of the Android phone, from `status`. */
data class Sim(val slot: Int, val sub: Int, val name: String, val carrier: String, val signal: Int, val net: String?,
               val service: String?, val roaming: Boolean) {
    val hasService get() = service == null || service == "in"
    companion object {
        fun of(o: JSONObject) = Sim(o.optInt("slot"), o.optInt("sub"), o.optString("name"), o.optString("carrier"),
            o.optInt("signal"), o.optStringOrNull("net"), o.optStringOrNull("service"), o.optBoolean("roaming"))
    }
}

/** Blocked numbers and muted conversations as the Android phone keeps them (the source of truth). */
data class BlockLists(val blocked: List<String> = emptyList(), val muted: List<Long> = emptyList(), val system: Boolean = false) {
    fun isBlocked(number: String): Boolean { val k = numberKey(number); return k.isNotEmpty() && blocked.any { numberKey(it) == k } }
    fun json(): JSONObject = JSONObject().put("blocked", JSONArray(blocked)).put("muted", JSONArray(muted)).put("system", system)
    companion object {
        fun of(o: JSONObject) = BlockLists(o.optJSONArray("blocked").strings(), o.optJSONArray("muted").longs(), o.optBoolean("system"))
    }
}

/** sync.ids: inclusive, sorted id ranges [[first, last], …]; binary search. */
object IdRanges {
    fun parse(a: JSONArray?, after: Long?): List<LongArray>? {
        if (a == null) return null
        val ranges = ArrayList<LongArray>(a.length())
        var previousEnd = after
        for (i in 0 until a.length()) {
            val range = a.optJSONArray(i) ?: return null
            if (range.length() != 2) return null
            val first = integerId(range.opt(0)) ?: return null
            val last = integerId(range.opt(1)) ?: return null
            if (first > last || (previousEnd != null && first <= previousEnd)) return null
            ranges.add(longArrayOf(first, last))
            previousEnd = last
        }
        return ranges
    }

    private fun integerId(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    fun contains(ranges: List<LongArray>, id: Long): Boolean {
        var lo = 0; var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = ranges[mid]
            if (id < r[0]) hi = mid - 1 else if (id > r[1]) lo = mid + 1 else return true
        }
        return false
    }
}

/** sms.unread: is message [id] read on the Android phone, as far as this list knows (null: unknown). */
fun unreadSays(ids: Set<Long>, all: Boolean, id: Long): Boolean? {
    if (id in ids) return false
    if (!all) { val m = ids.minOrNull(); if (m != null && id < m) return null }
    return true
}

/**
 * Search as people type it, like TetherCore's SearchText: case, accents and Arabic / Persian letter variants do not
 * matter, and a number matches in any notation ("0912…" finds "+98 912…", Persian digits work).
 */
object Search {
    private val variants = listOf("ي" to "ی", "ى" to "ی", "ك" to "ک", "ة" to "ه", "أ" to "ا", "إ" to "ا", "آ" to "ا", "\u200C" to "", "\u200F" to "")

    fun fold(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        for ((a, b) in variants) t = t.replace(a, b)
        return t
    }

    fun digits(s: String) = s.map { c -> if (c in '۰'..'۹') '0' + (c - '۰') else if (c in '٠'..'٩') '0' + (c - '٠') else c }
        .filter { it in '0'..'9' }.joinToString("")

    /** Digits of a typed number without a trunk prefix ("0") or the Iranian country code, so notations meet. */
    fun numberCore(s: String): String {
        var d = digits(s)
        if (d.startsWith("0098")) d = d.substring(4) else if (d.startsWith("98") && d.length > 10) d = d.substring(2)
        return d.trimStart('0')
    }

    fun matches(query: String, names: List<String>, numbers: List<String>): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        // Decomposing folds Persian letters too (آ → ا + mark): compare folded with folded only.
        if (names.any { fold(it).contains(q) }) return true
        val qd = numberCore(query)
        if (qd.length < 2 || query.none { it.isDigit() }) return false
        return numbers.any { numberCore(it).contains(qd) }
    }
}

/** Digits and a leading + of a typed number (what is dialed and sent). */
fun dialable(s: String): String {
    val t = s.map { c -> if (c in '۰'..'۹') '0' + (c - '۰') else if (c in '٠'..'٩') '0' + (c - '٠') else c }
    return t.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.joinToString("")
}

/** The number of a tel: link's scheme-specific part (%-escapes decoded once more, as before), digits, + * # only. */
fun telNumber(ssp: String?): String {
    if (ssp == null) return ""
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < ssp.length) {
        val c = ssp[i]
        val hex = if (c == '%' && i + 2 < ssp.length) ssp.substring(i + 1, i + 3).takeIf { h -> h.all { it.isLetterOrDigit() } }?.toIntOrNull(16) else null
        if (hex != null) { out.write(hex); i += 3 } else { out.write(c.toString().toByteArray()); i++ }
    }
    return dialable(out.toString("UTF-8"))
}

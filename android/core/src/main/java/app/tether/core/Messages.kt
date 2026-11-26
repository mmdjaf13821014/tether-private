package app.tether.core

import org.json.JSONArray
import org.json.JSONObject

/** Control message envelope (protocol §5). */
class Message(val t: String, val b: JSONObject = JSONObject(), val id: Int? = null, val re: Int? = null) {
    fun toJson(): ByteArray {
        val o = JSONObject()
        o.put("t", t)
        id?.let { o.put("id", it) }
        re?.let { o.put("re", it) }
        o.put("b", b)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    override fun toString() = "Message($t id=$id re=$re)"

    companion object {
        fun parse(bytes: ByteArray): Message {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            return Message(
                o.getString("t"),
                o.optJSONObject("b") ?: JSONObject(),
                if (o.has("id")) o.getInt("id") else null,
                if (o.has("re")) o.getInt("re") else null,
            )
        }
    }
}

object T {
    const val PING = "ping"; const val PONG = "pong"; const val STATUS = "status"; const val SYNC = "sync"
    const val SMS_BATCH = "sms.batch"; const val SMS_NEW = "sms.new"; const val SMS_SEND = "sms.send"; const val SMS_READ = "sms.read"; const val SMS_UNREAD = "sms.unread"; const val SMS_RESULT = "sms.result"
    const val CONTACTS_BATCH = "contacts.batch"; const val CONTACT_ADD = "contact.add"; const val CONTACT_UPDATE = "contact.update"; const val CONTACT_STAR = "contact.star"; const val CONTACT_RESULT = "contact.result"
    const val CALLS_BATCH = "calls.batch"; const val CALL_STATE = "call.state"; const val CALL_LIST = "call.list"
    const val CALL_ANSWER = "call.answer"; const val CALL_REJECT = "call.reject"; const val CALL_END = "call.end"
    const val CALL_HOLD = "call.hold"; const val CALL_MUTE = "call.mute"; const val CALL_DTMF = "call.dtmf"
    const val CALL_DIAL = "call.dial"; const val CALL_DIALED = "call.dialed"; const val CALL_TAKE = "call.take"
    const val AUDIO_START = "audio.start"; const val AUDIO_STOP = "audio.stop"; const val AUDIO_STATS = "audio.stats"; const val AUDIO_SET = "audio.set"
    const val SELFTEST = "selftest"; const val SELFTEST_RESULT = "selftest.result"
    const val NET_GET = "net.get"; const val NET_SET = "net.set"; const val NET_STATE = "net.state"
    const val SMS_DELETE = "sms.delete"; const val SMS_DELETED = "sms.deleted"
    const val CALLS_DELETE = "calls.delete"; const val CALLS_DELETED = "calls.deleted"
    const val LISTS_SET = "lists.set"; const val LISTS = "lists"; const val SYNC_IDS = "sync.ids"
    const val USSD_SEND = "ussd.send"; const val USSD_RESULT = "ussd.result"
    const val PHONE_FIND = "phone.find"; const val PHONE_FINDING = "phone.finding"
    const val RINGER_SET = "ringer.set"; const val RINGER_RESULT = "ringer.result"
    const val UNPAIR = "unpair"; const val ERROR = "error"
}

/**
 * Extension messages (protocol §7): the reserved namespace, the capability fields and the limits. Extension types are
 * not core types, so they are never listed in [T]; the registry in app.tether.core.ext applies these rules.
 */
object Ext {
    /** Every extension type starts with this; nothing else may (core types never do). */
    const val PREFIX = "x."
    /** Extensions shipped in this repository; third parties use a reverse-DNS name they control. */
    const val BUILT_IN_PREFIX = "x.tether."
    /** `caps` in `sync` (client → phone) and `status` (phone → client): ["<id>/<major>", …]. */
    const val CAPS = "caps"
    /** `ext` in `status`: {"<id>": {…}}, small per-extension status fields. */
    const val STATUS = "ext"
    const val MAX_ID = 48
    const val MAX_TYPE = 64
    const val MAX_CAPS = 64
    /** The whole encoded envelope of one extension message; while call audio runs on the link, [MAX_BYTES_IN_CALL]. */
    const val MAX_BYTES = 16_384
    const val MAX_BYTES_IN_CALL = 2_048
    const val MAX_STATUS_BYTES = 1_024
    /** Extension messages per second per link and direction, with bursts up to [BURST]. */
    const val RATE_PER_S = 20
    const val BURST = 40
    /** Failures in a row after which an extension is switched off for the rest of the process. */
    const val MAX_FAILURES = 10
    /** `error.code` values for refused extension messages. */
    const val ERR_UNKNOWN = "unknown-type"
    const val ERR_TOO_LARGE = "too-large"
    const val ERR_FAILED = "failed"
    /** The built-in diagnostics extension (§7.9). */
    const val ECHO = "x.tether.echo"
}

data class Sms(val id: Long, val thread: Long, val addr: String, val body: String, val date: Long,
               val box: Int, val read: Boolean, val sub: Int?) {
    fun json(): JSONObject = JSONObject().put("id", id).put("thread", thread).put("addr", addr).put("body", body)
        .put("date", date).put("box", box).put("read", read).apply { sub?.let { put("sub", it) } }
}

data class Phone(val n: String, val type: String, val label: String? = null) {
    fun json(): JSONObject = JSONObject().put("n", n).put("type", type).apply { label?.let { put("label", it) } }
}

data class Contact(val id: Long, val name: String, val phones: List<Phone>, val emails: List<String>,
                   val photo: String?, val starred: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("phones", JSONArray(phones.map { it.json() })).put("emails", JSONArray(emails))
        .put("starred", starred).apply { photo?.let { put("photo", it) } }

    /** Rough encoded size, used to keep pages under the record limit. */
    fun weight() = 80 + name.length * 2 + phones.size * 40 + emails.sumOf { it.length + 4 } + (photo?.length ?: 0)
}

data class CallEntry(val id: Long, val num: String, val name: String?, val date: Long, val dur: Int, val type: Int, val sub: Int?) {
    fun json(): JSONObject = JSONObject().put("id", id).put("num", num).put("date", date).put("dur", dur)
        .put("type", type).apply { name?.let { put("name", it) }; sub?.let { put("sub", it) } }
}

data class CallInfo(val cid: String, val num: String, val name: String?, val state: String, val dir: String,
                    val sub: Int?, val since: Long?, val cause: String?, val hold: Boolean) {
    fun json(): JSONObject = JSONObject().put("cid", cid).put("num", num).put("state", state).put("dir", dir)
        .put("hold", hold).apply {
            name?.let { put("name", it) }; sub?.let { put("sub", it) }
            since?.let { put("since", it) }; cause?.let { put("cause", it) }
        }
}

/** Splits items into pages whose summed weight stays under [maxWeight]. */
fun <T> paginate(items: List<T>, maxWeight: Int, weight: (T) -> Int): List<List<T>> {
    val pages = ArrayList<List<T>>()
    var cur = ArrayList<T>()
    var w = 0
    for (it in items) {
        val iw = weight(it)
        if (cur.isNotEmpty() && w + iw > maxWeight) {
            pages.add(cur); cur = ArrayList(); w = 0
        }
        cur.add(it); w += iw
    }
    if (cur.isNotEmpty() || pages.isEmpty()) pages.add(cur)
    return pages
}

/** G.711 µ-law, bit-exact with the iOS implementation. */
object G711 {
    fun encode(sample: Short): Byte {
        var s = sample.toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0f
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    fun decode(b: Byte): Short {
        val u = b.toInt().inv() and 0xff
        val sign = u and 0x80
        val exp = (u shr 4) and 7
        val mant = u and 0x0f
        val s = (((mant shl 3) + 0x84) shl exp) - 0x84
        return (if (sign != 0) -s else s).toShort()
    }

    fun encode(pcm: ShortArray) = ByteArray(pcm.size) { encode(pcm[it]) }
    fun decode(b: ByteArray) = ShortArray(b.size) { decode(b[it]) }
}

/** Audio channel frame: codec ‖ seq ‖ payload. */
class AudioFrame(val codec: Int, val seq: Int, val payload: ByteArray) {
    fun encoded() = byteArrayOf(codec.toByte(), (seq ushr 8).toByte(), seq.toByte()) + payload

    companion object {
        const val OPUS = 1
        const val PCMU = 2
        fun parse(b: ByteArray): AudioFrame? {
            if (b.size < 3) return null
            val c = b[0].toInt() and 0xff
            if (c != OPUS && c != PCMU) return null
            return AudioFrame(c, ((b[1].toInt() and 0xff) shl 8) or (b[2].toInt() and 0xff), b.copyOfRange(3, b.size))
        }
    }
}

/** Same key as the iOS PhoneNumber.matchKey: the last 9 digits (Persian digits included), so "+98912…" = "0912…". */
fun numberKey(s: String): String {
    val d = s.map { c -> if (c in '۰'..'۹') '0' + (c - '۰') else if (c in '٠'..'٩') '0' + (c - '٠') else c }.filter { it in '0'..'9' }
    return d.takeLast(9).joinToString("")
}

/**
 * Ids as inclusive ranges, [[first,last],…], ascending: how "these rows still exist" is sent on sync so a
 * client can drop what was deleted (SMS and call ids are mostly consecutive, so this stays small).
 */
fun idRanges(ids: List<Long>): JSONArray {
    val out = JSONArray()
    var start = -1L; var prev = -1L
    for (id in ids.sorted()) {
        if (start < 0) { start = id; prev = id; continue }
        if (id == prev) continue
        if (id != prev + 1) { out.put(JSONArray().put(start).put(prev)); start = id }
        prev = id
    }
    if (start >= 0) out.put(JSONArray().put(start).put(prev))
    return out
}

package app.tether.core

import java.io.ByteArrayOutputStream

/** Record kinds (protocol §2). */
object Kind {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val REJECT = 0x03
    const val SEALED = 0x10
}

object Reject {
    const val BAD_VERSION = 1
    const val UNKNOWN_DEVICE = 2
    const val PAIRING_CLOSED = 3
    const val BUSY = 4
}

class Record(val kind: Int, val body: ByteArray) {
    fun encoded(): ByteArray {
        require(body.size <= 65534) { "record body too large" }
        val len = body.size + 1
        val out = ByteArray(body.size + 3)
        out[0] = (len ushr 8).toByte()
        out[1] = len.toByte()
        out[2] = kind.toByte()
        System.arraycopy(body, 0, out, 3, body.size)
        return out
    }

    override fun equals(other: Any?) = other is Record && other.kind == kind && other.body.contentEquals(body)
    override fun hashCode() = kind * 31 + body.contentHashCode()
    override fun toString() = "Record(kind=$kind, ${body.size}B)"
}

class FramingException(msg: String) : Exception(msg)

/** Accumulates stream bytes and yields complete records. */
class RecordReader {
    private var buf = ByteArray(4096)
    private var start = 0
    private var end = 0

    val pendingBytes get() = end - start

    fun append(data: ByteArray, off: Int = 0, len: Int = data.size) {
        if (end + len > buf.size) {
            val live = end - start
            val nb = if (live + len > buf.size) ByteArray(maxOf(buf.size * 2, live + len)) else buf
            System.arraycopy(buf, start, nb, 0, live)
            buf = nb
            start = 0
            end = live
        }
        System.arraycopy(data, off, buf, end, len)
        end += len
    }

    fun next(): Record? {
        if (end - start < 2) return null
        val len = ((buf[start].toInt() and 0xff) shl 8) or (buf[start + 1].toInt() and 0xff)
        if (len == 0) throw FramingException("zero-length record")
        if (end - start < 2 + len) return null
        val kind = buf[start + 2].toInt() and 0xff
        val body = buf.copyOfRange(start + 3, start + 2 + len)
        start += 2 + len
        if (start == end) { start = 0; end = 0 }
        return Record(kind, body)
    }

    fun drain(): List<Record> {
        val out = ArrayList<Record>()
        while (true) out.add(next() ?: return out)
    }
}

object Bytes {
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun concat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }
    fun be64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    fun readBe64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }
    fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64url(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)
}

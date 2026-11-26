package app.tether.core.ext

import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import org.json.JSONObject

/**
 * `x.tether.echo` (protocol §7.9): link diagnostics on every side. A request `{ts, data?}` with an `id` is answered
 * with `{ts, data?, at, via}`: the request's own timestamp and data back, the responder's clock and its transport.
 * The asker gets the round trip and an estimate of the clock offset. Touches nothing but the link, so it is safe on a
 * test transport and harmless to leave on.
 */
class EchoExtension(private val clock: () -> Long = System::currentTimeMillis) : TetherExtension {
    /** One answer: round trip, the responder's clock minus ours at the midpoint, its transport, the payload size. */
    data class Result(val peer: String, val rttMs: Long, val offsetMs: Long, val via: String, val bytes: Int, val error: String? = null)

    override val id = Ext.ECHO
    @Volatile var last: Result? = null; private set
    /** Every answer (the host's extension thread). */
    @Volatile var onResult: ((Result) -> Unit)? = null

    /** Asks [session]'s peer; the result arrives in [onResult]. Null when not sent (the peer does not speak echo). */
    fun ping(session: ExtensionSession, data: String? = null): Int? =
        session.request(id, JSONObject().put("ts", clock()).apply { data?.let { put("data", it) } })

    override fun onMessage(session: ExtensionSession, message: Message) {
        val b = message.b
        if (message.re != null) {
            val r = if (message.t == T.ERROR) Result(session.peerName, -1, 0, "", 0, b.optString("code", b.optString("msg")))
            else {
                val now = clock(); val ts = b.optLong("ts", now)
                val rtt = (now - ts).coerceAtLeast(0)
                Result(session.peerName, rtt, b.optLong("at", now) - (ts + rtt / 2), b.optString("via"), b.optString("data").toByteArray().size)
            }
            last = r
            onResult?.invoke(r)
            return
        }
        if (message.id == null) return   // neither a request nor an answer: nothing to do
        val out = JSONObject().put("ts", b.optLong("ts")).put("at", clock()).put("via", session.transport)
        if (b.has("data")) out.put("data", b.optString("data"))
        session.reply(message, out)
    }
}

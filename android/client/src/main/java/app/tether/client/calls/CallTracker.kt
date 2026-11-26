package app.tether.client.calls

import app.tether.client.model.Call
import app.tether.client.model.LinkPort
import app.tether.core.T
import app.tether.core.numberKey
import org.json.JSONObject
import java.util.UUID

/** Why a local call ended (mapped onto Telecom's DisconnectCause). */
enum class EndReason { REMOTE, UNANSWERED, FAILED, ANSWERED_ELSEWHERE, LOCAL, BUSY }

/** The platform's call system (Telecom on Android), driven by [CallTracker] with local call ids. */
interface CallSink {
    /** Ring: a new incoming call. */
    fun reportIncoming(local: String, c: Call)
    /** A call that already runs (dialed or answered on the Android phone, moved here, live at reconnect). */
    fun reportStarted(local: String, c: Call)
    fun update(local: String, c: Call)
    fun setActive(local: String)
    fun setHeld(local: String, on: Boolean)
    fun ended(local: String, reason: EndReason)
    fun ringback(on: Boolean)
    /** The microphone may go to the Android phone ([CallUplink]): a call here is connected. Called on changes only. */
    fun uplink(open: Boolean) {}
    /** No local call is left: call audio stops. */
    fun idle()
    fun changed() {}
    fun log(line: String) {}
}

/** What the call screen shows. */
data class LocalCall(val local: String, val cid: String?, val number: String, val name: String?, val state: String,
                     val incoming: Boolean, val sub: Int?, val connectedAt: Long?, val held: Boolean)

/**
 * Maps the Android phone's calls onto the local call system and back: a port of the iPhone's CallController (CallKit)
 * onto Telecom, with the same hard-won rules: ring first, a hang-up guard against late states, a dial hung up before
 * the Android phone reported it is ended as soon as it shows up, mirrored answers and holds, `elsewhere` ends the
 * local call (answered elsewhere) and offers "Move here", and calls are ended locally when the link goes.
 * Main thread only.
 */
class CallTracker(private val link: LinkPort, private val sink: CallSink, private val nameOf: (String) -> String? = { null },
                  private val now: () -> Long = System::currentTimeMillis, private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> }) {
    /** Calls whose audio is on another client, by Android phone call id (shown as "On … · Move here"). */
    val remote = LinkedHashMap<String, Call>()
    private val locals = HashMap<String, String>()        // cid → local
    private val cids = HashMap<String, String>()          // local → cid
    private val lastState = HashMap<String, String>()     // cid → state
    private val info = HashMap<String, Call>()            // local → call
    private val connectedAt = HashMap<String, Long>()
    private val answeredHere = HashSet<String>()
    private val holdRequested = HashSet<String>()
    private var hungUp = HangUpGuard()
    /** Outgoing call started here, waiting for the Android phone to report its call id. */
    var pendingOutgoing: String? = null; private set
    private var pendingNumber = ""
    private var pendingSub: Int? = null
    /** The user hung up an outgoing call before the Android phone reported it: end it as soon as it shows up. */
    private var cancelledDialAt = 0L
    private var cancelledNumber = ""
    private var dialToken = 0
    private var uplinkOpen = false

    val hasCalls get() = locals.isNotEmpty() || pendingOutgoing != null
    fun cidOf(local: String) = cids[local]
    fun localOf(cid: String) = locals[cid]

    /** Every local call, the current one first (active, then dialing, ringing, held). */
    val calls: List<LocalCall> get() {
        val out = ArrayList<LocalCall>()
        for ((local, c) in info) out += LocalCall(local, c.cid, c.num, nameOf(c.num) ?: c.name, c.state, c.isIncoming, c.sub,
            connectedAt[local], c.state == "holding")
        pendingOutgoing?.let { p -> if (out.none { it.local == p }) out += LocalCall(p, null, pendingNumber, nameOf(pendingNumber), "dialing", false, pendingSub, null, false) }
        val order = listOf("active", "dialing", "connecting", "ringing", "holding")
        return out.sortedBy { order.indexOf(it.state).let { i -> if (i < 0) 9 else i } }
    }

    // ------------------------------------------------------------------ from the Android phone

    fun handle(c: Call) {
        if (lastState[c.cid] != c.state) sink.log("call ${c.cid} ${c.dir} ${c.state}${if (c.elsewhere) " elsewhere" else ""}")
        when (hungUp.check(c.cid, c.isEnded, now())) {
            HangUpGuard.Verdict.PASS -> Unit
            HangUpGuard.Verdict.DROP -> return
            HangUpGuard.Verdict.DROP_AND_RESEND -> {
                // Still not ended on the Android phone 2 s after we hung up: ask again.
                link.send(if (c.state == "ringing") T.CALL_REJECT else T.CALL_END, JSONObject().put("cid", c.cid))
                return
            }
        }
        val prev = lastState[c.cid]
        if (c.isEnded) lastState.remove(c.cid) else lastState[c.cid] = c.state

        if (c.isEnded) remote.remove(c.cid)
        if (!c.isEnded && c.elsewhere) {
            // Answered / dialed / moved to another client: stop ringing or playing here, offer "Move here".
            remote[c.cid] = c
            locals.remove(c.cid)?.let { local ->
                cids.remove(local); info.remove(local)
                sink.ended(local, EndReason.ANSWERED_ELSEWHERE)
                cleanup(local)
            }
            sink.changed()
            return
        }
        remote.remove(c.cid)

        if (c.isEnded) {
            val local = locals.remove(c.cid) ?: run { sink.changed(); return }
            cids.remove(local); info.remove(local)
            val reason = when {
                prev == "ringing" && c.isIncoming -> EndReason.UNANSWERED
                c.cause?.contains("busy", ignoreCase = true) == true -> EndReason.BUSY
                else -> EndReason.REMOTE
            }
            sink.ended(local, reason)
            cleanup(local)
            sink.changed()
            return
        }

        var fresh = false
        if (locals[c.cid] == null) {
            val p = pendingOutgoing
            when {
                c.isIncoming && c.state == "ringing" -> {
                    // Report before anything else: this is what makes the phone ring.
                    val local = UUID.randomUUID().toString()
                    bind(c.cid, local); info[local] = c
                    sink.reportIncoming(local, c)
                    sink.changed()
                    return
                }
                cancelledDialAt > 0 && now() - cancelledDialAt < 30_000 && cancelledNumber.isNotEmpty() && !c.isIncoming &&
                    numberKey(c.num) == numberKey(cancelledNumber) && (p == null || numberKey(c.num) != numberKey(pendingNumber)) -> {
                    // The dial the user already hung up on (a quick redial to another number must not take its place).
                    cancelledDialAt = 0; cancelledNumber = ""
                    lastState.remove(c.cid)
                    link.send(T.CALL_END, JSONObject().put("cid", c.cid))
                    return
                }
                p != null && !c.isIncoming -> {
                    pendingOutgoing = null
                    dialToken++
                    bind(c.cid, p)
                }
                cancelledDialAt > 0 && now() - cancelledDialAt < 30_000 && cancelledNumber.isEmpty() && !c.isIncoming -> {
                    cancelledDialAt = 0
                    lastState.remove(c.cid)
                    link.send(T.CALL_END, JSONObject().put("cid", c.cid))
                    return
                }
                else -> {
                    // Dialed or answered on the Android phone, moved here, or live when we reconnected: show it as a
                    // started call (an incoming ring would ring for a call that is already connected).
                    val local = UUID.randomUUID().toString()
                    bind(c.cid, local); fresh = true
                }
            }
        }
        val local = locals[c.cid] ?: return
        info[local] = c
        if (fresh) sink.reportStarted(local, c) else sink.update(local, c)

        when (c.state) {
            "dialing", "connecting" -> if (!c.isIncoming) sink.ringback(true)
            "active" -> {
                sink.ringback(false)
                if (connectedAt[local] == null) connectedAt[local] = c.since ?: now()
                // Connected, answered on the Android phone (not here), or taken off hold there: the local call follows.
                // (An answer or unhold done here already set it; setting it again changes nothing.)
                holdRequested.remove(local)
                if (prev != "active") sink.setActive(local)
            }
            "holding" -> {
                // Held on the Android phone (e.g. a second call answered there): the local call must know, or its
                // buttons act on the wrong call.
                holdRequested.remove(local)
                if (prev != "holding") sink.setHeld(local, true)
            }
        }
        updateUplink()
        sink.changed()
    }

    /** "Move here": the Android phone switches call audio to this client and re-sends the state without `elsewhere`. */
    fun take(cid: String) {
        if (remote.remove(cid) == null) return
        lastState.remove(cid)
        link.send(T.CALL_TAKE, JSONObject().put("cid", cid))
        sink.changed()
    }

    private fun updateUplink() {
        val open = CallUplink.open(info.values.map { it.state })
        if (open != uplinkOpen) { uplinkOpen = open; sink.uplink(open) }
    }

    private fun bind(cid: String, local: String) { locals[cid] = local; cids[local] = cid }

    private fun cleanup(local: String) {
        answeredHere.remove(local); holdRequested.remove(local); connectedAt.remove(local)
        updateUplink()
        if (!hasCalls) { sink.ringback(false); sink.idle() }
    }

    /** After a reconnect (call.list): end local calls the Android phone no longer has. */
    fun reconcile(list: List<Call>) {
        val live = list.filter { !it.isEnded }.map { it.cid }.toSet()
        remote.keys.retainAll(live)
        for ((cid, local) in locals.toMap()) if (cid !in live) {
            locals.remove(cid); cids.remove(local); lastState.remove(cid); info.remove(local)
            sink.ended(local, EndReason.REMOTE)
            cleanup(local)
        }
        sink.changed()
    }

    /** Link lost: the calls can no longer be controlled from here, so they end here (they go on on the Android phone). */
    fun linkLost() {
        for (local in locals.values) sink.ended(local, EndReason.FAILED)
        pendingOutgoing?.let { sink.ended(it, EndReason.FAILED) }
        locals.clear(); cids.clear(); lastState.clear(); info.clear(); remote.clear(); connectedAt.clear()
        pendingOutgoing = null; pendingNumber = ""; cancelledNumber = ""; cancelledDialAt = 0; dialToken++
        hungUp.reset(); answeredHere.clear(); holdRequested.clear()
        updateUplink()
        sink.ringback(false)
        sink.idle()
        sink.changed()
    }

    /** The Android phone accepted the dial: if it never reports a call for it, fail it after 30 s. */
    fun dialAccepted() {
        val p = pendingOutgoing ?: return
        val token = ++dialToken
        schedule(30_000) {
            if (dialToken == token && pendingOutgoing == p) { sink.log("dial: no call from the Android phone after 30 s"); dialFailed() }
        }
    }

    fun dialFailed() {
        val p = pendingOutgoing ?: return
        pendingOutgoing = null
        dialToken++
        sink.ringback(false)   // another call may still be live: its audio must not carry our ringback
        sink.ended(p, EndReason.FAILED)
        cleanup(p)
        sink.changed()
    }

    // ------------------------------------------------------------------ from this phone (Telecom callbacks)

    /** An outgoing call this phone started: the Android phone is asked to dial. */
    fun performStart(local: String, number: String, sub: Int?) {
        pendingOutgoing = local
        pendingNumber = number
        pendingSub = sub
        sink.ringback(true)
        link.send(T.CALL_DIAL, JSONObject().put("num", number).apply { sub?.let { put("sub", it) } }, expectReply = true)
        sink.changed()
    }

    fun performAnswer(local: String) {
        answeredHere += local
        cids[local]?.let { link.send(T.CALL_ANSWER, JSONObject().put("cid", it)) }
        sink.changed()
    }

    fun performEnd(local: String) {
        sink.log("end from this phone (${cids[local] ?: "no call id yet"})")
        sink.ringback(false)
        val cid = cids[local]
        if (cid != null) {
            val ringing = lastState[cid] == "ringing"
            link.send(if (ringing) T.CALL_REJECT else T.CALL_END, JSONObject().put("cid", cid))
            hungUp.hungUp(cid, now())
            locals.remove(cid); cids.remove(local); lastState.remove(cid); info.remove(local)
        } else if (pendingOutgoing == local) {
            pendingOutgoing = null
            cancelledDialAt = now()
            cancelledNumber = pendingNumber
            dialToken++
        }
        cleanup(local)
        sink.changed()
    }

    fun performHold(local: String, on: Boolean): Boolean {
        val cid = cids[local] ?: return false
        holdRequested += local
        link.send(T.CALL_HOLD, JSONObject().put("cid", cid).put("on", on))
        return true
    }

    fun performMute(on: Boolean) { link.send(T.CALL_MUTE, JSONObject().put("on", on)) }

    fun performDtmf(local: String, digits: String) {
        val cid = cids[local] ?: return
        for (d in digits) link.send(T.CALL_DTMF, JSONObject().put("cid", cid).put("d", d.toString()))
    }

    /** The local call system refused or dropped the call before it was set up (e.g. too many calls). */
    fun localFailed(local: String) {
        cids.remove(local)?.let { cid -> locals.remove(cid); lastState[cid] = "unreported" }
        info.remove(local)
        if (pendingOutgoing == local) { pendingOutgoing = null; dialToken++ }
        cleanup(local)
        sink.changed()
    }
}

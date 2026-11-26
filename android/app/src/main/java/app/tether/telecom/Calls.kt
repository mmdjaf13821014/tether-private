package app.tether.telecom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import app.tether.R
import app.tether.TetherApp
import app.tether.core.CallInfo
import app.tether.core.OldAndroid
import app.tether.ui.CallActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which call a client's end / reject means: its own id; else, for an id this process never knew (the app
 * restarted mid-call), the only call there is; never another call when the id belongs to a call that ended here.
 */
fun <C> chooseCall(cid: String, calls: Map<String, C>, ended: Set<String>): C? =
    calls[cid] ?: if (cid in ended) null else calls.values.singleOrNull()

/** Tracks live calls (from our InCallService) and executes call actions coming from the iPhone. */
object CallRegistry {
    const val TAG = "Tether"
    private val calls = ConcurrentHashMap<String, Call>()
    private val ids = ConcurrentHashMap<Call, String>()
    private val listeners = CopyOnWriteArrayList<(CallInfo) -> Unit>()
    @Volatile var service: TetherInCallService? = null
    private val main = Handler(Looper.getMainLooper())
    lateinit var app: TetherApp

    fun addListener(l: (CallInfo) -> Unit) { listeners.add(l) }

    fun cidOf(c: Call): String = ids.getOrPut(c) { UUID.randomUUID().toString().substring(0, 8).also { calls[it] = c } }

    fun list(): List<CallInfo> = calls.values.map(::info)

    /** Exactly this call (no "the only call" fallback), and only on Tether's own test line: what a test client may touch. */
    fun isTestCall(ctx: android.content.Context, cid: String): Boolean =
        calls[cid]?.details?.accountHandle == TestConnectionService.handle(ctx)

    fun active(): List<Call> = calls.values.toList()

    fun hasActiveCall() = calls.values.any { it.stateCompat() == Call.STATE_ACTIVE }

    /**
     * Audio is bridged while a call is active or on hold, and also while an outgoing call is dialing,
     * so the iPhone hears the network's ringback.
     */
    fun wantsAudio() = calls.values.any {
        when (it.stateCompat()) {
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> true
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> it.isOutgoing()
            else -> false
        }
    }

    internal fun added(c: Call) {
        cidOf(c)
        c.registerCallback(callback)
        publish(c)
    }

    internal fun removed(c: Call) {
        c.unregisterCallback(callback)
        // Calls never registered (blocked callers, other apps' VoIP calls) end without telling anyone.
        val cid = ids.remove(c) ?: run { firstOutgoing.remove(c); return }
        calls.remove(cid)
        endedIds.add(cid)
        listeners.forEach { it(info(c, cid).copy(state = "ended")) }
        firstOutgoing.remove(c)
    }

    /** Before Android 10: whether each call is outgoing, fixed by the state it was first seen in (see [isOutgoing]). */
    private val firstOutgoing = ConcurrentHashMap<Call, Boolean>()
    internal fun firstSeenOutgoing(c: Call): Boolean = firstOutgoing.getOrPut(c) { OldAndroid.outgoingFromFirstState(c.stateCompat()) }

    /** Ids of calls that ended in this process: a late end / reject for one of them must not hit another call. */
    private val endedIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish(call)
    }

    private fun publish(c: Call) {
        val i = info(c)
        listeners.forEach { it(i) }
    }

    fun stateName(s: Int) = when (s) {
        Call.STATE_RINGING -> "ringing"
        Call.STATE_DIALING, Call.STATE_PULLING_CALL -> "dialing"
        Call.STATE_ACTIVE -> "active"
        Call.STATE_HOLDING -> "holding"
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "ended"
        else -> "connecting"
    }

    fun info(c: Call, cid: String = cidOf(c)): CallInfo {
        val d = c.details
        val num = d.handle?.schemeSpecificPart ?: ""
        // No contacts query here: this runs on the ring path. The iPhone resolves names from its synced contacts.
        val name = (if (Build.VERSION.SDK_INT >= 30) d.contactDisplayName else null)?.takeIf { it.isNotBlank() } ?: d.callerDisplayName?.takeIf { it.isNotBlank() }
        val dir = if (c.isOutgoing()) "out" else "in"
        return CallInfo(cid, num, name, stateName(c.stateCompat()), dir, app.sims.subFor(d.accountHandle),
            d.connectTimeMillis.takeIf { it > 0 }, d.disconnectCause?.reason?.takeIf { it.isNotBlank() },
            d.can(Call.Details.CAPABILITY_HOLD))
    }

    fun answer(cid: String) = calls[cid]?.answer(VideoProfile.STATE_AUDIO_ONLY) != null
    /** The call for an id from a client; if the id is unknown (e.g. the Android phone app restarted) but only one call exists, that one. */
    private fun callFor(cid: String): Call? = chooseCall(cid, calls, endedIds)?.also { if (calls[cid] == null) Log.w(TAG, "call $cid unknown, using the only call") }

    /** Reject a ringing call; a call that is no longer ringing is ended instead (the client's view was late). */
    fun reject(cid: String): Boolean {
        val c = callFor(cid) ?: return false
        if (c.stateCompat() == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
        return true
    }

    /** End a call; a call still ringing is rejected (disconnect would only silence it on some builds). */
    fun end(cid: String): Boolean {
        val c = callFor(cid) ?: return false
        if (c.stateCompat() == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
        Log.i(TAG, "end requested for $cid (${stateName(c.stateCompat())})")
        return true
    }
    fun hold(cid: String, on: Boolean) = calls[cid]?.let { if (on) it.hold() else it.unhold() } != null

    fun dtmf(cid: String, digit: Char): Boolean {
        val c = calls[cid] ?: calls.values.firstOrNull { it.stateCompat() == Call.STATE_ACTIVE } ?: return false
        if (digit !in "0123456789*#") return false
        c.playDtmfTone(digit)
        main.postDelayed({ c.stopDtmfTone() }, 180)
        return true
    }

    fun mute(on: Boolean) = service?.setMuted(on) != null
    fun isMuted(): Boolean = service?.callAudioState?.isMuted == true

    fun speaker(on: Boolean) =
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE) != null

    /** Places a real cellular call. [dryRun] validates without dialing. */
    @SuppressLint("MissingPermission")
    fun dial(ctx: Context, number: String, sub: Int?, dryRun: Boolean = false): String? {
        val clean = number.filter { it.isDigit() || it in "+*#," }
        if (clean.isEmpty()) return "empty number"
        val extras = Bundle()
        app.sims.accountFor(sub)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        if (dryRun) return null
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", clean, null), extras)
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }
}

/** Default-dialer in-call service: owns call control and the (minimal) on-device call screen. */
class TetherInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CallRegistry.service = this
    }

    override fun onDestroy() {
        if (CallRegistry.service === this) CallRegistry.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val d = call.details
        if (d.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) && d.accountHandle?.componentName?.packageName != packageName) {
            return // another app's VoIP call; it has its own UI
        }
        // Last line of defence for blocked callers (screening normally stops them first): no ring, no forwarding.
        if (call.stateCompat() in setOf(Call.STATE_NEW, Call.STATE_RINGING) && !call.isOutgoing() &&
            runCatching { (application as TetherApp).lists.isBlocked(d.handle?.schemeSpecificPart ?: "") }.getOrDefault(false)) {
            Log.i(CallRegistry.TAG, "blocked caller: rejected without ringing")
            call.reject(false, null)
            return
        }
        Log.i(CallRegistry.TAG, "call added state=${CallRegistry.stateName(call.stateCompat())} (${call.stateCompat()}) out=${call.isOutgoing()} handle=${d.handle != null}")
        CallRegistry.added(call)
        call.registerCallback(uiCallback)
        showCallUi(call)
    }

    private val uiCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED) return
            showCallUi(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(uiCallback)
        CallRegistry.removed(call)
        if (CallRegistry.active().isEmpty()) getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
    }

    private fun showCallUi(call: Call) {
        val info = CallRegistry.info(call)
        val open = PendingIntent.getActivity(this, 1, Intent(this, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val who = info.name ?: info.num.ifEmpty { getString(R.string.unknown_caller) }
        val b = Notification.Builder(this, TetherApp.CH_CALLS)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = Person.Builder().setName(who).setImportant(true).build()
            b.setStyle(if (info.state == "ringing") {
                Notification.CallStyle.forIncomingCall(person, action("decline", info.cid, 2), action("answer", info.cid, 3))
            } else {
                Notification.CallStyle.forOngoingCall(person, action("end", info.cid, 4))
            })
        } else {
            // Android 8-11 have no CallStyle: a plain call notification with the same buttons.
            b.setContentTitle(who).setContentText(getString(if (info.state == "ringing") R.string.call_incoming else R.string.call_ongoing))
            val acts = if (info.state == "ringing") listOf("decline" to 2, "answer" to 3) else listOf("end" to 4)
            for ((a, code) in acts) {
                val label = getString(when (a) { "decline" -> R.string.call_decline; "answer" -> R.string.call_answer; else -> R.string.call_end })
                b.addAction(Notification.Action.Builder(null, label, action(a, info.cid, code)).build())
            }
        }
        val n = b.build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, n)
    }

    private fun action(a: String, cid: String, code: Int) = PendingIntent.getActivity(this, code,
        Intent(this, CallActivity::class.java).putExtra("action", a).putExtra("cid", cid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object { const val NOTIF_CALL = 20 }
}

/** Whether the call is outgoing: `Call.Details.getCallDirection` from Android 10; before, the state it was first seen in. */
fun Call.isOutgoing(): Boolean =
    if (Build.VERSION.SDK_INT >= 29) details.callDirection == Call.Details.DIRECTION_OUTGOING else CallRegistry.firstSeenOutgoing(this)

/** The call's state: `Call.Details.getState` only exists from Android 12, `Call.getState` is deprecated there. */
fun Call.stateCompat(): Int =
    if (Build.VERSION.SDK_INT >= 31) details.state else @Suppress("DEPRECATION") state

package app.tether.client.calls

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import app.tether.client.ClientApp
import app.tether.client.R
import app.tether.client.model.Call
import app.tether.client.model.Problem
import java.util.UUID

/**
 * The calls of the Android phone as self-managed Telecom calls on this phone: Telecom routes their audio (earpiece,
 * speaker, wired and Bluetooth headsets, the car) and shows them to Bluetooth devices; the app shows its own call
 * screen and rings itself. [CallTracker] decides; this applies its decisions to Telecom connections.
 */
class TelecomBridge(private val app: ClientApp) : CallSink {
    private val tm = app.getSystemService(TelecomManager::class.java)
    val account = PhoneAccountHandle(ComponentName(app, TetherConnectionService::class.java), "tether")
    private val conns = HashMap<String, TetherConnection>()
    /** State for calls whose Telecom connection does not exist yet (Telecom creates it asynchronously). */
    private val pending = HashMap<String, Call>()
    private val pendingActive = HashSet<String>()
    private val endedEarly = HashMap<String, EndReason>()
    private val main = Handler(Looper.getMainLooper())
    var audioState: CallAudioState? = null; private set

    fun register() {
        runCatching {
            tm.registerPhoneAccount(PhoneAccount.builder(account, app.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build())
        }.onFailure { app.journal.add("phone account not registered: ${it.message}") }
    }

    fun connection(local: String) = conns[local]

    /** The keypad / a tel: link: Telecom creates the outgoing connection (onCreateOutgoingConnection). */
    @SuppressLint("MissingPermission")   // MANAGE_OWN_CALLS (install time) is what a self-managed account needs
    fun placeCall(number: String, sub: Int?): Boolean = try {
        tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { sub?.let { putInt(EXTRA_SUB, it) } })
        })
        true
    } catch (t: Throwable) { app.journal.add("placeCall refused: ${t.message}"); false }

    override fun reportIncoming(local: String, c: Call) {
        pending[local] = c
        app.service(callMode = true)
        try {
            tm.addNewIncomingCall(account, Bundle().apply { putString(EXTRA_LOCAL, local) })
        } catch (t: Throwable) {
            app.journal.add("incoming call not reported: ${t.message}")
            pending.remove(local); app.calls.localFailed(local)
        }
    }

    @SuppressLint("MissingPermission")
    override fun reportStarted(local: String, c: Call) {
        pending[local] = c
        if (c.state == "active") pendingActive += local
        app.service(callMode = true)
        try {
            if (c.isIncoming) tm.addNewIncomingCall(account, Bundle().apply { putString(EXTRA_LOCAL, local); putBoolean(EXTRA_STARTED, true) })
            else tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_LOCAL, local) })
            })
        } catch (t: Throwable) {
            app.journal.add("started call not reported: ${t.message}")
            pending.remove(local); app.calls.localFailed(local)
        }
    }

    /** Telecom made the connection for [local]: apply what happened meanwhile. */
    fun attach(local: String, conn: TetherConnection): Connection {
        endedEarly.remove(local)?.let { return Connection.createFailedConnection(cause(it)) }
        conns[local] = conn
        pending.remove(local)?.let { conn.show(it) }
        if (pendingActive.remove(local)) conn.setActive()
        refreshAudio()
        return conn
    }

    fun detach(local: String) { conns.remove(local); refreshAudio() }

    override fun update(local: String, c: Call) { conns[local]?.show(c) ?: run { pending[local] = c } }

    override fun setActive(local: String) {
        val c = conns[local] ?: run { pendingActive += local; return }
        if (c.state != Connection.STATE_ACTIVE) c.setActive()
        app.notifier.cancelIncoming(local)
        refreshAudio()
    }

    override fun setHeld(local: String, on: Boolean) {
        val c = conns[local] ?: return
        if (on && c.state != Connection.STATE_HOLDING) c.setOnHold() else if (!on && c.state != Connection.STATE_ACTIVE) c.setActive()
    }

    override fun ended(local: String, reason: EndReason) {
        app.notifier.cancelIncoming(local)
        pending.remove(local); pendingActive.remove(local)
        val c = conns.remove(local)
        if (c == null) { endedEarly[local] = reason; main.postDelayed({ endedEarly.remove(local) }, 10_000); return }
        c.setDisconnected(cause(reason))
        c.destroy()
        refreshAudio()
    }

    private fun cause(r: EndReason) = DisconnectCause(when (r) {
        EndReason.REMOTE -> DisconnectCause.REMOTE
        EndReason.UNANSWERED -> DisconnectCause.MISSED
        EndReason.FAILED -> DisconnectCause.ERROR
        EndReason.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
        EndReason.LOCAL -> DisconnectCause.LOCAL
        EndReason.BUSY -> DisconnectCause.BUSY
    })

    override fun ringback(on: Boolean) { app.audio.ringback = on }
    override fun uplink(open: Boolean) = app.audio.setUplink(open)

    override fun idle() {
        app.audio.stop()
        app.link.setHighPriority(false)
        app.service(callMode = false)
    }

    override fun changed() = app.changed()
    override fun log(line: String) = app.journal.add(line)

    /** Runs the call audio engine while any connection is dialing, active or held. */
    fun refreshAudio() {
        val live = conns.values.any { it.state == Connection.STATE_DIALING || it.state == Connection.STATE_ACTIVE || it.state == Connection.STATE_HOLDING }
        app.audio.engine(live)
        if (live) app.link.setHighPriority(true)
        app.changed()
    }

    fun audioStateChanged(s: CallAudioState) {
        val wasMuted = audioState?.isMuted
        audioState = s
        // Mute from a headset or the car: the uplink goes silent here and the Android phone is told.
        if (wasMuted != null && wasMuted != s.isMuted) setMuted(s.isMuted)
        app.changed()
    }

    fun setMuted(on: Boolean) {
        if (app.audio.muted == on) return
        app.audio.muted = on
        app.calls.performMute(on)
        app.changed()
    }

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) { conns.values.firstOrNull { it.state == Connection.STATE_ACTIVE || it.state == Connection.STATE_DIALING }?.setAudioRoute(route) }

    companion object {
        const val EXTRA_LOCAL = "app.tether.client.LOCAL"
        const val EXTRA_STARTED = "app.tether.client.STARTED"
        const val EXTRA_SUB = "app.tether.client.SUB"
    }
}

/** One call of the Android phone as Telecom sees it. Telecom calls these on the main thread. */
class TetherConnection(private val app: ClientApp, val local: String) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE
    }

    fun show(c: Call) {
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, c.num, null), TelecomManager.PRESENTATION_ALLOWED)
        val name = app.state.name(c.num) ?: c.name
        val sim = app.state.simName(c.sub)
        setCallerDisplayName(listOfNotNull(name ?: app.format(c.num), sim).joinToString(" · "), TelecomManager.PRESENTATION_ALLOWED)
        connectionCapabilities = CAPABILITY_MUTE or if (c.hold) CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD else 0
    }

    override fun onShowIncomingCallUi() { app.notifier.showIncomingCall(local) }
    override fun onAnswer() { answer() }
    override fun onAnswer(videoState: Int) { answer() }

    private fun answer() {
        app.calls.performAnswer(local)
        setActive()
        app.notifier.cancelIncoming(local)
        app.telecom.refreshAudio()
        app.openCallScreen()
    }

    override fun onReject() = end(DisconnectCause.REJECTED)
    override fun onDisconnect() = end(DisconnectCause.LOCAL)
    override fun onAbort() = end(DisconnectCause.LOCAL)

    private fun end(code: Int) {
        app.notifier.cancelIncoming(local)
        app.calls.performEnd(local)
        setDisconnected(DisconnectCause(code))
        destroy()
        app.telecom.detach(local)
    }

    override fun onHold() { if (app.calls.performHold(local, true)) setOnHold() }
    override fun onUnhold() { if (app.calls.performHold(local, false)) { setActive(); app.telecom.refreshAudio() } }
    override fun onPlayDtmfTone(c: Char) = app.calls.performDtmf(local, c.toString())
    override fun onSilence() { app.notifier.silenceIncoming(local) }
    override fun onStateChanged(state: Int) { app.telecom.refreshAudio() }
    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState) { app.telecom.audioStateChanged(state) }
}

class TetherConnectionService : ConnectionService() {
    private val app get() = application as ClientApp

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val local = request.extras?.getString(TelecomBridge.EXTRA_LOCAL) ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val conn = TetherConnection(app, local)
        // A started call (answered on the Android phone, moved here) is live already: no ring.
        if (request.extras?.getBoolean(TelecomBridge.EXTRA_STARTED) != true) conn.setRinging() else conn.setActive()
        conn.setInitialized()
        return app.telecom.attach(local, conn)
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        // Telecom refused the call (too many calls, Do Not Disturb policy…): forget the binding, so that if it is
        // answered on the Android phone it shows up here as a started call with audio instead of a half-bound one.
        request.extras?.getString(TelecomBridge.EXTRA_LOCAL)?.let { app.journal.add("Telecom refused an incoming call"); app.calls.localFailed(it) }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        val mirrored = extras?.getString(TelecomBridge.EXTRA_LOCAL)
        val local = mirrored ?: UUID.randomUUID().toString()
        val conn = TetherConnection(app, local)
        conn.setDialing()
        conn.setInitialized()
        val number = request.address?.schemeSpecificPart.orEmpty()
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        val attached = app.telecom.attach(local, conn)
        // A call this phone dials (keypad, a tel: link, Recents): the Android phone is asked to dial it.
        if (mirrored == null) {
            val sub = extras?.takeIf { it.containsKey(TelecomBridge.EXTRA_SUB) }?.getInt(TelecomBridge.EXTRA_SUB)
            app.calls.performStart(local, number, sub)
            app.openCallScreen()
        }
        return attached
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        val local = request.extras?.getString(TelecomBridge.EXTRA_LOCAL)
        app.journal.add("Telecom refused an outgoing call")
        if (local != null) app.calls.localFailed(local) else app.problem(Problem.CALL_FAILED)
    }
}

/** The route after [current]: earpiece → speaker → Bluetooth / wired (whatever [supported] has) → earpiece; null: none. */
fun nextAudioRoute(supported: Int?, current: Int?): Int? {
    val mask = supported ?: (android.telecom.CallAudioState.ROUTE_EARPIECE or android.telecom.CallAudioState.ROUTE_SPEAKER)
    val order = listOf(android.telecom.CallAudioState.ROUTE_EARPIECE, android.telecom.CallAudioState.ROUTE_SPEAKER,
        android.telecom.CallAudioState.ROUTE_BLUETOOTH, android.telecom.CallAudioState.ROUTE_WIRED_HEADSET).filter { mask and it != 0 }
    if (order.isEmpty()) return null
    val cur = order.indexOf(current ?: android.telecom.CallAudioState.ROUTE_EARPIECE)
    return order[(cur + 1) % order.size]
}

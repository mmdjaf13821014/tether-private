package app.tether.client.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.CallAudioState
import app.tether.client.calls.nextAudioRoute
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.client.R
import app.tether.client.calls.LocalCall
import app.tether.client.calls.MicPolicy

/**
 * The call screen (self-managed calls show the app's own): who, SIM, state and time; answer / decline while ringing;
 * mute, audio route (earpiece, speaker, Bluetooth, wired), hold, keypad and end. The screen goes off at the ear.
 */
class CallActivity : Activity() {
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var local: String? = null
    private var showKeys = false
    private var proximity: PowerManager.WakeLock? = null
    private var seenCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(48), dp(24), dp(32)); setBackgroundColor(col(R.color.background)) }
        setContentView(root)
        fitSystemBars(root)
        handle(intent)
        // A call is starting: ask for the microphone again if it is still refused (Android stops showing the question
        // after two refusals; the warning below then says where to allow it).
        if (MicPolicy.atCall(app.hasMicPermission()) == MicPolicy.CallCheck.ASK) {
            app.journal.add("microphone: permission missing at call start: asking")
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        app.journal.add("microphone: " + if (app.hasMicPermission()) "allowed" else "refused")
        app.audio.micPermissionChanged()
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: android.content.Intent) {
        i.getStringExtra(EXTRA_LOCAL)?.let { local = it }
        if (i.getBooleanExtra(EXTRA_ANSWER, false)) local?.let { l -> app.telecom.connection(l)?.onAnswer() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // In front now: the link service may become a microphone service (Android refuses that from the background).
        if (app.calls.hasCalls) app.service(callMode = true)
        app.addListener(listener); tick(); render()
    }
    override fun onPause() { app.removeListener(listener); main.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { proximity?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    private fun tick() { main.postDelayed({ render(); tick() }, 1000) }

    private fun current(): LocalCall? {
        val calls = app.calls.calls
        return calls.firstOrNull { it.local == local } ?: calls.firstOrNull()
    }

    private fun render() {
        val c = current()
        if (c == null) { if (seenCall || app.calls.calls.isEmpty()) { updateProximity(null); finish() }; return }
        seenCall = true
        local = c.local
        root.removeAllViews()
        root.addView(label(c.name ?: app.format(c.number), size = 30f, bold = true).apply { gravity = Gravity.CENTER })
        if (c.name != null) root.addView(label(app.format(c.number), R.color.muted, 16f).apply { gravity = Gravity.CENTER })
        val sim = app.state.simName(c.sub)
        val stateText = when (c.state) {
            "ringing" -> getString(R.string.incoming_call)
            "dialing", "connecting" -> getString(R.string.call_dialing)
            "holding" -> getString(R.string.call_on_hold)
            else -> c.connectedAt?.let { val s = ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0); "%d:%02d".format(s / 60, s % 60) } ?: getString(R.string.call_active)
        }
        root.addView(label(listOfNotNull(stateText, sim).joinToString(" · "), R.color.muted, 16f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(32)) })
        app.micWarning?.let { root.addView(label(getString(micText(it)), R.color.warn, 14f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) }) }
        val others = app.calls.calls.filter { it.local != c.local }
        for (o in others) root.addView(label("${o.name ?: app.format(o.number)} · ${getString(if (o.state == "holding") R.string.call_on_hold else R.string.call_waiting)}", R.color.warn, 14f).apply {
            gravity = Gravity.CENTER; setOnClickListener { local = o.local; render() }
        })
        val conn = app.telecom.connection(c.local)
        if (c.state == "ringing" && c.incoming) {
            root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            root.addView(evenRow(
                pill(getString(R.string.decline), colorId = R.color.bad) { conn?.onReject() ?: app.calls.performEnd(c.local) },
                pill(getString(R.string.answer), colorId = R.color.ok) { conn?.onAnswer() },
            ))
            updateProximity(null)
            return
        }
        if (showKeys) {
            val grid = GridLayout(this).apply { columnCount = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")) grid.addView(TextView(this).apply {
                text = k; textSize = 26f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); background = round(R.color.surface, 68)
                setOnClickListener { app.calls.performDtmf(c.local, k) }
            }, GridLayout.LayoutParams().apply { width = dp(68); height = dp(68); setMargins(dp(10), dp(6), dp(10), dp(6)) })
            root.addView(grid)
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val audio = app.telecom.audioState
        val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val routeName = getString(when (route) {
            CallAudioState.ROUTE_SPEAKER -> R.string.route_speaker; CallAudioState.ROUTE_BLUETOOTH -> R.string.route_bluetooth
            CallAudioState.ROUTE_WIRED_HEADSET -> R.string.route_wired; else -> R.string.route_earpiece
        })
        val muted = app.audio.muted
        root.addView(evenRow(
            pill(getString(if (muted) R.string.unmute_mic else R.string.mute_mic), secondary = !muted) { app.telecom.setMuted(!muted) },
            pill(routeName, secondary = route == CallAudioState.ROUTE_EARPIECE) { nextRoute(audio) },
            pill(getString(R.string.keypad), secondary = !showKeys) { showKeys = !showKeys; render() },
        ))
        if (app.calls.cidOf(c.local) != null) root.addView(evenRow(
            pill(getString(if (c.held) R.string.resume else R.string.hold), secondary = !c.held) { if (c.held) conn?.onUnhold() else conn?.onHold() },
        ))
        root.addView(pill(getString(R.string.end_call), colorId = R.color.bad) { conn?.onDisconnect() ?: app.calls.performEnd(c.local) }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) }
        })
        updateProximity(route)
    }

    /** Earpiece → speaker → Bluetooth / wired (whatever is there) → earpiece. */
    private fun nextRoute(s: CallAudioState?) {
        nextAudioRoute(s?.supportedRouteMask, s?.route)?.let { app.telecom.setRoute(it) }
    }

    /** Screen off at the ear while the call is on the earpiece (not on speaker or a headset). */
    private fun updateProximity(route: Int?) {
        val want = route == CallAudioState.ROUTE_EARPIECE
        val pm = getSystemService(PowerManager::class.java)
        if (want && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "tether:proximity").apply { acquire(4 * 3600_000L) }
        else if (!want && proximity != null) { proximity?.let { if (it.isHeld) it.release() }; proximity = null }
    }

    companion object { const val EXTRA_LOCAL = "local"; const val EXTRA_ANSWER = "answer" }
}

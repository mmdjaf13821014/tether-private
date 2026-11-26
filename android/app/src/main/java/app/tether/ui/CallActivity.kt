package app.tether.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.R
import app.tether.TetherApp
import app.tether.telecom.CallRegistry

/** Minimal on-device call screen (the phone usually lives in a bag; the iPhone is the real UI). */
class CallActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var buttons: LinearLayout
    private var speaker = false
    private var lookedUp: String? = null
    private var lookedUpName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest's showWhenLocked / turnScreenOn exist from Android 8.1; on 8.0 the window flags do the same.
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 27) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(getColor(R.color.background))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        title = TextView(this).apply { textSize = 30f; setTextColor(getColor(R.color.text)); gravity = Gravity.CENTER }
        state = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.muted)); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(40)) }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(title); root.addView(state); root.addView(buttons)
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val cid = i.getStringExtra("cid")
        when (i.getStringExtra("action")) {
            "answer" -> cid?.let(CallRegistry::answer)
            "decline" -> cid?.let(CallRegistry::reject)
            "end" -> cid?.let(CallRegistry::end)
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { main.removeCallbacksAndMessages(null); super.onPause() }

    private fun refresh() {
        val call = CallRegistry.active().firstOrNull()
        if (call == null) { finish(); return }
        val info = CallRegistry.info(call)
        if (info.name == null && info.num.isNotEmpty() && lookedUp != info.num) {
            lookedUp = info.num
            Thread { val n = (application as TetherApp).contacts.nameFor(info.num); main.post { lookedUpName = n } }.start()
        }
        title.text = info.name ?: lookedUpName ?: info.num.ifEmpty { getString(R.string.unknown_caller) }.let { if (info.num.isEmpty()) it else displayNumber(info.num) }
        state.text = getString(when (info.state) {
            "ringing" -> R.string.call_incoming
            "dialing", "connecting" -> R.string.call_outgoing
            "holding" -> R.string.call_holding
            "ended" -> R.string.call_ended
            else -> R.string.call_active
        })
        buttons.removeAllViews()
        if (info.state == "ringing") {
            buttons.addView(round(getString(R.string.decline), R.color.bad) { CallRegistry.reject(info.cid) })
            buttons.addView(round(getString(R.string.answer), R.color.ok) { CallRegistry.answer(info.cid) })
        } else {
            buttons.addView(round(getString(R.string.speaker), if (speaker) R.color.accent else R.color.muted) {
                speaker = !speaker; CallRegistry.speaker(speaker)
            })
            buttons.addView(round(getString(R.string.end_call), R.color.bad) { CallRegistry.end(info.cid) })
        }
        main.postDelayed(::refresh, 500)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(t: String, colorId: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(getColor(R.color.text))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(colorId)) }
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(16); marginEnd = dp(16) }
        setOnClickListener { onClick(); main.postDelayed(::refresh, 200) }
    }
}

/** The number grouped for display by the SIM / network country ("+98 912 345 6789"); unchanged when Android cannot. */
private fun Activity.displayNumber(n: String): String {
    val tm = getSystemService(android.telephony.TelephonyManager::class.java)
    val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        ?: java.util.Locale.getDefault().country).uppercase()
    return android.telephony.PhoneNumberUtils.formatNumber(n, iso) ?: n
}

/** ACTION_DIAL handler (required for the phone-app role): a number field and a Call button. */
class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TetherApp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); setBackgroundColor(getColor(R.color.background))
        }
        val number = EditText(this).apply {
            hint = getString(R.string.dialer_hint); textSize = 28f; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(intent?.data?.schemeSpecificPart ?: "")
        }
        val call = Button(this).apply {
            text = getString(R.string.dial); isAllCaps = false; setTextColor(getColor(R.color.text))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(getColor(R.color.ok)) }
            setOnClickListener { CallRegistry.dial(this@DialerActivity, number.text.toString(), null); finish() }
        }
        root.addView(number); root.addView(call)
        setContentView(root)
        app.hashCode()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

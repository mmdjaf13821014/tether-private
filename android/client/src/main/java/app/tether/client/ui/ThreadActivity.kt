package app.tether.client.ui

import app.tether.client.model.NumberShape
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import app.tether.client.R
import app.tether.core.Sms

/** One conversation: bubbles, read state, send from either SIM, copy / delete, call, mute, block. */
class ThreadActivity : Activity() {
    private var thread = Long.MIN_VALUE
    private lateinit var address: String
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var title: android.widget.TextView
    private lateinit var simButton: android.widget.Button
    private var sub: Int? = null
    private var lastCount = -1
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        thread = intent.getLongExtra(EXTRA_THREAD, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: app.state.threadFor(address) ?: Long.MIN_VALUE
        sub = app.state.preferredSim(address)
        // A text sent into a new conversation gets its real thread id with the provider row: follow it.
        app.state.onThreadMoved = { from, to -> if (from == thread) thread = to }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.background)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(8)) }
        title = label(app.displayName(address), size = 19f, bold = true).apply { setSingleLine() }
        header.addView(title.weight())
        header.addView(pill(getString(R.string.call), secondary = true) { app.dial(address, sub) })
        header.addView(pill("⋯", secondary = true) { menu() })
        root.addView(header)
        list = ListView(this).apply { divider = null; transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; isStackFromBottom = true }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8)) }
        simButton = pill("", secondary = true) { pickSim() }
        bar.addView(simButton)
        input = EditText(this).apply {
            hint = getString(R.string.text_message); setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted)); maxLines = 5
            setText(app.state.draft(address))
        }
        bar.addView(input.weight())
        bar.addView(pill(getString(R.string.send)) { send() })
        root.addView(bar)
        setContentView(root)
        fitSystemBars(root)
    }

    override fun onResume() {
        super.onResume()
        app.addListener(listener)
        app.appVisible = true
        render()
    }

    override fun onPause() {
        app.state.setDraft(input.text.toString(), address)
        app.removeListener(listener)
        app.appVisible = false
        if (app.state.openThread == thread) app.state.openThread = null
        super.onPause()
    }

    override fun onDestroy() { if (app.state.onThreadMoved != null) app.state.onThreadMoved = null; super.onDestroy() }

    private fun render() {
        val s = app.state
        s.openThread = thread
        title.text = app.displayName(address)
        val sims = s.sims
        simButton.visibility = if (sims.size > 1) View.VISIBLE else View.GONE
        simButton.text = sims.firstOrNull { it.sub == sub }?.let { it.name.ifEmpty { getString(R.string.sim_n, it.slot + 1) } } ?: getString(R.string.sim)
        val msgs = if (thread == Long.MIN_VALUE) emptyList() else s.messages(thread)
        s.markRead(thread)
        val atEnd = list.lastVisiblePosition >= list.count - 2
        list.adapter = Rows(msgs) { bubble(it) }
        list.setOnItemLongClickListener { _, _, pos, _ -> bubbleMenu(msgs[pos]); true }
        if (atEnd || msgs.size != lastCount) list.setSelection(msgs.size - 1)
        lastCount = msgs.size
    }

    private fun bubble(m: Sms): View {
        val mine = m.box != 1
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START; setPadding(dp(12), dp(3), dp(12), dp(3)) }
        val body = label(m.body, if (mine) R.color.background else R.color.text, 16f).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(col(if (mine) R.color.accent else R.color.surface)) }
            // Links, e-mail addresses and phone numbers, except "numbers" that are dates, versions or amounts.
            text = android.text.SpannableString(m.body).also { sp ->
                Linkify.addLinks(sp, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES)
                for (link in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java)) {
                    if (link.url.startsWith("tel:") && NumberShape.isNotAPhoneNumber(sp.substring(sp.getSpanStart(link), sp.getSpanEnd(link)))) sp.removeSpan(link)
                }
            }
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        wrap.addView(body, LinearLayout.LayoutParams(-2, -2))
        val state = when (m.box) { 4, 6 -> getString(R.string.sending); 5 -> getString(R.string.not_delivered); else -> null }
        wrap.addView(label(listOfNotNull(time(m.date), app.state.simName(m.sub), state).joinToString(" · "), if (m.box == 5) R.color.bad else R.color.muted, 11f))
        return wrap
    }

    private fun bubbleMenu(m: Sms) {
        val opts = mutableListOf(getString(R.string.copy), getString(R.string.delete))
        if (m.box == 5) opts += getString(R.string.send_again)
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", m.body))
                1 -> app.state.deleteMessages(listOf(m.id))
                2 -> { app.state.deleteMessages(listOf(m.id)); app.state.sendSms(address, m.body, sub) }
            }
        }.show()
    }

    private fun pickSim() {
        val sims = app.state.sims
        AlertDialog.Builder(this).setItems(sims.map { it.name.ifEmpty { getString(R.string.sim_n, it.slot + 1) } }.toTypedArray()) { _, w ->
            sub = sims[w].sub; app.state.setPreferredSim(sub, address); render()
        }.show()
    }

    private fun send() {
        val text = input.text.toString()
        if (text.isBlank()) return
        app.state.sendSms(address, text, sub)
        if (thread == Long.MIN_VALUE) thread = app.state.threadFor(address) ?: thread
        input.setText("")
        render()
    }

    private fun menu() {
        val s = app.state
        val muted = thread != Long.MIN_VALUE && s.isMuted(thread); val blocked = s.isBlocked(address)
        val opts = listOf(getString(if (muted) R.string.unmute else R.string.mute), getString(if (blocked) R.string.unblock else R.string.block),
            getString(R.string.delete_conversation))
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> if (thread != Long.MIN_VALUE) s.setMuted(thread, !muted)
                1 -> s.setBlocked(address, !blocked)
                2 -> AlertDialog.Builder(this).setMessage(R.string.delete_conversation_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> if (thread != Long.MIN_VALUE) s.deleteConversation(thread); finish() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    companion object { const val EXTRA_ADDRESS = "address"; const val EXTRA_THREAD = "thread" }
}

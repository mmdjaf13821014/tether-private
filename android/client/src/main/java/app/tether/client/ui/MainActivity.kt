package app.tether.client.ui

import app.tether.client.report.ClientReport
import app.tether.client.report.ReportUi
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.tether.client.R
import app.tether.client.link.BleCentral
import app.tether.client.link.LinkService
import app.tether.client.model.Search
import app.tether.client.model.dialable
import app.tether.core.CallEntry
import app.tether.core.Contact
import app.tether.core.Phone

/** Recents, Contacts (Favourites), Keypad, Messages and Link: the iPhone app's five tabs. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var status: TextView
    private val tabButtons = ArrayList<TextView>()
    private var tab = TAB_KEYPAD
    private val listener: () -> Unit = { render() }
    private var query = ""
    private var number = StringBuilder()
    private var list: ListView? = null
    private var listTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: if (app.identity.isPaired) TAB_RECENTS else TAB_LINK
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.background)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(6)) }
        header.addView(label(getString(R.string.app_name), size = 22f, bold = true).weight())
        status = label("", R.color.muted, 13f)
        header.addView(status)
        root.addView(header)
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        root.addView(banner)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(col(R.color.surface)) }
        listOf(R.string.tab_recents, R.string.tab_contacts, R.string.tab_keypad, R.string.tab_messages, R.string.tab_link).forEachIndexed { i, s ->
            val b = TextView(this).apply {
                text = getString(s); gravity = Gravity.CENTER; textSize = 13f; setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { select(i) }
            }
            tabButtons += b
            bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar)
        setContentView(root)
        fitSystemBars(root)
        handleIntent(intent)
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }?.let { tab = it }
        i?.getStringExtra(EXTRA_NUMBER)?.let { number = StringBuilder(dialable(it)); tab = TAB_KEYPAD }
        listTab = -1
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    override fun onResume() {
        super.onResume()
        app.addListener(listener)
        app.appVisible = true
        if (app.calls.hasCalls) app.service(callMode = true)
        render()
    }

    override fun onPause() {
        app.removeListener(listener)
        app.appVisible = false
        super.onPause()
    }

    private fun askPermissions() {
        val want = app.linkPermissions() + listOfNotNull(Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else if (app.identity.isPaired) LinkService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (app.identity.isPaired) LinkService.start(this)
        render()
    }

    private fun select(i: Int) {
        if (tab == TAB_RECENTS && i != TAB_RECENTS) app.state.markRecentsSeen()
        tab = i; query = ""; listTab = -1
        render()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val s = app.state
        status.text = when (app.linkState) {
            BleCentral.State.CONNECTED -> "● " + (app.identity.peerName ?: getString(R.string.android_phone))
            BleCentral.State.OFF -> getString(R.string.state_bt_off)
            BleCentral.State.UNPAIRED -> getString(R.string.state_unpaired)
            BleCentral.State.SEARCHING -> getString(R.string.state_searching)
            else -> getString(R.string.state_connecting)
        }
        status.setTextColor(col(if (app.linkState == BleCentral.State.CONNECTED) R.color.ok else R.color.muted))
        tabButtons.forEachIndexed { i, b ->
            val badge = when (i) { TAB_RECENTS -> s.missedCount; TAB_MESSAGES -> s.unreadTotal; else -> 0 }
            b.text = getString(listOf(R.string.tab_recents, R.string.tab_contacts, R.string.tab_keypad, R.string.tab_messages, R.string.tab_link)[i]) +
                if (badge > 0) " ($badge)" else ""
            b.setTextColor(col(if (i == tab) R.color.accent else R.color.muted))
        }
        renderBanner()
        when (tab) {
            TAB_RECENTS -> recents()
            TAB_CONTACTS -> contacts()
            TAB_KEYPAD -> keypad()
            TAB_MESSAGES -> messages()
            else -> linkTab()
        }
    }

    private fun renderBanner() {
        banner.removeAllViews()
        app.state.problem?.let { p ->
            banner.addView(label(describe(p), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() }
                setOnClickListener { app.state.problem = null; render() }
            })
        }
        app.micWarning?.takeIf { app.identity.isPaired }?.let { w ->
            banner.addView(label(getString(micText(w)), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() }
            })
        }
        app.calls.calls.firstOrNull()?.let { c ->
            banner.addView(label("☎ " + (c.name ?: app.format(c.number)) + " · " + stateWord(c.state), R.color.background, 14f).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(col(R.color.ok)); cornerRadius = dp(10).toFloat() }
                setOnClickListener { app.openCallScreen() }
            })
        }
        for (r in app.calls.remote.values) {
            banner.addView(row(
                label(getString(R.string.call_elsewhere, app.displayName(r.num), r.where ?: getString(R.string.another_device)), R.color.text, 14f).weight(),
                pill(getString(R.string.move_here)) { app.calls.take(r.cid) },
            ).apply { setPadding(dp(12), dp(4), dp(4), dp(4)); background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() } })
        }
    }

    private fun stateWord(s: String) = getString(when (s) {
        "ringing" -> R.string.call_ringing; "dialing", "connecting" -> R.string.call_dialing
        "holding" -> R.string.call_on_hold; else -> R.string.call_active
    })

    /** A search field above a list; the list survives redraws (its scroll position too). */
    private fun listScreen(hint: String?, extra: View? = null, rows: Rows<*>, onLong: ((Int) -> Unit)? = null, onClick: ((Int) -> Unit)? = null) {
        val lv = list.takeIf { listTab == tab } ?: run {
            content.removeAllViews()
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            if (hint != null || extra != null) {
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                if (hint != null) top.addView(EditText(this).apply {
                    this.hint = hint; setText(query); setSingleLine(); setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(e: Editable?) { query = e?.toString() ?: ""; render() }
                    })
                }.weight())
                extra?.let { top.addView(it) }
                box.addView(top)
            }
            val l = ListView(this).apply { divider = null; isFocusable = false }
            box.addView(l, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(box)
            list = l; listTab = tab
            l
        }
        val first = lv.firstVisiblePosition; val top = lv.getChildAt(0)?.top ?: 0
        lv.adapter = rows   // rows are built as they scroll into view
        lv.setSelectionFromTop(first, top)
        lv.setOnItemClickListener { _, _, pos, _ -> onClick?.invoke(pos) }
        lv.setOnItemLongClickListener { _, _, pos, _ -> onLong?.invoke(pos); onLong != null }
    }

    private fun rowView(avatar: View?, title: CharSequence, subtitle: CharSequence?, right: CharSequence?, titleColor: Int = R.color.text, bold: Boolean = false) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
            avatar?.let { addView(it) }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, titleColor, 16f, bold).apply { setSingleLine() })
            subtitle?.let { texts.addView(label(it, R.color.muted, 13f).apply { maxLines = 2 }) }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            right?.let { addView(label(it, R.color.muted, 12f).apply { setPadding(dp(8), 0, 0, 0) }) }
        }

    // ------------------------------------------------------------------ Recents

    private fun recents() {
        val s = app.state
        val items = s.recents.filter { Search.matches(query, listOfNotNull(it.name, s.name(it.num)), listOf(it.num)) }
        val clear = pill(getString(R.string.clear_all), secondary = true) {
            AlertDialog.Builder(this).setMessage(R.string.clear_recents_confirm)
                .setPositiveButton(R.string.clear_all) { _, _ -> s.clearRecents() }.setNegativeButton(android.R.string.cancel, null).show()
        }
        listScreen(getString(R.string.search), clear, Rows(items) { c ->
            val who = s.name(c.num) ?: c.name ?: app.format(c.num)
            val kind = getString(when (c.type) { 1 -> R.string.call_in; 2 -> R.string.call_out; 3 -> R.string.call_missed; 5 -> R.string.call_declined; 6 -> R.string.call_blocked; else -> R.string.call_other })
            val sub = listOfNotNull(kind, s.simName(c.sub), if (c.dur > 0) "%d:%02d".format(c.dur / 60, c.dur % 60) else null).joinToString(" · ")
            rowView(avatar(who, s.photo(c.num)), who, sub, time(c.date), if (c.type == 3) R.color.bad else R.color.text)
        }, onLong = { recentMenu(items[it]) }) { app.dial(items[it].num, items[it].sub ?: s.defaultSub) }
    }

    private fun recentMenu(c: CallEntry) {
        val s = app.state
        val blocked = s.isBlocked(c.num)
        val opts = listOf(getString(R.string.call), getString(R.string.message), getString(R.string.copy_number), getString(R.string.delete),
            getString(if (blocked) R.string.unblock else R.string.block))
        AlertDialog.Builder(this).setTitle(s.name(c.num) ?: app.format(c.num)).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> app.dial(c.num, c.sub ?: s.defaultSub)
                1 -> openThread(c.num)
                2 -> copy(c.num)
                3 -> s.deleteRecents(listOf(c.id))
                4 -> s.setBlocked(c.num, !blocked)
            }
        }.show()
    }

    // ------------------------------------------------------------------ Contacts

    private fun contacts() {
        val s = app.state
        val all = s.contacts.filter { Search.matches(query, listOf(it.name), it.phones.map { p -> p.n }) }
        val favs = if (query.isBlank()) all.filter { it.starred } else emptyList()
        val items = ArrayList<Any>()
        if (favs.isNotEmpty()) { items += getString(R.string.favourites); items.addAll(favs); items += getString(R.string.all_contacts) }
        items.addAll(all)
        val add = pill(getString(R.string.add), secondary = true) { editContact(null) }
        listScreen(getString(R.string.search), add, Rows(items) { it ->
            if (it is String) label(it.uppercase(), R.color.accent, 12f, bold = true).apply { setPadding(dp(4), dp(12), 0, dp(4)) }
            else (it as Contact).let { c -> rowView(avatar(c.name, c.photo), (if (c.starred) "★ " else "") + c.name, c.phones.firstOrNull()?.let { p -> app.format(p.n) }, null) }
        }) { pos -> (items[pos] as? Contact)?.let { contactMenu(it) } }
    }

    private fun contactMenu(c: Contact) {
        val s = app.state
        val opts = ArrayList<Pair<String, () -> Unit>>()
        for (p in c.phones) {
            opts += getString(R.string.call_number, app.format(p.n)) to { app.dial(p.n, s.defaultSub) }
            opts += getString(R.string.message_number, app.format(p.n)) to { openThread(p.n) }
        }
        opts += getString(if (c.starred) R.string.unfavourite else R.string.favourite) to { s.setStarred(c.id, !c.starred) }
        opts += getString(R.string.edit) to { editContact(c) }
        c.phones.firstOrNull()?.let { p -> val b = s.isBlocked(p.n); opts += getString(if (b) R.string.unblock else R.string.block) to { s.setBlocked(p.n, !b) } }
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun editContact(c: Contact?, prefill: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = getString(R.string.name); setText(c?.name ?: "") }
        val phone = EditText(this).apply { hint = getString(R.string.phone); inputType = InputType.TYPE_CLASS_PHONE; setText(c?.phones?.firstOrNull()?.n ?: prefill ?: "") }
        box.addView(name); box.addView(phone)
        AlertDialog.Builder(this).setTitle(if (c == null) R.string.new_contact else R.string.edit).setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim(); val p = phone.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val phones = listOfNotNull(p.takeIf { it.isNotEmpty() }?.let { Phone(it, c?.phones?.firstOrNull()?.type ?: "mobile") }) + (c?.phones?.drop(1) ?: emptyList())
                if (c == null) app.state.addContact(n, phones, emptyList()) else app.state.updateContact(c.id, n, phones, c.emails)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ Keypad

    private fun keypad() {
        list = null; listTab = -1
        content.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val shown = label(if (number.isEmpty()) "" else app.format(number.toString()), size = 32f).apply { gravity = Gravity.CENTER; setSingleLine() }
        val who = label(app.state.name(number.toString()) ?: "", R.color.muted, 14f).apply { gravity = Gravity.CENTER }
        box.addView(shown, LinearLayout.LayoutParams(-1, dp(56)))
        box.addView(who, LinearLayout.LayoutParams(-1, -2))
        val grid = GridLayout(this).apply { columnCount = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        for ((k, sub) in keys) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = round(R.color.surface, 76)
                addView(label(k, size = 28f).apply { gravity = Gravity.CENTER })
                if (sub.isNotEmpty()) addView(label(sub, R.color.muted, 10f).apply { gravity = Gravity.CENTER })
                setOnClickListener { number.append(k); keypad() }
                if (k == "0") setOnLongClickListener { number.append("+"); keypad(); true }
            }
            grid.addView(b, GridLayout.LayoutParams().apply { width = dp(76); height = dp(76); setMargins(dp(10), dp(8), dp(10), dp(8)) })
        }
        box.addView(grid)
        val sims = app.state.sims
        val callRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        if (sims.size > 1) for (sim in sims) callRow.addView(pill(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, colorId = R.color.ok) {
            app.dial(number.toString(), sim.sub); number.clear(); keypad()
        }) else callRow.addView(pill(getString(R.string.call), colorId = R.color.ok) { app.dial(number.toString(), app.state.defaultSub); number.clear(); keypad() })
        callRow.addView(pill("⌫", secondary = true) { if (number.isNotEmpty()) number.deleteCharAt(number.length - 1); keypad() }.apply {
            setOnLongClickListener { number.clear(); keypad(); true }
        })
        box.addView(callRow)
        if (number.isNotEmpty() && app.state.name(number.toString()) == null)
            box.addView(pill(getString(R.string.add_contact), secondary = true) { editContact(null, number.toString()) })
        content.addView(ScrollView(this).apply { addView(box) })
    }

    // ------------------------------------------------------------------ Messages

    private fun messages() {
        val s = app.state
        val convs = s.conversations.filter { c -> Search.matches(query, listOfNotNull(s.name(c.address)), listOf(c.address)) ||
            (query.length > 1 && s.messages(c.thread).any { Search.fold(it.body).contains(Search.fold(query)) }) }
        val compose = pill(getString(R.string.new_message), secondary = true) {
            val f = EditText(this).apply { hint = getString(R.string.phone); inputType = InputType.TYPE_CLASS_PHONE }
            AlertDialog.Builder(this).setTitle(R.string.new_message).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) })
                .setPositiveButton(R.string.next) { _, _ -> dialable(f.text.toString()).takeIf { it.isNotEmpty() }?.let { openThread(it) } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        listScreen(getString(R.string.search), compose, Rows(convs) { c ->
            val who = s.name(c.address) ?: app.format(c.address)
            val muted = if (s.isMuted(c.thread)) "🔕 " else ""
            val last = (if (!c.last.let { it.box == 1 }) getString(R.string.you) + ": " else "") + c.last.body.replace('\n', ' ')
            rowView(avatar(who, s.photo(c.address)), muted + who + if (c.unread > 0) "  (${c.unread})" else "", last.take(120), time(c.last.date), bold = c.unread > 0)
        }, onLong = { pos -> threadMenu(convs[pos].thread, convs[pos].address) }) { pos -> openThread(convs[pos].address, convs[pos].thread) }
    }

    private fun threadMenu(thread: Long, address: String) {
        val s = app.state
        val muted = s.isMuted(thread); val blocked = s.isBlocked(address)
        val opts = listOf(getString(R.string.call), getString(if (muted) R.string.unmute else R.string.mute),
            getString(if (blocked) R.string.unblock else R.string.block), getString(R.string.mark_read), getString(R.string.delete_conversation))
        AlertDialog.Builder(this).setTitle(s.name(address) ?: app.format(address)).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> app.dial(address, s.preferredSim(address))
                1 -> s.setMuted(thread, !muted)
                2 -> s.setBlocked(address, !blocked)
                3 -> s.markRead(thread)
                4 -> AlertDialog.Builder(this).setMessage(R.string.delete_conversation_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> s.deleteConversation(thread) }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    private fun openThread(address: String, thread: Long? = null) {
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_ADDRESS, address)
            .putExtra(ThreadActivity.EXTRA_THREAD, thread ?: app.state.threadFor(address) ?: Long.MIN_VALUE))
    }

    private fun copy(t: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", t))
    }

    // ------------------------------------------------------------------ Link

    private fun linkTab() {
        val lv = list
        val keepScroll = (content.getChildAt(0) as? ScrollView)?.takeIf { listTab == TAB_LINK && lv == null }?.scrollY ?: 0
        list = null; listTab = TAB_LINK
        content.removeAllViews()
        val s = app.state
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        val connected = app.link.isConnected

        // The Android phone
        val phone = card()
        val st = s.status
        phone.addView(label(app.identity.peerName ?: getString(R.string.android_phone), size = 18f, bold = true))
        phone.addView(label(when (app.linkState) {
            BleCentral.State.CONNECTED -> getString(R.string.state_connected_via, app.transport.uppercase())
            BleCentral.State.OFF -> getString(R.string.state_bt_off)
            BleCentral.State.UNPAIRED -> getString(R.string.state_unpaired)
            BleCentral.State.SEARCHING -> getString(R.string.state_searching)
            BleCentral.State.HANDSHAKING, BleCentral.State.CONNECTING -> getString(R.string.state_connecting)
        }, if (connected) R.color.ok else R.color.muted))
        if (st != null) phone.addView(label(getString(R.string.battery, st.optInt("battery"), if (st.optBoolean("charging")) " ⚡" else ""), R.color.muted, 14f))
        // The Android phone cannot bridge call audio (Android 8-10, or its software gives none): calls stay on it.
        when (st?.optJSONObject("helper")?.optString("audio")) {
            "unavailable" -> phone.addView(label(getString(R.string.audio_unavailable), R.color.warn, 13f))
            "blocked" -> phone.addView(label(getString(R.string.audio_blocked), R.color.warn, 13f))
        }
        for (sim in s.sims) {
            val bars = "▂▄▆█".take(sim.signal.coerceIn(0, 4)).padEnd(4, '·')
            val line = listOfNotNull(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, sim.carrier.takeIf { it.isNotEmpty() && it != sim.name },
                if (sim.hasService) "$bars ${sim.net ?: ""}".trim() else getString(R.string.no_service), if (sim.roaming) "R" else null).joinToString(" · ")
            phone.addView(label(line, size = 14f))
        }
        st?.optJSONArray("clients")?.let { a ->
            val names = (0 until a.length()).map { a.getJSONObject(it) }.map { (if (it.optBoolean("self")) "● " else "○ ") + it.optString("name") + if (it.optBoolean("audio")) " ♪" else "" }
            if (names.size > 1) phone.addView(label(getString(R.string.clients, names.joinToString("  ")), R.color.muted, 13f))
        }
        // Not paired yet: how to start, in plain words.
        if (!app.identity.isPaired) phone.addView(label(getString(R.string.pair_guide), R.color.muted, 13f))
        phone.addView(row(
            pill(getString(if (app.identity.isPaired) R.string.pair_again else R.string.pair)) { startActivity(Intent(this, PairActivity::class.java)) },
            *(if (app.identity.isPaired) arrayOf(pill(getString(R.string.unpair), secondary = true, colorId = R.color.bad) {
                AlertDialog.Builder(this).setMessage(R.string.unpair_confirm).setPositiveButton(R.string.unpair) { _, _ -> app.unpair() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }) else emptyArray()),
        ))
        box.addView(phone)

        // Switches
        if (connected) {
            if (s.net == null && s.netPending == null) s.refreshNet()
            val net = s.net
            val sw = card()
            sw.addView(label(getString(R.string.switches).uppercase(), R.color.accent, 12f, bold = true))
            val can = net?.optJSONObject("can")
            fun toggle(title: Int, what: String, on: Boolean?, shown: Boolean = true) {
                if (!shown) return
                sw.addView(Switch(this).apply {
                    text = getString(title); textSize = 16f; setTextColor(col(R.color.text)); isChecked = on == true
                    isEnabled = on != null && s.netPending == null
                    setPadding(0, dp(8), 0, dp(8))
                    setOnCheckedChangeListener { _, v -> if (v != on) s.setNet(what, v) }
                })
            }
            toggle(R.string.mobile_data, "data", net?.optBoolean("data"))
            toggle(R.string.hotspot, "hotspot", net?.optBoolean("hotspot"), can?.optBoolean("hotspot", true) ?: true)
            toggle(R.string.bt_tethering, "bt", net?.optBoolean("bt"), can?.optBoolean("bt", true) ?: true)
            toggle(R.string.airplane, "airplane", net?.optBoolean("airplane"))
            toggle(R.string.share_vpn, "share", net?.optBoolean("share"))
            if (net?.optBoolean("vpn") == true) sw.addView(label(getString(R.string.vpn_up), R.color.ok, 13f))
            net?.optJSONObject("proxy")?.optJSONArray("addrs")?.let { a ->
                if (net.optBoolean("share")) for (i in 0 until a.length()) a.getJSONObject(i).let { ad ->
                    sw.addView(label("${ad.optString("kind")}: ${ad.optString("ip")}:${net.optJSONObject("proxy")?.optInt("port")}  ·  ${ad.optString("pac")}", R.color.muted, 12f).apply { setTextIsSelectable(true) })
                }
            }
            val sims = s.sims
            if (sims.size > 1) {
                val dataSub = net?.optInt("dataSub", -1) ?: -1
                sw.addView(row(label(getString(R.string.data_sim), size = 16f).weight(), *sims.map { sim ->
                    pill(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, secondary = sim.sub != dataSub) { if (sim.sub != dataSub) s.setDataSim(sim.sub) }
                }.toTypedArray()))
            }
            for (sim in sims) {
                val mode = net?.optJSONArray("rat")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("slot") == sim.slot }?.optString("mode") }
                sw.addView(row(label(getString(R.string.network_mode, sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }), size = 16f).weight(),
                    pill(mode ?: "–", secondary = true) {
                        val modes = arrayOf("5G", "4G", "3G", "2G")
                        AlertDialog.Builder(this).setItems(modes) { _, w -> s.setNetworkMode(sim.slot, modes[w]) }.show()
                    }))
            }
            s.netPending?.let { sw.addView(label(getString(R.string.waiting_for_phone), R.color.muted, 13f)) }
            s.netError?.let { sw.addView(label(if (it == "no_answer") getString(R.string.err_no_answer) else it, R.color.warn, 13f)) }
            box.addView(sw)
        }

        // This phone
        val me = card()
        me.addView(label(getString(R.string.this_phone).uppercase(), R.color.accent, 12f, bold = true))
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) me.addView(pill(getString(R.string.allow_background), secondary = true) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        })
        if (!app.hasLinkPermissions()) me.addView(pill(getString(R.string.grant_permissions)) { askPermissions() })
        app.link.roundTripMs?.let { me.addView(label(getString(R.string.round_trip, it), R.color.muted, 13f)) }
        me.addView(row(
            pill(getString(R.string.report_title)) { ReportUi.send(this) { ClientReport.build(app) } },
        ))
        me.addView(label(getString(R.string.report_hint), R.color.muted, 13f))
        me.addView(row(
            pill(getString(R.string.link_log), secondary = true) { showLog() },
            *(if (connected) arrayOf(pill(getString(R.string.selftest), secondary = true) { s.runSelftest() }) else emptyArray()),
        ))
        s.selftest?.let { t ->
            val m = t.optJSONArray("methods")
            me.addView(label((0 until (m?.length() ?: 0)).joinToString("\n") { i -> m!!.getJSONObject(i).let { "${if (it.optBoolean("ok")) "✓" else "✗"} ${it.optString("name")} ${it.optString("note")}" } }, R.color.muted, 12f))
        }
        box.addView(me)
        val sv = ScrollView(this).apply { addView(box) }
        content.addView(sv)
        sv.post { sv.scrollTo(0, keepScroll) }
    }

    private fun showLog() {
        val t = TextView(this).apply { text = app.journal.text; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        AlertDialog.Builder(this).setTitle(R.string.link_log).setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", app.journal.text)) }
            .show()
    }

    companion object {
        const val EXTRA_TAB = "tab"; const val EXTRA_NUMBER = "number"
        const val TAB_RECENTS = 0; const val TAB_CONTACTS = 1; const val TAB_KEYPAD = 2; const val TAB_MESSAGES = 3; const val TAB_LINK = 4
    }
}

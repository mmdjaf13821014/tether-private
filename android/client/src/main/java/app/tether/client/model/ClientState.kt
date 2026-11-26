package app.tether.client.model

import app.tether.core.CallEntry
import app.tether.core.Contact
import app.tether.core.Ext
import app.tether.core.Phone
import app.tether.core.Sms
import app.tether.core.T
import app.tether.core.numberKey
import org.json.JSONArray
import org.json.JSONObject

/** What the state needs from the link. */
interface LinkPort {
    val isConnected: Boolean
    /** Sends a control message; returns the request id when [expectReply] (0 when not sent). */
    fun send(t: String, b: JSONObject = JSONObject(), expectReply: Boolean = false): Int
}

/** What the state asks of the platform: notifications, a timer, saving. */
interface Effects {
    fun showSms(m: Sms, from: String?, sim: String?) {}
    fun clearSms(thread: Long) {}
    fun clearSmsIds(ids: List<Long>) {}
    fun showMissedCall(c: CallEntry, from: String?, sim: String?) {}
    fun schedule(ms: Long, block: () -> Unit) {}
    fun save(json: String) {}
    fun changed() {}
    fun now(): Long = System.currentTimeMillis()
}

/** A problem to show the user; [kind] is translated by the UI, [detail] comes from the Android phone as is. */
data class Problem(val kind: String, val detail: String? = null) {
    companion object {
        const val NOT_CONNECTED = "not_connected"; const val NOT_DELETED = "not_deleted"; const val NOT_SENT = "not_sent"
        const val CONTACT_NOT_SAVED = "contact_not_saved"; const val SEND_LATER = "send_later"; const val TOO_LONG = "too_long"
        const val CALL_FAILED = "call_failed"; const val NO_ANSWER = "no_answer"; const val PAIRING_EXPIRED = "pairing_expired"
        const val UNKNOWN_DEVICE = "unknown_device"; const val OTHER = "other"
    }
}

data class Conversation(val thread: Long, val address: String, val last: Sms, val unread: Int)

data class OutboxItem(val to: String, val body: String, val sub: Int?, val placeholder: Long)

/**
 * Everything synced from the Android phone and the logic around it: a port of the iPhone's AppModel (sync from the
 * last completed sync, placeholders for texts being sent and an outbox while out of reach, read state both ways,
 * sync.ids deletions, paged contacts, missed calls, block / mute lists, network switches). Main thread only.
 */
class ClientState(private val link: LinkPort, private val fx: Effects) {
    var sms: List<Sms> = emptyList(); private set
    var contacts: List<Contact> = emptyList(); private set
    var recents: List<CallEntry> = emptyList(); private set
    var contactsVersion = ""; private set
    val readUpTo = HashMap<Long, Long>()
    val drafts = HashMap<String, String>()
    val threadSim = HashMap<String, Int>()
    var status: JSONObject? = null; private set
    var lists = BlockLists(); private set
    var smsSyncedUpTo: Long? = null; private set
    var outbox: List<OutboxItem> = emptyList(); private set
    var recentsSeenUpTo: Long? = null; private set

    var net: JSONObject? = null; private set
    var netPending: String? = null; private set
    var netError: String? = null; private set
    var selftest: JSONObject? = null; private set
    var problem: Problem? = null
    var syncing = false; private set
    var lastSync = 0L; private set
    /** The conversation on screen (no notification for it). */
    var openThread: Long? = null
    var appActive = false
    /** A placeholder's conversation became the real one (the open thread screen follows). */
    var onThreadMoved: ((from: Long, to: Long) -> Unit)? = null

    private val pendingRead = HashMap<Long, Long>()
    private val pendingSms = HashMap<Int, Long>()      // request id → placeholder id
    private var incrementalSync = false
    private var contactsPageStart = true
    private var incomingContacts = ArrayList<Contact>()
    private var pendingIdsSms = ArrayList<LongArray>()
    private var pendingIdsCalls = ArrayList<LongArray>()
    private var invalidIdsBatch = false
    private var nameIndex = HashMap<String, String>()
    private var photoIndex = HashMap<String, String>()

    // ------------------------------------------------------------------ derived data

    val conversations: List<Conversation>
        get() = sms.groupBy { it.thread }.mapNotNull { (thread, msgs) ->
            val last = msgs.maxWithOrNull(compareBy({ it.date }, { it.id })) ?: return@mapNotNull null
            val seen = readUpTo[thread] ?: 0
            Conversation(thread, last.addr, last, msgs.count { it.incoming && !it.read && it.id > seen })
        }.sortedByDescending { it.last.date }

    val unreadTotal get() = conversations.sumOf { it.unread }

    fun messages(thread: Long) = sms.filter { it.thread == thread }.sortedWith(compareBy({ it.date }, { it.id }))

    fun threadFor(address: String): Long? { val k = numberKey(address); return sms.lastOrNull { numberKey(it.addr) == k }?.thread }

    fun name(number: String): String? = nameIndex[numberKey(number)]
    fun photo(number: String): String? = photoIndex[numberKey(number)]

    val sims: List<Sim> get() = status?.optJSONArray("sims").mapObjects(Sim::of)
    val defaultSub: Int? get() = status?.optIntOrNull("defaultSub")

    /** The SIM's own name, only with two SIMs (with one it says nothing). */
    fun simName(sub: Int?): String? {
        val all = sims
        if (all.size < 2 || sub == null) return null
        val s = all.firstOrNull { it.sub == sub } ?: return null
        return s.name.ifEmpty { "SIM ${s.slot + 1}" }
    }

    /** Missed calls newer than the last look at Recents (declined calls are not missed; blocked numbers never count). */
    val missedCount: Int get() {
        val seen = recentsSeenUpTo ?: (recents.maxOfOrNull { it.id } ?: 0)
        return recents.count { it.type == 3 && it.id > seen && !lists.isBlocked(it.num) }
    }

    fun markRecentsSeen() {
        if (recents.isEmpty()) return   // a 0 here would defeat the first-sync marker
        val top = recents.maxOf { it.id }
        if ((recentsSeenUpTo ?: -1) < top) { recentsSeenUpTo = top; persist() }
    }

    val favourites get() = contacts.filter { it.starred }
    fun isMuted(thread: Long) = thread in lists.muted
    fun isBlocked(number: String) = lists.isBlocked(number)

    fun draft(address: String) = drafts[numberKey(address)] ?: ""
    fun setDraft(text: String, address: String) { if (text.isEmpty()) drafts.remove(numberKey(address)) else drafts[numberKey(address)] = text; persist() }
    fun preferredSim(address: String): Int? = threadSim[numberKey(address)] ?: defaultSub
    fun setPreferredSim(sub: Int?, address: String) { if (sub == null) threadSim.remove(numberKey(address)) else threadSim[numberKey(address)] = sub; persist() }

    // ------------------------------------------------------------------ read state

    fun markRead(thread: Long) {
        val newest = sms.filter { it.thread == thread }.maxOfOrNull { it.id } ?: 0
        if ((readUpTo[thread] ?: 0) >= newest) return
        readUpTo[thread] = newest
        // Read here means read everywhere: the Android phone and the other devices follow.
        if (newest > 0) {
            if (link.isConnected) link.send(T.SMS_READ, JSONObject().put("thread", thread).put("upTo", newest))
            else pendingRead[thread] = newest
        }
        fx.clearSms(thread)
        persist()
    }

    // ------------------------------------------------------------------ delete, mute, block

    private fun requireLink(): Boolean {
        if (link.isConnected) return true
        problem = Problem(Problem.NOT_CONNECTED); fx.changed(); return false
    }

    fun deleteConversation(thread: Long) {
        // A conversation that only exists here (texts still waiting to be sent) needs no phone.
        if (thread < 0 || sms.none { it.thread == thread && it.id > 0 }) { applyDeleted(emptyList(), thread); return }
        if (!requireLink()) return
        link.send(T.SMS_DELETE, JSONObject().put("thread", thread), expectReply = true)
        applyDeleted(emptyList(), thread)   // at once; the phone's answer confirms (or the next sync restores)
    }

    fun deleteMessages(ids: List<Long>) {
        val real = ids.filter { it > 0 }
        if (real.isNotEmpty()) {
            if (!requireLink()) return
            link.send(T.SMS_DELETE, JSONObject().put("ids", JSONArray(real)), expectReply = true)
        }
        applyDeleted(ids, null)
    }

    private fun applyDeleted(ids: List<Long>, thread: Long?) {
        val gone = ids.toHashSet()
        sms = sms.filterNot { it.id in gone || (thread != null && it.thread == thread) }
        if (thread != null) { readUpTo.remove(thread); fx.clearSms(thread) }
        if (ids.isNotEmpty()) {
            fx.clearSmsIds(ids)
            outbox = outbox.filterNot { it.placeholder in gone }
        }
        if (thread != null) outbox = outbox.filterNot { o -> sms.none { it.id == o.placeholder } }
        persist()
    }

    fun deleteRecents(ids: List<Long>) {
        if (ids.isEmpty() || !requireLink()) return
        link.send(T.CALLS_DELETE, JSONObject().put("ids", JSONArray(ids)), expectReply = true)
        val gone = ids.toHashSet()
        recents = recents.filterNot { it.id in gone }
        persist()
    }

    fun clearRecents() {
        if (!requireLink()) return
        link.send(T.CALLS_DELETE, JSONObject().put("all", true), expectReply = true)
        recents = emptyList()
        persist()
    }

    fun setBlocked(number: String, on: Boolean) {
        if (!requireLink()) return
        val k = numberKey(number)
        lists = lists.copy(blocked = lists.blocked.filterNot { numberKey(it) == k } + if (on) listOf(number) else emptyList())
        link.send(T.LISTS_SET, JSONObject().put("what", "block").put("num", number).put("on", on), expectReply = true)
        persist()
    }

    fun setMuted(thread: Long, on: Boolean) {
        if (!requireLink()) return
        lists = lists.copy(muted = lists.muted.filterNot { it == thread } + if (on) listOf(thread) else emptyList())
        if (on) fx.clearSms(thread)
        link.send(T.LISTS_SET, JSONObject().put("what", "mute").put("thread", thread).put("on", on), expectReply = true)
        persist()
    }

    // ------------------------------------------------------------------ contacts

    private fun phonesJson(phones: List<Phone>) = JSONArray(phones.map { it.json() })

    fun addContact(name: String, phones: List<Phone>, emails: List<String>) {
        if (!requireLink()) return
        link.send(T.CONTACT_ADD, JSONObject().put("name", name).put("phones", phonesJson(phones)).put("emails", JSONArray(emails)), expectReply = true)
    }

    fun updateContact(id: Long, name: String, phones: List<Phone>, emails: List<String>) {
        if (!requireLink()) return
        contacts = contacts.map { if (it.id == id) it.copy(name = name, phones = phones, emails = emails) else it }
        rebuildIndex()
        link.send(T.CONTACT_UPDATE, JSONObject().put("id", id).put("name", name).put("phones", phonesJson(phones)).put("emails", JSONArray(emails)), expectReply = true)
    }

    fun setStarred(id: Long, on: Boolean) {
        if (!requireLink()) return
        contacts = contacts.map { if (it.id == id) it.copy(starred = on) else it }
        link.send(T.CONTACT_STAR, JSONObject().put("id", id).put("on", on), expectReply = true)
        fx.changed()
    }

    // ------------------------------------------------------------------ network switches

    private fun netRequest(pending: String, body: JSONObject) {
        if (!link.isConnected) return
        netPending = pending
        netError = null
        link.send(T.NET_SET, body, expectReply = true)
        // A lost reply must not leave the switches greyed out for ever.
        fx.schedule(35_000) { if (netPending == pending) { netPending = null; netError = Problem.NO_ANSWER; fx.changed() } }
        fx.changed()
    }

    fun setNet(what: String, on: Boolean) = netRequest(what, JSONObject().put("what", what).put("on", on))
    fun setDataSim(sub: Int) = netRequest("dataSub", JSONObject().put("what", "dataSub").put("on", true).put("sub", sub))
    fun setNetworkMode(slot: Int, mode: String) = netRequest("rat$slot", JSONObject().put("what", "rat").put("on", true).put("slot", slot).put("mode", mode))
    private var netAskedAt = 0L
    /** Asks for the switches' state (at most every 10 s: the Link screen asks on every redraw until it has one). */
    fun refreshNet() {
        if (!link.isConnected || fx.now() - netAskedAt < 10_000) return
        netAskedAt = fx.now()
        link.send(T.NET_GET, JSONObject(), expectReply = true)
    }
    fun runSelftest() { selftest = null; link.send(T.SELFTEST, JSONObject(), expectReply = true); fx.changed() }

    // ------------------------------------------------------------------ sync and texts

    /** [caps]: the extensions this client speaks (protocol §7.2); left out of the sync when there are none. */
    fun requestSync(full: Boolean = false, codecs: List<String>, kind: String, name: String, caps: List<String> = emptyList()) {
        if (!link.isConnected) return
        syncing = true
        incrementalSync = !full && sms.isNotEmpty()
        // From the last *completed* sync: a text that came in live during an interrupted sync must not make us skip the rest.
        val lastSms = if (full) 0 else smsSyncedUpTo ?: (sms.maxOfOrNull { it.id } ?: 0)
        val lastCall = if (full) 0 else recents.maxOfOrNull { it.id } ?: 0
        lastSyncArgs = Triple(codecs, kind, name); lastCaps = caps
        link.send(T.SYNC, JSONObject().put("sms", maxOf(0, lastSms)).put("calls", maxOf(0, lastCall)).put("contacts", if (full) "" else contactsVersion)
            .put("codecs", JSONArray(codecs)).put("client", JSONObject().put("kind", kind).put("name", name))
            .apply { if (caps.isNotEmpty()) put(Ext.CAPS, JSONArray(caps)) })
        // Conversations read while out of reach.
        for ((thread, upTo) in pendingRead) link.send(T.SMS_READ, JSONObject().put("thread", thread).put("upTo", upTo))
        pendingRead.clear()
        fx.changed()
    }

    private var lastSyncArgs = Triple(listOf("pcmu"), "android", "Android")
    private var lastCaps = emptyList<String>()
    private fun resync(full: Boolean = false) = requestSync(full, lastSyncArgs.first, lastSyncArgs.second, lastSyncArgs.third, lastCaps)

    fun sendSms(to: String, body: String, sub: Int?) {
        val text = body.trim()
        if (text.isEmpty()) return
        if (text.toByteArray().size > 12_000) { problem = Problem(Problem.TOO_LONG); fx.changed(); return }
        // Local placeholder until the Android phone's provider row arrives through sms.new. Ids are negative and unique.
        val now = fx.now()
        val localId = -maxOf(now, (sms.maxOfOrNull { -it.id } ?: 0) + 1)
        val thread = threadFor(to) ?: -(numberKey(to).hashCode().toLong().let { if (it < 0) -it else it } % 1_000_000 + 1)
        val placeholder = Sms(localId, thread, to, text, now, 4, true, sub)
        sms = sms + placeholder
        drafts.remove(numberKey(to))
        if (link.isConnected) {
            val id = link.send(T.SMS_SEND, smsSendBody(to, text, sub), expectReply = true)
            pendingSms[id] = localId
        } else {
            outbox = outbox + OutboxItem(to, text, sub, localId)
            problem = Problem(Problem.SEND_LATER)
        }
        persist()
    }

    private fun smsSendBody(to: String, body: String, sub: Int?) = JSONObject().put("to", to).put("body", body).apply { sub?.let { put("sub", it) } }

    private fun flushOutbox() {
        val items = outbox
        if (items.isEmpty()) return
        outbox = emptyList()
        for (o in items) {
            if (sms.none { it.id == o.placeholder }) continue   // deleted meanwhile
            pendingSms[link.send(T.SMS_SEND, smsSendBody(o.to, o.body, o.sub), expectReply = true)] = o.placeholder
        }
        persist()
    }

    /**
     * After a sync: a text still "sending" that nobody waits for any more (its answer was lost with the link) becomes
     * "Not delivered", so the user can see it and send it again. Never resent automatically: the Android phone may have
     * sent it, and a resend would reach the recipient twice.
     */
    private fun failStalePlaceholders() {
        val waiting = pendingSms.values.toHashSet() + outbox.map { it.placeholder }
        val old = fx.now() - 120_000
        sms = sms.map { if (it.id < 0 && it.box == 4 && it.date < old && it.id !in waiting) it.copy(box = 5) else it }
    }

    // ------------------------------------------------------------------ link events

    fun linkUp(codecs: List<String>, kind: String, name: String, caps: List<String> = emptyList()) {
        problem = null
        requestSync(false, codecs, kind, name, caps)
        flushOutbox()
    }

    fun linkDown() {
        // A sync cut off mid-way must start clean next time.
        contactsPageStart = true
        incomingContacts = ArrayList()
        resetPendingIds()
        // Answers to texts in flight are lost with the link: the next sync settles them (the real row replaces a sent
        // placeholder; an unconfirmed one turns Not delivered after 2 min, never resent).
        pendingSms.clear()
        syncing = false
        netPending = null
        fx.changed()
    }

    /** A control message that is not about calls or call audio. Returns false when it was not one of ours. */
    fun received(t: String, b: JSONObject, re: Int?): Boolean {
        when (t) {
            T.STATUS -> status = b   // saved with the next real change
            T.SMS_BATCH -> {
                val items = b.optJSONArray("items").mapObjects(::smsOf)
                val recent = fx.now() - 6 * 3600_000L
                merge(items, notify = incrementalSync && items.any { it.incoming && !it.read && it.date > recent })
                if (!b.optBoolean("more")) {
                    syncing = false; lastSync = fx.now()
                    smsSyncedUpTo = sms.filter { it.id > 0 }.maxOfOrNull { it.id }
                    failStalePlaceholders()
                    persist()
                }
            }
            T.SMS_NEW -> b.optJSONObject("item")?.let { val m = smsOf(it); merge(listOf(m), notify = m.incoming) }
            T.SMS_READ -> {
                val thread = b.optLong("thread", -1); val upTo = b.optLong("upTo", -1)
                if (thread >= 0 && (readUpTo[thread] ?: 0) < upTo) { readUpTo[thread] = upTo; fx.clearSms(thread); persist() }
            }
            T.SMS_UNREAD -> {
                val ids = b.optJSONArray("ids").longs().toHashSet(); val all = b.optBoolean("all", true)
                val changed = HashSet<Long>()
                sms = sms.map { m ->
                    if (m.incoming && !m.read && m.id > 0 && unreadSays(ids, all, m.id) == true) { changed += m.thread; m.copy(read = true) } else m
                }
                if (changed.isNotEmpty()) {
                    val stillUnread = conversations.filter { it.unread > 0 }.map { it.thread }.toHashSet()
                    for (th in changed) if (th !in stillUnread) fx.clearSms(th)
                    persist()
                }
            }
            T.SMS_RESULT -> {
                val p = re?.let { pendingSms[it] }
                if (p != null) {
                    val ok = b.optBoolean("ok")
                    sms = sms.map { if (it.id == p) it.copy(box = if (ok) 2 else 5) else it }
                    if (b.optString("state") != "sent") pendingSms.remove(re)
                    if (!ok) problem = Problem(Problem.NOT_SENT, b.optStringOrNull("err"))
                    persist()
                }
            }
            T.SMS_DELETED -> if (b.optBoolean("ok")) applyDeleted(b.optJSONArray("ids").longs(), b.optLongOrNull("thread"))
                else { problem = Problem(Problem.NOT_DELETED, b.optStringOrNull("err")); resync(full = true) }
            T.CALLS_DELETED -> if (b.optBoolean("ok")) {
                    if (b.optBoolean("all")) recents = emptyList()
                    else { val g = b.optJSONArray("ids").longs().toHashSet(); recents = recents.filterNot { it.id in g } }
                    persist()
                } else { problem = Problem(Problem.NOT_DELETED, b.optStringOrNull("err")); resync(full = true) }
            T.LISTS -> {
                b.optStringOrNull("err")?.let { problem = Problem(Problem.OTHER, it) }
                lists = BlockLists.of(b)
                lists.muted.forEach { fx.clearSms(it) }
                persist()
            }
            T.SYNC_IDS -> {
                val more = if (b.has("more")) b.opt("more") else false
                if (more !is Boolean) {
                    // An invalid marker cannot end the batch. Discard pages through the next valid final marker.
                    invalidIdsBatch = true
                    return true
                }
                if (!invalidIdsBatch) {
                    val smsIds = IdRanges.parse(b.optJSONArray("sms"), pendingIdsSms.lastOrNull()?.get(1))
                    val callIds = IdRanges.parse(b.optJSONArray("calls"), pendingIdsCalls.lastOrNull()?.get(1))
                    // One malformed page makes the entire set unsafe for deletion.
                    if (smsIds == null || callIds == null) invalidIdsBatch = true
                    else {
                        pendingIdsSms.addAll(smsIds)
                        pendingIdsCalls.addAll(callIds)
                    }
                }
                if (!more) {
                    val smsIds = pendingIdsSms
                    val callIds = pendingIdsCalls
                    val valid = !invalidIdsBatch
                    resetPendingIds()
                    if (valid) {
                        val gone = sms.filter { it.id > 0 && !IdRanges.contains(smsIds, it.id) }.map { it.id }
                        val before = recents.size
                        recents = recents.filter { IdRanges.contains(callIds, it.id) }
                        if (gone.isNotEmpty()) applyDeleted(gone, null) else if (recents.size != before) persist()
                    }
                }
            }
            T.CONTACTS_BATCH -> {
                // A full sync arrives in pages; swap the list in once the last page is here.
                if (b.optBoolean("full") && contactsPageStart) incomingContacts = ArrayList()
                val more = b.optBoolean("more")
                contactsPageStart = !more
                incomingContacts.addAll(b.optJSONArray("items").mapObjects(::contactOf))
                if (!more) {
                    contacts = incomingContacts.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    contactsVersion = b.optString("version")
                    incomingContacts = ArrayList()
                    rebuildIndex()
                    persist()
                }
            }
            T.CONTACT_RESULT -> {
                if (!b.optBoolean("ok")) problem = Problem(Problem.CONTACT_NOT_SAVED, b.optStringOrNull("err"))
                resync()   // saved or not, fetch the Android phone's version so both sides agree again
            }
            T.CALLS_BATCH -> mergeCalls(b.optJSONArray("items").mapObjects(::callEntryOf), b.optBoolean("more"))
            T.NET_STATE -> {
                // An error reply carries no switch values: keep the last known ones.
                val old = net
                if (!b.has("data") && old != null) {
                    if (b.has("share")) { old.put("share", b.optBoolean("share")); b.optJSONObject("proxy")?.let { old.put("proxy", it) } }
                } else net = b
                netError = b.optStringOrNull("err")
                // A state pushed because another client flipped a switch must not clear our own pending one.
                if (re != null) netPending = null
            }
            T.SELFTEST_RESULT -> selftest = b
            T.ERROR -> problem = Problem(Problem.OTHER, b.optString("msg"))
            T.PONG, T.PING -> return true
            else -> return false
        }
        fx.changed()
        return true
    }

    private fun mergeCalls(items: List<CallEntry>, more: Boolean) {
        // First sync (new pairing): its whole history (all its pages) counts as seen, not as hundreds missed.
        // (Long.MAX_VALUE marks a first sync in progress, saved with the snapshot.)
        if (recentsSeenUpTo == null) recentsSeenUpTo = if (recents.isEmpty()) Long.MAX_VALUE else recents.maxOf { it.id }
        val firstCallSync = recentsSeenUpTo == Long.MAX_VALUE
        // A call missed in the last 10 minutes that we did not know yet: notify.
        val known = recents.maxOfOrNull { it.id } ?: 0
        val fresh = fx.now() - 600_000
        for (c in items) if (c.type == 3 && c.id > known && c.date > fresh && !isBlocked(c.num) && recents.isNotEmpty() && !firstCallSync)
            fx.showMissedCall(c, c.name ?: name(c.num), simName(c.sub))
        val byId = LinkedHashMap<Long, CallEntry>()
        recents.forEach { byId[it.id] = it }
        items.forEach { byId[it.id] = it }
        recents = byId.values.sortedByDescending { it.date }.take(3000)
        if (firstCallSync && !more) recentsSeenUpTo = recents.maxOfOrNull { it.id } ?: 0
        persist()
    }

    private val smsIndex = HashMap<Long, Int>()

    private fun merge(items: List<Sms>, notify: Boolean) {
        if (items.isEmpty()) return
        val arr = ArrayList(sms)
        if (smsIndex.size != arr.size) { smsIndex.clear(); arr.forEachIndexed { i, m -> smsIndex.putIfAbsent(m.id, i) } }
        val fresh = ArrayList<Sms>()
        for (m in items) {
            val i = smsIndex[m.id]
            if (i != null) arr[i] = m else { smsIndex[m.id] = arr.size; arr.add(m); fresh.add(m) }
        }
        // Drop local placeholders (negative ids) that the provider has now confirmed (same address and body, written
        // after the placeholder minus clock skew, so an old identical text in history does not swallow a new one).
        val outgoing = items.filter { !it.incoming }
        if (outgoing.isNotEmpty() && arr.any { it.id < 0 }) {
            val confirmed = HashSet<Long>()
            for (p in arr) if (p.id < 0) {
                val real = outgoing.firstOrNull { it.date >= p.date - 120_000 && numberKey(it.addr) == numberKey(p.addr) && it.body == p.body } ?: continue
                confirmed += p.id
                if (p.thread != real.thread) onThreadMoved?.invoke(p.thread, real.thread)
                pendingSms.entries.removeAll { it.value == p.id }
            }
            if (confirmed.isNotEmpty()) arr.removeAll { it.id in confirmed }
            smsIndex.clear()
        }
        sms = arr
        if (smsIndex.isEmpty()) arr.forEachIndexed { i, m -> smsIndex.putIfAbsent(m.id, i) }
        persist()
        if (notify) {
            // Only texts that are still unread and recent: a reconnect must not replay old or already-read ones.
            val recent = fx.now() - 6 * 3600_000L
            for (m in fresh) if (m.incoming && !m.read && m.date > recent && !(appActive && openThread == m.thread) && !isMuted(m.thread) && !isBlocked(m.addr))
                fx.showSms(m, name(m.addr), simName(m.sub))
        }
    }

    private fun rebuildIndex() {
        val n = HashMap<String, String>(); val p = HashMap<String, String>()
        for (c in contacts) for (ph in c.phones) {
            val k = numberKey(ph.n)
            if (k.isEmpty()) continue
            n[k] = c.name
            c.photo?.let { p[k] = it }
        }
        nameIndex = n; photoIndex = p
    }

    // ------------------------------------------------------------------ pairing, persistence

    /** A new pairing or unpairing: nothing of the old Android phone's data is kept. */
    fun wipe() {
        resetPendingIds()
        sms = emptyList(); contacts = emptyList(); recents = emptyList(); contactsVersion = ""
        readUpTo.clear(); drafts.clear(); threadSim.clear(); status = null; lists = BlockLists(); smsSyncedUpTo = null
        outbox = emptyList(); recentsSeenUpTo = null; net = null; pendingRead.clear(); pendingSms.clear(); smsIndex.clear()
        rebuildIndex(); persist()
    }

    private fun resetPendingIds() {
        pendingIdsSms = ArrayList()
        pendingIdsCalls = ArrayList()
        invalidIdsBatch = false
    }

    private fun persist() { fx.save(toJson().toString()); fx.changed() }

    fun toJson(): JSONObject = JSONObject()
        .put("sms", JSONArray(sms.map { it.json() }))
        .put("contacts", JSONArray(contacts.map { it.json() }))
        .put("recents", JSONArray(recents.map { it.json() }))
        .put("contactsVersion", contactsVersion)
        .put("readUpTo", JSONObject().apply { readUpTo.forEach { (k, v) -> put(k.toString(), v) } })
        .put("drafts", JSONObject(drafts as Map<*, *>))
        .put("threadSim", JSONObject(threadSim as Map<*, *>))
        .put("lists", lists.json())
        .put("outbox", JSONArray(outbox.map { JSONObject().put("to", it.to).put("body", it.body).put("placeholder", it.placeholder).apply { it.sub?.let { s -> put("sub", s) } } }))
        .apply {
            status?.let { put("status", it) }
            smsSyncedUpTo?.let { put("smsSyncedUpTo", it) }
            recentsSeenUpTo?.let { put("recentsSeenUpTo", it) }
        }

    fun load(o: JSONObject) {
        sms = o.optJSONArray("sms").mapObjects(::smsOf)
        contacts = o.optJSONArray("contacts").mapObjects(::contactOf)
        recents = o.optJSONArray("recents").mapObjects(::callEntryOf)
        contactsVersion = o.optString("contactsVersion")
        o.optJSONObject("readUpTo")?.let { r -> r.keys().forEach { k -> k.toLongOrNull()?.let { readUpTo[it] = r.optLong(k) } } }
        o.optJSONObject("drafts")?.let { r -> r.keys().forEach { drafts[it] = r.optString(it) } }
        o.optJSONObject("threadSim")?.let { r -> r.keys().forEach { threadSim[it] = r.optInt(it) } }
        status = o.optJSONObject("status")
        lists = o.optJSONObject("lists")?.let(BlockLists::of) ?: BlockLists()
        smsSyncedUpTo = o.optLongOrNull("smsSyncedUpTo")
        recentsSeenUpTo = o.optLongOrNull("recentsSeenUpTo")
        outbox = o.optJSONArray("outbox").mapObjects { OutboxItem(it.optString("to"), it.optString("body"), it.optIntOrNull("sub"), it.optLong("placeholder")) }
        rebuildIndex()
    }
}

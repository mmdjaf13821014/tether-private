package app.tether.client

import app.tether.client.model.ClientState
import app.tether.client.model.LinkPort
import app.tether.client.model.Problem
import app.tether.core.CallEntry
import app.tether.core.Sms
import app.tether.core.T
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ClientState fed message by message (no session): what each message from the Android phone does to the synced state. */
class ClientStateTest {
    class Sent(val t: String, val b: JSONObject, val id: Int)

    class FakeLink : LinkPort {
        override var isConnected = true
        val sent = ArrayList<Sent>()
        private var next = 10
        override fun send(t: String, b: JSONObject, expectReply: Boolean): Int {
            if (!isConnected) return 0
            val id = if (expectReply) ++next else 0
            sent += Sent(t, b, id); return id
        }
        fun last(t: String) = sent.last { it.t == t }
    }

    private val link = FakeLink()
    private val fx = TestEffects()
    private val s = ClientState(link, fx)
    private val now get() = fx.clock

    private fun sms(id: Long, thread: Long = 7, addr: String = "+10000000001", body: String = "b$id", date: Long = now - 1000,
                    box: Int = 1, read: Boolean = false) = Sms(id, thread, addr, body, date, box, read, null)

    private fun rx(t: String, b: JSONObject = JSONObject(), re: Int? = null) = s.received(t, b, re)
    private fun batch(vararg items: Sms, more: Boolean = false) =
        rx(T.SMS_BATCH, JSONObject().put("items", JSONArray(items.map { it.json() })).put("more", more))
    private fun ranges(vararg r: LongArray) = JSONArray(r.map { JSONArray(it.toList()) })
    private fun syncIds(sms: JSONArray?, calls: JSONArray?, more: Boolean = false) =
        rx(T.SYNC_IDS, JSONObject().apply { sms?.let { put("sms", it) }; calls?.let { put("calls", it) }; put("more", more) })
    private fun call(id: Long, type: Int, num: String = "+10000000003", date: Long = now - 1000) = CallEntry(id, num, null, date, 0, type, null)
    private fun calls(vararg c: CallEntry, more: Boolean = false) =
        rx(T.CALLS_BATCH, JSONObject().put("items", JSONArray(c.map { it.json() })).put("more", more))

    // ------------------------------------------------------------------ sync and sms

    @Test fun syncAsksFromTheLastCompletedSyncNotFromALiveText() {
        s.requestSync(false, listOf("opus"), "android", "Test")
        assertEquals(0L, link.last(T.SYNC).b.getLong("sms"))
        batch(sms(1), sms(2), more = true)
        assertTrue("paging: still syncing", s.syncing)
        batch(sms(3))
        assertFalse(s.syncing); assertEquals(3L, s.smsSyncedUpTo)
        // A live text arrives during an interrupted later sync: the next sync must still start at 3.
        rx(T.SMS_NEW, JSONObject().put("item", sms(9).json()))
        s.requestSync(false, listOf("opus"), "android", "Test")
        assertEquals(3L, link.last(T.SYNC).b.getLong("sms"))
        assertEquals("opus", link.last(T.SYNC).b.getJSONArray("codecs").getString(0))
        assertEquals("android", link.last(T.SYNC).b.getJSONObject("client").getString("kind"))
        s.requestSync(true, listOf("opus"), "android", "Test")
        assertEquals("a full sync starts from zero", 0L, link.last(T.SYNC).b.getLong("sms"))
        assertEquals("", link.last(T.SYNC).b.getString("contacts"))
    }

    @Test fun aRowSentAgainReplacesItNeverDuplicates() {
        batch(sms(1, body = "old"))
        batch(sms(1, body = "new", read = true))
        assertEquals("new", s.sms.single().body)
    }

    @Test fun notificationRules() {
        batch(sms(1))   // the first sync never notifies
        assertTrue(fx.notified.isEmpty())
        rx(T.SMS_NEW, JSONObject().put("item", sms(2).json()))
        assertEquals(listOf("sms 2"), fx.notified)
        rx(T.SMS_NEW, JSONObject().put("item", sms(3, read = true).json()))
        rx(T.SMS_NEW, JSONObject().put("item", sms(4, box = 2).json()))
        rx(T.SMS_NEW, JSONObject().put("item", sms(5, date = now - 7 * 3600_000L).json()))
        assertEquals("read, outgoing and old texts are not notified", listOf("sms 2"), fx.notified)
        s.appActive = true; s.openThread = 7
        rx(T.SMS_NEW, JSONObject().put("item", sms(6).json()))
        assertEquals("not for the conversation on screen", 1, fx.notified.size)
        s.appActive = false
        rx(T.LISTS, JSONObject().put("blocked", JSONArray(listOf("+10000000009"))).put("muted", JSONArray(listOf(8L))))
        rx(T.SMS_NEW, JSONObject().put("item", sms(7, thread = 8).json()))
        rx(T.SMS_NEW, JSONObject().put("item", sms(10, thread = 9, addr = "10000000009").json()))
        assertEquals("muted and blocked are silent", 1, fx.notified.size)
        rx(T.SMS_NEW, JSONObject().put("item", sms(2).json()))
        assertEquals("a repeated row is not notified twice", 1, fx.notified.size)
    }

    @Test fun incrementalSyncNotifiesOnlyFreshUnreadTexts() {
        batch(sms(1))
        s.requestSync(false, listOf("opus"), "android", "Test")
        batch(sms(2), sms(3, read = true))
        assertEquals(listOf("sms 2"), fx.notified)
    }

    @Test fun unreadPartialListLeavesOlderRowsAlone() {
        batch(sms(1), sms(2), sms(3), sms(4))
        assertEquals(4, s.unreadTotal)
        // all=false: only rows at or above the smallest listed id are known; 1 and 2 are unknown and stay unread.
        rx(T.SMS_UNREAD, JSONObject().put("ids", JSONArray(listOf(3L))).put("all", false))
        assertEquals(listOf(false, false, false, true), s.sms.sortedBy { it.id }.map { it.read })
        rx(T.SMS_UNREAD, JSONObject().put("ids", JSONArray()).put("all", true))
        assertEquals(0, s.unreadTotal)
    }

    @Test fun readOnThePhoneMovesTheMarkForwardOnly() {
        batch(sms(1), sms(2), sms(3))
        rx(T.SMS_READ, JSONObject().put("thread", 7).put("upTo", 2))
        assertEquals(1, s.unreadTotal)
        rx(T.SMS_READ, JSONObject().put("thread", 7).put("upTo", 1))
        assertEquals("an older mark never takes reads back", 2L, s.readUpTo[7])
    }

    @Test fun readWhileOfflineIsSentWithTheNextSync() {
        batch(sms(1), sms(2))
        link.isConnected = false
        s.markRead(7)
        assertEquals(0, s.unreadTotal)
        link.isConnected = true
        s.requestSync(false, listOf("opus"), "android", "Test")
        assertEquals(2L, link.last(T.SMS_READ).b.getLong("upTo"))
        s.requestSync(false, listOf("opus"), "android", "Test")
        assertEquals("sent once", 1, link.sent.count { it.t == T.SMS_READ })
        s.markRead(7)
        assertEquals("nothing new to mark", 1, link.sent.count { it.t == T.SMS_READ })
    }

    // ------------------------------------------------------------------ sync.ids: deletions done elsewhere

    @Test fun syncIdsDeletesOnlyWhatThePhoneNoLongerHas() {
        batch(sms(1), sms(2), sms(5), sms(6))
        calls(call(1, 1), call(2, 3))
        s.sendSms("+10000000002", "waiting", null)   // a placeholder (negative id) is never the phone's to delete
        syncIds(ranges(longArrayOf(1, 2)), ranges(longArrayOf(2, 2)), more = true)
        assertEquals("nothing deleted before the last page", 5, s.sms.size)
        syncIds(ranges(longArrayOf(6, 6)), ranges())
        assertEquals(listOf(1L, 2L, 6L), s.sms.filter { it.id > 0 }.map { it.id }.sorted())
        assertEquals(1, s.sms.count { it.id < 0 })
        assertEquals(listOf(2L), s.recents.map { it.id })
    }

    @Test fun aMalformedRangeNeverDeletes() {
        batch(sms(1), sms(2))
        syncIds(JSONArray().put(JSONArray(listOf(1L))), ranges())
        assertEquals(2, s.sms.size)
    }

    @Test fun aSyncIdsWithoutTheSmsListDeletesNoTexts() {
        batch(sms(1), sms(2))
        calls(call(1, 1))
        syncIds(null, ranges(longArrayOf(1, 1)))
        assertEquals("a missing list is not an empty one", 2, s.sms.size)
        syncIds(ranges(longArrayOf(1, 2)), null)
        assertEquals(1, s.recents.size)
    }

    @Test fun aSyncCutOffMidPagesStartsClean() {
        batch(sms(1), sms(2), sms(3))
        syncIds(ranges(longArrayOf(1, 1)), ranges(), more = true)
        s.linkDown()
        syncIds(ranges(longArrayOf(2, 3)), ranges())
        assertEquals("the half-received pages of the old link do not count", listOf(2L, 3L), s.sms.map { it.id }.sorted())
    }

    @Test fun invalidIdListsNeverDeleteEitherKindOfHistory() {
        batch(sms(1), sms(2))
        calls(call(1, 1))
        val invalid = listOf(
            JSONObject.NULL, "not an array", JSONObject(),
            JSONArray().put(JSONArray().put("not an id").put(2)),
            ranges(longArrayOf(2, 1)),
            ranges(longArrayOf(2, 2), longArrayOf(1, 1)),
        )
        for (value in invalid) {
            for (key in listOf("sms", "calls")) {
                rx(T.SYNC_IDS, JSONObject().put("sms", ranges()).put("calls", ranges()).put(key, value))
                assertEquals(2, s.sms.size)
                assertEquals(1, s.recents.size)
            }
        }
    }

    @Test fun aMalformedMiddlePageCancelsDeletionsUntilTheNextSync() {
        batch(sms(1), sms(2), sms(3))
        calls(call(1, 1), call(2, 1))
        syncIds(ranges(longArrayOf(1, 1)), ranges(longArrayOf(1, 1)), more = true)
        rx(T.SYNC_IDS, JSONObject().put("sms", JSONObject.NULL).put("calls", ranges()).put("more", true))
        syncIds(ranges(longArrayOf(3, 3)), ranges())
        assertEquals(listOf(1L, 2L, 3L), s.sms.map { it.id }.sorted())
        assertEquals(2, s.recents.size)

        syncIds(ranges(longArrayOf(2, 2)), ranges())
        assertEquals(listOf(2L), s.sms.map { it.id })
        assertTrue(s.recents.isEmpty())
        syncIds(ranges(), ranges())
        assertTrue(s.sms.isEmpty())
    }

    @Test fun outOfOrderIdPagesNeverDelete() {
        batch(sms(1), sms(2), sms(3))
        syncIds(ranges(longArrayOf(3, 3)), ranges(), more = true)
        syncIds(ranges(longArrayOf(1, 1)), ranges())
        assertEquals(listOf(1L, 2L, 3L), s.sms.map { it.id }.sorted())
    }

    private fun assertHistory(vararg ids: Long) {
        assertEquals("SMS ids", ids.toList(), s.sms.map { it.id }.sorted())
        assertEquals("call history ids", ids.toList(), s.recents.map { it.id }.sorted())
    }

    private fun malformedFirstPageDoesNotReconcile(more: Any) {
        batch(sms(1), sms(2), sms(3))
        calls(call(1, 1), call(2, 1), call(3, 1))
        val first = ranges(longArrayOf(1, 1))
        rx(T.SYNC_IDS, JSONObject().put("sms", first).put("calls", first).put("more", more))
        assertHistory(1, 2, 3)

        val tail = ranges(longArrayOf(3, 3))
        syncIds(tail, tail)
        assertHistory(1, 2, 3)

        val keep = ranges(longArrayOf(2, 2))
        syncIds(keep, keep)
        assertHistory(2)
    }

    @Test fun nullMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile(JSONObject.NULL)
    @Test fun zeroMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile(0)
    @Test fun oneMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile(1)
    @Test fun stringFalseMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile("false")
    @Test fun stringTrueMoreDoesNotAllowReconciliation() = malformedFirstPageDoesNotReconcile("true")
    @Test fun objectMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile(JSONObject())
    @Test fun arrayMoreDoesNotFinalizeAPartialPage() = malformedFirstPageDoesNotReconcile(JSONArray())

    @Test fun malformedMoreAfterAValidPageInvalidatesTheWholeCycle() {
        batch(sms(1), sms(2), sms(3))
        calls(call(1, 1), call(2, 1), call(3, 1))
        val first = ranges(longArrayOf(1, 1))
        syncIds(first, first, more = true)
        assertHistory(1, 2, 3)

        val middle = ranges(longArrayOf(2, 2))
        rx(T.SYNC_IDS, JSONObject().put("sms", middle).put("calls", middle).put("more", "false"))
        assertHistory(1, 2, 3)
        val tail = ranges(longArrayOf(3, 3))
        syncIds(tail, tail)
        assertHistory(1, 2, 3)
    }

    @Test fun malformedMoreAndIdListsCannotLetTheTailDelete() {
        batch(sms(1), sms(2))
        calls(call(1, 1), call(2, 1))
        rx(T.SYNC_IDS, JSONObject().put("sms", JSONObject.NULL).put("calls", "invalid").put("more", 0))
        assertHistory(1, 2)
        syncIds(ranges(), ranges())
        assertHistory(1, 2)

        // A missing marker is a valid final page, including for a legitimate empty set.
        rx(T.SYNC_IDS, JSONObject().put("sms", ranges()).put("calls", ranges()))
        assertHistory()
    }

    @Test fun linkDownClearsAnInvalidIdsCycle() {
        batch(sms(1), sms(2), sms(3))
        calls(call(1, 1), call(2, 1), call(3, 1))
        val first = ranges(longArrayOf(1, 1))
        syncIds(first, first, more = true)
        rx(T.SYNC_IDS, JSONObject().put("sms", JSONObject.NULL).put("calls", first).put("more", "true"))
        assertHistory(1, 2, 3)
        s.linkDown()

        val keep = ranges(longArrayOf(2, 2))
        syncIds(keep, keep, more = true)
        assertHistory(1, 2, 3)
        syncIds(ranges(), ranges(), more = false)
        assertHistory(2)
    }

    // ------------------------------------------------------------------ sending texts

    @Test fun sendResultsAndThePlaceholder() {
        s.sendSms("+10000000002", "  hi  ", 2)
        val p = s.sms.single()
        assertTrue(p.id < 0); assertEquals(4, p.box); assertEquals("hi", p.body)
        val req = link.last(T.SMS_SEND)
        assertEquals(2, req.b.getInt("sub"))
        rx(T.SMS_RESULT, JSONObject().put("ok", true).put("state", "sent"), re = req.id)
        assertEquals(2, s.sms.single().box)
        rx(T.SMS_NEW, JSONObject().put("item", sms(50, thread = 11, addr = "+10000000002", body = "hi", date = now, box = 2, read = true).json()))
        assertEquals("the real row replaces the placeholder", listOf(50L), s.sms.map { it.id })
    }

    @Test fun aFailedSendIsNotDeliveredAndSaysWhy() {
        s.sendSms("+10000000002", "x", null)
        rx(T.SMS_RESULT, JSONObject().put("ok", false).put("err", "radio off"), re = link.last(T.SMS_SEND).id)
        assertEquals(5, s.sms.single().box)
        assertEquals(Problem(Problem.NOT_SENT, "radio off"), s.problem)
        rx(T.SMS_RESULT, JSONObject().put("ok", true), re = 999)
        assertEquals("an answer to nobody changes nothing", 5, s.sms.single().box)
    }

    @Test fun anOldIdenticalTextDoesNotSwallowANewPlaceholder() {
        s.sendSms("+10000000002", "ok", null)
        rx(T.SMS_NEW, JSONObject().put("item", sms(3, addr = "+10000000002", body = "ok", date = now - 10 * 60_000, box = 2).json()))
        assertEquals(2, s.sms.size)
    }

    @Test fun emptyAndTooLongTextsAreNotSent() {
        s.sendSms("+10000000002", "   ", null)
        s.sendSms("+10000000002", "x".repeat(12_001), null)
        assertTrue(s.sms.isEmpty()); assertEquals(Problem.TOO_LONG, s.problem?.kind)
        assertTrue(link.sent.none { it.t == T.SMS_SEND })
    }

    @Test fun outboxGoesOnLinkUpExceptWhatWasDeleted() {
        link.isConnected = false
        s.sendSms("+10000000002", "one", null)
        fx.clock += 1
        s.sendSms("+10000000002", "two", null)
        assertEquals(Problem.SEND_LATER, s.problem?.kind)
        assertEquals(2, s.outbox.size)
        s.deleteMessages(listOf(s.sms.first { it.body == "one" }.id))   // local only: works without the link
        link.isConnected = true
        s.linkUp(listOf("opus"), "android", "Test")
        assertNull(s.problem)
        assertEquals(listOf("two"), link.sent.filter { it.t == T.SMS_SEND }.map { it.b.getString("body") })
        assertTrue(s.outbox.isEmpty())
    }

    @Test fun aPlaceholderNobodyWaitsForTurnsNotDeliveredAfterTwoMinutes() {
        s.sendSms("+10000000002", "x", null)
        s.linkDown()   // the answer is lost with the link
        fx.clock += 60_000
        batch()
        assertEquals("not yet", 4, s.sms.single().box)
        fx.clock += 61_000
        batch()
        assertEquals(5, s.sms.single().box)
    }

    // ------------------------------------------------------------------ deletes, lists, contacts

    @Test fun deletesNeedTheLinkExceptLocalOnlyConversations() {
        batch(sms(1))
        link.isConnected = false
        s.deleteMessages(listOf(1))
        assertEquals(Problem.NOT_CONNECTED, s.problem?.kind)
        assertEquals(1, s.sms.size)
        s.sendSms("+10000000002", "local", null)
        val th = s.sms.first { it.id < 0 }.thread
        s.deleteConversation(th)
        assertEquals(listOf(1L), s.sms.map { it.id })
        assertTrue(s.outbox.isEmpty())
    }

    @Test fun aRefusedDeleteSaysSoAndResyncsInFull() {
        batch(sms(1))
        s.deleteMessages(listOf(1))
        assertTrue(s.sms.isEmpty())
        rx(T.SMS_DELETED, JSONObject().put("ok", false).put("err", "no"))
        assertEquals(Problem(Problem.NOT_DELETED, "no"), s.problem)
        assertEquals("the full sync brings the row back", 0L, link.last(T.SYNC).b.getLong("sms"))
    }

    @Test fun recentsDeleteAndClear() {
        calls(call(1, 1), call(2, 2), call(3, 3))
        s.deleteRecents(listOf(2))
        assertEquals(listOf(2L), link.last(T.CALLS_DELETE).b.getJSONArray("ids").let { listOf(it.getLong(0)) })
        rx(T.CALLS_DELETED, JSONObject().put("ok", true).put("ids", JSONArray(listOf(3L))))
        assertEquals(listOf(1L), s.recents.map { it.id })
        rx(T.CALLS_DELETED, JSONObject().put("ok", true).put("all", true))
        assertTrue(s.recents.isEmpty())
    }

    @Test fun blockAndMuteAreMirroredThenTakenFromThePhone() {
        s.setBlocked("+1 000 000 0005", true)
        assertTrue("shown at once, in any notation", s.isBlocked("10000000005"))
        assertEquals("block", link.last(T.LISTS_SET).b.getString("what"))
        s.setMuted(4, true)
        assertTrue(s.isMuted(4))
        // The phone's answer is the truth: it did not keep the mute.
        rx(T.LISTS, JSONObject().put("blocked", JSONArray(listOf("+10000000005"))).put("muted", JSONArray()).put("system", true))
        assertFalse(s.isMuted(4)); assertTrue(s.lists.system)
        s.setBlocked("+10000000005", false)
        assertFalse(s.isBlocked("+10000000005"))
        rx(T.LISTS, JSONObject().put("blocked", JSONArray()).put("muted", JSONArray()).put("err", "denied"))
        assertEquals(Problem(Problem.OTHER, "denied"), s.problem)
    }

    @Test fun contactsSwapInOnlyAfterTheLastPage() {
        val c1 = JSONObject().put("id", 1).put("name", "Zed Test").put("phones", JSONArray().put(JSONObject().put("n", "+10000000001")))
        val c2 = JSONObject().put("id", 2).put("name", "amy Test").put("starred", true)
        rx(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray().put(c1)).put("more", true).put("full", true).put("version", "v2"))
        assertTrue(s.contacts.isEmpty())
        rx(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray().put(c2)).put("more", false).put("full", true).put("version", "v2"))
        assertEquals("sorted case-insensitively", listOf("amy Test", "Zed Test"), s.contacts.map { it.name })
        assertEquals("v2", s.contactsVersion)
        assertEquals("Zed Test", s.name("1 000 000 0001"))
        assertEquals(listOf(2L), s.favourites.map { it.id })
        s.setStarred(1, true)
        assertEquals(2, s.favourites.size)
        assertTrue(link.last(T.CONTACT_STAR).b.getBoolean("on"))
        // A full sync cut off after its first page: the next full sync starts from an empty list.
        rx(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray().put(c1)).put("more", true).put("full", true).put("version", "v3"))
        s.linkDown()
        rx(T.CONTACTS_BATCH, JSONObject().put("items", JSONArray().put(c2)).put("more", false).put("full", true).put("version", "v3"))
        assertEquals(listOf("amy Test"), s.contacts.map { it.name })
    }

    @Test fun aContactResultAlwaysResyncs() {
        rx(T.CONTACT_RESULT, JSONObject().put("ok", false).put("err", "x"))
        assertEquals(Problem.CONTACT_NOT_SAVED, s.problem?.kind)
        assertEquals(1, link.sent.count { it.t == T.SYNC })
    }

    // ------------------------------------------------------------------ missed calls

    @Test fun firstCallSyncCountsAsSeenThenNewMissedCallsCountAndNotify() {
        calls(call(1, 3), call(2, 3), more = true)
        calls(call(3, 3))
        assertEquals("a new pairing's history is not hundreds of missed calls", 0, s.missedCount)
        assertTrue(fx.notified.isEmpty())
        calls(call(4, 3), call(5, 2), call(6, 3, num = "+10000000009", date = now - 11 * 60_000))
        assertEquals("declined calls are not missed", 2, s.missedCount)
        assertEquals("only a fresh missed call notifies", listOf("missed 4"), fx.notified)
        s.setBlocked("+10000000009", true)
        assertEquals(1, s.missedCount)
        s.markRecentsSeen()
        assertEquals(0, s.missedCount)
    }

    // ------------------------------------------------------------------ status, switches, persistence

    @Test fun statusSimsAndOptionalFields() {
        rx(T.STATUS, JSONObject().put("defaultSub", 2).put("country", "zz").put("ringer", "silent").put("dnd", true)
            .put("tools", JSONObject().put("x", true))
            .put("sims", JSONArray().put(JSONObject().put("slot", 0).put("sub", 3).put("name", "Work"))
                .put(JSONObject().put("slot", 1).put("sub", 2).put("name", ""))))
        assertEquals(2, s.defaultSub)
        assertEquals("Work", s.simName(3)); assertEquals("SIM 2", s.simName(2)); assertNull(s.simName(9)); assertNull(s.simName(null))
        assertEquals("unknown fields are kept as sent", "silent", s.status!!.getString("ringer"))
        rx(T.STATUS, JSONObject().put("sims", JSONArray().put(JSONObject().put("slot", 0).put("sub", 3).put("name", "Work"))))
        assertNull("one SIM: no name", s.simName(3))
        assertNull("an older phone without defaultSub", s.defaultSub)
        assertEquals("per-conversation SIM falls back to the default", null, s.preferredSim("+10000000001"))
        s.setPreferredSim(3, "+1 000 000 0001")
        assertEquals(3, s.preferredSim("10000000001"))
    }

    @Test fun netStateErrorsKeepTheLastValuesAndOnlyOurAnswerClearsPending() {
        s.setNet("data", false)
        assertEquals("data", s.netPending)
        rx(T.NET_STATE, JSONObject().put("data", true).put("hotspot", false))   // pushed by another client
        assertEquals("data", s.netPending)
        rx(T.NET_STATE, JSONObject().put("err", "denied").put("share", true), re = link.last(T.NET_SET).id)
        assertNull(s.netPending); assertEquals("denied", s.netError)
        assertTrue("kept", s.net!!.getBoolean("data")); assertTrue(s.net!!.getBoolean("share"))
    }

    @Test fun aLostSwitchAnswerTimesOut() {
        s.setNet("hotspot", true)
        fx.timers.single().second()
        assertNull(s.netPending); assertEquals(Problem.NO_ANSWER, s.netError)
        link.isConnected = false
        s.setNet("hotspot", true)
        assertNull("nothing pending without a link", s.netPending)
    }

    @Test fun netRefreshAtMostEveryTenSeconds() {
        s.refreshNet(); s.refreshNet()
        fx.clock += 10_000; s.refreshNet()
        assertEquals(2, link.sent.count { it.t == T.NET_GET })
    }

    @Test fun saveAndLoadKeepEverything() {
        batch(sms(1), sms(2))
        calls(call(1, 1))
        s.markRead(7); s.setDraft("draft", "+10000000001"); s.setPreferredSim(2, "+10000000001")
        link.isConnected = false; s.sendSms("+10000000004", "later", 1)
        val copy = ClientState(FakeLink(), TestEffects()).apply { load(JSONObject(s.toJson().toString())) }
        assertEquals(s.sms, copy.sms); assertEquals(s.recents, copy.recents); assertEquals(s.outbox, copy.outbox)
        assertEquals(s.readUpTo, copy.readUpTo); assertEquals("draft", copy.draft("10000000001")); assertEquals(2, copy.preferredSim("+10000000001"))
        assertEquals(s.smsSyncedUpTo, copy.smsSyncedUpTo); assertEquals(s.recentsSeenUpTo, copy.recentsSeenUpTo)
        copy.wipe()
        assertTrue(copy.sms.isEmpty() && copy.outbox.isEmpty() && copy.readUpTo.isEmpty() && copy.smsSyncedUpTo == null)
    }

    @Test fun unknownMessagesAreNotOurs() {
        assertFalse(rx("x.unknown"))
        assertTrue(rx(T.PING))
        rx(T.ERROR, JSONObject().put("msg", "bad"))
        assertEquals(Problem(Problem.OTHER, "bad"), s.problem)
    }
}

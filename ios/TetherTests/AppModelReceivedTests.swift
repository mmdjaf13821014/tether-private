import XCTest
import TetherCore
@testable import Tether

/// What the iPhone shows after each message the Android phone sends (protocol §5), fed through `received`.
@MainActor
final class AppModelReceivedTests: XCTestCase {
    var m: AppModel { AppModel.shared }

    override func setUp() async throws {
        await MainActor.run { AppModel.shared.resetForTests() }
    }

    // MARK: SMS

    /// A sync page builds conversations, newest first, with unread counts; the last page records the sync point.
    func testSmsBatchBuildsConversations() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1, read: true, date: 1000),
                                                       sms(2, thread: 1, date: 3000),
                                                       sms(3, thread: 2, incoming: false, read: true, date: 2000)], more: true)))
        XCTAssertNil(m.data.smsSyncedUpTo, "a page with more to come is not a completed sync")
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(4, thread: 2, date: 4000)], more: false)))
        XCTAssertEqual(m.conversations.map(\.thread), [2, 1])
        XCTAssertEqual(m.conversations.map(\.unread), [1, 1])
        XCTAssertEqual(m.unreadTotal, 2)
        XCTAssertEqual(m.data.smsSyncedUpTo, 4)
        XCTAssertEqual(m.messages(thread: 1).map(\.id), [1, 2])
    }

    /// The same id arriving again replaces the row (no duplicates).
    func testSmsMergeById() {
        feed(m, wire(MsgType.smsNew, SmsNewW(item: sms(7, thread: 1))))
        var again = sms(7, thread: 1); again.read = true
        feed(m, wire(MsgType.smsNew, SmsNewW(item: again)))
        XCTAssertEqual(m.data.sms.count, 1)
        XCTAssertEqual(m.unreadTotal, 0)
    }

    /// The Android phone's field names for sms.new (pinned as JSON, not through the Swift struct).
    func testSmsNewWireFields() {
        feed(m, wireJSON(MsgType.smsNew, #"{"item":{"id":9,"thread":3,"addr":"+10000000002","body":"hi","date":1,"box":1,"read":false,"sub":2}}"#))
        XCTAssertEqual(m.data.sms.first?.sub, 2)
        XCTAssertEqual(m.conversations.first?.address, "+10000000002")
    }

    /// A provider row for a text sent here replaces its "sending" placeholder (same address and body).
    func testPlaceholderReplacedBySentRow() {
        m.sendSms(to: "+10000000003", body: "on my way", sub: nil)   // link down: goes to the outbox
        XCTAssertEqual(m.data.sms.filter { $0.id < 0 }.count, 1)
        XCTAssertEqual(m.data.outbox?.count, 1)
        feed(m, wire(MsgType.smsNew, SmsNewW(item: Sms(id: 50, thread: 9, addr: "+10000000003", body: "on my way",
                                                      date: ms(minutesAgo: 0), box: 2, read: true))))
        XCTAssertEqual(m.data.sms.map(\.id), [50])
    }

    /// An old identical text in the history must not swallow a new placeholder.
    func testOldIdenticalTextDoesNotConfirmPlaceholder() {
        m.sendSms(to: "+10000000003", body: "Ok", sub: nil)
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [Sms(id: 5, thread: 9, addr: "+10000000003", body: "Ok",
                                                           date: ms(minutesAgo: 600), box: 2, read: true)], more: false)))
        XCTAssertEqual(m.data.sms.filter { $0.id < 0 }.count, 1)
    }

    /// sms.read from the other side clears unread up to the given id only.
    func testSmsRead() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1, date: 1), sms(2, thread: 1, date: 2)], more: false)))
        feed(m, wire(MsgType.smsRead, SmsRead(thread: 1, upTo: 1)))
        XCTAssertEqual(m.unreadTotal, 1)
        feed(m, wire(MsgType.smsRead, SmsRead(thread: 1, upTo: 0)))   // an older mark never brings unread back
        XCTAssertEqual(m.data.readUpTo[1], 1)
    }

    /// sms.unread lists the ids still unread on the Android phone: everything else is read.
    func testSmsUnread() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1, date: 1), sms(2, thread: 1, date: 2), sms(3, thread: 2, date: 3)], more: false)))
        feed(m, wire(MsgType.smsUnread, SmsUnread(ids: [2])))
        XCTAssertEqual(m.data.sms.filter { !$0.read }.map(\.id), [2])
        XCTAssertEqual(m.unreadTotal, 1)
    }

    /// sms.deleted (ok) removes the rows; a whole thread goes with its read mark.
    func testSmsDeleted() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1), sms(2, thread: 1), sms(3, thread: 2)], more: false)))
        feed(m, wire(MsgType.smsRead, SmsRead(thread: 2, upTo: 3)))
        feed(m, wire(MsgType.smsDeleted, SmsDeleted(ok: true, ids: [1])))
        XCTAssertEqual(Set(m.data.sms.map(\.id)), [2, 3])
        feed(m, wire(MsgType.smsDeleted, SmsDeleted(ok: true, ids: [], thread: 2)))
        XCTAssertEqual(m.data.sms.map(\.id), [2])
        XCTAssertNil(m.data.readUpTo[2])
        feed(m, wire(MsgType.smsDeleted, SmsDeleted(ok: false, ids: [2], err: "no")))
        XCTAssertEqual(m.data.sms.map(\.id), [2], "a failed delete keeps the text")
        XCTAssertNotNil(m.lastError)
    }

    // MARK: sync.ids (deletions elsewhere): never delete local history wrongly

    /// Rows outside the ranges are gone on the phone; rows inside stay; local placeholders always stay.
    func testSyncIdsDeletesOnlyMissing() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: (1...6).map { sms($0, thread: 1) }, more: false)))
        m.sendSms(to: "+10000000004", body: "pending", sub: nil)
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 10, num: "+10000000001", date: 1, dur: 0, type: 1),
                                                           CallEntry(id: 11, num: "+10000000001", date: 2, dur: 0, type: 1)], more: false)))
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[1, 2], [5, 6]], calls: [[11, 11]])))
        XCTAssertEqual(m.data.sms.filter { $0.id > 0 }.map(\.id).sorted(), [1, 2, 5, 6])
        XCTAssertEqual(m.data.sms.filter { $0.id < 0 }.count, 1)
        XCTAssertEqual(m.data.recents.map(\.id), [11])
    }

    /// Pages are applied only once the last one is in: an id in a later page must not be deleted by an earlier one.
    func testSyncIdsPaged() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1), sms(9, thread: 1)], more: false)))
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[1, 1]], calls: [], more: true)))
        XCTAssertEqual(m.data.sms.count, 2)
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[9, 9]], calls: [], more: false)))
        XCTAssertEqual(m.data.sms.map(\.id).sorted(), [1, 9])
    }

    /// A malformed range never deletes anything.
    func testSyncIdsMalformedRangeKeepsAll() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1), sms(9, thread: 1)], more: false)))
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[5]], calls: [])))
        XCTAssertEqual(m.data.sms.count, 2)
    }

    /// A page cut off by a link drop is forgotten: the next (complete) answer alone decides.
    func testSyncIdsPagesDroppedOnLinkLoss() {
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1), sms(9, thread: 1)], more: false)))
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[1, 1]], calls: [], more: true)))
        m.linkChanged(.connected(transport: "l2cap"))
        m.linkChanged(.searching)
        feed(m, wire(MsgType.syncIds, SyncIds(sms: [[9, 9]], calls: [])))
        XCTAssertEqual(m.data.sms.map(\.id), [9])
    }

    // MARK: calls, missed-call badge

    /// The first call sync of a pairing counts its whole history as seen; later misses count.
    func testFirstCallSyncIsSeenThenMissedCounts() {
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 1, num: "+10000000001", date: 1, dur: 0, type: 3)], more: true)))
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 2, num: "+10000000001", date: 2, dur: 0, type: 3)], more: false)))
        XCTAssertEqual(m.missedCount, 0)
        XCTAssertEqual(m.data.recentsSeenUpTo, 2)
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 3, num: "+10000000002", date: 3, dur: 0, type: 3),
                                                           CallEntry(id: 4, num: "+10000000002", date: 4, dur: 30, type: 1)], more: false)))
        XCTAssertEqual(m.missedCount, 1)
        XCTAssertEqual(m.data.recents.map(\.id), [4, 3, 2, 1], "newest first")
        m.markRecentsSeen()
        XCTAssertEqual(m.missedCount, 0)
    }

    /// A first sync cut off before its last page stays a first sync (marker Int64.max) and never badges.
    func testInterruptedFirstSyncStillNotMissed() {
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 1, num: "+10000000001", date: 1, dur: 0, type: 3)], more: true)))
        XCTAssertEqual(m.data.recentsSeenUpTo, Int64.max)
        XCTAssertEqual(m.missedCount, 0)
        m.markRecentsSeen()
        XCTAssertEqual(m.missedCount, 0)
    }

    /// Missed calls from blocked numbers never count.
    func testBlockedMissedNotCounted() {
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 1, num: "+10000000001", date: 1, dur: 0, type: 1)], more: false)))
        feed(m, wire(MsgType.lists, BlockLists(blocked: ["+1 000-000-0009"], muted: [])))
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: [CallEntry(id: 2, num: "+10000000009", date: 2, dur: 0, type: 3)], more: false)))
        XCTAssertEqual(m.missedCount, 0)
    }

    /// calls.deleted: ids or all.
    func testCallsDeleted() {
        feed(m, wire(MsgType.callsBatch, CallsBatchW(items: (1...3).map { CallEntry(id: $0, num: "+10000000001", date: $0, dur: 0, type: 1) }, more: false)))
        feed(m, wire(MsgType.callsDeleted, CallsDeleted(ok: true, ids: [2])))
        XCTAssertEqual(m.data.recents.map(\.id), [3, 1])
        feed(m, wire(MsgType.callsDeleted, CallsDeleted(ok: false, ids: [3])))
        XCTAssertEqual(m.data.recents.count, 2)
        feed(m, wire(MsgType.callsDeleted, CallsDeleted(ok: true, ids: [], all: true)))
        XCTAssertTrue(m.data.recents.isEmpty)
    }

    // MARK: contacts

    /// Contact pages are swapped in only when the last page arrives; names and photos index by number.
    func testContactsPaged() {
        feed(m, wireJSON(MsgType.contactsBatch, #"{"items":[{"id":1,"name":"Zed Test","phones":[{"n":"+10000000001","type":"mobile"}],"emails":[],"starred":true}],"more":true,"version":"v1","full":true}"#))
        XCTAssertTrue(m.data.contacts.isEmpty)
        feed(m, wireJSON(MsgType.contactsBatch, #"{"items":[{"id":2,"name":"amy Test","phones":[{"n":"(000) 000-0002","type":"home"}],"emails":[],"photo":"PH","starred":false}],"more":false,"version":"v1","full":true}"#))
        XCTAssertEqual(m.data.contacts.map(\.name), ["amy Test", "Zed Test"])
        XCTAssertEqual(m.data.contactsVersion, "v1")
        XCTAssertEqual(m.name(for: "+10000000002"), "amy Test")
        XCTAssertEqual(m.photo(for: "0000000002"), "PH")
        XCTAssertEqual(m.favourites.map(\.id), [1])
    }

    // MARK: status, lists

    /// Status with only the required fields (an older Android build) decodes; the phone tools stay hidden.
    func testStatusMinimalFields() {
        feed(m, wireJSON(MsgType.status, #"{"device":"A","battery":10,"charging":false,"sims":[],"dialer":false,"helper":{"state":"off","up":false,"down":false},"wifi":false,"adbWifi":false}"#))
        XCTAssertEqual(m.status?.battery, 10)
        XCTAssertNil(m.status?.tools)
        XCTAssertNil(m.status?.defaultSub)
    }

    /// Status with every optional field.
    func testStatusFullFields() {
        feed(m, wireJSON(MsgType.status, #"{"device":"A","battery":10,"charging":true,"sims":[{"slot":0,"sub":3,"name":"Line A","carrier":"C","signal":3,"net":"LTE","service":"in","roaming":false,"country":"us"},{"slot":1,"sub":2,"name":"","carrier":"D","signal":0,"service":"out"}],"defaultSub":3,"dialer":true,"helper":{"state":"running","method":"intercept","up":true,"down":true,"silent":"full"},"wifi":true,"adbWifi":true,"clients":[{"name":"iPhone","kind":"iphone","self":true}],"tools":{"ussd":true,"find":false},"ringer":"vibrate","dnd":true,"future":1}"#))
        XCTAssertEqual(m.sims.count, 2)
        XCTAssertEqual(m.simName(3), "Line A")
        XCTAssertEqual(m.simName(2), "SIM 2", "an unnamed SIM is called by its slot")
        XCTAssertFalse(m.sims[1].hasService)
        XCTAssertEqual(m.status?.tools?.ussd, true)
        XCTAssertEqual(m.status?.ringer, "vibrate")
        XCTAssertEqual(m.preferredSim(for: "+10000000005"), 3, "no choice made: the default SIM")
    }

    /// With one SIM its name is never shown.
    func testSimNameHiddenWithOneSim() {
        feed(m, wire(MsgType.status, status(sims: [SimInfo(slot: 0, sub: 3, name: "Only", carrier: "C")])))
        XCTAssertNil(m.simName(3))
    }

    /// lists: blocked numbers match in any notation; muted threads; an error is shown.
    func testLists() {
        feed(m, wireJSON(MsgType.lists, #"{"blocked":["+10000000009"],"muted":[4],"system":true,"err":"partial"}"#))
        XCTAssertTrue(m.isBlocked("(000) 000-0009"))
        XCTAssertFalse(m.isBlocked("+10000000008"))
        XCTAssertFalse(m.isBlocked(""))
        XCTAssertTrue(m.isMuted(4))
        XCTAssertEqual(m.lastError, "partial")
    }

    // MARK: phone tools, net

    func testUssdResult() {
        feed(m, wire(MsgType.ussdResult, UssdResult(ok: true, text: "Balance 10")))
        XCTAssertEqual(m.ussdResult?.text, "Balance 10")
        XCTAssertFalse(m.ussdPending)
    }

    /// An invalid code is refused here without a message (and only when linked).
    func testUssdRefusedWhenNotLinked() {
        m.sendUssd("*123#", sub: nil)
        XCTAssertFalse(m.ussdPending)
        XCTAssertNil(m.ussdResult)
    }

    func testPhoneFinding() {
        feed(m, wireJSON(MsgType.phoneFinding, #"{"on":true}"#))
        XCTAssertTrue(m.finding)
        feed(m, wireJSON(MsgType.phoneFinding, #"{"on":false,"err":"no ringtone"}"#))
        XCTAssertFalse(m.finding)
        XCTAssertEqual(m.phoneToolError, "no ringtone")
    }

    /// ringer.result updates the last status' ringer mode.
    func testRingerResult() {
        feed(m, wire(MsgType.status, status(tools: .init(ringer: true), ringer: "normal")))
        feed(m, wireJSON(MsgType.ringerResult, #"{"ok":true,"mode":"silent"}"#))
        XCTAssertEqual(m.status?.ringer, "silent")
    }

    /// An error net.state keeps the last known switch values; a reply clears the pending switch.
    func testNetStateErrorKeepsValues() {
        feed(m, wire(MsgType.netState, NetState(data: true, hotspot: false, bt: true)))
        feed(m, wireJSON(MsgType.netState, #"{"err":"helper down"}"#, re: 5))
        XCTAssertEqual(m.net?.data, true)
        XCTAssertEqual(m.net?.bt, true)
        XCTAssertEqual(m.net?.err, "helper down")
        XCTAssertNil(m.netPending)
    }

    // MARK: calls (signalling)

    /// call.state rows become active calls and leave when ended.
    func testCallStateTracksActiveCalls() {
        feed(m, wireJSON(MsgType.callState, #"{"cid":"c1","num":"+10000000001","state":"ringing","dir":"in","hold":false}"#))
        XCTAssertEqual(m.activeCalls["c1"]?.state, "ringing")
        feed(m, wireJSON(MsgType.callState, #"{"cid":"c1","num":"+10000000001","state":"ended","dir":"in","hold":false,"cause":"remote"}"#))
        XCTAssertNil(m.activeCalls["c1"])
    }

    /// A call whose audio is on another client is listed as remote ("Move here").
    func testCallListElsewhere() {
        feed(m, wireJSON(MsgType.callList, #"{"calls":[{"cid":"c2","num":"+10000000002","state":"active","dir":"out","hold":false,"elsewhere":true,"where":"iPad"}]}"#))
        XCTAssertEqual(m.remoteCalls.map(\.cid), ["c2"])
        XCTAssertEqual(m.remoteCalls.first?.at, "iPad")
        feed(m, wireJSON(MsgType.callList, #"{"calls":[]}"#))
        XCTAssertTrue(m.remoteCalls.isEmpty)
    }

    /// audio.stats only goes to the Link log; a malformed body or unknown type changes nothing and never crashes.
    func testAudioStatsAndBadBodies() {
        feed(m, wire(MsgType.audioStats, AudioStats(summary: "frames 1")))
        feed(m, wireJSON(MsgType.smsBatch, #"{"items":"nope"}"#))
        feed(m, wireJSON("future.type", #"{"x":1}"#))
        XCTAssertTrue(m.data.sms.isEmpty)
    }
}

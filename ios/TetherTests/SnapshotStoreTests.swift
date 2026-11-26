import XCTest
import TetherCore
@testable import Tether

final class SnapshotStoreTests: XCTestCase {
    /// A snapshot written by today's released app must decode in every later build (the owner's synced data).
    func testDecodesReleasedFormatFixture() throws {
        let d = try Data(contentsOf: fixtureURL("snapshot-v1.json"))
        let s = try JSONDecoder().decode(Snapshot.self, from: d)
        XCTAssertEqual(s.sms.count, 4)
        XCTAssertEqual(s.sms[0], Sms(id: 101, thread: 7, addr: "+10000000001", body: "Test text one", date: 1763800000000, box: 1, read: false, sub: 3))
        XCTAssertNil(s.sms[2].sub)
        XCTAssertEqual(s.contacts.map(\.name), ["Test Person", "Other Person"])
        XCTAssertEqual(s.contacts[1].phones[0].label, "Desk")
        XCTAssertEqual(s.contacts[1].photo, "AAAA")
        XCTAssertTrue(s.contacts[0].starred)
        XCTAssertEqual(s.recents.map(\.id), [201, 200])
        XCTAssertNil(s.recents[1].name)
        XCTAssertEqual(s.contactsVersion, "v-test-1")
        XCTAssertEqual(s.readUpTo, [7: 101, 8: 103])
        XCTAssertEqual(s.drafts, ["000000002": "draft text"])
        XCTAssertEqual(s.threadSim, ["000000001": 2])
        XCTAssertEqual(s.lastStatus?.sims.count, 2)
        XCTAssertEqual(s.lastStatus?.sims[0].country, "us")
        XCTAssertEqual(s.lastStatus?.tools?.ussd, true)
        XCTAssertEqual(s.lastStatus?.clients?.first?.isSelf, true)
        XCTAssertEqual(s.lists?.blocked, ["+10000000009"])
        XCTAssertEqual(s.lists?.muted, [8])
        XCTAssertEqual(s.smsSyncedUpTo, 103)
        XCTAssertEqual(s.outbox?.first?.placeholder, -1763800180000)
        XCTAssertEqual(s.recentsSeenUpTo, 200)
    }

    /// Encoding and decoding gives back the same data (what the app writes it can read).
    func testRoundTrip() throws {
        let s = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: fixtureURL("snapshot-v1.json")))
        let again = try JSONDecoder().decode(Snapshot.self, from: JSONEncoder().encode(s))
        // (Not compared byte for byte: readUpTo's Int64 keys encode as a pair list in hash order.)
        XCTAssertEqual(again.contacts, s.contacts); XCTAssertEqual(again.recents, s.recents); XCTAssertEqual(again.lists, s.lists)
        XCTAssertEqual(again.drafts, s.drafts); XCTAssertEqual(again.threadSim, s.threadSim)
        XCTAssertEqual(again.outbox?.map(\.placeholder), s.outbox?.map(\.placeholder))
        XCTAssertEqual(again.smsSyncedUpTo, s.smsSyncedUpTo); XCTAssertEqual(again.recentsSeenUpTo, s.recentsSeenUpTo)
        XCTAssertEqual(again.sms, s.sms); XCTAssertEqual(again.readUpTo, s.readUpTo); XCTAssertEqual(again.lastStatus, s.lastStatus)
    }

    /// The oldest files have only the first fields; the optional ones added later must not be required.
    func testOldFileWithoutOptionalFields() throws {
        let old = #"{"sms":[],"contacts":[],"recents":[],"contactsVersion":"","readUpTo":[],"drafts":{},"threadSim":{}}"#
        let s = try JSONDecoder().decode(Snapshot.self, from: Data(old.utf8))
        XCTAssertNil(s.lists); XCTAssertNil(s.smsSyncedUpTo); XCTAssertNil(s.outbox); XCTAssertNil(s.recentsSeenUpTo); XCTAssertNil(s.lastStatus)
    }

    /// A file from a newer build (unknown keys) still opens in an older one.
    func testUnknownFieldsIgnored() throws {
        var obj = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL("snapshot-v1.json"))) as! [String: Any]
        obj["somethingNew"] = ["x": 1]
        var sms0 = (obj["sms"] as! [[String: Any]])[0]; sms0["future"] = true
        obj["sms"] = [sms0]
        let s = try JSONDecoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: obj))
        XCTAssertEqual(s.sms.count, 1)
    }

    /// saveNow writes to disk before returning and load reads it back.
    func testStoreSaveNowAndLoad() throws {
        let url = tempDir().appendingPathComponent("tether-data.json")
        let store = SnapshotStore(url: url)
        var s = Snapshot(); s.sms = [sms(5, thread: 1)]; s.recentsSeenUpTo = 9
        store.saveNow(s)
        let back = SnapshotStore(url: url).load()
        XCTAssertEqual(back.sms, s.sms); XCTAssertEqual(back.recentsSeenUpTo, 9)
    }

    /// A pending debounced save must not bring wiped data back (unpair / new pairing).
    func testWipeCancelsPendingSave() throws {
        let url = tempDir().appendingPathComponent("tether-data.json")
        let store = SnapshotStore(url: url)
        var s = Snapshot(); s.sms = [sms(5, thread: 1)]
        store.save(s)
        store.wipe()
        Thread.sleep(forTimeInterval: 1.3)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(store.load().sms.isEmpty)
    }

    /// A missing file is a fresh install: an empty snapshot.
    func testMissingFileIsEmpty() {
        XCTAssertTrue(SnapshotStore(url: tempDir().appendingPathComponent("none.json")).load().sms.isEmpty)
    }

    /// A file that cannot be decoded loads as empty without crashing, and is kept aside, not overwritten by the
    /// next save (the data would otherwise be lost silently).
    func testCorruptFileIsKeptAside() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("tether-data.json")
        try Data("{\"sms\": [ truncated".utf8).write(to: url)
        let store = SnapshotStore(url: url)
        XCTAssertTrue(store.load().sms.isEmpty)
        store.saveNow(Snapshot())
        let aside = dir.appendingPathComponent("tether-data.corrupt.json")
        XCTAssertEqual(try String(contentsOf: aside, encoding: .utf8), "{\"sms\": [ truncated")
    }
}

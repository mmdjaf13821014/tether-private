import XCTest
@testable import TetherCore

final class ListsTests: XCTestCase {
    func testSyncIdsContains() {
        let r: [[Int64]] = [[1, 3], [7, 8], [10, 10]]
        XCTAssertTrue(SyncIds.contains(r, 1)); XCTAssertTrue(SyncIds.contains(r, 3)); XCTAssertTrue(SyncIds.contains(r, 8))
        XCTAssertTrue(SyncIds.contains(r, 10))
        XCTAssertFalse(SyncIds.contains(r, 4)); XCTAssertFalse(SyncIds.contains(r, 9)); XCTAssertFalse(SyncIds.contains(r, 11))
        XCTAssertFalse(SyncIds.contains([], 1))
    }

    func testBlockedMatchesAnyNotation() {
        let l = BlockLists(blocked: ["+989121234567", "7000"])
        XCTAssertTrue(l.isBlocked("09121234567"))
        XCTAssertTrue(l.isBlocked("۰۹۱۲۱۲۳۴۵۶۷"))
        XCTAssertTrue(l.isBlocked("7000"))
        XCTAssertFalse(l.isBlocked("09121234568"))
        XCTAssertFalse(l.isBlocked(""))
    }

    func testWireFormat() throws {
        let d = try JSONDecoder().decode(SyncIds.self, from: Data(#"{"sms":[[1,3]],"calls":[]}"#.utf8))
        XCTAssertEqual(d.sms, [[1, 3]])
        let l = try JSONDecoder().decode(BlockLists.self, from: Data(#"{"blocked":["1"],"muted":[5],"system":true,"where":"system"}"#.utf8))
        XCTAssertEqual(l.muted, [5]); XCTAssertEqual(l.system, true)
        let s = String(data: try JSONEncoder().encode(SmsDelete(thread: 9)), encoding: .utf8)!
        XCTAssertEqual(s, #"{"thread":9}"#)
    }

    func testShareProxyReachable() throws {
        let j = #"{"data":false,"vpn":true,"share":true,"proxy":{"port":8118,"addrs":[{"iface":"swlan0","ip":"10.123.181.251","kind":"hotspot","pac":"http://10.123.181.251:8118/proxy.pac"},{"iface":"bt-pan","ip":"192.168.44.1","kind":"bt","pac":"http://192.168.44.1:8118/proxy.pac"}]}}"#
        let st = try JSONDecoder().decode(NetState.self, from: Data(j.utf8))
        XCTAssertEqual(st.vpn, true); XCTAssertEqual(st.share, true)
        let p = try XCTUnwrap(st.proxy)
        XCTAssertEqual(p.reachable(from: ["192.168.0.20", "10.123.181.37"])?.iface, "swlan0")
        XCTAssertEqual(p.reachable(from: ["192.168.44.85"])?.kind, "bt")
        XCTAssertNil(p.reachable(from: ["192.168.0.20"]))
        XCTAssertNil(try JSONDecoder().decode(NetState.self, from: Data(#"{"data":true}"#.utf8)).proxy)
    }

    func testUnreadMirror() {
        let full = SmsUnread(ids: [50, 40], all: true)
        XCTAssertEqual(full.isRead(50), false)
        XCTAssertEqual(full.isRead(10), true)        // read on the phone
        let capped = SmsUnread(ids: [50, 40], all: false)
        XCTAssertNil(capped.isRead(10))              // below the capped list: unknown, leave it
        XCTAssertEqual(capped.isRead(45), true)
    }
}

import XCTest
import TetherCore
@testable import Tether

@MainActor
final class DisplayTests: XCTestCase {
    var m: AppModel { AppModel.shared }
    override func setUp() async throws { await MainActor.run { AppModel.shared.resetForTests() } }

    /// An unknown number is wrapped in a left-to-right isolate so "+…" keeps its plus sign in Persian layout.
    func testUnknownNumberInIsolate() {
        let s = m.displayName("+10000000001")
        XCTAssertTrue(s.hasPrefix("\u{2066}") && s.hasSuffix("\u{2069}"))
        XCTAssertTrue(s.filter(\.isNumber).hasSuffix("10000000001"))
    }

    /// A contact's name is shown as is (no isolate), matched in any notation.
    func testContactName() {
        var s = Snapshot(); s.contacts = [Contact(id: 1, name: "Test Person", phones: [Phone(n: "+1 (000) 000-0001")])]
        m.resetForTests(s)
        XCTAssertEqual(m.displayName("+10000000001"), "Test Person")
    }

    func testEmptyNumberIsUnknown() {
        XCTAssertFalse(m.displayName("").contains("\u{2066}"))
        XCTAssertFalse(m.displayName("").isEmpty)
    }

    /// National numbers are grouped by the default SIM's country, not the iPhone's region.
    func testRegionFromDefaultSim() {
        feed(m, wire(MsgType.status, status(sims: [SimInfo(slot: 0, sub: 3, name: "A", carrier: "C", country: "ir"),
                                                   SimInfo(slot: 1, sub: 2, name: "B", carrier: "D", country: "us")], defaultSub: 2)))
        XCTAssertEqual(m.numberRegion, "us")
        XCTAssertEqual(m.formatted("2025550123"), PhoneFormat.format("2025550123", region: "us"))
        feed(m, wire(MsgType.status, status(sims: [SimInfo(slot: 0, sub: 3, name: "A", carrier: "C", country: "ir")], defaultSub: 2)))
        XCTAssertEqual(m.numberRegion, "ir", "default SIM without a country: the first SIM that has one")
    }

    /// Short codes and USSD codes are shown unchanged.
    func testShortCodesUnchanged() {
        XCTAssertEqual(m.formatted("*140#"), "*140#")
        XCTAssertEqual(m.formatted("7000"), "7000")
    }
}

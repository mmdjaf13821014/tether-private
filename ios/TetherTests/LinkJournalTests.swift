import XCTest
@testable import Tether

final class LinkJournalTests: XCTestCase {
    /// The log keeps only the newest lines (file and memory), newest first in the view.
    func testCapAndOrder() throws {
        let url = tempDir().appendingPathComponent("link-log.txt")
        let j = LinkJournal(url: url, cap: 3)
        for i in 1...5 { j.add("event \(i)") }
        j.flush()
        let shown = j.text.split(separator: "\n").map { String($0.split(separator: " ", maxSplits: 2)[2]) }
        XCTAssertEqual(shown, ["event 5", "event 4", "event 3"])
        let file = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        XCTAssertEqual(file.count, 3)
        XCTAssertTrue(file[2].hasSuffix("event 5"))
    }

    /// Lines survive a relaunch, trimmed to the cap.
    func testReloadAfterRelaunch() {
        let url = tempDir().appendingPathComponent("link-log.txt")
        let j = LinkJournal(url: url, cap: 10)
        for i in 1...4 { j.add("e\(i)") }
        j.flush()
        let again = LinkJournal(url: url, cap: 2)
        XCTAssertEqual(again.text.split(separator: "\n").count, 2)
        XCTAssertTrue(again.text.hasSuffix("e3"))
    }

    /// Call lines in the Link log name calls by id and state only, never by number.
    @MainActor
    func testCallLinesHaveNoNumbers() {
        AppModel.shared.resetForTests()
        feed(AppModel.shared, wireJSON("call.state", #"{"cid":"jt1","num":"+10000000007","state":"ringing","dir":"in","hold":false}"#))
        feed(AppModel.shared, wireJSON("call.state", #"{"cid":"jt1","num":"+10000000007","state":"ended","dir":"in","hold":false}"#))
        LinkJournal.shared.flush()
        let log = LinkJournal.shared.text
        XCTAssertTrue(log.contains("call jt1 in ringing"))
        XCTAssertFalse(log.contains("10000000007"))
    }
}

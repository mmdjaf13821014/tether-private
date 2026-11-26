import XCTest
@testable import Tether

/// The problem report the Link tab builds: the sections in order, no device names, no addresses.
@MainActor
final class ProblemReportBuilderTests: XCTestCase {
    func testReportHasEverySectionAndNoPersonalData() async {
        LinkJournal.shared.add("call from +15550101 ended, peer 00:11:22:33:44:55, proxy 192.168.44.1")
        LinkJournal.shared.flush()
        AppEvents.shared.add("link state: searching")
        let (text, _) = await ProblemReportBuilder.text(model: AppModel.shared)
        let titles = text.split(separator: "\n").filter { $0.hasPrefix("## ") }.map(String.init)
        XCTAssertEqual(titles, ["## Device", "## Setup", "## Link", "## Calls and audio", "## Android phone (from its last status)", "## Link log", "## App events"])
        XCTAssertTrue(text.hasPrefix("=== Tether problem report ===\nApp: Cellular "))
        XCTAssertTrue(text.contains("· iPhone app\nCreated: "))
        XCTAssertTrue(text.contains("model: "))
        XCTAssertTrue(text.contains("background modes: ok"))
        XCTAssertFalse(text.contains("+15550101"))
        XCTAssertFalse(text.contains("192.168.44.1"))
        XCTAssertFalse(text.contains("00:11:22:33:44:55"))
        XCTAssertTrue(text.contains("**:**:**:**:44:55"))
    }

    func testAppEventsKeepNewestFirst() {
        AppEvents.shared.add("first")
        AppEvents.shared.add("second")
        let lines = AppEvents.shared.newestFirst
        XCTAssertTrue(lines[0].hasSuffix("second"))
        XCTAssertTrue(lines[1].hasSuffix("first"))
    }
}

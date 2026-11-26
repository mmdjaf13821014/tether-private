import XCTest
@testable import TetherCore

final class SearchTests: XCTestCase {
    func testNamesFoldPersianVariantsAndCase() {
        XCTAssertTrue(SearchText.matches("علی", names: ["علي رضايي"], numbers: []))      // Arabic yeh in the contact
        XCTAssertTrue(SearchText.matches("کریم", names: ["كريم"], numbers: []))           // Arabic kaf / yeh
        XCTAssertTrue(SearchText.matches("sara", names: ["Sára Ahmadi"], numbers: []))
        XCTAssertFalse(SearchText.matches("nima", names: ["Sara"], numbers: ["+15550101"]))
    }

    func testNumbersMatchAcrossNotations() {
        XCTAssertTrue(SearchText.matches("0912 123", names: [], numbers: ["+98 912 123 4567"]))
        XCTAssertTrue(SearchText.matches("۰۹۱۲۱۲۳", names: [], numbers: ["+989121234567"]))
        XCTAssertTrue(SearchText.matches("4567", names: [], numbers: ["09121234567"]))
        XCTAssertFalse(SearchText.matches("4568", names: [], numbers: ["09121234567"]))
        XCTAssertFalse(SearchText.matches("a", names: [], numbers: ["0912"]))
    }
}

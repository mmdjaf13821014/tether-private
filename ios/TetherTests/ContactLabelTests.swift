import XCTest
import TetherCore
@testable import Tether

/// The contact editor keeps a number's custom kind ("Satellite") from the Android phone instead of turning it into "Other".
final class ContactLabelTests: XCTestCase {
    private typealias Row = EditContactView.PhoneRow

    func testKeptCustomKindIsSentWithItsLabel() {
        let p = EditContactView.phone(from: Row(number: " +10000000003 ", type: EditContactView.custom, label: "Satellite"))
        XCTAssertEqual(p, Phone(n: "+10000000003", type: "other", label: "Satellite"))
    }

    func testStandardKindPickedForCustomClearsTheLabel() {
        let p = EditContactView.phone(from: Row(number: "+10000000003", type: "home", label: "Satellite"))
        XCTAssertEqual(p, Phone(n: "+10000000003", type: "home", label: ""))
    }

    func testPlainNumberSendsNoLabel() {
        let p = EditContactView.phone(from: Row(number: "+10000000002", type: "work"))
        XCTAssertEqual(p, Phone(n: "+10000000002", type: "work", label: nil))
        // On the wire: no "label" key at all, which the Android phone reads as "keep whatever custom kind exists".
        let json = String(decoding: try! JSONEncoder().encode(p), as: UTF8.self)
        XCTAssertFalse(json.contains("label"))
    }

    func testClearingLabelIsSentAsEmptyString() {
        let json = String(decoding: try! JSONEncoder().encode(Phone(n: "+10000000003", type: "home", label: "")), as: UTF8.self)
        XCTAssertTrue(json.contains("\"label\":\"\""))
    }
}

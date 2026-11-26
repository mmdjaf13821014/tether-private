import XCTest
@testable import TetherCore

/// Things that only look like numbers (dates, versions, addresses, amounts) are never offered as phone numbers, in a
/// text message or in text shared to "Call with"; real numbers in their usual forms still are.
final class NumberDetectionTests: XCTestCase {
    private func phones(_ s: String) -> [String] {
        MessageText.parse(s).entities.compactMap { if case .phone(let n) = $0.kind { return n } else { return nil } }
    }

    private let notNumbers = [
        "Meeting on 2024-11-23 at the office",
        "Paid on 23/11/2024, thanks",
        "Delivery 23.11.2024",
        "Due 11-23-2024",
        "Invoice date 2024/11/23",
        "Persian date ۱۴۰۳/۰۹/۰۲ is the deadline",
        "Version 1.2.3.4.5 is out",
        "Update to 17.2.1 now",
        "Server at 192.168.1.10 is down",
        "Gateway 10.0.0.138",
        "Total 1,250,000 Rials",
        "Balance 12,345,678.90",
        "Opening hours 09:00-17:30",
    ]

    private let numbers: [(String, String)] = [
        ("Call +1 (000) 000-0001 today", "+10000000001"),
        ("My number: 0912 123 4567", "09121234567"),
        ("Office 021-1234-5678", "02112345678"),
        ("Dial 555.123.4567 please", "5551234567"),
        ("Reach me at +44 7700 900123", "+447700900123"),
        ("شماره من ۰۹۱۲۱۲۳۴۵۶۷ است", "09121234567"),
    ]

    func testFindNumberSkipsDatesVersionsAddressesAmounts() {
        for t in notNumbers { XCTAssertNil(DialLink.findNumber(in: t), t) }
    }

    func testFindNumberStillFindsNumbers() {
        for (t, n) in numbers { XCTAssertEqual(DialLink.findNumber(in: t), n, t) }
        XCTAssertEqual(DialLink.findNumber(in: "Meeting 2024-11-23, call 0912 123 4567"), "09121234567")
    }

    func testMessagesDoNotUnderlineDatesVersionsAddressesAmounts() {
        for t in notNumbers { XCTAssertEqual(phones(t), [], t) }
    }

    func testMessagesStillUnderlineNumbers() {
        for (t, n) in numbers { XCTAssertEqual(phones(t), [n], t) }
    }

    func testLooksLikeNonPhoneRules() {
        for s in ["2024-11-23", "23/11/2024", "1403/09/02", "1.2.3", "192.168.1.10", "1,250,000", "12,345,678.90", "09:00-17:30"] {
            XCTAssertTrue(NumberShape.isNotAPhoneNumber(s), s)
        }
        for s in ["+1 (000) 000-0001", "0912 123 4567", "021-1234-5678", "555.123.4567", "555-12-34", "+98 21 1234 5678"] {
            XCTAssertFalse(NumberShape.isNotAPhoneNumber(s), s)
        }
    }
}

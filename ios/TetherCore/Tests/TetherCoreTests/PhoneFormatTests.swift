import XCTest
@testable import TetherCore

final class PhoneFormatTests: XCTestCase {
    private func f(_ s: String, _ r: String? = nil) -> String { PhoneFormat.format(s, region: r) }

    func testInternational() {
        XCTAssertEqual(f("+989121234567"), "+98 912 123 4567")
        XCTAssertEqual(f("+982112345678"), "+98 21 1234 5678")
        XCTAssertEqual(f("+15550100001"), "+1 (555) 010-0001")
        XCTAssertEqual(f("+10000000001"), "+1 (000) 000-0001")
        XCTAssertEqual(f("+447700900123"), "+44 7700 900123")
        XCTAssertEqual(f("+442079460000"), "+44 20 7946 0000")
        XCTAssertEqual(f("+4915123456789"), "+49 151 23456789")
        XCTAssertEqual(f("+33612345678"), "+33 6 12 34 56 78")
        XCTAssertEqual(f("+905321234567"), "+90 532 123 45 67")
        XCTAssertEqual(f("+971501234567"), "+971 50 123 4567")
        XCTAssertEqual(f("+3541234567"), "+354 123 4567")          // generic plan
        XCTAssertEqual(f("00989121234567"), "+98 912 123 4567")
        XCTAssertEqual(f("+98 912-123 4567"), "+98 912 123 4567")  // already grouped another way
    }

    func testNational() {
        XCTAssertEqual(f("09121234567", "ir"), "0912 123 4567")
        XCTAssertEqual(f("02112345678", "IR"), "021 1234 5678")
        XCTAssertEqual(f("5550100001", "us"), "(555) 010-0001")
        XCTAssertEqual(f("15550100001", "us"), "1 (555) 010-0001")
        XCTAssertEqual(f("07700900123", "gb"), "07700 900123")
        XCTAssertEqual(f("09121234567"), "09121234567")             // region unknown
        XCTAssertEqual(f("09121234567", "zz"), "09121234567")
    }

    func testPassThrough() {
        for s in ["*#06#", "*140#", "#31#0912", "Carrier", "Bank-Info", "10000000", "112", "5555",
                  "123456", "", "+", "+9", "0912", "۰۹۱۲۱۲۳۴۵۶۷", "+98 912 123 4567 ext 2"] {
            XCTAssertEqual(f(s, "ir"), s, s)
            XCTAssertEqual(f(s, "us"), s, s)
        }
        XCTAssertEqual(f("3000123456", "us"), "(300) 012-3456")
        XCTAssertEqual(f("5550100", "us"), "5550100")
        XCTAssertEqual(f("+989121234567890123"), "+98 9121234567890123")   // longer than the plan
        XCTAssertEqual(f("091212345678901", "ir"), "091212345678901")
    }

    func testAsYouType() {
        let typed = "+989121234567"
        let steps = (1...typed.count).map { f(String(typed.prefix($0))) }
        XCTAssertEqual(steps, ["+", "+9", "+98", "+98 9", "+98 91", "+98 912", "+98 912 1", "+98 912 12",
                               "+98 912 123", "+98 912 123 4", "+98 912 123 45", "+98 912 123 456", "+98 912 123 4567"])
        XCTAssertEqual(f("091212", "ir"), "0912 12")
        XCTAssertEqual(f("+1555", "ir"), "+1 555")
        XCTAssertEqual(f("+15550", "ir"), "+1 (555) 0")
    }

    func testDisplayDoesNotChangeMatching() {
        XCTAssertEqual(PhoneNumber.matchKey(f("+989121234567")), PhoneNumber.matchKey("+989121234567"))
        XCTAssertEqual(PhoneNumber.digits(f("+15550100001")), "+15550100001")
        XCTAssertTrue(SearchText.matches("0912 123", names: [], numbers: [f("+989121234567")]))
        XCTAssertTrue(SearchText.matches("(555) 010", names: [], numbers: ["+15550100001"]))
    }
}

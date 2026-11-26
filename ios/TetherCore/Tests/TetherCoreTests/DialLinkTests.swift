import XCTest
@testable import TetherCore

final class DialLinkTests: XCTestCase {
    private func n(_ s: String) -> String? { DialLink.number(fromLink: s) }

    func testTelLinks() {
        XCTAssertEqual(n("tel:+1-000-000-0001"), "+10000000001")
        XCTAssertEqual(n("tel://+1 (000) 000.0002"), "+10000000002")
        XCTAssertEqual(n("telprompt:10000000003"), "10000000003")
        XCTAssertEqual(n("TEL:%2B1%20000%20000%200004"), "+10000000004")
        XCTAssertEqual(n("tel:+10000000005;ext=123"), "+10000000005,123")
        XCTAssertEqual(n("tel:+10000000006;phone-context=example.com;isub=77"), "+10000000006")
        XCTAssertEqual(n("tel:+10000000007;postd=pp12w3"), "+10000000007,12,3")
        XCTAssertEqual(n("tel:10000000008?body=x"), "10000000008")
        XCTAssertEqual(n("tel:۰۹۱۲۰۰۰۰۰۰۱"), "09120000001")
    }

    func testServiceCodes() {
        XCTAssertEqual(n("tel:*123%23"), "*123#")
        XCTAssertEqual(n("tel:*123#"), "*123#")
        XCTAssertEqual(n("tel:%23%2331%23"), "##31#")
    }

    func testPlusOnlyInFrontAndPauses() {
        XCTAssertEqual(DialLink.dialable("00+1 0"), "0010")
        XCTAssertEqual(DialLink.dialable("+1000p,,w12,"), "+1000,12")
        XCTAssertEqual(DialLink.dialable(",123"), "123")   // a leading pause means nothing
        XCTAssertNil(DialLink.dialable("+"))
        XCTAssertNil(DialLink.dialable("abc"))
    }

    func testOwnScheme() {
        XCTAssertEqual(n("tether://dial?number=%2B10000000001"), "+10000000001")
        XCTAssertEqual(n("tether://dial?number=+10000000001"), "+10000000001")
        XCTAssertEqual(n("tether:call?n=1000+000+0002"), "10000000002")
        XCTAssertEqual(n("tether://dial/%2B10000000003"), "+10000000003")
        XCTAssertNil(n("tether://link"))
        XCTAssertNil(n("tether://dial"))
        XCTAssertNil(n("https://example.com/tel:123"))
        XCTAssertNil(n("mailto:a@example.com"))
    }

    func testURLObject() {
        XCTAssertEqual(DialLink.number(from: URL(string: "tel:+10000000001")!), "+10000000001")
        XCTAssertEqual(DialLink.number(from: URL(string: "tel:*100%23")!), "*100#")
    }

    func testFindNumberInText() {
        XCTAssertEqual(DialLink.findNumber(in: "Call me at +1 (000) 000-0001 tomorrow"), "+10000000001")
        XCTAssertEqual(DialLink.findNumber(in: "Office: 000-000-0002 ext. 45"), "0000000002,45")
        XCTAssertEqual(DialLink.findNumber(in: "<a href=\"tel:+10000000003\">us</a>"), "+10000000003")
        XCTAssertEqual(DialLink.findNumber(in: "شماره: ۰۹۱۲ ۰۰۰ ۰۰۰۴"), "09120000004")
        XCTAssertNil(DialLink.findNumber(in: "Room 12, floor 3"))
        XCTAssertNil(DialLink.findNumber(in: ""))
    }
}

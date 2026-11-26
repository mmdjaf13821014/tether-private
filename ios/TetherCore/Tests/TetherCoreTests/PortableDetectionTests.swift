import Foundation
import XCTest
@testable import TetherCore

/// The link / e-mail / phone detection used where NSDataDetector does not exist (Linux and other non-Apple
/// platforms). Runs everywhere; Apple platforms keep NSDataDetector.
final class PortableDetectionTests: XCTestCase {
    private func found(_ s: String) -> [String] {
        let ns = NSString(string: s)
        return MessageText.portableEntities(s).map { e in
            let text = ns.substring(with: e.range)
            switch e.kind {
            case .link(let u): return "link \(text) \(u.absoluteString)"
            case .email(let m): return "email \(m)"
            case .phone(let p): return "phone \(p)"
            case .address(let a): return "address \(a)"
            case .code(let c): return "code \(c)"
            }
        }
    }

    func testLinks() {
        XCTAssertEqual(found("see https://example.com/a?b=1 now"), ["link https://example.com/a?b=1 https://example.com/a?b=1"])
        XCTAssertEqual(found("go to www.example.org."), ["link www.example.org http://www.example.org"])
        XCTAssertEqual(found("pay at shaparak.ir/pay today"), ["link shaparak.ir/pay http://shaparak.ir/pay"])
        XCTAssertEqual(found("(https://example.com/x)"), ["link https://example.com/x https://example.com/x"])
    }

    func testNotLinks() {
        XCTAssertEqual(found("open file.txt or version 1.2.3"), [])
        XCTAssertEqual(found("e.g. this"), [])
    }

    func testEmailIsNotALink() {
        XCTAssertEqual(found("Mail support@example.com today"), ["email support@example.com"])
    }

    func testPhones() {
        XCTAssertEqual(found("call +1 (555) 010-0123 now"), ["phone +15550100123"])
        XCTAssertEqual(found("call 0912 123 4567"), ["phone 09121234567"])
        XCTAssertEqual(found("amount 1250000 and card 6037991234567890"), [])
    }

    /// Whatever the platform, the full parse still finds the code, the link and the number.
    func testParseUsesADetectorOnEveryPlatform() {
        let p = MessageText.parse("Code 482913. Details: https://example.com, help +1 (555) 010-0123")
        XCTAssertEqual(p.code, "482913")
        XCTAssertTrue(p.entities.contains { if case .link = $0.kind { true } else { false } })
        XCTAssertTrue(p.entities.contains { $0.kind == .phone("+15550100123") })
    }
}

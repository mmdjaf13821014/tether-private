import Foundation
import XCTest
@testable import TetherCore

/// Numbers handed in from outside (links, Shortcuts, Siri, pasted text) must be dialable as they are or refused:
/// never a number made up from text that holds none.
final class DialLinkFuzzTests: XCTestCase {
    /// The shape of everything DialLink returns: an optional leading +, then digits / * / #, pauses as single commas.
    private func isDialable(_ s: String) -> Bool {
        s.range(of: #"^\+?[0-9*#]+(,[0-9*#]+)*$"#, options: .regularExpression) != nil
            && s.split(separator: ",")[0].contains(where: \.isNumber)
    }

    func testRandomLinksReturnOnlyDialableNumbers() {
        var rng = SeededRandom(seed: 2026)
        let schemes = ["tel:", "TEL:", "telprompt:", "callto:", "tel://", "tether://dial/", "tether://dial?number=", "tether:call?n=", "sms:", "https://"]
        let atoms = ["1", "0", "9", "+", "%2B", "%20", "%23", "%2A", "*", "#", ";ext=", ";postd=", ";isub=", ";phone-context=",
                     "p", "w", ",", "-", "(", ")", ".", " ", "%", "%zz", "?x=1", "&", "=", "a", "ب", "۵", "٣", "/"]
        for _ in 0..<3000 {
            let link = schemes.randomElement(using: &rng)! + (0..<Int.random(in: 0...12, using: &rng)).map { _ in atoms.randomElement(using: &rng)! }.joined()
            guard let n = DialLink.number(fromLink: link) else { continue }
            XCTAssertTrue(isDialable(n), "\(link) -> \(n)")
            XCTAssertFalse(link.hasPrefix("sms:") || link.hasPrefix("https:"), "not a call link: \(link)")
        }
    }

    /// Text without a digit never produces a number, whatever the separators.
    func testNoDigitsNoNumber() {
        var rng = SeededRandom(seed: 5)
        let chars = Array("abcxpwPW+-()*#;,. \n\tتلفنکد:/=%")
        for _ in 0..<2000 {
            let s = String((0..<Int.random(in: 0...30, using: &rng)).map { _ in chars.randomElement(using: &rng)! })
            XCTAssertNil(DialLink.findNumber(in: s), s)
            XCTAssertNil(DialLink.number(fromLink: "tel:" + s), s)
            XCTAssertNil(DialLink.dialable(s), s)
        }
    }

    /// An extension or pause with no number before it is not a number to call (it used to dial the extension).
    func testExtensionAloneIsRefused() {
        XCTAssertNil(DialLink.number(fromLink: "tel:;ext=1"))
        XCTAssertNil(DialLink.number(fromLink: "tel:;postd=123"))
        XCTAssertEqual(DialLink.number(fromLink: "tel:p123"), "123", "a leading pause means nothing")
        XCTAssertEqual(DialLink.number(fromLink: "tel:*;ext=1"), nil)
    }

    func testPercentEscapesAndServiceCodes() {
        XCTAssertEqual(DialLink.number(fromLink: "tel:+1%2D000%2D000%2D0001"), "+10000000001")
        XCTAssertEqual(DialLink.number(fromLink: "tel:%2A%2A21%2A%2B10000000002%23"), "**21*10000000002#", "+ only in front")
        XCTAssertEqual(DialLink.number(fromLink: "tel:%zz10000000003"), "10000000003", "a broken escape is kept as text and dropped")
        XCTAssertNil(DialLink.number(fromLink: "tel:%"))
        XCTAssertEqual(DialLink.number(fromLink: "tether://dial?foo=1&number=%2B10000000004"), "+10000000004")
        XCTAssertEqual(DialLink.number(fromLink: "  tel:+10000000005  \n"), "+10000000005")
        XCTAssertEqual(DialLink.number(fromLink: "callto://+10000000006"), "+10000000006")
        XCTAssertNil(DialLink.number(fromLink: "tether://dial?number="))
        XCTAssertNil(DialLink.number(fromLink: "tether://open/10000000007"))
    }

    func testPausesCollapse() {
        XCTAssertEqual(DialLink.dialable("+10000000001pppwww;;,,2"), "+10000000001,2")
        XCTAssertEqual(DialLink.dialable("+10000000001 x 12"), "+10000000001,12")
        XCTAssertEqual(DialLink.dialable("+,1"), "+1", "a pause right after + means nothing")
        XCTAssertEqual(DialLink.dialable("*#06#"), "*#06#")
        XCTAssertNil(DialLink.dialable("*#"), "no digit")
    }

    /// The first number in a long message is found, past other digits that are too short to be one.
    func testFindNumberInLongText() {
        let filler = String(repeating: "Lorem ipsum dolor sit amet, room 12, floor 3. ", count: 200)
        XCTAssertEqual(DialLink.findNumber(in: filler + "Reach us on +1 000 000 0001 or later." + filler), "+10000000001")
        XCTAssertEqual(DialLink.findNumber(in: filler + "tel:+10000000002 and +1 000 000 0003"), "+10000000002")
        XCTAssertNil(DialLink.findNumber(in: filler))
        XCTAssertNil(DialLink.findNumber(in: "Price 1,234,567 rials"))
        XCTAssertNil(DialLink.findNumber(in: "The year 1999"))
    }
}

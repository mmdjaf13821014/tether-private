import Foundation
import XCTest
@testable import TetherCore

/// Display grouping per country, as the keypad shows it while typing. Display only: the digits never change.
final class PhoneFormatTableTests: XCTestCase {
    private func f(_ s: String, _ r: String? = nil) -> String { PhoneFormat.format(s, region: r) }

    /// Each row: the full number, its region, and the display after every typed digit.
    private let typing: [(String, String?, [String])] = [
        ("02079460000", "gb", ["0", "02", "020", "0207", "02079", "020 794", "020 7946", "020 7946 0", "020 7946 00", "020 7946 000", "020 7946 0000"]),
        ("01611234567", "gb", ["0", "01", "016", "0161", "01611", "0161 12", "0161 123", "0161 123 4", "0161 123 45", "0161 123 456", "0161 123 4567"]),
        ("015123456789", "de", ["0", "01", "015", "0151", "01512", "0151 23", "0151 234", "0151 2345", "0151 23456", "0151 234567", "0151 2345678", "0151 23456789"]),
        ("03012345678", "de", ["0", "03", "030", "0301", "03012", "030 123", "030 1234", "030 12345", "030 123456", "030 1234567", "030 12345678"]),
        ("0612345678", "fr", ["0", "06", "061", "0612", "06123", "06 12 34", "06 12 34 5", "06 12 34 56", "06 12 34 56 7", "06 12 34 56 78"]),
        ("05321234567", "tr", ["0", "05", "053", "0532", "05321", "0532 12", "0532 123", "0532 123 4", "0532 123 45", "0532 123 45 6", "0532 123 45 67"]),
        ("0501234567", "ae", ["0", "05", "050", "0501", "05012", "050 123", "050 123 4", "050 123 45", "050 123 456", "050 123 4567"]),
        ("021234567", "ae", ["0", "02", "021", "0212", "02123", "02 123 4", "02 123 45", "02 123 456", "02 123 4567"]),
        ("0412345678", "au", ["0", "04", "041", "0412", "04123", "0412 34", "0412 345", "0412 3456", "0412 345 67", "0412 345 678"]),
        ("2125550123", "ca", ["2", "21", "212", "2125", "21255", "212555", "2125550", "(212) 555-01", "(212) 555-012", "(212) 555-0123"]),
        ("+919876543210", nil, ["+", "+9", "+91", "+91 9", "+91 98", "+91 987", "+91 9876", "+91 98765", "+91 98765 4", "+91 98765 43", "+91 98765 432", "+91 98765 4321", "+91 98765 43210"]),
        ("+79161234567", nil, ["+", "+7", "+7 9", "+7 91", "+7 916", "+7 916 1", "+7 916 12", "+7 916 123", "+7 916 123 4", "+7 916 123 45", "+7 916 123 45 6", "+7 916 123 45 67"]),
        ("+12125550123", nil, ["+", "+1", "+1 2", "+1 21", "+1 212", "+1 (212) 5", "+1 (212) 55", "+1 (212) 555", "+1 (212) 555-0", "+1 (212) 555-01", "+1 (212) 555-012", "+1 (212) 555-0123"]),
        ("+34612345678", nil, ["+", "+3", "+34", "+34 6", "+34 61", "+34 612", "+34 6123", "+34 612 34", "+34 612 345", "+34 612 3456", "+34 612 345 67", "+34 612 345 678"]),
    ]

    func testAsYouTypePerCountry() {
        for (number, region, steps) in typing {
            let got = (1...number.count).map { f(String(number.prefix($0)), region) }
            XCTAssertEqual(got, steps, number)
        }
    }

    /// Formatting what is already formatted changes nothing (the keypad re-formats on every key).
    func testIdempotent() {
        var inputs: [(String, String?)] = typing.flatMap { row in (1...row.0.count).map { (String(row.0.prefix($0)), row.1) } }
        inputs += ["+989121234567", "09121234567", "+447700900123", "*#06#", "Carrier", "۰۹۱۲۱۲۳۴۵۶۷", "5550100"].map { ($0, "ir") }
        for (s, r) in inputs {
            let once = f(s, r)
            XCTAssertEqual(f(once, r), once, "\(s) \(r ?? "-")")
        }
    }

    /// The digits (and a leading +) survive formatting exactly: display never changes what is dialled.
    func testDigitsPreserved() {
        var rng = SeededRandom(seed: 99)
        let regions: [String?] = [nil, "ir", "us", "gb", "de", "fr", "tr", "ae", "in", "ru", "au", "zz"]
        for _ in 0..<2000 {
            let len = Int.random(in: 1...16, using: &rng)
            var s = (0..<len).map { _ in String(Int.random(in: 0...9, using: &rng)) }.joined()
            if Bool.random(using: &rng) { s = "+" + s }
            let r = regions.randomElement(using: &rng)!
            let out = f(s, r)
            // "00" may be shown as "+" (the international prefix), nothing else changes.
            let expected = s.hasPrefix("00") && out.hasPrefix("+") ? "+" + s.dropFirst(2) : s
            XCTAssertEqual(out.filter { $0.isNumber || $0 == "+" }, expected, "\(s) \(r ?? "-")")
            XCTAssertEqual(f(out, r), out, "idempotent: \(s)")
        }
    }

    /// Persian / Arabic-Indic digits and right-to-left text are not reformatted (they are shown as received).
    func testNonASCIIPassesThrough() {
        for s in ["۰۹۱۲۱۲۳۴۵۶۷", "+۹۸۹۱۲۱۲۳۴۵۶۷", "٠٩١٢١٢٣٤٥٦٧", "همراه اول", "+98 912 ۱۲۳ ۴۵۶۷", "\u{200F}09121234567"] {
            XCTAssertEqual(f(s, "ir"), s, s)
        }
    }

    /// The trunk prefix 8 (Russia) and numbers without the national 0 are left as typed.
    func testNationalNumbersWithoutTrunkZeroStayAsTyped() {
        XCTAssertEqual(f("89161234567", "ru"), "89161234567")
        XCTAssertEqual(f("9121234567", "ir"), "9121234567")
        XCTAssertEqual(f("0012", "ir"), "0012", "too short for an international number")
    }

    func testCountryCodes() {
        XCTAssertEqual(PhoneFormat.countryCode(of: "989121"), "98")
        XCTAssertEqual(PhoneFormat.countryCode(of: "1555"), "1")
        XCTAssertEqual(PhoneFormat.countryCode(of: "7916"), "7")
        XCTAssertEqual(PhoneFormat.countryCode(of: "971501"), "971")
        XCTAssertEqual(PhoneFormat.countryCode(of: "35"), nil, "a three-digit code is not complete yet")
        XCTAssertNil(PhoneFormat.countryCode(of: "0123"))
        XCTAssertNil(PhoneFormat.countryCode(of: ""))
        for (region, cc) in PhoneFormat.regionCodes { XCTAssertFalse(cc.isEmpty, region) }
    }
}

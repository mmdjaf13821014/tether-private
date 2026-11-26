import Foundation

/// Shapes of digit runs that are not phone numbers although a phone-number search would match them: dates (also
/// Persian calendar ones), version numbers and IP addresses, amounts with thousands separators or decimals, times.
/// Used by both number finders (text messages, and text shared to "Call with").
enum NumberShape {
    static func isNotAPhoneNumber(_ raw: String) -> Bool {
        let s = OTPExtractor.normalizeDigits(raw).trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("+") { return false }
        if s.contains(":") { return true }   // times, "09:00-17:30"
        let g = s.split(whereSeparator: { !$0.isNumber }).map(String.init)
        let seps = Set(s.filter { !$0.isNumber })
        // Dates: y-m-d, d-m-y, m-d-y with -, / or . (and Persian calendar years 13xx / 14xx).
        if g.count == 3, seps.count == 1, let sep = seps.first, "-/.".contains(sep), isDate(g) { return true }
        // Versions and IP addresses: three or more dot groups of at most three digits ("1.2.3", "192.168.1.10").
        if g.count >= 3, seps == ["."], g.allSatisfy({ $0.count <= 3 }) { return true }
        // Amounts: "1,250,000", "12,345,678.90", "1234567.89".
        if s.range(of: #"^\d{1,3}(,\d{3})+(\.\d{1,2})?$"#, options: .regularExpression) != nil { return true }
        if s.range(of: #"^\d+\.\d{1,2}$"#, options: .regularExpression) != nil { return true }
        return false
    }

    /// The text with every token that is not a phone number (see above) blanked out, same length, so a phone-number
    /// search never matches a piece of a date or an amount ("678.90" out of "12,345,678.90").
    static func maskingNonPhones(_ text: String) -> String {
        var out = text
        var tokens: [Range<String.Index>] = [], from = text.startIndex
        while from < text.endIndex, let r = text.range(of: #"\d[\d,./:\-]*\d"#, options: .regularExpression, range: from..<text.endIndex) {
            tokens.append(r); from = r.upperBound
        }
        for r in tokens.reversed() where isNotAPhoneNumber(String(text[r])) {
            out.replaceSubrange(r, with: String(repeating: " ", count: text[r].count))
        }
        return out
    }

    private static func isDate(_ g: [String]) -> Bool {
        guard let a = Int(g[0]), let b = Int(g[1]), let c = Int(g[2]) else { return false }
        func md(_ m: Int, _ d: Int) -> Bool { (1...12).contains(m) && (1...31).contains(d) }
        if g[0].count == 4 { return md(b, c) }                         // 2024-11-23, 1403/09/02
        if g[2].count == 4 || g[2].count == 2 && g[0].count <= 2 && g[1].count <= 2 {
            return md(b, a) || md(a, b)                                 // 23/11/2024, 11-23-2024, 23.11.24
        }
        return false
    }
}

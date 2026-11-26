import Foundation

/// Groups a phone number for display, like phones show it: "+98 912 345 6789", "0912 345 6789",
/// "+1 (555) 010-0001", "+44 7700 900123". Display only: the number that is dialled, stored or matched never
/// changes. Anything that is not plainly a phone number comes back untouched: short codes, USSD / MMI codes
/// (`*`, `#`), alphanumeric senders, numbers longer than their country's plan, national numbers without a known
/// region. Works on partial input too, so the keypad can format as you type.
public enum PhoneFormat {
    /// `region`: ISO 3166 country code ("ir", "US") the national numbers belong to (the SIM's country).
    public static func format(_ s: String, region: String? = nil) -> String {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.allSatisfy({ allowed.contains($0) }) else { return s }
        let raw = t.filter { $0.isASCII && ($0.isNumber || $0 == "+") }
        if raw.dropFirst().contains("+") { return s }
        var digits = raw.filter(\.isNumber)

        // International: "+…" or "00…".
        if raw.hasPrefix("+") || (digits.hasPrefix("00") && digits.count > 4) {
            if !raw.hasPrefix("+") { digits.removeFirst(2) }
            guard let cc = countryCode(of: digits) else { return s }
            let nsn = String(digits.dropFirst(cc.count))
            if nsn.isEmpty { return "+" + cc }
            guard let g = group(nsn, cc: cc) else { return "+" + cc + " " + nsn }
            return "+" + cc + " " + g
        }

        // National: needs to know the country.
        guard let region, let cc = regionCodes[region.lowercased()] else { return s }
        if cc == "1" {
            // 7-digit local numbers and short codes stay as typed; area codes start with 2–9.
            let one = digits.hasPrefix("1"), area = one ? digits.dropFirst() : Substring(digits)
            guard area.count >= 8, let a = area.first, ("2"..."9").contains(a),
                  let g = group(String(area), cc: cc) else { return s }
            return one ? "1 " + g : g
        }
        // Elsewhere a full national number starts with the trunk prefix 0; anything else is local or a service.
        guard digits.count >= 6, digits.hasPrefix("0"), !digits.hasPrefix("00"),
              let g = group(String(digits.dropFirst()), cc: cc) else { return s }
        return "0" + g
    }

    // MARK: country plans

    private static let allowed = Set("0123456789+ -().\u{00A0}")

    /// Country of a SIM / locale (lowercase ISO 3166) → calling code, for the plans below.
    static let regionCodes: [String: String] = [
        "us": "1", "ca": "1", "pr": "1", "ir": "98", "gb": "44", "de": "49", "fr": "33", "tr": "90", "ae": "971",
        "in": "91", "ru": "7", "kz": "7", "it": "39", "es": "34", "nl": "31", "au": "61",
    ]

    /// Two-digit calling codes; other codes starting 2–9 have three digits, "1" and "7" one.
    private static let twoDigit: Set<String> = [
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66", "81", "82", "84",
        "86", "90", "91", "92", "93", "94", "95", "98",
    ]

    static func countryCode(of d: String) -> String? {
        guard let f = d.first, f != "0" else { return nil }
        if f == "1" || f == "7" { return String(f) }
        if d.count >= 2, twoDigit.contains(String(d.prefix(2))) { return String(d.prefix(2)) }
        return d.count >= 3 ? String(d.prefix(3)) : nil
    }

    /// Groups a national significant number (no trunk prefix). nil: longer than the plan allows.
    static func group(_ n: String, cc: String) -> String? {
        let sizes: [Int]
        switch cc {
        case "1":
            guard n.count <= 10 else { return nil }
            let g = split(n, [3, 3, 4])
            if g.count == 1 { return g[0] }
            return "(\(g[0])) \(g[1])" + (g.count > 2 ? "-\(g[2])" : "")
        case "98": sizes = n.hasPrefix("9") ? [3, 3, 4] : [2, 4, 4]
        case "44":
            if n.hasPrefix("7") { sizes = [4, 6] }
            else if ["20", "23", "24", "28", "29"].contains(where: n.hasPrefix) { sizes = [2, 4, 4] }
            else if n.count >= 3, Array(n)[1] == "1" || Array(n)[2] == "1" { sizes = [3, 3, 4] }
            else { sizes = [4, 6] }
        case "49":
            if ["15", "16", "17"].contains(where: n.hasPrefix) { sizes = [3, 8] }
            else if ["30", "40", "69", "89"].contains(where: n.hasPrefix) { sizes = [2, 9] }
            else { sizes = [3, 9] }
        case "33": sizes = [1, 2, 2, 2, 2]
        case "90": sizes = [3, 3, 2, 2]
        case "7": sizes = [3, 3, 2, 2]
        case "91": sizes = [5, 5]
        case "971": sizes = n.hasPrefix("5") ? [2, 3, 4] : [1, 3, 4]
        default:
            // Generic: threes, the last group two to four digits.
            guard n.count <= 14 else { return nil }
            var s: [Int] = [], left = n.count
            while left > 4 { s.append(3); left -= 3 }
            s.append(left)
            sizes = s
        }
        guard n.count <= sizes.reduce(0, +) else { return nil }
        return split(n, sizes).joined(separator: " ")
    }

    private static func split(_ n: String, _ sizes: [Int]) -> [String] {
        var out: [String] = [], rest = Substring(n)
        for size in sizes where !rest.isEmpty {
            out.append(String(rest.prefix(size))); rest = rest.dropFirst(size)
        }
        return out
    }
}

import Foundation

/// Phone numbers handed to the app from outside: `tel:` / `telprompt:` links (the default calling app, Safari, Mail),
/// the app's own `tether:` scheme (Shortcuts), CallKit / Siri handles and free text (an App Intent's input).
public enum DialLink {
    /// The app's own URL scheme: `tether://dial?number=…` (also `tether:call?number=…`, `tether://dial/<number>`).
    public static let scheme = "tether"

    /// The number a link asks to call, ready to dial; nil when the link is not a call link or holds no number.
    public static func number(from url: URL) -> String? { number(fromLink: url.absoluteString) }

    public static func number(fromLink raw: String) -> String? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = s.firstIndex(of: ":") else { return nil }
        let sch = s[..<colon].lowercased()
        var rest = String(s[s.index(after: colon)...])
        switch sch {
        case "tel", "telprompt", "callto":
            if rest.hasPrefix("//") { rest.removeFirst(2) }
            return telNumber(rest)
        case scheme:
            if rest.hasPrefix("//") { rest.removeFirst(2) }
            let parts = rest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
            var path = String(parts[0]).split(separator: "/").map(String.init)
            guard let action = path.first?.lowercased(), action == "dial" || action == "call" else { return nil }
            path.removeFirst()
            if parts.count > 1 {
                for item in parts[1].split(separator: "&") {
                    let kv = item.split(separator: "=", maxSplits: 1)
                    guard kv.count == 2, ["number", "n", "tel"].contains(kv[0].lowercased()) else { continue }
                    // In a query "+" may stand for a space; "%2B" is the plus sign.
                    let v = String(kv[1])
                    return dialable(decode(v.hasPrefix("+") ? v : v.replacingOccurrences(of: "+", with: " ")))
                }
            }
            if let p = path.first { return dialable(decode(p)) }
            return nil
        default:
            return nil
        }
    }

    /// RFC 3966: `number;ext=…;isub=…;phone-context=…`, plus the common `;postd=` and a `?query` (ignored).
    static func telNumber(_ rest: String) -> String? {
        let noQuery = rest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let fields = noQuery.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
        // An extension or pause alone is no number to call.
        guard var n = fields.first.map(decode), dialable(n) != nil else { return nil }
        for f in fields.dropFirst() {
            let kv = f.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2 else { continue }
            switch kv[0].lowercased() {
            case "ext": n += "," + decode(kv[1])
            case "postd": n += "," + decode(kv[1])
            default: break   // isub, phone-context: not dialable
            }
        }
        return dialable(n)
    }

    static func decode(_ s: String) -> String { s.removingPercentEncoding ?? s }

    /// What the Android phone dials: digits (any script), `+` only in front, `*`, `#`, and `,` for a pause (from
    /// `,` `;` `p` `w`). Visual separators and other characters go. Nil when there is no digit before the pause.
    public static func dialable(_ raw: String) -> String? {
        var out = ""
        for ch in OTPExtractor.normalizeDigits(raw) {
            switch ch {
            case "0"..."9", "*", "#": out.append(ch)
            case "+": if out.isEmpty { out.append(ch) }
            case ",", ";", "p", "P", "w", "W", "x", "X": if !out.isEmpty && out != "+" && out.last != "," { out.append(",") }
            default: break
            }
        }
        while out.last == "," { out.removeLast() }
        let main = out.split(separator: ",", maxSplits: 1).first ?? ""
        guard main.contains(where: \.isNumber) else { return nil }
        return out
    }

    /// The first phone number in free text (a shared note, a selection, a pasted address card): a call link first,
    /// else a run of at least five digits with the usual separators.
    public static func findNumber(in text: String) -> String? {
        if let r = text.range(of: #"(?i)\b(tel|telprompt):[^\s<>"']+"#, options: .regularExpression),
           let n = number(fromLink: String(text[r])) { return n }
        let t = NumberShape.maskingNonPhones(OTPExtractor.normalizeDigits(text))
        // \x{00A0} (no-break space) is the ICU escape every Foundation understands; \u{00A0} parses on Apple platforms only.
        let pattern = #"\+?\(?\d[\d \-.()/\x{00A0}]{3,}\d(\s*(;|,|ext\.?|x)\s*\d+)?"#
        var search = t.startIndex..<t.endIndex
        while let r = t.range(of: pattern, options: [.regularExpression, .caseInsensitive], range: search) {
            let found = String(t[r])
            if !NumberShape.isNotAPhoneNumber(found), let n = dialable(found),
               n.split(separator: ",").first.map({ $0.filter(\.isNumber).count >= 5 }) == true {
                return n
            }
            search = r.upperBound..<t.endIndex
        }
        return nil
    }
}

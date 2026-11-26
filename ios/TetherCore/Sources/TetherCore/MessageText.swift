import Foundation

/// Rich reading of an SMS body, the way Messages treats text: links, e-mail addresses, phone numbers,
/// postal addresses and one-time codes become tappable, and light WhatsApp-style formatting
/// (`*bold*`, `_italic_`, `~strike~`, and backticks for code) is applied where a sender clearly used it.
///
/// Everything is computed on the original string; `text` is the body with formatting markers removed and
/// all ranges (UTF-16, `NSRange`) refer to `text`. Parsing is pure and cheap enough to cache per message.
public struct ParsedMessage: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        case link(URL)
        case email(String)
        case phone(String)      // dialable digits, e.g. "+989121234567"
        case address(String)
        case code(String)       // one-time code, digits only
    }
    public struct Entity: Equatable, Sendable { public var range: NSRange; public var kind: Kind }
    public struct Style: OptionSet, Equatable, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let bold = Style(rawValue: 1), italic = Style(rawValue: 2)
        public static let strike = Style(rawValue: 4), mono = Style(rawValue: 8)
    }
    public struct Styled: Equatable, Sendable { public var range: NSRange; public var style: Style }

    public var text: String
    public var entities: [Entity]
    public var styles: [Styled]
    /// The paragraph's base direction (first strong character), for alignment of the bubble text.
    public var rightToLeft: Bool
    /// In right-to-left text: stretches to keep left-to-right as a unit (links, numbers, codes, and masked
    /// numbers such as "6037**1234", which the bidi algorithm would otherwise show with the halves swapped).
    public var isolates: [NSRange] = []

    public var code: String? {
        for e in entities { if case .code(let c) = e.kind { return c } }
        return nil
    }
}

public enum MessageText {
    /// Plain text for previews and notifications: formatting markers removed, nothing else changed.
    public static func plain(_ raw: String) -> String { parse(raw, detect: false).text }

    public static func parse(_ raw: String) -> ParsedMessage { parse(raw, detect: true) }

    static func parse(_ raw: String, detect: Bool) -> ParsedMessage {
        let ns = raw as NSString
        let full = NSRange(location: 0, length: ns.length)
        var entities = detect ? detectEntities(raw) : []
        // Formatting never applies inside a link, address, number or code (snake_case_urls, *140*1# USSD…).
        let protected = entities.map(\.range)
        var styled: [(outer: NSRange, inner: NSRange, markers: [NSRange], style: ParsedMessage.Style)] = []
        for (marker, style) in [("*", ParsedMessage.Style.bold), ("_", .italic), ("~", .strike), ("`", .mono)] {
            for m in formatRegex(marker).matches(in: raw, range: full) {
                let outer = m.range, inner = m.range(at: 1)
                if protected.contains(where: { NSIntersectionRange($0, outer).length > 0 }) { continue }
                let content = ns.substring(with: inner)
                // A real emphasis span has a letter in it (not "*1*2#", "5*3*2", "__init__"-like noise)…
                if style != .mono && !content.contains(where: \.isLetter) { continue }
                // …and does not cross a span we already took (nesting inside one, *bold _italic_*, is fine).
                if styled.contains(where: { NSIntersectionRange($0.outer, outer).length > 0 &&
                    !(outer.location >= $0.inner.location && NSMaxRange(outer) <= NSMaxRange($0.inner)) }) { continue }
                let open = NSRange(location: outer.location, length: 1)
                let close = NSRange(location: NSMaxRange(outer) - 1, length: 1)
                styled.append((outer, inner, [open, close], style))
            }
        }
        // Remove the markers and shift every range.
        let markers = styled.flatMap(\.markers).sorted { $0.location < $1.location }
        func map(_ i: Int) -> Int { i - markers.filter { $0.location < i }.count }
        func mapRange(_ r: NSRange) -> NSRange {
            let a = map(r.location), b = map(NSMaxRange(r))
            return NSRange(location: a, length: max(0, b - a))
        }
        var text = raw
        if !markers.isEmpty {
            let m = NSMutableString(string: raw)
            for r in markers.reversed() { m.deleteCharacters(in: r) }
            text = m as String
        }
        entities = entities.map { ParsedMessage.Entity(range: mapRange($0.range), kind: $0.kind) }
        let styles = styled.map { ParsedMessage.Styled(range: mapRange($0.inner), style: $0.style) }
        let rtl = isRightToLeft(text)
        var isolates: [NSRange] = []
        if rtl && detect {
            isolates = entities.compactMap { if case .address = $0.kind { nil } else { $0.range } }
            let tns = text as NSString
            for m in maskedNumber.matches(in: text, range: NSRange(location: 0, length: tns.length))
                where !isolates.contains(where: { NSIntersectionRange($0, m.range).length > 0 }) {
                isolates.append(m.range)
            }
            isolates.sort { $0.location < $1.location }
        }
        return ParsedMessage(text: text, entities: entities, styles: styles, rightToLeft: rtl, isolates: isolates)
    }

    /// Digit groups joined by masks or dashes: "6037**1234", "6219-86**-****-1234", "****4821".
    private static let maskedNumber = try! NSRegularExpression(pattern: "[0-9۰-۹*]*[0-9۰-۹][0-9۰-۹*\\-]*\\*[0-9۰-۹*\\-]*[0-9۰-۹*]|\\*{2,}[0-9۰-۹]+")

    private static var regexCache: [String: NSRegularExpression] = [:]
    private static let regexLock = NSLock()
    private static func formatRegex(_ marker: String) -> NSRegularExpression {
        regexLock.lock(); defer { regexLock.unlock() }
        if let r = regexCache[marker] { return r }
        let m = NSRegularExpression.escapedPattern(for: marker)
        // Opening marker at a word start, closing marker at a word end, content on one line, not empty or padded.
        let p = "(?<![\\p{L}\\p{N}\(m)])\(m)(?=[^\\s\(m)])([^\\n\(m)]*?[^\\s\(m)])\(m)(?![\\p{L}\\p{N}\(m)])"
        let r = try! NSRegularExpression(pattern: p)
        regexCache[marker] = r
        return r
    }

    // MARK: - Entities

    #if canImport(Darwin)
    private static let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue |
        NSTextCheckingResult.CheckingType.phoneNumber.rawValue | NSTextCheckingResult.CheckingType.address.rawValue)
    #endif

    static func detectEntities(_ raw: String) -> [ParsedMessage.Entity] {
        // Persian / Arabic-Indic digits → ASCII (same UTF-16 length, so ranges carry over).
        let norm = OTPExtractor.normalizeDigits(raw)
        let ns = norm as NSString
        var found: [ParsedMessage.Entity] = []
        if let otp = OTPExtractor.find(raw) { found.append(.init(range: otp.range, kind: .code(otp.code))) }
        #if canImport(Darwin)
        for r in detector.matches(in: norm, range: NSRange(location: 0, length: ns.length)) {
            switch r.resultType {
            case .link:
                guard let url = r.url else { continue }
                if url.scheme == "mailto" {
                    found.append(.init(range: r.range, kind: .email(String(url.absoluteString.dropFirst("mailto:".count)))))
                } else if ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                    found.append(.init(range: r.range, kind: .link(url)))
                }
            case .phoneNumber:
                let s = ns.substring(with: r.range)
                if let dial = dialable(s) { found.append(.init(range: r.range, kind: .phone(dial))) }
            case .address:
                found.append(.init(range: r.range, kind: .address(ns.substring(with: r.range))))
            default:
                break
            }
        }
        #else
        found += portableEntities(norm)
        #endif
        // Iranian numbers the detector misses (no separators, Persian digits): 09xxxxxxxxx, +98 / 0098, 0xx-xxxxxxxx.
        for m in iranPhone.matches(in: norm, range: NSRange(location: 0, length: ns.length)) {
            if let dial = dialable(ns.substring(with: m.range)) { found.append(.init(range: m.range, kind: .phone(dial))) }
        }
        // One entity per stretch of text: code > link > e-mail > phone > address; longer wins within a kind.
        func rank(_ k: ParsedMessage.Kind) -> Int {
            switch k { case .code: 0; case .link: 1; case .email: 2; case .phone: 3; case .address: 4 }
        }
        var out: [ParsedMessage.Entity] = []
        for e in found.sorted(by: { rank($0.kind) != rank($1.kind) ? rank($0.kind) < rank($1.kind) : $0.range.length > $1.range.length })
            where !out.contains(where: { NSIntersectionRange($0.range, e.range).length > 0 }) {
            out.append(e)
        }
        return out.sorted { $0.range.location < $1.range.location }
    }

    // NSDataDetector exists on Apple platforms only. Elsewhere links (with a scheme, "www." or a common domain
    // ending), e-mail addresses and phone numbers are found with regular expressions; postal addresses are not
    // detected. Compiled everywhere so its tests run on every platform; Apple platforms do not use it.
    private static let portableLink = try! NSRegularExpression(pattern:
        "(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+|(?<![@\\w.-])(?:[a-z0-9-]+\\.)+(?:" + commonTLDs +
        ")\\b(?![@.\\w-]*@)(?:/[^\\s<>\"]*)?")
    /// Bare domains ("example.com/pay") count as links only with one of these endings.
    private static let commonTLDs = "com|org|net|edu|gov|io|ir|co|me|app|dev|info|biz|us|uk|de|fr|ru|in|tv|ai|xyz"
    private static let portableEmail = try! NSRegularExpression(pattern:
        "(?i)(?<![\\w.+-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?![\\w-])")
    private static let portablePhone = try! NSRegularExpression(pattern:
        "(?<![\\w+*/.])(?:\\+|\\()?\\d[\\d\\s().-]{5,}\\d(?![\\w*/])")

    static func portableEntities(_ norm: String) -> [ParsedMessage.Entity] {
        let ns = NSString(string: norm)
        let all = NSRange(location: 0, length: ns.length)
        var out: [ParsedMessage.Entity] = []
        for m in portableLink.matches(in: norm, range: all) {
            // Trailing punctuation ends a sentence, not the link.
            var r = m.range
            while r.length > 0, let last = ns.substring(with: NSRange(location: NSMaxRange(r) - 1, length: 1)).unicodeScalars.first,
                  ".,;:!?)]}'\"".unicodeScalars.contains(last) { r.length -= 1 }
            let s = ns.substring(with: r)
            let full = s.lowercased().hasPrefix("http://") || s.lowercased().hasPrefix("https://") ? s : "http://" + s
            if let url = URL(string: full), url.host != nil { out.append(.init(range: r, kind: .link(url))) }
        }
        for m in portableEmail.matches(in: norm, range: all) {
            out.append(.init(range: m.range, kind: .email(ns.substring(with: m.range))))
        }
        for m in portablePhone.matches(in: norm, range: all) {
            let r = m.range
            if let dial = dialable(ns.substring(with: r)) { out.append(.init(range: r, kind: .phone(dial))) }
        }
        return out
    }

    private static let iranPhone = try! NSRegularExpression(
        pattern: "(?<![\\d*])(?:(?:\\+|00)98[\\s-]?9\\d{2}|09\\d{2})[\\s-]?\\d{3}[\\s-]?\\d{4}(?![\\d*])|(?<![\\d*])0\\d{2,3}-\\d{7,8}(?![\\d*])")

    /// Digits a phone call can use, or nil for things that only look like numbers (amounts, card numbers, short codes).
    static func dialable(_ s: String) -> String? {
        let digits = OTPExtractor.normalizeDigits(s).filter { $0.isNumber }
        let plus = s.trimmingCharacters(in: .whitespaces).hasPrefix("+")
        guard (7...15).contains(digits.count), !NumberShape.isNotAPhoneNumber(s) else { return nil }
        // Card / account numbers are 16 digits; long digit runs without + or a leading 0 are not phone numbers.
        let first = digits.first!
        guard plus || first == "0" || s.contains("(") || s.contains("-") || s.contains(" ") || s.contains(".") else { return nil }
        if s.contains("*") { return nil }
        return (plus ? "+" : "") + digits
    }

    // MARK: - Direction

    /// First strong character decides, as in the Unicode bidi algorithm (rule P2).
    public static func isRightToLeft(_ s: String) -> Bool {
        for u in s.unicodeScalars {
            switch u.value {
            case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF: return true
            default:
                if u.properties.isAlphabetic { return false }
            }
        }
        return false
    }
}

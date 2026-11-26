import Foundation

/// Pairing code: `tether1:<S_P>.<token>.<name>` (base64url, no padding), shown as a QR code since 1.2 in its link form
/// `tether://pair?c=<S_P>.<token>.<name>`, which the iPhone's Camera app offers to open in the app. Both forms parse.
public struct PairingCode: Equatable, Sendable {
    public static let prefix = "tether1:"
    /// The link form's start (the QR code since 1.2).
    public static let linkPrefix = "tether://pair?c="
    public var peripheralKey: Data
    public var token: Data
    public var deviceName: String

    public init(peripheralKey: Data, token: Data, deviceName: String) {
        self.peripheralKey = peripheralKey
        self.token = token
        self.deviceName = deviceName
    }

    public var string: String {
        Self.prefix + peripheralKey.base64URL() + "." + token.base64URL() + "." + Data(deviceName.utf8).base64URL()
    }

    /// The link form (what the QR code holds since 1.2).
    public var link: String {
        Self.linkPrefix + peripheralKey.base64URL() + "." + token.base64URL() + "." + Data(deviceName.utf8).base64URL()
    }

    /// True for a `tether:` pairing link (`tether://pair?…` or `tether:pair?…`), valid or not.
    public static func isPairLink(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return t.hasPrefix("tether://pair?") || t.hasPrefix("tether:pair?") || t.hasPrefix("tether://pair/?")
    }

    /// Parses either form: `tether1:…` (the code, and the QR code of 1.1 phones) or `tether://pair?c=…`.
    public static func parse(_ s: String) -> PairingCode? {
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if isPairLink(trimmed) {
            guard let q = trimmed.split(separator: "?", maxSplits: 1).last else { return nil }
            for item in q.split(separator: "&") {
                let kv = item.split(separator: "=", maxSplits: 1)
                if kv.count == 2, kv[0] == "c" { return parseBody(String(kv[1]).removingPercentEncoding ?? String(kv[1])) }
            }
            return nil
        }
        guard trimmed.hasPrefix(prefix) else { return nil }
        return parseBody(String(trimmed.dropFirst(prefix.count)))
    }

    private static func parseBody(_ body: String) -> PairingCode? {
        let parts = body.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let key = Data(base64URL: String(parts[0])), key.count == 65, key.first == 0x04,
              let token = Data(base64URL: String(parts[1])), token.count == 16,
              let nameData = Data(base64URL: String(parts[2])),
              let name = String(data: nameData, encoding: .utf8) else { return nil }
        return PairingCode(peripheralKey: key, token: token, deviceName: name)
    }
}

/// G.711 µ-law, the fallback codec (8 kHz, 64 kbps).
public enum G711 {
    public static func encode(_ sample: Int16) -> UInt8 {
        let bias: Int32 = 0x84
        let clip: Int32 = 32635
        var s = Int32(sample)
        let sign: Int32 = s < 0 ? 0x80 : 0
        if s < 0 { s = -s }
        if s > clip { s = clip }
        s += bias
        var exponent: Int32 = 7
        var mask: Int32 = 0x4000
        while exponent > 0 && (s & mask) == 0 {
            exponent -= 1
            mask >>= 1
        }
        let mantissa = (s >> (exponent + 3)) & 0x0f
        return UInt8(truncatingIfNeeded: ~(sign | (exponent << 4) | mantissa))
    }

    public static func decode(_ byte: UInt8) -> Int16 {
        let u = Int32(~byte & 0xff)
        let sign = u & 0x80
        let exponent = (u >> 4) & 0x07
        let mantissa = u & 0x0f
        var s = ((mantissa << 3) + 0x84) << exponent
        s -= 0x84
        return Int16(sign != 0 ? -s : s)
    }

    public static func encode(_ pcm: [Int16]) -> Data { Data(pcm.map(encode)) }
    public static func decode(_ data: Data) -> [Int16] { data.map(decode) }
}

/// Fixed-delay jitter buffer keyed by 16-bit sequence numbers. Late frames are dropped.
public struct JitterBuffer<Frame> {
    public let targetDepth: Int
    public let maxDepth: Int
    private var frames: [UInt16: Frame] = [:]
    private var nextSeq: UInt16?
    private var primed = false
    public private(set) var dropped = 0
    public private(set) var concealed = 0

    public init(targetDepth: Int = 3, maxDepth: Int = 12) {
        self.targetDepth = targetDepth
        self.maxDepth = maxDepth
    }

    public var depth: Int { frames.count }

    /// Signed distance a - b in sequence space.
    static func distance(_ a: UInt16, _ b: UInt16) -> Int { Int(Int16(bitPattern: a &- b)) }

    public mutating func push(seq: UInt16, _ frame: Frame) {
        if let next = nextSeq, Self.distance(seq, next) < 0 {
            dropped += 1
            return
        }
        if nextSeq == nil { nextSeq = seq }
        frames[seq] = frame
        // Too far behind: skip ahead to the oldest frames we can still use (past lost ones too, or the depth grows).
        while frames.count > maxDepth, let next = nextSeq {
            frames.removeValue(forKey: next)
            nextSeq = next &+ 1
            dropped += 1
        }
    }

    /// Called once per frame period. `nil` means play silence / conceal.
    public mutating func pop() -> Frame? {
        guard let next = nextSeq else { return nil }
        if !primed {
            guard frames.count >= targetDepth else { return nil }
            primed = true
        }
        nextSeq = next &+ 1
        if let f = frames.removeValue(forKey: next) { return f }
        concealed += 1
        if frames.isEmpty { primed = false; nextSeq = nil }
        return nil
    }

    public mutating func reset() {
        frames.removeAll()
        nextSeq = nil
        primed = false
    }
}

/// Finds a one-time code in an SMS body (4–8 digits, preferring ones near "code"-like words).
public enum OTPExtractor {
    /// Words that announce a one-time code (lower-cased; Persian with and without hamza / ZWNJ variants).
    static let keywords = ["code", "otp", "pin", "password", "passcode", "verification", "one-time", "one time", "security code",
                           "کد", "رمز", "تایید", "تأیید", "پویا", "یکبار", "یک بار", "یک‌بار", "کلمه عبور", "گذرواژه"]
    /// Words that mark the number next to them as something else (money, balance, card, date, time, phone).
    static let notCode = ["ریال", "تومان", "مبلغ", "مانده", "موجودی", "برداشت", "واریز", "rial", "toman", "irr", "amount",
                          "balance", "usd", "$", "€", "کارت", "card", "حساب", "account", "تاریخ", "date", "ساعت", "time", "ثانیه",
                          "دقیقه", "seconds", "minutes", "sec", "min", "تلفن", "phone", "tel"]

    static let units = ["ریال", "تومان", "rial", "toman", "irr", "usd", "$", "€", "ثانیه", "دقیقه", "sec", "min"]

    public static func extract(_ text: String) -> String? { find(text)?.code }

    /// The one-time code in a message and where it is (UTF-16 range in the original string), or nil.
    ///
    /// Candidates are 4–8 digit runs (or 3+3 groups such as "123-456") that are not part of a longer number,
    /// a masked card ("6037**1234"), a date / time ("1403/08/30", "14:35") or a decimal / grouped amount
    /// ("1,250,000"). The message must contain a code keyword; the candidate nearest after a keyword wins,
    /// 5–6 digits preferred, and numbers next to money / card / date / time words are skipped.
    public static func find(_ text: String) -> (code: String, range: NSRange)? {
        let normalized = normalizeDigits(text)
        let lower = normalized.lowercased() as NSString
        var keywordEnds: [Int] = []
        var keywordStarts: [Int] = []
        for k in keywords {
            var search = NSRange(location: 0, length: lower.length)
            while true {
                let r = lower.range(of: k, options: [], range: search)
                if r.location == NSNotFound { break }
                keywordStarts.append(r.location); keywordEnds.append(NSMaxRange(r))
                search = NSRange(location: NSMaxRange(r), length: lower.length - NSMaxRange(r))
            }
        }
        guard !keywordEnds.isEmpty else { return nil }
        let ns = normalized as NSString
        var best: (score: Int, code: String, range: NSRange)?
        for m in candidate.matches(in: normalized, range: NSRange(location: 0, length: ns.length)) {
            let r = m.range
            let code = ns.substring(with: r).filter(\.isNumber)
            guard (4...8).contains(code.count) else { continue }
            // Context: ~14 characters on each side.
            let before = lower.substring(with: NSRange(location: max(0, r.location - 14), length: min(14, r.location)))
            let afterStart = NSMaxRange(r)
            let after = lower.substring(with: NSRange(location: afterStart, length: min(14, lower.length - afterStart)))
            // Bank texts are one field per line: context stops at a line break.
            let nearBefore = String(before.suffix(9).split(separator: "\n", omittingEmptySubsequences: false).last ?? "")
            let nearAfter = String(after.prefix(9).split(separator: "\n", omittingEmptySubsequences: false).first ?? "")
            // Labels come before a number ("مبلغ", "card", "date"); units after it ("ریال", "seconds").
            if notCode.contains(where: { nearBefore.contains($0) }) || units.contains(where: { nearAfter.contains($0) }) {
                // …unless a code keyword sits right before the number ("رمز: 1234 مبلغ" still is a code).
                if !keywordEnds.contains(where: { r.location - $0 >= 0 && r.location - $0 <= 4 }) { continue }
            }
            var score = (5...6).contains(code.count) ? 3 : 1
            if let d = keywordEnds.map({ r.location - $0 }).filter({ $0 >= 0 }).min() { score += d <= 3 ? 6 : d <= 30 ? 4 : 1 }
            if let d = keywordStarts.map({ $0 - NSMaxRange(r) }).filter({ $0 >= 0 }).min(), d <= 30 { score += 2 } // "123456 is your code"
            if best == nil || score > best!.score { best = (score, code, r) }
        }
        return best.map { ($0.code, $0.range) }
    }

    private static let candidate = try! NSRegularExpression(pattern:
        // Not glued to other digits, masks, dates, times, decimals or thousands separators.
        "(?<![\\d*/:.,،٫٬])(?<!\\d[-\\s])(?:\\d{3}[-\\s]\\d{3}|\\d{4,8})(?![\\d*/:٫٬])(?![.,،]\\d)(?![-\\s]\\d)")

    /// Converts Persian and Arabic-Indic digits to ASCII.
    public static func normalizeDigits(_ s: String) -> String {
        var out = ""
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x06F0...0x06F9: out.unicodeScalars.append(Unicode.Scalar(scalar.value - 0x06F0 + 0x30)!)
            case 0x0660...0x0669: out.unicodeScalars.append(Unicode.Scalar(scalar.value - 0x0660 + 0x30)!)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }
}

/// Normalises phone numbers for matching contacts to SMS / calls.
public enum PhoneNumber {
    public static func digits(_ s: String) -> String {
        OTPExtractor.normalizeDigits(s).filter { $0.isNumber || $0 == "+" }
    }

    /// Last 9 digits; good enough to match "+98912…" with "0912…".
    public static func matchKey(_ s: String) -> String {
        let d = digits(s).filter(\.isNumber)
        return String(d.suffix(9))
    }
}

/// Search as people type it: case, accents and Arabic / Persian letter variants (ي/ی, ك/ک, ة/ه, ZWNJ) do not
/// matter, and a number matches in any notation ("0912…" finds "+98 912…", Persian digits work).
public enum SearchText {
    public static func fold(_ s: String) -> String {
        var t = s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
        for (a, b) in [("ي", "ی"), ("ى", "ی"), ("ك", "ک"), ("ة", "ه"), ("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("\u{200C}", ""), ("\u{200F}", "")] {
            t = t.replacingOccurrences(of: a, with: b)
        }
        return t
    }

    /// Digits of a typed number without a trunk prefix ("0") or the Iranian country code, so notations meet.
    public static func numberCore(_ s: String) -> String {
        var d = PhoneNumber.digits(s).filter(\.isNumber)
        if d.hasPrefix("0098") { d.removeFirst(4) } else if d.hasPrefix("98") && d.count > 10 { d.removeFirst(2) }
        while d.hasPrefix("0") { d.removeFirst() }
        return d
    }

    /// True when `query` matches any of the names or numbers.
    public static func matches(_ query: String, names: [String], numbers: [String]) -> Bool {
        let q = fold(query.trimmingCharacters(in: .whitespaces))
        if q.isEmpty { return true }
        if names.contains(where: { fold($0).contains(q) }) { return true }
        let qd = numberCore(query)
        guard qd.count >= 2, query.contains(where: { $0.isNumber }) else { return false }
        return numbers.contains { numberCore($0).contains(qd) }
    }
}

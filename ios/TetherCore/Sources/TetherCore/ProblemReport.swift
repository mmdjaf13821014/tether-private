import Foundation

// The problem report (Link tab › Send a problem report): one text a person can attach to an issue. It has the same
// layout as the Android apps' report. Everything goes through ``PrivacyScrubber`` before it leaves the app. This is
// SPI (`@_spi(TetherApp) import TetherCore`), not part of the SDK's stable API.

/// Removes what identifies a person from log text: phone numbers, e-mail addresses, Bluetooth addresses, IPv4 addresses,
/// long digit runs (IMEI, ICCID), long keys and pairing codes. It leaves timestamps, versions, counters and percentages
/// alone ("11-25 16:20:13.123", "1.2.3", "1020399", "980 frames", "46 ms", "0.3 %").
///
/// Pure and line-oriented: no pattern matches across a newline.
@_spi(TetherApp) public enum PrivacyScrubber {
    private static func rx(_ pattern: String) -> NSRegularExpression {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: pattern, options: [])
    }

    private static let pairing = rx(#"tether1:[A-Za-z0-9_\-.=]+|tether:(?://)?pair\?[^\s"'<>]+"#)
    private static let email = rx(#"(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+"#)
    private static let uuid = rx(#"\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b"#)
    private static let mac = rx(#"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}[:\-]){4}([0-9A-Fa-f]{2}[:\-][0-9A-Fa-f]{2})(?![0-9A-Fa-f])"#)
    /// A run of at least 24 key characters. Whether it is a secret is decided in code (`looksSecret`).
    private static let longToken = rx(#"[A-Za-z0-9+/_\-]{24,}={0,2}"#)
    private static let ipv4 = rx(#"(?<![\p{Nd}.])(?:(?:25[0-5]|2[0-4]\p{Nd}|1?\p{Nd}?\p{Nd})\.){3}(?:25[0-5]|2[0-4]\p{Nd}|1?\p{Nd}?\p{Nd})(?![\p{Nd}]|\.\p{Nd})"#)
    private static let idRun = rx(#"(?<![\p{Nd}])\p{Nd}{14,}(?![\p{Nd}])"#)
    /// +98 912 345 6789, +1 (555) 010-0123, +4915112345678: a plus, then 7 to 15 digits with single separators.
    private static let international = rx(#"(?<![\p{L}\p{Nd}])\+\p{Nd}(?:[ \t\-().]{0,2}\p{Nd}){6,14}(?![\p{Nd}])"#)
    /// 09123456789, 00989123456789, 021-88776655: a leading zero and 10 to 14 digits, compact or in groups.
    private static let localCompact = rx(#"(?<![\p{Nd}.:\-/])[0\u06F0\u0660]\p{Nd}{9,13}(?![\p{Nd}])"#)
    private static let localGrouped = rx(#"(?<![\p{Nd}.:\-/])[0\u06F0\u0660]\p{Nd}{1,3}[ \t\-]\p{Nd}{3,4}(?:[ \t\-]\p{Nd}{3,4}|\p{Nd}{2,4})(?![\p{Nd}])"#)
    private static let parens = rx(#"\(\p{Nd}{3}\)[ \t]?\p{Nd}{3}[ \t\-]?\p{Nd}{4}(?![\p{Nd}])"#)
    private static let dashed = rx(#"(?<![\p{Nd}.:\-/])\p{Nd}{3}[ \t\-]\p{Nd}{3}[ \t\-]\p{Nd}{4}(?![\p{Nd}])"#)

    /// The scrubbed text. `literals` are strings the caller knows to be personal (the Android phone's name, say): each
    /// of 3 or more characters is replaced, ignoring case, by `<name>`.
    public static func scrub(_ text: String, literals: [String] = []) -> String {
        var s = text
        for lit in literals where lit.count >= 3 {
            s = s.replacingOccurrences(of: lit, with: "<name>", options: [.caseInsensitive, .literal])
        }
        s = replace(pairing, in: s, with: "<pairing code>")
        s = replace(email, in: s, with: "<email>")
        s = replace(uuid, in: s, with: "<uuid>")
        s = replace(mac, in: s) { m, ns in "**:**:**:**:" + ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "-", with: ":") }
        s = replace(longToken, in: s) { m, ns in looksSecret(ns.substring(with: m.range)) ? "<secret>" : nil }
        s = replace(ipv4, in: s) { m, ns in ns.substring(with: m.range) == "127.0.0.1" ? nil : "<ip>" }
        for r in [international, localCompact, localGrouped, parens, dashed] { s = replace(r, in: s, with: "<number>") }
        s = replace(idRun, in: s, with: "<id>")
        return s
    }

    /// A long run is a secret when it is hexadecimal, or a mix of letters and digits (a base64 key). Plain words and
    /// identifiers without digits ("AVAudioSessionCategoryPlayAndRecord", "no_audio_in_this_mode_yet") stay.
    static func looksSecret(_ t: String) -> Bool {
        let digits = t.contains { $0.isASCII && $0.isNumber }
        let letters = t.contains { $0.isASCII && $0.isLetter }
        if t.allSatisfy({ $0.isASCII && $0.isHexDigit }) { return true }
        // Separators of a path or a sentence-like id ("a-b-c", "x_y_z") with digits: only when mostly alphanumeric.
        let alnum = t.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.count
        let noSeparators = t.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
        return digits && letters && (noSeparators || t.contains("=") || t.contains("+") || t.contains("/") || Double(alnum) / Double(t.count) > 0.85)
    }

    private static func replace(_ regex: NSRegularExpression, in s: String, with template: String) -> String {
        replace(regex, in: s) { _, _ in template }
    }

    /// Replaces each match by what `make` returns (nil keeps the match).
    private static func replace(_ regex: NSRegularExpression, in s: String, make: (NSTextCheckingResult, NSString) -> String?) -> String {
        let ns = s as NSString
        let matches = regex.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length))
        if matches.isEmpty { return s }
        var out = ""
        var last = 0
        for m in matches {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            out += make(m, ns) ?? ns.substring(with: m.range)
            last = m.range.location + m.range.length
        }
        out += ns.substring(from: last)
        return out
    }
}

/// The report text: a fixed header, titled sections, then the link log and the app's events. The whole text is scrubbed
/// and capped at ``maxBytes``; when it is too long the oldest log lines go first.
@_spi(TetherApp) public struct ProblemReport {
    public struct Section: Equatable {
        public var title: String
        public var lines: [String]
        public init(_ title: String, _ lines: [String]) { self.title = title; self.lines = lines }
    }

    public static let maxBytes = 1_000_000
    /// The newest log lines kept.
    public static let maxLogLines = 600
    static let maxLineLength = 2_000

    /// "Cellular 1.2.2 (1020299) · iPhone app"
    public var app: String
    public var created: Date
    public var timeZone: TimeZone
    /// Device, Setup, Link, Calls and audio and Android phone, in this order.
    public var sections: [Section]
    /// Newest first.
    public var linkLog: [String]
    /// Newest first.
    public var events: [String]
    /// Personal strings the scrubber cannot recognise by shape (the paired phone's name).
    public var literals: [String]

    public init(app: String, created: Date = Date(), timeZone: TimeZone = .current, sections: [Section], linkLog: [String],
                events: [String], literals: [String] = []) {
        self.app = app; self.created = created; self.timeZone = timeZone; self.sections = sections
        self.linkLog = linkLog; self.events = events; self.literals = literals
    }

    public static let privacyLine = "Privacy: phone numbers, names, message text, e-mail, addresses and keys are removed. Check before posting."

    /// "2026-11-25 16:20:13 +03:30"
    public static func dateText(_ date: Date, timeZone: TimeZone) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd HH:mm:ss xxx"
        return f.string(from: date)
    }

    public func render() -> String {
        func clean(_ line: String) -> String {
            var l = PrivacyScrubber.scrub(line, literals: literals)
            if l.count > Self.maxLineLength { l = String(l.prefix(Self.maxLineLength)) + "…" }
            return l
        }
        var head: [String] = [
            "=== Tether problem report ===",
            "App: \(app)",
            "Created: \(Self.dateText(created, timeZone: timeZone))",
            Self.privacyLine,
        ]
        for s in sections {
            head.append("")
            head.append("## \(s.title)")
            head += s.lines.map(clean)
        }
        var log = Array(linkLog.prefix(Self.maxLogLines)).map(clean)
        var ev = Array(events.prefix(Self.maxLogLines)).map(clean)
        func assemble() -> String {
            var all = head
            all += ["", "## Link log"]
            all += log.isEmpty ? ["(empty)"] : log
            all += ["", "## App events"]
            all += ev.isEmpty ? ["(empty)"] : ev
            return all.joined(separator: "\n") + "\n"
        }
        var text = assemble()
        // Over the cap: the oldest lines (the end of each newest-first list) go first, the log before the events.
        while text.utf8.count > Self.maxBytes, !(log.isEmpty && ev.isEmpty) {
            let over = text.utf8.count - Self.maxBytes
            let drop = max(1, over / (2 * Self.maxLineLength))
            if log.count >= ev.count { log.removeLast(min(drop, log.count)) } else { ev.removeLast(min(drop, ev.count)) }
            text = assemble()
        }
        return text
    }

    // MARK: The Android phone's last status

    /// What the report says of the Android phone's last `status` and `net`: no SIM number or name, no client names, no
    /// proxy addresses, no extension values.
    public static func androidLines(status s: StatusBody?, net: NetState?) -> [String] {
        guard let s else { return ["No status received from the Android phone yet."] }
        var l: [String] = []
        l.append("device: \(s.device)")
        l.append("battery: \(s.battery)%\(s.charging ? " (charging)" : "")")
        l.append("default phone app: \(s.dialer ? "yes" : "no"), Wi-Fi: \(s.wifi ? "on" : "off"), wireless debugging: \(s.adbWifi ? "on" : "off")")
        for sim in s.sims {
            var f = ["SIM \(sim.slot + 1): carrier \(sim.carrier.isEmpty ? "-" : sim.carrier)",
                     "network \(sim.net ?? "-")", "signal \(sim.signal)", "service \(sim.service ?? "-")"]
            if let r = sim.roaming { f.append("roaming \(r ? "yes" : "no")") }
            if let c = sim.country { f.append("country \(c)") }
            if sim.sub == s.defaultSub { f.append("default for calls") }
            l.append(f.joined(separator: ", "))
        }
        let h = s.helper
        var hf = ["state \(h.state)", "method \(h.method ?? "-")", "capture \(h.capture ?? "-")", "up \(h.up)", "down \(h.down)"]
        if let v = h.audio { hf.append("audio \(v)") }
        if let v = h.silent { hf.append("silent \(v)") }
        if let v = h.mic { hf.append("mic \(v)") }
        if let v = h.micMute { hf.append("micMute \(v)") }
        if let v = h.micPath { hf.append("micPath \(v)") }
        if let v = h.uplink { hf.append("uplink \(v)") }
        l.append("helper: " + hf.joined(separator: ", "))
        if let c = s.clients {
            let kinds = Dictionary(grouping: c, by: \.kind).map { "\($0.value.count) \($0.key)" }.sorted().joined(separator: ", ")
            l.append("clients: \(c.count) (\(kinds))\(c.contains { $0.audio == true } ? ", call audio goes to one of them" : "")")
        }
        if let t = s.tools { l.append("tools: ussd \(t.ussd.map(String.init) ?? "-"), find \(t.find.map(String.init) ?? "-"), ringer \(t.ringer.map(String.init) ?? "-")") }
        if s.ringer != nil || s.dnd != nil { l.append("ringer: \(s.ringer ?? "-"), do not disturb access: \(s.dnd.map { $0 ? "yes" : "no" } ?? "-")") }
        if let caps = s.caps { l.append("extensions: \(caps.sorted().joined(separator: ", "))") }
        if let n = net {
            func b(_ v: Bool?) -> String { v.map { $0 ? "on" : "off" } ?? "-" }
            var nf = ["data \(b(n.data))", "hotspot \(b(n.hotspot))", "Bluetooth tethering \(b(n.bt))", "VPN \(b(n.vpn))",
                      "share VPN \(b(n.share))", "airplane \(b(n.airplane))"]
            if let r = n.rat, !r.isEmpty { nf.append("network mode " + r.sorted { $0.slot < $1.slot }.map { "SIM \($0.slot + 1) \($0.mode)" }.joined(separator: "/")) }
            if let c = n.can { nf.append("can tether: hotspot \(b(c.hotspot)), Bluetooth \(b(c.bt))") }
            l.append("net: " + nf.joined(separator: ", "))
            if let e = n.err, !e.isEmpty { l.append("net error: \(e)") }
        }
        return l
    }
}

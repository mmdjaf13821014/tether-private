import SwiftUI
import TetherCore

/// Turns a message body into display text: links, e-mail, phone numbers, addresses and one-time codes are
/// tappable, sender formatting (*bold*, _italic_, ~strike~, `code`) is applied, and in right-to-left
/// messages every left-to-right item (URL, number, code) is wrapped in a bidi isolate so it cannot pull the
/// surrounding Persian text out of order. Results are cached per message and bubble colour.
@MainActor
final class RichTextCache {
    static let shared = RichTextCache()
    struct Entry { let text: AttributedString; let parsed: ParsedMessage }
    private var cache: [String: Entry] = [:]

    /// Internal schemes our bubbles handle themselves (see `RichTextAction`).
    static let codeScheme = "tether-code", phoneScheme = "tether-phone", addressScheme = "tether-address"

    func entry(for m: Sms, mine: Bool) -> Entry {
        let key = "\(m.id)|\(mine ? 1 : 0)|\(m.body.count)|\(m.body.hashValue)"
        if let e = cache[key] { return e }
        if cache.count > 3000 { cache.removeAll(keepingCapacity: true) }
        let parsed = MessageText.parse(m.body)
        let e = Entry(text: Self.render(parsed, mine: mine), parsed: parsed)
        cache[key] = e
        return e
    }

    static func render(_ p: ParsedMessage, mine: Bool) -> AttributedString {
        var a = AttributedString(p.text)
        for s in p.styles {
            guard let r = Range(s.range, in: a) else { continue }
            var intent = a[r].inlinePresentationIntent ?? []
            if s.style.contains(.bold) { intent.insert(.stronglyEmphasized) }
            if s.style.contains(.italic) { intent.insert(.emphasized) }
            if s.style.contains(.strike) { intent.insert(.strikethrough) }
            if s.style.contains(.mono) { intent.insert(.code) }
            a[r].inlinePresentationIntent = intent
        }
        // Links read on both bubbles: white on the green (sent) one, the accent teal on the grey one.
        let linkColor: Color = mine ? .white : Color("AccentColor")
        for e in p.entities {
            guard let r = Range(e.range, in: a) else { continue }
            switch e.kind {
            case .link(let url):
                a[r].link = url
            case .email(let addr):
                a[r].link = URL(string: "mailto:\(addr)")
            case .phone(let num):
                a[r].link = URL(string: "\(phoneScheme):\(num)")
            case .address(let text):
                var c = URLComponents(); c.scheme = addressScheme; c.path = text
                a[r].link = c.url
            case .code(let code):
                a[r].link = URL(string: "\(codeScheme):\(code)")
                a[r].inlinePresentationIntent = (a[r].inlinePresentationIntent ?? []).union(.stronglyEmphasized)
                a[r].backgroundColor = mine ? Color.black.opacity(0.22) : Color("AccentColor").opacity(0.28)
                a[r].kern = 1.2
                a[r].foregroundColor = mine ? .white : .primary
                continue
            }
            a[r].foregroundColor = linkColor
            a[r].underlineStyle = .single
        }
        if p.rightToLeft {
            // Left-to-right isolate … pop isolate, inserted back to front so earlier ranges stay valid.
            for range in p.isolates.sorted(by: { $0.location > $1.location }) {
                guard let r = Range(range, in: a) else { continue }
                a.characters.insert("\u{2069}", at: r.upperBound)
                a.characters.insert("\u{2066}", at: r.lowerBound)
            }
        }
        return a
    }
}

/// What a tap on an item in a bubble does.
enum RichTextAction {
    case copyCode(String)
    case phone(String)
    case system(URL)

    init(_ url: URL) {
        switch url.scheme {
        case RichTextCache.codeScheme: self = .copyCode(String(url.absoluteString.dropFirst(RichTextCache.codeScheme.count + 1)))
        case RichTextCache.phoneScheme: self = .phone(String(url.absoluteString.dropFirst(RichTextCache.phoneScheme.count + 1)))
        case RichTextCache.addressScheme:
            var c = URLComponents(string: "https://maps.apple.com/")!
            c.queryItems = [URLQueryItem(name: "q", value: URLComponents(url: url, resolvingAgainstBaseURL: false)?.path ?? "")]
            self = .system(c.url!)
        default: self = .system(url)
        }
    }
}

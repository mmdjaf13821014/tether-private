import Foundation

/// The shared wire fixtures in protocol/fixtures (also read by the A54's ContractTest), found next to this file.
enum ContractFixtures {
    static let repo: URL = {
        var u = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { u.deleteLastPathComponent() }   // Tests/TetherCoreTests/<file> → TetherCore → ios → repo
        return u
    }()

    struct Fixture {
        let t: String
        let dir: String
        /// Variant name → body as raw JSON.
        let variants: [String: Data]
        var receivedByIPhone: Bool { dir != "client-to-phone" }
        var sentByIPhone: Bool { dir != "phone-to-client" }
    }

    /// Core types (§5). Extension fixtures (`x.…`, §7) are in `extensions()`.
    static func all() throws -> [String: Fixture] { try every().filter { !$0.key.hasPrefix("x.") } }
    static func extensions() throws -> [String: Fixture] { try every().filter { $0.key.hasPrefix("x.") } }

    static func every() throws -> [String: Fixture] {
        let dir = repo.appendingPathComponent("protocol/fixtures")
        var out: [String: Fixture] = [:]
        for f in try FileManager.default.contentsOfDirectory(atPath: dir.path) where f.hasSuffix(".json") {
            let o = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent(f))) as! [String: Any]
            let v = o["variants"] as! [String: Any]
            let t = o["t"] as! String
            out[t] = Fixture(t: t, dir: o["dir"] as! String,
                             variants: try v.mapValues { try JSONSerialization.data(withJSONObject: $0) })
        }
        return out
    }

    /// A body wrapped in the §5 envelope, as it arrives off the link.
    static func envelope(_ t: String, _ body: Data) -> Data {
        Data("{\"t\":\"\(t)\",\"id\":5,\"b\":".utf8) + body + Data("}".utf8)
    }

    static func text(_ path: String) throws -> String {
        try String(contentsOf: repo.appendingPathComponent(path), encoding: .utf8)
    }

    /// Every quoted type in a source block between `start` and the next line equal to `end`.
    static func quotedTypes(in source: String, from start: String, to end: String) -> Set<String> {
        guard let r = source.range(of: start) else { return [] }
        let rest = source[r.upperBound...]
        let block = rest[..<(rest.range(of: end)?.lowerBound ?? rest.endIndex)]
        let re = try! NSRegularExpression(pattern: "\"([a-z]+(\\.[a-z]+)?)\"")
        let s = String(block)
        return Set(re.matches(in: s, range: NSRange(s.startIndex..., in: s)).map { String(s[Range($0.range(at: 1), in: s)!]) })
    }

    /// Types in PROTOCOL.md §5: the message table's first column and every type named after an arrow.
    static func protocolTypes() throws -> Set<String> {
        let md = try text("protocol/PROTOCOL.md")
        let sec = md.components(separatedBy: "## 5. Control messages")[1].components(separatedBy: "\n### 5.1")[0]
        let tick = try! NSRegularExpression(pattern: "`([a-z.]+)`"), arrow = try! NSRegularExpression(pattern: "→ `([a-z.]+)`")
        var out = Set<String>()
        for line in sec.split(separator: "\n").map(String.init) where line.hasPrefix("| `") {
            let first = line.split(separator: "|")[0].description
            for m in tick.matches(in: first, range: NSRange(first.startIndex..., in: first)) { out.insert(String(first[Range(m.range(at: 1), in: first)!])) }
            for m in arrow.matches(in: line, range: NSRange(line.startIndex..., in: line)) { out.insert(String(line[Range(m.range(at: 1), in: line)!])) }
        }
        return out
    }
}

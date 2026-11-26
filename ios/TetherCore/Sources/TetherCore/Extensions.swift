import Foundation

/// Extension messages (protocol §7): the reserved namespace, the capability fields and the limits. Extension types are
/// not core types, so they never appear in `MsgType`. Same values as Kotlin's `Ext`.
public enum Ext {
    /// Every extension type starts with this; no core type does.
    public static let prefix = "x."
    /// Extensions shipped in this repository; third parties use a reverse-DNS name they control.
    public static let builtInPrefix = "x.tether."
    public static let maxId = 48, maxType = 64, maxCaps = 64
    /// The whole encoded envelope of one extension message; while call audio runs on the link, `maxBytesInCall`.
    public static let maxBytes = 16_384, maxBytesInCall = 2_048, maxStatusBytes = 1_024
    /// Extension messages per second per link and direction, with bursts up to `burst`.
    public static let ratePerSecond = 20, burst = 40
    /// Failures in a row after which an extension is switched off for the rest of the process.
    public static let maxFailures = 10
    /// `error.code` values for refused extension messages.
    public static let errUnknown = "unknown-type", errTooLarge = "too-large", errFailed = "failed"
    /// The built-in diagnostics extension (§7.9).
    public static let echo = "x.tether.echo"
}

/// Any JSON value: bodies an extension reads loosely, and the `status.ext` fields.
public enum JSONValue: Codable, Equatable, Hashable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): if n == n.rounded(), abs(n) < 9e15 { try c.encode(Int64(n)) } else { try c.encode(n) }
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public subscript(key: String) -> JSONValue? { if case .object(let o) = self { return o[key] } else { return nil } }
    public var bool: Bool? { if case .bool(let b) = self { return b } else { return nil } }
    public var number: Double? { if case .number(let n) = self { return n } else { return nil } }
    public var string: String? { if case .string(let s) = self { return s } else { return nil } }
}

/// The link as the extensions see it (the app implements it over `TetherLink`; tests record what is sent).
public protocol ExtensionPort: AnyObject {
    /// "l2cap", "gatt" or "memory".
    var transport: String { get }
    /// Call audio runs on the link now: extension messages are kept small (protocol §7.3).
    var inCall: Bool { get }
    /// Sends one control message; the request id when `expectReply` (0 otherwise), nil when it was not sent.
    func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int?
}

/// One extension message as it came off the link: its header and the whole envelope (decode the body with `body`).
public struct ExtensionMessage {
    public let header: MessageHeader
    public let payload: Data
    public init(header: MessageHeader, payload: Data) { self.header = header; self.payload = payload }
    public var t: String { header.t }
    public var id: Int? { header.id }
    public var re: Int? { header.re }
    public func body<B: Decodable>(_ type: B.Type) throws -> B { try Control.body(type, from: payload) }
}

/// What an extension may ask of the app that hosts it.
public protocol ExtensionHost: AnyObject {
    /// The link to the Android phone when a common version was agreed, else nil.
    var session: ExtensionSession? { get }
    func log(_ line: String)
}

/// A feature added on top of the link without changing the core apps (protocol §7, docs/EXTENDING.md).
///
/// The registry calls an extension only for its own message types, never for a core one. Report a failure by throwing:
/// it is logged and answered, and the session goes on (a trap, such as a failed force unwrap, still ends the app).
/// Calls arrive on the thread that drives the registry (the app's main thread).
public protocol TetherExtension: AnyObject {
    /// `x.<reverse-dns>`, for example `x.com.example.battery`; `x.tether.*` is reserved for this repository.
    var id: String { get }
    /// Major versions this build speaks; announced as `<id>/<major>` in `caps`.
    var versions: [Int] { get }
    /// The message types it handles and sends: `id` itself or `<id>.<name>`.
    var types: Set<String> { get }
    func start(_ host: ExtensionHost)
    func stop()
    /// The Android phone agreed a common version (`up`) or it went (link down, withdrawn, extension switched off).
    func linkChanged(_ session: ExtensionSession, up: Bool)
    /// A message of one of `types`, or an `error` answering a request this extension sent.
    func received(_ message: ExtensionMessage, on session: ExtensionSession) throws
}

public extension TetherExtension {
    var versions: [Int] { [1] }
    var types: Set<String> { [id] }
    func start(_ host: ExtensionHost) {}
    func stop() {}
    func linkChanged(_ session: ExtensionSession, up: Bool) {}
}

/// An extension's handle on the link: everything it sends goes through the registry's checks.
public final class ExtensionSession {
    private unowned let registry: ExtensionRegistry
    public let owner: TetherExtension
    let port: ExtensionPort
    init(_ registry: ExtensionRegistry, _ e: TetherExtension, _ port: ExtensionPort) { self.registry = registry; owner = e; self.port = port }

    public var transport: String { port.transport }
    /// The agreed major version, or nil.
    public var version: Int? { registry.version(of: owner) }

    /// An unsolicited message; only when a version was agreed. False when not sent.
    @discardableResult
    public func send(_ type: String, _ body: any Encodable) -> Bool { registry.out(owner, type, body, request: false, re: nil) != nil }
    /// A request (with `id`); the answer, or an `error` with `re`, arrives in `received`. The id, nil when not sent.
    public func request(_ type: String, _ body: any Encodable) -> Int? { registry.out(owner, type, body, request: true, re: nil) }
    /// The answer to `message` (which carried an `id`); allowed even when the peer announced nothing.
    @discardableResult
    public func reply(to message: ExtensionMessage, _ body: any Encodable, type: String? = nil) -> Bool {
        guard let id = message.id else { return false }
        return registry.out(owner, type ?? message.t, body, request: false, re: id) != nil
    }
}

public enum ExtensionError: Error, Equatable { case invalid(String) }

/// Routes extension messages (protocol §7) between the link and the registered extensions, and keeps them from
/// touching anything else: core types are never routed here or sent from here, sizes and rates are capped, a thrown
/// error is logged and answered, an extension failing `Ext.maxFailures` times in a row is switched off. One link
/// (a client has one Android phone). Not thread-safe: drive it from one thread (the app's main thread).
public final class ExtensionRegistry {
    private struct Bucket {
        var tokens = Double(Ext.burst)
        var at: TimeInterval?
        mutating func take(_ now: TimeInterval) -> Bool {
            if let at { tokens = min(Double(Ext.burst), tokens + max(0, now - at) * Double(Ext.ratePerSecond)) }
            at = now
            guard tokens >= 1 else { return false }
            tokens -= 1
            return true
        }
    }

    private final class Host: ExtensionHost {
        weak var registry: ExtensionRegistry?
        let e: TetherExtension
        init(_ r: ExtensionRegistry, _ e: TetherExtension) { registry = r; self.e = e }
        var session: ExtensionSession? { registry?.session(for: e) }
        func log(_ line: String) { registry?.log("\(e.id): \(line)") }
    }

    private let log: (String) -> Void
    private let clock: () -> TimeInterval
    /// Called when caps change (an extension switched off): the app sends a fresh sync.
    public var onCapsChanged: (() -> Void)?
    public private(set) var extensions: [TetherExtension] = []
    private var byType: [String: TetherExtension] = [:]
    private var disabled = Set<String>()
    private var failures: [String: Int] = [:]
    private var port: ExtensionPort?
    private var peer: [String: Set<Int>] = [:]
    private var agreed: [String: Int] = [:]
    private var pending: [Int: String] = [:]
    private var pendingOrder: [Int] = []
    private var inBucket = Bucket(), outBucket = Bucket()
    private var started = false
    public private(set) var counts: [String: Int] = [:]

    public init(log: @escaping (String) -> Void = { _ in }, clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.log = log; self.clock = clock
    }

    // MARK: names

    private static func isLabel(_ s: Substring) -> Bool {
        guard let f = s.unicodeScalars.first, f.isLowerAlnum else { return false }
        return s.unicodeScalars.allSatisfy { $0.isLowerAlnum || $0 == "-" }
    }

    public static func isExtensionType(_ t: String) -> Bool { t.hasPrefix(Ext.prefix) }

    public static func validId(_ id: String) -> Bool {
        guard id.count <= Ext.maxId, id.hasPrefix(Ext.prefix) else { return false }
        let labels = id.dropFirst(2).split(separator: ".", omittingEmptySubsequences: false)
        return labels.count >= 2 && labels.allSatisfy(isLabel)
    }

    public static func validType(_ t: String, id: String) -> Bool {
        guard t.count <= Ext.maxType else { return false }
        if t == id { return true }
        guard t.hasPrefix(id + ".") else { return false }
        return isLabel(t.dropFirst(id.count + 1))
    }

    /// "<id>/<major>" → (id, major), or nil (core capabilities included: none defined yet).
    public static func parseCap(_ s: String) -> (id: String, major: Int)? {
        guard s.count <= Ext.maxType, let slash = s.lastIndex(of: "/") else { return nil }
        let id = String(s[..<slash])
        guard let v = Int(s[s.index(after: slash)...]), v >= 1, validId(id) else { return nil }
        return (id, v)
    }

    public static func parseCaps(_ caps: [String]?) -> [String: Set<Int>] {
        var out: [String: Set<Int>] = [:]
        for c in (caps ?? []).prefix(Ext.maxCaps) { if let (id, v) = parseCap(c) { out[id, default: []].insert(v) } }
        return out
    }

    public static func agree(_ ours: [Int], _ theirs: Set<Int>?) -> Int? { theirs.flatMap { t in ours.filter { t.contains($0) }.max() } }

    // MARK: registration

    /// Adds an extension. Throws when its id or a type is not in the extension namespace, names a core type, does not
    /// belong to its id or is already taken: an extension can never replace a core message or another extension.
    public func register(_ e: TetherExtension) throws {
        guard Self.validId(e.id) else { throw ExtensionError.invalid("extension id '\(e.id)' is not x.<reverse-dns> (protocol §7.1)") }
        guard !e.versions.isEmpty, e.versions.allSatisfy({ $0 >= 1 }) else { throw ExtensionError.invalid("\(e.id): versions must be ≥ 1") }
        guard !e.types.isEmpty else { throw ExtensionError.invalid("\(e.id): no message types") }
        guard !extensions.contains(where: { $0.id == e.id }) else { throw ExtensionError.invalid("\(e.id) is already registered") }
        for t in e.types {
            guard Self.isExtensionType(t) else { throw ExtensionError.invalid("\(e.id): '\(t)' is a core type; extensions cannot handle or replace core messages") }
            guard Self.validType(t, id: e.id) else { throw ExtensionError.invalid("\(e.id): '\(t)' is not \(e.id) or \(e.id).<name>") }
            guard byType[t] == nil else { throw ExtensionError.invalid("\(e.id): '\(t)' is already handled by \(byType[t]!.id)") }
        }
        extensions.append(e)
        for t in e.types { byType[t] = e }
        if started { e.start(Host(self, e)) }
    }

    public func isEnabled(_ id: String) -> Bool { extensions.contains { $0.id == id } && !disabled.contains(id) }

    /// What this side announces in `caps`: every enabled extension's majors.
    public var caps: [String] {
        extensions.filter { !disabled.contains($0.id) }.flatMap { e in Set(e.versions).sorted().map { "\(e.id)/\($0)" } }
    }

    public func start() {
        guard !started else { return }
        started = true
        for e in extensions { e.start(Host(self, e)) }
    }

    public func stop() {
        guard started else { return }
        started = false
        for e in extensions { e.stop() }
    }

    // MARK: link

    public func linkUp(_ port: ExtensionPort) {
        if self.port != nil { linkDown() }
        self.port = port
        peer = [:]; agreed = [:]; pending = [:]; pendingOrder = []
        inBucket = Bucket(); outBucket = Bucket()
    }

    public func linkDown() {
        guard let port else { return }
        let was = agreed
        agreed = [:]; peer = [:]; pending = [:]; pendingOrder = []
        for e in extensions where was[e.id] != nil { e.linkChanged(ExtensionSession(self, e, port), up: false) }
        self.port = nil
    }

    /// The Android phone's `caps` (from its latest status; absent: none). Calls `linkChanged` for every change.
    public func peerCaps(_ caps: [String]?) {
        peer = Self.parseCaps(caps)
        guard let port else { return }
        for e in extensions {
            let v = disabled.contains(e.id) ? nil : Self.agree(e.versions, peer[e.id])
            let old = agreed[e.id]
            agreed[e.id] = v
            if (old == nil) != (v == nil) { e.linkChanged(ExtensionSession(self, e, port), up: v != nil) }
        }
    }

    public func version(of e: TetherExtension) -> Int? { disabled.contains(e.id) ? nil : agreed[e.id] }
    public func session(for e: TetherExtension) -> ExtensionSession? {
        guard let port, version(of: e) != nil else { return nil }
        return ExtensionSession(self, e, port)
    }
    public var peerCapabilities: [String: Set<Int>] { peer }

    private var limit: Int { port?.inCall == true ? Ext.maxBytesInCall : Ext.maxBytes }

    // MARK: routing

    /// Called by the app's router for a message no core handler took (and for every `error`). True when it was an
    /// extension's: handled, refused or dropped. False for core types: the router carries on as before.
    public func route(_ h: MessageHeader, _ payload: Data) -> Bool {
        guard let port else { return Self.isExtensionType(h.t) }
        if h.t == MsgType.error {
            guard let re = h.re, let owner = takePending(re) else { return false }
            if let e = extensions.first(where: { $0.id == owner && !disabled.contains($0.id) }) { deliver(e, h, payload, port) }
            return true
        }
        guard Self.isExtensionType(h.t) else { return false }
        guard inBucket.take(clock()) else { count("dropped.rate"); return true }
        guard payload.count <= limit else { refuse(h, Ext.errTooLarge, port); return true }
        if let re = h.re { _ = takePending(re) }
        guard let e = byType[h.t], !disabled.contains(e.id) else { refuse(h, Ext.errUnknown, port); return true }
        deliver(e, h, payload, port)
        return true
    }

    private func takePending(_ re: Int) -> String? {
        guard let owner = pending.removeValue(forKey: re) else { return nil }
        pendingOrder.removeAll { $0 == re }
        return owner
    }

    private func refuse(_ h: MessageHeader, _ code: String, _ port: ExtensionPort) {
        count("refused.\(code)")
        if code != Ext.errUnknown { log("refused \(h.t): \(code)") }
        guard let id = h.id else { return }
        _ = port.send(MsgType.error, ErrorBody(msg: "\(h.t): \(code)", t: h.t, code: code), expectReply: false, re: id)
    }

    private func deliver(_ e: TetherExtension, _ h: MessageHeader, _ payload: Data, _ port: ExtensionPort) {
        do {
            try e.received(ExtensionMessage(header: h, payload: payload), on: ExtensionSession(self, e, port))
            failures[e.id] = nil
            count("handled")
        } catch {
            let n = (failures[e.id] ?? 0) + 1
            failures[e.id] = n
            log("extension \(e.id) failed on \(h.t): \(error)")
            if h.id != nil && h.t != MsgType.error { refuse(h, Ext.errFailed, port) }
            if n >= Ext.maxFailures { disable(e, port) }
        }
    }

    private func disable(_ e: TetherExtension, _ port: ExtensionPort) {
        guard disabled.insert(e.id).inserted else { return }
        log("extension \(e.id) switched off after \(Ext.maxFailures) failures in a row")
        if agreed.removeValue(forKey: e.id) != nil { e.linkChanged(ExtensionSession(self, e, port), up: false) }
        e.stop()
        onCapsChanged?()
    }

    /// Checks and sends for `ExtensionSession`: the id for a request, 0 for another message, nil when not sent.
    func out(_ e: TetherExtension, _ type: String, _ body: any Encodable, request: Bool, re: Int?) -> Int? {
        guard e.types.contains(type) else {
            log("\(e.id) may only send its own types, not '\(type)'"); count("unsent.not-own"); return nil
        }
        guard !disabled.contains(e.id), let port else { return nil }
        if re == nil && version(of: e) == nil { count("unsent.no-version"); return nil }
        guard outBucket.take(clock()) else { count("unsent.rate"); log("\(e.id): \(type) not sent (rate limit)"); return nil }
        // The size with the largest id the link may give it.
        guard let size = try? Control.encode(type, body, id: request ? Int(Int32.max) : nil, re: re).count, size <= limit else {
            count("unsent.too-large"); log("\(e.id): \(type) not sent (over \(limit) bytes)"); return nil
        }
        guard let id = port.send(type, body, expectReply: request, re: re) else { return nil }
        if request {
            pending[id] = e.id; pendingOrder.append(id)
            if pendingOrder.count > 256 { pending[pendingOrder.removeFirst()] = nil }
        }
        count("sent")
        return id
    }

    private func count(_ what: String) { counts[what, default: 0] += 1 }

    /// One line for logs and the Debug developer row: extensions, what the phone announced, counters (no contents).
    public var summary: String {
        let ext = extensions.map { "\($0.id) \(disabled.contains($0.id) ? "off" : agreed[$0.id].map { "v\($0)" } ?? "not agreed")" }
        return "extensions: \(ext.joined(separator: ", ")); phone caps \(peer.count); " + counts.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ")
    }
}

private extension Unicode.Scalar {
    var isLowerAlnum: Bool { ("a"..."z").contains(self) || ("0"..."9").contains(self) }
}

import Foundation

/// `x.tether.echo` (protocol §7.9): link diagnostics. A request `{ts, data?}` with an `id` is answered with
/// `{ts, data?, at, via}`: the request's timestamp and data back, the responder's clock and its transport. The asker
/// gets the round trip and an estimate of the clock offset. Touches nothing but the link.
public final class EchoExtension: TetherExtension {
    public struct Request: Codable, Equatable, Sendable {
        public var ts: Int64?; public var data: String?
        public init(ts: Int64, data: String? = nil) { self.ts = ts; self.data = data }
    }
    public struct Reply: Codable, Equatable, Sendable {
        public var ts: Int64; public var data: String?; public var at: Int64; public var via: String
        public init(ts: Int64, data: String? = nil, at: Int64, via: String) { self.ts = ts; self.data = data; self.at = at; self.via = via }
    }
    /// One answer: round trip, the responder's clock minus ours at the midpoint, its transport, the payload size.
    public struct Result: Equatable, Sendable {
        public var rttMs: Int64; public var offsetMs: Int64; public var via: String; public var bytes: Int; public var error: String?
        public init(rttMs: Int64, offsetMs: Int64, via: String, bytes: Int, error: String? = nil) {
            self.rttMs = rttMs; self.offsetMs = offsetMs; self.via = via; self.bytes = bytes; self.error = error
        }
    }

    public let id = Ext.echo
    public private(set) var last: Result?
    /// Every answer, on the registry's thread.
    public var onResult: ((Result) -> Void)?
    private let clock: () -> Int64

    public init(clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) { self.clock = clock }

    /// Asks the Android phone; the result arrives in `onResult`. Nil when not sent (no link, or the phone has no echo).
    public func ping(_ session: ExtensionSession, data: String? = nil) -> Int? {
        session.request(id, Request(ts: clock(), data: data))
    }

    public func received(_ message: ExtensionMessage, on session: ExtensionSession) throws {
        if message.re != nil {
            let r: Result
            if message.t == MsgType.error {
                let e = try message.body(ErrorBody.self)
                r = Result(rttMs: -1, offsetMs: 0, via: "", bytes: 0, error: e.code ?? e.msg)
            } else {
                let a = try message.body(Reply.self)
                let rtt = max(0, clock() - a.ts)
                r = Result(rttMs: rtt, offsetMs: a.at - (a.ts + rtt / 2), via: a.via, bytes: a.data?.utf8.count ?? 0)
            }
            last = r
            onResult?(r)
            return
        }
        guard message.id != nil else { return }   // neither a request nor an answer
        let q = try message.body(Request.self)
        session.reply(to: message, Reply(ts: q.ts ?? 0, data: q.data, at: clock(), via: session.transport))
    }
}

import XCTest
@testable import TetherCore

/// The iPhone's extension registry (protocol §7): names, routing, isolation, capabilities, limits. Same rules as the
/// Kotlin registry's tests.
final class ExtensionRegistryTests: XCTestCase {
    final class Port: ExtensionPort {
        var transport = "memory", inCall = false, up = true
        var sent: [(t: String, id: Int?, re: Int?, body: Data)] = []
        private var next = 10
        func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int? {
            guard up else { return nil }
            let id = expectReply ? { next += 1; return next }() : nil
            let env = try! Control.encode(type, body, id: id, re: re)
            sent.append((type, id, re, env))
            return id ?? 0
        }
        func lastBody() -> [String: Any] { (try! JSONSerialization.jsonObject(with: sent.last!.body) as! [String: Any])["b"] as! [String: Any] }
    }

    final class Probe: TetherExtension {
        let id: String, versions: [Int], types: Set<String>
        var got: [ExtensionMessage] = []
        var links: [Bool] = []
        var throwing = false, stopped = false
        var host: ExtensionHost?
        init(id: String = "x.org.example.probe", versions: [Int] = [1], types: Set<String>? = nil) {
            self.id = id; self.versions = versions; self.types = types ?? [id, id + ".ask"]
        }
        struct Boom: Error {}
        func start(_ host: ExtensionHost) { self.host = host }
        func stop() { stopped = true }
        func linkChanged(_ session: ExtensionSession, up: Bool) { links.append(up) }
        func received(_ message: ExtensionMessage, on session: ExtensionSession) throws {
            if throwing { throw Boom() }
            got.append(message)
        }
    }

    private var now: TimeInterval = 0
    private var logs: [String] = []
    private lazy var reg = ExtensionRegistry(log: { [unowned self] in self.logs.append($0) }, clock: { [unowned self] in self.now })
    private let port = Port()

    private func env(_ t: String, _ body: String = "{}", id: Int? = nil, re: Int? = nil) -> (MessageHeader, Data) {
        var s = "{\"t\":\"\(t)\""
        if let id { s += ",\"id\":\(id)" }
        if let re { s += ",\"re\":\(re)" }
        let d = Data((s + ",\"b\":\(body)}").utf8)
        return (try! Control.header(d), d)
    }
    @discardableResult private func route(_ m: (MessageHeader, Data)) -> Bool { reg.route(m.0, m.1) }
    private func registered(_ p: Probe = Probe()) -> Probe { try! reg.register(p); reg.start(); reg.linkUp(port); return p }

    // MARK: names

    func testNamesFollowTheReservedNamespace() {
        for ok in ["x.tether.echo", "x.com.example.battery", "x.io.dev-team.cam2"] { XCTAssertTrue(ExtensionRegistry.validId(ok), ok) }
        for bad in ["echo", "x.echo", "x.", "x.Com.example", "y.com.example", "x.com..example", "x.com.example.", "x.com.-a",
                    "x." + String(repeating: "a", count: 60) + ".b"] { XCTAssertFalse(ExtensionRegistry.validId(bad), bad) }
        XCTAssertTrue(ExtensionRegistry.validType("x.com.example.battery.low", id: "x.com.example.battery"))
        XCTAssertFalse(ExtensionRegistry.validType("x.com.example.battery.low.now", id: "x.com.example.battery"))
        XCTAssertFalse(ExtensionRegistry.validType("x.com.example.batteryx", id: "x.com.example.battery"))
    }

    func testCoreTypesCanNeverBeClaimed() throws {
        let core = ContractFixtures.quotedTypes(in: try ContractFixtures.text("ios/TetherCore/Sources/TetherCore/Messages.swift"),
                                                from: "public enum MsgType {", to: "\n}")
        XCTAssertGreaterThan(core.count, 40)
        for t in core {
            XCTAssertThrowsError(try reg.register(Probe(types: ["x.org.example.probe", t])), t) { e in
                guard case ExtensionError.invalid(let why) = e else { return XCTFail() }
                XCTAssertTrue(why.contains("core type"), why)
            }
        }
        XCTAssertThrowsError(try reg.register(Probe(id: "sms")))
        XCTAssertTrue(reg.extensions.isEmpty)
    }

    func testATypeBelongsToOneExtensionOnly() throws {
        try reg.register(Probe())
        XCTAssertThrowsError(try reg.register(Probe()))
        XCTAssertThrowsError(try reg.register(Probe(id: "x.org.example.other", types: ["x.org.example.probe.ask"])))
        XCTAssertThrowsError(try reg.register(Probe(id: "x.org.example.v", versions: [])))
    }

    // MARK: routing

    func testCoreMessagesPassThroughUntouched() {
        let p = registered()
        for t in [MsgType.sync, MsgType.status, MsgType.callState, MsgType.smsNew, "future.core"] { XCTAssertFalse(route(env(t, id: 4)), t) }
        XCTAssertFalse(route(env(MsgType.error, #"{"msg":"x"}"#, re: 4)), "an error answering no extension request is the app's")
        XCTAssertTrue(p.got.isEmpty); XCTAssertTrue(port.sent.isEmpty)
    }

    func testExtensionMessagesReachTheirExtension() {
        let p = registered()
        XCTAssertTrue(route(env("x.org.example.probe.ask", #"{"q":1}"#, id: 7)))
        XCTAssertEqual(p.got.map(\.t), ["x.org.example.probe.ask"])
        struct Q: Decodable { var q: Int }
        XCTAssertEqual(try p.got[0].body(Q.self).q, 1)
        XCTAssertTrue(port.sent.isEmpty)
    }

    func testUnknownExtensionTypeWithIdIsAnsweredAndWithoutIdIgnored() {
        registered()
        XCTAssertTrue(route(env("x.com.example.nobody", id: 12)))
        XCTAssertEqual(port.sent.last?.t, MsgType.error); XCTAssertEqual(port.sent.last?.re, 12); XCTAssertNil(port.sent.last?.id)
        XCTAssertEqual(port.lastBody()["code"] as? String, Ext.errUnknown); XCTAssertEqual(port.lastBody()["t"] as? String, "x.com.example.nobody")
        XCTAssertTrue(route(env("x.com.example.nobody")))
        XCTAssertEqual(port.sent.count, 1)
    }

    func testAThrowingExtensionIsIsolatedAnsweredAndSwitchedOffAfterTenFailures() throws {
        let p = registered(); p.throwing = true
        let ok = Probe(id: "x.org.example.fine"); try reg.register(ok)
        var capsChanged = 0; reg.onCapsChanged = { capsChanged += 1 }
        reg.peerCaps(["x.org.example.probe/1", "x.org.example.fine/1"])
        for i in 1...9 { XCTAssertTrue(route(env(p.id, id: i))) }
        XCTAssertEqual(port.lastBody()["code"] as? String, Ext.errFailed)
        XCTAssertTrue(reg.isEnabled(p.id))
        route(env(p.id, id: 10))
        XCTAssertFalse(reg.isEnabled(p.id)); XCTAssertTrue(p.stopped)
        XCTAssertFalse(reg.caps.contains("x.org.example.probe/1")); XCTAssertEqual(capsChanged, 1)
        XCTAssertEqual(p.links, [true, false])
        route(env(p.id, id: 11))
        XCTAssertEqual(port.lastBody()["code"] as? String, Ext.errUnknown)
        route(env(ok.id, id: 12)); XCTAssertEqual(ok.got.count, 1)
        XCTAssertFalse(route(env(MsgType.ping, id: 13)))
        XCTAssertTrue(logs.contains { $0.contains("switched off") })
    }

    // MARK: capabilities

    func testCapsAnnounceEveryMajorAndTheHighestCommonOneIsAgreed() {
        let p = registered(Probe(versions: [2, 1]))
        XCTAssertEqual(reg.caps, ["x.org.example.probe/1", "x.org.example.probe/2"])
        XCTAssertNil(reg.version(of: p))
        reg.peerCaps(["x.org.example.probe/1", "x.org.example.probe/3", "core-feature", "bad/x", "x.Bad/1"])
        XCTAssertEqual(reg.version(of: p), 1); XCTAssertEqual(p.links, [true])
        reg.peerCaps(["x.org.example.probe/2", "x.org.example.probe/1"])
        XCTAssertEqual(reg.version(of: p), 2); XCTAssertEqual(p.links, [true])
        reg.peerCaps(nil)
        XCTAssertNil(reg.version(of: p)); XCTAssertEqual(p.links, [true, false])
        XCTAssertNil(p.host?.session)
    }

    func testCapsAreCappedAndMalformedOnesIgnored() {
        XCTAssertEqual(ExtensionRegistry.parseCaps((1...100).map { "x.org.example.e\($0)/1" }).count, Ext.maxCaps)
        XCTAssertNil(ExtensionRegistry.parseCap("x.org.example/0"))
        XCTAssertNil(ExtensionRegistry.parseCap("/1"))
        XCTAssertEqual(ExtensionRegistry.parseCap("x.org.example/3")?.major, 3)
    }

    func testUnsolicitedOnlyWhenAgreedRepliesAlways() throws {
        let p = registered()
        XCTAssertNil(reg.session(for: p))
        reg.peerCaps(["x.org.example.probe/1"])
        let s = try XCTUnwrap(reg.session(for: p))
        XCTAssertTrue(s.send(p.id, ["a": 1]))
        reg.peerCaps([])
        XCTAssertFalse(s.send(p.id, ["a": 1]))
        let req = ExtensionMessage(header: env("x.org.example.probe.ask", id: 3).0, payload: env("x.org.example.probe.ask", id: 3).1)
        XCTAssertTrue(s.reply(to: req, ["ok": true]))
        XCTAssertEqual(port.sent.last?.re, 3)
        let noId = ExtensionMessage(header: env(p.id).0, payload: env(p.id).1)
        XCTAssertFalse(s.reply(to: noId, ["ok": true]))
    }

    func testAnExtensionCanSendOnlyItsOwnTypes() throws {
        let p = registered()
        reg.peerCaps(["x.org.example.probe/1"])
        let s = try XCTUnwrap(reg.session(for: p))
        for t in [MsgType.smsSend, MsgType.callDial, MsgType.unpair, "x.org.example.other"] { XCTAssertFalse(s.send(t, Empty()), t) }
        XCTAssertTrue(port.sent.isEmpty)
    }

    func testRequestsGetTheirAnswerAndErrorsByRe() throws {
        let p = registered()
        reg.peerCaps(["x.org.example.probe/1"])
        let id = try XCTUnwrap(reg.session(for: p)?.request("x.org.example.probe.ask", Empty()))
        XCTAssertTrue(route(env(MsgType.error, #"{"msg":"no"}"#, re: id)))
        XCTAssertEqual(p.got.map(\.t), [MsgType.error])
        XCTAssertFalse(route(env(MsgType.error, #"{"msg":"no"}"#, re: id)), "answered once only")
    }

    func testLinkDownEndsTheSession() {
        let p = registered()
        reg.peerCaps(["x.org.example.probe/1"])
        reg.linkDown()
        XCTAssertEqual(p.links, [true, false])
        XCTAssertNil(reg.session(for: p))
        XCTAssertTrue(route(env(p.id, id: 1)), "no link: an extension type is still not the app's")
        XCTAssertTrue(port.sent.isEmpty)
    }

    // MARK: limits

    func testOversizedMessagesAreRefusedBothWays() throws {
        let p = registered()
        reg.peerCaps(["x.org.example.probe/1"])
        let big = String(repeating: "a", count: Ext.maxBytes)
        XCTAssertTrue(route(env(p.id, "{\"data\":\"\(big)\"}", id: 5)))
        XCTAssertTrue(p.got.isEmpty); XCTAssertEqual(port.lastBody()["code"] as? String, Ext.errTooLarge)
        port.sent = []
        let s = try XCTUnwrap(reg.session(for: p))
        XCTAssertFalse(s.send(p.id, ["data": big])); XCTAssertTrue(port.sent.isEmpty)
        port.inCall = true
        let mid = String(repeating: "a", count: 3000)
        XCTAssertFalse(s.send(p.id, ["data": mid]))
        port.inCall = false
        XCTAssertTrue(s.send(p.id, ["data": mid]))
    }

    func testFloodsAreDroppedAtTheRateLimit() throws {
        let p = registered()
        for _ in 0..<100 { route(env(p.id)) }
        XCTAssertEqual(p.got.count, Ext.burst)
        now += 1
        for _ in 0..<100 { route(env(p.id)) }
        XCTAssertEqual(p.got.count, Ext.burst + Ext.ratePerSecond)
        XCTAssertFalse(route(env(MsgType.ping)))
        reg.peerCaps(["x.org.example.probe/1"])
        let s = try XCTUnwrap(reg.session(for: p))
        XCTAssertEqual((0..<100).filter { _ in s.send(p.id, Empty()) }.count, Ext.burst)
    }

    func testSummaryHasCountsNotContents() {
        let p = registered()
        route(env(p.id, #"{"secret":"+10000000001"}"#))
        XCTAssertTrue(reg.summary.contains("handled 1"))
        XCTAssertFalse(reg.summary.contains("+10000000001"))
    }

    // MARK: echo, JSON values

    func testEchoAnswersAndMeasures() throws {
        var clock: Int64 = 1_000
        let echo = EchoExtension(clock: { clock })
        try reg.register(echo); reg.start(); reg.linkUp(port)
        route(env(Ext.echo, #"{"ts":900,"data":"abc"}"#, id: 2))
        XCTAssertEqual(port.sent.last?.re, 2)
        XCTAssertEqual(port.lastBody()["at"] as? Int, 1000); XCTAssertEqual(port.lastBody()["data"] as? String, "abc")
        XCTAssertEqual(port.lastBody()["via"] as? String, "memory")
        route(env(Ext.echo, #"{"ts":900}"#))
        XCTAssertEqual(port.sent.count, 1, "no id: not answered")
        reg.peerCaps(["x.tether.echo/1"])
        let id = try XCTUnwrap(echo.ping(try XCTUnwrap(reg.session(for: echo))))
        clock = 1_040
        route(env(Ext.echo, #"{"ts":1000,"at":5020,"via":"l2cap"}"#, re: id))
        XCTAssertEqual(echo.last, EchoExtension.Result(rttMs: 40, offsetMs: 4_000, via: "l2cap", bytes: 0))
        let id2 = try XCTUnwrap(echo.ping(try XCTUnwrap(reg.session(for: echo))))
        route(env(MsgType.error, #"{"msg":"x","code":"failed"}"#, re: id2))
        XCTAssertEqual(echo.last?.error, "failed")
    }

    func testJSONValueRoundTrips() throws {
        let text = #"{"a":[1,2.5,true,null,"s"],"b":{"c":false}}"#
        let v = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
        XCTAssertEqual(v["b"]?["c"]?.bool, false)
        XCTAssertEqual(v["a"], .array([.number(1), .number(2.5), .bool(true), .null, .string("s")]))
        let e = JSONEncoder(); e.outputFormatting = .sortedKeys
        XCTAssertEqual(String(data: try e.encode(v), encoding: .utf8), text)
    }
}

import XCTest
@testable import TetherCore

/// The iPhone's half of the wire contract (protocol/fixtures, shared with the A54's ContractTest).
final class ContractTests: XCTestCase {
    private struct Codec {
        let decode: (Data) throws -> Any
        /// Decode the body, then encode it again as the iPhone would send it (body only).
        let reencode: (Data) throws -> Data
    }
    private static func c<B: Codable>(_: B.Type) -> Codec {
        Codec(decode: { try JSONDecoder().decode(B.self, from: $0) },
              reencode: { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]; return try e.encode(try JSONDecoder().decode(B.self, from: $0)) })
    }

    /// The Swift body for every type that crosses the link.
    private let bodies: [String: Codec] = [
        "ping": c(PingBody.self), "pong": c(PingBody.self), "status": c(StatusBody.self), "sync": c(SyncBody.self),
        "sms.batch": c(SmsBatch.self), "sms.new": c(SmsNew.self), "sms.send": c(SmsSend.self), "sms.read": c(SmsRead.self),
        "sms.unread": c(SmsUnread.self), "sms.result": c(SmsResult.self),
        "contacts.batch": c(ContactsBatch.self), "contact.add": c(ContactAdd.self), "contact.update": c(ContactUpdate.self),
        "contact.star": c(ContactStar.self), "contact.result": c(ContactResult.self),
        "calls.batch": c(CallsBatch.self), "call.state": c(CallInfo.self), "call.list": c(CallList.self),
        "call.answer": c(CallRef.self), "call.reject": c(CallRef.self), "call.end": c(CallRef.self), "call.take": c(CallRef.self),
        "call.hold": c(CallHold.self), "call.mute": c(CallMute.self), "call.dtmf": c(CallDtmf.self),
        "call.dial": c(CallDial.self), "call.dialed": c(OkResult.self),
        "audio.start": c(AudioStart.self), "audio.stop": c(Empty.self), "audio.stats": c(AudioStats.self), "audio.set": c(AudioSet.self),
        "selftest": c(Empty.self), "selftest.result": c(SelftestResult.self),
        "net.get": c(Empty.self), "net.set": c(NetSet.self), "net.state": c(NetState.self),
        "sms.delete": c(SmsDelete.self), "sms.deleted": c(SmsDeleted.self),
        "calls.delete": c(CallsDelete.self), "calls.deleted": c(CallsDeleted.self),
        "lists.set": c(ListsSet.self), "lists": c(BlockLists.self), "sync.ids": c(SyncIds.self),
        "ussd.send": c(UssdSend.self), "ussd.result": c(UssdResult.self),
        "phone.find": c(PhoneFind.self), "phone.finding": c(PhoneFinding.self),
        "ringer.set": c(RingerSet.self), "ringer.result": c(RingerResult.self),
        "unpair": c(Empty.self), "error": c(ErrorBody.self),
    ]

    /// iPhone-sent variants TetherCore's body cannot produce, with the reason.
    private let notProducedByCore: [String: String] = [
        "sync/full": "codecs and client are added by the app's SyncBodyV1 (AppModel.swift), not TetherCore's SyncBody",
        "sync/android": "sent by the Android client, not the iPhone",
    ]

    func testFixturesAreFoundFromTheSourceTree() throws {
        XCTAssertGreaterThan(try ContractFixtures.all().count, 40)
    }

    func testMsgTypeKotlinTAndProtocolAgree() throws {
        let swift = ContractFixtures.quotedTypes(in: try ContractFixtures.text("ios/TetherCore/Sources/TetherCore/Messages.swift"),
                                                 from: "public enum MsgType {", to: "\n}")
        let kotlin = ContractFixtures.quotedTypes(in: try ContractFixtures.text("android/core/src/main/java/app/tether/core/Messages.kt"),
                                                  from: "object T {", to: "\n}")
        let spec = try ContractFixtures.protocolTypes()
        XCTAssertEqual(swift, kotlin, "MsgType and Kotlin T differ")
        XCTAssertEqual(swift, spec, "MsgType and PROTOCOL.md §5 differ")
        XCTAssertEqual(Set(try ContractFixtures.all().keys), swift, "every type has a fixture, every fixture a type")
        XCTAssertEqual(Set(bodies.keys), swift, "every type has its Swift body here")
    }

    func testEveryBodyTheIPhoneReceivesDecodes() throws {
        for f in try ContractFixtures.all().values where f.receivedByIPhone {
            let codec = try XCTUnwrap(bodies[f.t], f.t)
            for (name, body) in f.variants {
                let env = ContractFixtures.envelope(f.t, body)
                XCTAssertEqual(try Control.header(env).t, f.t)
                XCTAssertNoThrow(try codec.decode(body), "\(f.t)/\(name) does not decode")
            }
        }
    }

    func testEveryBodyTheIPhoneSendsEncodesAsTheFixture() throws {
        for f in try ContractFixtures.all().values where f.sentByIPhone {
            let codec = try XCTUnwrap(bodies[f.t], f.t)
            for (name, body) in f.variants where notProducedByCore["\(f.t)/\(name)"] == nil {
                let out = try JSONSerialization.jsonObject(with: try codec.reencode(body)) as! NSDictionary
                let want = try JSONSerialization.jsonObject(with: body) as! NSDictionary
                XCTAssertEqual(out, want, "\(f.t)/\(name): the iPhone would send something else")
                XCTAssertEqual(Self.shape(out), Self.shape(want), "\(f.t)/\(name): JSON types differ")
            }
        }
    }

    /// Key → JSON kind, nested, so a Bool sent as a number (or the reverse) fails.
    private static func shape(_ v: Any) -> String {
        switch v {
        case let d as NSDictionary: return "{" + (d.allKeys as! [String]).sorted().map { "\($0):\(shape(d[$0]!))" }.joined(separator: ",") + "}"
        case let a as NSArray: return "[" + a.map { shape($0) }.joined(separator: ",") + "]"
        #if canImport(Darwin)
        case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? "bool" : "num"
        #else
        // swift-corelibs-foundation gives JSON booleans as Bool and numbers as NSNumber.
        case let b where type(of: b) == Bool.self: return "bool"
        case is NSNumber: return "num"
        #endif
        case is String: return "str"
        default: return "?"
        }
    }

    private func body<B: Decodable>(_ t: String, _ variant: String, as _: B.Type) throws -> B {
        let f = try XCTUnwrap(try ContractFixtures.all()[t], t)
        return try JSONDecoder().decode(B.self, from: try XCTUnwrap(f.variants[variant], "\(t)/\(variant)"))
    }

    // Optional and newer fields: present from current A54 builds, absent from older ones; both must decode.

    func testStatusOptionalFields() throws {
        let s = try body("status", "full", as: StatusBody.self)
        XCTAssertEqual(s.sims[0].country, "us"); XCTAssertEqual(s.sims[1].net, "5G"); XCTAssertEqual(s.sims[1].service, "none")
        XCTAssertEqual(s.helper.silent, "earpiece"); XCTAssertEqual(s.tools?.ussd, true)
        XCTAssertEqual(s.helper.mic, "auto"); XCTAssertEqual(s.helper.micPath, "off");
        XCTAssertEqual(s.helper.audio, "full"); XCTAssertFalse(s.helper.audioUnavailable)
        XCTAssertEqual(s.helper.micMute, false); XCTAssertEqual(s.helper.uplink, "ok"); XCTAssertEqual(s.helper.capture, "intercept"); XCTAssertEqual(s.tools?.ringer, true)
        XCTAssertEqual(s.ringer, "vibrate"); XCTAssertEqual(s.dnd, false)
        XCTAssertEqual(s.clients?.map(\.kind), ["iphone", "android"]); XCTAssertTrue(s.clients![0].isSelf)
        let o = try body("status", "old", as: StatusBody.self)
        XCTAssertNil(o.helper.mic, "an Android phone before the mic setting: the old switch"); XCTAssertNil(o.helper.micPath)
        XCTAssertNil(o.helper.audio); XCTAssertFalse(o.helper.audioUnavailable, "no field: nothing to warn about")
        XCTAssertNil(o.sims[0].country); XCTAssertNil(o.sims[0].net); XCTAssertTrue(o.sims[0].hasService)
        XCTAssertNil(o.helper.micMute, "an Android phone without the mic setting: no switch"); XCTAssertNil(o.helper.uplink); XCTAssertNil(o.helper.capture)
        XCTAssertNil(o.helper.silent); XCTAssertNil(o.tools); XCTAssertNil(o.ringer); XCTAssertNil(o.dnd); XCTAssertNil(o.clients)
        XCTAssertNil(o.defaultSub)
        XCTAssertEqual(s.caps?.first, "x.tether.echo/1"); XCTAssertEqual(s.ext?["x.com.example.battery"]?["at"]?.number, 15)
        XCTAssertNil(o.caps, "an Android phone from before extensions"); XCTAssertNil(o.ext)
        // An A54 newer than this iPhone may add fields: they are ignored, not fatal.
        XCTAssertEqual(try body("status", "unknown", as: StatusBody.self), s)
    }

    func testCallStateOptionalFields() throws {
        let c = try body("call.state", "full", as: CallInfo.self)
        XCTAssertTrue(c.isElsewhere); XCTAssertEqual(c.at, "Test iPad"); XCTAssertEqual(c.since, 1763800000000)
        let o = try body("call.state", "old", as: CallInfo.self)
        XCTAssertFalse(o.isElsewhere); XCTAssertNil(o.at); XCTAssertNil(o.sub)
        XCTAssertEqual(try body("call.state", "unknown", as: CallInfo.self), o)
        XCTAssertTrue(try body("call.state", "ended", as: CallInfo.self).isEnded)
    }

    func testAudioAndToolsOptionalFields() throws {
        XCTAssertEqual(try body("audio.start", "full", as: AudioStart.self).bitrate, 32000)
        XCTAssertNil(try body("audio.start", "old", as: AudioStart.self).bitrate)
        XCTAssertEqual(try body("audio.stats", "full", as: AudioStats.self).summary, "frames 250/250")
        XCTAssertEqual(try body("ussd.result", "full", as: UssdResult.self).text, "Balance: 10.00")
        XCTAssertEqual(try body("phone.finding", "full", as: PhoneFinding.self).err, "no alarm stream")
        XCTAssertEqual(try body("ringer.result", "old", as: RingerResult.self).mode, "vibrate")
        XCTAssertNil(try body("ringer.result", "old", as: RingerResult.self).err)
        XCTAssertEqual(try body("audio.set", "off", as: AudioSet.self).micMute, false)
        XCTAssertEqual(try body("audio.set", "mic", as: AudioSet.self).mic, "auto"); XCTAssertNil(try body("audio.set", "mic", as: AudioSet.self).micMute)
        let st = try body("selftest.result", "full", as: SelftestResult.self)
        XCTAssertEqual(st.methods.first?.name, "call-audio"); XCTAssertEqual(st.methods.last?.ok, false)
    }

    func testDataOptionalFields() throws {
        let u = try body("sms.unread", "old", as: SmsUnread.self)
        XCTAssertNil(u.all); XCTAssertEqual(u.isRead(1), true); XCTAssertEqual(u.isRead(120), false)
        XCTAssertNil(try body("sms.new", "old", as: SmsNew.self).item.sub)
        let batch = try body("contacts.batch", "full", as: ContactsBatch.self)
        XCTAssertNotNil(batch.items[0].photo); XCTAssertNil(batch.items[1].photo); XCTAssertEqual(batch.items[0].phones[1].label, "Studio")
        XCTAssertEqual(try body("sms.batch", "unknown", as: SmsBatch.self).items.count, 1)
        XCTAssertNil(try body("sync.ids", "old", as: SyncIds.self).more)
        XCTAssertNil(try body("lists", "old", as: BlockLists.self).system)
        XCTAssertTrue(SyncIds.contains(try body("sync.ids", "full", as: SyncIds.self).sms, 50))
    }

    func testNetStateOptionalFields() throws {
        let n = try body("net.state", "full", as: NetState.self)
        XCTAssertEqual(n.mode(slot: 1), "5G"); XCTAssertEqual(n.can?.bt, true); XCTAssertEqual(n.proxy?.addrs.first?.kind, "bt")
        let o = try body("net.state", "old", as: NetState.self)
        XCTAssertNil(o.can); XCTAssertNil(o.rat); XCTAssertNil(o.proxy); XCTAssertNil(o.airplane)
        XCTAssertEqual(try body("net.state", "error", as: NetState.self).err, "helper did not answer")
    }

    // MARK: extensions (§7)

    private final class Port: ExtensionPort {
        var transport = "l2cap", inCall = false
        var sent: [Data] = []
        func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int? {
            sent.append(try! Control.encode(type, body, id: expectReply ? 99 : nil, re: re)); return expectReply ? 99 : 0
        }
        func lastBody() throws -> NSDictionary {
            (try JSONSerialization.jsonObject(with: sent.last!) as! NSDictionary)["b"] as! NSDictionary
        }
    }

    func testExtensionFixturesAreNamedInSection7AndNotInSection5() throws {
        let ext = try ContractFixtures.extensions()
        XCTAssertNotNil(ext["x.tether.echo"]); XCTAssertNotNil(ext["x.org.example.unknown"])
        let sec7 = try ContractFixtures.text("protocol/PROTOCOL.md").components(separatedBy: "## 7. Extensions")[1]
        for t in ext.keys {
            XCTAssertTrue(ExtensionRegistry.validId(t), t)
            XCTAssertTrue(sec7.contains("`\(t)`"), "\(t) is not named in §7")
        }
        XCTAssertTrue(try ContractFixtures.protocolTypes().allSatisfy { !$0.hasPrefix("x.") })
    }

    func testEchoAnswersEveryRequestFixtureLikeTheReplyFixture() throws {
        let f = try XCTUnwrap(try ContractFixtures.extensions()["x.tether.echo"])
        let reply = try JSONSerialization.jsonObject(with: try XCTUnwrap(f.variants["reply"])) as! NSDictionary
        let at = (reply["at"] as! NSNumber).int64Value
        for (name, body) in f.variants where !name.hasPrefix("reply") {
            let reg = ExtensionRegistry(), port = Port()
            try reg.register(EchoExtension(clock: { at })); reg.start(); reg.linkUp(port)
            let env = ContractFixtures.envelope("x.tether.echo", body)
            XCTAssertTrue(reg.route(try Control.header(env), env))
            let out = try port.lastBody()
            let want = reply.mutableCopy() as! NSMutableDictionary   // portable: Linux has no NSDictionary-to-Swift bridging
            if (try JSONSerialization.jsonObject(with: body) as! NSDictionary)["data"] == nil { want.removeObject(forKey: "data") }
            XCTAssertEqual(out, want, "echo/\(name)")
            XCTAssertEqual(Self.shape(out), Self.shape(want))
            XCTAssertEqual(try Control.header(port.sent.last!).re, 5)
        }
        for (name, body) in f.variants where name.hasPrefix("reply") {
            XCTAssertNoThrow(try JSONDecoder().decode(EchoExtension.Reply.self, from: body), name)
        }
    }

    func testUnknownExtensionIsRefusedWithTheErrorFixture() throws {
        let f = try XCTUnwrap(try ContractFixtures.extensions()["x.org.example.unknown"])
        let reg = ExtensionRegistry(), port = Port()
        try reg.register(EchoExtension()); reg.start(); reg.linkUp(port)
        let env = ContractFixtures.envelope(f.t, try XCTUnwrap(f.variants["request"]))
        XCTAssertTrue(reg.route(try Control.header(env), env))
        let want = try JSONSerialization.jsonObject(with: try XCTUnwrap(try ContractFixtures.all()["error"]?.variants["extension"])) as! NSDictionary
        XCTAssertEqual(try port.lastBody(), want)
        XCTAssertEqual(try Control.header(port.sent.last!).t, MsgType.error)
        let noId = Data("{\"t\":\"\(f.t)\",\"b\":{}}".utf8)
        XCTAssertTrue(reg.route(try Control.header(noId), noId))
        XCTAssertEqual(port.sent.count, 1, "without an id: ignored")
    }

    func testCapsInSyncAndStatusFixturesAgreeEcho() throws {
        struct Caps: Decodable { var caps: [String]? }
        let echo = EchoExtension()
        let reg = ExtensionRegistry(); try reg.register(echo); reg.linkUp(Port())
        for (t, v) in [("sync", "full"), ("sync", "android"), ("status", "full"), ("status", "unknown")] {
            reg.peerCaps(try body(t, v, as: Caps.self).caps)
            XCTAssertEqual(reg.version(of: echo), 1, "\(t)/\(v)")
        }
        for t in ["sync", "status"] {
            reg.peerCaps(try body(t, "old", as: Caps.self).caps)
            XCTAssertNil(reg.version(of: echo), "\(t)/old")
        }
        XCTAssertEqual(reg.caps, ["x.tether.echo/1"])
    }
}

import XCTest
import TetherCore
@testable import Tether

/// The iPhone app's hook for extensions (protocol §7): `received` hands only extension types and errors answering an
/// extension's request to the registry, after every core type; the status carries the phone's caps, the sync ours.
@MainActor
final class ExtensionRouterTests: XCTestCase {
    final class Port: ExtensionPort {
        var transport = "memory", inCall = false
        var sent: [(t: String, re: Int?, body: [String: Any])] = []
        private var next = 100
        func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int? {
            let env = try! Control.encode(type, body, re: re)
            let b = (try! JSONSerialization.jsonObject(with: env) as! [String: Any])["b"] as! [String: Any]
            sent.append((type, re, b))
            if expectReply { next += 1; return next }
            return 0
        }
    }

    var m: AppModel { AppModel.shared }
    private var port = Port()

    override func setUp() async throws {
        await MainActor.run {
            AppModel.shared.resetForTests()
            port = Port()
            AppModel.shared.extensions.linkUp(port)
        }
    }

    private func raw(_ t: String, _ body: String, id: Int? = nil, re: Int? = nil) -> (MessageHeader, Data) {
        var s = "{\"t\":\"\(t)\""
        if let id { s += ",\"id\":\(id)" }
        if let re { s += ",\"re\":\(re)" }
        let d = Data((s + ",\"b\":\(body)}").utf8)
        return (try! Control.header(d), d)
    }

    private let minimalStatus = #""device":"A","battery":10,"charging":false,"sims":[],"dialer":false,"helper":{"state":"off","up":false,"down":false},"wifi":false,"adbWifi":false"#

    func testEchoRequestFromThePhoneIsAnswered() {
        feed(m, raw("x.tether.echo", #"{"ts":5,"data":"hi"}"#, id: 9))
        XCTAssertEqual(port.sent.count, 1)
        XCTAssertEqual(port.sent[0].t, "x.tether.echo"); XCTAssertEqual(port.sent[0].re, 9)
        XCTAssertEqual(port.sent[0].body["data"] as? String, "hi"); XCTAssertEqual(port.sent[0].body["via"] as? String, "memory")
    }

    func testStatusCapsDecideTheAgreement() {
        feed(m, raw(MsgType.status, "{\(minimalStatus),\"caps\":[\"x.tether.echo/1\"]}"))
        XCTAssertEqual(m.extensions.version(of: m.echo), 1)
        XCTAssertEqual(m.status?.caps, ["x.tether.echo/1"])
        XCTAssertNotNil(m.extensions.session(for: m.echo))
        feed(m, raw(MsgType.status, "{\(minimalStatus)}"))
        XCTAssertNil(m.extensions.version(of: m.echo), "an Android build without extensions")
        XCTAssertEqual(m.status?.battery, 10, "the status itself is read as before")
    }

    func testCoreMessagesNeverReachTheExtensions() {
        let before = m.extensions.counts
        feed(m, raw(MsgType.status, "{\(minimalStatus)}", id: 3))
        feed(m, wire(MsgType.smsBatch, SmsBatchW(items: [sms(1, thread: 1)], more: false)))
        feed(m, raw("future.core.type", "{}", id: 4))
        XCTAssertTrue(port.sent.isEmpty, "no core message is answered by the registry")
        XCTAssertEqual(m.data.sms.count, 1)
        XCTAssertEqual(m.extensions.counts, before, "the registry saw none of them")
    }

    func testUnknownExtensionTypeIsRefusedOnlyWhenItHasAnId() {
        feed(m, raw("x.org.example.unknown", #"{"q":1}"#, id: 21))
        XCTAssertEqual(port.sent.last?.t, MsgType.error); XCTAssertEqual(port.sent.last?.re, 21)
        XCTAssertEqual(port.sent.last?.body["code"] as? String, "unknown-type")
        feed(m, raw("x.org.example.unknown", #"{"q":1}"#))
        XCTAssertEqual(port.sent.count, 1)
        XCTAssertNil(m.lastError)
    }

    func testAnErrorAnsweringAnExtensionRequestGoesToIt() throws {
        feed(m, raw(MsgType.status, "{\(minimalStatus),\"caps\":[\"x.tether.echo/1\"]}"))
        m.echoTest()
        XCTAssertEqual(port.sent.last?.t, "x.tether.echo")
        XCTAssertEqual(m.extensionLine, "Waiting for the Android phone…")
        feed(m, raw(MsgType.error, #"{"msg":"x.tether.echo: failed","t":"x.tether.echo","code":"failed"}"#, re: 101))
        XCTAssertEqual(m.extensionLine, "Refused: failed")
        XCTAssertNil(m.lastError)
        m.echoTest()
        feed(m, raw("x.tether.echo", #"{"ts":1,"at":2,"via":"l2cap"}"#, re: 102))
        XCTAssertTrue(m.extensionLine?.contains("via l2cap") == true, m.extensionLine ?? "")
    }

    func testEchoTestWithoutAgreementSaysSo() {
        m.echoTest()
        XCTAssertTrue(port.sent.isEmpty)
        XCTAssertTrue(m.extensionLine?.contains("does not speak") == true)
    }

    func testSyncCarriesCapsOnlyWhenThereAreSome() throws {
        let hello = ClientHello(kind: "iphone", name: "iPhone")
        let e = JSONEncoder(); e.outputFormatting = .sortedKeys
        let with = String(data: try e.encode(SyncBodyV1(sms: 1, calls: 2, contacts: "v", codecs: ["opus"], client: hello, caps: m.extensions.caps)), encoding: .utf8)!
        XCTAssertTrue(with.contains(#""caps":["x.tether.echo\/1"]"#) || with.contains(#""caps":["x.tether.echo/1"]"#), with)
        let without = String(data: try e.encode(SyncBodyV1(sms: 1, calls: 2, contacts: "v", codecs: ["opus"], client: hello, caps: nil)), encoding: .utf8)!
        XCTAssertFalse(without.contains("caps"))
    }
}

import Foundation
import XCTest
@_spi(TetherApp) @testable import TetherCore

/// The privacy scrubber and the problem report's layout (Link tab › Send a problem report).
final class ProblemReportTests: XCTestCase {
    private func scrub(_ s: String, _ literals: [String] = []) -> String { PrivacyScrubber.scrub(s, literals: literals) }

    // MARK: removed

    func testPhoneNumbersAreRemoved() {
        let numbers = ["+989123456789", "+98 912 345 6789", "+98-912-345-6789", "+1 (555) 010-0123", "+15550100", "+10000000001",
                       "+4915112345678", "09123456789", "0912 345 6789", "0912-345-6789", "00989123456789", "021-88776655",
                       "(555) 010-0123", "555-010-0123", "+۹۸ ۹۱۲ ۳۴۵ ۶۷۸۹", "۰۹۱۲۱۲۳۴۵۶۷"]
        for n in numbers {
            XCTAssertEqual(scrub("call from \(n) ended"), "call from <number> ended", n)
            XCTAssertEqual(scrub(n), "<number>", n)
        }
        XCTAssertEqual(scrub("to +15550100, from 09123456789."), "to <number>, from <number>.")
    }

    func testEmailIsRemoved() {
        XCTAssertEqual(scrub("mail me at first.last+tag@example.com now"), "mail me at <email> now")
        XCTAssertEqual(scrub("a@example.org"), "<email>")
    }

    func testBluetoothAddressKeepsLastTwoBytes() {
        XCTAssertEqual(scrub("peer 00:11:22:33:44:55 gone"), "peer **:**:**:**:44:55 gone")
        XCTAssertEqual(scrub("AA-BB-CC-DD-EE-FF"), "**:**:**:**:EE:FF")
        XCTAssertEqual(scrub("route 00:11:22:33:44:55-tsco"), "route **:**:**:**:44:55-tsco")
        XCTAssertEqual(scrub("addr aa:bb:cc:dd:ee:ff."), "addr **:**:**:**:ee:ff.")
    }

    func testIPv4IsRemovedExceptLoopback() {
        XCTAssertEqual(scrub("proxy 192.168.44.1:8118"), "proxy <ip>:8118")
        XCTAssertEqual(scrub("10.123.181.251"), "<ip>")
        XCTAssertEqual(scrub("http://10.0.0.5:8118/proxy.pac"), "http://<ip>:8118/proxy.pac")
        XCTAssertEqual(scrub("127.0.0.1"), "127.0.0.1")
        XCTAssertEqual(scrub("bound to 127.0.0.1 and 8.8.8.8"), "bound to 127.0.0.1 and <ip>")
    }

    func testLongDigitRunsAreIds() {
        XCTAssertEqual(scrub("imei 356938035643809 end"), "imei <id> end")
        XCTAssertEqual(scrub("iccid 8998010000000000000"), "iccid <id>")
        XCTAssertEqual(scrub("12345678901234"), "<id>")
    }

    func testSecretsAreRemoved() {
        let hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        XCTAssertEqual(scrub("key \(hex) end"), "key <secret> end")
        XCTAssertEqual(scrub("tok Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZg== x"), "tok <secret> x")
        XCTAssertEqual(scrub("k=Yk3p9XqL2mZ8vR5tN7wC1sD4fG6hJ0aB end"), "k=<secret> end")
        XCTAssertEqual(scrub("deadbeefdeadbeefdeadbeef"), "<secret>")
    }

    func testPairingCodeIsRemoved() {
        let body = String(repeating: "A1b2", count: 30) + "." + "QUJDREVGR0hJSktMTU5PUA" + ".TXkgQW5kcm9pZA"
        XCTAssertEqual(scrub("scanned tether1:\(body) ok"), "scanned <pairing code> ok")
        XCTAssertEqual(scrub("scanned tether://pair?c=\(body)"), "scanned <pairing code>")
    }

    func testUUIDsAreRemoved() {
        XCTAssertEqual(scrub("peripheral 7C2B1F5A-90D3-4E21-8B44-0A1F2E3D4C5B up"), "peripheral <uuid> up")
    }

    func testLiteralsAreRemovedIgnoringCase() {
        XCTAssertEqual(scrub("pairing with Amir's A54, then amir's a54 again", ["Amir's A54"]), "pairing with <name>, then <name> again")
        XCTAssertEqual(scrub("ab stays", ["ab"]), "ab stays")
    }

    // MARK: kept

    func testTimestampsVersionsAndCountersSurvive() {
        let kept = [
            "11-25 16:20:13.123 link up", "2026-11-25 16:20:13 +03:30", "Cellular 1.2.2 (1020299) · iPhone app",
            "build 1020399", "iOS 26.1", "version 1.2.3", "980 frames, 46 ms, 0.3 %", "drift -12 ppm", "drift +50 ppm",
            "jitter 4.5 ms, worst gap 87 ms", "lost 0 of 250 frames", "concealed 1.1 %", "epoch 1764087613123",
            "round trip 118 ms", "target 65, playout delay 46 ms", "port 8118", "iPhone17,3", "SIM 2 · LTE+ · signal 4",
            "underruns 3, drained 40 ms (speech 0 ms)", "call c12 in active", "LTE+ 5G", "7000", "SM-A546E",
            "AVAudioSessionCategoryPlayAndRecord", "voice_chat_mode_with_long_identifier_names",
            "buffer 55 ms · jitter 3.1 ms · gaps 2", "00:15 elapsed", "1.5.2",
        ]
        for k in kept { XCTAssertEqual(scrub(k), k, k) }
    }

    func testRealLogLinesSurvive() {
        let lines = [
            "11-25 16:20:13.123 call audio: link round trip 118 ms, playout delay 46 ms (target 65, arrival spread asks 44), concealed 12 ms, underruns 0, drained 40 ms (speech 0 ms), added 20 ms, lost 0 of 959 frames, arrival jitter 3.1 ms, worst gap 87 ms, drift -12 ppm, io 5.3 ms, out latency 4.2 ms",
            "11-25 16:20:14.001 connected over l2cap in 1.2 s",
            "11-25 16:20:14.500 audio route speaker (out Speaker, in BuiltInMic)",
        ]
        for l in lines { XCTAssertEqual(scrub(l), l) }
    }

    func testScrubbingIsIdempotentAndLineLocal() {
        let text = "a +98 912 345 6789 b\n00:11:22:33:44:55 c\nx@example.com\n10.1.2.3 d\n"
        let once = scrub(text)
        XCTAssertEqual(scrub(once), once)
        XCTAssertEqual(once, "a <number> b\n**:**:**:**:44:55 c\n<email>\n<ip> d\n")
        // A number never joins digits across a line break.
        XCTAssertEqual(scrub("+98 912\n345 6789"), "+98 912\n345 6789")
    }

    // MARK: report

    private func report(log: [String] = ["11-25 16:20:13.123 link up"], events: [String] = ["app active"]) -> ProblemReport {
        ProblemReport(app: "Cellular 1.2.2 (1020299) · iPhone app", created: Date(timeIntervalSince1970: 1_764_087_613),
                      timeZone: TimeZone(secondsFromGMT: 12_600)!,
                      sections: [.init("Device", ["model: iPhone17,3"]), .init("Setup", ["paired: yes"]), .init("Link", ["state: connected over l2cap"]),
                                 .init("Calls and audio", ["mic: allowed"]), .init("Android phone (from its last status)", ["device: SM-A546E"])],
                      linkLog: log, events: events, literals: ["My A54"])
    }

    func testReportLayout() {
        let t = report().render()
        let titles = t.split(separator: "\n").filter { $0.hasPrefix("## ") }.map(String.init)
        XCTAssertEqual(titles, ["## Device", "## Setup", "## Link", "## Calls and audio", "## Android phone (from its last status)", "## Link log", "## App events"])
        XCTAssertTrue(t.hasPrefix("=== Tether problem report ===\nApp: Cellular 1.2.2 (1020299) · iPhone app\nCreated: 2025-11-25 "), t)
        XCTAssertTrue(t.contains(" +03:30\nPrivacy: phone numbers, names, message text, e-mail, addresses and keys are removed. Check before posting.\n"))
        XCTAssertTrue(t.contains("## Link log\n11-25 16:20:13.123 link up\n"))
        XCTAssertTrue(t.hasSuffix("## App events\napp active\n"))
    }

    func testWholeReportIsScrubbed() {
        let t = ProblemReport(app: "Cellular 1 (1)", sections: [.init("Link", ["peer My A54 at 00:11:22:33:44:55"])],
                              linkLog: ["call from +15550100 to 09123456789", "mail a@example.com"], events: ["proxy 192.168.1.9"], literals: ["My A54"]).render()
        XCTAssertFalse(t.contains("+15550100"))
        XCTAssertFalse(t.contains("09123456789"))
        XCTAssertFalse(t.contains("a@example.com"))
        XCTAssertFalse(t.contains("192.168.1.9"))
        XCTAssertFalse(t.contains("My A54"))
        XCTAssertTrue(t.contains("peer <name> at **:**:**:**:44:55"))
    }

    func testLongLogKeepsNewestLinesUnderTheCap() {
        let log = (0..<600).map { i in "line \(i) " + String(repeating: "x", count: 1_990) }
        let t = report(log: log).render()
        XCTAssertLessThanOrEqual(t.utf8.count, ProblemReport.maxBytes)
        XCTAssertTrue(t.contains("line 0 "), "the newest line is kept")
        XCTAssertFalse(t.contains("line 599 "), "the oldest goes first")
        let few = report(log: (0..<900).map { "short \($0)" }).render()
        XCTAssertTrue(few.contains("short 599"))
        XCTAssertFalse(few.contains("short 600"), "at most 600 lines")
    }

    func testEmptyLogsSayEmpty() {
        let t = report(log: [], events: []).render()
        XCTAssertTrue(t.contains("## Link log\n(empty)\n"))
        XCTAssertTrue(t.contains("## App events\n(empty)\n"))
    }

    func testAndroidLinesDropNamesAndNumbers() {
        let status = StatusBody(device: "SM-A546E", battery: 82, charging: true,
                                sims: [SimInfo(slot: 0, sub: 3, name: "Sara's work SIM", carrier: "Carrier A", number: "+989123456789", signal: 4, net: "LTE", service: "in", country: "ir")],
                                defaultSub: 3, dialer: true,
                                helper: HelperInfo(state: "running", method: "intercept", capture: "intercept", up: true, down: true, silent: "full", uplink: "ok", mic: "auto", micPath: "off", audio: "full"),
                                wifi: true, adbWifi: true,
                                clients: [ClientInfo(name: "Sara's iPhone", kind: "iphone", isSelf: true), ClientInfo(name: "Pixel", kind: "android")],
                                caps: ["x.tether.echo/1"])
        let net = NetState(data: true, hotspot: false, bt: true, vpn: false, share: false,
                           proxy: ShareProxyInfo(port: 8118, addrs: [.init(iface: "swlan0", ip: "10.1.2.3", kind: "hotspot", pac: "http://10.1.2.3:8118/proxy.pac")]), airplane: false,
                           rat: [.init(slot: 0, mode: "5G")])
        let text = ProblemReport.androidLines(status: status, net: net).joined(separator: "\n")
        for secret in ["Sara", "+98912", "Pixel", "10.1.2.3", "swlan0"] { XCTAssertFalse(text.contains(secret), secret) }
        for kept in ["SM-A546E", "battery: 82% (charging)", "carrier Carrier A", "network LTE", "method intercept", "micPath off", "audio full",
                     "clients: 2 (1 android, 1 iphone)", "Bluetooth tethering on", "x.tether.echo/1", "SIM 1 5G"] {
            XCTAssertTrue(text.contains(kept), kept)
        }
        XCTAssertEqual(ProblemReport.androidLines(status: nil, net: nil), ["No status received from the Android phone yet."])
    }
}

import XCTest
import TetherCore
@testable import Tether

/// Link tab › Call audio: the Android phone's microphone switch and the uplink check (issue #2).
@MainActor
final class CallAudioRowsTests: XCTestCase {
    var m: AppModel { AppModel.shared }
    override func setUp() async throws { await MainActor.run { AppModel.shared.resetForTests() } }

    private let withSetting = #"{"device":"A","battery":10,"charging":false,"sims":[],"dialer":true,"helper":{"state":"running","method":"intercept","capture":"intercept","up":true,"down":true,"silent":"earpiece","micMute":false,"uplink":"muted-locally-fixed"},"wifi":true,"adbWifi":true}"#

    /// An Android build without the setting: no switch.
    func testSwitchHiddenWithoutTheSetting() {
        feed(m, wire(MsgType.status, status()))
        XCTAssertFalse(CallAudioRows.showsMicToggle(m.status))
        XCTAssertFalse(CallAudioRows.showsMicToggle(nil))
    }

    /// The setting, the capture method and the uplink check come with the status.
    func testSwitchShownWhenTheAndroidPhoneReportsIt() {
        feed(m, wireJSON(MsgType.status, withSetting))
        XCTAssertTrue(CallAudioRows.showsMicToggle(m.status))
        XCTAssertEqual(m.status?.helper.micMute, false)
        XCTAssertEqual(m.status?.helper.uplink, "muted-locally-fixed")
        XCTAssertEqual(m.status?.helper.capture, "intercept")
    }

    /// Not linked: the switch changes nothing (the Android phone would never hear of it).
    func testSettingNeedsTheLink() {
        feed(m, wireJSON(MsgType.status, withSetting))
        m.setPhoneMicMute(true)
        XCTAssertEqual(m.status?.helper.micMute, false)
    }

    func testUplinkTexts() {
        let all = ["ok", "checking", "muted-locally-fixed", "off", "unverified"].map(CallAudioRows.uplinkText)
        XCTAssertEqual(Set(all).count, all.count, "each state has its own text")
        XCTAssertEqual(CallAudioRows.uplinkText("something-new"), CallAudioRows.uplinkText("unverified"), "unknown states read as not checked")
    }

    /// The body the switch sends, as the Android phone reads it.
    func testAudioSetBody() throws {
        let d = try Control.encode(MsgType.audioSet, AudioSet(micMute: true))
        XCTAssertEqual(String(data: d, encoding: .utf8), #"{"b":{"micMute":true},"t":"audio.set"}"#)
    }

    private let withMic = #"{"device":"A","battery":10,"charging":false,"sims":[],"dialer":true,"helper":{"state":"running","method":"intercept","up":true,"down":true,"silent":"full","micMute":false,"uplink":"ok","mic":"auto","micPath":"off"},"wifi":true,"adbWifi":true}"#

    /// A current Android build: the three-way setting and what the mic does in the call.
    func testMicSettingFromCurrentAndroidBuilds() {
        feed(m, wireJSON(MsgType.status, withMic))
        XCTAssertEqual(m.status?.helper.mic, "auto")
        XCTAssertEqual(m.status?.helper.micPath, "off")
        XCTAssertEqual(CallAudioRows.micChoice("open"), "open")
        XCTAssertEqual(CallAudioRows.micChoice("future"), "auto", "an unknown setting reads as the default")
        let paths = ["off", "muted", "open"].map(CallAudioRows.micPathText)
        XCTAssertEqual(Set(paths).count, 3)
        let footers = ["auto", "mute", "open"].map(CallAudioRows.micFooter)
        XCTAssertEqual(Set(footers).count, 3)
    }

    /// The picker sends `mic`, never the old switch's `micMute`.
    func testAudioSetMicBody() throws {
        let d = try Control.encode(MsgType.audioSet, AudioSet(mic: "open"))
        XCTAssertEqual(String(data: d, encoding: .utf8), #"{"b":{"mic":"open"},"t":"audio.set"}"#)
    }

    func testMicSettingNeedsTheLink() {
        feed(m, wireJSON(MsgType.status, withMic))
        m.setPhoneMic("open")
        XCTAssertEqual(m.status?.helper.mic, "auto")
    }
}

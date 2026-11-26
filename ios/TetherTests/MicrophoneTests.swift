import XCTest
import TetherCore
@testable import Tether

/// This iPhone's microphone (#14): asked for before the first call, checked when a call starts, and a refused or
/// silent microphone is never shown as a voice that reaches the other side.
@MainActor
final class MicrophoneTests: XCTestCase {
    override func setUp() async throws { await MainActor.run { AppModel.shared.resetForTests(); AppModel.shared.micWarning = nil } }

    func testAskedOnceWhenPairedAndInFront() {
        XCTAssertTrue(MicPolicy.shouldAsk(.undetermined, paired: true, foreground: true))
        XCTAssertFalse(MicPolicy.shouldAsk(.undetermined, paired: false, foreground: true), "not before pairing")
        XCTAssertFalse(MicPolicy.shouldAsk(.undetermined, paired: true, foreground: false), "no alert can show in the background")
        XCTAssertFalse(MicPolicy.shouldAsk(.granted, paired: true, foreground: true))
        XCTAssertFalse(MicPolicy.shouldAsk(.denied, paired: true, foreground: true), "after a refusal only Settings can change it")
    }

    func testCheckWhenACallStarts() {
        XCTAssertEqual(MicPolicy.atCall(.granted), .fine)
        XCTAssertEqual(MicPolicy.atCall(.undetermined), .ask)
        XCTAssertEqual(MicPolicy.atCall(.denied), .denied)
    }

    func testWarnings() {
        XCTAssertEqual(MicPolicy.warning(access: .denied, captureSilent: false), .denied)
        XCTAssertEqual(MicPolicy.warning(access: .denied, captureSilent: true), .denied, "the refusal is the reason")
        XCTAssertEqual(MicPolicy.warning(access: .granted, captureSilent: true), .silent)
        XCTAssertNil(MicPolicy.warning(access: .granted, captureSilent: false))
    }

    /// Exact silence counts only after several seconds of a connected call; any sound clears it.
    func testSilentCapture() {
        let rate = 48000.0
        XCTAssertFalse(MicPolicy.captureSilent(samples: Int(rate * 2), rate: rate, anySound: false), "a pause is not a dead microphone")
        XCTAssertTrue(MicPolicy.captureSilent(samples: Int(rate * MicPolicy.silentAfterSeconds), rate: rate, anySound: false))
        XCTAssertFalse(MicPolicy.captureSilent(samples: Int(rate * 60), rate: rate, anySound: true))
    }

    /// The Android phone's uplink check says "reaches the other side"; with this iPhone's microphone refused or silent
    /// the row says it does not.
    func testVoiceRowNeverClaimsAudioWithoutAMicrophone() {
        let ok = CallAudioRows.uplinkText("ok")
        XCTAssertEqual(CallAudioRows.voiceText("ok", mic: nil), ok)
        XCTAssertNotEqual(CallAudioRows.voiceText("ok", mic: .denied), ok)
        XCTAssertNotEqual(CallAudioRows.voiceText("ok", mic: .silent), ok)
        XCTAssertNotEqual(CallAudioRows.voiceText("ok", mic: .denied), CallAudioRows.voiceText("ok", mic: .silent))
        XCTAssertEqual(CallAudioRows.micWarning(denied: true, last: nil), .denied)
        XCTAssertEqual(CallAudioRows.micWarning(denied: true, last: .silent), .denied)
        XCTAssertEqual(CallAudioRows.micWarning(denied: false, last: .silent), .silent)
        XCTAssertNil(CallAudioRows.micWarning(denied: false, last: .denied), "allowed again in Settings: no stale warning")
    }

    /// A call started or answered here sets the warning from the permission as it is (the tests' host never shows
    /// the alert).
    func testCallTimeCheckSetsTheWarning() {
        AppModel.shared.micWarning = .silent   // from the last call
        Microphone.checkForCall("answer")
        XCTAssertEqual(AppModel.shared.micWarning, MicPolicy.warning(access: Microphone.access, captureSilent: false))
    }
}

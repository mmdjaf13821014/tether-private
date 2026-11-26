import CryptoKit
import Intents
import XCTest
import TetherCore
@testable import Tether

@MainActor
final class RouterTests: XCTestCase {
    let r = Router.shared

    override func setUp() async throws {
        await MainActor.run {
            AppModel.shared.resetForTests()
            Router.shared.dialRequest = nil; Router.shared.pairRequest = nil; Router.shared.tab = .messages; Router.shared.linkWaitTries = 1
        }
    }

    /// Any web page can open tel: links: they only ask (Keypad + "Call …?"), never dial.
    func testTelLinkAsksNeverDials() {
        r.open(url: URL(string: "tel:+10000000001")!)
        XCTAssertEqual(r.dialRequest?.number, "+10000000001")
        XCTAssertEqual(r.tab, .keypad)
        XCTAssertNil(AppModel.shared.callScreen)
    }

    func testTetherDialLinkAsks() {
        r.open(url: URL(string: "tether://dial?number=%2B10000000002")!)
        XCTAssertEqual(r.dialRequest?.number, "+10000000002")
    }

    private func pairingCode() -> PairingCode {
        PairingCode(peripheralKey: P256.KeyAgreement.PrivateKey().publicKey.x963Representation,
                    token: Data(repeating: 7, count: 16), deviceName: "Test Phone")
    }

    /// The QR code opened from the Camera app: the Link tab asks "Pair with …?", nothing pairs by itself.
    func testPairLinkAsksNeverPairs() {
        let code = pairingCode()
        r.open(url: URL(string: code.link)!)
        XCTAssertEqual(r.pairRequest?.code, code)
        XCTAssertEqual(r.tab, .link)
        XCTAssertNil(r.dialRequest)
        XCTAssertFalse(AppModel.shared.isPaired)
    }

    /// The 1.1 form as a link is no URL the system opens, but a pasted tether1: code still parses elsewhere;
    /// a damaged pairing link says so and asks nothing.
    func testDamagedPairLinkShowsAnError() {
        AppModel.shared.lastError = nil
        r.open(url: URL(string: "tether://pair?c=abc")!)
        XCTAssertNil(r.pairRequest)
        XCTAssertEqual(r.tab, .link)
        XCTAssertNotNil(AppModel.shared.lastError)
        AppModel.shared.lastError = nil
    }

    func testDialLinkIsNotAPairLink() {
        r.open(url: URL(string: "tether://dial?number=%2B10000000002")!)
        XCTAssertNil(r.pairRequest)
        XCTAssertEqual(r.dialRequest?.number, "+10000000002")
    }

    /// A link without a number does nothing.
    func testLinkWithoutNumberIgnored() {
        r.open(url: URL(string: "tether://settings")!)
        XCTAssertNil(r.dialRequest)
        XCTAssertEqual(r.tab, .messages)
    }

    /// INStartCallIntent from Recents / Siri / Contacts: its first contact's handle is the number.
    func testStartCallIntentNumber() {
        let p = INPerson(personHandle: INPersonHandle(value: "+1 000 000 0003", type: .phoneNumber), nameComponents: nil,
                         displayName: "Test", image: nil, contactIdentifier: nil, customIdentifier: nil)
        XCTAssertNotNil(Router.number(from: INStartCallIntent(callRecordFilter: nil, callRecordToCallBack: nil, audioRoute: .unknown,
                                                              destinationType: .normal, contacts: [p], callCapability: .audioCall)))
        XCTAssertNil(Router.number(from: INStartCallIntent(callRecordFilter: nil, callRecordToCallBack: nil, audioRoute: .unknown,
                                                           destinationType: .normal, contacts: [], callCapability: .audioCall)))
        XCTAssertNil(Router.number(from: nil))
    }

    /// Not linked: a call from system UI is not lost, it becomes a question on the Keypad.
    func testCallNowNotLinkedAsks() async throws {
        r.callNow("+10000000004")
        for _ in 0..<40 where r.dialRequest == nil { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(r.dialRequest?.number, "+10000000004")
        XCTAssertEqual(r.tab, .keypad)
        XCTAssertNil(AppModel.shared.callScreen)
    }

    /// A notification tap opens the conversation.
    func testOpenThread() {
        r.open(thread: 42)
        XCTAssertEqual(r.tab, .messages)
        XCTAssertEqual(r.openThread, 42)
    }
}

import XCTest
@testable import TetherCore

final class PhoneToolsTests: XCTestCase {
    func testUssdValidation() {
        for ok in ["*123#", "#123#", "*#06#", "*100*1#", "*1#", "*" + String(repeating: "1", count: 30) + "#"] { XCTAssertTrue(UssdCode.isValid(ok), ok) }
        for bad in ["", "123", "*123", "*#", "#", "*12a#", "*123# ", "123#", "*123#\n", "*" + String(repeating: "1", count: 31) + "#"] { XCTAssertFalse(UssdCode.isValid(bad), bad) }
    }

    func testBodiesRoundTrip() throws {
        let enc = JSONEncoder()
        let u = try JSONDecoder().decode(UssdSend.self, from: try enc.encode(UssdSend(code: "*123#", sub: 3)))
        XCTAssertEqual(u, UssdSend(code: "*123#", sub: 3))
        let r = try JSONDecoder().decode(UssdResult.self, from: Data(#"{"ok":true,"text":"Balance 5"}"#.utf8))
        XCTAssertEqual(r.text, "Balance 5"); XCTAssertNil(r.err)
        let f = try JSONDecoder().decode(PhoneFinding.self, from: Data(#"{"on":false,"err":"x"}"#.utf8))
        XCTAssertFalse(f.on); XCTAssertEqual(f.err, "x")
        let rr = try JSONDecoder().decode(RingerResult.self, from: Data(#"{"ok":false,"mode":"normal","err":"dnd"}"#.utf8))
        XCTAssertEqual(rr.mode, "normal"); XCTAssertFalse(rr.ok)
        XCTAssertEqual(String(data: try enc.encode(RingerSet(mode: "silent")), encoding: .utf8)!, #"{"mode":"silent"}"#)
        XCTAssertEqual(String(data: try enc.encode(PhoneFind(on: true)), encoding: .utf8)!, #"{"on":true}"#)
    }

    func testStatusWithAndWithoutTools() throws {
        let base = #"{"b":{"device":"d","battery":1,"charging":false,"sims":[],"dialer":true,"helper":{"state":"running","up":true,"down":true},"wifi":true,"adbWifi":false"#
        let old = try Control.body(StatusBody.self, from: Data((base + "}}").utf8))
        XCTAssertNil(old.tools); XCTAssertNil(old.ringer)
        let new = try Control.body(StatusBody.self, from: Data((base + #","tools":{"ussd":true,"find":true,"ringer":true},"ringer":"vibrate","dnd":false}}"#).utf8))
        XCTAssertEqual(new.tools?.find, true); XCTAssertEqual(new.ringer, "vibrate"); XCTAssertEqual(new.dnd, false)
    }
}

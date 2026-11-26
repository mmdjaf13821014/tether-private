import Foundation
import TetherCore
@testable import Tether

/// A message as the Android phone puts it on the wire ({"t":…,"b":…}), parsed back into header + payload.
func wire<B: Encodable>(_ type: String, _ body: B, re: Int? = nil) -> (MessageHeader, Data) {
    let d = try! Control.encode(type, body, re: re)
    return (try! Control.header(d), d)
}

/// The same from raw JSON body text, so a test pins the field names the Android phone sends.
func wireJSON(_ type: String, _ body: String, re: Int? = nil) -> (MessageHeader, Data) {
    let reField = re.map { ",\"re\":\($0)" } ?? ""
    let d = Data("{\"t\":\"\(type)\"\(reField),\"b\":\(body)}".utf8)
    return (try! Control.header(d), d)
}

@MainActor func feed(_ m: AppModel, _ msg: (MessageHeader, Data)) { m.received(msg.0, msg.1) }

func ms(minutesAgo: Double) -> Int64 { Int64(Date().addingTimeInterval(-minutesAgo * 60).timeIntervalSince1970 * 1000) }

func sms(_ id: Int64, thread: Int64, addr: String = "+10000000001", incoming: Bool = true, read: Bool = false,
         date: Int64? = nil, body: String = "t", sub: Int? = nil) -> Sms {
    Sms(id: id, thread: thread, addr: addr, body: body, date: date ?? ms(minutesAgo: Double(1000 - id % 1000)),
        box: incoming ? 1 : 2, read: read, sub: sub)
}

func status(sims: [SimInfo] = [], defaultSub: Int? = nil, tools: StatusBody.PhoneTools? = nil, ringer: String? = nil) -> StatusBody {
    StatusBody(device: "Test Android", battery: 50, charging: false, sims: sims, defaultSub: defaultSub, dialer: true,
               helper: HelperInfo(state: "running"), wifi: true, adbWifi: true, tools: tools, ringer: ringer)
}

func fixtureURL(_ name: String) -> URL {
    final class Marker {}
    return Bundle(for: Marker.self).url(forResource: name, withExtension: nil)!
}

func tempDir() -> URL {
    let u = FileManager.default.temporaryDirectory.appendingPathComponent("tether-tests-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
    return u
}

// Bodies TetherCore only decodes (no public init): the same fields, as the Android phone writes them.
struct SmsBatchW: Encodable { var items: [Sms]; var more: Bool }
struct SmsNewW: Encodable { var item: Sms }
struct CallsBatchW: Encodable { var items: [CallEntry]; var more: Bool }

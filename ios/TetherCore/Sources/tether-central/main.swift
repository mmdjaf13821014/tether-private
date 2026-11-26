// Mac-side test central for the Tether link (same TetherLink code as the iPhone app).
//   tether-central pair "<tether1:… code>" [seconds]
//   tether-central run [seconds]
// State lives in ~/.tether-central.json (test identity only).
import CryptoKit
import Foundation
import TetherCore

final class FileStore: IdentityStore {
    let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".tether-central.json")
    var dict: [String: String]
    init() { dict = (try? JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))) ?? [:] }
    func save() { try? JSONEncoder().encode(dict).write(to: url) }
    func staticKey() -> P256.KeyAgreement.PrivateKey {
        if let h = dict["key"], let d = Data(hex: h), let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d) { return k }
        let k = P256.KeyAgreement.PrivateKey(); dict["key"] = k.rawRepresentation.hex; save(); return k
    }
    var peerKey: Data? { get { dict["peer"].flatMap { Data(hex: $0) } } set { dict["peer"] = newValue?.hex; save() } }
    var peerName: String? { get { dict["name"] } set { dict["name"] = newValue; save() } }
    var peripheralID: UUID? { get { dict["pid"].flatMap(UUID.init) } set { dict["pid"] = newValue?.uuidString; save() } }
}

final class Runner: TetherLinkDelegate {
    var counts: [String: Int] = [:]
    var items: [String: Int] = [:]
    var audioFrames = 0
    let start = Date()
    func link(_ link: TetherLink, didChange state: TetherLink.State) {
        print(String(format: "%6.2fs state %@", Date().timeIntervalSince(start), "\(state)"))
        if state.isConnected {
            link.send(MsgType.sync, SyncWithCodecs(sms: 0, calls: 0, contacts: "", codecs: ["opus", "pcmu"]))
            link.send(MsgType.ping, PingBody(ts: 1), expectReply: true)
        }
    }
    func link(_ link: TetherLink, didReceive h: MessageHeader, data: Data) {
        counts[h.t, default: 0] += 1
        if let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let b = o["b"] as? [String: Any],
           let arr = b["items"] as? [Any] { items[h.t, default: 0] += arr.count }
        if h.t == "status" || h.t == "call.state" || h.t == "pong" || h.t == "audio.start" {
            print(String(format: "%6.2fs %@", Date().timeIntervalSince(start), String(data: data, encoding: .utf8)!.prefix(300) as CVarArg))
        }
    }
    func link(_ link: TetherLink, didReceiveAudio frame: AudioFrame) { audioFrames += 1 }
    func link(_ link: TetherLink, didFail reason: String) { print("fail: \(reason)") }
}

struct SyncWithCodecs: Encodable { var sms: Int64; var calls: Int64; var contacts: String; var codecs: [String] }

let args = CommandLine.arguments
let store = FileStore()
let link = TetherLink(store: store)
let runner = Runner()
link.delegate = runner
var seconds = 40.0
if args.count > 2, args[1] == "pair", let code = PairingCode.parse(args[2]) {
    link.pair(with: code)
    if args.count > 3 { seconds = Double(args[3]) ?? seconds }
} else if args.count > 1, args[1] == "run" {
    if args.count > 2 { seconds = Double(args[2]) ?? seconds }
} else {
    print("usage: tether-central pair <code> [seconds] | run [seconds]"); exit(2)
}
DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
    print("messages: \(runner.counts)")
    print("items: \(runner.items)  audio frames: \(runner.audioFrames)  state: \(link.state)")
    exit(0)
}
RunLoop.main.run()

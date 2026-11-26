// MacCentral: a Mac client for a Tether phone over real Bluetooth, built on TetherLink (the same link code the
// iPhone app uses).
//
//   MacCentral pair "<code>" [seconds]   pair with the code the phone shows, then run
//   MacCentral run [seconds]                         reconnect with the stored identity
//   MacCentral unpair                                forget the phone (and tell it, if it is in reach)
//
// It prints the link state, the phone's status (battery, SIMs, signal) and the latest text messages, then exits
// after `seconds` (default 60). The client identity (a P-256 key and the paired phone's public key) is stored in
// ~/Library/Application Support/TetherMacCentral/identity.json, readable by you only.
import Foundation
import TetherCore

// MARK: - Identity store

/// TetherLink keeps its long-term state in an `IdentityStore`: our static key, the paired phone's key and name, and
/// the CoreBluetooth identifier of the phone (to reconnect without scanning). The iPhone app keeps it in the
/// Keychain; a JSON file is enough here.
final class FileStore: IdentityStore {
    private let url: URL
    private var values: [String: String]

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TetherMacCentral", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        url = dir.appendingPathComponent("identity.json")
        values = (try? JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))) ?? [:]
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(values) else { return }
        try? data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    func staticKey() -> TetherPrivateKey {
        if let hex = values["key"], let raw = Data(hex: hex), let key = try? TetherPrivateKey(rawRepresentation: raw) {
            return key
        }
        let key = TetherPrivateKey()
        values["key"] = key.rawRepresentation.hex
        save()
        return key
    }

    var peerKey: Data? {
        get { values["peer"].flatMap { Data(hex: $0) } }
        set { values["peer"] = newValue?.hex; save() }
    }
    var peerName: String? {
        get { values["name"] }
        set { values["name"] = newValue; save() }
    }
    var peripheralID: UUID? {
        get { values["pid"].flatMap(UUID.init(uuidString:)) }
        set { values["pid"] = newValue?.uuidString; save() }
    }
}

// MARK: - Link delegate

/// Delegate calls arrive on the link's queue, one at a time.
final class Client: TetherLinkDelegate {
    private let start = Date()
    private var latest: [Sms] = []
    private var pingId: Int?
    private var pingSent = Date()
    private var counts: [String: Int] = [:]

    private func say(_ s: String) {
        print(String(format: "%6.1fs  ", Date().timeIntervalSince(start)) + s)
    }

    func link(_ link: TetherLink, didChange state: TetherLink.State) {
        say("link: \(state)")
        guard state.isConnected else { return }
        // Ask for everything (0 / "" = full sync). Contacts and call history come as well; we only count them.
        link.send(MsgType.sync, SyncBody(sms: 0, calls: 0, contacts: ""))
        pingSent = Date()
        pingId = link.send(MsgType.ping, PingBody(ts: Int64(pingSent.timeIntervalSince1970 * 1000)), expectReply: true)
    }

    func link(_ link: TetherLink, didReceive header: MessageHeader, data: Data) {
        counts[header.t, default: 0] += 1
        do {
            switch header.t {
            case MsgType.status:
                let s = try Control.body(StatusBody.self, from: data)
                let sims = s.sims.map { "\($0.name): \($0.net ?? "no network"), signal \($0.signal)/4" }
                say("status: \(s.device), battery \(s.battery) %\(s.charging ? " (charging)" : ""); " + sims.joined(separator: "; "))
            case MsgType.pong where header.re == pingId:
                say("round trip \(Int(Date().timeIntervalSince(pingSent) * 1000)) ms")
            case MsgType.smsBatch:
                let batch = try Control.body(SmsBatch.self, from: data)
                latest = Array((latest + batch.items).sorted { $0.date > $1.date }.prefix(5))
                if !batch.more { printLatest() }
            case MsgType.smsNew:
                let sms = try Control.body(SmsNew.self, from: data).item
                say("new text from \(sms.addr): \(preview(sms.body))")
            case MsgType.callState:
                let call = try Control.body(CallInfo.self, from: data)
                say("call \(call.dir) \(call.state)")
            case MsgType.error:
                say("phone reports an error: \((try? Control.body(ErrorBody.self, from: data).msg) ?? "?")")
            default:
                break   // contacts.batch, calls.batch, lists, sync.ids, …: see protocol/PROTOCOL.md §5
            }
        } catch {
            say("could not read \(header.t): \(error)")
        }
    }

    func link(_ link: TetherLink, didReceiveAudio frame: AudioFrame) {
        counts["audio", default: 0] += 1
    }

    func link(_ link: TetherLink, didFail reason: String) {
        say("link failed: \(reason) (it retries by itself)")
    }

    private func preview(_ body: String) -> String {
        let plain = MessageText.plain(body).replacingOccurrences(of: "\n", with: " ")
        return plain.count > 60 ? plain.prefix(60) + "…" : plain
    }

    private func printLatest() {
        say("latest texts:")
        for sms in latest {
            let date = Date(timeIntervalSince1970: Double(sms.date) / 1000)
            let when = DateFormatter.localizedString(from: date, dateStyle: .short, timeStyle: .short)
            print("          \(when)  \(sms.incoming ? "from" : "to  ") \(sms.addr): \(preview(sms.body))")
        }
    }

    func summary() -> String {
        counts.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ")
    }
}

// MARK: - Main

let args = Array(CommandLine.arguments.dropFirst())
let store = FileStore()
let link = TetherLink(store: store)
let client = Client()
link.delegate = client
link.log = { _ in }   // the link's own diagnostics; print them while debugging: link.log = { print("[link] \($0)") }

var seconds = 60.0
switch args.first {
case "pair":
    guard args.count >= 2, let code = PairingCode.parse(args[1]) else {
        print("not a pairing code: it starts with \(PairingCode.linkPrefix) or \(PairingCode.prefix)"); exit(2)
    }
    if args.count >= 3 { seconds = Double(args[2]) ?? seconds }
    print("pairing with \(code.deviceName)")
    link.pair(with: code)
case "run":
    guard link.isPaired else { print("not paired yet: run MacCentral pair \"<code>\" first"); exit(2) }
    if args.count >= 2 { seconds = Double(args[1]) ?? seconds }
    print("connecting to \(store.peerName ?? "the paired phone")")
case "unpair":
    link.unpair()
    seconds = 2
default:
    print("""
    usage: MacCentral pair "<tether://pair?c=… or tether1:… code>" [seconds]
           MacCentral run [seconds]
           MacCentral unpair
    """)
    exit(2)
}

DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
    link.queue.sync { print("messages: \(client.summary())") }
    exit(0)
}
RunLoop.main.run()

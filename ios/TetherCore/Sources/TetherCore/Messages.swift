import Foundation

/// Control message envelope (protocol §5).
public struct MessageHeader: Codable, Equatable, Sendable {
    public var t: String
    public var id: Int?
    public var re: Int?
    public init(t: String, id: Int? = nil, re: Int? = nil) { self.t = t; self.id = id; self.re = re }
}

public struct Empty: Codable, Equatable, Sendable { public init() {} }

public enum Control {
    private struct Out<B: Encodable>: Encodable {
        let t: String
        let id: Int?
        let re: Int?
        let b: B
    }

    private struct BodyOnly<B: Decodable>: Decodable { let b: B }

    public static func encode<B: Encodable>(_ type: String, _ body: B, id: Int? = nil, re: Int? = nil) throws -> Data {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try enc.encode(Out(t: type, id: id, re: re, b: body))
    }

    public static func header(_ data: Data) throws -> MessageHeader {
        try JSONDecoder().decode(MessageHeader.self, from: data)
    }

    public static func body<B: Decodable>(_ type: B.Type, from data: Data) throws -> B {
        try JSONDecoder().decode(BodyOnly<B>.self, from: data).b
    }
}

public enum MsgType {
    public static let ping = "ping", pong = "pong", status = "status", sync = "sync"
    public static let smsBatch = "sms.batch", smsNew = "sms.new", smsSend = "sms.send", smsRead = "sms.read", smsUnread = "sms.unread", smsResult = "sms.result"
    public static let contactsBatch = "contacts.batch", contactAdd = "contact.add", contactUpdate = "contact.update", contactStar = "contact.star", contactResult = "contact.result"
    public static let callsBatch = "calls.batch", callState = "call.state", callList = "call.list"
    public static let callAnswer = "call.answer", callReject = "call.reject", callEnd = "call.end"
    public static let callHold = "call.hold", callMute = "call.mute", callDtmf = "call.dtmf"
    public static let callDial = "call.dial", callDialed = "call.dialed", callTake = "call.take"
    public static let audioStart = "audio.start", audioStop = "audio.stop", audioStats = "audio.stats", audioSet = "audio.set"
    public static let selftest = "selftest", selftestResult = "selftest.result"
    public static let netGet = "net.get", netSet = "net.set", netState = "net.state"
    public static let smsDelete = "sms.delete", smsDeleted = "sms.deleted", callsDelete = "calls.delete", callsDeleted = "calls.deleted"
    public static let listsSet = "lists.set", lists = "lists", syncIds = "sync.ids"
    public static let ussdSend = "ussd.send", ussdResult = "ussd.result"
    public static let phoneFind = "phone.find", phoneFinding = "phone.finding", ringerSet = "ringer.set", ringerResult = "ringer.result"
    public static let unpair = "unpair", error = "error"
}

// MARK: - Objects

public struct Sms: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: Int64
    public var thread: Int64
    public var addr: String
    public var body: String
    public var date: Int64
    public var box: Int
    public var read: Bool
    public var sub: Int?

    public init(id: Int64, thread: Int64, addr: String, body: String, date: Int64, box: Int, read: Bool, sub: Int? = nil) {
        self.id = id; self.thread = thread; self.addr = addr; self.body = body
        self.date = date; self.box = box; self.read = read; self.sub = sub
    }

    public var incoming: Bool { box == 1 }
}

public struct Phone: Codable, Equatable, Hashable, Sendable {
    public var n: String
    public var type: String
    public var label: String?
    public init(n: String, type: String = "mobile", label: String? = nil) {
        self.n = n; self.type = type; self.label = label
    }
}

public struct Contact: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: Int64
    public var name: String
    public var phones: [Phone]
    public var emails: [String]
    public var photo: String?
    public var starred: Bool

    public init(id: Int64, name: String, phones: [Phone], emails: [String] = [], photo: String? = nil, starred: Bool = false) {
        self.id = id; self.name = name; self.phones = phones; self.emails = emails
        self.photo = photo; self.starred = starred
    }
}

public struct CallEntry: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: Int64
    public var num: String
    public var name: String?
    public var date: Int64
    public var dur: Int
    public var type: Int
    public var sub: Int?

    public init(id: Int64, num: String, name: String? = nil, date: Int64, dur: Int, type: Int, sub: Int? = nil) {
        self.id = id; self.num = num; self.name = name; self.date = date
        self.dur = dur; self.type = type; self.sub = sub
    }
}

public struct CallInfo: Codable, Equatable, Hashable, Sendable {
    public var cid: String
    public var num: String
    public var name: String?
    public var state: String
    public var dir: String
    public var sub: Int?
    public var since: Int64?
    public var cause: String?
    public var hold: Bool
    /// The call's audio is on another client (answered / dialed on the Mac or the iPhone); `at` names it.
    public var elsewhere: Bool?
    public var at: String?

    enum CodingKeys: String, CodingKey {
        case cid, num, name, state, dir, sub, since, cause, hold, elsewhere
        case at = "where"
    }

    public init(cid: String, num: String, name: String? = nil, state: String, dir: String,
                sub: Int? = nil, since: Int64? = nil, cause: String? = nil, hold: Bool = false,
                elsewhere: Bool? = nil, at: String? = nil) {
        self.cid = cid; self.num = num; self.name = name; self.state = state; self.dir = dir
        self.sub = sub; self.since = since; self.cause = cause; self.hold = hold
        self.elsewhere = elsewhere; self.at = at
    }

    public var isElsewhere: Bool { elsewhere == true }

    public var isEnded: Bool { state == "ended" }
    public var isIncoming: Bool { dir == "in" }
}

public struct SimInfo: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var slot: Int
    public var sub: Int
    public var name: String
    public var carrier: String
    public var number: String?
    public var signal: Int
    /// Network the SIM is on as phones label it: "5G", "LTE+", "LTE", "H+", "H", "3G", "E", "2G" (nil: unknown).
    public var net: String?
    /// "in" (service), "none", "emergency" (emergency calls only), "off" (radio off, e.g. airplane mode).
    public var service: String?
    public var roaming: Bool?
    /// The SIM's country, lowercase ISO 3166 ("ir"); national numbers are grouped by it. Older Android apps omit it.
    public var country: String?
    public var id: Int { sub }
    public var hasService: Bool { service == nil || service == "in" }

    public init(slot: Int, sub: Int, name: String, carrier: String, number: String? = nil, signal: Int = 0,
                net: String? = nil, service: String? = nil, roaming: Bool? = nil, country: String? = nil) {
        self.slot = slot; self.sub = sub; self.name = name; self.carrier = carrier
        self.number = number; self.signal = signal; self.net = net; self.service = service; self.roaming = roaming
        self.country = country
    }
}

public struct HelperInfo: Codable, Equatable, Sendable {
    public var state: String
    /// Inject method of the current or last call ("intercept", "telephony_tx", …).
    public var method: String?
    /// Capture method of the current or last call.
    public var capture: String?
    public var up: Bool
    public var down: Bool
    /// During a bridged call: how silent the Android phone itself is ("full", "earpiece", "none").
    public var silent: String?
    /// The setting "mute the Android phone's microphone during bridged calls" (nil from builds without it: no switch).
    /// Newer builds send ``mic`` too; this stays true only for "mute".
    public var micMute: Bool?
    /// Whether this iPhone's voice reaches the call: "checking", "ok", "muted-locally-fixed", "unverified", "off".
    public var uplink: String?
    /// The setting "the Android phone's microphone during bridged calls": "auto" (taken out of the call by a redirect
    /// audio mode where the phone allows it), "mute", "open". Nil from builds before it (they have only ``micMute``).
    public var mic: String?
    /// During a bridged call, what the Android phone's own mic does: "off" (out of the call), "muted", "open".
    public var micPath: String?
    /// What call audio this Android version allows: "full" (13+), "fallback" (11-12, best effort), "unavailable"
    /// (8 to 10: calls are controlled from the iPhone but their audio stays on the Android phone). Nil from older builds.
    public var audio: String?
    public init(state: String, method: String? = nil, capture: String? = nil, up: Bool = false, down: Bool = false,
                silent: String? = nil, micMute: Bool? = nil, uplink: String? = nil, mic: String? = nil, micPath: String? = nil,
                audio: String? = nil) {
        self.state = state; self.method = method; self.capture = capture; self.up = up; self.down = down; self.silent = silent
        self.micMute = micMute; self.uplink = uplink; self.mic = mic; self.micPath = micPath; self.audio = audio
    }

    /// The Android phone cannot bridge call audio at all (Android 8 to 10).
    public var audioUnavailable: Bool { audio == "unavailable" }
    /// The Android phone's software gave Tether no call audio in a real call (since 1.2.2): calls stay on that phone.
    public var audioBlocked: Bool { audio == "blocked" }
}

public struct StatusBody: Codable, Equatable, Sendable {
    public var device: String
    public var battery: Int
    public var charging: Bool
    public var sims: [SimInfo]
    public var defaultSub: Int?
    public var dialer: Bool
    public var helper: HelperInfo
    public var wifi: Bool
    public var adbWifi: Bool
    /// Every client connected to the Android phone right now (this one has `self`).
    public var clients: [ClientInfo]?
    /// What the Phone section may offer (nil from Android builds without carrier codes / find my phone / ringer: hide it).
    public var tools: PhoneTools?
    /// Ringer mode of the Android phone: "normal", "vibrate" or "silent"; `dnd` = Do Not Disturb access (needed for silent).
    public var ringer: String?; public var dnd: Bool?
    /// The Android phone's extensions, `"<id>/<major>"` (protocol §7.2; nil from builds without extensions).
    public var caps: [String]?
    /// Their status fields, by extension id (only those this client agreed, §7.5).
    public var ext: [String: JSONValue]?
    public struct PhoneTools: Codable, Equatable, Sendable {
        public var ussd: Bool?; public var find: Bool?; public var ringer: Bool?
        public init(ussd: Bool? = nil, find: Bool? = nil, ringer: Bool? = nil) { self.ussd = ussd; self.find = find; self.ringer = ringer }
    }

    public init(device: String, battery: Int, charging: Bool, sims: [SimInfo], defaultSub: Int?, dialer: Bool,
                helper: HelperInfo, wifi: Bool, adbWifi: Bool, clients: [ClientInfo]? = nil,
                tools: PhoneTools? = nil, ringer: String? = nil, dnd: Bool? = nil,
                caps: [String]? = nil, ext: [String: JSONValue]? = nil) {
        self.tools = tools; self.ringer = ringer; self.dnd = dnd; self.caps = caps; self.ext = ext
        self.device = device; self.battery = battery; self.charging = charging; self.sims = sims
        self.defaultSub = defaultSub; self.dialer = dialer; self.helper = helper; self.wifi = wifi; self.adbWifi = adbWifi
        self.clients = clients
    }
}

/// A client of the Android phone (iPhone, Mac). `audio` = call audio currently goes to it.
public struct ClientInfo: Codable, Equatable, Hashable, Sendable {
    public var name: String
    public var kind: String
    public var `self`: Bool?
    public var audio: Bool?
    public init(name: String, kind: String, isSelf: Bool? = nil, audio: Bool? = nil) {
        self.name = name; self.kind = kind; self.`self` = isSelf; self.audio = audio
    }
    public var isSelf: Bool { self.`self` == true }
}

/// Sent with `sync`: who this client is, so the phone can name it and pick where call audio goes.
public struct ClientHello: Codable, Equatable, Sendable {
    public var kind: String; public var name: String
    public init(kind: String, name: String) { self.kind = kind; self.name = name }
}

// MARK: - Bodies

public struct PingBody: Codable, Equatable, Sendable { public var ts: Int64; public init(ts: Int64) { self.ts = ts } }

/// `sync` (client → phone): the newest SMS and call ids and the contacts version the client holds (0 / "" for a full
/// sync), the codecs it can decode and encode (best first), and who it is. Optional fields left nil are not sent.
public struct SyncBody: Codable, Equatable, Sendable {
    public var sms: Int64
    public var calls: Int64
    public var contacts: String
    public var codecs: [String]?
    public var client: ClientHello?
    public init(sms: Int64, calls: Int64, contacts: String, codecs: [String]? = nil, client: ClientHello? = nil) {
        self.sms = sms; self.calls = calls; self.contacts = contacts; self.codecs = codecs; self.client = client
    }
}

/// Star (Favourites) or unstar a contact on the Android phone; answered with `contact.result`.
public struct ContactStar: Codable, Equatable, Sendable {
    public var id: Int64; public var on: Bool
    public init(id: Int64, on: Bool) { self.id = id; self.on = on }
}
public struct SmsBatch: Codable, Equatable, Sendable {
    public var items: [Sms]; public var more: Bool
    public init(items: [Sms], more: Bool) { self.items = items; self.more = more }
}
public struct SmsNew: Codable, Equatable, Sendable { public var item: Sms; public init(item: Sms) { self.item = item } }
/// "Everything in this conversation up to message `upTo` has been read." Sent by the device where the
/// user read it; the Android phone marks those messages read and forwards it to the other devices.
public struct SmsRead: Codable, Equatable, Sendable {
    public var thread: Int64; public var upTo: Int64
    public init(thread: Int64, upTo: Int64) { self.thread = thread; self.upTo = upTo }
}
/// The Android phone's unread incoming texts (after a sync and whenever its read flags change). Everything else
/// was read there (or on the other device). `all` false: the list was capped, so only ids ≥ its smallest are known.
public struct SmsUnread: Codable, Equatable, Sendable {
    public var ids: [Int64]; public var all: Bool?
    public init(ids: [Int64], all: Bool? = true) { self.ids = ids; self.all = all }
    /// Is message `id` read on the phone, as far as this list knows (nil: unknown).
    public func isRead(_ id: Int64) -> Bool? {
        if ids.contains(id) { return false }
        if all == false, let m = ids.min(), id < m { return nil }
        return true
    }
}
public struct SmsSend: Codable, Equatable, Sendable {
    public var to: String; public var body: String; public var sub: Int?
    public init(to: String, body: String, sub: Int?) { self.to = to; self.body = body; self.sub = sub }
}
public struct SmsResult: Codable, Equatable, Sendable {
    public var ok: Bool; public var state: String; public var err: String?
    public init(ok: Bool, state: String, err: String? = nil) { self.ok = ok; self.state = state; self.err = err }
}

public struct ContactsBatch: Codable, Equatable, Sendable {
    public var items: [Contact]; public var more: Bool; public var version: String; public var full: Bool
    public init(items: [Contact], more: Bool, version: String, full: Bool) {
        self.items = items; self.more = more; self.version = version; self.full = full
    }
}
public struct ContactAdd: Codable, Equatable, Sendable {
    public var name: String; public var phones: [Phone]; public var emails: [String]
    public init(name: String, phones: [Phone], emails: [String]) { self.name = name; self.phones = phones; self.emails = emails }
}
/// Replaces the name, phone numbers and e-mail addresses of an existing contact on the Android phone.
public struct ContactUpdate: Codable, Equatable, Sendable {
    public var id: Int64; public var name: String; public var phones: [Phone]; public var emails: [String]
    public init(id: Int64, name: String, phones: [Phone], emails: [String]) {
        self.id = id; self.name = name; self.phones = phones; self.emails = emails
    }
}
public struct ContactResult: Codable, Equatable, Sendable {
    public var ok: Bool; public var id: Int64?; public var err: String?
    public init(ok: Bool, id: Int64? = nil, err: String? = nil) { self.ok = ok; self.id = id; self.err = err }
}

public struct CallsBatch: Codable, Equatable, Sendable {
    public var items: [CallEntry]; public var more: Bool
    public init(items: [CallEntry], more: Bool) { self.items = items; self.more = more }
}
public struct CallList: Codable, Equatable, Sendable { public var calls: [CallInfo]; public init(calls: [CallInfo]) { self.calls = calls } }
public struct CallRef: Codable, Equatable, Sendable { public var cid: String; public init(cid: String) { self.cid = cid } }
public struct CallHold: Codable, Equatable, Sendable { public var cid: String; public var on: Bool; public init(cid: String, on: Bool) { self.cid = cid; self.on = on } }
public struct CallMute: Codable, Equatable, Sendable { public var on: Bool; public init(on: Bool) { self.on = on } }
public struct CallDtmf: Codable, Equatable, Sendable { public var cid: String; public var d: String; public init(cid: String, d: String) { self.cid = cid; self.d = d } }
public struct CallDial: Codable, Equatable, Sendable { public var num: String; public var sub: Int?; public init(num: String, sub: Int?) { self.num = num; self.sub = sub } }
public struct OkResult: Codable, Equatable, Sendable { public var ok: Bool; public var err: String?; public init(ok: Bool, err: String? = nil) { self.ok = ok; self.err = err } }

public struct AudioStart: Codable, Equatable, Sendable {
    public var codec: String; public var rate: Int; public var ms: Int; public var up: Bool
    /// Opus bitrate the phone encodes at; the client matches it for the uplink.
    public var bitrate: Int?
    public init(codec: String, rate: Int, ms: Int, up: Bool, bitrate: Int? = nil) {
        self.codec = codec; self.rate = rate; self.ms = ms; self.up = up; self.bitrate = bitrate
    }
}

/// The Android phone's half of a call's audio statistics, sent after `audio.stop` (shown in the Link log).
public struct AudioStats: Codable, Equatable, Sendable {
    public var summary: String
    public init(summary: String) { self.summary = summary }
}

/// Network switches on the Android phone (Link tab). `err` is set when the phone could not do it.
public struct NetState: Codable, Equatable, Sendable {
    public var data: Bool?; public var hotspot: Bool?; public var bt: Bool?; public var err: String?
    /// Subscription id of the SIM that carries mobile data.
    public var dataSub: Int?
    /// A VPN is up on the Android phone (read-only: it is switched in the VPN app there).
    public var vpn: Bool?
    /// "Share VPN": the phone's proxy forwards through its VPN; `proxy` says where clients reach it.
    public var share: Bool?; public var proxy: ShareProxyInfo?
    /// Airplane mode on the Android phone (Bluetooth stays on).
    public var airplane: Bool?
    /// Preferred network per SIM slot: the highest generation it may use ("5G", "4G", "3G", "2G").
    public var rat: [SlotRat]?
    /// What the phone can tether at all (nil from older Android builds: assume yes).
    public var can: Capabilities?
    public struct Capabilities: Codable, Equatable, Sendable {
        public var hotspot: Bool?; public var bt: Bool?
        public init(hotspot: Bool? = nil, bt: Bool? = nil) { self.hotspot = hotspot; self.bt = bt }
    }
    public struct SlotRat: Codable, Equatable, Sendable, Hashable {
        public var slot: Int; public var mode: String
        public init(slot: Int, mode: String) { self.slot = slot; self.mode = mode }
    }
    public init(data: Bool? = nil, hotspot: Bool? = nil, bt: Bool? = nil, err: String? = nil, dataSub: Int? = nil,
                vpn: Bool? = nil, share: Bool? = nil, proxy: ShareProxyInfo? = nil, airplane: Bool? = nil, rat: [SlotRat]? = nil) {
        self.data = data; self.hotspot = hotspot; self.bt = bt; self.err = err; self.dataSub = dataSub
        self.vpn = vpn; self.share = share; self.proxy = proxy; self.airplane = airplane; self.rat = rat
    }
    public func mode(slot: Int) -> String? { rat?.first { $0.slot == slot }?.mode }
}
/// The Android phone's VPN-sharing proxy: one port for HTTP, HTTPS (CONNECT), SOCKS5 and the PAC file,
/// on each tethering interface (hotspot, Bluetooth PAN).
public struct ShareProxyInfo: Codable, Equatable, Sendable {
    public struct Addr: Codable, Equatable, Sendable, Hashable {
        public var iface: String; public var ip: String; public var kind: String; public var pac: String
        public init(iface: String, ip: String, kind: String, pac: String) { self.iface = iface; self.ip = ip; self.kind = kind; self.pac = pac }
    }
    public var port: Int; public var addrs: [Addr]
    public init(port: Int, addrs: [Addr]) { self.port = port; self.addrs = addrs }

    /// The phone's address on a network this device is on (same IPv4 /24 as one of `local`), if any.
    public func reachable(from local: [String]) -> Addr? {
        func net(_ ip: String) -> String? {
            let p = ip.split(separator: "."); return p.count == 4 ? p.prefix(3).joined(separator: ".") : nil
        }
        let nets = Set(local.compactMap(net))
        return addrs.first { a in net(a.ip).map { nets.contains($0) } ?? false && !local.contains(a.ip) }
    }
}
public struct NetSet: Codable, Equatable, Sendable {
    public var what: String; public var on: Bool; public var sub: Int?
    /// For `what` = "rat": SIM slot and generation ("5G", "4G", "3G", "2G").
    public var slot: Int?; public var mode: String?
    public init(what: String, on: Bool, sub: Int? = nil, slot: Int? = nil, mode: String? = nil) {
        self.what = what; self.on = on; self.sub = sub; self.slot = slot; self.mode = mode
    }
}
/// Carrier (USSD) codes: `ussd.send` → `ussd.result`. Codes are `*` or `#`, then digits / `*` / `#`, ending in `#`, at most 32 characters.
public enum UssdCode {
    public static func isValid(_ s: String) -> Bool {
        let u = Array(s.utf8)
        guard (3...32).contains(u.count), u.first == 0x2A || u.first == 0x23, u.last == 0x23 else { return false }
        return u.dropFirst().allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2A || $0 == 0x23 }
    }
}
public struct UssdSend: Codable, Equatable, Sendable {
    public var code: String; public var sub: Int?
    public init(code: String, sub: Int? = nil) { self.code = code; self.sub = sub }
}
public struct UssdResult: Codable, Equatable, Sendable {
    public var ok: Bool; public var text: String?; public var err: String?
    public init(ok: Bool, text: String? = nil, err: String? = nil) { self.ok = ok; self.text = text; self.err = err }
}
/// Make the Android phone ring on its alarm stream (at most 30 s) / stop; answered, and later changed, by `phone.finding`.
public struct PhoneFind: Codable, Equatable, Sendable { public var on: Bool; public init(on: Bool) { self.on = on } }
public struct PhoneFinding: Codable, Equatable, Sendable { public var on: Bool; public var err: String?; public init(on: Bool, err: String? = nil) { self.on = on; self.err = err } }
/// `mode` is "normal", "vibrate" or "silent"; answered with `ringer.result`.
public struct RingerSet: Codable, Equatable, Sendable { public var mode: String; public init(mode: String) { self.mode = mode } }
public struct RingerResult: Codable, Equatable, Sendable {
    public var ok: Bool; public var mode: String?; public var err: String?
    public init(ok: Bool, mode: String? = nil, err: String? = nil) { self.ok = ok; self.mode = mode; self.err = err }
}
/// Changes the Android phone's microphone setting (answered with a `status`): `mic` "auto" | "mute" | "open" for
/// Android builds that report `helper.mic`, else the older switch `micMute`.
public struct AudioSet: Codable, Equatable, Sendable {
    public var micMute: Bool?
    public var mic: String?
    public init(micMute: Bool) { self.micMute = micMute }
    public init(mic: String) { self.mic = mic }
}
public struct SelftestMethod: Codable, Equatable, Sendable, Hashable {
    public var name: String; public var ok: Bool; public var note: String?
    public init(name: String, ok: Bool, note: String? = nil) { self.name = name; self.ok = ok; self.note = note }
}
public struct SelftestResult: Codable, Equatable, Sendable {
    public var methods: [SelftestMethod]; public var chosen: String?
    public init(methods: [SelftestMethod], chosen: String? = nil) { self.methods = methods; self.chosen = chosen }
}
/// Delete messages by id, or a whole conversation (`thread`). The Android phone deletes them and sends
/// `sms.deleted` to every client, so a deletion on the iPhone also disappears from the Mac.
public struct SmsDelete: Codable, Equatable, Sendable {
    public var ids: [Int64]?; public var thread: Int64?
    public init(ids: [Int64]? = nil, thread: Int64? = nil) { self.ids = ids; self.thread = thread }
}
public struct SmsDeleted: Codable, Equatable, Sendable {
    public var ok: Bool; public var ids: [Int64]; public var thread: Int64?; public var err: String?
    public init(ok: Bool, ids: [Int64], thread: Int64? = nil, err: String? = nil) { self.ok = ok; self.ids = ids; self.thread = thread; self.err = err }
}
/// Delete call-log entries; `all` clears the whole log ("Clear All").
public struct CallsDelete: Codable, Equatable, Sendable {
    public var ids: [Int64]?; public var all: Bool?
    public init(ids: [Int64]? = nil, all: Bool? = nil) { self.ids = ids; self.all = all }
}
public struct CallsDeleted: Codable, Equatable, Sendable {
    public var ok: Bool; public var ids: [Int64]; public var all: Bool?; public var err: String?
    public init(ok: Bool, ids: [Int64], all: Bool? = nil, err: String? = nil) { self.ok = ok; self.ids = ids; self.all = all; self.err = err }
}
/// Block / unblock a number (`what` = "block", `num`) or mute / unmute a conversation (`what` = "mute", `thread`).
public struct ListsSet: Codable, Equatable, Sendable {
    public var what: String; public var num: String?; public var thread: Int64?; public var on: Bool
    public init(what: String, num: String? = nil, thread: Int64? = nil, on: Bool) { self.what = what; self.num = num; self.thread = thread; self.on = on }
}
/// Blocked numbers and muted conversations as the Android phone keeps them (the source of truth).
/// `system`: the numbers are in Android's own block list, so the phone itself refuses those calls and texts.
public struct BlockLists: Codable, Equatable, Sendable {
    public var blocked: [String]; public var muted: [Int64]; public var system: Bool?; public var err: String?
    public init(blocked: [String] = [], muted: [Int64] = [], system: Bool? = nil, err: String? = nil) {
        self.blocked = blocked; self.muted = muted; self.system = system; self.err = err
    }
    public func isBlocked(_ number: String) -> Bool {
        let k = PhoneNumber.matchKey(number)
        return !k.isEmpty && blocked.contains { PhoneNumber.matchKey($0) == k }
    }
}
/// After a sync: every id that still exists on the Android phone, as inclusive ranges [[first, last], …].
/// Anything a client holds outside them was deleted (on the phone itself or by the other client).
public struct SyncIds: Codable, Equatable, Sendable {
    public var sms: [[Int64]]; public var calls: [[Int64]]
    /// More pages follow (a phone with very many gaps in its ids); apply only once the last one is in.
    public var more: Bool?
    public init(sms: [[Int64]], calls: [[Int64]], more: Bool? = nil) { self.sms = sms; self.calls = calls; self.more = more }

    /// Binary search over sorted, non-overlapping ranges.
    public static func contains(_ ranges: [[Int64]], _ id: Int64) -> Bool {
        var lo = 0, hi = ranges.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let r = ranges[mid]
            guard r.count == 2 else { return true }   // malformed: never delete because of it
            if id < r[0] { hi = mid - 1 } else if id > r[1] { lo = mid + 1 } else { return true }
        }
        return false
    }
}
public struct ErrorBody: Codable, Equatable, Sendable {
    public var msg: String
    /// Set when an extension message is refused (protocol §7.4): its type and why ("unknown-type", "too-large", "failed").
    public var t: String?; public var code: String?
    public init(msg: String, t: String? = nil, code: String? = nil) { self.msg = msg; self.t = t; self.code = code }
}

// MARK: - Audio channel

public struct AudioFrame: Equatable, Sendable {
    public enum Codec: UInt8, Sendable { case opus = 1, pcmu = 2 }
    public var codec: Codec
    public var seq: UInt16
    public var payload: Data

    public init(codec: Codec, seq: UInt16, payload: Data) {
        self.codec = codec; self.seq = seq; self.payload = payload
    }

    public func encoded() -> Data {
        var d = Data([codec.rawValue])
        d.appendBE(seq)
        return d + payload
    }

    public static func parse(_ d: Data) -> AudioFrame? {
        guard d.count >= 3, let c = Codec(rawValue: d[d.startIndex]) else { return nil }
        return AudioFrame(codec: c, seq: d.readBE16(at: 1), payload: Data(d.dropFirst(3)))
    }
}

/// What the iPhone does with a call state for a call it already hung up (the Android phone may still send states that
/// were on their way): drop them, ask again if the Android phone still reports the call 2 s later, forget after an end.
public struct HangUpGuard: Sendable {
    public enum Verdict: Equatable, Sendable { case pass, drop, dropAndResend }
    private var hungUp: [String: Date] = [:]
    public init() {}

    public mutating func hungUp(_ cid: String, at: Date = Date()) { hungUp[cid] = at }
    public mutating func reset() { hungUp.removeAll() }
    public func contains(_ cid: String) -> Bool { hungUp[cid] != nil }

    public mutating func check(cid: String, ended: Bool, now: Date = Date()) -> Verdict {
        guard let t = hungUp[cid] else { return .pass }          // any other call, including every new one
        if ended { hungUp[cid] = nil; return .pass }               // the end we wanted: let it close what is left
        let age = now.timeIntervalSince(t)
        if age > 30 { hungUp[cid] = nil; return .drop }            // give up asking; never resurrect it
        if age > 2 { hungUp[cid] = now; return .dropAndResend }
        return .drop
    }
}

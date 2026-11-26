import Foundation
import Perception
import TetherCore

struct Conversation: Identifiable, Hashable {
    var id: Int64 { thread }
    let thread: Int64
    let address: String
    let last: Sms
    let unread: Int
}

struct SyncBodyV1: Encodable {
    var sms: Int64
    var calls: Int64
    var contacts: String
    var codecs: [String]
    var client: ClientHello
    /// The extensions this app speaks (protocol §7.2); left out when there are none.
    var caps: [String]?
}

/// The link as the extensions see it. AppModel keeps `isUp`, `transport` and `inCall` current (main thread).
final class LinkExtensionPort: ExtensionPort {
    let link: TetherLink
    var isUp = false, transport = "none", inCall = false
    init(link: TetherLink) { self.link = link }
    func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int? {
        guard isUp else { return nil }
        return link.send(type, body, expectReply: expectReply, re: re)
    }
}

/// How this client introduces itself to the Android phone (the protocol allows several clients).
enum Platform {
    static let kind = "iphone"
    static let clientName = "iPhone"
}

/// The app's state and the glue between the BLE link, CallKit, audio and the UI.
@MainActor @Perceptible
final class AppModel {
    static let shared = AppModel()

    /// Link identity in the Keychain (a throw-away one in demo mode).
    let identity: IdentityStore = AppModel.demoMode || AppModel.runningTests ? MemoryIdentityStore() : KeychainIdentityStore()

    /// The app is the host of the unit tests (TetherTests): no Keychain, no saved data read or written, no
    /// notification permission prompt; the tests drive the model through `received` and `resetForTests`.
    static let runningTests: Bool = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    /// Debug demo mode (-TetherDemo…): made-up data, never connects and never saves, so it cannot touch the
    /// real pairing or the real synced data on a device that is also used for real.
    static let demoMode: Bool = {
        #if DEBUG
        let a = ProcessInfo.processInfo.arguments
        return a.contains("-TetherDemo") || a.contains("-TetherDemoCall")
        #else
        return false
        #endif
    }()
    @PerceptionIgnored let link: TetherLink
    @PerceptionIgnored private let store = SnapshotStore()
    /// Extensions (protocol §7, docs/EXTENDING.md): registered in `registerExtensions`; messages reach them only from
    /// the fallback of `received`, after every core type.
    @PerceptionIgnored let extensions = ExtensionRegistry(log: { LinkJournal.shared.add("ext: \($0)") })
    @PerceptionIgnored let extensionPort: LinkExtensionPort
    @PerceptionIgnored let echo = EchoExtension()
    /// The last result of the Debug developer row's echo test (Link tab).
    var extensionLine: String?
    @PerceptionIgnored lazy var calls = CallController(model: self)
    @PerceptionIgnored lazy var audio = CallAudio(model: self, link: link)

    private(set) var linkState: TetherLink.State = .unpaired
    private(set) var data: Snapshot
    var status: StatusBody? { data.lastStatus }
    var lastError: String?
    /// Short confirmation ("Copied") shown for a moment at the bottom.
    var notice: String?
    @PerceptionIgnored private var noticeTask: Task<Void, Never>?
    func flash(_ text: String) {
        notice = text
        noticeTask?.cancel()
        noticeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1.6))
            if !Task.isCancelled { self?.notice = nil }
        }
    }
    var lastSync: Date?
    var selftest: SelftestResult?
    /// Conversations read while the link was down: thread → newest id read, sent on reconnect.
    @PerceptionIgnored private var pendingRead: [Int64: Int64] = [:]
    /// Mobile data / hotspot / Bluetooth tethering on the Android phone; nil until first asked.
    var net: NetState?
    /// The switch that was just flipped and has not been confirmed yet ("data", "hotspot", "bt").
    var netPending: String?
    /// Phone section (Link tab): the last carrier-code answer, whether the Android phone is ringing for "find my phone", errors.
    var ussdResult: UssdResult?
    var ussdPending = false
    var finding = false
    var phoneToolError: String?
    var syncing = false
    var openThread: Int64?
    /// Scene is in the foreground (an open thread in a backgrounded app must still notify).
    var appActive = true { didSet { if appActive != oldValue { AppEvents.shared.add(appActive ? "app active" : "app inactive") }; if appActive && !oldValue { link.appBecameActive() } } }
    #if DEBUG
    /// Calls the -TetherAutoHangup debug option already scheduled to end.
    @PerceptionIgnored var autoHangupArmed = Set<String>()
    #endif
    var activeCalls: [String: CallInfo] = [:]
    /// The in-app call screen (CallKit shows system UI only for incoming calls).
    var callScreen: CallScreen? { didSet { if oldValue?.uuid != callScreen?.uuid || oldValue?.state != callScreen?.state || oldValue?.speaker != callScreen?.speaker { calls.updateProximity() } } }
    var showCallScreen = false
    /// Calls whose audio is on another client (protocol §5.1): "On … · Move here".
    var remoteCalls: [CallInfo] = []
    /// Figures from the last call's audio (Link tab).
    var lastAudioStats: CallAudio.Stats?
    /// This iPhone's microphone is refused or delivered only silence in the current / last call (call screen, Link tab).
    var micWarning: MicWarning?
    private var pendingSms: [Int: Sms] = [:]

    private(set) var nameIndex: [String: String] = [:]
    private(set) var photoIndex: [String: String] = [:]

    private init() {
        data = AppModel.demoMode || AppModel.runningTests ? Snapshot() : store.load()
        link = TetherLink(store: identity, restoreID: "app.tether.central")
        extensionPort = LinkExtensionPort(link: link)
        link.delegate = LinkBridge.shared
        link.log = { msg in NSLog("[link] %@", msg); LinkJournal.shared.add(msg) }
        rebuildIndex()
        registerExtensions()
        #if DEBUG
        loadDemoIfRequested()
        applyDebugLaunchOptions()
        debugAudioTest()
        #endif
    }

    #if DEBUG
    func injectDemo(sms: [Sms], contacts: [Contact], recents: [CallEntry], status: StatusBody, lists: BlockLists? = nil) {
        data.sms = sms; data.contacts = contacts; data.recents = recents; data.lastStatus = status; data.lists = lists
        rebuildIndex()
    }
    #endif

    /// Add your extension here (one line), see docs/EXTENDING.md.
    private func registerExtensions() {
        for e in [echo] as [TetherExtension] {
            do { try extensions.register(e) } catch { NSLog("[ext] not registered: %@", "\(error)") }
        }
        // An extension switched off after repeated failures: the Android phone learns it with a new sync.
        extensions.onCapsChanged = { [weak self] in self?.requestSync() }
        extensions.start()
    }

    /// Debug builds (Link › Developer): one x.tether.echo round trip to the Android phone.
    func echoTest() {
        guard let s = extensions.session(for: echo) else {
            extensionLine = "The Android phone does not speak x.tether.echo (older build, or not linked)"; return
        }
        echo.onResult = { [weak self] r in
            self?.extensionLine = r.error.map { "Refused: \($0)" } ?? "\(r.rttMs) ms round trip, clock offset \(r.offsetMs) ms, via \(r.via)"
        }
        extensionLine = echo.ping(s) == nil ? "Not sent" : "Waiting for the Android phone…"
    }

    var isPaired: Bool { identity.peerKey != nil }
    var peerName: String { identity.peerName ?? "Android" }

    // MARK: derived data

    var conversations: [Conversation] {
        var byThread: [Int64: [Sms]] = [:]
        for m in data.sms { byThread[m.thread, default: []].append(m) }
        return byThread.compactMap { thread, msgs in
            guard let last = msgs.max(by: { $0.date < $1.date }) else { return nil }
            let seen = data.readUpTo[thread] ?? 0
            let unread = msgs.filter { $0.incoming && !$0.read && $0.id > seen }.count
            return Conversation(thread: thread, address: last.addr, last: last, unread: unread)
        }.sorted { $0.last.date > $1.last.date }
    }

    var unreadTotal: Int { conversations.reduce(0) { $0 + $1.unread } }

    func messages(thread: Int64) -> [Sms] {
        data.sms.filter { $0.thread == thread }.sorted { ($0.date, $0.id) < ($1.date, $1.id) }
    }

    func thread(for address: String) -> Int64? {
        let key = PhoneNumber.matchKey(address)
        return data.sms.last(where: { PhoneNumber.matchKey($0.addr) == key })?.thread
    }

    func name(for number: String) -> String? { nameIndex[PhoneNumber.matchKey(number)] }
    func photo(for number: String) -> String? { photoIndex[PhoneNumber.matchKey(number)] }
    /// A contact's name, else the number in a left-to-right isolate (so "+98…" keeps its plus sign in Persian layout).
    func displayName(_ number: String) -> String { name(for: number) ?? (number.isEmpty ? String(localized: "Unknown") : "\u{2066}\(formatted(number))\u{2069}") }
    /// Country national numbers belong to: the default SIM's (from the Android phone), else this iPhone's region.
    var numberRegion: String? {
        let sim = sims.first(where: { $0.sub == status?.defaultSub && $0.country != nil }) ?? sims.first(where: { $0.country != nil })
        return sim?.country ?? Locale.current.region?.identifier
    }
    /// The number grouped for display ("+98 912 345 6789"); short codes, USSD and names unchanged. Display only.
    func formatted(_ number: String) -> String { PhoneFormat.format(number, region: numberRegion) }

    // MARK: missed calls (the Recents badge, like Phone)

    /// Missed calls newer than the last time Recents was looked at (a call the user declined is not missed;
    /// blocked numbers never count).
    var missedCount: Int {
        let seen = data.recentsSeenUpTo ?? (data.recents.map(\.id).max() ?? 0)
        return data.recents.filter { $0.type == 3 && $0.id > seen && !isBlocked($0.num) }.count
    }

    func markRecentsSeen() {
        // Nothing to mark before the first calls arrive (a 0 here would defeat the first-sync marker).
        guard !data.recents.isEmpty else { return }
        let top = data.recents.map(\.id).max() ?? 0
        if (data.recentsSeenUpTo ?? -1) < top { data.recentsSeenUpTo = top; persist(); Notifications.shared.updateBadge(unreadTotal + missedCount) }
    }

    var sims: [SimInfo] { status?.sims ?? [] }

    /// The SIM's own name ("Personal", "Work"), only when there are two SIMs (with one it says nothing).
    func simName(_ sub: Int?) -> String? {
        guard sims.count > 1, let sub, let sim = sims.first(where: { $0.sub == sub }) else { return nil }
        return sim.name.isEmpty ? String(localized: "SIM \(sim.slot + 1)") : sim.name
    }

    func markRead(thread: Int64) {
        let newest = data.sms.filter { $0.thread == thread }.map(\.id).max() ?? 0
        if (data.readUpTo[thread] ?? 0) < newest {
            data.readUpTo[thread] = newest
            // Read here means read everywhere: the Android phone and the other device follow.
            // (Placeholder ids of messages still being sent are negative and never the newest real id.)
            if newest > 0 {
                if linkState.isConnected { link.send(MsgType.smsRead, SmsRead(thread: thread, upTo: newest)) }
                else { pendingRead[thread] = newest }
            }
            persist()
            Notifications.shared.updateBadge(unreadTotal + missedCount)
        }
    }

    // MARK: delete, mute, block (the Android phone does it and tells every client)

    var blockLists: BlockLists { data.lists ?? BlockLists() }
    func isMuted(_ thread: Int64) -> Bool { blockLists.muted.contains(thread) }
    func isBlocked(_ number: String) -> Bool { blockLists.isBlocked(number) }

    private func requireLink() -> Bool {
        if linkState.isConnected { return true }
        lastError = String(localized: "The Android phone is not connected.")
        return false
    }

    func deleteConversation(_ thread: Int64) {
        // A conversation that only exists here (texts still waiting to be sent) needs no phone.
        if thread < 0 || !data.sms.contains(where: { $0.thread == thread && $0.id > 0 }) {
            applyDeleted(ids: [], thread: thread); return
        }
        guard requireLink() else { return }
        link.send(MsgType.smsDelete, SmsDelete(thread: thread), expectReply: true)
        applyDeleted(ids: [], thread: thread)   // at once; the phone's answer confirms (or the next sync restores)
    }

    func deleteMessages(_ ids: [Int64]) {
        let real = ids.filter { $0 > 0 }
        if !real.isEmpty {
            guard requireLink() else { return }
            link.send(MsgType.smsDelete, SmsDelete(ids: real), expectReply: true)
        }
        applyDeleted(ids: ids, thread: nil)
    }

    private func applyDeleted(ids: [Int64], thread: Int64?) {
        let gone = Set(ids)
        data.sms.removeAll { gone.contains($0.id) || (thread != nil && $0.thread == thread) }
        smsIndex = [:]
        if let thread { data.readUpTo[thread] = nil; Notifications.shared.clearSms(thread: thread) }
        if !ids.isEmpty { Notifications.shared.clearSms(ids: ids) }
        persist()
        Notifications.shared.updateBadge(unreadTotal + missedCount)
    }

    func deleteRecents(_ ids: [Int64]) {
        guard !ids.isEmpty, requireLink() else { return }
        link.send(MsgType.callsDelete, CallsDelete(ids: ids), expectReply: true)
        let gone = Set(ids)
        data.recents.removeAll { gone.contains($0.id) }
        persist()
    }

    func clearRecents() {
        guard requireLink() else { return }
        link.send(MsgType.callsDelete, CallsDelete(all: true), expectReply: true)
        data.recents = []
        persist()
    }

    func setBlocked(_ number: String, _ on: Bool) {
        guard requireLink() else { return }
        var l = blockLists
        let k = PhoneNumber.matchKey(number)
        l.blocked.removeAll { PhoneNumber.matchKey($0) == k }
        if on { l.blocked.append(number) }
        data.lists = l
        link.send(MsgType.listsSet, ListsSet(what: "block", num: number, on: on), expectReply: true)
    }

    func setMuted(_ thread: Int64, _ on: Bool) {
        guard requireLink() else { return }
        var l = blockLists
        l.muted.removeAll { $0 == thread }
        if on { l.muted.append(thread); Notifications.shared.clearSms(thread: thread) }
        data.lists = l
        link.send(MsgType.listsSet, ListsSet(what: "mute", thread: thread, on: on), expectReply: true)
    }

    func draft(for address: String) -> String { data.drafts[PhoneNumber.matchKey(address)] ?? "" }
    func setDraft(_ text: String, for address: String) {
        data.drafts[PhoneNumber.matchKey(address)] = text.isEmpty ? nil : text
        persist()
    }

    func preferredSim(for address: String) -> Int? {
        data.threadSim[PhoneNumber.matchKey(address)] ?? status?.defaultSub
    }
    func setPreferredSim(_ sub: Int?, for address: String) {
        data.threadSim[PhoneNumber.matchKey(address)] = sub
        persist()
    }

    private func rebuildIndex() {
        var n: [String: String] = [:], p: [String: String] = [:]
        for c in data.contacts {
            for ph in c.phones {
                let k = PhoneNumber.matchKey(ph.n)
                guard !k.isEmpty else { continue }
                n[k] = c.name
                if let photo = c.photo { p[k] = photo }
            }
        }
        nameIndex = n
        photoIndex = p
    }

    private func persist() { if !AppModel.demoMode && !AppModel.runningTests { store.save(data) } }
    /// Saved at once (the outbox of a text sent from a notification must survive the app being suspended).
    func persistNow() { if !AppModel.demoMode && !AppModel.runningTests { store.saveNow(data) } }

    /// Unit tests only: start from the given snapshot with no sync in progress.
    func resetForTests(_ snapshot: Snapshot = Snapshot()) {
        precondition(AppModel.runningTests)
        data = snapshot
        pendingIds = SyncIds(sms: [], calls: []); incomingContacts = []; contactsPageStart = true
        smsIndex = [:]; pendingSms = [:]; incrementalSync = false
        net = nil; netPending = nil; ussdResult = nil; ussdPending = false; finding = false; phoneToolError = nil
        lastError = nil; selftest = nil; syncing = false; openThread = nil
        extensions.linkDown(); extensionPort.isUp = false; extensionLine = nil
        rebuildIndex()
    }

    // MARK: actions

    func pair(_ code: PairingCode) {
        lastError = nil
        data = Snapshot()
        store.wipe()
        rebuildIndex()
        link.pair(with: code)
    }

    func unpair() {
        link.unpair()
        data = Snapshot()
        store.wipe()
        rebuildIndex()
    }

    /// True while an incremental sync runs: new incoming texts in it were missed while out of reach.
    @PerceptionIgnored private var incrementalSync = false

    func requestSync(full: Bool = false) {
        guard linkState.isConnected else { return }
        syncing = true
        incrementalSync = !full && !data.sms.isEmpty
        // From the last *completed* sync: a text that came in live during an interrupted sync must not make us
        // skip the rest of it.
        let lastSms = full ? 0 : (data.smsSyncedUpTo ?? (data.sms.map(\.id).max() ?? 0))
        let lastCall = full ? 0 : (data.recents.map(\.id).max() ?? 0)
        link.send(MsgType.sync, SyncBodyV1(sms: lastSms, calls: lastCall, contacts: full ? "" : data.contactsVersion,
                                           codecs: CallAudio.supportedCodecs,
                                           client: ClientHello(kind: Platform.kind, name: Platform.clientName),
                                           caps: extensions.caps.isEmpty ? nil : extensions.caps))
        // Conversations read while out of reach.
        for (thread, upTo) in pendingRead { link.send(MsgType.smsRead, SmsRead(thread: thread, upTo: upTo)) }
        pendingRead.removeAll()
        // Once: everything read on this device before read state was shared with the Android phone.
        if !UserDefaults.standard.bool(forKey: "readStateBackfilled") {
            UserDefaults.standard.set(true, forKey: "readStateBackfilled")
            for (thread, upTo) in data.readUpTo where upTo > 0 { link.send(MsgType.smsRead, SmsRead(thread: thread, upTo: upTo)) }
        }
    }

    /// Texts written while the link is down (e.g. a notification reply right after a background launch).
    /// Texts written while the link is down: kept in the snapshot, so a suspended or killed app still sends them.
    private var outbox: [OutboxItem] {
        get { data.outbox ?? [] }
        set { data.outbox = newValue.isEmpty ? nil : newValue }
    }

    func sendSms(to: String, body: String, sub: Int?) {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard text.utf8.count <= 12_000 else { lastError = String(localized: "Message too long"); return }
        // Local placeholder until the Android phone's provider row arrives through sms.new. Ids are negative and unique.
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let localId = -max(now, (data.sms.map { -$0.id }.max() ?? 0) + 1)
        let thread = thread(for: to) ?? -Int64(abs(PhoneNumber.matchKey(to).hashValue % 1_000_000))
        let placeholder = Sms(id: localId, thread: thread, addr: to, body: text, date: now, box: 4, read: true, sub: sub)
        data.sms.append(placeholder)
        setDraft("", for: to)
        if linkState.isConnected {
            let id = link.send(MsgType.smsSend, SmsSend(to: to, body: text, sub: sub), expectReply: true)
            pendingSms[id] = placeholder
        } else {
            outbox.append(OutboxItem(to: to, body: text, sub: sub, placeholder: localId))
            persist()
            lastError = String(localized: "Will send when the Android phone is back in reach")
        }
    }

    private func flushOutbox() {
        let items = outbox
        outbox = []
        persist()
        for o in items {
            let id = link.send(MsgType.smsSend, SmsSend(to: o.to, body: o.body, sub: o.sub), expectReply: true)
            if let p = data.sms.first(where: { $0.id == o.placeholder }) { pendingSms[id] = p }
        }
    }

    /// After a sync: a text still "sending" that nobody is waiting for any more (its answer was lost with the link)
    /// becomes "Not Delivered", so the user can see it and send it again. Never resent automatically: the Android phone may
    /// have sent it, and a resend would reach the recipient twice.
    private func failStalePlaceholders() {
        let waiting = Set(pendingSms.values.map(\.id)).union(outbox.map(\.placeholder))
        let old = Int64(Date().addingTimeInterval(-120).timeIntervalSince1970 * 1000)
        for i in data.sms.indices where data.sms[i].id < 0 && data.sms[i].box == 4 && data.sms[i].date < old && !waiting.contains(data.sms[i].id) {
            data.sms[i].box = 5
        }
    }

    func dial(_ number: String, sub: Int?) {
        calls.startOutgoing(number: number, sub: sub)
    }

    func addContact(name: String, phones: [Phone], emails: [String]) {
        link.send(MsgType.contactAdd, ContactAdd(name: name, phones: phones, emails: emails), expectReply: true)
    }

    func updateContact(id: Int64, name: String, phones: [Phone], emails: [String]) {
        // Show the edit at once; the Android phone's answer (and the re-sync it triggers) settles it.
        if let i = data.contacts.firstIndex(where: { $0.id == id }) {
            data.contacts[i].name = name; data.contacts[i].phones = phones; data.contacts[i].emails = emails
            rebuildIndex()
        }
        link.send(MsgType.contactUpdate, ContactUpdate(id: id, name: name, phones: phones, emails: emails), expectReply: true)
    }

    /// Favourites: star / unstar on the Android phone (shown at once; the phone's answer re-syncs contacts).
    func setStarred(_ id: Int64, _ on: Bool) {
        guard requireLink() else { return }
        if let i = data.contacts.firstIndex(where: { $0.id == id }) { data.contacts[i].starred = on }
        link.send(MsgType.contactStar, ContactStar(id: id, on: on), expectReply: true)
    }

    var favourites: [Contact] { data.contacts.filter(\.starred) }

    /// Chooses which SIM carries mobile data on the Android phone.
    func setDataSim(_ sub: Int) {
        guard linkState.isConnected else { return }
        netPending = "dataSub"
        net?.err = nil
        link.send(MsgType.netSet, NetSet(what: "dataSub", on: true, sub: sub), expectReply: true)
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(35))
            guard let self, self.netPending == "dataSub" else { return }
            self.netPending = nil
            self.net?.err = String(localized: "The Android phone did not answer.")
        }
    }

    /// Preferred network of a SIM slot on the Android phone (5G / 4G / 3G / 2G, "auto connect" like its own menu).
    func setNetworkMode(slot: Int, _ mode: String) {
        guard linkState.isConnected else { return }
        netPending = "rat\(slot)"
        net?.err = nil
        link.send(MsgType.netSet, NetSet(what: "rat", on: true, slot: slot, mode: mode), expectReply: true)
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(35))
            guard let self, self.netPending == "rat\(slot)" else { return }
            self.netPending = nil
            self.net?.err = String(localized: "The Android phone did not answer.")
        }
    }

    func refreshNet() {
        guard linkState.isConnected else { return }
        link.send(MsgType.netGet, Empty(), expectReply: true)
    }

    func setNet(_ what: String, on: Bool) {
        guard linkState.isConnected else { return }
        netPending = what
        net?.err = nil
        link.send(MsgType.netSet, NetSet(what: what, on: on), expectReply: true)
        // A lost reply must not leave the switches greyed out for ever.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(35))
            guard let self, self.netPending == what else { return }
            self.netPending = nil
            self.net?.err = String(localized: "The Android phone did not answer.")
        }
    }

    /// Carrier code (balance etc.) on a SIM of the Android phone; the answer arrives as `ussd.result` (up to 30 s).
    func sendUssd(_ code: String, sub: Int?) {
        let code = code.trimmingCharacters(in: .whitespaces)
        guard linkState.isConnected, !ussdPending else { return }
        guard UssdCode.isValid(code) else {
            ussdResult = UssdResult(ok: false, text: nil, err: String(localized: "Not a carrier code (like *123#)"))
            return
        }
        ussdPending = true; ussdResult = nil
        link.send(MsgType.ussdSend, UssdSend(code: code, sub: sub), expectReply: true)
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(40))
            guard let self, self.ussdPending else { return }
            self.ussdPending = false
            self.ussdResult = UssdResult(ok: false, text: nil, err: String(localized: "The Android phone did not answer."))
        }
    }

    func setFinding(_ on: Bool) {
        guard linkState.isConnected else { return }
        phoneToolError = nil
        link.send(MsgType.phoneFind, PhoneFind(on: on), expectReply: true)
    }

    func setRinger(_ mode: String) {
        guard linkState.isConnected else { return }
        phoneToolError = nil
        link.send(MsgType.ringerSet, RingerSet(mode: mode), expectReply: true)
    }

    /// The Android phone's "mute its own microphone during bridged calls"; shown at once, confirmed by its next status.
    func setPhoneMicMute(_ on: Bool) {
        guard linkState.isConnected, var st = data.lastStatus, st.helper.micMute != nil else { return }
        link.send(MsgType.audioSet, AudioSet(micMute: on))
        st.helper.micMute = on
        data.lastStatus = st
    }

    /// The Android phone's "its own microphone during bridged calls" ("auto", "mute", "open"; Android builds that report
    /// `helper.mic`); shown at once, confirmed by its next status.
    func setPhoneMic(_ mic: String) {
        guard linkState.isConnected, var st = data.lastStatus, st.helper.mic != nil else { return }
        link.send(MsgType.audioSet, AudioSet(mic: mic))
        st.helper.mic = mic
        st.helper.micMute = mic == "mute"
        data.lastStatus = st
    }

    func runSelftest() {
        selftest = nil
        link.send(MsgType.selftest, Empty(), expectReply: true)
    }

    // MARK: link events (main actor)

    func linkChanged(_ s: TetherLink.State) {
        let wasConnected = linkState.isConnected
        linkState = s
        AppEvents.shared.add("link state: \(s.reportName)")
        if case .connected(let t) = s { extensionPort.transport = t }
        if s.isConnected && !wasConnected {
            // Before the sync: it carries what the extensions speak.
            extensionPort.isUp = true
            extensions.linkUp(extensionPort)
            lastError = nil
            requestSync()
            flushOutbox()
            // Paired and linked: ask for the microphone now, not when a call is answered from the lock screen.
            Microphone.askIfNeeded(paired: isPaired, foreground: appActive)
        }
        if !s.isConnected && wasConnected {
            extensionPort.isUp = false
            extensions.linkDown()
            calls.linkLost()
            remoteCalls = []
            // A sync cut off mid-way must start clean next time.
            contactsPageStart = true
            incomingContacts = []
            pendingIds = SyncIds(sms: [], calls: [])
            // Answers to texts in flight are lost with the link: the next sync settles them (the real row replaces
            // a sent placeholder; an unconfirmed one turns Not Delivered after 2 min, never resent).
            pendingSms = [:]
            syncing = false
        }
    }

    func linkFailed(_ reason: String) {
        AppEvents.shared.add("link failed: \(reason)")
        lastError = reason
    }

    func received(_ h: MessageHeader, _ payload: Data) {
        do {
            switch h.t {
            case MsgType.status:
                // Kept in memory; saved with the next real change (a full snapshot write per status was wasted).
                data.lastStatus = try Control.body(StatusBody.self, from: payload)
                extensions.peerCaps(data.lastStatus?.caps)   // absent (older Android builds): no extensions
            case MsgType.smsBatch:
                let b = try Control.body(SmsBatch.self, from: payload)
                let recent = Int64(Date().addingTimeInterval(-6 * 3600).timeIntervalSince1970 * 1000)
                merge(sms: b.items, notify: incrementalSync && b.items.contains { $0.incoming && !$0.read && $0.date > recent })
                if !b.more {
                    syncing = false; lastSync = Date()
                    data.smsSyncedUpTo = data.sms.map(\.id).filter { $0 > 0 }.max()
                    failStalePlaceholders()
                    persist()
                }
            case MsgType.smsRead:
                // Read on the other device (or re-announced): clear the unread marks here too.
                let r = try Control.body(SmsRead.self, from: payload)
                if (data.readUpTo[r.thread] ?? 0) < r.upTo {
                    data.readUpTo[r.thread] = r.upTo
                    persist()
                    Notifications.shared.clearSms(thread: r.thread)
                    Notifications.shared.updateBadge(unreadTotal + missedCount)
                }
            case MsgType.smsUnread:
                // Read on the Android phone itself (or the other device): unread marks and notifications follow.
                let u = try Control.body(SmsUnread.self, from: payload)
                var changed = Set<Int64>()
                for i in data.sms.indices where data.sms[i].incoming && !data.sms[i].read && data.sms[i].id > 0 {
                    if u.isRead(data.sms[i].id) == true { data.sms[i].read = true; changed.insert(data.sms[i].thread) }
                }
                if !changed.isEmpty {
                    for t in changed where !conversations.contains(where: { $0.thread == t && $0.unread > 0 }) { Notifications.shared.clearSms(thread: t) }
                    persist()
                    Notifications.shared.updateBadge(unreadTotal + missedCount)
                }
            case MsgType.smsDeleted:
                let d = try Control.body(SmsDeleted.self, from: payload)
                if d.ok { applyDeleted(ids: d.ids, thread: d.thread) }
                else { lastError = String(localized: "Not deleted") + (d.err.map { ": \($0)" } ?? ""); requestSync(full: true) }
            case MsgType.callsDeleted:
                let d = try Control.body(CallsDeleted.self, from: payload)
                if d.ok {
                    if d.all == true { data.recents = [] } else { let g = Set(d.ids); data.recents.removeAll { g.contains($0.id) } }
                    persist()
                } else {
                    lastError = String(localized: "Not deleted") + (d.err.map { ": \($0)" } ?? "")
                    requestSync(full: true)
                }
            case MsgType.lists:
                let l = try Control.body(BlockLists.self, from: payload)
                if let err = l.err { lastError = err }
                data.lists = l
                for t in l.muted { Notifications.shared.clearSms(thread: t) }
                persist()
            case MsgType.syncIds:
                // Whatever the phone no longer has was deleted there or on the other client.
                let page = try Control.body(SyncIds.self, from: payload)
                pendingIds.sms += page.sms; pendingIds.calls += page.calls
                if page.more == true { break }
                let s = pendingIds
                pendingIds = SyncIds(sms: [], calls: [])
                let before = (data.sms.count, data.recents.count)
                let goneSms = data.sms.filter { $0.id > 0 && !SyncIds.contains(s.sms, $0.id) }.map(\.id)
                data.recents.removeAll { !SyncIds.contains(s.calls, $0.id) }
                if !goneSms.isEmpty { applyDeleted(ids: goneSms, thread: nil) }
                if (data.sms.count, data.recents.count) != before { persist() }
            case MsgType.smsNew:
                let b = try Control.body(SmsNew.self, from: payload)
                merge(sms: [b.item], notify: b.item.incoming)
            case MsgType.smsResult:
                let r = try Control.body(SmsResult.self, from: payload)
                if let re = h.re, let p = pendingSms[re] {
                    if let i = data.sms.firstIndex(where: { $0.id == p.id }) { data.sms[i].box = r.ok ? 2 : 5 }
                    if r.state != "sent" { pendingSms[re] = nil }
                    if !r.ok { lastError = String(localized: "Message not sent") + (r.err.map { ": \($0)" } ?? "") }
                }
            case MsgType.contactsBatch:
                let b = try Control.body(ContactsBatch.self, from: payload)
                // A full sync arrives in pages; swap the list in once the last page is here.
                if b.full && contactsPageStart { incomingContacts = [] }
                contactsPageStart = !b.more
                incomingContacts += b.items
                if !b.more {
                    data.contacts = incomingContacts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                    data.contactsVersion = b.version
                    incomingContacts = []
                    rebuildIndex()
                    persist()
                }
            case MsgType.contactResult:
                let r = try Control.body(ContactResult.self, from: payload)
                if !r.ok { lastError = r.err.map { String(localized: "Contact not saved") + ": " + $0 } ?? String(localized: "Contact not saved") }
                // Saved or not, fetch the Android phone's version so both sides agree again.
                requestSync()
            case MsgType.callsBatch:
                let b = try Control.body(CallsBatch.self, from: payload)
                // First sync (new pairing): its whole history (all its pages) counts as seen, not as hundreds missed.
                // (Int64.max marks a first sync in progress, saved with the snapshot: it survives the app being killed.)
                if data.recentsSeenUpTo == nil { data.recentsSeenUpTo = data.recents.isEmpty ? Int64.max : (data.recents.map(\.id).max() ?? 0) }
                let firstCallSync = data.recentsSeenUpTo == Int64.max
                // A call missed in the last 10 minutes that we did not know yet: notify, as Phone does.
                let known = data.recents.map(\.id).max() ?? 0
                let fresh = Int64(Date().addingTimeInterval(-600).timeIntervalSince1970 * 1000)
                for c in b.items where (c.type == 3) && c.id > known && c.date > fresh && !isBlocked(c.num) && !data.recents.isEmpty && !firstCallSync {
                    Notifications.shared.showMissedCall(c, from: c.name ?? displayName(c.num), sim: simName(c.sub))
                }
                var byId = Dictionary(uniqueKeysWithValues: data.recents.map { ($0.id, $0) })
                for c in b.items { byId[c.id] = c }
                data.recents = byId.values.sorted { $0.date > $1.date }
                if data.recents.count > 3000 { data.recents.removeLast(data.recents.count - 3000) }
                if firstCallSync && !b.more { data.recentsSeenUpTo = data.recents.map(\.id).max() ?? 0 }
                persist()
                Notifications.shared.updateBadge(unreadTotal + missedCount)
            case MsgType.callState:
                let c = try Control.body(CallInfo.self, from: payload)
                updateCall(c)
            case MsgType.callList:
                let l = try Control.body(CallList.self, from: payload)
                for c in l.calls { updateCall(c) }
                calls.reconcile(with: l.calls)
                remoteCalls = calls.remote.values.sorted { $0.cid < $1.cid }
            case MsgType.callDialed:
                let r = try Control.body(OkResult.self, from: payload)
                if !r.ok { calls.dialFailed(r.err ?? "") ; lastError = String(localized: "Call failed") + (r.err.map { ": \($0)" } ?? "") }
                else { calls.dialAccepted() }
            case MsgType.audioStart:
                audio.configure(try Control.body(AudioStart.self, from: payload))
            case MsgType.audioStop:
                audio.remoteStopped()
            case MsgType.audioStats:
                LinkJournal.shared.add("Android phone call audio: " + (try Control.body(AudioStats.self, from: payload)).summary)
            case MsgType.netState:
                let st = try Control.body(NetState.self, from: payload)
                // An error reply carries no switch values: keep the last known ones.
                if st.data == nil, var old = net {
                    // Sharing state comes from Tether itself even when the helper did not answer.
                    old.err = st.err
                    if let s = st.share { old.share = s; old.proxy = st.proxy }
                    net = old
                } else { net = st }
                // (A state pushed because the other client flipped a switch must not clear our own pending one.)
                if h.re != nil { netPending = nil }
            case MsgType.ussdResult:
                ussdResult = try Control.body(UssdResult.self, from: payload)
                ussdPending = false
            case MsgType.phoneFinding:
                let f = try Control.body(PhoneFinding.self, from: payload)
                finding = f.on
                if let e = f.err { phoneToolError = e }
            case MsgType.ringerResult:
                let r = try Control.body(RingerResult.self, from: payload)
                if let m = r.mode, var st = data.lastStatus { st.ringer = m; data.lastStatus = st }
                if let e = r.err { phoneToolError = e }
            case MsgType.selftestResult:
                selftest = try Control.body(SelftestResult.self, from: payload)
            default:
                // Extension types (x.…) and errors answering an extension's request; everything else stays ignored.
                _ = extensions.route(h, payload)
            }
        } catch {
            NSLog("[model] bad %@: %@", h.t, "\(error)")
        }
    }

    @PerceptionIgnored private var contactsPageStart = true
    /// sync.ids pages collected until the last one (ranges stay sorted: pages come in order).
    @PerceptionIgnored private var pendingIds = SyncIds(sms: [], calls: [])
    @PerceptionIgnored private var incomingContacts: [Contact] = []

    private func updateCall(_ c: CallInfo) {
        if c.isEnded { activeCalls[c.cid] = nil } else { activeCalls[c.cid] = c }
        extensionPort.inCall = !activeCalls.isEmpty
        calls.handle(c)
        remoteCalls = calls.remote.values.sorted { $0.cid < $1.cid }
        #if DEBUG
        debugAutoAnswer(c)
        #endif
        // An answered or outgoing call brings the call screen up when the app is in front.
        if !c.isEnded && c.state != "ringing" && callScreen != nil && !showCallScreen && !callScreenDismissed { showCallScreen = true }
    }

    /// The user minimised the call screen for this call (a banner lets them return).
    var callScreenDismissed = false

    /// id → position in `data.sms`, so a sync page is merged in O(page) rather than O(all messages).
    @PerceptionIgnored private var smsIndex: [Int64: Int] = [:]

    private func merge(sms: [Sms], notify: Bool) {
        guard !sms.isEmpty else { return }
        var arr = data.sms
        if smsIndex.count != arr.count { smsIndex = Dictionary(arr.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { a, _ in a }) }
        var fresh: [Sms] = []
        for m in sms {
            if let i = smsIndex[m.id] { arr[i] = m } else { smsIndex[m.id] = arr.count; arr.append(m); fresh.append(m) }
        }
        // Drop local placeholders (negative ids) that the provider has now confirmed (same address + body).
        if arr.contains(where: { $0.id < 0 }) {
            // A provider row confirms a placeholder only if it was written after the placeholder (minus clock skew),
            // so an old identical text ("Ok") in history does not swallow a new one.
            let outgoing = sms.filter { !$0.incoming }
            if !outgoing.isEmpty {
                arr.removeAll { p in
                    guard p.id < 0, let real = outgoing.first(where: { $0.date >= p.date - 120_000 && PhoneNumber.matchKey($0.addr) == PhoneNumber.matchKey(p.addr) && $0.body == p.body }) else { return false }
                    // A new conversation's first text: its screen follows the real conversation (it went blank).
                    if p.thread != real.thread, openThread == p.thread { Router.shared.openThread = real.thread }
                    return true
                }
                smsIndex = [:]
            }
        }
        data.sms = arr
        if smsIndex.isEmpty { smsIndex = Dictionary(arr.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { a, _ in a }) }
        persist()
        if notify {
            // Only texts that are still unread and recent: a reconnect must not replay old or already-read ones.
            let recent = Int64(Date().addingTimeInterval(-6 * 3600).timeIntervalSince1970 * 1000)
            for m in fresh where m.incoming && !m.read && m.date > recent && !(appActive && openThread == m.thread) && !isMuted(m.thread) && !isBlocked(m.addr) {
                Notifications.shared.showSms(m, from: displayName(m.addr), photo: photo(for: m.addr), sim: simName(m.sub))
            }
            Notifications.shared.updateBadge(unreadTotal + missedCount)
        }
    }

    func sendAudio(_ f: AudioFrame) { link.sendAudio(f) }
}

/// Hops link delegate callbacks (link queue) onto the main actor.
final class LinkBridge: TetherLinkDelegate {
    static let shared = LinkBridge()

    // DispatchQueue.main keeps events in order ("ringing" before "ended"); separate Tasks do not promise that.
    func link(_ link: TetherLink, didChange state: TetherLink.State) {
        DispatchQueue.main.async { MainActor.assumeIsolatedCompat { AppModel.shared.linkChanged(state) } }
    }

    func link(_ link: TetherLink, didReceive header: MessageHeader, data: Data) {
        DispatchQueue.main.async { MainActor.assumeIsolatedCompat { AppModel.shared.received(header, data) } }
    }

    func link(_ link: TetherLink, didReceiveAudio frame: AudioFrame) {
        // Audio stays off the main thread.
        CallAudio.incoming(frame)
    }

    func link(_ link: TetherLink, didFail reason: String) {
        DispatchQueue.main.async { MainActor.assumeIsolatedCompat { AppModel.shared.linkFailed(reason) } }
    }
}

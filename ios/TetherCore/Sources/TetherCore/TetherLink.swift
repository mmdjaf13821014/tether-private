// The CoreBluetooth central exists on Apple platforms only; everything else in TetherCore builds everywhere.
#if canImport(CoreBluetooth)
import CoreBluetooth
import CryptoKit
import Foundation

public enum TetherUUID {
    public static let service = CBUUID(string: "5ABF5C3C-2BF1-4B1C-9E8E-5D153F835BD5")
    public static let psm = CBUUID(string: "6C0D1EB1-67F4-4181-9752-9CC90D6EFF88")
    public static let rx = CBUUID(string: "214E1CD9-F397-4A0F-BB6C-ED1D8C7A38A5")
    public static let tx = CBUUID(string: "7BA52139-0888-4234-A49D-0271E0F2A647")
    public static let wake = CBUUID(string: "9A3F1C52-7E0B-4D8A-B1E6-2C5D4F7A8E31")
}

/// Where the link keeps its long-term state (Keychain on iOS, a file for the Mac test tool).
public protocol IdentityStore: AnyObject {
    /// Our static P-256 private key (raw 32 bytes), created on first use.
    func staticKey() -> P256.KeyAgreement.PrivateKey
    var peerKey: Data? { get set }
    var peerName: String? { get set }
    var peripheralID: UUID? { get set }
}

public protocol TetherLinkDelegate: AnyObject {
    func link(_ link: TetherLink, didChange state: TetherLink.State)
    func link(_ link: TetherLink, didReceive header: MessageHeader, data: Data)
    func link(_ link: TetherLink, didReceiveAudio frame: AudioFrame)
    func link(_ link: TetherLink, didFail reason: String)
}

/// BLE central side of the Tether link: discovery, L2CAP (or GATT fallback), handshake, sealed records,
/// keepalive and endless reconnect. All work happens on `queue`; delegate calls arrive on `queue`.
public final class TetherLink: NSObject {
    public enum State: Equatable, Sendable {
        case poweredOff, unauthorized, unpaired, searching, connecting, handshaking
        case connected(transport: String)

        public var isConnected: Bool { if case .connected = self { return true } else { return false } }
    }

    public weak var delegate: TetherLinkDelegate?
    public let queue: DispatchQueue
    public private(set) var state: State = .unpaired { didSet { if state != oldValue { delegate?.link(self, didChange: state) } } }
    public var log: (String) -> Void = { print("[link] \($0)") }

    private let store: IdentityStore
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var pairing: PairingCode?
    private var channel: CBL2CAPChannel?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var transport = "none"
    private var reader = RecordReader()
    private var outBuffer = Data()
    private var handshake: CentralHandshake?
    private var session: SecureSession?
    /// When bytes last arrived from the Android phone (a wake checks that the session still answers).
    private var lastReceivedAt = Date.distantPast
    private var keys: SessionKeys?
    /// Request ids, taken from any thread without waiting for the link queue (`send` is called from the main thread,
    /// and a `queue.sync` there waited behind whatever the link queue was doing, e.g. a big sync page).
    private let ids = RequestIds()
    private var keepalive: DispatchSourceTimer?
    private var l2capTimer: DispatchWorkItem?
    private var audioBacklog = 0
    /// Round trip of the last keep-alive ping over the link, in ms (nil until one came back).
    public private(set) var lastRoundTripMs: Int?
    /// Measures the round trip now (call start), without waiting for the 25 s keep-alive.
    public func measureRoundTrip() { queue.async { if self.session != nil { self.sendNow(MsgType.ping, PingBody(ts: Int64(Date().timeIntervalSince1970 * 1000))) } } }

    /// - Parameters:
    ///   - store: Where the link keeps its identity and the paired phone.
    ///   - restoreID: CoreBluetooth state-restoration identifier (iOS background relaunch).
    ///   - queue: The queue all link work and every delegate call runs on.
    public init(store: IdentityStore, restoreID: String? = nil, queue: DispatchQueue = DispatchQueue(label: "tether.link")) {
        self.store = store
        self.queue = queue
        super.init()
        var opts: [String: Any] = [CBCentralManagerOptionShowPowerAlertKey: true]
        #if os(iOS)
        if let restoreID { opts[CBCentralManagerOptionRestoreIdentifierKey] = restoreID }
        #endif
        central = CBCentralManager(delegate: self, queue: queue, options: opts)
    }

    public var isPaired: Bool { store.peerKey != nil }

    // MARK: - Public API (any thread)

    public func pair(with code: PairingCode) {
        queue.async {
            self.log("pairing with \(code.deviceName)")
            self.pairing = code
            self.store.peerKey = code.peripheralKey
            self.store.peerName = code.deviceName
            self.store.peripheralID = nil
            self.disconnect(reason: "pairing")
            self.startIfReady()
        }
    }

    public func unpair() {
        queue.async {
            if self.state.isConnected { self.sendNow(MsgType.unpair, Empty()) }
            self.queue.asyncAfter(deadline: .now() + 0.3) {
                self.store.peerKey = nil
                self.store.peerName = nil
                self.store.peripheralID = nil
                self.pairing = nil
                self.disconnect(reason: "unpaired")
                self.central.stopScan()
                self.state = .unpaired
            }
        }
    }

    /// Sends a control message; returns the request id (use it to match `re` in replies). `re`: this answers the
    /// Android phone's request with that id (extensions, protocol §7).
    @discardableResult
    public func send<B: Encodable>(_ type: String, _ body: B, expectReply: Bool = false, re: Int? = nil) -> Int {
        let id = ids.next()
        queue.async { self.sendNow(type, body, id: expectReply ? id : nil, re: re) }
        return id
    }

    public func sendAudio(_ frame: AudioFrame) {
        queue.async {
            guard self.state.isConnected, let session = self.session else { return }
            // Drop-late: if the transport is backed up by more than ~100 ms of audio, skip this frame.
            if self.outBuffer.count > 4096 || self.audioBacklog > 5 { return }
            guard let r = try? session.seal(channel: Proto.channelAudio, frame.encoded()) else { return }
            self.write(r.encoded())
        }
    }

    // MARK: - Internals

    private func sendNow<B: Encodable>(_ type: String, _ body: B, id: Int? = nil, re: Int? = nil) {
        guard state.isConnected, let session else { return }
        do {
            let data = try Control.encode(type, body, id: id, re: re)
            // A record body holds at most 65534 bytes (seal adds 16): refuse instead of crashing in encoded().
            guard data.count <= 65_000 else { log("send \(type) refused: \(data.count) bytes is over the record limit"); return }
            write(try session.seal(channel: Proto.channelControl, data).encoded())
        } catch {
            log("send \(type) failed: \(error)")
        }
    }

    private static func phase(_ p: CBPeripheral?) -> LinkStart.Phase {
        guard let p else { return .none }
        switch p.state {
        case .connecting: return .connecting
        case .connected: return .connected
        case .disconnecting: return .disconnecting
        default: return .disconnected
        }
    }

    private func startIfReady() {
        let busy = settingUp || session != nil || handshake != nil || channel != nil || transport != "none"
        switch LinkStart.step(poweredOn: central.state == .poweredOn, paired: isPaired, held: Self.phase(peripheral), sessionOrSetup: busy) {
        case .notReady, .waitForConnect, .keep: return
        case .unpaired: state = .unpaired; return
        case .resume:
            // Restored by iOS (relaunch after suspension/termination) still connected, but our L2CAP
            // channel and session died with the old process: set them up again.
            if let p = peripheral {
                settingUp = true
                log("resuming restored connection")
                state = .connecting
                p.delegate = self
                armSetupWatchdog()
                p.discoverServices([TetherUUID.service])
            }
            return
        case .connect: break
        }
        if let id = store.peripheralID, let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            log("pending connect to known peripheral \(id)")
            connect(p)
        }
        // Scan as well: the identifier changes until bonding, and after a reset of the Android phone's Bluetooth.
        if peripheral == nil || pairing != nil {
            state = .searching
        }
        central.scanForPeripherals(withServices: [TetherUUID.service], options: nil)
    }

    /// The app came to the foreground. Without a session, start over: a pending connect iOS has not completed (for
    /// example issued while the Android phone held its own LE link) is cancelled and issued again, with a scan, and
    /// the back-off from earlier failures is forgotten: opening the app must link within seconds.
    public func appBecameActive() { connectAfresh("app active without a session: connecting afresh") }

    private func connectAfresh(_ why: String) {
        queue.async {
            guard self.isPaired, self.session == nil, !self.settingUp else { return }
            self.log(why)
            self.failures.removeAll(); self.retryItem?.cancel(); self.retryItem = nil
            self.renewPendingConnect()
            self.startIfReady()
        }
    }

    /// A connect still pending after 10 s while the app runs is renewed (the timer does not run while suspended).
    private var connectWatchdog: DispatchWorkItem?

    /// didConnect came for the current connect (a nil-error disconnect before it is our own cancellation).
    private var connectedThisAttempt = false

    /// Cancels a pending connect and forgets its peripheral at once (LinkStart.renew): CoreBluetooth may report it as
    /// connecting for a while after the cancel, and a held one made startIfReady wait for a connect that never came.
    /// Its late disconnect (no error, never connected) is recognised as our own cancellation.
    private func renewPendingConnect() {
        let r = LinkStart.renew(Self.phase(peripheral))
        if r.cancel, let p = peripheral { central.cancelPeripheralConnection(p) }
        if r.forget { peripheral = nil; connectedThisAttempt = false }
    }

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        state = .connecting
        if p.state != .connected { connectedThisAttempt = false }
        connectWatchdog?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, let cur = self.peripheral, cur.state == .connecting, !self.settingUp, self.session == nil else { return }
            self.log("connect still pending after 10 s: renewing")
            self.renewPendingConnect()
            self.startIfReady()
        }
        connectWatchdog = w
        queue.asyncAfter(deadline: .now() + 10, execute: w)
        // Never times out: iOS keeps it pending until the Android phone is in range, also while we are suspended.
        // No CBConnectPeripheralOptionEnableAutoReconnect: without the isReconnecting delegate variant iOS
        // rejects the connect with "One or more parameters were invalid". Our own retry re-issues connect().
        central.connect(p, options: [CBConnectPeripheralOptionNotifyOnDisconnectionKey: false])
    }

    private func disconnect(reason: String) {
        resetSession(reason: reason)
        log("disconnect: \(reason)")
        if let p = peripheral {
            if p.state == .connected { cancelledByUs = p.identifier }   // a connected link reports its end
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
    }

    /// Ends the session (channel, keys, buffers, timers) but not the Bluetooth connection.
    private func resetSession(reason: String) {
        // Link statistics: every session's transport, length and end reason (Link › Link log).
        if let since = sessionSince {
            log("session over \(transport) ended after \(Int(Date().timeIntervalSince(since))) s: \(reason)")
            sessionSince = nil
        }
        partialSince = nil
        keepalive?.cancel(); keepalive = nil
        setupWatchdog?.cancel(); setupWatchdog = nil
        l2capTimer?.cancel(); l2capTimer = nil
        if let ch = channel {
            let i: InputStream? = ch.inputStream, o: OutputStream? = ch.outputStream
            i?.close(); o?.close()
        }
        channel = nil
        session = nil
        handshake = nil
        keys = nil
        reader = RecordReader()
        outBuffer = Data()
        audioBacklog = 0
        transport = "none"
        settingUp = false
    }

    /// Sessions restarted in a row on a connection that stayed up (reset when a session comes up).
    private var restartsOnOpenConnection = 0

    private func failAndRetry(_ reason: String) {
        delegate?.link(self, didFail: reason)
        // The session ended but the Bluetooth connection is still up (the Android phone closed the channel, a stalled
        // stream, a protocol error): start a new session on it. Cancelling and connecting again does not work then:
        // iOS does not complete a new connect while an LE link to the Android phone still exists, and that link
        // stays up for tens of seconds (seen 2026-11-22: relinks of 15 s to over a minute). Twice in a row at most.
        if let p = peripheral, p.state == .connected, restartsOnOpenConnection < 2, pairing == nil,
           !reason.hasPrefix("setup timed out"), central.state == .poweredOn {
            restartsOnOpenConnection += 1
            resetSession(reason: reason)
            log("restarting the session on the open connection (\(reason))")
            settingUp = true
            state = .connecting
            p.delegate = self
            armSetupWatchdog()
            p.discoverServices([TetherUUID.service])
            return
        }
        restartsOnOpenConnection = 0
        disconnect(reason: reason)
        reconnectSoon()
    }

    /// Recent failures, to back off when setup keeps failing instead of looping.
    private var failures: [Date] = []

    /// Re-issues the pending connect right away: a pending connect is the only thing that still works while
    /// iOS keeps the app suspended (timers do not run then, so a timer-only retry could wait minutes for the
    /// next wake-up). After repeated failures a short timer adds a pause before the next attempt.
    private func reconnectSoon() {
        let now = Date()
        let step = ReconnectPolicy.next(failures: failures, now: now)
        failures = step.failures
        if case .atOnce = step.action {
            if let id = store.peripheralID, central.state == .poweredOn, isPaired,
               let p = central.retrievePeripherals(withIdentifiers: [id]).first {
                log("reconnecting at once")
                connect(p)
            }
            scheduleRetry(after: 1)   // also scans, in case the Android phone's identifier changed
        } else {
            // Backing off: no pending connect, but a scan, which iOS keeps running while we are suspended and
            // which wakes us when the Android phone advertises (a timer alone would not run until the app is opened).
            if central.state == .poweredOn, isPaired { central.scanForPeripherals(withServices: [TetherUUID.service], options: nil) }
            if case .backOff(let seconds) = step.action { scheduleRetry(after: seconds) }
        }
    }

    /// Setup (services → PSM → L2CAP → handshake) normally takes well under a second. If it stalls (seen when
    /// the iPhone reconnects while the Android phone still holds the old, dead link), drop it and start over instead of
    /// waiting for iOS's 30 s ATT timeout.
    private var setupWatchdog: DispatchWorkItem?
    private func armSetupWatchdog() {
        setupWatchdog?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, !self.state.isConnected, self.peripheral?.state == .connected else { return }
            self.failAndRetry("setup timed out")
        }
        setupWatchdog = item
        queue.asyncAfter(deadline: .now() + 12, execute: item)
    }

    /// Exactly one pending retry at a time (stream end + disconnect + errors used to stack several).
    private var retryItem: DispatchWorkItem?
    private func scheduleRetry(after seconds: Double) {
        retryItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.retryItem = nil; self?.startIfReady() }
        retryItem = item
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    /// True from didConnect until the session is up or torn down: blocks a second, overlapping setup.
    private var settingUp = false
    /// Identifier of the peripheral whose connection we cancelled ourselves (its didDisconnect is expected).
    private var cancelledByUs: UUID?

    // MARK: transport

    private func openTransport(_ name: String) {
        transport = name
        reader = RecordReader()
        guard let peerKey = store.peerKey else { failAndRetry("no peer key"); return }
        let hs = CentralHandshake(staticKey: store.staticKey(), peerStatic: peerKey, token: pairing?.token)
        handshake = hs
        state = .handshaking
        write(hs.hello().encoded())
    }

    private func write(_ bytes: Data) {
        outBuffer.append(bytes)
        flush()
    }

    private func flush() {
        if let ch = channel {
            let maybeOut: OutputStream? = ch.outputStream
            guard let out = maybeOut else { return }
            while !outBuffer.isEmpty && out.hasSpaceAvailable {
                let n = outBuffer.withUnsafeBytes { out.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: min($0.count, 2048)) }
                if n <= 0 { break }
                outBuffer.removeFirst(n)
            }
        } else if let p = peripheral, let rx = rxChar {
            let chunk = max(20, p.maximumWriteValueLength(for: .withoutResponse))
            while !outBuffer.isEmpty && p.canSendWriteWithoutResponse {
                let n = min(chunk, outBuffer.count)
                p.writeValue(outBuffer.prefix(n), for: rx, type: .withoutResponse)
                outBuffer.removeFirst(n)
            }
        }
        audioBacklog = outBuffer.count / 80
    }

    private func received(_ data: Data) {
        lastReceivedAt = Date()
        reader.append(data)
        do {
            while let r = try reader.next() { try handle(r) }
        } catch {
            failAndRetry("protocol error: \(error)")
            return
        }
        // A record left half received (a lost GATT chunk) never completes: the keep-alive ends such a session.
        partialSince = reader.pendingBytes == 0 ? nil : (partialSince ?? Date())
    }

    /// When the current session started (link statistics) and since when a record has been half received.
    private var sessionSince: Date?
    private var partialSince: Date?

    private func handle(_ r: Record) throws {
        if let hs = handshake, session == nil {
            if r.kind == RecordKind.reject.rawValue {
                let reason = r.body.first ?? 0
                log("rejected by peripheral: \(reason)")
                switch reason {
                case 3 where pairing != nil:
                    // Pairing code expired or already used: stop this pairing attempt.
                    delegate?.link(self, didFail: "Pairing code expired. Show a new one on the Android phone.")
                    pairing = nil
                    store.peerKey = nil
                    disconnect(reason: "pairing rejected")
                    central.stopScan()
                    state = .unpaired
                    return
                case 2:
                    // The Android phone does not (currently) accept this iPhone. Keep our keys – the Android phone may only be
                    // busy with a test or restoring – but back off instead of retrying every 2 s.
                    delegate?.link(self, didFail: "The Android phone does not recognise this iPhone. If you unpaired it there, pair again.")
                    disconnect(reason: "rejected 2")
                    state = .searching   // waiting to retry, not still handshaking
                    scheduleRetry(after: 60)
                    return
                case 4:
                    disconnect(reason: "peripheral busy")
                    state = .searching   // waiting to retry, not still handshaking
                    scheduleRetry(after: 5)
                    return
                default:
                    break
                }
                throw HandshakeError.rejected(reason)
            }
            let (s, fin) = try hs.receive(r)
            keys = hs.keys
            session = s
            write(fin.encoded())
            return
        }
        guard let session, let keys else { return }
        if handshake != nil {
            try CentralHandshake.verifyFinish(session, keys: keys, record: r)
            handshake = nil
            if pairing != nil {
                pairing = nil
                log("paired")
            }
            if let p = peripheral { store.peripheralID = p.identifier }
            central.stopScan()
            state = .connected(transport: transport)
            sessionSince = Date()
            restartsOnOpenConnection = 0
            log("session up over \(transport)")
            settingUp = false
            setupWatchdog?.cancel(); setupWatchdog = nil
            failures.removeAll()
            retryItem?.cancel(); retryItem = nil
            startKeepalive()
            return
        }
        let (ch, payload) = try session.open(r)
        switch ch {
        case Proto.channelControl:
            let h = try Control.header(payload)
            if h.t == MsgType.ping {
                if let b = try? Control.body(PingBody.self, from: payload) { sendNow(MsgType.pong, b, re: h.id) }
            } else if h.t == MsgType.pong, let b = try? Control.body(PingBody.self, from: payload) {
                // Our own keep-alive ping came back: the link's control round trip (call statistics).
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                if now >= b.ts && now - b.ts < 30_000 { lastRoundTripMs = Int(now - b.ts) }
            }
            delegate?.link(self, didReceive: h, data: payload)
        case Proto.channelAudio:
            if let f = AudioFrame.parse(payload) { delegate?.link(self, didReceiveAudio: f) }
        default:
            break
        }
    }

    /**
     * The Android phone woke this app (a call, a text, 15 min of silence, or it has no session with us). Answer at
     * once: the phone closes a session that stays silent after a wake. With no session, or a session that does not
     * answer within [WakeCheck.answerSeconds], start a new one on the open connection: a suspended app is not told
     * that the channel closed (2026-11-25: 21 minutes without a session until the app was opened).
     */
    private func wokenByPhone() {
        let now = Date()
        switch WakeCheck.onWake(hasSession: session != nil, settingUp: settingUp) {
        case .answer:
            sendNow(MsgType.ping, PingBody(ts: Int64(now.timeIntervalSince1970 * 1000)))
            queue.asyncAfter(deadline: .now() + WakeCheck.answerSeconds) { [weak self] in
                guard let self, self.session != nil else { return }
                if WakeCheck.stale(lastReceived: self.lastReceivedAt, wokenAt: now) {
                    self.log("woken by the Android phone, but the session does not answer: starting a new one")
                    self.failAndRetry("no answer after a wake")
                }
            }
        case .newSession:
            log("woken by the Android phone without a session: setting one up")
            if let p = peripheral, p.state == .connected { failAndRetry("woken without a session") } else { reconnectSoon() }
        case .ignore:
            break
        }
    }

    private func startKeepalive() {
        keepalive?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 25, repeating: 25)
        // Only a ping: no client-side timeout. While suspended this timer does not run, so on wake-up
        // a receive timestamp could look stale even though the link is fine. CoreBluetooth reports real disconnects.
        t.setEventHandler { [weak self] in
            guard let self else { return }
            if let p = self.partialSince, Date().timeIntervalSince(p) > 10 { self.failAndRetry("stream stalled mid-record"); return }
            self.sendNow(MsgType.ping, PingBody(ts: Int64(Date().timeIntervalSince1970 * 1000)))
        }
        t.resume()
        keepalive = t
    }
}

extension TetherLink: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: startIfReady()
        case .unauthorized: state = .unauthorized
        default:
            disconnect(reason: "bluetooth \(central.state.rawValue)")
            state = .poweredOff
        }
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], let p = ps.first {
            log("restored peripheral \(p.identifier) state \(p.state.rawValue)")
            peripheral = p
            p.delegate = self
            connectedThisAttempt = p.state == .connected
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                               advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard isPaired else { return }
        if let cur = peripheral {
            if cur.state == .connected || cur.identifier == p.identifier && cur.state == .connecting { return }
            // The pending connect targets a stale identifier (Android phone Bluetooth reset / new address): switch.
            if cur.state == .connecting { central.cancelPeripheralConnection(cur) }
        }
        log("discovered \(p.identifier) rssi \(RSSI)")
        central.stopScan()
        connect(p)
    }

    public func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        connectWatchdog?.cancel(); connectWatchdog = nil
        connectedThisAttempt = true
        log("connected \(p.identifier)")
        cancelledByUs = nil
        state = .connecting
        if settingUp { return }
        settingUp = true
        armSetupWatchdog()
        p.discoverServices([TetherUUID.service])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        failAndRetry("connect failed: \(Self.describe(error))")
    }

    /// CoreBluetooth error with its code (CBError 6 = connection timeout / supervision timeout, 7 = peripheral
    /// disconnected, 14 = peer removed pairing info, 15 = encryption timed out…), for the link log.
    static func describe(_ error: Error?) -> String {
        guard let e = error as NSError? else { return "-" }
        return "\(e.domain) \(e.code): \(e.localizedDescription)"
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        // The disconnect we caused ourselves (failAndRetry already reconnected): not a second failure.
        if cancelledByUs == p.identifier { cancelledByUs = nil; log("disconnected (by us)"); return }
        // A connect that never connected and ends without an error was cancelled by us (opening the app, the connect
        // watchdog): not a failure. Treating it as one tore down the connect that replaced it and, after three, put
        // the app into back-off right when it should connect (seen 2026-11-22 after closing and reopening the app).
        if error == nil && !connectedThisAttempt { log("pending connect cancelled"); return }
        guard p == peripheral || peripheral == nil else { return }
        log("disconnected: \(Self.describe(error))")
        disconnect(reason: "peripheral disconnected")
        state = isPaired ? .searching : .unpaired
        reconnectSoon()
    }
}

extension TetherLink: CBPeripheralDelegate {
    /// The Android phone changed its GATT database (Tether's service restarted): if we were still setting up, look again.
    public func peripheral(_ p: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard invalidatedServices.contains(where: { $0.uuid == TetherUUID.service }) else { return }
        log("Android phone services changed")
        if settingUp && channel == nil && transport == "none" { p.discoverServices([TetherUUID.service]); return }
        // Tether restarted on the Android phone (an update, a restart): the session this app holds is gone, and while
        // suspended it is not told that the channel closed. Start a new one on this connection (2026-11-25: 21 minutes
        // without a session until the app was opened).
        if session != nil || channel != nil || transport != "none" { failAndRetry("Android phone services changed") }
    }

    public func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == TetherUUID.service }) else {
            failAndRetry("service not found"); return
        }
        p.discoverCharacteristics([TetherUUID.psm, TetherUUID.rx, TetherUUID.tx, TetherUUID.wake], for: s)
    }

    public func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for c in s.characteristics ?? [] {
            switch c.uuid {
            // Nothing here needs link encryption: Tether's own pairing secures the link, never a Bluetooth bond.
            case TetherUUID.psm: p.readValue(for: c)
            case TetherUUID.rx: rxChar = c
            case TetherUUID.tx: txChar = c
            case TetherUUID.wake: p.setNotifyValue(true, for: c) // notifications wake us when suspended
            default: break
            }
        }
    }

    public func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        if c.uuid == TetherUUID.psm {
            guard error == nil, let v = c.value, v.count >= 2 else {
                // No L2CAP without the PSM, but the link does not need it: an Android phone build that still asks for
                // encryption, a broken bond left by Bluetooth tethering ("insufficient encryption key size"), or a
                // GATT connection over the classic link all still work over the GATT transport.
                startGattFallback("PSM read failed: \(error?.localizedDescription ?? "empty")")
                if channel == nil, transport == "none", txChar == nil || rxChar == nil {
                    failAndRetry("PSM read failed: \(error?.localizedDescription ?? "empty")")
                }
                return
            }
            let psm = CBL2CAPPSM(UInt16(v[v.startIndex]) | UInt16(v[v.startIndex + 1]) << 8)
            log("opening L2CAP psm \(psm)")
            p.openL2CAPChannel(psm)
            let fallback = DispatchWorkItem { [weak self] in self?.startGattFallback("L2CAP timeout") }
            l2capTimer = fallback
            queue.asyncAfter(deadline: .now() + 6, execute: fallback)
        } else if c.uuid == TetherUUID.tx, let v = c.value {
            received(v)
        } else if c.uuid == TetherUUID.wake {
            wokenByPhone()
        }
    }

    public func peripheral(_ p: CBPeripheral, didOpen ch: CBL2CAPChannel?, error: Error?) {
        l2capTimer?.cancel(); l2capTimer = nil
        if channel != nil || transport != "none" {
            // Late or duplicate channel (e.g. GATT fallback already running): never restart a live session.
            let i: InputStream? = ch?.inputStream, o: OutputStream? = ch?.outputStream
            i?.close(); o?.close()
            return
        }
        guard let ch, error == nil else {
            startGattFallback("L2CAP open failed: \(error?.localizedDescription ?? "?")"); return
        }
        // The stream properties are optional in some SDKs; bind them through optionals.
        let maybeIn: InputStream? = ch.inputStream, maybeOut: OutputStream? = ch.outputStream
        guard let input = maybeIn, let output = maybeOut else { startGattFallback("L2CAP without streams"); return }
        channel = ch
        input.delegate = self
        output.delegate = self
        CFReadStreamSetDispatchQueue(input as CFReadStream, queue)
        CFWriteStreamSetDispatchQueue(output as CFWriteStream, queue)
        input.open()
        output.open()
        openTransport("l2cap")
    }

    private func startGattFallback(_ why: String) {
        guard channel == nil, transport == "none", let p = peripheral, let tx = txChar, rxChar != nil else { return }
        log("GATT fallback: \(why)")
        p.setNotifyValue(true, for: tx)
    }

    public func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        if c.uuid == TetherUUID.tx, c.isNotifying, channel == nil, transport == "none" { openTransport("gatt") }
    }

    public func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) { flush() }
}

extension TetherLink: StreamDelegate {
    public func stream(_ s: Stream, handle event: Stream.Event) {
        switch event {
        case .hasBytesAvailable:
            guard let input = s as? InputStream else { return }
            var buf = [UInt8](repeating: 0, count: 4096)
            while input.hasBytesAvailable {
                let n = input.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                received(Data(buf[0..<n]))
            }
        case .hasSpaceAvailable:
            flush()
        case .errorOccurred, .endEncountered:
            // Only the current channel's streams count (both of them report the end; a closed channel's late events
            // must not end the session that replaced it).
            guard let ch = channel, s === (ch.inputStream as Stream?) || s === (ch.outputStream as Stream?) else { return }
            failAndRetry("L2CAP stream ended")
        default:
            break
        }
    }
}

#endif

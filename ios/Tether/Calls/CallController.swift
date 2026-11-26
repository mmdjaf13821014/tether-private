import AVFoundation
import CallKit
import Foundation
@_spi(TetherApp) import TetherCore
import UIKit

/// What the in-app call screen shows.
struct CallScreen: Equatable {
    var uuid: UUID
    var number: String
    var name: String?
    var state: String          // "dialing", "ringing", "active", "holding", "ended"
    var incoming: Bool
    var connectedAt: Date?
    var sub: Int?
    var muted = false
    var held = false
    var speaker = false
    /// Where the call's audio is now, the routes on offer and the selected one (the audio menu).
    var route: RouteKind = .receiver
    var routes: [RouteOption] = []
    var selectedRoute = RouteOption.iPhoneID
}

/// Maps the Android phone's calls onto CallKit (native incoming screen, lock screen too) and drives the in-app
/// call screen. Answer / end / mute / hold / DTMF go through CallKit transactions so both stay in sync.
///
/// The Android phone accepts several clients (protocol §5.1): when a call's audio is on another one, the Android phone sends
/// `elsewhere`; this client then drops its local call (CallKit: "answered elsewhere") but keeps a
/// "Move here" banner, which sends `call.take`.
@MainActor
final class CallController: NSObject {
    private unowned let model: AppModel
    /// Rebuilt when CallKit no longer knows it ([rebuildCallKit]): iOS may restart its call daemon under a running app.
    private(set) var provider: CXProvider
    private var controller = CXCallController()
    /// CallKit lost our provider while a call was up: set it up again once no call is left.
    private var callKitRebuildPending = false
    /// Calls whose audio is on another client, by Android phone call id (shown as "On … · Move here").
    private(set) var remote: [String: CallInfo] = [:]

    /// Android phone call id ↔ CallKit UUID.
    private var uuids: [String: UUID] = [:]
    private var cids: [UUID: String] = [:]
    private var lastState: [String: String] = [:]
    private var info: [UUID: CallInfo] = [:]
    /// Outgoing call started on the iPhone, waiting for the Android phone to report its call id.
    private var pendingOutgoing: UUID?
    private var pendingSub: Int?
    /// Actions we created ourselves to mirror something done on the Android phone (must not be echoed back).
    private var mirrored = Set<UUID>()
    private var answeredHere = Set<UUID>()
    private var reportedConnecting = Set<UUID>()
    private var reportedConnected = Set<UUID>()
    /// CallKit has performed the start action for these outgoing calls (reports before that are dropped).
    private var startPerformed = Set<UUID>()
    /// The user hung up an outgoing call before the Android phone reported it: end it as soon as it shows up.
    private var cancelledDialAt: Date?
    /// Numbers of the dial in flight and of the one hung up before the Android phone reported it (to tell a quick redial apart).
    private var pendingNumber = "", cancelledNumber = ""
    /// Holds we asked for ourselves (a hold done on the Android phone is mirrored into CallKit instead).
    private var holdRequested = Set<UUID>()
    private var dialWatchdog: Task<Void, Never>?

    init(model: AppModel) {
        self.model = model
        provider = CXProvider(configuration: Self.providerConfiguration())
        super.init()
        provider.setDelegate(self, queue: nil)
        // An audio-system reset may take CallKit's daemon with it: check that it still knows us.
        NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolatedCompat { self?.rebuildCallKit("media services were reset") }
        }
        // The speaker button follows the real route (CallKit's own speaker button, AirPods, a cable…).
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            let reason = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            MainActor.assumeIsolatedCompat { self?.routeChanged(reason: reason) }
        }
    }

    private static func providerConfiguration() -> CXProviderConfiguration {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 2
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber]
        // The app has its own Recents synced from the Android phone; the Phone app's Recents are optional (a tap there
        // comes back as an INStartCallIntent activity, see Router.continueActivity).
        config.includesCallsInRecents = UserDefaults.standard.bool(forKey: Self.phoneRecentsKey)
        // Template glyph CallKit shows on the incoming screen / in the call banner (our own handset artwork).
        if let icon = UIImage(named: "CallKitIcon")?.pngData() { config.iconTemplateImageData = icon }
        // Note: CallKit labels the call "<CFBundleDisplayName> Audio". Since iOS 14 there is no API to change or
        // hide that line (CXProviderConfiguration.localizedName is unsupported), so the display name is kept neutral.
        return config
    }

    /// The request failed because CallKit does not know our provider (its daemon restarted under this app).
    nonisolated static func lostProvider(_ error: Error) -> Bool {
        let e = error as NSError
        return e.domain == CXErrorDomainRequestTransaction && e.code == CXErrorCodeRequestTransactionError.Code.unknownCallProvider.rawValue
    }

    /// Sets the CallKit provider and controller up again. iOS can restart its call daemon (callservicesd) while this
    /// app keeps running; the old provider is then unknown to it: every request fails with "unknown call provider" and
    /// an incoming call is shown but its audio session is never activated (no microphone, no sound), until the app is
    /// force-quit (2026-11-25, iPhone 17 Pro). Done only with no call up; otherwise once the last call is gone.
    func rebuildCallKit(_ why: String) {
        guard uuids.isEmpty, pendingOutgoing == nil, info.isEmpty else {
            callKitRebuildPending = true
            LinkJournal.shared.add("CallKit: \(why); set up again after this call")
            return
        }
        callKitRebuildPending = false
        let old = provider
        old.setDelegate(nil, queue: nil)
        old.invalidate()
        provider = CXProvider(configuration: Self.providerConfiguration())
        provider.setDelegate(self, queue: nil)
        controller = CXCallController()
        audioSessionActive = false
        LinkJournal.shared.add("CallKit: \(why); provider set up again")
    }

    /// When the app comes to the front with no call: an empty request tells whether CallKit still knows our provider.
    func checkCallKit() {
        guard uuids.isEmpty, pendingOutgoing == nil else { return }
        controller.request(CXTransaction(actions: [])) { error in
            guard let error, Self.lostProvider(error) else { return }
            Task { @MainActor in self.rebuildCallKit("CallKit no longer knew this app's provider") }
        }
    }

    /// The user's speaker choice for the current call; applied whenever the audio session is (re)activated.
    private var speakerWanted = false
    /// CallKit has activated the call's audio session (didActivate until didDeactivate).
    private(set) var audioSessionActive = false
    /// Calls ended from our own call screen: CallKit's end action that follows must not act twice.
    private var endedHere = Set<UUID>()
    /// Android phone call ids hung up here: late updates for them must not bring the call back (TetherCore, tested).
    private var hungUp = HangUpGuard()

    /// Screen off at the ear, like a native call: proximity monitoring while a call is up and its audio is on the
    /// earpiece (not on speaker, headphones or AirPods). CallKit does this for its own screen only.
    func updateProximity() {
        let s = model.callScreen
        let live = s != nil && s?.state != "ended"
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let earpiece = outputs.isEmpty || outputs.contains { $0.portType == .builtInReceiver }
        let want = live && earpiece && s?.speaker != true
        if UIDevice.current.isProximityMonitoringEnabled != want {
            UIDevice.current.isProximityMonitoringEnabled = want
            LinkJournal.shared.add("proximity \(want ? "on" : "off")")
        }
    }

    private var lastLoggedRoute: RouteKind?

    /// The call screen follows the real route, whoever chose it: CallKit's screen, Control Center, a car head unit,
    /// a headset connecting mid-call, or our own menu.
    func routeChanged(reason: AVAudioSession.RouteChangeReason? = nil) {
        defer { updateProximity() }
        guard model.callScreen != nil else { return }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-TetherDemoRoutes") { return }
        #endif
        let s = AVAudioSession.sharedInstance()
        let outs = s.currentRoute.outputs.map { RoutePort(type: $0.portType.rawValue, uid: $0.uid, name: $0.portName) }
        let ins = s.currentRoute.inputs.map { RoutePort(type: $0.portType.rawValue, uid: $0.uid, name: $0.portName) }
        let available = (s.availableInputs ?? []).map { RoutePort(type: $0.portType.rawValue, uid: $0.uid, name: $0.portName) }
        let hasReceiver = UIDevice.current.userInterfaceIdiom == .phone
        let options = AudioRoutes.options(inputs: available, hasReceiver: hasReceiver)
        let kind = AudioRoutes.current(outputs: outs.map(\.type))
        // Before CallKit activates the session the route is not the call's yet: a speaker choice made while connecting
        // is kept (and shown) until didActivate applies it; resetting it here lost it (#15).
        speakerWanted = AudioRoutes.speakerWanted(previous: speakerWanted, sessionActive: audioSessionActive, outputs: outs.map(\.type))
        let onSpeaker = audioSessionActive ? AudioRoutes.speakerOn(outputs: outs.map(\.type)) : speakerWanted
        var screen = model.callScreen
        screen?.speaker = onSpeaker
        screen?.route = kind
        screen?.routes = options
        screen?.selectedRoute = AudioRoutes.selected(outputs: outs, inputs: ins, options: options)
        if screen != model.callScreen { model.callScreen = screen }
        if kind != lastLoggedRoute {
            lastLoggedRoute = kind
            // Port types only: device names stay out of the log.
            LinkJournal.shared.add("audio route \(kind.rawValue) (out \(outs.map(\.type).joined(separator: "+")), in \(ins.map(\.type).joined(separator: "+")))\(reason.map { ", reason \(Self.reasonName($0))" } ?? "")")
        }
    }

    private static func reasonName(_ r: AVAudioSession.RouteChangeReason) -> String {
        switch r {
        case .newDeviceAvailable: "new device"
        case .oldDeviceUnavailable: "device gone"
        case .categoryChange: "category"
        case .override: "override"
        case .wakeFromSleep: "wake"
        case .noSuitableRouteForCategory: "no route"
        case .routeConfigurationChange: "configuration"
        default: "other"
        }
    }

    /// Re-applies the speaker choice when the session is (re)activated, unless a headset or car has the call.
    func applySpeaker() {
        let outs = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType.rawValue)
        if AudioRoutes.reapplySpeaker(wanted: speakerWanted, outputs: outs) { selectRoute(RouteOption.speakerID) }
        else { routeChanged() }
    }

    /// Moves the call's audio to an option of the audio menu (iPhone, Speaker, a headset or the car).
    func selectRoute(_ id: String) {
        let s = AVAudioSession.sharedInstance()
        let mic = s.availableInputs?.first { $0.portType == .builtInMic }
        let plan = AudioRoutes.plan(for: id, builtInMicUID: mic?.uid)
        speakerWanted = plan.speakerOverride
        model.callScreen?.speaker = plan.speakerOverride   // at once; the route notification confirms or corrects it
        model.callScreen?.selectedRoute = id
        do {
            let port = plan.preferredInputUID.flatMap { uid in s.availableInputs?.first { $0.uid == uid } }
            if plan.preferredInputUID != nil && port == nil { NSLog("[calls] route %@ no longer available", id) }
            if !plan.speakerOverride { try s.overrideOutputAudioPort(.none) }
            try s.setPreferredInput(port)
            if plan.speakerOverride { try s.overrideOutputAudioPort(.speaker) }
        } catch { NSLog("[calls] route change failed: %@", "\(error)") }
        routeChanged()
    }

    // MARK: CallKit

    private func reportIncoming(_ uuid: UUID, _ update: CXCallUpdate, then: @escaping @MainActor () -> Void) {
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            LinkJournal.shared.add("CallKit incoming report: \(error.map { "\($0)" } ?? "ok")")
            Task { @MainActor in
                guard let error else { then(); return }
                NSLog("[calls] report incoming failed: %@", "\(error)")
                // CallKit refused it (Focus filter, too many calls…): forget the binding, so that if the call is
                // answered on the Android phone it shows up here as a started call with audio instead of a half-bound one.
                if let cid = self.cids[uuid] {
                    self.uuids[cid] = nil; self.cids[uuid] = nil; self.info[uuid] = nil
                    self.lastState[cid] = "unreported"
                }
                // Its call screen (and proximity) must not stay up: nothing would ever end it.
                self.cleanup(uuid, flash: false)
            }
        }
    }

    private func reportEnded(_ uuid: UUID, _ reason: CXCallEndedReason) {
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
    }

    private func reportUpdated(_ uuid: UUID, _ update: CXCallUpdate) {
        provider.reportCall(with: uuid, updated: update)
    }

    /// Runs a call action as a CallKit transaction.
    private func run(_ action: CXCallAction, mirrored: Bool = false) {
        if mirrored { self.mirrored.insert(action.callUUID) }
        request(action) { error in
            NSLog("[calls] %@ failed: %@", "\(type(of: action))", "\(error)")
            // A refused mirrored action must not make the user's next action look mirrored (and not be sent).
            if mirrored { self.mirrored.remove(action.callUUID) }
        }
    }

    var hasCalls: Bool { !uuids.isEmpty || pendingOutgoing != nil }

    // MARK: from the Android phone

    func handle(_ c: CallInfo) {
        if lastState[c.cid] != c.state { LinkJournal.shared.add("call \(c.cid) \(c.dir) \(c.state)\(c.isElsewhere ? " elsewhere" : "")") }
        switch hungUp.check(cid: c.cid, ended: c.isEnded) {
        case .pass: break
        case .drop: return
        case .dropAndResend:
            // Still not ended on the Android phone 2 s after we hung up: ask again.
            model.link.send(c.state == "ringing" ? MsgType.callReject : MsgType.callEnd, CallRef(cid: c.cid))
            return
        }
        let prev = lastState[c.cid]
        lastState[c.cid] = c.isEnded ? nil : c.state
        let handle = CXHandle(type: .phoneNumber, value: c.num)
        let name = model.name(for: c.num) ?? c.name

        if c.isEnded { remote[c.cid] = nil }
        if !c.isEnded && c.isElsewhere {
            // Answered / dialed / moved to another client: stop ringing or playing here, offer "Move here".
            remote[c.cid] = c
            lastState[c.cid] = c.state
            if let uuid = uuids.removeValue(forKey: c.cid) {
                cids[uuid] = nil
                info[uuid] = nil
                reportEnded(uuid, .answeredElsewhere)
                cleanup(uuid)
            }
            return
        }
        remote[c.cid] = nil

        if c.isEnded {
            guard let uuid = uuids.removeValue(forKey: c.cid) else { return }
            cids[uuid] = nil
            info[uuid] = nil
            let reason: CXCallEndedReason = switch (prev, c.cause) {
            case ("ringing", _) where c.isIncoming: .unanswered
            case (_, let cause?) where cause.localizedCaseInsensitiveContains("busy"): .failed
            default: .remoteEnded
            }
            reportEnded(uuid, reason)
            cleanup(uuid)
            return
        }

        if uuids[c.cid] == nil {
            if c.isIncoming && c.state == "ringing" {
                // Report to CallKit before anything else: this is what makes the iPhone ring.
                let uuid = UUID()
                bind(c.cid, uuid)
                let update = CXCallUpdate()
                update.remoteHandle = handle
                // CallKit has no line label: with two SIMs the SIM it rings on follows the name ("Sara · Work").
                update.localizedCallerName = [name ?? model.formatted(c.num), model.simName(c.sub)].compactMap { $0 }.joined(separator: " · ")
                update.hasVideo = false
                update.supportsHolding = c.hold
                update.supportsDTMF = true
                update.supportsGrouping = false
                update.supportsUngrouping = false
                reportIncoming(uuid, update) {}
            } else if let t = cancelledDialAt, Date().timeIntervalSince(t) < 30, !cancelledNumber.isEmpty, !c.isIncoming,
                      PhoneNumber.matchKey(c.num) == PhoneNumber.matchKey(cancelledNumber),
                      pendingOutgoing == nil || PhoneNumber.matchKey(c.num) != PhoneNumber.matchKey(pendingNumber) {
                // The dial the user already hung up on (a quick redial to another number must not take its place).
                cancelledDialAt = nil; cancelledNumber = ""
                lastState[c.cid] = nil
                model.link.send(MsgType.callEnd, CallRef(cid: c.cid))
                return
            } else if let uuid = pendingOutgoing {
                pendingOutgoing = nil
                dialWatchdog?.cancel()
                bind(c.cid, uuid)
            } else if let t = cancelledDialAt, Date().timeIntervalSince(t) < 30, cancelledNumber.isEmpty, !c.isIncoming {
                // The dial the user already hung up on before its number was known: end it on the Android phone instead of
                // showing it (a call to a known other number, or an incoming one, is never taken for it).
                cancelledDialAt = nil
                lastState[c.cid] = nil
                model.link.send(MsgType.callEnd, CallRef(cid: c.cid))
                return
            } else {
                // Dialed or answered on the Android phone, moved here, or live when we reconnected: show it here as a
                // started call (an incoming-call report would ring for a call that is already connected).
                let uuid = UUID()
                bind(c.cid, uuid)
                run(CXStartCallAction(call: uuid, handle: handle), mirrored: true)
            }
        }
        guard let uuid = uuids[c.cid] else { return }
        info[uuid] = c

        let update = CXCallUpdate()
        update.remoteHandle = handle
        update.localizedCallerName = [name ?? model.formatted(c.num), model.simName(c.sub)].compactMap { $0 }.joined(separator: " · ")
        update.supportsHolding = c.hold
        update.supportsDTMF = true
        reportUpdated(uuid, update)

        switch c.state {
        case "dialing", "connecting":
            reportOutgoingProgress(uuid)
            if !c.isIncoming { model.audio.ringback = true }
        case "active":
            model.audio.ringback = false
            reportOutgoingProgress(uuid)
            if c.isIncoming && prev == "ringing" && !answeredHere.contains(uuid) { mirrorAnswer(uuid) }
            if prev == "holding" && holdRequested.remove(uuid) == nil { run(CXSetHeldCallAction(call: uuid, onHold: false), mirrored: true) }
        case "holding":
            // Held on the Android phone (e.g. a second call answered there): CallKit must know, or its buttons act on the wrong call.
            if prev == "active" && holdRequested.remove(uuid) == nil { run(CXSetHeldCallAction(call: uuid, onHold: true), mirrored: true) }
        default:
            break
        }
        updateScreen(uuid)
        updateUplink()
    }

    /// The microphone goes out only while a call here is connected (CallUplink).
    private func updateUplink() {
        model.audio.setUplink(open: CallUplink.open(callStates: info.values.map(\.state)))
    }

    /// Reports outgoing progress to CallKit once per stage, and only after CallKit performed the start action.
    private func reportOutgoingProgress(_ uuid: UUID) {
        // Calls we started in CallKit: our own dials and mirrored ones (dialed / answered on the Android phone, moved here).
        guard let c = info[uuid], startPerformed.contains(uuid) else { return }
        if reportedConnecting.insert(uuid).inserted {
            provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
        }
        if c.state == "active" || c.state == "holding", reportedConnected.insert(uuid).inserted {
            provider.reportOutgoingCall(with: uuid, connectedAt: c.since.map { Date(millis: $0) } ?? Date())
        }
    }

    private func mirrorAnswer(_ uuid: UUID) {
        run(CXAnswerCallAction(call: uuid), mirrored: true)
    }

    /// "Move here": the Android phone switches call audio to this client and re-sends the state without `elsewhere`.
    func take(_ cid: String) {
        guard remote[cid] != nil else { return }
        remote[cid] = nil
        lastState[cid] = nil
        model.link.send(MsgType.callTake, CallRef(cid: cid))
    }

    private func bind(_ cid: String, _ uuid: UUID) {
        uuids[cid] = uuid
        cids[uuid] = cid
    }

    private func cleanup(_ uuid: UUID, flash: Bool = true) {
        defer {
            updateUplink()
            if callKitRebuildPending { rebuildCallKit("after the call") }
        }
        answeredHere.remove(uuid); reportedConnecting.remove(uuid); reportedConnected.remove(uuid); mirrored.remove(uuid)
        startPerformed.remove(uuid)
        // Close the call screen at once (another call takes it over if there is one).
        if model.callScreen?.uuid == uuid {
            model.callScreen = nextScreen()
            if model.callScreen != nil { routeChanged() }
            if model.callScreen == nil { model.showCallScreen = false; if flash { model.flash(String(localized: "Call ended")) } }
        }
        if !hasCalls {
            model.audio.stop()
            speakerWanted = false
            lastLoggedRoute = nil
            try? AVAudioSession.sharedInstance().setPreferredInput(nil)   // the next call starts on the system's route
        }
    }

    private func nextScreen() -> CallScreen? {
        guard let (uuid, _) = cids.first, let c = info[uuid] else { return nil }
        var s = CallScreen(uuid: uuid, number: c.num, name: model.name(for: c.num) ?? c.name, state: c.state,
                           incoming: c.isIncoming, sub: c.sub)
        s.held = c.state == "holding"
        if c.state == "active" { s.connectedAt = c.since.map { Date(millis: $0) } ?? Date() }
        return s
    }

    /// Mirrors a call into the in-app call screen (`force`: the user just answered it, so it takes the screen).
    private func updateScreen(_ uuid: UUID, force: Bool = false) {
        guard let c = info[uuid] else { return }
        var s = model.callScreen?.uuid == uuid ? model.callScreen! :
            CallScreen(uuid: uuid, number: c.num, name: nil, state: c.state, incoming: c.isIncoming, sub: c.sub)
        s.number = c.num
        s.name = model.name(for: c.num) ?? c.name
        s.state = c.state
        s.sub = c.sub
        s.held = c.state == "holding"
        if c.state == "active", s.connectedAt == nil { s.connectedAt = c.since.map { Date(millis: $0) } ?? Date() }
        // Only take over the screen for the current call, or when nothing is shown.
        if force || model.callScreen == nil || model.callScreen?.uuid == uuid || model.callScreen?.state == "ended" {
            let fresh = model.callScreen?.uuid != uuid
            if fresh { model.callScreenDismissed = false }
            model.callScreen = s
            if fresh { routeChanged() }   // a new screen: fill in the route and the audio menu
        }
    }

    /// After a reconnect: end CallKit calls the Android phone no longer has.
    func reconcile(with list: [CallInfo]) {
        let live = Set(list.filter { !$0.isEnded }.map(\.cid))
        for cid in remote.keys where !live.contains(cid) { remote[cid] = nil }
        for (cid, uuid) in uuids where !live.contains(cid) {
            reportEnded(uuid, .remoteEnded)
            uuids[cid] = nil; cids[uuid] = nil; lastState[cid] = nil; info[uuid] = nil
            cleanup(uuid)
        }
    }

    /// Link lost: we can no longer control the calls, so end them here (they continue on the Android phone).
    func linkLost() {
        for (_, uuid) in uuids { reportEnded(uuid, .failed) }
        uuids.removeAll(); cids.removeAll(); lastState.removeAll(); info.removeAll(); remote.removeAll()
        if let p = pendingOutgoing { reportEnded(p, .failed); pendingOutgoing = nil }
        hungUp.reset(); endedHere.removeAll(); answeredHere.removeAll(); startPerformed.removeAll(); mirrored.removeAll()
        reportedConnecting.removeAll(); reportedConnected.removeAll(); cancelledDialAt = nil; speakerWanted = false
        holdRequested.removeAll(); dialWatchdog?.cancel(); pendingNumber = ""; cancelledNumber = ""
        model.callScreen = nil
        model.showCallScreen = false
        model.audio.stop()
        if callKitRebuildPending { rebuildCallKit("after link lost") }
    }

    /// The Android phone accepted the dial: if it never reports a call for it (Telecom dropped it), fail it after 30 s
    /// instead of showing "dialing" with our ringback for ever.
    func dialAccepted() {
        guard let p = pendingOutgoing else { return }
        dialWatchdog?.cancel()
        dialWatchdog = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard let self, !Task.isCancelled, self.pendingOutgoing == p else { return }
            LinkJournal.shared.add("dial: no call from the Android phone after 30 s")
            self.dialFailed("no call")
            self.model.lastError = String(localized: "Call failed")
        }
    }

    func dialFailed(_ err: String) {
        if let p = pendingOutgoing {
            reportEnded(p, .failed)
            pendingOutgoing = nil
            dialWatchdog?.cancel()
            model.audio.ringback = false   // another call may still be live: its audio must not carry our ringback
            cleanup(p, flash: false)   // screen closed (no full-screen view left for the next ring); "Call failed" says it
        }
    }

    // MARK: from the iPhone UI

    static let phoneRecentsKey = "callsInPhoneRecents"

    /// The "Show calls in the Phone app" switch: CallKit reads the configuration again when it is replaced.
    func applyRecentsSetting() {
        let config = provider.configuration
        config.includesCallsInRecents = UserDefaults.standard.bool(forKey: Self.phoneRecentsKey)
        provider.configuration = config
    }

    func startOutgoing(number: String, sub: Int?) {
        // Keeps *, # and pauses (",") for service codes and extensions; the Android phone dials those as given.
        let clean = DialLink.dialable(number) ?? ""
        guard !clean.isEmpty else { return }
        let uuid = UUID()
        pendingSub = sub
        // Show our call screen immediately (CallKit only shows system UI for incoming calls).
        model.callScreen = CallScreen(uuid: uuid, number: clean, name: model.name(for: clean), state: "dialing",
                                      incoming: false, sub: sub)
        model.showCallScreen = true
        model.callScreenDismissed = false
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .phoneNumber, value: clean))
        action.contactIdentifier = model.name(for: clean)
        request(action) { error in
            NSLog("[calls] start failed: %@", "\(error)")
            self.model.callScreen = nil; self.model.lastError = String(localized: "Call failed")
        }
    }

    /// Runs [action] as a CallKit transaction; when CallKit no longer knows our provider, sets it up again and tries
    /// once more (the action's copy: a transaction takes an action only once). [failed] runs on the main actor.
    private func request(_ action: CXCallAction, retried: Bool = false, failed: @escaping @MainActor (Error) -> Void) {
        controller.request(CXTransaction(action: action)) { error in
            guard let error else { return }
            Task { @MainActor in
                if !retried, Self.lostProvider(error), let again = action.copy() as? CXCallAction {
                    self.rebuildCallKit("CallKit no longer knew this app's provider (\(type(of: action)))")
                    if !self.callKitRebuildPending { self.request(again, retried: true, failed: failed); return }
                }
                failed(error)
            }
        }
    }

    /// Hang up from our call screen: done here at once (the Android phone is told, audio stops, the screen closes),
    /// then CallKit is told; if CallKit refuses (it may not know the call yet), its call is ended by report.
    func end(_ uuid: UUID) {
        endedHere.insert(uuid)
        performEnd(uuid)
        controller.request(CXTransaction(action: CXEndCallAction(call: uuid))) { [weak self] error in
            guard let error else { return }
            NSLog("[calls] end transaction failed: %@", "\(error)")
            Task { @MainActor in
                self?.endedHere.remove(uuid)
                if Self.lostProvider(error) {
                    self?.rebuildCallKit("CallKit no longer knew this app's provider (CXEndCallAction)")
                } else {
                    self?.provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
                }
            }
        }
    }
    func answer(_ uuid: UUID) { run(CXAnswerCallAction(call: uuid)) }
    func setMuted(_ uuid: UUID, _ on: Bool) { run(CXSetMutedCallAction(call: uuid, muted: on)) }
    func setHeld(_ uuid: UUID, _ on: Bool) { run(CXSetHeldCallAction(call: uuid, onHold: on)) }
    func dtmf(_ uuid: UUID, _ digit: String) { run(CXPlayDTMFCallAction(call: uuid, digits: digit, type: .singleTone)) }

    /// Answers a ringing call by its Android phone id (debug auto-answer).
    func answer(cid: String) { if let u = uuids[cid] { answer(u) } }
    /// Ends a call by its Android-side id (debug auto hang-up).
    func end(cid: String) { if let u = uuids[cid] { end(u) } }

    func setSpeaker(_ on: Bool) { selectRoute(on ? RouteOption.speakerID : RouteOption.iPhoneID) }

    func cid(for uuid: UUID) -> String? { cids[uuid] }
}

extension CallController: CXProviderDelegate {
    // The provider calls these on the main queue (setDelegate(self, queue: nil)), in CallKit's order. They run right
    // here, on the main actor, not in a Task: a Task per callback could run didDeactivate before the didActivate that
    // came first, or an action after the reset that followed it (#15).

    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolatedCompat {
            guard provider === self.provider else { return }
            self.uuids.removeAll(); self.cids.removeAll(); self.lastState.removeAll(); self.info.removeAll()
            self.pendingOutgoing = nil; self.mirrored.removeAll(); self.answeredHere.removeAll()
            self.reportedConnecting.removeAll(); self.reportedConnected.removeAll(); self.startPerformed.removeAll()
            self.remote.removeAll(); self.hungUp.reset(); self.endedHere.removeAll()
            self.audioSessionActive = false; self.speakerWanted = false
            self.model.callScreen = nil
            self.model.audio.stop()
            if self.callKitRebuildPending { self.rebuildCallKit("after reset") }
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolatedCompat {
            self.performStart(action.callUUID, number: action.handle.value)
            action.fulfill()
            if self.reportedConnecting.insert(action.callUUID).inserted {
                provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
            }
            self.reportOutgoingProgress(action.callUUID) // replay state that arrived before the action ran
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        MainActor.assumeIsolatedCompat { self.performAnswer(action.callUUID); action.fulfill() }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolatedCompat {
            // Already done when the end came from our own screen.
            if self.endedHere.remove(action.callUUID) == nil { self.performEnd(action.callUUID) }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolatedCompat { self.performMute(action.callUUID, action.isMuted); action.fulfill() }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        MainActor.assumeIsolatedCompat { if self.performHold(action.callUUID, action.isOnHold) { action.fulfill() } else { action.fail() } }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        MainActor.assumeIsolatedCompat { self.performDtmf(action.callUUID, action.digits); action.fulfill() }
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolatedCompat { guard provider === self.provider else { return }; self.sessionDidActivate() }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolatedCompat { guard provider === self.provider else { return }; self.sessionDidDeactivate() }
    }
}

// MARK: - The actions themselves (from the CallKit delegate)

extension CallController {
    /// CallKit activated the call's audio session. The speaker choice made while connecting is applied first, then the
    /// engine starts (on the audio queue, after this): it is built on the final route, instead of the override racing
    /// the engine start and its configuration change.
    func sessionDidActivate() {
        LinkJournal.shared.add("audio session activated")
        audioSessionActive = true
        applySpeaker()   // a speaker choice made while connecting; a headset or car keeps the call
        model.audio.sessionActivated()
    }

    func sessionDidDeactivate() {
        audioSessionActive = false
        model.audio.sessionDeactivated()
    }

    func performStart(_ uuid: UUID, number: String) {
        CallAudio.configureSession()
        Microphone.checkForCall(mirrored.contains(uuid) ? "call shown here" : "outgoing call")
        if mirrored.remove(uuid) == nil {
            pendingOutgoing = uuid
            pendingNumber = number
            model.audio.ringback = true
            model.link.send(MsgType.callDial, CallDial(num: number, sub: pendingSub), expectReply: true)
        }
        startPerformed.insert(uuid)
    }

    func performAnswer(_ uuid: UUID) {
        answeredHere.insert(uuid)
        CallAudio.configureSession()
        Microphone.checkForCall("answer")
        if mirrored.remove(uuid) == nil, let cid = cids[uuid] {
            model.link.send(MsgType.callAnswer, CallRef(cid: cid))
        }
        updateScreen(uuid, force: true)   // the answered call takes the screen (call waiting: not the held one)
    }

    func performEnd(_ uuid: UUID) {
        LinkJournal.shared.add("end from this iPhone (\(cids[uuid] ?? "no call id yet"))")
        model.audio.ringback = false
        if let cid = cids[uuid] {
            let ringing = lastState[cid] == "ringing"
            model.link.send(ringing ? MsgType.callReject : MsgType.callEnd, CallRef(cid: cid))
            hungUp.hungUp(cid)
            uuids[cid] = nil
            cids[uuid] = nil
            lastState[cid] = nil
            info[uuid] = nil
        } else if pendingOutgoing == uuid {
            pendingOutgoing = nil
            cancelledDialAt = Date()
            cancelledNumber = pendingNumber
            dialWatchdog?.cancel()
        }
        cleanup(uuid)
    }

    func performMute(_ uuid: UUID, _ on: Bool) {
        model.audio.muted = on
        model.link.send(MsgType.callMute, CallMute(on: on))
        if model.callScreen?.uuid == uuid { model.callScreen?.muted = on }
    }

    func performHold(_ uuid: UUID, _ on: Bool) -> Bool {
        guard let cid = cids[uuid] else { return false }
        if mirrored.remove(uuid) != nil {   // mirroring a hold done on the Android phone: nothing to send
            if model.callScreen?.uuid == uuid { model.callScreen?.held = on }
            return true
        }
        holdRequested.insert(uuid)
        model.link.send(MsgType.callHold, CallHold(cid: cid, on: on))
        if model.callScreen?.uuid == uuid { model.callScreen?.held = on }
        return true
    }

    func performDtmf(_ uuid: UUID, _ digits: String) {
        guard let cid = cids[uuid] else { return }
        for d in digits { model.link.send(MsgType.callDtmf, CallDtmf(cid: cid, d: String(d))) }
    }
}

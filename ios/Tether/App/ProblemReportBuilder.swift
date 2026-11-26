import AVFoundation
import CallKit
import CoreBluetooth
import Foundation
import UIKit
import UserNotifications
@_spi(TetherApp) import TetherCore

/// The app's own recent events (link state changes, failures, foreground / background), kept in memory for the problem
/// report's "App events" section. No message text, no numbers, no names.
final class AppEvents: @unchecked Sendable {
    static let shared = AppEvents()
    private let lock = NSLock()
    private var lines: [String] = []
    private let cap = 200
    private let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MM-dd HH:mm:ss.SSS"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()

    func add(_ event: String) {
        let line = "\(fmt.string(from: Date())) \(event)"
        lock.lock(); defer { lock.unlock() }
        lines.append(line)
        if lines.count > cap { lines.removeFirst(lines.count - cap) }
    }

    /// Newest first.
    var newestFirst: [String] { lock.lock(); defer { lock.unlock() }; return lines.reversed() }
}

/// Gathers what the problem report says of this iPhone, its permissions, the link and the last call (Link tab ›
/// Send a problem report). The text is put together, scrubbed and capped by `ProblemReport` in TetherCore.
@MainActor enum ProblemReportBuilder {
    /// The finished text and when it was made.
    static func text(model: AppModel, now: Date = Date()) async -> (String, Date) {
        (await make(model: model, now: now).render(), now)
    }

    static func make(model: AppModel, now: Date = Date()) async -> ProblemReport {
        let notifications = await UNUserNotificationCenter.current().notificationSettings()
        return ProblemReport(
            app: appLine(),
            created: now,
            sections: [
                .init("Device", device()),
                .init("Setup", setup(model: model, notifications: notifications)),
                .init("Link", link(model: model)),
                .init("Calls and audio", calls(model: model)),
                .init("Android phone (from its last status)", ProblemReport.androidLines(status: model.status, net: model.net)),
            ],
            linkLog: LinkJournal.shared.text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init),
            events: AppEvents.shared.newestFirst,
            literals: [model.peerName])
    }

    static func appLine() -> String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "Cellular \(v) (\(b)) · iPhone app"
    }

    // MARK: Device

    /// "iPhone17,3" (the Simulator reports the model it pretends to be).
    static func modelIdentifier() -> String {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim + " (Simulator)" }
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    }

    static func device() -> [String] {
        let p = ProcessInfo.processInfo
        var l = ["model: \(modelIdentifier())",
                 "iOS: \(UIDevice.current.systemVersion)",
                 "language: \(Locale.preferredLanguages.first ?? "?"), region \(Locale.current.region?.identifier ?? "?")",
                 "low power mode: \(p.isLowPowerModeEnabled ? "on" : "off")",
                 "thermal state: \(thermal(p.thermalState))"]
        l.append("installed: \(installKind())")
        return l
    }

    static func thermal(_ s: ProcessInfo.ThermalState) -> String {
        switch s { case .nominal: "nominal"; case .fair: "fair"; case .serious: "serious"; case .critical: "critical"; @unknown default: "unknown" }
    }

    /// An embedded provisioning profile means a sideloaded build (SideStore, AltStore, Sideloadly, Xcode); none means
    /// the App Store, TestFlight or a tool that installs without one (TrollStore).
    static func installKind() -> String {
        #if targetEnvironment(simulator)
        return "Simulator"
        #else
        if Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision") != nil {
            if let e = Signing.expiry { return "sideloaded, signature runs out \(ProblemReport.dateText(e, timeZone: .current)) (\(Signing.daysLeft ?? 0) days)" }
            return "sideloaded"
        }
        return "no embedded profile (App Store, TestFlight or TrollStore)"
        #endif
    }

    // MARK: Setup

    static func setup(model: AppModel, notifications: UNNotificationSettings) -> [String] {
        let modes = (Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]) ?? []
        let needed = ["bluetooth-central", "audio", "voip"]
        let missing = needed.filter { !modes.contains($0) }
        return [
            "paired: \(model.isPaired ? "yes" : "no")",
            "Bluetooth: permission \(bluetoothAuthorization()), \(model.linkState.reportName)",
            "microphone: \(microphonePermission())\(model.micWarning.map { ", finding: \($0.rawValue)" } ?? "")",
            "notifications: \(notificationStatus(notifications.authorizationStatus)), alerts \(setting(notifications.alertSetting)), sounds \(setting(notifications.soundSetting)), badges \(setting(notifications.badgeSetting))",
            "camera: \(cameraAuthorization())",
            "background modes: \(missing.isEmpty ? "ok (\(modes.sorted().joined(separator: ", ")))" : "missing \(missing.joined(separator: ", "))")",
            "background app refresh: \(refresh(UIApplication.shared.backgroundRefreshStatus))",
        ]
    }

    static func bluetoothAuthorization() -> String {
        switch CBManager.authorization {
        case .notDetermined: "not asked"
        case .restricted: "restricted"
        case .denied: "denied"
        case .allowedAlways: "allowed"
        @unknown default: "unknown"
        }
    }

    static func microphonePermission() -> String {
        if #available(iOS 17, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return "allowed"
            case .denied: return "denied"
            case .undetermined: return "not asked"
            @unknown default: return "unknown"
            }
        }
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return "allowed"
        case .denied: return "denied"
        case .undetermined: return "not asked"
        @unknown default: return "unknown"
        }
    }

    static func cameraAuthorization() -> String {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined: "not asked"
        case .restricted: "restricted"
        case .denied: "denied"
        case .authorized: "allowed"
        @unknown default: "unknown"
        }
    }

    static func notificationStatus(_ s: UNAuthorizationStatus) -> String {
        switch s {
        case .notDetermined: "not asked"
        case .denied: "denied"
        case .authorized: "allowed"
        case .provisional: "provisional"
        case .ephemeral: "ephemeral"
        @unknown default: "unknown"
        }
    }

    static func setting(_ s: UNNotificationSetting) -> String {
        switch s { case .notSupported: "n/a"; case .disabled: "off"; case .enabled: "on"; @unknown default: "unknown" }
    }

    static func refresh(_ s: UIBackgroundRefreshStatus) -> String {
        switch s { case .restricted: "restricted"; case .denied: "off"; case .available: "on"; @unknown default: "unknown" }
    }

    // MARK: Link

    /// The journal lines that tell how the link came and went.
    static let linkKeys = ["session up", "session over", "disconnect", "rejected by", "reconnecting", "connect still pending",
                           "GATT fallback", "restarting the session", "resuming restored", "Android phone services changed", "setup"]

    static func link(model: AppModel) -> [String] {
        let journal = LinkJournal.shared.text.split(separator: "\n").map(String.init)   // newest first
        func count(_ key: String) -> Int { journal.filter { $0.contains(key) }.count }
        var l = ["state: \(model.linkState.reportName)",
                 "paired: \(model.isPaired ? "yes" : "no")"]
        if case .connected(let t) = model.linkState { l.append("transport: \(t)") }
        l.append("last keep-alive round trip: \(model.link.lastRoundTripMs.map { "\($0) ms" } ?? "-")")
        l.append("last sync: \(model.lastSync.map { ProblemReport.dateText($0, timeZone: .current) } ?? "-")")
        if let e = model.lastError, !e.isEmpty { l.append("last error shown: \(e)") }
        l.append("in the kept log: sessions up \(count("session up")), sessions over \(count("session over")), disconnects \(count("disconnected")), "
                 + "rejected \(count("rejected by")), setup timeouts \(count("setup")), GATT fallbacks \(count("GATT fallback")), reconnects at once \(count("reconnecting at once"))")
        let last = journal.filter { line in linkKeys.contains { line.contains($0) } }.prefix(8)
        if !last.isEmpty {
            l.append("latest connect / disconnect events (newest first):")
            l += last.map { "  " + $0 }
        }
        l.append(model.extensions.summary)
        return l
    }

    // MARK: Calls and audio

    static func calls(model: AppModel) -> [String] {
        let s = AVAudioSession.sharedInstance()
        var l: [String] = []
        l.append("audio session: category \(s.category.rawValue), mode \(s.mode.rawValue), sample rate \(Int(s.sampleRate)) Hz, io buffer \(String(format: "%.1f", s.ioBufferDuration * 1000)) ms")
        l.append("route in: \(portTypes(s.currentRoute.inputs)), out: \(portTypes(s.currentRoute.outputs))")
        l.append("microphone: permission \(microphonePermission()), warning \(model.micWarning?.rawValue ?? "none")")
        if let c = model.callScreen {
            l.append("call screen: \(c.state), speaker \(c.speaker ? "on" : "off"), route \(c.route.rawValue)")
        } else {
            l.append("call screen: none")
        }
        l.append("CallKit: calls known \(CXCallObserver().calls.count)")
        if let a = model.lastAudioStats {
            l.append(String(format: "last call: playout delay %.0f ms (target %.0f), underruns %d, drained %.0f ms, added %.0f ms, lost %d of %d frames, arrival jitter %.1f ms, worst gap %.0f ms, io %.1f ms, out latency %.1f ms",
                            a.delayMs, a.playoutMs, a.underruns, a.trimmedMs, a.addedMs, a.lost, a.frames, a.jitterMs, a.worstGapMs, a.ioBufferMs, a.outputLatencyMs))
        } else {
            l.append("last call: no call since the app started")
        }
        let audioLines = LinkJournal.shared.text.split(separator: "\n").map(String.init)
            .filter { $0.contains("call audio:") || $0.contains("microphone:") || $0.contains("audio route") }.prefix(6)
        if !audioLines.isEmpty {
            l.append("latest call audio lines (newest first; concealed, drift and round trip are in them):")
            l += audioLines.map { "  " + $0 }
        }
        return l
    }

    /// Port types only ("BluetoothHFP", "Speaker"), never the names of devices.
    static func portTypes(_ ports: [AVAudioSessionPortDescription]) -> String {
        ports.isEmpty ? "-" : ports.map { $0.portType.rawValue }.joined(separator: ", ")
    }
}

extension TetherLink.State {
    /// For the problem report: no associated values but the transport.
    var reportName: String {
        switch self {
        case .poweredOff: "Bluetooth off"
        case .unauthorized: "Bluetooth not allowed"
        case .unpaired: "not paired"
        case .searching: "searching"
        case .connecting: "connecting"
        case .handshaking: "handshaking"
        case .connected(let t): "connected over \(t)"
        }
    }
}

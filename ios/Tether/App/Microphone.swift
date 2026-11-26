import AVFoundation
import Foundation
import TetherCore
import UIKit
import UserNotifications

/// This iPhone's microphone permission as Tether sees it.
enum MicAccess: String, Sendable { case granted, denied, undetermined }

/// What is wrong with this iPhone's microphone in a call (nil: nothing known).
enum MicWarning: String, Sendable, Equatable {
    /// Tether may not use the microphone: every call's uplink is silence.
    case denied
    /// The microphone delivered nothing but digital silence for several seconds of a connected call.
    case silent
}

/// The microphone rules (#14), without AVFoundation so they can be tested. iOS shows its permission alert once; after
/// a "Don't Allow" only Settings can change it, and the engine then captures digital silence without any error, so the
/// far end hears nothing while everything else looks fine. Hence: ask as soon as the app is paired (before the first
/// call can be answered from the lock screen, where no alert can show), ask again when a call starts if it is still
/// open, warn on the call screen and in the Link tab, and never call a silent uplink working.
enum MicPolicy {
    /// Ask now: paired, the question still open, and the app in the foreground (the alert needs it).
    static func shouldAsk(_ access: MicAccess, paired: Bool, foreground: Bool) -> Bool {
        paired && foreground && access == .undetermined
    }

    enum CallCheck: Equatable {
        /// Allowed: nothing to do.
        case fine
        /// Still open: ask now (shown once the iPhone is unlocked and the app in front).
        case ask
        /// Refused: the other side will hear nothing; say so on the call screen, in the Link log and, in the
        /// background, in a notification.
        case denied
    }

    /// The check when CallKit starts or answers a call.
    static func atCall(_ access: MicAccess) -> CallCheck {
        switch access {
        case .granted: return .fine
        case .undetermined: return .ask
        case .denied: return .denied
        }
    }

    /// The warning for the call screen and the Link tab: a refusal wins over a silent capture.
    static func warning(access: MicAccess, captureSilent: Bool) -> MicWarning? {
        if access == .denied { return .denied }
        return captureSilent ? .silent : nil
    }

    /// Seconds of a connected call with only digital silence from the microphone before it counts as silent. Long
    /// enough that a pause in a quiet room never counts (the built-in microphone always delivers some noise; a
    /// refused or broken one exact zeros).
    static let silentAfterSeconds = 6.0

    /// The microphone counts as silent: `samples` captured while the call was connected, none of them above zero.
    static func captureSilent(samples: Int, rate: Double, anySound: Bool) -> Bool {
        !anySound && Double(samples) >= silentAfterSeconds * rate
    }
}

/// The permission flow on this iPhone (main actor): asking, checking at call time, and telling the owner.
@MainActor
enum Microphone {
    static var access: MicAccess {
        if #available(iOS 17, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return .granted
            case .denied: return .denied
            default: return .undetermined
            }
        }
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return .granted
        case .denied: return .denied
        default: return .undetermined
        }
    }

    /// The unit tests' host and demo screenshots never show the alert.
    private static var quiet: Bool {
        AppModel.runningTests || AppModel.demoMode || ProcessInfo.processInfo.arguments.contains("-TetherDemoQuiet")
    }

    /// Asks once the app is paired and in the foreground (after pairing, and at the first start of an app updated
    /// from a version that never asked).
    static func askIfNeeded(paired: Bool, foreground: Bool) {
        guard !quiet, MicPolicy.shouldAsk(access, paired: paired, foreground: foreground) else { return }
        request("paired")
    }

    /// CallKit starts or answers a call (`what`: for the Link log).
    static func checkForCall(_ what: String) {
        let a = access
        switch MicPolicy.atCall(a) {
        case .fine: break
        case .ask:
            LinkJournal.shared.add("microphone: not asked yet at \(what): asking")
            if !quiet { request(what) }
        case .denied:
            LinkJournal.shared.add("microphone: denied at \(what): the other side will hear nothing")
            notifyDenied()
        }
        AppModel.shared.micWarning = MicPolicy.warning(access: a, captureSilent: false)
    }

    private static func request(_ why: String) {
        let done: @Sendable (Bool) -> Void = { ok in
            DispatchQueue.main.async {
                MainActor.assumeIsolatedCompat {
                    LinkJournal.shared.add("microphone: \(ok ? "allowed" : "refused") (asked: \(why))")
                    if !ok { AppModel.shared.micWarning = .denied }
                    else if AppModel.shared.micWarning == .denied { AppModel.shared.micWarning = nil }
                }
            }
        }
        if #available(iOS 17, *) { AVAudioApplication.requestRecordPermission(completionHandler: done) }
        else { AVAudioSession.sharedInstance().requestRecordPermission(done) }
    }

    /// A call answered from the lock screen with the microphone refused: the owner cannot see the app, so tell them.
    private static func notifyDenied() {
        guard !quiet, UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = String(localized: "Microphone off for Tether")
        c.body = String(localized: "The other side cannot hear you. Allow the microphone in Settings › Privacy & Security › Microphone.")
        c.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "mic-denied", content: c, trigger: nil))
    }

    /// The call screen's and the Link tab's line for a warning.
    static func text(_ w: MicWarning) -> String {
        switch w {
        case .denied: return String(localized: "Microphone off for Tether: the other side cannot hear you. Allow it in Settings › Privacy & Security › Microphone.")
        case .silent: return String(localized: "This iPhone's microphone gives no sound: the other side may not hear you.")
        }
    }
}

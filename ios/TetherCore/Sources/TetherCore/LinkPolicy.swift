import Foundation

/// A counter shared across threads under an unfair lock held for one increment.
final class RequestIds: @unchecked Sendable {
    private let lock = UnfairLock()
    private var last = 1
    func next() -> Int { lock.lock(); defer { lock.unlock() }; last += 1; return last }
}

/// When to try the link again after a failure (pure, for tests): the failures of the last minute decide. Up to three
/// reconnect at once (a pending connect, plus a scan after 1 s); more back off 2 s per failure, at most 30 s.
enum ReconnectPolicy {
    enum Action: Equatable { case atOnce, backOff(Double) }
    static let window: TimeInterval = 60
    static func next(failures: [Date], now: Date) -> (failures: [Date], action: Action) {
        let recent = failures.filter { now.timeIntervalSince($0) < window } + [now]
        return (recent, recent.count <= 3 ? .atOnce : .backOff(Double(min(30, 2 * recent.count))))
    }
}

/// What `TetherLink.startIfReady` does with the peripheral it holds (pure, without CoreBluetooth, for tests).
enum LinkStart {
    /// The held peripheral's connection state (`none`: the link holds no peripheral).
    enum Phase: Equatable { case none, disconnected, connecting, connected, disconnecting }

    enum Step: Equatable {
        /// Bluetooth is off or not authorised yet.
        case notReady
        /// Nothing paired.
        case unpaired
        /// A connect is pending: leave it, iOS completes it when the Android phone is in range (also while suspended).
        case waitForConnect
        /// Connected (restored by iOS after a relaunch) but without a session: set one up on this connection.
        case resume
        /// Connected, with a session up or being set up: nothing to do.
        case keep
        /// Connect to the known peripheral (if any) and scan.
        case connect
    }

    static func step(poweredOn: Bool, paired: Bool, held: Phase, sessionOrSetup: Bool) -> Step {
        guard poweredOn else { return .notReady }
        guard paired else { return .unpaired }
        switch held {
        case .connecting: return .waitForConnect
        case .connected: return sessionOrSetup ? .keep : .resume
        case .none, .disconnected, .disconnecting: return .connect
        }
    }

    /// Renewing a pending connect (opening the app, the 10 s connect watchdog): whether to cancel it, and whether the
    /// link forgets the peripheral before starting again. It must forget it at once: CoreBluetooth reports a cancelled
    /// connect as still `connecting` for a while, and `step` then waited for a connect that would never complete, so the
    /// link never reconnected (#15). A connected peripheral is never renewed.
    static func renew(_ held: Phase) -> (cancel: Bool, forget: Bool) {
        switch held {
        case .connecting: return (true, true)
        case .connected: return (false, false)
        case .none, .disconnected, .disconnecting: return (false, true)
        }
    }
}

/// What the iPhone does when the Android phone's wake notification arrives (TetherLink.wokenByPhone). Pure.
public enum WakeCheck {
    public enum Action: Equatable { case answer, newSession, ignore }
    /// Seconds the session has to answer the ping sent on a wake (iOS gives a woken app about 10 s).
    public static let answerSeconds: Double = 4

    public static func onWake(hasSession: Bool, settingUp: Bool) -> Action {
        if settingUp { return .ignore }
        return hasSession ? .answer : .newSession
    }

    /// The session did not answer: nothing arrived since the wake.
    public static func stale(lastReceived: Date, wokenAt: Date) -> Bool { lastReceived < wokenAt }
}

import Foundation
import XCTest
@testable import TetherCore

/// The link's retry rules: opening the app after a failure must link within seconds, and a link that keeps failing
/// must back off instead of looping.
final class LinkPolicyTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testFirstThreeFailuresReconnectAtOnce() {
        var f: [Date] = []
        for i in 0..<3 {
            let s = ReconnectPolicy.next(failures: f, now: t0.addingTimeInterval(Double(i)))
            XCTAssertEqual(s.action, .atOnce, "failure \(i + 1)")
            f = s.failures
        }
        XCTAssertEqual(f.count, 3)
    }

    func testBackOffGrowsAndIsCapped() {
        var f: [Date] = [], actions: [ReconnectPolicy.Action] = []
        for i in 0..<20 {
            let s = ReconnectPolicy.next(failures: f, now: t0.addingTimeInterval(Double(i)))
            f = s.failures; actions.append(s.action)
        }
        XCTAssertEqual(Array(actions[3...6]), [.backOff(8), .backOff(10), .backOff(12), .backOff(14)])
        XCTAssertEqual(actions.last, .backOff(30))
        for case .backOff(let s) in actions { XCTAssertLessThanOrEqual(s, 30) }
    }

    /// Failures older than a minute are forgotten: one failure after a quiet minute reconnects at once.
    func testOldFailuresExpire() {
        let old = (0..<10).map { t0.addingTimeInterval(Double($0)) }
        let s = ReconnectPolicy.next(failures: old, now: t0.addingTimeInterval(70))
        XCTAssertEqual(s.action, .atOnce)
        XCTAssertEqual(s.failures.count, 1)
        let edge = ReconnectPolicy.next(failures: [t0, t0, t0], now: t0.addingTimeInterval(60))
        XCTAssertEqual(edge.action, .atOnce, "exactly 60 s old no longer counts")
    }

    /// Request ids are unique across threads (a reply is matched to its request by id) and start at 2.
    func testRequestIdsAreUniqueUnderConcurrency() {
        let ids = RequestIds(), lock = NSLock()
        var seen: [Int] = []
        DispatchQueue.concurrentPerform(iterations: 1000) { _ in
            let i = ids.next(); lock.lock(); seen.append(i); lock.unlock()
        }
        XCTAssertEqual(Set(seen).count, 1000)
        XCTAssertEqual(seen.min(), 2)
        XCTAssertEqual(seen.max(), 1001)
    }

    #if canImport(CoreBluetooth)
    func testStateIsConnected() {
        XCTAssertTrue(TetherLink.State.connected(transport: "l2cap").isConnected)
        XCTAssertFalse(TetherLink.State.connecting.isConnected)
        XCTAssertFalse(TetherLink.State.handshaking.isConnected)
    }
    #endif
}

/// What the link does with the peripheral it holds when it (re)starts, and renewing a pending connect (#15).
final class LinkStartTests: XCTestCase {
    func testStepsPerPhase() {
        XCTAssertEqual(LinkStart.step(poweredOn: false, paired: true, held: .none, sessionOrSetup: false), .notReady)
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: false, held: .connecting, sessionOrSetup: false), .unpaired)
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: .connecting, sessionOrSetup: false), .waitForConnect)
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: .connected, sessionOrSetup: false), .resume)
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: .connected, sessionOrSetup: true), .keep)
        for p in [LinkStart.Phase.none, .disconnected, .disconnecting] {
            XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: p, sessionOrSetup: false), .connect, "\(p)")
        }
    }

    /// The bug: after cancelling a pending connect, CoreBluetooth still reports the peripheral as connecting for a
    /// while. Holding on to it, the start waited for a connect that never completes; forgetting it connects again.
    func testRenewingAPendingConnectConnectsAgain() {
        var held = LinkStart.Phase.connecting
        let r = LinkStart.renew(held)
        XCTAssertTrue(r.cancel)
        XCTAssertTrue(r.forget, "the cancelled peripheral is forgotten at once")
        // Kept (the old behaviour), it still reads as connecting right after the cancel: the start would wait.
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: held, sessionOrSetup: false), .waitForConnect)
        if r.forget { held = .none }
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: held, sessionOrSetup: false), .connect)
    }

    /// Renewing never cancels a connected peripheral (a restored connection is resumed, not dropped).
    func testRenewingKeepsAConnection() {
        let r = LinkStart.renew(.connected)
        XCTAssertFalse(r.cancel)
        XCTAssertFalse(r.forget)
        XCTAssertEqual(LinkStart.step(poweredOn: true, paired: true, held: .connected, sessionOrSetup: false), .resume)
        for p in [LinkStart.Phase.none, .disconnected, .disconnecting] { XCTAssertFalse(LinkStart.renew(p).cancel, "\(p)") }
    }

    func testAWakeIsAnsweredOrStartsASession() {
        // 2026-11-25: the Android phone closed a session that stayed silent after its wake, and the suspended app was
        // never told; it now answers every wake, and sets a session up when it has none.
        XCTAssertEqual(WakeCheck.onWake(hasSession: true, settingUp: false), .answer)
        XCTAssertEqual(WakeCheck.onWake(hasSession: false, settingUp: false), .newSession)
        XCTAssertEqual(WakeCheck.onWake(hasSession: false, settingUp: true), .ignore)
        let woken = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(WakeCheck.stale(lastReceived: woken.addingTimeInterval(-60), wokenAt: woken))
        XCTAssertFalse(WakeCheck.stale(lastReceived: woken.addingTimeInterval(0.2), wokenAt: woken))
    }
}

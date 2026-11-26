import Foundation
import XCTest
@_spi(TetherApp) @testable import TetherCore

/// The lock shared with the real-time audio thread (the iPhone app's render-thread flags use it too, #15): its lock
/// word lives in its own allocation, so every thread locks the same word.
final class LockTests: XCTestCase {
    private final class Counter: @unchecked Sendable {
        let lock = UnfairLock()
        var value = 0
        var flag = false
    }

    func testExcludesAcrossThreads() {
        let c = Counter()
        DispatchQueue.concurrentPerform(iterations: 8) { _ in
            for _ in 0..<20_000 { c.lock.withLock { c.value += 1 } }
        }
        XCTAssertEqual(c.value, 160_000)
    }

    /// A reader and a writer on different threads (as the render thread and the audio queue): a pair written
    /// together is never seen half-written.
    func testPairsWrittenTogetherAreSeenTogether() {
        let c = Counter()
        let done = expectation(description: "writer")
        DispatchQueue.global().async {
            for i in 0..<50_000 { c.lock.withLock { c.value = i; c.flag = i % 2 == 1 } }
            done.fulfill()
        }
        var torn = 0
        for _ in 0..<50_000 { c.lock.withLock { if (c.value % 2 == 1) != c.flag { torn += 1 } } }
        wait(for: [done], timeout: 10)
        XCTAssertEqual(torn, 0)
    }

    func testWithLockReturnsAndRethrowsAndUnlocks() {
        struct Boom: Error {}
        let l = UnfairLock()
        XCTAssertEqual(l.withLock { 42 }, 42)
        XCTAssertThrowsError(try l.withLock { throw Boom() })
        // Unlocked after the throw: taking it again does not deadlock.
        XCTAssertEqual(l.withLock { "again" }, "again")
    }
}

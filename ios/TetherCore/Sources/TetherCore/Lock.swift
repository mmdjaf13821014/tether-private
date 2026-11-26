#if canImport(os)
import os
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#elseif canImport(Android)
import Android
#elseif canImport(WinSDK)
import WinSDK
#endif

/// A short-critical-section lock that is safe on a real-time audio thread: `os_unfair_lock` on Apple platforms
/// (as before), a `pthread_mutex_t` elsewhere (an `SRWLOCK` on Windows). The lock word lives in its own allocation,
/// so its address never moves (an `os_unfair_lock` stored as a property and passed as `&property` may be moved or
/// copied by Swift, which Apple's documentation forbids). Shared with the iPhone app (SPI) for its render-thread flags.
@_spi(TetherApp) public final class UnfairLock: @unchecked Sendable {
    #if canImport(os)
    private let raw: UnsafeMutablePointer<os_unfair_lock>
    #elseif canImport(WinSDK)
    private let raw: UnsafeMutablePointer<SRWLOCK>
    #else
    private let raw: UnsafeMutablePointer<pthread_mutex_t>
    #endif

    public init() {
        raw = .allocate(capacity: 1)
        #if canImport(os)
        raw.initialize(to: os_unfair_lock())
        #elseif canImport(WinSDK)
        InitializeSRWLock(raw)
        #else
        raw.initialize(to: pthread_mutex_t())
        pthread_mutex_init(raw, nil)
        #endif
    }

    deinit {
        #if !canImport(os) && !canImport(WinSDK)
        pthread_mutex_destroy(raw)
        #endif
        raw.deallocate()
    }

    @inline(__always) public func lock() {
        #if canImport(os)
        os_unfair_lock_lock(raw)
        #elseif canImport(WinSDK)
        AcquireSRWLockExclusive(raw)
        #else
        pthread_mutex_lock(raw)
        #endif
    }

    @inline(__always) public func unlock() {
        #if canImport(os)
        os_unfair_lock_unlock(raw)
        #elseif canImport(WinSDK)
        ReleaseSRWLockExclusive(raw)
        #else
        pthread_mutex_unlock(raw)
        #endif
    }

    /// Runs `body` with the lock held (keep it to a few instructions on a real-time thread).
    @inline(__always) public func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }
        return try body()
    }
}

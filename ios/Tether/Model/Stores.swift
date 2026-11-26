import CryptoKit
import Foundation
import Security
import TetherCore

/// Throwaway identity for demo mode: never paired, nothing stored.
final class MemoryIdentityStore: IdentityStore {
    private let key = P256.KeyAgreement.PrivateKey()
    func staticKey() -> P256.KeyAgreement.PrivateKey { key }
    var peerKey: Data?
    var peerName: String?
    var peripheralID: UUID?
}

/// Link identity in the Keychain (available after first unlock, so BLE background wake works while locked).
final class KeychainIdentityStore: IdentityStore {
    private let service = "app.tether.identity"

    private func read(_ account: String) -> Data? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    private func write(_ account: String, _ data: Data?) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
        guard let data else { return }
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    func staticKey() -> P256.KeyAgreement.PrivateKey {
        if let d = read("static"), let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d) { return k }
        let k = P256.KeyAgreement.PrivateKey()
        write("static", k.rawRepresentation)
        return k
    }

    var peerKey: Data? {
        get { read("peer") }
        set { write("peer", newValue) }
    }

    var peerName: String? {
        get { read("peerName").flatMap { String(data: $0, encoding: .utf8) } }
        set { write("peerName", newValue.map { Data($0.utf8) }) }
    }

    var peripheralID: UUID? {
        get { read("peripheral").flatMap { String(data: $0, encoding: .utf8) }.flatMap(UUID.init) }
        set { write("peripheral", newValue.map { Data($0.uuidString.utf8) }) }
    }
}

/// Everything synced from the Android phone, kept only in this app (a JSON file with file protection).
struct Snapshot: Codable {
    var sms: [Sms] = []
    var contacts: [Contact] = []
    var recents: [CallEntry] = []
    var contactsVersion = ""
    var readUpTo: [Int64: Int64] = [:]          // thread → newest SMS id the user has seen
    var drafts: [String: String] = [:]           // address key → draft text
    var threadSim: [String: Int] = [:]           // address key → preferred subscription id
    var lastStatus: StatusBody?
    /// Blocked numbers / muted conversations as the Android phone last sent them (optional: older files lack it).
    var lists: BlockLists?
    /// Highest SMS id of the last *completed* sync (a live sms.new does not move it; nil in older files).
    var smsSyncedUpTo: Int64?
    /// Texts written while the link was down, sent on reconnect (kept across launches).
    var outbox: [OutboxItem]?
    /// Newest call-log id seen in Recents (missed-call badge).
    var recentsSeenUpTo: Int64?
}

struct OutboxItem: Codable { var to: String; var body: String; var sub: Int?; var placeholder: Int64 }

final class SnapshotStore {
    private let url: URL
    private let queue = DispatchQueue(label: "tether.store")
    private var pending: DispatchWorkItem?

    init(url: URL? = nil) {
        if let url { self.url = url; return }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.url = dir.appendingPathComponent("tether-data.json")
    }

    func load() -> Snapshot {
        guard let d = try? Data(contentsOf: url) else { return Snapshot() }
        if let s = try? JSONDecoder().decode(Snapshot.self, from: d) { return s }
        // Unreadable (damaged, or from a build that changed the format): keep it aside instead of letting the
        // next save overwrite it, so the data can still be recovered.
        let aside = url.deletingPathExtension().appendingPathExtension("corrupt.json")
        try? FileManager.default.removeItem(at: aside)
        try? FileManager.default.moveItem(at: url, to: aside)
        return Snapshot()
    }

    /// Debounced write.
    func save(_ s: Snapshot) {
        pending?.cancel()
        let item = DispatchWorkItem { [url] in
            guard let d = try? JSONEncoder().encode(s) else { return }
            #if os(iOS)
            try? d.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try? d.write(to: url, options: [.atomic])
            #endif
        }
        pending = item
        queue.asyncAfter(deadline: .now() + 1, execute: item)
    }

    /// Written before returning (a notification reply: the app may be suspended right after).
    func saveNow(_ s: Snapshot) {
        pending?.cancel(); pending = nil
        queue.sync {
            guard let d = try? JSONEncoder().encode(s) else { return }
            #if os(iOS)
            try? d.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try? d.write(to: url, options: [.atomic])
            #endif
        }
    }

    /// Deletes the file, after any write still queued (a pending save must not bring the old data back).
    func wipe() {
        pending?.cancel(); pending = nil
        queue.sync { try? FileManager.default.removeItem(at: url) }
    }
}


/// The last few hundred link events (connects, drops with their CoreBluetooth error codes, setup steps), kept
/// across launches so a drop can be explained afterwards (Link › Link log). Thread-safe; written off the main thread.
final class LinkJournal: @unchecked Sendable {
    static let shared = LinkJournal()
    private let q = DispatchQueue(label: "tether.journal")
    private var lines: [String] = []
    private let url: URL
    private let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MM-dd HH:mm:ss.SSS"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()

    private let cap: Int

    init(url: URL? = nil, cap: Int = 600) {
        self.cap = cap
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("link-log.txt")
        lines = ((try? String(contentsOf: self.url, encoding: .utf8)) ?? "").split(separator: "\n").suffix(cap).map(String.init)
    }

    /// Waits for queued writes (tests).
    func flush() { q.sync {} }

    func add(_ event: String) {
        let line = "\(fmt.string(from: Date())) \(event)"
        q.async {
            self.lines.append(line)
            if self.lines.count > self.cap { self.lines.removeFirst(self.lines.count - self.cap) }
            try? (self.lines.joined(separator: "\n") + "\n").write(to: self.url, atomically: true, encoding: .utf8)
        }
    }

    var text: String { q.sync { lines.reversed().joined(separator: "\n") } }
}

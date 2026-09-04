import Foundation
import Observation
import Security

/// Session and the optional Anthropic key, both in the Keychain (never iCloud-synchronised).
/// One instance per process (`shared`): the sign-in sheet, Settings, the Bro tab and the AI flow all observe it.
@Observable
@MainActor
final class AuthStore {
    static let shared = AuthStore()

    private(set) var session: Session?
    /// Masked Anthropic key for display, e.g. "sk-ant-…1234". `nil` when no key is stored.
    private(set) var maskedAnthropicKey: String?

    var isSignedIn: Bool { session != nil }
    var hasAnthropicKey: Bool { maskedAnthropicKey != nil }

    /// `true` when the real backend is in use and there is no session: Bro pairing and AI estimates
    /// show their "Sign in" state instead of calling the network. The demo backend never needs an account.
    var needsSignIn: Bool { !AppConfig.shared.useMockBackend && session == nil }

    init() {
        session = KeychainHelper.readSession()
        maskedAnthropicKey = KeychainHelper.readAnthropicKey().map(Self.mask)
    }

    func save(_ session: Session) {
        KeychainHelper.writeSession(session)
        self.session = session
    }

    func clear() {
        KeychainHelper.delete(account: KeychainHelper.Account.session)
        session = nil
    }

    /// Re-reads the Keychain after the transport rotated (or dropped) the session off the main actor.
    func reload() {
        session = KeychainHelper.readSession()
    }

    // MARK: Anthropic key (BYOK)

    /// The raw key. Read on demand for the request header; never held in a view.
    var anthropicKey: String? {
        get { KeychainHelper.readAnthropicKey() }
        set {
            if let key = newValue?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty {
                KeychainHelper.writeAnthropicKey(key)
                maskedAnthropicKey = Self.mask(key)
            } else {
                removeAnthropicKey()
            }
        }
    }

    func removeAnthropicKey() {
        KeychainHelper.delete(account: KeychainHelper.Account.anthropicKey)
        maskedAnthropicKey = nil
    }

    /// "sk-ant-api03-…7Yq2" → "sk-ant-…7Yq2"
    static func mask(_ key: String) -> String {
        let suffix = String(key.suffix(4))
        let prefix = key.hasPrefix("sk-ant-") ? "sk-ant-" : String(key.prefix(min(3, max(0, key.count - 4))))
        return "\(prefix)…\(suffix)"
    }
}

// MARK: - Keychain

/// Minimal generic-password wrapper. Items are device-only and never synchronisable.
enum KeychainHelper {
    static let service = "app.notomorrow"

    enum Account {
        static let session = "session"
        static let anthropicKey = "anthropic-key"
    }

    // Session: available once the device has been unlocked after boot (background refreshes, push handling).
    static func readSession() -> Session? {
        guard let data = read(account: Account.session) else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

    static func writeSession(_ session: Session) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        write(data, account: Account.session, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    }

    // Anthropic key: only while unlocked; the user is always in the foreground when it is used.
    static func readAnthropicKey() -> String? {
        read(account: Account.anthropicKey).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func writeAnthropicKey(_ key: String) {
        write(Data(key.utf8), account: Account.anthropicKey, accessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
    }

    // MARK: Primitives

    static func read(account: String) -> Data? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: false,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    @discardableResult
    static func write(_ data: Data, account: String, accessible: CFString) -> Bool {
        let base: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: false,
        ]
        let attributes: [CFString: Any] = [kSecValueData: data, kSecAttrAccessible: accessible]
        let update = SecItemUpdate(base as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return true }
        if update != errSecItemNotFound {
            // Accessibility can't change through an update; replace the item.
            SecItemDelete(base as CFDictionary)
        }
        let add = base.merging(attributes) { _, new in new }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    static func delete(account: String) -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: false,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}

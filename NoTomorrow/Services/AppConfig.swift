import Foundation
import Observation

/// Who estimates food photos. `.standard` goes through our backend (Gemini); `.claudeBYOK` uses the user's own
/// Anthropic key from the Keychain and talks to Anthropic directly.
enum AIProvider: String, Codable, CaseIterable, Identifiable {
    case standard
    case claudeBYOK

    var id: String { rawValue }
}

/// Runtime configuration that is not user profile data: AI provider, backend URL, demo-data switch.
@Observable
final class AppConfig {
    static let shared = AppConfig()

    static let defaultBackendURL = URL(string: "https://notomorrow-api.fly.dev")!
    /// Placeholder from before the backend existed; a stored copy of it is ignored.
    private static let legacyBackendURL = "https://api.notomorrow.app"

    var aiProvider: AIProvider {
        didSet { UserDefaults.standard.set(aiProvider.rawValue, forKey: Keys.aiProvider) }
    }

    var backendBaseURL: URL {
        didSet { UserDefaults.standard.set(backendBaseURL.absoluteString, forKey: Keys.backendBaseURL) }
    }

    /// "Use demo data (offline)": the in-memory `MockBackendClient` and `MockAIEstimateService` instead of the
    /// Fly.io backend. Off by default; reachable from Settings → AI estimates → Developer.
    var useMockBackend: Bool {
        didSet { UserDefaults.standard.set(useMockBackend, forKey: Keys.useMockBackend) }
    }

    init(defaults: UserDefaults = .standard) {
        aiProvider = defaults.string(forKey: Keys.aiProvider).flatMap(AIProvider.init(rawValue:)) ?? .standard
        let storedURL = defaults.string(forKey: Keys.backendBaseURL)
        backendBaseURL = storedURL.flatMap { $0 == Self.legacyBackendURL ? nil : URL(string: $0) } ?? Self.defaultBackendURL
        useMockBackend = defaults.bool(forKey: Keys.useMockBackend)
    }

    /// One client per process: the mock keeps partner state (and its 20 s / 10 s timers) in memory, and the
    /// remote client coalesces token refreshes. Rebuilt when the demo switch or the URL changes.
    private var cachedClient: (any BackendClient)?
    private var cachedClientIsMock = true

    func makeBackendClient() -> any BackendClient {
        if let cachedClient, cachedClientIsMock == useMockBackend,
           useMockBackend || cachedClient.baseURL == backendBaseURL {
            return cachedClient
        }
        let client: any BackendClient = useMockBackend
            ? MockBackendClient()
            : RemoteBackendClient(baseURL: backendBaseURL, storage: Self.keychainSessionStorage)
        cachedClient = client
        cachedClientIsMock = useMockBackend
        return client
    }

    /// The remote client reads the session straight from the Keychain (synchronous, any thread) and writes
    /// rotated pairs back the same way, then nudges the observable `AuthStore` so views update.
    static let keychainSessionStorage = SessionStorage(
        read: { KeychainHelper.readSession() },
        write: { session in
            if let session {
                KeychainHelper.writeSession(session)
            } else {
                KeychainHelper.delete(account: KeychainHelper.Account.session)
            }
            Task { @MainActor in AuthStore.shared.reload() }
        }
    )

    private enum Keys {
        static let aiProvider = "nt.aiProvider"
        static let backendBaseURL = "nt.backendBaseURL"
        static let useMockBackend = "nt.useMockBackend"
    }
}

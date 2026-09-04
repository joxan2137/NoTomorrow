import Foundation
import Observation

/// Who estimates food photos. `.standard` goes through our backend (Gemini); `.claudeBYOK` uses the user's own
/// Anthropic key from the Keychain and talks to Anthropic directly.
enum AIProvider: String, Codable, CaseIterable, Identifiable {
    case standard
    case claudeBYOK

    var id: String { rawValue }
}

/// Runtime configuration that is not user profile data: AI provider, backend URL, mock switch.
@Observable
final class AppConfig {
    static let shared = AppConfig()

    static let defaultBackendURL = URL(string: "https://api.notomorrow.app")!

    var aiProvider: AIProvider {
        didSet { UserDefaults.standard.set(aiProvider.rawValue, forKey: Keys.aiProvider) }
    }

    var backendBaseURL: URL {
        didSet { UserDefaults.standard.set(backendBaseURL.absoluteString, forKey: Keys.backendBaseURL) }
    }

    /// `true` until a real backend exists; the whole app is demoable offline against `MockBackendClient`.
    var useMockBackend: Bool {
        didSet { UserDefaults.standard.set(useMockBackend, forKey: Keys.useMockBackend) }
    }

    init(defaults: UserDefaults = .standard) {
        aiProvider = defaults.string(forKey: Keys.aiProvider).flatMap(AIProvider.init(rawValue:)) ?? .standard
        backendBaseURL = defaults.string(forKey: Keys.backendBaseURL).flatMap(URL.init(string:)) ?? Self.defaultBackendURL
        useMockBackend = defaults.object(forKey: Keys.useMockBackend) == nil ? true : defaults.bool(forKey: Keys.useMockBackend)
    }

    /// One client per process: the mock keeps partner state (and its 20 s / 10 s timers) in memory.
    private var cachedClient: (any BackendClient)?
    private var cachedClientIsMock = true

    func makeBackendClient() -> any BackendClient {
        if let cachedClient, cachedClientIsMock == useMockBackend,
           useMockBackend || cachedClient.baseURL == backendBaseURL {
            return cachedClient
        }
        let client: any BackendClient = useMockBackend
            ? MockBackendClient()
            : RemoteBackendClient(baseURL: backendBaseURL, accessToken: { KeychainHelper.readSession()?.accessToken })
        cachedClient = client
        cachedClientIsMock = useMockBackend
        return client
    }

    private enum Keys {
        static let aiProvider = "nt.aiProvider"
        static let backendBaseURL = "nt.backendBaseURL"
        static let useMockBackend = "nt.useMockBackend"
    }
}

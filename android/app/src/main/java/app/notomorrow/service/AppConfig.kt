package app.notomorrow.service

import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.AIProvider
import app.notomorrow.net.BackendClient
import app.notomorrow.net.InMemoryMockPairingStore
import app.notomorrow.net.MockBackendClient
import app.notomorrow.net.MockPairingStore
import app.notomorrow.net.MockStrings
import app.notomorrow.net.RemoteBackendClient
import app.notomorrow.net.SessionStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runtime configuration that is not user-profile data: AI provider, backend URL, demo-data switch —
 * the port of `AppConfig` in `NoTomorrow/Services/AppConfig.swift`.
 *
 * `UserDefaults` + `didSet` becomes `AppPrefs` + a `StateFlow` per key: the flow updates
 * immediately (so a Settings toggle is felt at once) and the DataStore write is fired on [scope].
 * [load] seeds all three from disk and must run once, in `AppContainer`, before the first
 * [makeBackendClient].
 */
class AppConfig(
    private val prefs: AppConfigStore,
    /** Resolved lazily: `AuthStore` is the store, and it needs this object for `needsSignIn`. */
    private val sessionStore: () -> SessionStore,
    private val mockStrings: () -> MockStrings,
    private val mockPairing: () -> MockPairingStore = { InMemoryMockPairingStore() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Test seam; production passes `null` and the transport builds an OkHttp engine. */
    private val engine: HttpClientEngine? = null,
) {

    private val _aiProvider = MutableStateFlow(AIProvider.Standard)
    val aiProvider: StateFlow<AIProvider> = _aiProvider.asStateFlow()

    private val _backendBaseUrl = MutableStateFlow(BackendClient.DEFAULT_BASE_URL)
    val backendBaseUrl: StateFlow<Url> = _backendBaseUrl.asStateFlow()

    /**
     * "Use demo data (offline)": the in-memory [MockBackendClient] and [MockAIEstimateService]
     * instead of the Fly.io backend. Off by default; reachable from Settings → AI estimates → Developer.
     */
    private val _useMockBackend = MutableStateFlow(false)
    val useMockBackend: StateFlow<Boolean> = _useMockBackend.asStateFlow()

    // MARK: - Lifecycle

    /** The `init(defaults:)` half: seed every value from DataStore. */
    suspend fun load() {
        _aiProvider.value = prefs.aiProvider()
        _backendBaseUrl.value = parseBaseUrl(prefs.backendBaseUrl())
        _useMockBackend.value = prefs.useMockBackend()
    }

    // MARK: - Setters (the `didSet` half)

    fun setAiProvider(provider: AIProvider) {
        _aiProvider.value = provider
        scope.launch { prefs.setAiProvider(provider) }
    }

    fun setBackendBaseUrl(url: String) {
        _backendBaseUrl.value = parseBaseUrl(url)
        scope.launch { prefs.setBackendBaseUrl(url) }
    }

    fun setUseMockBackend(value: Boolean) {
        _useMockBackend.value = value
        scope.launch { prefs.setUseMockBackend(value) }
    }

    // MARK: - Backend client

    private var cachedClient: BackendClient? = null
    private var cachedClientIsMock = true

    /**
     * One client per process: the mock keeps partner state (and its 20 s / 10 s timers) in memory,
     * and the remote client coalesces token refreshes. Rebuilt only when the demo switch or the URL
     * changes — so the Settings toggle needs no relaunch, exactly as on iOS.
     */
    @Synchronized
    fun makeBackendClient(): BackendClient {
        val mock = _useMockBackend.value
        val url = _backendBaseUrl.value
        val cached = cachedClient
        if (cached != null && cachedClientIsMock == mock && (mock || cached.baseUrl == url)) {
            return cached
        }
        val client: BackendClient = if (mock) {
            MockBackendClient(strings = mockStrings(), pairing = mockPairing())
        } else {
            RemoteBackendClient(baseUrl = url, store = sessionStore(), engine = engine)
        }
        cachedClient = client
        cachedClientIsMock = mock
        return client
    }

    companion object {
        val DEFAULT_BACKEND_URL: Url = BackendClient.DEFAULT_BASE_URL

        /** Placeholder from before the backend existed; a stored copy of it is ignored. */
        const val LEGACY_BACKEND_URL: String = BackendClient.LEGACY_BASE_URL

        /** A blank, legacy or unparsable value falls back to [DEFAULT_BACKEND_URL]. */
        fun parseBaseUrl(raw: String?): Url {
            if (raw.isNullOrBlank() || raw == LEGACY_BACKEND_URL) return DEFAULT_BACKEND_URL
            return runCatching { Url(raw) }.getOrElse { DEFAULT_BACKEND_URL }
        }
    }
}

/**
 * `nt.mock.paired` — where the demo client remembers that you paired with Tomek. [MockPairingStore]
 * reads synchronously (it is consulted in `MockBackendClient`'s constructor), so the value is
 * mirrored into an atomic and written back on [scope]. Call [load] in `AppContainer` beside
 * [AppConfig.load].
 */
class PrefsMockPairingStore(
    private val prefs: AppPrefs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MockPairingStore {

    private val paired = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun load() {
        paired.set(prefs.mockPairedOnce())
    }

    override fun isPaired(): Boolean = paired.get()

    override fun setPaired(paired: Boolean) {
        this.paired.set(paired)
        scope.launch { prefs.setMockPaired(paired) }
    }
}

/**
 * The three `UserDefaults` keys [AppConfig] owns, behind an interface so the selection logic is
 * testable on the JVM without DataStore. Production is [from]; `AppContainer` builds it from
 * the shared [AppPrefs].
 */
interface AppConfigStore {

    suspend fun aiProvider(): AIProvider

    suspend fun backendBaseUrl(): String

    suspend fun useMockBackend(): Boolean

    suspend fun setAiProvider(provider: AIProvider)

    suspend fun setBackendBaseUrl(url: String)

    suspend fun setUseMockBackend(value: Boolean)

    companion object {
        fun from(prefs: AppPrefs): AppConfigStore = object : AppConfigStore {
            override suspend fun aiProvider(): AIProvider = prefs.aiProviderOnce()
            override suspend fun backendBaseUrl(): String = prefs.backendBaseUrlOnce()
            override suspend fun useMockBackend(): Boolean = prefs.useMockBackendOnce()
            override suspend fun setAiProvider(provider: AIProvider) = prefs.setAiProvider(provider)
            override suspend fun setBackendBaseUrl(url: String) = prefs.setBackendBaseUrl(url)
            override suspend fun setUseMockBackend(value: Boolean) = prefs.setUseMockBackend(value)
        }
    }
}

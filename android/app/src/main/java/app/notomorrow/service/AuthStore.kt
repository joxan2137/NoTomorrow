package app.notomorrow.service

import app.notomorrow.data.prefs.SecureStore
import app.notomorrow.net.SessionStore
import app.notomorrow.net.dto.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Session and the optional BYOK keys (Anthropic, Gemini) — the port of `AuthStore` in
 * `NoTomorrow/Services/AuthStore.swift`, with `SecureStore` (Keystore + Tink + DataStore)
 * standing in for the Keychain.
 *
 * One instance per process: the sign-in sheet, Settings, the Bro tab and the AI flow all observe it.
 * It also **is** the [SessionStore] the transport reads on the hot path of every authenticated call
 * — the analogue of `AppConfig.keychainSessionStorage`, whose write closure nudges the observable
 * store. Because `SecureStore` is `suspend` and [SessionStore.session] must not be, the session is
 * mirrored into a `StateFlow` and written back asynchronously.
 *
 * [load] must run once at container construction, before the first authenticated call.
 */
class AuthStore(
    private val secureStore: SecureStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionStore {

    private val _session = MutableStateFlow<Session?>(null)
    val sessionFlow: StateFlow<Session?> = _session.asStateFlow()

    val isSignedIn: StateFlow<Boolean> =
        _session.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Masked Anthropic key for display, e.g. `"sk-ant-…7Yq2"`. `null` when no key is stored. */
    private val _maskedAnthropicKey = MutableStateFlow<String?>(null)
    val maskedAnthropicKey: StateFlow<String?> = _maskedAnthropicKey.asStateFlow()

    val hasAnthropicKey: StateFlow<Boolean> =
        _maskedAnthropicKey.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Masked Gemini key for display, e.g. `"AIz…7Yq2"`. `null` when no key is stored. */
    private val _maskedGeminiKey = MutableStateFlow<String?>(null)
    val maskedGeminiKey: StateFlow<String?> = _maskedGeminiKey.asStateFlow()

    val hasGeminiKey: StateFlow<Boolean> =
        _maskedGeminiKey.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Fed by `AppConfig.useMockBackend` through [bindMockBackend]; the demo backend needs no account. */
    private val mockBackend = MutableStateFlow(false)

    /**
     * `true` when the real backend is in use and there is no session: Bro pairing and AI estimates
     * show their "Sign in" state instead of calling the network.
     */
    val needsSignIn: StateFlow<Boolean> =
        combine(_session, mockBackend) { session, mock -> !mock && session == null }
            .stateIn(scope, SharingStarted.Eagerly, true)

    // MARK: - Lifecycle

    /** The `init` half of the Swift store: read every record out of the secure store. */
    suspend fun load() {
        _session.value = secureStore.session()
        _maskedAnthropicKey.value = secureStore.anthropicKey()?.let(::mask)
        _maskedGeminiKey.value = secureStore.geminiKey()?.let(::mask)
    }

    /** Wired in `AppContainer`: `authStore.bindMockBackend(appConfig.useMockBackend)`. */
    fun bindMockBackend(flow: Flow<Boolean>) {
        scope.launch { flow.collect { mockBackend.value = it } }
    }

    /** Re-reads the store after the transport rotated (or dropped) the session off this class. */
    suspend fun reload() {
        _session.value = secureStore.session()
    }

    // MARK: - SessionStore (synchronous, any thread)

    override fun session(): Session? = _session.value

    override fun save(session: Session) {
        _session.value = session
        scope.launch { secureStore.setSession(session) }
    }

    override fun clear() {
        _session.value = null
        scope.launch { secureStore.clearSession() }
    }

    /** The sign-in path: the session must be durable before the next call goes out. */
    suspend fun saveAwait(session: Session) {
        secureStore.setSession(session)
        _session.value = session
    }

    /** Sign-out / delete-account: wait for the record to be gone. */
    suspend fun clearAwait() {
        secureStore.clearSession()
        _session.value = null
    }

    // MARK: - Anthropic key (BYOK)

    /** The raw key. Read on demand for the request header; never held in a view. */
    suspend fun anthropicKey(): String? = secureStore.anthropicKey()

    /** A blank key removes the record, exactly as the Swift setter does. */
    suspend fun setAnthropicKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrEmpty()) {
            removeAnthropicKey()
            return
        }
        secureStore.setAnthropicKey(trimmed)
        _maskedAnthropicKey.value = mask(trimmed)
    }

    suspend fun removeAnthropicKey() {
        secureStore.clearAnthropicKey()
        _maskedAnthropicKey.value = null
    }

    // MARK: - Gemini key (BYOK)

    /** The raw key. Read on demand for the request header; never held in a view. */
    suspend fun geminiKey(): String? = secureStore.geminiKey()

    /** A blank key removes the record, exactly as the Swift setter does. */
    suspend fun setGeminiKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrEmpty()) {
            removeGeminiKey()
            return
        }
        secureStore.setGeminiKey(trimmed)
        _maskedGeminiKey.value = mask(trimmed)
    }

    suspend fun removeGeminiKey() {
        secureStore.clearGeminiKey()
        _maskedGeminiKey.value = null
    }

    companion object {
        /** `"sk-ant-api03-…7Yq2"` → `"sk-ant-…7Yq2"`. */
        fun mask(key: String): String {
            val suffix = key.takeLast(4)
            val prefix = if (key.startsWith("sk-ant-")) {
                "sk-ant-"
            } else {
                key.take(minOf(3, maxOf(0, key.length - 4)))
            }
            return "$prefix…$suffix"
        }
    }
}

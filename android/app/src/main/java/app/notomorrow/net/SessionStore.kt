package app.notomorrow.net

import app.notomorrow.net.dto.Session
import java.util.concurrent.atomic.AtomicReference

/**
 * Where [RemoteTransport] reads the session before every request and writes it
 * back after a refresh (rotated pair) or when the refresh was rejected — the
 * port of Swift's `SessionStorage`.
 *
 * **Reads must be synchronous and cheap**: they happen on the hot path of every
 * authenticated call. `AuthStore` (service/, Wave 2) implements this over the
 * Keystore-backed `SecureStore` and nudges its observable state from [save] and
 * [clear], the way `AppConfig.keychainSessionStorage` does on iOS.
 */
interface SessionStore {

    /** The stored session, or `null` when signed out. */
    fun session(): Session?

    /** Persist a freshly issued or rotated pair. */
    fun save(session: Session)

    /** Drop the session — the refresh token was rejected, or the user signed out. */
    fun clear()
}

/** Process-local store: the mock client, tests and previews. */
class InMemorySessionStore(initial: Session? = null) : SessionStore {

    private val ref = AtomicReference(initial)

    override fun session(): Session? = ref.get()

    override fun save(session: Session) {
        ref.set(session)
    }

    override fun clear() {
        ref.set(null)
    }
}

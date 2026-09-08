package app.notomorrow.net

import app.notomorrow.net.dto.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a `POST auth/refresh` round-trip settled on. */
sealed interface RefreshOutcome {
    data class Rotated(val session: Session) : RefreshOutcome

    /** The server rejected the refresh token (expired, revoked, reused): the session is gone. */
    data object Rejected : RefreshOutcome

    /** Could not reach the server: keep the session and let the caller report `network`. */
    data object Unreachable : RefreshOutcome
}

/**
 * Serialises refreshes so concurrent 401s rotate the pair **once** — the port of
 * the Swift `TokenRefresher` actor.
 *
 * A caller whose token is already stale (someone else refreshed meanwhile) gets
 * the current session with no round-trip; presenting a rotated refresh token
 * twice would otherwise revoke the whole family server-side.
 */
class TokenRefresher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val mutex = Mutex()
    private var inFlight: Deferred<RefreshOutcome>? = null

    /**
     * @param afterAccessToken the access token whose request just 401'd.
     * @return the session to retry with, or `null` when the refresh was rejected.
     * @throws BackendError.Network when the refresh could not reach the server.
     */
    suspend fun refresh(
        afterAccessToken: String,
        store: SessionStore,
        rotate: suspend (refreshToken: String) -> RefreshOutcome,
    ): Session? {
        var started = false
        val work: Deferred<RefreshOutcome> = mutex.withLock {
            val current = store.session()
            if (current != null && current.accessToken != afterAccessToken) return current
            inFlight ?: run {
                val refreshToken = store.session()?.refreshToken ?: return null
                started = true
                scope.async {
                    val outcome = rotate(refreshToken)
                    when (outcome) {
                        is RefreshOutcome.Rotated -> store.save(outcome.session)
                        RefreshOutcome.Rejected -> store.clear()
                        RefreshOutcome.Unreachable -> Unit
                    }
                    outcome
                }.also { inFlight = it }
            }
        }
        val outcome = work.await()
        if (started) mutex.withLock { if (inFlight === work) inFlight = null }
        return resolve(outcome)
    }

    private fun resolve(outcome: RefreshOutcome): Session? = when (outcome) {
        is RefreshOutcome.Rotated -> outcome.session
        RefreshOutcome.Rejected -> null
        RefreshOutcome.Unreachable -> throw BackendError.Network
    }
}

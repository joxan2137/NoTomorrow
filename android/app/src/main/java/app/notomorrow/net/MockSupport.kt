package app.notomorrow.net

import android.content.Context
import androidx.annotation.StringRes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How [MockBackendClient] reaches the string catalog. The demo client lives in
 * `net/`, which owns no Android context of its own, and the app locale is an
 * app-level concern (`AppCompatDelegate.setApplicationLocales`) — so resolution
 * is injected.
 */
fun interface MockStrings {

    fun string(@StringRes id: Int): String

    companion object {
        /** Wire this in `AppContainer` with the application context. */
        fun from(context: Context): MockStrings = MockStrings { id -> context.getString(id) }
    }
}

/**
 * Where the demo client remembers that you paired with Tomek. iOS keeps this in
 * `UserDefaults` under `nt.mock.paired`; on Android `AppPrefs` owns that key —
 * see the shared-change request in the port report.
 */
interface MockPairingStore {

    fun isPaired(): Boolean

    fun setPaired(paired: Boolean)
}

/** Process-local fallback: previews and unit tests. */
class InMemoryMockPairingStore(initial: Boolean = false) : MockPairingStore {

    private val paired = AtomicBoolean(initial)

    override fun isPaired(): Boolean = paired.get()

    override fun setPaired(paired: Boolean) {
        this.paired.set(paired)
    }
}

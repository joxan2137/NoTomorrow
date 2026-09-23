package app.notomorrow.app

import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.AppTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide state that is neither in Room nor in the design system — the port of
 * `NoTomorrow/App/AppState.swift`.
 *
 * Swift's `@Observable` + `didSet` becomes a `StateFlow` per value plus a write-through to
 * [AppPrefs] on [scope]: the flow changes immediately (so a tab tap or a language switch is felt
 * at once) and the DataStore write happens off the main thread. The keys are the iOS ones,
 * `nt.hasOnboarded` and `nt.language`, so the two ports stay legible side by side.
 *
 * [load] must run once at start-up, before the first frame that depends on [hasOnboarded];
 * [isLoaded] gates that frame so the app never flashes onboarding at a returning user.
 */
class AppState(
    private val prefs: AppPrefs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** `selectedTab` — the tab bar's selection. Not persisted, exactly as on iOS. */
    val selectedTab: MutableStateFlow<AppTab> = MutableStateFlow(AppTab.Today)

    private val _hasOnboarded = MutableStateFlow(false)

    /** `hasOnboarded` (`nt.hasOnboarded`). Onboarding writes it at the very end of `finish()`. */
    val hasOnboarded: StateFlow<Boolean> = _hasOnboarded.asStateFlow()

    private val _languageOverride = MutableStateFlow<String?>(null)

    /** `languageOverride` (`nt.language`): `"en"`, `"pl"` or `null` for "System". */
    val languageOverride: StateFlow<String?> = _languageOverride.asStateFlow()

    /**
     * `pendingRoute` — a deep link or a tapped notification, consumed by `RootScreen`. The rest
     * sheet a rest-notification tap asks for travels on `WorkoutSessionController.wantsRestSheet`.
     */
    val pendingRoute: MutableStateFlow<Route?> = MutableStateFlow(null)

    private val _isLoaded = MutableStateFlow(false)

    /** `false` until [load] has read both keys back; the splash screen stays up meanwhile. */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /**
     * The modal Settings sheet (iOS presents it from the Dashboard avatar). Hoisted here so
     * `Route.Settings` from a notification can open it from any tab; `feature/settings` observes it.
     */
    val showsSettings: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val _fuelTodayRequests = MutableStateFlow(0)

    /** Bumped by the Dashboard's Fuel row; the Fuel tab jumps to today whenever it changes. */
    val fuelTodayRequests: StateFlow<Int> = _fuelTodayRequests.asStateFlow()

    /**
     * `AppState.Route` — where a push or deep link wants to land. [wire] matches the extras
     * `push/` puts on the launch intent (`NtPushIntents.EXTRA_ROUTE`).
     */
    enum class Route(val wire: String) {
        RestTimer("restTimer"),
        ActiveWorkout("activeWorkout"),
        Bro("bro"),
        Settings("settings");

        companion object {
            fun from(raw: String?): Route? {
                val key = raw?.trim().orEmpty()
                if (key.isEmpty()) return null
                return entries.firstOrNull { it.wire.equals(key, ignoreCase = true) }
            }
        }
    }

    // MARK: - Lifecycle

    /** The `init(…)` half of the Swift class, plus the locale the override implies. */
    suspend fun load() {
        _hasOnboarded.value = runCatching { prefs.hasOnboardedOnce() }.getOrDefault(false)
        _languageOverride.value = runCatching { prefs.languageOnce() }.getOrNull()
        withContext(Dispatchers.Main) { AppLocale.applyIfNeeded(_languageOverride.value) }
        _isLoaded.value = true
    }

    // MARK: - Setters (the `didSet` half)

    fun setHasOnboarded(value: Boolean) {
        if (_hasOnboarded.value == value) return
        _hasOnboarded.value = value
        scope.launch { runCatching { prefs.setHasOnboarded(value) } }
    }

    /**
     * Writes `nt.language` and applies the locale. Call from the main thread: applying recreates
     * the Activity, which is why Android has no "relaunch" hint (see [AppLocale]).
     */
    fun setLanguageOverride(code: String?) {
        val normalized = code?.takeIf { it.isNotEmpty() }
        if (_languageOverride.value == normalized) return
        _languageOverride.value = normalized
        scope.launch { runCatching { prefs.setLanguage(normalized) } }
        AppLocale.apply(normalized)
    }

    fun select(tab: AppTab) {
        selectedTab.value = tab
    }

    /** Opens the Fuel tab on today, whatever day it was left on. */
    fun openFuelToday() {
        _fuelTodayRequests.update { it + 1 }
        select(AppTab.Fuel)
    }

    // MARK: - Routing

    /** Called by `MainActivity` when a notification or deep link launches the app. */
    fun requestRoute(route: Route?) {
        if (route != null) pendingRoute.value = route
    }

    fun requestRoute(wire: String?) = requestRoute(Route.from(wire))

    /** `RootScreen` clears the request once it has acted on it. */
    fun consumeRoute() {
        pendingRoute.value = null
    }
}

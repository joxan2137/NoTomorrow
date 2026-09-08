package app.notomorrow.di

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.notomorrow.app.AppState
import app.notomorrow.data.db.DatabaseModule
import app.notomorrow.data.db.NoTomorrowDatabase
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.prefs.SecureStore
import app.notomorrow.net.MockStrings
import app.notomorrow.push.PushRegistrar
import app.notomorrow.rest.RestTimerController
import app.notomorrow.service.AIEstimateLocalizer
import app.notomorrow.service.AIEstimateProviders
import app.notomorrow.service.AppConfig
import app.notomorrow.service.AppConfigStore
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.AuthStore
import app.notomorrow.service.BroService
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.FoodSearchService
import app.notomorrow.service.HealthService
import app.notomorrow.service.PrefsMockPairingStore
import app.notomorrow.service.RecordService
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.TargetCalculator
import app.notomorrow.service.WorkoutSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI container — the Android replacement for iOS's `@Environment` graph. One instance,
 * created in `NoTomorrowApp.onCreate`, read through [LocalAppContainer].
 *
 * No Hilt: the graph is small, entirely hand-written, and manual DI keeps build times and
 * parallel-work friction down (`docs/android-architecture.md`, "Environment / DI").
 *
 * **Everything below the preference stores is `by lazy`.** `Application.onCreate` runs on the main
 * thread and `DatabaseModule.build` opens the SQLite file eagerly (so its in-memory fallback can
 * fire); the first touch therefore happens on the start-up coroutine in `NoTomorrowApp`, not in
 * `onCreate`. Laziness is also what resolves the `AuthStore` ⇄ `AppConfig` cycle: `AppConfig` takes
 * the session store as a `() -> SessionStore`.
 *
 * [load] must run once, off the main thread, before the first authenticated request or the first
 * frame that depends on `hasOnboarded`.
 */
class AppContainer(val app: Application) {

    /** Long-lived, process-scoped work: preference write-through, start-up seeding. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MARK: - Storage

    val db: NoTomorrowDatabase by lazy { DatabaseModule.build(app) }

    val appPrefs: AppPrefs by lazy { AppPrefs(app) }

    val secureStore: SecureStore by lazy { SecureStore(app) }

    // MARK: - Session and configuration

    /** Also **is** the `SessionStore` the transport reads on every authenticated request. */
    val authStore: AuthStore by lazy { AuthStore(secureStore, scope) }

    /** `nt.mock.paired`, so the demo backend remembers the pairing across relaunches. */
    val mockPairingStore: PrefsMockPairingStore by lazy { PrefsMockPairingStore(appPrefs, scope) }

    /**
     * Owns `makeBackendClient()`, which caches one client per process and rebuilds it only when
     * `useMockBackend` flips or `backendBaseURL` changes — so the Settings "Use demo data" toggle
     * takes effect without a relaunch, exactly as on iOS.
     */
    val appConfig: AppConfig by lazy {
        AppConfig(
            prefs = AppConfigStore.from(appPrefs),
            sessionStore = { authStore },
            mockStrings = { MockStrings.from(app) },
            mockPairing = { mockPairingStore },
            scope = scope,
        )
    }

    // MARK: - Controllers

    /**
     * Always through [RestTimerController.get]: the alarm and notification receivers resolve the
     * same instance in a possibly container-less process, and two instances would open the same
     * DataStore file twice.
     */
    val restTimer: RestTimerController by lazy { RestTimerController.get(app) }

    val workoutSession: WorkoutSessionController by lazy {
        WorkoutSessionController(appPrefs, db.workoutDao())
    }

    // MARK: - Services

    /** Process-global singleton, the analogue of iOS's `BroShared.service`. */
    val broService: BroService by lazy {
        BroService(
            clientProvider = { appConfig.makeBackendClient() },
            scheduleDao = db.scheduleDao(),
            attendanceDao = db.attendanceDao(),
            headsUpDao = db.headsUpDao(),
            pairingDao = db.broPairingDao(),
        )
    }

    val healthService: HealthService by lazy { HealthService(app) }

    val exerciseLibrary: ExerciseLibrary by lazy { ExerciseLibrary(app, db.exerciseDao()) }

    val routineSeeder: RoutineSeeder by lazy { RoutineSeeder(db.routineDao(), db.exerciseDao()) }

    val recordService: RecordService by lazy { RecordService(db.workoutDao()) }

    val attendanceService: AttendanceService by lazy {
        AttendanceService(db.attendanceDao(), db.scheduleDao(), db.profileDao())
    }

    /** Stateless; exposed through the container so features never reach for the object directly. */
    val targetCalculator: TargetCalculator = TargetCalculator

    val foodSearchService: FoodSearchService by lazy { FoodSearchService() }

    /**
     * Resolves the provider **per call** from `appConfig` and the stored BYOK keys: demo backend →
     * mock, "Claude with your key" + a key → direct Anthropic, "Gemini with your key" + a key →
     * direct Google, otherwise the Fly.io backend (whose Gemini key is for whitelisted users).
     */
    val aiEstimateService: AIEstimateProviders by lazy {
        AIEstimateProviders(appConfig, authStore, AIEstimateLocalizer.from(app))
    }

    /**
     * The backend supplier is resolved per call, so the demo toggle needs no relaunch and the
     * registrar is a no-op while signed out.
     */
    val pushRegistrar: PushRegistrar by lazy {
        PushRegistrar.install(app) {
            if (authStore.session() != null) appConfig.makeBackendClient() else null
        }
    }

    // MARK: - Shell state

    val appState: AppState by lazy { AppState(appPrefs, scope) }

    // MARK: - Lifecycle

    /**
     * Seeds every mirrored store from disk. Order matters exactly once: `authStore.load()` must
     * finish before anything issues an authenticated request, and `bindMockBackend` is what makes
     * `needsSignIn` false while the demo backend is on.
     */
    suspend fun load() {
        appConfig.load()
        authStore.load()
        authStore.bindMockBackend(appConfig.useMockBackend)
        mockPairingStore.load()
        appState.load()
    }

    /**
     * The `RootView.task` equivalent. The seeder is a no-op until the 15 library ids exist, so the
     * import has to come first; both are idempotent and cheap on later launches.
     */
    suspend fun seed() {
        runCatching { exerciseLibrary.importIfNeeded() }
        runCatching { routineSeeder.seedIfNeeded() }
    }
}

/**
 * The one CompositionLocal. Provided in `MainActivity.setContent`; every feature reads its
 * dependencies through it, usually via [ntViewModel].
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided — MainActivity must wrap content in CompositionLocalProvider(LocalAppContainer provides …).")
}
